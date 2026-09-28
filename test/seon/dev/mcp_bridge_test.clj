(ns seon.dev.mcp-bridge-test
  "The MCP bridge as pure transport, driven through an explicit world value:
  discovery and prepl are fakes (or a silent socket), never a JVM."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.dev.mcp :as mcp])
  (:import [java.net ServerSocket]
           [java.util.concurrent TimeUnit]))

(def ^:private source-file (io/file "script/seon/dev/mcp.clj"))

(def ^:private live-advertisement
  {:seon.boot/cluster-name "default" :seon.boot/pid 11
   :seon.boot/start-instant #inst "2026-09-28T00:00:00Z"
   :seon.boot/prepl-host "127.0.0.1" :seon.boot/prepl-port 1})

(def ^:private jvm-tools
  (json/generate-string [{:name "eval_clj" :description "from the JVM" :inputSchema {:type "object"}}]))

(def ^:private empty-result "{\"content\":[],\"isError\":false}")

(defn- temp-root
  "A fresh operator root directory."
  {:malli/schema [:=> [:cat] :string]}
  []
  (str (java.nio.file.Files/createTempDirectory
        "mcp-bridge" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- delete-root!
  "Delete a root `temp-root` made, never following symlinks."
  {:malli/schema [:=> [:cat :string] :nil]}
  [root]
  (doseq [file (reverse (file-seq (io/file root)))
          :when (not (java.nio.file.Files/isSymbolicLink (.toPath ^java.io.File file)))]
    (.delete ^java.io.File file))
  nil)

(defn- fake-world
  "A world under `root` advertising `advertisements`, whose JVM answers
  `jvm-tools` to the tool list and `call-result` to a call; each form it
  receives is appended to the volatile at `::sent`."
  {:malli/schema [:=> [:cat :string [:vector :map] [:maybe :string]] :map]}
  [root advertisements call-result]
  (let [sent (volatile! [])]
    {::sent sent
     ::mcp/root root
     ::mcp/advertisements (fn [_] advertisements)
     ::mcp/prepl (fn [_ form _]
                   (vswap! sent conj form)
                   (if (str/includes? form "/tools)") jvm-tools call-result))}))

(defn- source-symbols
  "Every symbol in the bridge source, with the `operator` alias expanded."
  {:malli/schema [:=> [:cat] [:set :symbol]]}
  []
  (with-open [reader (java.io.PushbackReader. (io/reader source-file))]
    (binding [*ns* (the-ns 'seon.dev.mcp)]
      (->> (repeatedly #(read {:eof ::eof :read-cond :allow} reader))
           (take-while #(not= ::eof %))
           (mapcat #(filter symbol? (tree-seq coll? seq %)))
           (map #(if (= "operator" (namespace %)) (symbol "seon.operator" (name %)) %))
           set))))

;;; ---------------------------------------------------------------------------
;;; The static boundary
;;; ---------------------------------------------------------------------------

(def ^:private allowed-seon-symbols
  "Every seon.* name the bridge source may contain: its own namespace, the
  entrance namespace whose `tools` and `call` it sends, and the operator
  discovery functions it calls on the babashka side."
  #{'seon.dev.mcp 'seon.mcp 'seon.operator
    'seon.operator/advertisements 'seon.operator/prepl-value! 'seon.operator/canonical-root})

(deftest the-bridge-names-only-the-entrance-and-operator-discovery
  (is (= #{}
         (into #{} (remove allowed-seon-symbols)
               (filter #(str/starts-with? (str %) "seon.") (source-symbols))))
      "The bridge source names no seon symbol beyond the entrance and operator discovery."))

(deftest the-bridge-sends-exactly-the-two-entrance-forms
  (is (= "((requiring-resolve 'seon.mcp/tools))" (#'mcp/tools-form))
      "The tool list is asked of seon.mcp/tools.")
  (is (= '((requiring-resolve 'seon.mcp/call) "{\"name\":\"x\"}")
         (read-string (#'mcp/call-form "{\"name\":\"x\"}")))
      "A call hands the request JSON string to seon.mcp/call."))

(deftest babashka-loads-the-bridge-from-the-launcher-classpath
  (let [process (.start (doto (ProcessBuilder.
                               ^java.util.List ["bb" "--config" "/dev/null" "--classpath" "script:src:resources"
                                                "-e" "(require 'seon.dev.mcp) (println :bridge-loaded)"])
                          (.redirectErrorStream true)))
        completed? (.waitFor process 10 TimeUnit/SECONDS)]
    (when-not completed? (.destroyForcibly process))
    (is completed? "Babashka loads the bridge within ten seconds.")
    (is (str/includes? (slurp (.getInputStream process)) ":bridge-loaded")
        "The bridge namespace loads on the launcher's classpath.")))

;;; ---------------------------------------------------------------------------
;;; JSON-RPC
;;; ---------------------------------------------------------------------------

(deftest initialize-advertises-list-changed
  (let [root (temp-root)
        world (fake-world root [] nil)
        {out ::mcp/out} (mcp/handle world (mcp/initial-state world)
                                    {"jsonrpc" "2.0" "id" 1 "method" "initialize" "params" {}})]
    (delete-root! root)
    (is (true? (get-in (first out) ["result" "capabilities" "tools" "listChanged"]))
        "The server tells the client its tool list can change.")))

(deftest an-unparseable-line-answers-a-parse-error
  (let [root (temp-root)
        world (fake-world root [] nil)
        {out ::mcp/out} (mcp/handle-line world (mcp/initial-state world) "not json")]
    (delete-root! root)
    (is (= -32700 (get-in (first out) ["error" "code"])) "A malformed line is a JSON-RPC parse error.")))

(deftest a-notification-answers-nothing
  (let [root (temp-root)
        world (fake-world root [] nil)
        {out ::mcp/out} (mcp/handle world (mcp/initial-state world)
                                    {"jsonrpc" "2.0" "method" "notifications/initialized"})]
    (delete-root! root)
    (is (= [] out) "A notification has no reply.")))

(deftest an-unknown-method-with-an-id-is-method-not-found
  (let [root (temp-root)
        world (fake-world root [] nil)
        {out ::mcp/out} (mcp/handle world (mcp/initial-state world)
                                    {"jsonrpc" "2.0" "id" 3 "method" "resources/list"})]
    (delete-root! root)
    (is (= -32601 (get-in (first out) ["error" "code"])) "An unserved method is method-not-found.")))

;;; ---------------------------------------------------------------------------
;;; Tool list
;;; ---------------------------------------------------------------------------

(deftest the-tool-list-comes-from-the-live-jvm
  (let [root (temp-root)
        world (fake-world root [live-advertisement] nil)
        {out ::mcp/out} (mcp/handle world (mcp/initial-state world)
                                    {"jsonrpc" "2.0" "id" 2 "method" "tools/list"})]
    (delete-root! root)
    (is (= (json/parse-string jvm-tools) (get-in (first out) ["result" "tools"]))
        "tools/list answers the JVM's tools exactly.")))

(deftest a-fetched-tool-list-is-cached-for-a-jvm-down-start
  (let [root (temp-root)
        live (fake-world root [live-advertisement] nil)
        _ (mcp/sync-tools live (mcp/initial-state live))
        tools (::mcp/tools (mcp/initial-state (fake-world root [] nil)))]
    (delete-root! root)
    (is (= jvm-tools tools) "With no live JVM the last fetched list is served.")))

(deftest with-no-jvm-and-no-cache-the-static-list-is-served
  (let [root (temp-root)
        tools (json/parse-string (::mcp/tools (mcp/initial-state (fake-world root [] nil))))]
    (delete-root! root)
    (is (= #{"eval_clj" "runtime_status"} (into #{} (map #(get % "name")) tools))
        "The static list names the tools a caller needs to learn the start remedy.")))

(deftest a-replaced-jvm-sends-list-changed-before-the-result
  (let [root (temp-root)
        world (fake-world root [(assoc live-advertisement :seon.boot/pid 22)] empty-result)
        state (assoc (mcp/initial-state world)
                     ::mcp/identity (select-keys live-advertisement [:seon.boot/pid :seon.boot/start-instant])
                     ::mcp/tools "[]")
        {out ::mcp/out} (mcp/handle world state {"jsonrpc" "2.0" "id" 7 "method" "tools/call"
                                                 "params" {"name" "eval_clj" "arguments" {}}})]
    (delete-root! root)
    (is (= "notifications/tools/list_changed" (get (first out) "method"))
        "A different JVM identity with a different list notifies the client first.")))

(deftest the-same-jvm-is-not-asked-for-its-tools-again
  (let [root (temp-root)
        world (fake-world root [live-advertisement] empty-result)
        [state _] (mcp/sync-tools world (mcp/initial-state world))
        _ (mcp/handle world state {"jsonrpc" "2.0" "id" 7 "method" "tools/call"
                                   "params" {"name" "eval_clj" "arguments" {}}})]
    (delete-root! root)
    (is (= 1 (count (filter #(str/includes? % "/tools)") @(::sent world))))
        "Tools are fetched once per JVM identity.")))

;;; ---------------------------------------------------------------------------
;;; tools/call
;;; ---------------------------------------------------------------------------

(deftest a-call-forwards-the-request-unchanged
  (let [root (temp-root)
        world (fake-world root [live-advertisement] empty-result)
        params {"name" "eval_clj" "arguments" {"code" "(+ 1 2)" "mode" "sci"}}
        _ (mcp/call-tool world params)
        form (read-string (last @(::sent world)))]
    (delete-root! root)
    (is (= params (json/parse-string (second form))) "The JVM receives the MCP params as given.")))

(deftest a-call-answers-the-jvms-result
  (let [root (temp-root)
        result {"content" [{"type" "text" "text" "3"}] "isError" false}
        world (fake-world root [live-advertisement] (json/generate-string result))]
    (is (= result (mcp/call-tool world {"name" "eval_clj" "arguments" {"code" "(+ 1 2)"}}))
        "The CallToolResult is the JVM's own.")
    (delete-root! root)))

(deftest a-call-with-no-live-jvm-names-the-start-remedy
  (let [root (temp-root)
        result (mcp/call-tool (fake-world root [] nil) {"name" "eval_clj" "arguments" {"cluster" "other"}})]
    (delete-root! root)
    (is (true? (get result "isError")) "A call with no JVM is an error result.")
    (is (str/includes? (get-in result ["content" 0 "text"]) "start other")
        "The text names the start command for the cluster.")))

(deftest a-call-routes-to-the-only-live-cluster
  (let [root (temp-root)
        seen (volatile! [])
        world (assoc (fake-world root [(assoc live-advertisement :seon.boot/cluster-name "solo")] nil)
                     ::mcp/prepl (fn [advertisement _ _]
                                   (vswap! seen conj (:seon.boot/cluster-name advertisement))
                                   empty-result))
        _ (mcp/call-tool world {"name" "eval_clj" "arguments" {}})]
    (delete-root! root)
    (is (= ["solo"] @seen) "Without a named cluster the only live one is used.")))

(deftest a-refused-connection-rediscovers-once
  (let [root (temp-root)
        attempts (volatile! 0)
        world (assoc (fake-world root [live-advertisement] nil)
                     ::mcp/prepl (fn [_ _ _]
                                   (if (= 1 (vswap! attempts inc))
                                     (throw (java.net.ConnectException. "Connection refused"))
                                     empty-result)))
        result (mcp/call-tool world {"name" "eval_clj" "arguments" {}})]
    (delete-root! root)
    (is (false? (get result "isError")) "A refused connection is retried after rediscovery.")))

(deftest a-failure-after-sending-is-not-retried
  (let [root (temp-root)
        attempts (volatile! 0)
        world (assoc (fake-world root [live-advertisement] nil)
                     ::mcp/prepl (fn [_ _ _] (vswap! attempts inc) (throw (ex-info "closed" {}))))
        _ (mcp/call-tool world {"name" "eval_clj" "arguments" {}})]
    (delete-root! root)
    (is (= 1 @attempts) "A request that may have reached the JVM is never sent twice.")))

(deftest a-jvm-exception-shows-the-jvms-cause
  (let [root (temp-root)
        world (assoc (fake-world root [live-advertisement] nil)
                     ::mcp/prepl (fn [_ _ _]
                                   (throw (ex-info "PREPL evaluation failed."
                                                   {:seon.error/offending
                                                    {:val (tagged-literal 'error {:cause "Could not locate seon/mcp.clj"})}}))))
        result (mcp/call-tool world {"name" "eval_clj" "arguments" {}})]
    (delete-root! root)
    (is (str/includes? (get-in result ["content" 0 "text"]) "Could not locate seon/mcp.clj")
        "The error text carries the JVM's cause, not only the prepl's summary.")))

(deftest a-silent-jvm-times-out-as-an-error-result
  ;; A real listening socket that never answers (the connection completes in
  ;; its backlog), read by the real operator prepl: the bound is timeout_ms
  ;; plus the bridge's grace (about 2 s).
  (with-open [server (ServerSocket. 0)]
    (let [root (temp-root)
          world (assoc (mcp/live-world root)
                       ::mcp/advertisements
                       (fn [_] [(assoc live-advertisement :seon.boot/prepl-port (.getLocalPort server))]))
          result (mcp/call-tool world {"name" "eval_clj" "arguments" {"timeout_ms" 1}})]
      (delete-root! root)
      (is (true? (get result "isError")) "A timeout is an error result, never a hang.")
      (is (str/includes? (get-in result ["content" 0 "text"]) "no result within")
          "The text says the call timed out and its outcome is unknown."))))
