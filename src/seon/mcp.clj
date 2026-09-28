(ns seon.mcp
  "The MCP bridge's one JVM entrance: `tools` and `call`, string in, string
  out. The bridge names nothing else, so a renamed internal never needs a
  bridge restart; the JSON grows by accretion only.

  An MCP caller is a Seon agent at its REPL. `eval_clj` runs its code as one
  ordinary run of the named agent (root, or an agent on its own branch) and
  answers exactly what that agent's next context reads for those
  evaluations (`seon.cluster.agent/run-observation`): SCI mode through the
  agent's turn (`evaluate-source!`), JVM mode through the one path's fold
  (`seon.turn.batch/jvm-request`), recorded as the same agent's run.
  `runtime_status` prints the cluster's runtime observation through its
  declared AI render. Every failure is the one error value, printed by the
  value printer, and a thrown one is recorded as a fault here."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as json-schema]
            [my.agent]
            [seon.batch]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.host :as host]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal :as refusal]
            [seon.fault :as fault]
            [seon.id :as id]
            [seon.operator.runtime :as runtime]
            [seon.render.value :as render.value]
            [seon.sci.eval :as eval]
            [seon.turn]
            [seon.turn.batch :as turn.batch]
            [seon.turn.world :as world])
  (:import (java.util Date)))

;;; ---------------------------------------------------------------------------
;;; The tools, as data. Argument keys arrive qualified in this namespace
;;; (`call`'s JSON key function) and leave as their names (clojure.data.json
;;; writes a keyword by its name).
;;; ---------------------------------------------------------------------------

(def eval-arguments
  "The `eval_clj` arguments."
  [:map {:closed true}
   [:seon.mcp/code [:string {:min 1 :description "Clojure code: every form, evaluated in order as one run."}]]
   [:seon.mcp/agent {:optional true}
    [:string {:min 1 :description "The agent you run as: \"root\" (default) on the cluster's own branch, or an agent id; a new id gets its own branch off the cluster head on first use, recorded as the agent's :seon.agent/branch."}]]
   [:seon.mcp/cluster {:optional true}
    [:string {:min 1 :description "The cluster; defaults to the only running one, else \"default\"."}]]
   [:seon.mcp/mode {:optional true}
    [:enum {:description "sci (default): the agent's SCI context, where definitions become program entities on its branch. jvm: host evaluation in the JVM, recorded as the same agent's run."}
     "sci" "jvm"]]
   [:seon.mcp/namespace {:optional true}
    [:string {:min 1 :description "The namespace the run starts in. SCI default: where the agent's previous run ended; JVM default: user."}]]])

(def status-arguments
  "The `runtime_status` arguments."
  [:map {:closed true}
   [:seon.mcp/cluster {:optional true}
    [:string {:min 1 :description "The cluster; defaults to the only running one, else \"default\"."}]]])

(def tool-table
  "Each tool's name, description and argument schema."
  [{:seon.mcp/name "eval_clj"
    :seon.mcp/description
    (str "You are a Seon agent at your REPL. Evaluate Clojure as one ordinary run of the agent you name "
         "(root by default) in the cluster you name, and read exactly what that agent's next context reads: "
         "each evaluation's value, :result handle, :ms, :calls, errors and faults. Dig into a value with its "
         "result symbol in a later sci evaluation, e.g. (keys result/e1a2b3c4d5e6). mode sci evaluates in the "
         "agent's SCI context (a defn becomes a program entity on its branch); mode jvm evaluates on the host "
         "JVM (pass connections explicitly, e.g. (seon.cluster.boot/connection \"default\")). Branches: "
         "my.agent/create-branch!, my.agent/branches, my.agent/retire-branch!.")
    :seon.mcp/arguments eval-arguments}
   {:seon.mcp/name "runtime_status"
    :seon.mcp/description
    "The cluster's runtime observation (health, open faults, flow, readiness, cache), printed as bin/seon status prints it."
    :seon.mcp/arguments status-arguments}])

(defn tools
  "The MCP `tools` array as a JSON string.

  Each tool's name, description and input schema, derived from its Malli
  argument schema (`malli.json-schema`).

  ## Examples

      ((requiring-resolve 'seon.mcp/tools))"
  {:malli/schema [:=> [:cat] :string]}
  []
  (json/write-str
   (mapv (fn [{tool :seon.mcp/name description :seon.mcp/description arguments :seon.mcp/arguments}]
           {"name" tool "description" description "inputSchema" (json-schema/transform arguments)})
         tool-table)))

;;; ---------------------------------------------------------------------------
;;; Errors and printing
;;; ---------------------------------------------------------------------------

(defn- refused
  "A request refusal: what was wrong, the member, what was expected and the offending value."
  {:malli/schema [:=> [:cat :seon.error/message :seon.error/member :seon.schema/value :seon.schema/value]
                  :seon.error/value]}
  [message member expected offending]
  (refusal/diagnostic (Date.) :seon.mcp/call `call
                      {:seon.error/message message :seon.error/member member
                       :seon.error/expected expected :seon.error/offending offending}))

(defn- printed
  "`value` through the value printer against the cluster's database, `source`
  being the form that shows how it was reached; an error value prints as a
  failure. If printing itself fails, the Throwable floor prints the failure."
  {:malli/schema [:=> [:cat :seon.schema/value :string [:maybe :seon.db/database-value] [:maybe :string]] :string]}
  [value source database cluster-name]
  (try
    (let [request (cond-> {:seon.cluster.eval/source source}
                    database (assoc :seon.db/db database)
                    cluster-name (assoc :seon.boot/cluster-name cluster-name))]
      (:seon.eval/shown (if (and (map? value) (:seon.error/at value))
                          (eval/shown-failure value request nil)
                          (eval/shown-result value request nil))))
    (catch Throwable failure (render.value/floor failure))))

(defn- result
  "The MCP CallToolResult as a JSON string: one text item per shown text."
  {:malli/schema [:=> [:cat [:vector :string] :boolean] :string]}
  [texts error?]
  (json/write-str {"content" (mapv (fn [text] {"type" "text" "text" text}) texts)
                   "isError" error?}))

;;; ---------------------------------------------------------------------------
;;; Custody: which agent, on which branch
;;; ---------------------------------------------------------------------------

(defn- instance
  "The running instance `cluster-name` names (default: the only one, else
  \"default\"), or a refusal naming the running ones. Its
  `:seon.cluster/name` is the one its handle carries (what configuration is
  read under); `:seon.mcp/cluster` is the name it runs under."
  {:malli/schema [:=> [:cat [:maybe :string]] :map]}
  [cluster-name]
  (let [running @runtime/running-instances
        chosen (or cluster-name (if (= 1 (count running)) (key (first running)) "default"))]
    (if-let [found (get running chosen)]
      (assoc found :seon.mcp/cluster chosen
             :seon.cluster/name (get-in found [:seon.turn.loop/cluster :seon.cluster/name] chosen))
      (refused (str "No cluster " chosen " is running in this JVM.") :seon.mcp/cluster
               (vec (sort (keys running))) chosen))))

(defn- execution
  "Where `agent-id` runs: an agent with no home branch (root) or homed on the
  cluster's own branch runs on the cluster; one homed elsewhere
  (`:seon.agent/branch`) runs hosted on that branch; an unknown id gets a new
  branch off the cluster head (named as `acquire-context!` names an isolated
  one) and is hosted there, which records its home. A retired agent (its
  admission closed) refuses by name. Answers the custody `evaluate-source!`
  takes."
  {:malli/schema [:=> [:cat :map :seon.agent/id [:maybe :symbol]] :map]}
  [{handle :seon.turn.loop/cluster store :seon.store/store} agent-id namespace-name]
  (let [connection (:seon.db/connection handle)
        database (db/db connection)
        live (get-in database [:config :branch])
        entity (db/pull database [:db/id :seon.agent/branch :seon.agent/admission-closed-tx] [:seon.agent/id agent-id])
        home (:seon.agent/branch entity)
        on-cluster {:seon.turn.loop/cluster handle :seon.agent/routing (:seon.agent/routing handle)
                    :seon.agent/id agent-id}]
    (cond
      (:seon.error/at entity) entity
      (:seon.agent/admission-closed-tx entity)
      (refused (str "Agent " agent-id " is retired: its admission closed. Name another agent id.")
               :seon.agent/admission-closed-tx :seon.agent/id agent-id)
      (and (:db/id entity) (or (nil? home) (= live home))) on-cluster
      :else
      (let [branch (or home (keyword (str "agent-" (id/id))))
            created (when-not home
                      (my.agent/create-branch! {:seon.store/branch branch}
                                               {:seon.store/store store :seon.db/connection connection
                                                :seon.agent/routing (:seon.agent/routing handle)}))]
        (if (:seon.error/at created)
          created
          ;; host! answers its refusal by name (closed admission, another
          ;; agent hosted there): the caller's to fix
          (let [host (host/host! {:seon.turn.loop/cluster (assoc handle :seon.store/store store)
                                  :seon.agent/branch branch :seon.agent/id agent-id
                                  :seon.ns/name (or namespace-name 'user)})]
            (if (:seon.error/at host)
              host
              (select-keys host [:seon.turn.loop/cluster :seon.agent/routing :seon.agent/id]))))))))

;;; ---------------------------------------------------------------------------
;;; Evaluation
;;; ---------------------------------------------------------------------------

(defn- observed
  "The settled run as the agent's next context reads it: one text per
  evaluation (its response, else its shown text or error) and whether any
  evaluation failed."
  {:malli/schema [:=> [:cat :seon.turn/id :seon.db/database-value :seon.agent/id :string]
                  [:tuple [:vector :string] :boolean]]}
  [run-id database agent-id cluster-name]
  (let [evaluations (agent/run-observation {:seon.turn/id run-id :seon.db/db database} agent-id cluster-name)]
    [(mapv (fn [{response :seon.repl/response shown :seon.eval/shown error :seon.cluster.eval/error}]
             (str (or response shown error "")))
           evaluations)
     (boolean (some :seon.cluster.eval/error evaluations))]))

(defn- sci-run
  "One ordinary SCI run of the agent (`evaluate-source!`, segmented as code)."
  {:malli/schema [:=> [:cat :map :string [:maybe :symbol] :string] [:tuple [:vector :string] :boolean]]}
  [custody code namespace-name cluster-name]
  (let [answer (agent/evaluate-source!
                (cond-> (assoc custody :seon.agent/segmenter 'seon.turn/code-sources :seon.cluster.reply/text code)
                  namespace-name (assoc :seon.turn/starting-ns [:seon.ns/name namespace-name])))
        database (:seon.db/db answer)]
    (if (and database (not (:seon.error/at answer)))
      (cond-> (observed (:seon.turn/id answer) database (:seon.agent/id custody) cluster-name)
        (:seon.turn.loop/refusal answer)
        (-> (update 0 conj (printed (:seon.turn.loop/refusal answer) code database cluster-name))
            (assoc 1 true)))
      [[(printed answer code (db/db (get-in custody [:seon.turn.loop/cluster :seon.db/connection])) cluster-name)] true])))

(defn- pages-changed!
  "Tell each page channel to re-render when this JVM's loaded code differs
  from `mark` (a defined, redefined or unmapped Var may change any page); a
  run that changed no loaded code keeps every cached page. Answers whether
  it signalled."
  {:malli/schema [:=> [:cat [:tuple :int [:fn seon.cluster/identity-hash-map?]] [:sequential :seon.flow/channel]]
                  :boolean]}
  [mark channels]
  (let [changed? (cluster/loaded-code-changed? mark)]
    (when changed?
      (run! #(async/offer! % :seon.render.web/runtime-eval) channels))
    changed?))

(defn- jvm-run
  "One JVM run of the agent through the fold (`jvm-request`).

  Host read and eval, the same show stage (each value bound under its result
  handle in the agent's context), recorded as the agent's run on its branch.
  A run that changed loaded code tells every cluster's pages to re-render."
  {:malli/schema [:=> [:cat :map :string [:maybe :symbol] :string] [:tuple [:vector :string] :boolean]]}
  [{handle :seon.turn.loop/cluster agent-id :seon.agent/id} code namespace-name cluster-name]
  (let [connection (:seon.db/connection handle)
        run-id (id/id)
        ;; the agent's own context holds each value under its result handle
        world (world/jvm-world cluster-name connection
                               (cond-> {:seon.agent/id agent-id :seon.turn/id run-id
                                        :seon.sci.eval/ctx (:seon.sci.eval/ctx (agent/acquire-context! handle agent-id))}
                                 (env/of handle) (assoc :seon.env/environment (env/of handle))
                                 (:seon.db.process/id handle) (assoc :seon.db.process/id (:seon.db.process/id handle))))
        mark (cluster/loaded-code-mark)
        batch (seon.batch/evaluate-batch
               (turn.batch/jvm-request {:seon.batch/world world :seon.batch/text code
                                        :seon.batch/start-ns (or namespace-name 'user)
                                        :seon.turn.batch/record-call #'seon.turn/record-evaluated-call}))
        commit (:seon.batch/commit batch)]
    (pages-changed! mark (keep #(get-in % [:seon.render.web/view :seon.render.web/runtime-eval-channel])
                               (vals @runtime/running-instances)))
    (if-let [after (:seon.batch/after commit)]
      (observed run-id after agent-id cluster-name)
      ;; nothing was recorded: the fold's own shown texts, and why
      (let [reply (turn.batch/reply batch)]
        [(conj (mapv :seon.batch/shown (:seon.batch/evaluations reply))
               (printed (merge (refused "The run was evaluated but not recorded." :seon.batch/commit
                                        :seon.batch/applied (:seon.batch/commit reply))
                               (select-keys reply [:seon.batch/diagnostics]))
                        code (db/db connection) cluster-name))
         true]))))

;;; ---------------------------------------------------------------------------
;;; The entrance
;;; ---------------------------------------------------------------------------

(defn- arguments
  "The tool's decoded JSON arguments validated against its argument schema,
  or a refusal naming what is wrong."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value] :map]}
  [schema value]
  (let [value (or value {})]
    (if-let [explanation (m/explain schema value)]
      (refused (str "Invalid arguments: " (render.value/bounded-text (me/humanize explanation)))
               :seon.mcp/arguments (m/form schema) value)
      value)))

(defn- eval-clj
  "`eval_clj`: the texts and whether any failed."
  {:malli/schema [:=> [:cat :map] [:tuple [:vector :string] :boolean]]}
  [{code :seon.mcp/code agent-id :seon.mcp/agent cluster :seon.mcp/cluster mode :seon.mcp/mode
    start :seon.mcp/namespace}]
  (let [found (instance cluster)
        namespace-name (some-> start symbol)
        custody (when-not (:seon.error/at found)
                  (execution found (or agent-id "root") namespace-name))
        cluster-name (:seon.cluster/name found)]
    (cond
      (:seon.error/at found) [[(printed found code nil nil)] true]
      (:seon.error/at custody)
      [[(printed custody code (db/db (get-in found [:seon.turn.loop/cluster :seon.db/connection])) cluster-name)] true]
      (= "jvm" mode) (jvm-run custody code namespace-name cluster-name)
      :else (sci-run custody code namespace-name cluster-name))))

(defn- runtime-status
  "`runtime_status`: the observation through its declared AI render, printed."
  {:malli/schema [:=> [:cat :map] [:tuple [:vector :string] :boolean]]}
  [{cluster-name :seon.mcp/cluster}]
  (let [found (instance cluster-name)
        running-name (:seon.mcp/cluster found cluster-name)
        source (str "(seon.cluster/mcp-runtime-observation " (id/printed running-name) ")")]
    (if (:seon.error/at found)
      [[(printed found source nil nil)] true]
      (let [database (db/db (get-in found [:seon.turn.loop/cluster :seon.db/connection]))
            observation (cluster/mcp-runtime-observation running-name)]
        [[(printed (cluster/runtime-observation-ai {:seon.render/value observation :seon.db/db database})
                   source database (:seon.cluster/name found))]
         (boolean (:seon.error/at observation))]))))

(defn- fault-receipt
  "Record `failure` as a core fault in the cluster's environment, answering
  the receipt, or the recording's own failure as an error value."
  {:malli/schema [:=> [:cat :seon.error/throwable [:maybe :map]] [:maybe :map]]}
  [failure found]
  (let [handle (:seon.turn.loop/cluster found)]
    (when-let [environment (env/of handle)]
      (try (fault/fault! environment failure {:seon.error/layer :seon.mcp/call :seon.error/operation `call
                                              :seon.db.process/id (:seon.db.process/id handle)})
           (catch Throwable recording
             (refusal/diagnostic (Date.) :seon.mcp/call `fault-receipt {:seon.error/throwable recording}))))))

(defn- decoded
  "The request's JSON as data, keys qualified in this namespace, or a refusal."
  {:malli/schema [:=> [:cat :string] :map]}
  [request]
  (let [value (try (json/read-str request :key-fn #(keyword "seon.mcp" %))
                   (catch Exception failure
                     (refused (str "The request is not JSON: " (ex-message failure)) :seon.mcp/request
                              :seon.mcp/json request)))]
    (if (map? value)
      value
      (refused "The request must be a JSON object." :seon.mcp/request :seon.mcp/json value))))

(defn call
  "Answer one MCP tool call, JSON in and JSON out.

  `request` is the JSON of `{\"name\": tool, \"arguments\": {...}}`; the answer is the JSON of an MCP
  CallToolResult, `{\"content\": [{\"type\": \"text\", \"text\": ...}],
  \"isError\": bool}`. Never throws: a failure is the one error value,
  printed by the value printer, and a thrown one is recorded as a fault.

  ## Examples

      ((requiring-resolve 'seon.mcp/call) \"{\\\"name\\\":\\\"eval_clj\\\",\\\"arguments\\\":{\\\"code\\\":\\\"(+ 1 2)\\\"}}\")"
  {:malli/schema [:=> [:cat :string] :string]}
  [request]
  (try
    (let [{tool :seon.mcp/name raw :seon.mcp/arguments :as value} (decoded request)
          [schema answer] (case tool
                            "eval_clj" [eval-arguments eval-clj]
                            "runtime_status" [status-arguments runtime-status]
                            nil)
          valid (when schema (arguments schema raw))]
      (apply result
             (cond
               (:seon.error/at value) [[(printed value request nil nil)] true]
               (nil? schema)
               [[(printed (refused (str "Unknown tool " (render.value/bounded-text tool) ".") :seon.mcp/name
                                   (mapv :seon.mcp/name tool-table) tool)
                          request nil nil)] true]
               (:seon.error/at valid) [[(printed valid request nil nil)] true]
               :else (answer valid))))
    (catch Throwable failure
      (let [found (try (instance nil) (catch Throwable _ nil))
            receipt (fault-receipt failure found)
            source (str/trim (subs request 0 (min 200 (count request))))]
        ;; the receipt rides the reply beside the error it records
        (result (cond-> [(printed (refusal/diagnostic (Date.) :seon.mcp/call `call {:seon.error/throwable failure})
                                  source nil nil)]
                  receipt (conj (printed receipt source nil nil)))
                true)))))
