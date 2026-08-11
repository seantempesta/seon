(ns ^{:seon.test/long "The regression completes a turn in a real instrumented cluster."}
  seon.sci.eval-instrumentation-test
  "Armed-instrumentation regression for database program acquisition."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.instrument :as instrument]
            [seon.schema.internal :as schema.internal]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(set! *warn-on-reflection* true)

(defn- await-fact!
  [connection probe publish!]
  (let [events (async/promise-chan)
        listener-key
        (keyword (str (ns-name *ns*)) (str (gensym "fact-")))]
    (d/listen
     connection
     listener-key
     (fn [report]
       (when-let [value (probe (:db-after report))]
         (async/offer! events value))))
    (try
      (when-let [value (probe @connection)]
        (async/offer! events value))
      (publish!)
      (test-support/await-event! events "instrumented agent receipt")
      (finally
        (d/unlisten connection listener-key)))))

(defn- completed-turn
  [db message-id]
  (when-let [run-id
             (db/q '[:find ?run-id .
                    :in $ ?message-id
                    :where
                    [?message :seon.cluster.message/id ?message-id]
                    [?run :seon.cluster.run/trigger ?message]
                    [?run :seon.cluster.run/id ?run-id]
                    [?run :seon.cluster.run/closed-at _]]
                  db
                  message-id)]
    (let [run (db/pull db '[*] [:seon.cluster.run/id run-id])
          receipts
          (db/q '[:find [(pull ?receipt [*]) ...]
                 :in $ ?run-id
                 :where
                 [?run :seon.cluster.run/id ?run-id]
                 [?receipt :seon.cluster.eval/run ?run]]
               db
               run-id)]
      (when (seq receipts)
        {:seon.sci.eval-instrumentation/run run
         :seon.sci.eval-instrumentation/receipts receipts}))))

(deftest an-instrumented-dev-cluster-completes-one-agent-turn
  (let [cluster-name (str "instrumented-acquire-" (random-uuid))
        root (str "tmp/instrumented-acquire-test/" cluster-name)
        message-id "instrumented-acquire-turn"]
    (test-support/delete-recursively! root)
    (try
      (test-support/populate-published-root! root)
      (let [instance
            (cluster/start! {:seon.boot/cluster-name cluster-name
                             :seon.boot/root root})]
        (try
          (let [connection (:seon.boot/cluster-connection instance)
                handle (:seon.cluster.loop/cluster instance)]
            (with-redefs
              [ai/complete
               (fn [_request]
                 {:seon.ai/text
                  "(my.run/complete \"instrumented acquisition ran\")"})]
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
                  (let [{run :seon.sci.eval-instrumentation/run
                         receipts
                         :seon.sci.eval-instrumentation/receipts}
                        (await-fact!
                         connection
                         #(completed-turn % message-id)
                         #(db/transact!
                           connection
                           [{:seon.cluster.message/id message-id
                             :seon.cluster.message/to
                             [:seon.cluster.agent/id "root"]
                             :seon.cluster.message/content
                             "Complete one instrumented turn."
                             :seon.cluster.message/at (Date.)}]))]
                    (testing "acquisition reached evaluation and settlement"
                      (is (= 1 (count receipts)))
                      (is (some? (:seon.cluster.eval/result-edn
                                  (first receipts))))
                      (is (some? (:seon.cluster.run/closed-at run)))
                      (is (nil? (:seon.cluster.run/process run))
                          "the terminal transaction released custody"))
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
