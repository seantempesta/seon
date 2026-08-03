(ns seon.cluster.prompt
  "The prompt acquires one retained entity walk from the cluster render proc.

  The loop's call site is deliberately stable: it still asks `prompt` for
  `{text, contributions, db}` and captures those exact bytes before the
  provider call. Internally there is no block selection or composition. One
  public `seon.render/walk` function returns the exact stable-prefix text and
  the final REPL-state line carries volatile basis and time after the provider
  cache boundary."
  (:require [seon.ai.tokens :as tokens]
            [seon.cluster.message :as message]
            [seon.context :as context]
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

(defn prompt
  "Acquire one retained walk for the agent holding the request's run.

  The held run must still have a recorded trigger; that custody invariant is
  independent of presentation. The returned contribution is forensic
  metadata for the exact one-walk text, not block membership."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.prompt/request]
                  :seon.cluster.prompt/rendered-context]}
  [db request]
  (validate-request! request)
  (let [run-id (:seon.cluster.run/id request)
        _ (or (message/trigger db run-id)
              (refuse! ::no-trigger
                       "Prompt request's held run has no recorded trigger."))
        acquired (render/acquire-context!
                  (:seon.render/context-channel request)
                  (assoc request
                         :seon.db/db db
                         :seon.render/distance
                         (long (get request :seon.render/distance
                                    default-depth))))
        text (:seon.cluster.prompt/text acquired)]
    {:seon.cluster.prompt/text text
     :seon.context/contributions [(walk-contribution text)]
     :seon.db/db (:seon.db/db acquired)}))
