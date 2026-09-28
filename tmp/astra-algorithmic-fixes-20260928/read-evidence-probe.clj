;; Evaluate in scratch.astra.algorithmic after evaluating the candidate
;; query-index-patterns definition there. Never evaluate it in seon.db.
(do
  (require '[seon.db] '[seon.cluster.boot])
  (let [database (seon.db/db (seon.cluster.boot/connection "default"))
        candidate (ns-resolve *ns* 'query-index-patterns)
        entities (vec (take 100 (seon.db/q '[:find [?e ...] :where [?e :seon.fn/sym]] database)))
        query '[:find (pull ?e [:seon.fn/sym]) :in $ [?e ...] :where [?e :seon.fn/sym]]]
    (mapv
     (fn [n]
       (let [request {:query query :args [database (subvec entities 0 n)]}
             measure (fn [f]
                       (f request 0)
                       (let [start (System/nanoTime)
                             result (f request 0)]
                         {:ms (/ (- (System/nanoTime) start) 1e6) :patterns result}))
             before (measure @#'seon.db/query-index-patterns)
             after (measure candidate)]
         {:inputs n :before-ms (:ms before) :after-ms (:ms after)
          :equal? (= (set (:patterns before)) (set (:patterns after)))}))
     [1 10 100])))
