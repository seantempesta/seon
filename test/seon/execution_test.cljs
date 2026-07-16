(ns seon.execution-test
  (:require
   [cljs.test :refer [deftest is testing]]
   [seon.db :as db]
   [seon.execution :as execution]))

(def digest (apply str (repeat 64 "a")))
(def coordinate
  {:seon.db.coordinate/database-id #uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
   :seon.db.coordinate/branch :db
   :seon.db.coordinate/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
   :seon.db.coordinate/t 42})

(def invocation
  {::execution/message execution/invoke-message
   ::execution/protocol-version execution/protocol-version
   ::execution/agent-id "agent-1"
   ::execution/invocation-id "invoke-1"
   ::execution/coordinate coordinate
   ::execution/function-source-identity
   {::execution/function-symbol 'my.render/view
    ::execution/source-digest digest}
   ::execution/capabilities #{'my.render/view}
   ::execution/input {:my.render/value 1}
   ::execution/deadline-ms 9999999999999
   ::execution/result-limit-bytes 4096})

(def startup
  {::execution/protocol-version execution/protocol-version
   ::execution/agent-id "agent-1"
   ::execution/artifact-digest digest
   ::execution/shadow-build-id "execution"
   ::execution/database-selection
   {::db/socket-path "tmp/test.sock"
    ::db/database-name "test"
    ::db/backend :memory}})

(defn decoded-sender [messages]
  (fn [encoded]
    (swap! messages conj (execution/decode-message encoded))))

(deftest transit-round-trip-preserves-the-ordinary-contract
  (let [decoded (execution/decode-message
                 (execution/encode-message invocation))]
    (is (= invocation decoded))
    (is (execution/valid-parent-message? decoded)))
  (is (false? (execution/valid-parent-message?
               (assoc invocation ::execution/input
                      {:my.render/value (js/Promise.resolve 1)}))))
  (is (execution/valid-parent-message?
       (assoc invocation ::execution/capabilities #{}))))

(deftest bounded-results-are-settled-ordinary-data
  (let [result (execution/bounded-result {:my.render/value 1} 4096)]
    (is (true? (::execution/ok? result)))
    (is (= {:my.render/value 1} (::execution/value result)))
    (is (pos? (::execution/result-bytes result))))
  (testing "host values cannot cross parent IPC"
    (let [result (execution/bounded-result (js/Promise.resolve 1) 4096)]
      (is (false? (::execution/ok? result)))
      (is (= :agent (get-in result [::execution/error
                                    :seon.error/kind])))))
  (testing "the caller's smaller byte limit is enforced"
    (let [result (execution/bounded-result {:my.render/value (apply str
                                                                         (repeat 100 "x"))}
                                           16)]
      (is (false? (::execution/ok? result)))
      (is (< 16 (get-in result [::execution/error :seon.error/data
                                ::execution/result-bytes]))))))

(deftest every-control-message-is-versioned-and-closed
  (is (execution/valid-parent-message?
       {::execution/message execution/cancel-message
        ::execution/protocol-version execution/protocol-version
        ::execution/invocation-id "invoke-1"}))
  (is (execution/valid-parent-message?
       {::execution/message execution/shutdown-message
        ::execution/protocol-version execution/protocol-version}))
  (is (false? (execution/valid-parent-message?
               (assoc invocation ::execution/extra true))))
  (is (execution/valid-child-message?
       {::execution/message execution/result-message
        ::execution/protocol-version execution/protocol-version
        ::execution/invocation-id "invoke-1"
        ::execution/coordinate coordinate
        ::execution/result {:my.render/value 1}
        ::execution/result-bytes 32})))

(deftest active-invocation-refuses-overlap-and-cancel-settles-once
  (let [token (js-obj)
        messages (atom [])
        closes (atom 0)
        state (atom {::execution/startup startup
                     ::execution/active
                     {::execution/token token
                      ::execution/invocation invocation}})]
    (with-redefs [db/close-session! (fn [] (swap! closes inc) true)]
      (@#'execution/receive!
       state (execution/encode-message invocation)
       (decoded-sender messages) (fn [_]) 0)
      (is (= execution/error-message
             (::execution/message (first @messages))))
      (is (identical? token (get-in @state [::execution/active
                                             ::execution/token])))

      (@#'execution/receive!
       state
       (execution/encode-message
        {::execution/message execution/cancel-message
         ::execution/protocol-version execution/protocol-version
         ::execution/invocation-id "invoke-1"})
       (decoded-sender messages) (fn [_]) 0)
      (is (= 1 @closes))
      (is (nil? (::execution/active @state)))
      (is (= execution/error-message
             (::execution/message (second @messages))))
      (is (= "invoke-1" (::execution/invocation-id (second @messages))))

      ;; A late duplicate cancel has no work and emits no second terminal value.
      (@#'execution/receive!
       state
       (execution/encode-message
        {::execution/message execution/cancel-message
         ::execution/protocol-version execution/protocol-version
         ::execution/invocation-id "invoke-1"})
       (decoded-sender messages) (fn [_]) 0)
      (is (= 2 (count @messages)))
      (is (= 1 @closes)))))

(deftest shutdown-closes-the-session-before-exit
  (let [messages (atom [])
        events (atom [])
        state (atom {::execution/startup startup})]
    (with-redefs [db/close-session! (fn [] (swap! events conj :close) true)]
      (@#'execution/receive!
       state
       (execution/encode-message
        {::execution/message execution/shutdown-message
         ::execution/protocol-version execution/protocol-version})
       (decoded-sender messages)
       (fn [status] (swap! events conj [:exit status]))
       0))
    (is (true? (::execution/shutting-down? @state)))
    (is (= [:close [:exit 0]] @events))
    (is (= execution/stopped-message
           (::execution/message (first @messages))))))
