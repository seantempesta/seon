(ns seon.repl-differential-p4-test
  "P4 rows: an arming failure keeps the function's root, with the diagnostic beside the value."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- reply
  "The reply map of one `eval_clj` of `code` as `agent-id`."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (support/reply (get (last (get result "content")) "text"))))

(deftest a-malformed-contract-leaves-the-function-callable
  (support/with-running-agent
   (fn [running agent-id _]
     (is (seq (:seon.repl/diagnostics (reply "(defn p4f {:malli/schema [:bogus]} [x] x)" agent-id running)))
         "the diagnostic arrives beside the value")
     (is (= 1 (:seon.repl/value (reply "(p4f 1)" agent-id running)))))))

(deftest a-valid-contract-refuses-a-violating-result
  (support/with-running-agent
   (fn [running agent-id _]
     (reply "(defn p4g {:malli/schema [:=> [:cat :int] :int]} [x] (str x))" agent-id running)
     (is (not= "1" (:seon.repl/value (reply "(p4g 1)" agent-id running)))))))
