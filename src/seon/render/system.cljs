(ns seon.render.system
  "Root's authority-backed cluster view.

   The execution child invokes [[system-view]] at its inherited immutable
   coordinate. One database query returns ordinary agent rows; this namespace
   only derives state and formats the human and AI twins. It never receives a
   Datahike value and never recursively renders another agent's surfaces."
  (:require
   [clojure.string :as str]
   [seon.db :as db]
   [seon.derive :as derive]
   [seon.schema :as schema]))

(def ^:private root-id "root")

(schema/register! ::state [:enum :idle :running :paused :terminated])
(schema/register!
 ::agent
 [:map
  [:seon.agent/id :string]
  [:seon.agent/purpose {:optional true} :string]
  [::state ::state]])
(schema/register! ::agents [:vector ::agent])

(def ^:private agent-query
  '[:find (pull ?agent
                [:seon.agent/id
                 :seon.agent/purpose
                 :seon.agent/terminated-at
                 {:seon.agent/run
                  [:seon.agent.run/status :seon.agent.run/paused-at]}])
    :where
    [?agent :seon.agent/id]])

(defn- agent-state [agent]
  (let [run (:seon.agent/run agent)
        open? (= :open (:seon.agent.run/status run))]
    (derive/state-from-primitives
     (cond-> {:seon.agent.run/open? open?}
       (:seon.agent/terminated-at agent)
       (assoc :seon.agent/terminated-at (:seon.agent/terminated-at agent))
       (and open? (:seon.agent.run/paused-at run))
       (assoc :seon.agent.run/paused-at (:seon.agent.run/paused-at run))))))

(defn fleet-summary
  "Derive the ordered cluster summary from ordinary query rows."
  {:malli/schema [:=> [:cat [:sequential [:tuple :map]]] ::agents]}
  [rows]
  (->> rows
       (keep first)
       (keep (fn [agent]
               (when-let [id (:seon.agent/id agent)]
                 (cond-> {:seon.agent/id id
                          ::state (agent-state agent)}
                   (string? (:seon.agent/purpose agent))
                   (assoc :seon.agent/purpose (:seon.agent/purpose agent))))))
       (sort-by (juxt #(if (= root-id (:seon.agent/id %)) 0 1)
                      :seon.agent/id))
       vec))

(def ^:private state-style
  {:idle ["●" "text-text-500"]
   :running ["●" "text-amber-400"]
   :paused ["●" "text-text-400"]
   :terminated ["○" "text-text-600"]})

(defn- agent-card [{id :seon.agent/id purpose :seon.agent/purpose state ::state}]
  (let [[dot style] (state-style state)
        root? (= root-id id)]
    [:a {:href (if root? "/" (str "/agent/" id))
         :class (str "flex min-h-32 flex-col gap-2 rounded border p-3 "
                     "bg-base-900/50 hover:border-amber-600 "
                     (if root? "border-amber-700/60" "border-base-800"))}
     [:div {:class "flex items-center gap-2 font-mono"}
      (when root? [:span {:class "text-amber-400"} "★"])
      [:span {:class "truncate text-sm text-text-100"} id]
      [:span {:class (str "ml-auto text-xs " style)} dot " " (name state)]]
     [:div {:class "text-xs text-text-400"}
      (or purpose (if root? "cluster supervisor" "no purpose recorded"))]
     [:div {:class "mt-auto text-right text-xs text-amber-500"} "open →"]]))

(defn- human-view [agents]
  (let [counts (frequencies (map ::state agents))]
    [:div {:class "seon-card flex flex-col bg-base-950 text-text-200"}
     (into
      [:div {:class (str "flex flex-wrap items-center gap-3 border-b "
                         "border-base-800 bg-base-900/60 px-3 py-2 "
                         "text-xs font-mono")}
       [:span {:class "font-semibold text-text-100"}
        (str (count agents) " agents")]]
      (concat
       (map (fn [state]
              (let [[dot style] (state-style state)]
                [:span {:key (name state) :class style}
                 dot " " (get counts state 0) " " (name state)]))
            [:idle :running :paused :terminated])
       [[:a {:href "/data"
             :class "ml-auto text-amber-500 hover:text-amber-300"}
         "database →"]]))
     (into
      [:div {:class "grid gap-3 p-3"
             :style "grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));"}]
      (map agent-card)
      agents)]))

(defn- ai-view [agents]
  (str/join
   "\n"
   (cons "; SYSTEM VIEW — cluster agents"
         (map (fn [{id :seon.agent/id purpose :seon.agent/purpose state ::state}]
                (str "; - " id " [" (name state) "]"
                     (when purpose (str " — " purpose))))
              agents))))

(defn ^:async system-view
  "Render root's human and AI cluster views at the child's pinned coordinate."
  {:malli/schema [:=> [:cat :map :any] :seon.render/html-response]}
  [_render-input _invoke-selected!]
  (let [rows (await (db/query {::db/query agent-query}))
        agents (fleet-summary rows)]
    {:seon.render/hiccup (human-view agents)
     :seon.render/ai (ai-view agents)}))
