(ns seon.branch-growth-cost-test
  "An agent's unchanged eval request costs what one form costs, whatever its branch authored before it: the
  calls and reads of a `nil` request are equal after one and after five authoring passes on the same branch
  (docs/seon/issues/an-agents-request-cost-grows-with-every-namespace-its-branch-authored.md)."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.cost-support :as cost-support]
            [seon.mcp :as mcp]
            [seon.test-support :as support]))

(defn- answer
  "The parsed MCP result of `code` evaluated in SCI as `agent-id` on `running`."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [running agent-id code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"cluster" running "agent" agent-id "mode" "sci" "code" code}}))))

(defn- unchanged-request-cost
  "The calls and reads of one `nil` request of `agent-id`, after two that settle what the previous request left."
  {:malli/schema [:=> [:cat :string :string] [:map [:calls :int] [:reads :int]]]}
  [running agent-id]
  (dotimes [_ 2] (answer running agent-id "nil"))
  (let [cost (cost-support/bound-cost running agent-id (answer running agent-id "nil"))]
    {:calls (:seon.cost/calls cost) :reads (:seon.cost/reads cost)}))

(defn- author!
  "One authoring pass `n` of `agent-id`: a namespace of two defs and a namespace holding a contracted function and
  the test reaching it, the program a live agent adds turn by turn."
  {:malli/schema [:=> [:cat :string :string :int] :nil]}
  [running agent-id n]
  (answer running agent-id (str "(in-ns 'growth.defs-" n ") (def a 1) (def b 2)"))
  (answer running agent-id (str "(ns growth.fn-" n " (:require [clojure.test :refer [deftest is]]))\n"
                                "(defn tiny \"One more.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"
                                "(deftest tiny-test (is (= 2 (tiny 1))))"))
  nil)

(defn- within
  "Whether `after` is within `share` of `before`."
  {:malli/schema [:=> [:cat :int :int number?] :boolean]}
  [before after share]
  (<= (abs (- after before)) (* share before)))

(deftest ^{:seon.test/long "Five authoring passes of one running agent, each a namespace of defs and a function with its reaching test run, and eight unchanged requests measured around them."
           :seon.test/long-ms 60000}
  an-unchanged-request-costs-the-same-after-one-and-five-authoring-passes
  (support/with-running-agent
   (fn [running agent-id _connection]
     (author! running agent-id 1)
     (let [one (unchanged-request-cost running agent-id)
           _ (doseq [n (range 2 6)] (author! running agent-id n))
           five (unchanged-request-cost running agent-id)]
       (is (within (:calls one) (:calls five) 0.02) "the calls of an unchanged request do not grow with the branch")
       (is (within (:reads one) (:reads five) 0.02) "the reads of an unchanged request do not grow with the branch")))))
