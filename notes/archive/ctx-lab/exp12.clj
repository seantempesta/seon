;; Experiment 12: exp11 again with the basis; and an ordinary write, a write of :t, and equality after
(def R "lab/ctx#1")
(reset! calls 0)
(def E12 (let [r (try (swap! ctx (fn [c] (agent/transact! [{:seon.task/id "lab/ctx" :seon.task/plan (str "written inside f, call " (swap! calls inc))}])
                                   (assoc-in c [:seon.run/_agent R :seon.run/task :seon.task/instructions] "set by the swap! again")))
                      :returned (catch Exception e (ex-message e)))
               t (d/pull (agent/db) [:seon.task/plan :seon.task/instructions] [:seon.task/id "lab/ctx"])
               ok (try (swap! ctx assoc-in [:seon.run/_agent R :seon.run/task :seon.task/plan] "Plan six.") :written (catch Exception e (ex-message e)))
               ro (try (swap! ctx assoc :t 1) :written (catch Exception e (ex-message e)))]
           {:swap-writing-db r :f-calls @calls :db t :ordinary ok :write-t ro
            :env (select-keys @ctx env-keys) :db-t (:max-tx (agent/db))
            :equal? (= (bare @ctx) (tree (me)))}))
