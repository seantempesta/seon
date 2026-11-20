(ns seon.render.wire
  "Single-source LLM wire context & ephemeral `<neighborhood-activity>` request builder."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.model :as model]
            [seon.render :as render]
            [seon.store :as store]
            [seon.render.data :as rdata :refer [agent-evals-in agent-messages-in compact-entity-preview evals->turns first-doc-line pull-agent-entity router-agent?]]
            [seon.render.tail :as tail]))

(defn neighborhood-recent-activity
"Delegates to composable ephemeral tail renderer `seon.render.tail/neighborhood-activity`."
{:malli/schema [:=> [:cat :any :any :map] [:maybe :string]]}
[main-db branch-db ag]
(:text (tail/neighborhood-activity {:main-db main-db :branch-db branch-db :agent ag})))

(defonce ^:seon.flow/edge wire-request-cache
(store/lru-cache 8))

(defn- wire-datoms-sig [ds]
  (reduce (fn [[n mx] dt] [(inc n) (max mx (long (:tx dt 0)))]) [0 0] ds))

(defn- wire-db-sig [db agent-id]
  (let [s (:schema db)]
    [(count s)
     (when (contains? s :seon.agent/id)
       [(d/pull db '[:seon.agent/goal :seon.agent/system-prompt :seon.agent/scope
                   :seon.agent/branch :seon.agent/examples :seon.agent/pinned
                   :seon.agent/context-prefs :seon.agent/router?
                   :seon.agent/tail-renders :seon.agent/last-proofs
                   :seon.config/viewport-screenshot?]
              [:seon.agent/id agent-id])
       (when (contains? s :seon.config/viewport-screenshot?)
         (store/config-val db agent-id :seon.config/viewport-screenshot?))])
     (when (contains? s :seon.agent/id) (wire-datoms-sig (d/datoms db :aevt :seon.agent/id)))
     (when (contains? s :seon.agent.eval/agent) (wire-datoms-sig (d/datoms db :avet :seon.agent.eval/agent agent-id)))
     (when (contains? s :seon.agent.message/agent) (wire-datoms-sig (d/datoms db :avet :seon.agent.message/agent agent-id)))
     (when (contains? s :seon.agent.call/callee) (wire-datoms-sig (d/datoms db :avet :seon.agent.call/callee agent-id)))
     (when (contains? s :seon.agent.call/caller) (wire-datoms-sig (d/datoms db :avet :seon.agent.call/caller agent-id)))
     (when (contains? s :seon.health.fault/id) (wire-datoms-sig (d/datoms db :aevt :seon.health.fault/id)))
     (when (contains? s :seon.task/id) (wire-datoms-sig (d/datoms db :aevt :seon.task/id)))
     (when (contains? s :seon.code.form/hash) (wire-datoms-sig (d/datoms db :aevt :seon.code.form/hash)))
     (when (contains? s :seon.schema/key) (wire-datoms-sig (d/datoms db :aevt :seon.schema/key)))
     (when (contains? s :seon.test/pass?) (wire-datoms-sig (d/datoms db :aevt :seon.test/pass?)))]))

(defn wire-cache-key [main-db branch-db agent-id vision? viewport-screenshot]
  (when (and main-db branch-db
             (not (instance? datahike.db.AsOfDB main-db))
             (not (instance? datahike.db.AsOfDB branch-db))
             (not (instance? datahike.db.HistoricalDB main-db))
             (not (instance? datahike.db.HistoricalDB branch-db)))
    (let [mcid (try (d/commit-id main-db) (catch Throwable _ nil))
          bcid (try (d/commit-id branch-db) (catch Throwable _ nil))]
      (when (and mcid bcid)
        [agent-id
         (wire-db-sig main-db agent-id)
         (if (= mcid bcid) :same-db [bcid (:max-tx branch-db)])
         (boolean vision?)
         (when viewport-screenshot
           [(:width viewport-screenshot) (:height viewport-screenshot)
            (:bytes viewport-screenshot) (:captured-at viewport-screenshot)
            (hash (:data-url viewport-screenshot)) (hash (:base64 viewport-screenshot))])]))))

(defn build-live-wire-request
  "Single source of truth for an agent's LLM context AND Debug View:
   Walks `branch-db`, projects discovered tiles via `render/render-value` -> `render/hiccup->text`,
   frames `:seon.agent/examples` in `<session kind=\"example\">...</session>`, and attaches ephemeral
   tail `<neighborhood-activity>` + `<live-viewport>` without polluting the static KV-cache prefix."
  {:malli/schema [:=> [:cat :any :any :keyword :map] :map]}
  [main-db branch-db agent-id {:keys [viewport-screenshot vision?]}]
  (let [ck (wire-cache-key main-db branch-db agent-id vision? viewport-screenshot)
        compute
        (fn []
          (let [ag (pull-agent-entity main-db branch-db agent-id)
              evals (agent-evals-in branch-db agent-id)
              msgs (agent-messages-in main-db agent-id)
              seen-ids (store/agent-seen-message-ids branch-db agent-id)
              unread (filterv #(not (contains? seen-ids (:seon.agent.message/id %))) msgs)
              tiles (render/discover-renderable-tiles main-db branch-db ag evals health/report)
              tile-projections
              (mapv (fn [{:keys [val] :as tile}]
                      (let [h (render/render-value branch-db val {:mode :agent :compact? true :main-db main-db :agent ag})
                            txt (render/hiccup->text h)]
                        (assoc tile :hiccup h :text txt)))
                    tiles)
              walk-text (str/join "\n\n---\n\n"
                                  (map (fn [{:keys [schema source handle text]}]
                                         (str "### [" (name source)
                                              (when handle (str " " handle))
                                              " · " schema "]\n" text))
                                       tile-projections))
              tail-res (tail/render-tail-blocks
                        {:main-db main-db
                         :branch-db branch-db
                         :agent ag
                         :opts {:viewport-screenshot viewport-screenshot
                                :vision? (boolean vision?)}})
              tail-blocks (:blocks tail-res)
              activity-text (not-empty (str/join "\n\n" (keep :text tail-blocks)))
              examples (vec
                        (for [ex-id (sort (:seon.agent/examples ag))
                              :when (first (d/datoms main-db :avet :seon.agent/id ex-id))
                              :let [ex-ag (d/pull main-db '[:seon.agent/id :seon.agent/goal] [:seon.agent/id ex-id])
                                    ex-evals (agent-evals-in main-db ex-id)]]
                          {:id ex-id
                           :goal (:seon.agent/goal ex-ag)
                           :turns (evals->turns ex-evals)}))
              wire (model/build-agent-wire-messages
                    {:thinking? true :replay-reasoning? true :vision? (boolean vision?)}
                    {:agent-id agent-id
                     :branch (:seon.agent/branch ag :main)
                     :role (:seon.agent/role ag :worker)
                     :goal (:seon.agent/goal ag)
                     :system-prompt (:seon.agent/system-prompt ag)
                     :examples examples
                     :walk-text walk-text
                     :tail-blocks tail-blocks
                     :ephemeral-image (:image tail-res)
                     :history (evals->turns evals (into {} (map (juxt :seon.agent.message/id identity)) msgs))
                     :unread unread
                     :viewport-screenshot viewport-screenshot})]
          (assoc wire
                 :agent ag
                 :tiles tile-projections
                 :tail-blocks tail-blocks
                 :activity-text activity-text
                 :evals evals
                 :messages-inbox msgs
                 :unread unread)))]
    (if ck
      (store/lru-get! wire-request-cache 8 ck compute)
      (compute))))