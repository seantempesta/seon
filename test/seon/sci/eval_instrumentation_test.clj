(ns ^{:seon.test/long
      "Published-root start, whole-image instrumentation, and one attempt-ready prompt."}
  seon.sci.eval-instrumentation-test
  "Armed-instrumentation regression for database program acquisition."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.instrument :as instrument]
            [seon.schema.internal :as schema.internal]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(set! *warn-on-reflection* true)

(deftest ^{:seon.test/fixture-observation "The test observes prompt acquisition under instrumentation installed by a real development-cluster boot."} an-instrumented-dev-cluster-builds-an-attempt-ready-prompt
  (let [cluster-name (str "instrumented-acquire-" (random-uuid))
        root (str "tmp/instrumented-acquire-test/" cluster-name)
        message-id "instrumented-acquire-turn"
        attempt-requests (async/promise-chan)]
    (test-support/delete-recursively! root)
    (try
      (test-support/populate-published-root! root)
      (let [instance
            (cluster/start! {:seon.boot/cluster-name cluster-name
                             :seon.boot/root root})]
        (try
          (let [connection (:seon.boot/cluster-connection instance)
                handle (:seon.turn.loop/cluster instance)]
            (with-redefs
              [ai/complete
               (fn [request]
                 (async/offer! attempt-requests request)
                 {:seon.ai/text
                  "(seon.run/complete \"instrumented acquisition ran\")"})]
              (try
                (let [applied
                      (instrument/apply!
                       {:seon.config/on-core-error :panic
                        :seon.sci.admit/caps
                        (:seon.sci.admit/caps handle)})]
                  (is (pos? (:seon.instrument/instrumented applied)))
                  (is (contains?
                       (instrument/instrumented)
                       #'schema.internal/assert-compilable-schema!)
                      "the regression keeps the formerly failing boundary armed")
                  (test-support/transacted!
                               connection
                               [{:seon.message/id message-id :seon.message/to [:seon.agent/id "root"] :seon.message/content "Complete one instrumented turn." :seon.message/inbox [:seon.agent/id "root"]}])
                  (let [attempt-request
                        (test-support/await-event!
                         attempt-requests "instrumented attempt request")]
                    (testing "acquisition produced an attempt-ready prompt"
                      (is (not-empty (:seon.ai/prompt attempt-request))))
                    (testing "the armed path emitted no contract fault"
                      (is (empty?
                           (db/q '[:find ?error
                                  :where
                                  [?error :seon.error/kind
                                   :seon.instrument/contract-violated]]
                                @connection))))))
                (finally
                  (instrument/remove!)))))
          (finally
            (cluster/stop! instance))))
      (finally
        (test-support/delete-recursively! root)))))
