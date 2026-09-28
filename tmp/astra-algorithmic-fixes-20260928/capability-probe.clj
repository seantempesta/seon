;; MCP JVM, scratch.astra.capability; evaluate the candidate function there first.
(do
  (require '[seon.db :as db] '[seon.fn :as f] '[seon.cluster.boot :as boot])
  (let [database (db/db (boot/connection "default"))
        artifact (first (:seon.fn.manifest/artifacts
                         (f/database-manifest database "/Users/sean/src/seon"
                                              ["src" "test"] ["src/seon/db.clj"])))
        rows (vec (take 100 (filter :seon.fn/sym (:seon.fn.file/rows artifact))))
        candidate (ns-resolve *ns* 'assert-capability-contracts!)]
    (mapv
     (fn [n]
       (let [artifacts [(assoc artifact :seon.fn.file/rows (subvec rows 0 n))]
             measure (fn [f]
                       (f artifacts database #{})
                       (let [start (System/nanoTime) result (f artifacts database #{})]
                         {:ms (/ (- (System/nanoTime) start) 1e6) :same? (= artifacts result)}))]
         {:n n :before (measure @#'f/assert-capability-contracts!) :after (measure candidate)}))
     [1 10 100])))
