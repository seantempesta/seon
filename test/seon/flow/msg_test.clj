(ns seon.flow.msg-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.flow.msg :as msg]))

(deftest schema-generation-test
  (testing "request schema generates valid samples"
    (doseq [sample (mg/sample ::msg/request {:size 10})]
      (is (m/validate ::msg/request sample))))

  (testing "reply schema generates valid samples"
    (doseq [sample (mg/sample ::msg/reply {:size 10})]
      (is (m/validate ::msg/reply sample))))

  (testing "event schema generates valid samples"
    (doseq [sample (mg/sample ::msg/event {:size 10})]
      (is (m/validate ::msg/event sample)))))

(deftest edn-round-trip-test
  (testing "request survives EDN round-trip"
    (let [request {::msg/id (random-uuid)
                   ::msg/version 1
                   ::msg/type :request
                   ::msg/from-ns "seon.test.alpha"
                   ::msg/to-ns "seon.test.beta"
                   ::msg/fn "seon.test.beta/format-name"
                   ::msg/args [{:name "sean"}]
                   ::msg/created-at (java.time.Instant/now)}
          serialized (pr-str request)
          deserialized (edn/read-string {:readers {'time/instant #(java.time.Instant/parse %)}}
                                        serialized)]
      (is (= request deserialized))
      (is (m/validate ::msg/request deserialized))))

  (testing "reply survives EDN round-trip"
    (let [reply {::msg/id (random-uuid)
                 ::msg/version 1
                 ::msg/type :reply
                 ::msg/status :ok
                 ::msg/from-ns "seon.test.beta"
                 ::msg/value {:result 42}
                 ::msg/duration-ms 5}
          serialized (pr-str reply)
          deserialized (edn/read-string serialized)]
      (is (= reply deserialized))
      (is (m/validate ::msg/reply deserialized))))

  (testing "event survives EDN round-trip"
    (let [event {::msg/id (random-uuid)
                 ::msg/version 1
                 ::msg/type :event
                 ::msg/event-kind :start
                 ::msg/from-ns "seon.test.alpha"
                 ::msg/created-at (java.time.Instant/now)}
          serialized (pr-str event)
          deserialized (edn/read-string {:readers {'time/instant #(java.time.Instant/parse %)}}
                                        serialized)]
      (is (= event deserialized))
      (is (m/validate ::msg/event deserialized)))))

(deftest negative-validation-test
  (testing "request missing required keys fails"
    (is (not (m/validate ::msg/request {}))))

  (testing "request with wrong version fails"
    (is (not (m/validate ::msg/request
                         {::msg/id (random-uuid)
                          ::msg/version 2
                          ::msg/type :request
                          ::msg/from-ns "a"
                          ::msg/to-ns "b"
                          ::msg/fn "c/d"
                          ::msg/args []
                          ::msg/created-at (java.time.Instant/now)}))))

  (testing "reply with invalid status fails"
    (is (not (m/validate ::msg/reply
                         {::msg/id (random-uuid)
                          ::msg/version 1
                          ::msg/type :reply
                          ::msg/status :bogus
                          ::msg/from-ns "a"
                          ::msg/duration-ms 0}))))

  (testing "event with invalid event-kind fails"
    (is (not (m/validate ::msg/event
                         {::msg/id (random-uuid)
                          ::msg/version 1
                          ::msg/type :event
                          ::msg/event-kind :bogus
                          ::msg/from-ns "a"
                          ::msg/created-at (java.time.Instant/now)})))))

(deftest version-always-one-test
  (testing "all generated messages have version 1"
    (doseq [sample (concat (mg/sample ::msg/request {:size 5})
                           (mg/sample ::msg/reply {:size 5})
                           (mg/sample ::msg/event {:size 5}))]
      (is (= 1 (::msg/version sample))))))
