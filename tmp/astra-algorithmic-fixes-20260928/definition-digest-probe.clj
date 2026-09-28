;; MCP JVM scratch namespace after definition-digest-candidate.clj's form.
(do
  (require '[seon.db :as db] '[seon.fn :as f] '[seon.cluster.boot :as boot])
  (let [database (db/db (boot/connection "default"))
        projection (db/carried-projection database)
        artifact (first (:seon.fn.manifest/artifacts
                         (f/database-manifest database "/Users/sean/src/seon"
                                              ["src" "test"] ["src/seon/db.clj"])))
        rows (vec (take 100 (filter :seon.fn/sym (:seon.fn.file/rows artifact))))
        namespace-row (first (filter :seon.ns/name (:seon.fn.file/rows artifact)))
        context (f/stored-namespace-context namespace-row)
        candidate (ns-resolve *ns* 'digest-candidate)
        original (ns-resolve 'seon.program 'definition-digest)
        stats #(get-in ((ns-resolve 'seon.cache 'status)) [:seon.cache/kinds ::digest-candidate])]
    (doseq [row rows] (candidate row context))
    {:measurements
     (mapv (fn [n]
             (let [selected (take n rows)
                   measure (fn [digest-fn]
                             (let [start (System/nanoTime)
                                   result (mapv #(digest-fn % context) selected)]
                               {:ms (/ (- (System/nanoTime) start) 1e6) :value result}))
                   before (measure original)
                   misses-before (:seon.cache/misses (stats))
                   after (measure candidate)]
               {:n n :before-ms (:ms before) :after-ms (:ms after)
                :same? (= (:value before) (:value after))
                :new-misses (- (:seon.cache/misses (stats)) misses-before)}))
           [1 10 100])
     :writer-same?
     (every? #(= (candidate % context)
                 (db/definition-digest-of projection % namespace-row)) rows)}))
