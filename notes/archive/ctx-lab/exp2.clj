;; Experiment 2 (patch): run after ctx.clj and (def C (ctx0)), in the lab agent
(def E2 (let [r1 (transact-diff! (new-eval-tx))
      diff-cost (cost/measure (fn [] (d/datoms-diff (:before r1) (:after r1) :eavt [])) [])
      p1 (cost/measure patch [C (:after r1) (:datoms r1)])
      C1 (patch C (:after r1) (:datoms r1))
      ;; a card-one change and a set member added
      r2 (transact-diff! [{:seon.task/id "lab/ctx" :seon.task/plan "A new plan." :seon.task/proofs #{'app.x-test/t9}}])
      C2 (patch C1 (:after r2) (:datoms r2))
      ;; a set member retracted, and a turn joining the run (two levels down)
      r3 (transact-diff! [[:db/retract [:seon.task/id "lab/ctx"] :seon.task/proofs 'app.x-test/t0]
                          {:seon.turn/id "lab/ctx#1/9999" :seon.turn/run [:seon.run/id "lab/ctx#1"] :seon.turn/n 9999 :seon.turn/reply "hi"
                           :seon.turn/forms 1 :seon.turn/errors 0 :seon.turn/red 0 :seon.turn/ms 1}])
      C3 (patch C2 (:after r3) (:datoms r3))
      ;; an eval retracted whole
      r4 (transact-diff! [[:db/retractEntity [:seon.eval/id "s7"]]])
      C4 (patch C3 (:after r4) (:datoms r4))
      fresh (tree (me))]
  (def C C4)
  {:datoms [(count (:datoms r1)) (count (:datoms r2)) (count (:datoms r3)) (count (:datoms r4))]
   :diff-cost diff-cost :patch-new-eval p1
   :patch-retract (cost/measure patch [C3 (:after r4) (:datoms r4)])
   :rebuild (cost/measure tree [(me)])
   :equal? (= (:tree C4) fresh)
   :task (select-keys (get-in (:tree C4) [:seon.run/_agent "lab/ctx#1" :seon.run/task]) [:seon.task/plan :seon.task/proofs])
   :turn? (contains? (get-in (:tree C4) [:seon.run/_agent "lab/ctx#1" :seon.turn/_run]) "lab/ctx#1/9999")
   :s7? (contains? (:seon.eval/_agent (:tree C4)) "s7")}))
