(ns seon.competency
  "The context bench: chain runs of small tasks, each graded from its attempt's facts on its kept chain branch.

  A run is one chain of one task (`seon.task/chain!`), its variant the chain's own config decisions
  (`:seon.chain/config`). `launch!` writes the runs and waits for each attempt's end, which root's db<->flow
  drives with no hand act; `grade` reads one attempt's end, calls, tokens, evaluation cost and form classes;
  `table` folds grades per task and variant; `compare-tables` applies the noise rule between two tables."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [edamame.core :as edamame]
            [seon.db :as db]
            [seon.id :as id]
            [seon.task :as task]))

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


(defn bench-set
  "The bench task set `resource` (default v1) as data: one entry per task, its id, split, chain task map and oracle."
  {:malli/schema [:function [:=> [:cat] [:vector :map]] [:=> [:cat :string] [:vector :map]]]}
  ([] (bench-set "seon/competency/bench-v1.edn"))
  ([resource] (edn/read-string (slurp (io/resource resource)))))

;;; CLASSES: what each model form of a run did, read from its stored source and error (context-patterns.md §3).

(def ^:private read-heads
  '#{source doc find-doc clojure.repl/source clojure.repl/doc my.program/source my.program/doc my.task/current
     seon.context/current-state})

(def ^:private search-heads
  '#{dir apropos clojure.repl/dir clojure.repl/apropos my.program/search my.program/callers my.program/ns-decls
     ns-publics ns-interns all-ns seon.db/q seon.db/pull})

(def ^:private progress-heads
  '#{def defn defn- defmacro defmulti defmethod deftest clojure.test/deftest seon.schema/register!
     my.program/ns-unmap! my.program/remove-ns! my.program/write-branch! my.task/update! my.edit/text})

(def ^:private wait-heads '#{my.turn/wait my.task/send-back! my.message/send})

(def productive
  "The form classes that move a run forward; a model call holding none of them is a wasted call."
  #{:read :probe :progress :wait})

(defn- form-head
  "The operator symbol of the one form `source` holds and whether it is a bare `(ns x)`, read with edamame; nil when
  it reads as no list."
  {:malli/schema [:=> [:cat :string] [:maybe [:tuple :symbol :boolean]]]}
  [source]
  (let [form (try (edamame/parse-string source {:all true :auto-resolve (fn [ns-alias] (if (= :current ns-alias) 'user ns-alias))
                                                :readers (fn [_tag] identity)})
                  (catch Exception _ nil))]
    (when (and (seq? form) (symbol? (first form)))
      [(first form) (= 2 (count form))])))

(defn- error-class
  "The class of a form's stored error text: an unresolved name, a contract refusal of its call shape, or another
  error. The evaluation stores its error as text only, so the text is all there is to read."
  {:malli/schema [:=> [:cat :string] [:enum :unresolved :contract-refusal :error]]}
  [error]
  (cond (some #(str/includes? error %) ["Unable to resolve symbol" "Could not find namespace" "No such namespace"
                                        "No such var"]) :unresolved
        (str/includes? error " refused ") :contract-refusal
        :else :error))

(defn classify
  "Each of `evaluations` (stored model forms in writer order, `:seon.cluster.eval/source` and maybe `/error`) with its
  `:seon.competency/class`: an error's class; `in-ns` or a bare `ns` is navigation; a read of source or doc is a read,
  or a re-read when the same form ran before; a search (a hand query of the database included); a definition is
  progress; a wait or send-back ends the turn;
  any other form repeated exactly is a repeat, else a probe."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [evaluations]
  (first
   (reduce (fn [[out seen] {source :seon.cluster.eval/source error :seon.cluster.eval/error :as evaluation}]
             (let [text (str/trim (or source ""))
                   [head bare?] (form-head text)
                   again? (contains? seen text)
                   form-class (cond (string? error) (error-class error)
                               (or (= 'in-ns head) (and (= 'ns head) bare?)) :navigation
                               (read-heads head) (if again? :re-read :read)
                               (search-heads head) (if again? :repeat :search)
                               (or (progress-heads head) (= 'ns head)) :progress
                               (wait-heads head) :wait
                               again? :repeat
                               :else :probe)]
               [(conj out (assoc evaluation :seon.competency/class form-class)) (conj seen text)]))
           [[] #{}]
           evaluations)))

;;; GRADE: one attempt, read on the branch it worked on, from its end facts.

(def ^:private call-selector
  [:db/id {:seon.turn/opened-tx [:db/id :db/txInstant]} {:seon.turn/closed-tx [:db/id :db/txInstant]}
   {:seon.turn/attempts [:seon.ai.usage/prompt-tokens :seon.ai.usage/completion-tokens :seon.ai.usage/cached-tokens
                         :seon.ai.attempt/error]}])

(def ^:private evaluation-selector
  [:db/id :seon.cluster.eval/source :seon.cluster.eval/error :seon.eval/duration-ms :seon.eval/allocated-bytes
   {:seon.cluster.eval/run [:db/id]}])

(defn- read!
  "`value`, a read's answer, or a throw of its refusal."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema/value]}
  [value]
  (if (and (map? value) (:seon.error/message value))
    (throw (ex-info (:seon.error/message value) value))
    value))

(defn- model-calls
  "The agent's call rows that carry a model attempt, in opening order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:vector :map]]}
  [database agent-id]
  (->> (read! (db/q '[:find [(pull ?call selector) ...] :in $ ?agent-id selector
                      :where [?agent :seon.agent/id ?agent-id] [?call :seon.turn/agent ?agent] [?call :seon.turn/attempts _]]
                    database agent-id call-selector))
       (sort-by #(get-in % [:seon.turn/opened-tx :db/id]))
       vec))

(defn- model-evaluations
  "The forms the agent's model calls evaluated, in writer order."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id] [:vector :map]]}
  [database agent-id]
  (->> (read! (db/q '[:find [(pull ?e selector) ...] :in $ ?agent-id selector
                      :where [?agent :seon.agent/id ?agent-id] [?call :seon.turn/agent ?agent]
                      [?e :seon.cluster.eval/run ?call] [?e :seon.cluster.eval/origin :model]]
                    database agent-id evaluation-selector))
       (sort-by :db/id)
       vec))

(defn- total
  "The sum of `k` over `xs`, absent values and negative (unmeasured) ones counting zero."
  {:malli/schema [:=> [:cat [:sequential :map] :keyword] :int]}
  [xs k]
  (transduce (keep #(let [x (get % k)] (when (and (int? x) (not (neg? x))) x))) + 0 xs))

(defn grade
  "Attempt `key`'s grade, read on `database`, the branch its agent worked on (a kept chain branch): how it ended
  (`:seon.task.attempt/end`, its agent's end facts for the proofs), green when it ended `:done` (the done rule passed:
  proofs green, nothing else red, no contract gap), its model calls (calls to green when green), the forms they
  evaluated by class, calls holding no productive form, errors, tokens, wall time from the first call's opening to
  the last's close, and the evaluations' summed time and allocation."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/assignment] :map]}
  [database assignment]
  (let [row (read! (db/pull database [:db/id :seon.task.attempt/end :seon.task.attempt/number
                                      {:seon.task/attempt-of [:seon.task/id :seon.task/name :seon.task/proofs]}]
                            [:seon.task/assignment assignment]))
        agent-id (str "attempt/" assignment)
        ended (read! (db/pull database [:seon.agent/end-green :seon.agent/end-red :seon.agent/end-pending]
                              [:seon.agent/id agent-id]))
        calls (model-calls database agent-id)
        evaluations (classify (model-evaluations database agent-id))
        by-call (group-by #(get-in % [:seon.cluster.eval/run :db/id]) evaluations)
        end (:seon.task.attempt/end row)
        green? (= :done end)
        opened (get-in (first calls) [:seon.turn/opened-tx :db/txInstant])
        closed (get-in (peek calls) [:seon.turn/closed-tx :db/txInstant])
        usage #(total (mapcat :seon.turn/attempts calls) %)]
    (cond-> {:seon.competency/assignment assignment
             :seon.competency/agent agent-id
             :seon.competency/task (get-in row [:seon.task/attempt-of :seon.task/id])
             :seon.competency/end (or end :running)
             :seon.competency/green? green?
             :seon.competency/proofs (into {} (keep (fn [[k proof]] (when-let [n (get ended k)] [proof n])))
                                           {:seon.agent/end-green :green :seon.agent/end-red :red
                                            :seon.agent/end-pending :pending})
             :seon.competency/model-calls (count calls)
             :seon.competency/forms (count evaluations)
             :seon.competency/classes (frequencies (map :seon.competency/class evaluations))
             :seon.competency/wasted-calls (count (remove #(some (comp productive :seon.competency/class) (by-call (:db/id %)))
                                                          calls))
             :seon.competency/no-form-calls (count (remove #(by-call (:db/id %)) calls))
             :seon.competency/agent-errors (count (filter :seon.cluster.eval/error evaluations))
             :seon.competency/platform-errors (count (keep :seon.ai.attempt/error (mapcat :seon.turn/attempts calls)))
             :seon.competency/prompt-tokens (usage :seon.ai.usage/prompt-tokens)
             :seon.competency/cached-tokens (usage :seon.ai.usage/cached-tokens)
             :seon.competency/completion-tokens (usage :seon.ai.usage/completion-tokens)
             :seon.competency/eval-ms (total evaluations :seon.eval/duration-ms)
             :seon.competency/alloc-mb (/ (Math/round (/ (total evaluations :seon.eval/allocated-bytes) 1e5)) 10.0)}
      green? (assoc :seon.competency/calls-to-green (count calls))
      (and opened closed) (assoc :seon.competency/wall-ms (- (inst-ms closed) (inst-ms opened))))))

;;; LAUNCH: each (task, variant, i) one chain of one, written by `seon.task/chain!` with the variant as the chain's
;;; config; root's db<->flow starts, runs and ends each attempt with no hand act; each is graded at its end.

(defn- attempt-end
  "The end attempt `key` recorded on chain `branch` of main `database`, or nil while it has none."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.chain/branch :seon.task/assignment] [:maybe :keyword]]}
  [database branch assignment]
  (when-let [head (db/branch-database database branch)]
    (try (read! (db/q '[:find ?e . :in $ ?k :where [?a :seon.task/assignment ?k] [?a :seon.task.attempt/end ?e]] head assignment))
         (finally (d/release-materialized-db head)))))

(defn- start-run!
  "Write `run`'s chain through `context` (main's connection and the store) with its variant's config; the run with
  its chain branch and first attempt's key, or with `chain!`'s refusal."
  {:malli/schema [:=> [:cat :seon.task/chain-context :map] :map]}
  [context {task-map :seon.competency/task-map config :seon.chain/config :as run}]
  (let [chain (task/chain! context [task-map] {:seon.chain/config config})]
    (if-let [branch (:seon.chain/branch chain)]
      (assoc run :seon.chain/branch branch :seon.task/assignment (id/id [(first (:seon.task/handles chain)) 1]))
      (assoc run :seon.competency/refusal chain))))

(defn- await-ends
  "Each started run of `runs` with its attempt's end (`:seon.competency/terminal`), read on its chain branch every
  `poll-ms` until every run has one or `deadline-ms` passed (`:deadline`)."
  {:malli/schema [:=> [:cat :seon.db/connection [:vector :map] pos-int? pos-int?] [:vector :map]]}
  [connection runs poll-ms deadline-ms]
  (let [deadline (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (let [database (db/db connection)
            ends (mapv #(attempt-end database (:seon.chain/branch %) (:seon.task/assignment %)) runs)]
        (cond (every? some? ends) (mapv #(assoc %1 :seon.competency/terminal %2) runs ends)
              (< deadline (System/currentTimeMillis)) (mapv #(assoc %1 :seon.competency/terminal (or %2 :deadline)) runs ends)
              :else (do (Thread/sleep (long poll-ms)) (recur)))))))

(defn- graded
  "`run`'s grade, read on its chain branch at main `database`, with its task id, split, variant, call cap and main's
  outcome for its attempt when root has written one."
  {:malli/schema [:=> [:cat :seon.db/database-value :map] :map]}
  [database {branch :seon.chain/branch assignment :seon.task/assignment config :seon.chain/config :as run}]
  (let [head (db/branch-database database branch)
        outcome (:seon.task/outcome (read! (db/pull database [:seon.task/outcome] [:seon.task/assignment assignment])))]
    (try
      (cond-> (merge (grade head assignment)
                     (select-keys run [:seon.competency/id :seon.competency/split :seon.competency/variant
                                       :seon.competency/launch :seon.competency/terminal :seon.chain/branch]))
        (:seon.config.run/max-episode-runs config) (assoc :seon.competency/cap (:seon.config.run/max-episode-runs config))
        outcome (assoc :seon.competency/outcome outcome))
      (finally (d/release-materialized-db head)))))

(defn launch!
  "Run every task of `:seon.competency/tasks` (bench-set entries) under every variant of `:seon.competency/variants`
  (a name and its `:seon.chain/config`: provider, model, call cap and any context dial) `:seon.competency/runs` times,
  and answer one grade per run, or the run with `chain!`'s refusal.

  Each run is a chain of one: its task named `<name>-<variant>-<launch>-<i>`, written by `seon.task/chain!` through
  main's `:seon.db/connection` and `:seon.store/store`. Root's db<->flow launches, runs and ends it; this waits, polling
  every `:seon.competency/poll-ms`, until each attempt has ended or `:seon.competency/deadline-ms` passed, then grades
  each on its chain branch. Branches stay as the cluster keeps them."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection] [:seon.store/store :seon.store/store]
                             [:seon.competency/tasks [:vector :map]]
                             [:seon.competency/variants [:vector [:map [:seon.competency/variant :keyword]
                                                                  [:seon.chain/config :seon.chain/config]]]]
                             [:seon.competency/runs pos-int?] [:seon.competency/poll-ms pos-int?]
                             [:seon.competency/deadline-ms pos-int?]]]
                  [:vector :map]]}
  [{connection :seon.db/connection tasks :seon.competency/tasks variants :seon.competency/variants
    n :seon.competency/runs :as request}]
  (let [launch (subs (id/id) 0 6)
        context (select-keys request [:seon.db/connection :seon.store/store])
        started (mapv #(start-run! context %)
                      (for [entry tasks {variant :seon.competency/variant config :seon.chain/config} variants i (range n)]
                        (merge (select-keys entry [:seon.competency/id :seon.competency/split])
                               {:seon.competency/variant variant :seon.competency/launch launch :seon.chain/config config
                                :seon.competency/task-map (update (:seon.competency/task entry) :seon.task/name str
                                                                  "-" (name variant) "-" launch "-" i)})))
        ended (await-ends connection (filterv :seon.chain/branch started)
                          (:seon.competency/poll-ms request) (:seon.competency/deadline-ms request))
        database (db/db connection)]
    (into (mapv #(graded database %) ended) (remove :seon.chain/branch) started)))

;;; TABLE: one row per task and variant; COMPARE: the E0 noise rule between two tables.

(defn- median
  "The middle value of `xs` (the lower of the two middles), or nil when empty."
  {:malli/schema [:=> [:cat [:sequential number?]] [:maybe number?]]}
  [xs]
  (when (seq xs) (nth (vec (sort xs)) (quot (dec (count xs)) 2))))

(defn- mean
  "The mean of `k` over `grades`, to one decimal."
  {:malli/schema [:=> [:cat [:vector :map] :keyword] :double]}
  [grades k]
  (/ (Math/round (* 10.0 (/ (double (transduce (map #(get % k 0)) + 0 grades)) (count grades)))) 10.0))

(defn score
  "A run's calls to green, with a run that did not end green scored one past its call cap (or its calls, with no
  cap known): lower is better, and every run has one."
  {:malli/schema [:=> [:cat :map] :int]}
  [{green? :seon.competency/green? calls :seon.competency/model-calls cap :seon.competency/cap}]
  (if green? calls (inc (or cap calls))))

(defn table
  "One row per task and variant of `grades` (graded runs; refused starts are left out), in first-seen order: runs,
  green, the median and range of scores (calls to green, a non-green run one past its cap) and of calls to green,
  means per run of calls, wasted calls, forms, errors, tokens, evaluation ms and allocation MB, the median wall ms,
  and the form classes summed over the cell."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]}
  [grades]
  (let [runs-graded (filterv :seon.competency/assignment grades)
        cell (juxt :seon.competency/id :seon.competency/variant)
        by-cell (group-by cell runs-graded)]
    (mapv (fn [[id variant :as k]]
            (let [runs (by-cell k)
                  scores (mapv score runs)
                  to-green (keep :seon.competency/calls-to-green runs)
                  walls (keep :seon.competency/wall-ms runs)]
              (cond-> {:seon.competency/id id :seon.competency/variant variant
                       :seon.competency/runs (count runs)
                       :seon.competency/green (count (filter :seon.competency/green? runs))
                       :seon.competency/score-median (median scores)
                       :seon.competency/score-range [(apply min scores) (apply max scores)]
                       :seon.competency/model-calls (mean runs :seon.competency/model-calls)
                       :seon.competency/wasted-calls (mean runs :seon.competency/wasted-calls)
                       :seon.competency/forms (mean runs :seon.competency/forms)
                       :seon.competency/agent-errors (mean runs :seon.competency/agent-errors)
                       :seon.competency/platform-errors (mean runs :seon.competency/platform-errors)
                       :seon.competency/prompt-tokens (mean runs :seon.competency/prompt-tokens)
                       :seon.competency/cached-tokens (mean runs :seon.competency/cached-tokens)
                       :seon.competency/completion-tokens (mean runs :seon.competency/completion-tokens)
                       :seon.competency/eval-ms (mean runs :seon.competency/eval-ms)
                       :seon.competency/alloc-mb (mean runs :seon.competency/alloc-mb)
                       :seon.competency/classes (apply merge-with + (map :seon.competency/classes runs))}
                (seq to-green) (assoc :seon.competency/calls-to-green-median (median to-green)
                                      :seon.competency/calls-to-green-range [(apply min to-green) (apply max to-green)])
                (seq walls) (assoc :seon.competency/wall-ms-median (median walls)))))
          (distinct (map cell runs-graded)))))

(defn compare-tables
  "Each cell `before` and `after` (two `table`s, e.g. two HEADs) share, with both green counts and score medians and
  ranges, and the noise rule's verdict (bench E0: one cell varied by 2 of 5 between batches): `:signal` when the
  green counts differ by 3 or more, or the score ranges do not overlap; then `:better` or `:worse` for `after`, else
  `:noise`."
  {:malli/schema [:=> [:cat [:vector :map] [:vector :map]] [:vector :map]]}
  [before after]
  (let [cell (juxt :seon.competency/id :seon.competency/variant)
        earlier (into {} (map (juxt cell identity)) before)]
    (into []
          (keep (fn [row]
                  (when-let [was (earlier (cell row))]
                    (let [[lo hi] (:seon.competency/score-range was)
                          [lo' hi'] (:seon.competency/score-range row)
                          dg (- (:seon.competency/green row) (:seon.competency/green was))
                          apart? (or (< hi' lo) (< hi lo'))
                          better? (if (>= (abs dg) 3) (pos? dg) (< hi' lo))]
                      {:seon.competency/id (:seon.competency/id row) :seon.competency/variant (:seon.competency/variant row)
                       :seon.competency/green [(:seon.competency/green was) (:seon.competency/green row)]
                       :seon.competency/score-median [(:seon.competency/score-median was) (:seon.competency/score-median row)]
                       :seon.competency/score-range [[lo hi] [lo' hi']]
                       :seon.competency/verdict (cond (not (or (>= (abs dg) 3) apart?)) :noise
                                                      better? :better
                                                      :else :worse)}))))
          after)))
