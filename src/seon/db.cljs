(ns seon.db
  "Database access. This is the only API you need to read, write, and
   react to the database. Everything in your runtime that touches the DB
   goes through this namespace.

   ## Your universe is one connection

   You have exactly ONE database. `seon.db/*conn*` is bound for you by
   the session-flow boundary before your code starts running; you never
   thread it through your own calls and you never open another conn.
   Every fn here defaults `:seon.db/conn` to `*conn*`, so call sites
   stay clean. Aliasing `seon.db` as `db` lets you write `::db/foo` for
   `:seon.db/foo`:

     (require '[seon.db :as db])

     (db/query    {::db/query '[:find ?n :where [_ ::name ?n]]})
     (db/transact! {::db/tx-data [{::name \"Alpha\" ::rank 1}]})

   That's how you talk to the world. Every map key in / out of seon.db
   is fully namespaced under `:seon.db/*`. The namespacing isn't
   cosmetic — it's what lets a single Datalog query connect the data
   in the database to the functions that operate on it.

   ## Register schemas before you transact

   `transact!` will refuse any tx that touches an attribute it doesn't
   recognize. This is deliberate: it means the DB never accumulates
   silent typos, and every attribute carries a Malli schema you and
   future code can introspect. Register first, transact second:

     (require '[seon.schema :as schema])
     (schema/register! ::name :string)
     (schema/register! ::rank :int)
     (schema/register! ::tags [:vector :keyword])

     (db/transact! {::db/tx-data [{::name \"Alpha\" ::rank 1 ::tags [:demo]}]})

   `:db/*` system attributes (`:db/ident`, `:db/valueType`, etc.) used
   to declare the datahike schema itself bypass the gate. Vector tuples
   (`[:db/add e a v]`, `[:db/retract e a v]`) flow through, but only
   their attribute is checked — entity-map values are what get full
   Malli validation. Validation errors throw synchronously; you don't
   have to thread them through the async channel.

   ## Reads are synchronous, writes return a channel

   `query`, `pull`, `entity` are sync — they resolve against the current
   db value (`@*conn*`), so you compose them in straight-line code:

     (let [eid (db/query {::db/query '[:find ?e .
                                       :in $ ?n
                                       :where [?e ::name ?n]]
                          ::db/args  [\"Alpha\"]})]
       (db/pull {::db/pull-pattern '[*]
                 ::db/ref          eid}))

   `transact!` is `^:async` and returns a Promise with an envelope:

     (let [{::db/keys [ok? tx-report error]}
           (await (db/transact! {::db/tx-data [...]}))]
       (if ok?
         (handle-success tx-report)
         (handle-failure error)))

   The envelope keeps commit failures (datahike-side, returned as values
   per spec-02 §2.5 safe-by-default — `error` is a `seon.error/->map`)
   distinct from validation failures (which throw synchronously because
   they're local programmer-mistakes the agent's eval boundary catches).

   ## Reactions: register a listener with a key

   `listen!` installs a tx-listener under a key. Multiple keys coexist;
   they each receive every tx-report. To stop a listener, retract it by
   key — there's no \"unsubscribe from one channel of many\"; one
   listener, one key.

   Handler input is rich — every key fully namespaced, pick what you
   want, ignore the rest:

     :seon.db/tx-report   full raw datahike report (escape hatch)
     :seon.db/db          the post-commit db value, ready to query
     :seon.db/db-before   the pre-commit db value, for change-detection
     :seon.db/datoms      [{:seon.db/e :seon.db/a :seon.db/v
                            :seon.db/tx :seon.db/added?} ...]
     :seon.db/attr-index  {:my.ns/attr [datoms-touching-it ...]}
                          — pre-grouped (keys are user-domain attrs;
                          values are vectors of :seon.db/datom maps)

   The `:seon.db/db` key means handlers don't reach back to `*conn*`.
   You query the exact post-commit state of the tx that fired you — no
   stale-read races even when the listener queue is backed up.

   ## The canonical reaction: kick your own agent loop on a user message

   The default agent boot wires this listener. Read it as your own
   self-description — this is how you start running when the user
   addresses you, and how you stop running when you have nothing left
   to say:

     (require '[seon.db :as db] '[seon.schema :as schema])

     ;; ---- schemas: registered once at boot ----
     (schema/register! :seon.message/role
                       [:enum :user :assistant :system])
     (schema/register! :seon.message/content   :string)
     (schema/register! :seon.message/agent     :seon.db/ref)
     (schema/register! :seon.agent/state
                       [:enum :idle :running])

     ;; ---- the kick-the-loop reaction ----
     (defn kick-on-user-message
       \"Trigger handler. Fires on every tx. For each newly-added
        :seon.message/role datom whose value is :user, look at the
        agent that owns the message and decide whether to spawn a
        turn.\"
       [{::db/keys [db attr-index]}]
       (doseq [{::db/keys [e added?]} (:seon.message/role attr-index)
               :when added?
               :let [msg (db/entity {::db/db db ::db/ref e})]
               :when (= :user (:seon.message/role msg))
               :let [agent (:seon.message/agent msg)
                     state (:seon.agent/state agent)]]
         (case state
           ;; Loop already running — it will see the new message on its
           ;; next ctx-build and respond to it. Doing nothing here is
           ;; the idempotency guarantee: you can paste 10 user messages
           ;; in a row and exactly one loop runs, processing all of them.
           :running nil
           ;; Idle (or nil for a fresh agent). Flip the state and run
           ;; one turn. The loop owns its own continuation — once it
           ;; has the `:running` flag it'll keep ticking until it
           ;; decides to stop.
           (do
             (db/transact!
               {::db/tx-data [{:db/id (:db/id agent)
                               :seon.agent/state :running}]})
             (seon.agent/run-turn-once!
               (:seon.agent/id agent) (seon.agent/home-ns (:seon.agent/id agent))
               llm-fn compile-state))))

     (db/listen! {::db/key     ::user-message-kick
                  ::db/handler kick-on-user-message})

   ### How the loop stops itself

   Each turn of `seon.agent/run-turn-once!` does:

     1. build ctx from the agent's messages (user + prior assistant
        + tool-call results),
     2. call the LLM,
     3. parse the response:
        - if it has tool calls → execute them, transact their results
          as `:seon.tool-call/*` entities, recurse for another turn
          (the tool-call results re-trigger ctx-build; the LLM gets
          its own outputs back),
        - if it's plain text with no tool calls → transact an
          `:seon.message/role :assistant` entity carrying the text,
          set `:seon.agent/state :idle`, return.

   The exit condition is what LLMs do most of the time anyway: when
   they have nothing useful left to do, they return prose. That prose
   becomes an `:assistant`-role message; the listener above ONLY fires
   on `:user`-role additions; the assistant's own message doesn't
   re-trigger the loop. So:

   - User types something → loop kicks → runs until LLM is done →
     halts. Idle.
   - User types again → loop kicks again. Idempotency means even if
     they paste fast, only one loop runs at a time.
   - The loop NEVER kicks itself by writing its own messages — that
     would be infinite recursion. The asymmetric trigger
     (`:user`-only) is the load-bearing piece.

   ### What if the LLM never stops calling tools?

   Bound the tick count. `run-agent-tick!` carries a `:tick` counter;
   when it crosses (say) 20 without producing a text response, the
   loop transacts a synthetic assistant message saying so, sets state
   `:idle`, and exits. The user can kick it again with a fresh
   prompt. Bounded recursion + the user as oracle on \"are we done?\"
   is the right shape for a system the user can supervise.

   ### Removing the reaction

   If you want to stop reacting to user messages (for instance, you
   want a session that the user can append to but you don't auto-
   respond to), just retract the listener:

     (db/unlisten! {::db/key ::user-message-kick})

   The session's message log keeps growing; no loops spawn. To bring
   it back, register the handler again.

   ### Triggers as data (V0-B-5)

   `seon.trigger/register!` will let you persist this same reaction as
   an entity in the DB — `:seon.trigger/signature` + `:seon.trigger/
   handler-symbol` — so the agent and user can both see what's wired
   and retract any single one without touching the others. The Shape B
   dispatcher under the hood is one `seon.db/listen!` call with key
   `::seon.trigger/dispatcher`. Direct `db/listen!` (above) is the
   primitive; `seon.trigger` is the data-driven layer on top."
  (:require
    [datahike.api :as d]
    [malli.core :as m]
    [seon.error :as error]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Schemas — registered at namespace load. seon.schema is global + atom-backed,
;; so re-loads idempotently overwrite the same keys.
;; ---------------------------------------------------------------------------

;; Every key in every map seon.db hands out — request, response, handler
;; input, decoded datom — is fully namespaced under `:seon.db/*`. This is
;; not stylistic: it's what lets the agent (or the user, or operator
;; tooling) join function specs to data with a single Datalog query. A
;; single-segment `:tx-data` key carries no information about which fn
;; owns it; `:seon.db/tx-data` does.

(schema/register! ::tx-data [:vector :any])
(schema/register! ::opts :map)
(schema/register! ::conn :any)

(schema/register!
  ::transact-request
  [:map
   [::tx-data ::tx-data]
   [::opts    {:optional true} ::opts]
   [::conn    {:optional true} ::conn]])

(schema/register!
  ::transact-response
  [:map
   [::ok?       :boolean]
   [::tx-report {:optional true} :any]
   [::error     {:optional true}
                [:map
                 [::msg  :string]
                 [::data {:optional true} :any]]]])

(schema/register!
  ::query-request
  [:map
   [::query :any]
   [::args  {:optional true} [:vector :any]]
   [::db    {:optional true} :any]
   [::conn  {:optional true} ::conn]])

(schema/register!
  ::pull-request
  [:map
   [::pull-pattern :any]
   [::ref          :any]
   [::db           {:optional true} :any]
   [::conn         {:optional true} ::conn]])

(schema/register!
  ::entity-request
  [:map
   [::ref  :any]
   [::db   {:optional true} :any]
   [::conn {:optional true} ::conn]])

(schema/register!
  ::datom
  [:map
   [::e      :int]
   [::a      :keyword]
   [::v      :any]
   [::tx     :int]
   [::added? :boolean]])

(schema/register!
  ::handler-input
  [:map
   [::tx-report  :any]
   [::db         :any]
   [::db-before  :any]
   [::datoms     [:vector ::datom]]
   [::attr-index [:map-of :keyword [:vector ::datom]]]
   [::trigger    {:optional true} :any]])

(schema/register!
  ::listen-request
  [:map
   [::handler [:fn fn?]]
   [::key     {:optional true} :any]
   [::conn    {:optional true} ::conn]])

(schema/register!
  ::listen-response
  [:map [::key :any]])

(schema/register!
  ::unlisten-request
  [:map
   [::key  :any]
   [::conn {:optional true} ::conn]])

(schema/register!
  ::unlisten-response
  [:map [::ok? :boolean]])

;; ---------------------------------------------------------------------------
;; The agent's universe. Bound by the session-flow boundary at session start;
;; nil outside a bound scope. Test/admin code may pass `:conn` explicitly per
;; the spec's escape-hatch posture.
;; ---------------------------------------------------------------------------

(def ^:dynamic *conn*
  "The runtime's datahike connection. Bound by the session-flow boundary at
   session start; never threaded through agent call sites. Reads default to
   `@*conn*` (a db value); writes route through this conn's writer (V0:
   local datahike-cljs; V1+: `:writer :kabel`, transparent to callers).

   For a CLJS user-pod runtime, this is the user's DB. All sessions for the
   same user share this conn — sessions are entities in it, not partitions
   of it."
  nil)

;; ---------------------------------------------------------------------------
;; Validation gate — mirrors `seon.db/transact!` 60–135 on JVM byte-for-byte
;; so the eventual .cljc merge is mechanical. Any change here must mirror
;; into the JVM file (and vice versa) until convergence.
;; ---------------------------------------------------------------------------

(defn- system-attr?
  "True for `:db/*` system attributes that drive datahike's own schema
   layer and should not be validated against the seon Malli registry."
  [k]
  (and (keyword? k)
       (= "db" (namespace k))))

(defn- extract-tx-attrs
  "Walk tx-data and collect every attribute keyword that appears. Handles
   both entity-map forms (`{:attr v ...}`) and vector tuple forms
   (`[:db/add e a v]`, `[:db/retract e a v]`). Tempids and metadata not
   keyed by a true attribute are filtered out."
  [tx-data]
  (into #{}
        (mapcat (fn [datum]
                  (cond
                    (map? datum)
                    (keys datum)

                    (and (vector? datum) (>= (count datum) 3))
                    [(nth datum 2)]

                    :else nil)))
        tx-data))

(defn- validate-attrs!
  "Ensure every non-`:db/*` attribute appearing in `tx-data` is registered
   in `seon.schema`. Throws `ex-info` with `{:unregistered [...]}` listing
   the offenders. Caller is expected to register schemas first."
  [attrs]
  (let [domain-attrs (remove system-attr? attrs)
        unregistered (into [] (remove schema/registered?) domain-attrs)]
    (when (seq unregistered)
      (throw (ex-info (str "Unregistered attributes in transaction: "
                           (pr-str unregistered)
                           ". Register them with seon.schema/register! first.")
                      {::error         :seon.db/unregistered-attrs
                       ::unregistered  unregistered})))))

(defn- truncate-value
  "Truncate a value's `pr-str` representation to 100 chars for error
   messages — keeps error payloads readable when a malformed value is
   large (e.g. a stringified pull pattern)."
  [v]
  (let [s (pr-str v)]
    (if (> (count s) 100)
      (str (subs s 0 97) "...")
      s)))

(defn- validate-entity-values!
  "Validate each `[attr v]` pair in an entity map against its registered
   Malli schema. Skips system attrs and unregistered attrs (the latter
   should have been caught by `validate-attrs!`)."
  [entity]
  (doseq [[attr val] entity]
    (when (and (not (system-attr? attr))
               (schema/registered? attr))
      (when-not (m/validate attr val)
        (throw (ex-info (str "Malli validation failed for " attr
                             ": expected " (pr-str (schema/schema-definition attr))
                             ", got " (truncate-value val))
                        {::error              :seon.db/invalid-value
                         ::attr               attr
                         ::expected-schema    (schema/schema-definition attr)
                         ::actual-value       val
                         ::malli-explanation  (m/explain attr val)}))))))

(defn- validate-values!
  "Walk tx-data and validate every entity map. Vector tuple forms
   (`[:db/add ...]`, `[:db/retract ...]`) carry only one attribute and
   are best validated through their declared Malli schema by the caller;
   this gate doesn't try to type-check vector tuples (the JVM impl makes
   the same call)."
  [tx-data]
  (doseq [datum tx-data]
    (when (map? datum)
      (validate-entity-values! datum))))

(defn- resolve-conn
  "Resolve a caller-supplied or default `*conn*`. Throws a clear error if
   neither is set — that almost always means `seon.db/*conn*` hasn't been
   bound at the session-flow boundary yet, or you're calling from outside
   a session scope."
  [conn]
  (or conn
      (throw (ex-info
               (str "seon.db: *conn* is unbound and no :seon.db/conn was "
                    "passed. Bind via session-flow setup, or pass "
                    "::db/conn explicitly.")
               {::error :seon.db/no-conn}))))

;; ---------------------------------------------------------------------------
;; Public write path
;; ---------------------------------------------------------------------------

(defn ^:async transact!
  "Commit tx-data to the agent's conn. Map-in / map-out.

   Validates synchronously BEFORE reaching datahike:
     1. every non-`:db/*` attribute in tx-data is registered;
     2. every value in an entity-map form satisfies its attribute's
        registered Malli schema.

   Validation failures throw `ex-info` immediately (these are local
   programmer-mistakes the agent boundary catches separately).

   Returns a Promise resolving to:
     {:seon.db/ok? true  :seon.db/tx-report <datahike report>}   ; ok
     {:seon.db/ok? false :seon.db/error <seon.error/->map e>}    ; fail

   Datahike-side commit failures come back as `:ok? false`, never thrown —
   this is the boundary layer (spec-02 §2.5 safe-by-default).

   Example:

     (require '[seon.db :as db] '[seon.schema :as schema])
     (schema/register! ::name :string)
     (schema/register! ::rank :int)
     (let [r (await (db/transact! {::db/tx-data [{::name \"Alpha\" ::rank 1}]}))]
       (println r))"
  {:malli/schema [:=> [:cat ::transact-request] [:fn some?]]}
  [{::keys [tx-data opts conn] :or {conn *conn*}}]
  (let [c     (resolve-conn conn)
        attrs (extract-tx-attrs tx-data)]
    (validate-attrs! attrs)
    (validate-values! tx-data)
    (try
      (let [report (if opts
                     (await (d/transact! c tx-data opts))
                     (await (d/transact! c tx-data)))]
        {::ok? true ::tx-report report})
      (catch :default e
        {::ok?  false
         ::error (error/->map e)}))))

;; ---------------------------------------------------------------------------
;; Public read path — synchronous over a db value (datahike-cljs is sync
;; once you have a db value; `@conn` is the cheap deref). When V1's kabel
;; replica lands, reads still resolve against the local replica and stay
;; synchronous; the API doesn't change.
;; ---------------------------------------------------------------------------

(defn query
  "Run a Datalog query. Caller may pass `::db/db` (a db value) directly;
   otherwise reads `@conn` from the resolved `*conn*`. Returns the query
   result set.

   Example:

     (db/query
       {::db/query '[:find ?n :where [?e ::name ?n]]})"
  {:malli/schema [:=> [:cat ::query-request] :any]}
  [{::keys [query args db conn] :or {conn *conn* args []}}]
  (let [db (or db @(resolve-conn conn))]
    (apply d/q query db args)))

(defn pull
  "Pull an entity by ref using the given pull pattern. Sync. Returns the
   pulled map (or nil if `ref` doesn't resolve)."
  {:malli/schema [:=> [:cat ::pull-request] :any]}
  [{::keys [pull-pattern ref db conn] :or {conn *conn*}}]
  (let [db (or db @(resolve-conn conn))]
    (d/pull db pull-pattern ref)))

(defn entity
  "Look up an entity by eid or lookup-ref. Sync. Returns a datahike entity
   (lazy map-like)."
  {:malli/schema [:=> [:cat ::entity-request] :any]}
  [{::keys [ref db conn] :or {conn *conn*}}]
  (let [db (or db @(resolve-conn conn))]
    (d/entity db ref)))

;; ---------------------------------------------------------------------------
;; Listener machinery
;;
;; Datahike's native `listen!` stores callbacks in a per-conn atom keyed by
;; an opaque key. Same key replaces (idempotent), distinct keys coexist as
;; independent listeners that EACH receive every tx-report.
;;
;; We wrap user-supplied handlers with a transformer that pre-computes the
;; common shape — decoded datoms, attr-grouped index, pre-resolved :db /
;; :db-before — so handlers don't reach to `*conn*` and don't recompute
;; the same group-by N times. Handlers receive a single map argument with
;; everything they might want; they pick what they use and ignore the rest.
;;
;; The Shape B trigger dispatcher (`seon.trigger`, V0-B-5) is a single call
;; to `listen!` under a stable key; its handler reads `:seon.trigger/entity`
;; records from the DB and fans out by signature. The primitive here is
;; equally usable for ad-hoc reactions, debug taps, and ops tooling — agents
;; can call `listen!`/`unlisten!` directly when they don't need the trigger
;; registry's data-driven shape.
;; ---------------------------------------------------------------------------

(defn- datom->map
  "Decode a datahike Datom into a fully-namespaced plain map. We re-emit
   under `:seon.db/*` rather than passing datahike's positional Datom
   record through, so handler bodies destructure with the same
   namespaced shape the rest of seon.db uses."
  [datom]
  {::e      (:e datom)
   ::a      (:a datom)
   ::v      (:v datom)
   ::tx     (:tx datom)
   ::added? (boolean (:added datom))})

(defn- build-handler-input
  "Build the rich handler input map from a raw datahike tx-report. Called
   once per listener invocation. Cheap: a single mapv + group-by over
   :tx-data. Output keys are all `:seon.db/*` so handler code can
   destructure with `::db/keys [...]`."
  [raw-tx-report]
  (let [datoms (mapv datom->map (:tx-data raw-tx-report))]
    {::tx-report  raw-tx-report
     ::db         (:db-after raw-tx-report)
     ::db-before  (:db-before raw-tx-report)
     ::datoms     datoms
     ::attr-index (group-by ::a datoms)}))

(defn listen!
  "Install a tx-listener on the conn. Map-in / map-out. SAFE BY DEFAULT
   per spec-02 §2.5 — the handler's sync throws are caught, and if the
   handler returns a rejecting Promise, `.catch` swallows it. Neither
   takes down the pod. Errors are logged via `js/console.warn`.

   `::db/handler` is a fn-of-one-map. It receives:

     {:seon.db/tx-report   <raw datahike report — escape hatch>
      :seon.db/db          <:db-after, ready to query>
      :seon.db/db-before   <:db-before, for change-detection>
      :seon.db/datoms      [{:seon.db/e :seon.db/a :seon.db/v
                             :seon.db/tx :seon.db/added?} ...]
      :seon.db/attr-index  {:my.ns/attr [datoms-touching-it ...] ...}}

   The `:my.ns/attr` keys of `::db/attr-index` are user-domain attributes
   (whatever attr appeared in the tx); the values are vectors of
   `::db/datom` maps.

   If the handler returns a sync value, transact blocks until it returns
   (back-pressure preserved). If it returns a Promise, transact resolves
   immediately and the listener completes fire-and-forget. Use
   `listen-sync!` / `listen-async!` aliases below to make the intent
   explicit at the call site.

   If the caller doesn't supply `::db/key`, a fresh `random-uuid` is
   generated. Same key on subsequent calls replaces the prior handler.

   Returns `{:seon.db/key <key>}` — the key suitable for `unlisten!`."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [{::keys [handler key conn] :or {conn *conn*}}]
  (let [c (resolve-conn conn)
        k (or key (random-uuid))]
    (d/listen c k
              (fn [raw-tx-report]
                (try
                  (let [input  (build-handler-input raw-tx-report)
                        result (handler input)]
                    (if (instance? js/Promise result)
                      (.catch result
                              (fn [err]
                                (js/console.warn "[seon.db/listen!" (pr-str k)
                                                 "] async-rejected:"
                                                 (error/->message err))))
                      result))
                  (catch :default e
                    (js/console.warn "[seon.db/listen!" (pr-str k) "] threw:"
                                     (error/->message e))
                    nil))))
    {::key k}))

(defn listen-sync!
  "Intent-revealing alias for [[listen!]]. Use when the handler is
   intentionally sync and should gate transactions via back-pressure."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [request]
  (listen! request))

(defn listen-async!
  "Intent-revealing alias for [[listen!]]. Use when the handler returns
   a Promise (fire-and-forget — transact does not wait)."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [request]
  (listen! request))

(defn unlisten!
  "Remove a listener by key. Returns `{:seon.db/ok? true}`. Idempotent —
   unknown keys are silently no-op (matches datahike upstream)."
  {:malli/schema [:=> [:cat ::unlisten-request] ::unlisten-response]}
  [{::keys [key conn] :or {conn *conn*}}]
  (let [c (resolve-conn conn)]
    (d/unlisten c key)
    {::ok? true}))
