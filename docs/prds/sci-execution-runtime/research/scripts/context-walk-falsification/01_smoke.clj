(in-ns 'falsify.harness)
(with-db
  (fn [conn]
    (seed! conn)
    (let [db @conn]
      (println "basis-t" (:max-tx db) "ns" (d/q '[:find (count ?e) . :where [?e :seon.ns/name]] db)
               "fn" (d/q '[:find (count ?e) . :where [?e :seon.fn/sym]] db))
      (doseq [dist [0 1 2 3]]
        (let [t0 (System/nanoTime)
              n (walk-node db [:seon.cluster.agent/id "alpha"] dist)
              text (walk/prose n)
              ms (/ (- (System/nanoTime) t0) 1e6)
              all (nodes n)]
          (println (format "d%d nodes=%d distinct=%d ms=%.0f chars=%d tokens=%s"
                           dist (count all)
                           (count (distinct (map :seon.render.walk/lookup all)))
                           ms (count (or text "")) (tokens/estimate (or text "")))))))))
