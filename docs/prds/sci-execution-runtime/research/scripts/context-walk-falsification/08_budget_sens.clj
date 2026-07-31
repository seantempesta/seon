(in-ns 'falsify.harness)
;;; ATTACK 4b — what actually governs the budget, and the worst realistic case.

(with-db
  (fn [conn]
    (seed! conn)
    (let [db @conn
          sizes (->> (d/q '[:find ?n (count ?f) :where [?f :seon.fn/ns ?ns] [?ns :seon.ns/name ?n]] db)
                     (sort-by (comp - second)))]
      (println "\nlargest namespaces by function count:" (take 5 sizes))
      (println "\n=== max-collection sensitivity, agent ns = seon.cluster.run, d2 ===")
      (doseq [mc [8 16 32 64 128]]
        (let [c (assoc caps :seon.config.eval.result/max-collection mc)
              n (walk/neighborhood {:seon.db/db db
                                    :seon.render.walk/lookup [:seon.cluster.agent/id "alpha"]
                                    :seon.render/kind :seon.render/ai
                                    :seon.render/floor 'seon.render.block/data-prose
                                    :seon.render/distance 2 :seon.sci.admit/caps c})
              t (or (walk/prose n) "")]
          (println (format "  max-collection=%3d -> nodes=%3d tokens=%6d"
                           mc (count (nodes n)) (tokens/estimate t)))))

      (println "\n=== worst realistic case: agent owns the LARGEST namespace ===")
      (let [big (ffirst sizes)
            big-eid (ns-eid db big)]
        (tx! conn [[:db/retract [:seon.cluster.agent/id "beta"] :seon.cluster.agent/namespace]])
        (tx! conn [{:seon.cluster.agent/id "worst" :seon.cluster.agent/namespace big-eid
                    :seon.cluster.agent/instructions
                    (mapv (fn [[id _]] [:seon.cluster.instruction/id id]) instructions)}])
        (let [db2 @conn]
          (doseq [dist [1 2]]
            (let [n (walk-node db2 [:seon.cluster.agent/id "worst"] dist)
                  t (or (walk/prose n) "")]
              (println (format "  ns=%s (%d fns) d%d -> nodes=%d tokens=%d"
                               big (second (first sizes)) dist (count (nodes n))
                               (tokens/estimate t))))))))))
