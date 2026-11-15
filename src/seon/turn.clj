(ns seon.turn
  "The agent's turn loop, inside its own process (sessions R1 and M1's proto.agents.turn). A
  turn observes the branch (the proofs run in a fork of this agent, the done rule), and
  either ends the run or acts: the context goes to the model (one interop edge), each of
  the reply's calls of the one tool, repl, goes through the one REPL path
  (seon.repl/eval-string: read, evaluate, record) and its answer is that call's result, which
  the next model call reads, and the turn's facts are written on the branch. The loop repeats until the done
  rule has no reason, a function ends it (my.turn/wait, my.task/fail!) or the turns run
  out. All green at the start ends it with no model call.

  A merge task's agent first merges main into the branch (no model call) and merges main
  again each time all is green, until a merge brings nothing new; it stops early when the
  merge is too hairy (seon.reconcile/hairy). What its evals did is on the branch as each ran
  (seon.repl/wrap-record), so the next agent on the branch holds it.

  Root starts it by evaluating (seon.turn/work! '{...}) in the agent; its end is
  a fact on the agent's record on the branch (:seon.agent/outcome and the rest), which root's judge reads."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.ctx :as ctx] [seon.edn :as sedn] [seon.profile :as profile] [seon.intr :as intr] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str]
            [seon.agent :as agent] [seon.repl :as repl] [seon.task :as task] [seon.reconcile :as r]
            [seon.proofs :as proofs] [seon.ctx.status :as status] [seon.ctx.wire :as wire] [seon.merge :as pm] [seon.problems :as problems]
            [my.branch :as branch] [seon.reader :as reader] [seon.learn :as learn] [seon.config :as cfg] [seon.lib :as lib] [seon.print :as print]
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
  nothing written when it already says so (seon.ctx.status/status reads it)."
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
  branch holds it. How the work stands is the status (seon.ctx.status/status), drawn from those
  facts (`status-now`): nothing here is held for it. {:test-run :task}."
  {:malli/schema [:=> [:cat :seon.task/id] :map]}
  [id]
  (let [;; the branch's own texts embedded first, so the plan's vector is the plan's now
        _ (agent/embed-written!)
        test-run (agent/prove!)]
    (test-error! test-run)
    {:test-run test-run :task (task/task (agent/db) id)}))

(defn- status-now
  "How the work stands now, the data a turn decides from: the status the agent's held page walk kept (seon.render/kept-of), so a warm
  turn computes it once for the page and the decision; with no walk held (a process started cold: a re-attach, a re-fork, a hand-back)
  or none kept there, seon.ctx.status/status directly, never a cold draw of the page (a long agent's cold page diffs every turn: lb76,
  144 turns, past 20G ticks; its status alone 228M). A status that throws is its error, the turn's to give the agent
  (docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map), never an empty status. See docs/turn.md#act."
  {:malli/schema [:=> [:cat] [:or :map :seon.error/base]]}
  [] (try (or (when (:walk @agent/state) (render/kept-of (repl/page-walk) '[seon.render.ui/status]))
              (status/status (agent/db) @ctx/ctx {}))
          ;; root's interrupt (a ThreadDeath, seon.intr) passes: it is no status error
          (catch Exception e
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
  "The agent's end: the end written on its branch (with
  the error that ended it and the turns' waste, as data: session D2), and the branch committed
  (a turn's end commits the agent's run-ahead writes)."
  {:malli/schema [:=> [:cat :map :seon.agent/outcome [:maybe :string] [:maybe :map]] :seon.turn/end]}
  [{:keys [turn t0]} outcome reason error]
  (let [db (agent/db) k (:key @agent/state)
        {:keys [tokens model-calls]} (agent-usage db k)
        ;; the run as its branch recorded it, whole across a restart (seon.learn/turns-of), and what its context showed: its task's
        ;; cites and proofs
        tid (some-> (d/entity db [:seon.agent/key k]) :seon.agent/task :seon.task/id)
        shown (if tid {'seon.render.ui/cite (set (keep task/cited-name (task/task-cites db tid)))
                       'seon.ctx.status/status (set (:seon.task/proofs (task/task db tid)))}
                  {})
        turns (learn/turns-of db k shown)]
  (agent/write-end! {:outcome outcome :reason reason :error error
                     :waste (learn/summary (learn/waste turns (learn/final-symbols db turns)))})
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
  "Deliver what was sent since the last turn onto this branch (the turn that reads it is derived, seon.ctx/placed-messages,
  docs/turn.md#the-turn-is-a-number), before the turn's request is made, so
  each sits in the conversation before the page that shows what came after it: the messages main holds
  to this agent (a person's, root's at a stage boundary or a refused verdict, another agent's:
  seon.agent/deliver!, the entity root's writer wrote on main, with what root wrote of the agent's own
  entities). Its entity on the branch is its delivery, and the record's
  messages read it (seon.ctx/placed-messages); nothing is held for the turn's basis. An effect's outcome
  (:from :fx, in the inbox) is no message: the eval that asked holds its answer. A read that is not the
  run's turn (take? false: an observer's read of an agent its model drives) delivers nothing."
  {:malli/schema [:=> [:cat :boolean] :nil]}
  [take?]
  (when take?
    (let [fx (count (agent/inbox))]
      (agent/deliver!)
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
    ;; written after this turn's basis, so the next turn reads it, after everything this turn wrote
    (agent/transact! (agent/own-message-tx (str k "/turn-error/" (random-uuid)) task/system-sender body (System/currentTimeMillis)))
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

(defn- turn-can-change
  "Whether a model call's typed error of type t is one a next turn or a next call can change (seon.turn/model-failed!): its schema
  entry says so, with its reason (:seon.model/retryable, root's ruling: data, never a second list). The rest of :seon.model/failure
  are the config's (no provider, no script, a script's invalid reply), which no turn changes. See
  docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error."
  {:malli/schema [:=> [:cat :keyword] :boolean]}
  [t] (boolean (:seon.model/retryable (contract/properties t))))

(defn- model-failed!
  "A model call that answered its typed error (em, a :seon.model/failure: the reasoning spent the whole token budget, the
  provider refused or was unreachable) given to the agent as data, as a throw is (turn-error!): its next turn reads why this
  one made no evals, counted in :threw, so a second in a row with no answered call between ends the work. Answers st after
  it, :error em. See docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error."
  {:malli/schema [:=> [:cat :map :seon.model/failure] :map]}
  [st em]
  (tell-error! em "your last model call answered an error, so that turn made no evals")
  (-> st (update :threw (fnil inc 0)) (assoc :error em)))

(defn- basis!
  "What a turn's context is built from, recorded instead of its text (a model call stores its
  basis, not its prompt): the commit it reads (the run-ahead writes committed now, as the
  model call's wait would), the task and turn (its number: docs/turn.md#the-turn-is-a-number). Everything the request shows is a fact of that
  commit: how the work stands (seon.ctx.status/status), what the turn was delivered (deliver!)."
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
  apportioned by the one sizer's share of them (seon.ctx.wire/messages-tokens) and rounded down to
  the provider's block (:seon.config.ai/cache-block-tokens, 0: no check). Measured on 94 recorded
  turn pairs: a hit is 0.87-1.12 of the shared prefix so sized, and none is short."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]] [:sequential :map] :map nat-int?] [:maybe :map]]}
  [prev messages {:keys [prompt-tokens cache-hit-tokens]} block]
  (when (and (seq prev) (pos? block) prompt-tokens cache-hit-tokens)
    (let [ms (vec messages)
          shared (count (take-while true? (map = prev ms)))]
      ;; nothing shared (the first message differs): no prefix the cache could serve
      (when (pos? shared)
        (let [expected (long (* prompt-tokens (/ (double (wire/messages-tokens (subvec ms 0 shared)))
                                                 (wire/messages-tokens ms))))
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

(defn- open-turn!
  "Turn n of task opens: what it takes, then the turn (the agent's number and its model call's first facts, with the main and merge base it heard), then its
  basis: the commit that holds them, so everything its request and its provider are made from is a fact of that commit
  (seon.ctx.wire/wire-turn makes them again there). A model's turn and an outside agent's reply open alike. Answers [its model call's id, its basis]; its page reaches the model as the turn's page message (seon.ctx.wire/page-diff-msgs), never an eval. See docs/turn.md#the-turn-is-a-number."
  {:malli/schema [:=> [:cat :seon.task/id :int [:maybe :int] :boolean [:maybe :string]] [:tuple :string :map]]}
  [task n max-turns merge? model-name]
  (let [k (:key @agent/state) id (lib/call-id k n)]
    (deliver! true)
    (agent/transact! (into [{:seon.agent/key k :seon.agent/turn n}
                            {:seon.model-call/id id :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n n :seon.model-call/origin :model}]
                           (main-base-tx)))
    [id (cond-> (assoc (basis! task n max-turns :model merge?) :agent k) model-name (assoc :model model-name))]))

(defn- model-request!
  "Turn n's model call made ready, never sent: the agent's turn number moved, its basis recorded and the request drawn from
  its walk. Answers {:id :t1 :request}, the request what root's :model owner is asked. See docs/turn.md#act."
  {:malli/schema [:=> [:cat :map :map [:maybe :string]] [:map [:id :string] [:request :map] [:t1 :int]]]}
  [{:keys [turn max-turns merge?]} t model-name]
  (let [;; the turn this is: the agent's number, one past the one in force, which the messages taken now and every record
        ;; this turn writes take
        n (inc (agent/turn-now))
        [id basis] (open-turn! (:seon.task/id t) n max-turns (boolean merge?) model-name)
        ;; the provider as the config stands at the basis (main's as the turn heard it, over the task's own), never one copied at boot
        provider (wire/provider-of (agent/db) basis)
        _ (agent/transact! [{:seon.model-call/id id :seon.model-call/basis basis}])
        _ (agent/commit!)
        ;; the request is the agent's model output, each turn's page message among it: made on the db that holds them
        db (agent/db)
        {:keys [messages]} (wire/render db basis {:walk (repl/page-walk)})
        ;; a hash of the bytes sent, which the debug page compares with its redraw
        _ (agent/transact! [{:seon.model-call/id id :seon.model-call/sent-hash (lib/digest (sedn/encode messages))}])
        _ (agent/commit!)]
    ;; :call and :agent name this model call and its agent, so the reply is read as it streams (docs/flow.md#effects-one-owner-and-its-workers-per-kind)
    {:id id :t1 (profile/now-ns)
     :request {:messages messages :task (:seon.task/id t) :turn turn :provider provider :call id :agent (:key @agent/state)}}))

(defn- act!
  "One model turn's answer r acted on: the outcome of the call made ready by model-request! (pending: that call, with :task, the
  task as the turn observed it, and :unmerged, its status's), {:value completion} or the provider's typed error. The reply's eval
  calls go through the one REPL path, each answered as its own result, and the turn's facts on its model call on the branch.
  Answers the run's state st after it, or the provider error. See docs/turn.md#act."
  {:malli/schema [:=> [:cat :map :map :map] :map]}
  [{:keys [turn merge? merge-step? model-name] :as st} {:keys [id request t1 unmerged] t :task} r]
  (let [model-ms (long (profile/ms-since t1))
        messages (:messages request)
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
            ;; what it answered (its reply and reasoning), the reply's usage and model (seon.model-call, beside the basis it
            ;; read: seon.ctx.wire/render makes the text from it), as soon as it answered: an eval of this reply that never ends leaves the call
            ;; recorded and its cost counted, never reading as a call nobody answered (deepseek-run6's records agent)
            _ (agent/transact! [(cond-> (merge (usage c) {:seon.model-call/id id :seon.model-call/reply (:text c) :seon.model-call/ms model-ms})
                                  (:truncated? c) (assoc :seon.model-call/truncated true)
                                  (:reasoning c) (assoc :seon.model-call/reasoning (:reasoning c))
                                  (:model c) (assoc :seon.model-call/model (:model c)))])
            t2 (profile/now-ns)
            ;; an interrupt left from before this turn's evals is not theirs
            _ (intr/clear-pending!)
            ;; each eval call through the one REPL path, in order; its response is the call's result. The reply's text and
            ;; reasoning are the model call's own (below), never copied onto an eval
            ;; every call runs, whatever the one before it did; a call with no intent or no form is not run, and its one
            ;; result says why (seon.repl/unrun, docs/prompt.md)
            ;; each eval streams its output under its place in the reply (seon.model/reply-of's :seon.model/at [k i]), as its code is
            ;; named: <call>/<k>/eval-<i>/... (seon.repl/streaming)
            at (into {} (map (juxt :id :seon.model/at)) (:tool-calls c))
            calls (repl/run-calls (vec (:tool-calls c)) (fn [cid] (cond-> {:origin :model :call-id cid}
                                                                    (at cid) (assoc :stream {:call id :k (first (at cid)) :i (second (at cid))}))) evaluated!)
            rs (mapv :response calls)
            eval-ms (long (profile/ms-since t2))
            _ (swap! agent/state assoc-in [:turn :evals] rs)]
        ;; a reply with text and no call pauses the agent until someone answers, whatever its proofs say
        ;; (docs/decisions.md#a-reply-with-no-calls-pauses-the-agent-whatever-its-proofs-say)
        ;; (a blank or cut reply is no message: the next turn reads the same state)
        (when (and (empty? calls) (not (str/blank? (:text c))) (not (:truncated? c))) (request-stop! :waiting (:text c)))
        ;; its evals' counts, and the time with them
        (agent/transact! [{:seon.model-call/id id :seon.model-call/forms (count calls)
                           :seon.model-call/ms (+ model-ms eval-ms)}])
        (cond-> (assoc st :turn (inc turn))
          ;; a merge turn: each turn of a merge step (seon.task/merge-step?, read from its needs and branch, since a merge
          ;; step's every turn is merge work); a task that merges its own work does that work first, so only a turn with what
          ;; both sides changed still to resolve
          (and merge? (or merge-step? (seq unmerged))) (update :merge-turns inc))))))

(defn context-block
  "The request of task as a model is sent it on this branch now, as one text: the system prompt, its conversation and its page.
  take?: the read is the run's turn (its model is outside): this process works task, takes the messages delivered, shows the
  page, and records the read as a model call (id \"<agent key>/read-<k>\"); else an observer's, which writes nothing. wire?:
  the request body as JSON (seon.ctx.wire/wire-turn). A read runs no proof. See docs/turn.md#an-outside-agent."
  {:malli/schema [:function [:=> [:cat :seon.task/id :boolean] :string] [:=> [:cat :seon.task/id :boolean :boolean] :string]]}
  ([task take?] (context-block task take? false))
  ([task take? wire?]
   (let [_ (when take? (begin! task))
         k (:key @agent/state)
         turn (inc (agent/turn-now))
         ;; an observer's read records nothing on the branch beyond the read and leaves the print cache
         _ (deliver! take?)
         ;; the main and merge base the read heard, decided as a model turn's are, before its request is drawn
         _ (when take? (when-let [tx (not-empty (main-base-tx))] (agent/transact! tx) (agent/commit!)))
         ;; the run's own max-turns, as a model turn's basis holds it (the agent's on main, else the config's: root passes them alike)
         max-turns (or (:seon.agent/max-turns (d/entity (agent/db) [:seon.agent/key k]))
                       (cfg/value :seon.config.agent/max-turns))
         ;; the turn a model would take now: every record before it is in the request
         ;; an observer's: the branch as it is, nothing committed for it
         basis (assoc (if take?
                        (basis! task turn max-turns :outside true)
                        (let [db (agent/db)] {:commit (d/commit-id db) :t (:max-tx db) :task task :turn turn :max-turns max-turns :origin :outside :merge? true}))
                      :agent k)
         walk (repl/page-walk)
         db (agent/db)
         {:keys [messages]} (wire/render db basis {:walk walk})
         ;; as a person reads it: the system prompt, then each record as the call made and its result
         text (wire/read-text db basis messages wire?)]
     ;; the run's turn read is a model call, as a model turn's is; its number the agent's own count of reads on the branch
     (when take?
       (let [n (inc (or (d/q '[:find (max ?n) . :in $ ?a :where [?m :seon.model-call/agent ?a] [?m :seon.model-call/origin :outside] [?m :seon.model-call/n ?n]]
                             (agent/db) (pdb/eid (agent/db) :seon.agent/key k))
                        0))]
         (agent/transact! [{:seon.model-call/id (lib/call-id k (str "read-" n)) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n n
                            :seon.model-call/origin :outside :seon.model-call/basis basis
                            :seon.model-call/sent-hash (lib/digest (sedn/encode messages))}])))
     text)))

(defn outside-reply!
  "An outside agent's seon_eval on this process, one reply as a model's is: its evals (`seon.model/call-evals`) through the one REPL path in order, every one whatever the one before it did (docs/model.md#one-tool-repl). With turn?, the agent works task with its model outside: the reply is its turn, its message on its model call, opened as a model's turn is, so it reads as a model's in the conversation, and its answer is what a model's next request would add: any page eval Seon ran, before and after its evals. Otherwise it is someone at the REPL of an agent its own model drives: no turn moves. Answers each response as the wire carries it, printed as EDN for the server (`seon.server/op-answer`). See docs/turn.md#an-outside-agent."
  {:malli/schema [:=> [:cat [:maybe :seon.task/id] [:maybe :string] :any :boolean] :nil]}
  [task message evals turn?]
  (let [n (when turn? (begin! task) (inc (agent/turn-now)))
        ;; as a model's turn does (work!): main moved, it merges in, and a conflict is an unmerged entry it sees. An outside agent has no
        ;; run to end and fork again, so its process takes its branch's program in place before its evals run: no reply runs old code
        _ (when (and turn? (agent/main-moved? (:base @agent/state) (agent/heard-main))) (branch/merge-main!))
        _ (when (and turn? (:behind @agent/state)) (agent/take-branch-program!))
        [id basis] (when turn? (open-turn! task n 1 false nil))
        _ (when turn? (agent/transact! [{:seon.model-call/id id :seon.model-call/basis basis}]) (agent/commit!))
        calls (model/call-evals {"message-md" message "evals" evals} (str "outside-" (or n "repl")))
        rs (repl/run-calls (mapv #(dissoc % :message-md) calls) (fn [cid] {:origin :outside :call-id cid}) evaluated!)]
    (when turn?
      (agent/transact! [{:seon.model-call/id id :seon.model-call/forms (count rs) :seon.model-call/model "outside"
                         :seon.model-call/reply (or message "")}])
      (agent/commit!))
    (print (sedn/encode (mapv agent/on-the-wire (map :response rs))))
    nil))

(defn specified-tx
  "The write that marks task task specified on branch db, or nil: the first status s a turn reads in which one of its proofs is red
  (or threw) writes :seon.task/specified-turn, the turn of agent k (its :seon.agent/turn) then, once; an agent with no turn yet writes nothing. A verdict keeps no history, so the
  moment it is observed is when it is written; a branch cut from main does not hold it. Nothing when no proof is red, or when the task
  holds it already. See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id :string :map] [:maybe :seon.db/tx-data]]}
  [db task k s]
  (let [n (:seon.agent/turn (d/entity db [:seon.agent/key k]))]
    ;; a turn not known is no turn written (unknown is never 0)
    (when (and n (some #(#{:fail :error} (:status %)) (:proofs s))
               (nil? (:seon.task/specified-turn (d/entity db [:seon.task/id task]))))
      [{:seon.task/id task :seon.task/specified-turn n}])))

(defn hand-back-end
  "How an outside agent's run ends: [outcome reason] from the stop its agent requested
  (my.task/fail! makes it :failed, with the reason it gave) and its status s (seon.ctx.status/status: the done rule's :reasons, and
  :test-run when a test run failed as a whole). A task the agent failed ends :failed whatever the proofs say, as the turn loop ends it
  (work!); else :green when the done rule has no reason, else :red with the reasons, after the failed run when there is one: while it
  stands no verdict counts and every proof reads unknown, so the run is what to fix, said first. See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat [:maybe :map] :map] [:tuple :seon.agent/outcome [:maybe :string]]]}
  [stop {:keys [reasons test-run]}]
  (cond (= :failed (:outcome stop)) [:failed (:reason stop)]
        (empty? reasons) [:green nil]
        :else [:red (str (when-let [f (seq (:failed test-run))]
                           (str "the test run failed as a whole (" (str/join "; " f) "), so no verdict counts: "))
                         (r/explain-text reasons))]))

(defn- unjudged-reason
  "What a green end of status s leaves unjudged, in words, or nil (seon.task/unjudged): the end says it, and the judge decides as before."
  {:malli/schema [:=> [:cat :map] [:maybe :string]]}
  [{:keys [unjudged]}]
  (when unjudged (str "process tests not judged: " (str/join ", " (:tests unjudged)) ": " (:why unjudged))))

(defn- clear?
  "Whether status s is clear: it was drawn and the done rule gives no reason, so the tests its change reaches are judged before it
  is done or merges (turn-begin's and hand-back-step's :prove). See docs/render.md#how-the-work-stands."
  {:malli/schema [:=> [:cat [:or :map :seon.error/base]] :boolean]}
  [s] (and (not (contract/error? s)) (empty? (:reasons s))))

(def ^:private judge-request
  "What a clear status asks of the judge (seon.agent/judge!'s names and reach?): every test its change reaches."
  {:names #{} :reach? true})

;; ---- a turn as Flow steps (docs/flow.md#the-step-fn-contract-every-proc-follows) ----------------------------------
;; A turn waits twice, on its verdict and on its model call, and each is an output, never a wait inside a step: turn-begin and
;; turn-verdict answer [st' {:prove request}] or [st' {:model request}], and the answer comes back as turn-verdict's or turn-end's
;; argument. A step that needs neither answers [::next st] (the next turn begins) or [::end st outcome reason error] (end! writes
;; it). The run's state st is plain data: :task :model-name :max-turns :merge? :merge-step? :merge-cfg :turn :merge-turns :t0, the
;; counts :threw and :thrown and the :error a turn's error left, and :pending, what the step that asked left for its answer.

(defn- threw
  "A turn of run state st that threw e, as the agent's to read: [::next st'] counted against the cap, or [::end ...] at the second throw
  in a row (called?: the turn's model call answered first, so the count starts again). See docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map."
  {:malli/schema [:=> [:cat :map :any :boolean] [:vector :any]]}
  [st e called?]
  (let [st' (cond-> (-> (turn-error! st (cond-> (agent/error-map e 'seon.turn/work!) (err/thrown-in e) (assoc :seon.error/in (err/thrown-in e))))
                        (update :turn inc) (update :thrown (fnil inc 0)))
              called? (assoc :threw 1))]
    (if (< 1 (:threw st'))
      [::end st' :error (str "two turns in a row threw: " (:seon.error/message (:error st'))) (:error st')]
      [::next st'])))

(defn- next-of
  "What follows a turn whose model call was acted on (st', act!'s answer): [::next st'] or [::end ...]. A retryable failure is
  read next turn, two in a row end the work. See docs/failure-points.md#entrant-boundaries-a-defect-becomes-a-recorded-error-map."
  {:malli/schema [:=> [:cat :map :map] [:vector :any]]}
  [st st']
  (cond (and (contract/valid? :seon.model/failure st') (turn-can-change (:seon.error/type st')))
        (let [st2 (-> (model-failed! st st') (update :turn inc))]
          (if (< 1 (:threw st2))
            [::end st2 :error (str "two model calls in a row failed: " (:seon.error/message st')) st']
            [::next st2]))
        (contract/error? st') [::end st :error (:seon.error/message st') st']
        :else [::next (dissoc st' :threw :error)]))

(defn- hairy-of
  "Whether a merge task's run st, its task t as observed and its status s, has grown past its merge limits (seon.reconcile/hairy),
  from what the task references as facts (its proofs, its plan's cites): never its plan re-parsed by a seon.ref/scan the agent may
  have broken. See docs/turn.md#work-from-release-to-judge."
  {:malli/schema [:=> [:cat :map :map :map] :any]}
  [{:keys [task merge-cfg merge-turns]} t s]
  (r/hairy (merge merge-cfg {:conflicts (vec (:unmerged s)) :merge-turns merge-turns
                             :refs (into (set (:proofs (:seon.task/accumulated t)))
                                         (keep #(get-in % [:seon.cite/target :var/name]))
                                         (task/task-cites (agent/db) task))})))

(defn- decide
  "The end checks of a turn of run state st, with t the task as it observed and s its status (judged when clear), and else its model
  call made ready: [::end ...], main merged in once more when green ({:merge}, `turn-merged`), or [st' {:model request}]. See
  docs/turn.md#work-from-release-to-judge."
  {:malli/schema [:=> [:cat :map :map [:or :map :seon.error/base]] :seon.turn/step]}
  [{:keys [task merge? max-turns model-name] :as st} t s]
  (let [no-status? (contract/error? s)
        ;; the first red of one of its proofs is when the task was specified: written once, as observed
        _ (when-not no-status? (some-> (specified-tx (agent/db) task (:key @agent/state) s) agent/transact!))
        reasons (:reasons s)
        stop (get-in @agent/state [:turn :stop])
        hairy (when (and merge? (not no-status?)) (hairy-of st t s))
        ;; the model call made ready, from act-st (st, or st after its status's error), and left pending with what its answer
        ;; is acted on with
        ask (fn [act-st] (let [call (model-request! act-st t model-name)]
                           [(assoc st :pending (assoc call :st act-st :task t :unmerged (:unmerged s))) {:model (:request call)}]))]
    (cond
      ;; the agent's own fail! is its verdict, on green proofs and at the cap too, as at a hand-back
      ;; (hand-back-end); a wait on the last turn is the budget spent, so the run is :cap, never :waiting
      (= :failed (:outcome stop)) [::end st :failed (:reason stop) nil]
      ;; git's CI after the merge: green on the combined program; merge main again until
      ;; it brings nothing new, a work task's run as a merge's, so it ends on main as main is
      ;; (a green run that ended behind main's newer merges was judged behind: swarm's s9)
      (and (not no-status?) (empty? reasons)) [(assoc st :pending {:merged :green :status s}) {:merge {}}]
      hairy [::end st :merge-failed (:reason hairy) nil]
      ;; the one check of the cap, before any model call: every turn reaches it, one with no
      ;; status and one after a turn that threw included (a change that broke
      ;; the status would otherwise let the run make model calls past the cap)
      (>= (+ (agent/turn-now) (:thrown st 0)) max-turns) (if no-status?
                                                          [::end st :cap (str "no status at the cap: " (:seon.error/message s)) s]
                                                          [::end st :cap (r/explain-text reasons) nil])
      stop [::end st (:outcome stop) (:reason stop) nil]
      ;; its status threw (the agent's program broke it): the agent reads why, this turn
      no-status? (ask (turn-error! st s))
      :else (ask st))))

(defn- merged-in
  "A turn of run state st with main merged in: behind, the run ends :refork (no turn runs old code: root forks it again on main's
  program); else it asks the task observed ({:observe}). See docs/merging.md#a-branch-merges-main-in."
  {:malli/schema [:=> [:cat :map] :seon.turn/step]}
  [st]
  (if (:behind @agent/state)
    [::end st :refork "merged main: the run goes on in a process forked on main's program" nil]
    [st {:observe (:task st)}]))

(defn turn-begin
  "A turn begun: main merged in if it moved ({:merge}, `turn-merged`), else the task observed ({:observe}, `turn-observed`), each an
  output answered as a message, so no step waits on main or the test service; a throw is the agent's to read. See
  docs/flow.md#the-step-fn-contract-every-proc-follows."
  {:malli/schema [:=> [:cat :map] :seon.turn/step]}
  [st]
  (let [st (dissoc st :pending)]
    (try
      (if (agent/main-moved? (:base @agent/state) (agent/heard-main))
        [(assoc st :pending {:merged :begin}) {:merge {}}]
        (merged-in st))
      (catch Exception e (threw st e false)))))

(defn turn-merged
  "A {:merge} output answered with m (merge-main!'s answer): at a turn's begin the turn goes on (`merged-in`); after a green status a
  merge that brought nothing ends the run :green, else the next turn judges the combined program; an error answer is the turn's throw.
  See docs/flow.md#the-five-rules."
  {:malli/schema [:=> [:cat :map :map] :seon.turn/step]}
  [st m]
  (let [{:keys [merged status]} (:pending st) st (dissoc st :pending)]
    (try (cond
           (contract/error? m) (threw st (ex-info (str (:seon.error/message m)) m) false)
           (= :green merged)
           (if (and (empty? (:applied m)) (empty? (:conflicts m))) [::end st :green (unjudged-reason status) nil] [::next st])
           :else (merged-in st))
         (catch Exception e (threw st e false)))))

(defn turn-observed
  "An {:observe} output answered with obs (`observe`'s): how the work stands read (one computation a turn, the page's component draws
  its own); clear, the tests its change reaches asked of the judge ({:prove}), else the end checks and the model call (decide). See
  docs/flow.md#the-five-rules."
  {:malli/schema [:=> [:cat :map :map] :seon.turn/step]}
  [st obs]
  (try (let [s (status-now)]
         (if (clear? s) [(assoc st :pending {:task (:task obs)}) {:prove judge-request}] (decide st (:task obs) s)))
       (catch Exception e (threw st e false))))

(defn turn-verdict
  "Turn-begin's :prove answered: verdict, seon.agent/judge!'s answer, written as the run's test error when the run failed as a
  whole (test-error!), the status read again from the branch, then the end checks and the model call made ready, as turn-begin
  answers them. See docs/flow.md#the-step-fn-contract-every-proc-follows."
  {:malli/schema [:=> [:cat :map :map] :seon.turn/step]}
  [st verdict]
  (let [t (get-in st [:pending :task]) st (dissoc st :pending)]
    (try (test-error! verdict)
         (decide st t (status-now))
         (catch Exception e (threw st e false)))))

(defn turn-end
  "A :model request answered: answer, the model call's outcome ({:value completion} or its typed :seon.model/failure), acted on
  (act!: the reply's evals run, the turn recorded) and folded (next-of): [::next st'] or [::end st outcome reason error]. See
  docs/flow.md#the-step-fn-contract-every-proc-follows."
  {:malli/schema [:=> [:cat :map :map] :seon.turn/step]}
  [st answer]
  (let [{act-st :st :as pending} (:pending st)
        st (dissoc st :pending)
        ;; the request the last answered model call sent: a turn whose own call answered replaces it
        asked (:requests @agent/state)]
    (try (next-of st (act! act-st pending answer))
         (catch Exception e (threw st e (not (identical? asked (:requests @agent/state))))))))

(defn start!
  "A run of task `task` begun: its turn state set, main merged in and the main it heard recorded. Answers the run's state, which
  turn-begin takes. See docs/turn.md#work-from-release-to-judge."
  {:malli/schema [:=> [:cat [:map [:task :seon.task/id] [:model {:optional true} :string] [:max-turns pos-int?]]] :map]}
  [{:keys [task model max-turns]}]
  (let [;; each wake begins its turn state; the page it last showed stands, so a wake is told only what moved since
        _ (swap! agent/state assoc :turn {} :requests nil)
        _ (agent/clear-end!)
        _ (begin! task)
        ;; the agent's own budget (:seon.agent/max-turns, root's on main, raised to extend it; root passes it) bounds the loop; the
        ;; page's turn line reads it as the branch holds main's (one writer per attribute: never copied here, docs/users.md)
        t0-task (task/task (agent/db) task)
        merge? (task/merges-on? (agent/db) task)
        merge-cfg (r/merge-limits t0-task)]
    ;; every run starts on main: a kept branch made before main moved merges it first, so
    ;; the branch's db, its base and this process hold one program (its tests' digests are the
    ;; program it runs); a branch at main's head brings nothing
    (branch/merge-main!)
    ;; the main and merge base this run heard, on the agent before its first check: its status measures the branch's change from
    ;; that base (seon.reconcile/changed?), so a branch at main's head that merged nothing is unchanged at turn 0, never unmeasured
    (when-let [tx (not-empty (main-base-tx))] (agent/transact! tx) (agent/commit!))
    (cond-> {:task task :turn 0 :merge-turns 0 :t0 (profile/now-ns) :merge-step? (task/merge-step-on? (agent/db) task)
             :max-turns max-turns :merge? merge? :merge-cfg merge-cfg}
      model (assoc :model-name model))))

(defn- effect-answer
  "Output out of a turn step (:seon.turn/output), its effect done: the verdict (seon.agent/judge!), the model call's outcome through
  root's :model owner, main merged in (my.branch/merge-main!) or the task observed (`observe`), as the message step-run takes
  ({::verdict v}, {::answer a}, {::merged m}, {::observed o}), or its throw ({::threw e}),
  the turn's as a throw inside a step is. Where it runs is its executor's: in place (work! with no :repl proc) or on a fiber of
  its own, the edge ask (seon.repl's :repl proc). See docs/flow.md#the-five-rules."
  {:malli/schema [:=> [:cat :seon.turn/output] :map]}
  [{:keys [prove model] :as out}]
  (try (cond prove {::verdict (agent/judge! (:names prove) (:reach? prove))}
             (:merge out) {::merged (branch/merge-main!)}
             (:observe out) {::observed (observe (:observe out))}
             :else {::answer (agent/fx! :model model {})})
       (catch Exception e {::threw e})))

(defn step-run
  "A run's one driver: run state st and msg, the message its last output was answered with (nil: the run's first step), stepped
  to its next output or its end. msg {::verdict v} goes to turn-verdict, {::merged m} to turn-merged, {::observed o} to turn-observed, {::answer a} to turn-end, {::threw e} to threw, and
  {::stop true} (root is stopping) ends the run at once, :waiting, the call in flight dropped; a turn that answers ::next begins
  the next. Answers [st' {:prove ..}|{:model ..}] or [::end st outcome reason error]. Each step is called through its var, so a
  redefined one takes effect at the next turn. See docs/flow.md#the-step-fn-contract-every-proc-follows."
  {:malli/schema [:=> [:cat :map [:maybe :map]] :seon.turn/step]}
  [st msg]
  (loop [step (cond (::stop msg) [::end (dissoc st :pending) :waiting "root is stopping: the run ended at once, with what its turns committed" nil]
                    (contains? msg ::threw) (threw (dissoc st :pending) (::threw msg) false)
                    (contains? msg ::verdict) (turn-verdict st (::verdict msg))
                    (contains? msg ::merged) (turn-merged st (::merged msg))
                    (contains? msg ::observed) (turn-observed st (::observed msg))
                    (contains? msg ::answer) (turn-end st (::answer msg))
                    :else (turn-begin st))]
    (if (= ::next (first step)) (recur (turn-begin (second step))) step)))

(defn- end-of
  "A run's end step [::end st outcome reason error] written and answered (end!). See docs/turn.md#the-end."
  {:malli/schema [:=> [:cat [:tuple [:= ::end] :map :seon.agent/outcome [:maybe :string] [:maybe :map]]] :seon.turn/end]}
  [[_ st outcome reason err]] (end! st outcome reason err))

(defn- drive!
  "A run, state st stepped by step (a step fn's var: step-run, hand-back-step) on the one message protocol, driven: in an eval the
  :repl proc runs (seon.repl/*run* bound) it is handed to that proc, which takes each output's answer as a message and replies to the
  eval with the end, and this answers {::run :started}; anywhere else each output's effect is done in place (effect-answer) and this
  answers the end. See docs/flow.md#flow-inside-an-agent."
  {:malli/schema [:=> [:cat :map :seon.host/var] [:or :seon.turn/end [:map [::run [:= :started]]]]]}
  [st step]
  (if-let [hand repl/*run*]
    (do (vreset! hand {:st st :step step :effect #'effect-answer :end #'end-of}) {::run :started})
    (loop [r (step st nil)]
      (if (= ::end (first r)) (end-of r) (recur (step (first r) (effect-answer (second r))))))))

(defn work!
  "Work task `task` (the model `model` when set, else root's), until the agent's turn reaches
  max-turns (agent-wide: its :seon.agent/turn against its budget, which root passes from the agent's own on main).
  It first merges main into the branch (start!), as a merge task does again at each green, then drives the run with step-run.
  In an eval the agent's :repl proc runs (seon.repl/*run* bound), the run is handed to that proc, which takes each output's
  answer as a message, so it answers ping and stop between the waits, and replies to this eval with the end when the run ends:
  this answers {::run :started}. Anywhere else each output's effect is done in place (effect-answer) and this answers the end
  ({:outcome :turns :model-calls :tokens :ms}); the same end is written on the branch either way. See docs/flow.md#flow-inside-an-agent."
  {:malli/schema [:=> [:cat [:map [:task :seon.task/id] [:model {:optional true} :string]
                             [:max-turns pos-int?]]]
                  [:or :seon.turn/end [:map [::run [:= :started]]]]]}
  [arg] (drive! (start! arg) #'step-run))

(defn- hand-back-end-step
  "The end of a hand-back of run state st whose status is s: what the status names written on the branch (specified-tx), then
  [::end {} outcome reason error] as hand-back-end says it. See docs/turn.md#the-end."
  {:malli/schema [:=> [:cat :map [:or :map :seon.error/base]] [:tuple [:= ::end] :map :seon.agent/outcome [:maybe :string] [:maybe :map]]]}
  [st s]
  (when-not (contract/error? s) (some-> (specified-tx (agent/db) (:task st) (:key @agent/state) s) agent/transact!))
  (let [[outcome reason] (if (contract/error? s) [:error (:seon.error/message s)] (let [[o why] (hand-back-end (:stop st) s)] [o (or why (unjudged-reason s))]))]
    [::end {} outcome reason (when (contract/error? s) s)]))

(defn hand-back-step
  "A hand-back's one driver step on step-run's message protocol, run state st ({:task}) and msg: nil begins and asks main merged in
  ({:merge}); {::merged m} asks the task observed ({:observe}); {::observed o} reads the status and, when clear, asks the judge
  ({:prove judge-request}: the tests its change reaches, as a turn asks them); {::verdict v} reads the status again with the verdict
  written (test-error!); each end is hand-back-end-step's. Every wait is an output, so the agent answers ping and stop throughout.
  {::threw e} is an output's throw, the hand-back's own; {::stop true} ends it :waiting. See docs/flow.md#flow-inside-an-agent."
  {:malli/schema [:=> [:cat :map [:maybe :map]] :seon.turn/step]}
  [st msg]
  (cond
    (::stop msg) [::end {} :waiting "root is stopping: the hand-back ended before its judge answered" nil]
    (contains? msg ::threw) (throw (::threw msg))
    (contains? msg ::verdict) (do (test-error! (::verdict msg)) (hand-back-end-step st (status-now)))
    ;; main taken now, as work! takes it at each start, so green work is never judged for being behind; a conflict is an unmerged
    ;; entry, a reason the status says. Asked of main itself, never of what this process last heard of it (an outside agent's green
    ;; task was judged behind 78 with that guard, 2026-11-11); a branch at main's head brings nothing
    (contains? msg ::merged) [st {:observe (:task st)}]
    ;; a clear status: the tests its change reaches run now, as the judge judges them, before it is done (the owner's ruling: a turn
    ;; runs the proofs, the reach runs once they are green)
    (contains? msg ::observed) (let [s (status-now)] (if (clear? s) [st {:prove judge-request}] (hand-back-end-step st s)))
    ;; the stop is read before begin!, which starts the turn state anew when it has none yet
    :else (let [stop (get-in @agent/state [:turn :stop])]
            (begin! (:task st))
            [(assoc st :stop stop) {:merge {}}])))

(defn hand-back!
  "End run of task as an outside agent hands it back (seon.server's :done): the proofs run
  again, and the run's end written as the turn loop writes its own (end!: the live runtime
  staged, the end committed on the branch): hand-back-end says how it ends. Its turns, model calls, tokens and time are the outside model's,
  unknown here, so absent (never 0). The judge decides from its own run of the proofs and the
  plan the run began with on main; this end is what the run says of itself. Driven as a run (drive!, hand-back-step), so its wait on
  the judge is an output and the agent answers ping and stop meanwhile. Answers the end, or {::run :started} in the :repl proc, which
  replies with the end. See docs/flow.md#flow-inside-an-agent."
  {:malli/schema [:=> [:cat :seon.task/id] [:or :seon.turn/end [:map [::run [:= :started]]]]]}
  [task] (drive! {:task task} #'hand-back-step))
