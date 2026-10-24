(ns seon.turn
  "The agent's turn loop, inside its own process (sessions R1 and M1's proto.agents.turn). A
  turn observes the branch (the proofs run in a fork of this agent, the done rule), and
  either ends the run or acts: the context goes to the model (one interop edge), each of
  the reply's calls of the one tool, eval, goes through the one REPL path
  (seon.repl/eval-string: read, evaluate, record) and its answer is that call's result, which
  the next model call reads, and the turn's facts are written on the branch. The loop repeats until the done
  rule has no reason, a function ends it (my.turn/wait, my.task/fail!) or the turns run
  out. All green at the start ends it with no model call.

  A merge task's agent first merges main into the branch (no model call) and merges main
  again each time all is green, until a merge brings nothing new; it stops early when the
  merge is too hairy (seon.reconcile/hairy). Either way the live runtime is staged onto the
  branch at the end (seon.stage/stage!), so the next agent on the branch holds it.

  Root starts it by evaluating (seon.turn/work! '{...}) in the agent; the run's end is
  a fact on the branch (:seon.end/*), which root's judge reads."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.intr :as intr] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str]
            [seon.agent :as agent] [seon.repl :as repl] [seon.task :as task] [seon.reconcile :as r]
            [seon.proofs :as proofs] [seon.context :as context] [seon.program :as program] [seon.merge :as pm] [seon.problems :as problems]
            [seon.stage :as stage] [seon.screen :as screen] [my.branch :as branch] [seon.reader :as reader] [seon.learn :as learn] [seon.config :as cfg] [seon.lib :as lib]
            [datahike.api :as d]))

;; The turn loop's own facts live in the agent's one state map, under :turn: the run it
;; works, and a stop a function requested.

(defn current-task
  "The id of the task this process works: the one root forked it for (seon.agent/init!), or
  nil for an agent forked with none."
  {:malli/schema [:=> [:cat] [:maybe :seon.task/id]]}
  [] (:task @agent/state))

(defn- begin!
  "This process works task as run: the turn state, begun once per run with the
  task's accumulated plan as the branch held it (a design step answers for improving it).
  The turn loop and an outside agent's context read begin it the same way."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id] :map]}
  [task run]
  (if (= run (get-in @agent/state [:turn :run]))
    (:turn @agent/state)
    (let [db (agent/db)]
      (:turn (swap! agent/state assoc :turn {:run run
                                             :plan-before (:plan (:seon.task/accumulated (task/task db task)))})))))

(defn last-evals
  "The responses of this process's last model turn, one per eval call, in order, each with its
  live value while this process holds it (:seon.eval/response)."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  [] (vec (get-in @agent/state [:turn :evals])))

(defn request-stop!
  "A function ends the loop after this turn's forms: :waiting (my.turn/wait) or :failed (my.task/fail!)."
  {:malli/schema [:=> [:cat [:enum :waiting :failed] [:maybe :string]] :map]}
  [outcome reason]
  (:turn (swap! agent/state assoc-in [:turn :stop] (cond-> {:outcome outcome} reason (assoc :reason reason)))))

(defn- added-gaps
  "The functions branch db defines without a contract since its merge base base (the
  branch's own changes, seon.merge/uncontracted over seon.merge/delta-changes): what root's judge and
  the merge gate ask."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:set :qualified-symbol]]}
  [base db] (set (pm/uncontracted (pm/delta-changes base db #{}))))

(defn- index!
  "The reconciler's index of db, observed from the one this process held for its db at the
  turn before (seon.reconcile/index over what differs); listed whole only at its first turn."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (let [{from :db idx :index} (:index @agent/state)
        v (if idx (r/index idx from db) (r/index db))]
    (swap! agent/state assoc :index {:db db :index v})
    v))

(defn- observe
  "What a turn starts from, measured now: the proofs run by the test service (seon.prove: one run
  per branch and tx whoever asks, in a fork of this agent; a run it could not fork ran no proof:
  every proof is unknown, and the refused answer is the run's error),
  the contract gaps the branch adds since its merge base (seon.merge/uncontracted, as root's
  judge and the merge gate ask it), its unmerged entries, the problems its work made true
  (seon.problems/caused, as root's judge asks it), the done rule over them, and
  the task as the branch holds it (its plan may have improved)."
  {:malli/schema [:=> [:cat :seon.task/id :seon.task/plan] :map]}
  [id plan-before]
  (let [t0 (profile/now-ns)
        test-run (agent/prove!)
        tests-ms (long (profile/ms-since t0))
        results (if (contract/error? test-run) {} (proofs/results test-run))
        db (agent/db)
        unc (added-gaps (:base @agent/state) db)
        unmerged (branch/unmerged)
        {:keys [caused attention]} (problems/caused-split (:base @agent/state) db)]
    {:test-run test-run :tests-ms tests-ms :results results :uncontracted unc :unmerged unmerged :caused caused :attention attention :task (task/task db id)
     :will-merge (program/divergence (:base @agent/state) db)
     ;; root's ruling (a): what this turn ran, what the judge will run, what the merge runs
     :tests (when-not (contract/error? test-run)
              (let [results (proofs/results test-run)
                    unfinished (count (remove #(contains? results %) (:selected test-run)))]
                (cond-> {:this-turn (count (:selected test-run)) :at-judge (:at-judge test-run) :when-asked (count (:deferred test-run))}
                  (pos? unfinished) (assoc :unfinished unfinished))))
     :reasons (r/done-explain (assoc (r/done-input (index! db) id results unc (mapv #(sedn/encode (:key %)) unmerged) nil plan-before
                                                   (if (contract/error? test-run) #{} (set (:deferred test-run))))
                                     :caused (set caused)))}))

(defn- end!
  "The run's end: the live runtime staged onto the branch, the end written there (with
  the error that ended it and the turns' waste, as data: session D2), and the branch committed
  (a turn's end commits the agent's run-ahead writes)."
  {:malli/schema [:=> [:cat :map :seon.run/outcome [:maybe :string] [:maybe :map]] :seon.turn/end]}
  [{:keys [turn tokens t0 run log]} outcome reason error]
  (stage/stage!)
  (agent/write-end! run {:outcome outcome :turns turn :tokens tokens :reason reason :error error
                             :waste (learn/summary (learn/waste (or log []) (learn/final-symbols (or log []))))})
  ;; what was not measured is absent, never 0: an outside run counts no model calls and no loop time
  (cond-> {:outcome outcome :results {} :uncontracted #{}}
    turn (assoc :turns turn :model-calls turn)
    t0 (assoc :ms (profile/ms-since t0))
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
  "The context inputs observe's map holds, beyond the proofs' results: every reason of the
  done rule, the failing assertions of each test it names, the test run's own errors, and what the
  branch's merge will carry to main."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [test-run reasons task results will-merge tests attention]}]
  (let [proofs (:proofs (:seon.task/accumulated task))
        named (into (set proofs) (keep :symbol) reasons)]
    {:results results :reasons reasons
     :reds (if (contract/error? test-run) [] (proofs/red-reports test-run named))
     :test-run-errors (if (contract/error? test-run) [(:seon.error/message test-run)] (proofs/test-run-errors test-run))
     :will-merge will-merge :tests tests :attention attention}))

(defn- basis!
  "What a turn's context is built from, recorded instead of its text (a model call stores its
  basis, not its prompt): the commit it reads (the run-ahead writes committed now, as the
  model call's wait would), the task, run and turn, the observations no db holds, the
  messages delivered since the last turn (taken: the turn is their reader; a message that is
  a fact is written on this branch first, the same fact root wrote on main, and the basis
  names it by id, so its text is read again from the commit), and the last evaluations'
  responses while no earlier turn shows them. A read that is not the run's turn (take?
  false: an observer's read of an agent its model drives) shows the inbox and leaves it, each
  message as it was sent."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id :int :int :seon.turn/origin :map :boolean] :seon.turn/basis]}
  [task run turn max-turns origin observed take?]
  (let [msgs (agent/inbox)
        {facts true others false} (if take? (group-by (comp some? :id) msgs) {false msgs})
        _ (when (seq facts)
            (agent/transact! (into [] (mapcat (fn [{:keys [id from to body]}] (task/message-tx id (name from) to body))) facts)))
        _ (agent/commit!)
        ;; taken once their facts are committed: a refused write leaves them in the inbox
        _ (when take? (agent/taken! (count msgs)))
        rs (when (or (= :outside origin) (zero? turn)) (last-evals))
        ev (when (seq rs) (context/evaluations-text rs))]
    (cond-> {:commit (d/commit-id (agent/db)) :task task :run run :turn turn :max-turns max-turns
             :origin origin :observed (cond-> (dissoc observed :merge?)
                                        (seq facts) (assoc :message-ids (mapv :id facts))
                                        ;; a send no fact holds (another agent's, until its sender writes one)
                                        (seq others) (assoc :messages (mapv (fn [{:keys [from body]}] {:from (name from) :body body}) others)))}
      (:merge? observed) (assoc :merge? true)
      ev (assoc :last-evals ev))))

(defn- act!
  "One model turn: the context of what was observed to the model, the reply's eval calls
  through the one REPL path, each answered as its own result, the turn's facts on the branch. Answers the loop state after it, or the
  provider error. The model call is an effect (my.fx :model, session FX1): root's model owner
  admits, performs and answers it; the API key never enters this process."
  {:malli/schema [:=> [:cat :map :map [:maybe :string]] [:or :map :map]]}
  [{:keys [turn tokens max-turns merge? run] :as st} {:keys [results tests-ms] :as obs} model-name]
  (let [t (:task obs)
        proofs (:proofs (:seon.task/accumulated t))
        t0 (profile/now-ns)
        ob (cond-> (observed obs) merge? (assoc :merge? true))
        last (get-in @agent/state [:turn :last])
        ;; the screen: its forms evaluated now, each recorded by the screen, so the request (and
        ;; any later reading of it) ends with their responses (seon.context/request)
        _ (swap! agent/state assoc-in [:turn :screen]
                 {:task (:seon.task/id t) :last last
                  :input (context/turn-input (agent/db) {:turn turn :max-turns max-turns :origin :model :observed ob :merge? merge?} last)})
        screen (vec (keep #(:record (repl/eval-string % {:origin :screen})) screen/forms))
        basis (cond-> (assoc (basis! (:seon.task/id t) run turn max-turns :model ob true) :agent (:key @agent/state))
                (seq screen) (assoc :screen screen))
        {:keys [block messages]} (context/render (agent/db) basis)
        context-ms (long (profile/ms-since t0))
        t1 (profile/now-ns)
        r (agent/fx! :model (cond-> {:messages messages :task (:seon.task/id t) :turn turn}
                              model-name (assoc :model model-name))
                     {})
        model-ms (long (profile/ms-since t1))
        c (:value r)]
    (if (contract/error? r)
      r
      (let [t2 (profile/now-ns)
            ;; an interrupt left from before this turn's evals is not theirs
            _ (intr/clear-pending!)
            ;; each eval call through the one REPL path, in order; its response is the call's
            ;; result; the reply's prose is the first call's
            ;; each eval's prose: the reply's text (on its first call) then the call's own, as the user reads it
            calls (vec (map-indexed (fn [i {:keys [id code prose]}]
                                      (let [content (when (and (zero? i) (not (str/blank? (:text c)))) (:text c))
                                            shown (not-empty (str/join "\n\n" (remove str/blank? [content prose])))]
                                        {:id id :code code :prose shown
                                         :response (repl/eval-string (:text (reader/forgive code))
                                                                     (cond-> {:origin :model :call-id id :call i}
                                                                       shown (assoc :prose shown)
                                                                       content (assoc :content content)
                                                                       (not (str/blank? prose)) (assoc :call-prose prose)
                                                                       (and (zero? i) (:reasoning c)) (assoc :reasoning (:reasoning c))))}))
                                    (:tool-calls c)))
            rs (mapv :response calls)
            eval-ms (long (profile/ms-since t2))
            _ (swap! agent/state assoc-in [:turn :evals] rs)
            used (:seon.turn/tokens (usage c))
            t3 (profile/now-ns)]
        ;; a reply with prose and no call is a message to the user: the turn ends, and the run
        ;; waits for the user (the design's tool_choice auto)
        ;; (a blank or cut reply is no message: the next turn reads the same state)
        (when (and (empty? calls) (not (str/blank? (:text c))) (not (:truncated? c))) (request-stop! :waiting (:text c)))
        (swap! agent/state assoc-in [:turn :last] (cond-> {:seon.turn/calls (mapv #(select-keys % [:id :code]) calls)
                                                           :prose (str/join "\n\n" (keep :prose calls))}
                                                    (:truncated? c) (assoc :seon.turn/truncated true)))
        ;; what the model read, as its basis (seon.context/render makes the text from it), what
        ;; it answered and how it stopped, which evals its calls are, and where the turn's time
        ;; went as this agent saw it
        (agent/transact! [{:db/id "run" :seon.run/id run}
                          (cond-> (merge (usage c) {:seon.turn/id (str run "/" turn) :seon.turn/run "run" :seon.turn/n turn
                                   :seon.turn/origin :model :seon.turn/basis basis
                                   :seon.turn/calls (mapv (fn [{:keys [id code response]}]
                                                            (cond-> {:id id :code code} (:record response) (assoc :eval (:record response))))
                                                          calls)
                                   :seon.turn/reply (:text c) :seon.turn/forms (count calls)
                                   :seon.turn/errors (count (filter :ex rs))
                                   :seon.turn/red (count (remove #(= :pass (val %)) (select-keys results proofs)))
                                   :seon.turn/ms (+ model-ms eval-ms)})
                            (:truncated? c) (assoc :seon.turn/truncated true)
                            (:finish-reason c) (assoc :seon.turn/finish (:finish-reason c))
                            (:model c) (assoc :seon.turn/model (:model c))
                            (:reasoning c) (assoc :seon.turn/reasoning (:reasoning c)))])
        (agent/transact! [{:seon.turn/id (str run "/" turn)
                           :seon.turn/split (cond-> {:context-ms context-ms :model-ms model-ms :eval-ms eval-ms
                                                     :record-ms (long (profile/ms-since t3))}
                                              tests-ms (assoc :tests-ms tests-ms))}])
        (cond-> (-> (assoc st :turn (inc turn) :tokens (when (and tokens used) (+ tokens used)) :calls (mapv :code calls))
                    ;; what this turn's block showed, by producer, and what it evaluated (waste)
                    (update :log (fnil conj []) {:n turn
                                                 :shown {'seon.docs/resolve-ref (set (keep :symbol (:references block)))
                                                         'seon.context/found (set (keep :symbol (:found block)))
                                                         'seon.context/current-state (set (map :test (:proofs block)))}
                                                 :items (mapv (fn [{:keys [code response]}] {:form code :error (:ex response)}) calls)}))
          merge? (update :merge-turns inc))))))

(defn context-block
  "The current-state block of task, as the model sees it on this branch now (the proofs run
  again): what an outside agent working the task reads, recorded as a turn of the run
  (origin :outside). It also makes this process work task as run, so the my.* functions act
  on it. take?: the read is the run's turn (its model is outside) and takes the messages
  delivered; else it is an observer's, and leaves them for the model's next turn."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id :boolean] :string]}
  [task run take?]
  (let [{:keys [plan-before]} (begin! task run)
        {:keys [tests-ms] :as obs} (observe task plan-before)
        n (get-in (swap! agent/state update-in [:turn :reads] (fnil inc 0)) [:turn :reads])
        t0 (profile/now-ns)
        basis (basis! task run n 1 :outside (assoc (observed obs) :merge? true) take?)
        text (context/reader-text (:messages (context/render (agent/db) basis)))]
    ;; the read is a turn of this run, as a model turn is: what it was built from and what it cost
    (agent/transact! [{:db/id "run" :seon.run/id run}
                      {:seon.turn/id (str run "/read-" n) :seon.turn/run "run" :seon.turn/n n
                       :seon.turn/origin :outside :seon.turn/basis basis
                       :seon.turn/split (cond-> {:context-ms (long (profile/ms-since t0))} tests-ms (assoc :tests-ms tests-ms))}])
    text))

(defn hand-back!
  "End run of task as an outside agent hands it back (seon.server's :done): the proofs run
  again, and the run's end written as the turn loop writes its own (end!: the live runtime
  staged, the end committed on the branch), :green when the done rule has no reason, else
  :red with the reasons. Its turns, model calls, tokens and time are the outside model's,
  unknown here, so absent (never 0). The judge decides from its own run of the proofs and the
  plan the run began with on main; this end is what the run says of itself. Without it the
  judge read no end and named the run :no-end. Answers the end."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id] :seon.turn/end]}
  [task run]
  (let [{:keys [plan-before]} (begin! task run)
        {:keys [reasons]} (observe task plan-before)]
    (end! {:run run} (if (empty? reasons) :green :red) (when (seq reasons) (r/explain-text reasons)) nil)))

(defn work!
  "Work task `task` as run `run` (the model `model` when set, else root's), at most
  max-turns model calls. A turn whose calls repeat the last turn's, made from the same
  observed state (the done rule's reasons and proof results), ends the run :no-progress, naming
  the code (root's research, lever 2: loops took 45% of tokens; the step E dry run's first
  real design repeated one pair of calls 18 times): the judge refines from that evidence.
  Answers the end ({:outcome :turns :model-calls :tokens :ms}); the same end is written on
  the branch. It first merges main into the branch (my.branch/merge-main!), as a merge task
  does again at each green."
  {:malli/schema [:=> [:cat [:map [:task :seon.task/id] [:run :seon.run/id] [:model {:optional true} :string]
                             [:max-turns pos-int?]]]
                  :seon.turn/end]}
  [{:keys [task run model max-turns]}]
  (let [{:keys [plan-before]} (begin! task run)
        t0-task (task/task (agent/db) task)
        merge? (= :merge (:seon.task/kind t0-task))
        merge-cfg (r/merge-limits t0-task)]
    ;; every run starts on main: a kept branch made before main moved merges it first, so
    ;; the branch's db, its base and this process hold one program (its tests' digests are the
    ;; program it runs); a branch at main's head brings nothing
    (branch/merge-main!)
    (loop [st {:turn 0 :tokens 0 :merge-turns 0 :t0 (profile/now-ns) :run run
               :max-turns max-turns :merge? merge?}]
      (let [{:keys [reasons unmerged] :as obs} (observe task plan-before)
            stop (get-in @agent/state [:turn :stop])
            hairy (when merge? (r/hairy (merge merge-cfg {:conflicts unmerged :task (:task obs) :merge-turns (:merge-turns st)})))]
        (cond
          ;; git's CI after the merge: green on the combined program; merge main again until
          ;; it brings nothing new
          (and (empty? reasons) merge?)
          (let [m (branch/merge-main!)]
            (if (and (empty? (:applied m)) (empty? (:conflicts m))) (end! st :green nil nil) (recur st)))
          (empty? reasons) (end! st :green nil nil)
          stop (end! st (:outcome stop) (:reason stop) nil)
          hairy (end! st :merge-failed (:reason hairy) nil)
          (>= (:turn st) max-turns) (end! st :cap (r/explain-text reasons) nil)
          :else (let [st' (act! st obs model)
                      seen [(select-keys obs [:reasons :results]) (:calls st')]]
                  (cond
                    (contract/error? st') (end! st :error (:seon.error/message st') st')
                    (= seen (:seen st)) (end! st' :no-progress (str "turn " (:turn st) " repeated turn " (dec (:turn st))
                                                                   "'s calls from the same state: " (str/join " " (:calls st'))) nil)
                    :else (recur (assoc st' :seen seen)))))))))
