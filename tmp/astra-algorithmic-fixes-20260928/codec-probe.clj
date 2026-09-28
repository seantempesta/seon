;; MCP JVM scratch.astra.codec; evaluate candidate decoder/helper there first.
(do
  (require '[seon.db :as db] '[seon.schema.datahike :as bridge]
           '[datahike.api :as d] '[seon.cluster.boot :as boot])
  (let [database (db/db (boot/connection "default"))
        projection (db/carried-projection database)
        ;; Inspect physical text at the bridge boundary, not its already decoded view.
        stored (first (d/datoms database :aevt :datahike.read/revision))
        candidate (ns-resolve *ns* 'decode-attribute-value-in)]
    (mapv
     (fn [n]
       (let [measure (fn [f]
                       (let [start (System/nanoTime)
                             result (reduce (fn [_ _] (f projection (:a stored) (:v stored))) nil (range n))]
                         {:ms (/ (- (System/nanoTime) start) 1e6) :value result}))
             before (measure bridge/decode-attribute-value-in)
             after (measure candidate)]
         {:n n :before-ms (:ms before) :after-ms (:ms after)
          :same? (= (:value before) (:value after))}))
     [1 10 100])))
