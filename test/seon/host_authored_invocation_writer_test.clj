(ns seon.host-authored-invocation-writer-test
  "Pinned authored-function invocation through the JVM host tier."
  (:require [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [sci.ctx-store]
            [seon.content-hash :as content-hash]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host.graduate :as graduate]
            [seon.host.instrument :as instrument]
            [seon.host-registry-writer-test :as registry-test])
  (:import [java.io File]
           [java.nio.channels SocketChannel]))

(def ^:private value-sampling-policy
  (var-get #'registry-test/value-sampling-policy))
(def ^:private dependencies
  (var-get #'registry-test/dependencies))
(def ^:private host-session!
  (var-get #'registry-test/host-session!))
(def ^:private socket-path
  (var-get #'registry-test/socket-path))

(def ^:private agent-candidates
  (db.id/candidate-manifest
   {:seon.agent/id :seon.db.id.generator/human-readable}
   [{:seon.db.id/key :fixture/agent
     :seon.db.id/identity-attr :seon.agent/id}]))
(def ^:private agent-id
  (:seon.db.id/value (first agent-candidates)))
(def ^:private home-ns
  (symbol (str "my.agent." agent-id)))

(def ^:private helper-a "(defn helper [] :helper-a)")
(def ^:private caller-a
  (str "(defn caller\n"
       "  {:malli/schema [:=> [:cat :int] [:tuple :keyword :int :keyword]]}\n"
       "  [x] [:a x ((resolve '" home-ns "/helper))])"))
(def ^:private private-a "(defn- private-answer [] :private-a)")
(def ^:private square-source
  (str "(defn square\n"
       "  {:malli/schema [:=> [:cat :int] :int]\n"
       "   :test (fn [] (assert (= 9 (square 3))))}\n"
       "  [x] (* x x))"))
(def ^:private helper-b "(defn helper [] :helper-b)")
(def ^:private caller-b
  (str "(defn caller\n"
       "  {:malli/schema [:=> [:cat :int] [:tuple :keyword :int :keyword]]}\n"
       "  [x] [:b x ((resolve '" home-ns "/helper))])"))

(defn- function-row
  ([sym source spec] (function-row sym source spec false))
  ([sym source spec private?]
   {:seon.fn/sym (str sym)
    :seon.fn/ns {:seon.ns/name (symbol (namespace sym))}
    :seon.fn/source source
    :seon.fn/source-fingerprint (content-hash/sha-256 source)
    :seon.fn/execution-tier :nursery
    :seon.fn/fn-var? true
    :seon.fn/arglists "([])"
    :seon.fn/doc "Authored invocation test function."
    :seon.fn/private? private?
    :seon.fn/spec spec
    :seon.fn/created-at (java.util.Date.)}))

(defn- invoke-authored!
  [live invocation-id database function-symbol source-digest arguments]
  (uds/write-frame!
   (::registry-test/output live)
   {:seon.execution/message :seon.execution.message/invoke
    :seon.execution/protocol-version 3
    :seon.execution/agent-id agent-id
    :seon.execution/invocation-id invocation-id
    :seon.db/db database
    :seon.execution/function-identity
    {:seon.execution/function-symbol function-symbol
     :seon.execution/source-digest source-digest}
    :seon.execution/arguments arguments
    :seon.execution/deadline-ms (+ (System/currentTimeMillis) 30000)
    :seon.execution/result-limit-bytes 1000000})
  (uds/read-frame (::registry-test/input live)))

(defn- error-message [response]
  (get-in response [:seon.execution/error :seon.error/message]))

(defn- query-function [session sym]
  (ffirst
   (context/query-writer!
    session
    '[:find (pull ?function [*])
      :in $ ?sym
      :where [?function :seon.fn/sym ?sym]]
    [(str sym)])))

(defn- seed-database! [session database-name]
  (let [seed
        (writer-test/seed-canonical-schema!
         session database-name
         [value-sampling-policy
          {:seon.user/id "user"}
          {:seon.db.process/id :seon.db.process/repl}])
        _ (is (true? (::protocol/success? seed)) (pr-str seed))
        database (db.host/resolve-db! session nil false)
        allocated
        (db.host/call!
         session
         (protocol/transaction-request
          {::protocol/request-id (str (random-uuid))
           :seon.db/db database
           ::protocol/transaction-data [{:seon.agent/id agent-id}]
           ::protocol/generated-candidates agent-candidates}))
        _ (is (true? (::protocol/success? allocated)) (pr-str allocated))]
    (let [installed
          (sci/eval-string*
           (context/fork-context (context/build-base! session))
           (str "(require 'seon.db)"
                "(seon.db/transact! {:seon.db/tx-data "
                (pr-str [{:seon.db/user [:seon.agent/id agent-id]
                          :seon.db/process
                          [:seon.db.process/id :seon.db.process/repl]}
                         {:seon.fn/sym "seed/install-probe"
                          :seon.fn/spec "[:=> [:cat :int] :int]"
                          :seon.fn/schema-error "none"
                          :seon.fn/read-attrs [:seed/attr]}])
                "})"))]
      (is (map? (:db-after installed)) (pr-str installed)))
    (let [database-before-functions (context/resolve-head! session)]
      (binding [context/*agent-id* agent-id]
      (let [seeded
            (context/transact-writer!
             session
             [(function-row (symbol (str home-ns "/helper")) helper-a
                            "[:=> [:cat] :keyword]")
              (function-row (symbol (str home-ns "/caller")) caller-a
                            "[:=> [:cat :int] [:tuple :keyword :int :keyword]]")
              (function-row (symbol (str home-ns "/private-answer")) private-a
                            "[:=> [:cat] :keyword]" true)
              (function-row (symbol (str home-ns "/square")) square-source
                            "[:=> [:cat :int] :int]")])]
        (is (:seon.db/ok? seeded) (pr-str seeded))))
      database-before-functions)))

(deftest pinned-authored-invocation-is-version-correct-and-isolated
  (let [database-name (str "host-authored-" (random-uuid))
        request-path (socket-path "authored-writer")
        host-socket (socket-path "authored-host")
        server (writer-test/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-name
                               ::writer/backend :memory
                               ::writer/request-socket-path request-path})
        session (context/writer-session
                 {::context/writer-socket-path request-path
                  ::context/database-name database-name
                  ::context/backend :memory})
        started (atom nil)]
    (try
      (let [database-before-functions (seed-database! session database-name)
            database-a (context/resolve-head! session)
            caller-sym (symbol (str home-ns "/caller"))
            helper-sym (symbol (str home-ns "/helper"))
            private-sym (symbol (str home-ns "/private-answer"))
            square-sym (symbol (str home-ns "/square"))
            caller-digest (content-hash/sha-256 caller-a)
            private-digest (content-hash/sha-256 private-a)
            square-digest (content-hash/sha-256 square-source)]
        (reset! started
                (host/start! {::host/socket-path host-socket
                              ::context/writer-socket-path request-path
                              ::context/database-name database-name
                              ::context/backend :memory}))
        (let [live (host-session! host-socket agent-id database-name)]
          (try
            (testing "matching nursery roots invoke directly and stay instrumented"
              (let [retained (get @(::host/contexts @started) agent-id)]
                (is (= caller-digest
                       (:seon.fn/source-fingerprint
                        (meta @(sci/resolve retained caller-sym))))))
              (is (= [:a 2 :helper-a]
                     (:seon.execution/result
                      (invoke-authored! live "direct-a" database-a caller-sym
                                        caller-digest [2]))))
              (let [wrong (invoke-authored! live "direct-wrong" database-a
                                             caller-sym caller-digest ["bad"])]
                (is (= :agent
                       (get-in wrong
                               [:seon.execution/error :seon.error/kind])))
                (is (re-find #"malli/instrument-input.*caller"
                             (error-message wrong))
                    (pr-str wrong))))
            (testing "qualified private authored functions match the child policy"
              (is (= :private-a
                     (:seon.execution/result
                      (invoke-authored! live "private-a" database-a private-sym
                                        private-digest [])))))
            (testing "absent and mismatched identities reject before invocation"
              (is (= "The requested current agent function does not exist."
                     (error-message
                      (invoke-authored!
                       live "absent-at-database" database-before-functions
                       caller-sym caller-digest [1])))
                  "a matching live root cannot bypass the pinned database")
              (is (= "The requested current agent function does not exist."
                     (error-message
                      (invoke-authored!
                       live "absent" database-a
                       (symbol (str home-ns "/absent")) caller-digest []))))
              (is (= "The requested function source is no longer current."
                     (error-message
                      (invoke-authored!
                       live "mismatch" database-a caller-sym
                       (apply str (repeat 64 "f")) [1])))))
            (testing "the same invocation path serves a graduated JVM root"
              (let [row (query-function session square-sym)
                    outcome
                    (graduate/graduate!
                     {::context/base (::host/base @started)
                      ::context/registry
                      (get-in @started [::host/base ::context/registry])
                      ::context/writer (::host/writer @started)
                      ::graduate/function-row row
                      ::graduate/contexts
                      (vec (vals @(::host/contexts @started)))})]
                (is (::graduate/ok? outcome) (pr-str outcome))
                (let [retained (get @(::host/contexts @started) agent-id)]
                  (is (= square-digest
                         (:seon.fn/source-fingerprint
                          (meta @(sci/resolve retained square-sym))))))
                (is (= 25
                       (:seon.execution/result
                        (invoke-authored! live "graduated" database-a square-sym
                                          square-digest [5]))))))
            (testing "an A request replays in a detached fork while live roots stay B"
              (binding [context/*agent-id* agent-id]
                (is (:seon.db/ok?
                     (context/transact-writer!
                      session
                      [(function-row helper-sym helper-b
                                     "[:=> [:cat] :keyword]")
                       (function-row caller-sym caller-b
                                     "[:=> [:cat :int] [:tuple :keyword :int :keyword]]")]))))
              (doseq [sym [helper-sym caller-sym]]
                (let [row (query-function session sym)]
                  (is (::graduate/ok?
                       (graduate/install-nursery!
                        {::context/base (::host/base @started)
                         ::context/registry
                         (get-in @started [::host/base ::context/registry])
                         ::graduate/function-row row
                         ::graduate/contexts
                         (vec (vals @(::host/contexts @started)))})))))
              (let [retained (get @(::host/contexts @started) agent-id)
                    apply-ledger
                    (::instrument/apply-ledger
                     (::instrument/state @started))
                    ledger-before @apply-ledger
                    live-b (sci.ctx-store/with-ctx retained
                             (sci/eval-string*
                              retained
                              (str "(" home-ns "/caller 3)")))]
                (is (= [:b 3 :helper-b] live-b))
                (is (= [:a 3 :helper-a]
                       (:seon.execution/result
                        (invoke-authored! live "pinned-a" database-a caller-sym
                                          caller-digest [3]))))
                (is (= ledger-before @apply-ledger)
                    "ephemeral reconciliation never retains apply-ledger rows")
                (is (= [:b 3 :helper-b]
                       (sci.ctx-store/with-ctx retained
                         (sci/eval-string*
                          retained
                          (str "(" home-ns "/caller 3)"))))
                    "pinned replay leaves the retained/shared B roots intact")))
            (finally
              (try (.close ^SocketChannel (::registry-test/channel live))
                   (catch Throwable _))))))
      (finally
        (when @started (host/stop! @started))
        (context/close-session! session)
        (writer/stop! server)
        (.delete (File. ^String request-path))
        (.delete (File. ^String host-socket))))))
