(ns seon.cluster.prompt
  "The prompt is exactly one fresh entity walk at the turn's database value.

  The loop's call site is deliberately stable: it still asks `prompt` for
  `{text, contributions, db}` and captures those exact bytes before the
  provider call. Internally there is no block selection or composition. One
  `seon.render.walk/neighborhood` value is projected by
  `seon.render.walk/prose`, followed by the one REPL-state line that marks the
  deliberately uncached boundary."
  (:require [datahike.api :as d]
            [seon.ai.tokens :as tokens]
            [seon.cluster.message :as message]
            [seon.context :as context]
            [seon.render.block :as block]
            [seon.render.walk :as walk]
            [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/prompt.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; One walk
;;; ---------------------------------------------------------------------------

(def ^:private default-depth 2)

(defn- refuse!
  [rule data]
  (throw (ex-info (str "prompt refused: " (name rule))
                  (assoc data :seon.error/kind ::refused ::rule rule))))

(defn- repl-state
  [db agent-id]
  (let [basis (long (:max-tx db))
        namespace-name
        (d/q '[:find ?name .
               :in $ ?agent-id
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?agent :seon.cluster.agent/namespace ?namespace]
               [?namespace :seon.ns/name ?name]]
             db agent-id)
        instant (:db/txInstant (d/pull db [:db/txInstant] basis))]
    (str ";; REPL state namespace=" (pr-str namespace-name)
         " basis=" basis
         " time=" (pr-str instant))))

(defn- walk-contribution
  [text]
  {:seon.render.block/name :walk
   :seon.render/kind :seon.render/ai
   :seon.context.contribution/position 0
   :seon.context.contribution/text text
   :seon.context.contribution/hash (context/contribution-hash text)
   :seon.context.contribution/tokens (tokens/estimate text)
   :seon.context.contribution/band :dynamic
   :seon.render/projection 'seon.render.walk/prose})

(defn prompt
  "Derive one fresh walk for the agent holding the request's run.

  The held run must still have a recorded trigger; that custody invariant is
  independent of presentation. The returned contribution is forensic
  metadata for the exact one-walk text, not block membership."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       :seon.cluster.prompt/request]
                  :seon.cluster.prompt/rendered-context]}
  [db request]
  (let [run-id (:seon.cluster.run/id request)
        agent-id (:seon.cluster.agent/id request)
        caps (:seon.sci.admit/caps request)
        depth (long (get request :seon.render/distance default-depth))
        _ (or (message/trigger db run-id)
              (refuse! ::no-trigger request))
        neighborhood
        (walk/neighborhood
         {:seon.db/db db
          :seon.render.walk/lookup [:seon.cluster.agent/id agent-id]
          :seon.render/kind :seon.render/ai
          :seon.render/floor `block/data-prose
          :seon.render/overrides {}
          :seon.render/distance depth
          :seon.sci.admit/caps caps})
        text (str (walk/prose db neighborhood)
                  "\n" (repl-state db agent-id))]
    {:seon.cluster.prompt/text text
     :seon.context/contributions [(walk-contribution text)]
     :seon.db/db db}))
