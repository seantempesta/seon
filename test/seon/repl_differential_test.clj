(ns seon.repl-differential-test
  "The REPL against plain SCI: each row sends its exact input through the MCP
  entrance (`seon.mcp/call`, SCI mode) and asserts the value it answers is the
  value plain SCI gives, or the row's stated value where a policy differs."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(def rows
  "`[label code]` or `[label code expected]`: the value of `code`'s last form
  equals plain SCI's, or `expected` when given. One line adds a row."
  [["add" "(+ 1 2)"]
   ["macro" "(defmacro twice [x] `(do ~x ~x)) (let [a (atom 0)] (twice (swap! a inc)) @a)"]
   ["records" "(defrecord R [x]) (:x (->R 8))"]
   ["alias" "(alias 'cc 'clojure.core) (cc/inc 1)"]
   ["unmap" "(def u 1) (ns-unmap *ns* 'u) (resolve 'u)"]
   ["exception-continues" "(/ 1 0)\n:after"]])

(defn- plain
  "The value of `code`'s last form that did not throw, in a fresh plain SCI context."
  {:malli/schema [:=> [:cat :string] :seon.schema/value]}
  [code]
  (let [ctx (sci/init {})
        reader (sci/reader code)]
    (loop [value nil]
      (let [form (sci/parse-next ctx reader)]
        (if (= :sci.core/eof form)
          value
          (recur (try (sci/eval-form ctx form) (catch Throwable _ value))))))))

(defn- answered
  "The value the entrance answers for `code` as `agent-id`: the last evaluation's reply value."
  {:malli/schema [:=> [:cat :string :string :string] :seon.schema/value]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (:seon.repl/value (support/reply (get (last (get result "content")) "text")))))

(deftest plain-sci-rows-answer-as-plain-sci
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [[label code :as row] rows]
       (testing label
         (is (= (if (> (count row) 2) (nth row 2) (plain code))
                (answered code agent-id running))))))))
