(ns seon.test-cost-test
  "A test member's stored result carries what its body cost: its armed calls,
  its body thread's allocation, its run time and its most-called definitions."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.batch :as batch]
            [seon.mcp :as mcp]
            [seon.test :as test]
            [seon.test-support :as support]))

(defn counted-member-call
  "The value the nested member calls through its armed boundary."
  {:malli/schema [:=> [:cat :int] :int]}
  [n]
  (inc n))

(deftest a-counted-member
  (is (= 17 (reduce (fn [n _] (counted-member-call n)) 0 (range 17)))))

(deftest a-member-keeps-its-own-calls-and-adds-them-to-its-parent
  (let [sink (batch/sink)
        execution (support/execution-handle nil)
        result (binding [batch/*world* (assoc batch/*world* :seon.batch/sink sink)]
                 (test/run {:seon.test/execution execution
                            :seon.test/recording-connection (:seon.db/connection execution)
                            :seon.test/policy :named
                            :seon.test/identities #{'seon.test-cost-test/a-counted-member}}))]
    (is (true? (:seon.test/passed? result)))
    (is (= 1 (:seon.test/executed-count result)))
    (is (= 17 (get (batch/calls sink) 'seon.test-cost-test/counted-member-call)))
    (is (<= 17 (:seon.test.member/calls (first (:seon.test/results result)))))))

(defn- answered
  "The reply value of `code` through the MCP entrance as `agent-id`."
  {:malli/schema [:=> [:cat :string :string :string] :seon.schema/value]}
  [code agent-id running]
  (let [result (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                                         "arguments" {"code" code "agent" agent-id "cluster" running}})))]
    (:seon.repl/value (support/mcp-reply result))))

(deftest a-member-records-its-calls-allocation-and-time
  (support/with-running-agent
   (fn [running agent-id _]
     (let [nsn (str "my.agents." agent-id)
           test-symbol (symbol nsn "spend-test")
           step (symbol nsn "step")
           in-ns-forms (str "(in-ns '" nsn ") (clojure.core/refer-clojure) (require '[clojure.test :refer [deftest is]]) ")
           check (str "(my.test/check {:seon.test/changed '[" nsn "/spend " test-symbol "]})")
           stored (str "(seon.db/q '[:find [(pull ?m [:seon.test.member/calls :seon.test.member/alloc-bytes"
                       " :seon.test.member/run-ms :seon.test.member/top]) ...]"
                       " :in $ ?s :where [?m :seon.test.member/symbol ?s] [?m :seon.test.member/completed-tx]] '"
                       test-symbol ")")]
       (answered (str in-ns-forms "(defn step {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))") agent-id running)
       ;; 50 armed calls and an 8 MB array on the body thread
       (answered "(defn spend {:malli/schema [:=> [:cat] :int]} [] (alength (long-array 1000000)) (reduce (fn [n _] (step n)) 0 (range 50)))"
                 agent-id running)
       (answered "(deftest spend-test (is (= 50 (spend))))" agent-id running)
       (is (true? (:seon.test/passed? (answered check agent-id running))) "the member ran green")
       (let [answer (answered stored agent-id running)
             members (when (sequential? answer) answer)
             member (first members)]
         (is (sequential? answer) "the cost attributes are declared and readable")
         (is (= 1 (count members)) "the check stored one member")
         (is (<= 50 (:seon.test.member/calls member)) "the body's own armed calls")
         (is (<= 8000000 (:seon.test.member/alloc-bytes member)) "the body thread's allocation")
         (is (double? (:seon.test.member/run-ms member)))
         (is (some #(= [step 50] (subvec (vec %) 0 2)) (:seon.test.member/top member))
             "the most-called definition with its calls"))))))
