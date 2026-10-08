(ns my.test-check-test
  "`my.test/check` through the MCP entrance."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]
            [seon.db :as db]
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

(defn- edited-defn
  "`source`, a `(defn name doc attr-map [args] body)` form, with docstring `doc`,
  and with a `nil` form before its body when `body?`."
  {:malli/schema [:=> [:cat :string :string :boolean] :string]}
  [source doc body?]
  (let [form (parser/parse-string source)
        children (vec (node/children form))
        at (into [] (remove #(node/whitespace-or-comment? (nth children %))) (range (count children)))
        children (assoc children (at 2) (node/string-node doc))
        args (inc (at 4))]
    (node/string (node/replace-children form (cond-> children
                                               body? (#(into (conj (subvec % 0 args) (node/whitespace-node " ") (node/token-node nil))
                                                             (subvec % args))))))))

(deftest ^{:seon/tasks #{'seon.turn.batch/a-docstring-defn-allocates-half-a-gigabyte}}
  a-docstring-only-defn-reruns-no-test-and-a-body-change-reruns-its-reaching-tests
  (support/with-running-agent
   (fn [running agent-id connection]
     (let [source (:seon.fn/source (db/pull (db/db connection) [:seon.fn/source] [:seon.fn/sym 'seon.task/steps-call]))
           pass (fn [code]
                  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                            "arguments" {"code" (str "(in-ns 'seon.task) " code)
                                                                         "agent" agent-id "cluster" running}}))))
           defined? #(= 'seon.task/steps-call (get-in (support/mcp-reply %) [:seon.repl/value :seon.render.var/symbol]))
           doc-only (pass (edited-defn source "Only the docstring changed." false))
           body (pass (edited-defn source "Only the docstring changed." true))]
       (is (defined? doc-only))
       (is (nil? (support/mcp-tests doc-only)) "a docstring-only change runs no test")
       (is (defined? body))
       (is (contains? (support/mcp-tests body) :seon.turn.loop/reaching)
           "a body change runs the tests reaching it")))))
