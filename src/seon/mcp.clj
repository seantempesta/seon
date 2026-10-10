(ns seon.mcp
  "The MCP bridge's one JVM entrance: `tools` and `call`, string in, string
  out. The bridge names nothing else, so a renamed internal never needs a
  bridge restart; the JSON grows by accretion only.

  An MCP caller is a Seon agent at its REPL. `eval_clj` runs its code as one
  ordinary run of the named agent (root, or an agent on its own branch) and
  answers each evaluation the run's settlement saved, printed as that agent's
  next context reads it (`seon.render.transcript/history-entries` over the held
  receipts): SCI mode through the agent's turn (`evaluate-source!`), JVM mode
  through the one path's fold (`seon.turn.batch/jvm-request`), recorded as the
  same agent's run.
  `runtime_status` prints the cluster's health report
  (`seon.health/report`) through its AI face. Every failure is the one error value, printed by the
  value printer, and a thrown one is recorded as a fault here."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [malli.core :as m]
            [malli.json-schema :as json-schema]
            [my.agent]
            [seon.batch]
            [seon.profile :as profile]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.host :as host]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal :as refusal]
            [seon.fault :as fault]
            [seon.health :as health]
            [seon.id :as id]
            [seon.operator.runtime :as runtime]
            [seon.render.transcript :as transcript]
            [seon.render.value :as render.value]
            [seon.sci.eval :as eval]
            [seon.test.runner :as runner]
            [seon.turn]
            [seon.turn.batch :as turn.batch]
            [seon.turn.world :as world])
  (:import (java.util Date)))

;;; ---------------------------------------------------------------------------
;;; The tools, as data. Argument keys arrive qualified in this namespace
;;; (`call`'s JSON key function) and leave as their names (clojure.data.json
;;; writes a keyword by its name).
;;; ---------------------------------------------------------------------------

(defn- eval-bound-ms
  "The longest `eval_clj` answer a caller may wait for: a platform check's boot and test bounds and its silence
  backstop (the operator dials' shipped defaults), the longest ordinary work an evaluation starts."
  {:malli/schema [:=> [:cat] pos-int?]}
  []
  (reduce + (map config/defaults [:seon.config.operator/boot-bound-ms :seon.config.operator/check-bound-ms
                                  :seon.config.operator/event-silence-backstop-ms])))

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
    [:string {:min 1 :description "The namespace the run starts in. SCI default: where the agent's previous run ended; JVM default: user."}]]
   [:seon.mcp/timeout_ms {:optional true}
    [:int {:min 1 :max (eval-bound-ms)
           :description "How long the bridge waits for the answer, in ms (default 30000). The JVM finishes the run either way; past this bound its outcome is unknown to you. A platform check (my.test/check-platform) boots a JVM: pass about 120000."}]]])

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
    (catch Throwable failure (render.value/core-text failure))))

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
  "The instance of the roster `running` (this JVM's running instances by
  name) that `cluster-name` names (default: the only one, else
  \"default\"), or a refusal naming the running ones, or one saying it is
  still starting or already stopping (not ready to evaluate). Its
  `:seon.cluster/name` is the one its handle carries (what configuration is
  read under); `:seon.mcp/cluster` is the name it runs under."
  {:malli/schema [:=> [:cat [:map-of :string :map] [:maybe :string]] :map]}
  [running cluster-name]
  (let [chosen (or cluster-name (if (= 1 (count running)) (key (first running)) "default"))
        found (get running chosen)]
    (cond
      (nil? found)
      (refused (str "No cluster " chosen " is running in this JVM.") :seon.mcp/cluster
               (vec (sort (keys running))) chosen)
      (:seon.boot/failure found)
      (refused (str "Cluster " chosen " failed to boot: "
                    (:seon.error/message (:seon.boot/failure found))
                    " Fix the cause, then run bin/seon start --head.")
               :seon.boot/ready-ms :seon.boot/ready-ms chosen)
      ;; the roster holds a start's partial instance from its first layer and
      ;; a stopping one until its exit: neither can evaluate yet or still
      (or (:seon.boot/stopping found) (not (contains? found :seon.boot/ready-ms)))
      (refused (str "Cluster " chosen " is not ready: it is "
                    (if (:seon.boot/stopping found) "stopping" "starting")
                    " (layers reached: " (mapv :seon.boot/layer (:seon.boot/layers found))
                    "). Retry when bin/seon status reports it ready.")
               :seon.boot/ready-ms :seon.boot/ready-ms chosen)
      :else
      (assoc found :seon.mcp/cluster chosen
               :seon.cluster/name (get-in found [:seon.turn.loop/cluster :seon.cluster/name] chosen)))))

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
  "The run's saved evaluations as the agent's next context reads them: one
  text per held form with the receipt its settlement saved (its response,
  else its shown text or error), printed on the settlement's `database`, and
  whether any evaluation failed."
  {:malli/schema [:=> [:cat :seon.turn.loop/evaluated-sources :seon.turn/id :seon.db/database-value
                       :seon.agent/id :string]
                  [:tuple [:vector :string] :boolean]]}
  [sources run-id database agent-id cluster-name]
  (if (empty? sources)
    [[] false]
    (let [entries (transcript/history-entries
                   {:seon.db/db database :seon.agent/id agent-id :seon.turn/id run-id
                    :seon.sci.admit/caps (config/result-caps (config/effective database cluster-name))
                    :seon.turn.loop/evaluated-sources sources})
          evaluations (mapv :seon.sci.eval/evaluation sources)]
      [(mapv (fn [{response :seon.render.history/printed-value} {shown :seon.eval/shown error :seon.cluster.eval/error}]
               (or response shown error ""))
             entries evaluations)
       (boolean (some :seon.cluster.eval/error evaluations))])))

(defn- since-ms
  "Wall milliseconds since the `System/nanoTime` reading `started`."
  {:malli/schema [:=> [:cat :int] :double]}
  [started]
  (/ (- (System/nanoTime) started) 1e6))

(defn- dial
  "`dial` of `cluster-name`'s effective configuration on `database`, else its
  shipped default when that configuration is refused or leaves it undecided."
  {:malli/schema [:=> [:cat :seon.db/database-value :string :qualified-keyword] :seon.schema/value]}
  [database cluster-name dial-key]
  (let [effective (config/effective database cluster-name)]
    (if-some [value (when-not (:seon.error/at effective) (get effective dial-key))]
      value
      (get config/defaults dial-key))))

(defn- with-diff
  "The request's cost with its live values (`:seon.cost/live`) replaced by
  their comparison with main's stored cost entities (`:seon.cost/diff`,
  `seon.batch/cost-diff`): one query on `main`, as the request found it, by
  the live symbols. Main storing none, or not yet declaring them, compares
  every value as new."
  {:malli/schema [:=> [:cat :map [:maybe :seon.db/database-value] [:maybe :string]] :map]}
  [cost main cluster-name]
  (let [live (:seon.cost/live cost)
        live-tests (:seon.cost/live-tests cost)
        cost (dissoc cost :seon.cost/live :seon.cost/live-tests)
        hysteresis (when (and main cluster-name) (dial main cluster-name :seon.config.fn.cost/hysteresis))
        tests (when (and (seq live-tests) hysteresis)
                (let [latest (runner/latest-results main (mapv :seon.test/sym live-tests))
                      stored (if (:seon.error/at latest)
                               {}
                               (into {} (keep (fn [r] (when-some [b (:seon.test.member/alloc-bytes r)] [(:seon.test/sym r) b]))) latest))]
                  (seon.batch/test-cost-diff live-tests stored hysteresis)))
        cost (cond-> cost (seq tests) (assoc :seon.cost/diff tests))]
    (if (and (seq live) main cluster-name)
      (let [rows (db/q '[:find ?s ?d ?us :in $ [?s ...]
                         :where [?e :seon.fn.cost/sym ?s] [?e :seon.fn.cost/digest ?d] [?e :seon.fn.cost/self-us ?us]]
                       main (mapv :seon.fn.cost/sym live))
            stored (if (db/read-refusal? rows) {} (into {} (map (fn [[s d us]] [s [d us]])) rows))
            diff (seon.batch/cost-diff live stored (dial main cluster-name :seon.config.fn.cost/hysteresis))]
        (cond-> cost (seq diff) (update :seon.cost/diff #(into (vec diff) %))))
      cost)))

(defn- sci-run
  "One ordinary SCI run of the agent (`evaluate-source!`, segmented as code):
  the texts, whether any failed, and the wall ms of each stage the run's pass
  ran and of the print."
  {:malli/schema [:=> [:cat :map :string [:maybe :symbol] :string]
                  [:tuple [:vector :string] :boolean [:map-of :keyword number?] [:maybe :seon.cost/counts]]]}
  [custody code namespace-name cluster-name]
  (let [answer (agent/evaluate-source!
                (cond-> (assoc custody :seon.agent/segmenter 'seon.turn/code-sources :seon.cluster.reply/text code)
                  namespace-name (assoc :seon.turn/starting-ns [:seon.ns/name namespace-name])))
        database (:seon.db/db answer)
        print-start (System/nanoTime)
        [texts failed?]
        (if (and database (not (:seon.error/at answer)))
          (cond-> (observed (:seon.turn.loop/evaluated-sources answer []) (:seon.turn/id answer) database
                            (:seon.agent/id custody) cluster-name)
            (:seon.turn.loop/refusal answer)
            (-> (update 0 conj (printed (:seon.turn.loop/refusal answer) code database cluster-name))
                (assoc 1 true))
            ;; the pass's automatic test step (proofs, then the tests reaching the change)
            (:seon.turn.loop/tests answer)
            (update 0 conj (printed (select-keys answer [:seon.turn.loop/tests]) code database cluster-name)))
          [[(printed answer code (db/db (get-in custody [:seon.turn.loop/cluster :seon.db/connection])) cluster-name)] true])]
    [texts failed? (assoc (:seon.turn.loop/stages answer {}) :print (since-ms print-start))
     (:seon.cost/counts answer)]))

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
  {:malli/schema [:=> [:cat :map :string [:maybe :symbol] :string]
                  [:tuple [:vector :string] :boolean [:map-of :keyword number?] [:maybe :seon.cost/counts]]]}
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
    (let [print-start (System/nanoTime)
          [texts failed?]
          (if-let [after (:seon.batch/after commit)]
            (observed (turn.batch/evaluated-sources batch) run-id after agent-id cluster-name)
            ;; nothing was recorded: the fold's own shown texts, and why
            (let [reply (turn.batch/reply batch)]
              [(conj (mapv :seon.batch/shown (:seon.batch/evaluations reply))
                     (printed (merge (refused "The run was evaluated but not recorded." :seon.batch/commit
                                              :seon.batch/applied (:seon.batch/commit reply))
                                     (select-keys reply [:seon.batch/diagnostics]))
                              code (db/db connection) cluster-name))
               true]))]
      [texts failed? (assoc (:seon.batch/timings batch {}) :print (since-ms print-start))
       (merge (profile/sink-counts (:seon.batch/sink world))
              (seon.batch/arm-measures (keep :seon.batch/evaluation (:seon.batch/entries batch)))
              (let [sink (:seon.batch/sink world)
                    database (db/db connection)]
                {:seon.cost/live (profile/live-costs sink (db/sink-digests database sink)
                                                     (dial database cluster-name :seon.config.fn.cost/min-total-us)
                                                     (dial database cluster-name :seon.config.test.cost/top))}))])))

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
      (assoc (refused "Invalid arguments; :seon.error/problems names each failing path."
                      :seon.mcp/arguments (m/form schema) value)
             :seon.error/problems (mapv (fn [{:keys [in schema value]}]
                                          {:seon.error/path in
                                           :seon.error/expected (m/form schema)
                                           :seon.error/offending value})
                                        (:errors explanation)))
      value)))

(defn- eval-clj
  "`eval_clj`: the texts and whether any failed. The last text is the eval
  request's cost (`:seon.repl/request`), measured from this entrance."
  {:malli/schema [:=> [:cat :map] [:tuple [:vector :string] :boolean]]}
  [{code :seon.mcp/code agent-id :seon.mcp/agent cluster :seon.mcp/cluster mode :seon.mcp/mode
    start :seon.mcp/namespace}]
  (let [started (System/nanoTime)
        counters (seon.batch/jvm-counters)
        found (instance @runtime/running-instances cluster)
        ;; main as the request found it: its own writes are not what it compares with
        main (some-> (get-in found [:seon.turn.loop/cluster :seon.db/connection]) db/db)
        namespace-name (some-> start symbol)
        custody (when-not (:seon.error/at found)
                  (execution found (or agent-id "root") namespace-name))
        cluster-name (:seon.cluster/name found)
        stages {:custody (since-ms started)}
        [texts failed? run-stages counts]
        (cond
          (:seon.error/at found) [[(printed found code nil nil)] true {} nil]
          (:seon.error/at custody)
          [[(printed custody code (db/db (get-in found [:seon.turn.loop/cluster :seon.db/connection])) cluster-name)] true {} nil]
          (= "jvm" mode) (jvm-run custody code namespace-name cluster-name)
          :else (sci-run custody code namespace-name cluster-name))]
    [(conj texts (printed {:seon.repl/request (-> (seon.batch/request-cost started (merge stages run-stages) counts)
                                                  (with-diff main cluster-name)
                                                  (assoc :seon.cost/jvm-window
                                                         (seon.batch/jvm-window counters (seon.batch/jvm-counters))))}
                          code nil cluster-name))
     failed?]))

(defn- runtime-status
  "`runtime_status`: the cluster's health report through its AI face, printed."
  {:malli/schema [:=> [:cat :map] [:tuple [:vector :string] :boolean]]}
  [{cluster-name :seon.mcp/cluster}]
  (let [found (instance @runtime/running-instances cluster-name)
        running-name (:seon.mcp/cluster found cluster-name)
        source (str "(seon.health/report {:seon.boot/cluster-name " (id/printed running-name) "})")]
    (if (:seon.error/at found)
      [[(printed found source nil nil)] true]
      (let [database (db/db (get-in found [:seon.turn.loop/cluster :seon.db/connection]))
            report (health/report {:seon.boot/cluster-name running-name :seon.db/db database})]
        [[(printed (health/render-ai {:seon.render/value report :seon.db/db database})
                   source database (:seon.cluster/name found))]
         (boolean (:seon.error/at report))]))))

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

(defn call-failure
  "The error value of a call that threw: its failure and the whole request,
  as data the value printer shows."
  {:malli/schema [:=> [:cat :string :seon.error/throwable] :seon.error/base]}
  [request failure]
  (refusal/diagnostic (Date.) :seon.mcp/call `call {:seon.error/throwable failure :seon.mcp/request request}))

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
               [[(printed (refused "Unknown tool; :seon.error/expected names the tools." :seon.mcp/name
                                   (mapv :seon.mcp/name tool-table) tool)
                          request nil nil)] true]
               (:seon.error/at valid) [[(printed valid request nil nil)] true]
               :else (answer valid))))
    (catch Throwable failure
      (let [found (try (instance @runtime/running-instances nil) (catch Throwable _ nil))
            receipt (fault-receipt failure found)]
        ;; the receipt rides the reply beside the error it records
        (result (cond-> [(printed (call-failure request failure) request nil nil)]
                  receipt (conj (printed receipt request nil nil)))
                true)))))
