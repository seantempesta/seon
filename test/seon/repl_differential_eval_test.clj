(ns seon.repl-differential-eval-test
  "Definitions made through `eval` have no source position; the REPL still
  answers the evaluated value, as plain SCI does."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(def rows
  "`[label passes]`: the last pass's value equals plain SCI's over the same passes."
  [["eval-def" ["(eval (list 'def 'ev1 1))" "ev1"]]
   ["eval-defn" ["(eval '(defn ev2 [x] (inc x)))" "(ev2 1)"]]
   ["lazy-eval-defs" ["(doall (map #(eval (list 'def (symbol (str \"lz\" %)) %)) (range 3)))" "[lz0 lz1 lz2]"]]])

(defn- plain
  "The value of the last pass in one fresh plain SCI context."
  {:malli/schema [:=> [:cat [:vector :string]] :seon.schema/value]}
  [passes]
  (let [ctx (sci/init {})]
    (last (mapv #(sci/eval-string* ctx %) passes))))

(defn- answered
  "The value the entrance answers for `code` as `agent-id`."
  {:malli/schema [:=> [:cat :string :string :string] :seon.schema/value]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (:seon.repl/value (support/mcp-reply result))))

(deftest a-definition-made-through-eval-answers-as-plain-sci
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [[label passes] rows]
       (testing label
         (is (= (plain passes) (last (mapv #(answered % agent-id running) passes)))))))))
