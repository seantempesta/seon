#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns context-mvp-drive
  "Drive the Context MVP through one dedicated scratch-cluster JVM.

  The default `nursery` mode sends one sentence that forces two real turns:
  the first defines and calls contracted code and returns a self-message via
  `my.message/send`; the second must answer from its newly projected walk.
  Every provider attempt prints the exact prompt bytes and exact reply bytes.

  The `birth` mode creates the agent assigned to `seon.flow` and prints its
  walk at depths one and two without calling a model.

  Run from a dedicated JVM (Ollama is the default provider):

    clojure -M:dev -e '(load-file \"tmp/context-mvp-drive.clj\")'

  Select the optional hosted row or the birth-context proof with environment
  data, never source edits:

    CONTEXT_MVP_PROVIDER=deepseek DEEPSEEK_API_KEY=... clojure ...
    CONTEXT_MVP_MODE=birth clojure ..."
  (:require [clojure.core.async :as async]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.ai.tokens :as tokens]
            [seon.cluster :as cluster]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.render :as render]
            [seon.sci.eval :as sci.eval])
  (:import [java.nio.charset StandardCharsets]
           [java.util Date UUID]))

(def ^:private mode
  (keyword (or (System/getenv "CONTEXT_MVP_MODE") "nursery")))

(def ^:private provider
  (keyword (or (System/getenv "CONTEXT_MVP_PROVIDER") "ollama")))

(def ^:private evidence-path
  (System/getenv "CONTEXT_MVP_EVIDENCE_PATH"))

(def ^:private ollama-reasoning-effort
  (System/getenv "CONTEXT_MVP_OLLAMA_REASONING_EFFORT"))

(def ^:private exact-blocks (atom []))

(def ^:private cluster-name
  (str "context-mvp-" (subs (str (UUID/randomUUID)) 0 8)))

;; A unique repository-local root makes recursive cleanup unnecessary. The
;; process is stopped in `finally`; retained facts are useful drive evidence.
(def ^:private process-root
  (str "tmp/context-mvp-drive/" cluster-name "/clusters"))

(def ^:private nursery-agent-id "context-mvp")
(def ^:private owner-agent-id "seon-flow-owner")

(def ^:private one-sentence-task
  (or (System/getenv "CONTEXT_MVP_TASK")
      (str "Define and call a permanent contracted function that returns the "
           "names of the schemas used by my.message, then return one vector "
           "containing a self-message made with my.message/send asking what "
           "those schemas are and a my.run/complete value; when that message "
           "starts your next turn, inspect your fresh walk and answer it.")))

(def ^:private provider-rows
  {:ollama
   {:seon.config.ai/endpoint
    (or (System/getenv "OLLAMA_OPENAI_ENDPOINT")
        "http://127.0.0.1:11434/v1/chat/completions")
    :seon.config.ai/model
    (or (System/getenv "OLLAMA_MODEL") "qwen3.5:35b-a3b-coding-nvfp4")
    :seon.config.ai/no-auth true
    :seon.config.ai/max-tokens 8192
    :seon.config.ai/timeout-ms 300000}

   :deepseek
   {:seon.config.ai/endpoint "https://api.deepseek.com/chat/completions"
    :seon.config.ai/model "deepseek-chat"
    :seon.config.ai/api-key-variable "DEEPSEEK_API_KEY"
    :seon.config.ai/timeout-ms 300000}})

(defn- stamp
  [& parts]
  (println (str "[" (java.time.LocalTime/now) "] " (apply str parts)))
  (flush))

(defn- utf8-bytes
  [text]
  (.getBytes ^String text StandardCharsets/UTF_8))

(defn- print-exact!
  "Print `text` without transforming it, framed by its exact token estimate
  and UTF-8 length."
  [label text]
  (let [payload (utf8-bytes text)]
    (swap! exact-blocks conj [label text])
    (println (str "\n================ " label " ("
                  (tokens/estimate text) " ESTIMATED TOKENS; "
                  (alength payload) " UTF-8 BYTES) ================"))
    (.write System/out payload 0 (alength payload))
    ;; This newline and the end marker are framing, not part of `payload`.
    (println (str "\n================ END " label " ================"))
    (flush)))

(defn- write-evidence!
  []
  (when evidence-path
    (when-not (or (.startsWith evidence-path "tmp/")
                  (.startsWith
                   evidence-path
                   "docs/prds/sci-execution-runtime/research/"))
      (throw (ex-info "Evidence path must stay inside this repository's tmp or owning research directory."
                      {:context-mvp-drive/evidence-path evidence-path})))
    (let [evidence-file (java.io.File. evidence-path)
          exists? (.exists evidence-file)]
      (spit evidence-path
            (str (when-not exists?
                   (str "---\ntype: research\nstatus: active\n"
                        "tags: [research, context, sci, mvp, evidence]\n---\n\n"
                        "# Context MVP seam proof — 2026-07-31\n\n"))
                 "## Verbatim captured projections\n\n"
               (apply str
                      (map (fn [[label text]]
                             (str "### " label "\n\n```clojure\n"
                                  text "\n```\n\n"))
                           @exact-blocks)))
            :append exists?))
    (stamp "WROTE exact evidence to " evidence-path)))

(defn- selected-provider-row
  []
  (or (get provider-rows provider)
      (throw (ex-info "Unknown CONTEXT_MVP_PROVIDER."
                      {:context-mvp-drive/provider provider
                       :context-mvp-drive/known (set (keys provider-rows))}))))

(defn- manifest
  []
  (selected-provider-row))

(defn- agent-namespace
  [agent-id]
  (case agent-id
    "seon-flow-owner" 'seon.flow
    (symbol "my.agents" agent-id)))

(defn- creation-request
  [agent-id]
  {:seon.cluster.agent/id agent-id
   :seon.cluster/name cluster-name
   :seon.ns/name (agent-namespace agent-id)})

(defn- ensure-agent!
  [connection process agent-id]
  (cluster/ensure-entity! connection process (creation-request agent-id))
  agent-id)

(defn- closed-run-count
  [db agent-id]
  (or (d/q '[:find (count ?run) .
             :in $ ?agent-id
             :where
             [?agent :seon.cluster.agent/id ?agent-id]
             [?run :seon.cluster.run/agent ?agent]
             [?run :seon.cluster.run/closed-at _]]
           db agent-id)
      0))

(defn- run-evidence
  [db agent-id]
  (->> (d/q '[:find ?opened ?run-id ?ordinal ?source ?result ?error ?cut
              :in $ ?agent-id
              :where
              [?agent :seon.cluster.agent/id ?agent-id]
              [?run :seon.cluster.run/agent ?agent]
              [?run :seon.cluster.run/id ?run-id]
              [?run :seon.cluster.run/opened-at ?opened]
              [?form :seon.cluster.run.form/run ?run]
              [?form :seon.cluster.run.form/ordinal ?ordinal]
              [?form :seon.cluster.run.form/source ?source]
              [?receipt :seon.cluster.eval/run ?run]
              [?receipt :seon.cluster.eval/ordinal ?ordinal]
              [(get-else $ ?receipt :seon.cluster.eval/result-edn "-")
               ?result]
              [(get-else $ ?receipt :seon.cluster.eval/error "-") ?error]
              [(get-else $ ?receipt :seon.cluster.eval/interrupted-at "-")
               ?cut]]
            db agent-id)
       (sort-by (juxt (comp inst-ms first) #(nth % 2)))
       vec))

(defn- await-fact!
  "Wait on Datahike commits; the clock only guards the remote-provider edge."
  [connection label probe]
  (let [events (async/promise-chan)
        listener-key (keyword "context-mvp-drive" (str (UUID/randomUUID)))]
    (d/listen connection listener-key
              (fn [report]
                (when-let [value (probe (:db-after report))]
                  (async/offer! events value))))
    (try
      (when-let [value (probe @connection)]
        (async/offer! events value))
      (let [[value port]
            (async/alts!! [events (async/timeout 600000)] :priority true)]
        (when-not (= port events)
          (throw (ex-info (str "Timed out awaiting " label ".")
                          {:context-mvp-drive/label label})))
        (stamp "OK   " label " -> " (pr-str value))
        value)
      (finally
        (d/unlisten connection listener-key)))))

(defn- send-message!
  [connection agent-id content]
  (d/transact
   connection
   [{:seon.cluster.message/id (str (UUID/randomUUID))
     :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
     :seon.cluster.message/from [:seon.cluster.agent/id "root"]
     :seon.cluster.message/content content
     :seon.cluster.message/at (Date.)}]))

(defn- capturing-complete
  [complete-fn captures]
  (fn [request]
    (let [attempt (inc (count @captures))
          context (:seon.ai/prompt request)
          completion
          (if (and (= :ollama provider) (seq ollama-reasoning-effort))
            ;; Scratch qualification only: fresh Seon's target schema does not
            ;; yet carry the provider-specific field. The direct endpoint probe
            ;; must prove this exact compatible projection before a drive uses
            ;; it; no production request mechanism is added here.
            (let [base-request-body ai/request-body]
              (with-redefs [ai/request-body
                            (fn [provider-request]
                              (assoc (base-request-body provider-request)
                                     "reasoning_effort"
                                     ollama-reasoning-effort))]
                (complete-fn request)))
            (complete-fn request))
          reply (:seon.ai/text completion)]
      (swap! captures conj
             {:context-mvp-drive/attempt attempt
              :context-mvp-drive/context context
              :context-mvp-drive/completion completion})
      (print-exact! (str "TURN/ATTEMPT " attempt " CONTEXT") context)
      (if (string? reply)
        (print-exact! (str "TURN/ATTEMPT " attempt " MODEL REPLY") reply)
        (do
          (swap! exact-blocks conj
                 [(str "TURN/ATTEMPT " attempt " PROVIDER VALUE")
                  (pr-str completion)])
          (println (str "\n================ TURN/ATTEMPT " attempt
                        " PROVIDER VALUE ================"))
          (prn completion)
          (flush)))
      completion)))

(defn- drive-nursery!
  [connection process]
  (ensure-agent! connection process nursery-agent-id)
  (let [captures (atom [])
        before (closed-run-count @connection nursery-agent-id)]
    (stamp "SEND one-sentence task: " one-sentence-task)
    (with-redefs [ai/complete (capturing-complete ai/complete captures)]
      (send-message! connection nursery-agent-id one-sentence-task)
      ;; A self-message keeps unanswered triggers non-empty between ordinary
      ;; turns. A lint-refused turn instead continues from its committed
      ;; receipt with no message, so one closed run plus no unanswered trigger
      ;; is terminal only when the work derivation also says there is no next
      ;; pass. This preserves the harness's fail-fast observation without
      ;; mistaking the ruled refusal continuation for an early stop.
      (let [terminal
            (await-fact!
             connection
             "nursery MVP terminal condition"
             (fn [db]
               (let [closed (- (closed-run-count db nursery-agent-id) before)
                     unanswered (work/unanswered-triggers db nursery-agent-id)
                     continuation?
                     (work/more-agent-work?
                      db
                      {:seon.cluster.agent/id nursery-agent-id
                       :seon.cluster.run/process process
                       :seon.cluster.work/now (Date.)})]
                 (cond
                   (and (>= closed 2)
                        (empty? unanswered)
                        (not continuation?))
                   {:context-mvp-drive/outcome :complete
                    :context-mvp-drive/closed-turns closed}

                   (and (= closed 1)
                        (empty? unanswered)
                        (not continuation?))
                   {:context-mvp-drive/outcome :stopped-after-first-turn
                    :context-mvp-drive/closed-turns closed}

                   :else nil))))]
        (stamp "RUN EVIDENCE "
               (pr-str (run-evidence @connection nursery-agent-id)))
        (stamp "CAPTURE COUNT " (count @captures))
        (if (= :complete (:context-mvp-drive/outcome terminal))
          :complete
          (throw (ex-info "The model stopped before the multi-turn MVP exit."
                          terminal)))))))

(defn- birth-context
  [db agent-id depth caps]
  (render/call-with-walk-context
   {:seon.db/db db
    :seon.cluster.agent/id agent-id
    :seon.sci.admit/caps caps}
   #(render/walk {:root [:seon.cluster.agent/id agent-id]
                  :depth depth})))

(defn- effective-caps
  [db]
  (config/result-caps (config/effective db cluster-name)))

(defn- print-birth-contexts!
  [connection process]
  (doseq [agent-id [nursery-agent-id owner-agent-id]]
    (ensure-agent! connection process agent-id))
  (let [db @connection
        caps (effective-caps db)]
    (doseq [[agent-id label] [[nursery-agent-id "NURSERY"]
                              [owner-agent-id "seon.flow OWNER"]]
            depth [1 2]]
      (print-exact! (str label " BIRTH CONTEXT d" depth)
                    (birth-context db agent-id depth caps))))
  :complete)

(defn- verify-agent-walk-eval!
  [connection process]
  (ensure-agent! connection process nursery-agent-id)
  (let [db @connection
        caps (effective-caps db)
        ctx (sci.eval/fork)
        _ (sci.eval/acquire! {:seon.sci.eval/ctx ctx :seon.db/db db})
        evaluation
        (render/call-with-walk-context
         {:seon.store/branch-connection connection
          :seon.cluster.agent/id nursery-agent-id
          :seon.sci.admit/caps caps}
         #(sci.eval/evaluate
           {:seon.cluster.run.form/source "(seon.render/walk)"
            :seon.cluster.run.form/ns
            [:seon.ns/name (agent-namespace nursery-agent-id)]
            :seon.sci.admit/caps caps
            :seon.sci.eval/ctx ctx
            :seon.cluster.agent/id nursery-agent-id
            :seon.sci.eval/time-limit-ms 30000
            :seon.config/on-core-error :panic}))
        value (:seon.sci.admit/value evaluation)
        walk-text
        (cond
          (string? value) value
          (and (map? value)
               (contains? value :seon.sci.admit/truncated-string))
          (:seon.sci.admit/truncated-string value)
          :else nil)]
    (when-not (and (string? walk-text)
                   (re-find #"root=\[:seon\.cluster\.agent/id \"context-mvp\"\]"
                            walk-text))
      (throw (ex-info "Public walk was not callable through the agent eval."
                      {:context-mvp-drive/evaluation evaluation})))
    (print-exact! "REAL AGENT EVAL (seon.render/walk) RESULT" walk-text)
    (stamp "REAL AGENT EVAL capped? "
           (:seon.sci.admit/capped? evaluation))
    :complete))

(defn- start-own-cluster!
  []
  ;; A dedicated process root has no shared ancestor yet. Publish the exact
  ;; committed source tree into its own `current-src` before the scratch branch
  ;; forks; this is the same explicit operator boundary as `bin/seon init`.
  (cluster/refresh-source! process-root)
  (try
    (cluster/start! {:seon.boot/cluster-name cluster-name
                     :seon.boot/root process-root
                     :seon.config/manifest (manifest)})
    (catch Throwable failure
      ;; `start!` publishes a degraded instance in ex-data when the tower
      ;; advanced far enough to own resources. It is still this script's
      ;; instance and must be stopped before the failure is rethrown.
      (when-let [instance (:seon.boot/instance (ex-data failure))]
        (try
          (cluster/stop! instance)
          (catch Throwable stop-failure
            (.addSuppressed failure stop-failure))))
      (throw failure))))

(defn- drive!
  []
  (stamp "BOOT dedicated scratch cluster " cluster-name
         " at " process-root " with " (name provider))
  (when (and (= :ollama provider) (seq ollama-reasoning-effort))
    (stamp "OLLAMA compatible reasoning_effort="
           (pr-str ollama-reasoning-effort)))
  (let [instance (start-own-cluster!)
        connection (:seon.boot/cluster-connection instance)
        process (cluster/process-identity (:seon.boot/advertisement instance))]
    (try
      (case mode
        :nursery (do (verify-agent-walk-eval! connection process)
                     (drive-nursery! connection process))
        :birth (print-birth-contexts! connection process)
        :all (do (print-birth-contexts! connection process)
                 (verify-agent-walk-eval! connection process)
                 (drive-nursery! connection process))
        (throw (ex-info "Unknown CONTEXT_MVP_MODE."
                        {:context-mvp-drive/mode mode
                         :context-mvp-drive/known #{:nursery :birth :all}})))
      (finally
        (stamp "STOP own scratch cluster " cluster-name)
        (cluster/stop! instance)))))

(let [outcome (try
                (drive!)
                (catch Throwable failure
                  (stamp "DRIVE FAILED: " (ex-message failure))
                  (stamp "EX-DATA: " (pr-str (ex-data failure)))
                  failure))]
  (write-evidence!)
  (if (= :complete outcome)
    (do (stamp "CONTEXT MVP DRIVE COMPLETE") (System/exit 0))
    (System/exit 1)))
