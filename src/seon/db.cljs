(ns seon.db
  "Database access. This is the only API you need to read, write, and
   react to the database.

   ## Your universe is one connection

   You have exactly ONE database. `seon.db/*conn*` is bound for you
   before your code runs; never thread it through your own calls, never
   open another conn. Every fn here defaults `:seon.db/conn` to
   `*conn*`. Alias `seon.db` as `db` and write `::db/foo`:

     (require '[seon.db :as db])
     (db/query     {::db/query '[:find ?n :where [_ ::name ?n]]})
     (db/transact! {::db/tx-data [{::name \"Alpha\" ::rank 1}]})

   Every map key in / out of seon.db is fully namespaced under
   `:seon.db/*` — that's what lets one Datalog query join the data in
   the database to the functions that operate on it.

   ## Register schemas before you transact

   `transact!` refuses any tx touching an attribute it doesn't
   recognize — register first:

     (require '[seon.schema :as schema])
     (schema/register! ::name :string)
     (schema/register! ::rank :int)
     (db/transact! {::db/tx-data [{::name \"Alpha\" ::rank 1}]})

   `:db/*` system attributes bypass the gate. Vector tuples
   (`[:db/add e a v]`) get attribute checks only; entity maps get full
   Malli value validation.

   ## Reads are synchronous, writes return a Promise

   `query`, `pull`, `entity` resolve against the current db value
   (`@*conn*`) — compose them in straight-line code. `transact!` is
   `^:async`; await it and you get an ENVELOPE, never a throw:

     (let [{::db/keys [ok? tx-report error]}
           (await (db/transact! {::db/tx-data [...]}))]
       (if ok? (handle-success tx-report) (handle-failure error)))

   ## Reactions: listen!/unlisten! by key

   `listen!` installs a tx-listener under a key. Distinct keys coexist
   and each receives every tx-report; the same key replaces; `unlisten!`
   retracts by key. The handler gets one rich map (see [[listen!]])
   including `:seon.db/db`, the exact post-commit db value — no reaching
   back to `*conn*`, no stale reads.

   The canonical reaction is the agent's own wake-up: a listener over
   newly-added `:seon.message/to` datoms targeting me (from ≠ me, so my
   own replies never re-trigger me; agent↔agent chains are bounded by
   `:seon.message/hops`). `seon.trigger/register!` is the data-driven
   layer over this primitive — triggers persisted as DB entities."
  (:require
    [datahike.api :as d]
    [seon.db.internal :as internal]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Schemas — every request/response shape, registered at namespace load.
;; ---------------------------------------------------------------------------

(schema/register! ::tx-data [:vector :any])
(schema/register! ::opts :map)
(schema/register! ::conn :any)
(schema/register! ::tx-meta :map)   ; positional 3-arity convenience slot

(schema/register!
  ::transact-request
  [:map
   [::tx-data ::tx-data]
   [::opts    {:optional true} ::opts]
   [::conn    {:optional true} ::conn]])

(schema/register!
  ::error
  [:map
   [:seon.error/message :string]
   [:seon.error/data    {:optional true} :map]
   [:seon.error/ex-data {:optional true} :map]
   [:seon.error/stack   {:optional true} :string]
   [:seon.error/cause   {:optional true} :map]
   [:seon.error/raw     {:optional true} :any]
   [:seon.error/truncated {:optional true} :boolean]])

(schema/register!
  ::transact-response
  [:or
   [:map
    [::ok?       [:= true]]
    [::tx-report :any]]
   [:map
    [::ok?       [:= false]]
    [::error     ::error]
    ;; When the substrate translated a cryptic datahike message into a
    ;; guiding one, the ORIGINAL message is preserved here verbatim.
    [::raw-error {:optional true} :string]]])

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

;; The ONE canonical "a datahike db value" shape — referenced by every
;; positional :db slot below (shared-shape rule; never inline [:fn map?]).
(schema/register! ::db-val [:fn map?])

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

;; Tx-meta attrs (v1.md §2.3) — the causality bundle auto-merged into
;; every tx (see [[with-tx-context]]). Id scalars reference the canonical
;; :seon.db/id shape registered in seon.schema.
(schema/register! ::agent-id        :seon.db/id)
(schema/register! ::session-id      :seon.db/id)
(schema/register! ::turn-id         :seon.db/id)
(schema/register! ::eval-id         :seon.db/id)
(schema/register! ::origin          [:enum :user :agent :system :replay :substrate-seed :test-run])
(schema/register! ::replay?         :boolean)
(schema/register! ::resume-marker?  :boolean)

;; ---------------------------------------------------------------------------
;; ID generation
;; ---------------------------------------------------------------------------

(def ^:private id-letters
  "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

(defn- id-pad-2 [n]
  (if (< n 10) (str "0" n) (str n)))

(defn new-id!
  "Fresh 14-char LLM-readable id, `<3-letter-random>-<YYMMDDHHmm>`,
   e.g. `Kpx-2605232138`. Datahike's tx-id remains the canonical
   creation order for sub-minute sorting."
  []
  (let [d        (js/Date.)
        time-str (str (id-pad-2 (mod (.getFullYear d) 100))
                      (id-pad-2 (inc (.getMonth d)))  ; JS month 0-based
                      (id-pad-2 (.getDate d))
                      (id-pad-2 (.getHours d))
                      (id-pad-2 (.getMinutes d)))
        rand-str (apply str (repeatedly 3 #(nth id-letters (rand-int 52))))]
    (str rand-str "-" time-str)))

(defn id->time-str
  "Extract the YYMMDDHHmm portion of an id for time sorting /
   comparison. Returns nil if the id doesn't match the expected shape."
  [id]
  (when (and (string? id) (= 14 (count id)) (= \- (nth id 3)))
    (subs id 4)))

;; ---------------------------------------------------------------------------
;; The agent's universe + fiber-local context scopes
;; ---------------------------------------------------------------------------

(def ^:dynamic *conn*
  "The runtime's datahike connection. Bound at session start; never
   threaded through agent call sites. Reads default to `@*conn*` (a db
   value); writes route through this conn's writer. All sessions for
   the same user share this conn — sessions are entities in it, not
   partitions of it."
  nil)

(defn current-tx-context
  "The active tx-context map, or nil outside a [[with-tx-context]]
   scope. Fiber-local across awaits (AsyncLocalStorage), safe under
   concurrent agents. Auto-merged into every `transact!`'s `:tx-meta`;
   explicit call-site `:tx-meta` keys win per-key."
  []
  (internal/current-tx-context))

(defn current-agent-id
  "The active agent-id (string), or nil outside a [[with-agent]] scope.
   Fiber-local across awaits. The standard accessor for any code that
   needs to know whose universe it's running in."
  []
  (internal/current-agent-id))

(defn with-agent
  "Establish an agent-id scope for the dynamic extent of `f` (a 0-arg
   fn). Inside `f` — including across `await`s and any Promises it
   returns — `(current-agent-id)` returns `agent-id`. Nesting: the
   inner scope wins, the outer restores on exit."
  [agent-id f]
  (internal/run-with-agent agent-id f))

(defn with-tx-context
  "Establish a tx-context for the dynamic extent of `f` (a 0-arg fn);
   nested calls MERGE. Returns whatever `f` returns (context propagates
   across `await` points). Keys are typically the 7 `:seon.db/*`
   tx-meta attrs registered above; any registered scalar attr works.

     (db/with-tx-context
       {::db/origin :agent ::db/agent-id agent-id}
       (fn [] (db/transact! {::db/tx-data [...]})))   ; auto-tagged"
  [ctx-map f]
  (internal/run-with-tx-context ctx-map f))

;; ---------------------------------------------------------------------------
;; Write path
;; ---------------------------------------------------------------------------

(defn ^:async transact!
  "Commit tx-data. Two call shapes:

   - map-in / map-out (preferred):
       (db/transact! {::db/tx-data [{::name \"A\"}]
                      ::db/opts {:tx-meta {…}}   ; optional
                      ::db/conn <conn>})          ; optional, defaults *conn*
   - positional, mirroring datahike `(d/transact! conn tx-data)` — conn
     FIRST and explicit, with a 3-arity tx-meta convenience:
       (db/transact! <conn> [{::name \"A\"}])
       (db/transact! <conn> [{::name \"A\"}] {:source :import})

   Both shapes resolve to the SAME envelope. SAFE BY DEFAULT: this
   never throws into your eval — every failure (bad invocation shape,
   unregistered attr, value fails its schema, datahike commit
   explosion) returns as data:

     {::db/ok? true  ::db/tx-report <datahike report>}   ; success
     {::db/ok? false ::db/error <error map>}             ; failure
     ;; + ::db/raw-error <original message> when the substrate
     ;; translated a cryptic datahike error into a guiding one

   The error's `:seon.error/data` carries `:seon.error/kind` —
   `:user-input` (fix tx-data and retry) vs `:substrate-bug` (the pod
   survived; report it, don't retry blindly).

   Before committing it validates shape, attrs, and values; installs
   datahike schema for any newly-registered attr; and auto-merges the
   active [[with-tx-context]] / [[with-agent]] context into `:tx-meta`."
  ;; NOTE: ^:async fns are skipped by instrumentation today, so this
  ;; schema is the discoverable contract; the internal guards enforce.
  {:malli/schema
   [:function
    [:=> [:cat ::transact-request] ::transact-response]
    [:=> [:catn [::conn ::conn] [::tx-data ::tx-data]] ::transact-response]
    [:=> [:catn [::conn ::conn] [::tx-data ::tx-data] [::tx-meta ::tx-meta]]
         ::transact-response]]}
  [& call-args]
  (try
    (let [arg (internal/normalize-transact-args call-args)]
      (internal/assert-invocation-shape! arg)
      ;; AWAIT is load-bearing: rejections must resolve to the envelope.
      (await (internal/transact!* (update arg ::conn #(or % *conn*)))))
    (catch :default e
      (internal/commit-error-envelope e))))

;; ---------------------------------------------------------------------------
;; Read path — synchronous over a db value. Each op has a map-in arity
;; AND a datahike-shaped positional arity (dispatch is by arg count; the
;; positional db slot is REQUIRED and explicit — no ambient *conn*).
;; ---------------------------------------------------------------------------

(defn query
  "Run a Datalog query. Two call shapes:

   - map-in:  (db/query {::db/query '[:find ?n :where [?e ::name ?n]]
                         ::db/db <db> | ::db/conn <conn>   ; default *conn*
                         ::db/args [...]})                  ; extra :in inputs
   - positional, mirroring datahike `(d/q query db & inputs)`:
       (db/query '[:find ?n :where [?e ::name ?n]] <db>)
       (db/query '[:find ?n :in $ ?t :where …] <db> \"Alice\")"
  ;; Pure-variadic body so CLJS malli.instrument wraps every arity.
  {:malli/schema
   [:function
    [:=> [:cat ::query-request] :any]
    [:=> [:catn [::query [:or [:vector :any] :map :string]]
                [::db    ::db-val]] :any]
    [:=> [:catn [::query [:or [:vector :any] :map :string]]
                [::db    ::db-val]
                [::inputs [:+ :any]]] :any]]}
  [& args]
  (if (= 1 (count args))
    (let [{::keys [query args db conn] :or {conn *conn* args []}} (first args)
          db (or db @(internal/resolve-conn conn))]
      (apply d/q query db args))
    (let [[q db & inputs] args]
      (apply d/q q db inputs))))

(defn pull
  "Pull an entity by ref using a pull pattern. Sync. Returns the pulled
   map, or nil if the ref doesn't resolve.

   - map-in:     (db/pull {::db/pull-pattern '[*] ::db/ref eid})
   - positional, mirroring datahike: (db/pull <db> selector eid)"
  {:malli/schema
   [:function
    [:=> [:cat ::pull-request] :any]
    [:=> [:catn [::db ::db-val] [::selector [:vector :any]] [::eid :any]] :any]]}
  ([req]
   (let [{::keys [pull-pattern ref db conn] :or {conn *conn*}} req
         db (or db @(internal/resolve-conn conn))]
     (d/pull db pull-pattern ref)))
  ([db selector eid]
   (d/pull db selector eid)))

(defn entity
  "Look up an entity by eid or lookup-ref. Sync. Returns a datahike
   entity (lazy map-like).

   - map-in:     (db/entity {::db/ref [::name \"Alpha\"]})
   - positional, mirroring datahike: (db/entity <db> eid)"
  {:malli/schema
   [:function
    [:=> [:cat ::entity-request] :any]
    [:=> [:catn [::db ::db-val] [::eid :any]] :any]]}
  ([req]
   (let [{::keys [ref db conn] :or {conn *conn*}} req
         db (or db @(internal/resolve-conn conn))]
     (d/entity db ref)))
  ([db eid]
   (d/entity db eid)))

;; ---------------------------------------------------------------------------
;; Listeners
;; ---------------------------------------------------------------------------

(defn listen!
  "Install a tx-listener. SAFE BY DEFAULT — handler throws / rejections
   are caught and logged, never crash the pod. `::db/handler` is a fn
   of one map:

     {:seon.db/tx-report   <raw datahike report — escape hatch>
      :seon.db/db          <post-commit db value, ready to query>
      :seon.db/db-before   <pre-commit db value, for change-detection>
      :seon.db/datoms      [{:seon.db/e :seon.db/a :seon.db/v
                             :seon.db/tx :seon.db/added?} ...]
      :seon.db/attr-index  {:my.ns/attr [datoms-touching-it ...] ...}}

   Sync handler return blocks transact (back-pressure); Promise return
   is fire-and-forget. Without `::db/key` a random-uuid is used; the
   same key replaces. Returns `{:seon.db/key <key>}` for [[unlisten!]]."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [{::keys [handler key conn] :or {conn *conn*}}]
  (let [c (internal/resolve-conn conn)
        k (or key (random-uuid))]
    (d/listen c k (internal/wrap-listen-handler k handler))
    {::key k}))

(defn listen-sync!
  "Intent-revealing alias for [[listen!]] (sync handler, back-pressure)."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [request]
  (listen! request))

(defn listen-async!
  "Intent-revealing alias for [[listen!]] (Promise handler, fire-and-forget)."
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [request]
  (listen! request))

(defn unlisten!
  "Remove a listener by key. Returns `{:seon.db/ok? true}`. Idempotent —
   unknown keys are a silent no-op."
  {:malli/schema [:=> [:cat ::unlisten-request] ::unlisten-response]}
  [{::keys [key conn] :or {conn *conn*}}]
  (let [c (internal/resolve-conn conn)]
    (d/unlisten c key)
    {::ok? true}))

;; ---------------------------------------------------------------------------
;; Schema-bridge + boot faces (impls in seon.db.internal)
;; ---------------------------------------------------------------------------

(defn malli->datahike-schema
  "Derive datahike attr declarations from seon.schema registrations.
   You normally never need this — `transact!` installs schema for
   registered attrs automatically."
  [attr-keys]
  (internal/malli->datahike-schema attr-keys))

(defn tx-meta-datahike-schema
  "Datahike schema entries for the 7 `:seon.db/*` tx-meta attrs."
  []
  (internal/tx-meta-datahike-schema))

(defn assert-preconditions!
  "Validate boot preconditions (conn has `:keep-history? true`; tx-meta
   attrs registered). Throws ex-info on failure. Called at agent boot."
  ([] (assert-preconditions! {}))
  ([{::keys [conn] :or {conn *conn*}}]
   (internal/assert-preconditions! conn)))
