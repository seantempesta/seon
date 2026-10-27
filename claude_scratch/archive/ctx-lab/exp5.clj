;; Experiment 5: writes through swap!, and refusals
(def R "lab/ctx#1")
(def E5
  (let [db0 (agent/db) t0 @ctx
        try* (fn [f] (try {:ok (some? (f))} (catch Exception e {:refused (ex-message e) :data (dissoc (ex-data e) :invalid) :invalid (some-> (ex-data e) :invalid first (select-keys [:at :value :explain]))})))
        a (try* #(swap! ctx assoc-in [:seon.run/_agent R :seon.run/task :seon.task/plan] "Plan five."))
        a-db (:seon.task/plan (d/pull (agent/db) [:seon.task/plan] [:seon.task/id "lab/ctx"]))
        b (try* #(swap! ctx assoc-in [:seon.run/_agent R :seon.run/task :seon.task/execution] :sideways))
        b-db (:seon.task/execution (d/pull (agent/db) [:seon.task/execution] [:seon.task/id "lab/ctx"]))
        c (try* #(swap! ctx assoc-in [:seon.run/_agent R :seon.message/_to "m-new"] {:seon.message/id "m-new" :seon.message/from "a1" :seon.message/body "a note to myself"}))
        c-db (d/pull (agent/db) [:seon.message/body {:seon.message/to [:seon.run/id]}] [:seon.message/id "m-new"])
        c-id (get-in @ctx [:seon.run/_agent R :seon.message/_to "m-new" :db/id])
        d (try* #(swap! ctx update :seon.eval/_agent dissoc "s9"))
        d-db (d/pull (agent/db) [:seon.eval/id] [:seon.eval/id "s9"])
        d-hist (count (d/datoms (d/history (agent/db)) :avet :seon.eval/id "s9"))
        e (try* #(swap! ctx assoc-in [:seon.run/_agent R :seon.message/_to "m-bad"] {:seon.message/id "m-bad" :seon.message/from "a1"}))
        f (try* #(swap! ctx assoc-in [:seon.run/_agent R :seon.run/pid] 4242))
        g (try* #(swap! ctx assoc-in [:seon.run/_agent R :seon.run/task :seon.task/proofs] #{'app.x-test/t1 'app.x-test/t5}))
        g-db (:seon.task/proofs (d/pull (agent/db) [:seon.task/proofs] [:seon.task/id "lab/ctx"]))
        fresh (tree (me))]
    {:a-plan [a a-db] :b-invalid [b b-db] :c-new-message [c c-db (some? c-id)] :d-remove-eval [d d-db {:history-datoms d-hist}]
     :e-missing-body e :f-left-out-attr f :g-set [g g-db]
     :equal-after? (= @ctx fresh)}))
