;; MCP JVM scratch.astra.publication; evaluate candidate key helpers there first.
(do
  (require '[seon.cluster :as cluster] '[seon.cluster.boot :as boot]
           '[seon.db :as db] '[seon.id :as id] '[clojure.string :as str])
  (let [database (db/db (boot/connection "default"))
        projection (db/carried-projection database)
        inputs (into {} (db/q '[:find ?path ?digest
                               :where [?file :seon.fn.file/relative-path ?path]
                                      [?file :seon.fn.file/digest ?digest]] database))
        paths (vec (take 100 (sort (filter #(and (str/starts-with? % "src/")
                                                (str/ends-with? % ".clj")) (keys inputs)))))
        schema-keys (#'cluster/stored-derivation-schema-keys
                     database (set (keys (:seon.schema.projection/forms projection))))
        code (id/digest 64 [::key-probe])
        candidate (ns-resolve *ns* 'derivation-keys)]
    (mapv
     (fn [n]
       (let [selected (set (take n paths))
             measure (fn [f]
                       (f code projection inputs schema-keys selected)
                       (let [start (System/nanoTime)
                             result (f code projection inputs schema-keys selected)]
                         {:ms (/ (- (System/nanoTime) start) 1e6) :value result}))
             before (measure @#'cluster/derivation-keys)
             after (measure candidate)]
         {:n n :before-ms (:ms before) :after-ms (:ms after)
          :same? (= (:value before) (:value after))}))
     [1 10 100])))
