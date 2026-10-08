(ns seon.competency
  "The competency bench's grade: one run's outcome read from the stored facts of its agent's branch.

  A run is its agent: the task it held, its settings overlay (the presentation variant) and the
  call rows and evaluations its model passes recorded. Nothing here runs a test or a model."
  (:require [seon.context :as context]
            [seon.db :as db]))

(def setups
  "The definitions each task's agent starts from, evaluated as its first eval request on its own branch;
  a task with no entry starts from the cluster's program."
  (let [slug "(ns comp.text (:require [clojure.string :as str]))
              (defn slug
                \"A lowercase, dash-separated slug of the title `s`.\"
                {:malli/schema [:=> [:cat :string] :string]}
                [s]
                (str/join \"-\" (str/split (str/lower-case (str/trim s)) #\"\\s+\")))"]
    {'seon.competency/slug-has-a-passing-test-prose slug
     'seon.competency/slug-has-a-passing-test-typed slug}))

(def oracles
  "Each task's known solution, as its agent would evaluate it; a task's two wordings share one."
  (let [area "(ns comp.geo)
              (defn area
                {:malli/schema [:=> [:cat [:int {:min 1}] [:int {:min 1}]] [:int {:min 1}]]}
                [w h]
                (* w h))"
        qty "(seon.schema/register! :comp.order/qty [:int {:min 1}])
             (ns comp.order)
             (defn double-qty {:malli/schema [:=> [:cat :comp.order/qty] :int]} [q] (* 2 q))"
        slug-test "(ns comp.text-test (:require [clojure.test :refer [deftest is]] [comp.text :as text]))
                   (deftest slug-lowercases
                     (is (= \"hello-world\" (text/slug \"Hello World\")))
                     (is (= \"abc\" (text/slug \"  ABC \"))))"
        aliased "(ns comp.util)
               (defn clamp {:malli/schema [:=> [:cat :int] :int]} [x] (max 0 (min 10 x)))
               (ns comp.app (:require [comp.util :as u]))
               (defn bounded {:malli/schema [:=> [:cat :int] :int]} [x] (u/clamp x))"]
    {'seon.competency/area-is-contracted area
     'seon.competency/area-is-contracted-typed area
     'seon.competency/quantity-schema-then-function-prose qty
     'seon.competency/quantity-schema-then-function-typed qty
     'seon.competency/slug-has-a-passing-test-prose slug-test
     'seon.competency/slug-has-a-passing-test-typed slug-test
     'seon.competency/app-calls-util-through-an-alias-prose aliased
     'seon.competency/app-calls-util-through-an-alias-typed aliased}))

(def ^:private call-selector
  [:db/id {:seon.turn/opened-tx [:db/id :db/txInstant]} {:seon.turn/closed-tx [:db/id :db/txInstant]}
   {:seon.turn/attempts [:seon.ai.usage/prompt-tokens :seon.ai.usage/completion-tokens
                         :seon.ai.usage/cached-tokens :seon.ai.attempt/error]}])

(def ^:private evaluation-selector
  [:db/id :seon.cluster.eval/source :seon.cluster.eval/error :seon.cluster.eval/faults
   {:seon.cluster.eval/run [:db/id]}])

(defn- model-calls
  "The agent's call rows that carry a model attempt, in opening order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:vector [:map [:db/id :int]]]]}
  [database agent-id]
  (->> (db/q '[:find [(pull ?call selector) ...] :in $ ?agent-id selector
               :where [?agent :seon.agent/id ?agent-id] [?call :seon.turn/agent ?agent] [?call :seon.turn/attempts _]]
             database agent-id call-selector)
       (sort-by #(get-in % [:seon.turn/opened-tx :db/id]))
       vec))

(defn- model-evaluations
  "The agent's evaluations its model passes recorded, in writer order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:vector [:map [:db/id :int]]]]}
  [database agent-id]
  (->> (db/q '[:find [(pull ?e selector) ...] :in $ ?agent-id selector
               :where [?agent :seon.agent/id ?agent-id] [?call :seon.turn/agent ?agent]
               [?e :seon.cluster.eval/run ?call] [?e :seon.cluster.eval/origin :model]]
             database agent-id evaluation-selector)
       (sort-by :db/id)
       vec))

(defn- first-green-tx
  "The transaction by which each of `proofs` had recorded a green result, or nil."
  {:malli/schema [:=> [:cat :seon.db/database-value [:set :qualified-symbol]] [:maybe :int]]}
  [database proofs]
  (let [firsts (into {} (db/q '[:find ?s (min ?tx) :in $ [?s ...]
                                :where [?m :seon.test.member/symbol ?s] [?m :seon.test.member/pass-count ?p ?tx]
                                [(pos? ?p)] [?m :seon.test.member/fail-count 0] [?m :seon.test.member/error-count 0]]
                              database (vec proofs)))]
    (when (and (seq proofs) (= (set (keys firsts)) proofs))
      (apply max (vals firsts)))))

(defn- usage-total
  "The sum of one usage attribute over the calls' attempts."
  {:malli/schema [:=> [:cat [:vector [:map [:db/id :int]]] :qualified-keyword] :int]}
  [calls k]
  (transduce (comp (mapcat :seon.turn/attempts) (keep k)) + 0 calls))

(defn grade
  "One run's grade, read from `database` (the agent's branch).

  Whether `task`'s proofs are green, the model calls taken and taken to green,
  agent and platform errors, wasted calls, tokens and wall time."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.task/sym]
                  [:or [:map
                        [:seon.competency/agent :seon.agent/id]
                        [:seon.competency/task :seon.task/sym]
                        [:seon.competency/green? :boolean]
                        [:seon.competency/proofs [:map-of :keyword :int]]
                        [:seon.competency/model-calls :int]
                        [:seon.competency/calls-to-green {:optional true} :int]
                        [:seon.competency/attempts :int]
                        [:seon.competency/forms :int]
                        [:seon.competency/agent-errors :int]
                        [:seon.competency/platform-errors :int]
                        [:seon.competency/no-form-calls :int]
                        [:seon.competency/repeated-forms :int]
                        [:seon.competency/prompt-tokens :int]
                        [:seon.competency/cached-tokens :int]
                        [:seon.competency/completion-tokens :int]
                        [:seon.competency/wall-ms {:optional true} :int]]
                   :seon.error/value]]}
  [database agent-id task]
  (let [proven (context/proofs database task)]
    (if (:seon.error/at proven)
      proven
      (let [counts (:seon.context/counts proven {})
            proofs (set (map :seon.test/sym (:seon.context/proof-list proven)))
            green? (boolean (and (seq proofs) (= (count proofs) (get counts :green 0))))
            calls (model-calls database agent-id)
            evaluations (model-evaluations database agent-id)
            evaluated (set (map #(get-in % [:seon.cluster.eval/run :db/id]) evaluations))
            green-tx (when green? (first-green-tx database proofs))
            sources (map :seon.cluster.eval/source evaluations)
            opened (get-in (first calls) [:seon.turn/opened-tx :db/txInstant])
            closed (get-in (peek calls) [:seon.turn/closed-tx :db/txInstant])]
        (cond-> {:seon.competency/agent agent-id
                 :seon.competency/task task
                 :seon.competency/green? green?
                 :seon.competency/proofs counts
                 :seon.competency/model-calls (count calls)
                 :seon.competency/attempts (transduce (map (comp count :seon.turn/attempts)) + 0 calls)
                 :seon.competency/forms (count evaluations)
                 :seon.competency/agent-errors (count (filter :seon.cluster.eval/error evaluations))
                 :seon.competency/platform-errors (+ (transduce (map (comp count :seon.cluster.eval/faults)) + 0 evaluations)
                                                     (count (keep :seon.ai.attempt/error (mapcat :seon.turn/attempts calls))))
                 :seon.competency/no-form-calls (count (remove (comp evaluated :db/id) calls))
                 :seon.competency/repeated-forms (- (count sources) (count (distinct sources)))
                 :seon.competency/prompt-tokens (usage-total calls :seon.ai.usage/prompt-tokens)
                 :seon.competency/cached-tokens (usage-total calls :seon.ai.usage/cached-tokens)
                 :seon.competency/completion-tokens (usage-total calls :seon.ai.usage/completion-tokens)}
          green-tx (assoc :seon.competency/calls-to-green
                          (count (filter #(< (get-in % [:seon.turn/opened-tx :db/id]) green-tx) calls)))
          (and opened closed) (assoc :seon.competency/wall-ms (- (inst-ms closed) (inst-ms opened))))))))
