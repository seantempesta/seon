(ns seon.repl-differential-p3-test
  "P3 rows: the namespace an evaluation ends in is kept even when its form throws."
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
    (support/mcp-reply result)))

(deftest in-ns-before-a-throw-is-kept
  (support/with-running-agent
   (fn [running agent-id _]
     (reply "(do (in-ns 'p3.kept) (throw (ex-info \"x\" {})))" agent-id running)
     (is (= 'p3.kept (:seon.repl/value (reply "(ns-name *ns*)" agent-id running)))))))
