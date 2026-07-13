(ns seon.db.replica
  "The pod's local Datahike replica of the authoritative JVM database.

   Reads dereference the shared immutable Konserve files locally. Writes use
   `seon.db.protocol` over the UDS transport and materialize the acknowledged
   database basis before returning. One persistent transaction feed advances
   native Datahike listeners; bounded history replay closes reconnect gaps.

   This namespace owns replica lifecycle and recovery policy. It does not
   invent protocol maps or transport framing."
  (:require
   [cljs.core.async :refer [promise-chan put! <! go]]
   [clojure.string :as str]
   [datahike.api :as d]
   [datahike.connector :as connector]
   [datahike.datom :as dd]
   [datahike.writer :as w]
   [seon.db.protocol :as protocol]
   [seon.db.transport.uds :as uds]
   [seon.platform :as platform]
   [seon.log :as log]
   [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(schema/register! ::socket-path [:string {:min 1}])
(schema/register! ::request-socket-path ::socket-path)
(schema/register! ::publish-socket-path ::socket-path)
(schema/register! ::database-name [:string {:min 1}])
(schema/register! ::database-id :uuid)
(schema/register! ::branch :keyword)
(schema/register! ::writer-backend :keyword)
(schema/register! ::basis-t [:int {:min 0}])
(schema/register!
 ::database-coordinate
 [:map
  [::database-name ::database-name]
  [::database-id ::database-id]
  [::branch ::branch]
  [::writer-backend ::writer-backend]])
(schema/register!
 ::progress-coordinate
 [:map
  [::database-coordinate ::database-coordinate]
  [::basis-t ::basis-t]])
;; The pod's datahike conn handle the listen attachment subscribes for — an
;; opaque runtime value (third-party boundary), hence :any.
(schema/register! ::conn :any)

;; A datahike config map — third-party boundary shape.
(schema/register! ::database-config-response :map)
(schema/register!
 ::database-config-request
 [:map [::database-id ::database-id]])
(schema/register!
 ::knn-search-request
 [:map
  [::protocol/query ::protocol/query]
  [::protocol/limit ::protocol/limit]
  [::protocol/entity-ids {:optional true} ::protocol/entity-ids]
  [::request-socket-path {:optional true} ::request-socket-path]])

;; ---------------------------------------------------------------------------
;; Cluster database identity and private Datahike config
;; ---------------------------------------------------------------------------

(def default-request-socket-path
  uds/default-request-socket-path)

(def default-publish-socket-path
  uds/default-publish-socket-path)

(def cluster-dir
  "The cluster's data dir — `SEON_CLUSTER_DIR`, default the live cluster.

   Everything per-cluster on disk (database, blobs) lives under it; the
   launcher (`bin/seon`, `bin/acme`, `bin/seon cluster create`) exports it."
  (or (platform/env-val "SEON_CLUSTER_DIR") "data/clusters/default"))

(def database-name
  "The pod's cluster name — the basename of [[cluster-dir]].

   One derivation supplies operation routing and transaction-feed filtering."
  (last (remove str/blank? (str/split cluster-dir #"/"))))

(def default-database-path
  (str cluster-dir "/db"))

(defn database-config
  "Build the private Datahike config for this read replica.

   The writer returns `database-id`; this process never duplicates its identity
   algorithm. Reads use the shared immutable file tree and writes route through
   the sole JVM authority.

   `:lock-blob? false` is REQUIRED for readers: konserve's sync read
   path takes a `.ksv.LOCK` by default and two sync readers race on the
   branch-root blob and throw (`:file-lock-acquisition-error`, found by
   Stage B oracle (d)). Lock-free reads are DIS-correct — the root is
   replaced by atomic rename, index nodes are content-addressed and
   immutable, and this peer's writes go over the wire, never through
   local konserve."
  {:malli/schema [:=> [:cat ::database-config-request]
                  ::database-config-response]}
  [{::keys [database-id]}]
  {:store               {:backend :file
                         :path    default-database-path
                         :id      database-id
                         :config  {:lock-blob? false}}
   :keep-history?       true
   :schema-flexibility  :write
   :writer              {:backend :seon.db.writer/remote
                         :socket-path default-request-socket-path}
   :allow-unsafe-config true})

;; ---------------------------------------------------------------------------
;; Boot gate — fail LOUD if the database writer is down. No dual backend: a
;; pod that can't reach its writer must not boot against a local writer.
;;
;; The ping retries within a fixed budget before the fail-loud throw:
;; `bin/seon up` brings the database server and pod up in order, but
;; the pod can exec before the writer's UDS socket accepts (or while a freshly
;; sha-bumped JVM warms up). Boot stays fail-loud, just not
;; fail-instant — after the budget the same error throws.
;; ---------------------------------------------------------------------------

(defn- sleep [ms]
  (js/Promise. (fn [res] (js/setTimeout res ms))))

(def ^:private ping-attempts 5)
(def ^:private ping-timeout-ms 2000)
(def ^:private ping-retry-delay-ms 500)
(def ^:private ensure-database-timeout-ms 15000)
(def ^:private transaction-timeout-ms 30000)
(def ^:private transaction-attempts 3)
(def ^:private replay-timeout-ms 30000)
(def ^:private feed-reconnect-delay-ms 2000)

(defn ^:async ^:private ping-once!
  "One ping rpc. Resolves to the reply map; throws on not-ok/transport."
  []
  (let [resp (await (uds/rpc {::uds/socket-path default-request-socket-path
                              ::uds/message (protocol/ping-request)
                              ::uds/timeout-ms ping-timeout-ms}))]
    (when-not (::protocol/success? resp)
      (throw (ex-info "Database writer ping failed."
                      {::resp resp})))
    resp))

(defn ^:async ping!
  "Ping the database writer with a fixed, bounded retry budget.

   Resolves to the reply map on success; throws a clear, actionable
   error once the budget is exhausted."
  ;; Resolves to a protocol response crossing the transport boundary.
  {:malli/schema [:=> [:cat] :any]}
  []
  (await
   ((fn ^:async attempt [n]
      (try
        (await (ping-once!))
        (catch :default e
          (if (< n ping-attempts)
            (do (js/console.warn
                 (str "[seon.db.replica] writer ping attempt " n "/" ping-attempts
                      " failed (" (or (.-message e) (str e))
                      ") — retrying in " ping-retry-delay-ms "ms"))
                (await (sleep ping-retry-delay-ms))
                (await (attempt (inc n))))
            (throw (ex-info
                    (str "The authoritative database writer is unreachable at "
                         default-request-socket-path " ("
                         (or (.-message e) (str e)) ") "
                         "after " n " attempts. "
                         "The pod boots only against the cluster database — there is "
                         "no local fallback. Start the database server with: "
                         "bin/seon up")
                    {::socket-path default-request-socket-path
                     ::attempts  n
                     :seon.error/kind :core-bug}))))))
    1)))

(defn ^:async ensure-database!
  "Ensure this pod's database is open on the authoritative writer.

   A freshly created database exists before the pod attaches its read replica.
   Throws on a not-ok reply (fail-loud, same posture as [[ping!]])."
  ;; Resolves to a protocol response crossing the transport boundary.
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [resp
        (await
         (uds/rpc
          {::uds/socket-path default-request-socket-path
           ::uds/message
           (protocol/ensure-database-request
            {::protocol/database-name database-name
             ::protocol/backend :file
             ::protocol/database-path default-database-path})
           ::uds/timeout-ms ensure-database-timeout-ms}))]
    (when-not (::protocol/success? resp)
      (throw (ex-info (str "Opening database " database-name " failed: "
                           (::protocol/error resp))
                      {::database-name database-name
                       ::resp resp
                       :seon.error/kind :core-bug})))
    resp))

(defn ^:async knn-search!
  "Ask the JVM database authority for nearest embedding neighbors.

   Returns the hits vector on success or the canonical failed protocol map.
   Query embedding and index access remain on the JVM."
  {:malli/schema [:=> [:cat ::knn-search-request] :any]}
  [{::protocol/keys [query limit entity-ids]
    ::keys [request-socket-path]
    :or {request-socket-path default-request-socket-path}}]
  (let [response
        (await
         (uds/rpc
          {::uds/socket-path request-socket-path
           ::uds/message
           (protocol/knn-search-request
            (cond-> {::protocol/database-name database-name
                     ::protocol/query query
                     ::protocol/limit limit}
              (seq entity-ids)
              (assoc ::protocol/entity-ids (vec entity-ids))))}))]
    (if (::protocol/success? response)
      (::protocol/hits response)
      response)))

;; ---------------------------------------------------------------------------
;; Protocol datom decode — transaction data is the native 5-vector
;; [e a v t added]. Under the uniform Transit frame a/v arrive native
;; (keyword attr, any value), so we reconstitute real Datahike datoms directly.
;; ---------------------------------------------------------------------------

(defn- transaction-datoms->datoms [transaction-data]
  (mapv (fn [[e a v t added]]
          (dd/datom e a v t added))
        transaction-data))

;; ---------------------------------------------------------------------------
;; RYOW deref — resolve a database value at-or-past `basis-t` from the replica.
;; Flush-before-ack (writer.cljc:108-134, probe-confirmed) makes attempt
;; #1 succeed; the bounded retry exists to FALSIFY that loudly, not hang.
;; ---------------------------------------------------------------------------

(defn- ryow-deref! [conn basis-t]
  (loop [attempt 1]
    (let [db @conn]
      (cond
        (>= (:max-tx db) basis-t) db
        (< attempt 10)            (recur (inc attempt))
        :else (throw (ex-info
                      "seon.db.replica: RYOW violated — deref never reached ack basis-t"
                      {::basis-t basis-t
                       ::max-tx  (:max-tx db)}))))))

;; ---------------------------------------------------------------------------
;; The `:seon.db.writer/remote` PWriter. Mirrors datahike.http.writer/
;; DatahikeServerWriter: non-streaming (flips deref-conn into
;; follow-the-database mode), dispatches `transact!` over the UDS transport,
;; returns a promise-chan the writer go-loop consumes.
;; ---------------------------------------------------------------------------

(def ^:private max-own-write-correlations
  "Maximum own writes retained while the transaction feed is behind.

   A disconnected feed cannot discard response-first ids without later
   delivering each own transaction twice. Admission therefore stops at this
   explicit bound instead of growing process memory without limit."
  4096)

(declare !attachment fire-own-tx-listeners!)

(defn- attachment-active-for-conn?
  [state conn]
  (and (not= ::stopped (::phase state))
       (identical? conn (::conn state))))

(defn- begin-transaction!
  "Admit one own-write correlation for the attached connection."
  [conn request-id]
  (let [result (volatile! ::untracked)]
    (swap! !attachment
           (fn [state]
             (if-not (attachment-active-for-conn? state conn)
               state
               (let [correlations (::correlations state)]
                 (if (< (count correlations) max-own-write-correlations)
                   (do
                     (vreset! result ::tracked)
                     (assoc-in state [::correlations request-id]
                               {::status ::pending}))
                   (do
                     (vreset! result ::saturated)
                     state))))))
    @result))

(defn- correlation-for
  [conn request-id]
  (let [state @!attachment]
    (when (attachment-active-for-conn? state conn)
      (get-in state [::correlations request-id]))))

(defn- reject-transaction!
  [conn request-id]
  (swap! !attachment
         (fn [state]
           (if (attachment-active-for-conn? state conn)
             (update state ::correlations dissoc request-id)
             state))))

(defn- resolve-transaction!
  "Mark a successful response. Returns true when its feed was already skipped."
  [conn request-id basis-t]
  (let [feed? (volatile! false)]
    (swap! !attachment
           (fn [state]
             (if-let [entry (when (attachment-active-for-conn? state conn)
                              (get-in state [::correlations request-id]))]
               (if (::feed-coordinate entry)
                 (do
                   (vreset! feed? true)
                   (update state ::correlations dissoc request-id))
                 (assoc-in state [::correlations request-id]
                           (assoc entry ::status ::resolved ::basis-t basis-t)))
               state)))
    @feed?))

(defn- prune-resolved-correlations
  [correlations basis-t]
  (into {}
        (remove (fn [[_ correlation]]
                  (and (= ::resolved (::status correlation))
                       (some? (::basis-t correlation))
                       (<= (::basis-t correlation) basis-t))))
        correlations))

(defn- correlation-capacity-error
  [request-id]
  (ex-info
   "The transaction feed is too far behind to correlate another own write."
   {::protocol/request-id request-id
    ::protocol/status protocol/feed-behind-status
    ::correlation-limit max-own-write-correlations
    :seon.error/kind :core-bug}))

(defn- ambiguous-transaction-error
  [conn request-id attempts error]
  (let [feed-coordinate (::feed-coordinate (correlation-for conn request-id))]
    (reject-transaction! conn request-id)
    (when feed-coordinate
      (fire-own-tx-listeners! conn (::basis-t feed-coordinate)))
    (ex-info
     (str "Database transaction lost every reply after " attempts
          " idempotent attempt(s); commit status remains unknown. "
          "The transaction must only be retried with the same request id.")
     {::protocol/request-id request-id
      ::protocol/status protocol/unknown-status
      ::protocol/attempts attempts
      ::protocol/basis-t (:max-tx @conn)
      ::protocol/transport-failure
      (::uds/failure (ex-data error))
      :seon.error/kind :core-bug}
     error)))

(defn- transact-rpc!
  "Send one frozen request, resubmitting the same durable id on reply loss."
  [request-socket-path request attempt]
  (-> (uds/rpc {::uds/socket-path request-socket-path
                ::uds/message request
                ::uds/timeout-ms transaction-timeout-ms})
      (.catch
       (fn [error]
         (if (< attempt transaction-attempts)
           (do
             (js/console.warn
              (str "[seon.db.replica] transaction reply lost; retrying request "
                   (::protocol/request-id request) " (attempt " (inc attempt)
                   "/" transaction-attempts ")"))
             (transact-rpc! request-socket-path request (inc attempt)))
           (js/Promise.reject error))))))

(defn- rejected-response-error
  [response generated?]
  (let [error-kind (::protocol/error-kind response)
        candidate  (::protocol/generated-candidate response)
        allocator-protocol? (and generated?
                                 (= protocol/protocol-error error-kind))]
    (ex-info
     (str "Database transaction failed: " (::protocol/error response))
     (cond->
      {::error-kind error-kind
       :seon.error/kind
       (if (= protocol/generated-candidate-conflict-error error-kind)
         :user-input
         :core-bug)}
       (= protocol/generated-candidate-conflict-error error-kind)
       (assoc :seon.db.id/error :seon.db.id.error/candidate-conflict)
       candidate
       (assoc :seon.db.id/generated-candidate candidate)
       allocator-protocol?
       (assoc :seon.db.id/error
              :seon.db.id.error/invalid-allocation-transaction)))))

(defn- response-processing-error
  [conn request-id response error]
  (let [feed-coordinate (::feed-coordinate (correlation-for conn request-id))]
    (reject-transaction! conn request-id)
    (when feed-coordinate
      (fire-own-tx-listeners! conn (::basis-t feed-coordinate)))
    (ex-info
     "A committed database transaction could not be materialized locally."
     {::protocol/request-id request-id
      ::protocol/status protocol/committed-status
      ::protocol/basis-t (::protocol/basis-t response)
      :seon.error/kind :core-bug}
     error)))

(defn- register-writer-operation!
  "Atomically admit `completion` while this writer remains open."
  [lifecycle completion]
  (let [[before _]
        (swap-vals! lifecycle
                    (fn [state]
                      (if (::writer-open? state)
                        (update state ::writer-pending conj completion)
                        state)))]
    (::writer-open? before)))

(defn- finish-writer-operation!
  "Resolve one admitted operation before removing it from the drain set."
  [lifecycle completion value]
  (put! completion value)
  (swap! lifecycle update ::writer-pending disj completion))

(defn- shutdown-writer!
  "Close admission and resolve only after every admitted RPC has completed."
  [lifecycle]
  (let [[before _] (swap-vals! lifecycle assoc ::writer-open? false)
        pending (::writer-pending before)
        done (promise-chan)]
    (go
      (doseq [completion pending]
        (<! completion))
      (put! done true))
    done))

(defrecord RemoteWriter [request-socket-path conn lifecycle]
  w/PWriter
  (-dispatch! [_ {:keys [op args]}]
    (let [p (promise-chan)]
      (if-not (register-writer-operation! lifecycle p)
        (put! p (ex-info "The remote database writer is shut down."
                         {::op op
                          :seon.error/kind :core-bug}))
        (if (not= op 'transact!)
          (finish-writer-operation!
            lifecycle p
            (ex-info "The remote database writer supports only transact!"
                     {::op op}))
          (let [arg-map    (first args)
                tx-data    (if (map? arg-map) (:tx-data arg-map) arg-map)
                tx-meta    (when (map? arg-map) (:tx-meta arg-map))
                generated? (and (map? arg-map)
                              (contains? arg-map
                                         :seon.db.id/generated-candidates))
              generated-candidates
              (when (map? arg-map)
                (:seon.db.id/generated-candidates arg-map))
              request-id    (str (random-uuid))
              request-input
              (cond-> {::protocol/database-name database-name
                       ::protocol/transaction-data (vec tx-data)
                       ::protocol/request-id request-id}
                (seq tx-meta)
                (assoc ::protocol/transaction-meta tx-meta)
                generated?
                (assoc ::protocol/generated-candidates
                       (vec generated-candidates)))
              req (protocol/transaction-request request-input)
              tracking   (begin-transaction! conn request-id)]
          (if (= ::saturated tracking)
            (finish-writer-operation!
             lifecycle p (correlation-capacity-error request-id))
            (-> (transact-rpc! request-socket-path req 1)
              (.then
               (fn [resp]
                 ;; A reply WAS read — no commit ambiguity from here on. Any
                 ;; throw in this post-reply processing (e.g. the RYOW guard)
                 ;; is put directly, so the .catch below stays rpc-layer-only.
                 (try
                   (if-not (::protocol/success? resp)
                     (do
                       (reject-transaction! conn request-id)
                       (finish-writer-operation!
                        lifecycle p
                        (rejected-response-error resp generated?)))
                     ;; RYOW: resolve only once a local deref is at/past
                     ;; the ack'd basis-t. The synthesized report carries
                     ;; the MATERIALIZED post-tx db value, so straight-line
                     ;; transact!-then-read code just works.
                     (let [bt      (::protocol/basis-t resp)
                           db      (ryow-deref! conn bt)
                           tempids (::protocol/temporary-ids resp)
                           tx-meta (::protocol/transaction-meta resp)
                           generated-eids
                           (::protocol/generated-entity-ids resp)
                           report
                           (cond-> {:db-after db
                                    :tx-data  (transaction-datoms->datoms
                                               (::protocol/transaction-data resp))
                                    :tempids  (or tempids {})
                                        ;; The sole JVM writer
                                        ;; computes the honest added/retracted
                                        ;; split over the REAL :added flags
                                        ;; (`tx-report->ok-map`). Carry those
                                        ;; counts on the synthesized report so
                                        ;; `transact-success-envelope` reports
                                        ;; them verbatim instead of re-deriving
                                        ;; from reconstituted datoms.
                                    :datoms-added     (::protocol/datoms-added resp)
                                    :datoms-retracted (::protocol/datoms-retracted resp)}
                             (seq generated-eids)
                             (assoc :seon.db.id/generated-eids generated-eids)
                             (::protocol/recovered? resp)
                             (assoc ::protocol/recovered? true)
                             (some? tx-meta) (assoc :tx-meta tx-meta)
                             (::protocol/basis-t-before resp)
                             (assoc :db-before
                                    (d/as-of db
                                             (::protocol/basis-t-before
                                              resp))))]
                       (resolve-transaction! conn request-id bt)
                       (finish-writer-operation! lifecycle p report)))
                   (catch :default e
                     (finish-writer-operation!
                       lifecycle p
                       (response-processing-error conn request-id resp e))))))
              (.catch
               (fn [e]
                 (finish-writer-operation!
                   lifecycle p
                   (ambiguous-transaction-error
                     conn request-id transaction-attempts
                     (if (instance? js/Error e)
                       e
                       (js/Error. (str e))))))))))))
      p))
  (-shutdown [_] (shutdown-writer! lifecycle))
  (-streaming? [_] false))

(defmethod w/create-writer :seon.db.writer/remote
  [{:keys [socket-path]} connection]
  (->RemoteWriter (or socket-path default-request-socket-path)
                  connection
                  (atom {::writer-open? true
                         ::writer-pending #{}})))

(defmethod connector/-connect* :seon.db.writer/remote [config opts]
  (connector/-connect-impl* config opts))

;; ---------------------------------------------------------------------------
;; listen! attachment — foreign writes fire the pod's native conn listeners.
;;
;; `seon.db/listen!` installs handlers via `d/listen` on the conn, which
;; stores them in `(:listeners (meta conn))` (the Connection proxies meta
;; to its wrapped-atom; connector.cljc:32,84). Own txs fire them via
;; `datahike.writer/transact!` (writer.cljc:247). For FOREIGN txs we
;; synthesize the same raw tx-report shape from the transaction event and
;; fire the same listener atom — ONE bus, two tx origins.
;; ---------------------------------------------------------------------------

(defn- stopped-attachment-state
  [generation]
  {::phase ::stopped
   ::generation generation
   ::correlations {}})

(defonce ^:private !attachment
  ;; One lifecycle owner for the feed attachment and own-write correlation.
  ;; It contains only live resources and pure coordinates — never a db value.
  (atom (stopped-attachment-state 0)))

(defn- connection-coordinate
  "Derive the branch-qualified identity of one Datahike connection."
  [db]
  (let [config         (:config db)
        database-id    (get-in config [:store :id])
        branch         (:branch config)
        writer-backend (get-in config [:writer :backend])]
    (when-not (uuid? database-id)
      (throw (ex-info "The attached database has no UUID identity."
                      {::database-id database-id
                       :seon.error/kind :core-bug})))
    (when-not (keyword? branch)
      (throw (ex-info "The attached database has no branch identity."
                      {::branch branch
                       :seon.error/kind :core-bug})))
    (when-not (keyword? writer-backend)
      (throw (ex-info "The attached database has no writer backend identity."
                      {::writer-backend writer-backend
                       :seon.error/kind :core-bug})))
    {::database-name database-name
     ::database-id database-id
     ::branch branch
     ::writer-backend writer-backend}))

(defn- progress-coordinate
  [database-coordinate basis-t]
  {::database-coordinate database-coordinate
   ::basis-t basis-t})

(defn- active-generation?
  ([state generation]
   (and (not= ::stopped (::phase state))
        (= generation (::generation state))))
  ([state generation conn]
   (and (active-generation? state generation)
        (identical? conn (::conn state)))))

(defn- destroy-socket!
  [socket]
  (when socket
    (try (.destroy ^js socket) (catch :default _))))

(defn- clear-reconnect-timer!
  [timer]
  (when timer
    (js/clearTimeout timer)))

(defn- cleanup-attachment-resources!
  [state]
  (clear-reconnect-timer! (::reconnect-timer state))
  (destroy-socket! (::socket state)))

(defn- stop-active-attachment!
  "Stop one active generation and dispose every resource it owns."
  ([] (stop-active-attachment! nil))
  ([expected-generation]
   (let [stopped? (volatile! false)
         [before _]
         (swap-vals!
          !attachment
          (fn [state]
            (if (and (not= ::stopped (::phase state))
                     (or (nil? expected-generation)
                         (= expected-generation (::generation state))))
              (do
                (vreset! stopped? true)
                (stopped-attachment-state (inc (::generation state))))
              state)))]
     (when @stopped?
       (cleanup-attachment-resources! before))
     @stopped?)))

(defn detach!
  "Stop the transaction feed and dispose its socket, timer, and correlations."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (stop-active-attachment!))

(defn status
  "Live attachment state for diagnostics.

   Reports only bounded diagnostics and the full database coordinate. It never
   exposes or retains a database value, socket, timer, or connection handle."
  {:malli/schema
   [:=> [:cat]
    [:map
     [::started? :boolean]
     [::connected? :boolean]
     [::phase [:enum ::stopped ::connecting ::live ::reconnecting]]
     [::generation :int]
     [::correlation-count [:int {:min 0}]]
     [::database-coordinate {:optional true} ::database-coordinate]
     [::last-applied-coordinate {:optional true} ::progress-coordinate]
     [::own-skips {:optional true} [:int {:min 0}]]]]}
  []
  (let [state @!attachment]
    (cond-> {::started? (not= ::stopped (::phase state))
             ::connected? (= ::live (::phase state))
             ::phase (::phase state)
             ::generation (::generation state)
             ::correlation-count (count (::correlations state))}
      (::database-coordinate state)
      (assoc ::database-coordinate (::database-coordinate state))
      (::last-applied-coordinate state)
      (assoc ::last-applied-coordinate (::last-applied-coordinate state))
      (some? (::own-skips state))
      (assoc ::own-skips (::own-skips state)))))

(defn- fire-native-listeners! [conn report]
  ;; Dispatch each listener on its OWN macrotask (`setTimeout 0`) so the
  ;; tx-feed pump never blocks on a single slow/heavy listener (a big
  ;; web UI layout, a wake-handler doing real work inline) — the pump must
  ;; keep draining events for ALL agents. The `report` is an immutable
  ;; snapshot (fully built in handle-feed-event! before this fires), safe to
  ;; defer. Ordering that matters is preserved: handle-feed-event! fires these
  ;; in commit order and Node runs same-delay timers in scheduling order, so
  ;; per-listener FIFO across txs holds. The throw guard stays INSIDE the
  ;; deferred fn so a thrown callback can't crash the pump and is logged
  ;; (the wrapped seon.db handlers already guard too — this is belt-and-braces).
  (doseq [[k callback] (some-> (:listeners (meta conn)) deref)]
    (js/setTimeout
      (fn []
        (try
          (callback report)
          (catch :default e
            (js/console.warn "[seon.db.replica]" (pr-str k)
                             "listener threw:" (str e)))))
      0)))

(defn- fire-own-tx-listeners!
  "Fire the conn's native listeners for OWN tx `tx-t` from local history.

   Used only when every same-id reply was lost after the feed already
   suppressed the commit. Reads are local (follow-the-store), so the
   committing tx's datoms come straight from the history index."
  [conn tx-t]
  (try
    (let [db (ryow-deref! conn tx-t)
          ds (->> (d/datoms (d/since (d/history db) (dec tx-t)) :eavt)
                  (filterv #(= tx-t (js/Math.abs (:tx %)))))]
      (fire-native-listeners! conn {:db-after  db
                                    :db-before (d/as-of db (dec tx-t))
                                    :tx-data   ds}))
    (catch :default e
      (js/console.warn "[seon.db.replica] own transaction listener failed for tx"
                       tx-t ":" (str e)))))

(defn- advance-progress
  [state basis-t]
  (-> state
      (assoc ::last-applied-coordinate
             (progress-coordinate (::database-coordinate state) basis-t))
      (update ::correlations prune-resolved-correlations basis-t)))

(defn- apply-own-feed-event!
  [generation conn request-id basis-t]
  ;; Materialize before advancing. A stale generation is checked again inside
  ;; the atomic update so an A→B reattach during the deref cannot mutate B.
  (ryow-deref! conn basis-t)
  (swap! !attachment
         (fn [state]
           (if-not (active-generation? state generation conn)
             state
             (let [correlation (get-in state [::correlations request-id])
                   feed-coordinate
                   (progress-coordinate (::database-coordinate state) basis-t)]
               (-> (if (= ::resolved (::status correlation))
                     (update state ::correlations dissoc request-id)
                     (assoc-in state [::correlations request-id ::feed-coordinate]
                               feed-coordinate))
                   (update ::own-skips (fnil inc 0))
                   (advance-progress basis-t)))))))

(defn- apply-foreign-feed-event!
  [generation conn basis-t basis-t-before ev]
  (when-not (and (integer? basis-t-before) (< basis-t-before basis-t))
    (throw (ex-info "A transaction feed event has no valid prior coordinate."
                    {::basis-t basis-t
                     ::basis-t-before basis-t-before
                     :seon.error/kind :core-bug})))
  (let [db      (ryow-deref! conn basis-t)
        report  (cond-> {:db-after  db
                         :db-before (d/as-of db basis-t-before)
                         :tx-data   (transaction-datoms->datoms
                                     (::protocol/transaction-data ev))}
                  (some? (::protocol/transaction-meta ev))
                  (assoc :tx-meta (::protocol/transaction-meta ev)))
        applied? (volatile! false)]
    (swap! !attachment
           (fn [state]
             (let [last-applied
                   (get-in state [::last-applied-coordinate ::basis-t])]
               (if (and (active-generation? state generation conn)
                        (or (nil? last-applied) (< last-applied basis-t)))
                 (do
                   (vreset! applied? true)
                   (advance-progress state basis-t))
                 state))))
    (when @applied?
      (fire-native-listeners! conn report))))

(defn- handle-feed-event! [generation conn ev]
  (let [state        @!attachment
        request-id      (::protocol/request-id ev)
        basis-t      (::protocol/basis-t ev)
        basis-before (::protocol/basis-t-before ev)
        last-applied (get-in state [::last-applied-coordinate ::basis-t])]
    (when-not (integer? basis-t)
      (throw (ex-info "A transaction feed event has no integer basis-t."
                      {::basis-t basis-t
                       :seon.error/kind :core-bug})))
    (cond
      ;; A callback from an attachment that has been stopped or replaced is a
      ;; stale resource completion, never input to the new connection.
      (not (active-generation? state generation conn))
      nil

      ;; Replay/live overlap is idempotent inside this branch-qualified
      ;; attachment. A numeric t is never carried from one attachment to the
      ;; next; start seeds a new full coordinate from the new connection.
      (and (some? last-applied) (<= basis-t last-applied))
      nil

      ;; The response-side Datahike writer fires own listeners. The feed only
      ;; advances its full cursor and resolves correlation ordering.
      (and request-id (get-in state [::correlations request-id]))
      (apply-own-feed-event! generation conn request-id basis-t)

      :else
      (apply-foreign-feed-event!
       generation conn basis-t basis-before ev))))

(defn- feed-event-dispatch!
  "Apply one transaction event for the attached database."
  [generation conn expected-database-name ev]
  (when (and (= protocol/transaction-event (::protocol/event ev))
             (= expected-database-name (::protocol/database-name ev)))
    (handle-feed-event! generation conn ev)))

(defn- replay-page-error
  [message data]
  (throw (ex-info (str "Invalid database replay page: " message)
                  (assoc data :seon.error/kind :core-bug))))

(defn- validated-replay-page
  "Validate one replay page before its cursor is allowed to advance.

   A malformed, stale, repeated-without-progress, or prematurely empty page is
   a reconnectable feed failure, never permission to skip a range. The first
   page establishes db-name and through-t; later pages must retain both."
  [cursor expected-through expected-db-name response]
  (when-not (::protocol/success? response)
    (replay-page-error "writer returned not-ok"
                       {::response response ::cursor cursor}))
  (let [response-since (::protocol/since-t response)
        through       (::protocol/through-t response)
        continuation  (::protocol/continuation-t response)
        done?         (::protocol/complete? response)
        response-database-name (::protocol/database-name response)
        events        (::protocol/events response)
        replayed      (::protocol/replayed-count response)
        basis-ts      (mapv ::protocol/basis-t events)
        before-ts     (mapv ::protocol/basis-t-before events)
        expected-before-ts (if (seq basis-ts)
                             (vec (cons cursor (butlast basis-ts)))
                             [])]
    (when-not (= cursor response-since)
      (replay-page-error "response since-t does not match the requested cursor"
                         {::cursor cursor ::response response}))
    (when-not (and (integer? through) (<= cursor through))
      (replay-page-error "through-t is not an integer at or above the cursor"
                         {::cursor cursor ::response response}))
    (when (and (some? expected-through) (not= expected-through through))
      (replay-page-error "through-t changed between pages"
                         {::expected-through expected-through
                          ::response response}))
    (when-not (and (string? response-database-name)
                   (not (str/blank? response-database-name)))
      (replay-page-error "database-name is missing"
                         {::response response}))
    (when (and (some? expected-db-name)
               (not= expected-db-name response-database-name))
      (replay-page-error "database-name changed between pages"
                         {::expected-db-name expected-db-name
                          ::response response}))
    (when-not (vector? events)
      (replay-page-error "events is not a vector"
                         {::response response}))
    (when-not (and (integer? replayed) (= replayed (count events)))
      (replay-page-error "replayed does not match the event count"
                         {::response response}))
    (when-not (boolean? done?)
      (replay-page-error "done? is not boolean"
                         {::response response}))
    (when-not (and (integer? continuation)
                   (<= cursor continuation through))
      (replay-page-error "continuation-t is outside the page bounds"
                         {::cursor cursor ::response response}))
    (when-not (every? (fn [basis-t]
                        (and (integer? basis-t)
                             (< cursor basis-t)
                             (<= basis-t through)))
                      basis-ts)
      (replay-page-error "an event basis-t is stale or outside the watermark"
                         {::cursor cursor ::response response}))
    (when-not (apply < cursor basis-ts)
      (replay-page-error "event basis-ts are not strictly ascending"
                         {::cursor cursor ::response response}))
    (when-not (= expected-before-ts before-ts)
      (replay-page-error "basis-t-before does not form one cursor chain"
                         {::cursor cursor ::response response}))
    (when-not (every? (fn [event]
                        (and (= protocol/transaction-event
                                (::protocol/event event))
                             (= response-database-name
                                (::protocol/database-name event))))
                      events)
      (replay-page-error "an event is not a tx for the resolved database"
                         {::response response}))
    (if done?
      (do
        (when-not (= through continuation)
          (replay-page-error "a final page does not continue at through-t"
                             {::response response}))
        (when (and (< cursor through)
                   (or (empty? basis-ts) (not= through (peek basis-ts))))
          (replay-page-error "a final page omitted the upper watermark"
                             {::cursor cursor ::response response})))
      (when (or (empty? basis-ts)
                (not= continuation (peek basis-ts))
                (<= continuation cursor))
        (replay-page-error "a continuation page made no progress"
                           {::cursor cursor ::response response})))
    {::database-name response-database-name
     ::through-t through
     ::continuation-t continuation
     ::done? done?
     ::events events
     ::replayed replayed}))

(def ^:private max-buffered-live-events
  "Maximum pub frames retained while a bounded replay is in progress."
  4096)

(defn- throw-if-feed-dropped!
  [connection-state]
  (when-let [reason (::drop-reason @connection-state)]
    (throw (ex-info "The transaction publisher dropped during replay."
                    {::drop-reason reason}))))

(defn- register-feed-socket!
  [generation conn socket]
  (let [registered? (volatile! false)]
    (swap! !attachment
           (fn [state]
             (if (active-generation? state generation conn)
               (do
                 (vreset! registered? true)
                 (assoc state ::socket socket))
               state)))
    (when-not @registered?
      (destroy-socket! socket)
      (throw (ex-info "The transaction feed attachment was replaced while connecting."
                      {::generation generation
                       :seon.error/kind :core-bug})))))

(defn- clear-feed-socket!
  [generation conn socket]
  (swap! !attachment
         (fn [state]
           (if (and (active-generation? state generation conn)
                    (identical? socket (::socket state)))
             (dissoc state ::socket)
             state))))

(defn- buffer-live-event!
  [connection-state event]
  (let [overflow? (volatile! false)]
    (swap! connection-state
           (fn [state]
             (if (< (count (::buffer state)) max-buffered-live-events)
               (update state ::buffer conj event)
               (do
                 (vreset! overflow? true)
                 state))))
    (when @overflow?
      (throw (ex-info "The live transaction buffer reached its fixed bound."
                      {::buffer-limit max-buffered-live-events
                       :seon.error/kind :core-bug})))))

(defn ^:async ^:private connect-feed!
  ;; One pub-socket feed connection: connect, walk bounded replay pages under
  ;; one fixed upper watermark, drain live frames buffered throughout, go live.
  ;; Resolves to {::database-name ::replayed}; throws on connect/replay failure (the
  ;; caller owns retry). Once live, `on-drop` fires ONCE if the connection dies;
  ;; a drop during replay rejects this connect attempt directly.
  [generation conn database-coordinate request-socket-path publish-socket-path on-drop]
  (let [!connection  (atom {::buffer []
                            ::live? false
                            ::database-name (::database-name database-coordinate)
                            ::closing? false})
        drop-promise (js/Promise.
                      (fn [_resolve reject]
                        (swap! !connection assoc ::reject-drop reject)))
        sock
        (await
         (uds/connect-publisher!
          {::uds/socket-path publish-socket-path
           ::uds/on-message
           (fn [event]
             (if (::live? @!connection)
               (feed-event-dispatch!
                generation conn (::database-name @!connection) event)
               (buffer-live-event! !connection event)))
           ::uds/on-close
           (fn [reason]
             (let [before @!connection]
               (swap! !connection assoc ::drop-reason reason)
               (cond
                 (::live? before)
                 (when on-drop (on-drop reason))

                 (not (::closing? before))
                 ((::reject-drop before)
                  (ex-info "The transaction publisher dropped during replay."
                           {::drop-reason reason})))))}))]
    (register-feed-socket! generation conn sock)
    (try
      ;; Replay every tx after the watermark. Pages are applied as they arrive,
      ;; so a failed later page reconnects from the advanced attachment watermark.
      ;; The pub socket remains open and buffers frames for the entire walk.
      (let [attachment-state @!attachment
            _ (when-not (active-generation? attachment-state generation conn)
                (throw (ex-info "The transaction feed attachment was stopped."
                                {::generation generation
                                 :seon.error/kind :core-bug})))
            initial-cursor
            (or (get-in attachment-state
                        [::last-applied-coordinate ::basis-t])
                0)
            expected-database-name (::database-name database-coordinate)
            replay-result
            (loop [cursor initial-cursor
                   through nil
                   replayed 0]
              (throw-if-feed-dropped! !connection)
              (let [replay-request
                    (protocol/replay-transactions-request
                     (cond->
                      {::protocol/database-name expected-database-name
                       ::protocol/since-t cursor}
                       (some? through)
                       (assoc ::protocol/through-t through)))
                    response
                    (await
                     (js/Promise.race
                      #js [(uds/rpc
                            {::uds/socket-path request-socket-path
                             ::uds/message replay-request
                             ::uds/timeout-ms replay-timeout-ms})
                           drop-promise]))
                    _        (throw-if-feed-dropped! !connection)
                    page     (validated-replay-page
                              cursor through expected-database-name response)
                    next-db  (::database-name page)
                    total    (+ replayed (::replayed page))]
                (doseq [event (::events page)]
                  (feed-event-dispatch! generation conn next-db event))
                (if (::done? page)
                  {::database-name next-db ::replayed total}
                  (recur (::continuation-t page)
                         (::through-t page)
                         total))))]
        (throw-if-feed-dropped! !connection)
        (when-not (active-generation? @!attachment generation conn)
          (throw (ex-info "The transaction feed attachment was stopped."
                          {::generation generation
                           :seon.error/kind :core-bug})))
        ;; Go live, then drain — one synchronous block (no await between), so
        ;; no frame can slip between the flip and drain or be applied twice.
        ;; Watermark idempotency removes replay/buffer overlap.
        (let [buffered (::buffer @!connection)]
          (swap! !connection assoc ::live? true ::buffer [])
          (doseq [event buffered]
            (feed-event-dispatch!
             generation conn (::database-name @!connection) event)))
        (assoc replay-result ::socket sock))
      (catch :default error
        (swap! !connection assoc ::closing? true)
        (clear-feed-socket! generation conn sock)
        (destroy-socket! sock)
        (throw error)))))

(declare schedule-reconnect!)

(defn- activate-feed!
  [generation result reconnected?]
  (let [activated? (volatile! false)]
    (swap! !attachment
           (fn [state]
             (if (active-generation? state generation)
               (do
                 (vreset! activated? true)
                 (-> state
                     (assoc ::phase ::live
                            ::database-name (::database-name result)
                            ::socket (::socket result))
                     (dissoc ::reconnect-timer ::ready)))
               state)))
    (when-not @activated?
      (destroy-socket! (::socket result))
      (throw (ex-info "The transaction feed attachment was replaced before activation."
                      {::generation generation
                       :seon.error/kind :core-bug})))
    (log/info-console!
     "seon.db.replica"
     (str "tx-feed " (if reconnected? "re-connected" "live")
          " (pub socket, db " (::database-name result)
          ", replayed " (::replayed result) ")"))
    (::database-name result)))

(defn- connect-current-attachment!
  [generation]
  (let [state @!attachment]
    (if-not (active-generation? state generation)
      (js/Promise.reject
       (ex-info "The transaction feed attachment is no longer active."
                {::generation generation
                 :seon.error/kind :core-bug}))
      (connect-feed!
       generation
       (::conn state)
       (::database-coordinate state)
       (::request-socket-path state)
       (::publish-socket-path state)
       #(schedule-reconnect! generation %)))))

(defn- launch-reconnect!
  [generation]
  (when (active-generation? @!attachment generation)
    (-> (connect-current-attachment! generation)
        (.then #(activate-feed! generation % true))
        (.catch
         (fn [error]
           (when (active-generation? @!attachment generation)
             (schedule-reconnect!
              generation (or (.-message error) (str error)))))))))

(defn- schedule-reconnect!
  "Schedule one reconnect owned by the active attachment generation."
  [generation reason]
  (let [!timer (volatile! nil)
        scheduled? (volatile! false)
        fire! (fn []
                (let [claimed? (volatile! false)]
                  (swap! !attachment
                         (fn [state]
                           (if (and (active-generation? state generation)
                                    (identical? @!timer
                                                (::reconnect-timer state)))
                             (do
                               (vreset! claimed? true)
                               (dissoc state ::reconnect-timer))
                             state)))
                  (when @claimed?
                    (launch-reconnect! generation))))
        timer (js/setTimeout fire! feed-reconnect-delay-ms)]
    (vreset! !timer timer)
    (swap! !attachment
           (fn [state]
             (if (and (active-generation? state generation)
                      (nil? (::reconnect-timer state)))
               (do
                 (vreset! scheduled? true)
                 (-> state
                     (assoc ::phase ::reconnecting
                            ::reconnect-timer timer)
                     (dissoc ::socket)))
               state)))
    (if @scheduled?
      (log/error-console!
       "seon.db.replica"
       (str "tx-feed pub connection lost (" reason ") — reconnecting in "
            feed-reconnect-delay-ms "ms"))
      (js/clearTimeout timer))))

(defn- same-attachment?
  [state conn database-coordinate request-socket-path publish-socket-path]
  (and (attachment-active-for-conn? state conn)
       (= database-coordinate (::database-coordinate state))
       (= request-socket-path (::request-socket-path state))
       (= publish-socket-path (::publish-socket-path state))))

(defn ^:async attach!
  "Connect the persistent pub-socket tx feed and pump FOREIGN tx into the conn.

   ONE streaming pub-socket connection (push) replaces the old req-socket
   poll pump: the database server writes every committed tx event down the
   stream, and the frame reader feeds the conn's native listeners directly.
   Idempotent for the same connection and coordinate. A different connection,
   branch, or transport path atomically disposes the old attachment before the
   new one connects. Resilient: a
   drop logs LOUDLY, reconnects after `wire/feed-reconnect-delay-ms`, and recovers the gap via the
   req-socket `replay-tx` from the basis-t watermark (DE-2 lossless wake) —
   the attachment never dies silently. The FIRST connect is fail-loud (boot is
   ping-gated; a pod that can't reach its feed must not run).

   Map-in: `{::conn <conn> ::request-socket-path <path>? ::publish-socket-path <path>?}`.
   Returns a Promise of the feed's db-name once a new attachment is live. An
   already-active identical attachment returns its db-name without opening a
   second socket."
  {:malli/schema [:=> [:cat [:map [::conn ::conn]
                                  [::request-socket-path {:optional true} ::request-socket-path]
                                  [::publish-socket-path {:optional true} ::request-socket-path]]] :any]}
  [{::keys [conn request-socket-path publish-socket-path]
    :or {request-socket-path default-request-socket-path publish-socket-path default-publish-socket-path}}]
  (let [db                  @conn
        database-coordinate (connection-coordinate db)
        basis-t             (:max-tx db)
        current             @!attachment]
    (when-not (integer? basis-t)
      (throw (ex-info "The attached database has no integer basis-t."
                      {::basis-t basis-t
                       :seon.error/kind :core-bug})))
    (if (same-attachment?
         current conn database-coordinate request-socket-path publish-socket-path)
      (await (or (::ready current)
                 (js/Promise.resolve (::database-name database-coordinate))))
      (let [generation (inc (::generation current))
            state {::phase ::connecting
                   ::generation generation
                   ::conn conn
                   ::database-coordinate database-coordinate
                   ::last-applied-coordinate
                   (progress-coordinate database-coordinate basis-t)
                   ::request-socket-path request-socket-path
                   ::publish-socket-path publish-socket-path
                   ::own-skips 0
                   ::correlations {}}]
        ;; Publish the new generation before destroying old resources. Any old
        ;; socket callback caused by `.destroy` then observes itself as stale.
        (reset! !attachment state)
        (cleanup-attachment-resources! current)
        (let [ready (-> (connect-current-attachment! generation)
                        (.then #(activate-feed! generation % false))
                        (.catch
                         (fn [error]
                           (stop-active-attachment! generation)
                           (throw error))))]
          (swap! !attachment
                 (fn [attachment]
                   (if (active-generation? attachment generation conn)
                     (assoc attachment ::ready ready)
                     attachment)))
          (await ready))))))
