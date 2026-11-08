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

  Root starts it by evaluating (seon.turn/work! '{...}) in the agent; its end is
  a fact on the agent's record on the branch (:seon.agent/outcome and the rest), which root's judge reads."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.ctx :as ctx] [seon.edn :as sedn] [seon.profile :as profile] [seon.intr :as intr] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str]
            [seon.agent :as agent] [seon.repl :as repl] [seon.task :as task] [seon.reconcile :as r]
            [seon.proofs :as proofs] [seon.context :as context] [seon.merge :as pm] [seon.problems :as problems]
            [seon.stage :as stage] [my.branch :as branch] [seon.reader :as reader] [seon.learn :as learn] [seon.config :as cfg] [seon.lib :as lib] [seon.print :as print]
            [seon.error :as err] [datahike.api :as d] [seon.db :as pdb] [seon.render :as render] [seon.model :as model] [clojure.data.json :as json]))

;; The turn loop's own facts live in the agent's one state map, under :turn: whether this
;; process is working a task, the last turn's evals and a stop a function requested.

(defn current-task
  "The id of the task this process works: the one root forked it for (seon.agent/init!), or
  nil for an agent forked with none."
  {:malli/schema [:=> [:cat] [:maybe :seon.task/id]]}
  [] (:task @agent/state))

(defn- begin!
  "This process works task: the task recorded as its agent's on its branch (seon.agent/begin-run!), and the
  turn state begun. The turn loop and an outside agent's context read begin it the same way."
  {:malli/schema [:=> [:cat :seon.task/id] :map]}
  [task]
  ;; the task is this agent's on its own branch too, and a fork's task with it, read after that write
  (agent/begin-run! task)
  (or (:turn @agent/state) (:turn (swap! agent/state assoc :turn {}))))

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

(defn- test-error!
  "Record on this agent's branch whether its test run test-run failed as a whole: the error as data on
  the agent (:seon.agent/test-error), retracted when a run answers;
  nothing written when it already says so (seon.context/status reads it)."
  {:malli/schema [:=> [:cat :any] :any]}
  [test-run]
  (let [k (:key @agent/state)
        held (:seon.agent/test-error (d/entity (agent/db) [:seon.agent/key k]))
        err (when (contract/error? test-run) (select-keys test-run [:seon.error/type :seon.error/operation :seon.error/message]))]
    (cond (and err (not= err held)) (agent/transact! [{:seon.agent/key k :seon.agent/test-error err}])
          (and held (not err)) (agent/transact! [[:db.fn/retractAttribute [:seon.agent/key k] :seon.agent/test-error]]))))

(defn- observe
  "What a turn starts from: the proofs run now by the test service (seon.prove: one run per branch
  and tx whoever asks, in a fork of this agent), their results facts on this branch
  (seon.agent/record-results!), a run that failed as a whole too (test-error!), and the task as the
  branch holds it. How the work stands is the status (seon.context/status), drawn from those
  facts (`status-now`): nothing here is held for it. {:test-run :task}."
  {:malli/schema [:=> [:cat :seon.task/id] :map]}
  [id]
  (let [;; the branch's own texts embedded first, so the plan's vector is the plan's now
        _ (agent/embed-written!)
        test-run (agent/prove!)]
    (test-error! test-run)
    {:test-run test-run :task (task/task (agent/db) id)}))

(defn- status-now
  "How the work stands now, the data a turn decides from: what the page's status component kept (seon.render/kept-of), one reaction for both, so a status nothing moved costs nothing; with none (it threw, or a layout places none) seon.context/status of the branch and @ctx. The branch may have changed
  what status calls, so a status that throws is its error, the turn's to give the agent (docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map), never an
  empty status: no reason is not done (AGENTS 9, unknown is not {})."
  {:malli/schema [:=> [:cat] [:or :map :seon.error/base]]}
  [] (try (or (render/kept-of (repl/page-walk) '[seon.render.ui/status])
              (context/status (agent/db) @ctx/ctx {}))
          (catch Throwable e
            (when (:interrupted (ex-data e)) (throw e))
            (agent/error-map e 'seon.turn/status-now))))

(defn agent-usage
  "What the model calls of the agent with key k spent, from their records on db (a model call is one fact, whichever way the turn
  ended): {:model-calls n :tokens t}, n the calls recorded (a turn that threw before its call made none), t their
  :seon.model-call/tokens summed, absent when no call reported its usage (unknown, never 0). A count carried
  through the turn loop lost every turn that threw after its call answered, and the turns stood for the calls. See docs/turn.md#the-turn-is-a-number."
  {:malli/schema [:=> [:cat :seon.host/db :string] [:map [:model-calls nat-int?] [:tokens {:optional true} :int]]]}
  [db k]
  ;; a call with no origin is a turn no request has been made for yet: a record named it (seon.task/call-ref)
  (let [calls (map #(d/entity db %) (d/q '[:find [?m ...] :in $ ?k :where [?a :seon.agent/key ?k] [?m :seon.model-call/agent ?a] [?m :seon.model-call/origin _]] db k))
        ts (keep :seon.model-call/tokens calls)]
    (cond-> {:model-calls (count calls)} (seq ts) (assoc :tokens (reduce + ts)))))

(defn- end!
  "The agent's end: the live runtime staged onto the branch, the end written there (with
  the error that ended it and the turns' waste, as data: session D2), and the branch committed
  (a turn's end commits the agent's run-ahead writes)."
  {:malli/schema [:=> [:cat :map :seon.agent/outcome [:maybe :string] [:maybe :map]] :seon.turn/end]}
  [{:keys [turn t0 log]} outcome reason error]
  (stage/stage!)
  (let [{:keys [tokens model-calls]} (agent-usage (agent/db) (:key @agent/state))]
  (agent/write-end! {:outcome outcome :reason reason :error error
                     :waste (learn/summary (learn/waste (or log []) (learn/final-symbols (or log []))))})
  ;; what was not measured is absent, never 0: an outside agent counts no model calls and no loop time
  (cond-> {:outcome outcome :results {} :uncontracted #{}}
    turn (assoc :turns turn :model-calls model-calls)
    t0 (assoc :ms (profile/ms-since t0))
    tokens (assoc :tokens tokens)
    reason (assoc :reason reason))))

(defn usage
  "What completion c cost, as the turn's model call records it (:seon.model-call/*): its tokens (prompt and completion), and the
  prompt tokens the provider's prefix cache served and did not; each absent when the provider
  did not report it (unknown, never 0)."
  {:malli/schema [:=> [:cat :map] [:map [:seon.model-call/tokens {:optional true} :int]
                                   [:seon.model-call/cache-hit-tokens {:optional true} :int] [:seon.model-call/cache-miss-tokens {:optional true} :int]]]}
  [{:keys [prompt-tokens completion-tokens cache-hit-tokens cache-miss-tokens]}]
  (cond-> {}
    (and prompt-tokens completion-tokens) (assoc :seon.model-call/tokens (+ prompt-tokens completion-tokens))
    cache-hit-tokens (assoc :seon.model-call/cache-hit-tokens cache-hit-tokens)
    cache-miss-tokens (assoc :seon.model-call/cache-miss-tokens cache-miss-tokens)))

(defn- deliver!
  "Deliver what was sent since the last turn onto this branch as turn n's (the turn that reads it: the next one,
  docs/turn.md#the-turn-is-a-number), before the turn's request is made, so
  each sits in the conversation before the page that shows what came after it: the messages main holds
  to this agent (a person's, root's at a stage boundary or a refused verdict, another agent's:
  seon.agent/deliver!, the entity root's writer wrote on main, with what root wrote of the agent's own
  entities). Its entity on the branch is its delivery, and the record's
  messages read it (seon.context/turn-members); nothing is held for the turn's basis. An effect's outcome
  (:from :fx, in the inbox) is no message: the eval that asked holds its answer. A read that is not the
  run's turn (take? false: an observer's read of an agent its model drives) delivers nothing."
  {:malli/schema [:=> [:cat :boolean nat-int?] :nil]}
  [take? n]
  (when take?
    (let [fx (count (agent/inbox))]
      (agent/deliver! n)
      ;; the outcomes seen are let go
      (agent/taken! fx)))
  nil)

(defn- tell-error!
  "Error map em given to the agent as a message from seon on its branch, saying what Seon was doing
  (what): its next request reads it as any message. nil."
  {:malli/schema [:=> [:cat :seon.error/base :string] :nil]}
  [em what]
  (let [k (:key @agent/state)
        body (print/text (vary-meta {:seon-threw what :error em} assoc :seon.print/full true))]
    ;; the next turn's: the turn that reads it, so it sits after everything this turn wrote
    (agent/transact! (agent/own-message-tx (str k "/turn-error/" (random-uuid)) "seon" body (System/currentTimeMillis) (inc (agent/turn-now))))
    (agent/commit!)
    nil))

(defn- turn-error!
  "A turn that threw, or one whose status threw (st, its state; em, the error map, seon.agent/error-map) given to the agent as data: em as a message
  from seon on its branch, which its
  next turn reads as any message, counted in :threw. A turn whose status threw still makes its model
  call that turn, the message in its request; a throw counts the turn, and work! ends the agent's
  work :error on the second throw in a row with no model turn between (its request cannot be drawn,
  so no model turn could read the message). Answers st after it, :error its error map. The turn loop's entrant boundary (docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map):
  Seon's code meets the agent's program each turn (the agent may redefine a function of Seon's own, scan for one, and the turn then throws inside it)."
  {:malli/schema [:=> [:cat :map :seon.error/base] :map]}
  [st em]
  (tell-error! em "your turn threw: Seon's code ran with the program on your branch as it stands now (Seon's own code and what you changed of it), so the cause may be either")
  (-> st (update :threw (fnil inc 0)) (assoc :error em)))

(defn- basis!
  "What a turn's context is built from, recorded instead of its text (a model call stores its
  basis, not its prompt): the commit it reads (the run-ahead writes committed now, as the
  model call's wait would), the task and turn (its number: docs/turn.md#the-turn-is-a-number). Everything the request shows is a fact of that
  commit: how the work stands (seon.context/status), what the turn was delivered (deliver!)."
  {:malli/schema [:=> [:cat :seon.task/id :int :int :seon.model-call/origin :boolean] :seon.model-call/basis]}
  [task turn max-turns origin merge?]
  (agent/commit!)
  (cond-> {:commit (d/commit-id (agent/db)) :t (:max-tx (agent/db)) :task task :turn turn :max-turns max-turns :origin origin}
    merge? (assoc :merge? true)))

(defn- evaluated!
  "Code through the one REPL path (seon.repl/eval-string, req) and its record committed to the
  branch as it lands (seon.agent/commit!), so a page shows each eval then, not at the turn's end
  (design section 11, item 1). Answers the response."
  {:malli/schema [:=> [:cat :string :map] :seon.repl/answer]}
  [code req]
  (let [r (repl/eval-string code req)]
    (agent/commit!)
    ;; the record of this eval went with the dropped writes, so the next turn would find no trace of the call: its error is a
    ;; message from seon on the branch (tell-error!) as well as under the answer's :unrecorded
    (let [e (agent/take-refused!)]
      (when e (tell-error! e "the branch refused the writes of your last eval at its commit; they were dropped"))
      (cond-> r e (assoc :unrecorded e)))))

(defn cache-miss
  "A :seon.turn/cache-miss when a request's prompt cache hit falls more than one block short of
  the prefix it shares with the request before it in its run, else nil. The prefix is the leading
  messages both requests hold equal; its tokens are the provider's own prompt count (usage),
  apportioned by the one sizer's share of them (seon.context/messages-tokens) and rounded down to
  the provider's block (:seon.config.ai/cache-block-tokens, 0: no check). Measured on 94 recorded
  turn pairs: a hit is 0.87-1.12 of the shared prefix so sized, and none is short."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]] [:sequential :map] :map nat-int?] [:maybe :map]]}
  [prev messages {:keys [prompt-tokens cache-hit-tokens]} block]
  (when (and (seq prev) (pos? block) prompt-tokens cache-hit-tokens)
    (let [ms (vec messages)
          shared (count (take-while true? (map = prev ms)))]
      ;; nothing shared (the first message differs): no prefix the cache could serve
      (when (pos? shared)
        (let [expected (long (* prompt-tokens (/ (double (context/messages-tokens (subvec ms 0 shared)))
                                                 (context/messages-tokens ms))))
              floor (* block (quot expected block))]
          (when (< cache-hit-tokens (- floor block))
        (contract/error :seon.turn/cache-miss #'seon.turn/cache-miss
                        (str "the prompt cache hit " cache-hit-tokens " tokens of the " expected " this request shares with the one before")
                            {:hit cache-hit-tokens :expected expected :block block :shared shared :messages (count ms)})))))))

(defn- main-base-tx
  "The transaction that records on the agent, before its request is drawn, the main and merge base it
  last heard (the commits its page's branch views read): what the page and a replay draw; none when
  nothing moved. :seon.agent/main-program stays at the last commit whose content differs from the main it heard, so a
  reader of what main's program holds runs again only when that moved (docs/render.md#how-the-work-stands)."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [db (agent/db) k (:key @agent/state) e (some->> (pdb/eid db :seon.agent/key k) (d/entity db))
        main (:main @agent/state)
        now (into {} (remove (comp nil? val)) {:seon.agent/main-commit (some-> main d/commit-id)
                                                 :seon.agent/main-program (when main (pm/content-commit (some->> (:seon.agent/main-program e) (d/commit-as-db (pdb/store-of db))) main))
                                                 :seon.agent/base-commit (some-> (:base @agent/state) d/commit-id)})
        moved (into {} (remove (fn [[a v]] (= v (get e a)))) now)]
    (if (seq moved) [(assoc moved :seon.agent/key k)] [])))

(defn- show-page!
  "Seon shows the agent its page as an eval of its own (docs/render.md#the-render-is-an-eval), before the turn's number moves, so
  the eval takes the turn in force and falls at the end of the turn before: the one its walk owes (seon.render/page-call), nothing moved, no eval. Recorded like any eval (origin :insert, by seon: seon.repl/insert!), so a render that
  throws is its error value in the conversation and the turn goes on. Answers its response, in a vector, for one who is told as it happens (`outside-reply!`). See docs/turn.md#an-eval-can-be-inserted."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [w (repl/page-walk)
        ;; a tree with no page to show (a pattern that holds none of it) is shown nothing
        call (some-> (render/page-call w (render/page-lists w)) context/render-calls)]
    (if call (repl/insert! "seon" [call]) [])))

(defn- open-turn!
  "Turn n of task opens: what it takes, the page shown if it moved, then the turn (the agent's number and its model call's first facts, with the main and merge base it heard), then its
  basis: the commit that holds them, so everything its request and its provider are made from is a fact of that commit
  (seon.context/wire-turn makes them again there). A model's turn and an outside agent's reply open alike. Answers [its model call's id, its basis, the page eval it ran]. See docs/turn.md#the-turn-is-a-number."
  {:malli/schema [:=> [:cat :seon.task/id :int [:maybe :int] :boolean [:maybe :string]] [:tuple :string :map [:vector :map]]]}
  [task n max-turns merge? model-name]
  (let [k (:key @agent/state) id (str k "/" n)]
    (deliver! true n)
    (let [shown (show-page!)]
      (agent/transact! (into [{:seon.agent/key k :seon.agent/turn n}
                              {:seon.model-call/id id :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n n :seon.model-call/origin :model}]
                             (main-base-tx)))
      [id (cond-> (assoc (basis! task n max-turns :model merge?) :agent k) model-name (assoc :model model-name)) shown])))

(defn- act!
  "One model turn: the page shown to the agent if it moved (`show-page!`), the agent's turn number moves (docs/turn.md#the-turn-is-a-number), the agent's context (its one tree read by seon.context/request, the page among it as evals; the status s is the turn's own) to the model, the reply's eval calls
  through the one REPL path, each answered as its own result, the turn's facts on its model call on the branch. Answers the loop state after it, or the
  provider error. The model call is an effect (my.fx :model, session FX1): root's model owner
  admits, performs and answers it; the API key never enters this process."
  {:malli/schema [:=> [:cat :map :map :map [:maybe :string]] [:or :map :map]]}
  [{:keys [turn max-turns merge? whole?] :as st} obs s model-name]
  (let [t (:task obs)
        ;; the turn this is: the agent's number, one past the one in force, which the messages taken now and every record
        ;; this turn writes take
        n (inc (agent/turn-now))
        k (:key @agent/state)
        [id basis _] (open-turn! (:seon.task/id t) n max-turns (boolean merge?) model-name)
        ;; the provider as the config stands at the basis (main's as the turn heard it, over the task's own), never one copied at boot
        provider (context/provider-of (agent/db) basis)
        _ (agent/transact! [{:seon.model-call/id id :seon.model-call/basis basis}])
        _ (agent/commit!)
        ;; the request is the agent's records, the page among them as evals (show-page!): made on the db that holds them
        db (agent/db)
        {:keys [messages]} (context/render db basis {:walk (repl/page-walk)})
        ;; a hash of the bytes sent, which the debug page compares with its redraw
        _ (agent/transact! [{:seon.model-call/id id :seon.model-call/sent-hash (lib/digest (sedn/encode messages))}])
        _ (agent/commit!)
        t1 (profile/now-ns)
        r (agent/fx! :model {:messages messages :task (:seon.task/id t) :turn turn :provider provider} {})
        model-ms (long (profile/ms-since t1))
        c (:value r)
        ;; the request before this one in its run: what the cache check compares (cache-miss)
        prev (:requests @agent/state)]
    (if (contract/error? r)
      ;; the call failed: its typed error on the model call (an error is data), and to the run's end
      (do (agent/transact! [{:seon.model-call/id id :seon.model-call/error r :seon.model-call/ms model-ms}])
          r)
      (let [;; a request answered is the next one's baseline (a failed call stored no prefix); a short
            ;; hit is the model call's outcome, an error this agent folds as :effect (seon.error/note)
            _ (swap! agent/state assoc :requests messages)
            _ (when-let [miss (cache-miss prev messages c (cfg/value :seon.config.ai/cache-block-tokens {:task t :model model-name}))]
                (swap! agent/state update :errors err/note miss :effect id (System/currentTimeMillis)))
            ;; what it answered and how it stopped (its reply, reasoning and finish), the reply's usage and model (seon.model-call, beside the basis it
            ;; read: seon.context/render makes the text from it), as soon as it answered: an eval of this reply that never ends leaves the call
            ;; recorded and its cost counted, never reading as a call nobody answered (deepseek-run6's records agent)
            _ (agent/transact! [(cond-> (merge (usage c) {:seon.model-call/id id :seon.model-call/reply (:text c) :seon.model-call/ms model-ms})
                                  (:truncated? c) (assoc :seon.model-call/truncated true)
                                  (:finish-reason c) (assoc :seon.model-call/finish (:finish-reason c))
                                  (:reasoning c) (assoc :seon.model-call/reasoning (:reasoning c))
                                  (:model c) (assoc :seon.model-call/model (:model c)))])
            t2 (profile/now-ns)
            ;; an interrupt left from before this turn's evals is not theirs
            _ (intr/clear-pending!)
            ;; each eval call through the one REPL path, in order; its response is the call's result. The reply's text and
            ;; reasoning are the model call's own (below), never copied onto an eval
            ;; a call with no intent or no form, or after one that threw or answered an error, is not run: its one
            ;; result says why (seon.repl/unrun, docs/prompt.md)
            calls (repl/run-calls (vec (:tool-calls c)) (fn [id] {:origin :model :call-id id}) evaluated!)
            rs (mapv :response calls)
            eval-ms (long (profile/ms-since t2))
            _ (swap! agent/state assoc-in [:turn :evals] rs)]
        ;; a reply with text and no call pauses the agent until someone answers, whatever its proofs say
        ;; (docs/decisions.md#a-reply-with-no-calls-pauses-the-agent-whatever-its-proofs-say)
        ;; (a blank or cut reply is no message: the next turn reads the same state)
        (when (and (empty? calls) (not (str/blank? (:text c))) (not (:truncated? c))) (request-stop! :waiting (:text c)))
        ;; its evals' counts, and the time with them
        (agent/transact! [{:seon.model-call/id id :seon.model-call/forms (count calls)
                           :seon.model-call/errors (count (filter :ex (map :response (remove :not-run calls))))
                           :seon.model-call/ms (+ model-ms eval-ms)}])
        (cond-> (-> (assoc st :turn (inc turn))
                    ;; what this turn's parts showed, by producer, and what it evaluated (waste)
                    (update :log (fnil conj []) {:n turn
                                                 :shown {'seon.render.ui/cited (set (keep :seon.cite/name (task/task-cites db (:seon.task/id t))))
                                                         'seon.context/status (set (map :test (:proofs s)))}
                                                 :items (mapv (fn [{:keys [code response]}] {:form code :error (:ex response)}) calls)}))
          ;; a merge turn: each turn of a merge task; a whole task's own work comes first, so
          ;; only a turn with what both sides changed still to resolve
          (and merge? (or (not whole?) (seq (:unmerged s)))) (update :merge-turns inc))))))

(defn context-block
  "The request of task as a model is sent it on this branch now: a read runs no proof and waits on none (their verdicts are the status's). What an
  outside agent working the task reads, the system prompt, its conversation and its page, as one text, recorded as a read of the agent
  (origin :outside, id \"<agent key>/read-<k>\", k the agent's own count of reads: a read moves no turn,
  docs/turn.md#the-turn-is-a-number). It also makes this process work task as run, so the my.* functions act
  on it. take?: the read is the run's turn (its
  model is outside): it takes the messages delivered and Seon shows the page as an eval, as before a model's turn; else it is an
  observer's, and leaves both for the model's next turn. wire?: the request body as JSON, the bytes a provider is sent
  (seon.context/wire-turn over the read's basis), for the text a person reads. See docs/server.md#working-a-task-from-a-worktree."
  {:malli/schema [:function [:=> [:cat :seon.task/id :boolean] :string] [:=> [:cat :seon.task/id :boolean :boolean] :string]]}
  ([task take?] (context-block task take? false))
  ([task take? wire?]
   (let [_ (begin! task)
         k (:key @agent/state)
         ;; the agent's own count of reads, from the branch: a process, a run or a wake does not restart it
         n (inc (or (d/q '[:find (max ?n) . :in $ ?a :where [?m :seon.model-call/agent ?a] [?m :seon.model-call/origin :outside] [?m :seon.model-call/n ?n]]
                         (agent/db) (pdb/eid (agent/db) :seon.agent/key k))
                    0))
         turn (inc (agent/turn-now))
         ;; an observer's read records nothing on the branch beyond the read and leaves the print cache
         _ (deliver! take? turn)
         ;; the main and merge base the read heard, decided as a model turn's are, before its request is drawn
         _ (when take? (when-let [tx (not-empty (main-base-tx))] (agent/transact! tx) (agent/commit!)))
         _ (when take? (show-page!))
         ;; the run's own max-turns, as a model turn's basis holds it (the agent's on main, else the config's: root passes them alike)
         max-turns (or (:seon.agent/max-turns (d/entity (agent/db) [:seon.agent/key k]))
                       (cfg/value :seon.config.agent/max-turns))
         ;; the turn a model would take now: every record before it is in the request
         basis (assoc (basis! task turn max-turns :outside true) :agent k)
         walk (repl/page-walk)
         db (agent/db)
         {:keys [messages]} (context/render db basis {:walk walk})
         ;; as a person reads it: the system prompt, then each record as the call made and its result
         text (context/read-text db basis messages wire?)]
     ;; the read is a model call of this agent, as a model turn's is: what it was built from and what it cost
     (agent/transact! [(cond-> {:seon.model-call/id (str k "/read-" n) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n n
                                :seon.model-call/origin :outside :seon.model-call/basis basis}
                         true (assoc :seon.model-call/sent-hash (lib/digest (sedn/encode messages))))])
     text)))

(defn outside-reply!
  "An outside agent's seon_eval on this process, one reply as a model's is: its evals (`seon.model/call-evals`) through the one REPL path in order, stopping at the first that throws, the rest answered not-run. With turn?, the agent works task with its model outside: the reply is its turn, its message on its model call, opened as a model's turn is, so it reads as a model's in the conversation, and its answer is what a model's next request would add: any page eval Seon ran, before and after its evals. Otherwise it is someone at the REPL of an agent its own model drives: no turn moves. Answers each response as the wire carries it, printed as EDN for the server (`seon.server/op-answer`). See docs/turn.md#an-outside-agent."
  {:malli/schema [:=> [:cat [:maybe :seon.task/id] [:maybe :string] :any :boolean] :nil]}
  [task message evals turn?]
  (let [n (when turn? (begin! task) (inc (agent/turn-now)))
        ;; as a model's turn does (work!): main moved, its program comes in now, so an outside agent is on main's code too and a conflict is an unmerged entry it sees
        _ (when (and turn? (agent/main-moved? (:base @agent/state) (agent/heard-main))) (branch/merge-main!))
        [id basis before] (when turn? (open-turn! task n 1 false nil))
        _ (when turn? (agent/transact! [{:seon.model-call/id id :seon.model-call/basis basis}]) (agent/commit!))
        calls (model/call-evals {"message-md" message "evals" evals} (str "outside-" (or n "repl")))
        rs (repl/run-calls (mapv #(dissoc % :message-md) calls) (fn [cid] {:origin :outside :call-id cid}) evaluated!)]
    (when turn?
      (agent/transact! [{:seon.model-call/id id :seon.model-call/forms (count rs) :seon.model-call/model "outside"
                         :seon.model-call/reply (or message "")
                         :seon.model-call/errors (count (filter :ex (map :response (remove :not-run rs))))}])
      (agent/commit!))
    (let [after (when turn? (show-page!))
          page #(assoc % :text (print/response-text (dissoc % :fns :record)))]
      (print (sedn/encode (mapv agent/on-the-wire (concat (map page before) (map :response rs) (map page after))))))
    nil))

(defn hand-back-end
  "How an outside agent's run ends: [outcome reason] from the stop its agent requested
  (my.task/fail! makes it :failed, with the reason it gave) and the done rule's reasons. A task
  the agent failed ends :failed whatever the proofs say, as the turn loop ends it (work!); else
  :green when the done rule has no reason, else :red with the reasons."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :any]] [:tuple :seon.agent/outcome [:maybe :string]]]}
  [stop reasons]
  (cond (= :failed (:outcome stop)) [:failed (:reason stop)]
        (empty? reasons) [:green nil]
        :else [:red (r/explain-text reasons)]))

(defn- unjudged-reason
  "What a green end of status s leaves unjudged, in words, or nil (seon.task/unjudged): the end says it, and the judge decides as before."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{:keys [unjudged]}]
  (when unjudged (str "process tests not judged: " (str/join ", " (:tests unjudged)) ": " (:why unjudged))))

(defn- judged
  "Status s, the tests its change reaches judged when s is clear: its proofs pass, so the tests its change reaches run now, as the
  judge judges them, the process tests among them on the gate's checkout (agent/judge!, their results facts on the branch), and the
  status is read again from the branch, before it is done or merges (the owner's ruling: a turn runs the proofs, the reach runs once
  they are green). A run that ended green on its proofs alone left the judge to find the reds its change made, with no turn left to fix them."
  {:malli/schema [:=> [:cat [:or :map :seon.error/base]] [:or :map :seon.error/base]]}
  [s]
  (if (and (not (contract/error? s)) (empty? (:reasons s)))
    (do (test-error! (agent/judge! #{} true)) (status-now))
    s))

(defn hand-back!
  "End run of task as an outside agent hands it back (seon.server's :done): the proofs run
  again, and the run's end written as the turn loop writes its own (end!: the live runtime
  staged, the end committed on the branch): hand-back-end says how it ends. Its turns, model calls, tokens and time are the outside model's,
  unknown here, so absent (never 0). The judge decides from its own run of the proofs and the
  plan the run began with on main; this end is what the run says of itself. Without it the
  judge read no end and named the run :no-end. Answers the end."
  {:malli/schema [:=> [:cat :seon.task/id] :seon.turn/end]}
  [task]
  (let [;; read before begin!, which starts the turn state anew when it has none yet
        stop (get-in @agent/state [:turn :stop])
        _ (begin! task)
        ;; main moved since its last merge: merged now, as a green turn's is, so green work is never judged for being behind; a conflict is an unmerged entry, a reason the status says
        _ (when (agent/main-moved? (:base @agent/state) (agent/heard-main)) (branch/merge-main!))
        _ (observe task)
        s (judged (status-now))
        [outcome reason] (if (contract/error? s) [:error (:seon.error/message s)] (let [[o why] (hand-back-end stop (:reasons s))] [o (or why (unjudged-reason s))]))]
    (end! {} outcome reason (when (contract/error? s) s))))

(defn work!
  "Work task `task` (the model `model` when set, else root's), until the agent's turn reaches
  max-turns (agent-wide: its :seon.agent/turn against its budget, which root passes from the agent's own on main).
  Answers the end ({:outcome :turns :model-calls :tokens :ms}); the same end is written on
  the branch. It first merges main into the branch (my.branch/merge-main!), as a merge task
  does again at each green."
  {:malli/schema [:=> [:cat [:map [:task :seon.task/id] [:model {:optional true} :string]
                             [:max-turns pos-int?]]]
                  :seon.turn/end]}
  [{:keys [task model] :as arg}]
  (let [;; each wake begins its turn state; the page it last showed stands, so a wake is told only what moved since
        _ (swap! agent/state assoc :turn {} :requests nil)
        _ (agent/clear-end!)
        _ (begin! task)
        ;; the agent's own budget (:seon.agent/max-turns on main, raised to extend it; root passes it) bounds the loop, and is
        ;; the cap its page's turn line reads (seon.render.ui/agent-state)
        max-turns (:max-turns arg)
        _ (when-not (= max-turns (:seon.agent/max-turns (d/entity (agent/db) [:seon.agent/key (:key @agent/state)])))
            (agent/transact! [{:seon.agent/key (:key @agent/state) :seon.agent/max-turns max-turns}]))
        t0-task (task/task (agent/db) task)
        merge? (task/merges? (:seon.task/kind t0-task))
        merge-cfg (r/merge-limits t0-task)]
    ;; every run starts on main: a kept branch made before main moved merges it first, so
    ;; the branch's db, its base and this process hold one program (its tests' digests are the
    ;; program it runs); a branch at main's head brings nothing
    (branch/merge-main!)
    (loop [st {:turn 0 :merge-turns 0 :t0 (profile/now-ns) :whole? (= :whole (:seon.task/kind t0-task))
               :max-turns max-turns :merge? merge?}]
      ;; each turn under the branch's schemas as they are now (repl/with-branch-schemas): work! is one eval,
      ;; bound when it began, and a schema the agent registers since must be the one Seon's code checks its
      ;; values against (a block registered since would otherwise meet the old :seon.ref/block in seon.ref/sections and
      ;; end the loop); a turn answers its end, or [::next st] to go on
      ;; the turn answers what comes next, [::next st] or [::end st outcome reason error]: its draw, its model call
      ;; and its evals are inside the boundary, since every one runs Seon's code with the program on the
      ;; agent's branch, Seon's own code included, which the agent may change (a registered
      ;; :seon.ref/block re-armed seon.ref/scan's contract, which the request's own scan then broke); a throw is
      ;; the agent's to read, as data, and the loop goes on (turn-error!), root's interrupt passing as a stop.
      ;; end! is outside it, run once on the turn's answer, so a failing end is never taken for a turn
      (let [ended (fn [st outcome reason err] [::end st outcome reason err])
            next-of (fn [st'] (if (contract/error? st') [::end st :error (:seon.error/message st') st'] [::next (dissoc st' :threw :error)]))
            ;; the request the last answered model call sent: a turn whose own call answered replaces it
            asked (:requests @agent/state)
            step (repl/with-branch-schemas
                   (fn []
                     (try
                       ;; main moved since the last turn: merge it in now, so the agent works on main's program and
                       ;; wiki as they are and a conflict is an unmerged entry it sees and resolves before it can
                       ;; merge (the owner, 2026-10-30: "updates as often as we can")
                       (when (agent/main-moved? (:base @agent/state) (agent/heard-main)) (branch/merge-main!))
                       (let [obs (observe task)
                             ;; how the work stands (one computation a turn, the page's component draws its own)
                             s (status-now)
                             s (judged s)
                             no-status? (contract/error? s)
                             reasons (:reasons s)
                             stop (get-in @agent/state [:turn :stop])
                             ;; what the task references, as facts (its proofs, its plan's cites): never its plan re-parsed
                             ;; here, by a seon.ref/scan the agent may have broken (a broken scan would end the run :error)
                             hairy (when (and merge? (not (contract/error? s)))
                                     (r/hairy (merge merge-cfg {:conflicts (vec (:unmerged s)) :merge-turns (:merge-turns st)
                                                                :refs (into (set (:proofs (:seon.task/accumulated (:task obs))))
                                                                            (comp (keep :seon.cite/name) (filter qualified-symbol?))
                                                                            (task/task-cites (agent/db) task))})))]
                         (cond
                           ;; git's CI after the merge: green on the combined program; merge main again until
                           ;; it brings nothing new, a work task's run as a merge's, so it ends on main as main is
                           ;; (a green run that ended behind main's newer merges was judged behind: swarm's s9)
                           (and (not no-status?) (empty? reasons))
                           (let [m (branch/merge-main!)]
                             (if (and (empty? (:applied m)) (empty? (:conflicts m))) (ended st :green (unjudged-reason s) nil) [::next st]))
                           ;; the agent's own fail! is its verdict, at the cap too; a wait on the last turn is
                           ;; the budget spent, so the run is :cap, never :waiting (its session parks either way)
                           (= :failed (:outcome stop)) (ended st :failed (:reason stop) nil)
                           hairy (ended st :merge-failed (:reason hairy) nil)
                           ;; the one check of the cap, before any model call: every turn reaches it, one with no
                           ;; status and one after a turn that threw included (a change that broke
                           ;; the status would otherwise let the run make model calls past the cap)
                           (>= (+ (agent/turn-now) (:thrown st 0)) max-turns) (if no-status?
                                                       (ended st :cap (str "no status at the cap: " (:seon.error/message s)) s)
                                                       (ended st :cap (r/explain-text reasons) nil))
                           stop (ended st (:outcome stop) (:reason stop) nil)
                           ;; its status threw (the agent's program broke it): the agent reads why, this turn
                           no-status? (next-of (act! (turn-error! st s) obs s model))
                           :else (next-of (act! st obs s model))))
                       (catch Throwable e
                         (if (:interrupted (ex-data e))
                           (throw e)
                           ;; the second in a row with no model turn between ends its work with that error: a turn
                           ;; whose model call answered before it threw was the agent's turn, so the count starts again
                           (let [called? (not (identical? asked (:requests @agent/state)))
                                 st' (cond-> (-> (turn-error! st (agent/error-map e 'seon.turn/work!)) (update :turn inc) (update :thrown (fnil inc 0)))
                                       called? (assoc :threw 1))]
                             ;; a turn that threw counts against the cap, which the next turn checks
                             (if (< 1 (:threw st'))
                               (ended st' :error (str "two turns in a row threw: " (:seon.error/message (:error st'))) (:error st'))
                               [::next st'])))))))]
        (case (first step)
          ::next (recur (second step))
          ::end (let [[_ st outcome reason err] step] (end! st outcome reason err)))))))
