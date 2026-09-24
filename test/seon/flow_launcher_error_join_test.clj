(ns seon.flow-launcher-error-join-test
  "The work launcher's failed submissions reach the one core-fault route."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datahike.core :as datahike]
            [seon.db :as db]
            [seon.flow :as sut]
            [seon.test-support :as test-support]))

(deftest a-throwing-background-submission-is-one-stored-fault
  ;; Behavior class (flow usage audit D1): the launcher's error output waited
  ;; in Flow's sliding error channel for a join, so a throwing submission
  ;; could be evicted before it reached the fault route. It is now recorded
  ;; through `seon.fault/fault!` on the launcher's own thread.
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "seon.flow-launcher-error-join-test"
            _ (test-support/apply-config! connection cluster-name {})
            environment (test-support/environment cluster-name connection)
            configuration
            (assoc (select-keys (test-support/effective-config)
                                sut/flow-workload-attributes)
                   :seon.config.agent/turn-completion-backstop-ms 60000)
            launcher (sut/start-work-launcher!
                      {:seon.env/environment environment
                       ::sut/configuration configuration})
            terminal (promise)
            transactions (async/chan 16)
            stored-messages #(filter (fn [message] (str/includes? message "background work failed"))
                                     (db/q '[:find [?message ...]
                                             :where [_ :seon.error.occurrence/message ?message]]
                                           %))]
        (datahike/listen! connection ::fault-write #(async/put! transactions %))
        (try
          (is (true? (sut/submit! launcher
                                  {:seon.env/environment environment
                                   ::sut/submission-id ::throwing
                                   ::sut/workload :io
                                   ::sut/work-fn (fn [_] (throw (ex-info "background work failed"
                                                                         {::cause ::probe})))
                                   ::sut/complete! #(deliver terminal %)})))
          (is (some? (test-support/await-event! (future @terminal) ::terminal))
              "the submitter's own terminal still settles")
          (test-support/await-event! transactions ::fault-committed
                                     #(seq (stored-messages (:db-after %))))
          (is (= 1 (count (stored-messages @connection)))
              "one stored occurrence names the failure")
          (is (nil? (sut/stop-work-launcher! launcher)))
          (finally
            (datahike/unlisten! connection ::fault-write)
            (async/close! transactions)
            (sut/stop-work-launcher! launcher)))))))
