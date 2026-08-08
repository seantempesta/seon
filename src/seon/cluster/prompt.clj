(ns seon.cluster.prompt
  "The prompt acquires one retained entity walk from the cluster render proc.

  The loop's call site is deliberately stable: it still asks `prompt` for
  `{text, contributions, db}` and captures those exact bytes before the
  provider call. Internally there is no block selection or composition. One
  public `seon.render/walk` function returns the exact stable-prefix text and
  the final REPL-state line carries volatile basis and time after the provider
  cache boundary.

  THE BUDGET IS ENFORCED IN THE PROVIDER'S OWN UNITS. `chars/4` ran
  23-26% low against DeepSeek, so this guard was correct against a
  measurement that was not, and prompts left the process up to 3,059
  tokens over a declared 32,768 with no refusal (whole-system-arc
  observer, 2026-08-08). [[model-calibration]] now fits the ratio to
  this model's own recorded usage before the check, and the check is
  `seon.ai.tokens/budget-report` — the one judgement that carries the
  calibration's observed error band, so a prompt that only fits by less
  than the measurement's own accuracy is admitted LOUDLY instead of
  silently."
  (:require [clojure.edn :as edn]
            [taoensso.timbre :as log]
            [seon.ai :as ai]
            [seon.ai.tokens :as tokens]
            [seon.cluster.message :as message]
            [seon.config :as config]
            [seon.context :as context]
            [seon.db :as db]
            [seon.render :as render]
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
       :seon.error/message
       "The prompt's database has no effective cluster configuration."
       :seon.error/data {:seon.cluster.agent/id agent-id}}

      :else
      (let [effective (config/effective database cluster-name)]
        (if (:seon.config/missing-effective effective)
          (assoc effective :seon.error/kind ::missing-config)
          (ai/settings effective (ai/agent-overlay database agent-id)))))))

(defn model-calibration
  "Fit this model's characters-per-token ratio to its own recorded usage.

  THE MEASUREMENT THE BUDGET TRUSTS, derived rather than assumed. Every
  settled attempt records what the provider counted
  (`:seon.ai.attempt/usage-edn` → `prompt_tokens`) and the capture for
  the same run records the exact characters that produced it
  (`:seon.ai.tokens/characters`), so the ratio is a join over facts we
  already commit — no new writing, no tokenizer, and per model because
  tokenizers differ.

  A model with no recorded usage yet — a fresh cluster's first turns —
  falls back to `seon.ai.tokens/shipped-calibration`, which names
  itself as uncalibrated so nothing downstream mistakes it for
  evidence. Recorded drift stays queryable: this function IS the query."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.ai/model]
                  :seon.ai.tokens/calibration]}
  [database model]
  (let [;; `?attempt` IS IN THE FIND ON PURPOSE: a Datalog result is a
        ;; SET, so two attempts that recorded the same characters and
        ;; the same count would collapse into one sample and quietly
        ;; reweight the fit. Projecting the attempt keeps one row per
        ;; recorded attempt, which is what a sample count means.
        rows (db/q '[:find ?attempt ?characters ?usage-edn
                     :in $ ?model
                     :where
                     [?attempt :seon.ai/model ?model]
                     [?attempt :seon.ai.attempt/usage-edn ?usage-edn]
                     [?attempt :seon.ai.attempt/run ?run]
                     [?capture :seon.context.capture/run ?run]
                     [?capture :seon.ai.tokens/characters ?characters]]
                   database model)]
    (tokens/calibrate
     (keep (fn [[_attempt characters usage-edn]]
             (let [provider-tokens (get (edn/read-string usage-edn)
                                        "prompt_tokens")]
               (when (int? provider-tokens)
                 {:seon.ai.tokens/characters characters
                  :seon.ai.usage/prompt-tokens provider-tokens})))
           (if (:seon.error/kind rows) [] rows)))))

(defn- refuse!
  [rule message]
  (throw (ex-info message
                  {:seon.error/kind ::refused
                   :seon.error/message message
                   ::rule rule})))

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

(defn- walk-contribution
  [text report]
  {:seon.render.block/name :walk
   :seon.context.contribution/position 0
   :seon.context.contribution/text text
   :seon.context.contribution/hash (context/contribution-hash text)
   :seon.context.contribution/tokens (:seon.ai.tokens/estimated report)})

(defn- acquire-within-budget
  [database request budget calibration agent-id]
  (loop [distance (long (get request :seon.render/distance default-depth))]
    (let [acquired (render/acquire-context!
                    (:seon.render/context-channel request)
                    (assoc request
                           :seon.db/db database
                           :seon.render/distance distance))
          text (:seon.cluster.prompt/text acquired)
          report (tokens/budget-report text budget calibration)]
      (case (:seon.ai.tokens/verdict report)
        ;; ADMITTED, BUT NOT SILENTLY: the point estimate fits and the
        ;; calibration's own worst observed miss does not. Saying
        ;; nothing here is exactly how three over-budget prompts left
        ;; the process, so the note is loud and the report rides the
        ;; returned value where a test and a caller can both read it.
        :seon.ai.tokens/near-limit
        (do (log/warn
             (str "seon prompt for agent " agent-id
                  " is within its budget only by less than the "
                  "calibration's own accuracy: "
                  (tokens/report-sentence report)))
            {:seon.cluster.prompt/text text
             :seon.context/contributions [(walk-contribution text report)]
             :seon.ai.tokens/budget-report report
             :seon.db/db (:seon.db/db acquired)})

        :seon.ai.tokens/within
        {:seon.cluster.prompt/text text
         :seon.context/contributions [(walk-contribution text report)]
         :seon.ai.tokens/budget-report report
         :seon.db/db (:seon.db/db acquired)}

        :seon.ai.tokens/over
        (if (pos? distance)
          (recur (dec distance))
          {:seon.error/kind ::budget-exceeded
           :seon.error/message
           (str "At render distance 0 the prompt still needs "
                (tokens/report-sentence report) ". It was not sent.")
           :seon.error/data
           (assoc report
                  :seon.config.ai/prompt-token-budget budget
                  :seon.context.contribution/tokens
                  (:seon.ai.tokens/estimated report)
                  :seon.render/distance distance)})))))

(defn prompt
  "Acquire one retained walk for the agent holding the request's run.

  The held run must still have a recorded trigger; that custody invariant is
  independent of presentation. The returned contribution is forensic
  metadata for the exact one-walk text, not block membership.

  The returned value carries its `:seon.ai.tokens/budget-report`, so the
  size that was checked, the basis that produced it, and the margin it
  carried are all readable rather than implied."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.prompt/request]
                  :seon.cluster.prompt/result]}
  [database request]
  (validate-request! request)
  (let [run-id (:seon.cluster.run/id request)
        agent-id (:seon.cluster.agent/id request)
        run (db/pull database [:seon.cluster.run/background-results]
                     [:seon.cluster.run/id run-id])
        _ (or (message/trigger database run-id)
              (seq (:seon.cluster.run/background-results run))
              (refuse! ::no-trigger
                       "Prompt request's held run has no trigger or background result."))
        settings (effective-ai-settings database agent-id)]
    (if (:seon.error/kind settings)
      settings
      (acquire-within-budget
       database request
       (:seon.config.ai/prompt-token-budget settings)
       (model-calibration database (:seon.config.ai/model settings))
       agent-id))))
