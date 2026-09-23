(ns seon.cluster.message-assignment-test
  "Subject tokens, sender classification and assignment correlation are independent."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.message :as message]
            [seon.problems :as problems]
            [seon.cluster.wake :as wake]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private now (Date. 1785283200000))

(defn- with-assignment-database
  {:malli/schema [:=> [:cat [:=> [:cat :seon.db/connection] :seon.schema/value]] :seon.schema/value]}
  [body]
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection
                             (into (test-support/agents-tx @connection ["alice" "bob"])
                               [{:seon.error/id "failure-17"}
                                {:seon.turn/id "red-run" :seon.turn/agent [:seon.agent/id "alice"] :seon.turn/opened-tx "datomic.tx"}
                                {:seon.cluster.eval/id "receipt-17"
                                 :seon.cluster.eval/run
                                 [:seon.turn/id "red-run"]
                                 :seon.cluster.eval/ordinal 0
                                 :seon.cluster.eval/at now

                                 :seon.cluster.eval/error
                                 "Unable to resolve symbol: missing-dependency"
                                 :seon.cluster.eval/source "(missing-dependency)"}]))
     (body connection))))

(defn- request
  [run-id content]
  {:my.message/value (seon.cluster.message/send "bob" content "failure-17") :seon.agent/id "alice" :seon.turn/id run-id :seon.cluster.eval/ordinal 0 :seon.config.message/max-chain 16})

(deftest delivery-preserves-the-supplied-subject-token
  (with-assignment-database
   (fn [connection]
     (test-support/transacted! connection
       (:seon.message/rows
        (message/delivery @connection
          (assoc (request "subject-row" "A different family shares this token")
                 :my.message/value (assoc (message/send "bob" "Subject") :seon.message/id "failure-17")))))
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
                     [?message :seon.message/about ?failure-id]]
                   @connection))
           "the driver stores the supplied token without resolving an entity")))))

(deftest an-unknown-subject-is-an-ordinary-observation
  (with-assignment-database
   (fn [connection]
     (let [delivery
           (message/delivery
            @connection
            (assoc (request "run-1" "repair this")
                   :my.message/value
                   (seon.cluster.message/send "bob" "repair this" "missing-fact")))]
       (is (= "missing-fact" (:seon.message/about (first (:seon.message/rows delivery)))))
       (is (empty? (:seon.error/values delivery)))))))

(deftest a-declination-settles-without-retiring-the-red-fact
  (with-assignment-database
   (fn [connection]
     (let [assignment
           (message/delivery
            @connection
            (assoc (request "assignment-run" "repair this")
                   :my.message/value
                   (assoc (message/send "bob" "repair this") :my.message/assignment "receipt-17")))
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

                       [?planner :seon.agent/id "alice"]
                       [?owner :seon.agent/id "bob"]
                       [?assignment :seon.message/assignment "receipt-17"]
                       [?assignment :seon.message/from ?planner]
                       [?assignment :seon.message/to ?owner]
                       [?declination :seon.message/assignment "receipt-17"]
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


(deftest sender-alone-determines-inside-after-subject-deletion
  (with-assignment-database
    (fn [connection]
      (test-support/transacted! connection
       [{:seon.message/id "subject" :seon.message/to [:seon.agent/id "bob"] :seon.message/content "Subject"}
        {:seon.message/id "inside-subject" :seon.message/from [:seon.agent/id "alice"] :seon.message/to [:seon.agent/id "bob"] :seon.message/about "subject" :seon.message/content "Inside"}
        {:seon.message/id "inside-only" :seon.message/from [:seon.agent/id "alice"] :seon.message/to [:seon.agent/id "bob"] :seon.message/content "Inside without subject"}
        {:seon.message/id "outside-subject" :seon.message/to [:seon.agent/id "bob"] :seon.message/about "subject" :seon.message/content "Outside"}])
      (doseq [delete? [false true]]
        (when delete? (test-support/transacted! connection [[:db/retractEntity [:seon.message/id "subject"]]]))
        (let [database @connection
              inside (wake/inside-attributes database)]
          (doseq [[id expected] [["inside-subject" true] ["inside-only" true] ["outside-subject" false]]]
            (is (= expected (wake/inside-wake? database (:db/id (db/pull database [:db/id] [:seon.message/id id])) inside))))
          (is (= "subject" (:seon.message/about (db/pull database [:seon.message/about] [:seon.message/id "inside-subject"])))))))))
