(ns seon.cluster.message-assignment-test
  "About-carrying sends resolve facts and upsert one assignment."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [my.message :as my.message]
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
     (d/transact connection
                 [{:seon.cluster.agent/id "alice"}
                  {:seon.cluster.agent/id "bob"}
                  {:seon.error/id "failure-17"}
                  {:seon.cluster.run/id "red-run"
                   :seon.cluster.run/agent
                   [:seon.cluster.agent/id "alice"]
                   :seon.cluster.run/opened-at now}
                  {:seon.cluster.run.form/id "red-run:0"
                   :seon.cluster.run.form/run
                   [:seon.cluster.run/id "red-run"]
                   :seon.cluster.run.form/ordinal 0
                   :seon.cluster.run.form/source "(missing-dependency)"}
                  {:seon.cluster.eval/id "receipt-17"
                   :seon.cluster.eval/run
                   [:seon.cluster.run/id "red-run"]
                   :seon.cluster.eval/ordinal 0
                   :seon.cluster.eval/at now
                   :seon.error/kind :seon.sci.eval/evaluation-failed
                   :seon.cluster.eval/error
                   "Unable to resolve symbol: missing-dependency"}])
     (body connection))))

(defn- request
  [run-id content]
  {:my.message/value
   (my.message/send "bob" content "failure-17")
   :seon.cluster.agent/id "alice"
   :seon.cluster.run/id run-id
   :seon.cluster.run.form/ordinal 0
   :seon.cluster.message/at now
   :seon.config.message/max-chain 16})

(deftest delivery-resolves-about-to-the-identified-fact
  (with-assignment-database
   (fn [connection]
     (let [delivery (message/delivery @connection
                                      (request "run-1" "repair this"))
           rows (:seon.cluster.message/rows delivery)]
       (is (empty? (:seon.error/values delivery)))
       (is (= 1 (count rows)))
       (d/transact connection rows)
       (is (= "failure-17"
              (d/q '[:find ?failure-id .
                     :where
                     [?message :seon.cluster.message/content "repair this"]
                     [?message :seon.cluster.message/about ?failure]
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
                   (my.message/send "bob" "repair this" "missing-fact")))]
       (is (empty? (:seon.cluster.message/rows delivery)))
       (is (= [:seon.cluster.message/unknown-about]
              (mapv :seon.error/kind (:seon.error/values delivery))))))))

(deftest a-declination-settles-without-retiring-the-red-fact
  (with-assignment-database
   (fn [connection]
     (let [assignment
           (message/delivery
            @connection
            (assoc (request "assignment-run" "repair this")
                   :my.message/value
                   (my.message/send "bob" "repair this" "receipt-17")))
           _ (d/transact connection
                         (:seon.cluster.message/rows assignment))
           red-before
           (:seon.problems/errored-receipts
            (problems/problems
             @connection {:seon.cluster.run/live-processes #{}}))
           reason "The dependency contract is missing."
           declination
           (message/delivery
            @connection
            {:my.message/value
             (my.message/decline "alice" "receipt-17" reason)
             :seon.cluster.agent/id "bob"
             :seon.cluster.run/id "declination-run"
             :seon.cluster.run.form/ordinal 0
             :seon.cluster.message/at now
             :seon.config.message/max-chain 16})
           rows (:seon.cluster.message/rows declination)]
       (is (empty? (:seon.error/values declination)))
       (is (= 1 (count rows)))
       (d/transact connection rows)
       (testing "the reply shape joins the assigned owner back to the problem"
         (is (= 1
                (d/q '[:find (count ?declination) .
                       :where
                       [?problem :seon.cluster.eval/id "receipt-17"]
                       [?planner :seon.cluster.agent/id "alice"]
                       [?owner :seon.cluster.agent/id "bob"]
                       [?assignment :seon.cluster.message/about ?problem]
                       [?assignment :seon.cluster.message/from ?planner]
                       [?assignment :seon.cluster.message/to ?owner]
                       [?declination :seon.cluster.message/about ?problem]
                       [?declination :seon.cluster.message/from ?owner]
                       [?declination :seon.cluster.message/to ?planner]
                       [?declination :my.message/reason _]]
                     @connection))
             "the owner's structured answer settles the routed form"))
       (testing "settlement does not erase the evidence that made the form red"
         (is (= red-before
                (:seon.problems/errored-receipts
                 (problems/problems
                  @connection {:seon.cluster.run/live-processes #{}})))
             "the form stays red because the errored receipt is untouched")
         (is (= ["receipt-17"]
                (mapv :seon.cluster.eval/id red-before)))
         (is (= reason
                (d/q '[:find ?reason .
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
                     (:seon.cluster.message/rows
                      (message/delivery @connection
                                        (request run-id content)))]
                 (.countDown ready)
                 (.await release)
                 (d/transact connection rows))))
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
                (d/q '[:find (count ?message) .
                       :where
                       [?failure :seon.error/id "failure-17"]
                       [?recipient :seon.cluster.agent/id "bob"]
                       [?message :seon.cluster.message/about ?failure]
                       [?message :seon.cluster.message/to ?recipient]]
                     @connection))
             "both stale derivations upsert the same assignment entity"))))))
