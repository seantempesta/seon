(ns seon.host-graduate-writer-test
  "Graduation proof over one real recorded corpus function (U3)."
  (:require [clojure.test :refer [deftest is]]
            [sci.core :as sci]
            [seon.content-hash :as content-hash]
            [seon.db.host :as db.host]
            [seon.db.id :as db.id]
            [seon.db.protocol :as protocol]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer]
            [seon.host :as host]
            [seon.host.context :as context]
            [seon.host.graduate :as graduate]
            [seon.host-registry-writer-test :as registry-test])
  (:import [java.io File]
           [java.nio.channels SocketChannel]))

(def ^:private value-sampling-policy
  (var-get #'registry-test/value-sampling-policy))
(def ^:private value-sampling-policy-query
  (var-get #'registry-test/value-sampling-policy-query))
(def ^:private dependencies
  (var-get #'registry-test/dependencies))
(def ^:private host-session!
  (var-get #'registry-test/host-session!))
(def ^:private invoke-batch!
  (var-get #'registry-test/invoke-batch!))
(def ^:private socket-path
  (var-get #'registry-test/socket-path))

(def ^:private agent-candidates
  (db.id/candidate-manifest
   {:seon.agent/id :seon.db.id.generator/human-readable
    :seon.agent.turn/id :seon.db.id.generator/compact}
   [{:seon.db.id/key :fixture/agent
     :seon.db.id/identity-attr :seon.agent/id}
    {:seon.db.id/key :fixture/turn
     :seon.db.id/identity-attr :seon.agent.turn/id}]))
(def ^:private agent-id
  (:seon.db.id/value (first agent-candidates)))
(def ^:private turn-id
  (:seon.db.id/value (second agent-candidates)))
(def ^:private home-ns
  (symbol (str "my.agent." agent-id)))

(def ^:private source-v1
  (str "(defn sum-squares\n"
       "  \"Sum every square from zero through n.\"\n"
       "  {:malli/schema [:=> [:cat [:int {:min 0}]] :int]\n"
       "   :test (fn [] (assert (= 55 (sum-squares 5))))}\n"
       "  [n]\n"
       "  (reduce + (map #(* % %) (range (inc n)))))"))

(def ^:private source-v2
  (str "(defn sum-squares\n"
       "  \"Sum every square from zero through n, then add one.\"\n"
       "  {:malli/schema [:=> [:cat [:int {:min 0}]] :int]\n"
       "   :test (fn [] (assert (= 56 (sum-squares 5))))}\n"
       "  [n]\n"
       "  (inc (reduce + (map #(* % %) (range (inc n))))))"))

(defn- query-one-function [session sym]
  (ffirst
   (context/query-writer!
    session
    '[:find (pull ?fn [:seon.fn/sym
                       :seon.fn/source
                       :seon.fn/source-fingerprint
                       :seon.fn/execution-tier
                       :seon.fn/spec
                       :seon.fn/schema-error
                       :seon.fn/arglists
                       :seon.fn/doc])
      :in $ ?sym
      :where
      [?fn :seon.fn/sym ?sym]]
    [sym])))

(defn- invoke-source! [live agent-id invocation-id head source]
  (invoke-batch!
   live agent-id turn-id invocation-id head
   [{:seon.repl/kind :form :seon.repl/source source}]))

(defn- caller-value [ctx]
  (sci/eval-string*
   ctx
   (str "(require '[" home-ns " :as graduated])"
        " (graduated/sum-squares 5)")))

(deftest trust-gate-is-a-pure-conjunction-over-recorded-facts
  (let [source "(defn answer [] 42)"
        fingerprint (graduate/fingerprint source)
        green {::graduate/schema-valid? true
               ::graduate/test-covered? true
               ::graduate/source source
               ::graduate/fingerprint fingerprint
               ::graduate/recorded-fingerprint fingerprint
               ::graduate/nursery-test
               {::graduate/ok? true ::graduate/result-edn "nil"}
               ::graduate/compiled-test
               {::graduate/ok? true ::graduate/result-edn "nil"}}]
    (is (graduate/trust-gate? green))
    (is (not (graduate/trust-gate?
              (assoc green ::graduate/schema-valid? false))))
    (is (not (graduate/trust-gate?
              (assoc green ::graduate/test-covered? false))))
    (is (not (graduate/trust-gate?
              (assoc-in green [::graduate/compiled-test
                               ::graduate/result-edn]
                        "different"))))
    (is (not (graduate/trust-gate?
              (assoc green ::graduate/recorded-fingerprint
                     (graduate/fingerprint (str source " "))))))))

(deftest native-graduation-refuses-and-legacy-rows-rebuild-in-the-nursery
  (let [database-name (str "host-u3-" (random-uuid))
        request-path (socket-path "u3-writer")
        host-socket (socket-path "u3-host")
        fn-sym (str home-ns "/sum-squares")
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
      (let [seeded
            (writer-test/seed-canonical-schema!
             session database-name
             [value-sampling-policy
              {:seon.user/id "user"}
              {:seon.db.process/id :seon.db.process/repl}])]
        (is (true? (::protocol/success? seeded)) (pr-str seeded)))
      (let [database (db.host/resolve-db! session nil false)
            allocated
            (db.host/call!
             session
             (protocol/transaction-request
              {::protocol/request-id (str (random-uuid))
               :seon.db/db database
               ::protocol/transaction-data
               [{:seon.agent/id agent-id}
                {:seon.agent.turn/id turn-id}]
               ::protocol/generated-candidates agent-candidates}))]
        (is (true? (::protocol/success? allocated)) (pr-str allocated)))
      (is (= [32 4096 1024 3 80 8 12]
             (context/query-writer! session value-sampling-policy-query
                                    ["cluster"])))
      ;; Install provenance and exact-set optional attrs before U4 recording.
      (let [probe
            (sci/eval-string*
             (context/fork-context (context/build-base! session))
             (str "(require 'seon.db)"
                  "(seon.db/transact! {:seon.db/tx-data "
                  (pr-str
                   [{:seon.db/user [:seon.agent/id agent-id]
                     :seon.db/process
                     [:seon.db.process/id :seon.db.process/repl]}
                    {:seon.fn/sym "seed/install-probe"
                     :seon.fn/spec "[:=> [:cat :int] :int]"
                     :seon.fn/schema-error "none"
                     :seon.fn/read-attrs [:seed/attr]}])
                  "})"))]
        (is (map? (:db-after probe)) (pr-str probe)))
      (reset! started
              (host/start! {::host/socket-path host-socket
                            ::context/writer-socket-path request-path
                            ::context/database-name database-name
                            ::context/backend :memory}))
      (let [live (host-session! host-socket agent-id database-name)]
        (try
          (let [defined (invoke-source! live agent-id "u3-define-v1"
                                        (context/resolve-head! session)
                                        source-v1)
                row-v1 (query-one-function session fn-sym)
                base (::host/base @started)
                registry (::context/registry base)
                author-ctx (get @(::host/contexts @started) agent-id)
                caller-ctx (context/fork-context base)
                nursery
                (graduate/install-nursery!
                 {::context/base base
                  ::context/registry registry
                  ::graduate/function-row row-v1
                  ::graduate/contexts [author-ctx caller-ctx]})]
            (is (map? row-v1) (pr-str row-v1))
            (is (= :seon.execution.message/result
                   (:seon.execution/message defined)) (pr-str defined))
            (is (= 1 (get-in defined [:seon.execution/result
                                      :seon.eval/n-ok]))
                (pr-str defined))
            (is (= 1 (count (get-in defined [:seon.execution/result
                                              :seon.eval/ids])))
                (pr-str defined))
            (is (= source-v1 (:seon.fn/source row-v1)))
            (is (= (content-hash/sha-256 source-v1)
                   (:seon.fn/source-fingerprint row-v1)))
            (is (= :nursery (:seon.fn/execution-tier row-v1)))
            (is (::graduate/ok? nursery) (pr-str nursery))
            (is (= 55 (caller-value caller-ctx)))
            (let [refused
                  (graduate/graduate!
                   {::context/base base
                    ::context/registry registry
                    ::context/writer session
                    ::graduate/function-row row-v1
                    ::graduate/contexts [author-ctx caller-ctx]})
                  row-after-refusal (query-one-function session fn-sym)]
              (is (= :core-bug (:seon.error/kind refused))
                  (pr-str refused))
              (is (= :R48
                     (get-in refused
                             [:seon.error/data
                              :seon.host.graduate/ruling])))
              (is (= :P4
                     (get-in refused
                             [:seon.error/data
                              :seon.host.graduate/reopen-when])))
              (is (re-find #"R48.*P4"
                           (:seon.error/message refused)))
              (is (= :nursery
                     (:seon.fn/execution-tier row-after-refusal)))
              (is (= 55 (caller-value caller-ctx))
                  "refusal leaves the interpreted registry root installed")
              (let [edited (invoke-source! live agent-id "u3-define-v2"
                                           (context/resolve-head! session)
                                           source-v2)
                    row-v2 (query-one-function session fn-sym)]
                (is (= :seon.execution.message/result
                       (:seon.execution/message edited)) (pr-str edited))
                (is (not= (:seon.fn/source-fingerprint row-v1)
                          (:seon.fn/source-fingerprint row-v2)))
                (is (= :nursery (:seon.fn/execution-tier row-v2)))
                (is (= :nursery (graduate/effective-tier row-v2)))
                (is (= 56 (caller-value caller-ctx))
                    "the linked registry var falls back on source eval")
                (let [refused-again
                      (graduate/graduate!
                       {::context/base base
                        ::context/registry registry
                        ::context/writer session
                        ::graduate/function-row row-v2
                        ::graduate/contexts [author-ctx caller-ctx]})]
                  (is (= :R48
                         (get-in refused-again
                                 [:seon.error/data
                                  :seon.host.graduate/ruling])))
                  (is (= 56 (caller-value caller-ctx))))
                ;; Simulate a row graduated by the retired tests-pass gate.
                ;; Restart must ignore that stale execution-tier fact.
                (let [legacy
                      (context/transact-writer!
                       session
                       [{:seon.fn/sym fn-sym
                         :seon.fn/source-fingerprint
                         (:seon.fn/source-fingerprint row-v2)
                         :seon.fn/execution-tier :graduated}])
                      legacy-row (query-one-function session fn-sym)]
                  (is (:seon.db/ok? legacy) (pr-str legacy))
                  (is (= :graduated
                         (:seon.fn/execution-tier legacy-row)))
                  (is (= :nursery
                         (graduate/effective-tier legacy-row)))))))
          (finally
            (try (.close ^SocketChannel (::registry-test/channel live))
                 (catch Throwable _)))))
      ;; A matching legacy :graduated fact cannot restore native code.
      (host/stop! @started)
      (reset! started
              (host/start! {::host/socket-path host-socket
                            ::context/writer-socket-path request-path
                            ::context/database-name database-name
                            ::context/backend :memory}))
      (let [report (::host/graduation-report @started)
            live (host-session! host-socket agent-id database-name)]
        (try
          (is (= 0 (::graduate/graduated report)) (pr-str report))
          (is (= 1 (::graduate/nursery report)) (pr-str report))
          (let [response
                (invoke-batch!
                 live agent-id turn-id "u3-after-restart"
                 (context/resolve-head! session)
                 [{:seon.repl/kind :form
                   :seon.repl/source "(sum-squares 5)"}])]
            (is (= 56 (get-in response [:seon.execution/result
                                        :seon.host/results 0
                                        :seon.eval/value]))
                (pr-str response)))
          (finally
            (try (.close ^SocketChannel (::registry-test/channel live))
                 (catch Throwable _)))))
      (finally
        (when @started (try (host/stop! @started) (catch Throwable _)))
        (context/close-session! session)
        (writer/stop! server)
        (.delete (File. ^String request-path))
        (.delete (File. ^String host-socket))))))
