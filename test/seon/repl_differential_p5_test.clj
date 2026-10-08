(ns seon.repl-differential-p5-test
  "P5 rows: printed output answers through the value printer's budget, with a dig form that reads the rest."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.ai.tokens :as tokens]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- reply-text
  "The reply text of one `eval_clj` of `code` as `agent-id`."
  {:malli/schema [:=> [:cat :string :string :string] :string]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (get (last (get result "content")) "text")))

(def ^:private lines 20000)

(deftest bulk-output-answers-within-the-budget
  (support/with-running-agent
   (fn [running agent-id _]
     (let [text (reply-text (str "(dotimes [i " lines "] (println i))") agent-id running)
           dig (re-find #"\(subs \(:seon\.cluster\.eval/output \(seon\.db/pull [^\n]*?\]\)\) (\d+)\)" text)
           total (reduce + (map #(inc (count (str %))) (range lines)))]
       (is (< (tokens/estimate text) 2000))
       (is (some? dig))
       (when dig
         (let [rest-count (reply-text (str "(count " (first dig) ")") agent-id running)]
           (is (= (- total (parse-long (second dig)))
                  (:seon.repl/value (support/reply rest-count))))))))))
