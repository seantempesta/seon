(ns my.test-check-test
  "`my.test/check` through the MCP entrance."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- answered
  "The value of the last evaluation `code` answers as `agent-id` through the MCP entrance."
  {:malli/schema [:=> [:cat :string :string :string] :seon.schema/value]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (:seon.repl/value (support/reply (get (last (get result "content")) "text")))))

(deftest ^{:seon/tasks #{'my.test/a-check-no-test-reaches-reads-as-a-failure}}
  a-check-over-a-change-no-test-reaches-names-it-and-gives-no-verdict
  (support/with-running-agent
   (fn [running agent-id _]
     (let [sym (symbol (str "my.agents." agent-id) "unreached-fn")
           _ (answered "(defn unreached-fn [] 1)" agent-id running)
           check (answered (str "(my.test/check {:seon.test/changed '[" sym "]})") agent-id running)]
       (is (= [sym] (:seon.test/unreached check)))
       (is (= [] (:seon.test/results check)))
       (is (not (contains? check :seon.test/passed?)))))))
