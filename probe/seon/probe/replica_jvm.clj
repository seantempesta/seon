(ns seon.probe.replica-jvm
  "2.2c DIS-replica probe — JVM side (WRITER + orchestrator).

   Runs against the SAME datahike fork sha as the pod
   (seantempesta/datahike@01ba3f18, via the self-contained
   :replica-probe-jvm alias — kills the version-skew variable the research
   flagged). Creates a THROWAWAY :file store under tmp/replica-probe/,
   transacts, then shells the Node reader (out/replica-probe/main.js) and
   compares what each side sees. Phases:

     1. fresh store: schema + 5 entities → Node :full reads them
        (fressian compat + sync lazy fetch + correct query results)
     2. RYOW: after d/transact RETURNS, (a) the JVM re-reads the branch
        root straight from konserve and checks max-tx (flush-before-ack),
        (b) Node derefs a fresh @conn and must see the new datom + new
        max-tx (root-follow)
     3. lazy-vs-full: bulk 5000 entities, then Node :tiny does ONE entity
        lookup — its counted .ksv blob reads must be a small fraction of
        the store's total blobs/bytes

   Prereq: node out/replica-probe/main.js built
           (clj -M:cljs compile replica-probe — fresh JVM, not cljs-watch).
   Run:    clj -M:replica-probe-jvm"
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [datahike.api :as d]
            [konserve.core :as k])
  (:import [java.io File]
           [java.util UUID]))

(def store-path "tmp/replica-probe/store")

(def store-id
  "Deterministic RFC UUID — same derivation contract as the pod/server
   helpers (stable id per store dir). Node side receives it via env."
  (UUID/nameUUIDFromBytes (.getBytes ^String store-path "UTF-8")))

(def cfg
  {:store              {:backend :file
                        :path    store-path
                        :id      store-id}
   :keep-history?      true
   :schema-flexibility :write})

(def schema-tx
  [{:db/ident       :seon.probe/id
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :seon.probe/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- rm-rf! [^File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf! c)))
    (.delete f)))

(defn- store-totals
  "All konserve blobs currently on disk: {:files n :bytes b}."
  []
  (let [fs (->> (file-seq (io/file store-path))
                (filter #(and (.isFile ^File %)
                              (str/ends-with? (.getName ^File %) ".ksv"))))]
    {:files (count fs)
     :bytes (reduce + 0 (map #(.length ^File %) fs))}))

(defn- stored-root-max-tx
  "Read the branch root DIRECTLY from konserve (bypassing the conn's atom) —
   what a cross-process reader would see at this instant. The db value
   carries its store (writing.cljc:236)."
  [conn]
  (let [store (:store @conn)]
    (:max-tx (k/get store :db nil {:sync? true}))))

(defn- run-node!
  "Shell the Node reader; parse its PROBE-EDN line."
  [mode extra-env]
  (let [t0  (System/nanoTime)
        res (shell/sh "node" "out/replica-probe/main.js"
                      :env (merge (into {} (System/getenv))
                                  {"REPLICA_STORE_PATH" store-path
                                   "REPLICA_STORE_ID"   (str store-id)
                                   "REPLICA_MODE"       (name mode)}
                                  extra-env))
        ms  (/ (- (System/nanoTime) t0) 1e6)
        line (->> (str/split-lines (:out res))
                  (filter #(str/starts-with? % "PROBE-EDN "))
                  last)]
    (when-not line
      (throw (ex-info "node probe produced no PROBE-EDN line" res)))
    (when (seq (:err res))
      (println "[node stderr]" (str/trim (:err res))))
    (assoc (read-string (subs line (count "PROBE-EDN ")))
           :seon.probe/node-wall-ms ms)))

(defn- check! [label ok? detail]
  (println (format "  %-52s %s  %s" label (if ok? "CONFIRMED" "REFUTED") (pr-str detail)))
  (when-not ok?
    (println "  *** CLAIM REFUTED — stop, do not work around. ***"))
  ok?)

;; ---------------------------------------------------------------------------
;; main
;; ---------------------------------------------------------------------------

(defn -main [& _]
  (println "datahike version skew check: JVM runs the fork (alias-pinned); see deps.edn :replica-probe-jvm")
  (rm-rf! (io/file "tmp/replica-probe"))
  ;; parent dir only — create-database must see NO existing store dir
  (io/make-parents (io/file store-path))
  (d/create-database cfg)
  (let [conn (d/connect cfg)
        results (atom [])]
    (letfn [(rec! [label ok? detail] (swap! results conj [label ok? detail]) (check! label ok? detail))]

      ;; ---- Phase 0: as-shipped blob-layout compat --------------------------
      ;; konserve 0.9.346 header bug (found by this probe): CLJ encodes
      ;; meta-size as 4-byte BE int at bytes 4-7, CLJS as ONE byte at offset 4
      ;; (storage_layout.cljc:29 vs :40/:118). The UNSHIMMED node run documents
      ;; the failure; all later runs use the probe-only REPLICA_HEADER_SHIM to
      ;; falsify the claims downstream of the header.
      (println "\n== Phase 0: as-shipped header compat (expected to fail) ==")
      (d/transact conn schema-tx)
      (let [node0 (run-node! :full {})]
        (rec! "As-shipped blob layout JVM->Node (no shim)"
              (nil? (:seon.probe/error node0))
              (select-keys node0 [:seon.probe/error])))

      ;; ---- Phase 1: seed + Node :full read --------------------------------
      (println "\n== Phase 1: JVM seeds, Node reads (header shim on) ==")
      (let [shim  {"REPLICA_HEADER_SHIM" "1"}
            names ["alpha" "beta" "gamma" "delta" "epsilon"]
            rpt   (d/transact conn (vec (map-indexed
                                         (fn [i n] {:seon.probe/id (inc i) :seon.probe/name n})
                                         names)))
            t1    (:max-tx (:db-after rpt))
            disk1 (stored-root-max-tx conn)
            _     (rec! "RYOW-disk: root flushed before transact returns (t1)"
                        (= disk1 t1) {:ack-max-tx t1 :stored-root-max-tx disk1})
            node1 (run-node! :full shim)]
        (when (:seon.probe/error node1)
          (println "NODE ERROR past the header shim (fressian incompat — REFUTES Q2):")
          (println " " (:seon.probe/error node1) (:seon.probe/data node1))
          (System/exit 1))
        (rec! "Node deref sees JVM root (max-tx)"
              (= (:seon.probe/max-tx node1) t1)
              {:jvm t1 :node (:seon.probe/max-tx node1)})
        (rec! "Node sync d/q returns correct rows"
              (= (set (:seon.probe/result node1))
                 (set (map-indexed (fn [i n] [(inc i) n]) names)))
              {:rows (:seon.probe/result node1)})
        (rec! "Node d/datoms eavt walk works sync"
              (= 5 (count (:seon.probe/datoms-head node1)))
              {:head (:seon.probe/datoms-head node1)})
        (println "  timings:" (select-keys node1 [:seon.probe/connect-ms :seon.probe/deref1-ms
                                                  :seon.probe/deref2-ms :seon.probe/query-ms
                                                  :seon.probe/node-wall-ms]))
        (println "  io:     " (select-keys node1 [:seon.probe/connect-io :seon.probe/deref1-io
                                                  :seon.probe/query-io :seon.probe/deref2-io]))

        ;; ---- Phase 2: RYOW / root-follow -----------------------------------
        (println "\n== Phase 2: JVM transacts MORE, Node fresh deref sees it ==")
        (let [rpt2  (d/transact conn [{:seon.probe/id 6 :seon.probe/name "zeta"}])
              t2    (:max-tx (:db-after rpt2))
              disk2 (stored-root-max-tx conn)
              _     (rec! "RYOW-disk: root flushed before transact returns (t2)"
                          (= disk2 t2) {:ack-max-tx t2 :stored-root-max-tx disk2})
              node2 (run-node! :full shim)]
          (rec! "Node fresh deref follows new root (max-tx advanced)"
                (= (:seon.probe/max-tx node2) t2)
                {:jvm t2 :node (:seon.probe/max-tx node2)})
          (rec! "Node sees the post-phase-1 datom"
                (contains? (set (:seon.probe/result node2)) [6 "zeta"])
                {:rows (count (:seon.probe/result node2))})

          ;; ---- Phase 3: lazy-vs-full -----------------------------------------
          (println "\n== Phase 3: bulk 5000, Node tiny lookup must stay lazy ==")
          (doseq [batch (partition-all 500 (range 1000 6000))]
            (d/transact conn (mapv (fn [i] {:seon.probe/id i
                                            :seon.probe/name (str "bulk-" i)})
                                   batch)))
          (let [totals (store-totals)
                node3  (run-node! :tiny (assoc shim "REPLICA_TINY_ID" "4321"))
                reads  (reduce + 0 (map :seon.probe/blob-reads
                                        [(:seon.probe/connect-io node3)
                                         (:seon.probe/deref1-io node3)
                                         (:seon.probe/query-io node3)]))
                bytes  (reduce + 0 (map :seon.probe/blob-bytes
                                        [(:seon.probe/connect-io node3)
                                         (:seon.probe/deref1-io node3)
                                         (:seon.probe/query-io node3)]))]
            (rec! "Node tiny lookup returns the right value"
                  (= "bulk-4321" (:seon.probe/result node3))
                  {:result (:seon.probe/result node3)})
            (rec! "Lazy: tiny query reads a FRACTION of the store"
                  (and (pos? reads)
                       (< reads (long (* 0.5 (:files totals))))
                       (< bytes (long (* 0.5 (:bytes totals)))))
                  {:blob-reads reads :blob-bytes bytes :store totals})
            (println "  store totals:" totals)
            (println "  node io:" {:reads reads :bytes bytes})
            (println "  timings:" (select-keys node3 [:seon.probe/connect-ms :seon.probe/deref1-ms
                                                      :seon.probe/deref2-ms :seon.probe/query-ms
                                                      :seon.probe/node-wall-ms])))))

      ;; ---- Summary ---------------------------------------------------------
      (println "\n== Summary ==")
      (doseq [[label ok? _] @results]
        (println (format "  [%s] %s" (if ok? "PASS" "FAIL") label)))
      (let [ok (every? second @results)]
        (println (if ok "\nALL CLAIMS CONFIRMED" "\nAT LEAST ONE CLAIM REFUTED"))
        (shutdown-agents)
        (System/exit (if ok 0 1))))))
