;; review-dhfork2-opus: a union after the pattern binding its entity var. java -cp FORK-CP clojure.main THIS LABEL
(require '[datahike.api :as d] '[datahike.db :as db] '[datahike.query :as q] '[datahike.lru :as lru])
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))
(defn alloc [] (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx (.threadId (Thread/currentThread))))
(def schema {:p/id {:db/unique :db.unique/identity} :p/tags {:db/cardinality :db.cardinality/many}
             :p/nums {:db/cardinality :db.cardinality/many} :p/team {:db/index true}})
(def big (d/db-with (db/empty-db schema)
           (vec (for [i (range 60000)] {:db/id (inc i) :p/id (str "p" i) :p/team (if (zero? (mod i 1000)) :rare :common)
                                        :p/tags [(str "t" (mod i 7)) (str "u" (mod i 5))] :p/nums [(mod i 3) (+ 10 (mod i 4))]}))))
(def shapes
  {:pattern-first '[:find ?e ?t :where [?e :p/team :rare] (or [?e :p/tags ?t] [?e :p/nums ?t])]
   :or-first '[:find ?e ?t :where (or [?e :p/tags ?t] [?e :p/nums ?t]) [?e :p/team :rare]]
   :id-first '[:find ?t :in $ ?id :where [?e :p/id ?id] (or [?e :p/tags ?t] [?e :p/nums ?t])]
   :or-join-first '[:find ?t :in $ ?id :where (or-join [?e ?t] [?e :p/tags ?t] [?e :p/nums ?t]) [?e :p/id ?id]]})
(defn measure [query args]
  (vreset! @#'q/plan-cache (lru/lru 100))
  (binding [q/*query-result-cache?* false] (dotimes [_ 5] (apply d/q query big args)))
  (let [rs (for [_ (range 9)]
             (let [a (alloc) t (System/nanoTime) r (binding [q/*query-result-cache?* false] (apply d/q query big args))]
               {:ms (/ (- (System/nanoTime) t) 1e6) :bytes (- (alloc) a) :rows (count r)}))
        med (fn [k] (nth (sort (map k rs)) 4))]
    {:ms (med :ms) :bytes (med :bytes) :rows (:rows (first rs))}))
(doseq [[k qq] shapes] (prn (first *command-line-args*) k (measure qq (if (#{:id-first :or-join-first} k) ["p5000"] []))))
(shutdown-agents) (System/exit 0)
