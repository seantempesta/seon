(ns seon.store.wire
  "THE pod↔cluster-store seam (unit 2.2e — the flip).

   The pod runs datahike-cljs as a DIS PEER on the JVM wire-server's
   `:file` cluster store:

   - READS are local + sync: the `:seon-wire` writer below reports
     `-streaming?` false, which flips `deref-conn` into follow-the-store
     mode — every `@conn` re-reads the branch root from konserve and
     reconstitutes a fresh db value with lazy LRU node fetch
     (connector.cljc:69-78). No API change anywhere in `seon.db`.
   - WRITES go over the wire: `d/transact!` dispatches to the
     `SeonWireWriter`, which forwards the op over the existing UDS
     `transact` op (`seon.store.internal.wire-node` → `seon.server.wire`) and
     synthesizes the tx-report from the ack + a local RYOW re-deref.
     The JVM is the SOLE writer on the store.
   - CHANGE NOTIFICATION: `start-listen-adapter!` subscribes to the
     wire tx feed; on each FOREIGN tx event (own wire ids skipped —
     own txs already fire the conn's native listeners via
     `datahike.writer/transact!`) it re-derefs and fires the conn's
     NATIVE `d/listen` listeners with a synthesized raw tx-report. So
     every `seon.db/listen!` handler (user-message triggers, web UI
     SSE) fires identically for own and foreign writes.
   - PUSH, NOT POLL: the feed is ONE persistent pub-socket connection
     (`seon.server.broadcast/start-pub-server!` writes every committed
     tx event to each subscriber); a streaming frame reader feeds
     `handle-feed-event!` directly. The pub stream is db-agnostic, so
     the adapter filters frames by its cluster's db-name client-side.
   - LOSSLESS WAKE (DE-2): a connection drop would lose a wake (the
     event IS the trigger to act). The adapter tracks the last-applied
     basis-t watermark and, on every (re)connect, fetches the gap via
     the req-socket `replay-tx` op — every missed tx, in commit order,
     applied ahead of buffered live frames. Feed application is
     idempotent on the watermark (a tx ≤ it is a no-op), so the
     replay↔live overlap fires each listener at most once.

   Proven off-pod by the Stage A/B replica probe/peer oracles (10/10 + 14/14;
   harness since retired — findings + retirement note in
   docs/prds/agent-runtime/research/datahike-native-replica-2026-06-09.md)."
  (:require
   [cljs.core.async :refer [promise-chan put! <! go]]
   [clojure.string :as str]
   [datahike.api :as d]
   [datahike.connector :as connector]
   [datahike.datom :as dd]
   [datahike.writer :as w]
   [seon.store.internal.wire-node :as wire]
   [seon.platform :as platform]
   [seon.log :as log]
   [seon.schema :as schema]))

(def ^:private node-crypto (js/require "crypto"))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(schema/register! ::sock-path [:string {:min 1}])
(schema/register! ::store-path [:string {:min 1}])
(schema/register! ::db-name [:string {:min 1}])
(schema/register! ::store-id :uuid)
(schema/register! ::branch :keyword)
(schema/register! ::writer-backend :keyword)
(schema/register! ::basis-t [:int {:min 0}])
(schema/register!
 ::database-coordinate
 [:map
  [::db-name ::db-name]
  [::store-id ::store-id]
  [::branch ::branch]
  [::writer-backend ::writer-backend]])
(schema/register!
 ::progress-coordinate
 [:map
  [::database-coordinate ::database-coordinate]
  [::basis-t ::basis-t]])
;; The pod's datahike conn handle the listen adapter subscribes for — an
;; opaque runtime value (third-party boundary), hence :any.
(schema/register! ::conn :any)

(schema/register! ::store-id-request [:map [::db-name ::db-name]])
;; A datahike config map — third-party boundary shape.
(schema/register! ::cluster-config-response :map)

;; ---------------------------------------------------------------------------
;; Cluster store identity + config
;; ---------------------------------------------------------------------------

(def default-sock-path
  "The cluster's UDS request socket — inherits `wire/default-req-sock`,
   which is itself cluster-isolation-aware (reads `SEON_REQ_SOCK`, falls
   back to the live-default constant). Matches bin/seon's wire-server
   --req-sock for the default cluster and bin/acme's exported override."
  wire/default-req-sock)

(def default-pub-sock-path
  "The cluster's UDS publish socket — the persistent broadcast stream the
   tx-feed adapter subscribes to. Inherits `wire/default-pub-sock`
   (cluster-isolation-aware via `SEON_PUB_SOCK`). Matches bin/seon's
   wire-server --pub-sock for the default cluster and bin/acme's export."
  wire/default-pub-sock)

(def cluster-dir
  "The cluster's data dir — `SEON_CLUSTER_DIR`, default the live cluster.

   Everything per-cluster on disk (store, blobs) lives under it; the
   launcher (`bin/seon`, `bin/acme`, `bin/seon cluster create`) exports it."
  (or (platform/env-val "SEON_CLUSTER_DIR") "data/clusters/default"))

(def cluster-name
  "The pod's cluster name — the basename of [[cluster-dir]].

   The ONE derivation (registry C15): this name IS the wire db-name every
   pod op carries, the feed label, and the konserve store `:id` seed on
   BOTH sides. A socket-path artifact never enters the identity."
  (last (remove str/blank? (str/split cluster-dir #"/"))))

(def default-store-path
  "The cluster's konserve `:file` store dir: `[[cluster-dir]]/store`.

   Matches the wire-server's `--path $SEON_CLUSTER_DIR/store` under
   `bin/seon` and the per-name path `ensure-cluster-db!` registers."
  (str cluster-dir "/store"))

(defn store-id
  "The cluster store's konserve `:id`, replicated from the JVM side.

   `seon.server.store/name->uuid` = `UUID/nameUUIDFromBytes` (md5
   name-based v3 UUID) of the db-name keyword's `str` — so cluster
   \"default\" hashes as \":default\". The db-name is the CLUSTER NAME on
   both sides (the one derivation). Verified against the live
   wire-server's `(:id (:store (:config @conn)))`."
  {:malli/schema [:=> [:cat ::store-id-request] :uuid]}
  [{::keys [db-name]}]
  (let [nm       (str ":" db-name)
        b        (-> (.createHash node-crypto "md5")
                     (.update (js/Buffer.from nm "utf8"))
                     (.digest))]
    ;; nameUUIDFromBytes: md5 digest with version 3 + IETF variant bits.
    (aset b 6 (bit-or (bit-and (aget b 6) 0x0f) 0x30))
    (aset b 8 (bit-or (bit-and (aget b 8) 0x3f) 0x80))
    (let [hex (.toString b "hex")]
      (uuid (str (subs hex 0 8) "-" (subs hex 8 12) "-" (subs hex 12 16)
                 "-" (subs hex 16 20) "-" (subs hex 20 32))))))

(defn cluster-config
  "datahike config for the pod's DIS-peer connection to the default store.

   The default cluster store. Reads are local konserve; writes route through the
   `:seon-wire` writer (the JVM wire-server is the sole writer).

   `:lock-blob? false` is REQUIRED for readers: konserve's sync read
   path takes a `.ksv.LOCK` by default and two sync readers race on the
   branch-root blob and throw (`:file-lock-acquisition-error`, found by
   Stage B oracle (d)). Lock-free reads are DIS-correct — the root is
   replaced by atomic rename, index nodes are content-addressed and
   immutable, and this peer's writes go over the wire, never through
   local konserve."
  {:malli/schema [:=> [:cat] ::cluster-config-response]}
  []
  {:store               {:backend :file
                         :path    default-store-path
                         :id      (store-id {::db-name cluster-name})
                         :config  {:lock-blob? false}}
   :keep-history?       true
   :schema-flexibility  :write
   :writer              {:backend   :seon-wire
                         :sock-path default-sock-path}
   :allow-unsafe-config true})

;; ---------------------------------------------------------------------------
;; Boot gate — fail LOUD if the wire-server is down. No dual backend: a
;; pod that can't reach its writer must not boot against a local store.
;;
;; The ping retries (budget: the wire-node timing block) before the
;; fail-loud throw: `bin/seon
;; start all` brings the wire-server and pod up in order, but the pod
;; can exec before the writer's UDS socket accepts (or while a freshly
;; sha-bumped JVM warms up). Boot stays fail-loud, just not
;; fail-instant — after the budget the same error throws.
;; ---------------------------------------------------------------------------

(defn- sleep [ms]
  (js/Promise. (fn [res] (js/setTimeout res ms))))

;; Wire timing constants (ping budget/backoff, transact/ensure-db/replay
;; timeouts, feed reconnect delay) live in ONE place: `seon.store.internal.
;; wire-node`'s "wire timing" block. Reference `wire/…`, never inline a value.

(defn ^:async ^:private ping-once!
  "One ping rpc. Resolves to the reply map; throws on not-ok/transport."
  []
  (let [resp (await (wire/rpc default-sock-path {:seon.store.wire/op "ping"}
                              {:timeout-ms wire/ping-timeout-ms}))]
    (when-not (:seon.store.wire/ok resp)
      (throw (ex-info "wire-server ping returned not-ok"
                      {::resp resp})))
    resp))

(defn ^:async ping!
  "Ping the wire-server, retrying up to `wire/ping-attempts` times.

   Worst case ~attempts × (rpc timeout + backoff) — see the wire timing
   block in `seon.store.internal.wire-node` for the values.
   Resolves to the reply map on success; throws a clear, actionable
   error once the budget is exhausted."
  ;; Resolves to the wire-server reply map (a wire/rpc return — third-party
  ;; boundary), hence the :any return.
  {:malli/schema [:=> [:cat] :any]}
  []
  (await
   ((fn ^:async attempt [n]
      (try
        (await (ping-once!))
        (catch :default e
          (if (< n wire/ping-attempts)
            (do (js/console.warn
                 (str "[seon.store.wire] ping attempt " n "/" wire/ping-attempts
                      " failed (" (or (.-message e) (str e))
                      ") — retrying in " wire/ping-retry-delay-ms "ms"))
                (await (sleep wire/ping-retry-delay-ms))
                (await (attempt (inc n))))
            (throw (ex-info
                    (str "seon.store.wire: the cluster wire-server is UNREACHABLE at "
                         default-sock-path " (" (or (.-message e) (str e)) ") "
                         "after " n " attempts. "
                         "The pod boots ONLY against the cluster store — there is "
                         "no local fallback. Start it with: bin/seon start wire-server")
                    {::sock-path default-sock-path
                     ::attempts  n
                     :seon.error/kind :core-bug}))))))
    1)))

(defn ^:async ensure-cluster-db!
  "Ensure this pod's cluster db is registered on the wire-server.

   Sends the `ensure-db` wire op with [[cluster-name]] as the db-name,
   backend `:file`, and [[default-store-path]] — idempotent on the
   registry (a re-ensure returns the existing conn's basis-t), so a
   freshly created cluster's store exists BEFORE the pod attaches its
   DIS-peer conn. Runs at boot between the ping gate and `d/connect`.
   Throws on a not-ok reply (fail-loud, same posture as [[ping!]])."
  ;; Resolves to the wire-server reply map (third-party boundary) — :any.
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [resp (await (wire/rpc default-sock-path
                              {:seon.store.wire/op      "ensure-db"
                               :seon.store.wire/db-name cluster-name
                               :seon.store.wire/backend "file"
                               :seon.store.wire/path    default-store-path}
                              {:timeout-ms wire/ensure-db-timeout-ms}))]
    (when-not (:seon.store.wire/ok resp)
      (throw (ex-info (str "seon.store.wire: ensure-db failed for cluster "
                           cluster-name " — " (:seon.store.wire/error resp))
                      {::db-name cluster-name
                       ::resp resp
                       :seon.error/kind :core-bug})))
    resp))

;; ---------------------------------------------------------------------------
;; Wire datom decode — server datom shape is the native 5-vector [e a v t op]
;; (seon.server.wire/datom->wire). Under the uniform Transit frame a/v arrive
;; native (keyword attr, any value), so we reconstitute REAL datahike Datoms
;; directly — no inner decode.
;; ---------------------------------------------------------------------------

(defn- wire-datoms->datoms [wire-data]
  (mapv (fn [[e a v t added]]
          (dd/datom e a v t added))
        wire-data))

;; ---------------------------------------------------------------------------
;; RYOW deref — resolve a db value at-or-past `basis-t` from the store.
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
                      "seon.store.wire: RYOW violated — deref never reached ack basis-t"
                      {::basis-t basis-t
                       ::max-tx  (:max-tx db)}))))))

;; ---------------------------------------------------------------------------
;; The `:seon-wire` PWriter. Mirrors datahike.http.writer/
;; DatahikeServerWriter: non-streaming (flips deref-conn into
;; follow-the-store mode), dispatches `transact!` over the UDS wire,
;; returns a promise-chan the writer go-loop consumes.
;; ---------------------------------------------------------------------------

(def ^:private max-own-write-correlations
  "Maximum own writes retained while the transaction feed is behind.

   A disconnected feed cannot discard response-first ids without later
   delivering each own transaction twice. Admission therefore stops at this
   explicit bound instead of growing process memory without limit."
  4096)

(declare !adapter fire-own-tx-listeners!)

(defn- attachment-active-for-conn?
  [state conn]
  (and (not= ::stopped (::phase state))
       (identical? conn (::conn state))))

(defn- begin-transaction!
  "Admit one own-write correlation for the attached connection."
  [conn wire-id]
  (let [result (volatile! ::untracked)]
    (swap! !adapter
           (fn [state]
             (if-not (attachment-active-for-conn? state conn)
               state
               (let [correlations (::correlations state)]
                 (if (< (count correlations) max-own-write-correlations)
                   (do
                     (vreset! result ::tracked)
                     (assoc-in state [::correlations wire-id]
                               {::status ::pending}))
                   (do
                     (vreset! result ::saturated)
                     state))))))
    @result))

(defn- correlation-for
  [conn wire-id]
  (let [state @!adapter]
    (when (attachment-active-for-conn? state conn)
      (get-in state [::correlations wire-id]))))

(defn- reject-transaction!
  [conn wire-id]
  (swap! !adapter
         (fn [state]
           (if (attachment-active-for-conn? state conn)
             (update state ::correlations dissoc wire-id)
             state))))

(defn- resolve-transaction!
  "Mark a successful response. Returns true when its feed was already skipped."
  [conn wire-id basis-t]
  (let [feed? (volatile! false)]
    (swap! !adapter
           (fn [state]
             (if-let [entry (when (attachment-active-for-conn? state conn)
                              (get-in state [::correlations wire-id]))]
               (if (::feed-coordinate entry)
                 (do
                   (vreset! feed? true)
                   (update state ::correlations dissoc wire-id))
                 (assoc-in state [::correlations wire-id]
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
  [wire-id]
  (ex-info
   "The transaction feed is too far behind to correlate another own write."
   {:seon.store.wire/id wire-id
    :seon.store.wire/status :seon.store.wire.status/feed-behind
    ::correlation-limit max-own-write-correlations
    :seon.error/kind :core-bug}))

(defn- ambiguous-transaction-error
  [conn wire-id attempts error]
  (let [feed-coordinate (::feed-coordinate (correlation-for conn wire-id))]
    (reject-transaction! conn wire-id)
    (when feed-coordinate
      (fire-own-tx-listeners! conn (::basis-t feed-coordinate)))
    (ex-info
     (str "wire transaction lost every reply after " attempts
          " idempotent attempt(s); commit status remains unknown. "
          "The transaction must only be retried with the same wire id.")
     {:seon.store.wire/id wire-id
      :seon.store.wire/status :seon.store.wire.status/unknown
      :seon.store.wire/attempts attempts
      :seon.store.wire/basis-t (:max-tx @conn)
      :seon.store.wire/rpc-failure
      (:seon.store.wire/rpc-failure (ex-data error))
      :seon.error/kind :core-bug}
     error)))

(defn- transact-rpc!
  "Send one frozen request, resubmitting the same durable id on reply loss."
  [sock-path request attempt]
  (-> (wire/rpc sock-path request {:timeout-ms wire/transact-timeout-ms})
      (.catch
       (fn [error]
         (if (< attempt wire/transact-attempts)
           (do
             (js/console.warn
              (str "[seon.store.wire] transaction reply lost; retrying the same id "
                   (:seon.store.wire/id request) " (attempt " (inc attempt)
                   "/" wire/transact-attempts ")"))
             (transact-rpc! sock-path request (inc attempt)))
           (js/Promise.reject error))))))

(defn- rejected-response-error
  [response generated?]
  (let [error-kind (:seon.store.wire/error-kind response)
        candidate  (:seon.store.wire/generated-candidate response)
        allocator-protocol? (and generated? (= "protocol" error-kind))]
    (ex-info
     (str "wire transaction failed: " (:seon.store.wire/error response))
     (cond->
      {::error-kind error-kind
       :seon.error/kind
       (if (= "generated-candidate-conflict" error-kind)
         :user-input
         :core-bug)}
       (= "generated-candidate-conflict" error-kind)
       (assoc :seon.db.id/error :seon.db.id.error/candidate-conflict)
       candidate
       (assoc :seon.db.id/generated-candidate candidate)
       allocator-protocol?
       (assoc :seon.db.id/error
              :seon.db.id.error/invalid-allocation-transaction)))))

(defn- response-processing-error
  [conn wire-id response error]
  (let [feed-coordinate (::feed-coordinate (correlation-for conn wire-id))]
    (reject-transaction! conn wire-id)
    (when feed-coordinate
      (fire-own-tx-listeners! conn (::basis-t feed-coordinate)))
    (ex-info
     "A committed wire transaction reply could not be materialized locally."
     {:seon.store.wire/id wire-id
      :seon.store.wire/status :seon.store.wire.status/committed
      :seon.store.wire/basis-t (:seon.store.wire/basis-t response)
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

(defrecord SeonWireWriter [sock-path conn lifecycle]
  w/PWriter
  (-dispatch! [_ {:keys [op args]}]
    (let [p (promise-chan)]
      (if-not (register-writer-operation! lifecycle p)
        (put! p (ex-info "seon-wire writer is shut down."
                         {::op op
                          :seon.error/kind :core-bug}))
        (if (not= op 'transact!)
          (finish-writer-operation!
            lifecycle p
            (ex-info "seon-wire writer supports only transact!" {::op op}))
          (let [arg-map    (first args)
                tx-data    (if (map? arg-map) (:tx-data arg-map) arg-map)
                tx-meta    (when (map? arg-map) (:tx-meta arg-map))
                generated? (and (map? arg-map)
                              (contains? arg-map
                                         :seon.db.id/generated-candidates))
              generated-candidates
              (when (map? arg-map)
                (:seon.db.id/generated-candidates arg-map))
              wire-id    (str (random-uuid))
              ;; Every write is db-name-routed to THIS pod's cluster db —
              ;; N pods can share one wire-server without ambient-conn
              ;; cross-talk (the registry resolves the conn per request).
              req        (cond-> {:seon.store.wire/op       "transact"
                                  :seon.store.wire/db-name  cluster-name
                                  :seon.store.wire/tx-data  tx-data
                                  :seon.store.wire/id       wire-id}
                           (seq tx-meta)
                           (assoc :seon.store.wire/tx-meta tx-meta)
                           generated?
                           (assoc :seon.store.wire/generated-candidates
                                  generated-candidates))
              tracking   (begin-transaction! conn wire-id)]
          (if (= ::saturated tracking)
            (finish-writer-operation!
             lifecycle p (correlation-capacity-error wire-id))
            (-> (transact-rpc! sock-path req 1)
              (.then
               (fn [resp]
                 ;; A reply WAS read — no commit ambiguity from here on. Any
                 ;; throw in this post-reply processing (e.g. the RYOW guard)
                 ;; is put directly, so the .catch below stays rpc-layer-only.
                 (try
                   (if-not (:seon.store.wire/ok resp)
                     (do
                       (reject-transaction! conn wire-id)
                       (finish-writer-operation!
                        lifecycle p
                        (rejected-response-error resp generated?)))
                     ;; RYOW: resolve only once a local deref is at/past
                     ;; the ack'd basis-t. The synthesized report carries
                     ;; the MATERIALIZED post-tx db value, so straight-line
                     ;; transact!-then-read code just works.
                     (let [bt      (:seon.store.wire/basis-t resp)
                           db      (ryow-deref! conn bt)
                           tempids (:seon.store.wire/tempids resp)
                           tx-meta (:seon.store.wire/tx-meta resp)
                           generated-eids
                           (:seon.store.wire/generated-eids resp)
                           report
                           (cond-> {:db-after db
                                    :tx-data  (wire-datoms->datoms
                                               (:seon.store.wire/tx-data resp))
                                    :tempids  (or tempids {})
                                        ;; The sole writer (JVM wire-server)
                                        ;; computes the honest added/retracted
                                        ;; split over the REAL :added flags
                                        ;; (`tx-report->ok-map`). Carry those
                                        ;; counts on the synthesized report so
                                        ;; `transact-success-envelope` reports
                                        ;; them verbatim instead of re-deriving
                                        ;; from reconstituted datoms.
                                    :datoms-added     (:seon.store.wire/datoms-added resp)
                                    :datoms-retracted (:seon.store.wire/datoms-retracted resp)}
                             (seq generated-eids)
                             (assoc :seon.db.id/generated-eids generated-eids)
                             (:seon.store.wire/recovered? resp)
                             (assoc :seon.store.wire/recovered? true)
                             (some? tx-meta) (assoc :tx-meta tx-meta)
                             (:seon.store.wire/basis-t-before resp)
                             (assoc :db-before
                                    (d/as-of db
                                             (:seon.store.wire/basis-t-before
                                              resp))))]
                       (resolve-transaction! conn wire-id bt)
                       (finish-writer-operation! lifecycle p report)))
                   (catch :default e
                     (finish-writer-operation!
                       lifecycle p
                       (response-processing-error conn wire-id resp e))))))
              (.catch
               (fn [e]
                 (finish-writer-operation!
                   lifecycle p
                   (ambiguous-transaction-error
                     conn wire-id wire/transact-attempts
                     (if (instance? js/Error e)
                       e
                       (js/Error. (str e))))))))))))
      p))
  (-shutdown [_] (shutdown-writer! lifecycle))
  (-streaming? [_] false))

(defmethod w/create-writer :seon-wire
  [{:keys [sock-path]} connection]
  (->SeonWireWriter (or sock-path default-sock-path)
                    connection
                    (atom {::writer-open? true
                           ::writer-pending #{}})))

(defmethod connector/-connect* :seon-wire [config opts]
  (connector/-connect-impl* config opts))

;; ---------------------------------------------------------------------------
;; listen! adapter — foreign writes fire the pod's native conn listeners.
;;
;; `seon.db/listen!` installs handlers via `d/listen` on the conn, which
;; stores them in `(:listeners (meta conn))` (the Connection proxies meta
;; to its wrapped-atom; connector.cljc:32,84). Own txs fire them via
;; `datahike.writer/transact!` (writer.cljc:247). For FOREIGN txs we
;; synthesize the same raw tx-report shape from the wire feed event and
;; fire the same listener atom — ONE bus, two tx origins.
;; ---------------------------------------------------------------------------

(defn- stopped-adapter-state
  [generation]
  {::phase ::stopped
   ::generation generation
   ::correlations {}})

(defonce ^:private !adapter
  ;; One lifecycle owner for the feed attachment and own-write correlation.
  ;; It contains only live resources and pure coordinates — never a db value.
  (atom (stopped-adapter-state 0)))

(defn- connection-coordinate
  "Derive the branch-qualified identity of one Datahike connection."
  [db]
  (let [config         (:config db)
        database-id    (get-in config [:store :id])
        branch         (:branch config)
        writer-backend (get-in config [:writer :backend])]
    (when-not (uuid? database-id)
      (throw (ex-info "The attached database has no UUID store identity."
                      {::store-id database-id
                       :seon.error/kind :core-bug})))
    (when-not (keyword? branch)
      (throw (ex-info "The attached database has no branch identity."
                      {::branch branch
                       :seon.error/kind :core-bug})))
    (when-not (keyword? writer-backend)
      (throw (ex-info "The attached database has no writer backend identity."
                      {::writer-backend writer-backend
                       :seon.error/kind :core-bug})))
    {::db-name cluster-name
     ::store-id database-id
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

(defn- cleanup-adapter-resources!
  [state]
  (clear-reconnect-timer! (::reconnect-timer state))
  (destroy-socket! (::socket state)))

(defn- stop-active-adapter!
  "Stop one active generation and dispose every resource it owns."
  ([] (stop-active-adapter! nil))
  ([expected-generation]
   (let [stopped? (volatile! false)
         [before _]
         (swap-vals!
          !adapter
          (fn [state]
            (if (and (not= ::stopped (::phase state))
                     (or (nil? expected-generation)
                         (= expected-generation (::generation state))))
              (do
                (vreset! stopped? true)
                (stopped-adapter-state (inc (::generation state))))
              state)))]
     (when @stopped?
       (cleanup-adapter-resources! before))
     @stopped?)))

(defn stop-listen-adapter!
  "Stop the transaction feed and dispose its socket, timer, and correlations."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (stop-active-adapter!))

(defn adapter-status
  "Live adapter state for diagnostics.

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
  (let [state @!adapter]
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
            (js/console.warn "[seon.store.wire adapter]" (pr-str k)
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
      (js/console.warn "[seon.store.wire] fire-own-tx-listeners! failed for tx"
                       tx-t ":" (str e)))))

(defn- advance-progress
  [state basis-t]
  (-> state
      (assoc ::last-applied-coordinate
             (progress-coordinate (::database-coordinate state) basis-t))
      (update ::correlations prune-resolved-correlations basis-t)))

(defn- apply-own-feed-event!
  [generation conn wire-id basis-t]
  ;; Materialize before advancing. A stale generation is checked again inside
  ;; the atomic update so an A→B reattach during the deref cannot mutate B.
  (ryow-deref! conn basis-t)
  (swap! !adapter
         (fn [state]
           (if-not (active-generation? state generation conn)
             state
             (let [correlation (get-in state [::correlations wire-id])
                   feed-coordinate
                   (progress-coordinate (::database-coordinate state) basis-t)]
               (-> (if (= ::resolved (::status correlation))
                     (update state ::correlations dissoc wire-id)
                     (assoc-in state [::correlations wire-id ::feed-coordinate]
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
                         :tx-data   (wire-datoms->datoms
                                     (:seon.store.wire/tx-data ev))}
                  (some? (:seon.store.wire/tx-meta ev))
                  (assoc :tx-meta (:seon.store.wire/tx-meta ev)))
        applied? (volatile! false)]
    (swap! !adapter
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
  (let [state        @!adapter
        wire-id      (:seon.store.wire/id ev)
        basis-t      (:seon.store.wire/basis-t ev)
        basis-before (:seon.store.wire/basis-t-before ev)
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
      (and wire-id (get-in state [::correlations wire-id]))
      (apply-own-feed-event! generation conn wire-id basis-t)

      :else
      (apply-foreign-feed-event!
       generation conn basis-t basis-before ev))))

(defn- feed-event-dispatch!
  ;; Apply one pub frame: `tx` events for this pod's cluster route into
  ;; handle-feed-event!; other clusters' transactions are ignored. The pub
  ;; stream is db-agnostic — this is the client-side db-name demux the
  ;; replay-tx reply keys.
  [generation conn db-name ev]
  (when (and (= "tx" (:seon.store.wire/event ev))
             (= db-name (:seon.store.wire/db-name ev)))
    (handle-feed-event! generation conn ev)))

(defn- replay-page-error
  [message data]
  (throw (ex-info (str "seon.store.wire: invalid replay page — " message)
                  (assoc data :seon.error/kind :core-bug))))

(defn- validated-replay-page
  "Validate one replay page before its cursor is allowed to advance.

   A malformed, stale, repeated-without-progress, or prematurely empty page is
   a reconnectable feed failure, never permission to skip a range. The first
   page establishes db-name and through-t; later pages must retain both."
  [cursor expected-through expected-db-name response]
  (when-not (:seon.store.wire/ok response)
    (replay-page-error "writer returned not-ok"
                       {::response response ::cursor cursor}))
  (let [response-since (:seon.store.wire/since-t response)
        through       (:seon.store.wire/through-t response)
        continuation  (:seon.store.wire/continuation-t response)
        done?         (:seon.store.wire/done? response)
        db-name       (:seon.store.wire/db-name response)
        events        (:seon.store.wire/events response)
        replayed      (:seon.store.wire/replayed response)
        basis-ts      (mapv :seon.store.wire/basis-t events)
        before-ts     (mapv :seon.store.wire/basis-t-before events)
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
    (when-not (and (string? db-name) (not (str/blank? db-name)))
      (replay-page-error "db-name is missing"
                         {::response response}))
    (when (and (some? expected-db-name) (not= expected-db-name db-name))
      (replay-page-error "db-name changed between pages"
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
                        (and (= "tx" (:seon.store.wire/event event))
                             (= db-name (:seon.store.wire/db-name event))))
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
    {::db-name db-name
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
    (throw (ex-info "seon.store.wire: pub socket dropped during replay"
                    {::drop-reason reason}))))

(defn- register-feed-socket!
  [generation conn socket]
  (let [registered? (volatile! false)]
    (swap! !adapter
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
  (swap! !adapter
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
  ;; Resolves to {::db-name ::replayed}; throws on connect/replay failure (the
  ;; caller owns retry). Once live, `on-drop` fires ONCE if the connection dies;
  ;; a drop during replay rejects this connect attempt directly.
  [generation conn database-coordinate sock-path pub-sock-path on-drop]
  (let [!connection  (atom {::buffer []
                            ::live? false
                            ::db-name (::db-name database-coordinate)
                            ::closing? false})
        drop-promise (js/Promise.
                      (fn [_resolve reject]
                        (swap! !connection assoc ::reject-drop reject)))
        sock          (await
                       (wire/connect-pub
                        pub-sock-path
                        {:on-event (fn [event]
                                     (if (::live? @!connection)
                                       (feed-event-dispatch!
                                        generation conn
                                        (::db-name @!connection) event)
                                       (buffer-live-event! !connection event)))
                         :on-close (fn [reason]
                                     (let [before @!connection]
                                       (swap! !connection assoc ::drop-reason reason)
                                       (cond
                                         (::live? before)
                                         (when on-drop (on-drop reason))

                                         (not (::closing? before))
                                         ((::reject-drop before)
                                          (ex-info
                                           "seon.store.wire: pub socket dropped during replay"
                                           {::drop-reason reason})))))}))]
    (register-feed-socket! generation conn sock)
    (try
      ;; Replay every tx after the watermark. Pages are applied as they arrive,
      ;; so a failed later page reconnects from the advanced adapter watermark.
      ;; The pub socket remains open and buffers frames for the entire walk.
      (let [adapter-state @!adapter
            _ (when-not (active-generation? adapter-state generation conn)
                (throw (ex-info "The transaction feed attachment was stopped."
                                {::generation generation
                                 :seon.error/kind :core-bug})))
            initial-cursor
            (or (get-in adapter-state
                        [::last-applied-coordinate ::basis-t])
                0)
            expected-db-name (::db-name database-coordinate)
            replay-result
            (loop [cursor initial-cursor
                   through nil
                   replayed 0]
              (throw-if-feed-dropped! !connection)
              (let [response (await
                              (js/Promise.race
                               #js [(wire/replay-tx
                                     sock-path
                                     (cond-> {:since-t cursor
                                              :db-name expected-db-name}
                                       (some? through)
                                       (assoc :through-t through)))
                                    drop-promise]))
                    _        (throw-if-feed-dropped! !connection)
                    page     (validated-replay-page
                              cursor through expected-db-name response)
                    next-db  (::db-name page)
                    total    (+ replayed (::replayed page))]
                (doseq [event (::events page)]
                  (feed-event-dispatch! generation conn next-db event))
                (if (::done? page)
                  {::db-name next-db ::replayed total}
                  (recur (::continuation-t page)
                         (::through-t page)
                         total))))]
        (throw-if-feed-dropped! !connection)
        (when-not (active-generation? @!adapter generation conn)
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
             generation conn (::db-name @!connection) event)))
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
    (swap! !adapter
           (fn [state]
             (if (active-generation? state generation)
               (do
                 (vreset! activated? true)
                 (-> state
                     (assoc ::phase ::live
                            ::db-name (::db-name result)
                            ::socket (::socket result))
                     (dissoc ::reconnect-timer ::ready)))
               state)))
    (when-not @activated?
      (destroy-socket! (::socket result))
      (throw (ex-info "The transaction feed attachment was replaced before activation."
                      {::generation generation
                       :seon.error/kind :core-bug})))
    (log/info-console!
     "seon.store.wire"
     (str "tx-feed " (if reconnected? "re-connected" "live")
          " (pub socket, db " (::db-name result)
          ", replayed " (::replayed result) ")"))
    (::db-name result)))

(defn- connect-current-attachment!
  [generation]
  (let [state @!adapter]
    (if-not (active-generation? state generation)
      (js/Promise.reject
       (ex-info "The transaction feed attachment is no longer active."
                {::generation generation
                 :seon.error/kind :core-bug}))
      (connect-feed!
       generation
       (::conn state)
       (::database-coordinate state)
       (::sock-path state)
       (::pub-sock-path state)
       #(schedule-reconnect! generation %)))))

(defn- launch-reconnect!
  [generation]
  (when (active-generation? @!adapter generation)
    (-> (connect-current-attachment! generation)
        (.then #(activate-feed! generation % true))
        (.catch
         (fn [error]
           (when (active-generation? @!adapter generation)
             (schedule-reconnect!
              generation (or (.-message error) (str error)))))))))

(defn- schedule-reconnect!
  "Schedule one reconnect owned by the active attachment generation."
  [generation reason]
  (let [!timer (volatile! nil)
        scheduled? (volatile! false)
        fire! (fn []
                (let [claimed? (volatile! false)]
                  (swap! !adapter
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
        timer (js/setTimeout fire! wire/feed-reconnect-delay-ms)]
    (vreset! !timer timer)
    (swap! !adapter
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
       "seon.store.wire"
       (str "tx-feed pub connection lost (" reason ") — reconnecting in "
            wire/feed-reconnect-delay-ms "ms"))
      (js/clearTimeout timer))))

(defn- same-attachment?
  [state conn database-coordinate sock-path pub-sock-path]
  (and (attachment-active-for-conn? state conn)
       (= database-coordinate (::database-coordinate state))
       (= sock-path (::sock-path state))
       (= pub-sock-path (::pub-sock-path state))))

(defn ^:async start-listen-adapter!
  "Connect the persistent pub-socket tx feed and pump FOREIGN tx into the conn.

   ONE streaming pub-socket connection (push) replaces the old req-socket
   poll pump: the wire-server writes every committed tx event down the
   stream, and the frame reader feeds the conn's native listeners directly.
   Idempotent for the same connection and coordinate. A different connection,
   branch, or transport path atomically disposes the old attachment before the
   new one connects. Resilient: a
   drop logs LOUDLY, reconnects after `wire/feed-reconnect-delay-ms`, and recovers the gap via the
   req-socket `replay-tx` from the basis-t watermark (DE-2 lossless wake) —
   the adapter never dies silently. The FIRST connect is fail-loud (boot is
   ping-gated; a pod that can't reach its feed must not run).

   Map-in: `{::conn <conn> ::sock-path <path>? ::pub-sock-path <path>?}`.
   Returns a Promise of the feed's db-name once a new attachment is live. An
   already-active identical attachment returns its db-name without opening a
   second socket."
  {:malli/schema [:=> [:cat [:map [::conn ::conn]
                                  [::sock-path {:optional true} ::sock-path]
                                  [::pub-sock-path {:optional true} ::sock-path]]] :any]}
  [{::keys [conn sock-path pub-sock-path]
    :or {sock-path default-sock-path pub-sock-path default-pub-sock-path}}]
  (let [db                  @conn
        database-coordinate (connection-coordinate db)
        basis-t             (:max-tx db)
        current             @!adapter]
    (when-not (integer? basis-t)
      (throw (ex-info "The attached database has no integer basis-t."
                      {::basis-t basis-t
                       :seon.error/kind :core-bug})))
    (if (same-attachment?
         current conn database-coordinate sock-path pub-sock-path)
      (await (or (::ready current)
                 (js/Promise.resolve (::db-name database-coordinate))))
      (let [generation (inc (::generation current))
            state {::phase ::connecting
                   ::generation generation
                   ::conn conn
                   ::database-coordinate database-coordinate
                   ::last-applied-coordinate
                   (progress-coordinate database-coordinate basis-t)
                   ::sock-path sock-path
                   ::pub-sock-path pub-sock-path
                   ::own-skips 0
                   ::correlations {}}]
        ;; Publish the new generation before destroying old resources. Any old
        ;; socket callback caused by `.destroy` then observes itself as stale.
        (reset! !adapter state)
        (cleanup-adapter-resources! current)
        (let [ready (-> (connect-current-attachment! generation)
                        (.then #(activate-feed! generation % false))
                        (.catch
                         (fn [error]
                           (stop-active-adapter! generation)
                           (throw error))))]
          (swap! !adapter
                 (fn [adapter]
                   (if (active-generation? adapter generation conn)
                     (assoc adapter ::ready ready)
                     adapter)))
          (await ready))))))
