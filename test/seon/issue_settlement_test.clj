(ns seon.issue-settlement-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [seon.eval]
            [clojure.core.async :as async]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.wake :as wake]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.id]
            [seon.issue]
            [seon.plan :as plan]
            [seon.schema]
            [seon.schema.datahike]
            [seon.schema.edn]
            [seon.sci.eval :as evaluation]
            [seon.test :as tests]
            [seon.turn :as turn]
            [seon.test-support :as support]))

(defn- admit! [connection ctx namespace-name source]
  (let [effective (config/effective (db/db connection) "issue-settlement")
        evaluated (evaluation/evaluate
                   {:seon.cluster.eval/source source
                    :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                    :seon.sci.eval/ctx ctx
                    :seon.sci.eval/time-limit-ms (:seon.config.eval/time-limit-ms effective)
                    :seon.sci.admit/caps (config/result-caps effective)
                    :seon.config/on-core-error (:seon.config/on-core-error effective)
                    :seon.db/db (db/db connection) :seon.db/connection connection})
        analysis (functions/analyze-forms
                  (db/db connection)
                  [{:seon.cluster.eval/source source
                    :seon.cluster.eval/ns [:seon.ns/name namespace-name]
                    :seon.program/row (:seon.program/row evaluated)}])
        row (when-not (:seon.error/kind analysis) (second (first analysis)))]
    (when (or (:seon.cluster.eval/error evaluated) (not row))
      (throw (ex-info "Issue test source admission failed."
                      {:seon.test/evaluation evaluated :seon.test/analysis analysis})))
    (let [report (db/transact! connection [(dissoc row :seon.sci.eval/evaluated?)])]
      (when (:seon.error/kind report) (throw (ex-info "Issue test admission transaction failed." report)))
      (evaluation/install-evaluated-rows!
       {:seon.sci.eval/ctx ctx :seon.db/db (:db-after report)
        :seon.sci.eval/installations [{:seon.program/row row :seon.sci.eval/evaluation evaluated}]}))))

(deftest issue-settlement-runs-tests-and-derives-completion
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "issue-settlement")
     (support/transacted! connection
                          (agent/creation-tx {:seon.agent/id "root" :seon.ns/name 'my.agents.root
                                              :seon.cluster/name "issue-settlement"}))
     (let [aid "issue-settlement-worker"
           namespace-name 'my.agents.issue-settlement
           test-symbol "my.agents.issue-settlement/success-test"
           steady-symbol "my.agents.issue-settlement/steady-test"
           _ (support/transacted! connection
                     (agent/creation-tx {:seon.agent/id aid :seon.ns/name namespace-name
                                         :seon.cluster/name "issue-settlement"}))
           _ (support/transacted! connection
                                 [{:seon.agent/id aid :seon.agent/settings
                                   {:seon.config.run/max-episode-runs 2}}])
           ctx (support/fork-cluster-ctx connection)
           handle (support/cluster-handle
                   {:seon.db/connection connection :seon.cluster/name "issue-settlement"
                    :seon.sci.eval/ctx ctx :seon.db.process/id cluster/boot-process-identity})
           evidence (fn [test-symbol]
                      (db/pull (db/db connection)
                        '[:seon.test/pass-count :seon.test/fail-count :seon.test/error-count
                          :seon.test/failure-message :seon.test/reach-digest
                          {:seon.test/run [:seon.test.run/id :seon.test.run/basis-t
                                           :seon.test.run/program-digest]}]
                        [:seon.test/sym test-symbol]))
           run-id (fn [test-symbol]
                    (get-in (evidence test-symbol) [:seon.test/run :seon.test.run/id]))
           system-settle
           (fn []
             (let [report (turn/system-turn
                            {:seon.turn.loop/cluster handle
                             :seon.agent/id aid :seon.turn/write? true})]
               (is (nil? (:seon.error/kind report)) (pr-str report))))
           closed? (fn [tid]
                     (some? (:seon.turn/closed-tx
                             (db/pull (db/db connection) [:seon.turn/closed-tx]
                                      [:seon.turn/id tid]))))
           ;; The ordinary close is the settlement under test, so the fixture
           ;; drives the real turn transitions: open an ordinary turn, accept
           ;; its virtual reply in the next transaction, then advance that
           ;; turn's own work until it carries a closed-tx. The pass bound is
           ;; LOUD — a turn that never closes names itself instead of hanging.
           close-ordinary-turn!
           (fn []
             (let [_ (when-not (turn/open-for-agent (db/db connection) [:seon.agent/id aid])
                       (let [work (turn/next-agent-work (db/db connection) {:seon.agent/id aid})]
                         (is (= :open (:seon.turn.work/situation work)) (pr-str work))
                         (turn/turn {:seon.turn.loop/cluster handle :seon.turn.work/next work}
                                    (java.util.Date.))))
                   tid (turn/open-for-agent (db/db connection) [:seon.agent/id aid])
                   submitted (support/transacted! connection
                               (turn/plan-tx {:seon.turn/id tid
                                              :seon.turn/reply "(+ 20 22)" :seon.turn/reply-size 9
                                              :seon.turn/sources [{:seon.cluster.eval/source "(+ 20 22)"}]}))]
               (is (string? tid) (pr-str submitted))
               (loop [pass 0]
                 (cond
                   (closed? tid) tid
                   (<= 24 pass)
                   (throw (ex-info "The submitted ordinary turn never closed."
                                   {:seon.turn/id tid :seon.turn.loop/forms-run pass}))
                   :else
                   (do (when-let [work (turn/next-agent-work
                                        (db/db connection) {:seon.agent/id aid})]
                         (when (= tid (:seon.turn/id work))
                           (turn/turn {:seon.turn.loop/cluster handle
                                       :seon.turn.work/next work}
                                      (java.util.Date.))))
                       (recur (inc pass)))))))
           issue-ref [:seon.issue/id "settlement-fixture"]
           step (fn [] (db/pull (db/db connection) [:my.plan.item/completed-tx]
                                [:my.plan.item/id "settlement-step"]))]
       (try
         (admit! connection ctx namespace-name
                 "(defn answer {:malli/schema [:=> [:cat] :int]} [] 0)")
         (admit! connection ctx namespace-name
                 "(defn constant {:malli/schema [:=> [:cat] :int]} [] 7)")
         (admit! connection ctx namespace-name
                 "(clojure.test/deftest success-test (clojure.test/is (= 1 (answer))))")
         (admit! connection ctx namespace-name
                 "(clojure.test/deftest steady-test (clojure.test/is (= 7 (constant))))")
         (is (:db-after
              (db/transact! connection
               [{:db/id "issue" :seon.issue/id "settlement-fixture"
                 :seon.issue/title "Settle from test evidence"
                 :seon.issue/status :open :seon.issue/severity :cleanup
                 :seon.issue/problem "The SCI answer must be one."
                 :seon.issue/agent [:seon.agent/id aid] :seon.issue/budget 2
                 :seon.issue/tests #{[:seon.test/sym test-symbol]
                                     [:seon.test/sym steady-symbol]}}
                {:seon.agent/id aid
                 :seon.agent/plan
                 {:my.plan/objective "Verify the SCI answer"
                  :my.plan/steps [{:my.plan.item/id "settlement-step"
                                   :my.plan.item/title "Make answer one"
                                   :my.plan.item/position 0
                                   :my.plan.item/subject "issue"
                                   :my.plan.item/done-query plan/issue-done-query}]}}])))
         ;; A SYSTEM TURN RUNS NO ISSUE TESTS. It appends generated reads;
         ;; nothing it settles can newly satisfy the issue, and the opening
         ;; settles one form per pass, so a test run there is pure cost.
         (system-settle)
         (is (nil? (:seon.test/run (evidence test-symbol))))
         (is (nil? (:seon.test/run (evidence steady-symbol))))
         (is (nil? (:my.plan.item/completed-tx (step))))
         ;; The ordinary close runs the issue's stale tests — here both,
         ;; because neither has a recorded result yet.
         (close-ordinary-turn!)
         (let [red (evidence test-symbol)
               steady (evidence steady-symbol)
               red-run (run-id test-symbol)
               steady-run (run-id steady-symbol)]
           (is (= :open (:seon.turn.work/situation
                         (turn/next-agent-work (db/db connection) {:seon.agent/id aid}))))
           (let [shown (:seon.eval/shown
                        (last (filter #(str/includes? (:seon.cluster.eval/source % "") "my.issue/status")
                                      (seon.eval/of-agent (db/db connection) aid))))]
             (println {:seon.test/issue-status :failed :seon.eval/shown shown})
             (is (str/includes? shown test-symbol) shown)
             (is (str/includes? shown (:seon.test/failure-message red)) shown))
           (is (= 1 (:seon.test/fail-count red)) (pr-str red))
           (is (string? red-run))
           (is (string? (:seon.test/reach-digest red)))
           (is (= 1 (:seon.test/pass-count steady)) (pr-str steady))
           (is (nil? (:my.plan.item/completed-tx (step))))
           ;; A CLOSE THAT CHANGED NOTHING RUNS NOTHING. Both reach closures
           ;; are unchanged, so both recorded results stand unaltered.
           (close-ordinary-turn!)
           (is (= red-run (run-id test-symbol)))
           (is (= steady-run (run-id steady-symbol)))
           (is (nil? (:my.plan.item/completed-tx (step))))
           (is (some? (:seon.issue/budget-exhausted-tx
                        (db/pull (db/db connection) [:seon.issue/budget-exhausted-tx] issue-ref))))
           (is (nil? (turn/next-agent-work (db/db connection) {:seon.agent/id aid})))
           (let [status-count (fn []
                                (count (filter #(str/includes? (:seon.cluster.eval/source % "") "my.issue/status")
                                               (seon.eval/of-agent (db/db connection) aid))))
                 before (status-count)]
             (system-settle)
             (is (= before (status-count)) "An unchanged system pass appends no second issue status."))
           (let [messages (db/q '[:find [?text ...] :in $ ?aid :where
                                   [?agent :seon.agent/id ?aid]
                                   [?message :seon.message/from ?agent]
                                   [?root :seon.agent/id "root"]
                                   [?message :seon.message/to ?root]
                                   [?message :seon.message/content ?text]] (db/db connection) aid)]
             (is (= 1 (count messages)) (pr-str messages))
             (is (str/includes? (first messages) "after 2 ordinary turns")))
           (let [resumed (seon.issue/start! {:seon.db/connection connection
                                           :seon.issue/id "settlement-fixture" :seon.issue/budget 4})]
             (is (nil? (:seon.error/kind resumed)) (pr-str resumed))
             (is (= aid (get-in resumed [:seon.issue/agent :seon.agent/id])))
             (is (= 2 (turn/turns-left (db/db connection) aid)))
             (let [database (db/db connection)
                   issue-eid (:db/id (db/pull database [:db/id] issue-ref))
                   budget-datom (first (db/datoms database :eavt issue-eid :seon.issue/budget))
                   matchers (get-in (#'wake/wake-matchers database)
                                    [:seon.issue/budget :seon.cluster.wake/matches])]
               (is (= [(:db/id (db/pull database [:db/id] [:seon.agent/id aid]))]
                      (vec (keep #(% budget-datom) matchers)))
                   "The committed budget datom addresses the resumed worker's existing wake route.")))
           ;; Editing one reached function makes exactly its test stale.
           (admit! connection ctx namespace-name
                   "(defn answer {:malli/schema [:=> [:cat] :int]} [] (throw (ex-info \"red\" {})))")
           (close-ordinary-turn!)
           (let [errored (evidence test-symbol)]
             (is (pos? (:seon.test/error-count errored)) (pr-str errored))
             (is (not= red-run (run-id test-symbol)))
             (is (= steady-run (run-id steady-symbol)))
             (is (nil? (:my.plan.item/completed-tx (step)))))
           ;; The shared deadline still owns a test that will not finish, and
           ;; the expiry is recorded against the test rather than swallowed.
           (admit! connection ctx namespace-name
                   "(defn answer {:malli/schema [:=> [:cat] :int]} [] (loop [] (recur)))")
           (support/transacted! connection
                   [{:seon.agent/id aid :seon.agent/settings {:seon.config.eval/time-limit-ms 100}}])
           (plan/run-issue-tests! handle aid)
           (let [expired (evidence test-symbol)]
             (is (pos? (:seon.test/error-count expired)) (pr-str expired))
             (is (seq (:seon.test/failure-message expired)))
             (is (= steady-run (run-id steady-symbol)))
             (is (nil? (:my.plan.item/completed-tx (step)))))
           (support/transacted! connection
                   [{:seon.agent/id aid :seon.agent/settings {:seon.config.eval/time-limit-ms 10000}}])
           (admit! connection ctx namespace-name
                   "(defn answer {:malli/schema [:=> [:cat] :int]} [] 1)")
           (close-ordinary-turn!)
           (let [green (evidence test-symbol)
                 completed (:my.plan.item/completed-tx (step))
                 resolved (:seon.issue/resolved-tx (db/pull (db/db connection)
                                                  [:seon.issue/resolved-tx] issue-ref))]
             (is (= 1 (:seon.test/pass-count green)) (pr-str green))
             (is (not= red-run (get-in green [:seon.test/run :seon.test.run/id])))
             (is (true? (tests/verified? (db/db connection) test-symbol)))
             ;; The step completes on RECORDED evidence: steady-test has not
             ;; re-run since its first green, and still answers the query.
             (is (= steady-run (run-id steady-symbol)))
             (is (true? (tests/verified? (db/db connection) steady-symbol)))
             (is (some? completed))
             (is (= completed resolved))
             (let [shown (:seon.eval/shown
                          (last (filter #(str/includes? (:seon.cluster.eval/source % "") "my.issue/status")
                                        (seon.eval/of-agent (db/db connection) aid))))]
               (println {:seon.test/issue-status :passed :seon.eval/shown shown})
               (is (str/includes? shown (str test-symbol ": passed")) shown)
               (is (str/includes? shown "resolved") shown))))
         (finally
           (doseq [k [:seon.cluster.wake/channel :seon.render/context-channel
                      :seon.turn.loop/completion]]
             (async/close! (get handle k)))))))))

(deftest started-issue-tests-retain-historical-authority
  (support/with-database
   (fn [connection]
     (let [retention-projection
           (seon.schema/build-projection
            (merge (:seon.schema.projection/forms (db/carried-projection (db/db connection)))
                   (seon.schema.edn/packaged-forms)))]
     (seon.schema/call-with-projection
      retention-projection
      (fn []
        (is (:db-after (db/transact! connection
                        [(seon.schema.datahike/malli->datahike-attr-in
                          retention-projection :seon.issue/created-by)])))
        (support/seed-cluster! connection "issue-settlement")
        (support/transacted! connection [{:seon.ns/name 'my.agents.retention}])
        (let [ctx (support/fork-cluster-ctx connection)]
          (admit! connection ctx 'my.agents.retention
                  "(clojure.test/deftest a (clojure.test/is true))")
          (admit! connection ctx 'my.agents.retention
                  "(clojure.test/deftest b (clojure.test/is true))"))
        (let [issue [:seon.issue/id "retention-fixture"]
              a [:seon.test/sym "my.agents.retention/a"]
              b [:seon.test/sym "my.agents.retention/b"]
              creator [:seon.agent/id "retention-creator"]
              worker [:seon.agent/id "retention-worker"]
              write (fn [actor tx] (db/transact! connection {:tx-data tx :tx-meta {:seon.db/user actor}}))
              tests-now (fn [] (set (map :seon.test/sym
                                     (:seon.issue/tests
                                      (db/pull (db/db connection)
                                       '[{:seon.issue/tests [:seon.test/sym]}] issue)))))]
          (is (:db-after
               (db/transact! connection
                [{:seon.agent/id "retention-creator"} {:seon.agent/id "retention-worker"}
                 {:seon.issue/id "retention-fixture" :seon.issue/title "Keep required tests"
                  :seon.issue/path "docs/seon/issues/retention-fixture.md"
                  :seon.issue/problem "Required tests survive assignment changes."
                  :seon.issue/status :open :seon.issue/severity :cleanup
                  :seon.issue/created-by creator :seon.issue/agent worker
                  :seon.issue/tests #{a b}}])))
          (doseq [tx [[[:db/retract issue :seon.issue/tests a]]
                     [[:db/retract issue :seon.issue/tests]]
                     [[:db/retract a :seon.test/sym "my.agents.retention/a"]]
                     [[:db.fn/retractAttribute issue :seon.issue/tests]]
                     [[:db.fn/retractEntity a]]
                     [[:db.fn/call (fn [_] [[:db/retract issue :seon.issue/tests a]])]]
                     [[:db/retract issue :seon.issue/agent worker]]
                     [[:db/add issue :seon.issue/created-by worker]]]]
            (let [result (write worker tx)]
              (is (= :seon.db/retention-refused (:seon.error/kind result)) (pr-str result))
              (is (= #{"my.agents.retention/a" "my.agents.retention/b"} (tests-now)))))
          (let [refused (write worker [[:db/retract issue :seon.issue/tests a]])]
            (is (.contains (:seon.error/message refused "") "retention-fixture"))
            (is (.contains (:seon.error/message refused "") "my.agents.retention/a")))
          (is (:db-after (write creator [[:db/retract issue :seon.issue/agent worker]])))
          (is (= :seon.db/retention-refused
                 (:seon.error/kind (write worker [[:db/retract issue :seon.issue/tests a]]))))
          (is (:db-after (write creator [[:db/retract issue :seon.issue/tests a]])))
          (is (= #{"my.agents.retention/b"} (tests-now)))
          (is (= :seon.db/retention-refused
                 (:seon.error/kind (write creator [[:db/retract issue :seon.issue/tests b]]))))
          (is (= #{"my.agents.retention/b"} (tests-now)))
          (is (:db-after (db/transact! connection [[:db.fn/call #'seon.issue/adopt-tx []]])))
          (is (= #{"my.agents.retention/b"} (tests-now)))
          (is (= "retention-creator"
                 (get-in (db/pull (db/db connection)
                           '[{:seon.issue/created-by [:seon.agent/id]}] issue)
                         [:seon.issue/created-by :seon.agent/id])))
          (let [request {:seon.issue/title "Authored retention"
                         :seon.issue/problem "Record creator authority."
                         :seon.issue/severity :cleanup :seon.issue/tests #{a}
                         :seon.agent/id "retention-creator"}
                authored-id (seon.id/id ["Authored retention" ()])]
            (is (:db-after (db/transact! connection [[:db.fn/call #'seon.issue/add-tx request]])))
            (is (= "retention-creator"
                   (get-in (db/pull (db/db connection)
                             '[{:seon.issue/created-by [:seon.agent/id]}]
                             [:seon.issue/id authored-id])
                           [:seon.issue/created-by :seon.agent/id])))
            (is (:db-after
                 (db/transact! connection
                   [[:db.fn/call #'seon.issue/tests-tx
                     {:seon.issue/id authored-id :seon.issue/tests #{b}
                      :seon.agent/id "retention-worker"}]])))
            (is (= 2 (count (:seon.issue/tests
                              (db/pull (db/db connection) [:seon.issue/tests]
                                       [:seon.issue/id authored-id])))))))))))))
