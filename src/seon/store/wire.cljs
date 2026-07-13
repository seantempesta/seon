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

(def !transactions
  "In-flight wire transactions keyed by their durable `:seon.store.wire/id`.

   Each entry is pending or response-resolved and may carry the basis-t of an
   already-suppressed feed event. Keeping this tiny state machine, instead of a
   bare id set, lets a same-id retry recover a lost reply without either firing
   native listeners twice or losing the feed event when every reply is lost."
  (atom {}))

(declare !adapter fire-own-tx-listeners!)

(defn- begin-transaction!
  [wire-id]
  (swap! !transactions assoc wire-id {::status ::pending}))

(defn- resolve-transaction!
  "Mark a successful response. Returns true when its feed was already skipped."
  [wire-id basis-t]
  (let [entry (get @!transactions wire-id)
        feed? (some? (::feed-t entry))]
    (if (or feed? (not (:started? @!adapter)))
      (swap! !transactions dissoc wire-id)
      (swap! !transactions assoc wire-id
             (assoc entry ::status ::resolved ::basis-t basis-t)))
    feed?))

(defn- prune-resolved-transactions!
  [basis-t]
  (swap! !transactions
         (fn [transactions]
           (into {}
                 (remove (fn [[_ transaction]]
                           (and (= ::resolved (::status transaction))
                                (some? (::basis-t transaction))
                                (<= (::basis-t transaction) basis-t))))
                 transactions))))

(defn- reject-transaction!
  [wire-id]
  (swap! !transactions dissoc wire-id))

(defn- ambiguous-transaction-error
  [conn wire-id attempts error]
  (let [feed-t (::feed-t (get @!transactions wire-id))]
    (swap! !transactions dissoc wire-id)
    (when feed-t
      (fire-own-tx-listeners! conn feed-t))
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
  (let [feed-t (::feed-t (get @!transactions wire-id))]
    (reject-transaction! wire-id)
    (when feed-t
      (fire-own-tx-listeners! conn feed-t))
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
                                  generated-candidates))]
          (begin-transaction! wire-id)
          (-> (transact-rpc! sock-path req 1)
              (.then
               (fn [resp]
                 ;; A reply WAS read — no commit ambiguity from here on. Any
                 ;; throw in this post-reply processing (e.g. the RYOW guard)
                 ;; is put directly, so the .catch below stays rpc-layer-only.
                 (try
                   (if-not (:seon.store.wire/ok resp)
                     (do
                       (reject-transaction! wire-id)
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
                       (resolve-transaction! wire-id bt)
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
                       (js/Error. (str e)))))))))))
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

(defonce ^:private !adapter
  ;; {:started? bool :connected? bool :db-name str :feed-gen int
  ;;  :last-db <db> :own-skips int :last-applied-t int}
  ;; :last-applied-t is the basis-t watermark — the highest tx basis-t already
  ;; applied (own or foreign). Drives BOTH the (re)connect replay-tx (we replay
  ;; from it) and feed idempotency (a tx ≤ it is a no-op). :feed-gen guards
  ;; reconnect scheduling: each failure path consumes the current generation,
  ;; so a drop AND a connect-attempt error can never schedule two loops.
  (atom {:started? false}))

(defn adapter-status
  "Live adapter state for diagnostics.

   `{::started? ::connected? ::db-name ::own-skips ::last-applied-t}`.
   `::last-applied-t` is the basis-t watermark the reconnect path replays
   from (DE-2); `::connected?` is the pub-socket connection's liveness."
  {:malli/schema [:=> [:cat] [:map [::started? :boolean]]]}
  []
  (let [{:keys [started? connected? db-name own-skips last-applied-t]} @!adapter]
    (cond-> {::started? (boolean started?)}
      (some? connected?)     (assoc ::connected? (boolean connected?))
      (some? db-name)        (assoc ::db-name db-name)
      (some? own-skips)      (assoc ::own-skips own-skips)
      (some? last-applied-t) (assoc ::last-applied-t last-applied-t))))

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

(defn- handle-feed-event! [conn ev]
  (let [wid          (:seon.store.wire/id ev)
        bt           (:seon.store.wire/basis-t ev)
        last-applied (:last-applied-t @!adapter)]
    (cond
      ;; IDEMPOTENT: a tx at or below the last-applied basis-t was already
      ;; applied — a no-op. This makes the since-t reconnect replay safe: the
      ;; replay↔live boundary can deliver a tx by BOTH paths (same basis-t), and
      ;; any duplicate/overlap is dropped here without re-firing listeners.
      ;; Events arrive in commit order (replay ascending, then live ascending),
      ;; so a monotonic basis-t watermark is sufficient — no per-tx dedup set.
      (and (some? bt) (some? last-applied) (<= bt last-applied))
      nil

      ;; An in-flight or response-resolved own transaction. Pending feed-first
      ;; state retains the basis-t for terminal recovery; response-first state
      ;; removes on this matching feed. In both cases the response-side native
      ;; Datahike wrapper is the one listener delivery.
      (boolean (and wid (get @!transactions wid)))
      (let [transaction (get @!transactions wid)
            db          (ryow-deref! conn bt)]
        (if (= ::resolved (::status transaction))
          (swap! !transactions dissoc wid)
          (swap! !transactions assoc-in [wid ::feed-t] bt))
        (swap! !adapter #(-> %
                             (update :own-skips (fnil inc 0))
                             (assoc :last-db db :last-applied-t bt)))
        (prune-resolved-transactions! bt))

      ;; FOREIGN tx (another agent / a human message — incl. every tx that
      ;; landed during a feed gap, since the pod can't write while the UDS is
      ;; down): synthesize the raw report and fire the conn's native listeners.
      :else
      (let [db-before (or (:last-db @!adapter) @conn)
            db        (ryow-deref! conn bt)
            tx-meta   (:seon.store.wire/tx-meta ev)
            report    (cond-> {:db-after  db
                               :db-before db-before
                               :tx-data   (wire-datoms->datoms
                                           (:seon.store.wire/tx-data ev))}
                        (some? tx-meta) (assoc :tx-meta tx-meta))]
        (swap! !adapter assoc :last-db db :last-applied-t bt)
        (prune-resolved-transactions! bt)
        (fire-native-listeners! conn report)))))

(defn- feed-event-dispatch!
  ;; Apply one pub frame: `tx` events for this pod's cluster route into
  ;; handle-feed-event!; other clusters' transactions are ignored. The pub
  ;; stream is db-agnostic — this is the client-side db-name demux the
  ;; replay-tx reply keys.
  [conn db-name ev]
  (when (and (= "tx" (:seon.store.wire/event ev))
             (= db-name (:seon.store.wire/db-name ev)))
    (handle-feed-event! conn ev)))

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

(defn- throw-if-feed-dropped!
  [drop-reason]
  (when-let [reason @drop-reason]
    (throw (ex-info "seon.store.wire: pub socket dropped during replay"
                    {::drop-reason reason}))))

(defn ^:async ^:private connect-feed!
  ;; One pub-socket feed connection: connect, walk bounded replay pages under
  ;; one fixed upper watermark, drain live frames buffered throughout, go live.
  ;; Resolves to {::db-name ::replayed}; throws on connect/replay failure (the
  ;; caller owns retry). Once live, `on-drop` fires ONCE if the connection dies;
  ;; a drop during replay rejects this connect attempt directly.
  [conn sock-path pub-sock-path on-drop]
  (let [!buffer      (atom [])
        !live?       (atom false)
        !db-name     (atom nil)
        !drop-reason (atom nil)
        !closing?    (atom false)
        !reject-drop (atom nil)
        drop-promise (js/Promise.
                      (fn [_resolve reject]
                        (reset! !reject-drop reject)))
        sock          (await
                       (wire/connect-pub
                        pub-sock-path
                        {:on-event (fn [event]
                                     (if @!live?
                                       (feed-event-dispatch! conn @!db-name event)
                                       (swap! !buffer conj event)))
                         :on-close (fn [reason]
                                     (reset! !drop-reason reason)
                                     (cond
                                       @!live?
                                       (when on-drop (on-drop reason))

                                       (not @!closing?)
                                       (@!reject-drop
                                        (ex-info
                                         "seon.store.wire: pub socket dropped during replay"
                                         {::drop-reason reason}))))}))]
    (try
      ;; Replay every tx after the watermark. Pages are applied as they arrive,
      ;; so a failed later page reconnects from the advanced adapter watermark.
      ;; The pub socket remains open and buffers frames for the entire walk.
      (let [initial-cursor (or (:last-applied-t @!adapter) 0)
            replay-result
            (loop [cursor initial-cursor
                   through nil
                   db-name nil
                   replayed 0]
              (throw-if-feed-dropped! !drop-reason)
              (let [response (await
                              (js/Promise.race
                               #js [(wire/replay-tx
                                     sock-path
                                     (cond-> {:since-t cursor
                                              :db-name cluster-name}
                                       (some? through)
                                       (assoc :through-t through)))
                                    drop-promise]))
                    _        (throw-if-feed-dropped! !drop-reason)
                    page     (validated-replay-page cursor through db-name response)
                    next-db  (::db-name page)
                    total    (+ replayed (::replayed page))]
                (reset! !db-name next-db)
                (doseq [event (::events page)]
                  (feed-event-dispatch! conn next-db event))
                (if (::done? page)
                  {::db-name next-db ::replayed total}
                  (recur (::continuation-t page)
                         (::through-t page)
                         next-db
                         total))))]
        (throw-if-feed-dropped! !drop-reason)
        ;; Go live, then drain — one synchronous block (no await between), so
        ;; no frame can slip between the flip and drain or be applied twice.
        ;; Watermark idempotency removes replay/buffer overlap.
        (let [buffered @!buffer]
          (reset! !live? true)
          (reset! !buffer [])
          (doseq [event buffered]
            (feed-event-dispatch! conn @!db-name event)))
        replay-result)
      (catch :default error
        (reset! !closing? true)
        (try (.destroy ^js sock) (catch :default _))
        (throw error)))))

(defn- schedule-reconnect!
  ;; Consume feed generation `gen` and schedule ONE reconnect after
  ;; `wire/feed-reconnect-delay-ms`. A no-op when `gen` is stale (the other
  ;; failure path of the same attempt already consumed it) — a drop and a
  ;; failed connect can never race two loops.
  [gen reason reconnect!]
  (when (= gen (:feed-gen @!adapter))
    (swap! !adapter #(-> % (update :feed-gen inc) (assoc :connected? false)))
    (log/error-console!
     "seon.store.wire"
     (str "tx-feed pub connection lost (" reason ") — reconnecting in "
          wire/feed-reconnect-delay-ms "ms"))
    (js/setTimeout reconnect! wire/feed-reconnect-delay-ms)))

(defn ^:async start-listen-adapter!
  "Connect the persistent pub-socket tx feed and pump FOREIGN tx into the conn.

   ONE streaming pub-socket connection (push) replaces the old req-socket
   poll pump: the wire-server writes every committed tx event down the
   stream, and the frame reader feeds the conn's native listeners directly.
   Idempotent (defonce-guarded) — a second call is a no-op. Resilient: a
   drop logs LOUDLY, reconnects after `wire/feed-reconnect-delay-ms`, and recovers the gap via the
   req-socket `replay-tx` from the basis-t watermark (DE-2 lossless wake) —
   the adapter never dies silently. The FIRST connect is fail-loud (boot is
   ping-gated; a pod that can't reach its feed must not run).

   Map-in: `{::conn <conn> ::sock-path <path>? ::pub-sock-path <path>?}`.
   Returns a Promise of the feed's db-name once the first connection is
   live (nil if already started)."
  {:malli/schema [:=> [:cat [:map [::conn ::conn]
                                  [::sock-path {:optional true} ::sock-path]
                                  [::pub-sock-path {:optional true} ::sock-path]]] :any]}
  [{::keys [conn sock-path pub-sock-path]
    :or {sock-path default-sock-path pub-sock-path default-pub-sock-path}}]
  (if (:started? @!adapter)
    (do (log/info-console! "seon.store.wire"
                           "listen adapter already started — no-op")
        nil)
    (do
      ;; Seed the basis-t watermark at the local snapshot's basis-t: the
      ;; connect-time replay-tx delivers anything committed past it, so
      ;; nothing between the snapshot read and feed-live is missed.
      (swap! !adapter assoc :started? true :feed-gen 0
             :last-db @conn :last-applied-t (:max-tx @conn))
      (let [go-live!   (fn [{db-name ::db-name replayed ::replayed} re?]
                         (swap! !adapter assoc :connected? true :db-name db-name)
                         (log/info-console!
                          "seon.store.wire"
                          (str "tx-feed " (if re? "re-connected" "live")
                               " (pub socket, db " db-name
                               ", replayed " replayed ")")))
            reconnect! (fn reconnect! []
                         (let [gen (:feed-gen @!adapter)]
                           (-> (connect-feed! conn sock-path pub-sock-path
                                              (fn [reason]
                                                (schedule-reconnect! gen reason reconnect!)))
                               (.then (fn [res] (go-live! res true)))
                               (.catch (fn [e]
                                         (schedule-reconnect!
                                          gen (or (.-message e) (str e)) reconnect!))))))
            res        (await (connect-feed! conn sock-path pub-sock-path
                                             (fn [reason]
                                               (schedule-reconnect! 0 reason reconnect!))))]
        (go-live! res false)
        (::db-name res)))))
