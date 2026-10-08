(ns my.test-check-test
  "`my.test/check` through the MCP entrance."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.id :as id]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- shown
  "Each shown text the eval `code` answers as `agent-id` through the MCP entrance, read back as data."
  {:malli/schema [:=> [:cat :string :string :string] [:vector :seon.schema/value]]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (mapv #(support/reply (get % "text")) (get result "content"))))

(defn- answered
  "The value of the last evaluation `code` answers as `agent-id` through the MCP entrance; the test
  step's summary after it is no evaluation."
  {:malli/schema [:=> [:cat :string :string :string] :seon.schema/value]}
  [code agent-id running]
  (:seon.repl/value (last (filter #(and (map? %) (contains? % :seon.repl/value)) (shown code agent-id running)))))

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

(deftest ^{:seon/tasks #{'my.test/check-in-the-defining-pass-says-the-test-does-not-exist}}
  a-check-naming-a-definition-of-its-own-request-runs-it-after-recording
  (support/with-running-agent
   (fn [running agent-id _]
     (let [namespace-name (str "my.after" (id/id))
           f (symbol namespace-name "f")
           replies (shown (str "(ns " namespace-name ")"
                               " (defn f [x] (inc x))"
                               " (clojure.test/deftest f-test (clojure.test/is (= 3 (f 1))))"
                               " (my.test/check {:seon.test/changed '[" f "]})")
                          agent-id running)
           tests (some :seon.turn.loop/tests replies)]
       (is (= {:my.test/after-recording [f]} (:seon.repl/value (nth replies 3))))
       (is (= [(symbol namespace-name "f-test")] (get-in tests [:seon.turn.loop/reaching :seon.turn.loop/red]))
           "the test step ran the reaching test after recording, in the same reply")))))
