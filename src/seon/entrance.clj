(ns seon.entrance
  "The entrance for outside agents (Claude Code through MCP, a person): a line-EDN socket on
  127.0.0.1, one request per line in, one answer per line out (R1's proto.agents.entrance,
  on the flow core). An outside agent evaluates in any running agent's process (recorded on
  that agent's branch, origin :outside) or as root (in root's process, with root's verbs,
  recorded on main), and can work a task as a Seon agent:

    {:op :agents}                         live agents and every task
    {:op :eval :agent \"a3\"|\"root\" :code s} the reply items
    {:op :context :agent \"a3\"}            the agent's current-state block (its task's), as the model sees it
    {:op :attach :task \"ns/name\"}          work the task here: an agent on its branch, model \"outside\"
    {:op :done :agent \"a3\"}               hand the attempt back: root judges it, as a turn loop's end

  The listener and each connection are edge threads (blocking accept and reads); a
  connection is an outside caller of root's API, as bin/check is. Try it:
  printf '{:op :agents}\\n' | nc -w 5 127.0.0.1 PORT"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [seon.root :as c] [seon.task :as task] [seon.reconcile :as r]
            [seon.lib :as lib] [datahike.api :as d] [clojure.edn :as edn] [clojure.string :as str]))

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

(defn- root-eval
  "Evaluate code in root's process, form by form, in the namespace `root.repl` where `sys`
  is this root (my.root's verbs take it), recorded on main as root's evaluations, origin
  :outside. Answers the reply items."
  {:malli/schema [:=> [:cat :seon.root/sys :string] [:vector :map]]}
  [sys code]
  (let [nsx (or (find-ns 'root.repl)
                (let [n (create-ns 'root.repl)]
                  (binding [*ns* n] (refer-clojure) (require '[my.root :as root] '[seon.root :as c] '[datahike.api :as d]))
                  n))
        _ (intern nsx 'sys sys)
        rd (java.io.PushbackReader. (java.io.StringReader. code))
        items (loop [acc [] n 0]
                (let [form (read {:eof ::eof} rd)]
                  (if (= ::eof form)
                    acc
                    (let [t0 (lib/now-ns)
                          v (binding [*ns* nsx] (eval form))]
                      (recur (conj acc {:id n :tag :ret :form (pr-str form) :printed (pr-str v) :ms (lib/ms-since t0)}) (inc n))))))
        run (str "root-outside-" (lib/now-ns))]
    @(c/transact-main! sys (conj (vec (for [it items]
                                        {:seon.cluster.eval/id (str run "/" (:id it)) :seon.cluster.eval/agent "agent"
                                         :seon.cluster.eval/record (pr-str (assoc (select-keys it [:tag :form :ms]) :shown (:printed it) :origin :outside))}))
                                 {:db/id "agent" :seon.agent/id :root}))
    items))

(defn- attach!
  "Start an outside attempt of task id: its desired model is \"outside\", so the
  reconciler starts no turn loop for it and does not kill it. Answers {:agent :task :attempt}."
  {:malli/schema [:=> [:cat :seon.root/sys :seon.task/id] :map]}
  [sys id]
  (if-not (task/task @(:conn sys) id)
    {:error (str "no task " id)}
    (do @(c/transact-main! sys (into (task/env-tx id {:model "outside"}) (task/execution-tx [id] :running)))
        (let [db @(:conn sys)
              env (r/desired-env (task/task db id))
              aid (str id "#" (inc (count (filter #(= id (:seon.attempt/task %)) (vals (task/attempts db))))))
              ag (c/spawn! sys {:branch (:branch env) :task id})]
          @(c/transact-main! sys (task/attempt-start-tx aid id (:boot sys) ag env (System/currentTimeMillis)))
          {:agent ag :task id :attempt aid}))))

(defn- attempt-of
  "The live attempt agent ag works, from main: {:attempt :task :env}, or nil."
  {:malli/schema [:=> [:cat :seon.root/sys :keyword] [:maybe :map]]}
  [sys ag]
  (some (fn [a] (when (and (= ag (:seon.attempt/agent a)) (= :running (:seon.attempt/status a)))
                  {:attempt (:seon.attempt/id a) :task (:seon.attempt/task a) :env (:seon.attempt/env a)}))
        (vals (task/attempts @(:conn sys)))))

(defn answer
  "One request's answer."
  {:malli/schema [:=> [:cat :seon.root/sys :map] :map]}
  [sys req]
  (let [ag (some-> (:agent req) (#(if (= "root" %) :root (keyword %))))]
    (case (:op req)
      :agents (agents-answer sys)
      :eval (if (= :root ag)
              {:items (root-eval sys (:code req))}
              {:items (mapv #(select-keys % [:tag :form :printed :out :recorded :handle])
                            @(c/submit! sys ag (:code req) {:origin :outside}))})
      ;; the block's text as the model reads it: printed to the agent's output, not as a
      ;; value (a value comes back as a string literal, escaped, under the reply's budget);
      ;; recorded with origin :context, a context read, which renders refer to, not repeat
      :context (if-let [{:keys [task attempt]} (attempt-of sys ag)]
                 (let [it (last @(c/submit! sys ag (pr-str (list 'print (list 'seon.turn/context-block task attempt))) {:origin :context}))]
                   (if (= :err (:tag it)) {:error (:printed it)} {:context (:out it)}))
                 {:error (str ag " works no task: attach one first")})
      :attach (attach! sys (:task req))
      :done (if-let [{:keys [attempt task env]} (attempt-of sys ag)]
              (do (c/judge! sys {:attempt attempt :task task :env env :agent ag})
                  {:judging attempt})
              {:error (str ag " works no task")})
      {:error (str "unknown op " (:op req))})))

(defn- serve-connection!
  "Answer one connection's requests, line by line, until it closes. The entrant boundary:
  a defect answers as data and the connection goes on."
  {:malli/schema [:=> [:cat :seon.root/sys :any] :any]}
  [sys sock]
  (let [r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock)))
        out (.getOutputStream sock)]
    (loop []
      (when-let [line (.readLine r)]
        (let [a (try (answer sys (edn/read-string line))
                     (catch Throwable e {:error (or (ex-message e) (str (type e))) :data (ex-data e)}))]
          (.write out (.getBytes (str (binding [*print-length* nil *print-level* nil] (pr-str a)) "\n") "UTF-8"))
          (.flush out)
          (recur))))
    (.close sock)))

(defn serve!
  "Listen on 127.0.0.1:port: the accept loop and each connection on edge threads (blocking
  accept and reads). Answers the server socket."
  {:malli/schema [:=> [:cat :seon.root/sys pos-int?] :any]}
  [sys port]
  (let [ss (java.net.ServerSocket. port 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (doto (Thread. (fn [] (loop [] (let [s (.accept ss)]
                                     (doto (Thread. (fn [] (serve-connection! sys s))) (.setDaemon true) (.start))
                                     (recur)))))
      (.setDaemon true) (.start))
    ss))
