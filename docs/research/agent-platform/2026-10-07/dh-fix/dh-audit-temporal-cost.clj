(require '[datahike.api :as d] '[datahike.db :as db] '[datahike.query :as q])
(let [before (d/db-with (db/empty-db {:item/a {:db/cardinality :db.cardinality/many}
                                    :item/b {:db/cardinality :db.cardinality/many}} {:keep-history? true})
                         [{:db/id 1 :item/a [1 2] :item/b [1 4 5]}])
      after (d/db-with before [[:db/retract 1 :item/b 1] [:db/add 1 :item/a 4]])
      database (d/history after)
      query '[:find ?v ?w ?added :where [?e :item/a ?v ?tx ?added] [?e :item/b ?w ?other-tx ?added]]
      mx (java.lang.management.ManagementFactory/getThreadMXBean)
      tid (.threadId (Thread/currentThread))]
 (binding [q/*query-result-cache?* false]
  (dotimes [_ 50] (d/q query database))
  (dotimes [_ 5]
   (let [a (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx tid) t (System/nanoTime)
         rows (d/q query database)]
    (prn {:rows (count rows) :ms (/ (- (System/nanoTime) t) 1e6)
          :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean mx tid) a)})))))
(shutdown-agents)
