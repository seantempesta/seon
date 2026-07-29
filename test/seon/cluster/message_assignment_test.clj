(ns seon.cluster.message-assignment-test
  "About-carrying sends resolve facts and upsert one assignment."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [my.message :as my.message]
            [seon.cluster.message :as message]
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
                  {:seon.error/id "failure-17"}])
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
