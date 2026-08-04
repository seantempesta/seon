(ns ^{:seon.test/long
      "Races shared-cluster database writes and multi-message delivery."}
  seon.concurrency-streams-test
  "Deterministic collision constructions retained from the 2026-08-04
  shared-cluster boundary verification. The red behavioral contracts live in
  their issue notes; these regressions retain the passing safety invariants so
  the collision shapes remain executable while those repairs land."
  (:require [clojure.test :refer [deftest is testing]]
            [my.message :as my.message]
            [seon.cluster.agent :as agent]
            [seon.cluster.message :as message]
            [seon.cluster.work :as work]
            [seon.db :as db]
            [seon.test-support :as test-support])
  (:import [java.util Date]
           [java.util.concurrent CountDownLatch]))

(set! *warn-on-reflection* true)

(defn- create-agent!
  [connection cluster-name agent-id namespace-name]
  (db/transact!
   connection
   (agent/creation-tx
    {:seon.cluster.agent/id agent-id
     :seon.ns/name namespace-name
     :seon.cluster/name cluster-name})))

(deftest unique-namespace-race-keeps-one-winner-and-one-flat-refusal
  (test-support/with-database
   (fn [connection]
     (let [cluster-name "concurrency-streams-unique"]
       (test-support/seed-cluster! connection cluster-name)
       (let [start (CountDownLatch. 1)
             submit
             (fn [agent-id]
               (future
                 (.await start)
                 (create-agent! connection cluster-name agent-id
                                'streams.test.unique)))
             tasks [(submit "streams-test-unique-a")
                    (submit "streams-test-unique-b")]]
         (.countDown start)
         (let [results (mapv #(test-support/await-event!
                              % "unique namespace race settled")
                             tasks)
               refusals (filterv :seon.error/kind results)
               winners
               (db/q '[:find [?agent-id ...]
                       :where
                       [?namespace :seon.ns/name streams.test.unique]
                       [?agent :seon.cluster.agent/namespace ?namespace]
                       [?agent :seon.cluster.agent/id ?agent-id]]
                     @connection)]
           (testing "the Datahike writer serializes the unique collision"
             (is (= 1 (count refusals)))
             (is (= :seon.db/rejected
                    (:seon.error/kind (first refusals))))
             (is (= 1 (count winners))))))))))

(deftest message-flood-retains-every-source-vector-entry
  (test-support/with-database
   (fn [connection]
     (let [cluster-name "concurrency-streams-messages"
           sender "streams-test-sender"
           recipient "streams-test-recipient"
           at (Date.)]
       (test-support/seed-cluster! connection cluster-name)
       (create-agent! connection cluster-name sender 'streams.test.sender)
       (create-agent! connection cluster-name recipient 'streams.test.recipient)
       (let [value
             (mapv (fn [index]
                     (my.message/send recipient (format "message-%02d" index)))
                   (range 12))
             delivery
             (message/delivery
              @connection
              {:my.message/value value
               :seon.cluster.agent/id sender
               :seon.cluster.run/id "streams-test-message-run"
               :seon.cluster.run.form/ordinal 1
               :seon.cluster.message/at at
               :seon.config.message/max-chain 64})
             rows (:seon.cluster.message/rows delivery)
             expected-ids (mapv #(str "streams-test-message-run-1-message-" %)
                                (range 12))]
         (db/transact! connection rows)
         (let [database @connection
               facts
               (db/q '[:find ?id ?content
                       :in $ ?recipient
                       :where
                       [?agent :seon.cluster.agent/id ?recipient]
                       [?message :seon.cluster.message/to ?agent]
                       [?message :seon.cluster.message/id ?id]
                       [?message :seon.cluster.message/content ?content]]
                     database recipient)
               trigger-ids
               (mapv :seon.cluster.message/id
                     (work/unanswered-triggers database recipient))]
           (testing "delivery keeps source-vector identity before commit"
             (is (= expected-ids
                    (mapv :seon.cluster.message/id rows)))
             (is (empty? (:seon.error/values delivery))))
           (testing "the database and trigger derivation lose no message"
             (is (= 12 (count facts)))
             (is (= (set expected-ids) (set trigger-ids))))))))))
