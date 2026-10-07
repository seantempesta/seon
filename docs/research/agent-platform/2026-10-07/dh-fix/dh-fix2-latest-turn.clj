;; Synthetic latest-turn store: agent root with 3,000 turns among 23,000; cold (cache 1) and warm cpu/bytes/reads.
(require '[datahike.api :as d] '[datahike.query :as q] '[datahike.lru :as lru])
;; java -Ddhfix.scratch=DIR -cp FORK-CP clojure.main THIS LABEL (builds DIR/synth-store once)
(def path (str (System/getProperty "dhfix.scratch") "/synth-store"))
(def cfg {:store {:backend :file :path path :id #uuid "11111111-2222-3333-4444-555555555555"} :schema-flexibility :write
          :keep-history? true :writer {:backend :self} :store-cache-size 1})
(when-not (.exists (java.io.File. path))
  (d/create-database cfg)
  (let [c (d/connect cfg)]
    (d/transact c [{:db/ident :seon.agent/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                   {:db/ident :seon.turn/agent :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
                   {:db/ident :seon.turn/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                   {:db/ident :seon.turn/closed-tx :db/valueType :db.type/ref :db/cardinality :db.cardinality/one}])
    (d/transact c (vec (for [i (range 20)] {:seon.agent/id (if (zero? i) "root" (str "a" i))})))
    (doseq [part (partition-all 1000 (range 23000))]
      (d/transact c (vec (for [i part] {:seon.turn/agent [:seon.agent/id (if (< i 3000) "root" (str "a" (inc (mod i 19))))]
                                        :seon.turn/id (str "t" i) :seon.turn/closed-tx :db/current-tx}))))
    (d/release c)))
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))
(defn m1 [db stats]
  (let [r0 (:reads @stats) a0 (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx (.threadId (Thread/currentThread))) c0 (.getCurrentThreadCpuTime mx)
        res (binding [q/*query-result-cache?* false]
              (d/q '[:find (max ?t) . :in $ ?agent-id :where [?agent :seon.agent/id ?agent-id] [?turn :seon.turn/agent ?agent] [?turn :seon.turn/id _ ?t] [?turn :seon.turn/closed-tx _]] db "root"))]
    {:cpu (/ (- (.getCurrentThreadCpuTime mx) c0) 1e6) :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx (.threadId (Thread/currentThread))) a0) :reads (- (:reads @stats) r0) :res res}))
(defn med [k xs] (nth (sort (map k xs)) (quot (count xs) 2)))
(defn sample [] (let [c (d/connect cfg)] (try (let [db @c st (-> db :store :storage :stats)] [(m1 db st) (m1 db st)]) (finally (d/release c)))))
(dotimes [_ 5] (sample))
(doseq [round [1 2]]
  (let [ss (vec (repeatedly 11 sample)) cold (map first ss) warm (map second ss)]
    (prn (first *command-line-args*) round :cold {:cpu (med :cpu cold) :bytes (med :bytes cold) :reads (med :reads cold)}
         :warm {:cpu (med :cpu warm) :bytes (med :bytes warm)} :res (:res (first cold)))))
(System/exit 0)
