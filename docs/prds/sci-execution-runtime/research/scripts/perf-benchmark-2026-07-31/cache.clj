;; Process-wide query-cache pressure at N clusters.
;;
;; The result cache is a process-global LRU over 64 DB SNAPSHOTS
;; (datahike/query.cljc:2445). With 25 live clusters each holding its own
;; database value, a round-robin read across all of them competes for those
;; 64 slots. This measures the same query, same pinned values, round-robin,
;; at the default 64 and at 512.
(require '[bench.support :as s] '[datahike.api :as d] '[datahike.query :as query])
(def instances @@(ns-resolve 'seon.cluster 'running-instances))
(def scale (into [] (comp (filter (fn [[k _]] (clojure.string/starts-with? k "scale-")))
                          (map (fn [[_ v]] @(:seon.boot/cluster-connection v))))
                 instances))
(println :CLUSTERS (count scale))
(defn walk-query [db]
  (count (d/q '[:find ?f ?sym :in $ ?nsname :where [?n :seon.ns/name ?nsname]
                [?f :seon.fn/ns ?n] [?f :seon.fn/sym ?sym]] db 'seon.cluster.store)))
(defn round-robin [dbs rounds]
  (dotimes [_ 2] (run! walk-query dbs))            ; prime
  (s/quantiles (into [] (mapcat (fn [_] (mapv (fn [db] (let [t (System/nanoTime)]
                                                         (walk-query db)
                                                         (- (System/nanoTime) t)))
                                              dbs)))
                     (range rounds))))
(doseq [size [64 512]]
  (query/set-query-cache-size! size)
  (println :CACHE size
           :n-1 (pr-str (round-robin (take 1 scale) 40))
           :n-10 (pr-str (round-robin (take 10 scale) 20))
           :n-25 (pr-str (round-robin scale 20))))
(query/set-query-cache-size! 64)
(println :CACHE-DONE)
