(require '[datahike.api :as d] '[datahike.db :as db] '[datahike.query :as q] '[datahike.lru :as lru])
(def mx (java.lang.management.ManagementFactory/getThreadMXBean))
(defn answer [database query args disabled?]
 (try (binding [q/*disable-planner* disabled? q/*query-result-cache?* false]
        (apply d/q query database args))
      (catch Throwable e {:error (.getMessage e)})))
(defn row [label database query args]
 (let [planned (answer database query args false) base (answer database query args true)]
  (prn {:shape label :planned planned :base base :equal? (= planned base)}) (flush)))
(let [before (d/db-with (db/empty-db {:i/a {:db/cardinality :db.cardinality/many}
                                    :i/b {:db/cardinality :db.cardinality/many}} {:keep-history? true})
                       [{:db/id 1 :i/a [1 2] :i/b [1 4 5]}])
      after (d/db-with before [[:db/retract 1 :i/b 1] [:db/add 1 :i/a 4]])]
 (doseq [query ['[:find ?v :where [?e :i/a ?v] [?e :i/b ?v]]
                '[:find ?v ?w :where [?e :i/a ?v ?tx] [?e :i/b ?w ?tx]]
                '[:find ?v ?w ?added :where [?e :i/a ?v ?tx ?added] [?e :i/b ?w ?tx2 ?added]]]]
   (row query (d/history after) query [])))
(let [database (d/db-with (db/empty-db {:u/id {:db/unique :db.unique/identity} :u/ref {:db/valueType :db.type/ref}})
                          [{:db/id 1 :u/id 1 :u/ref 2} {:db/id 2 :u/id 2 :u/ref 3} {:db/id 3 :u/id 3}])
      same '[[(same ?v ?x) [(identity ?v) ?x]]]]
 (doseq [rules [same]
         [label query args] [[:scalar '[:find ?x :in $ % ?v :where (same ?v ?x)] [[:u/id 2]]]
                             [:missing-data '[:find ?x :in $ % ?v :where (same ?v ?x)] [[:not/an-attribute 2]]]
                             [:literal '[:find ?x :in $ % :where (same [:u/id 2] ?x)] []]
                             [:coll '[:find ?x :in $ % [?v ...] :where (same ?v ?x)] [[[:u/id 2]]]]]]
    (row [(if (= same rules) :plain :recursive) label] database query (into [rules] args))))
(defn measured [f]
 (let [tid (.threadId (Thread/currentThread)) a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx tid)
       t (System/nanoTime) result (f)]
   {:ms (/ (- (System/nanoTime) t) 1e6)
    :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx tid) a) :rows (count result)}))
(let [schema {:g/a {:db/index true} :g/b {:db/valueType :db.type/ref} :g/c {}}
      small (d/db-with (db/empty-db schema)
               (into [{:db/id 1 :g/a 1} {:db/id 1000000 :g/c 1}]
                 (for [i (range 2 12)] {:db/id i :g/b 1})))
      large (d/db-with small (vec (for [i (range 12 8012)] {:db/id i :g/a i})))
      query '[:find ?e ?x :where [?e :g/a ?a] [?x :g/b ?e]]]
 (binding [q/*query-result-cache?* false]
  (vreset! @#'q/plan-cache (lru/lru 100))
  (d/q query small)
  (dotimes [_ 5] (d/q query large))
  (prn :sparse-id :small-max-eid (:max-eid small) :large-max-eid (:max-eid large)
       :stale (vec (repeatedly 5 #(measured (fn [] (d/q query large))))))
  (vreset! @#'q/plan-cache (lru/lru 100))
  (dotimes [_ 5] (d/q query large))
  (prn :sparse-id :fresh (vec (repeatedly 5 #(measured (fn [] (d/q query large))))))))
(shutdown-agents)
