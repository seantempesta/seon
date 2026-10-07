(let [database @(seon.cluster.boot/connection "default")
      attributes (seon.db/identity-attributes database)
      paths (vec (take 100 (seon.db/q '[:find [?p ...] :where [_ :seon.fn.file/relative-path ?p]] database)))
      ids (vec (for [path paths f (#'seon.fn/seek database :avet :seon.fn.file/relative-path path)
                     e (#'seon.fn/seek database :avet :seon.fn/file (:e f))] (:e e)))
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      measure (fn [label f] (let [t (System/nanoTime) b (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean (.threadId (Thread/currentThread))) v (f)]
                              {:label label :ms (/ (- (System/nanoTime) t) 1e6) :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean (.threadId (Thread/currentThread))) b) :value v}))
      before (measure :pull #(into #{} (mapcat (fn [e] (dissoc e :db/id))) (seon.db/pull-many database attributes ids)))
      after (measure :query #(seon.db/q '[:find ?a ?v :in $ [?e ...] [?a ...] :where [?e ?a ?v]] database ids attributes))]
 {:ids (count ids) :equal (= (:value before) (:value after)) :measurements (mapv #(assoc (dissoc % :value) :count (count (:value %))) [before after])})
