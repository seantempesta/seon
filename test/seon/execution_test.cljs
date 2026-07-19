(ns seon.execution-test
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.agent.lifecycle :as lifecycle]
   [seon.config :as config]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.error :as error]
   [seon.eval :as seval]
   [seon.execution :as execution]
   [seon.runtime.admission :as admission]
   [seon.schema :as schema]))

(def digest (apply str (repeat 64 "a")))
(def database
  {:db-name "test"
   :store-id [#uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" :db]
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(def invocation
  {::execution/message execution/invoke-message
   ::execution/protocol-version execution/protocol-version
   ::execution/agent-id "agent-1"
   ::execution/invocation-id "invoke-1"
   :seon.db/db database
   ::execution/function-identity
   {::execution/function-symbol 'my.render/view
    ::execution/source-digest digest}
   ::execution/arguments [{:my.render/value 1}]
   ::execution/deadline-ms 9999999999999
   ::execution/result-limit-bytes 4096})

(deftest compiled-function-map-is-one-closed-descriptor-contract
  (let [descriptor {::execution/compiled-function (fn [_ _ _ _] :ok)
                    ::execution/pin-database? true}
        valid {'seon.execution.runtime/render-prompt! descriptor}]
    (is (@#'execution/valid-compiled-functions? valid))
    (is (false?
         (@#'execution/valid-compiled-functions?
          {'seon.execution.runtime/render-prompt!
           (::execution/compiled-function descriptor)}))
        "bare callables are not a second compiled-function representation")
    (is (false?
         (@#'execution/valid-compiled-functions?
          {'seon.execution.runtime/render-prompt!
           (dissoc descriptor ::execution/pin-database?)})))
    (is (false?
         (@#'execution/valid-compiled-functions?
          {'seon.execution.runtime/render-prompt!
           (assoc descriptor ::execution/extra true)})))))

(def startup
  {::execution/protocol-version execution/protocol-version
   ::execution/agent-id "agent-1"
   ::execution/artifact-digest digest
   ::execution/shadow-build-id "execution"
   ::execution/database-selection
   {::db/socket-path "tmp/test.sock"
    ::db/database-name "test"
    ::db/backend :memory}})

(def prompt-function 'seon.execution.runtime/render-prompt!)

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
        state (atom {::execution/startup startup
                     ::execution/compiled-functions
                     {prompt-function
                      {::execution/compiled-function (fn [_ _ _ _] nil)
                       ::execution/pin-database? true}}})
        request (execution/compiled-invocation
                 "agent-1" 'my.render/view [] database digest)]
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
        state (atom {::execution/startup startup
                     ::execution/compiled-functions
                     {prompt-function
                      {::execution/compiled-function (fn [_ _ _ _] nil)
                       ::execution/pin-database? true}}})
        wrong-digest (apply str (repeat 64 "b"))
        request (execution/compiled-invocation
                 "agent-1" prompt-function [1] database wrong-digest)]
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
          state (atom {::execution/startup startup
                       ::execution/compiled-functions
                       {prompt-function
                        {::execution/compiled-function
                         (fn [arguments invoke-selected! compile-state!
                              prepare-program!]
                           (is (fn? invoke-selected!))
                           (is (fn? compile-state!))
                           (is (fn? prepare-program!))
                           (is (= database
                                  (:seon.db/db (db/current-tx-context))))
                           {:seon.execution-test/value (first arguments)})
                         ::execution/pin-database? true}}})
          request (execution/compiled-invocation
                   "agent-1" prompt-function [7] database digest)]
      (with-redefs [db/open-session! (fn [_] (js/Promise.resolve nil))
                    db/execute-many
                    (fn [_]
                      (swap! reads inc)
                      (js/Promise.reject
                       (js/Error. "compiled dispatch read authored program")))
                    seval/lookup-value
                    (fn [_]
                      (throw (js/Error. "compiled dispatch used global lookup")))]
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

(deftest child-publishes-the-committed-program-before-ready
  (async done
    (let [events (atom [])
          messages (atom [])
          publication {::admission/published? true
                       ::admission/recovered? false
                       ::admission/generation 42}]
      (with-redefs [db/open-session!
                    (fn [_]
                      (swap! events conj :session-opened)
                      (js/Promise.resolve {:seon.db/db database}))
                    admission/prepare-committed!
                    (fn [request]
                      (is (= {::admission/record-failures? false
                              ::admission/instrument? false}
                             request)
                          "the isolated child activates the projection without pod-wide wrappers")
                      (swap! events conj :publication-started)
                      (js/Promise.resolve
                       {::admission/prepared? true
                        ::admission/recovered? false
                        ::admission/generation 42}))
                    admission/admit-prepared!
                    (fn [_] publication)]
        (-> (js/Promise.resolve
             (@#'execution/start-child!
              {} startup
              (fn [encoded]
                (swap! events conj :ready-sent)
                ((decoded-sender messages) encoded))
              (fn [_] (swap! events conj :receiver-installed))
              (fn [_])))
            (.then
             (fn [_]
               (is (= [:session-opened :publication-started :ready-sent
                       :receiver-installed]
                      @events))
               (is (= execution/ready-message
                      (::execution/message (first @messages))))
               (done)))
            (.catch
             (fn [error]
               (is false (str "child startup rejected: " error))
               (done))))))))

(deftest unpinned-compiled-dispatch-can-read-the-moving-authority-head
  (async done
    (let [messages (atom [])
          state (atom
                 {::execution/startup startup
                  ::execution/compiled-functions
                  {prompt-function
                   {::execution/compiled-function
                    (fn [_ _ _ _]
                      {:seon.execution-test/pinned-database
                       (:seon.db/db (db/current-tx-context))})
                    ::execution/pin-database? false}}})
          request (execution/compiled-invocation
                   "agent-1" prompt-function [] database digest)]
      (with-redefs [db/open-session! (fn [_] (js/Promise.resolve nil))]
        (@#'execution/receive! state (execution/encode-message request)
         (decoded-sender messages) (fn [_]) 0)
        (-> (js/Promise. (fn [resolve _] (js/setTimeout resolve 0)))
            (.then
             (fn [_]
               (is (= {:seon.execution-test/pinned-database nil}
                      (::execution/result (first @messages))))
               (done)))
            (.catch
             (fn [error]
               (is false (str "unpinned dispatch rejected: " error))
               (done))))))))

(deftest child-compiler-state-initializes-once
  (async done
    (let [initialized (atom 0)
          compile-state (atom {})
          state (atom {})]
      (with-redefs [seval/init-bootstrap!
                    (fn []
                      (swap! initialized inc)
                      (js/Promise.resolve compile-state))]
        (-> (js/Promise.resolve
             (@#'execution/ensure-compile-state! state))
            (.then
             (fn [first-state]
               (-> (js/Promise.resolve
                    (@#'execution/ensure-compile-state! state))
                   (.then
                    (fn [second-state]
                      (is (identical? compile-state first-state))
                      (is (identical? first-state second-state))
                      (is (= 1 @initialized))
                      (done))))))
            (.catch
             (fn [error]
               (is false (str "compiler state initialization rejected: " error))
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
                                    :seon.error/kind])))
      (is (= [] (get-in result [::execution/error :seon.error/data
                                ::execution/value-path])))
      (is (= "object" (get-in result [::execution/error :seon.error/data
                                      ::execution/value-type])))))
  (testing "the refusal identifies a nested lazy sequence"
    (let [result (execution/bounded-result
                  {:seon.render/hiccup [:div (map identity [1 2])]}
                  4096)]
      (is (false? (::execution/ok? result)))
      (is (= [:seon.render/hiccup 1]
             (get-in result [::execution/error :seon.error/data
                             ::execution/value-path])))))
  (testing "the caller's smaller byte limit is enforced"
    (let [result (execution/bounded-result {:my.render/value (apply str
                                                                         (repeat 100 "x"))}
                                           16)]
      (is (false? (::execution/ok? result)))
      (is (< 16 (get-in result [::execution/error :seon.error/data
                                ::execution/result-bytes]))))))

(deftest child-errors-preserve-each-ordinary-ex-data-entry
  (let [value
        (@#'execution/exception-value
         (ex-info "bad call"
                  {:seon.error/kind :core-bug
                   :seon.error.malli/fn-sym 'my.example/call
                   :seon.error.malli/got-edn "{:bad true}"
                   :seon.error.malli/raw (js-obj "not" "wire data")}))]
    (is (= {:seon.error/message "bad call"
            :seon.error/kind :core-bug
            :seon.error/data
            {:seon.error/kind :core-bug
             :seon.error.malli/fn-sym 'my.example/call
             :seon.error.malli/got-edn "{:bad true}"}}
           value))))

(deftest authored-program-identity-is-order-independent
  (let [edge-a {:db/id 1
                :seon.ns.require/target :my.dep
                :seon.ns.require/refers #{'z 'a}}
        edge-b {:db/id 2 :seon.ns.require/target :seon.db
                :seon.ns.require/alias 'db}
        namespace-rows [[:my.render "(ns my.render)"]]
        edge-rows [[:my.render edge-a] [:my.render edge-b]]
        row-a ["my.render/view" "(defn view [_] :ok)" :my.render]
        row-b ["my.render/helper" "(defn helper [] 1)" :my.render]
        first-value (execution/canonical-program
                     (set namespace-rows) edge-rows [] [row-a row-b] []
                     [[:z/schema ":string"] [:a/schema ":int"]]
                     [["my.render/view" "[:=> [:cat :map] :any]"]])
        second-value (execution/canonical-program
                      namespace-rows (reverse edge-rows) [] [row-b row-a] []
                      [[:a/schema ":int"] [:z/schema ":string"]]
                      [["my.render/view" "[:=> [:cat :map] :any]"]])]
    (is (= first-value second-value))
    (is (= (execution/source-digest first-value)
           (execution/source-digest second-value)))))

(deftest execution-configuration-pull-budgets-retained-nodes
  (let [member (@#'execution/config-member database)]
    (is (= '[*] (::protocol/selector member)))
    (is (= 256 (:datahike.resource/max-results member)))
    (is (= 65536 (:datahike.resource/max-result-weight member)))))

(deftest authored-program-queries-bound-retained-results-and-bytes
  (let [member (@#'execution/query-member database '[:find ?value] [])]
    (is (= 16384 (:datahike.resource/max-results member)))
    (is (= (* 3 1024 1024)
           (:datahike.resource/max-result-weight member)))))

(deftest source-identity-hashes-exact-utf8-bytes
  (let [source "(defn view [_] :ok)\n"
        expected (-> (.createHash (js/require "node:crypto") "sha256")
                     (.update source "utf8")
                     (.digest "hex"))]
    (is (= expected (execution/source-digest source)))))

(deftest runtime-program-acquisition-is-one-shared-database-value
  (async done
    (let [request (atom nil)
          results
          [#{[:my.agent.agent-1 "(ns my.agent.agent-1)"]}
           #{}
           #{["my.agent.agent-1/run" "(defn run [] :ok)"
              :my.agent.agent-1]}
           #{["my.agent.agent-1/check" "(deftest check (is true))"
              :my.agent.agent-1]}
           #{}
           #{}]
          response
          {::db/results
           (mapv (fn [result]
                   {::protocol/success? true
                    :datahike.query/result result})
                 results)}]
      (with-redefs [db/execute-many
                    (fn [value]
                      (reset! request value)
                      (js/Promise.resolve response))]
        (-> (js/Promise.resolve
             (@#'execution/acquire-program! database))
            (.then
             (fn [program]
               (is (= 6 (count (::db/members @request))))
               (is (every? #(= database
                                (first (::protocol/arguments %)))
                           (::db/members @request)))
               (let [row (first (::execution/namespace-rows program))]
                 (is (= :my.agent.agent-1 (:seon.ns/name row)))
                 (is (= ['my.agent.agent-1/run]
                        (mapv :seon.fn/sym (:seon.fn/_ns row))))
                 (is (= ['my.agent.agent-1/check]
                        (mapv :seon.test/sym (:seon.test/_ns row))))
                 (is (re-find #"deftest check" (seval/namespace-source row))))
               (is (= #{'my.agent.agent-1/run 'my.agent.agent-1/check}
                      (set (keys (::execution/source-by-symbol program)))))
               (done)))
            (.catch
             (fn [error]
               (is false (str "program acquisition rejected: " error))
               (done))))))))

(deftest authored-loader-loads-each-selected-namespace-once
  (async done
    (let [schema-state (schema/snapshot-state)]
      (-> (seval/load-authored-program!
         {:seon.execution/schema-forms []
          :seon.execution/function-contracts []
          :seon.execution/namespace-rows
          [{:seon.ns/name :my.execution.alpha
            :seon.ns/source "(ns my.execution.alpha)"
            :seon.ns/require-edges []
            :seon.fn/_ns
            [{:seon.fn/sym 'my.execution.alpha/first
              :seon.fn/source "(defn first [] 1)"}
             {:seon.fn/sym 'my.execution.alpha/second
              :seon.fn/source "(defn second [] 2)"}]
            :seon.test/_ns []}
           {:seon.ns/name :my.execution.beta
            :seon.ns/source "(ns my.execution.beta)"
            :seon.ns/require-edges []
            :seon.fn/_ns
            [{:seon.fn/sym 'my.execution.beta/run
              :seon.fn/source "(defn run [] 3)"}]
            :seon.test/_ns []}]
          :seon.execution/function-symbols
          ['my.execution.alpha/first 'my.execution.alpha/second
           'my.execution.beta/run 'my.execution.alpha/first]})
        (.then
         (fn [compile-state]
           (is (some? compile-state))
           (is (fn? (seval/lookup-value 'my.execution.alpha/first)))
           (is (fn? (seval/lookup-value 'my.execution.alpha/second)))
           (is (fn? (seval/lookup-value 'my.execution.beta/run)))))
        (.catch
         (fn [error]
           (is false (str "multi-target load rejected: " error))))
          (.finally
           (fn []
             (schema/restore-state! schema-state)
             (done)))))))

(deftest authored-loader-seeds-persisted-referred-host-functions
  (async done
    (let [schema-state (schema/snapshot-state)
          sym 'my.execution.referred/run]
      (is (fn? lifecycle/complete)
          "the referred lifecycle namespace is host compiled")
      (-> (seval/load-authored-program!
           {:seon.execution/schema-forms []
            :seon.execution/function-contracts []
            :seon.execution/namespace-rows
            [{:seon.ns/name :my.execution.referred
              :seon.ns/source
              (str "(ns my.execution.referred\n"
                   "  (:require [seon.agent.lifecycle :refer [complete]]))")
              :seon.ns/require-edges
              [{:seon.ns.require/target :seon.agent.lifecycle
                :seon.ns.require/refers #{'complete}}]
              :seon.fn/_ns
              [{:seon.fn/sym sym
                :seon.fn/source "(defn run [] (fn? complete))"}]
              :seon.test/_ns []}]
            :seon.execution/function-symbols [sym]})
          (.then
           (fn [_]
             (is (true? ((seval/lookup-value sym))))
             (schema/restore-state! schema-state)
             (done)))
          (.catch
           (fn [error]
             (is false (str "persisted :refer failed to load: " error))
             (schema/restore-state! schema-state)
             (done)))))))

(deftest failed-persisted-program-keeps-the-eval-repair-door-open
  (async done
    (let [compile-state (atom {})
          state (atom {::execution/startup startup})
          empty-query-result
          {::protocol/success? true
           :datahike.query/result []}
          response
          {::db/results
           (conj (vec (repeat 6 empty-query-result))
                 {::protocol/success? true
                  :datahike.pull/result {}})}]
      (with-redefs [db/execute-many (fn [_] (js/Promise.resolve response))
                    seval/init-bootstrap!
                    (fn [] (js/Promise.resolve compile-state))
                    seval/load-authored-program!
                    (fn [_]
                      (js/Promise.reject
                       (ex-info "broken persisted namespace"
                                {:seon.error/kind :compile})))]
        (-> (js/Promise.resolve
             (@#'execution/prepare-eval-program! state invocation))
            (.then
             (fn [prepared]
               (is (identical? compile-state
                               (::execution/compile-state prepared)))
               (is (= "broken persisted namespace"
                      (get-in prepared [::execution/program-load-error
                                        :seon.error/message])))
               (is (map? (::execution/program prepared))
                   "the exact source map remains available to a repair form")
               (done)))
            (.catch
             (fn [error]
               (is false (str "repair preparation rejected: " error))
               (done))))))))

(deftest selected-compiled-functions-skip-authored-acquisition
  (async done
    (let [reads (atom 0)
          state (atom {::execution/startup startup})]
      (with-redefs [db/execute-many
                    (fn [_]
                      (swap! reads inc)
                      (js/Promise.reject
                        (js/Error. "compiled selection acquired a program")))
                    seval/lookup-value
                    (fn [sym]
                      (when (= 'seon.test/compiled sym)
                        (fn [value invoke-selected!]
                          (is (fn? invoke-selected!))
                          (inc value))))]
        (-> (js/Promise.resolve
             (@#'execution/invoke-selected!
              state invocation
              [{::execution/function-symbol 'seon.test/compiled
                ::execution/invoke-selected? true
                ::execution/arguments [41]}]))
            (.then
              (fn [results]
                (is (zero? @reads))
                (is (= [{::execution/ok? true ::execution/value 42}]
                       results))
                (done)))
            (.catch
              (fn [error]
                (is false (str "compiled selection rejected: " error))
                (done))))))))

(deftest nested-compiled-selection-keeps-its-declared-arity
  (async done
    (let [state (atom {::execution/startup startup})]
      (with-redefs [seval/lookup-value
                    (fn [sym]
                      (when (= 'seon.test/renderer sym)
                        (fn [value] (inc value))))]
        (-> (js/Promise.resolve
             (@#'execution/invoke-selected!
              state invocation
              [{::execution/function-symbol 'seon.test/renderer
                ::execution/arguments [41]}]))
            (.then
              (fn [results]
                (is (= [{::execution/ok? true ::execution/value 42}]
                       results))
                (done)))
            (.catch
              (fn [error]
                (is false (str "nested compiled selection rejected: " error))
                (done))))))))

(deftest selected-call-boundary-records-core-but-not-authored-failures
  (async done
    (let [recorded (atom [])
          state (atom {::execution/authored-symbols #{'my.orders/view}})]
      (with-redefs [error/record! #(swap! recorded conj %)
                    seval/lookup-value
                    (fn [sym]
                      (when (= 'my.orders/view sym)
                        (fn [] (throw (js/Error. "authored failure")))))]
        (-> (js/Promise.all
             #js [(@#'execution/call-selected!
                   state invocation
                   {::execution/function-symbol 'my.orders/view
                    ::execution/arguments []})
                  (@#'execution/call-selected!
                   state invocation
                   {::execution/function-symbol 'seon.missing/core-renderer
                    ::execution/arguments []})])
            (.then
             (fn [results]
               (is (false? (::execution/ok? (aget results 0))))
               (is (false? (::execution/ok? (aget results 1))))
               (is (= 1 (count @recorded)))
               (is (= :core (::error/fault (first @recorded))))
               (is (= 'seon.missing/core-renderer
                      (get (ex-data (::error/raw (first @recorded)))
                           ::execution/function-symbol)))
               (done)))
            (.catch
             (fn [exception]
               (is false (str exception))
               (done))))))))

(deftest top-level-core-failure-loads-the-database-crash-policy-only-on-error
  (async done
    (let [record-top-level! (deref #'execution/record-top-level-call-error!)
          current-scope (deref #'error/current-scope)
          reads (atom [])
          recorded (atom nil)
          original-db db/db
          original-entity db/entity
          original-record! error/record!
          core-error (ex-info "composition failed"
                              {:seon.error/kind :core-bug})]
      (set! db/db
            (fn
              ([]
               (swap! reads conj :db)
               (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! db/entity
            (fn
              ([_ reference]
               (swap! reads conj reference)
               (js/Promise.resolve
                {:seon.config/id config/cluster-config-id
                 :seon.config/on-core-error :crash}))
              ([_] (js/Promise.resolve nil))))
      (set! error/record!
            (fn [request]
              (reset! recorded
                      {:request request
                       :policy
                       (get-in
                        (current-scope)
                        [:seon.error.scope/configuration
                         :seon.config/on-core-error])})))
      (try
        (is (= :core
               (error/fault-for
                'seon.execution.runtime/render-agent-view!)))
        (-> (js/Promise.resolve
             (record-top-level! 'my.orders/view
                                (js/Error. "authored failure")))
            (.then
             (fn [_]
               (is (empty? @reads))
               (js/Promise.resolve
                (record-top-level!
                 'seon.execution.runtime/render-agent-view! core-error))))
            (.then
             (fn [_]
               (is (= [:db [:seon.config/id config/cluster-config-id]]
                      @reads))
               (is (= :core (get-in @recorded [:request ::error/fault])))
               (is (identical? core-error
                               (get-in @recorded [:request ::error/raw])))
               (is (= :crash (:policy @recorded)))
               (done)))
            (.catch
             (fn [exception]
               (is false (str exception))
               (done)))
            (.finally
             (fn []
               (set! db/db original-db)
               (set! db/entity original-entity)
               (set! error/record! original-record!))))
        (catch :default exception
          (set! db/db original-db)
          (set! db/entity original-entity)
          (set! error/record! original-record!)
          (throw exception))))))

(deftest selected-load-error-preserves-only-child-reload
  (let [ordinary (ex-info "ordinary compile failure"
                          {:seon.error/kind :compile})
        reload (ex-info "Authored source changed; a fresh child is required."
                        {::execution/reload-required? true
                         :seon.error/kind :core-bug})]
    (is (identical? ordinary
                    (@#'execution/selected-load-error ordinary)))
    (try
      (@#'execution/selected-load-error reload)
      (is false "the child reload signal became an ordinary selected error")
      (catch :default error
        (is (identical? reload error))))))

(deftest ordinary-namespace-source-preserves-one-compile-unit
  (let [source (seval/namespace-source
                {:seon.ns/name :my.render
                 :seon.ns/source ""
                 :seon.ns/require-edges
                 [{:seon.ns.require/target :my.dep
                   :seon.ns.require/alias 'dep}]
                 :seon.fn/_ns
                 [{:seon.fn/sym 'my.render/view
                   :seon.fn/source
                   "(def shared 1)\n(defn view [_] (dep/show shared))"}
                  {:seon.fn/sym 'my.render/other
                   :seon.fn/source "(defn other [_] shared)"}]
                 :seon.test/_ns []})]
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
            {::db/results
             [{::protocol/success? true
               :datahike.query/result
               [["my.one/first" (get source-by-symbol "my.one/first")]
                ["my.one/second" (get source-by-symbol "my.one/second")]
                ["my.two/run" (get source-by-symbol "my.two/run")]]}]}))]
        (-> (execution/prepare-invocations!
             {:seon.db/db database
              ::execution/invocation-plans plans})
            (.then
             (fn [prepared]
               (is (= 1 (count @requests)))
               (is (every? #(= database
                                (first (::protocol/arguments %)))
                           (::db/members (first @requests))))
               (is (= 1 (count (::db/members (first @requests))))
                   "one source query covers every agent plan")
               (let [member (first (::db/members (first @requests)))
                     [_ requested] (::protocol/arguments member)]
                 (is (= #{"my.one/first" "my.one/second" "my.two/run"}
                        (set requested))
                     "source identity is database-wide rather than per-agent"))
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
        :seon.db/db database
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
