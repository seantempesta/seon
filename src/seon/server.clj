(ns seon.server
  "Root's MCP and request server for outside agents (Claude Code through MCP, a person): a
  socket on 127.0.0.1, one request per line in, one answer per line out (R1's
  proto.agents.entrance, on the flow core). A line is line EDN ({:op k ...}), or MCP's
  JSON-RPC when it opens `{\"` (mcp-reply; bin/mcp relays a client's stdio here). An
  outside agent evaluates in any running agent's process (recorded on that agent's branch,
  origin :outside) or as root (in root's process, with root's functions, through the same
  REPL path, recorded on main), and can work a task as a Seon agent. Its ops are one table
  (`ops`: {:op k ...args}), which MCP lists as its tools.

  The listener and each connection are fibers parked on their sockets, and each op runs on a
  :server-op worker (perform-op); a connection is an outside caller of root's API, as bin/check is. Try it:
  printf '{:op :agents}\\n' | nc -w 5 127.0.0.1 PORT
  printf '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\\n' | nc -w 5 127.0.0.1 PORT"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.step :as step] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.system :as c] [my.root :as root] [seon.fx :as fx] [seon.config :as cfg] [seon.task :as task] [seon.template :as tpl] [seon.proofs :as proofs] [seon.error :as err] [seon.reconcile :as r] [datahike.api :as d]
            [seon.lib :as lib] [seon.edn :as sedn] [seon.db :as pdb] [seon.repl :as repl] [seon.intr :as intr] [seon.print :as print] [seon.ctx.wire :as wire] [clojure.edn :as edn] [clojure.data.json :as json] [clojure.string :as str] [clojure.walk] [clojure.core.async :as a] [jolt.fibers :as fibers]))

(defn- refused
  "The server refusing a request, as root's API does: the one error map."
  {:malli/schema [:=> [:cat :string] :seon.system/refused]}
  [why] (contract/error :seon.system/refused #'seon.server/refused why))

(defn- stop-ops!
  "Stop the server ops whose stop key holds each entry of k ({:client c}, and :id, one MCP request): one message to the
  :server-op owner on its :ask port (seon.fx/stop), answered at once, never behind the ops it stops. Answers
  {:stopped n :dropped n}, or the owner's refusal. See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.system/sys :seon.fx/stop] :map]}
  [sys k]
  (let [o @(c/effect! sys :server-op {:seon.fx/stop k})] (if (contract/error? o) o (:value o))))

(def ^:dynamic *token*
  "The interrupt token the :server-op owner made for the op this worker runs (seon.fx/stop): answer runs the op under it."
  nil)

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
         :agents (into (sorted-map) (for [[id a] (:agents (c/state sys)) :when (not (seon.task/gone-statuses (:status a)))]
                                      [id (select-keys a [:branch :status :task])]))))

(def ^:dynamic *sys*
  "This root while its REPL evaluates a form: the `sys` each form reads (root-eval)."
  nil)

(defn- root-eval
  "Evaluate code as root through the one REPL path (seon.repl/eval-string: the printer,
  cost, error maps, *1 and *e), in root.repl (my.root as root, seon.system as c, datahike as
  d) with `sys` a local bound to this root, unbounded (root's own forms take no tick or allocation
  budget: a whole-program load allocates tens of GB, and the agent's per-form
  budget stopped it; the one op answer runs with no :server-op bound for that reason). What ends one that runs too long is an
  interrupt: root's SIGUSR1 (seon.intr), or end-all at root's stop; its response recorded on main as root's eval, by the user (seon.repl/record-tx). The record's write is
  sent and not waited on: nothing reads it before the answer, and root's writer keeps its order before any later write
  (it cost 6-30 ms of every root eval's 45-54 ms). Its *in* is input, the request's own (empty when none). See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.system/sys :string [:maybe :string]] :seon.repl/answer]}
  [sys code input]
  (when-not (find-ns 'root.repl)
    (binding [*ns* (create-ns 'root.repl)] (refer-clojure) (require '[my.root :as root] '[seon.system :as c] '[datahike.api :as d])))
  ;; *in* is this request's own input (empty unless it sent some), never the process's stdin: a read of stdin answers EOF at once,
  ;; where it once held its connection 30 min (docs/server.md#ops)
  (let [r (binding [*sys* sys *in* (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. (or input "")))]
            (repl/eval-string code {"ns" "root.repl" :origin :outside :ticks 0 :bytes 0
                                    :repl/eval-fn (fn [form] (eval (list 'let '[sys seon.server/*sys*] form)))}))]
    (c/transact-main! sys (repl/record-tx {:seon.system/id :root} (str "root-outside-" (profile/now-ns)) (task/sender (cfg/value :seon.config.root/user-name))
                                          (repl/record-details code r {})))
    ;; its value too: the :eval answer passes it on when it is plain data (op-answer :eval)
    r))

(defn- live-run
  "Task id's running or parked agent whose process lives in root's view now (a parked one idles at its turn boundary), as [work nil], or [nil dead]
  with dead its running agent that is gone (died, ended by seon_done, killed for memory): a
  death is a fact root's view reads, so a dead agent never answers as the one to work the
  task (attach! starts a fresh agent on the same branch). [nil nil] when none runs."
  {:malli/schema [:=> [:cat :seon.system/sys :seon.host/db :seon.task/id] [:tuple [:maybe :map] [:maybe :map]]]}
  [sys db id]
  (if-let [r (some #(when (= id (:task %)) %) (concat (task/running db) (task/running db :parked)))]
    (let [a (get-in (c/state sys) [:agents (:agent r)])]
      (if (and a (not (seon.task/gone-statuses (:status a)))) [r nil] [nil r]))
    [nil nil]))

(defn- template-task!
  "Task id made from template (a name of seon.template/templates) on main, parked, as root's own session is made
  (seon.system/root-agent-tx): a session's first seon_attach of session/<area> template=user makes its own agent's task, so the
  reconciler starts nothing for it until attach! starts it. Answers nil, or the refusal. See docs/users.md#messages."
  {:malli/schema [:=> [:cat :seon.system/sys :seon.task/id :string] [:maybe :map]]}
  [sys id template]
  (let [db @(:conn sys) tp (tpl/templates (keyword template)) s (symbol id)]
    (cond (not tp) (refused (str "no template " template ": the templates are " (str/join ", " (map name (keys tpl/templates)))))
          (not (namespace s)) (refused (str "a task id is ns/name: " id))
          :else (let [tx (tpl/request-tx db (tpl/from tp {:seon.task/ns (symbol (namespace s)) :seon.task/name (name s)
                                                          :seon.task/plan (str "Made at its first attach from the " template " template.")
                                                          ;; a session drives it: no model ever works it (seon.reconcile/actions starts no outside task)
                                                          :seon.task/model "outside"}))]
                  (if (contract/error? tx)
                    tx
                    (let [r @(c/transact-main! sys (conj (vec tx) {:seon.task/id id :seon.task/outcome :parked}))]
                      (when (contract/error? r) r)))))))

(defn- run-begun
  "answer, an attach's, once agent's run start (:seon.agent/run-ms, written on main: root's alone) is on its branch too, where its request
  is drawn: the agent merges main (seon.system/merge-main!, the one merge), as a model agent's turn does when main moved. A merge that
  fails is the answer's :merge-main, its error as data. See docs/turn.md#the-turn-is-a-number."
  {:malli/schema [:=> [:cat :seon.system/sys :seon.agent/id :map] :map]}
  [sys agent answer]
  (let [r @(c/merge-main! sys agent)]
    (cond-> answer (contract/error? r) (assoc :merge-main r))))

(defn- attach!
  "Start an outside agent on task id: its env's model is \"outside\" (the task keeps its own), so the
  reconciler starts no turn loop for it and does not kill it. Its env, the release and its start are written
  on main in one transaction BEFORE the spawn, with the agent's key made here and the agent :spawning, so its branch is cut holding
  its task (root's alone) and the reconciler, which counts a :spawning agent live, spawns no second one; a refused spawn answers that
  start (the task refused, its execution as it was, the agent ended). A clone's start follows its spawn (its branch is cut from its source's). The session's agent continues (its key) unless fresh?:
  a reset, the task's branch a new one cut from main and its conversation empty (the agent on the
  old branch is killed here once the task is the new one's: the reconciler compares the task's own agent, so it never
  saw the old one; that branch and its records stay). A task
  whose agent is live answers that agent, :shared true: the caller has the branch, with
  whoever attached it, and that one's seon_done ends it for both. An agent that is gone (live-run)
  is replaced by a fresh one on its branch, the session's key continuing. A branch another live agent holds
  is spawn!'s refusal, answered as a value, naming the agent and the task it works.
  clone-of: the agent is that live agent's clone (seon.system/clone!: live values and all, its branch
  cut from the clone's once its writes commit), its own key and conversation (a fork's). client:
  who attached it (bin/mcp's id for its session), the agent's :holder: that client's done and eval act on it
  (own-agent), and a client attaching a live agent takes its hold over. template: a task id that does not exist is made from that template first
  (template-task!): a session's own agent, session/<area> from :user.
  Answers {:agent :task}."
  {:malli/schema [:function [:=> [:cat :seon.system/sys :seon.task/id] :map]
                  [:=> [:cat :seon.system/sys :seon.task/id [:map [:fresh? {:optional true} :boolean] [:clone-of {:optional true} :keyword] [:client {:optional true} [:maybe :string]]
                                                  [:template {:optional true} [:maybe :string]]]] :map]]}
  ([sys id] (attach! sys id {}))
  ([sys id {:keys [fresh? clone-of client template]}]
  (let [;; a task that does not exist is made from template, when one is named (a session's own: template-task!)
        made (when (and template (not (task/task @(:conn sys) id))) (template-task! sys id template))
        db @(:conn sys)
        t (task/task db id)
        [live dead] (if (or fresh? clone-of) [nil nil] (live-run sys db id))]
    (cond
      made made
      (not t) (refused (str "no task " id))
      ;; its agent is live: it is the one to work it (a second spawn on its branch is refused)
      live (do
             ;; the client attaching it takes its hold over (root, 2026-11-16): its done and eval act on the agent it last attached (own-agent),
             ;; and the client that held it before acts on it no more. An attach begins a run (root, 2026-11-16: a session's re-attach is a
             ;; new run), so its request holds only what is linked from now (seon.ctx.wire/turn-entities)
             @(c/transact-main! sys (cond-> [[:db/add [:seon.agent/key (:key live)] :seon.agent/run-ms (System/currentTimeMillis)]]
                                      (and client (not= client (get-in live [:env :holder])))
                                      (conj [:db/add [:seon.agent/key (:key live)] :seon.agent/holder client])))
             (run-begun sys (:agent live) {:agent (:agent live) :task id :shared true}))
      :else
      (let [restart (when fresh? (task/restart-tx db id))
            env (cond-> (assoc (r/desired-env t) :model "outside")
                  restart (assoc :branch (:branch restart))
                  client (assoc :holder client))
            ;; the session's agent continues (docs/turn.md#parked); a new one's key is made here, so its start is main's before its branch is cut
            k (when-not clone-of (or (when-not fresh? (task/agent-key db id)) (lib/unique-id)))
            opts (cond-> {:branch (:branch env) :task id} k (assoc :key k))
            replaced (when fresh? (:agent (first (live-run sys db id))))
            ;; read after the restart's retractions: its outcome swap starts from none
            start (fn [ag] (vec (concat (task/execution-tx [id] :running) (:tx restart)
                                        (task/start-tx (cond-> db restart (-> (pdb/with (:tx restart)) :db-after)) id (:boot sys) ag env (System/currentTimeMillis)))))
            ;; the start (its outside env, so the reconciler starts no loop; :spawning, so it counts the agent live and spawns none of its own) is
            ;; written on main BEFORE the spawn: the branch is cut holding its agent's task, root's alone (:seon.agent/task, docs/users.md), so the
            ;; agent never copies it (two writers disagreed at a whole task's stage move). A clone's branch is cut from its source's: it starts after
            ;; an attach begins a run (start-tx writes one only for a task begun): its request holds what is linked from now
            started (when k @(c/transact-main! sys (conj (start (keyword k)) {:seon.agent/key k :seon.agent/status :spawning :seon.agent/run-ms (System/currentTimeMillis)})))
            r (if clone-of @(c/clone! sys clone-of opts) @(c/call! sys (c/request 'spawn! nil opts nil)))
            ag (:id r)]
        ;; spawn!'s request, its answer a value: a refusal (a branch another agent holds, named with
        ;; its task) is the attach's answer, never a server defect; the start written for it is undone exactly (each fact its transaction
        ;; wrote on the task and the agent inverted, one rule for every attribute: the session's key, a restart's branch, the release), then the
        ;; refusal written and the agent that never ran ended
        (if-not ag
          (do (when (:t started)
                (let [db @(:conn sys) h (d/history db)
                      undo (vec (for [e (remove nil? [(pdb/eid db :seon.task/id id) (pdb/eid db :seon.agent/key k)])
                                      dt (d/datoms h :eavt e) :when (= (:t started) (:tx dt))]
                                  [(if (:added dt) :db/retract :db/add) (:e dt) (:a dt) (:v dt)]))]
                  ;; one transaction: the refusal read on the task as the undo leaves it
                  @(c/transact-main! sys (into undo (conj (task/refused-tx (:db-after (pdb/with db undo)) id r)
                                                          {:seon.agent/key k :seon.agent/status :ended :seon.agent/ended-ms (System/currentTimeMillis)})))))
              r)
          (do (when clone-of @(c/transact-main! sys (start ag)))
              (when replaced (c/kill! sys replaced))
              (cond->> {:agent ag :task id} (not clone-of) (run-begun sys ag)))))))))

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
  description and its arguments (JSON Schema properties; :required, the ones it needs; :inline, an op that runs on its connection's fiber,
  never a :server-op worker: a wait on our own system's event, or a stop: inline-ops); its
  answer is op-answer's method of its key. MCP lists each as the tool seon_<op> (mcp-reply)."
  (array-map
   :agents {:doc "Live agents (id, branch, status, task); the tasks running or with an agent, newest first (execution, done, blocked-by, its agent and outcome, problem); every other task counted by execution, and the form that lists them all."
            :args {}
            ;; the form that answers it again at root's REPL (seon_eval root): its reply's cuts read through it
            :again '(seon.server/agents-answer sys)}
   :eval {:doc "Evaluate Clojure in an agent's process as one reply, as a model's is: its message-md (optional) and its evals, every one run in order whatever the one before it did (docs/model.md#one-tool-repl); each is recorded on its branch and my.agent/*, my.db/*, my.task/*, my.branch/* and (my.agent/prove) are its API. For an agent you attached the reply is its turn, its message on its call. Or as \"root\" in root's process with plain code (root.repl: `sys` is root, main is @(:conn sys), my.root/* its API; root has no conversation). Answers each eval under its handle (result/e12), which later forms read."
          :args {:agent {:type "string" :description "root, or an agent: a caller with a client (bin/mcp) evaluates in the agent it last attached, one with none in the agent it names"}
                 :message-md {:type "string" :description "This reply's message to whoever started the agent, in Markdown, when it has something to say; a reply of calls alone needs none. An agent's own model drives: empty, since a message to it is seon_message."}
                 :evals {:type "array" :description "The evals this reply runs, in order."
                         :items {:type "object" :properties (array-map :intent {:type "string" :description "What this eval is about to do. A sentence is fine."} :code {:type "string" :description "Clojure to evaluate."})
                                 :required ["intent" "code"]}}
                 :code {:type "string" :description "Clojure forms: root's, or on an agent the one eval of this reply (with intent), as evals [{intent, code}] would send it"}
                 :intent {:type "string" :description "with code on an agent: what that eval is about to do"}
                 :input {:type "string" :description "root only: what the forms read as *in* (stdin), empty when none is sent"}
                 :task {:type "string" :description "the agent's task (ns/name): needed only for an agent another session attached"}}
          :required ["agent"]}
   :interrupt {:inline true
               :doc "Stop every op you asked that still runs or waits (your seon_eval at root included, which has no work bound): a queued one is answered stopped, a running one interrupted and answered :seon.server/interrupted. Answered at once, never behind the ops it stops. An MCP client's notifications/cancelled stops the one request it names the same way."
               :args {}}
   :import {:doc "Import a file you wrote onto the Seon agent your checkout holds (my.branch/import!): what bin/seon-import, the edit hook, asks. path: the file's absolute path, inside the checkout your client names (bin/mcp call's checkout-<path>)."
            :args {:path {:type "string" :description "the file's absolute path"}} :required ["path"]}
   :context {:doc "The request of the task an agent works as a model is sent it now: system prompt, its conversation and its page. Name the agent, or a task (ns/name): the agent working it, else one attached to it now (attach), so one request reads a task's context. wire: the request body as JSON, as a provider is sent it (roles, call ids, tools; a root with no --profile has no model or max_tokens in it). No model is called."
             :args {:agent {:type "string"} :task {:type "string" :description "ns/name, instead of an agent"}
                    :wire {:type "boolean" :description "answer the request body as JSON, not the text"}}}
   :message {:doc "Send an agent a message (the owner's words): a message on main to the agent (whether or not its process lives), which its next turn delivers onto its branch and reads, and which wakes a session that waits, with its allowance of turns more. Name the agent, or its task (a session's agent: its task session/<area>). Any client may send one: a message acts on nothing, it is read at the agent's next turn. The page's message bar sends the same request (seon.system/message!)."
             :args {:agent {:type "string" :description "an agent id"} :body {:type "string" :description "what to tell the agent"}
                    :task {:type "string" :description "ns/name: its agent, instead of agent"}} :required ["body"]}
   :write_back {:doc "Sync main's changed files to disk and commit them on the merges branch: every merged task whose files no commit holds. Answers the tasks, the paths and the commit, or what it couldn't."
                :args {}}
   :create {:doc "Declare a task, held: nothing starts and nothing is paid. Answers its id, which seon_attach works. Its proofs may be named later."
            :args {:ns {:type "string" :description "the task's namespace"} :name {:type "string" :description "its name"} :plan {:type "string" :description "what to do"}
                   :proofs {:type "array" :items {:type "string"} :description "qualified test names; optional"}}
            :required ["ns" "name" "plan"]}
   :attach {:doc "Work a task as a Seon agent: root starts an agent on the task's branch (model outside) and answers its id and the next step. fresh: reset it, a new branch from main and an empty conversation (the old branch kept). from: fork that agent, a clone of it on a branch of its head working a sibling task. template: make a task that does not exist from that template first (a session's own agent: task=session/<area> template=user)."
            :args {:task {:type "string" :description "ns/name"} :fresh {:type "boolean" :description "reset: start the task again from main"}
                   :template {:type "string" :description "make the task from this template when it does not exist (user: a session's own agent)"}
                   :from {:type "string" :description "fork: an agent id (a3) whose program, live values and branch the new agent goes on from, on a sibling of its task"}}}
   :prove {:doc "Run the tests a branch's change reaches, once per branch and tx however many ask (a session, an agent's turn): the one test service (seon.prove). Names an agent (its branch at its head), else main. Answers the run: its key, the counts, the red tests; a result already held for a test's digest is not run again."
           :args {:agent {:type "string" :description "an agent id whose branch to prove; omit for main"}
                  :names {:type "array" :items {:type "string"} :description "test namespaces, or qualified test vars (ns/test, run alone: seon.agent/named-tests); omit for every test whose digest holds no green"}
                  :checkout {:type "string" :description "a worktree of this root's repository whose files to prove (with tree), in place of an agent"}
                  :tree {:type "string" :description "the checkout's tree id (seon.fx/tree-id, as bin/build records it): its branch's key"}
                  :mode {:type "string" :description "judge (default for a checkout), turn, or main (every key with no green at its digest, against no base: a whole tree)"}
                  :stream {:type "boolean" :description "EDN only: each test's verdict as a line as it ends, then the answer"}}}
   :inbox {:inline true
           :doc "Read an agent's messages, writing nothing (its key, or its task: a session's agent is the one attached to session/<area>): those linked to it after since (main's t: the :t of your last answer; 0 for all), at once, else the first ones written while it waits, else the typed :seon.server/no-message at :seon.config.root/inbox-wait-ms (ask again, with the same since). Every answer carries :t, your next since. No live agent process is needed. Run it in the background: its answer is your notification. With stream=true (bin/mcp call inbox ... stream=true, under a session's Monitor tool) each message is written as one line the moment it comes, until a wait ends with none."
           :args {:agent {:type "string" :description "an agent id"} :task {:type "string" :description "ns/name: its agent, instead of agent"}
                  :since {:type "string" :description "main's t of your last answer (its :t); 0 for every message; none for only what comes from now"}
                  :stream {:type "boolean" :description "each message a line as it comes (bin/mcp call), until a wait ends with none"}}}
   :done {:doc "Hand the task the agent works back to root: the agent writes its end (its proofs run, seon.turn/hand-back!), then root judges it, and merges it when the done rule has no reason. Answers the verdict when main holds it, as one line and a handle (seon.server/verdict-line): the task's outcome (done, merged, or closed with its task held) and reason, each red test with its first failure, and how main differs from the branch; the handle is the form that reads it whole at root. A defect of the reconciler that stops the judge, as that defect. It waits as long as the judge does (the run's proofs, a gate, a merge), so a caller's read timeout must allow minutes."
          :args {:agent {:type "string" :description "the agent, for a caller with no client; one with a client (bin/mcp) hands back the agent it last attached"}
                 :checkout {:type "string" :description "a session's land: its checkout's absolute path, whose changed files are imported onto the agent's branch first (my.branch/import!)"}}}))

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
  "An outside agent's request of agent ag as one reply (seon.turn/outside-reply!): {:responses [...]}, each eval's, and :request, what the model's
  next request adds, when the reply is the agent's turn (docs/users.md#messages). See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.system/sys :keyword :map] :map]}
  [sys ag req]
  (let [{:keys [task env]} (work-of sys ag)
        outside? (and task (= "outside" (:model env)))
        msg (:message-md req)]
    (cond
      (and (nil? task) (not (str/blank? msg)))
      (refused (str (name ag) " works no task now (its last has ended): seon_attach task=ns/name works one again, on its branch."))
      (and (not outside?) (not (str/blank? msg)))
      (refused (str (name ag) "'s own model drives it, so this reply is no message of its: send message-md empty, and seon_message to tell it something."))
      :else
      (let [form (list 'seon.turn/outside-reply! task msg (clojure.walk/stringify-keys (:evals req)) (boolean outside?))
            r @(c/submit! sys ag (sedn/encode form) {:origin :context})]
        (cond (contract/error? r) r
              (:ex r) (:ex r)
              :else (sedn/decode (:seon.print/code (:out r))))))))

(defmethod op-answer :eval [sys ag req]
  (if (= :root ag)
    (let [r (root-eval sys (:code req) (:input req))]
      ;; its value as data beside its printed text when it is plain data (seon.contract/plain?: what the wire's EDN carries):
      ;; a reader of the answer reads the value, never the text back (an eval that printed has its :out verbatim in the
      ;; text, which is no EDN since out-verbatim). The model reads the text alone (tools/call)
      (if (contract/error? r) r {:response (cond-> (select-keys r [:handle :text :ex :recorded :main :unrecorded])
                                             (and (contains? r :value) (contract/plain? (:value r))) (assoc :value (:value r)))}))
    ;; plain code on an agent is a reply of that one eval, for every caller (bin/mcp call's code=, a model that sends it)
    (agent-reply sys ag (if (and (contains? req :code) (not (contains? req :evals)))
                          (-> req (assoc :evals [{:intent (or (:intent req) "") :code (:code req)}]) (update :message-md #(or % "")) (dissoc :code :intent))
                          req))))

(defmethod op-answer :interrupt [sys _ req]
  (if-let [cl (:client req)]
    (stop-ops! sys {:client cl})
    (refused "seon_interrupt stops its caller's own ops, named by its client id (bin/mcp sends seon/client with every call): this request names none")))

(defmethod op-answer :import [sys _ req]
  (let [cl (:client req)
        ;; both canonical, so a path with .. or a link never names a file outside the checkout (root's reviewer)
        canon #(.getCanonicalPath (java.io.File. ^String %))
        checkout (some->> cl (re-matches #"checkout-(/.+)") second canon)
        path (canon (str (:path req)))
        rel (when (and checkout (str/starts-with? path (str checkout "/"))) (subs path (inc (count checkout))))]
    (cond (nil? checkout) (refused (str "seon_import imports onto the agent a checkout holds: its client is checkout-<path> (bin/mcp call), not " (pr-str cl)))
          (nil? rel) (refused (str path " is outside the checkout " checkout ": not imported"))
          :else (let [branch (fx/checkout-branch checkout)]
                  (if-let [{:keys [agent task]} (some->> branch (root/branch-agent sys))]
                    ;; an eval at the session's agent that is no turn of its: an edit moves the branch, never the agent's turn or its messages
                    (let [form (list 'seon.turn/outside-reply! task "" [{"intent" (str "import " rel ", written by the session")
                                                                         "code" (str "(my.branch/import! " (pr-str checkout) " [" (pr-str rel) "])")}] false)
                          r @(c/submit! sys agent (sedn/encode form) {:origin :context})]
                      (cond (contract/error? r) r (:ex r) (:ex r) :else (sedn/decode (:seon.print/code (:out r)))))
                    (refused (str checkout " is on " (or branch "no branch") ", whose session has no live agent (SEON_MCP_CLIENT=<area> bin/mcp call attach task=session/<area> template=user): " rel " not imported")))))))

;; the block's text as the model reads it: printed to the agent's output, not as a
;; value (a value comes back as a string literal, escaped, under the reply's budget);
;; recorded with origin :context, a context read, which renders refer to, not repeat
(defmethod op-answer :context [sys ag req]
  (let [ag (or ag (when-let [t (:task req)]
                    (or (:agent (first (live-run sys @(:conn sys) t)))
                        (:agent (attach! sys t {:client (:client req)})))))]
    ;; an agent whose task is not running (parked, waiting for a message: root's session between requests) is read from its branch too
    (if-let [{:keys [task env key parked? branch]} (when ag (or (work-of sys ag)
                                                       (let [e (d/pull @(:conn sys) [:seon.agent/key :seon.agent/branch {:seon.agent/task [:seon.task/id]}] [:seon.agent/key (name ag)])]
                                                         (when-let [t (get-in e [:seon.agent/task :seon.task/id])] {:task t :key (:seon.agent/key e) :branch (:seon.agent/branch e) :parked? true}))))]
      (let [a (get-in (c/state sys) [:agents ag])]
        (if (or parked? (= :busy (:status a)))
          ;; its one REPL is in an eval, which may never end (a test blocked on nothing): a read waits on no one, so root reads its branch
          (let [db (:db @(c/branch-db sys (or (:branch a) branch)))
                basis {:commit (d/commit-id db) :task task :turn (inc (or (:seon.agent/turn (d/pull db [:seon.agent/turn] [:seon.agent/key key])) 0)) :max-turns 1 :origin :outside :agent key}
                {:keys [messages]} (wire/render db basis)]
            {:agent ag :task task :context (str (name ag) (if parked? " waits for a message" (str " is still running an eval" (when-let [t (:form (:last-op a))] (str ", at " t)))) ": this is its branch as last committed, and nothing was taken.\n\n"
                           (wire/read-text db basis messages (true? (:wire req))))})
          ;; an outside model's read is its turn, and takes its messages; anyone
          ;; else's read of an agent its model drives only looks
          (let [r @(c/submit! sys ag (sedn/encode (list 'print (list 'seon.turn/context-block task (= "outside" (:model env)) (true? (:wire req))))) {:origin :context})]
            (cond (contract/error? r) r (:ex r) (:ex r) :else {:agent ag :task task :context (:seon.print/code (:out r))}))))
      (refused (if ag (str ag " works no task: attach one first") (str "no task " (:task req) ", and no agent named"))))))

(defn connection-agent
  "The key of the agent req's client is (my.root/held-agents, the one read of a client's agents): its session's own, of kind :user,
  else the one it last attached, ended or not (who sent a message outlives the process that held it); nil with no client or none
  held, then the signed-in person. See docs/users.md#messages."
  {:malli/schema [:=> [:cat :seon.system/sys :map] [:maybe :string]]}
  [sys req]
  (when-let [c (:client req)]
    (let [held (root/held-agents sys c)]
      (some-> (or (first (filter #(= :user (:kind %)) held)) (first held)) :agent name))))

;; Estimate: one :avet seek of the agent's message links, a tx filter and an entity read each, O(m) for m messages to it (~10k ticks a
;; message [M]); no write. A wait parks the connection's fiber (no worker, no thread) on await-message, its bound held by the api
(defn- inbox-messages
  "The messages main db holds to agent key k linked after since (a t), oldest first, each {:id :from :body :sent-ms :t}, :from by name
  and :t the link's transaction. Reads only. See docs/users.md#messages."
  {:malli/schema [:=> [:cat :seon.host/db :string :int] [:vector :map]]}
  [db k since]
  (if-let [e (pdb/eid db :seon.agent/key k)]
    (->> (d/datoms db :avet :seon.message/to e)
         (filter #(> (:tx %) since))
         (map (fn [dt] (let [m (d/entity db (:e dt))]
                         {:id (:seon.message/id m) :from (task/sender-name (:seon.message/from m)) :body (:seon.message/body m)
                          :sent-ms (:seon.message/sent-ms m) :t (:tx dt)})))
         (sort-by (juxt :t :id))
         vec)
    []))

(defn- inbox-answer
  "seon_inbox: req's agent's messages linked after since, at once or at the first commit that links one, else typed
  :seon.server/no-message at its bound; writes nothing, and :t is the next since. See docs/users.md#messages."
  {:malli/schema [:=> [:cat :seon.system/sys :map] :map]}
  [sys req]
  (let [db @(:conn sys)
        k (or (:agent req) (some->> (:task req) (task/agent-key db)))
        v (:since req)
        since (cond (nil? v) (:max-tx db) (int? v) v (and (string? v) (re-matches #"\d+" v)) (parse-long v))]
    (cond
      (not (and k (pdb/eid db :seon.agent/key k)))
      (refused (str "seon_inbox names no agent: give :agent (an agent key) or :task (a task id); asked agent " (:agent req) ", task " (:task req)))
      (nil? since) (refused (str "seon_inbox since is main's t, a whole number (the :t of your last answer); got " v))
      :else
      (let [ms (cfg/value :seon.config.root/inbox-wait-ms)
            opts {:k (keyword "seon.server" (str "inbox-" (lib/unique-id))) :ms ms
                  :due (contract/error :seon.server/no-message #'seon.server/inbox-answer (str "no message to " k " in " ms " ms: ask again, since=" since) {:agent k :waited-ms ms :t since})}
            answer (fn [db] (when-let [ms (not-empty (inbox-messages db k since))] {:agent k :messages ms :t (:max-tx db)}))]
        (or (answer db)
            ;; the wait starts at the t this read: a message linked after it wakes it
            (loop [t0 (:max-tx db)]
              (let [r @(:p (c/await-message sys k t0 opts))]
                (if (contract/error? r)
                  r
                  (if-let [a (answer r)]
                    (do (c/drop-wait! sys (:k opts)) a)
                    (recur (:max-tx r)))))))))))

(defmethod op-answer :message [sys ag req]
  ;; to the agent named, or the one its task names (as :inbox resolves it); from the agent the connection works through
  ;; (connection-agent), else the signed-in person
  (if-let [to (or ag (some->> (:task req) (task/agent-key @(:conn sys)) keyword))]
    @(c/message! sys to (:body req) (some->> (connection-agent sys req) (hash-map :from)))
    (refused (str "seon_message names no agent: give :agent (an agent key) or :task (a task id); asked task " (:task req)))))

(defmethod op-answer :write_back [sys _ _] (root/write-back! sys))

(defmethod op-answer :create [sys _ {:keys [ns name plan proofs]}]
  (let [decl (cond-> {:seon.task/ns (symbol ns) :seon.task/name name :seon.task/plan plan :seon.task/kind :whole}
               (seq proofs) (assoc :seon.task/proofs (set (map symbol proofs))))
        tx (task/create-tx @(:conn sys) [decl])
        r (if (contract/error? tx) tx @(c/transact-main! sys tx))]
    (if (contract/error? r) r {:task (task/task-id (symbol ns) name)})))

(defmethod op-answer :attach [sys _ req]
  (let [a (cond (:from req) (fork! sys (keyword (:from req)) (:client req))
                (:task req) (attach! sys (:task req) {:fresh? (true? (:fresh req)) :client (:client req) :template (:template req)})
                :else (refused "seon_attach needs task=ns/name, the task to work (or from=an-agent-id, to fork one)"))]
    (cond-> a
      (and (:agent a) (not (contract/error? a))) (assoc :next (str "seon_context with agent " (name (:agent a)) " reads what its model reads (wire true: the request body). "
                                   "seon_eval with it, message-md and evals (each an intent and code), is one reply, a model's turn; (my.agent/prove) there runs its proofs. seon_done hands it back.")))))

(def ^:dynamic *emit*
  "A line out before the answer, on the connection answering this request (serve-connection!): an EDN request's
  :prove with :stream writes each test's verdict through it as it ends. nil for MCP's JSON-RPC, which answers once.
  See docs/server.md#ops."
  nil)

(defmethod op-answer :inbox [sys _ req]
  (if (and (:stream req) *emit*)
    ;; a stream (an EDN request, bin/mcp call inbox ... stream=true): each read's messages written as lines as they come, the next
    ;; read from its :t, until a read ends with none (its bound's typed answer carries :t, the since to stream from again)
    (loop [req req]
      (let [r (inbox-answer sys req)]
        (if (seq (:messages r))
          (do (doseq [m (:messages r)] (*emit* (assoc m :type :message :agent (:agent r))))
              (recur (assoc req :since (:t r))))
          r)))
    (inbox-answer sys req)))

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
      (cond-> (select-keys r [:key :ran :pass :fail :error :red :reused :ms :no-verdict])
        (seq (:red r)) (assoc :failures (into (sorted-map) (for [[t fs] (proofs/failures r)] [t (first fs)])))
        (seq (:deferred r)) (assoc :deferred (:deferred r) :why (:why r))))))

(defmethod op-answer :done [sys ag req]
  (if-let [{:keys [task env]} (work-of sys ag)]
    ;; a session's land: its checkout's changed files onto its agent's branch first, one it cannot read refusing the land, since a
    ;; judgement of part of the files would be wrong; then the end, as a turn loop's end is written, so the judge reads it. Which files
    ;; changed is root's to read (seon.system/catch-up of the checkout against the tree the agent's branch last indexed: main's code,
    ;; whatever program the agent's process runs); the agent imports them by name (my.branch/import!, the edit hook's own call)
    (let [co (:checkout req)
          diff (when co (let [b @(c/branch-db sys (:branch env))]
                          (if (contract/error? b) b (c/catch-up (:db b) co {:fact? false}))))
          paths (when (map? diff) (into (vec (:paths diff)) (map :path) (:skipped diff)))
          imported (cond (nil? co) nil
                         (contract/error? diff) diff
                         (nil? diff) (refused (str "seon_done: the branch of " (name ag) " holds no tree to read " co "'s changes against"))
                         (empty? paths) {:value {:imported [] :unchanged [] :skipped []}}
                         :else @(c/submit! sys ag (sedn/encode (list 'my.branch/import! co paths)) {:origin :outside}))
          skipped (some-> imported :value :skipped seq)
          ;; the process then runs what its branch now holds (seon.agent/take-branch-program!, an outside agent's load-diff!), so the
          ;; judge judges the imported code, never the program the process started with
          loaded (when (and imported (not (contract/error? imported)) (not (:ex imported)) (not skipped) (seq (get-in imported [:value :imported])))
                   @(c/submit! sys ag (sedn/encode '(seon.agent/take-branch-program!)) {:origin :outside}))
          load-errors (some-> loaded :value seq)
          r (cond (contract/error? imported) imported
                  (:ex imported) (:ex imported)
                  skipped (refused (str "seon_done: " (count skipped) " file(s) of " (:checkout req) " do not read, so nothing is judged: "
                                        (str/join "; " (for [{:keys [path why]} skipped] (str path " (" why ")")))))
                  (contract/error? loaded) loaded
                  (:ex loaded) (:ex loaded)
                  load-errors (refused (str "seon_done: the imported code does not load, so nothing is judged: " (pr-str (vec (take 5 load-errors)))))
                  :else @(c/submit! sys ag (sedn/encode (list 'seon.turn/hand-back! task)) {:origin :outside}))]
      ;; judged only on the end written: an error or no answer is answered as it is,
      ;; and the task stays the agent's to hand back again
      (cond (contract/error? r) r
            (:ex r) (:ex r)
            (:text r) (let [
                            outcome #(:seon.task/outcome (d/pull % [:seon.task/outcome] [:seon.task/id task]))
                            ;; the judge's own end answers (seon.reconcile/answer-judged): the task judged and past its merge (it
                            ;; left :running and the gate), or the typed error of a judge that left it :running, or root stopped
                            j (c/judge! sys {:task task :env env :agent ag})
                            db @(:conn sys)]
                        (cond (contract/error? j) (assoc j :task task)
                              (:stopped j) (refused (str "root stopped while judging " task))
                              ;; a reconciler step threw while judging: its flight never ends, the defect is the answer
                              (:defect j) {:task task :defect (select-keys (:defect j) [:seon.error/type :seon.error/message])}
                              ;; :behind, the one :merge's :reprove (main's program moved since its proof, its branch now holding main): handed
                              ;; back again, which proves again on the branch, as a model agent's turn loop does when it is told so
                              (= :behind (outcome db)) (op-answer sys ag req)
                              ;; a defect and a judge that left the task :running are the judge's own answers (j), never re-read here
                              :else (let [files (fx/uncommitted (str (cfg/checkout)))]
                                        (cond-> (verdict-line db task (:main r))
                                          ;; judged; its merge gate runs on: the verdict comes later, where the agent reads
                                          (= :gate (outcome db))
                                          (assoc :next (str "the merge gate runs " (count (:seon.task/deferred (d/pull db [:seon.task/deferred] [:seon.task/id task])))
                                                            " process test(s) the change reaches; their verdict reaches your page (seon_context) and the task's outcome"))
                                          (and (seq files) (not (contract/error? files))) (assoc :checkout-not-merged files)))))
            :else (refused (str ag " answered no end for " task))))
    (refused (str ag " works no task"))))

(def ^:private inline-ops
  "The ops a connection answers on its own fiber, never through a :server-op worker (serve-connection!): those that declare :inline
  in ops (a wait on our own system's event, seon_inbox, parks the fiber; a stop, seon_interrupt, never waits behind the ops it
  stops). Read from the one table, never named in code."
  (into #{} (keep (fn [[op v]] (when (:inline v) op))) ops))

(def ^:private own-ops
  "The ops that act on an agent's work: a caller with a client acts on the agent that client last attached (`own-agent`)."
  #{:done :eval})

(defn- own-agent
  "The agent req acts on: for :done or :eval from a caller with a client, the agent that client last attached (or the answer
  that it holds none); else the agent req names. See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.system/sys :map] :any]}
  [sys req]
  (let [named (some-> (:agent req) (#(if (= "root" %) :root (keyword %))))
        cl (:client req)]
    (if (and cl (own-ops (:op req)) (not= :root named))
      (or (:agent (root/held-agent sys cl))
          (refused (str "client " cl " holds no agent: seon_attach task=ns/name attaches one, which its seon_" (name (:op req)) " then acts on")))
      named)))

(defn answer
  "One request's answer: its op's (op-answer) on the agent it acts on (`own-agent`). The op runs under its owner's token (*token*) and its
  work bound (:seon.config.fx/bounds :server-op), past which it throws typed; root's own :eval has no bound. See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.system/sys :map] :map]}
  [sys req]
  (let [ag (own-agent sys req)
        missing (remove #(contains? req (keyword %)) (:required (get ops (:op req))))
        run (fn [] (if (contract/error? ag) ag (op-answer sys ag req)))]
    (or (when (seq missing) (refused (str "seon_" (name (:op req)) " needs " (str/join ", " missing) ": " (str/join "; " (for [m missing] (str m " is " (get-in ops [(:op req) :args (keyword m) :description] "required")))))))
        (cond
          ;; answered on the connection's own fiber (serve-connection!): a stop is one message to the owner, never bounded work
          (inline-ops (:op req)) (run)
          (and (= :eval (:op req)) (= :root ag)) (intr/run nil nil *token* run)
          :else (let [{:seon.bound/keys [ticks bytes]} (:server-op (cfg/value :seon.config.fx/bounds))]
                  (intr/run ticks bytes *token* run))))))

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
    ;; MCP's cancellation (a notification: answered nothing): the client gave up on its request requestId, which is stopped
    (when (and (= "notifications/cancelled" method) (string? (get msg "seon/client")) (some? (get params "requestId")))
      (stop-ops! sys {:client (get msg "seon/client") :id (get params "requestId")}))
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
                                                 ;; who asks: bin/mcp's id for its session (own-agent)
                                                 (get msg "seon/client") (assoc :client (get msg "seon/client"))))]
                           ;; what the model reads: the block itself, an eval's response as printed
                           ;; under its handle, an error by the printer, an op's answer that root's
                           ;; REPL answers again (:again) under the reply budget with that form as its
                           ;; handle, anything else whole by the printer (nothing could re-ask it)
                           (result {"content" [{"type" "text" "text" (cond (and (= :context op) (:context a)) (:context a)
                                                                           (:response a) (eval-text (:response a))
                                                                           (:responses a) (str (str/join "\n\n" (map eval-text (:responses a)))
                                                                                               ;; what the model's next request adds (seon.turn/outside-reply!): the page's edits and the messages it took
                                                                                               (when-let [t (not-empty (:request a))] (str "\n\nWhat your next request adds:\n\n" t)))
                                                                           (contract/error? a) (print/text a)
                                                                           (:again (ops op)) (let [p (print/print-value a {:handle (:again (ops op))})] (or (:text p) (:seon.error/message p)))
                                                                           :else (print/text (vary-meta a assoc :seon.print/full true)))}]}))))
        (rpc-error id -32601 (str "no method " method ": this server answers initialize, ping, tools/list and tools/call"))))))

(defn- respond
  "The answer line to one request line, or nil (an MCP notification). The protocol rule: a
  line opening `{\"` is MCP's JSON-RPC (mcp-reply, JSON out), every other line is EDN (answer,
  EDN out), so an EDN request is a keyword-keyed map. The entrant boundary: a defect (a line
  that does not read, an op that throws) answers that request as the typed
  :seon.server/defect (an op its caller stopped, :seon.server/interrupted), for MCP inside a JSON-RPC error with the request's id (-32700 for
  a line that is not JSON, naming the rule)."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.system/sys] :string] [:maybe :string]]
                  [:=> [:cat [:maybe :seon.system/sys] :string :any] [:maybe :string]]]}
  ([sys line] (respond sys line nil))
  ;; read: the line as the connection already read it (line-parts), nil to read it here
  ([sys line read]
  (let [mcp? (str/starts-with? (str/triml line) "{\"")
        msg (volatile! nil)]
    (try (if mcp?
           (some-> (mcp-reply sys (vreset! msg (or read (json/read-str line)))) json/write-str)
           (sedn/encode (answer sys (or read (edn/read-string line)))))
         (catch Throwable e
           (let [cause (step/error-msg e)
                 d (if (instance? ThreadDeath e)
                     ;; its caller stopped it (seon_interrupt, notifications/cancelled), or root's interrupt
                     (contract/error :seon.server/interrupted #'seon.server/respond "the op was stopped by its caller's interrupt" {:line line})
                     (contract/error :seon.server/defect #'seon.server/respond
                                     (if (and mcp? (nil? @msg)) (str "a line opening {\" is MCP's JSON-RPC, and an EDN request a keyword-keyed map; this one is not JSON: " cause) cause)
                                     (cond-> {:line line} (ex-data e) (assoc :data (ex-data e)))))]
             (if mcp?
               (json/write-str (rpc-error (get @msg "id") (if @msg -32603 -32700) (print/text d)))
               (sedn/encode d))))))))

(defn perform-op
  "The :server-op effect's perform: one server op, run on the kind's :mixed worker (docs/server.md#ops). The request's :run is the
  op as a thunk the connection made (bound-fn: its *emit* and the dynamic vars it was taken with) of the token the owner made
  for it (:seon.fx/token, seon.fx/stop), so an op that blocks holds this worker, never the connection's fiber, a carrier or
  another connection. Answers {:value its line}."
  {:malli/schema [:=> [:cat :map] :map]}
  [{{:keys [run] :as req} :req}] {:value (run (:seon.fx/token req))})

(defn- line-parts
  "One request line read once: :msg (respond takes it), :inline? (a stop, answered on the fiber) and :stop-key ({:client
  :id}, who may stop it; nil: no client). A line that does not read is none of them. See docs/server.md#ops."
  {:malli/schema [:=> [:cat :string] [:map [:inline? :boolean] [:stop-key [:maybe :seon.fx/stop-key]] [:msg :any]]]}
  [line]
  (let [msg (try (if (str/starts-with? (str/triml line) "{\"") (json/read-str line) (edn/read-string line)) (catch Exception _ nil))]
    (cond (and (map? msg) (contains? msg "jsonrpc"))
          (let [cl (get msg "seon/client")]
            {:inline? (boolean (or (= "notifications/cancelled" (get msg "method"))
                                   (and (= "tools/call" (get msg "method")) (inline-ops (some-> (get-in msg ["params" "name"]) (str/replace #"^seon_" "") keyword)))))
             :stop-key (when (string? cl) (cond-> {:client cl} (some? (get msg "id")) (assoc :id (get msg "id"))))
             :msg msg})
          (map? msg) {:inline? (boolean (inline-ops (:op msg))) :stop-key (when (string? (:client msg)) {:client (:client msg)}) :msg msg}
          :else {:inline? false :stop-key nil :msg nil})))

(defn- serve-connection!
  "Answer one connection's request lines, line by line, until it closes; a notification is answered nothing. It runs on a
  fiber parked on its socket (jolt.socket), and each line's op runs on a :server-op worker (perform-op), so the connection
  holds no OS thread and an op that blocks holds no other connection; a stop (seon_interrupt, MCP's notifications/cancelled)
  runs on this fiber, so it is answered while every worker is held. See docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.system/sys :any] :any]}
  [sys sock]
  (let [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
        out (.getOutputStream sock)]
    (loop []
      (when-let [line (.readLine r)]
        (let [mcp? (str/starts-with? (str/triml line) "{\"")
              {:keys [inline? stop-key msg]} (line-parts line)
              run (bound-fn [tok] (binding [*emit* (when-not mcp?
                                                     (fn [m] (.write out (.getBytes (str (sedn/encode m) "\n") "UTF-8")) (.flush out)))
                                            *token* tok]
                                    (respond sys line msg)))
              ;; a stop, and a wait on our own system's event, run on this fiber (inline-ops): a stop is never queued behind the ops it
              ;; stops, and a wait parks, holding no worker thread; every other op on a worker, under the
              ;; token its owner makes for it when the request names who may stop it
              o (if inline? {:value (run nil)} @(c/effect! sys :server-op (cond-> {:run run} stop-key (assoc :seon.fx/stop-key stop-key))))
              ;; the owner's own refusal (no worker, a stopped root) is this request's answer, in its protocol
              a (if (contract/error? o)
                  (if mcp? (json/write-str (rpc-error nil -32603 (print/text o))) (sedn/encode o))
                  (:value o))]
          (when a
            (.write out (.getBytes (str a "\n") "UTF-8"))
            (.flush out)))
        (recur)))
    (.close sock)))

(defn serve!
  "Listen on 127.0.0.1:port (0: a free port the kernel picks; read it with .getLocalPort). The accept loop and each connection
  are fibers parked on their sockets (jolt.socket), at most :seon.config.root/server-connections open: one past it waits in the
  accept until one closes (a token taken at its accept, given back at its close). Answers the server socket. See
  docs/server.md#ops."
  {:malli/schema [:=> [:cat :seon.system/sys nat-int?] :any]}
  [sys port]
  (let [ss (java.net.ServerSocket. port 50 (java.net.InetAddress/getByName "127.0.0.1"))
        tokens (a/chan (cfg/value :seon.config.root/server-connections))]
    (a/io-thread
      (loop []
        (a/>!! tokens true)
        (let [s (.accept ss)]
          (a/io-thread (try (serve-connection! sys s) (finally (a/poll! tokens))))
          (recur))))
    ss))
