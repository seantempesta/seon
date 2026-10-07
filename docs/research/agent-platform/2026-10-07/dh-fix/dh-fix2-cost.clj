;; dh-fix2 cost driver: bench.clj shapes on the APFS clone, fixed inputs, 2 rounds after warm-up.
;; java -Ddhfix.scratch=DIR (DIR holds store/, an APFS clone of data/store) -cp FORK-CP clojure.main THIS LABEL
(load-file "/Users/sean/src/seon/docs/research/agent-platform/2026-10-07/dh-fix/bench.clj")
(in-ns 'bench)
(require '[clojure.edn :as edn])
(def input-path (str (System/getProperty "dhfix.scratch") "/cost-inputs.edn"))
(def ins (if (.exists (java.io.File. input-path))
           (edn/read-string (slurp input-path))
           (let [v (with-conn [c] (inputs @c))] (spit input-path (pr-str v)) v)))
(defn cpu [] (.getCurrentThreadCpuTime ^java.lang.management.ThreadMXBean mx))
(defn measure1 [db stats query args]
  (let [r0 (:reads @stats) a0 (alloc) c0 (cpu) t0 (System/nanoTime)
        res (binding [q/*query-result-cache?* false] (apply d/q query db args))
        ms (/ (- (System/nanoTime) t0) 1e6) cms (/ (- (cpu) c0) 1e6)]
    {:ms (/ (Math/round (* 10 ms)) 10.0) :cpu (/ (Math/round (* 10 cms)) 10.0) :bytes (- (alloc) a0) :reads (- (:reads @stats) r0)
     :rows (if (coll? res) (count res) 1) :answer (hash res)}))
(defn run-shape [ins label]
  (let [query (shapes label) args (ins label)]
    (vreset! @#'q/plan-cache (lru/lru 100))
    (with-conn [c] (binding [q/*query-result-cache?* false] (apply d/q query @c args)))
    (let [ss (mapv (fn [_] (sample query args)) (range 9)) cold (map first ss) warm (map second ss)
          m (fn [xs] {:ms (median-by :ms xs) :cpu (median-by :cpu xs) :bytes (median-by :bytes xs) :reads (median-by :reads xs)})]
      {:shape label :cold (m cold) :warm (m warm) :rows (:rows (first cold)) :answer (:answer (first cold))})))
(def labels [:receipts :analysis :next-ordinal :latest-turn :affected])
(doseq [l labels] (run-shape ins l))
(doseq [round [1 2] l labels]
  (let [r (run-shape ins l)] (prn (first *command-line-args*) round l (:cold r) (:warm r) :rows (:rows r) :answer (:answer r))))
(shutdown-agents)
(System/exit 0)
