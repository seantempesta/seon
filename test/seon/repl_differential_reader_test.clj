(ns seon.repl-differential-reader-test
  "Reader rows through the MCP entrance (`seon.mcp/call`, SCI mode) against
  plain SCI: every form's value, not only the last, so a form the reader
  should consume is never evaluated. Reply-mode rows run the same entrance's
  pass with the model reply's segmenter (`seon.turn/planned-sources`)."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.operator.runtime :as runtime]
            [seon.test-support :as support]))

(defn- plain-values
  "Every form's value of the last of `codes`, all run in order in one fresh plain SCI context."
  {:malli/schema [:=> [:cat [:vector :string]] [:vector :seon.schema/value]]}
  [codes]
  (let [ctx (sci/init {})]
    (last (for [code codes]
            (let [reader (sci/reader code)]
              (loop [values []]
                (let [form (sci/parse-next ctx reader)]
                  (if (= :sci.core/eof form)
                    values
                    (recur (conj values (sci/eval-form ctx form)))))))))))

(defn- replies
  "Each evaluation's reply of `code` through the MCP entrance."
  {:malli/schema [:=> [:cat :string :string :string] [:vector :map]]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (support/mcp-replies result)))

(defn- settled
  "The settled answer of `text` run by the MCP entrance's pass as `agent-id`, read by `segmenter`."
  {:malli/schema [:=> [:cat :string :string :string :qualified-symbol] :map]}
  [text agent-id running segmenter]
  (let [found (#'mcp/instance @runtime/running-instances running)]
    (agent/evaluate-source! (assoc (#'mcp/execution found agent-id nil)
                                   :seon.agent/segmenter segmenter :seon.cluster.reply/text text))))

(defn- stored
  "The source and repairs of each evaluation of the run `answer` settled, in order, as read back."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{database :seon.db/db run-id :seon.turn/id}]
  (->> (db/q '[:find [(pull ?e [:seon.cluster.eval/ordinal :seon.cluster.eval/source :seon.cluster.eval/delimiter-repairs]) ...]
               :in $ ?run-id
               :where [?r :seon.turn/id ?run-id] [?e :seon.cluster.eval/run ?r]]
             database run-id)
       (sort-by :seon.cluster.eval/ordinal)
       (mapv #(dissoc % :seon.cluster.eval/ordinal))))

(deftest a-nested-discard-answers-as-plain-sci
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [codes [["#_ #_ 1 2 3"]
                    ["#_ #_ (+ 1 1) #_ (+ 2 2) (+ 3 3) 4"]
                    ["'#_ a b"]
                    ["(def hits (atom 0))" "#_ #_ (swap! hits inc) (swap! hits inc) @hits"]]]
       (testing (peek codes)
         (is (= (plain-values codes)
                (last (mapv #(mapv :seon.repl/value (replies % agent-id running)) codes)))))))))

(deftest true-false-and-nil-lines-are-code-in-a-reply
  (support/with-running-agent
   (fn [running agent-id _]
     (let [text "true\nfalse\nnil\n42"]
       (is (= ["true" "false" "nil" "42"]
              (mapv :seon.cluster.eval/source (stored (settled text agent-id running 'seon.turn/planned-sources)))))
       (is (= (plain-values [text]) (mapv :seon.repl/value (replies text agent-id running))))))))

(deftest a-repair-rides-with-the-form-it-repaired-on-a-shared-line
  (support/with-running-agent
   (fn [running agent-id _]
     (is (= [[2 [[1 :dropped ")"]]] [3 nil]]
            (mapv (juxt :seon.repl/value :seon.repl/repairs) (replies "(inc 1)) (inc 2)" agent-id running)))))))

(deftest a-stored-repair-reads-back-as-data
  (support/with-running-agent
   (fn [running agent-id _]
     (is (= [{:seon.cluster.eval/source "(inc 1)"
              :seon.cluster.eval/delimiter-repairs [[1 :dropped ")"] [1 :dropped ")"]]}
             {:seon.cluster.eval/source "(inc 2)"}
             {:seon.cluster.eval/source "(vector 1 (inc 2))"
              :seon.cluster.eval/delimiter-repairs [[2 :inserted "))"] [2 :dropped "]"]]}]
            (stored (settled "(inc 1))) (inc 2)\n(vector 1 (inc 2]" agent-id running 'seon.turn/code-sources)))))))
