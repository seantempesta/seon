(ns seon.turn
  "The agent's turn loop, inside its own process (lanes R1 and M1's proto.agents.turn). A
  turn observes the branch (the proofs run in a fork of this agent, the done rule), and
  either ends the attempt or acts: the context goes to the model (one interop edge), each of
  the reply's calls of the one tool, eval, goes through the one REPL path
  (seon.repl/eval-string: read, evaluate, record) and its answer is that call's result, which
  the next model call reads, and the turn's facts are written on the branch. The loop repeats until the done
  rule has no refusal, a function ends it (my.turn/wait, my.task/fail!) or the turns run
  out. All green at the start ends it with no model call.

  A merge task's agent first merges main into the branch (no model call) and merges main
  again each time all is green, until a merge brings nothing new; it stops early when the
  merge is too hairy (seon.reconcile/hairy). Either way the live runtime is staged onto the
  branch at the end (seon.harvest/harvest!), so the next agent on the branch holds it.

  Root starts it by evaluating (seon.turn/work! '{...}) in the agent; the attempt's end is
  a fact on the branch (:seon.end/*), which root's judge reads."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.intr :as intr] [seon.contract :refer [defn defn-]] [clojure.string :as str] [clojure.set :as set]
            [seon.agent :as agent] [seon.repl :as repl] [seon.task :as task] [seon.reconcile :as r]
            [seon.proofs :as proofs] [seon.context :as context] [seon.program :as program]
            [seon.harvest :as harvest] [my.branch :as branch] [seon.reader :as reader] [seon.learn :as learn] [seon.config :as cfg] [seon.lib :as lib]
            [datahike.api :as d]))

;; The turn loop's own facts live in the agent's one state map, under :turn: the attempt it
;; works, and a stop a function requested.

(defn current-task
  "The id of the task this process works: the one root forked it for (seon.agent/init!), or
  nil for an agent forked with none."
  {:malli/schema [:=> [:cat] [:maybe :seon.task/id]]}
  [] (:task @agent/state))

(defn- begin!
  "This process works task as attempt: the turn state, begun once per attempt with the
  program's contract gaps as they stood (the attempt answers only for gaps it adds) and the
  task's accumulated plan as the branch held it (a design step answers for improving it).
  The turn loop and an outside agent's context read begin it the same way."
  {:malli/schema [:=> [:cat :seon.task/id :seon.attempt/id] :map]}
  [task attempt]
  (if (= attempt (get-in @agent/state [:turn :attempt]))
    (:turn @agent/state)
    (let [db (agent/db)]
      (:turn (swap! agent/state assoc :turn {:attempt attempt :gaps-before (harvest/uncontracted db)
                                             :plan-before (:plan (:seon.task/accumulated (task/task db task)))})))))

(defn last-evals
  "The items of this process's last model turn, in order: each one's form, printed value or
  error, output, and its live value (:value) while this process holds it."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [rs (:results @agent/state)]
    (mapv (fn [it] (cond-> (select-keys it [:id :tag :form :printed :out :handle])
                     (contains? rs (:handle it)) (assoc :value (get rs (:handle it)))))
          (get-in @agent/state [:turn :evals]))))

(defn request-stop!
  "A function ends the loop after this turn's forms: :waiting (my.turn/wait) or :failed (my.task/fail!)."
  {:malli/schema [:=> [:cat [:enum :waiting :failed] [:maybe :string]] :map]}
  [outcome reason]
  (:turn (swap! agent/state assoc-in [:turn :stop] (cond-> {:outcome outcome} reason (assoc :reason reason)))))

(defn- observe
  "What a turn starts from, measured now: the proofs run in a fork of this agent, the
  branch's new contract gaps and unmerged entries, the done rule over them, and the task as
  the branch holds it (its plan may have improved)."
  {:malli/schema [:=> [:cat :seon.task/id [:set :qualified-symbol] :seon.task/plan] :map]}
  [id gaps-before plan-before]
  (let [t0 (profile/now-ns)
        run (agent/run-tests-forked (cfg/value :seon.config.agent/test-ticks) (:proofs (:seon.task/accumulated (task/task (agent/db) id))))
        tests-ms (long (profile/ms-since t0))
        results (proofs/results run)
        db (agent/db)
        unc (set/difference (harvest/uncontracted db) gaps-before)
        unmerged (branch/unmerged)]
    {:run run :tests-ms tests-ms :results results :uncontracted unc :unmerged unmerged :task (task/task db id)
     :will-merge (program/divergence (:base @agent/state) db)
     :refusals (r/done-refusals (r/done-input (r/index db) id results unc (mapv #(sedn/encode (:key %)) unmerged) nil plan-before))}))

(defn- end!
  "The attempt's end: the live runtime staged onto the branch, the end written there (with
  the error that ended it and the turns' waste, as data: lane D2), and the branch committed
  (a turn's end commits the agent's run-ahead writes)."
  {:malli/schema [:=> [:cat :map :seon.attempt/outcome [:maybe :string] [:maybe :map]] :seon.turn/end]}
  [{:keys [turn tokens t0 attempt log]} outcome reason error]
  (harvest/harvest!)
  (agent/write-end! attempt {:outcome outcome :turns turn :tokens tokens :reason reason :error error
                             :waste (learn/summary (learn/waste (or log []) (learn/final-symbols (or log []))))})
  (cond-> {:outcome outcome :turns turn :model-calls turn :results {} :uncontracted #{}
           :ms (profile/ms-since t0)}
    tokens (assoc :tokens tokens)
    reason (assoc :reason reason)))

(defn usage
  "What completion c cost, as the turn records it: its tokens (prompt and completion), and the
  prompt tokens the provider's prefix cache served and did not; each absent when the provider
  did not report it (unknown, never 0)."
  {:malli/schema [:=> [:cat :map] [:map [:seon.turn/tokens {:optional true} :int]
                                   [:seon.turn/cache-hit-tokens {:optional true} :int] [:seon.turn/cache-miss-tokens {:optional true} :int]]]}
  [{:keys [prompt-tokens completion-tokens cache-hit-tokens cache-miss-tokens]}]
  (cond-> {}
    (and prompt-tokens completion-tokens) (assoc :seon.turn/tokens (+ prompt-tokens completion-tokens))
    cache-hit-tokens (assoc :seon.turn/cache-hit-tokens cache-hit-tokens)
    cache-miss-tokens (assoc :seon.turn/cache-miss-tokens cache-miss-tokens)))

(defn- observed
  "The context inputs observe's map holds, beyond the proofs' results: every refusal of the
  done rule, the failing assertions of each test it names, the run's own errors, and what the
  branch's merge will carry to main."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [run refusals task results will-merge]}]
  (let [proofs (:proofs (:seon.task/accumulated task))
        named (into (set proofs) (keep :symbol) refusals)]
    {:results results :refusals refusals :reds (proofs/red-reports run named) :run-errors (proofs/run-errors run)
     :will-merge will-merge}))

(defn- basis!
  "What a turn's context is built from, recorded instead of its text (a model call stores its
  basis, not its prompt): the commit it reads (the run-ahead writes committed now, as the
  model call's wait would), the task, attempt and turn, the observations no db holds, and
  the last evaluations' transcript while no earlier turn shows them."
  {:malli/schema [:=> [:cat :seon.task/id :seon.attempt/id :int :int :seon.turn/origin :map] :seon.turn/basis]}
  [task attempt turn max-turns origin observed]
  (agent/commit!)
  (let [items (when (or (= :outside origin) (zero? turn)) (last-evals))
        ev (when (seq items) (context/evaluations-text items))]
    (cond-> {:commit (d/commit-id (agent/db)) :task task :attempt attempt :turn turn :max-turns max-turns
             :origin origin :observed (dissoc observed :merge?)}
      (:merge? observed) (assoc :merge? true)
      ev (assoc :last-evals ev))))

(defn- act!
  "One model turn: the context of what was observed to the model, the reply's eval calls
  through the one REPL path, each answered as its own result, the turn's facts on the branch. Answers the loop state after it, or the
  provider error. The model call is an effect (my.fx :model, lane FX1): root's model owner
  admits, performs and answers it; the API key never enters this process."
  {:malli/schema [:=> [:cat :map :map [:maybe :string]] [:or :map :map]]}
  [{:keys [turn tokens max-turns merge? attempt] :as st} {:keys [results tests-ms] :as obs} model-name]
  (let [t (:task obs)
        proofs (:proofs (:seon.task/accumulated t))
        t0 (profile/now-ns)
        basis (basis! (:seon.task/id t) attempt turn max-turns :model
                      (cond-> (observed obs) merge? (assoc :merge? true)))
        {:keys [block messages]} (context/render (agent/db) basis)
        context-ms (long (profile/ms-since t0))
        t1 (profile/now-ns)
        r (agent/fx! :model (cond-> {:messages messages :task (:seon.task/id t) :turn turn}
                              model-name (assoc :model model-name))
                     {})
        model-ms (long (profile/ms-since t1))
        c (:value r)]
    (if (:seon.error/type r)
      r
      (let [t2 (profile/now-ns)
            ;; an interrupt left from before this turn's evals is not theirs
            _ (intr/clear-pending!)
            ;; each eval call through the one REPL path, in order; its answer is the call's result
            calls (mapv (fn [{:keys [id code]}] {:id id :code code :items (repl/eval-string (:text (reader/forgive code)))})
                        (:tool-calls c))
            items (into [] (mapcat :items) calls)
            eval-ms (long (profile/ms-since t2))
            _ (swap! agent/state assoc-in [:turn :evals] items)
            used (:seon.turn/tokens (usage c))
            t3 (profile/now-ns)]
        ;; what the model read, as its basis (seon.context/render makes the text from it), what
        ;; it answered and how it stopped, what its forms evaluated to as the next turns show it,
        ;; and where the turn's time went as this agent saw it
        (agent/transact! [(cond-> (merge (usage c) {:seon.turn/id (str attempt "/" turn) :seon.turn/attempt attempt :seon.turn/n turn
                                   :seon.turn/origin :model :seon.turn/basis basis
                                   :seon.turn/calls (mapv (fn [{:keys [id code items]}] {:id id :code code :evals (context/evaluations-text items)}) calls)
                                   :seon.turn/reply (:text c) :seon.turn/forms (count items)
                                   :seon.turn/errors (count (filter #(= :err (:tag %)) items))
                                   :seon.turn/red (count (remove #(= :pass (val %)) (select-keys results proofs)))
                                   :seon.turn/ms (+ model-ms eval-ms)})
                            (:truncated? c) (assoc :seon.turn/truncated true)
                            (:finish-reason c) (assoc :seon.turn/finish (:finish-reason c))
                            (:model c) (assoc :seon.turn/model (:model c))
                            (:reasoning c) (assoc :seon.turn/reasoning (:reasoning c)))])
        (agent/transact! [{:seon.turn/id (str attempt "/" turn)
                           :seon.turn/split (cond-> {:context-ms context-ms :model-ms model-ms :eval-ms eval-ms
                                                     :record-ms (long (profile/ms-since t3))}
                                              tests-ms (assoc :tests-ms tests-ms))}])
        (cond-> (-> (assoc st :turn (inc turn) :tokens (when (and tokens used) (+ tokens used)))
                    ;; what this turn's block showed, by producer, and what it evaluated (waste)
                    (update :log (fnil conj []) {:n turn
                                                 :shown {'seon.context/resolve-ref (set (keep :symbol (:references block)))
                                                         'seon.context/found (set (keep :symbol (:found block)))
                                                         'seon.context/current-state (set (map :test (:proofs block)))}
                                                 :items (mapv (fn [it] {:form (:form it) :error (:err it)}) items)}))
          merge? (update :merge-turns inc))))))

(defn context-block
  "The current-state block of task, as the model sees it on this branch now (the proofs run
  again): what an outside agent working the task reads, recorded as a turn of the attempt
  (origin :outside). It also makes this process work task as attempt, so the my.* functions act
  on it."
  {:malli/schema [:=> [:cat :seon.task/id :seon.attempt/id] :string]}
  [task attempt]
  (let [{:keys [gaps-before plan-before]} (begin! task attempt)
        {:keys [tests-ms] :as obs} (observe task gaps-before plan-before)
        n (get-in (swap! agent/state update-in [:turn :reads] (fnil inc 0)) [:turn :reads])
        t0 (profile/now-ns)
        basis (basis! task attempt n 1 :outside (assoc (observed obs) :merge? true))
        text (context/reader-text (:messages (context/render (agent/db) basis)))]
    ;; the read is a turn of this attempt, as a model turn is: what it was built from and what it cost
    (agent/transact! [{:seon.turn/id (str attempt "/read-" n) :seon.turn/attempt attempt :seon.turn/n n
                       :seon.turn/origin :outside :seon.turn/basis basis
                       :seon.turn/split (cond-> {:context-ms (long (profile/ms-since t0))} tests-ms (assoc :tests-ms tests-ms))}])
    text))

(defn work!
  "Work task `task` as attempt `attempt` (the model `model` when set, else root's), at most
  max-turns model calls.
  Answers the end ({:outcome :turns :model-calls :tokens :ms}); the same end is written on
  the branch."
  {:malli/schema [:=> [:cat [:map [:task :seon.task/id] [:attempt :seon.attempt/id] [:model {:optional true} :string]
                             [:max-turns pos-int?]]]
                  :seon.turn/end]}
  [{:keys [task attempt model max-turns]}]
  (let [{:keys [gaps-before plan-before]} (begin! task attempt)
        t0-task (task/task (agent/db) task)
        merge? (= :merge (:seon.task/kind t0-task))
        dials (r/dials t0-task)]
    (when merge? (branch/merge-main!))
    (loop [st {:turn 0 :tokens 0 :merge-turns 0 :t0 (profile/now-ns) :attempt attempt
               :max-turns max-turns :merge? merge? :gaps-before gaps-before}]
      (let [{:keys [refusals unmerged] :as obs} (observe task (:gaps-before st) plan-before)
            stop (get-in @agent/state [:turn :stop])
            hairy (when merge? (r/hairy (merge dials {:conflicts unmerged :task (:task obs) :merge-turns (:merge-turns st)})))]
        (cond
          ;; git's CI after the merge: green on the combined program; merge main again until
          ;; it brings nothing new
          (and (empty? refusals) merge?)
          (let [m (branch/merge-main!)]
            (if (and (empty? (:applied m)) (empty? (:conflicts m))) (end! st :green nil nil) (recur st)))
          (empty? refusals) (end! st :green nil nil)
          stop (end! st (:outcome stop) (:reason stop) nil)
          hairy (end! st :merge-failed (:reason hairy) nil)
          (>= (:turn st) max-turns) (end! st :cap (r/refusals-text refusals) nil)
          :else (let [st' (act! st obs model)]
                  (if (:seon.error/type st') (end! st :error (:seon.error/message st') st') (recur st'))))))))
