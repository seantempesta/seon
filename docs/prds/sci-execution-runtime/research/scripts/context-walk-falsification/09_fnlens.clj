(in-ns 'falsify.harness)
(with-db
  (fn [conn]
    (seed! conn)
    (let [db @conn
          n (walk-node db [:seon.cluster.agent/id "alpha"] 2)
          fn-nodes (filter #(= :seon.fn/ns (:seon.render.walk/attribute %)) (nodes n))
          eids (map :seon.render.walk/lookup fn-nodes)
          pulls (map #(d/pull db '[*] %) eids)]
      (println "fn nodes at d2:" (count fn-nodes))
      (println "raw floor tokens :" (reduce + (map #(tokens/estimate (or (node-text %) "")) fn-nodes)))
      (println "  of which source:" (reduce + (map #(tokens/estimate (str (:seon.fn/source %))) pulls)))
      (println "  arglists+doc1  :" (reduce + (map #(tokens/estimate
                                                     (str (:seon.fn/sym %) " " (:seon.fn/arglists %) " "
                                                          (first (clojure.string/split-lines (or (:seon.fn/doc %) "")))))
                                                   pulls)))
      (println "example floor bytes head:")
      (println (subs (or (node-text (first fn-nodes)) "") 0 250)))))
