(ns seon.render.canvas.blocks
"Living Canvas hero, Chronicle timeline, delegation panel, and router pulse Hiccup blocks."
(:require [clojure.string :as str]
          [datahike.api :as d]
          [seon.health :as health]
          [seon.render :as render]
          [seon.render.chronicle :as chronicle]
          [seon.render.data :as rdata :refer [first-doc-line]]
          [seon.render.markdown :as rmd :refer [render-markdown-blocks]]))

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

(def chronicle-css
  "Scoped Phosphor dark/light stylesheet for Living System Understanding & Chronicle timeline blocks."
  ".ch-hero{position:relative;overflow:hidden;container-type:inline-size;background:linear-gradient(180deg,color-mix(in srgb,var(--signal) 4%,var(--base-900)) 0%,var(--base-900) 48%);border:1px solid var(--base-800);border-radius:12px;padding:18px 20px;display:flex;flex-direction:column;gap:13px}
.ch-hero::before{content:'';position:absolute;inset:0 0 auto 0;height:2px;background:linear-gradient(90deg,var(--signal),color-mix(in srgb,var(--info) 65%,transparent) 55%,transparent)}
.ch-hero.is-past{border-color:color-mix(in srgb,var(--signal) 45%,var(--base-800))}
.ch-hero-top,.ch-hero-foot,.ch-stream-head,.ch-entry-head{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}
.ch-kicker{display:inline-flex;align-items:center;gap:6px;font-family:var(--font-mono);font-size:10.5px;letter-spacing:.12em;text-transform:uppercase;color:var(--signal);font-weight:600}
.ch-time{font-family:var(--font-mono);font-size:11px;font-variant-numeric:tabular-nums;color:var(--text-400)}
.ch-hero-title{margin:0;font-family:var(--font-text);font-size:clamp(19px,2.3vw,24px);line-height:1.24;font-weight:650;letter-spacing:-.022em;color:var(--text-50)}
.ch-hero-body{font-family:var(--font-text);font-size:14px;line-height:1.62;color:var(--text-200)}
.ch-hero-body .prose-blocks{gap:7px}
.ch-hero-body .prose-list{gap:4px;margin:0}
.ch-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}
@container (max-width:560px){.ch-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}}
.ch-kpi{background:var(--base-950);border:1px solid var(--base-800);border-radius:8px;padding:9px 11px;display:flex;flex-direction:column;gap:2px;text-align:left;min-width:0;font:inherit;color:inherit;transition:border-color .14s,background .14s}
.ch-kpi.is-btn{cursor:pointer}
.ch-kpi.is-btn:hover{border-color:var(--signal);background:var(--base-850)}
.ch-kpi-label{font-family:var(--font-mono);font-size:10px;text-transform:uppercase;letter-spacing:.09em;color:var(--text-400)}
.ch-kpi-val{font-family:var(--font-mono);font-size:13.5px;font-weight:650;font-variant-numeric:tabular-nums;color:var(--text-50);display:inline-flex;align-items:center;gap:6px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.ch-kpi-val.green{color:var(--success)}.ch-kpi-val.red{color:var(--error)}.ch-kpi-val.amber{color:var(--signal)}.ch-kpi-val.blue{color:var(--info)}
.ch-kpi-sub{font-family:var(--font-mono);font-size:10.5px;color:var(--text-400);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.ch-hero-foot{padding-top:10px;border-top:1px solid var(--base-800)}
.ch-ref-btn{background:var(--base-950);border:1px solid var(--base-800);color:var(--text-200);padding:2px 9px;border-radius:999px;font-family:var(--font-mono);font-size:11px;cursor:pointer;display:inline-flex;align-items:center;gap:5px;transition:border-color .14s,color .14s}
.ch-ref-btn:hover{border-color:var(--signal);color:var(--text-50)}
.ch-ref-btn.is-active{border-color:rgba(240,180,41,.45);color:var(--signal)}
.ch-ref-pill{border:1px solid var(--base-800);color:var(--text-400);padding:2px 8px;border-radius:999px;font-family:var(--font-mono);font-size:10.5px}
.ch-stream{display:flex;flex-direction:column;gap:10px}
.ch-stream-head{padding:0 2px}
.ch-timeline{list-style:none;margin:0;padding:0 0 0 15px;border-left:1px solid var(--base-800);display:flex;flex-direction:column;gap:8px}
.ch-entry{position:relative;background:var(--base-900);border:1px solid var(--base-800);border-radius:9px;padding:11px 14px;display:flex;flex-direction:column;gap:6px;transition:border-color .14s}
.ch-entry:hover{border-color:var(--base-700)}
.ch-entry.is-current{border-color:var(--signal);background:color-mix(in srgb,var(--signal) 5%,var(--base-900))}
.ch-node{position:absolute;left:-20px;top:15px;width:9px;height:9px;border-radius:50%;border:2px solid var(--base-950);background:var(--text-400)}
.ch-node.amber{background:var(--signal)}.ch-node.green{background:var(--success)}.ch-node.blue{background:var(--info)}.ch-node.purple{background:var(--eval)}
.ch-entry-title{display:inline-flex;align-items:center;gap:7px;min-width:0;font-size:13.5px;font-weight:600;color:var(--text-100)}
.ch-entry-agent{background:none;border:none;padding:0;color:var(--text-100);font-family:var(--font-mono);font-size:12.5px;font-weight:600;cursor:pointer;text-align:left}
.ch-entry-agent:hover{color:var(--signal)}
.ch-entry-meta{display:inline-flex;align-items:center;gap:7px;flex-shrink:0}
.ch-tx-btn{background:var(--base-950);border:1px solid var(--base-800);color:var(--text-300);padding:2px 8px;border-radius:5px;font-family:var(--font-mono);font-size:10.5px;font-variant-numeric:tabular-nums;cursor:pointer;transition:border-color .14s,color .14s,background .14s}
.ch-tx-btn:hover{border-color:var(--signal);color:var(--signal);background:rgba(240,180,41,.08)}
.ch-entry-desc{margin:0;font-size:12.5px;line-height:1.5;color:var(--text-300);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.ch-secondary{display:flex;flex-direction:column;gap:8px;padding-top:10px;border-top:1px solid var(--base-800)}
.ch-secondary .activity-fold{background:transparent}
.ch-secondary .activity-fold[open]{background:var(--base-900)}
.ch-older-wrap{margin-top:4px}")