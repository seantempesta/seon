(ns seon.server
  "Root's MCP and request server for outside agents (Claude Code through MCP, a person): a
  socket on 127.0.0.1, one request per line in, one answer per line out (R1's
  proto.agents.entrance, on the flow core). A line is line EDN ({:op k ...}), or MCP's
  JSON-RPC when it opens `{\"` (mcp-reply; bin/mcp relays a client's stdio here). An
  outside agent evaluates in any running agent's process (recorded on that agent's branch,
  origin :outside) or as root (in root's process, with root's functions, through the same
  REPL path, recorded on main), and can work a task as a Seon agent. Its ops are one table
  (`ops`: {:op k ...args}), which MCP lists as its tools.

  The listener and each connection are edge threads (blocking accept and reads); a
  connection is an outside caller of root's API, as bin/check is. Try it:
  printf '{:op :agents}\\n' | nc -w 5 127.0.0.1 PORT
  printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\\n' | nc -w 5 127.0.0.1 PORT"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.step :as step] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.system :as c] [my.root :as root] [seon.fx :as fx] [seon.config :as cfg] [seon.task :as task] [seon.proofs :as proofs] [seon.error :as err] [seon.reconcile :as r] [datahike.api :as d]
            [seon.lib :as lib] [seon.edn :as sedn] [seon.db :as pdb] [seon.repl :as repl] [seon.print :as print] [seon.ctx.wire :as wire] [clojure.edn :as edn] [clojure.data.json :as json] [clojure.string :as str] [clojure.walk] [clojure.core.async :as a] [jolt.fibers :as fibers]))

(defn- refused
  "The server refusing a request, as root's API does: the one error map."
  {:malli/schema [:=> [:cat :string] :seon.system/refused]}
  [why] (contract/error :seon.system/refused #'seon.server/refused why))

(defn task-summaries
  "Every task on main db: {id {:execution :done :blocked-by :agent :outcome :failure}}: the server's and the
  e2e harness's one summary. A key says something or is left out: :done only when done, :blocked-by only when
  there are some, :agent and :outcome only when an agent has worked it (676 held tasks spelled
  `:done false, :blocked-by #{}` each). With idx, the db's reconciler index (`seon.reconcile/index`) that a caller holds already, none is built;
  with tasks too, only those ([id task] of idx's :tasks) are summarized (docs/tasks.md)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map] [:=> [:cat :seon.host/db :seon.reconcile/index] :map]
                  [:=> [:cat :seon.host/db :seon.reconcile/index [:sequential :any]] :map]]}
  ([db] (task-summaries db (r/index db)))
  ([db idx] (task-summaries db idx (:tasks idx)))
  ([_db idx tasks]
    (into (sorted-map)
          (for [[id t] tasks
                :let [blocked (r/blocked-by idx id)
                      agent (some-> (get-in idx [:agents (:seon.task/agent t) :seon.agent/key]) keyword)]]
            [id (cond-> {:execution (:seon.task/execution t)}
                  (r/done? idx id) (assoc :done true)
                  (seq blocked) (assoc :blocked-by blocked)
                  agent (assoc :agent agent)
                  (:seon.task/outcome t) (assoc :outcome (:seon.task/outcome t))
                  ;; the reason cut by the one printer: a held task's reason names every red (about
                  ;; 300 once), and an answer stays small; the whole is (seon.server/verdict ...)'s
                  (:seon.task/failure t) (assoc :failure (print/text (:reason (:seon.task/failure t)) (:part print/budgets))))]))))

(defn live-tasks
  "What is live on main db, as an outside agent reads it: :tasks, each task running or with an agent
  (task-summaries), the latest started first; :others, every other task counted by its execution; :all,
  the form that lists them all at root's REPL. Main holds hundreds of held tasks with no agent
  (580 on the standing root), which listed whole hid what runs. A running task whose agent has not
  started yet comes last."
  {:malli/schema [:function [:=> [:cat :seon.host/db] [:map [:tasks :map] [:others :map] [:all :any]]]
                  [:=> [:cat :seon.host/db :seon.reconcile/index] [:map [:tasks :map] [:others :map] [:all :any]]]]}
  ([db] (live-tasks db (r/index db)))
  ([db idx]
  (let [started (fn [id] (or (some->> (get-in idx [:tasks id :seon.task/agent]) (vector :seon.agent/key) (d/entity db) :seon.agent/started-ms) 0))
        ;; live: running, or with an agent (task-summaries' :agent): only those are summarized, the rest counted
        live? (fn [[_ t]] (or (= :running (:seon.task/execution t)) (some? (get-in idx [:agents (:seon.task/agent t) :seon.agent/key]))))
        {live true others false} (group-by live? (:tasks idx))]
    {:tasks (apply array-map (mapcat identity (sort-by (comp started key) #(compare %2 %1) (task-summaries db idx (or live [])))))
     :others (frequencies (map (comp :seon.task/execution val) others))
     :all '(seon.server/task-summaries @(:conn sys))})))

(defonce ^:private held-index
  ;; {conn {:commit id :index idx}}: the last reconciler index this process made of each root's main, derived data
  ;; (docs/data-modeling.md#derived-data-is-a-cache-never-relied-on), kept by the commit it is of, never a db value
  (atom {}))

(defn- index-now
  "Main db's reconciler index (seon.reconcile/index), brought from the one this process last made for conn by what
  changed since (index's incremental arity over the commit it was made on, d/commit-as-db), as the fork server keeps
  its own (seon.fork-server/index-at-fork): the cost of what main changed, not of every task (a whole index was 37-46
  ms of each :agents answer on a root of ~800 tasks). See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.host/conn :seon.host/db] :seon.reconcile/index]}
  [conn db]
  (let [{:keys [commit index]} (get @held-index conn)
        now (d/commit-id db)
        idx (cond (and index (= commit now)) index
                  index (r/index index (d/commit-as-db conn commit) db)
                  :else (r/index db))]
    (swap! held-index assoc conn {:commit now :index idx})
    idx))

(defn agents-answer
  "Live agents (id, branch, status, task) and what is live on main (live-tasks), over main's index kept by change (index-now)."
  {:malli/schema [:=> [:cat :seon.system/sys] :map]}
  [sys]
  (assoc (let [db @(:conn sys)] (live-tasks db (index-now (:conn sys) db)))
         :agents (into (sorted-map) (for [[id a] (:agents (c/state sys)) :when (not (c/gone-statuses (:status a)))]
                                      [id (select-keys a [:branch :status :task])]))))

(def ^:dynamic *sys*
  "This root while its REPL evaluates a form: the `sys` each form reads (root-eval)."
  nil)

(defn- root-eval
  "Evaluate code as root through the one REPL path (seon.repl/eval-string: the printer,
  cost, error maps, *1 and *e), in root.repl (my.root as root, seon.system as c, datahike as
  d) with `sys` a local bound to this root, unbounded (root's own forms take no tick or allocation
  budget: a whole-program load allocates tens of GB, and the agent's per-form
  budget stopped it); its response recorded on main as root's eval, by the user (seon.repl/record-tx). The record's write is
  sent and not waited on: nothing reads it before the answer, and root's writer keeps its order before any later write
  (it cost 6-30 ms of every root eval's 45-54 ms). See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.system/sys :string] :seon.repl/answer]}
  [sys code]
  (when-not (find-ns 'root.repl)
    (binding [*ns* (create-ns 'root.repl)] (refer-clojure) (require '[my.root :as root] '[seon.system :as c] '[datahike.api :as d])))
  (let [r (binding [*sys* sys]
            (repl/eval-string code {"ns" "root.repl" :origin :outside :ticks 0 :bytes 0
                                    :repl/eval-fn (fn [form] (eval (list 'let '[sys seon.server/*sys*] form)))}))]
    (c/transact-main! sys (repl/record-tx {:seon.system/id :root} (str "root-outside-" (profile/now-ns)) (task/sender (cfg/value :seon.config.root/user-name))
                                          (repl/record-details code r {})))
    ;; its value too: the :eval answer passes it on when it is plain data (op-answer :eval)
    r))

(defn- live-run
  "Task id's running agent whose process lives in root's view now, as [work nil], or [nil dead]
  with dead its running agent that is gone (died, ended by seon_done, killed for memory): a
  death is a fact root's view reads, so a dead agent never answers as the one to work the
  task (attach! starts a fresh agent on the same branch). [nil nil] when none runs."
  {:malli/schema [:=> [:cat :seon.system/sys :seon.host/db :seon.task/id] [:tuple [:maybe :map] [:maybe :map]]]}
  [sys db id]
  (if-let [r (some #(when (= id (:task %)) %) (task/running db))]
    (let [a (get-in (c/state sys) [:agents (:agent r)])]
      (if (and a (not (c/gone-statuses (:status a)))) [r nil] [nil r]))
    [nil nil]))

(defn- attach!
  "Start an outside agent on task id: its env's model is \"outside\" (the task keeps its own), so the
  reconciler starts no turn loop for it and does not kill it. The agent is spawned first;
  only once it exists are its env, the release and its start written, in one transaction,
  so a refused spawn writes nothing and the reconciler never sees a release with no agent
  (it would spawn a second agent for it). The session's agent continues (its key) unless fresh?:
  a reset, the task's branch a new one cut from main and its conversation empty (the agent on the
  old branch is killed here once the task is the new one's: the reconciler compares the task's own agent, so it never
  saw the old one; that branch and its records stay). A task
  whose agent is live answers that agent, :shared true: the caller has the branch, with
  whoever attached it, and that one's seon_done ends it for both. An agent that is gone (live-run)
  is replaced by a fresh one on its branch, the session's key continuing. A branch another live agent holds
  is spawn!'s refusal, answered as a value, naming the agent and the task it works.
  clone-of: the agent is that live agent's clone (seon.system/clone!: live values and all, its branch
  cut from the clone's once its writes commit), its own key and conversation (a fork's). client:
  who attached it (bin/mcp's id for its session), the agent's :holder: only that client's requests,
  or ones that name the task, act on it (held).
  Answers {:agent :task}."
  {:malli/schema [:function [:=> [:cat :seon.system/sys :seon.task/id] :map]
                  [:=> [:cat :seon.system/sys :seon.task/id [:map [:fresh? {:optional true} :boolean] [:clone-of {:optional true} :keyword] [:client {:optional true} [:maybe :string]]]] :map]]}
  ([sys id] (attach! sys id {}))
  ([sys id {:keys [fresh? clone-of client]}]
  (let [db @(:conn sys)
        t (task/task db id)
        [live dead] (if (or fresh? clone-of) [nil nil] (live-run sys db id))]
    (cond
      (not t) (refused (str "no task " id))
      ;; its agent is live: it is the one to work it (a second spawn on its branch is refused)
      live {:agent (:agent live) :task id :shared true}
      :else
      (let [env (cond-> (assoc (r/desired-env t) :model "outside")
                  fresh? (assoc :branch (keyword (str (name (task/default-branch id)) "-r" (random-uuid))))
                  client (assoc :holder client))
            ;; the session's agent continues (docs/turn.md#parked)
            k (when-not (or fresh? clone-of) (task/agent-key db id))
            opts (cond-> {:branch (:branch env) :task id} k (assoc :key k))
            r (if clone-of @(c/clone! sys clone-of opts) @(c/call! sys (c/request 'spawn! nil opts nil)))
            ag (:id r)]
        ;; spawn!'s request, its answer a value: a refusal (a branch another agent holds, named with
        ;; its task) is the attach's answer, never a server defect
        (if-not ag
          r
          (let [replaced (when fresh? (:agent (first (live-run sys db id))))]
            @(c/transact-main! sys (vec (concat (task/execution-tx [id] :running)
                                                (when fresh? [{:seon.task/id id :seon.task/branch (:branch env)}])
                                                (task/start-tx db id (:boot sys) ag env (System/currentTimeMillis)))))
            (when replaced (c/kill! sys replaced))
            {:agent ag :task id})))))))

(defn- fork!
  "A fork of live agent from: a sibling of its task, <name>-fork-<n> (its plan, proofs and kind, on
  a branch of its own), declared on main and attached with the agent's clone (attach!'s clone-of),
  so the fork goes on from the agent's program and branch while the agent goes on on its own. The
  clone has its own key and conversation: an agent's entity on main is its key's, and two live
  agents of one key overwrite each other's facts. Each is judged and merged on its own; root
  cancels the one not wanted, and a refused clone cancels its sibling at once. Answers attach!'s."
  {:malli/schema [:=> [:cat :seon.system/sys :keyword [:maybe :string]] :map]}
  [sys from client]
  (let [db @(:conn sys)
        t (some->> (get-in (c/state sys) [:agents from :task]) (task/task db))]
    (if-not t
      (refused (str from " works no task: nothing to fork"))
      (let [nm (first (for [i (iterate inc 1) :let [nm (str (:seon.task/name t) "-fork-" i)]
                            :when (not (task/task db (str (:seon.task/ns t) "/" nm)))] nm))
            id (str (:seon.task/ns t) "/" nm)
            made @(c/transact-main! sys (task/create-tx db [(assoc (select-keys t [:seon.task/plan :seon.task/proofs :seon.task/kind :seon.task/instructions])
                                                                   :seon.task/ns (symbol (:seon.task/ns t)) :seon.task/name nm :seon.task/branch (task/default-branch id))]))
            a (if (contract/error? made) made (attach! sys id {:clone-of from :client client}))]
        (when (and (not (contract/error? made)) (not (:agent a)))
          @(c/transact-main! sys (task/execution-tx [id] :cancelled)))
        a))))

(defn- work-of
  "The task the live agent ag works, from main: {:task :env :key}, or nil."
  {:malli/schema [:=> [:cat :seon.system/sys :keyword] [:maybe :map]]}
  [sys ag]
  (some (fn [a] (when (= ag (:agent a)) (select-keys a [:task :env :key])))
        (task/running @(:conn sys))))

(defn verdict
  "Task's judgement as main db holds it: its outcome (:done or :merged: the done rule
  had no reason; :closed with its task held: it had some; :parked, :told or :cap: it parked its session,
  waiting for a message) and reason, and, for a held
  task, the failure the judge wrote (its reason names the done rule's reasons) and the evidence."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id] :map]}
  [db task]
  (let [t (d/pull db [:seon.task/outcome :seon.task/reason :seon.task/execution :seon.task/failure :seon.task/evidence] [:seon.task/id task])]
    (cond-> {:task task}
      (:seon.task/outcome t) (assoc :outcome (:seon.task/outcome t))
      (:seon.task/reason t) (assoc :reason (:seon.task/reason t))
      (= :held (:seon.task/execution t)) (assoc :held (select-keys t [:seon.task/failure :seon.task/evidence])))))

(defn verdict-line
  "The done answer (owner: a line and a handle): the task's verdict as one line, its outcome and
  reason, and for each red test its first failing assertion's message, whole, what merged and no commit holds yet (:not-on-disk, seon.reconcile/lost-write-back), with how main differs from the branch when
  the agent said so (main, seon.agent/main-difference's line), and the form that reads the whole
  verdict at root (`verdict`)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id [:maybe :string]] [:map [:line :string] [:handle :string]]]}
  [db task main]
  (let [v (verdict db task)
        fails (:seon.task/failures (d/pull db [:seon.task/failures] [:seon.task/id task]))
        reds (get-in v [:held :seon.task/evidence :red])
        waiting (r/lost-write-back (d/pull db [:seon.task/outcome :seon.task/error] [:seon.task/id task]))]
    {:line (print/text (cond-> (select-keys v [:outcome :reason])
                         (and (:held v) (empty? reds)) (assoc :held (get-in v [:held :seon.task/failure :reason]))
                         (seq reds) (assoc :reds (vec (for [t (take 10 reds)] (if-let [why (or (some-> (get fails t) first :message) (some-> (get fails t) first :type name))] [t why] [t]))))
                         (< 10 (count reds)) (assoc :reds-more (str (- (count reds) 10) " more: the form under :handle reads them"))
                         main (assoc :main main)
                         waiting (assoc :not-on-disk (str (:seon.error/message waiting) ": " (str/join ", " (:seon.git/files waiting)))))
                       ;; the answer that says why a task closed, whole
                       Integer/MAX_VALUE)
     :handle (str "(seon.server/verdict @(:conn sys) " (pr-str task) ")")}))

(def ops
  "The server's ops, the one table, keyed by a request's :op (an EDN request is a
  keyword-keyed map, {:op :agents}; a line opening `{\"` is MCP's, respond): each op's
  description and its arguments (JSON Schema properties; :required, the ones it needs); its
  answer is op-answer's method of its key. MCP lists each as the tool seon_<op> (mcp-reply)."
  (array-map
   :agents {:doc "Live agents (id, branch, status, task); the tasks running or with an agent, newest first (execution, done, blocked-by, its agent and outcome, problem); every other task counted by execution, and the form that lists them all."
            :args {}
            ;; the form that answers it again at root's REPL (seon_eval root): its reply's cuts read through it
            :again '(seon.server/agents-answer sys)}
   :eval {:doc "Evaluate Clojure in an agent's process as one reply, as a model's is: its message-md (optional) and its evals, every one run in order whatever the one before it did (docs/model.md#one-tool-repl); each is recorded on its branch and my.agent/*, my.db/*, my.task/*, my.branch/* and (my.agent/prove) are its API. For an agent you attached the reply is its turn, its message on its call. Or as \"root\" in root's process with plain code (root.repl: `sys` is root, main is @(:conn sys), my.root/* its API; root has no conversation). Answers each eval under its handle (result/e12), which later forms read."
          :args {:agent {:type "string" :description "an agent id (a3) or root"}
                 :message-md {:type "string" :description "This reply's message to whoever started the agent, in Markdown, when it has something to say; a reply of calls alone needs none. An agent's own model drives: empty, since a message to it is seon_message."}
                 :evals {:type "array" :description "The evals this reply runs, in order."
                         :items {:type "object" :properties (array-map :intent {:type "string" :description "What this eval is about to do. A sentence is fine."} :code {:type "string" :description "Clojure to evaluate."})
                                 :required ["intent" "code"]}}
                 :code {:type "string" :description "root only: Clojure forms"}
                 :task {:type "string" :description "the agent's task (ns/name): needed only for an agent another session attached"}}
          :required ["agent"]}
   :context {:doc "The request of the task an agent works as a model is sent it now: system prompt, its conversation and its page. Name the agent, or a task (ns/name): the agent working it, else one attached to it now (attach), so one request reads a task's context. wire: the request body as JSON, as a provider is sent it (roles, call ids, tools; a root with no --profile has no model or max_tokens in it). No model is called."
             :args {:agent {:type "string"} :task {:type "string" :description "ns/name, instead of an agent"}
                    :wire {:type "boolean" :description "answer the request body as JSON, not the text"}}}
   :message {:doc "Send an agent a message (the owner's words): a message on main to the agent (whether or not its process lives), which its next turn delivers onto its branch and reads, and which wakes a session that waits, with its allowance of turns more. The page's message bar sends the same request (seon.system/message!)."
             :args {:agent {:type "string"} :body {:type "string" :description "what to tell the agent"}
                    :task {:type "string" :description "the agent's task (ns/name): needed only for an agent another session attached"}} :required ["agent" "body"]}
   :write_back {:doc "Sync main's changed files to disk and commit them on the merges branch: every merged task whose files no commit holds. Answers the tasks, the paths and the commit, or what it couldn't."
                :args {}}
   :create {:doc "Declare a task, held: nothing starts and nothing is paid. Answers its id, which seon_attach works. Its proofs may be named later."
            :args {:ns {:type "string" :description "the task's namespace"} :name {:type "string" :description "its name"} :plan {:type "string" :description "what to do"}
                   :proofs {:type "array" :items {:type "string"} :description "qualified test names; optional"}}
            :required ["ns" "name" "plan"]}
   :attach {:doc "Work a task as a Seon agent: root starts an agent on the task's branch (model outside) and answers its id and the next step. fresh: reset it, a new branch from main and an empty conversation (the old branch kept). from: fork that agent, a clone of it on a branch of its head working a sibling task."
            :args {:task {:type "string" :description "ns/name"} :fresh {:type "boolean" :description "reset: start the task again from main"}
                   :from {:type "string" :description "fork: an agent id (a3) whose program, live values and branch the new agent goes on from, on a sibling of its task"}}}
   :prove {:doc "Run the tests a branch's change reaches, once per branch and tx however many ask (a session, an agent's turn): the one test service (seon.prove). Names an agent (its branch at its head), else main. Answers the run: its key, the counts, the red tests; a result already held for a test's digest is not run again."
           :args {:agent {:type "string" :description "an agent id whose branch to prove; omit for main"}
                  :names {:type "array" :items {:type "string"} :description "test namespaces to run; omit for every test whose digest holds no green"}
                  :checkout {:type "string" :description "a worktree of this root's repository whose files to prove (with tree), in place of an agent"}
                  :tree {:type "string" :description "the checkout's tree id (seon.fx/tree-id, as bin/build records it): its branch's key"}
                  :mode {:type "string" :description "judge (default for a checkout) or turn"}
                  :stream {:type "boolean" :description "EDN only: each test's verdict as a line as it ends, then the answer"}}}
   :done {:doc "Hand the task the agent works back to root: the agent writes its end (its proofs run, seon.turn/hand-back!), then root judges it, and merges it when the done rule has no reason. Answers the verdict when main holds it, as one line and a handle (seon.server/verdict-line): the task's outcome (done, merged, or closed with its task held) and reason, each red test with its first failure, and how main differs from the branch; the handle is the form that reads it whole at root. A defect of the reconciler that stops the judge, as that defect. It waits as long as the judge does (the run's proofs, a gate, a merge), so a caller's read timeout must allow minutes."
          :args {:agent {:type "string"} :task {:type "string" :description "the agent's task (ns/name): needed only for an agent another session attached"}} :required ["agent"]}))

(defn- held
  "The refusal of request req on agent ag when another client holds it, else nil: its
  :holder (attach!'s client) is not req's :client and req names no :task, or another than the
  agent's. One session's seon_done once named another session's agent and ended its work red."
  {:malli/schema [:=> [:cat :seon.system/sys :keyword :map] [:maybe :map]]}
  [sys ag req]
  (when-let [{:keys [task env]} (work-of sys ag)]
    (let [h (:holder env)]
      (when (and h (not= h (:client req)) (not= task (:task req)))
        (contract/error :seon.server/held #'seon.server/held
                        (str (name ag) " works " task " for client " h ": name its task (task \"" task "\") to act on it")
                        {:agent ag :task task :holder h})))))

(defmulti op-answer
  "A request's answer, by its :op: a function of root's system, the agent the request names
  (:root, an agent's keyword, or nil) and the request, one method per key of ops. One method
  each, so a test's reach takes the methods of the ops it sends and no other
  (seon.program/reach-step)."
  {:malli/schema [:=> [:cat :seon.system/sys [:maybe :keyword] :map] :map]}
  (fn [_ _ req] (:op req)))

(defmethod op-answer :default [_ _ req] (refused (str "unknown op " (:op req))))

(defmethod op-answer :agents [sys _ _]
  (agents-answer sys))

(defn- agent-reply
  "An outside agent's request of agent ag as one reply (seon.turn/outside-reply!): {:responses [...]}, each eval's. The shape every eval of an agent takes, the old :code refused, saying so, with nothing run. See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.system/sys :keyword :map] :map]}
  [sys ag req]
  (let [{:keys [task env]} (work-of sys ag)
        outside? (and task (= "outside" (:model env)))
        msg (:message-md req)]
    (cond
      (and (contains? req :code) (not (contains? req :evals)))
      (refused (str "seon_eval on an agent is one reply now: {agent, message-md, evals: [{intent, code}, ...]}; your code was not run. Only root takes plain code."))
      (and (nil? task) (not (str/blank? msg)))
      (refused (str (name ag) " works no task now (its last has ended): seon_attach task=ns/name works one again, on its branch."))
      (and (not outside?) (not (str/blank? msg)))
      (refused (str (name ag) "'s own model drives it, so this reply is no message of its: send message-md empty, and seon_message to tell it something."))
      :else
      (let [form (list 'seon.turn/outside-reply! task msg (clojure.walk/stringify-keys (:evals req)) (boolean outside?))
            r @(c/submit! sys ag (sedn/encode form) {:origin :context})]
        (cond (contract/error? r) r
              (:ex r) (:ex r)
              :else {:responses (sedn/decode (:seon.print/code (:out r)))})))))

(defmethod op-answer :eval [sys ag req]
  (if (= :root ag)
    (let [r (root-eval sys (:code req))]
      ;; its value as data beside its printed text when it is plain data (seon.contract/plain?: what the wire's EDN carries):
      ;; a reader of the answer reads the value, never the text back (an eval that printed has its :out verbatim in the
      ;; text, which is no EDN since out-verbatim). The model reads the text alone (tools/call)
      (if (contract/error? r) r {:response (cond-> (select-keys r [:handle :text :ex :recorded :main :unrecorded])
                                             (and (contains? r :value) (contract/plain? (:value r))) (assoc :value (:value r)))}))
    (agent-reply sys ag req)))

;; the block's text as the model reads it: printed to the agent's output, not as a
;; value (a value comes back as a string literal, escaped, under the reply's budget);
;; recorded with origin :context, a context read, which renders refer to, not repeat
(defmethod op-answer :context [sys ag req]
  (let [ag (or ag (when-let [t (:task req)]
                    (or (:agent (first (live-run sys @(:conn sys) t)))
                        (:agent (attach! sys t {:client (:client req)})))))]
    (if-let [{:keys [task env key]} (when ag (work-of sys ag))]
      (let [a (get-in (c/state sys) [:agents ag])]
        (if (= :busy (:status a))
          ;; its one REPL is in an eval, which may never end (a test blocked on nothing): a read waits on no one, so root reads its branch
          (let [db (:db @(c/branch-db sys (:branch a)))
                basis {:commit (d/commit-id db) :task task :turn (inc (or (:seon.agent/turn (d/pull db [:seon.agent/turn] [:seon.agent/key key])) 0)) :max-turns 1 :origin :outside :agent key}
                {:keys [messages]} (wire/render db basis)]
            {:agent ag :task task :context (str (name ag) " is still running an eval" (when-let [t (:form (:last-op a))] (str ", at " t)) ": this is its branch as last committed, and nothing was taken.\n\n"
                           (wire/read-text db basis messages (true? (:wire req))))})
          ;; an outside model's read is its turn, and takes its messages; anyone
          ;; else's read of an agent its model drives only looks
          (let [r @(c/submit! sys ag (sedn/encode (list 'print (list 'seon.turn/context-block task (= "outside" (:model env)) (true? (:wire req))))) {:origin :context})]
            (cond (contract/error? r) r (:ex r) (:ex r) :else {:agent ag :task task :context (:seon.print/code (:out r))}))))
      (refused (if ag (str ag " works no task: attach one first") (str "no task " (:task req) ", and no agent named"))))))

(defmethod op-answer :message [sys ag req]
  @(c/message! sys ag (:body req)))

(defmethod op-answer :write_back [sys _ _] (root/write-back! sys))

(defmethod op-answer :create [sys _ {:keys [ns name plan proofs]}]
  (let [decl (cond-> {:seon.task/ns (symbol ns) :seon.task/name name :seon.task/plan plan :seon.task/kind :whole}
               (seq proofs) (assoc :seon.task/proofs (set (map symbol proofs))))
        tx (task/create-tx @(:conn sys) [decl])
        r (if (contract/error? tx) tx @(c/transact-main! sys tx))]
    (if (contract/error? r) r {:task (task/task-id (symbol ns) name)})))

(defmethod op-answer :attach [sys _ req]
  (let [a (cond (:from req) (fork! sys (keyword (:from req)) (:client req))
                (:task req) (attach! sys (:task req) {:fresh? (true? (:fresh req)) :client (:client req)})
                :else (refused "seon_attach needs task=ns/name, the task to work (or from=an-agent-id, to fork one)"))]
    (cond-> a
      (and (:agent a) (not (contract/error? a))) (assoc :next (str "seon_context with agent " (name (:agent a)) " reads what its model reads (wire true: the request body). "
                                   "seon_eval with it, message-md and evals (each an intent and code), is one reply, a model's turn; (my.agent/prove) there runs its proofs. seon_done hands it back.")))))

(def ^:dynamic *emit*
  "A line out before the answer, on the connection answering this request (serve-connection!): an EDN request's
  :prove with :stream writes each test's verdict through it as it ends. nil for MCP's JSON-RPC, which answers once.
  See docs/server.md#ops."
  nil)

(defn- prove-streamed
  "run's answer (a function answering a run's answer), and before it each :prove-verdict event of root's whose run key is
  of branch's runs (seon.prove/run-key: \"<branch>@<digest>...\"), written through *emit* as it comes: the events tapped
  before run starts (on a fiber of its own), let go after. The answer's :verdicts (seon.prove's answer-asker: every
  verdict the run was told) are what the client is owed: one the events did not bring before the answer (another path
  than the answer's, and a sliding tap) is written then, so none is lost and none written twice. See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.system/sys :keyword ifn?] :any]}
  [sys branch run]
  (let [ev (c/events sys)
        done (a/chan 1)
        prefix (str (name branch) "@")
        line #(select-keys % [:type :key :test :digest :status :ms :failures :held :load :max-load :queued])
        id (juxt :test :digest :kind)
        owed (fn [written answer]
               (doseq [w (:verdicts answer) :when (not (written (id w)))]
                 (*emit* (line (assoc w :type :prove-verdict :key (:key answer)))))
               answer)]
    ;; a throw in run is the stream's answer, never a reader left waiting on done: a spawned fiber keeps its throw for a
    ;; joiner, and nobody joins this one (jolt.fibers/monitor!; the callback runs on the fiber's carrier, so it puts, never blocks)
    (fibers/monitor! (fibers/spawn #(a/>!! done (run)))
                     (fn [err] (when err (a/put! done (contract/error :seon.server/defect #'seon.server/prove-streamed
                                                                       (str "the run threw: " (or (ex-message err) (str err))) {:line "prove"})))))
    (try (loop [written #{}]
           (let [[v ch] (a/alts!! [done ev])]
             (cond (= ch done) (owed written v)
                   ;; root's events ended (it stopped): only the answer is left to wait on
                   (nil? v) (owed written (a/<!! done))
                   :else (if (and (#{:prove-verdict :prove-held} (:type v)) (str/starts-with? (str (:key v)) prefix))
                           (do (*emit* (line v)) (recur (cond-> written (= :prove-verdict (:type v)) (conj (id v)))))
                           (recur written)))))
         (finally (a/untap (:events-mult sys) ev)))))

(defn ask-streamed
  "One EDN request m to the server on 127.0.0.1:port, a client of serve-connection!: each line before the answer (an event
  *emit* wrote, a map carrying :type) handed to on-event as it comes, then the answer, the first line that is no event. The
  wait ends at the connection's end: refused, closed before the answer or reset is :seon.server/unanswered (:why :refused,
  :eof or :reset), never a hang and never a timer. See docs/merging.md#a-session-proves-through-root."
  {:malli/schema [:=> [:cat :int :map ifn?] :any]}
  [port m on-event]
  (let [unanswered #(contract/error :seon.server/unanswered #'seon.server/ask-streamed
                                    (str "root on port " port (case % :refused " refused the connection: no root listens there"
                                                                     ;; the system ended under the ask: a landing's restart (bin/update) is the
                                                                     ;; usual one, and a red the asker could read as a test's says so
                                                                     :eof " closed the connection before its answer: the system ended or restarted (a landing restarts it); ask again, the verdicts that landed are kept"
                                                                     :reset " reset the connection before its answer: the system ended or restarted (a landing restarts it); ask again, the verdicts that landed are kept"))
                                    {:port port :why %})]
    ;; the edge where a client's socket failures become the one typed error (docs/failure-points.md)
    (try
      (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
        (let [out (.getOutputStream s)
              r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s) "UTF-8"))]
          (.write out (.getBytes (str (sedn/encode m) "\n") "UTF-8"))
          (.flush out)
          (loop []
            (if-let [line (.readLine r)]
              (let [v (edn/read-string {:default tagged-literal} line)]
                (if (and (map? v) (contains? v :type)) (do (on-event v) (recur)) v))
              (unanswered :eof)))))
      (catch java.net.ConnectException _ (unanswered :refused))
      (catch java.io.IOException _ (unanswered :reset)))))

(defmethod op-answer :prove [sys ag req]
  (let [run (fn [] (c/prove-tree! sys (cond-> (select-keys req [:checkout :tree :names]) (:mode req) (assoc :mode (keyword (name (:mode req)))))))
        ;; a checkout's files, on their tree's branch (seon.system/prove-tree!), its verdicts streamed when asked; else an agent's branch, or main
        r (cond (and (:checkout req) (:stream req) *emit*) (prove-streamed sys (c/tree-branch (:tree req)) run)
                (:checkout req) (run)
                :else @(c/prove! sys (if ag (or (c/agent-ref sys ag) {}) {:branch :main}) (set (map symbol (:names req)))))]
    ;; each red named with its first failing assertion (message, expected, actual), and the
    ;; process tests left to the judge with why (seon.agent/run-tests-forked's :deferred :why)
    (if (contract/error? r)
      r
      (cond-> (select-keys r [:key :ran :pass :fail :error :red :reused :ms])
        (seq (:red r)) (assoc :failures (into (sorted-map) (for [[t fs] (proofs/failures r)] [t (first fs)])))
        (seq (:deferred r)) (assoc :deferred (:deferred r) :why (:why r))
        (seq (:baked r)) (assoc :baked (:baked r))))))

(defn- done-settled?
  "Whether seon_done may answer on db: task's own work is judged, so its outcome has left :running and :ready (judged green,
  waiting for its merge, minutes at most), or a root defect stopped the judge (defect, a fn of db). At :gate the judge has
  decided the task's own proofs and the merge gate runs the process tests its change reaches, which can take as long as the
  run (242 items, ~30 min on the standing system, longer than an MCP client waits): done answers there, saying so, and the
  gate's verdict reaches the agent's page and the task's outcome. A gate with no process test to run (:seon.task/deferred
  empty: only the candidate is analyzed) is its merge, minutes at most, so done waits through it as through :ready.
  See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id ifn?] :boolean]}
  [db task defect]
  (let [{o :seon.task/outcome ts :seon.task/deferred} (d/pull db [:seon.task/outcome :seon.task/deferred] [:seon.task/id task])]
    (boolean (or (not (or (#{:running :ready} o) (and (= :gate o) (empty? ts)))) (defect db)))))

(defmethod op-answer :done [sys ag req]
  (if-let [{:keys [task env]} (work-of sys ag)]
    ;; the end first, as a turn loop's end is written, so the judge reads it
    (let [r @(c/submit! sys ag (sedn/encode (list 'seon.turn/hand-back! task)) {:origin :outside})]
      ;; judged only on the end written: an error or no answer is answered as it is,
      ;; and the task stays the agent's to hand back again
      (cond (contract/error? r) r
            (:ex r) (:ex r)
            (:text r) (let [t (:max-tx @(:conn sys))
                            outcome #(:seon.task/outcome (d/pull % [:seon.task/outcome] [:seon.task/id task]))
                            ;; judged and past its merge: the task left :running and the gate (the reconciler merged, parked,
                            ;; held or refused it), or a root defect stopped the judge
                            _ (c/judge! sys {:task task :env env :agent ag})
                            db (c/await-db sys (str "the judge of " task)
                                          #(done-settled? % task (fn [db] (c/defect db t "seon.reconcile"))))]
                        ;; a judge that has not moved by root's quiet never will: that, or root
                        ;; stopped, is the answer
                        (cond (contract/error? db) (assoc db :task task)
                              ;; green, behind main alone (seon.reconcile's judgment, bounded by max-behind): handed back again,
                              ;; which takes main first, as a model agent's turn loop does when it is told so
                              (= :behind (outcome db)) (op-answer sys ag req)
                              :else (if-let [p (and (= :running (outcome db)) (c/defect db t "seon.reconcile"))]
                                      {:task task :defect (select-keys p [:seon.error/type :seon.error/message])}
                                      (let [files (fx/uncommitted (str (cfg/checkout)))]
                                        (cond-> (verdict-line db task (:main r))
                                          ;; judged; its merge gate runs on: the verdict comes later, where the agent reads
                                          (= :gate (outcome db))
                                          (assoc :next (str "the merge gate runs " (count (:seon.task/deferred (d/pull db [:seon.task/deferred] [:seon.task/id task])))
                                                            " process test(s) the change reaches; their verdict reaches your page (seon_context) and the task's outcome"))
                                          (and (seq files) (not (contract/error? files))) (assoc :checkout-not-merged files))))))
            :else (refused (str ag " answered no end for " task))))
    (refused (str ag " works no task"))))

(def ^:private held-ops
  "The ops that act on an agent's work, which its holder's client (or a request naming its task) may ask."
  #{:done :eval :message})

(defn answer
  "One request's answer: its op's (op-answer), or held's refusal for an op on an agent another client holds."
  {:malli/schema [:=> [:cat :seon.system/sys :map] :map]}
  [sys req]
  (let [ag (some-> (:agent req) (#(if (= "root" %) :root (keyword %))))
        missing (remove #(contains? req (keyword %)) (:required (get ops (:op req))))]
    (or (when (seq missing) (refused (str "seon_" (name (:op req)) " needs " (str/join ", " missing) ": " (str/join "; " (for [m missing] (str m " is " (get-in ops [(:op req) :args (keyword m) :description] "required")))))))
        (when (and (held-ops (:op req)) (keyword? ag) (not= :root ag)) (held sys ag req))
        (op-answer sys ag req))))

(def protocol-versions
  "The MCP protocol versions this server answers (modelcontextprotocol.io/specification):
  initialize, tools/list and tools/call with text content are the same in each. The client's
  version when it is one of these, else the newest, as the spec's version negotiation says."
  ["2024-11-05" "2025-03-26" "2025-06-18"])

(defn- rpc-error
  "A JSON-RPC 2.0 error answer to id: code (the spec's: -32700 parse, -32601 no such method,
  -32602 bad params, -32603 internal) and message."
  {:malli/schema [:=> [:cat :any :int :string] :map]}
  [id code message]
  {"jsonrpc" "2.0" "id" id "error" {"code" code "message" message}})

(defn- eval-text
  "An eval's response r as a tool's text: what it printed, how main differs from the branch (:main, said only to an outside agent:
  seon.agent/main-difference), and that its record was refused. See docs/server.md#mcp."
  {:malli/schema [:=> [:cat :map] :string]}
  [r] (str (:text r) (when-let [m (:main r)] (str "\n:main " m)) (when-let [u (:unrecorded r)] (str "\n" (:seon.error/message u)))))

(defn mcp-reply
  "The JSON-RPC answer to one MCP message, or nil for a notification: initialize; ping;
  tools/list, each op the tool seon_<op>; tools/call, the op's answer as the tool's text
  (seon_context's, the block's text itself: the model's own view, not an escaped string
  inside a map). A method it does not know, or a tool no op names, is the JSON-RPC error
  saying so."
  {:malli/schema [:=> [:cat [:maybe :seon.system/sys] :map] [:maybe :map]]}
  [sys {:strs [id method params] :as msg}]
  (let [result (fn [r] {"jsonrpc" "2.0" "id" id "result" r})]
    (when id
      (case method
        "initialize" (result {"protocolVersion" (or (some #{(get params "protocolVersion")} protocol-versions) (peek protocol-versions))
                              "capabilities" {"tools" {}} "serverInfo" {"name" "seon" "version" "next"}})
        "ping" (result {})
        "tools/list" (result {"tools" (vec (for [[op v] ops]
                                             {"name" (str "seon_" (name op)) "description" (:doc v)
                                              "inputSchema" (cond-> {:type "object" :properties (:args v)} (:required v) (assoc :required (:required v)))}))})
        "tools/call" (let [n (str (get params "name"))
                           op (when (str/starts-with? n "seon_") (keyword (subs n 5)))]
                       (if-not (ops op)
                         (rpc-error id -32602 (str "no tool " n ": the tools are " (str/join ", " (map #(str "seon_" (name %)) (keys ops)))))
                         (let [a (answer sys (cond-> (into {:op op} (for [[k v] (get params "arguments")] [(keyword k) v]))
                                                 ;; who asks: bin/mcp's id for its session (held)
                                                 (get msg "seon/client") (assoc :client (get msg "seon/client"))))]
                           ;; what the model reads: the block itself, an eval's response as printed
                           ;; under its handle, an error by the printer, an op's answer that root's
                           ;; REPL answers again (:again) under the reply budget with that form as its
                           ;; handle, anything else whole by the printer (nothing could re-ask it)
                           (result {"content" [{"type" "text" "text" (cond (and (= :context op) (:context a)) (:context a)
                                                                           (:response a) (eval-text (:response a))
                                                                           (:responses a) (str/join "\n\n" (map eval-text (:responses a)))
                                                                           (contract/error? a) (print/text a)
                                                                           (:again (ops op)) (let [p (print/print-value a {:handle (:again (ops op))})] (or (:text p) (:seon.error/message p)))
                                                                           :else (print/text (vary-meta a assoc :seon.print/full true)))}]}))))
        (rpc-error id -32601 (str "no method " method ": this server answers initialize, ping, tools/list and tools/call"))))))

(defn- respond
  "The answer line to one request line, or nil (an MCP notification). The protocol rule: a
  line opening `{\"` is MCP's JSON-RPC (mcp-reply, JSON out), every other line is EDN (answer,
  EDN out), so an EDN request is a keyword-keyed map. The entrant boundary: a defect (a line
  that does not read, an op that throws) answers that request as the typed
  :seon.server/defect, for MCP inside a JSON-RPC error with the request's id (-32700 for
  a line that is not JSON, naming the rule)."
  {:malli/schema [:=> [:cat [:maybe :seon.system/sys] :string] [:maybe :string]]}
  [sys line]
  (let [mcp? (str/starts-with? (str/triml line) "{\"")
        msg (volatile! nil)]
    (try (if mcp?
           (some-> (mcp-reply sys (vreset! msg (json/read-str line))) json/write-str)
           (sedn/encode (answer sys (edn/read-string line))))
         (catch Throwable e
           (let [cause (step/error-msg e)
                 d (contract/error :seon.server/defect #'seon.server/respond
                                   (if (and mcp? (nil? @msg)) (str "a line opening {\" is MCP's JSON-RPC, and an EDN request a keyword-keyed map; this one is not JSON: " cause) cause)
                                   (cond-> {:line line} (ex-data e) (assoc :data (ex-data e))))]
             (if mcp?
               (json/write-str (rpc-error (get @msg "id") (if @msg -32603 -32700) (print/text d)))
               (sedn/encode d)))))))

(defn- serve-connection!
  "Answer one connection's request lines (respond), line by line, until it closes; a
  notification is answered nothing. Every other connection is its own thread."
  {:malli/schema [:=> [:cat :seon.system/sys :any] :any]}
  [sys sock]
  (let [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
        out (.getOutputStream sock)]
    (loop []
      (when-let [line (.readLine r)]
        (when-let [a (binding [*emit* (when-not (str/starts-with? (str/triml line) "{\"")
                                        (fn [m] (.write out (.getBytes (str (sedn/encode m) "\n") "UTF-8")) (.flush out)))]
                       (respond sys line))]
          (.write out (.getBytes (str a "\n") "UTF-8"))
          (.flush out))
        (recur)))
    (.close sock)))

(defn serve!
  "Listen on 127.0.0.1:port (0: a free port the kernel picks; read it with .getLocalPort):
  the accept loop and each connection on edge threads (blocking accept and reads). Answers
  the server socket."
  {:malli/schema [:=> [:cat :seon.system/sys nat-int?] :any]}
  [sys port]
  (let [ss (java.net.ServerSocket. port 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (doto (Thread. ^Runnable (fn [] (loop [] (let [s (.accept ss)]
                                                (doto (Thread. ^Runnable (fn [] (serve-connection! sys s)) "seon-server-conn")
                                                  (.setDaemon true) (.start))
                                                (recur))))
                   "seon-server-accept")
      (.setDaemon true) (.start))
    ss))
