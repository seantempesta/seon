(ns seon.execution-test
  (:require
   [cljs.js :as cljs]
   [cljs.test :refer [async deftest is testing]]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.eval :as seval]
   [seon.execution :as execution]
   [seon.schema :as schema]))

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
   ::execution/function-identity
   {::execution/function-symbol 'my.render/view
    ::execution/source-digest digest}
   ::execution/arguments [{:my.render/value 1}]
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
               (assoc invocation ::execution/arguments
                      [(js/Promise.resolve 1)]))))
  (is (execution/valid-parent-message? invocation)))

(deftest compiled-identity-refuses-agent-authored-symbols
  (let [messages (atom [])
        state (atom {::execution/startup startup})
        request (-> (execution/compiled-invocation
                     "agent-1" [] coordinate digest)
                    (assoc-in [::execution/function-identity
                               ::execution/function-symbol]
                              'my.render/view))]
    (@#'execution/receive! state (execution/encode-message request)
     (decoded-sender messages) (fn [_]) 0)
    (is (= execution/error-message
           (::execution/message (first @messages))))
    (is (= :core-bug
           (get-in (first @messages) [::execution/error
                                      :seon.error/kind])))))

(deftest child-rejects-a-compiled-identity-from-another-artifact
  (let [messages (atom [])
        opens (atom 0)
        state (atom {::execution/startup startup})
        wrong-digest (apply str (repeat 64 "b"))
        request (execution/compiled-invocation
                 "agent-1" [1] coordinate wrong-digest)]
    (with-redefs [db/open-session!
                  (fn [_]
                    (swap! opens inc)
                    (js/Promise.resolve nil))]
      (@#'execution/receive! state (execution/encode-message request)
       (decoded-sender messages) (fn [_]) 0))
    (is (zero? @opens) "identity rejection happens before session/program work")
    (is (= execution/error-message
           (::execution/message (first @messages))))
    (is (= :core-bug
           (get-in (first @messages) [::execution/error
                                      :seon.error/kind])))))

(deftest compiled-dispatch-skips-authored-program-acquisition
  (async done
    (let [messages (atom [])
          reads (atom 0)
          state (atom {::execution/startup startup})
          request (execution/compiled-invocation
                   "agent-1" [7] coordinate digest)]
      (with-redefs [db/open-session! (fn [_] (js/Promise.resolve nil))
                    db/execute-many
                    (fn [_]
                      (swap! reads inc)
                      (js/Promise.reject
                       (js/Error. "compiled dispatch read authored program")))
                    seval/lookup-value
                    (fn [_]
                      (fn [value]
                        {:seon.execution-test/value value}))]
        (@#'execution/receive! state (execution/encode-message request)
         (decoded-sender messages) (fn [_]) 0)
        (-> (js/Promise.
             (fn [resolve _]
               (js/setTimeout resolve 0)))
            (.then
             (fn [_]
               (is (zero? @reads))
               (is (= execution/result-message
                      (::execution/message (first @messages))))
               (is (= {:seon.execution-test/value 7}
                      (::execution/result (first @messages))))
               (done)))
            (.catch
             (fn [error]
               (is false (str "compiled dispatch rejected: " error))
               (done))))))))

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

(deftest authored-program-identity-is-order-independent
  (let [edge-a {:db/id 1
                :seon.ns.require/target :my.dep
                :seon.ns.require/refers #{'z 'a}}
        edge-b {:db/id 2 :seon.ns.require/target :seon.db
                :seon.ns.require/alias 'db}
        row-a ["my.render/view" "(defn view [_] :ok)" :my.render ""
               {:seon.ns/require-edges #{edge-a edge-b}}]
        row-b ["my.render/helper" "(defn helper [] 1)" :my.render ""
               {:seon.ns/require-edges #{edge-b edge-a}}]
        first-value (execution/canonical-program
                     [row-a row-b]
                     [[:z/schema ":string"] [:a/schema ":int"]]
                     [["my.render/view" "[:=> [:cat :map] :any]"]])
        second-value (execution/canonical-program
                      [row-b row-a]
                      [[:a/schema ":int"] [:z/schema ":string"]]
                      [["my.render/view" "[:=> [:cat :map] :any]"]])]
    (is (= first-value second-value))
    (is (= (execution/source-digest first-value)
           (execution/source-digest second-value)))))

(deftest authored-loader-loads-each-selected-namespace-once
  (async done
    (let [compile-state (atom {})
          loaded (atom #{})
          evaluated (atom [])
          projections (atom 0)
          original-init seval/init-bootstrap!
          original-lookup seval/lookup-value
          original-eval-str cljs/eval-str
          original-build schema/build-projection
          original-activate schema/activate-projection!]
      (set! seval/init-bootstrap!
            (fn [] (js/Promise.resolve compile-state)))
      (set! seval/lookup-value #(when (contains? @loaded %) :loaded))
      (set! schema/build-projection (fn [& _] :projection))
      (set! schema/activate-projection!
            (fn [projection]
              (is (= :projection projection))
              (swap! projections inc)))
      (set! cljs/eval-str
            (fn [_ source target _ callback]
              (swap! evaluated conj [target source])
              (case target
                my.alpha (swap! loaded into
                                ['my.alpha/first 'my.alpha/second])
                my.beta (swap! loaded conj 'my.beta/run))
              (callback {})))
      (-> (seval/load-authored-program!
            {:seon.execution/schema-forms []
             :seon.execution/function-contracts []
             :seon.execution/namespace-rows
             [{:seon.ns/name :my.alpha
               :seon.ns/source "(ns my.alpha)"
               :seon.ns/require-edges []
               :seon.fn/symbols ['my.alpha/first 'my.alpha/second]
               :seon.fn/sources ["(defn first [] 1)"
                                 "(defn second [] 2)"]}
              {:seon.ns/name :my.beta
               :seon.ns/source "(ns my.beta)"
               :seon.ns/require-edges []
               :seon.fn/symbols ['my.beta/run]
               :seon.fn/sources ["(defn run [] 3)"]}]
             :seon.execution/function-symbols
             ['my.alpha/first 'my.alpha/second
              'my.beta/run 'my.alpha/first]})
          (.then
            (fn [returned-state]
              (is (identical? compile-state returned-state))
              (is (= 1 @projections))
              (is (= ['my.alpha 'my.beta] (mapv first @evaluated)))
              (is (= 2 (count @evaluated)))))
          (.catch
            (fn [error]
              (is false (str "multi-target load rejected: " error))))
          (.finally
            (fn []
              (set! seval/init-bootstrap! original-init)
              (set! seval/lookup-value original-lookup)
              (set! cljs/eval-str original-eval-str)
              (set! schema/build-projection original-build)
              (set! schema/activate-projection! original-activate)
              (done)))))))

(deftest ordinary-namespace-source-preserves-one-compile-unit
  (let [source (seval/namespace-source
                {:seon.ns/name :my.render
                 :seon.ns/source ""
                 :seon.ns/require-edges
                 [{:seon.ns.require/target :my.dep
                   :seon.ns.require/alias 'dep}]
                 :seon.fn/sources
                 ["(def shared 1)\n(defn view [_] (dep/show shared))"
                  "(defn other [_] shared)"]})]
    (is (.startsWith source "(ns my.render (:require [my.dep :as dep]))"))
    (is (= 1 (count (re-seq #"\(def shared 1\)" source)))
        "a batch source is deduplicated rather than split per function")))

(deftest preparation-batches-agents-and-preserves-plan-position
  (async done
    (let [requests (atom [])
          plans [(execution/invocation-plan "agent-2" 'my.two/run [2])
                 (execution/invocation-plan "agent-1" 'my.one/first [1])
                 (execution/invocation-plan "agent-1" 'my.one/second [3])]
          source-by-symbol {"my.one/first" "(defn first [x] x)"
                            "my.one/second" "(defn second [x] x)"
                            "my.two/run" "(defn run [x] x)"}]
      (with-redefs
        [db/execute-many
         (fn [request]
           (swap! requests conj request)
           (js/Promise.resolve
            {::db/coordinate coordinate
             ::db/results
             [{::protocol/success? true
               :datahike.query/result
               [["my.one/first" (get source-by-symbol "my.one/first")]
                ["my.one/second" (get source-by-symbol "my.one/second")]]}
              {::protocol/success? true
               :datahike.query/result
               [["my.two/run" (get source-by-symbol "my.two/run")]]}]}))]
        (-> (execution/prepare-invocations!
             {::execution/coordinate coordinate
              ::execution/invocation-plans plans})
            (.then
             (fn [prepared]
               (is (= 1 (count @requests)))
               (is (= coordinate (::db/coordinate (first @requests))))
               (is (= 2 (count (::db/members (first @requests))))
                   "one execute-many member is issued per agent")
               (is (= (mapv ::execution/invocation-id plans)
                      (mapv ::execution/invocation-id prepared))
                   "prepared invocations retain caller position")
               (doseq [[plan invocation] (map vector plans prepared)]
                 (let [symbol (::execution/function-symbol plan)
                       identity (::execution/function-identity invocation)]
                   (is (= symbol (::execution/function-symbol identity)))
                   (is (= (execution/source-digest
                           (get source-by-symbol (str symbol)))
                          (::execution/source-digest identity)))))
               (done)))
            (.catch
             (fn [error]
               (is false (str "preparation rejected: " error))
               (done))))))))

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

(deftest child-timeout-poisons-the-process-before-a-late-invocation
  (let [token (js-obj)
        messages (atom [])
        exits (atom [])
        closes (atom 0)
        state (atom {::execution/startup startup
                     ::execution/active
                     {::execution/token token
                      ::execution/invocation invocation}})
        send-message! (decoded-sender messages)
        exit! #(swap! exits conj %)]
    (with-redefs [db/close-session! (fn [] (swap! closes inc) true)]
      (@#'execution/timeout-invocation!
       state token invocation send-message! exit!)
      (is (true? (::execution/poisoned? @state)))
      (is (nil? (::execution/active @state)))
      (is (= [1] @exits))
      (is (= "The invocation timed out."
             (get-in (first @messages)
                     [::execution/error :seon.error/message])))

      ;; Even before the injected event-loop-flush exit runs, a late parent
      ;; message cannot enter work in this compiler/global process.
      (@#'execution/receive!
       state
       (execution/encode-message
        (assoc invocation ::execution/invocation-id "after-timeout"))
       send-message! exit! 0)
      (is (= "The execution child is retiring."
             (get-in (second @messages)
                     [::execution/error :seon.error/message])))
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
