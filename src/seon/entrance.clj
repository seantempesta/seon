(ns seon.entrance
  "The entrance for outside agents (Claude Code through MCP, a person): a line-EDN socket on
  127.0.0.1, one request per line in, one answer per line out (R1's proto.agents.entrance,
  on the flow core). An outside agent evaluates in any running agent's process (recorded on
  that agent's branch, origin :outside) or as root (in root's process, with root's verbs,
  through the same REPL path, recorded on main), and can work a task as a Seon agent. Its
  verbs are one table (`verbs`: {:op k ...args}), which MCP lists as its tools.

  The listener and each connection are edge threads (blocking accept and reads); a
  connection is an outside caller of root's API, as bin/check is. Try it:
  printf '{:op :agents}\\n' | nc -w 5 127.0.0.1 PORT"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.root :as c] [seon.task :as task] [seon.reconcile :as r]
            [seon.lib :as lib] [seon.repl :as repl] [clojure.edn :as edn]))

(defn- agents-answer
  "Live agents (id, branch, status, task) and every task (execution, done, blocked-by, problem)."
  {:malli/schema [:=> [:cat :seon.root/sys] :map]}
  [sys]
  (let [db @(:conn sys) idx (r/index db)]
    {:agents (into (sorted-map) (for [[id a] (:agents (c/state sys)) :when (not (#{:dead :killed :ended} (:status a)))]
                                  [id (select-keys a [:branch :status :task])]))
     :tasks (into (sorted-map) (for [[id t] (task/tasks db)]
                                 [id (cond-> {:execution (:seon.task/execution t) :done (r/done? idx id) :blocked-by (r/blocked-by idx id)}
                                       (:seon.task/problem t) (assoc :problem (:seon.task/problem t)))]))}))

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
    @(c/transact-main! sys (repl/record-tx :root (str "root-outside-" (lib/now-ns)) items nil))
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
      {:error (str "no task " id)}
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

(def verbs
  "The entrance's verbs, the one table: each op's description, its arguments (JSON Schema
  properties; :required, the ones it needs) and its answer, a fn of root's system, the
  agent the request names (:root, an agent's keyword, or nil) and the request. MCP lists
  each as the tool seon_<op> (seon.mcp)."
  (array-map
   :agents {:doc "Live agents (id, branch, status, task) and every task (execution, done, blocked-by, problem)."
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
                           (if (= :err (:tag it)) {:error (:printed it)} {:context (:out it)}))
                         {:error (str ag " works no task: attach one first")}))}
   :attach {:doc "Work a task as a Seon agent: root starts an agent on the task's branch (model outside) and answers its id."
            :args {:task {:type "string" :description "ns/name"}} :required ["task"]
            :answer (fn [sys _ req] (attach! sys (:task req)))}
   :done {:doc "Hand the attempt the agent works back to root: its proofs run, it is judged, and merged when the done rule has no refusal."
          :args {:agent {:type "string"}} :required ["agent"]
          :answer (fn [sys ag _]
                    (if-let [{:keys [attempt task env]} (attempt-of sys ag)]
                      (do (c/judge! sys {:attempt attempt :task task :env env :agent ag})
                          {:judging attempt})
                      {:error (str ag " works no task")}))}))

(defn answer
  "One request's answer: its verb's."
  {:malli/schema [:=> [:cat :seon.root/sys :map] :map]}
  [sys req]
  (if-let [v (verbs (:op req))]
    ((:answer v) sys (some-> (:agent req) (#(if (= "root" %) :root (keyword %)))) req)
    {:error (str "unknown op " (:op req))}))

(defn- serve-connection!
  "Answer one connection's requests, line by line, until it closes. The entrant boundary:
  a defect (a line that does not read, a verb that throws) answers that request as the typed
  :seon.entrance/defect, and the connection goes on; every other connection is its own
  thread and never sees it."
  {:malli/schema [:=> [:cat :seon.root/sys :any] :any]}
  [sys sock]
  (let [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock)))
        out (.getOutputStream sock)]
    (loop []
      (when-let [line (.readLine r)]
        (let [a (try (answer sys (edn/read-string line))
                     (catch Throwable e
                       (contract/error :seon.entrance/defect 'seon.entrance/answer (or (ex-message e) (str (type e)))
                                       (cond-> {:line line} (ex-data e) (assoc :data (pr-str (ex-data e)))))))]
          (.write out (.getBytes (str (binding [*print-length* nil *print-level* nil] (pr-str a)) "\n") "UTF-8"))
          (.flush out)
          (recur))))
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
