(ns seon.mcp
  "A thin MCP server for outside agents (owner, 2026-10-20: \"minimal MCP wrapping so they can
  repl in and run statements against any agent or as root\"): JSON-RPC 2.0 over stdio, each
  tool exactly one request to root's entrance (seon.entrance, line EDN on 127.0.0.1), its
  answer the tool's text. No logic lives here.

    target/seon mcp PORT        (root runs the entrance on PORT)"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.data.json :as json] [clojure.edn :as edn]))

(def tools
  "Each tool: its name, what it does, its arguments (JSON Schema) and the entrance request it is."
  [{:name "seon_agents" :description "Live agents (id, branch, status, task) and every task (execution, done, blocked-by, problem)."
    :schema {:type "object" :properties {}} :op :agents}
   {:name "seon_eval" :description "Evaluate Clojure in a running agent's process (recorded on its branch, origin :outside) or as \"root\" in root's process (root.repl: `sys` is root; my.root/run!, my.root/cancel!, seon.root/merge! ...). Answers one reply item per form."
    :schema {:type "object" :properties {:agent {:type "string" :description "an agent id (a3) or root"}
                                         :code {:type "string" :description "Clojure forms"}}
             :required ["agent" "code"]}
    :op :eval}
   {:name "seon_context" :description "The current-state block of the task an agent works: what the model would read now."
    :schema {:type "object" :properties {:agent {:type "string"}} :required ["agent"]} :op :context}
   {:name "seon_attach" :description "Work a task as a Seon agent: root starts an agent on the task's branch (model outside) and answers its id."
    :schema {:type "object" :properties {:task {:type "string" :description "ns/name"}} :required ["task"]} :op :attach}
   {:name "seon_done" :description "Hand the attempt the agent works back to root: its proofs run, it is judged, and merged when the done rule has no refusal."
    :schema {:type "object" :properties {:agent {:type "string"}} :required ["agent"]} :op :done}])

(defn- request!
  "One entrance request on 127.0.0.1:port, its answer as text. The interop edge: a refused
  connection is the tool's error text."
  {:malli/schema [:=> [:cat pos-int? :map] :string]}
  [port req]
  (try
    (let [s (java.net.Socket. "127.0.0.1" (int port))
          out (.getOutputStream s)
          in (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))]
      (.write out (.getBytes (str (pr-str req) "\n") "UTF-8"))
      (.flush out)
      (let [line (.readLine in)] (.close s) (or line "{:error \"no answer\"}")))
    (catch Throwable e (pr-str {:error (str "root's entrance on port " port ": " (or (ex-message e) (str (type e))))}))))

(defn reply
  "The JSON-RPC answer to one message, or nil for a notification."
  {:malli/schema [:=> [:cat pos-int? :map] [:maybe :map]]}
  [port {:strs [id method params]}]
  (when id
    {"jsonrpc" "2.0" "id" id
     "result" (case method
                "initialize" {"protocolVersion" (get params "protocolVersion" "2024-11-05")
                              "capabilities" {"tools" {}} "serverInfo" {"name" "seon" "version" "next"}}
                "tools/list" {"tools" (mapv (fn [t] {"name" (:name t) "description" (:description t) "inputSchema" (:schema t)}) tools)}
                "tools/call" (let [t (first (filter #(= (get params "name") (:name %)) tools))
                                   args (into {} (for [[k v] (get params "arguments")] [(keyword k) v]))]
                               {"content" [{"type" "text" "text" (if t (request! port (assoc args :op (:op t))) (str "no tool " (get params "name")))}]})
                {})}))

(defn serve!
  "The stdio loop: one JSON-RPC message per line in, its answer per line out, until EOF."
  {:malli/schema [:=> [:cat pos-int?] :any]}
  [port]
  (loop []
    (when-let [line (read-line)]
      (when-let [r (reply port (json/read-str line))]
        (println (json/write-str r))
        (flush))
      (recur))))
