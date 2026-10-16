(ns seon.printer-cost-test
  "The printer cleanup's cost proof: each probe of `seon.printer-baseline` printed through the print entry,
  its allocation held against the parent figures recorded in baseline.edn.
  A phase tightens its own row of `bounds` in the commit that earns it."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [seon.db :as db]
            [seon.mcp :as mcp]
            [seon.printer-baseline :as baseline]
            [seon.render.value :as value]
            [seon.test-support :as support]))

(def ^:private recorded
  "The parent figures, recorded on default by `seon.printer-baseline/write-baseline!`."
  (delay (edn/read-string (slurp baseline/baseline-path))))

(def ^:private bounds
  "Per probe, the most this entry may allocate as a multiple of the recorded parent's allocation.
  The recorded input is default's data and the measured one a branch of it, so a row at 1.5 allows for drift."
  {:small-reply 1.5
   :function-entity 1.5
   :function-entity-small 1.5
   :function-entity-pair 1.5
   ;; P1b's water-fill: the 4,956-tuple relation shows whole tuples then one cut, work proportional to what shows
   :documented-functions 0.25})

(def ^:private transcript-bound
  "The most the entry may allocate over the direct pair, as a multiple of the recorded entry's multiple of it."
  1.5)

(defn- ratio
  "The allocation of a recorded transcript's entry over its direct pair."
  {:malli/schema [:=> [:cat :map] number?]}
  [transcript]
  (/ (get-in transcript [:entry :alloc-bytes]) (get-in transcript [:direct :alloc-bytes])))

(defn- custody
  "The probes' custody on `connection`: its database, a fork of the member's SCI context and its cluster."
  {:malli/schema [:=> [:cat :seon.db/connection] :map]}
  [connection]
  (let [database (db/db connection)]
    {:seon.db/db database
     :seon.sci.eval/ctx (support/fork-cluster-ctx connection)
     :seon.boot/cluster-name (db/q '[:find ?name . :where [_ :seon.config/cluster ?name]] database)}))

(defn- evaluate!
  "Evaluate `form` as `agent-id` on `running` through the MCP entrance, which records the evaluation."
  {:malli/schema [:=> [:cat :string :string :string] :map]}
  [running agent-id form]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"cluster" running "agent" agent-id
                                                         "mode" "sci" "code" form}}))))

(deftest every-probe-prints-within-its-budget-and-allocation-bound
  (support/with-running-agent
   (fn [running agent-id connection]
     (doseq [n (range 40)] (evaluate! running agent-id (str "(+ " n " " n ")")))
     (let [measured (baseline/baseline (custody connection) value/prepare)]
       (doseq [[probe limit] bounds
               :let [parent (get-in @recorded [:probes probe])
                     now (get-in measured [:probes probe])
                     budget (get-in @recorded [:budgets probe])]]
         (is (< 0 (:alloc-bytes now)) (str probe " was measured"))
         (is (<= (:alloc-bytes now) (* limit (:alloc-bytes parent)))
             (str probe " allocates at most " limit " times its recorded parent"))
         (when budget
           (is (<= (:tokens now) budget) (str probe " prints within its token budget"))))
       (is (= 40 (get-in measured [:transcript :evaluations])) "the transcript printed 40 stored evaluations")
       (is (<= (get-in measured [:transcript :entry :alloc-bytes])
               (* transcript-bound (ratio (:transcript @recorded)) (get-in measured [:transcript :direct :alloc-bytes])))
           "the transcript through the entry allocates at most its recorded multiple of the direct pair, in one run")))))
