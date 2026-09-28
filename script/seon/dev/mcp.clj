(ns seon.dev.mcp
  "The MCP stdio server as pure transport. Tool names, descriptions, schemas
  and every call's result come from the cluster JVM's entrance through two
  forms: `((requiring-resolve 'seon.mcp/tools))` and
  `((requiring-resolve 'seon.mcp/call) \"<request json>\")`, each answering a
  JSON string. The bridge names no other JVM symbol, so a bridge from an old
  commit keeps working across JVM restarts at newer ones; when the JVM it
  listed tools from is replaced, it re-reads them and sends
  `notifications/tools/list_changed`."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.operator :as operator])
  (:import [java.io BufferedReader]
           [java.nio.file Files StandardCopyOption]))

;;; ---------------------------------------------------------------------------
;;; The world: discovery and prepl are values, so tests pass fakes.
;;; ---------------------------------------------------------------------------

(def entrance
  "The JVM namespace holding `tools` and `call`."
  'seon.mcp)

(def default-timeout-ms 30000)

(def grace-ms
  "Added to a call's own `timeout_ms`, so an entrance that honours the bound
  answers before the bridge gives up on the connection."
  2000)

(defn live-world
  "The operator discovery and prepl the bridge runs against, under `root`."
  [root]
  {::root root
   ::advertisements operator/advertisements
   ::prepl (fn [advertisement form bound-ms]
             (operator/prepl-value! advertisement form bound-ms nil bound-ms))})

(defn- jvm-identity [advertisement]
  (select-keys advertisement [:seon.boot/pid :seon.boot/start-instant]))

(defn- route
  "The cluster a request names (else the only live one, else \"default\") and
  its live advertisement, if any."
  [{::keys [root advertisements]} cluster]
  (let [live (advertisements root)
        cluster (or cluster
                    (when (= 1 (count live)) (:seon.boot/cluster-name (first live)))
                    "default")]
    {::cluster cluster
     ::advertisement (some #(when (= cluster (:seon.boot/cluster-name %)) %) live)}))

(defn- jvm-string!
  "Evaluate `form` on the advertised JVM; its value must be a string."
  [{::keys [prepl]} advertisement form bound-ms]
  (let [value (prepl advertisement form bound-ms)]
    (if (string? value)
      value
      (throw (ex-info (str "The entrance answered " (type value) ", not a JSON string.") {})))))

(defn- failure-text
  "What went wrong: the JVM's own cause when the prepl answered an exception
  (`#error` reads as a tagged literal), else the bridge's message."
  [failure]
  (let [value (get-in (ex-data failure) [:seon.error/offending :val])
        value (if (tagged-literal? value) (:form value) value)]
    (or (when (map? value) (some #(when (string? %) %) [(:seon.error/message value) (:cause value)]))
        (ex-message failure)
        (str (class failure)))))

(defn- tools-form [] (str "((requiring-resolve '" entrance "/tools))"))

(defn- call-form [request-json]
  ;; pr-str writes the request as one EDN string literal on the wire.
  (str "((requiring-resolve '" entrance "/call) "
       #_{:clj-kondo/ignore [:discouraged-var]} (pr-str request-json) ")"))

;;; ---------------------------------------------------------------------------
;;; Tool list: fetched from the JVM, cached beside the cluster's advertisement.
;;; ---------------------------------------------------------------------------

(def static-tools
  "The list served with no live JVM and no cached list: enough to call a tool
  and read the start remedy."
  (json/generate-string
   (for [tool ["eval_clj" "runtime_status"]]
     {:name tool
      :description "No live Seon JVM answered yet; a call returns how to start one."
      :inputSchema {:type "object"
                    :properties {:code {:type "string"} :cluster {:type "string"}}}})))

(defn- cache-file [root cluster]
  (io/file root "data" "clusters" cluster "mcp-tools.json"))

(defn- write-cache! [root cluster tools-json]
  (let [target (cache-file root cluster)
        staging (io/file (str target ".tmp"))]
    (io/make-parents target)
    (spit staging tools-json)
    (Files/move (.toPath staging) (.toPath target)
                (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))

(defn initial-state
  "Before any JVM answers: the home cluster's cached list, else the static one."
  [{::keys [root]}]
  (let [cached (cache-file root "default")]
    {::identity nil
     ::tools (if (.isFile cached) (slurp cached) static-tools)}))

(defn- log
  "One diagnostic line on stderr: the transport's own text, never a value."
  [& parts]
  (binding [*out* *err*]
    #_{:clj-kondo/ignore [:discouraged-var]} (apply println "[seon mcp]" parts)
    (flush)))

(defn sync-tools
  "`[state changed?]` after checking the home JVM against the tools' source.

  A different live JVM's list is fetched, cached and served; changed? means
  the served list differs."
  [{::keys [root] :as world} state]
  (let [{::keys [cluster advertisement]} (route world nil)
        jvm (some-> advertisement jvm-identity)]
    (if (or (nil? jvm) (= jvm (::identity state)))
      [state false]
      (try
        (let [tools (jvm-string! world advertisement (tools-form) default-timeout-ms)]
          (json/parse-string tools)
          (try (write-cache! root cluster tools)
               (catch Exception failure (log "tool cache not written:" (ex-message failure))))
          [(assoc state ::identity jvm ::tools tools) (not= tools (::tools state))])
        (catch Exception failure
          ;; The identity stays unrecorded, so the next request fetches again.
          (log "tools not fetched from" cluster ":" (failure-text failure))
          [state false])))))

;;; ---------------------------------------------------------------------------
;;; tools/call
;;; ---------------------------------------------------------------------------

(defn- error-result [text]
  {"content" [{"type" "text" "text" text}] "isError" true})

(defn- start-remedy [root cluster]
  (str "No live Seon JVM for cluster " cluster " under " root
       ": run bin/seon --root " root " start " cluster "."))

(defn- connect-refused? [failure]
  (some #(instance? java.net.ConnectException %) (take-while some? (iterate ex-cause failure))))

(defn call-tool
  "One tools/call forwarded unchanged to the routed JVM's entrance.

  Every failure is a CallToolResult with isError, never an exception."
  [world {tool "name" arguments "arguments" :as params}]
  (try
    (let [world (cond-> world (string? (get arguments "root"))
                  (assoc ::root (operator/canonical-root (get arguments "root"))))
          root (::root world)
          form (call-form (json/generate-string params))
          timeout (get arguments "timeout_ms")
          bound (+ grace-ms (if (pos-int? timeout) timeout default-timeout-ms))
          attempt (fn []
                    (let [{::keys [cluster advertisement]} (route world (get arguments "cluster"))]
                      (if advertisement
                        {::cluster cluster ::advertisement advertisement
                         ::value (jvm-string! world advertisement form bound)}
                        {::cluster cluster})))
          ;; A refused connection sent nothing: the JVM was replaced between
          ;; discovery and connect, so discover again once.
          outcome (try (attempt)
                       (catch Exception failure
                         (if (connect-refused? failure) (attempt) (throw failure))))]
      (if-let [value (::value outcome)]
        (json/parse-string value)
        (error-result (start-remedy root (::cluster outcome)))))
    (catch Exception failure
      (let [{timed-out :seon.operator/timeout-ms cluster :seon.boot/cluster-name pid :seon.boot/pid}
            (:seon.error/offending (ex-data failure))]
        (error-result
         (if timed-out
           (str "Tool " tool " on cluster " cluster " (JVM pid " pid ")"
                " gave no result within " timed-out " ms; its connection is closed and the"
                " evaluation's outcome in the JVM is unknown. Retry with a larger timeout_ms,"
                " or inspect the JVM with runtime_status.")
           (str "Tool " tool " failed in the MCP bridge (" (failure-text failure) ")."
                " Check the JVM with bin/seon status; start it with bin/seon start.")))))))

;;; ---------------------------------------------------------------------------
;;; JSON-RPC
;;; ---------------------------------------------------------------------------

(def list-changed {"jsonrpc" "2.0" "method" "notifications/tools/list_changed"})

(defn handle
  "One JSON-RPC message answered as `{::state state ::out [messages]}`.

  Pure but for the world's discovery and prepl."
  [world state {:strs [id method params]}]
  (let [reply (fn [state result & before]
                {::state state ::out (conj (vec before) {"jsonrpc" "2.0" "id" id "result" result})})]
    (case method
      "initialize"
      (reply state {"protocolVersion" (get params "protocolVersion" "2024-11-05")
                    "capabilities" {"tools" {"listChanged" true}}
                    "serverInfo" {"name" "seon" "version" "0.5.0"}})

      "ping" (reply state {})

      "tools/list"
      (let [[state _] (sync-tools world state)]
        (reply state {"tools" (json/parse-string (::tools state))}))

      "tools/call"
      (let [[state changed?] (sync-tools world state)
            result (call-tool world params)]
        (if changed? (reply state result list-changed) (reply state result)))

      {::state state
       ::out (if (some? id)
               [{"jsonrpc" "2.0" "id" id "error" {"code" -32601 "message" (str "Method not found: " method)}}]
               [])})))

(defn handle-line
  "`handle` for one input line; an unparseable line answers a parse error."
  [world state line]
  (try
    (handle world state (json/parse-string line))
    (catch Exception failure
      {::state state
       ::out [{"jsonrpc" "2.0" "id" nil
               "error" {"code" -32700 "message" (str "Parse error: " (ex-message failure))}}]})))

(defn- exit-with-parent!
  "End this process when the client that launched it exits."
  []
  (let [parent (.parent (java.lang.ProcessHandle/current))]
    (when (.isPresent parent)
      (.thenRun (.onExit ^java.lang.ProcessHandle (.get parent))
                (reify Runnable (run [_] (System/exit 0)))))))

(defn -main
  "Serve newline-delimited MCP JSON-RPC on standard I/O."
  [& _]
  (exit-with-parent!)
  (let [world (live-world (.getCanonicalPath (io/file (or (System/getenv "SEON_PROJECT_ROOT")
                                                      (System/getProperty "user.dir")))))
        reader (BufferedReader. *in*)]
    (log "serving; root" (::root world))
    (loop [state (initial-state world)]
      (when-let [line (.readLine reader)]
        (if (str/blank? line)
          (recur state)
          (let [{::keys [state out]} (handle-line world state line)]
            (doseq [message out]
              ;; The transport's own JSON-RPC line, one per message.
              #_{:clj-kondo/ignore [:discouraged-var]} (println (json/generate-string message)))
            (flush)
            (recur state)))))))
