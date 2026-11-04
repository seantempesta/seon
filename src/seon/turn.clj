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
  (:require [seon.ctx :as ctx] [seon.edn :as sedn] [seon.profile :as profile] [seon.intr :as intr] [seon.contract :as contract :refer [defn defn-]] [clojure.string :as str]
            [seon.agent :as agent] [seon.repl :as repl] [seon.task :as task] [seon.reconcile :as r]
            [seon.proofs :as proofs] [seon.context :as context] [seon.merge :as pm] [seon.problems :as problems]
            [seon.stage :as stage] [my.branch :as branch] [seon.reader :as reader] [seon.learn :as learn] [seon.config :as cfg] [seon.lib :as lib] [seon.print :as print]
            [seon.error :as err] [datahike.api :as d] [seon.db :as pdb]))

;; The turn loop's own facts live in the agent's one state map, under :turn: the run it
;; works, and a stop a function requested.

(defn current-task
  "The id of the task this process works: the one root forked it for (seon.agent/init!), or
  nil for an agent forked with none."
  {:malli/schema [:=> [:cat] [:maybe :seon.task/id]]}
  [] (:task @agent/state))

(defn- begin!
  "This process works task as run: the run recorded as its session's on its branch, and the
  turn state, begun once per run (the plan it began with is the run's fact, :seon.run/plan).
  The turn loop and an outside agent's context read begin it the same way."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id] :map]}
  [task run]
  (if (= run (get-in @agent/state [:turn :run]))
    (:turn @agent/state)
    ;; the run is this session's on its own branch too (seon.agent/begin-run!), and a fork's task
    ;; with it, read after that write
    (do (agent/begin-run! task run)
        (:turn (swap! agent/state assoc :turn {:run run})))))

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
  its process's entity (:seon.process/test-error, \"<agent key>/<pid>\"), retracted when a run answers;
  nothing written when it already says so (seon.context/status reads it)."
  {:malli/schema [:=> [:cat :any] :any]}
  [test-run]
  (let [k (:key @agent/state) pk (str k "/" (lib/c-getpid))
        held (:seon.process/test-error (d/entity (agent/db) [:seon.process/id pk]))
        err (when (contract/error? test-run) (select-keys test-run [:seon.error/type :seon.error/operation :seon.error/message]))]
    (cond (and err (not= err held)) (agent/transact! [{:seon.process/id pk :seon.process/agent [:seon.agent/key k] :seon.process/test-error err}])
          (and held (not err)) (agent/transact! [[:db.fn/retractAttribute [:seon.process/id pk] :seon.process/test-error]]))))

(defn- observe
  "What a turn starts from: the proofs run now by the test service (seon.prove: one run per branch
  and tx whoever asks, in a fork of this agent), their results facts on this branch
  (seon.agent/record-results!), a run that failed as a whole too (test-error!), and the task as the
  branch holds it. How the work stands is the status (seon.context/status), drawn from those
  facts by the agent's walk (`status-now`): nothing here is held for it. {:test-run :tests-ms :task}."
  {:malli/schema [:=> [:cat :seon.task/id] :map]}
  [id]
  (let [;; the branch's own texts embedded first, so the plan's vector is the plan's now
        _ (agent/embed-written!)
        t0 (profile/now-ns)
        test-run (agent/prove!)
        tests-ms (long (profile/ms-since t0))]
    (test-error! test-run)
    {:test-run test-run :tests-ms tests-ms :task (task/task (agent/db) id)}))

(defn- status-now
  "How the work stands now, from the agent's walk (seon.repl/page-data): the status
  part's data (seon.context/status), which the page and the model read too, so a turn computes it
  once. A walk that drew no status (its render threw, or the layout placed none) is the typed
  :seon.turn/no-status, never an empty status: no reason is not done (AGENTS 9, unknown is not {})."
  {:malli/schema [:=> [:cat] [:or :map :seon.error/base]]}
  [] (or (get (repl/page-data) :seon.context/status)
         (contract/error :seon.turn/no-status #'status-now "the agent's walk drew no status (seon.render.ui/status threw, or no layout placed it): the done rule cannot be read" {})))

(defn run-usage
  "What run's model calls spent, from their records on db (a model call is one fact, whichever way the turn
  ended): {:model-calls n :tokens t}, n the calls recorded (a turn that threw before its call made none), t their
  :seon.model-call/tokens summed, absent when no call reported its usage (unknown, never 0). A count carried
  through the turn loop lost every turn that threw after its call answered, and the turns stood for the calls."
  {:malli/schema [:=> [:cat :seon.host/db :seon.run/id] [:map [:model-calls nat-int?] [:tokens {:optional true} :int]]]}
  [db run]
  (let [calls (map #(d/entity db %) (d/q '[:find [?m ...] :in $ ?r :where [?x :seon.run/id ?r] [?m :seon.model-call/run ?x]] db run))
        ts (keep :seon.model-call/tokens calls)]
    (cond-> {:model-calls (count calls)} (seq ts) (assoc :tokens (reduce + ts)))))

(defn- end!
  "The run's end: the live runtime staged onto the branch, the end written there (with
  the error that ended it and the turns' waste, as data: session D2), and the branch committed
  (a turn's end commits the agent's run-ahead writes)."
  {:malli/schema [:=> [:cat :map :seon.run/outcome [:maybe :string] [:maybe :map]] :seon.turn/end]}
  [{:keys [turn t0 run log]} outcome reason error]
  (stage/stage!)
  (let [{:keys [tokens model-calls]} (run-usage (agent/db) run)]
  (agent/write-end! run {:outcome outcome :turns turn :tokens tokens :reason reason :error error
                             :waste (learn/summary (learn/waste (or log []) (learn/final-symbols (or log []))))})
  ;; what was not measured is absent, never 0: an outside run counts no model calls and no loop time
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

(defn- take!
  "Take what was delivered since the last turn onto this branch as turn n's (the turn that reads it: the next one,
  docs/turn.md#the-turn-is-a-number), before the turn's request is made, so
  each sits in the conversation before the page that shows what came after it: the entries main holds
  to the session's runs (a person's message, root's at a stage boundary, a refused verdict:
  seon.agent/take-main!, the same facts root wrote on main, with what root wrote of the agent's own
  entities), then each send of the inbox (another agent's, root's request) as a message fact of its
  own. Each is :seon.message/agent's, so its presence on the branch is its delivery and the record's
  messages read it (seon.context/records); nothing is held for the turn's basis. An effect's outcome
  (:from :fx) is no message: its fact is the effect's (seon.agent/fx-record!). A read that is not the
  run's turn (take? false: an observer's read of an agent its model drives) takes nothing."
  {:malli/schema [:=> [:cat :boolean nat-int?] :nil]}
  [take? n]
  (when take?
    (let [sends (agent/inbox)
          k (:key @agent/state)
          tx (into [] (mapcat (fn [{:keys [from body]}]
                                ;; a send's body is the sender's value: its text, by the one printer, whole, is the message
                                (agent/own-message-tx (str k "/send/" (random-uuid)) :message (name from) nil
                                                      (if (string? body) body (print/text (cond-> body (instance? clojure.lang.IObj body) (vary-meta assoc :seon.print/full true))))
                                                      n)))
                   (remove #(= :fx (:from %)) sends))]
      (agent/take-main! n)
      (when (seq tx) (agent/transact! tx) (agent/commit!))
      ;; taken once their facts are committed: a refused write leaves them for the next turn
      (agent/taken! (count sends))))
  nil)

(defn- tell-error!
  "Error map em given to the agent as a message from seon on its branch, saying what Seon was doing
  (what): its next request reads it as any message. nil."
  {:malli/schema [:=> [:cat :seon.error/base :string] :nil]}
  [em what]
  (let [k (:key @agent/state)
        body (print/text (vary-meta {:seon-threw what :error em} assoc :seon.print/full true))]
    ;; the next turn's: the turn that reads it, so it sits after everything this turn wrote
    (agent/transact! (agent/own-message-tx (str k "/turn-error/" (random-uuid)) :message "seon" nil body (inc (agent/turn-now))))
    (agent/commit!)
    nil))

(defn- turn-error!
  "A turn that threw, or one Seon could not draw (st, its state; em, the error map: what it threw, seon.agent/error-map,
  or the walk's missing status, :seon.turn/no-status) given to the agent as data: em as a message
  from seon on its branch, which its
  next turn reads as any message, counted in :threw. A walk that drew no status still makes its model
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
  model call's wait would), the task, run and turn (its number: docs/turn.md#the-turn-is-a-number). Everything the request shows is a fact of that
  commit: how the work stands (seon.context/status), what the turn took (take!)."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id :int :int :seon.model-call/origin :boolean] :seon.model-call/basis]}
  [task run turn max-turns origin merge?]
  (agent/commit!)
  (cond-> {:commit (d/commit-id (agent/db)) :task task :run run :turn turn :max-turns max-turns :origin origin}
    merge? (assoc :merge? true)))

(defn- call-problem
  "Why an eval call, its prose and its code, is not the eval tool's shape, as the error its one
  result is, or nil: a call is prose and one or more forms (docs/prompt.md).
  Code that does not read is left to the REPL, whose read error says where."
  {:malli/schema [:=> [:cat [:maybe :string] :string] [:maybe :seon.turn/invalid-call]]}
  [prose code]
  (let [forms (reader/read-text code 'user)]
    (cond
      (str/blank? prose)
      (contract/error :seon.turn/invalid-call #'call-problem
                      "this call has no prose: say in a sentence what its form does and what you expect; the form was not run" {:problem :no-prose})
      (and forms (empty? forms))
      (contract/error :seon.turn/invalid-call #'call-problem
                      "this call has no form: its code is empty; nothing was run" {:problem :forms :forms 0}))))

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
  nothing moved."
  {:malli/schema [:=> [:cat] [:vector :map]]}
  []
  (let [db (agent/db) k (:key @agent/state) e (some->> (pdb/eid db :seon.agent/key k) (d/entity db))
        now (into {} (remove (comp nil? val)) {:seon.agent/main-commit (some-> (:main @agent/state) d/commit-id)
                                                 :seon.agent/base-commit (some-> (:base @agent/state) d/commit-id)})
        moved (into {} (remove (fn [[a v]] (= v (get e a)))) now)]
    (if (seq moved) [(assoc moved :seon.agent/key k)] [])))

(defn repeated
  "The ids of a turn's evals when every one it ran repeated code it had run before with the same answer (each
  response's :again, seon.repl/again), else nil: a turn that can tell the agent nothing new (told :again, an agent that sends the same evals turn after turn runs to the turn cap). See docs/agent.md#small-answers."
  {:malli/schema [:=> [:cat [:sequential :map]] [:maybe [:vector :string]]]}
  [calls]
  (let [ran (remove :not-run calls)]
    (when (and (seq ran) (every? #(get-in % [:response :again]) ran))
      (mapv :id ran))))

(defn- act!
  "One model turn: the agent's turn number moves (docs/turn.md#the-turn-is-a-number), the agent's context (its record and its parts, the status s among them) to the model, the reply's eval calls
  through the one REPL path, each answered as its own result, the turn's facts on its model call on the branch. Answers the loop state after it, or the
  provider error. The model call is an effect (my.fx :model, session FX1): root's model owner
  admits, performs and answers it; the API key never enters this process."
  {:malli/schema [:=> [:cat :map :map :map [:maybe :string]] [:or :map :map]]}
  [{:keys [turn max-turns merge? whole? run] :as st} {:keys [tests-ms] :as obs} s model-name]
  (let [t (:task obs)
        t0 (profile/now-ns)
        ;; the turn this is: the agent's number, one past the one in force, which the messages taken now and every record
        ;; this turn writes take
        n (inc (agent/turn-now))
        k (:key @agent/state)
        _ (take! true n)
        id (str k "/" n)
        ;; the turn first (the agent's number and its model call's first facts, with the main and merge base it heard), then its
        ;; basis: the commit that holds them, so everything its request and its provider are made from is a fact of that commit
        ;; (seon.context/wire-turn makes them again there); then what it was made from (its model call's basis): its page is
        ;; drawn on the db that holds it (its proofs as this turn observed them), then what it sent, committed, so a page
        ;; shows the call in flight and a call that fails keeps what it sent
        _ (agent/transact! (into [{:db/id "run" :seon.run/id run}
                                  {:seon.agent/key k :seon.agent/turn n}
                                  {:seon.model-call/id id :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n n :seon.model-call/origin :model
                                   :seon.model-call/run "run"}]
                                 (main-base-tx)))
        basis (cond-> (assoc (basis! (:seon.task/id t) run n max-turns :model (boolean merge?)) :agent k)
                model-name (assoc :model model-name))
        ;; the provider as the config stands at the basis (main's as the turn heard it, over the task's own), never one copied at boot
        provider (context/provider-of (agent/db) basis)
        _ (agent/transact! [{:seon.model-call/id id :seon.model-call/basis basis}])
        _ (agent/commit!)
        ;; what the run's earlier requests printed, reused by each record's id (seon.context/printed-run)
        ;; its parts are the live walk's (what changed since the frame before), never drawn cold, and the
        ;; request is made on the db the walk is drawn to: one db for both
        [db parts] (repl/page-hiccup)
        {:keys [messages sent printed]} (context/render db basis {:printed (get-in @agent/state [:printed run]) :parts parts :ctx @ctx/ctx})
        _ (swap! agent/state assoc :printed {run printed})
        context-ms (long (profile/ms-since t0))
        _ (when sent (agent/transact! [{:seon.model-call/id id :seon.model-call/sent sent}]))
        _ (agent/commit!)
        t1 (profile/now-ns)
        r (agent/fx! :model {:messages messages :task (:seon.task/id t) :turn turn :provider provider} {})
        model-ms (long (profile/ms-since t1))
        c (:value r)
        ;; the request before this one in its run: what the cache check compares (cache-miss)
        prev (get-in @agent/state [:requests run])]
    (if (contract/error? r)
      ;; the call failed: its typed error on the model call (an error is data), and to the run's end
      (do (agent/transact! [{:seon.model-call/id id :seon.model-call/error r :seon.model-call/ms model-ms
                             :seon.model-call/split {:context-ms context-ms :model-ms model-ms}}])
          r)
      (let [;; a request answered is the next one's baseline (a failed call stored no prefix); a short
            ;; hit is the model call's outcome, an error this agent folds as :effect (seon.error/note)
            _ (swap! agent/state assoc :requests {run messages})
            _ (when-let [miss (cache-miss prev messages c (cfg/value :seon.config.ai/cache-block-tokens {:task t :model model-name}))]
                (swap! agent/state update :errors err/note miss :effect id (System/currentTimeMillis)))
            t2 (profile/now-ns)
            ;; an interrupt left from before this turn's evals is not theirs
            _ (intr/clear-pending!)
            ;; each eval call through the one REPL path, in order; its response is the call's
            ;; result; the reply's prose is the first call's
            ;; each eval's prose: the reply's text (on its first call) then the call's own, as the user reads it
            ;; a call with no prose or no form, or after one that threw or answered an error, is not run: its one
            ;; result says why (seon.repl/unrun, docs/prompt.md)
            calls (reduce (fn [acc [i {:keys [id code prose]}]]
                            (let [content (when (and (zero? i) (not (str/blank? (:text c)))) (:text c))
                                  ;; the forgiving reader's repairs, told on this call's own result (:repaired)
                                  {src :text repairs :repairs} (reader/forgive code)
                                  ;; the reply's text heads its block, on its first call; each call keeps its own prose
                                  req (cond-> {:origin :model :call-id id :call i}
                                        (not (str/blank? prose)) (assoc :prose prose)
                                        content (assoc :content content)
                                        (and (zero? i) (:reasoning c)) (assoc :reasoning (:reasoning c))
                                        (seq repairs) (assoc :repaired (context/repaired src repairs)))
                                  ;; a call that failed (threw, answered an error value such as a refused edit, or had no
                                  ;; prose or no form) stops the rest: none builds on a change that never happened
                                  failed (some #(when (or (:ex (:response %)) (contract/error? (:value (:response %)))) (:id %)) acc)
                                  why (if failed
                                        (contract/error :seon.turn/not-run #'act! (str "not run: call " failed " of this reply failed before it (its result says why)") {:after failed})
                                        (call-problem prose src))]
                              (conj acc (if why
                                          {:id id :code code :prose prose :not-run (some? failed)
                                           :response (let [r (repl/unrun src req why)] (agent/commit!) r)}
                                          {:id id :code code :prose prose :response (evaluated! src req)}))))
                          [] (map-indexed vector (:tool-calls c)))
            rs (mapv :response calls)
            eval-ms (long (profile/ms-since t2))
            _ (swap! agent/state assoc-in [:turn :evals] rs)
            t3 (profile/now-ns)]
        ;; a reply with prose and no call is a message to the user: the turn ends, and the run
        ;; waits for the user (the design's tool_choice auto)
        ;; (a blank or cut reply is no message: the next turn reads the same state)
        (when (and (empty? calls) (not (str/blank? (:text c))) (not (:truncated? c))) (request-stop! :waiting (:text c)))
        ;; a turn that only repeated: the agent is told so, as any message reaches it (its next request carries it)
        (when-let [again (repeated calls)]
          ;; the next turn's: the turn that reads it
          (agent/transact! (agent/own-message-tx (str id "/repeated") :message "seon" nil
                                                 (str "Turn " turn " only ran code you had run before, each answered as it was then ("
                                                      (str/join ", " again) "): running it again tells you nothing new. Write what you know: "
                                                      "the plan, a proof or a definition, or end your turn with prose.")
                                                 (inc n))))
        ;; what it answered and how it stopped (its reply, reasoning and finish), the reply's usage, model and counts
        ;; (seon.model-call, beside the basis it read: seon.context/render makes the text from it), and where the turn's
        ;; time went as this agent saw it
        (agent/transact! [(cond-> (merge (usage c) {:seon.model-call/id id :seon.model-call/forms (count calls)
                                                    :seon.model-call/reply (:text c)
                                                    :seon.model-call/errors (count (filter :ex (map :response (remove :not-run calls))))
                                                    :seon.model-call/red (count (remove #(= :pass (:status %)) (:proofs s)))
                                                    :seon.model-call/ms (+ model-ms eval-ms)})
                            (:truncated? c) (assoc :seon.model-call/truncated true)
                            (:finish-reason c) (assoc :seon.model-call/finish (:finish-reason c))
                            (:reasoning c) (assoc :seon.model-call/reasoning (:reasoning c))
                            (:model c) (assoc :seon.model-call/model (:model c)))])
        (agent/transact! [{:seon.model-call/id id
                           :seon.model-call/split (cond-> {:context-ms context-ms :model-ms model-ms :eval-ms eval-ms
                                                           :record-ms (long (profile/ms-since t3))}
                                                    tests-ms (assoc :tests-ms tests-ms))}])
        (cond-> (-> (assoc st :turn (inc turn))
                    ;; what this turn's parts showed, by producer, and what it evaluated (waste)
                    (update :log (fnil conj []) {:n turn
                                                 :shown {'seon.render.ui/cite-model (set (keep :seon.cite/name (task/task-cites db (:seon.task/id t))))
                                                         'seon.context/status (set (map :test (:proofs s)))}
                                                 :items (mapv (fn [{:keys [code response]}] {:form code :error (:ex response)}) calls)}))
          ;; a merge turn: each turn of a merge task; a whole task's own work comes first, so
          ;; only a turn with what both sides changed still to resolve
          (and merge? (or (not whole?) (seq (:unmerged s)))) (update :merge-turns inc))))))

(defn context-block
  "The request of task as a model is sent it on this branch now (the proofs run again): what an
  outside agent working the task reads, the system prompt and its page, as one text, recorded as a turn of the run
  (origin :outside, id \"<agent key>/read-<k>\", k the agent's own count of reads: a read moves no turn,
  docs/turn.md#the-turn-is-a-number). It also makes this process work task as run, so the my.* functions act
  on it. take?: the read is the run's turn (its
  model is outside) and takes the messages delivered; else it is an observer's, and leaves them
  for the model's next turn."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id :boolean] :string]}
  [task run take?]
  (let [_ (begin! task run)
        {:keys [tests-ms]} (observe task)
        k (:key @agent/state)
        ;; the agent's own count of reads, from the branch: a process, a run or a wake does not restart it
        n (inc (or (d/q '[:find (max ?n) . :in $ ?a :where [?m :seon.model-call/agent ?a] [?m :seon.model-call/origin :outside] [?m :seon.model-call/n ?n]]
                        (agent/db) (pdb/eid (agent/db) :seon.agent/key k))
                   0))
        t0 (profile/now-ns)
        ;; an observer's read records nothing on the branch beyond the read and leaves the print cache
        _ (take! take? (inc (agent/turn-now)))
        ;; the main and merge base the read heard, decided as a model turn's are, before its request is drawn
        _ (when take? (when-let [tx (not-empty (main-base-tx))] (agent/transact! tx) (agent/commit!)))
        basis (assoc (basis! task run n 1 :outside true) :agent k)
        [db parts] (repl/page-hiccup)
        {:keys [messages printed sent]} (context/render db basis {:printed (get-in @agent/state [:printed run]) :parts parts :ctx @ctx/ctx})
        _ (when take? (swap! agent/state assoc-in [:printed run] printed))
        text (str/join "\n\n" (map :content messages))]
    ;; the read is a model call of this run, as a model turn's is: what it was built from and what it cost
    (agent/transact! [{:db/id "run" :seon.run/id run}
                      (cond-> {:seon.model-call/id (str k "/read-" n) :seon.model-call/agent {:seon.agent/key k} :seon.model-call/n n
                               :seon.model-call/origin :outside :seon.model-call/run "run" :seon.model-call/basis basis
                               :seon.model-call/split (cond-> {:context-ms (long (profile/ms-since t0))} tests-ms (assoc :tests-ms tests-ms))}
                        sent (assoc :seon.model-call/sent sent))])
    text))

(defn hand-back-end
  "How an outside agent's run ends: [outcome reason] from the stop its agent requested
  (my.task/fail! makes it :failed, with the reason it gave) and the done rule's reasons. A task
  the agent failed ends :failed whatever the proofs say, as the turn loop ends it (work!); else
  :green when the done rule has no reason, else :red with the reasons."
  {:malli/schema [:=> [:cat [:maybe :map] [:sequential :any]] [:tuple :seon.run/outcome [:maybe :string]]]}
  [stop reasons]
  (cond (= :failed (:outcome stop)) [:failed (:reason stop)]
        (empty? reasons) [:green nil]
        :else [:red (r/explain-text reasons)]))

(defn hand-back!
  "End run of task as an outside agent hands it back (seon.server's :done): the proofs run
  again, and the run's end written as the turn loop writes its own (end!: the live runtime
  staged, the end committed on the branch): hand-back-end says how it ends. Its turns, model calls, tokens and time are the outside model's,
  unknown here, so absent (never 0). The judge decides from its own run of the proofs and the
  plan the run began with on main; this end is what the run says of itself. Without it the
  judge read no end and named the run :no-end. Answers the end."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id] :seon.turn/end]}
  [task run]
  (let [;; read before begin!, which starts the turn state anew when this run has none yet
        stop (get-in @agent/state [:turn :stop])
        _ (begin! task run)
        _ (observe task)
        s (status-now)
        [outcome reason] (if (contract/error? s) [:error (:seon.error/message s)] (hand-back-end stop (:reasons s)))]
    (end! {:run run} outcome reason (when (contract/error? s) s))))

(defn work!
  "Work task `task` as run `run` (the model `model` when set, else root's), at most
  max-turns model calls.
  Answers the end ({:outcome :turns :model-calls :tokens :ms}); the same end is written on
  the branch. It first merges main into the branch (my.branch/merge-main!), as a merge task
  does again at each green."
  {:malli/schema [:=> [:cat [:map [:task :seon.task/id] [:run :seon.run/id] [:model {:optional true} :string]
                             [:max-turns pos-int?]]]
                  :seon.turn/end]}
  [{:keys [task run model] :as arg}]
  (let [_ (begin! task run)
        ;; the agent's own budget (:seon.agent/max-turns, raised to extend it) bounds the loop; the arg is the default
        max-turns (or (:seon.agent/max-turns (d/entity (agent/db) [:seon.agent/key (:key @agent/state)])) (:max-turns arg))
        ;; the cap this run works under, a fact of the run as root records it at a run's start (seon.task/run-start-tx),
        ;; when the run began here: what its page's turn line reads (seon.render.ui/run-head)
        _ (when-not (:seon.run/max-turns (d/entity (agent/db) [:seon.run/id run]))
            (agent/transact! [{:seon.run/id run :seon.run/max-turns (:max-turns arg)}]))
        t0-task (task/task (agent/db) task)
        merge? (task/merges? (:seon.task/kind t0-task))
        merge-cfg (r/merge-limits t0-task)]
    ;; every run starts on main: a kept branch made before main moved merges it first, so
    ;; the branch's db, its base and this process hold one program (its tests' digests are the
    ;; program it runs); a branch at main's head brings nothing
    (branch/merge-main!)
    (loop [st {:turn 0 :merge-turns 0 :t0 (profile/now-ns) :run run :whole? (= :whole (:seon.task/kind t0-task))
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
            asked (get-in @agent/state [:requests run])
            step (repl/with-branch-schemas
                   (fn []
                     (try
                       ;; main moved since the last turn: merge it in now, so the agent works on main's program and
                       ;; wiki as they are and a conflict is an unmerged entry it sees and resolves before it can
                       ;; merge (the owner, 2026-10-30: "updates as often as we can")
                       (when (agent/main-moved? (:base @agent/state) (agent/heard-main)) (branch/merge-main!))
                       (let [obs (observe task)
                             ;; how the work stands, from the walk the request is drawn from (one computation a turn)
                             s (status-now)
                             ;; its proofs pass: the tests its change reaches run now, judged as the judge judges them
                             ;; (agent/prove! :judge, their results facts on the branch), and the status is read again
                             ;; from the branch, before it is done or merges (the owner's ruling: a turn runs the
                             ;; proofs, the reach runs once they are green). a run that ended green on its proofs
                             ;; alone left the judge to find the reds its change made
                             s (if (and (not (contract/error? s)) (empty? (:reasons s)))
                                 (do (test-error! (agent/prove! :judge))
                                     ;; the walk drawn again, to the branch the judge's verdicts were written on
                                     (status-now))
                                 s)
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
                             (if (and (empty? (:applied m)) (empty? (:conflicts m))) (ended st :green nil nil) [::next st]))
                           ;; the agent's own fail! is its verdict, at the cap too; a wait on the last turn is
                           ;; the budget spent, so the run is :cap, never :waiting (its session parks either way)
                           (= :failed (:outcome stop)) (ended st :failed (:reason stop) nil)
                           hairy (ended st :merge-failed (:reason hairy) nil)
                           ;; the one check of the cap, before any model call: every turn reaches it, one with no
                           ;; status and one after a turn that threw included (a change that broke
                           ;; the status would otherwise let the run make model calls past the cap)
                           (>= (:turn st) max-turns) (if no-status?
                                                       (ended st :cap (str "no status at the cap: " (:seon.error/message s)) s)
                                                       (ended st :cap (r/explain-text reasons) nil))
                           stop (ended st (:outcome stop) (:reason stop) nil)
                           ;; the walk drew no status (the agent's program broke the layout): the agent reads why, this turn
                           no-status? (next-of (act! (turn-error! st s) obs s model))
                           :else (next-of (act! st obs s model))))
                       (catch Throwable e
                         (if (:interrupted (ex-data e))
                           (throw e)
                           ;; the second in a row with no model turn between ends its work with that error: a turn
                           ;; whose model call answered before it threw was the agent's turn, so the count starts again
                           (let [called? (not (identical? asked (get-in @agent/state [:requests run])))
                                 st' (cond-> (update (turn-error! st (agent/error-map e 'seon.turn/work!)) :turn inc)
                                       called? (assoc :threw 1))]
                             ;; a turn that threw counts against the cap, which the next turn checks
                             (if (< 1 (:threw st'))
                               (ended st' :error (str "two turns in a row threw: " (:seon.error/message (:error st'))) (:error st'))
                               [::next st'])))))))]
        (case (first step)
          ::next (recur (second step))
          ::end (let [[_ st outcome reason err] step] (end! st outcome reason err)))))))
