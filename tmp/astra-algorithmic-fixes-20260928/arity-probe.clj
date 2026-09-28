;; MCP JVM scratch.astra.arity; evaluate candidate arity helpers there first.
(do
  (require '[seon.db :as db] '[seon.cluster.boot :as boot])
  (let [database (db/db (boot/connection "default"))
        speculative (db/with database [])
        query-fn (partial @#'db/storage-q-in (db/carried-projection database))
        candidate (ns-resolve *ns* 'arity-base)]
    (candidate query-fn speculative)
    (mapv
     (fn [n]
       (let [measure (fn [f]
                       (let [start (System/nanoTime)
                             result (reduce (fn [_ _] (f query-fn speculative)) nil (range n))]
                         {:ms (/ (- (System/nanoTime) start) 1e6) :value result}))
             before (measure @#'db/arity-base)
             after (measure candidate)]
         {:n n :before-ms (:ms before) :after-ms (:ms after)
          :same? (= (:value before) (:value after))}))
     [1 10 100])))
