(ns seon.agent
  "The agent RECORD + the agent-facing verbs — 'what an agent IS' (the loop
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
     - `armable-agent-ids` — the wakeable roster (a `:seon.db/db` map-in
       adapter over the one [[seon.derive]] leaf); state is a projection of the
       run/terminated-at primitives, never stored
     - `derive-status` — the agent fingerprint, re-exported from [[seon.derive]]
     - `inbound-msg-datom?` — the wake gate ([[seon.agent.loop]]'s trigger
       and the transcript head-render both reuse it)
     - `create!` / `boot!` — allocate the agent entity (boot! does NOT arm
       the wake trigger — that's the client boot path)
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
   or a verb closes the run (see [[seon.agent.loop/run-loop!]]).

   ## Prompt assembly

   The LLM ctx is ONE recursive render of the ROOT renderable
   (`seon.agent.ctx/context-root`): `seon.agent.turn/render-prompt` calls
   `(seon.render/render :seon.render/ai ctx (seon.agent.ctx/context-root ctx))`,
   shared byte-for-byte with the inspector (`seon.agent.inspect/ctx-preview`).
   The default block set (`seon.config/default-ctx-blocks`) is SEED-COPIED
   into a new agent's own `:seon.agent/ctx` at creation; render reads that one
   complete collection priority-sorted — no merge, no separate default set.
   Each block's `:seon.render/ai` slot is a verbatim string or a fn symbol
   resolved late via `seon.eval/lookup-value`.

   The agent customizes by `seon.agent.ctx/install!` / `remove!` on its
   `:seon.agent/ctx` blocks, or by transacting a completely different symbol
   onto the agent's `:seon.render/ai` slot."
  (:require
    [clojure.string :as str]
    [seon.agent.message :as msg]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.namespaces :as ctx-namespaces]
    [seon.agent.ctx.transcript :as ctx-transcript]
    [seon.agent.ctx.warnings :as ctx-warnings]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — every shape the agent reads or writes. The agent-ns is not
;; stored on the entity — it's deterministic from the id via `home-ns`.
;; ============================================================

;; The literal "root" id (the orchestrator-root base case) is the ONE
;; agent id exempt from the 14-char minted-id shape — every other agent id
;; is a `:seon.db/id`. The `:or` bridges to the SAME datahike schema:
;; the CLJS bridge (seon.db.internal/form->datahike-value-type) walks
;; `:and` → base, then the `:or` (one mappable type :db.type/string via
;; :seon.db/id + the unmappable `[:= "root"]`) → :db.type/string; the
;; `{:seon.db/identity true}` prop still yields :db.unique/identity. Because
;; the resolved head is `:and` (not `:or`), `edn-encoded-attr?` is FALSE —
;; "root" stores as a plain string, NOT pr-str'd EDN. Net datahike schema:
;; byte-identical to before; only Malli validation broadens.
(schema/register! :seon.agent/id            [:and {:seon.db/identity true} [:or [:= "root"] :seon.db/id]])
(schema/register! :seon.agent/purpose       :string)
;; Subagent → parent (optional; delivery is a thin conditional in
;; `complete` — no spawn path sets this yet). References the canonical ref
;; shape; never inline.
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

(def home-ns ctx/home-ns)
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

(schema/register! :seon.eval/id          [:and {:seon.db/identity true} :seon.db/id])
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
;; inspector REPL) — optional, never nil. A ref so `[:seon.agent/id id]` value
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
(schema/register! :seon.schema/source     :string)
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
;; These required-sets feed schemas-as-queryable-data: at boot,
;; `seon.client/start-agent!` decomposes each :map into a `:seon.schema`
;; entity whose `:seon.schema/required-attrs` is the set computed from
;; entries without `{:optional true}`. Kind-lookup in `seon.render`
;; queries those entities via datalog.

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
   ;; analyzer projections — present when the eval defined a var; null
   ;; on schema-only registrations. Optional rather than always-present
   ;; because var-projection returns nil for non-var defs.
   [:seon.fn/fn-var?    {:optional true} :seon.fn/fn-var?]
   [:seon.fn/arglists   {:optional true} :seon.fn/arglists]
   [:seon.fn/doc        {:optional true} :seon.fn/doc]
   [:seon.fn/private?   {:optional true} :seon.fn/private?]
   [:seon.fn/spec       {:optional true} :seon.fn/spec]
   [:seon.fn/schema-error {:optional true} :seon.fn/schema-error]
   [:seon.fn/created-at {:optional true} :seon.fn/created-at]])

(schema/register! :seon.schema
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.schema/render-ai
         :seon.render/html 'seon.handlers.schema/render-html}
   [:seon.schema/key    :seon.schema/key]
   [:seon.schema/source :seon.schema/source]
   [:seon.schema/ns         {:optional true} :seon.schema/ns]
   [:seon.schema/created-at {:optional true} :seon.schema/created-at]])

(schema/register! :seon.ns
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.ns/render-ai
         :seon.render/html 'seon.handlers.ns/render-html}
   [:seon.ns/name   :seon.ns/name]
   [:seon.ns/source :seon.ns/source]])

;; :seon.agent — the agent's OWN entity-kind. The `:seon.render/html`
;; property makes `seon.render.default/view` the DEFAULT tile renderer via
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
   [:seon.render/ai   {:optional true} :seon.render/ai]
   [:seon.render/html {:optional true} :seon.render/html]])

;; ============================================================
;; DERIVED state — there is no stored `:seon.agent/state`. The FSM state is a
;; projection of the agent's primitives (terminated-at / open run / paused-at)
;; via [[seon.derive/derive-state]] — the ONE derivation leaf. `armable-agent-ids`
;; (below) is the wakeable roster, a FILTER over that one rule;
;; `derive-status` (re-exported below) is the full fingerprint. The loop + wake
;; gate now call [[seon.derive/derive-state]] directly with the db value they
;; hold.
;; ============================================================

(schema/register! ::armable-agent-ids-request [:map [:seon.db/db {:optional true} :seon.db/db-val]])
(schema/register! ::armable-agent-ids-response [:vector :seon.agent/id])

(defn armable-agent-ids
  "Agent ids whose DERIVED state is `:idle` — the ones a trigger can WAKE.

   `:idle` = not `:terminated` AND with no OPEN run. Open a fresh run for one;

   a running/paused agent is mid-run, a terminated agent is dead. The boot
   resume roster + the wake re-arm both read this. Map-in `:seon.db/db` adapter
   over [[seon.derive/armable-agent-ids]] (the one filter-over-derive-state
   rule); `:seon.db/db` optional (defaults to `*conn*`'s db)."
  {:malli/schema [:=> [:cat ::armable-agent-ids-request] ::armable-agent-ids-response]}
  [{:seon.db/keys [db]}]
  (derive/armable-agent-ids (or db @db/*conn*)))

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
;; The wake GATE — the one predicate the loop trigger ([[seon.agent.loop]])
;; and the transcript head-render both reuse so a message wakes (and renders
;; as an inbound) under exactly ONE rule. The loop + the trigger themselves
;; live in seon.agent.loop; this gate stays here (the agent owns 'what counts
;; as a message TO me').
;; ============================================================

(defn inbound-msg-datom?
  "True iff this datom is a waking inbound message for `my-eid`.

   An added `:seon.agent.message/to` targeting `my-eid` from a
   DIFFERENT sender with a WAKING origin (∈ {:human :agent}). The to-check is
   load-bearing: every agent installs the wake listener, so without it ONE
   message wakes EVERY agent's loop. The from/origin rule is the shared
   [[seon.agent.message/waking-inbound?]] — ONE source of truth with the
   transcript head-render. This datom adapter pulls the message entity, then
   delegates. Hop-exhausted messages still pass here; the wake handler
   partitions them out to refuse loudly (see [[seon.agent.loop/wake-handler]])."
  {:malli/schema [:=> [:catn [:db :any] [:datom :any] [:my-eid :any]] :any]}
  [db {eid :seon.db/e target :seon.db/v} my-eid]
  (and (= target my-eid)
       (msg/waking-inbound? (db/entity {:seon.db/db db :seon.db/ref eid})
                            my-eid)))

;; ============================================================
;; Agent creation. Allocates an id, transacts the entity.
;; ============================================================

;; purpose / default-turn-limit are :any (not their stored :string / :int):
;; create! folds them in UNCONDITIONALLY, so a caller (boot!) that has no
;; purpose passes an EXPLICIT nil — :any tolerates the absent-or-nil request
;; slot without throwing (the cond-> guards what actually reaches the tx).
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

(defn ^:async create!
  "Allocate an agent entity — just its `:seon.agent/id`.

   State is DERIVED: a fresh agent with no open run is `:idle`. Idempotent:
   re-calling with the
   same id is a no-op upsert that NEVER re-seeds — a resumed agent keeps its
   own purpose and its own edited/removed ctx blocks. A GENUINELY NEW entity
   gets `:seon.agent/purpose` ONLY when the human stated one; otherwise the
   attr stays ABSENT (optional = absent) until the agent derives a purpose and
   transacts it. Purpose is ENTITY DATA, never agent-directed instruction text
   — the welcome tile shows it verbatim to the customer.
   `:seon.agent/default-turn-limit`, when given, is transacted onto the entity
   (it seeds a new run's WORK bound); absent leaves the stored value unchanged.

   SEED-COPY: a genuinely-new entity gets the FULL default block set copied
   into its own `:seon.agent/ctx` via `seon.agent.ctx/seed-default-ctx!` (run
   in the new agent's `with-agent` scope), so render reads one collection and
   needs no default fallback. The seed rides the SAME `fresh?` gate as purpose:
   a resumed agent keeps whatever blocks it edited/removed.

   Returns `{:seon.agent/id id}` on success; on a FAILED transact the
   db error envelope (`{:seon.db/ok? false :seon.db/error …}`) comes
   back as-is — errors are values, the same contract as
   `seon.agent.message/message!`. A failed create means NO agent
   entity; callers must branch instead of chasing a ghost."
  {:malli/schema [:=> [:cat ::create-request] ::create-response]}
  [{:seon.agent/keys [id purpose default-turn-limit]}]
  (let [fresh? (nil? (db/entity {:seon.db/ref [:seon.agent/id id]}))
        res    (await (db/transact!
                        {:seon.db/tx-data
                         [(cond-> {:seon.agent/id id}
                            (and fresh?
                                 (string? purpose)
                                 (not (str/blank? purpose)))
                            (assoc :seon.agent/purpose purpose)
                            (some? default-turn-limit)
                            (assoc :seon.agent/default-turn-limit default-turn-limit))]}))]
    (if (false? (:seon.db/ok? res))
      ;; Surface-errors-loudly AND return the failure: a success-shaped
      ;; map after a failed transact is a dishonest record.
      (do (js/console.error
            (str "seon.agent/create! transact FAILED for " id ": "
                 (:seon.error/message (:seon.db/error res))))
          res)
      ;; SEED-COPY the default block set into the NEW agent's own ctx
      ;; (creation-only — the fresh? gate keeps a resumed agent's edits).
      ;; Runs in the agent's scope so `seed-default-ctx!` targets it.
      (do (when fresh?
            (let [seed (await (db/with-agent id
                                (fn ^:async seed-ctx! []
                                  (await (ctx/seed-default-ctx!)))))]
              (when (false? (:seon.agent.ctx/ok? seed))
                (js/console.error
                  (str "seon.agent/create! seed-default-ctx! FAILED for " id
                       ": " (:seon.agent.ctx/error seed))))))
          {:seon.agent/id id}))))

;; ============================================================
;; start! — the spawn verb. Alias of create! that ALSO writes the caller as
;; the new agent's `:seon.agent/parent`. The base case of the spawn recursion
;; is the orchestrator-root ("root", parentless); every other agent is spawned
;; by some parent via this verb.
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

;; Boot-registered ARM hook (#30). A freshly-spawned child is created IDLE with
;; NO wake trigger; a message to it strands silently until something installs
;; one. The arming machinery (llm-fn + bootstrap compile-state +
;; `seon.agent.loop/install-wake-trigger!`) lives in `seon.client`, which
;; REQUIRES `seon.agent` — so `seon.agent` cannot require it back. The client
;; registers a `(fn [child-id] -> Promise)` into this atom at boot;
;; `start!` invokes it BEFORE returning, so a message the parent sends right
;; after spawn actually wakes the child. INJECTION breaks the cycle (same
;; pattern `seon.agent.schedule` uses for its drive!/exec-fn!). nil before the
;; client registers it (gym/tests that don't need a live wake) ⇒ start! is a
;; no-op on arming and just returns the id.
(defonce !arm-child-fn (atom nil))

(defn ^:async start!
  "Spawn a child agent — the capability-gated spawn lifecycle verb.

   The spawn counterpart of `seon.agent.lifecycle/terminate`. An alias of `create!`
   that ALSO writes `:seon.agent/parent` = the CALLING agent (read from the
   ALS scope via `db/current-agent-id`). That parent write IS the activation
   of `:seon.agent/parent` — no separate writer. ALWAYS mints a fresh
   14-char child id (a child is never you; a chosen id goes through
   `create!`). The child
   is created IDLE: it does no work until it receives a message (which opens
   its run #1).

   The minted child's wake trigger is ARMED IN-PROCESS before this returns
   (via the boot-registered `!arm-child-fn` hook — `seon.client/init-agent!`),
   so a message the parent sends RIGHT AFTER spawn actually wakes the child
   (arming is reactive-only: a message sent before arming never wakes it, even
   later — so arming must precede any inbound). Before the client registers the
   hook (gym/tests with no live loop) arming is a no-op.

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
  {:malli/schema [:=> [:cat ::start-request] ::create-response]}
  [{:seon.agent/keys [purpose default-turn-limit]}]
  (let [child-id  (db/new-id!)
        parent-id (db/current-agent-id)
        res       (await (create! {:seon.agent/id child-id
                                   :seon.agent/purpose purpose
                                   :seon.agent/default-turn-limit default-turn-limit}))]
    (if (false? (:seon.db/ok? res))
      res
      (let [penv (when parent-id
                   (await (db/transact!
                            {:seon.db/tx-data
                             [{:seon.agent/id     child-id
                               :seon.agent/parent [:seon.agent/id parent-id]}]})))]
        (if (and penv (false? (:seon.db/ok? penv)))
          (do (js/console.error
                (str "seon.agent/start! parent-write FAILED for " child-id
                     " (parent " parent-id "): "
                     (:seon.error/message (:seon.db/error penv))))
              penv)
          ;; ARM the minted child IN-PROCESS (#30) so a message sent right
          ;; after spawn wakes it. Injected hook (no require cycle); a nil hook
          ;; (no live loop) leaves the child unarmed — the same shape as the
          ;; pre-arm world, never an error.
          (do (when-let [arm @!arm-child-fn]
                (await (arm child-id)))
              {:seon.agent/id child-id}))))))

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
  {:malli/schema [:=> [:cat ::delegate-request] ::create-response]}
  [{:seon.agent/keys [purpose default-turn-limit]
    content :seon.agent.message/content}]
  (let [spawn-args (cond-> {}
                     (some? purpose)            (assoc :seon.agent/purpose purpose)
                     (some? default-turn-limit) (assoc :seon.agent/default-turn-limit default-turn-limit))
        res        (await (start! spawn-args))]
    (if (false? (:seon.db/ok? res))
      res
      (let [child-id (:seon.agent/id res)
            menv     (await (msg/agent child-id content))]
        (if (false? (:seon.db/ok? menv))
          (do (js/console.error
                (str "seon.agent/delegate! spawned " child-id
                     " but the task message FAILED: "
                     (:seon.error/message (:seon.db/error menv))))
              (assoc menv :seon.agent/id child-id))
          {:seon.agent/id child-id})))))

;; ============================================================
;; Boot. The single entry point seon.client calls at startup. Agent
;; identity flows via the `seon.db/agent-id-als` scope, not process-global
;; atoms: the caller (seon.client/start-agent!) mints the id locally and
;; wraps the boot pipeline in `(seon.db/with-agent id …)`. The home-ns
;; stays deterministic via `(home-ns id)`.
;; ============================================================

;; The input map ALSO carries :seon.agent/llm-fn + :seon.agent/compile-state
;; (kept in the signature for the caller, unused here) — :map is open, so the
;; extra runtime slots pass. purpose is :any (absent-or-nil tolerant).
(schema/register! ::boot-request
  [:map
   [:seon.agent/id      :seon.agent/id]
   [:seon.agent/purpose {:optional true} :any]])

;; Success = `{:seon.agent/id _ :seon.agent/ns <home-ns symbol>}`; on a failed
;; create! the db error envelope propagates as-is. :seon.agent/ns is :any (the
;; home-ns symbol — an opaque derived value, not a stored attr).
(schema/register! ::boot-response
  [:or
   [:map [:seon.agent/id :seon.agent/id] [:seon.agent/ns :any]]
   :seon.db/transact-response])

(defn ^:async boot!
  "Create the agent entity. Map-in / map-out.

   Input:
     :seon.agent/id             agent id string (REQUIRED — pass the id
                                minted by the caller; no implicit default)
     :seon.agent/llm-fn         ctx-string -> Promise<{:text \"…\"}> (kept in
                                the signature for the caller; not used here)
     :seon.agent/compile-state  defonce'd bootstrap compile-state (idem)

   Does NOT arm the wake trigger — that is the CLIENT boot path's job
   (`seon.agent.loop/install-wake-trigger!`), so `seon.agent` need not depend
   on `seon.agent.loop` (acyclic). Returns `{:seon.agent/id _ :seon.agent/ns _}`.
   On a FAILED create! the db error envelope propagates as-is (errors are
   values): there is NO agent entity, so the caller must not arm a trigger."
  {:malli/schema [:=> [:cat ::boot-request] ::boot-response]}
  [{:seon.agent/keys [id purpose]}]
  (let [res (await (create! {:seon.agent/id id :seon.agent/purpose purpose}))]
    (if (false? (:seon.db/ok? res))
      ;; create! already console.error'd the transact failure; name the
      ;; boot path too, then hand the envelope up — callers branch.
      (do (js/console.error
            (str "seon.agent/boot! ABORTED for " id
                 " — create! failed; propagating the error envelope"))
          res)
      (let [{:seon.agent/keys [id]} res]
        {:seon.agent/id id
         :seon.agent/ns (home-ns id)}))))

;; ============================================================
;; message! lives in [[seon.agent.message]] (the keyword namespace matches
;; the code namespace). Re-exported here so `seon.agent/message!` resolves;
;; the agent-facing messaging verbs are `seon.agent.message/user` + `/agent`
;; via the `message/` alias. Same caveat as the ctx aliases above — a def
;; alias captures the fn value at load time (pre-instrumentation); call
;; `seon.agent.message/*` directly for the validated entry point.
;; ============================================================

(def message! msg/message!)
(def user-ref msg/user-ref)

;; ============================================================
;; Lifecycle verbs — wait / complete / pause / resume / terminate — live in
;; [[seon.agent.lifecycle]] (a lean, whitelisted teaching ns). They are the
;; agent-facing run-lifecycle verbs; each MUTATES the agent's RUN (close /
;; pause / set terminated-at), and the derived state follows. The agent home
;; ns `:refer`s them directly.
;; ============================================================

;; ============================================================
;; The agent's ctx-LAYOUT surface is `seon.agent.ctx/install!` /
;; `seon.agent.ctx/remove!` — the scope-aware override + seed verbs over the
;; agent's own `:seon.agent/ctx` block set. The block fns + the render
;; pipeline live in seon.agent.ctx (read API re-exported above).
;; ============================================================

;; ============================================================
;; Self-context verbs — the validated path onto YOUR OWN entity. Errors are
;; values; default scope = the calling agent; explicit :seon.agent/id allowed
;; (a human or another agent can configure an agent — it is all just
;; transacts; the verb is the validated path).
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
                             [:seon.agent/id {:optional true} :string]]]
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
