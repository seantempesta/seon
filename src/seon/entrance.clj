(ns seon.entrance
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
  (:require [seon.profile :as profile] [seon.contract :as contract :refer [defn defn-]] [seon.root :as c] [seon.task :as task] [seon.reconcile :as r]
            [seon.lib :as lib] [seon.repl :as repl] [seon.print :as print] [seon.render :as render] [seon.render.ai :as rai] [clojure.edn :as edn] [clojure.data.json :as json] [clojure.string :as str]))

(defn- refused
  "The entrance refusing a request, as root's API does: the one error map."
  {:malli/schema [:=> [:cat :string] :seon.root/refused]}
  [why] (contract/error :seon.root/refused 'seon.entrance/answer why))

(defn task-summaries
  "Every task on main db: {id {:execution :done :blocked-by :attempts [[id status outcome
  turns tokens]] :problem}}: the server's and the e2e harness's one summary."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (let [idx (r/index db)]
    (into (sorted-map)
          (for [[id t] (:tasks idx)]
            [id (cond-> {:execution (:seon.task/execution t) :done (r/done? idx id) :blocked-by (r/blocked-by idx id)
                         :attempts (mapv (juxt :seon.attempt/id :seon.attempt/status :seon.attempt/outcome :seon.attempt/turns :seon.attempt/tokens)
                                         (sort-by :seon.attempt/id (map (:attempts idx) (get-in idx [:by-task id]))))}
                  (:seon.task/problem t) (assoc :problem (:seon.task/problem t)))]))))

(defn- agents-answer
  "Live agents (id, branch, status, task) and every task (task-summaries)."
  {:malli/schema [:=> [:cat :seon.root/sys] :map]}
  [sys]
  {:agents (into (sorted-map) (for [[id a] (:agents (c/state sys)) :when (not (c/gone-statuses (:status a)))]
                                [id (select-keys a [:branch :status :task])]))
   :tasks (task-summaries @(:conn sys))})

(def ^:dynamic *sys*
  "This root while its REPL evaluates a form: the `sys` each form reads (root-eval)."
  nil)

(defn- root-eval
  "Evaluate code as root through the one REPL path (seon.repl/eval-string: the printer,
  cost, error maps, *1 and *e), in root.repl (my.root as root, seon.root as c, datahike as
  d) with `sys` a local bound to this root, unbounded (root's own forms take no tick
  budget); its items recorded on main as root's evaluations, origin :outside
  (seon.repl/record-tx)."
  {:malli/schema [:=> [:cat :seon.root/sys :string] :seon.agent/items]}
  [sys code]
  (when-not (find-ns 'root.repl)
    (binding [*ns* (create-ns 'root.repl)] (refer-clojure) (require '[my.root :as root] '[seon.root :as c] '[datahike.api :as d])))
  (let [items (mapv #(assoc % :origin :outside)
                    (binding [*sys* sys]
                      (repl/eval-string code {"ns" "root.repl" :origin :outside :ticks 0
                                              :repl/eval-fn (fn [form] (eval (list 'let '[sys seon.entrance/*sys*] form)))})))]
    @(c/transact-main! sys (repl/record-tx :root (str "root-outside-" (profile/now-ns)) items nil))
    items))

(defn- attach!
  "Start an outside attempt of task id: its desired model is \"outside\", so the
  reconciler starts no turn loop for it and does not kill it. The agent is spawned first;
  only once it exists are its env, the release and the attempt written, in one transaction,
  so a refused spawn writes nothing and the reconciler never sees a release with no attempt
  (it would spawn a second agent for it). Answers {:agent :task :attempt}."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.task/id] :map]}
  [sys id]
  (let [db @(:conn sys)
        t (task/task db id)]
    (if-not t
      (refused (str "no task " id))
      (let [env (assoc (r/desired-env t) :model "outside")
            aid (str id "#" (inc (count (filter #(= id (:seon.attempt/task %)) (vals (task/attempts db))))))
            ag (c/spawn! sys {:branch (:branch env) :task id :attempt aid})]
        @(c/transact-main! sys (vec (concat (task/env-tx id {:model "outside"}) (task/execution-tx [id] :running)
                                            (task/attempt-start-tx aid id (:boot sys) ag env (System/currentTimeMillis)
                                                                   (:plan (:seon.task/accumulated t))))))
        {:agent ag :task id :attempt aid}))))

(defn- attempt-of
  "The live attempt agent ag works, from main: {:attempt :task :env}, or nil."
  {:malli/schema [:=> [:cat :seon.root/sys :keyword] [:maybe :map]]}
  [sys ag]
  (some (fn [a] (when (and (= ag (:seon.attempt/agent a)) (= :running (:seon.attempt/status a)))
                  {:attempt (:seon.attempt/id a) :task (:seon.attempt/task a) :env (:seon.attempt/env a)}))
        (vals (task/attempts @(:conn sys)))))

(def ops
  "The server's ops, the one table, keyed by a request's :op (an EDN request is a
  keyword-keyed map, {:op :agents}; a line opening `{\"` is MCP's, respond): each op's
  description, its arguments (JSON Schema properties; :required, the ones it needs) and its
  answer, a fn of root's system, the agent the request names (:root, an agent's keyword, or
  nil) and the request. MCP lists each as the tool seon_<op> (mcp-reply)."
  (array-map
   :agents {:doc "Live agents (id, branch, status, task) and every task (execution, done, blocked-by, its attempts as [id status outcome turns tokens], problem)."
            :args {} :answer (fn [sys _ _] (agents-answer sys))}
   :eval {:doc "Evaluate Clojure in a running agent's process (recorded on its branch, origin :outside) or as \"root\" in root's process (root.repl: `sys` is root; my.root/run!, my.root/cancel!, seon.root/merge! ...), each through the one REPL path. Answers one reply item per form."
          :args {:agent {:type "string" :description "an agent id (a3) or root"} :code {:type "string" :description "Clojure forms"}}
          :required ["agent" "code"]
          :answer (fn [sys ag req]
                    {:items (mapv #(select-keys % [:tag :form :printed :out :recorded :handle])
                                  (if (= :root ag) (root-eval sys (:code req)) @(c/submit! sys ag (:code req) {:origin :outside})))})}
   ;; the block's text as the model reads it: printed to the agent's output, not as a
   ;; value (a value comes back as a string literal, escaped, under the reply's budget);
   ;; recorded with origin :context, a context read, which renders refer to, not repeat
   :context {:doc "The current-state block of the task an agent works: what the model would read now."
             :args {:agent {:type "string"}} :required ["agent"]
             :answer (fn [sys ag _]
                       (if-let [{:keys [task attempt]} (attempt-of sys ag)]
                         (let [it (last @(c/submit! sys ag (pr-str (list 'print (list 'seon.turn/context-block task attempt))) {:origin :context}))]
                           (if (= :err (:tag it)) (:err it) {:context (:out it)}))
                         (refused (str ag " works no task: attach one first"))))}
   :attach {:doc "Work a task as a Seon agent: root starts an agent on the task's branch (model outside) and answers its id."
            :args {:task {:type "string" :description "ns/name"}} :required ["task"]
            :answer (fn [sys _ req] (attach! sys (:task req)))}
   :done {:doc "Hand the attempt the agent works back to root: its proofs run, it is judged, and merged when the done rule has no refusal."
          :args {:agent {:type "string"}} :required ["agent"]
          :answer (fn [sys ag _]
                    (if-let [{:keys [attempt task env]} (attempt-of sys ag)]
                      (do (c/judge! sys {:attempt attempt :task task :env env :agent ag})
                          {:judging attempt})
                      (refused (str ag " works no task"))))}))

(defn answer
  "One request's answer: its op's."
  {:malli/schema [:=> [:cat :seon.root/sys :map] :map]}
  [sys req]
  (if-let [v (ops (:op req))]
    ((:answer v) sys (some-> (:agent req) (#(if (= "root" %) :root (keyword %)))) req)
    (refused (str "unknown op " (:op req)))))

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
  [sys {:strs [id method params]}]
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
                         (let [a (answer sys (into {:op op} (for [[k v] (get params "arguments")] [(keyword k) v])))]
                           ;; what the model reads: the block itself, an evaluation's items as the
                           ;; REPL's own text, an error by its render, anything else by the printer
                           (result {"content" [{"type" "text" "text" (cond (and (= :context op) (:context a)) (:context a)
                                                                           (:items a) (rai/items (:items a))
                                                                           (contract/error? a) (render/ai a {})
                                                                           :else (print/text a (:context print/budgets)))}]}))))
        (rpc-error id -32601 (str "no method " method ": this server answers initialize, ping, tools/list and tools/call"))))))

(defn- respond
  "The answer line to one request line, or nil (an MCP notification). The protocol rule: a
  line opening `{\"` is MCP's JSON-RPC (mcp-reply, JSON out), every other line is EDN (answer,
  EDN out), so an EDN request is a keyword-keyed map. The entrant boundary: a defect (a line
  that does not read, an op that throws) answers that request as the typed
  :seon.entrance/defect, for MCP inside a JSON-RPC error with the request's id (-32700 for
  a line that is not JSON, naming the rule)."
  {:malli/schema [:=> [:cat [:maybe :seon.root/sys] :string] [:maybe :string]]}
  [sys line]
  (let [mcp? (str/starts-with? (str/triml line) "{\"")
        msg (volatile! nil)]
    (try (if mcp?
           (some-> (mcp-reply sys (vreset! msg (json/read-str line))) json/write-str)
           (binding [*print-length* nil *print-level* nil] (pr-str (answer sys (edn/read-string line)))))
         (catch Throwable e
           (let [cause (or (ex-message e) (str (type e)))
                 d (contract/error :seon.entrance/defect 'seon.entrance/answer
                                   (if (and mcp? (nil? @msg)) (str "a line opening {\" is MCP's JSON-RPC, and an EDN request a keyword-keyed map; this one is not JSON: " cause) cause)
                                   (cond-> {:line line} (ex-data e) (assoc :data (print/text (ex-data e) (:part print/budgets)))))]
             (if mcp?
               (json/write-str (rpc-error (get @msg "id") (if @msg -32603 -32700) (render/ai d {})))
               (pr-str d)))))))

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
                                                (doto (Thread. ^Runnable (fn [] (serve-connection! sys s)) "seon-entrance-conn")
                                                  (.setDaemon true) (.start))
                                                (recur))))
                   "seon-entrance-accept")
      (.setDaemon true) (.start))
    ss))
