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
  equals plain SCI's, or `expected` when given. A vector `code` is one pass per
  string, plain SCI running them all in one context. One line adds a row."
  [["add" "(+ 1 2)"]
   ["macro" "(defmacro twice [x] `(do ~x ~x)) (let [a (atom 0)] (twice (swap! a inc)) @a)"]
   ["records" "(defrecord R [x]) (:x (->R 8))"]
   ["alias" "(alias 'cc 'clojure.core) (cc/inc 1)"]
   ["unmap" "(def u 1) (ns-unmap *ns* 'u) (resolve 'u)"]
   ["exception-continues" "(/ 1 0)\n:after"]
   ["later-pass-redefinition-reaches-earlier-callers"
    ["(defn g2 [] 1) (defn h2 [] (g2)) (defn g2 [] 2) [(h2) (g2)]" "(defn g2 [] 3) [(h2) (g2)]"]]
   ["later-pass-redefinition-reaches-a-caller-of-a-later-pass"
    ["(defn g3 [] 1) (defn h3 [] (g3))" "(defn k3 [] (h3))" "(defn g3 [] 2) [(k3) (h3) (g3)]"]]
   ["redefinition-after-a-pass-that-changed-no-program"
    ["(defn g4 [] 1) (defn h4 [] (g4))" ":idle" "(defn g4 [] 2) [(h4) (g4)]"]]])

(defn- plain
  "The value of `code`'s last form that did not throw, in a fresh plain SCI context."
  {:malli/schema [:=> [:cat [:or :string [:vector :string]]] :seon.schema/value]}
  [code]
  (let [ctx (sci/init {})
        reader (sci/reader (if (string? code) code (apply str (interpose "\n" code))))]
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

(deftest ^{:seon/tasks #{'seon.sci.eval/a-caller-keeps-the-definition-of-an-earlier-pass}} plain-sci-rows-answer-as-plain-sci
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [[label code :as row] rows]
       (testing label
         (is (= (if (> (count row) 2) (nth row 2) (plain code))
                (last (mapv #(answered % agent-id running)
                            (if (string? code) [code] code))))))))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-caller-keeps-the-definition-of-an-earlier-pass}}
  a-test-of-an-earlier-pass-runs-the-redefined-callee
  (support/with-running-agent
   (fn [running agent-id _]
     (answered (str "(require '[clojure.test :refer [deftest is]]) (defn sq [x] (* x x)) (defn other [x] (sq x))"
                    " (deftest other-squares (is (= 4 (other 2))))")
               agent-id running)
     (answered "(defn sq [x] (+ x 4)) (deftest other-is-six (is (= 6 (other 2))))" agent-id running)
     (let [namespace-name (str "my.agents." agent-id)
           check (answered (str "(my.test/check {:seon.test/changed '[" namespace-name "/other-squares "
                                namespace-name "/other-is-six]})")
                           agent-id running)
           failures (into {} (map (juxt (comp name :seon.test/sym) :seon.test/fail-count))
                          (:seon.test/results check))]
       (is (= {"other-squares" 1 "other-is-six" 0} failures))))))
