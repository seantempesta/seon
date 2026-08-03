(ns seon.test-runner-test
  "The opt-in JVM test-result fact sink."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.test-runner-failure-fixture]
            [seon.test.runner :as runner]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch]))

(def ^:private at (java.util.Date. 1785283200000))
(def ^:private git-sha (apply str (repeat 40 "a")))
(def ^:private run-id "test-run-1")

(defn- captured-run-with-output []
  (let [writer (java.io.StringWriter.)
        result
        (binding [clojure.test/*test-out* writer]
          (runner/run!
           {:seon.test.runner/namespaces
            ['seon.test-runner-failure-fixture]
            :seon.test.run/id run-id
            :seon.test.run/at at
            :seon.test.run/git-sha git-sha}))]
    {::result result
     ::output (str writer)}))

(defn- captured-run []
  (::result (captured-run-with-output)))

(defn- occurrences
  [text fragment]
  (loop [from 0
         found 0]
    (if-let [match-at (str/index-of text fragment from)]
      (recur (+ match-at (count fragment)) (inc found))
      found)))

(deftest captures-one-pass-or-fail-value-per-test
  (let [result (captured-run)
        by-symbol (into {}
                        (map (juxt :seon.test/sym identity))
                        (:seon.test.runner/results result))]
    (is (= #:seon.test.runner{:test-count 4
                              :pass-count 1
                              :fail-count 1
                              :error-count 8}
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

(deftest repeated-identical-errors-have-one-bounded-face
  (let [{::keys [result output]} (captured-run-with-output)
        by-symbol (into {}
                        (map (juxt :seon.test/sym identity))
                        (:seon.test.runner/results result))
        repeated-message
        (:seon.test.failure/message
         (by-symbol
          "seon.test-runner-failure-fixture/repeated-identical-error"))
        repeated-signature (apply str (repeat 64 "a"))
        distinct-signature (apply str (repeat 64 "b"))
        signature-at (str/index-of output repeated-signature)
        repeated-start (inc (str/last-index-of output "\nERROR in"
                                                signature-at))
        summary-start (str/index-of output "\nRan" repeated-start)
        repeated-face (subs output repeated-start summary-start)]
    (testing "all events remain counted while only distinct causes render"
      (is (= 8
             (get-in result [:seon.test.runner/summary
                             :seon.test.runner/error-count])))
      (is (= 2 (occurrences output "ERROR in")))
      (is (= 2 (occurrences output "  signature:")))
      (is (str/includes? output "one repeated refusal"))
      (is (str/includes? output distinct-signature)))
    (testing "one face and the captured fact are bounded and deduplicated"
      (is (<= (count repeated-face)
              (:seon.config.eval.result/blob-threshold
               (config/defaults))))
      (is (= 1 (occurrences repeated-message
                            "the same refusal reached the reporter again"))))))

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
               :in $ ?selected-test
               :where
               [?result :seon.test.result/outcome :fail]
               [?result :seon.test.result/test ?test]
               [?test :seon.test/sym ?test-symbol]
               [(= ?test-symbol ?selected-test)]
               [?test :seon.test/ns ?namespace]
               [?agent :seon.cluster.agent/namespace ?namespace]
               [?agent :seon.cluster.agent/id ?agent-id]
               [?result :seon.test.result/failure ?failure]
               [?failure :seon.test.failure/message ?message]
               [?result :seon.test.result/run ?run]
               [?run :seon.test.run/at ?at]
               [?run :seon.test.run/git-sha ?git-sha]]
             @connection
             "seon.test-runner-failure-fixture/failing-example")))))))

(deftest the-effectful-sink-refuses-the-default-cluster
  (let [refusal
        (test-support/refusal-data
         #(runner/record!
           {:seon.test.runner/run-result (captured-run)
            :seon.boot/cluster-name "default"
            :seon.boot/root "tmp/test-result-default-refusal"}))]
    (is (= :seon.test.runner/default-cluster-refused
           (:seon.error/kind refusal)))))

(deftest liveness-dump-includes-virtual-threads
  (let [release (CountDownLatch. 1)
        path (volatile! nil)
        thread
        (-> (Thread/ofVirtual)
            (.name "seon-test-runner-virtual-thread-proof")
            (.start
             (reify Runnable
               (run [_]
                 (.await release)))))]
    (try
      (let [dump-path (#'runner/persist-virtual-thread-dump!)
            _ (vreset! path dump-path)
            dump (slurp dump-path)]
        (is (str/includes? dump
                           "seon-test-runner-virtual-thread-proof"))
        (is (str/includes? dump "\"virtual\": true")
            "the retained diagnostic is not the platform-only MXBean view"))
      (finally
        (.countDown release)
        (.join thread)
        (when-let [dump-path @path]
          (io/delete-file dump-path true))))))
