(let [held (:seon.store/store (#'seon.cluster.boot/selected-instance "default"))
 before (clojure.edn/read-string (slurp "/Users/sean/src/seon/tmp/astra-rederive/clean-baseline.edn"))
 after (clojure.edn/read-string (slurp "/Users/sean/src/seon/tmp/astra-rederive/clean-after.edn"))
 databases (mapv #(seon.cluster.source/database held (get-in % [:answer :seon.source/commit-id])) [before after])]
 (try
  (let [started (System/nanoTime)
        rows (mapv seon.fn/published-index-entities databases)
        normalize (fn [entities] (into {} (map (fn [row] [(seon.program/row-identity row) (dissoc row :seon.fn.file/derivation-digest)])) entities))
        [old new] (mapv normalize rows)
        changed (into [] (keep (fn [identity] (when (not= (get old identity) (get new identity)) identity))) (into (set (keys old)) (keys new)))
        result {:equal? (= old new) :row-counts (mapv count rows) :changed-identities changed :comparison-ms (quot (- (System/nanoTime) started) 1000000) :current (seon.cluster.source/current held)}]
    (spit "/Users/sean/src/seon/tmp/astra-rederive/clean-equality.edn" (pr-str result)) result)
  (finally (doseq [database databases] (datahike.api/release-materialized-db database)))))
