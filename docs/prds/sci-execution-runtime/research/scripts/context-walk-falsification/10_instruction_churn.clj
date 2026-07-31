(in-ns 'falsify.harness)
;;; ATTACK 1d — spec §3's own claim: "an instruction row untouched since
;;; seeding keeps its seed basis forever and fronts every prompt."
(defn narrow-basis [db e] (reduce max 0 (map :tx (d/datoms db :eavt e))))
(defn walk-basis [db e]
  (max (narrow-basis db e)
       (reduce max 0 (map first (d/q '[:find ?tx :in $ ?t :where [?s ?a ?t ?tx]] db e)))))

(with-db
  (fn [conn]
    (seed! conn)
    (let [inst (d/q '[:find ?e . :where [?e :seon.cluster.instruction/id :reply-grammar]] @conn)
          irefs (mapv (fn [[id _]] [:seon.cluster.instruction/id id]) instructions)]
      (println "\nturn | event                | inst narrow | inst walk | rank-by-walk (of N blocks)")
      (dotimes [turn 10]
        (let [event (cond (= turn 3) (do (tx! conn [{:seon.cluster.agent/id (str "new" turn)
                                                     :seon.cluster.agent/instructions irefs}])
                                         "PEER AGENT CREATED")
                          (= turn 6) (do (tx! conn [{:seon.cluster.agent/id (str "new" turn)
                                                     :seon.cluster.agent/instructions irefs}])
                                         "PEER AGENT CREATED")
                          :else (do (message! conn {:id (str "m" turn) :to "alpha"
                                                    :content (str "t" turn)
                                                    :at (java.util.Date. (+ 1785000000000 (* turn 1000)))})
                                    "message to alpha"))
              db @conn
              n (walk-node db [:seon.cluster.agent/id "alpha"] 2)
              eids (distinct (filter integer? (map :seon.render.walk/lookup (nodes n))))
              ordered (sort-by #(walk-basis db %) eids)
              rank (.indexOf (vec ordered) inst)]
          (println (format "%4d | %-20s | %11d | %9d | %d/%d"
                           turn event (narrow-basis db inst) (walk-basis db inst)
                           rank (count eids))))))))
