(ns seon.flow.harness.channel-test
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [seon.flow.harness.channel :as channel]
            [seon.flow.msg :as msg]))

(defn- with-timeout
  "Take from channel with timeout. Returns value or :timeout."
  ([ch] (with-timeout ch 5000))
  ([ch ms]
   (let [[v _] (a/alts!! [ch (a/timeout ms)])]
     (or v :timeout))))

(deftest roundtrip-test
  (testing "Server and client can exchange EDN maps bidirectionally"
    (let [srv    (channel/start-server! {::channel/port 0})
          client (channel/connect! {::channel/host "localhost"
                                    ::channel/port (::channel/port srv)})]
      (try
        ;; Client -> Server
        (a/>!! (::channel/out-ch client) {:hello "world" :n 42})
        (is (= {:hello "world" :n 42}
               (with-timeout (::channel/in-ch srv))))

        ;; Server -> Client
        (a/>!! (::channel/out-ch srv) {:reply "ok" :data [1 2 3]})
        (is (= {:reply "ok" :data [1 2 3]}
               (with-timeout (::channel/in-ch client))))
        (finally
          ((::channel/close! client))
          ((::channel/close! srv)))))))

(deftest msg-envelope-roundtrip-test
  (testing "Full seon.flow.msg/request envelope round-trips through TCP"
    (let [srv     (channel/start-server! {::channel/port 0})
          client  (channel/connect! {::channel/host "localhost"
                                     ::channel/port (::channel/port srv)})
          request {::msg/id (random-uuid)
                   ::msg/version 1
                   ::msg/type :request
                   ::msg/from-ns "seon.test.alpha"
                   ::msg/to-ns "seon.test.beta"
                   ::msg/fn "seon.test.beta/format-name"
                   ::msg/args [{:seon.test.beta/raw-name "sean"}]
                   ::msg/created-at (java.time.Instant/now)}]
      (try
        (a/>!! (::channel/out-ch client) request)
        (let [received (with-timeout (::channel/in-ch srv))]
          (is (map? received) "Should receive a map, not :timeout")
          (is (= (::msg/id request) (::msg/id received)))
          (is (= 1 (::msg/version received)))
          (is (= :request (::msg/type received)))
          (is (= "seon.test.beta/format-name" (::msg/fn received)))
          (is (= "seon.test.alpha" (::msg/from-ns received))))
        (finally
          ((::channel/close! client))
          ((::channel/close! srv)))))))

(deftest close-cleanup-test
  (testing "After close!, channels are closed"
    (let [srv    (channel/start-server! {::channel/port 0})
          client (channel/connect! {::channel/host "localhost"
                                    ::channel/port (::channel/port srv)})]
      ;; Send one message to confirm connection is live
      (a/>!! (::channel/out-ch client) {:ping true})
      (with-timeout (::channel/in-ch srv))
      ;; Close both sides
      ((::channel/close! client))
      ((::channel/close! srv))
      ;; Channels should be closed - reads return nil
      (Thread/sleep 200)
      (is (nil? (a/poll! (::channel/in-ch srv))))
      (is (nil? (a/poll! (::channel/in-ch client)))))))

(deftest ordering-test
  (testing "Multiple messages preserve ordering"
    (let [srv      (channel/start-server! {::channel/port 0})
          client   (channel/connect! {::channel/host "localhost"
                                      ::channel/port (::channel/port srv)})
          messages (mapv (fn [i] {:index i :data (str "msg-" i)}) (range 10))]
      (try
        ;; Send all messages
        (doseq [msg messages]
          (a/>!! (::channel/out-ch client) msg))
        ;; Receive all and verify order
        (doseq [expected messages]
          (is (= expected (with-timeout (::channel/in-ch srv)))))
        (finally
          ((::channel/close! client))
          ((::channel/close! srv)))))))
