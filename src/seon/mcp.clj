(ns seon.mcp
  "A thin MCP server for outside agents (owner, 2026-10-20: \"minimal MCP wrapping so they can
  repl in and run statements against any agent or as root\"): JSON-RPC 2.0 over stdio, each
  tool exactly one request to root's entrance (seon.entrance, line EDN on 127.0.0.1), its
  answer the tool's text: the tools are the entrance's verbs (seon.entrance/verbs), each
  seon_<op>. No logic lives here.

    target/seon mcp PORT        (root runs the entrance on PORT)"
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :as contract :refer [defn defn-]] [seon.step :as step] [seon.entrance :as entrance] [clojure.data.json :as json] [clojure.edn :as edn] [clojure.string :as str]))

(defn- unreachable
  "The tool's text when root's entrance on port answered nothing: the one error map."
  {:malli/schema [:=> [:cat pos-int? :string] :string]}
  [port why] (pr-str (contract/error :seon.root/refused 'seon.mcp/request! (str "root's entrance on port " port ": " why))))

(defn- request!
  "One entrance request on 127.0.0.1:port, its answer as text. The interop edge: a refused
  connection, or no answer, is the tool's text: the one error map."
  {:malli/schema [:=> [:cat pos-int? :map] :string]}
  [port req]
  (try
    (let [s (java.net.Socket. "127.0.0.1" (int port))
          out (.getOutputStream s)
          in (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))]
      (.write out (.getBytes (str (pr-str req) "\n") "UTF-8"))
      (.flush out)
      (let [line (.readLine in)] (.close s) (or line (unreachable port "it closed the connection with no answer"))))
    (catch Throwable e (unreachable port (step/error-msg e)))))

(defn- tool-text
  "What a tool call answers: the context's text itself for seon_context (the model's own
  view, not an escaped string inside a map), else the entrance's answer line."
  {:malli/schema [:=> [:cat :keyword :string] :string]}
  [op line]
  (or (when (= :context op) (:context (edn/read-string line))) line))

(defn reply
  "The JSON-RPC answer to one message, or nil for a notification."
  {:malli/schema [:=> [:cat pos-int? :map] [:maybe :map]]}
  [port {:strs [id method params]}]
  (when id
    {"jsonrpc" "2.0" "id" id
     "result" (case method
                "initialize" {"protocolVersion" (get params "protocolVersion" "2024-11-05")
                              "capabilities" {"tools" {}} "serverInfo" {"name" "seon" "version" "next"}}
                "tools/list" {"tools" (vec (for [[op v] entrance/verbs]
                                             {"name" (str "seon_" (name op)) "description" (:doc v)
                                              "inputSchema" (cond-> {:type "object" :properties (:args v)} (:required v) (assoc :required (:required v)))}))}
                "tools/call" (let [n (get params "name")
                                   op (when (str/starts-with? n "seon_") (keyword (subs n 5)))
                                   args (into {} (for [[k v] (get params "arguments")] [(keyword k) v]))]
                               {"content" [{"type" "text" "text" (if (entrance/verbs op) (tool-text op (request! port (assoc args :op op))) (str "no tool " n))}]})
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
