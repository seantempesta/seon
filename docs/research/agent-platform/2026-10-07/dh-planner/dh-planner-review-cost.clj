(require '[datahike.api :as d] '[datahike.db :as db] '[datahike.query :as q] '[datahike.lru :as lru])
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))
(def tid (.threadId (Thread/currentThread)))
(defn measured [f]
  (let [b (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx tid)
        t (System/nanoTime) r (f)]
    {:ms (/ (- (System/nanoTime) t) 1e6)
     :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx tid) b)
     :rows (count r)}))
(defn clear-plans [] (vreset! @#'q/plan-cache (lru/lru 100)))
(defn plan-shape []
  (mapv (fn [[_ p]] (mapv #(select-keys % [:op :clause :estimated-card :scan-card]) (:ops p))) (.-key-value ^datahike.lru.LRU @@#'q/plan-cache)))
(def schema {:g/a {:db/index true} :g/b {:db/valueType :db.type/ref} :g/c {:db/index true}})
(def small (d/db-with (db/empty-db schema)
                     (into [{:db/id 1 :g/a 1}] (for [i (range 2 102)] {:db/id i :g/b 1 :g/c i}))))
(def query '[:find ?e ?x :where [?e :g/a ?a] [?x :g/b ?e]])
(binding [q/*query-result-cache?* false]
 (doseq [size [1000 10000 50000]]
  (let [large (d/db-with small (vec (for [i (range 102 (+ 102 size))] {:db/id i :g/a i})))]
    (clear-plans)
    (d/q query small)
    (prn :size size :cached-plan (plan-shape))
    (dotimes [_ 3] (d/q query large))
    (prn :stale (vec (repeatedly 5 #(measured (fn [] (d/q query large))))))
    (clear-plans)
    (d/q query large)
    (prn :fresh-plan (plan-shape))
    (dotimes [_ 3] (d/q query large))
    (prn :fresh (vec (repeatedly 5 #(measured (fn [] (d/q query large)))))))))
(shutdown-agents)
