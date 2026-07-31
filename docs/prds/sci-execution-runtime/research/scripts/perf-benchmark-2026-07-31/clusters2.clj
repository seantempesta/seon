(require '[bench.support :as s] '[seon.cluster.registry :as registry]
         '[clojure.java.shell :as shell] '[clojure.string :as str])
(def bench-store (:seon.store/store s/instance))
(def source-commit (registry/branch-commit-id {:seon.store/store bench-store
                                               :seon.store/branch :current-src}))
(defn store-mib []
  (-> (:out (shell/sh "du" "-sm" "/Users/sean/src/seon/tmp/bench-head/data/clusters/store"))
      (str/split #"\t") first parse-long))
(println :SOURCE-COMMIT (str source-commit) :store-mib (store-mib))
(def fork-samples
  (mapv (fn [i]
          (let [t (System/nanoTime)]
            (registry/branch! {:seon.store/store bench-store
                               :seon.cluster.registry/from source-commit
                               :seon.store/branch (keyword (str "forkprobe-" i))})
            (- (System/nanoTime) t)))
        (range 50)))
(println :FORK-ONLY (pr-str (s/quantiles fork-samples)) :store-mib (store-mib))
(println :ROSTER (count (registry/roster bench-store)))
