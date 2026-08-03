(ns seon.test-runner-test
  "The opt-in JVM test-result fact sink."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.test-runner-failure-fixture]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support]))

(def ^:private at (java.util.Date. 1785283200000))
(def ^:private git-sha (apply str (repeat 40 "a")))
(def ^:private run-id "test-run-1")

(defn- captured-run []
  (binding [clojure.test/*test-out* (java.io.StringWriter.)]
    (runner/run!
     {:seon.test.runner/namespaces
      ['seon.test-runner-failure-fixture]
      :seon.test.run/id run-id
      :seon.test.run/at at
      :seon.test.run/git-sha git-sha})))

(deftest captures-one-pass-or-fail-value-per-test
  (let [result (captured-run)
        by-symbol (into {}
                        (map (juxt :seon.test/sym identity))
                        (:seon.test.runner/results result))]
    (is (= #:seon.test.runner{:test-count 2
                              :pass-count 1
                              :fail-count 1
                              :error-count 0}
           (:seon.test.runner/summary result)))
    (is (= :pass
           (:seon.test.result/outcome
            (by-symbol
             "seon.test-runner-failure-fixture/passing-example"))))
    (is (= :fail
           (:seon.test.result/outcome
            (by-symbol
             "seon.test-runner-failure-fixture/failing-example"))))
    (is (str/includes?
         (:seon.test.failure/message
          (by-symbol
           "seon.test-runner-failure-fixture/failing-example"))
         "deliberate broken-test evidence"))))

(deftest result-facts-join-through-test-namespace-to-its-owner
  (test-support/with-database
    (fn [connection]
      (let [run-result (captured-run)]
        (test-support/seed-cluster! connection "test")
        (db/transact! connection (runner/record-tx run-result))
        (db/transact!
         connection
         (agent/creation-tx
          {:seon.cluster.agent/id "fixture-owner"
           :seon.cluster/name "test"
           :seon.ns/name 'seon.test-runner-failure-fixture}))
        (is
         (= #{["seon.test-runner-failure-fixture/failing-example"
                "fixture-owner"
                "the deliberate broken-test evidence\nexpected: (= 5 (+ 2 2))\nactual: (not (= 5 4))"
                at
                git-sha]}
            (db/q
             '[:find ?test-symbol ?agent-id ?message ?at ?git-sha
               :where
               [?result :seon.test.result/outcome :fail]
               [?result :seon.test.result/test ?test]
               [?test :seon.test/sym ?test-symbol]
               [?test :seon.test/ns ?namespace]
               [?agent :seon.cluster.agent/namespace ?namespace]
               [?agent :seon.cluster.agent/id ?agent-id]
               [?result :seon.test.result/failure ?failure]
               [?failure :seon.test.failure/message ?message]
               [?result :seon.test.result/run ?run]
               [?run :seon.test.run/at ?at]
               [?run :seon.test.run/git-sha ?git-sha]]
             @connection)))))))

(deftest the-effectful-sink-refuses-the-default-cluster
  (let [refusal
        (test-support/refusal-data
         #(runner/record!
           {:seon.test.runner/run-result (captured-run)
            :seon.boot/cluster-name "default"
            :seon.boot/root "tmp/test-result-default-refusal"}))]
    (is (= :seon.test.runner/default-cluster-refused
           (:seon.error/kind refusal)))))
