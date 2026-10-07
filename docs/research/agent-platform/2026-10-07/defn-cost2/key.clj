(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
      old (malli.instrument/-f->original @#'seon.db/declaration-content-key)
      fast (fn [database]
             (let [installed (datahike.db.interface/-schema database)]
               (mapv (fn [attribute]
                       (let [datoms (if (get installed attribute) (vec (datahike.api/datoms database :aevt attribute)) [])]
                         [attribute datoms (into [] (map :tx) datoms)]))
                     seon.schema/projection-attributes)))
      measure (fn [f] (let [a (.getCurrentThreadAllocatedBytes mx) t (System/nanoTime)
                             answer (f database)]
                         {:ms (/ (- (System/nanoTime) t) 1e6) :bytes (- (.getCurrentThreadAllocatedBytes mx) a) :ranges (count answer)}))]
  {:equal (= (old database) (fast database))
   :old (mapv (fn [_] (measure old)) (range 5))
   :new (mapv (fn [_] (measure fast)) (range 5))})
