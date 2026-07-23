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
   [seon.render.value :as render.value]
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

(deftest package-source-admission-joins-prefix-to-installed-ledger-row
  (let [namespace-name 'seon.packages.js.fast-deep-equal
        installed {:seon.packages/package
                   {:seon.packages/as namespace-name}}
        other-cluster {}
        mismatched {:seon.packages/package
                    {:seon.packages/as 'seon.packages.js.other}}
        non-js {:seon.packages/package
                {:seon.packages/as 'seon.packages.browser}}]
    (is (execution/package-source-admitted? installed namespace-name))
    (is (execution/package-source-admitted? other-cluster namespace-name)
        "ordinary REPL corpus rows remain admitted by process provenance")
    (is (false? (execution/package-source-admitted? mismatched namespace-name)))
    (is (false? (execution/package-source-admitted? non-js
                                                   'seon.packages.browser)))))

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

(def sample-limits
  {:seon.config.render/value-max-path-segments 32
   :seon.config.render/value-max-path-bytes 4096
   :seon.config.render/value-max-realized-items 32
   :seon.config.render/value-max-depth 3
   :seon.config.render/value-max-string 80
   :seon.config.render/value-shape-sample 2
   :seon.render.value/page-size 3})

(defn sample-request [path]
  {::execution/message execution/value-sample-message
   ::execution/protocol-version execution/protocol-version
   ::execution/agent-id "agent-1"
   ::execution/request-id "sample-1"
   ::execution/eval-id "eval-1"
   :seon.render.value/path path
   :seon.render.value/offset 0
   :seon.render.value/effective-limits sample-limits})

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

(deftest value-sample-frames-are-closed-and-preflight-work-bounded
  (let [request (sample-request [])
        oversized (sample-request (vec (repeat 1000000 :x)))
        visits (atom 0)
        original render.value/drill-path-segment?]
    (is (execution/valid-parent-message?
         (execution/decode-message (execution/encode-message request))))
    (is (false? (execution/valid-parent-message? (assoc request ::extra true))))
    (with-redefs [render.value/drill-path-segment?
                  (fn [segment] (swap! visits inc) (original segment))]
      (is (false? (execution/valid-parent-message? oversized)))
      (is (zero? @visits) "a million-segment vector is count-rejected")
      (is (execution/valid-parent-message?
           (sample-request (vec (repeat 32 :x)))))
      (is (= 32 @visits)))
    (is (= 1000000 (count (:seon.render.value/path oversized))))))

(deftest value-sample-terminal-frames-round-trip-as-closed-ordinary-data
  (let [request (sample-request [])
        drill-request (select-keys request
                                   [:seon.render.value/path
                                    :seon.render.value/offset
                                    :seon.render.value/effective-limits])
        result (render.value/drill-value (schema/current-projection)
                                         [1 2 3 4] drill-request)
        result-frame {::execution/message
                      execution/value-sample-result-message
                      ::execution/protocol-version execution/protocol-version
                      ::execution/agent-id "agent-1"
                      ::execution/request-id "sample-1"
                      :seon.render.value/result result}
        error-frame {::execution/message execution/value-sample-error-message
                     ::execution/protocol-version execution/protocol-version
                     ::execution/agent-id "agent-1"
                     ::execution/request-id "sample-1"
                     ::execution/error {:seon.error/message "unavailable"
                                        :seon.error/kind
                                        :seon.runtime/unavailable}}]
    (doseq [frame [result-frame error-frame]]
      (let [decoded (execution/decode-message (execution/encode-message frame))]
        (is (= frame decoded))
        (is (execution/valid-child-message? decoded))
        (is (false? (execution/valid-child-message?
                     (assoc decoded ::extra true))))))))

(deftest child-samples-a-live-value-without-interpreting-its-map-shape
  (async done
    (let [messages (atom [])
          raw {:seon.eval/ok? false :user/value 42}
          state (atom {::execution/startup startup
                       ::execution/compile-state (atom {})
                       ::execution/compiled-functions {}})]
      (with-redefs [seval/result-live? (constantly true)
                    seval/result-sampling-entry
                    (fn [_ _] {:seon.eval/found? true
                               :seon.eval/metadata-valid? true
                               :seon.eval/sampling-limits sample-limits
                               :seon.eval/sampling-database database})
                    seval/lookup-result (fn [_] (js/Promise.resolve raw))]
        (@#'execution/receive! state
         (execution/encode-message (sample-request []))
         (decoded-sender messages) (fn [_]) 0)
        (-> (js/Promise.resolve nil)
            (.then (fn [_] (js/Promise.resolve nil)))
            (.then
             (fn [_]
               (let [frame (first @messages)
                     result (:seon.render.value/result frame)]
                 (is (= execution/value-sample-result-message
                        (::execution/message frame)))
                 (is (= :available (:seon.render.value/availability result)))
                 (is (= [[:seon.eval/ok? false] [:user/value 42]]
                        (get-in result [:seon.render.value/projection
                                        :seon.render.value/tree
                                        :seon.render.value/map-entries]))))))
            (.catch #(is false (str %)))
            (.finally done))))))

(deftest shutdown-invalidates-an-in-flight-sample-before-stopped
  (async done
    (let [messages (atom [])
          resolve-lookup! (atom nil)
          lookup (js/Promise. (fn [resolve _] (reset! resolve-lookup! resolve)))
          state (atom {::execution/startup startup
                       ::execution/compile-state (atom {})
                       ::execution/compiled-functions {}})]
      (with-redefs [seval/result-live? (constantly true)
                    seval/result-sampling-entry
                    (fn [_ _] {:seon.eval/found? true
                               :seon.eval/metadata-valid? true
                               :seon.eval/sampling-limits sample-limits
                               :seon.eval/sampling-database database})
                    seval/lookup-result (fn [_] lookup)]
        (@#'execution/receive! state
         (execution/encode-message (sample-request []))
         (decoded-sender messages) (fn [_]) 0)
        (@#'execution/receive!
         state
         (execution/encode-message
          {::execution/message execution/shutdown-message
           ::execution/protocol-version execution/protocol-version})
         (decoded-sender messages) (fn [_]) 0)
        (@resolve-lookup! {:late true})
        (-> (js/Promise.resolve nil)
            (.then (fn [_] (js/Promise.resolve nil)))
            (.then
             (fn [_]
               (is (= [execution/stopped-message]
                      (mapv ::execution/message @messages)))
               (is (nil? (::execution/active @state)))))
            (.catch #(is false (str %)))
            (.finally done))))))

(deftest forged-wide-sample-limits-are-rejected-before-live-slot-lookup
  (let [lookups (atom 0)]
    (doseq [[field maximum] sample-limits]
      (let [messages (atom [])
            state (atom {::execution/startup startup
                         ::execution/compile-state (atom {})
                         ::execution/compiled-functions {}})
            forged (assoc-in (sample-request [])
                             [:seon.render.value/effective-limits field]
                             (inc maximum))]
        (with-redefs [seval/result-sampling-entry
                      (fn [_ _] {:seon.eval/found? true
                                 :seon.eval/metadata-valid? true
                                 :seon.eval/sampling-limits sample-limits
                                 :seon.eval/sampling-database database})
                      seval/result-live? (fn [_] (swap! lookups inc) true)
                      seval/lookup-result (fn [_]
                                            (swap! lookups inc)
                                            (js/Promise.resolve [1 2 3]))]
          (@#'execution/receive! state
           (execution/encode-message forged)
           (decoded-sender messages) (fn [_]) 0))
        (is (= execution/value-sample-result-message
               (::execution/message (first @messages))) (str field))
        (is (= (render.value/sampling-policy-refusal)
               (:seon.render.value/result (first @messages))) (str field))))
    (is (zero? @lookups))))

(deftest incomplete-retained-policy-is-unavailable-before-live-slot-lookup
  (doseq [definition [{:seon.eval/result-var? true
                       :seon.eval/sampling-limits sample-limits}
                      {:seon.eval/result-var? true
                       :seon.eval/sampling-limits {:bad true}
                       :seon.eval/sampling-database database}]]
    (let [messages (atom [])
          lookups (atom 0)
          state (atom {::execution/startup startup
                       ::execution/compile-state
                       (atom {:cljs.analyzer/namespaces
                              {seval/result-ns-sym
                               {:defs {'eval-1 definition}}}})
                       ::execution/compiled-functions {}})]
      (with-redefs [seval/result-live? (fn [_] (swap! lookups inc) true)
                    seval/lookup-result (fn [_]
                                          (swap! lookups inc)
                                          (js/Promise.resolve [1]))]
        (@#'execution/receive! state
         (execution/encode-message (sample-request []))
         (decoded-sender messages) (fn [_]) 0))
      (is (zero? @lookups))
      (is (= {::execution/message execution/value-sample-error-message
              ::execution/protocol-version execution/protocol-version
              ::execution/agent-id "agent-1"
              ::execution/request-id "sample-1"
              ::execution/error
              {:seon.error/message
               render.value/sampling-policy-unavailable-message
               :seon.error/kind :seon.runtime/unavailable}}
             (first @messages))))))

(deftest non-ordinary-parent-message-reports-an-ordinary-value-path
  (let [failure
        (try
          (execution/encode-message
           {::execution/message execution/invoke-message
            ::execution/arguments [(map identity [1 2])]})
          nil
          (catch :default error error))
        diagnostic (:seon.error/data (ex-data failure))]
    (is (= "Execution IPC accepts only eager ordinary data."
           (ex-message failure)))
    (is (= [::execution/arguments 0]
           (::execution/value-path diagnostic)))
    (is (string? (::execution/value-type diagnostic)))
    (is (protocol/ordinary-wire-value? diagnostic))))

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
               (is (= [] (::db/read-evidence (first @messages)))
                   "every successful child call returns its scoped read evidence")
               (done)))
            (.catch
             (fn [error]
               (is false (str "compiled dispatch rejected: " error))
               (done))))))))

(deftest child-publishes-the-committed-program-before-ready
  (async done
    (let [events (atom [])
          messages (atom [])
          disconnect-handler (atom nil)
          closed (atom 0)
          exits (atom [])
          original-close db/close-session!
          publication {::admission/published? true
                       ::admission/recovered? false
                       ::admission/generation 42}]
      (set! db/close-session! #(swap! closed inc))
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
              (fn [handler]
                (reset! disconnect-handler handler)
                (swap! events conj :disconnect-installed))
              #(swap! exits conj %)))
            (.then
             (fn [_]
               (is (= [:session-opened :publication-started :ready-sent
                       :receiver-installed :disconnect-installed]
                      @events))
               (is (= execution/ready-message
                      (::execution/message (first @messages))))
               (@disconnect-handler)
               (is (= 1 @closed))
               (is (= [0] @exits))
               (is (= 1 (count @messages))
                   "an IPC disconnect cannot send on the closed channel")
               nil))
            (.catch
             (fn [error]
               (is false (str "child startup rejected: " error))))
            (.finally
             (fn []
               (set! db/close-session! original-close)
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
  (testing "a non-ordinary map key produces an ordinary refusal"
    (let [result (execution/bounded-result {(js-obj "host" true) :value} 4096)]
      (is (false? (::execution/ok? result)))
      (is (= [:map-key]
             (get-in result [::execution/error :seon.error/data
                             ::execution/value-path])))
      (is (protocol/ordinary-wire-value? result))))
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
                :seon.ns.require/target 'my.dep
                :seon.ns.require/refers #{'z 'a}}
        edge-b {:db/id 2 :seon.ns.require/target 'seon.db
                :seon.ns.require/alias 'db}
        namespace-rows [['my.render "(ns my.render)"]]
        edge-rows [['my.render edge-a] ['my.render edge-b]]
        row-a ["my.render/view" "(defn view [_] :ok)" 'my.render]
        row-b ["my.render/helper" "(defn helper [] 1)" 'my.render]
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
  (let [request (@#'execution/config-request database)]
    (is (= '[*] (::db/pull-pattern request)))
    (is (= database (::db/db request)))
    (is (= 60000 (::db/max-result-weight request)))
    (is (= (:datahike.resource/max-work config/configuration-read-profile)
           (::db/max-work request)))
    (is (= (:datahike.resource/max-results config/configuration-read-profile)
           (::db/max-results request)))))

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
    (let [requests (atom [])
          namespace-rows [['my.agent.agent-1 "(ns my.agent.agent-1)"]]
          edge {:db/id 100 :seon.ns.require/target 'seon.db
                :seon.ns.require/alias 'db}
          edge-rows [['my.agent.agent-1 edge]]
          function-rows
          [["my.agent.agent-1/run" "(defn run [] :ok)"
            'my.agent.agent-1]
           ["my.agent.agent-1/helper" "(defn helper [] 1)"
            'my.agent.agent-1]]
          test-rows
          [["my.agent.agent-1/check" "(deftest check (is true))"
            'my.agent.agent-1]]
          asserting-tx
          {:seon.db/process {:seon.db.process/id :seon.db.process/boot}}
          schema-rows [[:my/value ":int" asserting-tx]]
          contract-rows
          [["my.agent.agent-1/run" "[:=> [:cat] :keyword]"
            asserting-tx]]
          expected (execution/canonical-program
                    namespace-rows edge-rows [] function-rows test-rows
                    schema-rows contract-rows)
          page-response
          (fn [request]
            (swap! requests conj request)
            (let [attr (first (::db/components request))
                  cursor (::db/cursor request)]
              (js/Promise.resolve
               (case attr
                 :seon.ns/name
                 {:datahike.index-page/datoms
                  [[1 attr 'my.agent.agent-1 1 true]]
                  :datahike.index-page/complete? true}

                 :seon.fn/sym
                 (if cursor
                   {:datahike.index-page/datoms
                    [[11 attr "my.agent.agent-1/helper" 1 true]]
                    :datahike.index-page/complete? true}
                   {:datahike.index-page/datoms
                    [[10 attr "my.agent.agent-1/run" 1 true]]
                    :datahike.index-page/complete? false
                    :datahike.index-page/cursor [10 attr
                                                 "my.agent.agent-1/run"
                                                 1 true]})

                 :seon.test/sym
                 {:datahike.index-page/datoms
                  [[20 attr "my.agent.agent-1/check" 1 true]]
                  :datahike.index-page/complete? true}

                 :seon.schema/key
                 {:datahike.index-page/datoms [[30 attr :my/value 1 true]]
                  :datahike.index-page/complete? true}))))
          page
          (fn
            ([request] (page-response request))
            ([_ _]
             (js/Promise.reject (js/Error. "unexpected positional index-page"))))
          query
          (fn [request]
            (swap! requests conj request)
            (let [arguments (::db/args request)]
              (js/Promise.resolve
               (cond
                 (= 4 (count arguments))
                 (let [[refs identity-attr form-attr _] arguments]
                   (case identity-attr
                     :seon.schema/key schema-rows
                     :seon.fn/sym
                     (if (some #{10} refs) contract-rows [])))

                 (= 2 (count arguments))
                 (case (second arguments)
                   :seon.ns/source [1]
                   :seon.fn/source (first arguments)
                   :seon.test/source [20])

                 :else [[1 100]]))))
          pull-many-response
          (fn [request]
            (swap! requests conj request)
            (let [pattern (::db/pull-pattern request)
                  refs (::db/refs request)]
              (js/Promise.resolve
               (cond
                 (or (= pattern @#'execution/namespace-source-pull-pattern)
                     (= pattern @#'execution/repl-namespace-source-pull-pattern))
                 [{:seon.ns/name 'my.agent.agent-1
                   :seon.ns/source "(ns my.agent.agent-1)"}]

                 (= pattern @#'execution/require-edge-pull-pattern) [edge]

                 (or (= pattern @#'execution/function-source-pull-pattern)
                     (= pattern @#'execution/repl-function-source-pull-pattern))
                 (mapv (fn [ref]
                         (let [[sym source namespace-name]
                               (first (filter #(= ref (if (= "my.agent.agent-1/run"
                                                            (first %)) 10 11))
                                              function-rows))]
                           {:seon.fn/sym sym
                            :seon.fn/source source
                            :seon.fn/ns {:seon.ns/name namespace-name}}))
                       refs)

                 (= pattern @#'execution/test-source-pull-pattern)
                 [{:seon.test/sym "my.agent.agent-1/check"
                   :seon.test/source "(deftest check (is true))"
                   :seon.test/ns {:seon.ns/name 'my.agent.agent-1}}]))))
          pull-many
          (fn
            ([request] (pull-many-response request))
            ([_ _]
             (js/Promise.reject (js/Error. "unexpected positional pull-many")))
            ([_ _ _]
             (js/Promise.reject (js/Error. "unexpected positional pull-many"))))]
      (with-redefs [db/index-page page
                    db/query query
                    db/pull-many pull-many]
        (-> (js/Promise.resolve (@#'execution/acquire-program! database))
            (.then
             (fn [package-program]
                 (is (= expected
                        (select-keys package-program
                                     [::execution/namespace-rows
                                      ::execution/schema-forms
                                      ::execution/function-contracts])))
               (is (every? #(= database (::db/db %)) @requests))
               (is (every? #(or (nil? (::db/limit %))
                                (= 32 (::db/limit %)))
                           @requests))
               (is (every? #(or (nil? (::db/max-result-weight %))
                                (= 60000 (::db/max-result-weight %)))
                           @requests))
               (is (= 4 (count (filter #(= :seon.fn/sym
                                            (first (::db/components %)))
                                      @requests)))
                   "function source and contract cursors page independently")
               (let [row (first (::execution/namespace-rows package-program))]
                 (is (= 'my.agent.agent-1 (:seon.ns/name row)))
                 (is (= ['my.agent.agent-1/helper 'my.agent.agent-1/run]
                        (mapv :seon.fn/sym (:seon.fn/_ns row))))
                 (is (= ['my.agent.agent-1/check]
                        (mapv :seon.test/sym (:seon.test/_ns row))))
                 (is (re-find #"deftest check" (seval/namespace-source row))))
               (is (= #{'my.agent.agent-1/run 'my.agent.agent-1/helper
                        'my.agent.agent-1/check}
                      (set (keys (::execution/source-by-symbol package-program)))))
               (done)))
            (.catch
             (fn [error]
               (is false (str "program acquisition rejected: " error))
               (done))))))))

(deftest program-acquisition-contract-follows-installed-package-schema
  (let [package-contract
        (@#'execution/program-acquisition-contract
         {:seon.packages/package {}})
        repl-contract (@#'execution/program-acquisition-contract {})]
    (is (= @#'execution/namespace-source-pull-pattern
           (::execution/namespace-pull-pattern package-contract)))
    (is (= @#'execution/function-source-pull-pattern
           (::execution/function-pull-pattern package-contract)))
    (is (= @#'execution/repl-namespace-source-pull-pattern
           (::execution/namespace-pull-pattern repl-contract)))
    (is (= @#'execution/repl-function-source-pull-pattern
           (::execution/function-pull-pattern repl-contract)))))

(deftest top-level-program-frame-error-reaches-the-child-error-value
  (async done
    (let [messages (atom [])
          state (atom {::execution/startup startup})
          open-session! db/open-session!
          index-page db/index-page
          frame-error
          {:seon.error/message "The database response exceeded its frame limit."
           :seon.error/kind :core-bug
           :seon.error/data
           {::protocol/error-kind :seon.db.protocol.error/frame-too-large
            ::protocol/request-id "frame-request"}}]
      (set! db/open-session!
            (fn [_] (js/Promise.resolve {:seon.db/db database})))
      (set! db/index-page
            (fn
              ([_] (js/Promise.resolve frame-error))
              ([_ _] (js/Promise.resolve frame-error))))
      (@#'execution/begin-invocation!
       state invocation (decoded-sender messages) (fn [_]) 0)
      (js/setTimeout
       (fn []
         (let [message (first @messages)
               child-error (::execution/error message)]
           (is (= execution/error-message (::execution/message message)))
           (is (= "Authored program acquisition failed."
                  (:seon.error/message child-error)))
           (is (= frame-error
                  (get-in child-error [:seon.error/data :seon.db/error])))
           (is (not= "v must satisfy IVector"
                     (:seon.error/message child-error)))
           (set! db/open-session! open-session!)
           (set! db/index-page index-page)
           (done)))
       20))))

(deftest authored-loader-loads-each-selected-namespace-once
  (async done
    (let [schema-state (schema/snapshot-state)]
      (-> (seval/load-authored-program!
         {:seon.execution/schema-forms []
          :seon.execution/function-contracts []
          :seon.execution/namespace-rows
          [{:seon.ns/name 'my.execution.alpha
            :seon.ns/source "(ns my.execution.alpha)"
            :seon.ns/require-edges []
            :seon.fn/_ns
            [{:seon.fn/sym 'my.execution.alpha/first
              :seon.fn/source "(defn first [] 1)"}
             {:seon.fn/sym 'my.execution.alpha/second
              :seon.fn/source "(defn second [] 2)"}]
            :seon.test/_ns []}
           {:seon.ns/name 'my.execution.beta
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
            [{:seon.ns/name 'my.execution.referred
              :seon.ns/source
              (str "(ns my.execution.referred\n"
                   "  (:require [seon.agent.lifecycle :refer [complete]]))")
              :seon.ns/require-edges
              [{:seon.ns.require/target 'seon.agent.lifecycle
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
          program (assoc (execution/canonical-program [] [] [] [] [] [] [])
                         ::execution/digest (execution/source-digest {})
                         ::execution/source-by-symbol {})]
      (with-redefs [execution/acquire-program!
                    (fn [_] (js/Promise.resolve program))
                    db/pull
                    (fn
                      ([_] (js/Promise.resolve {}))
                      ([_ _] (js/Promise.resolve {}))
                      ([_ _ _] (js/Promise.resolve {})))
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
          state
          (atom
           {::execution/authored-symbols #{'my.orders/view}
            ::execution/program
            {::execution/source-by-symbol
             {'my.orders/unloaded "(defn unloaded [] :ok)"}}})]
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
                   {::execution/function-symbol 'my.orders/stale
                    ::execution/arguments []})
                  (@#'execution/call-selected!
                   state invocation
                   {::execution/function-symbol 'my.orders/unloaded
                    ::execution/arguments []})
                  (@#'execution/call-selected!
                   state invocation
                   {::execution/function-symbol 'seon.missing/core-renderer
                    ::execution/arguments []})])
            (.then
             (fn [results]
               (is (false? (::execution/ok? (aget results 0))))
               (is (false? (::execution/ok? (aget results 1))))
               (is (false? (::execution/ok? (aget results 2))))
               (is (false? (::execution/ok? (aget results 3))))
               (is (= "The selected function is absent from the current database program."
                      (get-in (aget results 1)
                              [::execution/error :seon.error/message])))
               (is (= "The selected function is not loaded in the execution child."
                      (get-in (aget results 2)
                              [::execution/error :seon.error/message])))
               (is (= 2 (count @recorded)))
               (is (every? #(= :core (::error/fault %)) @recorded))
               (is (= #{'my.orders/unloaded
                        'seon.missing/core-renderer}
                      (into #{}
                            (map #(get (ex-data (::error/raw %))
                                       ::execution/function-symbol))
                            @recorded)))
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
               (is (= :crash (:policy @recorded)))))
            (.catch
             (fn [exception]
               (is false (str exception))))
            (.finally
             (fn []
               (set! db/db original-db)
               (set! db/entity original-entity)
               (set! error/record! original-record!)
               (done))))
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

(deftest missing-authored-function-is-a-core-fault
  (let [selected-error (deref #'execution/selected-call-error)
        recorded (atom [])
        exception (ex-info "The selected function is not loaded."
                           {:seon.error/kind :core-bug})]
    (with-redefs [error/record! #(swap! recorded conj %)]
      (is (false? (::execution/ok?
                   (selected-error 'my.orders/view exception :core))))
      (is (= 1 (count @recorded)))
      (is (= :core (get-in @recorded [0 ::error/fault])))
      (is (identical? exception (get-in @recorded [0 ::error/raw]))))))

(deftest ordinary-namespace-source-preserves-one-compile-unit
  (let [source (seval/namespace-source
                {:seon.ns/name 'my.render
                 :seon.ns/source ""
                 :seon.ns/require-edges
                 [{:seon.ns.require/target 'my.dep
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

(deftest namespace-source-reconstructs-effective-aliases-after-bare-reentry
  (let [source (seval/namespace-source
                {:seon.ns/name 'my.consumer
                 :seon.ns/source "(ns my.consumer)"
                 :seon.ns/require-edges
                 [{:seon.ns.require/target 'my.base
                   :seon.ns.require/alias 'base}]
                 :seon.fn/_ns
                 [{:seon.fn/sym 'my.consumer/answer
                   :seon.fn/source "(defn answer [] (base/value))"}]
                 :seon.test/_ns []})]
    (is (.startsWith source
                     "(ns my.consumer (:require [my.base :as base]))")
        "cold source reflects the analyzer aliases retained by the live child")
    (is (.includes source "(defn answer [] (base/value))"))))

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
