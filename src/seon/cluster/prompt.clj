(ns seon.cluster.prompt
  "The prompt acquires one retained entity walk from the cluster render proc.

  The loop's call site is deliberately stable: it still asks `prompt` for
  `{text, contributions, db}` and captures those exact bytes before the
  provider call. Internally there is no block selection or composition. One
  public `seon.render/walk` function returns the exact stable-prefix text and
  the final REPL-state line carries volatile basis and time after the provider
  cache boundary."
  (:require [seon.ai :as ai]
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

(defn- agent-cluster-name
  [database agent-id]
  (db/q '[:find ?cluster-name .
          :in $ ?agent-id
          :where
          [?agent :seon.cluster.agent/id ?agent-id]
          [?agent :seon.cluster.agent/cluster ?cluster]
          [?cluster :seon.cluster/name ?cluster-name]]
        database agent-id))

(defn- prompt-token-budget
  [database agent-id]
  (let [cluster-name (agent-cluster-name database agent-id)]
    (cond
      (:seon.error/kind cluster-name)
      cluster-name

      (nil? cluster-name)
      {:seon.error/kind ::missing-cluster
       :seon.error/message
       "The prompt's agent is not connected to a cluster."
       :seon.error/data {:seon.cluster.agent/id agent-id}}

      :else
      (let [effective (config/effective database cluster-name)]
        (if (:seon.config/missing-effective effective)
          (assoc effective :seon.error/kind ::missing-config)
          (:seon.config.ai/prompt-token-budget
           (ai/settings effective (ai/agent-overlay database agent-id))))))))

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
  [text]
  {:seon.render.block/name :walk
   :seon.context.contribution/position 0
   :seon.context.contribution/text text
   :seon.context.contribution/hash (context/contribution-hash text)
   :seon.context.contribution/tokens (tokens/estimate text)})

(defn- acquire-within-budget
  [database request budget]
  (loop [distance (long (get request :seon.render/distance default-depth))]
    (let [acquired (render/acquire-context!
                    (:seon.render/context-channel request)
                    (assoc request
                           :seon.db/db database
                           :seon.render/distance distance))
          text (:seon.cluster.prompt/text acquired)
          estimated (tokens/estimate text)]
      (cond
        (<= estimated budget)
        {:seon.cluster.prompt/text text
         :seon.context/contributions [(walk-contribution text)]
         :seon.db/db (:seon.db/db acquired)}

        (pos? distance)
        (recur (dec distance))

        :else
        {:seon.error/kind ::budget-exceeded
         :seon.error/message
         (str "The prompt still needs " estimated
              " estimated tokens at render distance 0, exceeding its "
              budget "-token provider budget.")
         :seon.error/data
         {:seon.config.ai/prompt-token-budget budget
          :seon.context.contribution/tokens estimated
          :seon.render/distance distance}}))))

(defn prompt
  "Acquire one retained walk for the agent holding the request's run.

  The held run must still have a recorded trigger; that custody invariant is
  independent of presentation. The returned contribution is forensic
  metadata for the exact one-walk text, not block membership."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.prompt/request]
                  :seon.cluster.prompt/result]}
  [database request]
  (validate-request! request)
  (let [run-id (:seon.cluster.run/id request)
        run (db/pull database [:seon.cluster.run/background-results]
                     [:seon.cluster.run/id run-id])
        _ (or (message/trigger database run-id)
              (seq (:seon.cluster.run/background-results run))
              (refuse! ::no-trigger
                       "Prompt request's held run has no trigger or background result."))
        budget (prompt-token-budget database
                                    (:seon.cluster.agent/id request))]
    (if (:seon.error/kind budget)
      budget
      (acquire-within-budget database request budget))))
