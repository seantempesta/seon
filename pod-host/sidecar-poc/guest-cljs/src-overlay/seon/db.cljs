(ns seon.db
  "Sidecar overlay for V0's `seon.db`. Shadows `src/seon/db.cljs` when
   the `:cljs-sidecar` alias's `src-overlay` path is on the classpath
   ahead of `src/`.

   Surface compatibility: maintains the V0 public API shape — map-in /
   map-out, `:seon.db/*` namespaced keys, `*conn*` dynamic binding,
   `with-tx-context`, `with-agent`, transact/query/pull/entity/listen/
   unlisten. Underneath, every operation routes through
   `sidecar-poc.datahike` to the JVM writer via WIT.

   Differences from V0 (documented honestly):

   - `*conn*` is a single guest-wide handle wrapping the sidecar
     connection (created lazily by `connect!`); it does NOT carry a
     datahike conn atom. Code that derefs `@*conn*` and threads the db
     value into multiple reads still works — we return the wrapped conn
     itself, and `query`/`pull`/`entity` accept it transparently.

   - `with-tx-context` / `with-agent` use plain `^:dynamic` binding
     instead of `node:async_hooks/AsyncLocalStorage`. Under wasm-rquickjs
     `node:async_hooks` is not available, and the guest event loop is a
     single QuickJS fiber per Store anyway — there's no concurrent-await
     fan-out for a Var to clobber across.

   - The Malli→datahike schema bridge is not reproduced here. The
     sidecar writer owns schema installation; the guest just calls
     `transact!` with already-installed attrs. V0 code paths that
     install schemas at boot via `assert-preconditions!` are not
     exercised under the sidecar today — schema is JVM-installed.

   - Listener handler input keys MATCH V0's `:seon.db/db`,
     `:seon.db/db-before`, `:seon.db/datoms`, `:seon.db/attr-index`,
     `:seon.db/tx-report`. The `:db` value is the wrapped conn (not a
     datahike db value); the overlay's `query` treats this as
     'current snapshot at call time'. `:db-before` is nil today — the
     sidecar pub event doesn't deliver a pre-commit db handle.
     `:datoms` are decoded from `tx-data` shipped on the pub event."
  (:require
    [sidecar-poc.datahike :as sd]
    [malli.core :as m]
    [seon.error :as error]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Schemas — registered at namespace load. Mirrors V0's `seon.db` registration
;; surface so callers that destructure `:seon.db/*` keys see the same shape.
;; ---------------------------------------------------------------------------

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
   [::error     {:optional true} :any]])

(schema/register! ::query-request   [:map [::query :any]
                                     [::args  {:optional true} [:vector :any]]
                                     [::db    {:optional true} :any]
                                     [::conn  {:optional true} ::conn]])

(schema/register! ::pull-request    [:map [::pull-pattern :any]
                                     [::ref          :any]
                                     [::db           {:optional true} :any]
                                     [::conn         {:optional true} ::conn]])

(schema/register! ::entity-request  [:map [::ref :any]
                                     [::db {:optional true} :any]
                                     [::conn {:optional true} ::conn]])

(schema/register! ::datom [:map
                           [::e :int]
                           [::a :keyword]
                           [::v :any]
                           [::tx :int]
                           [::added? :boolean]])

(schema/register! ::handler-input
                  [:map
                   [::tx-report :any]
                   [::db        :any]
                   [::db-before {:optional true} :any]
                   [::datoms    [:vector ::datom]]
                   [::attr-index [:map-of :keyword [:vector ::datom]]]])

(schema/register! ::listen-request [:map [::handler [:fn fn?]]
                                    [::key {:optional true} :any]
                                    [::conn {:optional true} ::conn]])
(schema/register! ::listen-response [:map [::key :any]])
(schema/register! ::unlisten-request [:map [::key :any]
                                      [::conn {:optional true} ::conn]])
(schema/register! ::unlisten-response [:map [::ok? :boolean]])

;; Tx-meta scalar attrs — V0's causality bundle.
(schema/register! ::agent-id        :seon.db/id)
(schema/register! ::session-id      :seon.db/id)
(schema/register! ::turn-id         :seon.db/id)
(schema/register! ::eval-id         :seon.db/id)
(schema/register! ::origin          [:enum :user :agent :system :replay])
(schema/register! ::replay?         :boolean)
(schema/register! ::resume-marker?  :boolean)

;; ---------------------------------------------------------------------------
;; ID generation — bit-for-bit V0 compatible.
;; ---------------------------------------------------------------------------

(def ^:private id-letters
  "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")

(defn- id-pad-2 [n]
  (if (< n 10) (str "0" n) (str n)))

(defn- id-rand-letter []
  (nth id-letters (rand-int 52)))

(defn new-id! []
  (let [d        (js/Date.)
        time-str (str (id-pad-2 (mod (.getFullYear d) 100))
                      (id-pad-2 (inc (.getMonth d)))
                      (id-pad-2 (.getDate d))
                      (id-pad-2 (.getHours d))
                      (id-pad-2 (.getMinutes d)))
        rand-str (str (id-rand-letter) (id-rand-letter) (id-rand-letter))]
    (str rand-str "-" time-str)))

(defn id->time-str [id]
  (when (and (string? id) (= 14 (count id)) (= \- (nth id 3)))
    (subs id 4)))

;; ---------------------------------------------------------------------------
;; *conn* + dynamic-binding-based context (replaces V0's AsyncLocalStorage)
;; ---------------------------------------------------------------------------

(def ^:dynamic *conn*
  "The agent's connection. Bound by the boot pipeline (or by ad-hoc test
   code) to a `sidecar-poc.datahike` conn wrapper. Reads default to
   `(query @*conn*)` but the overlay's `query`/`pull`/`entity` accept
   the wrapper itself transparently."
  nil)

(def ^:dynamic ^:private *tx-context* nil)
(def ^:dynamic ^:private *agent-id*   nil)

(defn current-tx-context [] *tx-context*)
(defn current-agent-id   [] *agent-id*)

(defn with-agent
  "V0 takes a 0-arg thunk; we do the same. Promise return value carries the
   binding through `await` because of `binding-frame` snapshotting added by
   the partial-cps fork of CLJS — verified in V0 probe 14."
  [agent-id f]
  (binding [*agent-id* agent-id] (f)))

(defn with-tx-context
  "Merge `ctx-map` on top of the existing tx-context for the dynamic extent
   of `f`. Returns whatever `f` returns; carries through awaits like
   `with-agent`."
  [ctx-map f]
  (let [merged (merge *tx-context* ctx-map)]
    (binding [*tx-context* merged] (f))))

;; ---------------------------------------------------------------------------
;; Validation gate — borrowed verbatim from V0. Pure CLJS, no Node deps.
;; ---------------------------------------------------------------------------

(defn- system-attr? [k]
  (and (keyword? k) (= "db" (namespace k))))

(defn- extract-tx-attrs [tx-data]
  (into #{}
        (mapcat (fn [datum]
                  (cond
                    (map? datum) (keys datum)
                    (and (vector? datum) (>= (count datum) 3)) [(nth datum 2)]
                    :else nil)))
        tx-data))

(defn- truncate-value [v]
  (let [s (pr-str v)]
    (if (> (count s) 100) (str (subs s 0 97) "...") s)))

(defn- validate-attrs! [attrs]
  (let [domain-attrs (remove system-attr? attrs)
        unregistered (into [] (remove schema/registered?) domain-attrs)]
    (when (seq unregistered)
      (throw (ex-info (str "Unregistered attributes in transaction: "
                           (pr-str unregistered)
                           ". Register them with seon.schema/register! first.")
                      {::error        :seon.db/unregistered-attrs
                       ::unregistered unregistered})))))

;; Minimal validate-values! — skip ref-arity branch checking (the sidecar
;; writer will catch ref-type mismatches at JVM tx time anyway, and we'd
;; pull in the bulk of V0's bridge to do it locally).
(defn- validate-values! [tx-data]
  (doseq [datum tx-data]
    (when (map? datum)
      (doseq [[attr v] datum]
        (when (and (not (system-attr? attr))
                   (schema/registered? attr)
                   ;; ref-typed attrs: skip — sidecar resolves these
                   ;; server-side via tempids/lookup-refs/nested maps.
                   (not (and (vector? v) (every? some? v)))
                   (not (map? v)))
          (when-not (m/validate attr v)
            (throw (ex-info (str "Malli validation failed for " attr
                                 ": got " (truncate-value v))
                            {::error           :seon.db/invalid-value
                             ::attr            attr
                             ::actual-value    v}))))))))

(defn- resolve-conn [conn]
  (or conn
      (throw (ex-info "seon.db: *conn* is unbound and no :seon.db/conn was passed."
                      {::error :seon.db/no-conn}))))

(defn- assert-invocation-shape! [arg]
  (cond
    (not (map? arg))
    (throw (ex-info (str "seon.db/transact! expects ONE map argument with "
                         "`:seon.db/tx-data`. Got: " (truncate-value arg))
                    {::error :seon.db/invalid-invocation-shape
                     ::actual-value arg}))
    (not (contains? arg ::tx-data))
    (throw (ex-info (str "seon.db/transact!: missing `:seon.db/tx-data` key. "
                         "Got keys: " (pr-str (vec (keys arg))))
                    {::error   :seon.db/invalid-invocation-shape
                     ::missing :seon.db/tx-data}))))

(defn- merge-tx-context-into-opts [opts]
  (let [ctx       *tx-context*
        agent-id  *agent-id*
        als-meta  (cond-> {}
                    agent-id (assoc ::agent-id agent-id))
        merged    (merge als-meta ctx)]
    (cond
      (and (nil? opts) (empty? merged)) nil
      (empty? merged)                   opts
      :else (update (or opts {}) :tx-meta #(merge merged %)))))

;; ---------------------------------------------------------------------------
;; Public write path
;; ---------------------------------------------------------------------------

(defn ^:async transact!
  {:malli/schema [:=> [:cat ::transact-request] [:fn some?]]}
  [arg]
  (assert-invocation-shape! arg)
  (let [{::keys [tx-data opts conn] :or {conn *conn*}} arg
        c           (resolve-conn conn)
        attrs       (extract-tx-attrs tx-data)
        merged-opts (merge-tx-context-into-opts opts)]
    (validate-attrs! attrs)
    (validate-values! tx-data)
    (try
      (let [tx-meta (:tx-meta merged-opts)
            report  (sd/transact! c (cond-> {:tx-data tx-data}
                                      tx-meta (assoc :tx-meta tx-meta)))]
        {::ok? true ::tx-report report})
      (catch :default e
        {::ok? false ::error (error/->map e)}))))

;; ---------------------------------------------------------------------------
;; Public read path — sync calls through sidecar-poc.datahike (sync against
;; latest writer-known basis-t).
;; ---------------------------------------------------------------------------

(defn query
  {:malli/schema [:=> [:cat ::query-request] :any]}
  [{::keys [query args db conn] :or {conn *conn* args []}}]
  (let [c (or db (resolve-conn conn))]
    (apply sd/q query c args)))

(defn pull
  {:malli/schema [:=> [:cat ::pull-request] :any]}
  [{::keys [pull-pattern ref db conn] :or {conn *conn*}}]
  (let [c (or db (resolve-conn conn))]
    (sd/pull c pull-pattern ref)))

(defn entity
  "Returns an EAGER realized map (sidecar `entity-pull` to depth 1).
   V0's `d/entity` was lazy; the audit confirmed all 15 V0 call sites
   read shallow attrs only, so eager is behavior-equivalent."
  {:malli/schema [:=> [:cat ::entity-request] :any]}
  [{::keys [ref db conn] :or {conn *conn*}}]
  (let [c (or db (resolve-conn conn))]
    (sd/entity c ref)))

;; ---------------------------------------------------------------------------
;; Listener machinery — fan out from sidecar tx events.
;; ---------------------------------------------------------------------------

(defn- datom->map [d]
  ;; sidecar tx-data is [e a v t op] vectors; `a` is a string like
  ;; "task/id" (the EDN keyword printer's stringification). We rebuild as
  ;; a keyword to match V0's `::a :keyword` shape.
  (let [[e a v t op] d
        a-kw (if (string? a) (keyword a) a)]
    {::e e ::a a-kw ::v v ::tx t ::added? (boolean op)}))

(defn- build-handler-input [ev conn]
  (let [datoms (mapv datom->map (:tx-data ev))]
    {::tx-report  ev
     ::db         conn          ; pass conn through; downstream uses sync overlay
     ::db-before  nil           ; sidecar pub event doesn't ship db-before
     ::datoms     datoms
     ::attr-index (group-by ::a datoms)}))

(defn listen!
  {:malli/schema [:=> [:cat ::listen-request] ::listen-response]}
  [{::keys [handler key conn] :or {conn *conn*}}]
  (let [c (resolve-conn conn)
        k (or key (random-uuid))]
    (sd/listen! c k
                (fn [ev]
                  (try
                    (let [input  (build-handler-input ev c)
                          result (handler input)]
                      (if (instance? js/Promise result)
                        (.catch result (fn [err]
                                         (js/console.warn "[seon.db/listen!" (pr-str k) "] async-rejected:" (error/->message err))))
                        result))
                    (catch :default e
                      (js/console.warn "[seon.db/listen!" (pr-str k) "] threw:" (error/->message e))
                      nil))))
    {::key k}))

(defn listen-sync!  [req] (listen! req))
(defn listen-async! [req] (listen! req))

(defn unlisten!
  {:malli/schema [:=> [:cat ::unlisten-request] ::unlisten-response]}
  [{::keys [key conn] :or {conn *conn*}}]
  (let [c (resolve-conn conn)]
    (sd/unlisten! c key)
    {::ok? true}))

;; ---------------------------------------------------------------------------
;; Bridge / preconditions — present as stubs so V0 callers don't break.
;; ---------------------------------------------------------------------------

(defn malli->datahike-attr
  "STUB — under the sidecar, schema installation happens JVM-side via
   the writer. The overlay does not synthesize datahike attr maps locally."
  [_attr]
  (throw (ex-info "malli->datahike-attr not supported under sidecar overlay; install schema JVM-side."
                  {::error :seon.db/bridge-stub})))

(defn malli->datahike-schema [_attrs]
  (throw (ex-info "malli->datahike-schema not supported under sidecar overlay."
                  {::error :seon.db/bridge-stub})))

(defn tx-meta-datahike-schema []
  ;; Used by V0's boot to install tx-meta attrs JVM-side. No-op under sidecar.
  nil)

(defn assert-preconditions!
  "STUB — the sidecar writer enforces preconditions JVM-side."
  ([_conn] true)
  ([_conn _opts] true))
