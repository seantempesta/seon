(ns seon.dev.wire-sync
  "Synchronous plain-Node UDS bridge to the JVM wire-server — the Track 2
   unit-2.1 plumbing that lets the UNCHANGED guest client
   (`seon.client-runtime.db` → `seon.client-runtime.wit`) run on plain Node.

   Why sync: the guest client mirrors `datahike.api` with SYNCHRONOUS calls
   because under WASM the WIT-bound host fns block. Plain Node sockets are
   async, so we use the standard synckit pattern: a worker thread
   (`wire_sync_worker.js`) does the async UDS I/O while the main thread
   blocks on `Atomics.wait` + drains the reply with `receiveMessageOnPort`.
   All PROTOCOL logic stays here on the main thread (CBOR envelope via
   `seon.dev.cbor` — byte-identical to `seon.server.codec`; values are the
   Transit-JSON strings `seon.client-runtime.wit` already produces). The
   worker is a dumb byte-exchanger — one wire-protocol implementation, not
   two.

   `install!` exposes the WIT op surface (q / transact / pull / entity-pull /
   pull-many / schema / reverse-schema) on
   `globalThis.__seon_client_runtime_db` — resolution path (a) in
   `seon.client-runtime.wit/resolve-wit-mod` — so `seon.client-runtime.db`
   works verbatim, single ambient conn (the back-compat path), against the
   running wire-server at `tmp/seon-cluster-default-req.sock`.

   WIT error contract: on a `{\"ok\" false}` reply we throw a JS value with
   `.tag` = the server's `error-kind` and `.val` = the message, exactly what
   `wit/wit-throw!` expects.

   NOT wired here (2.2+): transact-batch, db-filter/q-filtered, the
   subscribe-tx/next-tx-event feed (the listen! loop), multi-DB routing.
   Missing ops throw wit's clear \"WIT import missing\" error.

   Probe (`-main`, `:wire-sync-probe` build): drives the guest client
   end-to-end — connect → ensure marker schema → transact! a unique marker →
   q it back — printing evidence for the 2.1 DONE-oracle.

   Build:  clj -M:cljs compile wire-sync-probe   (fresh JVM — the long-running
           watcher predates the guest-cljs/ move and lacks it on classpath)
   Run:    node out/wire-sync-probe/main.js      (from the project root —
           worker + socket paths are project-relative)"
  (:require [seon.client-runtime.db :as client-db]
            [seon.dev.cbor :as cbor]))

(def default-req-sock "tmp/seon-cluster-default-req.sock")

(def ^:private worker-path "src/seon/dev/wire_sync_worker.js")

;; ---------- worker bridge ----------

(defonce ^:private !bridge (atom nil))

(defn- start-bridge! []
  (let [^js wt   (js/require "node:worker_threads")
        sab      (js/SharedArrayBuffer. 4)
        i32      (js/Int32Array. sab)
        ^js ch   (new (.-MessageChannel wt))
        port1    (.-port1 ch)
        port2    (.-port2 ch)
        ;; Worker requires an absolute path (or ./-relative); resolve from cwd.
        abs-path (.resolve ^js (js/require "node:path") worker-path)
        ^js w    (new (.-Worker wt) abs-path
                      #js {:workerData   #js {:port port2 :sab sab}
                           :transferList #js [port2]})]
    (.on w "error" (fn [e] (js/console.error "[wire-sync] worker error:" e)))
    ;; unref so a finished main thread can exit without tearing down manually
    (.unref w)
    {:wt wt :i32 i32 :port1 port1 :worker w}))

(defn- bridge [] (or @!bridge (reset! !bridge (start-bridge!))))

(defn call-sync
  "Send one CBOR-framed request map (string keys) over the UDS socket and
   BLOCK until the decoded reply map is available. Throws on transport
   error or timeout."
  ([req] (call-sync default-req-sock req {}))
  ([sock-path req] (call-sync sock-path req {}))
  ([sock-path req {:keys [timeout-ms] :or {timeout-ms 10000}}]
   (let [{:keys [wt i32 port1]} (bridge)
         framed (cbor/frame (cbor/encode req))]
     (js/Atomics.store i32 0 0)
     (.postMessage ^js port1 #js {:sock sock-path :bytes framed :timeoutMs timeout-ms})
     ;; "ok" = notified; "not-equal" = worker finished before we waited.
     (let [r (js/Atomics.wait i32 0 0 (+ timeout-ms 500))]
       (when (= r "timed-out")
         (throw (js/Error. (str "wire-sync: rpc timed out after " timeout-ms "ms"))))
       (let [msg (.receiveMessageOnPort ^js wt port1)]
         (when (nil? msg)
           (throw (js/Error. "wire-sync: woke without a reply message")))
         (let [^js m (.-message msg)]
           (if (.-ok m)
             (cbor/decode (.from cbor/B (.-bytes m)))
             (throw (js/Error. (str "wire-sync transport: " (.-error m)))))))))))

;; ---------- WIT-surface adapter ----------

(defn- unwrap!
  "Return the reply map when ok; otherwise throw the WIT-shaped JS error
   value (`.tag` / `.val`) `seon.client-runtime.wit/wit-throw!` expects."
  [op reply]
  (if (get reply "ok")
    reply
    (let [e (js-obj)]
      (aset e "tag" (or (get reply "error-kind") "internal"))
      (aset e "val" (str op ": " (get reply "error")))
      (throw e))))

(defn- bt->num
  "WIT s64 basis-t arrives as a BigInt (see wit/->bigint). Return a plain
   positive number, or nil for 0/absent (= current db)."
  [b]
  (let [n (if (identical? "bigint" (js* "typeof ~{}" b)) (js/Number b) b)]
    (when (and (number? n) (pos? n)) n)))

(defn- with-bt [req basis-t]
  (if-let [n (bt->num basis-t)] (assoc req "basis-t" n) req))

(defn wit-surface
  "Build the JS object exposing the WIT op surface over the sync UDS bridge.
   Every value argument is already a Transit-JSON string (wit.cljs encodes
   before calling); we pass them through verbatim."
  [sock]
  (js-obj
   "q"
   (fn [query-t args-arr basis-t]
     (-> (call-sync sock (with-bt {"op" "q" "query" query-t "args" (vec args-arr)}
                                  basis-t))
         (->> (unwrap! "q"))
         (get "result")))

   "transact"
   (fn [tx-t meta-t request-id]
     (-> (call-sync sock (cond-> {"op" "transact" "tx-data" tx-t
                                  "request-id" (or request-id "")}
                           (and meta-t (not= "" meta-t)) (assoc "tx-meta" meta-t)))
         (->> (unwrap! "transact"))
         (get "payload")))

   "pull"
   (fn [selector-t eid-t basis-t]
     (-> (call-sync sock (with-bt {"op" "pull" "selector" selector-t "eid" eid-t}
                                  basis-t))
         (->> (unwrap! "pull"))
         (get "result")))

   "entity-pull"
   (fn [ref-t selector-t depth basis-t]
     (-> (call-sync sock (with-bt (cond-> {"op" "entity-pull" "ref" ref-t
                                           "depth" (or depth 1)}
                                    (and selector-t (not= "" selector-t))
                                    (assoc "selector" selector-t))
                                  basis-t))
         (->> (unwrap! "entity-pull"))
         (get "result")))

   "pull-many"
   (fn [selector-t eids-arr basis-t]
     (-> (call-sync sock (with-bt {"op" "pull-many" "selector" selector-t
                                   "eids" (vec eids-arr)}
                                  basis-t))
         (->> (unwrap! "pull-many"))
         (get "result")))

   "schema"
   (fn []
     (-> (call-sync sock {"op" "schema"}) (->> (unwrap! "schema")) (get "result")))

   "reverse-schema"
   (fn []
     (-> (call-sync sock {"op" "reverse-schema"})
         (->> (unwrap! "reverse-schema"))
         (get "result")))))

(defn install!
  "Install the sync UDS bridge as the guest client's WIT module
   (`globalThis.__seon_client_runtime_db`). Must run BEFORE the first
   `seon.client-runtime.db` call in the process (wit.cljs caches the module
   on first invoke). Returns the installed JS object."
  ([] (install! default-req-sock))
  ([sock]
   (let [m (wit-surface sock)]
     (set! (.-__seon_client_runtime_db js/globalThis) m)
     m)))

;; ---------- 2.1 probe ----------

(def ^:private marker-attr :seon.track2/pod-marker)

(defn- ensure-marker-schema!
  "The central store is :schema-flexibility :write — the marker attr needs
   schema before first use. Idempotent: query for the ident first."
  [conn]
  (when (empty? (client-db/q '[:find ?e :where [?e :db/ident :seon.track2/pod-marker]]
                             conn))
    (client-db/transact! conn [{:db/ident       marker-attr
                                :db/valueType   :db.type/string
                                :db/cardinality :db.cardinality/one
                                :db/unique      :db.unique/identity}])))

(defn probe!
  "Drive the UNCHANGED guest client end-to-end over the sync UDS bridge:
   connect (schema probe) → ensure marker schema → transact! a unique
   marker → q it back (at the post-tx basis-t — exercises as-of). Returns
   {:marker ... :report ... :all-count N :mine #{[e marker]}}."
  []
  (install!)
  (let [conn   (client-db/connect {})
        marker (str "pod-client-2.1-" (.now js/Date))]
    (ensure-marker-schema! conn)
    (let [report (client-db/transact! conn [{marker-attr marker}])
          all    (client-db/q '[:find ?v :where [?e :seon.track2/pod-marker ?v]] conn)
          mine   (client-db/q '[:find ?e ?v :in $ ?v
                                :where [?e :seon.track2/pod-marker ?v]]
                              conn marker)]
      (js/console.log "[probe] marker:        " marker)
      (js/console.log "[probe] tx report:     "
                      (pr-str (select-keys report [:basis-t :basis-t-before
                                                   :datoms-added :tempids
                                                   :request-id])))
      (js/console.log "[probe] conn basis-t:  " (pr-str @(:basis-t conn)))
      (js/console.log "[probe] all markers:   " (count all))
      (js/console.log "[probe] q my marker:   " (pr-str mine))
      {:marker marker :report report :all-count (count all) :mine mine})))

(defn -main [& _]
  (try
    (let [{:keys [marker mine]} (probe!)]
      (if (= marker (second (first mine)))
        (do (js/console.log "[probe] OK — wrote + read the marker through"
                            "seon.client-runtime.db over the sync UDS bridge")
            (js/process.exit 0))
        (do (js/console.error "[probe] MISMATCH — q did not return the marker")
            (js/process.exit 1))))
    (catch :default e
      (js/console.error "[probe] FAILED:" e)
      (js/process.exit 1))))
