(ns seon.competency
  "The competency bench's grade: one run's outcome read from the stored facts of its agent's branch.

  A run is its agent: the task it held, its settings overlay (the presentation variant) and the
  call rows and evaluations its model passes recorded. `launch!` starts runs through the agents' own
  REPL entrance and grades them; `table` folds grades into one row per cell."
  (:require [clojure.data.json :as json]
            [seon.agent :as agent]
            [seon.cluster :as cluster]
            [seon.context :as context]
            [seon.db :as db]
            [seon.eval.drive :as drive]
            [seon.id :as id]
            [seon.mcp :as mcp]
            [seon.operator.runtime :as runtime]
            [seon.test-support :as support]
            [seon.task :as task]
            [seon.turn :as turn]))

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
     'seon.competency/slug-has-a-passing-test-typed slug
     'seon.competency/slug-removes-edge-hyphens slug}))

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
        slug-trimmed "(ns comp.text (:require [clojure.string :as str]))
                      (defn slug
                        \"A lowercase, dash-separated slug of the title `s`, without edge hyphens.\"
                        {:malli/schema [:=> [:cat :string] :string]}
                        [s]
                        (-> (str/lower-case (str/trim s)) (str/replace #\"\\s+\" \"-\") (str/replace #\"^-+|-+$\" \"\")))"
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
     'seon.competency/slug-removes-edge-hyphens slug-trimmed
     'seon.competency/app-calls-util-through-an-alias-prose aliased
     'seon.competency/app-calls-util-through-an-alias-typed aliased}))

(def ^:private call-selector
  [:db/id {:seon.turn/opened-tx [:db/id :db/txInstant]} {:seon.turn/closed-tx [:db/id :db/txInstant]}
   {:seon.turn/attempts [:db/id :seon.ai.usage/prompt-tokens :seon.ai.usage/completion-tokens
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
  "The forms the agent's model passes evaluated, in writer order: evaluations that ran (`:seon.eval/duration-ms`),
  as the turn loop counts them; a reply of prose or only read errors evaluated none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:vector [:map [:db/id :int]]]]}
  [database agent-id]
  (->> (db/q '[:find [(pull ?e selector) ...] :in $ ?agent-id selector
               :where [?agent :seon.agent/id ?agent-id] [?call :seon.turn/agent ?agent]
               [?e :seon.cluster.eval/run ?call] [?e :seon.cluster.eval/origin :model] [?e :seon.eval/duration-ms _]]
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
  agent and platform errors, wasted calls, tokens, wall time and the agent's settings overrides (its variant)."
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
                        [:seon.competency/wall-ms {:optional true} :int]
                        [:seon.competency/variant :map]]
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
                 :seon.competency/completion-tokens (usage-total calls :seon.ai.usage/completion-tokens)
                 :seon.competency/variant (let [overrides (agent/settings database agent-id)]
                                            (if (:seon.error/at overrides) {} overrides))}
          green-tx (assoc :seon.competency/calls-to-green
                          (count (filter #(< (get-in % [:seon.turn/opened-tx :db/id]) green-tx) calls)))
          (and opened closed) (assoc :seon.competency/wall-ms (- (inst-ms closed) (inst-ms opened))))))))

;;; LAUNCH: fresh agents on their own branches, each assigned one task under one strategy's settings,
;;; woken together by one outside message each, graded once idle, their branches retired.

(defn- agent-eval!
  "The parsed MCP result of `code` evaluated as `agent-id` in the running cluster `running`."
  {:malli/schema [:=> [:cat :string :seon.agent/id :string] :map]}
  [running agent-id code]
  (json/read-str (mcp/call (json/write-str {"name" "eval_clj"
                                            "arguments" {"code" code "agent" agent-id "cluster" running}}))))

(defn- failed
  "The launch error for `agent-id` at `step`, carrying the MCP result's text."
  {:malli/schema [:=> [:cat :seon.agent/id :keyword :map] :map]}
  [agent-id step result]
  {:seon.competency/agent agent-id :seon.competency/launch-failed step
   :seon.error/message (str "The run's " (name step) " was refused.")
   :seon.competency/reply (mapv #(get % "text") (get result "content"))})

(defn- start-run!
  "Create the run's agent on its own branch through its first eval request (its task's setup, then its
  settings), assign it its task at the task home and write its one outside message on its branch. Answers
  the run with its branch and connection, or the step that was refused."
  {:malli/schema [:=> [:cat :string :map] :map]}
  [running {agent-id :seon.competency/agent task-sym :seon.competency/task settings :seon.competency/settings :as run}]
  (let [{branch :seon.store/branch target :seon.db/connection first-request :seon.test/reply}
        (support/own-branch-agent! running agent-id (str (get setups task-sym "(ns user)")
                                                         "\n(my.agent/settings! " (id/printed settings) ")"))
        connection (:seon.db/connection (:seon.turn.loop/cluster (get @runtime/running-instances running)))]
    (if-not branch
      (merge run (failed agent-id :setup first-request))
      (let [task-id (db/q '[:find ?id . :in $ ?sym :where [?t :seon.task/sym ?sym] [?t :seon.task/id ?id]]
                          (db/db (task/home-connection connection)) task-sym)
            assigned (task/assign! {:seon.agent/context-source (support/running-cluster running) :seon.db/connection connection :seon.task/assigned task-id
                                    :seon.task/agent [:seon.agent/id agent-id] :seon.task/branch branch})]
        (if (:seon.error/at assigned)
          (merge run (failed agent-id :assignment {}) {:seon.competency/refusal assigned})
          (do (support/outside-wake-on-branch! running agent-id target "Work your task.")
              (assoc run :seon.store/branch branch :seon.db/connection target)))))))

(defn- run-state
  "Where the run of `agent-id` stands on its branch `database`: `:running` while a model call or a test member
  is open; once the task's end closed the agent, how it ended (`:done`, `:capped`, `:given-back`), as its end
  message to root at the task home says; `:waiting` once its last reply leaves the turn loop nothing to continue
  (`seon.turn`'s own rule: no evaluated form, a disposition or a failed attempt); else `:running`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:enum :running :done :waiting :capped :given-back :stalled]]}
  [database agent-id]
  (let [calls (model-calls database agent-id)
        testing? (db/q '[:find ?m . :where [?m :seon.test.member/began? true] (not [?m :seon.test.member/ended? true])]
                       database)
        home (or (some-> (task/home database) db/db) database)
        ;; a task's end closes the agent at the task home, with its end message
        closed? (:seon.agent/admission-closed-tx (db/pull home [:seon.agent/admission-closed-tx] [:seon.agent/id agent-id]))
        ended (when closed?
                (db/q '[:find ?end . :in $ ?agent-id
                        :where [?agent :seon.agent/id ?agent-id] [?m :seon.message/from ?agent] [?m :seon.task/end ?end]]
                      home agent-id))]
    (cond (or (empty? calls) (some #(nil? (:seon.turn/closed-tx %)) calls) testing?) :running
          ended ended
          (not (@#'turn/continuing-reply? database agent-id)) :waiting
          :else :running)))

(defn- await-runs
  "Each started run with the state it settled in: polled every `poll-ms` until every run held a terminal
  state on two polls running (a call's test step starts as the call closes, so one poll can fall between),
  or `deadline-ms` passed (`:deadline`)."
  {:malli/schema [:=> [:cat [:vector :map] pos-int? pos-int?] [:vector :map]]}
  [runs poll-ms deadline-ms]
  (let [deadline (+ (System/currentTimeMillis) deadline-ms)
        states #(mapv (fn [{agent-id :seon.competency/agent connection :seon.db/connection settings :seon.competency/settings}]
                        (run-state (db/db connection) agent-id))
                      runs)]
    (loop [previous nil]
      (let [now (states)]
        (cond (and (= now previous) (not-any? #{:running} now))
              (mapv #(assoc %1 :seon.competency/terminal %2) runs now)
              (< deadline (System/currentTimeMillis))
              (mapv #(assoc %1 :seon.competency/terminal (if (= :running %2) :deadline %2)) runs now)
              :else (do (Thread/sleep (long poll-ms)) (recur now)))))))

(defn launch!
  "Run every cell of `:seon.competency/cells` (a task, a strategy name and its settings) `:seon.competency/runs`
  times, all in parallel, and answer one grade per run with its strategy and terminal state.

  Each run is a fresh agent `bench-<strategy>-<task name>-<launch>-<i>` on its own branch: its first eval
  request (through MCP in the running cluster `:seon.competency/cluster`) evaluates the task's setup and sets
  its own overrides, the cell's settings (the strategy's dials, the provider and the call cap
  `:seon.config.run/max-episode-runs`); root assigns the task at the task home; one outside message wakes it.
  Once every run is waiting, capped or past `:seon.competency/deadline-ms`, each is graded on its branch and
  its branch retired by root (`:seon.competency/retired?`). A run whose start was refused answers that refusal in place of its grade."
  {:malli/schema [:=> [:cat [:map [:seon.competency/cluster :string]
                             [:seon.competency/cells [:vector [:map [:seon.competency/task :seon.task/sym]
                                                               [:seon.competency/strategy :keyword]
                                                               [:seon.competency/settings
                                                                [:map [:seon.config.run/max-episode-runs pos-int?]]]]]]
                             [:seon.competency/runs pos-int?] [:seon.competency/poll-ms pos-int?]
                             [:seon.competency/deadline-ms pos-int?]]]
                  [:vector :map]]}
  [{running :seon.competency/cluster cells :seon.competency/cells n :seon.competency/runs :as request}]
  (let [launch (subs (id/id) 0 6)
        planned (vec (for [{task-sym :seon.competency/task strategy :seon.competency/strategy :as cell} cells
                           i (range n)]
                       (assoc cell :seon.competency/agent
                              (str "bench-" (name strategy) "-" (name task-sym) "-" launch "-" i))))
        started (mapv #(start-run! running %) planned)
        settled (await-runs (filterv :seon.db/connection started)
                            (:seon.competency/poll-ms request) (:seon.competency/deadline-ms request))
        graded (mapv (fn [{agent-id :seon.competency/agent connection :seon.db/connection branch :seon.store/branch
                           :as run}]
                       (let [graded-run (grade (db/db connection) agent-id (:seon.competency/task run))
                             retired (agent-eval! running "root"
                                                  (str "(my.agent/retire-branch! {:seon.store/branch " branch "})"))]
                         (merge graded-run (select-keys run [:seon.competency/strategy :seon.competency/terminal
                                                             :seon.store/branch])
                                {:seon.competency/retired? (false? (get retired "isError"))})))
                     settled)]
    (into graded (comp (remove :seon.db/connection) (map #(dissoc % :seon.competency/settings))) started)))

(defn- median
  "The middle value of `xs` (the lower of the two middles), or nil when empty."
  {:malli/schema [:=> [:cat [:sequential :int]] [:maybe :int]]}
  [xs]
  (when (seq xs) (nth (vec (sort xs)) (quot (dec (count xs)) 2))))

(defn- per-run
  "The mean of `k` over `grades`, to one decimal."
  {:malli/schema [:=> [:cat [:vector :map] :keyword] :double]}
  [grades k]
  (/ (Math/round (* 10.0 (/ (double (transduce (map #(get % k 0)) + 0 grades)) (count grades)))) 10.0))

(defn table
  "One row per cell (task and strategy) of `grades`: runs, green share, median and range of calls to green,
  agent and platform errors, wasted calls and tokens per run, in first-seen cell order."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [grades]
  (let [graded (filterv :seon.competency/task grades)
        cells (distinct (map (juxt :seon.competency/task :seon.competency/strategy) graded))
        by-cell (group-by (juxt :seon.competency/task :seon.competency/strategy) graded)]
    (mapv (fn [[task-sym strategy :as cell]]
            (let [runs (by-cell cell)
                  to-green (keep :seon.competency/calls-to-green runs)]
              (cond-> {:seon.competency/task task-sym :seon.competency/strategy strategy
                       :seon.competency/runs (count runs)
                       :seon.competency/green (count (filter :seon.competency/green? runs))
                       :seon.competency/agent-errors (per-run runs :seon.competency/agent-errors)
                       :seon.competency/platform-errors (per-run runs :seon.competency/platform-errors)
                       :seon.competency/no-form-calls (per-run runs :seon.competency/no-form-calls)
                       :seon.competency/repeated-forms (per-run runs :seon.competency/repeated-forms)
                       :seon.competency/prompt-tokens (per-run runs :seon.competency/prompt-tokens)
                       :seon.competency/cached-tokens (per-run runs :seon.competency/cached-tokens)
                       :seon.competency/completion-tokens (per-run runs :seon.competency/completion-tokens)}
                (seq to-green) (assoc :seon.competency/calls-to-green-median (median to-green)
                                      :seon.competency/calls-to-green-range [(apply min to-green) (apply max to-green)]))))
          cells)))
