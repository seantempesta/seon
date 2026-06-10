(ns seon.probe.replica-peer
  "2.2d Stage B peer oracle — JVM orchestrator (OFF-POD).

   Spawns a SECOND wire-server (`clojure -M:writer` — the sha-ALIGNED alias:
   datahike fork 01ba3f18 + :local/root konserve) on a THROWAWAY store under
   tmp/replica-peer/, then drives Node DIS peers (out/replica-peer/main.js)
   through the Stage B oracle:

     (a) rw:     peer transacts over the :seon-wire PWriter → reads its own
                 write locally via lazy deref (RYOW attempts must be 1)
     (b) listen: a FOREIGN wire write (raw poke client) → subscribe-tx event
                 → adapter re-derefs → handler fires with the new db value
                 containing the datom; the peer's OWN tx is skipped
     (c) lazy:   blob reads / deref ms stay in the 2.2c probe's family
     (d) two listen peers against ONE store both follow the same foreign
                 write

   It also proves deps.edn item 3: the aligned `:writer` alias STARTS and
   SERVES (this process IS the verification — the live wire-server on
   data/clusters/default is never touched, the pod is never touched).

   Cleanup: the spawned wire-server's process tree is destroyed in `finally`;
   sockets + store live under tmp/replica-peer/ + tmp/replica-peer-*.sock and
   are wiped at the start of every run.

   Prereq: node out/replica-peer/main.js built
           (clj -M:cljs compile replica-peer — fresh JVM, not cljs-watch).
   Run:    clj -M:replica-peer-jvm"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [konserve.core :as k]
            [konserve.filestore :as kf])
  (:import [java.io File]
           [java.lang ProcessHandle]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(def dir        "tmp/replica-peer")
(def store-path "tmp/replica-peer/store")
(def req-sock   "tmp/replica-peer-req.sock")
(def pub-sock   "tmp/replica-peer-pub.sock")
(def wire-log   "tmp/replica-peer/wire-server.log")
(def db-name    "seon.peer/oracle")

(def store-id
  "MUST equal seon.server.store/name->uuid of the keyword db-name:
   (UUID/nameUUIDFromBytes (.getBytes (str :seon.peer/oracle)))."
  (UUID/nameUUIDFromBytes (.getBytes (str (keyword db-name)) "UTF-8")))

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- rm-rf! [^File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf! c)))
    (.delete f)))

(defn- check! [results label ok? detail]
  (swap! results conj [label ok? detail])
  (println (format "  %-56s %s  %s" label (if ok? "CONFIRMED" "REFUTED") (pr-str detail)))
  (when-not ok?
    (println "  *** CLAIM REFUTED — stop, do not work around. ***"))
  ok?)

(defn- peer-env [mode extra]
  (merge (into {} (System/getenv))
         {"PEER_SOCK_PATH"  req-sock
          "PEER_STORE_PATH" store-path
          "PEER_STORE_ID"   (str store-id)
          "PEER_MODE"       mode}
         extra))

(defn- last-peer-edn [out]
  (some->> (str/split-lines (str out))
           (filter #(str/starts-with? % "PEER-EDN "))
           last
           (#(read-string (subs % (count "PEER-EDN "))))))

(defn- run-node!
  "Run one Node peer to completion (sync); parse its last PEER-EDN line."
  [mode extra-env]
  (let [t0  (System/nanoTime)
        res (shell/sh "node" "out/replica-peer/main.js" :env (peer-env mode extra-env))
        ms  (/ (- (System/nanoTime) t0) 1e6)]
    (when (seq (:err res))
      (println "[node stderr]" (str/trim (:err res))))
    (if-let [edn (last-peer-edn (:out res))]
      (assoc edn :seon.peer/node-wall-ms ms)
      (throw (ex-info "node peer produced no PEER-EDN line" res)))))

(defn- spawn!
  "Start a background process, stdout+stderr redirected to `log-file`.
   Returns the Process."
  ^Process [cmd log-file env-extra]
  (io/make-parents (io/file log-file))
  (let [pb (ProcessBuilder. ^java.util.List (vec cmd))]
    (.redirectErrorStream pb true)
    (.redirectOutput pb (io/file log-file))
    (when env-extra
      (.putAll (.environment pb) env-extra))
    (.start pb)))

(defn- await-line!
  "Poll `file` until a line containing `needle` appears, or throw after
   `timeout-ms`."
  [file needle timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [hit? (and (.exists (io/file file))
                      (some #(str/includes? % needle)
                            (str/split-lines (slurp file))))]
        (cond
          hit? true
          (< (System/currentTimeMillis) deadline) (do (Thread/sleep 250) (recur))
          :else (throw (ex-info "timed out waiting for line"
                                {:file file :needle needle
                                 :tail (when (.exists (io/file file))
                                         (take-last 20 (str/split-lines (slurp file))))})))))))

(defn- await-file! [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (.exists (io/file path)) true
        (< (System/currentTimeMillis) deadline) (do (Thread/sleep 100) (recur))
        :else (throw (ex-info "timed out waiting for file" {:path path}))))))

(defn- destroy-tree! [^Process p]
  (when p
    (doseq [^ProcessHandle h (iterator-seq (.iterator (.descendants (.toHandle p))))]
      (.destroy h))
    (.destroy p)
    (when-not (.waitFor p 5 TimeUnit/SECONDS)
      (doseq [^ProcessHandle h (iterator-seq (.iterator (.descendants (.toHandle p))))]
        (.destroyForcibly h))
      (.destroyForcibly p))))

(defn- stored-root-max-tx
  "Independent oracle: read the branch root straight off the store via
   konserve (no datahike, no wire) — what ANY reader sees on disk now."
  []
  (let [store (kf/connect-fs-store store-path :opts {:sync? true})]
    (:max-tx (k/get store :db nil {:sync? true}))))

;; ---------------------------------------------------------------------------
;; main
;; ---------------------------------------------------------------------------

(defn -main [& _]
  (let [results (atom [])
        rec!    (partial check! results)]
    ;; --- sha alignment of the :writer alias (static check) ------------------
    (println "== :writer alias sha alignment (deps resolution) ==")
    (let [cp (:out (shell/sh "clojure" "-A:writer" "-Spath"))]
      (rec! ":writer resolves datahike to the fork sha (01ba3f18)"
            (str/includes? cp "datahike/01ba3f18")
            {:datahike-entry (some #(when (str/includes? % "datahike") %)
                                   (str/split (str/trim cp) #":"))})
      (rec! ":writer resolves konserve to the :local/root fork"
            (str/includes? cp "/Users/sean/src/konserve")
            {}))

    ;; --- fresh throwaway world ----------------------------------------------
    (rm-rf! (io/file dir))
    (doseq [s [req-sock pub-sock]] (.delete (io/file s)))
    (io/make-parents (io/file store-path))

    (let [wire-proc (spawn! ["clojure" "-M:writer"
                             "--backend" "file" "--path" store-path
                             "--db-name" db-name
                             "--req-sock" req-sock "--pub-sock" pub-sock]
                            wire-log nil)]
      (try
        (println "\n== Second wire-server (sha-aligned :writer, throwaway store) ==")
        (await-line! wire-log "[writer] ready" 180000)
        (rec! "Aligned :writer alias STARTS + serves (fork JVM writer)"
              true {:log wire-log})

        ;; ---- (a) rw: transact over :seon-wire, read locally ----------------
        (println "\n== Oracle (a): peer transacts over the wire, reads locally ==")
        (let [a (run-node! "rw" {})]
          (when (:seon.peer/error a)
            (println "PEER ERROR:" (:seon.peer/error a) (:seon.peer/data a))
            (throw (ex-info "rw peer failed" a)))
          (rec! "(a) RYOW: peer's own write visible via local deref"
                (and (= [[1 "alpha"]] (:seon.peer/rows a))
                     (= [[1 "alpha"]] (:seon.peer/rows-from-report a)))
                (select-keys a [:seon.peer/rows :seon.peer/rows-from-report]))
          (rec! "(a) RYOW immediate: every ack satisfied on deref attempt 1"
                (and (seq (:seon.peer/ryow a))
                     (every? #(= 1 (:seon.peer/attempts %)) (:seon.peer/ryow a)))
                {:ryow (:seon.peer/ryow a)})
          (rec! "(a) own tx fires the conn's native d/listen listeners"
                (= 2 (count (:seon.peer/own-listener-max-txs a)))
                {:own-listener-max-txs (:seon.peer/own-listener-max-txs a)})
          (rec! "(a) disk root carries the peer's write (independent konserve read)"
                (= (:seon.peer/max-tx a) (stored-root-max-tx))
                {:peer-max-tx (:seon.peer/max-tx a)
                 :disk-root-max-tx (stored-root-max-tx)})

          ;; ---- (c) lazy numbers stay in family ------------------------------
          (println "\n== Oracle (c): lazy numbers in family ==")
          (let [store-files (->> (file-seq (io/file store-path))
                                 (filter #(and (.isFile ^File %)
                                               (str/ends-with? (.getName ^File %) ".ksv")))
                                 count)
                reads       (reduce + 0 (map :seon.peer/blob-reads
                                             (keep a [:seon.peer/connect-io
                                                      :seon.peer/write-io
                                                      :seon.peer/query-io])))]
            (rec! "(c) blob reads a fraction of the store; deref ms in family"
                  (and (pos? reads)
                       (< reads (max 10 store-files))
                       (< (:seon.peer/deref-ms a) 50))
                  {:blob-reads reads :store-files store-files
                   :deref-ms (:seon.peer/deref-ms a)
                   :connect-ms (:seon.peer/connect-ms a)
                   :write-ms (:seon.peer/write-ms a)})))

        ;; ---- (b) foreign write → feed event → handler ----------------------
        (println "\n== Oracle (b): foreign wire write wakes the listen adapter ==")
        (let [ready  (str dir "/ready-b")
              out-b  (str dir "/listen-b.out")
              p      (spawn! ["node" "out/replica-peer/main.js"] out-b
                             (peer-env "listen" {"PEER_OWN_ID"    "201"
                                                 "PEER_EXPECT_ID" "99"
                                                 "PEER_READY_FILE" ready}))]
          (try
            (await-file! ready 30000)
            (let [poke (run-node! "poke" {"PEER_POKE_ID" "99"
                                          "PEER_POKE_NAME" "foreign-poke"})]
              (rec! "(b) poke (raw wire client) committed"
                    (true? (:seon.peer/resp-ok poke)) poke))
            (when-not (.waitFor p 30 TimeUnit/SECONDS)
              (throw (ex-info "listen peer did not exit" {:out (slurp out-b)})))
            (let [b (last-peer-edn (slurp out-b))]
              (rec! "(b) handler fired with db VALUE containing the foreign datom"
                    (= ["foreign-poke"] (:seon.peer/expect-row b))
                    (select-keys b [:seon.peer/expect-row :seon.peer/handler-fired]))
              (rec! "(b) consecutive db values (db-before < db) at the handler"
                    (true? (:seon.peer/consecutive? b))
                    (select-keys b [:seon.peer/fired]))
              (rec! "(b) own tx skipped by the adapter (fired locally instead)"
                    (>= (or (:seon.peer/own-skips b) 0) 1)
                    {:own-skips (:seon.peer/own-skips b)}))
            (finally (destroy-tree! p))))

        ;; ---- (d) two peers, one store, both follow the same write ----------
        (println "\n== Oracle (d): two peer processes both follow one foreign write ==")
        (let [mk (fn [tag own-id]
                   (let [ready (str dir "/ready-" tag)
                         out   (str dir "/listen-" tag ".out")]
                     {:tag tag :ready ready :out out
                      :proc (spawn! ["node" "out/replica-peer/main.js"] out
                                    (peer-env "listen"
                                              {"PEER_OWN_ID"     own-id
                                               "PEER_EXPECT_ID"  "100"
                                               "PEER_READY_FILE" ready}))}))
              peers [(mk "d1" "211") (mk "d2" "212")]]
          (try
            (doseq [{:keys [ready]} peers] (await-file! ready 30000))
            (run-node! "poke" {"PEER_POKE_ID" "100" "PEER_POKE_NAME" "fanout"})
            (doseq [{:keys [tag out proc]} peers]
              (when-not (.waitFor ^Process proc 30 TimeUnit/SECONDS)
                (throw (ex-info "listen peer did not exit" {:peer tag :out (slurp out)})))
              (let [edn (last-peer-edn (slurp out))]
                (rec! (str "(d) peer " tag " handler saw the fanout datom")
                      (= ["fanout"] (:seon.peer/expect-row edn))
                      (select-keys edn [:seon.peer/expect-row
                                        :seon.peer/handler-fired
                                        :seon.peer/own-skips]))))
            (finally (doseq [{:keys [proc]} peers] (destroy-tree! proc)))))

        (finally
          (destroy-tree! wire-proc)
          (doseq [s [req-sock pub-sock]] (.delete (io/file s)))
          (println "\n[cleanup] second wire-server destroyed; sockets removed;"
                   "store kept at" store-path "for post-mortem (wiped next run)"))))

    ;; ---- Summary ------------------------------------------------------------
    (println "\n== Summary ==")
    (doseq [[label ok? _] @results]
      (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
    (let [ok (and (seq @results) (every? second @results))]
      (println (if ok "\nALL STAGE-B CLAIMS CONFIRMED" "\nAT LEAST ONE CLAIM REFUTED"))
      (shutdown-agents)
      (System/exit (if ok 0 1)))))
