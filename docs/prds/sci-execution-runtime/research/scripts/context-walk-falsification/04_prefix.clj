(in-ns 'falsify.harness)
;;; ATTACK 2 — P5 prefix sharing, and the shared-hub reverse fan-out bomb.

(defn find-node [n eid]
  (first (filter #(= eid (:seon.render.walk/lookup %)) (nodes n))))

(with-db
  (fn [conn]
    (seed! conn)
    ;; a realistic fleet: 18 more agents all referencing the SAME instruction rows
    (let [irefs (mapv (fn [[id _]] [:seon.cluster.instruction/id id]) instructions)]
      (tx! conn (into [] (for [i (range 18)]
                           {:seon.cluster.agent/id (str "peer" i)
                            :seon.cluster.agent/instructions irefs}))))
    (let [db @conn
          inst (d/q '[:find ?e . :where [?e :seon.cluster.instruction/id :reply-grammar]] db)
          a (walk-node db [:seon.cluster.agent/id "alpha"] 2)
          b (walk-node db [:seon.cluster.agent/id "beta"] 2)
          an (find-node a inst) bn (find-node b inst)]
      (println "\n=== P5: the SAME instruction row rendered from two agent roots ===")
      (println "alpha block bytes:" (pr-str (node-text an)))
      (println "beta  block bytes:" (pr-str (node-text bn)))
      (println "block bytes identical? " (= (node-text an) (node-text bn)))
      (println "alpha node neighbours:" (count (:seon.render.walk/neighbours an)))
      (println "beta  node neighbours:" (count (:seon.render.walk/neighbours bn)))
      (println "alpha neighbour lookups:" (mapv :seon.render.walk/lookup (:seon.render.walk/neighbours an)))
      (println "beta  neighbour lookups:" (mapv :seon.render.walk/lookup (:seon.render.walk/neighbours bn)))
      (println "\n--- the ASSEMBLED framing for that instruction (what actually enters the prompt) ---")
      (let [pa (walk/prose {:seon.render.walk/neighbours [an]})
            pb (walk/prose {:seon.render.walk/neighbours [bn]})]
        (println "alpha subtree bytes:" (count pa) " beta subtree bytes:" (count pb))
        (println "subtree byte-identical?" (= pa pb))
        (println "alpha subtree head:\n" (subs pa 0 (min 300 (count pa))))
        (println "beta  subtree head:\n" (subs pb 0 (min 300 (count pb)))))
      (println "\n=== the shared-hub fan-out bomb (20 agents, 4 shared instruction rows) ===")
      (doseq [dist [1 2 3]]
        (let [t0 (System/nanoTime)
              n (walk-node db [:seon.cluster.agent/id "alpha"] dist)
              text (or (walk/prose n) "")
              ms (/ (- (System/nanoTime) t0) 1e6)
              all (nodes n)
              elided (count (filter #(= :seon.render.walk/elided
                                        (:seon.error/kind (:seon.error/value %))) all))]
          (println (format "d%d nodes=%d distinct=%d elided=%d ms=%.0f chars=%d tokens=%s"
                           dist (count all) (count (distinct (map :seon.render.walk/lookup all)))
                           elided ms (count text) (tokens/estimate text))))))))
