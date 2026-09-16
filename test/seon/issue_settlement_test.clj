(ns seon.issue-settlement-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.plan :as plan]
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
     (let [aid "issue-settlement-worker"
           namespace-name 'my.agents.issue-settlement
           test-symbol "my.agents.issue-settlement/success-test"
           _ (db/transact! connection
               (agent/creation-tx {:seon.agent/id aid :seon.ns/name namespace-name
                                   :seon.cluster/name "issue-settlement"}))
           ctx (support/fork-cluster-ctx connection)
           handle (support/cluster-handle
                   {:seon.db/connection connection :seon.cluster/name "issue-settlement"
                    :seon.sci.eval/ctx ctx :seon.db.process/id cluster/boot-process-identity})
           settle (fn []
                    (let [report (turn/system-turn
                                   {:seon.turn.loop/cluster handle
                                    :seon.agent/id aid :seon.turn/write? true})]
                      (is (nil? (:seon.error/kind report)) (pr-str report)))
                    (db/pull (db/db connection)
                      '[:seon.test/pass-count :seon.test/fail-count :seon.test/error-count
                        :seon.test/failure-message :seon.test/reach-digest
                        {:seon.test/run [:seon.test.run/id :seon.test.run/basis-t
                                         :seon.test.run/program-digest]}]
                      [:seon.test/sym test-symbol]))
           issue-ref [:seon.issue/id "settlement-fixture"]
           step (fn [] (db/pull (db/db connection) [:my.plan.item/completed-tx]
                                [:my.plan.item/id "settlement-step"]))]
       (try
         (admit! connection ctx namespace-name
                 "(defn answer {:malli/schema [:=> [:cat] :int]} [] 0)")
         (admit! connection ctx namespace-name
                 "(clojure.test/deftest success-test (clojure.test/is (= 1 (answer))))")
         (is (:db-after
              (db/transact! connection
               [{:db/id "issue" :seon.issue/id "settlement-fixture"
                 :seon.issue/title "Settle from test evidence"
                 :seon.issue/status :open :seon.issue/severity :cleanup
                 :seon.issue/problem "The SCI answer must be one."
                 :seon.issue/agent [:seon.agent/id aid]
                 :seon.issue/tests #{[:seon.test/sym test-symbol]}}
                {:seon.agent/id aid
                 :seon.agent/plan
                 {:my.plan/objective "Verify the SCI answer"
                  :my.plan/steps [{:my.plan.item/id "settlement-step"
                                   :my.plan.item/title "Make answer one"
                                   :my.plan.item/position 0
                                   :my.plan.item/subject "issue"
                                   :my.plan.item/done-query plan/issue-done-query}]}}])))
         (let [red (settle)]
           (is (= 1 (:seon.test/fail-count red)) (pr-str red))
           (is (string? (get-in red [:seon.test/run :seon.test.run/id])))
           (is (string? (:seon.test/reach-digest red)))
           (is (nil? (:my.plan.item/completed-tx (step))))
           (admit! connection ctx namespace-name
                   "(defn answer {:malli/schema [:=> [:cat] :int]} [] (throw (ex-info \"red\" {})))")
           (let [errored (settle)]
             (is (pos? (:seon.test/error-count errored)) (pr-str errored))
             (is (nil? (:my.plan.item/completed-tx (step)))))
           (admit! connection ctx namespace-name
                   "(defn answer {:malli/schema [:=> [:cat] :int]} [] (loop [] (recur)))")
           (db/transact! connection
             [{:seon.agent/id aid :seon.agent/settings {:seon.config.eval/time-limit-ms 100}}])
           (let [expired (settle)]
             (is (pos? (:seon.test/error-count expired)) (pr-str expired))
             (is (seq (:seon.test/failure-message expired)))
             (is (nil? (:my.plan.item/completed-tx (step)))))
           (db/transact! connection
             [{:seon.agent/id aid :seon.agent/settings {:seon.config.eval/time-limit-ms 10000}}])
           (admit! connection ctx namespace-name
                   "(defn answer {:malli/schema [:=> [:cat] :int]} [] 1)")
           (let [green (settle)
                 completed (:my.plan.item/completed-tx (step))
                 resolved (:seon.issue/resolved-tx (db/pull (db/db connection)
                                                  [:seon.issue/resolved-tx] issue-ref))]
             (is (not= (get-in red [:seon.test/run :seon.test.run/id])
                       (get-in green [:seon.test/run :seon.test.run/id])))
             (is (= 1 (:seon.test/pass-count green)) (pr-str green))
             (is (true? (tests/verified? (db/db connection) test-symbol)))
             (is (some? completed))
             (is (= completed resolved))))
         (finally
           (doseq [k [:seon.cluster.wake/channel :seon.render/context-channel
                      :seon.turn.loop/completion]]
             (async/close! (get handle k)))))))))
