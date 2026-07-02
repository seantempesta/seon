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
;; The pod's datahike conn handle the listen adapter subscribes for — an
;; opaque runtime value (third-party boundary), hence :any.
(schema/register! ::conn :any)

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

(def default-pub-sock-path
  "The cluster's UDS publish socket — the persistent broadcast stream the
   tx-feed adapter subscribes to. Inherits `wire/default-pub-sock`
   (cluster-isolation-aware via `SEON_PUB_SOCK`). Matches bin/seon's
   wire-server --pub-sock for the default cluster and bin/acme's export."
  wire/default-pub-sock)

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
  "The cluster store's konserve `:id`, replicated from the JVM side.

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
  (let [resp (await (wire/rpc default-sock-path {:seon.store.wire/op "ping"}
                              {:timeout-ms ping-timeout-ms}))]
    (when-not (:seon.store.wire/ok resp)
      (throw (ex-info "wire-server ping returned not-ok"
                      {::resp resp})))
    resp))

(defn ^:async ping!
  "Ping the wire-server, retrying up to `ping-attempts` times.

   Up to ~10s worst case: 5 × 2s rpc timeout, 500ms backoff between attempts.
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

(declare !adapter fire-own-tx-listeners!)

(defn- committed-write-tx
  "The basis-t at which `write-id` committed, or nil if not observed.

   Reads the LOCAL store (follow-the-store deref — no wire round-trip): the
   wire-server threads every forwarded write's id into the committed tx-meta
   as a `:seon.store.wire/write-id` datom on the tx entity, so a plain query
   over the current db answers commit-or-not for a transact whose rpc reply
   was lost."
  [db write-id]
  (d/q '[:find ?tx . :in $ ?wid :where [?tx :seon.store.wire/write-id ?wid]]
       db write-id))

(defn- transact-rpc-failure
  "DEFINED semantics for a transact whose rpc failed before a reply was read
   (timeout / transport / socket closed): the server may or may not have
   committed — resolve the ambiguity instead of reporting a silent maybe.

   1. Drop `write-id` from the echo-suppression set: IF the write did (or
      later does) land, its feed event now dispatches as FOREIGN and fires
      the conn's native listeners — the wake is not lost.
   2. Check the local store for the committed write-id. If it committed and
      the feed already applied it (own-skip, before step 1), fire the native
      listeners now from local history — they were suppressed for a tx the
      caller is about to be told failed.
   3. Return an ex-info whose message AND ex-data state commit-or-not
      (`:seon.store.wire/committed?` + `:seon.store.wire/basis-t`), so the
      caller can retry a NOT-committed write and must NOT re-send a
      committed one."
  [conn write-id e]
  (swap! !own-write-ids disj write-id)
  (let [flavor     (:seon.store.wire/rpc-failure (ex-data e))
        db         @conn
        tx-t       (committed-write-tx db write-id)
        committed? (some? tx-t)]
    (when (and committed?
               (some-> (:last-applied-t @!adapter) (>= tx-t)))
      (fire-own-tx-listeners! conn tx-t))
    (ex-info
     (str "wire transact "
          (case flavor
            :timeout "TIMED OUT waiting for the reply"
            :closed  "lost its connection before the reply"
            "hit a transport error")
          " (" (or (.-message e) (str e)) ") — commit status: "
          (if committed?
            (str "COMMITTED at basis-t " tx-t
                 " (the reply was lost, not the write). Do NOT re-send this tx.")
            (str "NOT observed in the store as of basis-t " (:max-tx db)
                 ". Safe to retry; if it lands later, listeners still fire.")))
     {:seon.store.wire/write-id   write-id
      :seon.store.wire/committed? committed?
      :seon.store.wire/basis-t    (or tx-t (:max-tx db))
      :seon.error/kind            :core-bug})))

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
              req        (cond-> {:seon.store.wire/op       "transact"
                                  :seon.store.wire/tx-data  tx-data
                                  :seon.store.wire/write-id write-id}
                           (seq tx-meta) (assoc :seon.store.wire/tx-meta tx-meta))]
          (swap! !own-write-ids conj write-id)
          (-> (wire/rpc sock-path req {:timeout-ms transact-timeout-ms})
              (.then
               (fn [resp]
                 ;; A reply WAS read — no commit ambiguity from here on. Any
                 ;; throw in this post-reply processing (e.g. the RYOW guard)
                 ;; is put directly, so the .catch below stays rpc-layer-only.
                 (try
                   (if-not (:seon.store.wire/ok resp)
                     (put! p (ex-info (str "wire transact failed: "
                                           (:seon.store.wire/error resp))
                                      {::error-kind (:seon.store.wire/error-kind resp)
                                       :seon.error/kind :user-input}))
                     ;; RYOW: resolve only once a local deref is at/past
                     ;; the ack'd basis-t. The synthesized report carries
                     ;; the MATERIALIZED post-tx db value, so straight-line
                     ;; transact!-then-read code just works.
                     (let [bt      (:seon.store.wire/basis-t resp)
                           db      (ryow-deref! conn bt)
                           tempids (:seon.store.wire/tempids resp)
                           tx-meta (:seon.store.wire/tx-meta resp)]
                       (put! p (cond-> {:db-after db
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
                                 (some? tx-meta) (assoc :tx-meta tx-meta)
                                 (:seon.store.wire/basis-t-before resp)
                                 (assoc :db-before
                                        (d/as-of db (:seon.store.wire/basis-t-before resp)))))))
                   (catch :default e
                     (put! p e)))))
              (.catch
               (fn [e]
                 ;; The rpc failed BEFORE a reply was read — the write may or
                 ;; may not have committed. Resolve the ambiguity honestly.
                 (put! p (try
                           (transact-rpc-failure conn write-id
                                                 (if (instance? js/Error e)
                                                   e
                                                   (js/Error. (str e))))
                           (catch :default e2 e2))))))))
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
  ;; inspector layout, a wake-handler doing real work inline) — the pump must
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

   Used by [[transact-rpc-failure]] when a timed-out transact turns out to
   have COMMITTED and the feed already echo-suppressed its event: the
   listeners were skipped for a tx whose caller is being told 'failed', so
   the wake must be synthesized here. Reads are local (follow-the-store),
   so the committing tx's datoms come straight from the history index."
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
  (let [wid          (:seon.store.wire/write-id ev)
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

      ;; Own tx already fired the native listeners via writer/transact!;
      ;; just advance the watermark + chain, drop the id.
      (boolean (and wid (contains? @!own-write-ids wid)))
      (do (swap! !own-write-ids disj wid)
          (swap! !adapter #(-> %
                               (update :own-skips (fnil inc 0))
                               (assoc :last-db (ryow-deref! conn bt)
                                      :last-applied-t bt))))

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
        (fire-native-listeners! conn report)))))

(defn- feed-event-dispatch!
  ;; Apply one pub frame: `tx` events for this pod's cluster route into
  ;; handle-feed-event!; everything else (other clusters' txs, reactive
  ;; changed-summaries) is ignored. The pub stream is db-agnostic — this is
  ;; the client-side db-name demux the replay-tx reply keys.
  [conn db-name ev]
  (when (and (= "tx" (:seon.store.wire/event ev))
             (= db-name (:seon.store.wire/db-name ev)))
    (handle-feed-event! conn ev)))

(defn ^:async ^:private connect-feed!
  ;; One pub-socket feed connection: connect, replay the watermark gap over
  ;; the req socket, drain live frames buffered during the replay, go live.
  ;; Resolves to {::db-name ::replayed}; throws on connect/replay failure
  ;; (the caller owns retry). `on-drop` fires ONCE if the connection dies.
  [conn sock-path pub-sock-path on-drop]
  (let [!buffer  (atom [])
        !live?   (atom false)
        !db-name (atom nil)
        sock     (await (wire/connect-pub
                         pub-sock-path
                         {:on-event (fn [ev]
                                      (if @!live?
                                        (feed-event-dispatch! conn @!db-name ev)
                                        (swap! !buffer conj ev)))
                          :on-close on-drop}))
        ;; Replay every tx after the watermark: on a fresh boot that covers
        ;; anything committed since the local snapshot read; on a reconnect,
        ;; the gap (DE-2). Frames arriving DURING the replay rpc buffer above;
        ;; the replay↔buffer overlap dedupes on the basis-t watermark.
        since-t  (:last-applied-t @!adapter)
        resp     (await (wire/replay-tx sock-path {:since-t since-t}))]
    (when-not (:seon.store.wire/ok resp)
      (try (.destroy ^js sock) (catch :default _))
      (throw (ex-info "seon.store.wire: replay-tx failed" {::resp resp})))
    (reset! !db-name (:seon.store.wire/db-name resp))
    (doseq [ev (:seon.store.wire/events resp)]
      (feed-event-dispatch! conn @!db-name ev))
    ;; Go live, then drain — one synchronous block (no await between), so no
    ;; frame can slip between the flip and the drain or be applied twice
    ;; (watermark idempotency covers the replay↔buffer overlap).
    (let [buffered @!buffer]
      (reset! !live? true)
      (reset! !buffer [])
      (doseq [ev buffered]
        (feed-event-dispatch! conn @!db-name ev)))
    {::db-name  @!db-name
     ::replayed (:seon.store.wire/replayed resp)}))

(defn- schedule-reconnect!
  ;; Consume feed generation `gen` and schedule ONE reconnect in 2s. A no-op
  ;; when `gen` is stale (the other failure path of the same attempt already
  ;; consumed it) — a drop and a failed connect can never race two loops.
  [gen reason reconnect!]
  (when (= gen (:feed-gen @!adapter))
    (swap! !adapter #(-> % (update :feed-gen inc) (assoc :connected? false)))
    (log/error-console!
     "seon.store.wire"
     (str "tx-feed pub connection lost (" reason ") — reconnecting in 2s"))
    (js/setTimeout reconnect! 2000)))

(defn ^:async start-listen-adapter!
  "Connect the persistent pub-socket tx feed and pump FOREIGN tx into the conn.

   ONE streaming pub-socket connection (push) replaces the old req-socket
   poll pump: the wire-server writes every committed tx event down the
   stream, and the frame reader feeds the conn's native listeners directly.
   Idempotent (defonce-guarded) — a second call is a no-op. Resilient: a
   drop logs LOUDLY, reconnects after 2s, and recovers the gap via the
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
