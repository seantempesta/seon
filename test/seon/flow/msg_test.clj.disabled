(ns seon.flow.msg-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.flow.msg :as msg]
            [taoensso.nippy :as nippy]))

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

(deftest nippy-round-trip-test
  (testing "request survives Nippy round-trip"
    (let [request {::msg/id (random-uuid)
                   ::msg/version 1
                   ::msg/type :request
                   ::msg/from-ns "seon.test.alpha"
                   ::msg/to-ns "seon.test.beta"
                   ::msg/fn "seon.test.beta/format-name"
                   ::msg/args [{:name "sean"}]
                   ::msg/created-at (java.time.Instant/now)}
          deserialized (nippy/fast-thaw (nippy/fast-freeze request))]
      (is (= request deserialized))
      (is (m/validate ::msg/request deserialized))))

  (testing "reply survives Nippy round-trip"
    (let [reply {::msg/id (random-uuid)
                 ::msg/version 1
                 ::msg/type :reply
                 ::msg/status :ok
                 ::msg/from-ns "seon.test.beta"
                 ::msg/value {:result 42}
                 ::msg/duration-ms 5}
          deserialized (nippy/fast-thaw (nippy/fast-freeze reply))]
      (is (= reply deserialized))
      (is (m/validate ::msg/reply deserialized))))

  (testing "event survives Nippy round-trip"
    (let [event {::msg/id (random-uuid)
                 ::msg/version 1
                 ::msg/type :event
                 ::msg/event-kind :start
                 ::msg/from-ns "seon.test.alpha"
                 ::msg/created-at (java.time.Instant/now)}
          deserialized (nippy/fast-thaw (nippy/fast-freeze event))]
      (is (= event deserialized))
      (is (m/validate ::msg/event deserialized))))

  (testing "Nippy preserves types EDN could not"
    (let [data {::msg/id (random-uuid)
                ::msg/version 1
                ::msg/type :reply
                ::msg/status :ok
                ::msg/from-ns "seon.test"
                ::msg/value {:bytes (byte-array [1 2 3])
                             :float-val (float 3.14)
                             :instant (java.time.Instant/now)}
                ::msg/duration-ms 0}
          deserialized (nippy/fast-thaw (nippy/fast-freeze data))
          val (::msg/value deserialized)]
      (is (instance? Float (:float-val val))
          "Float type preserved (EDN coerced to Double)")
      (is (java.util.Arrays/equals ^bytes (get-in data [::msg/value :bytes])
                                    ^bytes (:bytes val))
          "byte[] preserved (EDN could not serialize)")
      (is (instance? java.time.Instant (:instant val))
          "Instant type preserved natively"))))

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
