;; MCP JVM scratch.astra.reach. Evaluate candidate reach-entry/reach-entries
;; there first, preserving :seon.test.runner keys; never replace default Vars.
(do
  (require '[seon.db :as db] '[seon.test.runner :as runner] '[seon.cluster.boot :as boot])
  (let [database (db/db (boot/connection "default"))
        symbols (vec (take 100 (sort (db/q '[:find [?s ...] :where [_ :seon.test/sym ?s]] database))))
        candidate (ns-resolve *ns* 'reach-entries)]
    (mapv
     (fn [n]
       (let [syms (subvec symbols 0 n)
             run (fn [f]
                   (let [start (System/nanoTime) a (f database syms) b (f database syms)]
                     {:ms (/ (- (System/nanoTime) start) 1e6) :result a :same? (= a b)}))
             before (run @#'runner/reach-entries)
             after (run candidate)]
         {:n n :before-ms (:ms before) :after-ms (:ms after)
          :same? (and (:same? before) (:same? after) (= (:result before) (:result after)))}))
     [1 10 100])))
