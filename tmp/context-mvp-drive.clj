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
            [seon.cluster :as cluster]
            [seon.cluster.work :as work]
            [seon.render.block :as block]
            [seon.render.walk :as walk])
  (:import [java.nio.charset StandardCharsets]
           [java.util Date UUID]))

(def ^:private mode
  (keyword (or (System/getenv "CONTEXT_MVP_MODE") "nursery")))

(def ^:private provider
  (keyword (or (System/getenv "CONTEXT_MVP_PROVIDER") "ollama")))

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
  "Print `text` without transforming it, framed by its exact UTF-8 length."
  [label text]
  (let [payload (utf8-bytes text)]
    (println (str "\n================ " label " (" (alength payload)
                  " UTF-8 BYTES) ================"))
    (.write System/out payload 0 (alength payload))
    ;; This newline and the end marker are framing, not part of `payload`.
    (println (str "\n================ END " label " ================"))
    (flush)))

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
          completion (complete-fn request)
          reply (:seon.ai/text completion)]
      (swap! captures conj
             {:context-mvp-drive/attempt attempt
              :context-mvp-drive/context context
              :context-mvp-drive/completion completion})
      (print-exact! (str "TURN/ATTEMPT " attempt " CONTEXT") context)
      (if (string? reply)
        (print-exact! (str "TURN/ATTEMPT " attempt " MODEL REPLY") reply)
        (do
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
      ;; The first turn must send itself a message; that durable fact opens the
      ;; second turn. Requiring two closed runs is the live multi-turn exit.
      (await-fact!
       connection
       "two nursery turns closed and no trigger remains unanswered"
       (fn [db]
         (let [closed (- (closed-run-count db nursery-agent-id) before)]
           (when (and (>= closed 2)
                      (empty? (work/unanswered-triggers db nursery-agent-id)))
             {:context-mvp-drive/closed-turns closed})))))
    (stamp "RUN EVIDENCE " (pr-str (run-evidence @connection nursery-agent-id)))
    (when (< (count @captures) 2)
      (throw (ex-info "The drive closed two runs without two provider replies."
                      {:context-mvp-drive/captures (count @captures)})))
    :complete))

(defn- birth-context
  [db depth]
  (walk/prose
   db
   (walk/neighborhood
    {:seon.db/db db
     :seon.render.walk/lookup [:seon.cluster.agent/id owner-agent-id]
     :seon.render/distance depth
     :seon.render/kind :seon.render/ai
     :seon.render/floor `block/data-prose
     :seon.render/overrides {}
     :seon.sci.admit/caps
     {:seon.config.eval.result/max-depth 12
      :seon.config.eval.result/max-collection 64
      :seon.config.eval.result/max-string 16384
      :seon.config.eval.result/max-nodes 4096}})))

(defn- print-birth-contexts!
  [connection process]
  (ensure-agent! connection process owner-agent-id)
  (let [db @connection]
    (doseq [depth [1 2]]
      (print-exact! (str "seon.flow OWNER BIRTH CONTEXT d" depth)
                    (birth-context db depth))))
  :complete)

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
  (let [instance (start-own-cluster!)
        connection (:seon.boot/cluster-connection instance)
        process (cluster/process-identity (:seon.boot/advertisement instance))]
    (try
      (case mode
        :nursery (drive-nursery! connection process)
        :birth (print-birth-contexts! connection process)
        :all (do (print-birth-contexts! connection process)
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
  (if (= :complete outcome)
    (do (stamp "CONTEXT MVP DRIVE COMPLETE") (System/exit 0))
    (System/exit 1)))
