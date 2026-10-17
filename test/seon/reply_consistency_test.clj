(ns seon.reply-consistency-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- answer
  "The parsed MCP result of SCI `code` evaluated as `agent-id` on `running`."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [running agent-id code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"cluster" running "agent" agent-id "mode" "sci"
                                                         "code" code}}))))

(deftest a-defn-value-names-no-test-status-the-step-derives
  (support/with-running-agent
   (fn [running agent-id _]
     (let [reply (answer running agent-id
                         (str "(in-ns 'reply.consistency) "
                              "(defn tw {:malli/schema [:=> [:cat :int] :int]} [x] (* 2 x)) "
                              "(clojure.test/deftest tw-test (clojure.test/is (= 4 (tw 2)))) "
                              "(defn tw {:malli/schema [:=> [:cat :int] :int]} [x] (* 5 x))"))
           values (keep #(:seon.repl/value (second %)) (#'support/mcp-parsed reply))
           defined (filter #(= 'reply.consistency/tw (:seon.render.var/symbol %)) values)]
       (is (seq defined))
       (is (not-any? #(some % [:seon.render.var/pending :seon.render.var/newly-red :seon.render.var/newly-green]) defined)
           "the reply's test step is the one status of the request's tests")
       (is (= ['reply.consistency/tw-test] (get-in (support/mcp-tests reply) [:seon.turn.loop/reaching :seon.turn.loop/red])))))))
