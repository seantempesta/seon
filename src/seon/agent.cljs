(ns seon.agent
  "The agent RECORD + the agent-facing functions — 'what an agent IS' (the loop
   that runs it lives in [[seon.agent.loop]], one turn in [[seon.agent.turn]]).

   The agent operates as a real REPL: bootstrap-CLJS evaluates its forms,
   results land in a per-agent home namespace (`my.agent.<id>`) as live
   values keyed by eval-id (via [[seon.eval]]), and durable records land as
   `:seon.eval` entities. The agent calls the real `seon.db/*` APIs directly.

   This namespace owns:
     - the `:seon.agent/*` schemas (id/purpose/run/terminated-at/parent/
       default-turn-limit/default-deadline-ms/schedules/ctx + the entity
       map), plus the `:seon.eval/*`, `:seon.ns/*`, `:seon.fn/*`,
       `:seon.schema/*` corpus schemas (`:seon.agent.message/*` lives in
       [[seon.agent.message]], `:seon.agent.turn/*` in [[seon.agent.turn]],
       `:seon.agent.run/*` in [[seon.agent.run]], `:seon.agent.ctx/*` in [[seon.agent.ctx]])
     - `armable-agent-ids` — the wakeable agent ids (a `:seon.db/db` map-in
       adapter over the one [[seon.derive]] leaf); state is a projection of the
       run/terminated-at primitives, never stored
     - `derive-status` — the agent fingerprint, re-exported from [[seon.derive]]
     - `create!` / `mint!` — reconcile or allocate the complete initial
       durable agent fact set
     - `message!` / `user-ref` — re-exported from [[seon.agent.message]]
     - `set-purpose!` — sugar over a one-attr transact to the agent's own
       entity. The ctx-block editing surface is [[seon.agent.ctx/install!]] /
       [[seon.agent.ctx/remove!]] (over `:seon.agent/ctx`), not here.

   Agent-id resolution: read APIs take `:seon.agent/id` and fall back to
   `(seon.db/current-agent-id)` when unset (the boot/run path wraps calls in
   `(seon.db/with-agent id …)`).

   ## State is DERIVED (the run model)

   There is no stored `:seon.agent/state`. The agent's FSM state is a pure
   projection of its primitives via [[seon.derive/derive-state]]:
     :terminated — `:seon.agent/terminated-at` present (UNWAKEABLE)
     :idle       — no OPEN run (WAKEABLE; a message opens a run → :running)
     :paused     — the open run carries `:seon.agent.run/paused-at`
     :running    — an open run, not paused (the loop is driving turns)
   A trigger (inbound message / due schedule) opens a RUN
   ([[seon.agent.run/open-run!]]); the loop drives turns until a bound fires
   or a function closes the run (see [[seon.agent.loop/run-loop!]]).

   ## Prompt assembly

   The LLM ctx is ONE recursive render of the ROOT renderable
   (`seon.agent.ctx/context-root`): `seon.agent.turn/render-prompt` calls
   `(seon.render/render :seon.render/ai ctx (seon.agent.ctx/context-root ctx))`,
   shared byte-for-byte with the debug view (`seon.agent.debug/ctx-preview`).
   The manifest-declared block set is copied into a new agent's own
   `:seon.agent/ctx` at creation; render reads that one
   complete collection priority-sorted — no merge, no separate default set.
   Each block's `:seon.render/ai` slot is a verbatim string or a fn symbol
   resolved late via `seon.eval/lookup-value`.

   The agent customizes by `seon.agent.ctx/install!` / `remove!` on its
   `:seon.agent/ctx` blocks, or by transacting a completely different symbol
   onto the agent's `:seon.render/ai` slot."
  (:require
    [clojure.string :as str]
    [seon.agent.home :as home]
    [seon.agent.message :as msg]
    [seon.agent.runtime :as runtime]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.namespaces :as ctx-namespaces]
    [seon.agent.ctx.transcript :as ctx-transcript]
    [seon.agent.ctx.warnings :as ctx-warnings]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.derive :as derive]
    [seon.error :as error]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — every shape the agent reads or writes. The agent-ns is not
;; stored on the entity — it's deterministic from the id via `home-ns`.
;; ============================================================

;; `:seon.agent/id` itself is registered in `seon.render` — the
;; FIRST-loading ns whose load-time schema references it
;; (`:seon.render/section-request` — this ns loads after seon.render via
;; the seon.agent.ctx require chain, and register!'s compilability guard
;; rejects forward references; same precedent as `:seon.ns/name` living
;; in seon.agent.ctx.render-fns).
(schema/register! :seon.agent/purpose       :string)
;; Subagent → parent (optional; the atomic spawn transaction sets it and
;; `complete` derives its delivery target through it). References the canonical
;; ref shape; never inline.
(schema/register! :seon.agent/parent        :seon.db/ref)

;; ── DERIVED-STATE primitives (the run model) ──────────────────────────────
;; There is NO stored state — the FSM state is a projection of these via
;; [[seon.derive/derive-state]]. `:seon.agent/run` points at the CURRENT
;; run (the fencing pointer + the spine of derived state — see
;; [[seon.agent.run]] / [[seon.derive]]); `terminated-at` presence ⇒
;; derived state :terminated; the default-* attrs seed a new run's two bounds
;; (`default-turn-limit` is the work bound, `default-deadline-ms` the
;; wall-clock bound); `schedules` is the self-managed cron vector
;; ([[seon.agent.schedule]]). All reference the canonical shapes; never inline.
(schema/register! :seon.agent/run                :seon.db/ref)
(schema/register! :seon.agent/terminated-at      :inst)
(schema/register! :seon.agent/default-turn-limit :int)
(schema/register! :seon.agent/default-deadline-ms :int)
(schema/register! :seon.agent/schedules
                  [:vector {:seon.db/component true} :seon.db/ref])
;; ============================================================
;; Aliases — the context machinery lives in `seon.agent.ctx`. These keep (a) the
;; agent-TAUGHT read surface (`seon.agent/messages` …) resolving via
;; seon.eval/lookup-value, and (b) stored `:seon.render/ai` slots pointing at
;; 'seon.agent/assemble-context working. An alias captures the fn value at
;; load time (pre-instrumentation) — call `seon.agent.ctx/*` directly when you
;; want the validated entry point.
;; ============================================================

(def messages ctx/messages)
(def current-turn ctx/current-turn)
(def evals ctx/evals)
(def current-ns ctx/current-ns)
(def ctx-entities ctx/ctx-entities)
(def host-timezone ctx/host-timezone)
(def truncate-edn ctx/truncate-edn)
(def message-label ctx/message-label)
(def eval-render-cap ctx/eval-render-cap)
(def cap-result ctx/cap-result)
(def cap-result-body ctx/cap-result-body)
(def namespaces-block ctx-namespaces/namespaces-block)
(def render-namespace ctx/render-namespace)
(def warnings-block ctx-warnings/warnings-block)
(def transcript-block ctx-transcript/transcript-block)
(def context-root ctx/context-root)

(schema/register!
  :seon.eval/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! :seon.eval/at          :inst)
;; Wall-clock duration of the eval in milliseconds. Populated by
;; seon.eval/eval-batch! per form. Source of truth for slow-eval warnings
;; without walking evals or computing :at deltas.
(schema/register! :seon.eval/duration-ms :int)
(schema/register! :seon.eval/narration   :string)
(schema/register! :seon.eval/source      :string)
(schema/register! :seon.eval/ok?         :boolean)
(schema/register! :seon.eval/result-edn  :string)
;; println/prn output captured during the eval span (*print-fn* otherwise
;; routes to the pod's stdout, invisible to the agent; a REPL shows print
;; output next to the result). Written by record-eval! only when something
;; printed; absent = no output.
(schema/register! :seon.eval/output      :string)
(schema/register! :seon.eval/error       :string)
;; Structured instrumentation envelope alongside the rendered error string.
;; Populated by record-eval! when the failure carries an instrumentation
;; envelope (i.e. (:seon.error/data error) satisfies
;; seon.error.instrument/instrument-error?). Programmatic readers branch on
;; this; absent for non-instrumentation failures (timeouts, generic throws).
;; Stored as :string (pr-str at write, read-string at read) because the
;; seon.db Malli→datahike bridge has no :db.type/map entry.
(schema/register! :seon.eval/error-data  :string)
;; The namespace the eval ended in. Written by eval-batch!'s per-form reduce
;; from the (:ns raw-result) of cljs.js/eval-str. For failed forms (read or
;; eval), carries the unchanged current-ns accumulator — the last-known-good
;; ns the form WOULD have run in. Always populated; never nil. Cross-batch
;; derivation of "the agent's current ns" reads this attribute on the latest
;; successful eval.
(schema/register! :seon.eval/ns          :keyword)
;; The agent whose scope produced the eval — a DENORMALIZED direct ref to the
;; owning agent (the same agent reachable via turn → run → agent, surfaced here
;; so an eval row can be found in ONE hop). Written by record-eval! from
;; `(seon.db/current-agent-id)` when the eval runs inside a `with-agent` scope
;; (every agent turn does); ABSENT for evals with no agent scope (boot index,
;; web UI REPL) — optional, never nil. A ref so `[:seon.agent/id id]` value
;; lookup-refs resolve and `/clear`'s `[?e :seon.eval/agent [:seon.agent/id …]]`
;; query matches by eid.
(schema/register! :seon.eval/agent       :seon.db/ref)

;; The agent's COMPLETE context block set — a component vector of
;; :seon.agent.ctx/block maps (see seon.agent.ctx). SEED-COPIED from the
;; default set at creation; render reads this one collection priority-sorted
;; (no merge over a separate default set). The one slot attr is
;; :seon.render/ai. (Turns are NOT owned here — a turn points UP to its run;
;; runs point UP to the agent via :seon.agent.run/agent.)
(schema/register! :seon.agent/ctx    [:vector {:seon.db/component true} :seon.db/ref])

;; ============================================================
;; Program graph. :seon.ns owns the namespace source; :seon.fn /
;; :seon.schema reference their ns via child→parent plain refs (NOT
;; component — a fn does not own its ns). Identity attrs upsert on redefine;
;; history retains prior :source values. Core fns/schemas/nses seed from the
;; indexed codebase at boot; agent-defined entities populate via
;; detect-and-tee in eval-batch!.
;;
;; :seon.ns/name + :seon.ns/source live in seon.agent.ctx (its render-namespace
;; schemas reference them and seon.agent.ctx loads first).
;; ============================================================

(schema/register! :seon.fn/sym        [:string {:seon.db/identity true}])
(schema/register! :seon.fn/ns         :seon.db/ref)
(schema/register! :seon.fn/source     :string)
;; Projections from the analyzer's var-map. Re-derived on every
;; detect-and-tee + on bulk-load resume.
(schema/register! :seon.fn/fn-var?    :boolean)
(schema/register! :seon.fn/arglists   :string)
(schema/register! :seon.fn/doc        :string)
(schema/register! :seon.fn/private?   :boolean)
;; Positive capability declaration. Presence true means the function may
;; enter agent tool context; absence keeps it only in the program graph.
(schema/register! :seon.fn/agent-facing? :boolean)
;; The fn's contract: `(pr-str (m/form <the fn's :malli/schema>))`.
;; PRESENT ⇒ specced (the exact contract is in the corpus); ABSENT ⇒
;; unspecced.
(schema/register! :seon.fn/spec       :string)
;; Set when `:malli/schema` metadata is present but the value fails to
;; parse via `malli.core/schema`. Orthogonal to `:seon.fn/spec` — when this
;; is set, the schema is present but unparseable, so we omit `:seon.fn/spec`
;; and will not instrument the fn.
(schema/register! :seon.fn/schema-error :string)
(schema/register! :seon.fn/created-at :inst)

(schema/register! :seon.schema/key        [:keyword {:seon.db/identity true}])
(schema/register! :seon.schema/ns         :seon.db/ref)
(schema/register! :seon.schema/form     :string)
(schema/register! :seon.schema/created-at :inst)

;; ============================================================
;; Entity-kind `:map` schemas. One per renderable kind, each DECLARED
;; with `{:seon.db/entity true}` (entity-kind-ness is declared, never
;; inferred — request/response envelopes stay unmarked). The
;; `:seon.render/ai` / `:seon.render/html` symbols live on the schema's
;; own properties — for a declared entity, `seon.schema/register!`
;; derives `:seon.entity/id-attr` from whichever entry carries
;; `{:seon.db/identity true}`. That id-attr is what the renderer
;; enumerates in AEVT to find all instances of the kind; the render
;; symbols are looked up via `(m/properties (m/schema :seon.eval))`
;; at render time (no per-row stamping).
;;
;; These are intentionally MINIMAL — they exist so the renderer's
;; discovery loop has a schema to consult.
;; ============================================================

;; Required attrs reflect what every writer of the kind populates
;; unconditionally — derived from the write sites:
;;   :seon.eval   — `record-eval!` (eval.cljs)
;;   :seon.agent.message — `message!` (the single write entry point,
;;                         seon.agent.message — its entity-kind :map
;;                         schema lives there too)
;;   :seon.fn     — `build-tee-entities` (eval.cljs)
;;   :seon.schema — `build-tee-entities` (eval.cljs)
;;   :seon.ns     — `build-tee-entities` (eval.cljs)
;;
;; Anything written conditionally (errors only on failure, result only
;; on success, projections that may be nil) is `{:optional true}` per
;; CLAUDE.md "Optional = absent" rule. Never `[:maybe X]`.
;;
;; The runtime schema projection derives each declared shape's required attrs
;; from entries without `{:optional true}`. `seon.render` matches that catalog
;; directly; no derived schema decomposition is persisted.

(schema/register! :seon.eval
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.eval/render-ai
         :seon.render/html 'seon.handlers.eval/render-html}
   [:seon.eval/id          :seon.eval/id]
   [:seon.eval/source      :seon.eval/source]
   [:seon.eval/ok?         :seon.eval/ok?]
   [:seon.eval/at          :seon.eval/at]
   [:seon.eval/agent       {:optional true} :seon.eval/agent]
   [:seon.eval/duration-ms {:optional true} :seon.eval/duration-ms]
   [:seon.eval/narration   {:optional true} :seon.eval/narration]
   [:seon.eval/ns          {:optional true} :seon.eval/ns]
   [:seon.eval/result-edn  {:optional true} :seon.eval/result-edn]
   [:seon.eval/output      {:optional true} :seon.eval/output]
   [:seon.eval/error       {:optional true} :seon.eval/error]
   [:seon.eval/error-data  {:optional true} :seon.eval/error-data]])

(schema/register! :seon.fn
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.fn/render-ai
         :seon.render/html 'seon.handlers.fn/render-html}
   [:seon.fn/sym    :seon.fn/sym]
   [:seon.fn/ns     :seon.fn/ns]
   [:seon.fn/source :seon.fn/source]
   ;; analyzer projections — the tee stamps all four on every row it
   ;; mints (strict persistence: only literal `(defn …)` sources get a
   ;; :seon.fn row). Optional because boot-indexed and legacy rows may
   ;; omit them.
   [:seon.fn/fn-var?    {:optional true} :seon.fn/fn-var?]
   [:seon.fn/arglists   {:optional true} :seon.fn/arglists]
   [:seon.fn/doc        {:optional true} :seon.fn/doc]
   [:seon.fn/private?   {:optional true} :seon.fn/private?]
   [:seon.fn/agent-facing? {:optional true} :seon.fn/agent-facing?]
   [:seon.fn/spec       {:optional true} :seon.fn/spec]
   [:seon.fn/schema-error {:optional true} :seon.fn/schema-error]
   ;; The declared read-set (qualified keyword literals in the source,
   ;; extracted from the already-read form at tee time — C28). ABSENT =
   ;; no literals OR a pre-structural row (readers regex-fallback).
   ;; Registered in seon.eval (the tee that writes it).
   [:seon.fn/read-attrs {:optional true} :seon.fn/read-attrs]
   [:seon.fn/created-at {:optional true} :seon.fn/created-at]])

(schema/register! :seon.schema
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.schema/render-ai
         :seon.render/html 'seon.handlers.schema/render-html}
   [:seon.schema/key    :seon.schema/key]
   [:seon.schema/form :seon.schema/form]
   [:seon.schema/ns         {:optional true} :seon.schema/ns]
   [:seon.schema/created-at {:optional true} :seon.schema/created-at]])

(schema/register! :seon.ns
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.ns/render-ai
         :seon.render/html 'seon.handlers.ns/render-html}
   [:seon.ns/name   :seon.ns/name]
   [:seon.ns/source :seon.ns/source]])

;; :seon.agent — the agent's OWN entity-kind. The `:seon.render/html`
;; property makes `seon.render.default/view` the default surface renderer via
;; the same kind-lookup every other kind uses; an agent OVERRIDES by
;; transacting `:seon.render/html '<its-own-fn-sym>` onto its own entity
;; (per-entity override wins in `seon.render/entity-html-sym`). No
;; `:seon.render/ai` property in the props — the agent entity must NOT enter
;; the chronological ai window. The ONLY required attr is `id` (the one thing
;; `create!` always writes); state is DERIVED (no stored enum), and every
;; other attr arrives lazily. `sections` keeps its own register! (still
;; transactable/queryable) but stays out of the record shape's required set.
(schema/register! :seon.agent
  [:map {:seon.db/entity   true
         :seon.render/html 'seon.render.default/view}
   [:seon.agent/id      :seon.agent/id]
   [:seon.agent/purpose            {:optional true} :seon.agent/purpose]
   [:seon.agent/parent             {:optional true} :seon.agent/parent]
   ;; derived-state primitives + run bounds + cron
   [:seon.agent/run                {:optional true} :seon.agent/run]
   [:seon.agent/terminated-at      {:optional true} :seon.agent/terminated-at]
   [:seon.agent/default-turn-limit {:optional true} :seon.agent/default-turn-limit]
   [:seon.agent/default-deadline-ms {:optional true} :seon.agent/default-deadline-ms]
   [:seon.agent/schedules          {:optional true} :seon.agent/schedules]
   [:seon.agent/ctx                {:optional true} :seon.agent/ctx]
   [:seon.agent.runtime/wake?      {:optional true} :seon.agent.runtime/wake?]
   [:seon.eval/home-requires       {:optional true} :seon.eval/home-requires]
   [:seon.render/ai   {:optional true} :seon.render/ai]
   [:seon.render/html {:optional true} :seon.render/html]])

;; ============================================================
;; DERIVED state — there is no stored `:seon.agent/state`. The FSM state is a
;; projection of the agent's primitives (terminated-at / open run / paused-at)
;; via [[seon.derive/derive-state]] — the ONE derivation leaf. `armable-agent-ids`
;; (below) returns the wakeable agent ids, a FILTER over that one rule;
;; `derive-status` (re-exported below) is the full fingerprint. The loop + wake
;; gate now call [[seon.derive/derive-state]] directly with the db value they
;; hold.
;; ============================================================

(schema/register! ::armable-agent-ids-request [:map [:seon.db/db {:optional true} :seon.db/db-val]])
(schema/register! ::armable-agent-ids-response [:vector :seon.agent/id])
(schema/register! ::resumable-agent-ids-request
  [:map [:seon.db/db {:optional true} :seon.db/db-val]])
(schema/register! ::resumable-agent-ids-response [:vector :seon.agent/id])
(schema/register! ::initial-created? :boolean)
(schema/register! ::ensure-initial-agent-request [:map])
(schema/register!
  ::ensure-initial-agent-response
  [:or
   [:map
    [::initial-created? ::initial-created?]
    [:seon.agent/id {:optional true} :seon.agent/id]]
   :seon.db/transact-response])

(defn armable-agent-ids
  "Born agent ids whose DERIVED state is `:idle` — the ones a trigger can WAKE.

   `:idle` = not `:terminated` AND with no OPEN run. Open a fresh run for one;

   a running/paused agent is mid-run, a terminated agent is dead. The boot
   resume pass + the wake re-arm both read this. Map-in `:seon.db/db` adapter
   over [[seon.derive/armable-agent-ids]] (the one filter-over-derive-state
   rule); `:seon.db/db` optional (defaults to `*conn*`'s db)."
  {:malli/schema [:=> [:cat ::armable-agent-ids-request] ::armable-agent-ids-response]}
  [{:seon.db/keys [db]}]
  (derive/armable-agent-ids (or db @db/*conn*)))

(defn resumable-agent-ids
  "Born agent ids this process must host: each without a termination fact.

   Unlike [[armable-agent-ids]], this includes running and paused agents; those
   states still require fresh process handles after a cold start or reload.
   An identity-only provenance-genesis target is not born and is omitted."
  {:malli/schema
   [:=> [:cat ::resumable-agent-ids-request]
    ::resumable-agent-ids-response]}
  [{:seon.db/keys [db]}]
  (derive/resumable-agent-ids (or db @db/*conn*)))

(defn- ordinary-agent-ever-born?
  "True when database history proves a non-root agent was ever born.

   History, rather than the current view, is load-bearing: deleting or
   terminating the initial agent must not make a later restart look like a
   first boot and silently manufacture a replacement."
  [database]
  (boolean
    (db/query
      {:seon.db/db (db/history database)
       :seon.db/query
       '[:find ?agent .
         :where
         [?agent :seon.agent/id ?id _ true]
         [?agent :seon.eval/home-requires _ _ true]
         [(not= ?id "root")]]})))

;; ============================================================
;; Derived status — the agent FINGERPRINT. The whole derived state in one map.
;; It is a pure DERIVED READ owned by [[seon.derive/derive-status]] (the one
;; derivation leaf); re-exported here so `seon.agent/derive-status` keeps
;; resolving for the agent-facing surface + the run/lifecycle tests. State is
;; DERIVED via [[seon.derive/derive-state]] over the primitives — there is NO
;; stored state.
;; ============================================================

(def derive-status derive/derive-status)

;; ============================================================
;; Agent creation. Reconcile known ids; allocate genuinely new ids atomically.
;; ============================================================

(schema/register! ::create-request
  [:map
   [:seon.agent/id                  :seon.agent/id]
   [:seon.agent/purpose             {:optional true} :any]
   [:seon.agent/default-turn-limit  {:optional true} :any]])

;; Success = `{:seon.agent/id id}`; a FAILED transact returns the db error
;; envelope as-is (errors are values).
(schema/register! ::create-response
  [:or
   [:map [:seon.agent/id :seon.agent/id]]
   :seon.db/transact-response])

(defn- unavailable-db-response
  []
  {:seon.db/ok? false
   :seon.db/error (:seon/error (admission/unavailable))})

(defn- initial-agent-tx
  "Complete creation facts for one new agent and its home namespace.

   Pure data. The caller commits this vector once, so no observer can see an
   identity without its configured context, scalar dials, and structural home
   namespace. `existing` is used only to finish the reserved root's bare
   provenance-genesis stub: facts already present are preserved, never reset."
  [id purpose default-turn-limit parent existing]
  (let [context       (ctx/initial-agent-context {:seon.agent/id id})
        home-requires (or (:seon.eval/home-requires context)
                          home/home-ns-require-specs)
        missing-context
        (reduce-kv (fn [m k v]
                     (if (contains? existing k) m (assoc m k v)))
                   {}
                   context)
        agent-row     (cond-> (assoc missing-context :seon.agent/id id)
                        (and (not (contains? existing :seon.agent/purpose))
                             (string? purpose) (not (str/blank? purpose)))
                        (assoc :seon.agent/purpose purpose)
                        (and (not (contains? existing
                                                     :seon.agent/default-turn-limit))
                             (some? default-turn-limit))
                        (assoc :seon.agent/default-turn-limit default-turn-limit)
                        (and (not (contains? existing :seon.agent/parent)) parent)
                        (assoc :seon.agent/parent parent))]
    [agent-row
     (home/initial-ns-entity
       {:seon.agent/id id :seon.eval/home-requires home-requires})]))

(defn ^:async create!
  "Reconcile a known agent entity by its durable id.

   State is DERIVED: a fresh agent with no open run is `:idle`. Idempotent:
   re-calling with the same born id performs no transaction — a resumed agent keeps
   its own purpose, dials, home declaration, and edited/removed context. The one
   exception is the reserved root lookup target installed by provenance genesis:
   if it has no durable home declaration yet, this function completes that bare
   stub without replacing any fact already present. A new entity
   gets `:seon.agent/purpose` ONLY when the human stated one; otherwise the
   attr stays ABSENT (optional = absent) until the agent derives a purpose and
   transacts it. Purpose is ENTITY DATA, never agent-directed instruction text
   — the welcome canvas shows it verbatim to the customer.
   `:seon.agent/default-turn-limit`, when given, is part of that initial entity.
   Identity, the full configured context component tree, scalar dials, purpose,
   parent, and the structural home-namespace entity commit in ONE transaction.

   Returns `{:seon.agent/id id}` on success; on a FAILED transact the
   db error envelope (`{:seon.db/ok? false :seon.db/error …}`) comes
   back as-is — errors are values, the same contract as
   `seon.agent.message/message!`. A failed create means NO agent
   entity; callers must branch instead of chasing a ghost."
  {:malli/schema [:=> [:cat ::create-request] ::create-response]}
  [{:seon.agent/keys [id purpose default-turn-limit]}]
  (let [entity (db/entity {:seon.db/ref [:seon.agent/id id]})
        home-source (:seon.ns/source
                      (db/entity {:seon.db/ref
                                  [:seon.ns/name (keyword (str (home/home-ns id)))]}))
        complete? (and entity
                       (or (not= "root" id) (string? home-source)))]
    (if complete?
      {:seon.agent/id id}
      (let [res (await (db/transact!
                         {:seon.db/tx-data
                          (initial-agent-tx id purpose default-turn-limit nil
                                            entity)}))]
      (if (false? (:seon.db/ok? res))
        (do (js/console.error
              (str "seon.agent/create! transact FAILED for " id ": "
                   (:seon.error/message (:seon.db/error res))))
            res)
          {:seon.agent/id id})))))

(schema/register!
  ::mint-request
  [:map
   [:seon.agent/purpose            {:optional true} :seon.agent/purpose]
   [:seon.agent/default-turn-limit {:optional true}
    :seon.agent/default-turn-limit]
   [:seon.agent/parent             {:optional true} :seon.db/ref]])

(defn ^:async mint!
  "Atomically allocate and create one genuinely new agent identity.

   The registered `:seon.agent/id` policy selects the readable-word package;
   the sole writer proves freshness before committing the complete initial
   agent and home-namespace facts. Known-id reconciliation remains [[create!]] and is intentionally
   separate."
  {:malli/schema [:=> [:cat ::mint-request] ::create-response]}
  [{:seon.agent/keys [purpose default-turn-limit parent]}]
  (let [env
        (await
          (db.id/allocate!
            {::db.id/allocations
             [{::db.id/key :seon.agent/id
               ::db.id/identity-attr :seon.agent/id}]
             ::db.id/transaction-builder
             (fn [ids]
               (let [id (get ids :seon.agent/id)]
                 {:seon.db/tx-data
                  (initial-agent-tx id purpose default-turn-limit parent nil)}))
             :seon.db/conn db/*conn*}))
        id (get-in env [::db.id/ids :seon.agent/id])]
    (if (false? (:seon.db/ok? env))
      (do (js/console.error
            (str "seon.agent/mint! transact FAILED: "
                 (:seon.error/message (:seon.db/error env))))
          env)
      {:seon.agent/id id})))

(defn ^:async ensure-initial-agent!
  "Create the cluster's one initial ordinary agent only on a true first boot.

   A non-root agent's historical birth fact permanently satisfies this
   transition, even if that agent is later terminated or retracted. The new
   agent is allocated through [[mint!]] and parented by root; this function
   adds no second creation path. Root must already exist as a lookup target.

   Returns `::initial-created?` and, when created, the new agent id. Database
   failures remain ordinary `seon.db/transact!` error envelopes."
  {:malli/schema
   [:=> [:cat ::ensure-initial-agent-request]
    ::ensure-initial-agent-response]}
  [_]
  (if (ordinary-agent-ever-born? @db/*conn*)
    {::initial-created? false}
    (let [result (await (mint! {:seon.agent/parent [:seon.agent/id "root"]}))]
      (if (false? (:seon.db/ok? result))
        result
        (assoc result ::initial-created? true)))))

;; ============================================================
;; Spawn depth (multi-agent-context Piece 2) — the DEPTH-CAP backstop. The soft
;; gate (spawn functions only in root's home-requires) keeps ordinary agents from
;; REACHING start!, but a full-qualified `(seon.agent/start! …)` slips past it;
;; this is the hard, computed structural rule that refuses it. `spawn-depth`
;; walks the `:seon.agent/parent` chain to a number; `start!` refuses a caller
;; already AT the config cap (default 1 — root spawns, a subagent does not).
;; No name list — a config-dialed number; raise the dial to deepen the tree.
;; ============================================================

(defn spawn-depth
  "Depth of `agent-id` in the spawn tree over `db` — root/parentless = 0.

   Walks `:seon.agent/parent` refs (child = parent + 1) with a visited-set
   cycle guard: a cycle is a `:core`-fault-worthy invariant break (recorded via
   `seon.error/record!`, never thrown — the fn returns the depth walked so
   far). Pure read over the passed db value."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db-val]
                             [:seon.agent/id :seon.agent/id]]
                  :int]}
  [db agent-id]
  (loop [id agent-id depth 0 seen #{}]
    (if (contains? seen id)
      (do (error/record!
            {:seon.error/raw
             (js/Error. (str "spawn-depth: :seon.agent/parent cycle detected at "
                             (pr-str id) " — the spawn tree must be acyclic"))
             :seon.error/fault :core})
          depth)
      (let [parent (:seon.agent/id
                     (:seon.agent/parent
                       (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]})))]
        (if (nil? parent)
          depth
          (recur parent (inc depth) (conj seen id)))))))

;; ============================================================
;; start! — the spawn function. Alias of create! that ALSO writes the caller as
;; the new agent's `:seon.agent/parent`. The base case of the spawn recursion
;; is the orchestrator-root ("root", parentless); every other agent is spawned
;; by some parent via this function.
;; ============================================================

;; NO `:seon.agent/id` slot: under the ONE required-key convention a
;; DECLARED-optional `:seon.agent/id` is resolved to the CALLING agent at
;; the eval boundary ("me") — but here the slot meant the CHILD, so every
;; agent-scoped spawn silently self-upserted instead of minting (live-caught
;; 2026-07-02). A child id is always minted; a chosen id goes through
;; `create!` (which REQUIRES the id, so nothing resolves into it).
(schema/register! ::start-request
  [:map
   [:seon.agent/purpose             {:optional true} :any]
   [:seon.agent/default-turn-limit  {:optional true} :any]])
(schema/register! ::start-response
  [:or ::create-response :seon.agent.runtime/resume-response])

(defn ^:async ^:private spawn-child!
  "The atomic mint→resume sequence for [[start!]], extracted so the depth-cap
   refusal short-circuits before any entity is minted."
  [purpose default-turn-limit parent-id]
  (let [res (await
              (mint!
                (cond-> {}
                  (some? purpose)
                  (assoc :seon.agent/purpose purpose)
                  (some? default-turn-limit)
                  (assoc :seon.agent/default-turn-limit default-turn-limit)
                  parent-id
                  (assoc :seon.agent/parent [:seon.agent/id parent-id]))))
        child-id (:seon.agent/id res)]
    (if (false? (:seon.db/ok? res))
      res
      (let [resumed (await (runtime/resume! {:seon.agent/id child-id}))]
        (if (:seon.agent.runtime/resumed? resumed)
          {:seon.agent/id child-id}
          resumed)))))

(defn ^:async start!
  "Spawn a child agent — the capability-gated spawn lifecycle function.

   The spawn counterpart of `seon.agent.lifecycle/terminate`. Unlike
   `create!`, it ALWAYS mints a fresh readable three-segment id and writes
   `:seon.agent/parent` = the CALLING agent (read from the ALS scope via
   `db/current-agent-id`) in the same transaction. The child is IDLE: it does
   no work until it receives a message (which opens its run #1).

   The minted child's complete process runtime is resumed before this returns,
   so a message the parent sends RIGHT AFTER spawn actually wakes the child
   (wake observation is reactive-only: a message sent before listener install
   never wakes it later, so resume must precede any inbound).

   RESOLVES to `{:seon.agent/id child-id}` — that id is the one you message to
   reach the child. On a failed transact the db error envelope comes back as-is
   (errors are values). Called outside an agent scope (no caller) → the child
   is created PARENTLESS (a host-initiated create), matching `create!`.

   ASYNC — read the id back, never inline it. `start!` is `^:async`: evaled
   ALONE its returned id is auto-awaited and you SEE the real
   `{:seon.agent/id \"…\"}`, but `(:seon.agent/id (start! …))` IN THE SAME FORM
   is `nil` (the Promise hasn't resolved — same re-reference rule as
   `result/<id>`). So NEVER `(let [c (start! …)] (message/agent (:seon.agent/id c) …))`
   — it spawns an ORPHAN and messages nil. Two safe paths:
     1. ONE COMBINATOR (preferred): `(delegate! {:seon.agent/purpose \"…\"
        :seon.agent.message/content \"<the task>\"})` spawns AND hands the
        child its task in one call (awaits internally; returns the real id).
     2. TWO FORMS: eval `(start! {…})` alone, COPY the rendered literal id,
        then `(message/agent \"<that-id>\" \"<the task>\")` in the NEXT form.
   Never invent/guess a child id — read it back."
  {:malli/schema [:=> [:cat ::start-request] ::start-response]}
  [{:seon.agent/keys [purpose default-turn-limit]}]
  (if-not (admission/available?)
    (unavailable-db-response)
    (let [parent-id    (db/current-agent-id)
          cap          (config/spawn-depth-cap)
          caller-depth (when parent-id (spawn-depth @db/*conn* parent-id))]
      (if (and caller-depth (>= caller-depth cap))
      ;; DEPTH-CAP BACKSTOP (Piece 2): the caller is already at/over the cap —
      ;; refuse as data (never a throw), mint no child. A subagent may not spawn
      ;; subagents; it does the work itself or reports back to its parent.
      {:seon.db/ok? false
       :seon.db/error
       {:seon.error/message
        (str "start!: refused — you (" (pr-str parent-id) ") are at spawn "
             "depth " caller-depth " (cap " cap "); subagents may not spawn "
             "subagents. Do the work yourself, or report back to your parent "
             "and let it delegate.")}}
        (spawn-child! purpose default-turn-limit parent-id)))))

;; ============================================================
;; delegate! — the one-form spawn→message combinator. `start!` is `^:async`,
;; so the broken `(let [c (start! …)] (message/agent (:seon.agent/id c) …))`
;; recipe reads `nil` (the Promise hasn't resolved). delegate! awaits start!
;; INTERNALLY, so the child id is REAL, then messages the child its task —
;; the ergonomic path agents reach for when delegating a task to a worker.
;; ============================================================

;; NO `:seon.agent/id` slot — same reason as [[::start-request]]: the
;; declared-optional key resolves to the CALLER, and the child is never you.
(schema/register! ::delegate-request
  [:map
   [:seon.agent.message/content     :string]
   [:seon.agent/purpose             {:optional true} :any]
   [:seon.agent/default-turn-limit  {:optional true} :any]])
(schema/register! ::delegate-response
  [:or ::create-response :seon.agent.runtime/resume-response])

(defn ^:async delegate!
  "Spawn a child AND hand it its task in ONE call.

   The ergonomic spawn→message combinator. Because `start!` is `^:async`, the inline
   `(let [c (start! …)] (message/agent (:seon.agent/id c) …))` recipe reads a
   `nil` id (the Promise hasn't resolved) and spawns an ORPHAN. delegate!
   awaits `start!` internally so the child id is REAL, then sends the child
   `:seon.agent.message/content` FROM you — the child is armed before the
   message lands, so it wakes on your task.

     (delegate! {:seon.agent/purpose \"research DuckDB for embedded analytics\"
                 :seon.agent.message/content
                 \"Research DuckDB for an embedded analytics app. Store findings
                  as my.kb.* data, then (complete \\\"<pointer>\\\") to report back.\"})

   `:seon.agent/purpose` is the child's stated reason-for-being (shown to your
   human verbatim); `:seon.agent.message/content` is the actual task you hand
   it. The child id is always minted (spawn a chosen id via `create!`);
   `:seon.agent/default-turn-limit` (optional) seeds the child's work
   bound. RESOLVES to `{:seon.agent/id child-id}` on success — the id you
   address for any follow-up. On a failed SPAWN the start! error envelope comes
   back as-is; on a spawn-ok-but-message-failed the message error envelope plus
   `:seon.agent/id child-id` (the child exists — retry the message). Errors are
   values; branch on `:seon.db/ok?`."
  {:malli/schema [:=> [:cat ::delegate-request] ::delegate-response]}
  [{:seon.agent/keys [purpose default-turn-limit]
    content :seon.agent.message/content}]
  (if-not (admission/available?)
    (unavailable-db-response)
    (let [spawn-args (cond-> {}
                       (some? purpose)            (assoc :seon.agent/purpose purpose)
                       (some? default-turn-limit) (assoc :seon.agent/default-turn-limit default-turn-limit))
          res        (await (start! spawn-args))]
      (if (or (false? (:seon.db/ok? res))
              (false? (:seon.agent.runtime/resumed? res)))
        res
        (let [child-id (:seon.agent/id res)
              menv     (await (msg/agent child-id content))]
          (if (false? (:seon.db/ok? menv))
            (do (js/console.error
                  (str "seon.agent/delegate! spawned " child-id
                       " but the task message FAILED: "
                       (:seon.error/message (:seon.db/error menv))))
                (assoc menv :seon.agent/id child-id))
            {:seon.agent/id child-id}))))))

;; ============================================================
;; Process lifecycle. Durable birth is above; these delegate to the one
;; process-local runtime owner.
;; ============================================================

(defn ^:async resume!
  "Reconstruct an existing nonterminated agent's process-local runtime."
  {:malli/schema
   [:=> [:cat :seon.agent.runtime/resume-request]
    :seon.agent.runtime/resume-response]}
  [request]
  (if-not (admission/available?)
    (let [id (:seon.agent/id request)]
      {:seon.agent/id id
       :seon.agent.runtime/resumed? false
       :seon.agent.runtime/error
       "resume!: runtime program generation is unavailable"
       :seon/error (:seon/error (admission/unavailable))})
    (await (runtime/resume! request))))

(defn unhost!
  "Remove an agent's listener, loop input, and runtime advertisement."
  {:malli/schema
   [:=> [:cat :seon.agent.runtime/unhost-request]
    :seon.agent.runtime/unhost-response]}
  [request]
  (runtime/unhost! request))

;; ============================================================
;; message! lives in [[seon.agent.message]] (the keyword namespace matches
;; the code namespace). Re-exported here so `seon.agent/message!` resolves;
;; the agent-facing messaging functions are `seon.agent.message/user` + `/agent`
;; via the `message/` alias. Same caveat as the ctx aliases above — a def
;; alias captures the fn value at load time (pre-instrumentation); call
;; `seon.agent.message/*` directly for the validated entry point.
;; ============================================================

(def message! msg/message!)
(def user-ref msg/user-ref)

;; ============================================================
;; Lifecycle functions — wait / complete / pause / resume / terminate — live in
;; [[seon.agent.lifecycle]] (a lean, whitelisted teaching ns). They are the
;; agent-facing run-lifecycle functions; each MUTATES the agent's RUN (close /
;; pause / set terminated-at), and the derived state follows. The agent home
;; ns `:refer`s them directly.
;; ============================================================

;; ============================================================
;; The agent's ctx-LAYOUT surface is `seon.agent.ctx/install!` /
;; `seon.agent.ctx/remove!` — the scope-aware override + seed functions over the
;; agent's own `:seon.agent/ctx` block set. The block fns + the render
;; pipeline live in seon.agent.ctx (read API re-exported above).
;; ============================================================

;; ============================================================
;; Self-context functions — the validated path onto YOUR OWN entity. Errors are
;; values; default scope = the calling agent; explicit :seon.agent/id allowed
;; (a human or another agent can configure an agent — it is all just
;; transacts; the function is the validated path).
;; ============================================================

;; Shared response shapes for set-purpose! (errors are values).
(schema/register! ::ok?   :boolean)
(schema/register! ::error :string)

(schema/register! ::section-response
  [:or
   [:map
    [::ok?          [:= true]]
    [:seon.agent.ctx/name :seon.agent.ctx/name]]
   [:map
    [::ok?   [:= false]]
    [::error ::error]]])

(defn ^:async set-purpose!
  "Pin or update why you exist.

   Sugar over a one-attr transact to
   your own entity (`:seon.agent/purpose`, rendered every turn in your
   entity section). Equivalent to the lookup-ref transact the creation
   tutorial demonstrates."
  {:malli/schema [:=> [:cat [:map
                             [:seon.render/ai :string]
                             [:seon.agent/id {:optional true} :seon.agent/id]]]
                  ::section-response]}
  [{text :seon.render/ai id :seon.agent/id}]
  (let [id (or id (db/current-agent-id))]
    (if (nil? id)
      {::ok? false
       ::error (str "set-purpose!: no agent in scope — pass "
                    ":seon.agent/id or call inside (seon.db/with-agent id …).")}
      (let [res (await (db/transact!
                         {:seon.db/tx-data
                          [{:seon.agent/id      id
                            :seon.agent/purpose text}]}))]
        (if (false? (:seon.db/ok? res))
          {::ok? false
           ::error (str "set-purpose! transact failed: "
                        (:seon.error/message (:seon.db/error res)))}
          {::ok? true :seon.agent.ctx/name :purpose})))))
