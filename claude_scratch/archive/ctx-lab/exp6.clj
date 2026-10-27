;; Experiment 6: what a write and a read cost on this agent's ctx (3,000 evals on a1, 100 on a2)
(def R "lab/ctx#1")
(defn m1 [f] (let [m (volatile! nil)] (seon.profile/measure! f #(vreset! m %)) (select-keys @m [:ticks :bytes])))
(def E6
  (let [k (atom 0)
        plan (m1 #(swap! ctx assoc-in [:seon.run/_agent R :seon.run/task :seon.task/plan] (str "Plan " (swap! k inc))))
        plan-tx (m1 #(agent/transact! [{:seon.task/id "lab/ctx" :seon.task/plan "Plan by transact!"}]))
        drop-eval (m1 #(swap! ctx update :seon.eval/_agent dissoc (first (keys (:seon.eval/_agent @ctx)))))
        diff-only (let [o @ctx nv (update o :seon.eval/_agent dissoc (second (keys (:seon.eval/_agent o))))]
                    (m1 #(diff-entity :seon.db/agent-fact o nv (:db/id o) [] {:tx [] :invalid [] :paths []})))
        new-eval-read (m1 #(agent/transact! (new-eval-tx)))
        new-eval-bare (do (remove-watch agent/state ::ctx) (let [r (m1 #(agent/transact! (new-eval-tx)))] (add-watch agent/state ::ctx on-state) (load!) r))]
    {:evals (count (:seon.eval/_agent @ctx))
     :swap-plan plan :transact-plan-no-ctx-write plan-tx :swap-drop-eval drop-eval :diff-of-a-drop diff-only
     :transact-new-eval-with-hook new-eval-read :transact-new-eval-without-hook new-eval-bare
     :equal? (= @ctx (tree (me)))}))
