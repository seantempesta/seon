(ns seon.branch-schema-check-test
  "A schema an agent registered on its branch reaches the test world that
  `my.test/check` builds from that branch."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test :as test]
            [seon.test-support :as support]))

(defn- answered
  "The reply value of `code` through the MCP entrance as `agent-id`."
  {:malli/schema [:=> [:cat :string :string :string] :seon.schema/value]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (:seon.repl/value (support/mcp-reply result))))

(deftest a-branch-registered-schema-contracting-a-function-checks-green
  (support/with-running-agent
   (fn [running agent-id _]
     (let [ns-name (str "my.agents." agent-id)
           in-ns (str "(in-ns '" ns-name ") (clojure.core/refer-clojure) (require '[clojure.test :refer [deftest is]]) ")]
       (answered "(seon.schema/register! :my.fbs/n :int)" agent-id running)
       (answered "(seon.schema/register! :my.fbs/thing [:map :my.fbs/n])" agent-id running)
       (answered (str in-ns "(defn f {:malli/schema [:=> [:cat :my.fbs/thing] :int]} [m] (:my.fbs/n m))") agent-id running)
       (answered "(deftest f-test (is (= 1 (f {:my.fbs/n 1}))))" agent-id running)
       (let [check (answered (str "(my.test/check {:seon.test/changed '[" ns-name "/f " ns-name "/f-test]})")
                             agent-id running)]
         (is (nil? (:seon.error/message check)) "the check answers a run, not an error")
         (is (true? (:seon.test/passed? check))))))))

(deftest a-request-that-throws-answers-an-unknown-error-with-its-cause
  ;; A message-less exception included: default's recorded error 7ea039b9635f was the
  ;; entrant's own diagnostic refusing the nil message it was handed.
  (support/with-running-agent
   (fn [_running _agent-id connection]
     (doseq [[thrown class-name] [[(ex-info "injected" {}) "clojure.lang.ExceptionInfo"]
                                  [(IllegalStateException.) "java.lang.IllegalStateException"]]]
       (let [result (test/run {:seon.test/execution (support/execution-handle connection)
                               :seon.test/recording-connection connection
                               :seon.test/policy :named
                               :seon.test/identities #{'seon.competency-test/t1-area-is-contracted}
                               :seon.db/writer (fn [_connection _transaction] (throw thrown))})]
         (is (some? (:seon.test/unknown result)) "an unknown error, not a throw")
         (is (= class-name (-> result :seon.error/chain first :seon.error/throwable-class)) "the cause is kept")
         (is (= (ex-message thrown) (-> result :seon.error/chain first :seon.error/message))))))))

(deftest a-failing-check-reply-carries-no-runner-report-in-out
  (support/with-running-agent
   (fn [running agent-id _]
     (let [ns-name (str "my.agents." agent-id)]
       (answered (str "(in-ns '" ns-name ") (clojure.core/refer-clojure) (require '[clojure.test :refer [deftest is]]) (defn g [] 1)") agent-id running)
       (answered "(deftest g-test (is (= 2 (g))))" agent-id running)
       (let [reply (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                             "arguments" {"code" (str "(my.test/check {:seon.test/changed '[" ns-name "/g " ns-name "/g-test]})")
                                                                          "agent" agent-id "cluster" running}})))
             check (support/mcp-reply reply)]
         (is (false? (:seon.test/passed? (:seon.repl/value check))) "the test failed")
         (is (nil? (:seon.repl/out check)) "stored failure reports are the record, not the echo"))))))
