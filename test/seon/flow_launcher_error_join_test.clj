(ns seon.flow-launcher-error-join-test
  "The work launcher graph's errors reach the cluster fault channel."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.test :refer [deftest is]]
            [seon.cluster.boot :as boot]
            [seon.flow :as sut]
            [seon.test-support :as test-support]))

(deftest a-throwing-background-submission-reaches-the-fault-channel
  ;; Behavior class (flow usage audit D1): before this join, nothing read the
  ;; launcher graph's error channel, so a throwing submission never reached
  ;; the fault route and Flow's sliding buffer evicted it.
  (let [environment (test-support/environment "seon.flow-launcher-error-join-test")
        configuration
        (assoc (select-keys (test-support/effective-config)
                            sut/flow-workload-attributes)
               :seon.config.agent/turn-completion-backstop-ms 60000)
        launcher (sut/start-work-launcher!
                  {:seon.env/environment environment
                   ::sut/configuration configuration})
        fault-channel (async/chan 8)
        failure (ex-info "background work failed" {::cause ::probe})
        terminal (promise)]
    (try
      (let [join (boot/join-launcher-errors!
                  {::sut/work-launcher launcher
                   ::sut/error-fanout {::sut/fault-channel fault-channel}})]
        (is (true? (sut/submit! launcher
                                {:seon.env/environment environment
                                 ::sut/submission-id ::throwing
                                 ::sut/workload :io
                                 ::sut/work-fn (fn [_] (throw failure))
                                 ::sut/complete! #(deliver terminal %)})))
        (let [fault (test-support/await-event! fault-channel ::launcher-fault)]
          (is (identical? failure (::flow/ex fault)))
          (is (= ::sut/work-launcher (::flow/pid fault)))
          (is (= ::sut/io-submission (::flow/cid fault))))
        (is (some? (test-support/await-event! (future @terminal) ::terminal))
            "the submitter's own terminal still settles")
        (is (nil? (sut/stop-work-launcher! launcher)))
        (is (= ::sut/error-fanout-stopped
               (test-support/await-event! join ::launcher-join-stopped))
            "the join ends when the stopped graph closes its error channel"))
      (finally
        (sut/stop-work-launcher! launcher)))))
