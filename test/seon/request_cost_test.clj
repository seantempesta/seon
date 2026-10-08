(ns seon.request-cost-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- answer
  "The parsed MCP result of `code` evaluated as `agent-id` on `running`."
  {:malli/schema [:=> [:cat :string :string :string :string] :map]}
  [running agent-id mode code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"cluster" running "agent" agent-id "mode" mode
                                                         "code" code}}))))

(deftest a-reply-carries-its-request-cost
  (support/with-running-agent
   (fn [running agent-id _]
     (let [ping (answer running agent-id "sci" ":ping")
           {total :seon.cost/ms stages :seon.cost/stages} (support/mcp-request ping)
           form-ms (:seon.repl/ms (support/mcp-reply ping) 0)
           defined (support/mcp-request
                    (answer running agent-id "sci" "(in-ns 'request.cost) (defn twice [x] (* 2 x))"))]
       (is (= :ping (:seon.repl/value (support/mcp-reply ping))))
       (is (pos? total) "a ping costs its real wall time, never 0")
       (is (<= (reduce + (vals stages)) (+ total 0.5)) "stages are disjoint parts of the request")
       (is (<= form-ms total))
       (is (every? #(contains? stages %) [:custody :fork :read :evaluate :settle :print]))
       (is (every? #(contains? (:seon.cost/stages defined) %) [:settle :test-step]))))))

(deftest a-jvm-reply-carries-its-request-cost
  (support/with-running-agent
   (fn [running agent-id _]
     (let [{total :seon.cost/ms stages :seon.cost/stages} (support/mcp-request (answer running agent-id "jvm" "(+ 1 2)"))]
       (is (pos? total))
       (is (<= (reduce + (vals stages)) (+ total 0.5)))
       (is (every? #(contains? stages %) [:custody :evaluate :settle :print]))))))
