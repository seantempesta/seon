(in-ns 'falsify.harness)
;;; ATTACK 1b — the pathological block, and tree-vs-flat ordering.

(defn narrow-basis [db eid] (reduce max 0 (map :tx (d/datoms db :eavt eid))))
(defn walk-basis [db eid]
  (max (narrow-basis db eid)
       (reduce max 0 (map first (d/q '[:find ?tx :in $ ?t :where [?s ?a ?t ?tx]] db eid)))))

(with-db
  (fn [conn]
    (seed! conn)
    (let [root [:seon.cluster.agent/id "alpha"]
          agent-eid (d/q '[:find ?e . :where [?e :seon.cluster.agent/id "alpha"]] @conn)
          beta-eid  (d/q '[:find ?e . :where [?e :seon.cluster.agent/id "beta"]] @conn)
          inst-eid  (d/q '[:find ?e . :where [?e :seon.cluster.instruction/id :reply-grammar]] @conn)
          rows (atom [])]
      (dotimes [turn 12]
        (message! conn {:id (str "m" turn) :to "alpha" :from "beta"
                        :content (str "turn " turn)
                        :at (java.util.Date. (+ 1785000000000 (* turn 60000)))})
        (let [db @conn
              n (walk-node db root 1)
              agent-node (first (nodes n))
              agent-bytes (or (node-text agent-node) "")
              tree-text (or (walk/prose n) "")]
          (swap! rows conj
                 {:turn turn
                  :agent-narrow (narrow-basis db agent-eid)
                  :agent-walk (walk-basis db agent-eid)
                  :agent-bytes (count agent-bytes)
                  :agent-digest (hash agent-bytes)
                  :beta-narrow (narrow-basis db beta-eid)
                  :beta-walk (walk-basis db beta-eid)
                  :inst-narrow (narrow-basis db inst-eid)
                  :inst-walk (walk-basis db inst-eid)
                  :tree-bytes (count tree-text)
                  :tree-digest (hash tree-text)})))
      (println "\n=== P3 soundness: does the ROOT block report fresh while its bytes change? ===")
      (doseq [r @rows] (prn (select-keys r [:turn :agent-narrow :agent-walk :agent-bytes :agent-digest])))
      (println "\nagent narrow-basis distinct values:" (distinct (map :agent-narrow @rows)))
      (println "agent rendered-digest distinct values:" (count (distinct (map :agent-digest @rows))))
      (println "\n=== the never-stabilizing neighbour: beta (a PEER, own datoms frozen) ===")
      (doseq [r (take 6 @rows)] (prn (select-keys r [:turn :beta-narrow :beta-walk])))
      (println "\n=== the stable instruction row ===")
      (doseq [r (take 3 @rows)] (prn (select-keys r [:turn :inst-narrow :inst-walk])))

      ;; tree vs flat: does prose order follow basis at all?
      (let [db @conn
            n (walk-node db root 2)
            all (->> (nodes n) (filter #(integer? (:seon.render.walk/lookup %))))
            traversal (mapv :seon.render.walk/lookup all)
            by-basis (mapv :eid (sort-by :eid (map (fn [e] {:eid e}) (distinct traversal))))
            basis-order (->> (distinct traversal) (sort-by #(walk-basis db %)) vec)]
        (println "\n=== ATTACK 1c: prose assembly order vs basis order ===")
        (println "traversal order (first 12):" (take 12 (distinct traversal)))
        (println "basis   order (first 12):" (take 12 basis-order))
        (println "identical?" (= (vec (distinct traversal)) basis-order))
        (println "prose emits parent framing; a flat basis sort discards nesting.")
        (println "sample prose head:")
        (println (subs (or (walk/prose n) "") 0 (min 400 (count (or (walk/prose n) ""))))))))) 
