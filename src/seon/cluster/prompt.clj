(ns seon.cluster.prompt
  "The prompt derives retained history on the calling turn's thread.

  It returns the exact text, ordered contribution measurements, and database
  value supplied to the provider boundary. The token budget is informative;
  prompt acquisition never clips or compacts history."
  (:require [clojure.edn :as edn]
            [seon.ai :as ai]
            [seon.ai.tokens :as tokens]
            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.render :as render]
            [seon.repl :as repl]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — resources/seon/schema.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; One walk
;;; ---------------------------------------------------------------------------

(def ^:private default-depth 2)

(defn- config-cluster-name
  [database]
  (db/q '[:find ?cluster-name .
          :where
          [_ :seon.config/cluster ?cluster-name]]
        database))

(defn- effective-ai-settings
  [database agent-id]
  (let [cluster-name (config-cluster-name database)]
    (cond
      (:seon.error/kind cluster-name)
      cluster-name

      (nil? cluster-name)
      {:seon.error/kind ::missing-cluster
       :seon.cluster.prompt/missing-cluster agent-id
       :seon.error/message
       "The prompt's database has no effective cluster configuration."
       :seon.error/data {:seon.agent/id agent-id}}

      :else
      (let [effective (config/effective database cluster-name)]
        (if (:seon.config/missing-effective effective)
          (assoc effective
                 :seon.error/kind ::missing-config
                 ::missing-config agent-id)
          (ai/settings effective (ai/agent-overlay database agent-id)))))))

(defn- calibration-for
  [database model fallback-calibration agent-id]
  (let [query (cond->
               '{:find [?attempt ?at ?characters ?usage-edn]
                 :in [$ ?model]
                 :where [[?attempt :seon.ai/model ?model]
                         [?attempt :seon.ai.attempt/at ?at]
                         [?attempt :seon.ai.attempt/usage-edn ?usage-edn]
                         [?run :seon.turn/attempts ?attempt]
                         [?capture :seon.context.capture/run ?run]
                         [?capture :seon.ai.tokens/characters ?characters]]}
                agent-id (update :in conj '?agent-id)
                agent-id (update :where into
                                 '[[?run :seon.turn/agent ?agent]
                                   [?agent :seon.agent/id ?agent-id]]))
        rows (if agent-id (db/q query database model agent-id)
                 (db/q query database model))]
    (tokens/recent-calibration
     (keep (fn [[_attempt _at characters usage-edn]]
             (let [provider-tokens (get (edn/read-string usage-edn) "prompt_tokens")]
               (when (and (int? provider-tokens) (pos? provider-tokens))
                 {:seon.ai.tokens/characters characters
                  :seon.ai.usage/prompt-tokens provider-tokens})))
           (sort-by (juxt second first) (if (:seon.error/kind rows) [] rows)))
     10 fallback-calibration)))

(defn model-calibration
  "Fit this model's characters-per-token ratio to its own recorded usage.

  THE MEASUREMENT THE BUDGET TRUSTS, derived rather than assumed. Every
  settled attempt records what the provider counted
  (`:seon.ai.attempt/usage-edn` → `prompt_tokens`) and the capture for
  the same run records the exact characters that produced it
  (`:seon.ai.tokens/characters`), so the ratio is a join over facts we
  already commit — no new writing, no tokenizer, and per model because
  tokenizers differ.

  The four-argument arity scopes the latest ten billed attempts to the agent.
  The shorter arities retain model-wide inspection for existing callers.
  A model with no recorded usage yet — a fresh cluster's first turns —
  uses the supplied measured prior without inventing an error band.
  Recorded drift stays queryable: this function IS the query."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/database-value :seon.ai/model]
     :seon.ai.tokens/calibration]
    [:=> [:cat :seon.db/database-value :seon.ai/model
          :seon.ai.tokens/calibration]
     :seon.ai.tokens/calibration]
    [:=> [:cat :seon.db/database-value :seon.ai/model
          :seon.ai.tokens/calibration :seon.agent/id]
     :seon.ai.tokens/calibration]]}
  ([database model]
   (model-calibration database model tokens/shipped-calibration))
  ([database model fallback-calibration]
   (calibration-for database model fallback-calibration nil))
  ([database model fallback-calibration agent-id]
   (calibration-for database model fallback-calibration agent-id)))

(defn agent-calibration
  "Fit an agent's recent model attempts, falling back to its config prior."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id :seon.ai/model]
                  [:or :seon.ai.tokens/calibration :seon.error/value]]}
  [database agent-id model]
  (let [settings (effective-ai-settings database agent-id)]
    (if (:seon.error/kind settings)
      settings
      (model-calibration database model
                         (tokens/prior-calibration (:seon.config.ai/chars-per-token-prior settings))
                         agent-id))))

(defn- refuse!
  [rule message]
  (throw (ex-info message
                  {:seon.error/kind ::refused
                   :seon.error/message message
                   ::rule rule
                   :seon.cluster.prompt/refused rule})))

(defn- missing-required-keys
  [explanation]
  (->> (:errors explanation)
       (keep (fn [problem]
               (when (= :malli.core/missing-key (:type problem))
                 (last (:in problem)))))
       distinct
       vec))

(defn- validate-request!
  [request]
  (when-let [explanation
             (schema/explain-candidate-value
              :seon.cluster.prompt/request request)]
    (let [missing (missing-required-keys explanation)]
      (refuse!
       ::missing-input
       (if (seq missing)
         (str "Prompt request is missing required input " (pr-str missing) ".")
         "Prompt request violates :seon.cluster.prompt/request.")))))

(defn- history-contributions
  "Price the exact retained entry segments whose concatenation is the prompt.

   Token estimates are differences between cumulative character estimates.
   That gives every separator to exactly one entry and guarantees the ordered
   decomposition sums to the same calibrated whole-prompt estimate."
  [segments calibration]
  (loop [remaining segments
         position 0
         characters 0
         estimated 0
         contributions []]
    (if-let [segment (first remaining)]
      (let [next-characters (+ characters (count segment))
            next-estimated
            (tokens/estimate-of-characters next-characters calibration)]
        (recur
         (next remaining)
         (inc position)
         next-characters
         next-estimated
         (conj contributions
               {:seon.render.block/name :walk
                :seon.context.contribution/position position
                :seon.context.contribution/text segment
                :seon.context.contribution/hash
                (context/contribution-hash segment)
                :seon.context.contribution/tokens
                (- next-estimated estimated)})))
      contributions)))

(defn- acquire-context-report
  [database request budget calibration]
  (let [distance (long (get request :seon.render/distance default-depth))
        acquired (render/acquire-context!
                  (assoc request
                         :seon.db/db database
                         :seon.render/distance distance))]
    (if (:seon.error/kind acquired)
      acquired
      (let [history (:seon.cluster.prompt/text acquired)
            frame (str (when (seq history) "\n\n")
                       (repl/frame (:seon.db/db acquired) (:seon.agent/id request)))
            text (str history frame)
            segments (conj (vec (or (:seon.render.history/segments acquired) [history])) frame)
            contributions (history-contributions segments calibration)
            report (tokens/budget-report text budget calibration)]
        {:seon.cluster.prompt/text text
         :seon.context/contributions
         (update contributions (dec (count contributions))
                 assoc :seon.render.block/name :frame)
         :seon.ai.tokens/budget-report report
         :seon.db/db (:seon.db/db acquired)}))))

(defn prompt
  "Acquire one retained walk for the agent holding the request's run.

  A turn may wake on changed facts without a message trigger. Acquisition
  selects history at its opening basis and appends the derived turn-budget
  frame. Contributions measure the exact resulting prompt.

  The returned value carries its `:seon.ai.tokens/budget-report`, so the
  size that was checked, the basis that produced it, and the margin it
  carried are all readable rather than implied."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.prompt/request]
                  :seon.cluster.prompt/result]}
  [database request]
  (validate-request! request)
  (let [agent-id (:seon.agent/id request)
        settings (effective-ai-settings database agent-id)]
    (if (:seon.error/kind settings)
      settings
      (acquire-context-report
       database request
       (:seon.config.ai/prompt-token-budget settings)
       (model-calibration
        database
        (:seon.config.ai/model settings)
        (tokens/prior-calibration
         (:seon.config.ai/chars-per-token-prior settings))
        agent-id)))))
