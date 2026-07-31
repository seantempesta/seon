(in-ns 'falsify.harness)
;;; ATTACK 5 — cache-key soundness: same key, different bytes.

(defn find-node [n eid] (first (filter #(= eid (:seon.render.walk/lookup %)) (nodes n))))
(defn subtree-prose [nd] (or (walk/prose {:seon.render.walk/neighbours [nd]}) ""))

;; a first-party renderer we can hot-reload mid-probe
(defn instruction-ai [unit] (str "INSTRUCTION " (:seon.cluster.instruction/id unit)))

(with-db
  (fn [conn]
    (seed! conn)
    (let [irefs (mapv (fn [[id _]] [:seon.cluster.instruction/id id]) instructions)]
      (tx! conn (into [] (for [i (range 5)]
                           {:seon.cluster.agent/id (str "peer" i)
                            :seon.cluster.agent/instructions irefs}))))
    (let [db @conn
          inst (d/q '[:find ?e . :where [?e :seon.cluster.instruction/id :reply-grammar]] db)
          basis (:max-tx db)
          a (walk-node db [:seon.cluster.agent/id "alpha"] 2)
          b (walk-node db [:seon.cluster.agent/id "beta"] 2)
          an (find-node a inst) bn (find-node b inst)]

      (println "\n=== 5a. SAME spec key (entity, renderer-var, distance, projection, basis) ===")
      (println "entity" inst "distance" (:seon.render/distance an)
               "projection" (:seon.render/projection an) "basis" basis)
      (println "beta   distance" (:seon.render/distance bn)
               "projection" (:seon.render/projection bn))
      (println "key identical?"
               (= [inst (:seon.render/distance an) (:seon.render/projection an) basis]
                  [inst (:seon.render/distance bn) (:seon.render/projection bn) basis]))
      (println "leaf block bytes identical?" (= (node-text an) (node-text bn)))
      (println "SUBTREE bytes identical?  " (= (subtree-prose an) (subtree-prose bn))
               (str "(" (count (subtree-prose an)) " vs " (count (subtree-prose bn)) ")"))

      (println "\n=== 5b. the VIEWER dimension: an override changes DESCENDANT bytes ===")
      ;; alpha's namespace declares an override for the message schema; beta's does not.
      (let [ov {:seon.cluster.instruction/instruction 'falsify.harness/instruction-ai}
            a2 (walk-node db [:seon.cluster.agent/id "alpha"] 2 ov)
            a2n (find-node a2 inst)]
        (println "with override, projection =" (:seon.render/projection a2n))
        (println "with override, bytes      =" (pr-str (node-text a2n)))
        (println "NOTE: overrides key on a REGISTERED schema key; the instruction family"
                 "has no registered Malli entity map here, so `family` returns []")
        (println "family of the instruction entity:"
                 (mapv :seon.schema/key (walk/family (d/pull db '[*] inst)))))

      (println "\n=== 5c. HOT RELOAD: identical key, different bytes ===")
      (let [k [inst 0 'falsify.harness/instruction-ai basis]
            render1 (walk/neighborhood {:seon.db/db db :seon.render.walk/lookup inst
                                        :seon.render/kind :seon.render/ai
                                        :seon.render/floor 'falsify.harness/instruction-ai
                                        :seon.render/distance 0 :seon.sci.admit/caps caps})]
        (println "key" k "bytes ->" (pr-str (node-text render1)))
        (alter-var-root #'instruction-ai
                        (constantly (fn [unit] (str "REVISED " (:seon.cluster.instruction/id unit)))))
        (let [render2 (walk/neighborhood {:seon.db/db db :seon.render.walk/lookup inst
                                          :seon.render/kind :seon.render/ai
                                          :seon.render/floor 'falsify.harness/instruction-ai
                                          :seon.render/distance 0 :seon.sci.admit/caps caps})]
          (println "key" k "bytes ->" (pr-str (node-text render2)))
          (println "SAME KEY, DIFFERENT BYTES:" (not= (node-text render1) (node-text render2))))))))
