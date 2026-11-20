(ns seon.render.canvas.blocks
"Living Canvas hero, Chronicle timeline, delegation panel, and router pulse Hiccup blocks."
(:require [clojure.string :as str]
          [datahike.api :as d]
          [seon.health :as health]
          [seon.render :as render]
          [seon.render.chronicle :as chronicle]
          [seon.render.tiles :as tiles]
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
      [:details {:id "cv-delegations" :class "activity-fold" :open false}
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

(defn- active-workers-in [db self-aid agent-states]
  (let [sc (when db (d/schema db))]
    (if-not (and sc (contains? sc :seon.agent/id)) []
      (let [live (into #{} (keep (fn [[k v]] (when (= :active v) k))) (when (map? agent-states) agent-states))
            rdy (if (contains? sc :seon.agent/ready?) (into #{} (comp (filter :v) (map :e)) (d/datoms db :aevt :seon.agent/ready?)) #{})
            mrg (if (contains? sc :seon.agent/merged-tx) (into #{} (map :e) (d/datoms db :aevt :seon.agent/merged-tx)) #{})
            cands (into (set (remove mrg rdy)) (keep #(:e (first (d/datoms db :avet :seon.agent/id %))) live))]
        (->> cands
             (map #(d/pull db '[:seon.agent/id :seon.agent/role :seon.agent/branch :seon.agent/scope
                                :seon.agent/archived? :seon.agent/paused?] %))
             (remove #(let [a (:seon.agent/id %)]
                        (or (nil? a) (= self-aid a) (:seon.agent/archived? %) (:seon.agent/paused? %)
                            (= :repl (:seon.agent/role %)) (#{"repl" "example"} (namespace a)))))
             (sort-by #(str (:seon.agent/id %))) (take 8) vec)))))

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
  "Fast indexed summary for any `:seon.agent/router? true` agent: active workers, last 5 merges,
   and cached health status. Derived directly from `main-db` (< 2 ms)."
  {:malli/schema [:=> [:cat :seon.host/db :keyword :any] :map]}
  [main-db self-aid agent-states]
  (let [mx (long (or (:max-tx main-db) 0))
        active (active-workers-in main-db self-aid agent-states)
        merges (mapv #(assoc % :merged-tx (:main-tx %)) (chronicle/merged-agents-in-range main-db 0 mx 5))
        rep (try (health/report main-db) (catch Throwable _ {:ok? true :problems []}))
        probs (:problems rep)
        err-n (count (filter #(= :error (:severity %)) probs))]
    {:active active :recent-merges merges :latest-merge-tx (long (or (:main-tx (first merges)) 0))
     :health {:ok? (boolean (:ok? rep)) :errors err-n :warnings (- (count probs) err-n)}}))

(def chronicle-css
  "Scoped Phosphor dark/light stylesheet for Living System Understanding & Chronicle timeline blocks."
  ".ch-hero{position:relative;overflow:hidden;container-type:inline-size;background:linear-gradient(180deg,color-mix(in srgb,var(--signal) 4%,var(--base-900)) 0%,var(--base-900) 48%);border:1px solid var(--base-800);border-radius:12px;padding:18px 20px;display:flex;flex-direction:column;gap:13px}\n.ch-hero::before{content:'';position:absolute;inset:0 0 auto 0;height:2px;background:linear-gradient(90deg,var(--signal),color-mix(in srgb,var(--info) 65%,transparent) 55%,transparent)}\n.ch-hero.is-past{border-color:color-mix(in srgb,var(--signal) 45%,var(--base-800))}\n.ch-hero-top,.ch-hero-foot,.ch-stream-head,.ch-entry-head{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap}\n.ch-kicker{display:inline-flex;align-items:center;gap:6px;font-family:var(--font-mono);font-size:10.5px;letter-spacing:.12em;text-transform:uppercase;color:var(--signal);font-weight:600}\n.ch-time{font-family:var(--font-mono);font-size:11px;font-variant-numeric:tabular-nums;color:var(--text-400)}\n.ch-hero-title{margin:0;font-family:var(--font-text);font-size:clamp(19px,2.3vw,24px);line-height:1.24;font-weight:650;letter-spacing:-.022em;color:var(--text-50)}\n.ch-hero-body{font-family:var(--font-text);font-size:14px;line-height:1.62;color:var(--text-200)}\n.ch-hero-body .prose-blocks{gap:7px}\n.ch-hero-body .prose-list{gap:4px;margin:0}\n.ch-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:8px}\n@container (max-width:560px){.ch-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}}\n.ch-kpi{background:var(--base-950);border:1px solid var(--base-800);border-radius:8px;padding:9px 11px;display:flex;flex-direction:column;gap:2px;text-align:left;min-width:0;font:inherit;color:inherit;transition:border-color .14s,background .14s}\n.ch-kpi.is-btn{cursor:pointer}\n.ch-kpi.is-btn:hover{border-color:var(--signal);background:var(--base-850)}\n.ch-kpi-label{font-family:var(--font-mono);font-size:10px;text-transform:uppercase;letter-spacing:.09em;color:var(--text-400)}\n.ch-kpi-val{font-family:var(--font-mono);font-size:13.5px;font-weight:650;font-variant-numeric:tabular-nums;color:var(--text-50);display:inline-flex;align-items:center;gap:6px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}\n.ch-kpi-val.green{color:var(--success)}.ch-kpi-val.red{color:var(--error)}.ch-kpi-val.amber{color:var(--signal)}.ch-kpi-val.blue{color:var(--info)}\n.ch-kpi-sub{font-family:var(--font-mono);font-size:10.5px;color:var(--text-400);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}\n.ch-hero-foot{padding-top:10px;border-top:1px solid var(--base-800)}\n.ch-ref-btn{background:var(--base-950);border:1px solid var(--base-800);color:var(--text-200);padding:2px 9px;border-radius:999px;font-family:var(--font-mono);font-size:11px;cursor:pointer;display:inline-flex;align-items:center;gap:5px;transition:border-color .14s,color .14s}\n.ch-ref-btn:hover{border-color:var(--signal);color:var(--text-50)}\n.ch-ref-btn.is-active{border-color:rgba(240,180,41,.45);color:var(--signal)}\n.ch-ref-pill{border:1px solid var(--base-800);color:var(--text-400);padding:2px 8px;border-radius:999px;font-family:var(--font-mono);font-size:10.5px}\n.ch-stream{display:flex;flex-direction:column;gap:10px}\n.ch-stream-head{padding:0 2px}\n.ch-timeline{list-style:none;margin:0;padding:0 0 0 15px;border-left:1px solid var(--base-800);display:flex;flex-direction:column;gap:8px}\n.ch-entry{position:relative;background:var(--base-900);border:1px solid var(--base-800);border-radius:9px;padding:11px 14px;display:flex;flex-direction:column;gap:6px;transition:border-color .14s}\n.ch-entry:hover{border-color:var(--base-700)}\n.ch-entry.is-current{border-color:var(--signal);background:color-mix(in srgb,var(--signal) 5%,var(--base-900))}\n.ch-node{position:absolute;left:-20px;top:15px;width:9px;height:9px;border-radius:50%;border:2px solid var(--base-950);background:var(--text-400)}\n.ch-node.amber{background:var(--signal)}.ch-node.green{background:var(--success)}.ch-node.blue{background:var(--info)}.ch-node.purple{background:var(--eval)}\n.ch-entry-title{display:inline-flex;align-items:center;gap:7px;min-width:0;font-size:13.5px;font-weight:600;color:var(--text-100)}\n.ch-entry-agent{background:none;border:none;padding:0;color:var(--text-100);font-family:var(--font-mono);font-size:12.5px;font-weight:600;cursor:pointer;text-align:left}\n.ch-entry-agent:hover{color:var(--signal)}\n.ch-entry-meta{display:inline-flex;align-items:center;gap:7px;flex-shrink:0}\n.ch-tx-btn{background:var(--base-950);border:1px solid var(--base-800);color:var(--text-300);padding:2px 8px;border-radius:5px;font-family:var(--font-mono);font-size:10.5px;font-variant-numeric:tabular-nums;cursor:pointer;transition:border-color .14s,color .14s,background .14s}\n.ch-tx-btn:hover{border-color:var(--signal);color:var(--signal);background:rgba(240,180,41,.08)}\n.ch-entry-desc{margin:0;font-size:12.5px;line-height:1.5;color:var(--text-300);display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}\n.ch-secondary{display:flex;flex-direction:column;gap:8px;padding-top:10px;border-top:1px solid var(--base-800)}\n.ch-secondary .activity-fold{background:transparent}\n.ch-secondary .activity-fold[open]{background:var(--base-900)}\n.ch-older-wrap{margin-top:4px}\n.ch-older-wrap>.ch-timeline{margin-top:10px}\n.ch-pending-card{position:relative;background:var(--base-900);border:1px solid var(--base-800);border-radius:12px;padding:16px 20px;display:flex;flex-direction:column;gap:10px;overflow:hidden}\n.ch-pending-card::before{content:'';position:absolute;inset:0 0 auto 0;height:2px;background:linear-gradient(90deg,var(--signal),var(--info) 65%,transparent)}\n.ch-pending-head{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}\n.ch-pending-card .ch-pending-title{margin:0;font-size:17px;font-weight:600;line-height:1.35;letter-spacing:-.015em;color:var(--text-100)}\n.ch-pending-status{display:inline-flex;align-items:center;gap:7px;background:rgba(240,180,41,.10);border:1px solid rgba(240,180,41,.32);color:var(--signal);border-radius:999px;padding:3px 10px;font-family:var(--font-mono);font-size:11px;font-weight:500;cursor:pointer}\n.ch-pending-status:hover{background:rgba(240,180,41,.18);border-color:var(--signal)}\n.ch-prompt-more{margin-top:2px}\n.ch-prompt-more>summary{cursor:pointer;font-family:var(--font-mono);font-size:11px;color:var(--text-400);user-select:none}\n.ch-prompt-more>summary:hover{color:var(--text-200)}\n.ch-prompt-more-body{margin-top:8px;padding-top:8px;border-top:1px solid var(--base-800)}\n.ch-live-delta{display:flex;flex-direction:column;gap:6px;padding:10px 12px;border-radius:8px;background:rgba(52,211,153,.06);border:1px solid rgba(52,211,153,.24)}\n.ch-live-delta-head{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap;font-family:var(--font-mono);font-size:11px;color:var(--ok,#34d399)}\n.ch-live-delta-row{display:flex;flex-wrap:wrap;gap:6px;align-items:center;font-size:12px;color:var(--text-200)}")

(defn stale-canvas?
  "True when a router/coordinator agent's manual `:seon.agent/canvas` is superseded by newer `:main`
   merges, a newer Chronicle summary, or > 150 transactions of drift."
  {:malli/schema [:=> [:cat :map] :boolean] :cost/ticks 100000 :cost/bytes 131072}
  [opts]
  (let [router? (boolean (:router? opts))
        i #(if (integer? (get opts %)) (long (get opts %)) 0)
        ctx-tx (i :canvas-tx)]
    (boolean (and router?
                  (or (zero? ctx-tx)
                      (> (i :latest-merge-tx) ctx-tx)
                      (> (i :latest-summary-tx) ctx-tx)
                      (> (- (i :main-tx) ctx-tx) 150))))))

(defn render-chronicle-style
  "Returns the scoped `<style id=\"seon-chronicle-css\">` Hiccup element (stripped by `hiccup->text`)."
  {:malli/schema [:=> [:cat] [:vector :any]] :cost/ticks 50000 :cost/bytes 65536}
  []
  [:style {:id "seon-chronicle-css"} (render/raw-html chronicle-css)])

(defn living-canvas-data
  "Fast (< 2 ms) unified Datahike snapshot powering Living System Understanding and Chronicle on coordinator Canvases."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/conn] :keyword :any [:maybe :int]] :map]}
  [eff-main-db conn aid agent-states as-of-tx]
  (let [mx (long (or as-of-tx (chronicle/db-max-tx eff-main-db)))
        nav-db (or (:origin-db eff-main-db) eff-main-db)
        nav-mx (chronicle/db-max-tx nav-db)
        ms (chronicle/tx-instant-ms nav-db mx)
        sums (chronicle/latest-summaries eff-main-db {:limit 10})
        latest (first sums)
        ltx (long (or (:seon.summary/to-tx latest) 0))
        merges (chronicle/merged-agents-in-range eff-main-db 0 mx 12)
        nav-sums (if as-of-tx (chronicle/latest-summaries nav-db {:limit 10}) sums)
        nav-merges (if as-of-tx (chronicle/merged-agents-in-range nav-db 0 nav-mx 12) merges)
        dist (chronicle/distill-tx-window eff-main-db {:from-tx (if latest ltx (max 0 (- mx 500)))
                                                       :to-tx mx :conn (when (nil? as-of-tx) conn)
                                                       :preloaded-merges merges})
        active (if (nil? as-of-tx) (active-workers-in eff-main-db aid agent-states) [])
        msince (if latest (count (filter #(> (long (or (:main-tx %) 0)) ltx) merges)) (count merges))
        fs {:has-summary? (some? latest) :last-summary-tx ltx :main-tx mx :tx-span (max 0 (- mx ltx))
            :merges-since msince :namespaces-since (count (:touched-nss dist)) :health-changed? false}]
    {:summaries sums :latest-summary latest
     :hero-summary (or latest (chronicle/synthesize-summary dist {:agent aid :created-ms (or ms 0)}))
     :synthesized? (nil? latest) :distilled dist :freshness (merge fs (chronicle/chronicle-due? fs))
     :entries (chronicle/merge-chronicle-entries nav-sums nav-merges)
     :active active :recent-merges merges :latest-merge-tx (long (or (:main-tx (first merges)) 0))
     :health (:health dist) :learning-metrics (:learning-metrics dist)
     :main-tx mx :main-ms ms :as-of-tx as-of-tx
     :bench-runs (if (and (nil? as-of-tx) (contains? (d/schema nav-db) :seon.bench.run/id))
                   (->> (d/datoms nav-db :aevt :seon.bench.run/id)
                        (map #(d/pull nav-db '[*] (:e %)))
                        (sort-by #(long (or (:seon.bench.run/started-ms %) 0)) #(compare %2 %1))
                        (take 3)
                        vec)
                   [])}))

(defn- render-ref-chip [rf]
  (cond
    (and (keyword? rf) (not= "branch" (namespace rf)))
    [:button {:type "button" :class "ch-ref-btn" :on-click (list 'seon.web/view-agent! rf :canvas)}
     (if-let [ns-p (namespace rf)] (if (= "agent" ns-p) (name rf) (str ns-p "/" (name rf))) (name rf))]
    (and (vector? rf) (= 2 (count rf)) (= :seon.agent/id (first rf)) (keyword? (second rf)))
    [:button {:type "button" :class "ch-ref-btn" :on-click (list 'seon.web/view-agent! (second rf) :canvas)}
     (str (second rf))]
    :else [:span {:class "ch-ref-pill"} (str rf)]))

(defn- hero-kpis [living]
  (let [h (or (:health living) {:ok? true :errors 0 :warnings 0})
        lm (:learning-metrics living)
        dist (:distilled living)
        merges (:recent-merges living)
        mx (:main-tx living 0)
        as-of (:as-of-tx living)
        ts (chronicle/format-epoch-utc (when (integer? (:main-ms living)) (:main-ms living)))]
    [{:label "Health" :val (if (:ok? h true) "Healthy" (str (:errors h 0) " err"))
      :sub (str (:warnings h 0) " advisories") :tone (if (:ok? h true) "green" "red")
      :dot (if (:ok? h true) "live" "bad") :action '(seon.web/set-view-mode! :debug)}
     {:label (if (and (map? lm) (pos? (long (or (:attempted lm) 0)))) "Merged (10)" "Recent Merges")
      :val (if (and (map? lm) (pos? (long (or (:attempted lm) 0))))
             (str (:merged lm 0) "/" (:attempted lm) " · " (:merge-rate-pct lm 0) "%")
             (str (count merges)))
      :sub (if (and (map? lm) (:median-turns-merged lm)) (str "median " (:median-turns-merged lm) " turns")
               (str (count (:touched-nss dist)) " ns touched")) :tone "amber"}
     {:label (if (and (map? lm) (integer? (:kv-cache-hit-pct lm))) "KV Cache" "Codebase")
      :val (if (and (map? lm) (integer? (:kv-cache-hit-pct lm))) (str (:kv-cache-hit-pct lm) "%")
               (str (:total-forms dist 0) " forms"))
      :sub (if (and (map? lm) (integer? (:eval-error-rate-pct lm))) (str (:eval-error-rate-pct lm) "% eval err")
               (str (:total-schemas dist 0) " schemas")) :tone "blue"}
     {:label (if as-of "Snapshot" "Head") :val (str "tx " mx)
      :sub (if-not (str/blank? ts) ts (str (:total-forms dist 0) " forms"))
      :tone (if as-of "amber" "")}]))

(defn render-living-hero
  "Renders the Living System Understanding hero `<article>` with semantic heading, concise narrative,
   4 responsive `@container` KPI cards, active worker pills, and clickable ref chips."
  {:malli/schema [:=> [:cat :map [:? :map]] [:vector :any]] :cost/ticks 2000000 :cost/bytes 4194304}
  ([living] (render-living-hero living {}))
  ([living opts]
   (let [l (if (map? living) living {}) o (if (map? opts) opts {})
         hero (if (map? (:hero-summary l)) (:hero-summary l) {})
         kind (if (keyword? (:seon.summary/kind hero)) (:seon.summary/kind hero) :system-pulse)
         as-of (when (integer? (:as-of-tx l)) (long (:as-of-tx l)))
         synthesized? (boolean (:synthesized? l))
         htag (if (:primary-heading? o) :h1 :h2)
         title (let [ht (:hero-title o) t (:seon.summary/title hero)] (cond (and synthesized? (empty? (:entries l)) (string? ht) (not (str/blank? ht))) ht (and (string? t) (not (str/blank? t))) t :else "Living System Understanding"))
         body (when (string? (:seon.summary/body hero)) (:seon.summary/body hero))
         ms (or (when (integer? (:seon.summary/created-ms hero)) (:seon.summary/created-ms hero))
                (when (integer? (:main-ms l)) (:main-ms l)))
         ts (chronicle/format-epoch-utc (when (and (integer? ms) (pos? (long ms)) (<= (long ms) 4102444800000)) (long ms)))
         tx-lbl (chronicle/tx-range-label (:seon.summary/from-tx hero) (:seon.summary/to-tx hero))
         active (if (sequential? (:active l)) (vec (filter map? (:active l))) [])
         refs (if (sequential? (:seon.summary/refs hero)) (vec (take 8 (:seon.summary/refs hero))) [])
         vis (:seon.summary/visual hero)
         fresh (when (map? (:freshness l)) (:freshness l))
         msince (if (integer? (:merges-since fresh)) (long (:merges-since fresh)) 0)
         kpis (hero-kpis l)]
     [:article {:id "cv-living-hero" :class (str "ch-hero" (when as-of " is-past")) :aria-label "Living system understanding"}
      [:header {:class "ch-hero-top"}
       [:div {:class "pill-row"}
        [:span {:class "ch-kicker"}
         [:span {:aria-hidden "true"} "◆"]
         (cond as-of "Historical Snapshot" synthesized? "Living Understanding" :else (str "Chronicle · " (name kind)))]
        (when-not (str/blank? tx-lbl) [:span {:class "badge muted"} tx-lbl])
        (when (and (not synthesized?) (pos? msince) (nil? as-of))
          [:span {:class "badge amber"} (str "+" msince " merge" (when (not= 1 msince) "s") " since")])]
       [:div {:class "pill-row"}
        (when-not (str/blank? ts) [:time {:class "ch-time"} ts])
        (when (seq active)
          [:span {:class "badge amber"} [:span {:class "dot pulsing" :aria-hidden "true"}] (str (count active) " active")])]]
      [htag {:class "ch-hero-title"} title]
      (let [dist (when (map? (:distilled l)) (:distilled l))
            new-merges (when (and (not as-of) (not synthesized?))
                         (vec (filter #(> (long (or (:main-tx %) 0)) (long (or (:seon.summary/to-tx hero) 0)))
                                      (or (:recent-merges l) []))))
            new-nss (when (and (not as-of) (not synthesized?)) (vec (take 6 (:touched-nss dist))))]
        (when (or (seq new-merges) (seq new-nss))
          [:div
          {:id "cv-live-delta", :class "ch-live-delta"}
          [:div
           {:class "ch-live-delta-head"}
           [:span
            {:class "row-sm", :style "gap:6px"}
            [:span {:class "dot live pulsing", :aria-hidden "true"}]
            (str "LIVE UPDATES · since tx " (or (:seon.summary/to-tx hero) 0))]
           [:span
            {:class "ch-mono-xs"}
            (str
              (count new-merges)
              " merged · "
              (count (:touched-nss dist))
              " ns touched")]]
          [:div
           {:class "ch-live-delta-row"}
           (for
             [m (take 4 new-merges) :let [mid (:agent m)] :when (keyword? mid)]
             [:button
              {:type "button",
               :class "ch-ref-btn",
               :on-click (list 'seon.web/view-agent! mid :canvas)}
              (str
                "✓ "
                (if-let [np (namespace mid)]
                  (str np "/" (name mid))
                  (name mid)))])
           (for
             [entry
              (take 4 new-nss)
              :let
              [nsym (if (map? entry) (:ns entry) entry)]
              :when
              nsym]
             (render-ref-chip nsym))]]))
      (when (and body (not (str/blank? body)))
        [:div {:class "ch-hero-body"} (render-markdown-blocks body)])
      [:div {:class "ch-metrics"}
       (for [{:keys [label val sub tone dot action]} kpis]
         (if action
           [:button {:type "button" :class "ch-kpi is-btn" :on-click action}
            [:span {:class "ch-kpi-label"} label]
            [:span {:class (str "ch-kpi-val " tone)}
             (when dot [:span {:class (str "dot " dot) :aria-hidden "true"}]) val]
            [:span {:class "ch-kpi-sub"} sub]]
           [:div {:class "ch-kpi"}
            [:span {:class "ch-kpi-label"} label]
            [:span {:class (str "ch-kpi-val " tone)} val]
            [:span {:class "ch-kpi-sub"} sub]]))]
      (when (and (vector? vis) (keyword? (first vis)))
        [:div {:class "canvas-surface"} vis])
      (when (seq refs)
      [:footer {:class "ch-hero-foot"}
       [:div {:class "pill-row"}
        (for [rf refs] (render-ref-chip rf))]])])))

(defn- render-chronicle-item [idx entry as-of-tx]
  (let [k (if (keyword? (:entry-kind entry)) (:entry-kind entry) :system-pulse)
        tone (case k (:milestone :architecture) "amber" :merge "green" :compaction "purple" "blue")
        title (let [t (:title entry)] (if (and (string? t) (not (str/blank? t))) t "Entry"))
        body (when (string? (:body entry)) (str/trim (:body entry)))
        ft (when (integer? (:from-tx entry)) (long (:from-tx entry)))
        tt (when (integer? (:to-tx entry)) (long (:to-tx entry)))
        tx-lbl (chronicle/tx-range-label ft tt)
        ms (when (integer? (:ms entry)) (long (:ms entry)))
        ts (chronicle/format-epoch-utc (when (and ms (pos? ms) (<= ms 4102444800000)) ms))
        ag (when (keyword? (:agent entry)) (:agent entry))
        refs (when (sequential? (:refs entry)) (vec (take 4 (:refs entry))))
        cur? (boolean (and as-of-tx tt (= (long as-of-tx) tt)))]
    [:li {:id (str "ch-item-" idx) :class (str "ch-entry" (when cur? " is-current"))}
     [:span {:class (str "ch-node " tone) :aria-hidden "true"}]
     [:div {:class "ch-entry-head"}
      [:div {:class "ch-entry-title"}
       [:span {:class (str "badge " tone)} (name k)]
       (if (and (= :merge k) ag)
         [:button {:type "button" :class "ch-entry-agent" :on-click (list 'seon.web/view-agent! ag :canvas)} title]
         [:span title])]
      [:div {:class "ch-entry-meta"}
       (when-not (str/blank? ts) [:time {:class "ch-time"} ts])
       (when (and tt (pos? tt) (not (str/blank? tx-lbl)))
         [:button {:type "button" :class "ch-tx-btn" :aria-label (str "Time-travel to " tx-lbl)
                   :on-click (list 'seon.web/jump-canvas-tx! tt)}
          (if cur? (str "Viewing · " tx-lbl) tx-lbl)])]]
     (when (and body (not (str/blank? body)))
       [:p {:class "ch-entry-desc"} body])
     (when (seq refs)
       [:div {:class "pill-row"} (for [rf refs] (render-ref-chip rf))])]))

(defn render-chronicle-stream
  "Renders the reverse-chronological Chronicle `<section>` with a clean vertical rail, top 5 visible
   entries, 1-click Datahike time-travel pills, and older milestones tucked in one `<details>`."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]] [:? :map]] :any] :cost/ticks 2000000 :cost/bytes 4194304}
  ([entries] (render-chronicle-stream entries {}))
  ([entries opts]
   (let [es (vec (filter map? entries))]
     (when (seq es)
       (let [o (if (map? opts) opts {})
             as-of (when (integer? (:as-of-tx o)) (long (:as-of-tx o)))
             vis-n (max 1 (long (if (integer? (:visible o)) (:visible o) 5)))
             shown (vec (take vis-n es))
             older (vec (drop vis-n es))
             txs (keep #(when (integer? (:to-tx %)) (long (:to-tx %))) es)
             span-lbl (when (seq txs) (chronicle/tx-range-label (apply min txs) (apply max txs)))]
         [:section {:id "cv-chronicle" :class "ch-stream" :aria-label "Chronicle timeline"}
          [:div {:class "ch-stream-head"}
           [:h2 {:class "kicker"} (str "Chronicle · " (count es) " milestone" (when (not= 1 (count es)) "s"))]
           (when-not (str/blank? span-lbl) [:span {:class "ch-time"} span-lbl])]
          [:ol {:class "ch-timeline"}
           (map-indexed #(render-chronicle-item %1 %2 as-of) shown)]
          (when (seq older)
            [:details {:id "ch-older" :class "activity-fold ch-older-wrap"}
             [:summary (str "Earlier milestones · " (count older) " more")]
             [:ol {:class "ch-timeline"}
              (map-indexed #(render-chronicle-item (+ vis-n %1) %2 as-of) older)]])])))))

(defn render-bench-runs
  "Renders active `:running` benchmark runs prominently and tucks settled runs cleanly in a fold."
  {:malli/schema [:function
                  [:=> [:cat [:maybe [:sequential :map]]] :any]
                  [:=> [:cat [:maybe [:sequential :map]] [:maybe :int]] :any]]
   :cost/ticks 2000000 :cost/bytes 2097152}
  ([runs] (render-bench-runs runs nil))
  ([runs ref-ms]
   (let [rs (vec (filter map? (or runs [])))
         active (filterv #(= :running (:seon.bench.run/status %)) rs)
         recent (filterv #(not= :running (:seon.bench.run/status %)) rs)
         latest-done (first recent)
         fin-ms (when latest-done
                  (long (or (:seon.bench.run/finished-ms latest-done)
                            (:seon.bench.run/updated-ms latest-done) 0)))
         fresh-done? (and latest-done
                          (or (nil? ref-ms)
                              (< (abs (- (long ref-ms) (long fin-ms))) 900000)))
         shown (vec (concat active (when (and (empty? active) fresh-done?) [latest-done])))
         older (if (and (empty? active) fresh-done?) (vec (rest recent)) recent)]
     (when (or (seq shown) (seq older))
       [:div {:id "cv-bench-runs" :class "stack-sm"}
        (for [r shown] (tiles/bench-run-tile r))
        (when (seq older)
          [:details {:id "cv-bench-older" :class "activity-fold"}
           [:summary (str "Past benchmark runs · " (count older))]
           [:div {:class "stack-sm" :style "margin-top:8px"}
            (for [r older] (tiles/bench-run-tile r))]])]))))