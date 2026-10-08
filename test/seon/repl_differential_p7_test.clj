(ns seon.repl-differential-p7-test
  "P7a rows: a repair note reaches the agent beside the value of the form
  whose lines it repaired."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(def rows
  "`[label code value repairs]`: the last form's value and the repairs its reply carries."
  [["a-dropped-closer-is-noted-beside-its-form" "(+ 1 2))" 3 [[1 :dropped ")"]]]
   ["an-unclosed-defn-notes-its-inserted-closer" "(defn p7f [x]\n  (+ x 1)\n(p7f 1)" 2 [[2 :inserted ")"]]]
   ["balanced-code-carries-no-repairs" "(+ 1 2)" 3 nil]])

(defn- replies
  "The reply of each form of `code` through the MCP entrance."
  {:malli/schema [:=> [:cat :string :string :string] [:vector :map]]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (support/mcp-replies result)))

(deftest repairs-ride-beside-the-form-they-repaired
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [[label code value repairs] rows]
       (testing label
         (let [replies (replies code agent-id running)]
           (is (= value (:seon.repl/value (peek replies))))
           (is (= repairs (some :seon.repl/repairs replies)))))))))
