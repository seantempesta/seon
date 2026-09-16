(ns seon.cluster.message-assignment-test
  "About-carrying sends resolve facts and upsert one assignment."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.message :as my.message]
            [seon.cluster.message :as message]
            [seon.problems :as problems]
            [seon.test-support :as test-support])
  (:import [java.util Date]
           [java.util.concurrent CountDownLatch]))

(def ^:private now (Date. 1785283200000))

(defn- with-assignment-database
  [body]
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection
                             [{:seon.agent/id "alice"}
                              {:seon.agent/id "bob"}
                              {:seon.error/id "failure-17"}
                              {:seon.turn/id "red-run" :seon.turn/agent [:seon.agent/id "alice"] :seon.turn/opened-tx "datomic.tx"}
                              {:seon.cluster.eval/id "receipt-17"
                               :seon.cluster.eval/run
                               [:seon.turn/id "red-run"]
                               :seon.cluster.eval/ordinal 0
                               :seon.cluster.eval/at now
                               :seon.error/kind :seon.sci.eval/evaluation-failed
                               :seon.cluster.eval/error
                               "Unable to resolve symbol: missing-dependency"
                               :seon.cluster.eval/source "(missing-dependency)"}])
     (body connection))))

(defn- request
  [run-id content]
  {:my.message/value (seon.cluster.message/send "bob" content "failure-17") :seon.agent/id "alice" :seon.turn/id run-id :seon.cluster.eval/ordinal 0 :seon.config.message/max-chain 16})

(deftest delivery-resolves-about-to-the-identified-fact
  (with-assignment-database
   (fn [connection]
     (let [delivery (message/delivery @connection
                                      (request "run-1" "repair this"))
           rows (:seon.message/rows delivery)]
       (is (empty? (:seon.error/values delivery)))
       (is (= 1 (count rows)))
       (test-support/transacted! connection rows)
       (is (= "failure-17"
              (db/q '[:find ?failure-id .
                     :where
                     [?message :seon.message/content "repair this"]
                     [?message :seon.message/about ?failure]
                     [?failure :seon.error/id ?failure-id]]
                   @connection))
           "the driver resolves the string identity and commits the ref")))))

(deftest an-unknown-about-identity-is-a-refusal-value
  (with-assignment-database
   (fn [connection]
     (let [delivery
           (message/delivery
            @connection
            (assoc (request "run-1" "repair this")
                   :my.message/value
                   (seon.cluster.message/send "bob" "repair this" "missing-fact")))]
       (is (empty? (:seon.message/rows delivery)))
       (is (= [:seon.message/unknown-about]
              (mapv :seon.error/kind (:seon.error/values delivery))))))))

(deftest a-declination-settles-without-retiring-the-red-fact
  (with-assignment-database
   (fn [connection]
     (let [assignment
           (message/delivery
            @connection
            (assoc (request "assignment-run" "repair this")
                   :my.message/value
                   (seon.cluster.message/send "bob" "repair this" "receipt-17")))
           _ (test-support/transacted! connection
                                     (:seon.message/rows assignment))
           red-before
           (:seon.problems/errored-receipts
            (problems/problems
             @connection {}))
           reason "The dependency contract is missing."
           declination
           (message/delivery
            @connection
            {:my.message/value (seon.cluster.message/decline "alice" "receipt-17" reason) :seon.agent/id "bob" :seon.turn/id "declination-run" :seon.cluster.eval/ordinal 0 :seon.config.message/max-chain 16})
           rows (:seon.message/rows declination)]
       (is (empty? (:seon.error/values declination)))
       (is (= 1 (count rows)))
       (test-support/transacted! connection rows)
       (testing "the reply shape joins the assigned owner back to the problem"
         (is (= 1
                (db/q '[:find (count ?declination) .
                       :where
                       [?problem :seon.cluster.eval/id "receipt-17"]
                       [?planner :seon.agent/id "alice"]
                       [?owner :seon.agent/id "bob"]
                       [?assignment :seon.message/about ?problem]
                       [?assignment :seon.message/from ?planner]
                       [?assignment :seon.message/to ?owner]
                       [?declination :seon.message/about ?problem]
                       [?declination :seon.message/from ?owner]
                       [?declination :seon.message/to ?planner]
                       [?declination :my.message/reason _]]
                     @connection))
             "the owner's structured answer settles the routed form"))
       (testing "settlement does not erase the evidence that made the form red"
         (is (= red-before
                (:seon.problems/errored-receipts
                 (problems/problems
                  @connection {})))
             "the form stays red because the errored receipt is untouched")
         (is (= ["receipt-17"]
                (mapv :seon.cluster.eval/id red-before)))
         (is (= reason
                (db/q '[:find ?reason .
                       :where
                       [?declination :my.message/reason ?reason]]
                     @connection))
             "the reason is a reader-facing fact, not parsed prose"))))))

(deftest concurrent-terminal-deliveries-upsert-one-assignment
  (with-assignment-database
   (fn [connection]
     (let [ready (CountDownLatch. 2)
           release (CountDownLatch. 1)
           transact
           (fn [run-id content]
             (future
               (let [rows
                     (:seon.message/rows
                      (message/delivery @connection
                                        (request run-id content)))]
                 (.countDown ready)
                 (.await release)
                 (db/transact! connection rows))))
           transactions [(transact "run-1" "first assignment")
                         (transact "run-2" "second assignment")]]
       (try
         (test-support/await-event! ready
                                    ::both-deliveries-derived-before-commit)
         (finally
           (.countDown release)))
       (doseq [transaction transactions]
         (test-support/await-event! transaction
                                    ::terminal-delivery-committed))
       (testing "the schema identity fences the race at commit"
         (is (= 1
                (db/q '[:find (count ?message) .
                       :where
                       [?failure :seon.error/id "failure-17"]
                       [?recipient :seon.agent/id "bob"]
                       [?message :seon.message/about ?failure]
                       [?message :seon.message/to ?recipient]]
                     @connection))
             "both stale derivations upsert the same assignment entity"))))))
