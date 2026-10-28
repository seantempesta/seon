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
            [seon.stage :as stage] [my.branch :as branch] [seon.reader :as reader] [seon.learn :as learn] [seon.config :as cfg] [seon.lib :as lib]
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
  turn state, begun once per run with the task's accumulated plan as the branch held it (a
  design step answers for improving it).
  The turn loop and an outside agent's context read begin it the same way."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id] :map]}
  [task run]
  (if (= run (get-in @agent/state [:turn :run]))
    (:turn @agent/state)
    ;; the run is this session's on its own branch too (seon.agent/begin-run!), and a fork's task
    ;; with it, read after that write
    (do (agent/begin-run! task run)
        (:turn (swap! agent/state assoc :turn {:run run
                                               :plan-before (:plan (:seon.task/accumulated (task/task (agent/db) task)))})))))

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

(defn- index!
  "The reconciler's index of db, observed from the one this process held for its db at the
  turn before (seon.reconcile/index over what differs), at the first turn from main's as the
  fork server handed it (seon.fork-server/index-at-fork); listed whole only when it holds none."
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
  the task as the branch holds it (its plan may have improved): the done rule's inputs, nothing
  else. What else a reader wants of the branch (whether it can merge, what its merge carries,
  what it changed under the wiki, its tests) is a render of the task's (seon.render.ui/merge-view,
  divergence-view, caused-view, tests-view), drawn when what it reads moves, never per turn."
  {:malli/schema [:=> [:cat :seon.task/id :seon.task/plan] :map]}
  [id plan-before]
  (let [;; the branch's own texts embedded first, so the plan's vector is the plan's now
        _ (agent/embed-written!)
        t0 (profile/now-ns)
        test-run (agent/prove!)
        tests-ms (long (profile/ms-since t0))
        results (if (contract/error? test-run) {} (proofs/results test-run))
        db (agent/db)
        ch (agent/branch-changes (:base @agent/state) db)
        ;; the functions the branch defines without a contract (seon.merge/uncontracted): what root's judge and the merge gate ask
        unc (set (pm/uncontracted ch))
        unmerged (branch/unmerged)
        {:keys [caused]} (problems/caused-split (:base @agent/state) db ch)
        t (task/task db id)]
    {:test-run test-run :tests-ms tests-ms :results results :uncontracted unc :unmerged unmerged :caused caused :task t
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
  done rule, the failing assertions of each test it names and the test run's own errors."
  {:malli/schema [:=> [:cat :map] :map]}
  [{:keys [test-run reasons task results]}]
  (let [proofs (:proofs (:seon.task/accumulated task))
        named (into (set proofs) (keep :symbol) reasons)]
    {:results results :reasons reasons
     :reds (if (contract/error? test-run) [] (proofs/red-reports test-run named))
     :test-run-errors (if (contract/error? test-run) [(:seon.error/message test-run)] (proofs/test-run-errors test-run))}))

(defn- take!
  "The entries since the last turn (a person's message, root's at a stage boundary, a refused
  verdict: :seon.message/kind), taken before the turn's request is made, so each sits in the
  conversation before the page that shows what came after it (a session's next stage opens with
  its :stage entry, seon.reconcile/stage-boundary, before that stage's first page). They are the
  facts main holds to the session's runs that this branch does not (seon.agent/untaken-entries):
  delivery is the fact, which root syncs before anything that follows it. The turn is their
  reader: each is written on this branch first, the same fact root wrote on main, and named by
  id, so its text is read again from the commit; with them, what root wrote on main of the
  agent's own entities (seon.agent/untaken-facts). The inbox holds the sends no fact holds
  (another agent's), shown and taken with them. A read that is not the run's turn (take? false:
  an observer's read of an agent its model drives) shows both and writes nothing. Answers the
  observations they add: {:message-ids [..]} and {:messages [{:from :body} ..]}, each only when
  there are some."
  {:malli/schema [:=> [:cat :boolean] :map]}
  [take?]
  (let [sends (agent/inbox)
        entries (if take? (agent/take-main!) (agent/untaken-entries))]
    ;; taken once their facts are committed: a refused write leaves them for the next turn
    (when take? (agent/taken! (count sends)))
    (cond-> {}
      (and take? (seq entries)) (assoc :message-ids (mapv :id entries))
      ;; an observer's read: the entries as they are, unwritten
      (and (not take?) (seq entries)) (assoc :messages (mapv #(select-keys % [:from :body]) entries))
      (seq sends) (update :messages (fnil into []) (mapv (fn [{:keys [from body]}] {:from (name from) :body body}) sends)))))

(defn- basis!
  "What a turn's context is built from, recorded instead of its text (a model call stores its
  basis, not its prompt): the commit it reads (the run-ahead writes committed now, as the
  model call's wait would), the task, run and turn, the observations no db holds, with the
  messages the turn took (taken, take!)."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id :int :int :seon.turn/origin :map :map] :seon.turn/basis]}
  [task run turn max-turns origin observed taken]
  (agent/commit!)
  (cond-> {:commit (d/commit-id (agent/db)) :task task :run run :turn turn :max-turns max-turns
           :origin origin :observed (merge (dissoc observed :merge?) taken)}
    (:merge? observed) (assoc :merge? true)))

(defn- call-problem
  "Why an eval call, its prose and its code, is not the eval tool's shape, as the error its one
  result is, or nil: a call is prose and exactly one form (owner, 2026-10-28; docs/prompt.md).
  Code that does not read is left to the REPL, whose read error says where."
  {:malli/schema [:=> [:cat [:maybe :string] :string] [:maybe :seon.turn/invalid-call]]}
  [prose code]
  (let [forms (reader/read-text code 'user)]
    (cond
      (str/blank? prose)
      (contract/error :seon.turn/invalid-call #'call-problem
                      "this call has no prose: say in a sentence what its form does and what you expect; the form was not run" {:problem :no-prose})
      (and forms (not= 1 (count forms)))
      (contract/error :seon.turn/invalid-call #'call-problem
                      (str "one form per call: this code holds " (count forms) " forms; make each its own call. None was run")
                      {:problem :forms :forms (count forms)}))))

(defn- evaluated!
  "Code through the one REPL path (seon.repl/eval-string, req) and its record committed to the
  branch as it lands (seon.agent/commit!), so a page shows each eval then, not at the turn's end
  (design section 11, item 1). Answers the response."
  {:malli/schema [:=> [:cat :string :map] :seon.eval/response]}
  [code req]
  (let [r (repl/eval-string code req)]
    (agent/commit!)
    r))

(defn- observed!
  "What a turn of task (a model's, or an outside agent's context read) observed, held for
  my.task/status (`input`): the turn input as this turn's observe measured it
  (seon.context/turn-input over in)."
  {:malli/schema [:=> [:cat :seon.task/id :map] :any]}
  [task in]
  (swap! agent/state assoc-in [:turn :observed]
         {:task task :input (context/turn-input (agent/db) in (get-in @agent/state [:turn :last]))}))

(defn input
  "What this process's last turn observed (my.task/status): {:task :input}, the task and the turn
  sections' input as its observe measured it; nil before any turn of this process observed its task."
  {:malli/schema [:=> [:cat] [:maybe :map]]}
  [] (get-in @agent/state [:turn :observed]))

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

(defn- included-tx
  "The transaction that records on the agent, before its request is drawn, what its context includes
  beside task now (seon.context/included) and the main and merge base it last heard (the commits its
  page's branch views read): what the page and a replay draw; none when nothing moved."
  {:malli/schema [:=> [:cat :seon.task/id] [:vector :map]]}
  [task]
  (let [db (agent/db) k (:key @agent/state) e (some->> (pdb/eid db :seon.agent/key k) (d/entity db))
        now (into {} (remove (comp nil? val)) {:seon.agent/included (context/included db task k)
                                                 :seon.agent/main-commit (some-> (:main @agent/state) d/commit-id)
                                                 :seon.agent/base-commit (some-> (:base @agent/state) d/commit-id)})
        moved (into {} (remove (fn [[a v]] (= v (get e a)))) now)]
    (if (seq moved) [(assoc moved :seon.agent/key k)] [])))

(defn- act!
  "One model turn: the context of what was observed to the model, the reply's eval calls
  through the one REPL path, each answered as its own result, the turn's facts on the branch. Answers the loop state after it, or the
  provider error. The model call is an effect (my.fx :model, session FX1): root's model owner
  admits, performs and answers it; the API key never enters this process."
  {:malli/schema [:=> [:cat :map :map [:maybe :string]] [:or :map :map]]}
  [{:keys [turn tokens max-turns merge? whole? run] :as st} {:keys [results tests-ms unmerged] :as obs} model-name]
  (let [t (:task obs)
        proofs (:proofs (:seon.task/accumulated t))
        t0 (profile/now-ns)
        ob (cond-> (observed obs) merge? (assoc :merge? true))
        taken (take! true)
        _ (observed! (:seon.task/id t) {:turn turn :max-turns max-turns :origin :model :observed ob :merge? merge?})
        basis (assoc (basis! (:seon.task/id t) run turn max-turns :model ob taken) :agent (:key @agent/state))
        id (str run "/" turn)
        ;; the turn first, what it was made from: its page is drawn on the db that holds it (its
        ;; proofs as this turn observed them), then what it sent, committed, so a page shows the
        ;; call in flight and a call that fails keeps what it sent
        _ (agent/transact! (into [{:db/id "run" :seon.run/id run}
                                  {:seon.turn/id id :seon.turn/run "run" :seon.turn/n turn :seon.turn/origin :model :seon.turn/basis basis}]
                                 (included-tx (:seon.task/id t))))
        _ (agent/commit!)
        ;; what the run's earlier requests printed, reused by each record's id (seon.context/printed-run)
        ;; its page is the live walk's (what changed since the frame before), never drawn cold, and the
        ;; request is made on the db the walk framed at: one commit for both
        [db page] (repl/page-hiccup)
        {:keys [block messages sent printed]} (context/render db basis {:printed (get-in @agent/state [:printed run]) :pages {(d/commit-id db) page}})
        _ (swap! agent/state assoc :printed {run printed})
        context-ms (long (profile/ms-since t0))
        _ (when sent (agent/transact! [{:seon.turn/id id :seon.turn/sent sent}]))
        _ (agent/commit!)
        t1 (profile/now-ns)
        ;; the provider as the config stands at this turn (main's, over the task's own), never one copied at boot
        r (agent/fx! :model (cond-> {:messages messages :task (:seon.task/id t) :turn turn
                                     :provider (cfg/provider-at (agent/heard-main) (agent/db) (:seon.task/id t))}
                              model-name (assoc :model model-name))
                     {})
        model-ms (long (profile/ms-since t1))
        c (:value r)
        ;; the request before this one in its run: what the cache check compares (cache-miss)
        prev (get-in @agent/state [:requests run])]
    (if (contract/error? r)
      ;; the call failed: its typed error on the turn (an error is data), and to the run's end
      (do (agent/transact! [{:seon.turn/id id :seon.turn/error r :seon.turn/ms model-ms
                             :seon.turn/split {:context-ms context-ms :model-ms model-ms}}])
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
            ;; a call that is not prose and one form, or after one that threw, is not run: its one
            ;; result says why (seon.repl/unrun, docs/prompt.md)
            calls (reduce (fn [acc [i {:keys [id code prose]}]]
                            (let [content (when (and (zero? i) (not (str/blank? (:text c)))) (:text c))
                                  ;; the reply's text heads its block, on its first call; each call keeps its own prose
                                  req (cond-> {:origin :model :call-id id :call i}
                                        (not (str/blank? prose)) (assoc :prose prose)
                                        content (assoc :content content)
                                        (and (zero? i) (:reasoning c)) (assoc :reasoning (:reasoning c)))
                                  threw (some #(when (:ex (:response %)) (:id %)) acc)
                                  ;; the forgiving reader's repairs, told on this call's own result (:repaired)
                                  {src :text repairs :repairs} (reader/forgive code)
                                  req (cond-> req (seq repairs) (assoc :repaired (context/repaired src repairs)))
                                  why (if threw
                                        (contract/error :seon.turn/not-run #'act! (str "not run: call " threw " of this reply threw before it") {:after threw})
                                        (call-problem prose src))]
                              (conj acc (if why
                                          {:id id :code code :prose prose :not-run (some? threw)
                                           :response (let [r (repl/unrun src req why)] (agent/commit!) r)}
                                          {:id id :code code :prose prose :response (evaluated! src req)}))))
                          [] (map-indexed vector (:tool-calls c)))
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
                          (cond-> (merge (usage c) {:seon.turn/id id :seon.turn/run "run" :seon.turn/n turn
                                   :seon.turn/calls (mapv (fn [{:keys [id code response]}]
                                                            (cond-> {:id id :code code} (:record response) (assoc :eval (:record response))))
                                                          calls)
                                   :seon.turn/reply (:text c) :seon.turn/forms (count calls)
                                   :seon.turn/errors (count (filter :ex (map :response (remove :not-run calls))))
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
        (cond-> (-> (assoc st :turn (inc turn) :tokens (when (and tokens used) (+ tokens used)))
                    ;; what this turn's block showed, by producer, and what it evaluated (waste)
                    (update :log (fnil conj []) {:n turn
                                                 :shown {'seon.docs/resolve-ref (set (keep :symbol (:write-set block)))
                                                         'seon.context/current-state (set (map :test (:proofs block)))}
                                                 :items (mapv (fn [{:keys [code response]}] {:form code :error (:ex response)}) calls)}))
          ;; a merge turn: each turn of a merge task; a whole task's own work comes first, so
          ;; only a turn with what both sides changed still to resolve
          (and merge? (or (not whole?) (seq unmerged))) (update :merge-turns inc))))))

(defn context-block
  "The request of task as a model is sent it on this branch now (the proofs run again): what an
  outside agent working the task reads, the system prompt and its page, as one text, recorded as a turn of the run
  (origin :outside). It also makes this process work task as run, so the my.* functions act
  on it (my.task/status answers as this read measured). take?: the read is the run's turn (its
  model is outside) and takes the messages delivered; else it is an observer's, and leaves them
  for the model's next turn."
  {:malli/schema [:=> [:cat :seon.task/id :seon.run/id :boolean] :string]}
  [task run take?]
  (let [{:keys [plan-before]} (begin! task run)
        {:keys [tests-ms] :as obs} (observe task plan-before)
        n (get-in (swap! agent/state update-in [:turn :reads] (fnil inc 0)) [:turn :reads])
        t0 (profile/now-ns)
        ob (assoc (observed obs) :merge? true)
        ;; the run's turn (take?) measures what my.task/status answers, as a model turn does; an
        ;; observer's read records nothing on the branch beyond the read and leaves the print cache
        taken (take! take?)
        _ (when take? (observed! task {:turn n :max-turns 1 :origin :outside :observed ob :merge? true}))
        ;; the read's related units, decided as a model turn's are, before its request is drawn
        _ (when take? (when-let [tx (not-empty (included-tx task))] (agent/transact! tx) (agent/commit!)))
        basis (assoc (basis! task run n 1 :outside ob taken) :agent (:key @agent/state))
        [db page] (repl/page-hiccup)
        {:keys [messages printed sent]} (context/render db basis {:printed (get-in @agent/state [:printed run])
                                                                  :pages (assoc (get-in @agent/state [:pages run]) (d/commit-id db) page)})
        _ (when take? (swap! agent/state assoc-in [:printed run] printed))
        ;; the page it drew, kept by every read (an observer's too, which records a turn): the next diff draws no page twice
        _ (swap! agent/state assoc-in [:pages run] (:pages printed))
        text (str/join "\n\n" (map :content messages))]
    ;; the read is a turn of this run, as a model turn is: what it was built from and what it cost
    (agent/transact! [{:db/id "run" :seon.run/id run}
                      (cond-> {:seon.turn/id (str run "/read-" n) :seon.turn/run "run" :seon.turn/n n
                               :seon.turn/origin :outside :seon.turn/basis basis
                               :seon.turn/split (cond-> {:context-ms (long (profile/ms-since t0))} tests-ms (assoc :tests-ms tests-ms))}
                        sent (assoc :seon.turn/sent sent))])
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
        {:keys [plan-before]} (begin! task run)
        {:keys [reasons]} (observe task plan-before)
        [outcome reason] (hand-back-end stop reasons)]
    (end! {:run run} outcome reason nil)))

(defn work!
  "Work task `task` as run `run` (the model `model` when set, else root's), at most
  max-turns model calls. A call of code the run ran before, answered the same, is told so on
  the answer it reads (seon.context/printed-run, :same-as): no count decides an end (the
  repeats of the live runs followed answers that said too little, a value
  folded to an earlier one's form or an error that named nothing to change, and each was fixed
  at that answer).
  Answers the end ({:outcome :turns :model-calls :tokens :ms}); the same end is written on
  the branch. It first merges main into the branch (my.branch/merge-main!), as a merge task
  does again at each green."
  {:malli/schema [:=> [:cat [:map [:task :seon.task/id] [:run :seon.run/id] [:model {:optional true} :string]
                             [:max-turns pos-int?]]]
                  :seon.turn/end]}
  [{:keys [task run model max-turns]}]
  (let [{:keys [plan-before]} (begin! task run)
        t0-task (task/task (agent/db) task)
        merge? (task/merges? (:seon.task/kind t0-task))
        merge-cfg (r/merge-limits t0-task)]
    ;; every run starts on main: a kept branch made before main moved merges it first, so
    ;; the branch's db, its base and this process hold one program (its tests' digests are the
    ;; program it runs); a branch at main's head brings nothing
    (branch/merge-main!)
    (loop [st {:turn 0 :tokens 0 :merge-turns 0 :t0 (profile/now-ns) :run run :whole? (= :whole (:seon.task/kind t0-task))
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
          ;; the agent's own fail! is its verdict, at the cap too; a wait on the last turn is
          ;; the budget spent, so the run is :cap, never :waiting (its session parks either way)
          (= :failed (:outcome stop)) (end! st :failed (:reason stop) nil)
          hairy (end! st :merge-failed (:reason hairy) nil)
          (>= (:turn st) max-turns) (end! st :cap (r/explain-text reasons) nil)
          stop (end! st (:outcome stop) (:reason stop) nil)
          :else (let [st' (act! st obs model)]
                  (if (contract/error? st') (end! st :error (:seon.error/message st') st') (recur st'))))))))
