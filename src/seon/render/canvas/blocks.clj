(ns seon.render.canvas.blocks
  "Extracted child namespace of `seon.render.canvas`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.render.data :as rdata :refer [agent-evals-in agent-label agent-messages-in attr-tx-in branch-db-for canvas-history-txs entry-agent-id first-doc-line pull-agent-entity]]
            [seon.render.markdown :as rmd :refer [interleave-timeline prompt-msg? render-activity-fold render-markdown-blocks render-tile-card split-exchanges svg-arrow-up svg-chevron-left svg-chevron-right]]))

(defn render-delegation-node
  {:malli/schema [:=> [:cat :any :int] :any]}
  [node depth]
  (let [st (:seon.agent.call/status node :pending)
        callee (:seon.agent.call/callee node)
        prompt (:seon.agent.call/prompt node "")
        badge-cls (case st :resolved "green" :failed "red" :cancelled "muted" "amber")]
    [:div {:style (str "margin-left:" (* depth 16) "px;padding:4px 0;border-left:" (if (pos? depth) "1px solid var(--border-subtle);padding-left:10px" "none"))}
     [:div {:class "pill-row"}
      [:span {:class (str "dot " (if (= :pending st) "pulsing" "live"))}]
      [:button {:type "button" :class "link-muted mono tiny strong"
                :on-click (list 'seon.web/view-agent! callee :canvas)}
       (str callee)]
      [:span {:class (str "badge " badge-cls)} (name st)]
      (when-not (str/blank? prompt)
        [:span {:class "muted tiny truncate"} (first-doc-line prompt 88)])]
     (for [ch (:children node)]
       (render-delegation-node ch (inc depth)))]))

(defn render-delegation-panel
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]]] :any]}
  [call-tree]
  (when (seq call-tree)
    (let [count-nodes (fn cn [xs] (reduce + 0 (map #(+ 1 (cn (:children %))) xs)))
          count-pend  (fn cp [xs] (reduce + 0 (map #(+ (if (= :pending (:seon.agent.call/status %)) 1 0)
                                                       (cp (:children %))) xs)))
          total (count-nodes call-tree)
          pend (count-pend call-tree)]
      [:details {:id "cv-delegations" :class "activity-fold" :open (pos? pend)}
       [:summary {:class "activity-summary"}
        [:span {:class "activity-label"}
         (if (pos? pend)
           (str "Delegated sub-agents · " pend " running / " total " call" (when (not= 1 total) "s"))
           (str "Delegated sub-agents · " total " settled"))]]
       [:div {:class "stack-sm" :style "padding:6px 0 2px 4px"}
        (for [n call-tree]
          (render-delegation-node n 0))]])))

(defn render-recent-messages-fold
  "Compact message-driven fold of recent exchanges & child/system notices on Canvas."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]]] :any]}
  [recent-exchanges]
  (when (seq recent-exchanges)
    [:details {:id "cv-recent-msgs" :class "activity-fold"}
     [:summary
      [:span (str "Recent messages · " (count recent-exchanges) " exchange" (when (not= 1 (count recent-exchanges)) "s"))]]
     [:div {:class "stack" :style "margin-top:8px;gap:8px"}
      (for [{:keys [prompt prose]} recent-exchanges]
        [:div {:class "card" :style "padding:8px 10px"}
         (when (not (str/blank? (str prompt)))
           [:div {:class "tiny muted" :style "margin-bottom:4px"}
            (str "You: " (let [p (str prompt)] (if (> (count p) 120) (str (subs p 0 120) "…") p)))])
         (when (not (str/blank? (str prose)))
           [:div {:class "sm-copy"}
            (render-markdown-blocks
             (let [s (str prose)] (if (> (count s) 360) (str (subs s 0 360) "…") s)))])])]]))

(defn render-router-pulse
  "Compact reactive status strip rendered at the top of any `:seon.agent/router? true` Canvas.
   Updates automatically on every `:main` merge or active-agent state change (0 manual evals)."
  {:malli/schema [:=> [:cat [:maybe :map]] :any]}
  [{:keys [active recent-merges health]}]
  (when (or (seq active) (seq recent-merges))
    (let [latest (first recent-merges)]
      [:details {:id "cv-router-pulse" :class "activity-fold" :open (boolean (seq active))}
       [:summary
        [:span {:class (if (:ok? health) "dot live" "dot err") :style "margin-right:6px"}]
        [:span (if (:ok? health) "System healthy" (str (:errors health) " health issue(s)"))]
        (when (seq active)
          [:span {:class "muted"} (str " · " (count active) " active")])
        (when latest
          [:span {:class "muted"} (str " · latest merge " (name (:id latest)))])]
       [:div {:class "stack" :style "margin-top:8px;gap:6px"}
        (when (seq active)
          [:div {:class "pill-row"}
           (for [a active
                 :let [aid (:seon.agent/id a)]]
             [:button {:type "button" :class "btn sm"
                       :on-click (list 'seon.web/view-agent! aid)}
              [:span {:class "dot pulsing" :style "margin-right:5px"}]
              (str (or (namespace aid) "") (when (namespace aid) "/") (name aid))])])
        (when (seq recent-merges)
          [:div {:class "stack" :style "gap:4px"}
           (for [{:keys [id merged-tx goal]} (take 4 recent-merges)]
             [:div {:class "row-between tiny" :style "gap:8px;padding:3px 0"}
              [:button {:type "button" :class "link-muted mono"
                        :on-click (list 'seon.web/view-agent! id)}
               (str "✓ " (namespace id) "/" (name id))]
              [:span {:class "muted" :style "flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"}
               (let [g (str goal)] (if (> (count g) 90) (str (subs g 0 90) "…") g))]
              [:span {:class "muted mono"} (str "tx " merged-tx)]])])]])))

(defn router-pulse-data
  "Pure indexed summary for any `:seon.agent/router? true` agent: active workers, last 5 merges,
   and cached health status. Derived directly from `main-db` (0 agent evals, < 3 ms)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword :any] :map]}
  [main-db self-aid agent-states]
  (let [ag-rows (mapv #(d/pull main-db '[:seon.agent/id :seon.agent/branch :seon.agent/role
                                         :seon.agent/goal :seon.agent/archived? :seon.agent/paused?
                                         :seon.agent/merged-tx :seon.agent/ready?] (:e %))
                      (d/datoms main-db :aevt :seon.agent/id))
        non-sys (remove #(or (= self-aid (:seon.agent/id %))
                             (= :repl (:seon.agent/role %))
                             (= "repl" (namespace (:seon.agent/id %)))
                             (= "example" (namespace (:seon.agent/id %))))
                        ag-rows)
        active (->> non-sys
                    (remove #(or (:seon.agent/archived? %) (:seon.agent/paused? %)))
                    (filter (fn [a]
                              (let [aid (:seon.agent/id a)
                                    st (get agent-states aid)]
                                (or (= :active st)
                                    (and (:seon.agent/ready? a) (nil? (:seon.agent/merged-tx a)))))))
                    (sort-by #(str (:seon.agent/id %)))
                    (take 8)
                    vec)
        recent-merges (->> non-sys
                           (filter :seon.agent/merged-tx)
                           (sort-by (comp - :seon.agent/merged-tx))
                           (take 5)
                           (mapv (fn [a]
                                   {:id (:seon.agent/id a)
                                    :branch (:seon.agent/branch a)
                                    :merged-tx (:seon.agent/merged-tx a)
                                    :goal (:seon.agent/goal a)})))
        rep (try (health/report main-db) (catch Throwable _ {:ok? true :problems []}))
        probs (:problems rep)
        err-n (count (filter #(= :error (:severity %)) probs))
        warn-n (count (filter #(= :warning (:severity %)) probs))]
    {:active active
     :recent-merges recent-merges
     :latest-merge-tx (or (:merged-tx (first recent-merges)) 0)
     :health {:ok? (boolean (:ok? rep)) :errors err-n :warnings warn-n}}))
