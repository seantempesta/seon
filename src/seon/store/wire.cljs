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
     wire tx feed; on each FOREIGN tx event (own write-ids skipped —
     own txs already fire the conn's native listeners via
     `datahike.writer/transact!`) it re-derefs and fires the conn's
     NATIVE `d/listen` listeners with a synthesized raw tx-report. So
     every `seon.db/listen!` handler (user-message triggers, inspector
     SSE) fires identically for own and foreign writes.

   Proven off-pod by the Stage A/B regression pair
   (`clj -M:replica-probe-jvm` 10/10, `clj -M:replica-peer-jvm` 14/14);
   prototype: `seon.dev.replica-peer` (which stays as the harness).
   Research: docs/prds/agent-runtime/research/datahike-native-replica-2026-06-09.md."
  (:require
   [cljs.core.async :refer [promise-chan put!]]
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

(schema/register! ::store-id-request [:map [::sock-path ::sock-path]])
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

(def default-store-path
  "The cluster's konserve `:file` store dir. Cluster-isolation-aware: reads
   `SEON_CLUSTER_DIR` from the pod's environment first (set+exported by an
   isolated launcher like `bin/acme`) as `$SEON_CLUSTER_DIR/store`, falling
   back to the live-default constant when unset/blank. Under the default
   deployment (`bin/seon`, which does NOT export `SEON_CLUSTER_DIR`) it
   resolves byte-identically to the old constant. Matches the wire-server's
   `--path $SEON_CLUSTER_DIR/store`."
  (if-let [dir (platform/env-val "SEON_CLUSTER_DIR")]
    (str dir "/store")
    "data/clusters/default/store"))

(defn store-id
  "The cluster store's konserve `:id`, replicated from the JVM side:
   `seon.server.store/name->uuid` = `UUID/nameUUIDFromBytes` (md5
   name-based v3 UUID) of the db-name keyword's `str`, where the
   wire-server derives the db-name as `:seon.server/<req-sock basename>`
   (seon.server.wire/opts->config-for-request). Verified against the
   live wire-server's `(:id (:store (:config @conn)))`."
  {:malli/schema [:=> [:cat ::store-id-request] :uuid]}
  [{::keys [sock-path]}]
  (let [basename (last (str/split sock-path #"/"))
        nm       (str ":seon.server/" basename)
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
  "datahike config for the pod's DIS-peer connection to the default
   cluster store. Reads are local konserve; writes route through the
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
                         :id      (store-id {::sock-path default-sock-path})
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
;; The ping retries for ~10s before the fail-loud throw: `bin/seon
;; start all` brings the wire-server and pod up in order, but the pod
;; can exec before the writer's UDS socket accepts (or while a freshly
;; sha-bumped JVM warms up). Boot stays fail-loud, just not
;; fail-instant — after the budget the same error throws.
;; ---------------------------------------------------------------------------

(defn- sleep [ms]
  (js/Promise. (fn [res] (js/setTimeout res ms))))

(def ^:private ping-attempts 5)
(def ^:private ping-timeout-ms 2000)
(def ^:private ping-retry-delay-ms 500)

(defn ^:async ^:private ping-once!
  "One ping rpc. Resolves to the reply map; throws on not-ok/transport."
  []
  (let [resp (await (wire/rpc default-sock-path {"op" "ping"}
                              {:timeout-ms ping-timeout-ms}))]
    (when-not (get resp "ok")
      (throw (ex-info "wire-server ping returned not-ok"
                      {::resp resp})))
    resp))

(defn ^:async ping!
  "Ping the wire-server, retrying up to `ping-attempts` times (~10s
   worst case: 5 × 2s rpc timeout, 500ms backoff between attempts).
   Resolves to the reply map on success; throws a clear, actionable
   error once the budget is exhausted."
  []
  (await
   ((fn ^:async attempt [n]
      (try
        (await (ping-once!))
        (catch :default e
          (if (< n ping-attempts)
            (do (js/console.warn
                 (str "[seon.store.wire] ping attempt " n "/" ping-attempts
                      " failed (" (or (.-message e) (str e))
                      ") — retrying in " ping-retry-delay-ms "ms"))
                (await (sleep ping-retry-delay-ms))
                (await (attempt (inc n))))
            (throw (ex-info
                    (str "seon.store.wire: the cluster wire-server is UNREACHABLE at "
                         default-sock-path " (" (or (.-message e) (str e)) ") "
                         "after " n " attempts (~10s). "
                         "The pod boots ONLY against the cluster store — there is "
                         "no local fallback. Start it with: bin/seon start wire-server")
                    {::sock-path default-sock-path
                     ::attempts  n
                     :seon.error/kind :core-bug}))))))
    1)))

;; ---------------------------------------------------------------------------
;; Wire datom decode — server datom shape is [e a-transit v-transit t op]
;; (seon.server.wire/datom->wire). Reconstituted as REAL datahike Datoms
;; so tx-reports / handler inputs are contract-faithful.
;; ---------------------------------------------------------------------------

(defn- wire-datoms->datoms [wire-data]
  (mapv (fn [[e a v t added]]
          (dd/datom e (wire/readT a) (wire/readT v) t added))
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

(def !own-write-ids
  "write-ids of txs THIS pod dispatched — the wire-protocol per-write
   ECHO-SUPPRESSION set. The pod mints a UUID per forwarded write; the
   wire-server threads it into the committed tx-meta under
   `:seon.store.wire/write-id` and echoes it back on the broadcast feed.
   The listen adapter skips a feed event whose write-id is in this set
   (own txs already fired the conn's native listeners via
   `datahike.writer/transact!`)."
  (atom #{}))

(def ^:private transact-timeout-ms
  "Wire rpc timeout for transacts. Generous: the boot core-index
   transact carries thousands of rows in one tx."
  30000)

(defrecord SeonWireWriter [sock-path conn]
  w/PWriter
  (-dispatch! [_ {:keys [op args]}]
    (let [p (promise-chan)]
      (if (not= op 'transact!)
        (put! p (ex-info "seon-wire writer supports only transact!"
                         {::op op}))
        (let [arg-map    (first args)
              tx-data    (if (map? arg-map) (:tx-data arg-map) arg-map)
              tx-meta    (when (map? arg-map) (:tx-meta arg-map))
              write-id   (str (random-uuid))
              req        (cond-> {"op"         "transact"
                                  "tx-data"    (wire/T tx-data)
                                  "request-id" write-id}
                           (seq tx-meta) (assoc "tx-meta" (wire/T tx-meta)))]
          (swap! !own-write-ids conj write-id)
          (-> (wire/rpc sock-path req {:timeout-ms transact-timeout-ms})
              (.then
               (fn [resp]
                 (if-not (get resp "ok")
                   (put! p (ex-info (str "wire transact failed: "
                                         (get resp "error"))
                                    {::error-kind (get resp "error-kind")
                                     :seon.error/kind :user-input}))
                   ;; RYOW: resolve only once a local deref is at/past
                   ;; the ack'd basis-t. The synthesized report carries
                   ;; the MATERIALIZED post-tx db value, so straight-line
                   ;; transact!-then-read code just works.
                   (let [bt      (get resp "basis-t")
                         db      (ryow-deref! conn bt)
                         tempids (wire/readT (get resp "tempids"))
                         tx-meta (wire/readT (get resp "tx-meta"))]
                     (put! p (cond-> {:db-after db
                                      :tx-data  (wire-datoms->datoms
                                                 (get resp "tx-data"))
                                      :tempids  (or tempids {})
                                      ;; The sole writer (JVM wire-server)
                                      ;; computes the honest added/retracted
                                      ;; split over the REAL :added flags
                                      ;; (`tx-report->ok-map`). Carry those
                                      ;; counts on the synthesized report so
                                      ;; `transact-success-envelope` reports
                                      ;; them verbatim instead of re-deriving
                                      ;; from reconstituted datoms.
                                      :datoms-added     (get resp "datoms-added")
                                      :datoms-retracted (get resp "datoms-retracted")}
                               (some? tx-meta) (assoc :tx-meta tx-meta)
                               (get resp "basis-t-before")
                               (assoc :db-before
                                      (d/as-of db (get resp "basis-t-before")))))))))
              (.catch
               (fn [e]
                 (put! p (if (instance? js/Error e)
                           e
                           (ex-info "wire transact transport error"
                                    {::cause (str e)}))))))))
      p))
  (-shutdown [_] nil)
  (-streaming? [_] false))

(defmethod w/create-writer :seon-wire
  [{:keys [sock-path]} connection]
  (->SeonWireWriter (or sock-path default-sock-path) connection))

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
  ;; {:started? bool :handle int :last-db <db> :own-skips int}
  (atom {:started? false}))

(defn adapter-status
  "Live adapter state for diagnostics: `{::started? ::handle ::own-skips}`."
  {:malli/schema [:=> [:cat] [:map [::started? :boolean]]]}
  []
  (let [{:keys [started? handle own-skips]} @!adapter]
    (cond-> {::started? (boolean started?)}
      (some? handle)    (assoc ::handle handle)
      (some? own-skips) (assoc ::own-skips own-skips))))

(defn- fire-native-listeners! [conn report]
  (doseq [[k callback] (some-> (:listeners (meta conn)) deref)]
    (try
      (callback report)
      (catch :default e
        (js/console.warn "[seon.store.wire adapter]" (pr-str k)
                         "listener threw:" (str e))))))

(defn- handle-feed-event! [conn ev]
  (let [wid  (get ev "request-id")
        own? (boolean (and wid (contains? @!own-write-ids wid)))
        bt   (get ev "basis-t")]
    (if own?
      ;; Own tx already fired the native listeners via writer/transact!;
      ;; just advance the consecutive-values chain + drop the id.
      (do (swap! !own-write-ids disj wid)
          (swap! !adapter #(-> %
                               (update :own-skips (fnil inc 0))
                               (assoc :last-db (ryow-deref! conn bt)))))
      (let [db-before (or (:last-db @!adapter) @conn)
            db        (ryow-deref! conn bt)
            tx-meta   (wire/readT (get ev "tx-meta"))
            report    (cond-> {:db-after  db
                               :db-before db-before
                               :tx-data   (wire-datoms->datoms
                                           (get ev "tx-data"))}
                        (some? tx-meta) (assoc :tx-meta tx-meta))]
        (swap! !adapter assoc :last-db db)
        (fire-native-listeners! conn report)))))

(defn ^:async ^:private subscribe! [sock-path]
  (let [sub (await (wire/subscribe-tx sock-path {}))]
    (when-not (get sub "ok")
      (throw (ex-info "seon.store.wire: subscribe-tx failed" {::resp sub})))
    (get sub "handle")))

(defn ^:async start-listen-adapter!
  "Subscribe to the wire tx feed and pump FOREIGN tx events into the
   conn's native listeners. Idempotent (defonce-guarded) — a second
   call is a no-op. Resilient: any pump failure (wire-server restart,
   stale handle, transport error) logs LOUDLY, waits 2s, and
   re-subscribes — the adapter never dies silently.

   Map-in: `{::conn <conn> ::sock-path <path>?}`. Returns a Promise of
   the initial subscription handle (or nil if already started)."
  [{::keys [conn sock-path] :or {sock-path default-sock-path}}]
  (if (:started? @!adapter)
    (do (log/info-console! "seon.store.wire"
                           "listen adapter already started — no-op")
        nil)
    (let [handle (await (subscribe! sock-path))]
      (swap! !adapter assoc :started? true :handle handle :last-db @conn)
      ((fn ^:async pump []
         (try
           (let [ev (await (wire/next-tx-event sock-path (:handle @!adapter)))]
             (cond
               (get ev "ok")                   (handle-feed-event! conn ev)
               (= "no-event" (get ev "error")) nil
               :else (throw (ex-info "tx-feed event error" {::event ev}))))
           (catch :default e
             (log/error-console!
              "seon.store.wire"
              (str "tx-feed pump failed (" (or (.-message e) (str e))
                   ") — re-subscribing in 2s"))
             (await (sleep 2000))
             (try
               (let [h (await (subscribe! sock-path))]
                 (swap! !adapter assoc :handle h)
                 (log/info-console! "seon.store.wire"
                                    (str "tx-feed re-subscribed, handle " h)))
               (catch :default e2
                 (log/error-console!
                  "seon.store.wire"
                  (str "tx-feed re-subscribe failed ("
                       (or (.-message e2) (str e2)) ") — retrying"))))))
         ;; detached tail call — promise chain does not grow
         (pump)
         nil))
      (log/info-console! "seon.store.wire"
                         (str "listen adapter live — feed handle " handle))
      handle)))
