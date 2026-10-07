;; Read-evidence walker cost for the or+join query, change vs parent, one throwaway JVM.
;; S=<dir holding db-parent.clj from `git show <parent>:src/seon/db.clj`> clojure -M:dev walker-bench.clj
(require '[datahike.api :as d] 'seon.db)
(def cfg {:store {:backend :memory :id (random-uuid)} :schema-flexibility :write :keep-history? false})
(d/create-database cfg)
(def conn (d/connect cfg))
(d/transact conn [{:db/ident :seon.fn/sym :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                  {:db/ident :seon.test/sym :db/valueType :db.type/symbol :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                  {:db/ident :seon.program/definition-digest :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
(def n-entities 12000)
(d/transact conn (vec (for [i (range n-entities)]
                        (if (even? i)
                          {:seon.fn/sym (symbol "p.fn" (str "f" i)) :seon.program/definition-digest (str "d" i)}
                          {:seon.test/sym (symbol "p.test" (str "t" i)) :seon.program/definition-digest (str "d" i)}))))
(def db @conn)
(def syms (vec (map first (sort (d/q '[:find ?s :where [?e :seon.fn/sym ?s]] db)))))
(def qf (:query (datahike.query/normalize-q-input
                 '[:find ?sym ?digest :in $ [?sym ...] :where (or [?e :seon.fn/sym ?sym] [?e :seon.test/sym ?sym]) [?e :seon.program/definition-digest ?digest]] [])))
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))
(defn meas [f]
  (let [tid (.getId (Thread/currentThread)) b0 (.getThreadAllocatedBytes mx tid) t0 (System/nanoTime) r (f)]
    {:ms (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0) :mb (/ (Math/round (/ (- (.getThreadAllocatedBytes mx tid) b0) 1e4)) 100.0) :patterns (count r)}))
(defn run [label]
  (doseq [n [1 10 142]]
    (let [walk #(#'seon.db/query-index-patterns {:query qf :args [db (subvec syms (* 10 n) (+ (* 10 n) n))]} 0)
          raw #(d/q qf db (subvec syms (+ 3000 (* 7 n)) (+ 3000 (* 8 n))))]
      (walk) ;; warm
      (println label n "walker" (meas walk) "raw-d/q(fresh inputs)" (meas raw)))))
(run :change)
(load-file (str (System/getenv "S") "/db-parent.clj"))
(run :parent)
(shutdown-agents)
(System/exit 0)
