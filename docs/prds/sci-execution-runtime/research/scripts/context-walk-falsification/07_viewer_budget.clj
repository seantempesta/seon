(in-ns 'falsify.harness)
;;; ATTACK 5b (viewer dimension, done right) + ATTACK 4 (token budget).

(defn find-node [n eid] (first (filter #(= eid (:seon.render.walk/lookup %)) (nodes n))))
(defn shout-ai [unit] (str "!!! " (:seon.fn/sym unit)))

(with-db
  (fn [conn]
    (seed! conn)
    (let [db @conn
          fn-eid (d/q '[:find ?e . :where [?e :seon.fn/sym "seon.render.walk/prose"]] db)
          fam (mapv :seon.schema/key (walk/family (d/pull db '[*] fn-eid)))]
      (println "\n=== 5b. VIEWER OVERRIDE is NOT in the spec's block-identity key ===")
      (println "fn entity" fn-eid "family" fam)
      (let [plain (walk/neighborhood {:seon.db/db db :seon.render.walk/lookup fn-eid
                                      :seon.render/kind :seon.render/ai
                                      :seon.render/floor 'seon.render.block/data-prose
                                      :seon.render/distance 0 :seon.sci.admit/caps caps})
            over  (walk/neighborhood {:seon.db/db db :seon.render.walk/lookup fn-eid
                                      :seon.render/kind :seon.render/ai
                                      :seon.render/floor 'seon.render.block/data-prose
                                      :seon.render/overrides {(first fam) 'falsify.harness/shout-ai}
                                      :seon.render/distance 0 :seon.sci.admit/caps caps})]
        (println "no-override projection:" (:seon.render/projection plain))
        (println "  override projection:" (:seon.render/projection over))
        (println "bytes differ?" (not= (node-text plain) (node-text over)))
        (println "override bytes:" (pr-str (node-text over)))
        (println "-> projection IS in the key, so a DIRECT node is safe;"
                 "the unsound case is a DESCENDANT whose own projection is unchanged"
                 "but whose PARENT chain differs, and the subtree above."))

      (println "\n=== ATTACK 4. token budget for a realistic agent ===")
      (doseq [dist [1 2 3]]
        (let [n (walk-node db [:seon.cluster.agent/id "alpha"] dist)
              text (or (walk/prose n) "")
              all (nodes n)
              by-family (->> all
                             (keep (fn [nd]
                                     (when-let [t (node-text nd)]
                                       [(let [l (:seon.render.walk/attribute nd)]
                                          (if l (namespace l) "ROOT"))
                                        (tokens/estimate t)])))
                             (reduce (fn [m [k v]] (update m k (fnil + 0) v)) {}))]
          (println (format "\n-- distance %d: %s tokens over %d nodes (%d distinct) --"
                           dist (tokens/estimate text) (count all)
                           (count (distinct (map :seon.render.walk/lookup all)))))
          (doseq [[k v] (sort-by (comp - val) by-family)]
            (println (format "   %-32s %8d tok  %5.1f%%" k v
                             (* 100.0 (/ v (max 1 (tokens/estimate text)))))))))

      (println "\n=== budget verdict ===")
      (doseq [[label budget] [["30k" 30000] ["50k" 50000]]]
        (doseq [dist [1 2 3]]
          (let [t (tokens/estimate (or (walk/prose (walk-node db [:seon.cluster.agent/id "alpha"] dist)) ""))]
            (println (format "  d%d = %6d tok  vs %s budget -> %s"
                             dist t label (if (<= t budget) "FITS" "BLOWN")))))))))
