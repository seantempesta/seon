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
  (:require [seon.step :as step] [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.root :as c] [seon.task :as task] [seon.proofs :as proofs] [seon.error :as err] [seon.reconcile :as r] [datahike.api :as d]
            [seon.lib :as lib] [seon.edn :as sedn] [seon.db :as pdb] [seon.repl :as repl] [seon.print :as print] [clojure.edn :as edn] [clojure.data.json :as json] [clojure.string :as str]))

(defn- refused
  "The server refusing a request, as root's API does: the one error map."
  {:malli/schema [:=> [:cat :string] :seon.root/refused]}
  [why] (contract/error :seon.root/refused #'seon.server/refused why))

(defn task-summaries
  "Every task on main db: {id {:execution :done :blocked-by :agent :outcome :failure}}: the server's and the
  e2e harness's one summary. A key says something or is left out: :done only when done, :blocked-by only when
  there are some, :agent and :outcome only when an agent has worked it (676 held tasks spelled
  `:done false, :blocked-by #{}` each). With idx, the db's reconciler index (`seon.reconcile/index`) that a caller holds already, none is built (docs/tasks.md)."
  {:malli/schema [:function [:=> [:cat :seon.host/db] :map] [:=> [:cat :seon.host/db :seon.reconcile/index] :map]]}
  ([db] (task-summaries db (r/index db)))
  ([db idx]
    (into (sorted-map)
          (for [[id t] (:tasks idx)
                :let [blocked (r/blocked-by idx id)
                      agent (get-in idx [:agents (:seon.task/agent t) :seon.agent/id])]]
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
  {:malli/schema [:=> [:cat :seon.host/db] [:map [:tasks :map] [:others :map] [:all :any]]]}
  [db]
  (let [idx (r/index db)
        started (fn [id] (or (some->> (get-in idx [:tasks id :seon.task/agent]) (vector :seon.agent/key) (d/entity db) :seon.agent/started-ms) 0))
        all (task-summaries db idx)
        live? (fn [[_ s]] (or (= :running (:execution s)) (:agent s)))]
    {:tasks (apply array-map (mapcat identity (sort-by (comp started key) #(compare %2 %1) (filter live? all))))
     :others (frequencies (map (comp :execution val) (remove live? all)))
     :all '(seon.server/task-summaries @(:conn sys))}))

(defn agents-answer
  "Live agents (id, branch, status, task) and what is live on main (live-tasks)."
  {:malli/schema [:=> [:cat :seon.root/sys] :map]}
  [sys]
  (assoc (live-tasks @(:conn sys))
         :agents (into (sorted-map) (for [[id a] (:agents (c/state sys)) :when (not (c/gone-statuses (:status a)))]
                                      [id (select-keys a [:branch :status :task])]))))

(def ^:dynamic *sys*
  "This root while its REPL evaluates a form: the `sys` each form reads (root-eval)."
  nil)

(defn- root-eval
  "Evaluate code as root through the one REPL path (seon.repl/eval-string: the printer,
  cost, error maps, *1 and *e), in root.repl (my.root as root, seon.root as c, datahike as
  d) with `sys` a local bound to this root, unbounded (root's own forms take no tick or allocation
  budget: a whole-program load allocates tens of GB, and the agent's per-form
  budget stopped it); its response recorded on main as root's eval, by \"outside\" (seon.repl/record-tx)."
  {:malli/schema [:=> [:cat :seon.root/sys :string] :seon.repl/answer]}
  [sys code]
  (when-not (find-ns 'root.repl)
    (binding [*ns* (create-ns 'root.repl)] (refer-clojure) (require '[my.root :as root] '[seon.root :as c] '[datahike.api :as d])))
  (let [r (binding [*sys* sys]
            (repl/eval-string code {"ns" "root.repl" :origin :outside :ticks 0 :bytes 0
                                    :repl/eval-fn (fn [form] (eval (list 'let '[sys seon.server/*sys*] form)))}))]
    @(c/transact-main! sys (repl/record-tx {:seon.root/id :root} (str "root-outside-" (profile/now-ns)) "outside"
                                           (repl/record-details code r {})))
    ;; its value too: the :eval answer passes it on when it is plain data (op-answer :eval)
    r))

(defn- live-run
  "Task id's running agent whose process lives in root's view now, as [work nil], or [nil dead]
  with dead its running agent that is gone (died, ended by seon_done, killed for memory): a
  death is a fact root's view reads, so a dead agent never answers as the one to work the
  task (attach! starts a fresh agent on the same branch). [nil nil] when none runs."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.host/db :seon.task/id] [:tuple [:maybe :map] [:maybe :map]]]}
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
  old branch is killed as its env changed; that branch and its records stay). A task
  whose agent is live answers that agent, :shared true: the caller has the branch, with
  whoever attached it, and that one's seon_done ends it for both. An agent that is gone (live-run)
  is replaced by a fresh one on its branch, the session's key continuing. A branch another live agent holds
  is spawn!'s refusal, answered as a value, naming the agent and the task it works.
  clone-of: the agent is that live agent's clone (seon.root/clone!: live values and all, its branch
  cut from the clone's once its writes commit), its own key and conversation (a fork's). client:
  who attached it (bin/mcp's id for its session), the agent's :holder: only that client's requests,
  or ones that name the task, act on it (held).
  Answers {:agent :task}."
  {:malli/schema [:function [:=> [:cat :seon.root/sys :seon.task/id] :map]
                  [:=> [:cat :seon.root/sys :seon.task/id [:map [:fresh? {:optional true} :boolean] [:clone-of {:optional true} :keyword] [:client {:optional true} [:maybe :string]]]] :map]]}
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
            k (when-not (or fresh? clone-of) (task/session-key db id))
            opts (cond-> {:branch (:branch env) :task id} k (assoc :key k))
            r (if clone-of @(c/clone! sys clone-of opts) @(c/call! sys (c/request 'spawn! nil opts nil)))
            ag (:id r)]
        ;; spawn!'s request, its answer a value: a refusal (a branch another agent holds, named with
        ;; its task) is the attach's answer, never a server defect
        (if-not ag
          r
          (do @(c/transact-main! sys (vec (concat (task/execution-tx [id] :running)
                                              (when fresh? [{:seon.task/id id :seon.task/branch (:branch env)}])
                                              (task/start-tx db id (:boot sys) ag env (System/currentTimeMillis) k))))
              {:agent ag :task id})))))))

(defn- fork!
  "A fork of live agent from: a sibling of its task, <name>-fork-<n> (its plan, proofs and kind, on
  a branch of its own), declared on main and attached with the agent's clone (attach!'s clone-of),
  so the fork goes on from the agent's program and branch while the agent goes on on its own. The
  clone has its own key and conversation: an agent's entity on main is its key's, and two live
  agents of one key overwrite each other's facts. Each is judged and merged on its own; root
  cancels the one not wanted, and a refused clone cancels its sibling at once. Answers attach!'s."
  {:malli/schema [:=> [:cat :seon.root/sys :keyword [:maybe :string]] :map]}
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
  "The task the live agent ag works, from main: {:task :env}, or nil."
  {:malli/schema [:=> [:cat :seon.root/sys :keyword] [:maybe :map]]}
  [sys ag]
  (some (fn [a] (when (= ag (:agent a)) (select-keys a [:task :env])))
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
  reason, and for each red test its first failing assertion's message, all cut by the one printer
  (:part print/budgets, the cut saying what it left), with how main differs from the branch when
  the agent said so (main, seon.agent/main-difference's line), and the form that reads the whole
  verdict at root (`verdict`)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.task/id [:maybe :string]] [:map [:line :string] [:handle :string]]]}
  [db task main]
  (let [v (verdict db task)
        fails (:seon.task/failures (d/pull db [:seon.task/failures] [:seon.task/id task]))
        reds (get-in v [:held :seon.task/evidence :red])]
    {:line (print/text (cond-> (select-keys v [:outcome :reason])
                         (and (:held v) (empty? reds)) (assoc :held (get-in v [:held :seon.task/failure :reason]))
                         (seq reds) (assoc :reds (vec (for [t reds] [t (or (some-> (get fails t) first :message) (some-> (get fails t) first :type name))])))
                         main (assoc :main main))
                       (:part print/budgets))
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
   :eval {:doc "Evaluate Clojure in a running agent's process (recorded on its branch, origin :outside) or as \"root\" in root's process (root.repl: `sys` is root; my.root/run!, my.root/cancel! ...), each through the one REPL path. Answers its response (:seon.eval/response): {:handle :value :out :err :ex}, printed under its handle."
          :args {:agent {:type "string" :description "an agent id (a3) or root"} :code {:type "string" :description "Clojure forms"}
                 :task {:type "string" :description "the agent's task (ns/name): needed only for an agent another session attached"}}
          :required ["agent" "code"]}
   :context {:doc "The request of the task an agent works as a model is sent it now: system prompt, its page and its conversation. Name the agent, or a task (ns/name): the agent working it, else one attached to it now (attach), so one request reads a task's context."
             :args {:agent {:type "string"} :task {:type "string" :description "ns/name, instead of an agent"}}}
   :message {:doc "Send an agent a message (the owner's words): a message on main to the task it works (refused when it works none), which its next turn takes onto its branch and reads, and which wakes a parked session. The page's message bar sends the same request (seon.root/message!)."
             :args {:agent {:type "string"} :body {:type "string" :description "what to tell the agent"}
                    :task {:type "string" :description "the agent's task (ns/name): needed only for an agent another session attached"}} :required ["agent" "body"]}
   :attach {:doc "Work a task as a Seon agent: root starts an agent on the task's branch (model outside) and answers its id. fresh: reset it, a new branch from main and an empty conversation (the old branch kept). from: fork that agent, a clone of it on a branch of its head working a sibling task."
            :args {:task {:type "string" :description "ns/name"} :fresh {:type "boolean" :description "reset: start the task again from main"}
                   :from {:type "string" :description "fork: an agent id (a3) whose program, live values and branch the new agent goes on from, on a sibling of its task"}}}
   :prove {:doc "Run the tests a branch's change reaches, once per branch and tx however many ask (a session, an agent's turn): the one test service (seon.prove). Names an agent (its branch at its head), else main. Answers the run: its key, the counts, the red tests; a result already held for a test's digest is not run again."
           :args {:agent {:type "string" :description "an agent id whose branch to prove; omit for main"}
                  :names {:type "array" :items {:type "string"} :description "test namespaces to run; omit for every test whose digest holds no green"}}}
   :done {:doc "Hand the task the agent works back to root: the agent writes its end (its proofs run, seon.turn/hand-back!), then root judges it, and merges it when the done rule has no reason. Answers the verdict when main holds it, as one line and a handle (seon.server/verdict-line): the task's outcome (done, merged, or closed with its task held) and reason, each red test with its first failure, and how main differs from the branch; the handle is the form that reads it whole at root. A defect of the reconciler that stops the judge, as that defect. It waits as long as the judge does (the run's proofs, a gate, a merge), so a caller's read timeout must allow minutes."
          :args {:agent {:type "string"} :task {:type "string" :description "the agent's task (ns/name): needed only for an agent another session attached"}} :required ["agent"]}))

(defn- held
  "The refusal of request req on agent ag when another client holds it, else nil: its
  :holder (attach!'s client) is not req's :client and req names no :task, or another than the
  agent's. One session's seon_done once named another session's agent and ended its work red."
  {:malli/schema [:=> [:cat :seon.root/sys :keyword :map] [:maybe :map]]}
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
  {:malli/schema [:=> [:cat :seon.root/sys [:maybe :keyword] :map] :map]}
  (fn [_ _ req] (:op req)))

(defmethod op-answer :default [_ _ req] (refused (str "unknown op " (:op req))))

(defmethod op-answer :agents [sys _ _]
  (agents-answer sys))

(defmethod op-answer :eval [sys ag req]
  (let [r (if (= :root ag) (root-eval sys (:code req)) @(c/submit! sys ag (:code req) {:origin :outside}))]
    ;; its value as data beside its printed text when it is plain data (seon.contract/plain?: what the wire's EDN carries):
    ;; a reader of the answer reads the value, never the text back (an eval that printed has its :out verbatim in the
    ;; text, which is no EDN since out-verbatim). The model reads the text alone (tools/call)
    (if (contract/error? r) r {:response (cond-> (select-keys r [:handle :text :ex :recorded :main :unrecorded])
                                           (and (contains? r :value) (contract/plain? (:value r))) (assoc :value (:value r)))})))

;; the block's text as the model reads it: printed to the agent's output, not as a
;; value (a value comes back as a string literal, escaped, under the reply's budget);
;; recorded with origin :context, a context read, which renders refer to, not repeat
(defmethod op-answer :context [sys ag req]
  (let [ag (or ag (when-let [t (:task req)]
                    (or (:agent (first (live-run sys @(:conn sys) t)))
                        (:agent (attach! sys t {:client (:client req)})))))]
    (if-let [{:keys [task env]} (when ag (work-of sys ag))]
      ;; an outside model's read is its turn, and takes its messages; anyone
      ;; else's read of an agent its model drives only looks
      (let [r @(c/submit! sys ag (sedn/encode (list 'print (list 'seon.turn/context-block task (= "outside" (:model env))))) {:origin :context})]
        (cond (contract/error? r) r (:ex r) (:ex r) :else {:context (:seon.print/code (:out r))}))
      (refused (if ag (str ag " works no task: attach one first") (str "no task " (:task req) ", and no agent named"))))))

(defmethod op-answer :message [sys ag req]
  @(c/message! sys ag (:body req)))

(defmethod op-answer :attach [sys _ req]
  (if-let [from (:from req)] (fork! sys (keyword from) (:client req)) (attach! sys (:task req) {:fresh? (true? (:fresh req)) :client (:client req)})))

(defmethod op-answer :prove [sys ag req]
  (let [r @(c/prove! sys ag (set (map symbol (:names req))))]
    ;; each red named with its first failing assertion (message, expected, actual), and the
    ;; process tests left to the judge with why (seon.agent/run-tests-forked's :deferred :why)
    (if (contract/error? r)
      r
      (cond-> (select-keys r [:key :ran :pass :fail :error :red :reused :ms])
        (seq (:red r)) (assoc :failures (into (sorted-map) (for [[t fs] (proofs/failures r)] [t (first fs)])))
        (seq (:deferred r)) (assoc :deferred (:deferred r) :why (:why r))))))

(defmethod op-answer :done [sys ag _]
  (if-let [{:keys [task env]} (work-of sys ag)]
    ;; the end first, as a turn loop's end is written, so the judge reads it
    (let [r @(c/submit! sys ag (sedn/encode (list 'seon.turn/hand-back! task)) {:origin :outside})]
      ;; judged only on the end written: an error or no answer is answered as it is,
      ;; and the task stays the agent's to hand back again
      (cond (contract/error? r) r
            (:ex r) (:ex r)
            (:text r) (let [t (:max-tx @(:conn sys))
                            outcome #(:seon.task/outcome (d/pull % [:seon.task/outcome] [:seon.task/id task]))
                            ;; judged: the task left :running (the reconciler accepted, parked,
                            ;; held or refused it), or a root defect stopped the judge
                            _ (c/judge! sys {:task task :env env :agent ag})
                            db (c/await-db sys (str "the judge of " task)
                                          #(or (not= :running (outcome %)) (c/defect % t "seon.reconcile")))]
                        ;; a judge that has not moved by root's quiet never will: that, or root
                        ;; stopped, is the answer
                        (cond (contract/error? db) (assoc db :task task)
                              :else (if-let [p (and (= :running (outcome db)) (c/defect db t "seon.reconcile"))]
                                      {:task task :defect (select-keys p [:seon.error/type :seon.error/message])}
                                      (verdict-line db task (:main r)))))
            :else (refused (str ag " answered no end for " task))))
    (refused (str ag " works no task"))))

(def ^:private held-ops
  "The ops that act on an agent's work, which its holder's client (or a request naming its task) may ask."
  #{:done :eval :message})

(defn answer
  "One request's answer: its op's (op-answer), or held's refusal for an op on an agent another client holds."
  {:malli/schema [:=> [:cat :seon.root/sys :map] :map]}
  [sys req]
  (let [ag (some-> (:agent req) (#(if (= "root" %) :root (keyword %))))]
    (or (when (and (held-ops (:op req)) (keyword? ag) (not= :root ag)) (held sys ag req))
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

(defn mcp-reply
  "The JSON-RPC answer to one MCP message, or nil for a notification: initialize; ping;
  tools/list, each op the tool seon_<op>; tools/call, the op's answer as the tool's text
  (seon_context's, the block's text itself: the model's own view, not an escaped string
  inside a map). A method it does not know, or a tool no op names, is the JSON-RPC error
  saying so."
  {:malli/schema [:=> [:cat [:maybe :seon.root/sys] :map] [:maybe :map]]}
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
                                                                           (:response a) (let [r (:response a)] (str (:text r) (when-let [u (:unrecorded r)] (str "\n" (:seon.error/message u)))))
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
  {:malli/schema [:=> [:cat [:maybe :seon.root/sys] :string] [:maybe :string]]}
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
  {:malli/schema [:=> [:cat :seon.root/sys :any] :any]}
  [sys sock]
  (let [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock)))
        out (.getOutputStream sock)]
    (loop []
      (when-let [line (.readLine r)]
        (when-let [a (respond sys line)]
          (.write out (.getBytes (str a "\n") "UTF-8"))
          (.flush out))
        (recur)))
    (.close sock)))

(defn serve!
  "Listen on 127.0.0.1:port (0: a free port the kernel picks; read it with .getLocalPort):
  the accept loop and each connection on edge threads (blocking accept and reads). Answers
  the server socket."
  {:malli/schema [:=> [:cat :seon.root/sys nat-int?] :any]}
  [sys port]
  (let [ss (java.net.ServerSocket. port 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (doto (Thread. ^Runnable (fn [] (loop [] (let [s (.accept ss)]
                                                (doto (Thread. ^Runnable (fn [] (serve-connection! sys s)) "seon-server-conn")
                                                  (.setDaemon true) (.start))
                                                (recur))))
                   "seon-server-accept")
      (.setDaemon true) (.start))
    ss))
