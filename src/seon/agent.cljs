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
       map), plus the rendered `:seon.eval` entity and the `:seon.ns/*`,
       `:seon.fn/*`, `:seon.schema/*` corpus schemas (`:seon.eval/*` attrs
       live behind [[seon.eval]], `:seon.agent.message/*` lives in
       [[seon.agent.message]], `:seon.agent.turn/*` in [[seon.agent.turn]],
       `:seon.agent.run/*` in [[seon.agent.run]], `:seon.agent.ctx/*` in [[seon.agent.ctx]])
     - `armable-agent-ids` — the wakeable agent ids (a `:seon.db/db` map-in
       adapter over the one [[seon.derive]] leaf); state is a projection of the
       run/terminated-at primitives, never stored
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
    [seon.agent.internal :as internal]
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

(def host-timezone ctx/host-timezone)
(def truncate-edn ctx/truncate-edn)
(def message-label ctx/message-label)
(def cap-result ctx/cap-result)
(def cap-result-body ctx/cap-result-body)
(def namespaces-block ctx-namespaces/namespaces-block)
(def warnings-block ctx-warnings/warnings-block)
(def transcript-block ctx-transcript/transcript-block)

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
   [:seon.eval/status      {:optional true} :seon.eval/status]
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

;; :seon.agent — the agent's OWN entity shape. Its page/canvas operation
;; selects the surface renderer after acquiring one immutable agent value;
;; the schema does not install a second host-side default reader. No
;; `:seon.render/ai` property in the props — the agent entity must NOT enter
;; the chronological ai window. The ONLY required attr is `id` (the one thing
;; `create!` always writes); state is DERIVED (no stored enum), and every
;; other attr arrives lazily. `sections` keeps its own register! (still
;; transactable/queryable) but stays out of the record shape's required set.
(schema/register! :seon.agent
  [:map {:seon.db/entity true}
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
   [:seon.agent.ctx/render-namespaces
    {:optional true} :seon.agent.ctx/render-namespaces]
   [:seon.agent.ctx/capabilities
    {:optional true} :seon.agent.ctx/capabilities]
   [:seon.agent.ctx/escape-clipping?
    {:optional true} :seon.agent.ctx/escape-clipping?]
   [:seon.agent.ctx/cache-breakpoint
    {:optional true} :seon.agent.ctx/cache-breakpoint]
   [:seon.agent.runtime/wake?      {:optional true} :seon.agent.runtime/wake?]
   [:seon.eval/home-requires       {:optional true} :seon.eval/home-requires]
   [:seon.render/ai   {:optional true} :seon.render/ai]
   [:seon.render/html {:optional true} :seon.render/html]])

;; ============================================================
;; DERIVED state — there is no stored `:seon.agent/state`. The FSM state is a
;; projection of the agent's primitives (terminated-at / open run / paused-at)
;; via [[seon.derive/derive-state]] — the ONE derivation leaf. The functions
;; below acquire an ordinary database value and call the owning async readers.
;; ============================================================

(schema/register!
 ::agent-ids-request
 [:map {:closed true}
  [:seon.db/db {:optional true} :seon.db/db]])
(schema/register! ::agent-ids-response [:vector :seon.agent/id])
(schema/register! ::direct-error [:map [:seon.error/message :string]])
(schema/register! ::agent-ids-result [:or ::agent-ids-response ::direct-error])
(schema/register! ::initial-created? :boolean)
(schema/register! ::root-created? :boolean)
(schema/register! ::ensure-initial-agent-request [:map])
(schema/register!
  ::ensure-initial-agent-response
  [:or
   [:map
    [::root-created? ::root-created?]
    [::initial-created? ::initial-created?]
    [:seon.agent/id {:optional true} :seon.agent/id]]
   ::direct-error])

(defn ^:async armable-agent-ids
  "Born agent ids whose DERIVED state is `:idle` — the ones a trigger can WAKE.

   `:idle` = not `:terminated` AND with no OPEN run. Open a fresh run for one;

   a running/paused agent is mid-run, a terminated agent is dead. The boot
   resume pass + the wake re-arm both read this. Map-in `:seon.db/db` adapter
   over [[seon.derive/armable-agent-ids]] (the one filter-over-derive-state
   rule); `:seon.db/db` optional (defaults to the session's latest value)."
  {:malli/schema [:=> [:cat ::agent-ids-request] ::agent-ids-result]}
  [{:seon.db/keys [db]}]
  (let [database (or db (await (db/db)))]
    (if (:seon.error/message database)
      database
      (await (derive/armable-agent-ids database)))))

(defn ^:async resumable-agent-ids!
  "Read born, nonterminated process hosts at one database value.

   Running and paused agents are included because they still need fresh
   process handles after a cold start or reload. Identity-only provenance
   targets are not born and are omitted."
  {:malli/schema
   [:function
    [:=> [:cat] ::agent-ids-result]
    [:=> [:cat ::agent-ids-request] ::agent-ids-result]]}
  ([] (await (resumable-agent-ids! {})))
  ([{:seon.db/keys [db]}]
   (let [database (or db (await (db/db)))]
     (if (:seon.error/message database)
       database
       (await (derive/resumable-agent-ids database))))))

(def ^:private ordinary-agent-ever-born-query
  '[:find ?agent .
    :where
    [?agent :seon.agent/id ?id _ true]
    [?agent :seon.eval/home-requires _ _ true]
    [(not= ?id "root")]])

;; ============================================================
;; Agent creation. Reconcile known ids; allocate genuinely new ids atomically.
;; ============================================================

(schema/register! ::create-request
  [:map
   [:seon.agent/id                  :seon.agent/id]
   [:seon.agent/purpose             {:optional true} :seon.agent/purpose]
   [:seon.agent/default-turn-limit  {:optional true}
    :seon.agent/default-turn-limit]])

;; Success = `{:seon.agent/id id}`; a failed database operation returns its
;; direct `:seon.error/message` value unchanged.
(schema/register! ::create-response
  [:or
   [:map [:seon.agent/id :seon.agent/id]]
   ::direct-error])

(defn- error-value?
  [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- unavailable-response
  []
  (:seon/error (admission/unavailable)))

(defn- acquisition-failure
  [message data]
  {:seon.error/message message
   :seon.error/data data})

(def ^:private configuration-ref
  [:seon.config/id config/cluster-config-id])

(defn- configuration-from-entity
  [entity]
  (if entity
    (db/decode-edn-values entity)
    (acquisition-failure
     "Agent creation requires the database configuration singleton."
     {:seon.db/ref configuration-ref})))

(defn- initial-agent-tx
  "Complete creation facts for one new agent and its home namespace.

   Pure data. The caller commits this vector once, so no observer can see an
   identity without its configured context, scalar dials, and structural home
   namespace. `existing` is used only to finish the reserved root's bare
   provenance-genesis stub: facts already present are preserved, never reset."
  [configuration id purpose default-turn-limit parent existing]
  (let [context       (ctx/initial-agent-context
                       {:seon.agent/id id
                        :seon.config/configuration configuration})
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

   Returns `{:seon.agent/id id}` on success; a database error comes back as
   an ordinary `:seon.error/message` value. A failed create means NO agent
   entity; callers must branch instead of chasing a ghost."
  {:malli/schema [:=> [:cat ::create-request] ::create-response]}
  [{:seon.agent/keys [id purpose default-turn-limit]}]
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [entities
            (await
             (db/pull-many
              {::db/db database
               ::db/selector '[*]
               ::db/eids
               [[:seon.agent/id id]
                [:seon.ns/name (keyword (str (home/home-ns id)))]
                configuration-ref]
               ::db/max-results 3
               ::db/max-result-weight 1048576}))]
        (if (error-value? entities)
          (acquisition-failure "Agent creation acquisition failed." entities)
          (let [[entity home-entity configuration-entity] entities
                configuration (configuration-from-entity configuration-entity)]
            (if (error-value? configuration)
              configuration
              (let [complete? (and entity
                                   (or (not= "root" id)
                                       (string? (:seon.ns/source home-entity))))]
                (if complete?
                  {:seon.agent/id id}
                  (let [res
                        (await
                         (db/transact!
                          {::db/db database
                           ::db/expected-db database
                           ::db/tx-data
                           (initial-agent-tx
                            configuration id purpose default-turn-limit nil entity)}))]
                    (if (error-value? res) res {:seon.agent/id id})))))))))))

(schema/register!
  ::mint-request
  [:map
   [:seon.agent/purpose            {:optional true} :seon.agent/purpose]
   [:seon.agent/default-turn-limit {:optional true}
    :seon.agent/default-turn-limit]
   [:seon.agent/parent             {:optional true} :seon.db/ref]])

(defn- ^:async allocate-agent!
  [{:seon.agent/keys [purpose default-turn-limit parent]
    :seon.db/keys [db tx-data expected-db]
    initial-message-transaction ::initial-message-transaction
    configuration :seon.config/configuration
    ::db.id/keys [generator-policies]}]
  (await
   (db.id/allocate!
    (cond->
     {::db/db db
      ::db.id/allocations
      (into [{::db.id/key :seon.agent/id
              ::db.id/identity-attr :seon.agent/id}]
            (:seon.agent.message/allocations initial-message-transaction))
      ::db.id/transaction-builder
      (fn [ids]
        (let [id (get ids :seon.agent/id)
              child-ref [:seon.agent/id id]
              message-rows
              (if initial-message-transaction
                (::db/tx-data
                 ((:seon.agent.message/transaction-builder
                   initial-message-transaction)
                  ids child-ref))
                [])]
          (cond->
           {::db/tx-data
            (into (into (vec tx-data)
                        (initial-agent-tx
                         configuration id purpose default-turn-limit parent nil))
                  message-rows)}
            expected-db
            (assoc ::db/expected-db expected-db))))}
       generator-policies
       (assoc ::db.id/generator-policies generator-policies)))))

(defn ^:async mint!
  "Atomically allocate and create one genuinely new agent identity.

   The registered `:seon.agent/id` policy selects the readable-word package;
   the sole writer proves freshness before committing the complete initial
   agent and home-namespace facts. Known-id reconciliation remains [[create!]] and is intentionally
   separate."
  {:malli/schema [:=> [:cat ::mint-request] ::create-response]}
  [{:seon.agent/keys [purpose default-turn-limit parent]}]
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [configuration-entity
            (await (db/entity database configuration-ref))]
        (if (error-value? configuration-entity)
          configuration-entity
          (let [configuration
                (configuration-from-entity configuration-entity)]
            (if (error-value? configuration)
              configuration
              (let [env
                    (await
                     (allocate-agent!
                      {::db/db database
                       :seon.config/configuration configuration
                       :seon.agent/purpose purpose
                       :seon.agent/default-turn-limit default-turn-limit
                       :seon.agent/parent parent}))
                    id (get-in env [::db.id/ids :seon.agent/id])]
                (if (error-value? env)
                  env
                  {:seon.agent/id id})))))))))

(defn ^:async ensure-initial-agent!
  "Ensure the root and initial ordinary agent.

   A non-root agent's historical birth fact permanently satisfies this
   transition, even if that agent is later terminated or retracted. A fresh
   database commits root and the initial agent atomically.

   Returns `::initial-created?` and, when created, the new agent id. Database
   failures remain ordinary `seon.db/transact!` error envelopes."
  {:malli/schema
   [:=> [:cat ::ensure-initial-agent-request]
    ::ensure-initial-agent-response]}
  [_]
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [root-data
            (await
             (db/pull-many
              {::db/db database
               ::db/selector '[*]
               ::db/eids [[:seon.agent/id "root"]
                          [:seon.ns/name :my.agent.root]
                          configuration-ref]
               ::db/max-results 3
               ::db/max-result-weight 1048576}))
            ordinary-born
            (when-not (error-value? root-data)
              (await
               (db/query
                {::db/db (db/history database)
                 ::db/query ordinary-agent-ever-born-query
                 ::db/max-results 1
                 ::db/max-result-weight 4096})))]
        (cond
          (error-value? root-data) root-data
          (error-value? ordinary-born) ordinary-born
          :else
          (let [[root root-home configuration-entity] root-data
                configuration (configuration-from-entity configuration-entity)]
            (if (error-value? configuration)
              configuration
              (let [root-complete? (and root (string? (:seon.ns/source root-home)))
                    ordinary-born? (boolean ordinary-born)
                    root-tx (if root-complete?
                              []
                              (initial-agent-tx
                               configuration "root" nil nil nil root))]
                (cond
                  (and root-complete? ordinary-born?)
                  {::root-created? false ::initial-created? false}

                  ordinary-born?
                  (let [result
                        (await
                         (db/transact!
                          {::db/db database
                           ::db/expected-db database
                           ::db/tx-data root-tx}))]
                    (if (error-value? result)
                      result
                      {::root-created? true ::initial-created? false}))

                  :else
                  (let [result
                        (await
                         (allocate-agent!
                          {::db/db database
                           ::db/expected-db database
                           ::db/tx-data root-tx
                           :seon.config/configuration configuration
                           :seon.agent/parent [:seon.agent/id "root"]}))]
                    (if (error-value? result)
                      result
                      {::root-created? (not root-complete?)
                       ::initial-created? true
                       :seon.agent/id
                       (get-in result [::db.id/ids :seon.agent/id])})))))))))))

;; ============================================================
;; Spawn depth (multi-agent-context Piece 2) — the DEPTH-CAP backstop. The soft
;; gate (spawn functions only in root's home-requires) keeps ordinary agents from
;; REACHING start!, but a full-qualified `(seon.agent/start! …)` slips past it;
;; this is the hard, computed structural rule that refuses it. `spawn-depth`
;; walks the `:seon.agent/parent` chain to a number; `start!` refuses a caller
;; already AT the config cap (default 1 — root spawns, a subagent does not).
;; No name list — a config-dialed number; raise the dial to deepen the tree.
;; ============================================================

(defn- spawn-depth-from
  [agent]
  (loop [current agent depth 0 seen #{}]
    (let [id (:seon.agent/id current)]
      (cond
        (nil? id) depth
        (contains? seen id)
        (do
          (error/record!
           {:seon.error/raw
            (js/Error. (str "spawn-depth: :seon.agent/parent cycle detected at "
                            (pr-str id) " — the spawn tree must be acyclic"))
            :seon.error/fault :core})
          depth)
        (:seon.agent/parent current)
        (recur (:seon.agent/parent current) (inc depth) (conj seen id))
        :else depth))))

(defn ^:async spawn-depth
  "Depth of `agent-id` in the spawn tree over `db` — root/parentless = 0.

   Walks `:seon.agent/parent` refs (child = parent + 1) with a visited-set
   cycle guard: a cycle is a `:core`-fault-worthy invariant break (recorded via
   `seon.error/record!`, never thrown — the fn returns the depth walked so
   far). The parent tree is acquired once at the passed database value."
  {:malli/schema [:=> [:catn [:seon.db/db :seon.db/db]
                             [:seon.agent/id :seon.agent/id]]
                  [:or :int ::direct-error]]}
  [database agent-id]
  (let [agent
        (await
         (db/pull
          {::db/db database
           ::db/selector internal/managed-agent-selector
           ::db/eid [:seon.agent/id agent-id]}))]
    (if (error-value? agent)
      agent
      (spawn-depth-from agent))))

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
   [:seon.agent/purpose             {:optional true} :seon.agent/purpose]
   [:seon.agent/default-turn-limit  {:optional true}
    :seon.agent/default-turn-limit]])
(schema/register! ::start-response
  [:or ::create-response :seon.agent.runtime/resume-response])

(defn ^:async ^:private spawn-child!
  "Commit one child birth, then host it from the committed facts."
  [database configuration purpose default-turn-limit parent-id
   initial-message-transaction]
  (let [res
        (await
         (allocate-agent!
          (cond->
           {::db/db database
            ::db/expected-db database
            :seon.config/configuration configuration
            ::initial-message-transaction initial-message-transaction}
            (some? purpose)
            (assoc :seon.agent/purpose purpose)
            (some? default-turn-limit)
            (assoc :seon.agent/default-turn-limit default-turn-limit)
            parent-id
            (assoc :seon.agent/parent [:seon.agent/id parent-id]))))
        child-id (get-in res [::db.id/ids :seon.agent/id])]
    (if (error-value? res)
      res
      (let [resumed (await (runtime/resume! {:seon.agent/id child-id}))]
        (if (:seon.agent.runtime/resumed? resumed)
          {:seon.agent/id child-id}
          resumed)))))

(defn- spawn-depth-error
  [function-name parent-id depth cap]
  {:seon.error/message
   (str function-name ": refused — agent " (pr-str parent-id)
        " is at spawn depth " depth " (cap " cap ").")})

(defn ^:async ^:private acquire-spawn-database
  [function-name parent-id]
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [refs (cond-> []
                   parent-id (conj [:seon.agent/id parent-id])
                   true (conj configuration-ref))
            entities
            (await
             (db/pull-many
              {::db/db database
               ::db/selector (into '[*] internal/managed-agent-selector)
               ::db/eids refs
               ::db/max-results (count refs)
               ::db/max-result-weight 1048576}))]
        (if (error-value? entities)
          entities
          (let [parent (when parent-id (first entities))
                configuration-entity (last entities)
                configuration
                (configuration-from-entity configuration-entity)
                depth (when parent-id (spawn-depth-from parent))]
            (if (error-value? configuration)
              configuration
              (let [cap (config/spawn-depth-cap configuration)]
                (if (and depth (>= depth cap))
                  (spawn-depth-error function-name parent-id depth cap)
                  {::db/db database
                   :seon.config/configuration configuration})))))))))

(defn ^:async start!
  "Spawn a child agent — the capability-gated spawn lifecycle function.

   The spawn counterpart of `seon.agent.lifecycle/terminate`. Unlike
   `create!`, it ALWAYS mints a fresh readable three-segment id and writes
   `:seon.agent/parent` = the CALLING agent (read from the ALS scope via
   `db/current-agent-id`) in the same transaction. The child is IDLE: it does
   no work until it receives a message (which opens its run #1).

   The minted child's process runtime is resumed before this returns. Use
   `delegate!` when child birth and an initial task must be one transaction.

   Resolves to `{:seon.agent/id child-id}`. Called outside an agent scope, the
   child is created parentless, matching `create!`."
  {:malli/schema [:=> [:cat ::start-request] ::start-response]}
  [{:seon.agent/keys [purpose default-turn-limit]}]
  (if-not (admission/available?)
    (unavailable-response)
    (let [parent-id (db/current-agent-id)
          acquisition (await (acquire-spawn-database "start!" parent-id))]
      (if (error-value? acquisition)
        acquisition
        (await
         (spawn-child!
          (::db/db acquisition) (:seon.config/configuration acquisition)
          purpose default-turn-limit parent-id nil))))))

;; ============================================================
;; delegate! — the one atomic child-birth plus initial-task transition.
;; ============================================================

;; NO `:seon.agent/id` slot — same reason as [[::start-request]]: the
;; declared-optional key resolves to the CALLER, and the child is never you.
(schema/register! ::delegate-request
  [:map
   [:seon.agent.message/content     :string]
   [:seon.agent/purpose             {:optional true} :seon.agent/purpose]
   [:seon.agent/default-turn-limit  {:optional true}
    :seon.agent/default-turn-limit]])
(schema/register! ::delegate-response
  [:or ::create-response :seon.agent.runtime/resume-response])

(defn ^:async delegate!
  "Spawn a child AND hand it its task in ONE call.

   Child birth and the initial task message commit in one transaction before
   the child process is hosted.

     (delegate! {:seon.agent/purpose \"research DuckDB for embedded analytics\"
                 :seon.agent.message/content
                 \"Research DuckDB for an embedded analytics app. Store findings
                  as my.kb.* data, then (complete \\\"<pointer>\\\") to report back.\"})

   `:seon.agent/purpose` is the child's stated reason-for-being (shown to your
   human verbatim); `:seon.agent.message/content` is the actual task you hand
   it. The child id is always minted (spawn a chosen id via `create!`);
   `:seon.agent/default-turn-limit` (optional) seeds the child's work
   bound. RESOLVES to `{:seon.agent/id child-id}` on success — the id you
   address for any follow-up. A database failure commits neither child nor
   task. A hosting failure returns the runtime error while the committed facts
   remain available for runtime reconciliation."
  {:malli/schema [:=> [:cat ::delegate-request] ::delegate-response]}
  [{:seon.agent/keys [purpose default-turn-limit]
    content :seon.agent.message/content}]
  (if-not (admission/available?)
    (unavailable-response)
    (if-let [parent-id (db/current-agent-id)]
      (let [acquisition (await (acquire-spawn-database "delegate!" parent-id))]
        (if (error-value? acquisition)
          acquisition
          (let [database (::db/db acquisition)
                configuration (:seon.config/configuration acquisition)
                message-transaction
                (await
                 (msg/initial-agent-transaction
                  database [:seon.agent/id parent-id] content))]
            (if (error-value? message-transaction)
              message-transaction
              (await
               (spawn-child!
                database configuration purpose default-turn-limit parent-id
                message-transaction))))))
      (internal/no-agent-error "delegate!"))))

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

(schema/register! ::purpose-request
  [:map
   [:seon.agent/purpose :seon.agent/purpose]
   [:seon.agent/id {:optional true} :seon.agent/id]])
(schema/register! ::purpose-response
  [:or
   [:map
    [:seon.agent/id :seon.agent/id]
    [:seon.agent/purpose :seon.agent/purpose]]
   ::direct-error])

(defn ^:async set-purpose!
  "Set why an agent exists.

   Omit `:seon.agent/id` to update yourself. Root may update any agent; an
   ordinary agent may update itself or a descendant. Returns the persisted id
   and purpose, or an error value."
  {:malli/schema [:=> [:cat ::purpose-request] ::purpose-response]}
  [{purpose :seon.agent/purpose target-id :seon.agent/id}]
  (let [caller-id (db/current-agent-id)
        id        (or target-id caller-id)]
    (cond
      (nil? id)
      {:seon.error/message
       (str "set-purpose!: no agent in scope — pass "
            ":seon.agent/id or call inside (seon.db/with-agent id …).")}

      (nil? caller-id)
      (internal/no-agent-error "set-purpose!")

      :else
      (let [database (await (db/db))]
        (if (error-value? database)
          database
          (let [target
                (await
                 (db/pull
                  {::db/db database
                   ::db/selector internal/managed-agent-selector
                   ::db/eid [:seon.agent/id id]}))]
            (cond
              (error-value? target) target
              (not (internal/manages? caller-id target))
              (internal/unauthorized-target-error "set-purpose!" caller-id id)
              :else
              (let [res
                    (await
                     (db/transact!
                      {::db/db database
                       ::db/expected-db database
                       ::db/tx-data
                       [{:seon.agent/id id
                         :seon.agent/purpose purpose}]}))]
                (if (error-value? res)
                  res
                  {:seon.agent/id id
                   :seon.agent/purpose purpose})))))))))
