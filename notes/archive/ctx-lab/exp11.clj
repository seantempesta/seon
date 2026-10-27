;; Experiment 11: the hazard of a swap! whose function writes the db, as ctx-atom.clj stands (no basis)
(def R "lab/ctx#1")
(def calls (atom 0))
(def E11 (let [r (try (swap! ctx (fn [c] (agent/transact! [{:seon.task/id "lab/ctx" :seon.task/plan (str "written inside f, call " (swap! calls inc))}])
                                   (assoc-in c [:seon.run/_agent R :seon.run/task :seon.task/instructions] "set by the swap!")))
                      :returned (catch Exception e (ex-message e)))
               t (d/pull (agent/db) [:seon.task/plan :seon.task/instructions] [:seon.task/id "lab/ctx"])]
           {:swap r :f-calls @calls :db t
            :ctx (select-keys (get-in @ctx [:seon.run/_agent R :seon.run/task]) [:seon.task/plan :seon.task/instructions])
            :equal? (= @ctx (tree (me)))}))
