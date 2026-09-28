(ns seon.mcp-entrance-test
  "The MCP bridge's one JVM entrance (`seon.mcp/tools`, `seon.mcp/call`):
  JSON in, JSON out, and what an MCP caller reads is what its agent's next
  context reads."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.mcp :as mcp]
            [seon.operator.runtime :refer [running-instances]]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(defn- answer
  "`seon.mcp/call` on `tool` and `arguments`, decoded."
  {:malli/schema [:=> [:cat :string [:map-of :string :seon.schema/value]] [:map-of :string :seon.schema/value]]}
  [tool arguments]
  (json/read-str (mcp/call (json/write-str {"name" tool "arguments" arguments}))))

(defn- with-running-agent
  "Run `body` with `(running agent-id connection)`: an armed agent on the member's
  branch, its cluster handle registered in `running-instances` under a key of
  its own (the member's cluster name is shared), released afterwards."
  {:malli/schema [:=> [:cat [:=> [:cat :string :seon.agent/id :seon.db/connection] :seon.schema/value]]
                  :seon.schema/value]}
  [body]
  (let [target (support/execution-handle nil)
        connection (:seon.db/connection target)
        cluster-name (:seon.cluster/name target)
        agent-id (str "mcp-entrance-" (id/id))
        running (str "mcp-entrance-" (id/id))
        environment (support/environment cluster-name connection)
        ctx (support/fork-cluster-ctx connection cluster-name)
        routing (support/attempt-routing connection)]
    (with-open [launcher (support/closeable
                          (flow/start-work-launcher!
                           {:seon.env/environment environment
                            :seon.flow/configuration (select-keys (config/effective (db/db connection) cluster-name)
                                                                  flow/flow-workload-attributes)})
                          flow/stop-work-launcher!)]
      (let [handle (support/cluster-handle
                    {:seon.env/environment environment :seon.db/connection connection :seon.cluster/name cluster-name
                     :seon.sci.eval/ctx ctx :seon.flow/work-launcher @launcher
                     :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                     :seon.db.process/id cluster/boot-process-identity})
            request {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id agent-id}]
        (support/transacted! connection (support/agent-tx @connection agent-id))
        (swap! running-instances assoc running {:seon.turn.loop/cluster (assoc handle :seon.agent/routing routing)
                                                  :seon.boot/ready-ms 0})
        (try
          (body running agent-id connection)
          (finally
            (swap! running-instances dissoc running)
            (agent/disarm! request)
            (doseq [channel [(:seon.cluster.wake/channel handle) (:seon.render/context-channel handle)
                             (:seon.turn.loop/completion handle)]]
              (when channel (async/close! channel)))))))))

(defn- latest-run
  "The agent's newest run id on `connection` (the latest transaction naming one)."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id] [:maybe :seon.turn/id]]}
  [connection agent-id]
  (->> (db/q '[:find ?id ?tx :in $ ?agent-id
               :where [?agent :seon.agent/id ?agent-id] [?run :seon.turn/agent ?agent] [?run :seon.turn/id ?id ?tx]]
             (db/db connection) agent-id)
       (sort-by second) last first))

(deftest tools-derive-each-input-schema-from-its-argument-schema
  (let [tools (json/read-str (mcp/tools))
        by-name (into {} (map (juxt #(get % "name") identity)) tools)]
    (is (= #{"eval_clj" "runtime_status"} (set (keys by-name))))
    (is (= ["code"] (get-in by-name ["eval_clj" "inputSchema" "required"])))
    (is (= ["sci" "jvm"] (get-in by-name ["eval_clj" "inputSchema" "properties" "mode" "enum"])))
    (is (false? (get-in by-name ["eval_clj" "inputSchema" "additionalProperties"]))
        "a misspelled argument is refused, never ignored")))

(deftest an-unknown-tool-answers-an-error-result
  (let [result (answer "get_value" {})]
    (is (true? (get result "isError")))
    (is (= 1 (count (get result "content"))))))

(deftest invalid-arguments-answer-an-error-result
  (is (true? (get (answer "eval_clj" {"code" "(+ 1 2)" "namepsace" "user"}) "isError")))
  (is (true? (get (answer "eval_clj" {}) "isError"))))

(deftest a-request-that-is-not-json-answers-an-error-result
  (is (true? (get (json/read-str (mcp/call "{not json")) "isError"))))

(deftest an-sci-call-answers-what-the-agents-next-context-reads
  (with-running-agent
   (fn [running agent-id connection]
     (let [result (answer "eval_clj" {"code" "(+ 1 2) (str \"a\" \"b\")" "agent" agent-id "cluster" running})
           run (latest-run connection agent-id)]
       (is (false? (get result "isError")))
       (is (= (mapv :seon.repl/response
                    (agent/run-observation {:seon.turn/id run :seon.db/db (db/db connection)} agent-id
                                           (:seon.cluster/name (support/execution-handle nil))))
              (mapv #(get % "text") (get result "content")))
           "one text per evaluation, exactly the agent's own response")))))

(deftest a-jvm-call-is-the-agents-recorded-run-and-its-value-digs-by-handle
  (with-running-agent
   (fn [running agent-id connection]
     (let [jvm (answer "eval_clj" {"code" "(+ 1 2)" "agent" agent-id "cluster" running "mode" "jvm"})
           handle (admit/result-handle (id/evaluation (latest-run connection agent-id) 0))
           dug (answer "eval_clj" {"code" (str "(inc " handle ")") "agent" agent-id "cluster" running})
           run (latest-run connection agent-id)]
       (is (false? (get jvm "isError")))
       (is (false? (get dug "isError")))
       (is (= ["4"] (mapv :seon.eval/shown
                          (agent/run-observation {:seon.turn/id run :seon.db/db (db/db connection)} agent-id
                                                 (:seon.cluster/name (support/execution-handle nil)))))
           "the JVM value is bound under its handle in the agent's own context")))))

(deftest only-a-run-that-changed-loaded-code-signals-pages
  (let [signal (fn [body]
                 (let [channel (async/chan 1)
                       mark (cluster/loaded-code-mark)]
                   (body)
                   (#'mcp/pages-changed! mark [channel])
                   (some? (async/poll! channel))))
        probe (symbol (str "signal-probe-" (id/id)))]
    (try
      (is (false? (signal #(+ 1 2))) "a run that changes no code keeps cached pages")
      (is (true? (signal #(binding [*ns* (the-ns 'seon.mcp-entrance-test)] (eval (list 'def probe 1)))))
          "a new Var re-renders pages")
      (finally (ns-unmap 'seon.mcp-entrance-test probe)))))

(deftest a-retired-agent-refuses-by-name
  (with-running-agent
   (fn [running agent-id connection]
     (support/transacted! connection [[:db/add [:seon.agent/id agent-id] :seon.agent/admission-closed-tx "datomic.tx"]])
     (is (true? (get (answer "eval_clj" {"code" "(+ 1 2)" "agent" agent-id "cluster" running}) "isError"))))))

(deftest a-cluster-still-starting-or-stopping-refuses-by-name
  ;; During a restart the roster holds the start's partial instance (its
  ;; first layers, no routing yet): an eval answered a bare
  ;; NullPointerException "Cannot invoke Future.get() because fut is null".
  ;; It refuses, saying the cluster is not ready.
  (let [starting {"c" {:seon.boot/layers [{:seon.boot/layer :seon.store/store :seon.boot/at-ms 3}]}}
        stopping {"c" {:seon.boot/ready-ms 10 :seon.boot/stopping true}}]
    (is (= :seon.boot/ready-ms (:seon.error/member (#'mcp/instance starting "c")))
        "a starting instance is refused as not ready")
    (is (= :seon.boot/ready-ms (:seon.error/member (#'mcp/instance stopping "c")))
        "a stopping instance is refused as not ready")
    (is (= "c" (:seon.mcp/cluster (#'mcp/instance {"c" {:seon.boot/ready-ms 10}} "c")))
        "a ready instance is the one named")))
