(ns seon.dev.replica-peer
  "2.2d Stage B — DIS read-only-peer, OFF-POD (the :client build never loads
   this ns; it lives only in the :replica-peer shadow build).

   A full datahike PEER on Node against a wire-server-owned `:file` store:

   - READS: lazy konserve reader — `deref-conn` follow-the-store mode
     (the writer below reports `-streaming?` false, so every `@conn`
     re-reads the branch root and reconstitutes a fresh db value;
     connector.cljc:69-78). Sync, LRU-cached, memory ∝ working set —
     exactly what the 2.2c probe confirmed.
   - WRITES: the REAL `:seon-wire` PWriter (the ~40-line piece the research
     called for, mirroring datahike.http.writer/DatahikeServerWriter's
     shape): `d/transact!` dispatches `{:op 'transact! :args [arg-map]}`
     here, we forward it over the EXISTING UDS `transact` op
     (seon.store.internal.wire-node → seon.server.wire), and synthesize the tx-report
     from the ack + a local re-deref.
   - RYOW: the dispatch resolves ONLY after a local deref shows
     `:max-tx` ≥ the ack'd basis-t. Flush-before-ack (writer.cljc:108-134,
     probe-confirmed) makes attempt #1 succeed — the oracle asserts that.
   - CHANGE NOTIFICATION: `start-listen-adapter!` — subscribe-tx over the
     existing wire feed; on each FOREIGN tx event (own request-ids are
     skipped — own txs already fire locally via datahike.writer/transact!),
     re-deref the reader conn and invoke registered handlers with the SAME
     envelope shape `seon.db/listen!` hands its handlers
     (`:seon.db/{tx-report,db,db-before,datoms,attr-index}`), where db /
     db-before are CONSECUTIVE materialized db values. This is the
     prototype for the pod's listen! adapter at cutover.

   Driven by `clj -M:replica-peer-jvm` (probe/seon/probe/replica_peer.clj),
   which spawns the sha-aligned second wire-server on a throwaway store.

   Env:
     PEER_SOCK_PATH   wire-server req UDS socket
     PEER_STORE_PATH  store dir (the wire-server's --path)
     PEER_STORE_ID    RFC UUID string — must match the server's store :id
     PEER_MODE        rw | listen | poke
     PEER_READY_FILE  (listen) file touched once the subscription is live
     PEER_OWN_ID      (listen) :seon.peer/id for this peer's OWN tx
     PEER_EXPECT_ID   (listen) :seon.peer/id whose arrival ends the run
     PEER_POKE_ID     (poke)   :seon.peer/id to transact raw over the wire
     PEER_POKE_NAME   (poke)   :seon.peer/name for the poke entity

   Emits machine-readable `PEER-EDN {...}` lines on stdout.
   Build:  clj -M:cljs compile replica-peer   (fresh JVM, not cljs-watch)
   Run:    node out/replica-peer/main.js"
  (:require
   [clojure.string :as str]
   [cljs.core.async :refer [promise-chan put!]]
   [datahike.api :as d]
   [datahike.connector :as connector]
   [datahike.datom :as dd]
   [datahike.writer :as w]
   ;; registers datahike's :file backend on Node (same require the pod does)
   [konserve.node-filestore]
   [seon.store.internal.wire-node :as wire]))

(def ^:private fs (js/require "fs"))

;; ---------------------------------------------------------------------------
;; fs read accounting (same technique as seon.dev.replica-probe, which stays
;; untouched as the Stage A regression suite): konserve's sync FileChannel
;; opens each blob via fs.openSync, so ".ksv opens" = konserve blob reads.
;; ---------------------------------------------------------------------------

;; defonce (not def): a hot reload must not zero the live read-counter.
(defonce ^:private !opens (atom []))

(defn- install-read-counter! []
  (let [orig (.-openSync fs)]
    (set! (.-openSync fs)
          (fn [& args]
            (let [path (first args)]
              (when (and (string? path) (str/ends-with? path ".ksv"))
                (swap! !opens conj
                       {:seon.peer/path  path
                        :seon.peer/bytes (try (.-size (.statSync fs path))
                                              (catch :default _ 0))}))
              (.apply orig fs (to-array args)))))))

(defn- drain-opens! []
  (let [v @!opens]
    (reset! !opens [])
    {:seon.peer/blob-reads (count v)
     :seon.peer/blob-bytes (reduce + 0 (map :seon.peer/bytes v))}))

;; ---------------------------------------------------------------------------
;; Wire datom decode — server datom shape is [e a-transit v-transit t op]
;; (seon.server.wire/datom->wire). Reconstituted as REAL datahike Datoms so
;; tx-reports / handler inputs are contract-faithful (keyword lookup `:e`
;; `:a` `:v` `:tx` `:added` works exactly as on a local tx-report).
;; ---------------------------------------------------------------------------

(defn- wire-datoms->datoms [wire-data]
  (mapv (fn [[e a v t added]]
          (dd/datom e a v t added))
        wire-data))

;; ---------------------------------------------------------------------------
;; RYOW deref — resolve a db value at-or-past `basis-t` from the store.
;; Flush-before-ack means attempt 1 must succeed; the bounded retry exists to
;; FALSIFY that loudly rather than hang.
;; ---------------------------------------------------------------------------

;; Per-write RYOW evidence: {:seon.peer/basis-t :seon.peer/attempts
;; :seon.peer/deref-ms}. The oracle asserts attempts = 1 on every entry.
;; defonce (not def): a hot reload of this ns must NOT wipe live peer
;; state mid-flight.
(defonce !ryow (atom []))

(defn- ryow-deref! [conn basis-t]
  (loop [attempt 1]
    (let [t0 (js/performance.now)
          db @conn
          ms (- (js/performance.now) t0)]
      (cond
        (>= (:max-tx db) basis-t)
        (do (swap! !ryow conj {:seon.peer/basis-t  basis-t
                               :seon.peer/attempts attempt
                               :seon.peer/deref-ms ms})
            db)

        (< attempt 10) (recur (inc attempt))

        :else (throw (ex-info "RYOW violated: deref never reached ack basis-t"
                              {:seon.peer/basis-t basis-t
                               :seon.peer/max-tx  (:max-tx db)}))))))

;; ---------------------------------------------------------------------------
;; The `:seon-wire` PWriter — the ONE new piece the research named.
;; Mirrors datahike.http.writer/DatahikeServerWriter: non-streaming (which is
;; what flips deref-conn into follow-the-store mode), dispatches the op over
;; the remote channel, returns a promise-chan the writer go-loop consumes.
;; ---------------------------------------------------------------------------

;; request-ids of txs THIS peer dispatched — the listen adapter skips their
;; feed events (own txs already fire local listeners via writer/transact!).
;; defonce (not def): a hot reload must NOT drop in-flight own-request-ids
;; (would double-fire local listeners for txs already dispatched).
(defonce !own-request-ids (atom #{}))

(defrecord SeonWireWriter [sock-path conn]
  w/PWriter
  (-dispatch! [_ {:keys [op args]}]
    (let [p (promise-chan)]
      (if (not= op 'transact!)
        (put! p (ex-info "seon-wire writer supports only transact!"
                         {:seon.peer/op op}))
        (let [arg-map    (first args)
              tx-data    (if (map? arg-map) (:tx-data arg-map) arg-map)
              tx-meta    (when (map? arg-map) (:tx-meta arg-map))
              request-id (str (random-uuid))
              req        (cond-> {:seon.store.wire/op       "transact"
                                  :seon.store.wire/tx-data  tx-data
                                  :seon.store.wire/write-id request-id}
                           (seq tx-meta) (assoc :seon.store.wire/tx-meta tx-meta))]
          (swap! !own-request-ids conj request-id)
          (-> (wire/rpc sock-path req {})
              (.then
               (fn [resp]
                 (if-not (:seon.store.wire/ok resp)
                   (put! p (ex-info (str "wire transact failed: "
                                         (:seon.store.wire/error resp))
                                    {:seon.peer/error-kind (:seon.store.wire/error-kind resp)}))
                   ;; RYOW: resolve only once a local deref is at/past the
                   ;; ack'd basis-t. The synthesized report carries the
                   ;; MATERIALIZED post-tx db value, so straight-line
                   ;; transact!-then-read peer code just works.
                   (let [bt      (:seon.store.wire/basis-t resp)
                         db      (ryow-deref! conn bt)
                         tempids (:seon.store.wire/tempids resp)
                         tx-meta (:seon.store.wire/tx-meta resp)]
                     (put! p (cond-> {:db-after db
                                      :tx-data  (wire-datoms->datoms
                                                 (:seon.store.wire/tx-data resp))
                                      :tempids  (or tempids {})}
                               (some? tx-meta) (assoc :tx-meta tx-meta)
                               (:seon.store.wire/basis-t-before resp)
                               (assoc :db-before
                                      (d/as-of db (:seon.store.wire/basis-t-before resp)))))))))
              (.catch
               (fn [e]
                 (put! p (if (instance? js/Error e)
                           e
                           (ex-info "wire transact transport error"
                                    {:seon.peer/cause (str e)}))))))))
      p))
  (-shutdown [_] nil)
  (-streaming? [_] false))

(defmethod w/create-writer :seon-wire
  [{:keys [sock-path]} connection]
  (->SeonWireWriter (or sock-path wire/default-req-sock) connection))

(defmethod connector/-connect* :seon-wire [config opts]
  (connector/-connect-impl* config opts))

;; ---------------------------------------------------------------------------
;; listen! adapter — prototype of the pod's cutover adapter. subscribe-tx →
;; bounded next-tx-event poll loop → on FOREIGN event: re-deref → fire
;; handlers with consecutive materialized db values in seon.db/listen!'s
;; handler-input envelope.
;; ---------------------------------------------------------------------------

;; defonce (not def): hot reload must NOT wipe live listen state — bare
;; def-atoms reset on every reload, dropping handlers / the last-db basis /
;; the own-skip count mid-flight.
(defonce !handlers (atom {}))
(defonce !last-db (atom nil))
(defonce !own-skips (atom 0))

(defn listen!
  "Register `handler` (fn of one seon.db-shaped handler-input map) under `k`."
  [k handler]
  (swap! !handlers assoc k handler))

(defn- build-handler-input
  "Mirror of seon.db/build-handler-input. db/db-before are CONSECUTIVE
   materialized values (previous adapter deref → fresh deref ≥ event
   basis-t); datoms come decoded from the event's wire tx-data."
  [{:keys [db db-before datoms]}]
  (let [dms (mapv (fn [dat] {:seon.db/e      (:e dat)
                             :seon.db/a      (:a dat)
                             :seon.db/v      (:v dat)
                             :seon.db/tx     (:tx dat)
                             :seon.db/added? (boolean (:added dat))})
                  datoms)]
    {:seon.db/tx-report  {:db-after db :db-before db-before :tx-data datoms}
     :seon.db/db         db
     :seon.db/db-before  db-before
     :seon.db/datoms     dms
     :seon.db/attr-index (group-by :seon.db/a dms)}))

(defn ^:async start-listen-adapter!
  "Subscribe to the wire tx feed and pump events to registered handlers.
   Returns the subscription handle."
  [conn sock-path]
  (reset! !last-db @conn)
  (let [sub    (await (wire/subscribe-tx sock-path {}))
        handle (:seon.store.wire/handle sub)]
    (when-not (:seon.store.wire/ok sub)
      (throw (ex-info "subscribe-tx failed" {:seon.peer/resp sub})))
    ((fn ^:async pump []
       (let [ev (await (wire/next-tx-event sock-path handle))]
         (cond
           (:seon.store.wire/ok ev)
           (let [rid  (:seon.store.wire/write-id ev)
                 own? (boolean (and rid (contains? @!own-request-ids rid)))
                 bt   (:seon.store.wire/basis-t ev)]
             (if own?
               ;; own tx already fired local listeners via writer/transact!;
               ;; still advance the consecutive-values chain.
               (do (swap! !own-skips inc)
                   (reset! !last-db (ryow-deref! conn bt)))
               (let [db-before @!last-db
                     db        (ryow-deref! conn bt)
                     _         (reset! !last-db db)
                     input     (build-handler-input
                                {:db        db
                                 :db-before db-before
                                 :datoms    (wire-datoms->datoms
                                             (:seon.store.wire/tx-data ev))})]
                 (doseq [[k h] @!handlers]
                   (try (h input)
                        (catch :default e
                          (js/console.warn "[replica-peer adapter]" (pr-str k)
                                           "handler threw:" (str e))))))))
           (= "no-event" (:seon.store.wire/error ev)) nil
           :else (js/console.warn "[replica-peer adapter] event error:"
                                  (pr-str ev)))
         ;; detached tail call — promise chain does not grow
         (pump)
         nil)))
    handle))

;; ---------------------------------------------------------------------------
;; Oracle modes
;; ---------------------------------------------------------------------------

(defn- emit! [m]
  (println (str "PEER-EDN " (pr-str m))))

(def ^:private schema-tx
  [{:db/ident       :seon.peer/id
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :seon.peer/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private rows-query
  '[:find ?id ?name
    :where [?e :seon.peer/id ?id] [?e :seon.peer/name ?name]])

(defn ^:async run-rw!
  "Oracle (a): transact through the :seon-wire writer; read locally via lazy
   deref. Reports rows from BOTH the synthesized report's :db-after and a
   fresh deref, RYOW evidence, own-listener firing, and io counts."
  [cfg sock-path]
  (let [t0         (js/performance.now)
        conn       (await (d/connect cfg))
        connect-ms (- (js/performance.now) t0)
        connect-io (drain-opens!)
        !local     (atom [])]
    ;; native datahike listener — writer/transact! must fire it for OWN txs
    (d/listen conn :seon.peer/own-tap
              (fn [report] (swap! !local conj (:max-tx (:db-after report)))))
    (await (d/transact! conn {:tx-data schema-tx}))
    (let [tw0       (js/performance.now)
          report    (await (d/transact! conn
                                        {:tx-data [{:seon.peer/id 1
                                                    :seon.peer/name "alpha"}]}))
          write-ms  (- (js/performance.now) tw0)
          write-io  (drain-opens!)
          rows-rpt  (vec (sort (d/q rows-query (:db-after report))))
          td        (js/performance.now)
          db        @conn
          deref-ms  (- (js/performance.now) td)
          rows      (vec (sort (d/q rows-query db)))
          query-io  (drain-opens!)]
      (emit! {:seon.peer/mode             :rw
              :seon.peer/max-tx           (:max-tx db)
              :seon.peer/rows-from-report rows-rpt
              :seon.peer/rows             rows
              :seon.peer/tx-data-count    (count (:tx-data report))
              :seon.peer/ryow             @!ryow
              :seon.peer/own-listener-max-txs @!local
              :seon.peer/connect-ms       connect-ms
              :seon.peer/write-ms         write-ms
              :seon.peer/deref-ms         deref-ms
              :seon.peer/connect-io       connect-io
              :seon.peer/write-io         write-io
              :seon.peer/query-io         query-io})
      (.exit js/process 0))))

(defn ^:async run-listen!
  "Oracle (b)/(d): own tx (must be SKIPPED by the adapter), then wait for a
   foreign tx carrying PEER_EXPECT_ID — handler must receive a db VALUE
   containing the datom, with consecutive db/db-before."
  [cfg sock-path ^js env]
  (let [conn       (await (d/connect cfg))
        own-id     (js/parseInt (.-PEER_OWN_ID env) 10)
        expect-id  (js/parseInt (.-PEER_EXPECT_ID env) 10)
        ready-file (.-PEER_READY_FILE env)
        !fired     (atom [])]
    (listen! :seon.peer/oracle
             (fn [{:seon.db/keys [db db-before datoms]}]
               (let [ids (into #{} (comp (filter #(= :seon.peer/id (:seon.db/a %)))
                                         (map :seon.db/v))
                           datoms)
                     row (first (d/q '[:find ?name :in $ ?id :where
                                       [?e :seon.peer/id ?id]
                                       [?e :seon.peer/name ?name]]
                                     db expect-id))]
                 (swap! !fired conj {:seon.peer/event-ids        ids
                                     :seon.peer/db-max-tx        (:max-tx db)
                                     :seon.peer/db-before-max-tx (:max-tx db-before)})
                 (when (contains? ids expect-id)
                   (emit! {:seon.peer/mode          :listen
                           :seon.peer/handler-fired (count @!fired)
                           :seon.peer/fired         @!fired
                           :seon.peer/expect-row    (vec row)
                           :seon.peer/own-skips     @!own-skips
                           :seon.peer/consecutive?  (< (:max-tx db-before)
                                                       (:max-tx db))
                           :seon.peer/ryow          @!ryow})
                   (.exit js/process 0)))))
    (await (start-listen-adapter! conn sock-path))
    ;; own tx AFTER subscribing — its feed event must be skipped (own-skips)
    (await (d/transact! conn {:tx-data [{:seon.peer/id own-id
                                         :seon.peer/name (str "own-" own-id)}]}))
    (when ready-file (.writeFileSync fs ready-file "ready"))
    (js/setTimeout (fn []
                     (emit! {:seon.peer/mode      :listen
                             :seon.peer/error     "timeout waiting for expected foreign tx"
                             :seon.peer/own-skips @!own-skips})
                     (.exit js/process 1))
                   20000)))

(defn ^:async run-poke!
  "Oracle helper: a RAW wire transact (its own wire client, NOT through any
   conn) — 'another writer' from every peer's point of view."
  [sock-path ^js env]
  (let [id   (js/parseInt (.-PEER_POKE_ID env) 10)
        nm   (or (.-PEER_POKE_NAME env) (str "poke-" id))
        resp (await (wire/transact sock-path
                                   [{:seon.peer/id id :seon.peer/name nm}]))
        bt   (:seon.store.wire/basis-t resp)]
    (emit! {:seon.peer/mode    :poke
            :seon.peer/basis-t bt
            :seon.peer/resp-ok (some? bt)})
    (.exit js/process (if bt 0 1))))

(defn ^:async -main [& _]
  (install-read-counter!)
  (try
    (let [^js env    (.-env js/process)
          sock-path  (or (.-PEER_SOCK_PATH env) wire/default-req-sock)
          store-path (or (.-PEER_STORE_PATH env) "tmp/replica-peer/store")
          mode       (keyword (or (.-PEER_MODE env) "rw"))
          cfg        {:store               {:backend :file
                                            :path    store-path
                                            :id      (uuid (.-PEER_STORE_ID env))
                                            ;; READERS take no blob locks (found by
                                            ;; oracle (d): two sync Node readers race
                                            ;; on the root blob's .LOCK — konserve's
                                            ;; SYNC get-lock can't wait, it spins 101
                                            ;; iterations and throws). Lock-free reads
                                            ;; are DIS-correct: the root is replaced
                                            ;; by atomic rename, index nodes are
                                            ;; content-addressed + immutable, and this
                                            ;; peer's WRITES go over the wire, never
                                            ;; through local konserve.
                                            :config  {:lock-blob? false}}
                      :keep-history?       true
                      :schema-flexibility  :write
                      :writer              {:backend   :seon-wire
                                            :sock-path sock-path}
                      :allow-unsafe-config true}]
      (case mode
        :rw     (await (run-rw! cfg sock-path))
        :listen (await (run-listen! cfg sock-path env))
        :poke   (await (run-poke! sock-path env))))
    (catch :default e
      (emit! {:seon.peer/error (str e)
              :seon.peer/data  (pr-str (ex-data e))})
      (.exit js/process 1))))
