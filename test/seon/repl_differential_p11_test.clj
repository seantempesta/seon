(ns seon.repl-differential-p11-test
  "P11 rows: after a `set!` of `*print-length*`, ordinary evaluations cost what they did before."
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

(defn- median-ms
  "The median wall milliseconds of `n` runs of `code`."
  {:malli/schema [:=> [:cat :string :string :string :int] :double]}
  [code agent-id running n]
  (let [times (vec (sort (for [_ (range n)]
                           (let [start (System/nanoTime)]
                             (reply code agent-id running)
                             (/ (- (System/nanoTime) start) 1e6)))))]
    (nth times (quot n 2))))

(deftest ^{:seon/tasks #{'seon.sci.eval/a-set-print-length-taxes-every-later-run}} a-set-print-length-costs-nothing-later
  (support/with-running-agent
   (fn [running agent-id _]
     (median-ms "(+ 1 1)" agent-id running 5)
     (let [floor (median-ms "(+ 1 1)" agent-id running 15)]
       (reply "(set! *print-length* 50)" agent-id running)
       (is (<= (median-ms "(+ 1 1)" agent-id running 15) (max (* 1.2 floor) (+ floor 50))))))))
