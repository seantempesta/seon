(ns seon.render.ui
  "Inside-Out Reagent/Hiccup UI driven 100% by Datahike (`:user/default` -> `:seon.user/*`) +
   schema-attached entity tiles powering BOTH the live 120fps Web UI AND the agent's wire context:
   - Everything about where the user is (`:seon.user/viewing`, `:seon.user/view-mode`,
     `:seon.user/sidebar-open?`, `:seon.user/expanded-agents`, `:seon.user/selected-branch`) lives
     in Datahike — restoring LMDB restores the exact screen, and any agent can move the user's view
     via `(user/show-user! ...)`!
   - Collapsible recursive left sidebar (`#seon-sidebar`) shows the full Root -> sub-agent ->
     sub-sub-agent hierarchy via `:seon.agent/parent` refs.
   - Three views of the viewed agent: `:canvas` (clean editorial user canvas), `:workspace`
     (2-panel chat + live `@ctx`/eval tiles), and `:debug` (exact wire request + diagnostics)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [datahike.api :as d]
            [fipp.edn :as fipp]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.health :as health]
            [seon.merge :as merge]
            [seon.model :as model]
            [seon.reconciler :as reconciler]
            [seon.render :as render]
            [seon.store :as store]
            [seon.render.data :as rdata]
            [seon.render.markdown :as rmd]
            [seon.render.tiles :as rtiles]
            [seon.render.ns-tile :as rnstile]
            [seon.render.wire :as rwire]))

(def default-canvas-syms rdata/default-canvas-syms)

(defn entry-agent-id [& args] (apply rdata/entry-agent-id args))

;; --- Inside-Out User Viewport State (Read Directly From Datahike `:user/default`) ---

(defn agent-id->slug [& args] (apply rdata/agent-id->slug args))

(defn slug->agent-id [& args] (apply rdata/slug->agent-id args))

(defn agent-href [& args] (apply rdata/agent-href args))
(defn debug-href [& args] (apply rdata/debug-href args))

(defn agent-label [& args] (apply rdata/agent-label args))

(defn- router-agent? [& args] (apply rdata/router-agent? args))

(defn- reads-main? [& args] (apply rdata/reads-main? args))

(defn user-view-state [& args] (apply rdata/user-view-state args))

;; --- Branch, Agent & Datahike History Time-Travel Helpers ---

(defn- branch-db-for [& args] (apply rdata/branch-db-for args))

(defn- pull-agent-entity [& args] (apply rdata/pull-agent-entity args))

(defn canvas-history-txs [& args] (apply rdata/canvas-history-txs args))

(defn- agent-evals-in [& args] (apply rdata/agent-evals-in args))

(defn- agent-messages-in [& args] (apply rdata/agent-messages-in args))

(defn- evals->turns [& args] (apply rdata/evals->turns args))

(defn- first-doc-line [& args] (apply rdata/first-doc-line args))

(defn build-live-wire-request [& args] (apply rwire/build-live-wire-request args))

;; --- Schema-Attached Entity Tile Renderers (`:seon.render/hiccup`) ---

(defn ctx-tile [& args] (apply rtiles/ctx-tile args))

(defn health-panel [& args] (apply rtiles/health-panel args))

(defn ns-tile [& args] (apply rnstile/ns-tile args))

(defn var-tile [& args] (apply rtiles/var-tile args))

(defn doc-tile [& args] (apply rtiles/doc-tile args))

(defn schema-tile [& args] (apply rtiles/schema-tile args))

(defn call-tile [& args] (apply rtiles/call-tile args))

(defn task-tile [& args] (apply rtiles/task-tile args))

(defn agent-tile [& args] (apply rtiles/agent-tile args))

(def ^:private svg-chevron-left rmd/svg-chevron-left)

(def ^:private svg-chevron-right rmd/svg-chevron-right)

;; --- Quiet Phosphor Top Bar (`id="seon-header"` — Always 1 Calm Row, Even at Half-Screen!) ---

(defn header-unit [db {:keys [boot-seq agent-states]}]
  (let [{:keys [entry-id viewing-id view-mode sidebar-open? theme]} (user-view-state db)
        h (health/report db)
        ok? (:ok? h)
        err-n (count (filter #(= :error (:severity %)) (:problems h)))
        warn-n (- (count (:problems h)) err-n)
        active? (= :active (get agent-states viewing-id))
        mode-btn (fn [m label]
                   [:button {:type "button"
                             :class (str "nav-pill" (when (= view-mode m) " active"))
                             :on-click (list 'seon.web/set-view-mode! m)}
                    label])]
    [:header {:id "seon-header" :class "topbar"
              :data-boot-seq (str (or boot-seq 1))
              :data-theme (name (or theme :auto))}
     [:script {:id "boot-meta" :type "application/json"}
      (render/raw-html (str "{\"bootSeq\":" (or boot-seq 1) ",\"mainTx\":" (:max-tx db) "}"))]
     [:div {:class "brand-row"}
      [:div {:class "brand"}
       [:button {:type "button"
                 :class (str "icon-btn sidebar-toggle" (when sidebar-open? " active"))
                 :aria-label (if sidebar-open? "Hide sidebar" "Show sidebar")
                 :title (if sidebar-open? "Hide sidebar" "Show sidebar")
                 :on-click '(seon.web/toggle-sidebar!)}
        svg-sidebar]
       [:button {:type "button" :class "brand-link"
                 :on-click (list 'seon.web/view-agent! entry-id :canvas)}
        [:span {:class "diamond"} "◆"]
        [:strong {:class "wordmark"} "seon"]]
       [:span {:class "crumb-sep"} "/"]
       [:span {:class "crumb" :title (str viewing-id)} (agent-label db viewing-id)]
       (when active?
         [:span {:class "live-pill"}
          [:span {:class "dot pulsing"}]
          "working"])]
      [:div {:class "stats-strip"}
       [:button {:type "button"
                 :class "icon-btn theme-toggle"
                 :aria-label (if (= theme :light) "Dark theme" "Light theme")
                 :title (if (= theme :light) "Switch to dark theme" "Switch to light theme")
                 :on-click '(seon.web/toggle-theme!)}
        (if (= theme :light) svg-moon svg-sun)]
       [:button {:type "button"
                 :class (str "status-pill " (if ok? "ok" "bad"))
                 :title (if ok?
                          (if (pos? warn-n)
                            (str "System healthy (" warn-n " efficiency/structure advisories) — open Debug")
                            "System healthy — open Debug")
                          (str err-n " error(s) — open Debug"))
                 :on-click '(seon.web/set-view-mode! :debug)}
        [:span {:class (str "dot " (if ok? "live" "bad"))}]
        [:span {:class "status-label"}
         (if ok? "Healthy" (str err-n " issue" (when (not= 1 err-n) "s")))]]
       [:div {:class "mode-group"}
        (mode-btn :canvas "Canvas")
        (mode-btn :workspace "Workspace")
        (mode-btn :debug "Debug")]]]]))

;; --- Collapsible Recursive Left Agent-Tree Sidebar (`id="seon-sidebar"` — Clean, Calm Real App) ---

(defn- render-agent-tree-node [children-by-parent viewing-id expanded-set agent-states depth ag]
  (let [aid (:seon.agent/id ag)
        kids (get children-by-parent aid)
        has-kids? (boolean (seq kids))
        expanded? (contains? expanded-set aid)
        selected? (= aid viewing-id)
        working? (= :active (get agent-states aid))]
    [:div {:class "tree-branch"}
     [:div {:class (str "tree-row" (when selected? " active"))
            :style (str "padding-left:" (+ 8 (* depth 14)) "px")}
      (if has-kids?
        [:button {:type "button" :class "tree-exp"
                  :on-click (list 'seon.web/toggle-expand! aid)}
         (if expanded? "▾" "▸")]
        [:span {:class "tree-exp-spacer"} "·"])
      [:button {:type "button" :class "tree-select"
                :title (str aid)
                :on-click (list 'seon.web/view-agent! aid :canvas)}
       [:span {:class (str "dot " (if working? "pulsing" "live"))}]
       [:span {:class "tree-name"} (agent-label ag aid)]
       (when working?
         [:span {:class "badge amber tree-role"} "live"])]]
     (when (and has-kids? expanded?)
       (for [child kids]
         (render-agent-tree-node children-by-parent viewing-id expanded-set agent-states (inc depth) child)))]))

(defn sidebar-unit [db {:keys [agent-states]}]
  (let [{:keys [viewing-id sidebar-open? expanded-agents]} (user-view-state db)
        all-agents (->> (d/datoms db :aevt :seon.agent/id)
                        (map #(d/pull db '[:seon.agent/id :seon.agent/parent :seon.agent/branch
                                           :seon.agent/role :seon.agent/scope :seon.agent/goal
                                           :seon.agent/label :seon.agent/entry?] (:e %)))
                        (remove #(str/starts-with? (str (namespace (:seon.agent/id %))) "example"))
                        (sort-by (juxt #(if (:seon.agent/entry? %) 0 1)
                                       #(if (:seon.agent/parent %) 1 0)
                                       #(str (:seon.agent/id %))))
                        vec)
        by-id (into {} (map (juxt :seon.agent/id identity)) all-agents)
        children-by-parent (group-by :seon.agent/parent all-agents)
        roots (filterv (fn [a]
                         (let [p (:seon.agent/parent a)]
                           (or (nil? p) (not (contains? by-id p)))))
                       all-agents)]
    [:aside {:id "seon-sidebar" :class (str "sidebar" (if sidebar-open? " open" " closed"))}
     (when sidebar-open?
       [:div {:class "sidebar-inner"}
        [:div {:class "sidebar-head"}
         [:span {:class "kicker"} (str "Agents · " (count all-agents))]]
        [:div {:class "tree-scroll"}
         (if (empty? roots)
           [:p {:class "muted tiny"} "No agents yet."]
           (for [r roots]
             (render-agent-tree-node children-by-parent viewing-id expanded-agents agent-states 0 r)))]
        [:details {:class "sidebar-spawn"}
         [:summary {:class "kicker"} "+ New agent"]
         [:form {:class "form-stack" :style "margin-top:8px"
                 :on-submit (list 'seon.web/spawn-agent-form! viewing-id)}
          [:input {:class "inp sm" :name "agent_id" :placeholder "Name (e.g. :ui/polish)"}]
          [:input {:class "inp sm" :name "scope" :placeholder "Scope (e.g. seon.web)"}]
          [:input {:class "inp sm" :name "goal" :placeholder "What should it work on?"}]
          [:button {:type "submit" :class "btn primary sm"} (str "Create under " (agent-label db viewing-id))]]]])]))

;; --- In-Place 120fps Live Stream Dock (`id="seon-stream-unit"` — 0 DB Reads, ZERO Front-Truncation!) ---

(defn stream-unit [_db {:keys [live-streams errors]}]
  (let [active? (or (seq live-streams) (seq errors))]
    [:section {:id "seon-stream-unit" :class (str "stream-dock" (if active? " active" " idle"))}
     (when active?
       [:div {:class "stack-sm"}
        (for [[aid sinfo] (sort-by (comp str key) live-streams)
              :let [rtxt (str (:reasoning sinfo ""))
                    txt  (str (:text sinfo ""))
                    has-r? (not (str/blank? rtxt))
                    has-t? (not (str/blank? txt))
                    phase (case (:status sinfo)
                            :reasoning "Thinking"
                            :running-tools "Running tools"
                            :token "Responding"
                            "Working")]]
          [:div {:id (str "stream-" (agent-id->slug aid)) :class "live-stream-card"}
           [:div {:class "live-stream-head"}
            [:div {:class "pill-row"}
             [:span {:class "dot pulsing"}]
             [:span {:class "mono tiny strong"} (friendly-agent-name aid)]
             [:span {:class "muted tiny mono"} (str "· " phase "…")]]
            (when has-r?
              [:button {:type "button" :class "thinking-toggle"
                        :data-on:click "$showThinking = !$showThinking"}
               [:span {:class "when-closed"} "Show thinking"]
               [:span {:class "when-open"} "Hide thinking"]])]
           (when has-r?
             [:div {:class "live-reasoning"} rtxt])
           (when has-t?
             [:div {:class "live-prose"} txt [:span {:class "cursor"} "▍"]])])
        (when (seq errors)
          [:div {:class "notice red mono tiny"} (pr-str (first errors))])])]))

;; --- Docked Canvas Composer (`id="seon-composer-unit"` — Never Overlaps Canvas, Never Interrupted!) ---

(defn composer-unit [db _opts]
  (let [{:keys [viewing-id view-mode]} (user-view-state db)
        show? (= view-mode :canvas)
        label (agent-label db viewing-id)]
    [:section {:id "seon-composer-unit" :class (str "composer-dock" (if show? " active" " idle"))}
     (when show?
       [:form {:class "composer-shell" :on-submit (list 'seon.web/chat-agent-form! viewing-id)}
        [:textarea {:class "composer-inp" :name "text" :rows 1
                    :data-on:keydown "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.target.value.trim()){evt.target.form.requestSubmit();}}"
                    :placeholder (str "Message " label "…")}]
        [:button {:type "submit" :class "send-icon-btn" :aria-label "Send" :title "Send (Enter)"}
         svg-arrow-up]])]))

(defn render-inline-prose [& args] (apply rmd/render-inline-prose args))

(defn render-markdown-blocks [& args] (apply rmd/render-markdown-blocks args))

(defn- prompt-msg? [& args] (apply rmd/prompt-msg? args))

(defn- render-activity-fold [& args] (apply rmd/render-activity-fold args))

;; --- VIEW 1: Pure Self-Replacing Live Canvas (`:view-mode :canvas` — `default-canvas [ctx]`) ---

(defn- windowed-history-indices
  "Selects up to 14 well-spaced step indices (always keeping first, current neighborhood, and latest)
   so the interactive timeline scrubber never overflows even at 50+ history steps or half-screen."
  [n cur-idx]
  (if (<= n 14)
    (vec (range n))
    (let [lo (max 1 (- cur-idx 5))
          hi (min (- n 2) (+ cur-idx 5))]
      (vec (distinct (concat [0] (range lo (inc hi)) [(dec n)]))))))

(defn- render-canvas-timeline
  "Sleek interactive Datahike history bar: real SVG Left/Right buttons + clickable timeline pips +
   step counter & Return to Live pill (only rendered when > 1 historical Canvas snapshot exists)."
  [hist-txs cur-idx as-of-tx]
  (let [n (count hist-txs)]
    (when (> n 1)
      (let [idx (or cur-idx (dec n))
            at-past? (boolean (and as-of-tx (< idx (dec n))))
            shown-idxs (windowed-history-indices n idx)]
        [:div {:class (str "canvas-timeline-bar" (when at-past? " historical"))
               :title "Time-travel through Canvas history via Datahike d/as-of"}
         [:div {:class "tl-nav"}
          [:button {:type "button" :class "tl-btn"
                    :aria-label "Previous step"
                    :title "Previous Canvas snapshot"
                    :disabled (zero? idx)
                    :on-click '(seon.web/step-canvas-history! -1)}
           svg-chevron-left]
          [:button {:type "button" :class "tl-btn"
                    :aria-label "Next step"
                    :title "Next Canvas snapshot"
                    :disabled (>= idx (dec n))
                    :on-click '(seon.web/step-canvas-history! 1)}
           svg-chevron-right]]
         [:div {:class "tl-track" :role "tablist" :aria-label "Canvas history timeline"}
          (for [i shown-idxs
                :let [tx (nth hist-txs i)
                      active? (= i idx)
                      past? (< i idx)]]
            [:button {:type "button"
                      :class (str "tl-pip" (cond active? " active" past? " past" :else ""))
                      :title (str "Step " (inc i) " of " n (when (= i (dec n)) " (Live)") " · tx " tx)
                      :aria-label (str "Jump to step " (inc i) " of " n)
                      :on-click (list 'seon.web/jump-canvas-tx! tx)}])]
         [:div {:class "tl-meta"}
          [:span (str (if at-past? "Time-travel · " "") (inc idx) " / " n)]
          (when at-past?
            [:button {:type "button" :class "step-live" :on-click '(seon.web/jump-canvas-live!)}
             "Live"])]]))))

(defn- render-delegation-node [node depth]
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

(defn- render-delegation-panel [call-tree]
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

(defn default-canvas
  "Data-driven default Canvas render function for ANY agent (takes `ctx` map, returns Hiccup):
   - Renders empty hero from `:seon.agent/hero-title` & `:seon.agent/goal` on the agent's record.
   - Renders live recursive delegation tree when `:seon.canvas/outgoing-calls` is present.
   - Renders `:seon.agent/canvas` (`{:title :summary :body}`) or prose replies + Datahike history scrubber.
   - Any agent can swap this completely via `:seon.agent/render-fn` (`(user/set-render! 'my.ui/canvas)`)."
  [ctx]
  (let [aid (:seon.agent/id ctx)
        label (agent-label ctx aid)
        branch (:seon.agent/branch ctx :main)
        canvas (or (:seon.agent/canvas ctx) {})
        latest-stage (last (:seon.agent/stage ctx))
        title (or (not-empty (:title canvas)) (:title latest-stage))
        body (or (:body canvas) (:hiccup latest-stage))
        prose-list (:seon.canvas/latest-prose ctx)
        prose-text (when (seq prose-list) (str/join "\n\n" prose-list))
        newer-prose? (boolean (:seon.canvas/newer-prose? ctx))
        pending-prompt (:seon.canvas/pending-prompt ctx)
        reply-prompt (:seon.canvas/reply-prompt ctx)
        active? (boolean (:seon.canvas/active? ctx))
        summary (if newer-prose?
                  prose-text
                  (or (not-empty (:summary canvas)) prose-text))
        actions (:seon.canvas/latest-actions ctx)
        call-tree (:seon.canvas/outgoing-calls ctx)
        as-of-tx (:seon.canvas/as-of-tx ctx)
        hist-txs (:seon.canvas/history-txs ctx)
        step-idx (:seon.canvas/step-idx ctx)
        branch-feedback (:seon.canvas/feedback ctx)
        fresh? (and (nil? pending-prompt) (nil? title) (nil? body) (str/blank? (str summary)))]
    [:div {:id "seon-view-unit" :class "root-canvas"}
     (render-canvas-timeline hist-txs step-idx as-of-tx)

     (when (and branch-feedback (not (:ok? branch-feedback)))
       [:div {:class "notice red row-between"}
        [:span (:text branch-feedback)]
        [:button {:type "button" :class "link-muted tiny" :on-click '(seon.web/dismiss-feedback!)} "×"]])

     (when (and (nil? pending-prompt) (seq actions))
       (render-activity-fold actions "cv"))

     (when (seq call-tree)
       (render-delegation-panel call-tree))

     (cond
       pending-prompt
       [:div {:class "canvas-pending"}
        [:div {:class "root-kicker"} (str "You asked · " label)]
        [:h1 {:class "canvas-prompt-headline"} pending-prompt]
        [:div {:class "canvas-pending-status"}
         [:span {:class "dot pulsing"}]
         [:span (if active? "Thinking & coordinating response…" "Sent — waking agent…")]]
        (when (seq actions)
          (render-activity-fold actions "cv-pend"))
        (when body
          [:details {:class "prev-peek"}
           [:summary (str "Previous visual" (when title (str " · " title)))]
           [:div {:class "canvas-surface" :style "margin-top:10px"} body]])]

       fresh?
       (if (and (seq actions) (not (str/blank? (str reply-prompt))))
         [:div {:class "canvas-pending"}
          [:div {:class "root-kicker"} (str "You asked · " label)]
          [:h1 {:class "canvas-prompt-headline"} reply-prompt]
          [:p {:class "root-sub"}
           (str "Paused after " (count actions) " tool step" (when (not= 1 (count actions)) "s")
                " without a final response. Expand Thinking & actions above to inspect what ran, or reply below to continue.")]]
         [:div {:class "root-hero"}
          [:div {:class "root-kicker"} (str label " · " branch)]
          [:h1 {:class "root-title"}
           (or (not-empty (:seon.agent/hero-title ctx))
               (str label " is ready"))]
          [:p {:class "root-sub"}
           (or (not-empty (:seon.agent/goal ctx))
               "Ask anything below — Canvas updates live with every answer and visual, and Workspace keeps the full conversation.")]])

       newer-prose?
       [:div {:class "stack"}
        (when (not (str/blank? (str prose-text)))
          [:section {:class "canvas-reply"}
           (when (not (str/blank? (str reply-prompt)))
             [:div {:class "canvas-re-prompt"}
              (str "Re: " (if (> (count reply-prompt) 90) (str (subs reply-prompt 0 90) "…") reply-prompt))])
           (render-markdown-blocks prose-text)])
        (when body
          [:section {:class "canvas-prev-visual"}
           [:div {:class "prev-visual-head"}
            [:span {:class "kicker"}
             (str "Previous visual" (when title (str " · " title)))]]
           [:div {:class "canvas-surface"}
            body]])]

       :else
       [:div {:class "stack"}
        (when (or title (not (str/blank? (str summary))))
          [:header {:class "canvas-header"}
           (when title
             [:h1 {:class "canvas-title"} title])
           (when (not (str/blank? (str summary)))
             [:div {:class "canvas-summary"} (render-markdown-blocks summary)])])
        (when body
          [:section {:class "canvas-surface"}
           body])])]))

(defn- exchange-prose [ex]
  (->> ex
       (filter #(= :eval (:kind %)))
       (map :data)
       (filter #(str/blank? (str (:seon.agent.eval/code %))))
       (map :seon.agent.eval/thought)
       (remove #(str/blank? (str %)))
       vec))

(defn- exchange-max-eval-tx [ex]
  (->> ex
       (filter #(= :eval (:kind %)))
       (keep #(get-in % [:data :seon.agent.eval/tx]))
       (reduce max 0)))

(defn- exchange-user-msg [ex]
  (last (filter prompt-msg? ex)))

(defn- pull-outgoing-call-tree
  "Builds a recursive tree of recent/active outgoing `:seon.agent.call/*` entries rooted at `agent-id`,
   including nested child/grandchild calls linked via `:seon.agent.call/parent-call`."
  [main-db agent-id]
  (when (and main-db agent-id (contains? (:schema main-db) :seon.agent.call/caller))
    (let [direct (->> (d/datoms main-db :avet :seon.agent.call/caller agent-id)
                      (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                              :seon.agent.call/callee :seon.agent.call/parent-call
                                              :seon.agent.call/status :seon.agent.call/prompt
                                              :seon.agent.call/created-tx] (:e %)))
                      (sort-by #(or (:seon.agent.call/created-tx %) 0))
                      vec)
          pending (filterv #(= :pending (:seon.agent.call/status %)) direct)
          roots (if (seq pending)
                  pending
                  (let [latest-tx (or (:seon.agent.call/created-tx (peek direct)) 0)]
                    (filterv #(>= (or (:seon.agent.call/created-tx %) 0) (- latest-tx 8)) direct)))
          has-parent-idx? (contains? (:schema main-db) :seon.agent.call/parent-call)
          expand (fn expand [c depth]
                   (let [kids (when (and has-parent-idx? (< depth 4))
                                (->> (d/datoms main-db :avet :seon.agent.call/parent-call (:seon.agent.call/id c))
                                     (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                                             :seon.agent.call/callee :seon.agent.call/parent-call
                                                             :seon.agent.call/status :seon.agent.call/prompt
                                                             :seon.agent.call/created-tx] (:e %)))
                                     (sort-by #(or (:seon.agent.call/created-tx %) 0))
                                     (mapv #(expand % (inc depth)))))]
                     (cond-> c (seq kids) (assoc :children kids))))]
      (when (seq roots)
        (mapv #(expand % 0) (take-last 8 roots))))))

(defn- build-canvas-ctx [main-db bdb head-bdb aid {:keys [as-of-tx branch-feedback agent-states]}]
  (let [eff-main-db (if as-of-tx (try (d/as-of main-db as-of-tx) (catch Throwable _ main-db)) main-db)
        ag (pull-agent-entity eff-main-db bdb aid)
        evals (agent-evals-in bdb aid)
        msgs (agent-messages-in eff-main-db aid)
        seen-ids (store/agent-seen-message-ids bdb aid)
        timeline (interleave-timeline msgs evals seen-ids)
        exchanges (split-exchanges timeline)
        canvas-tx (max (or (attr-tx-in bdb aid :seon.agent/canvas) 0)
                       (or (attr-tx-in eff-main-db aid :seon.agent/canvas) 0)
                       (or (attr-tx-in bdb aid :seon.agent/stage) 0)
                       (or (attr-tx-in eff-main-db aid :seon.agent/stage) 0))
        latest-ex (peek exchanges)
        latest-user-msg (exchange-user-msg latest-ex)
        latest-msg-id (get-in latest-user-msg [:data :seon.agent.message/id])
        msg-unread? (boolean (and latest-msg-id (not (contains? seen-ids latest-msg-id))))
        active? (= :active (get agent-states aid))
        call-tree (when (nil? as-of-tx) (pull-outgoing-call-tree eff-main-db aid))
        awaiting-calls? (boolean (some #(= :pending (:seon.agent.call/status %)) call-tree))
        latest-prev-max-tx (if (> (count exchanges) 1)
                             (reduce max 0 (map exchange-max-eval-tx (pop exchanges)))
                             0)
        latest-updated-canvas? (and (pos? canvas-tx) (> canvas-tx latest-prev-max-tx))
        pending-prompt (when (and (nil? as-of-tx)
                                  latest-user-msg
                                  (empty? (exchange-prose latest-ex))
                                  (not latest-updated-canvas?)
                                  (or msg-unread? active? awaiting-calls?))
                         (str (:seon.agent.message/body (:data latest-user-msg))))
        settled-idx (last (keep-indexed (fn [i ex] (when (seq (exchange-prose ex)) i)) exchanges))
        display-ex (cond
                     pending-prompt latest-ex
                     (some? settled-idx) (nth exchanges settled-idx)
                     :else latest-ex)
        cur-evals (map :data (filter #(= :eval (:kind %)) display-ex))
        prose-parts (if pending-prompt [] (exchange-prose display-ex))
        reply-prompt (some-> (exchange-user-msg display-ex) :data :seon.agent.message/body)
        action-evals (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                   (not (str/blank? (str (:seon.agent.eval/reasoning %)))))
                              cur-evals)
        reply-tx (->> cur-evals
                      (filter #(and (str/blank? (str (:seon.agent.eval/code %)))
                                    (not (str/blank? (str (:seon.agent.eval/thought %))))))
                      (keep :seon.agent.eval/tx)
                      (reduce max 0))
        prev-ex-max-tx (if (and (some? settled-idx) (pos? settled-idx))
                         (reduce max 0 (map exchange-max-eval-tx (subvec exchanges 0 settled-idx)))
                         0)
        newer-prose? (boolean (and (nil? pending-prompt)
                                   (pos? reply-tx)
                                   (> reply-tx canvas-tx)
                                   (or (zero? canvas-tx) (<= canvas-tx prev-ex-max-tx))))
        hist-txs (canvas-history-txs head-bdb aid)
        step-idx (when (seq hist-txs)
                   (if as-of-tx
                     (or (first (keep-indexed (fn [i tx] (when (= tx as-of-tx) i)) hist-txs)) (dec (count hist-txs)))
                     (dec (count hist-txs))))]
    (assoc ag
           :seon.ctx/db bdb
           :seon.ctx/main-db main-db
           :seon.ctx/tx (:max-tx bdb)
           :seon.canvas/latest-prose prose-parts
           :seon.canvas/latest-actions action-evals
           :seon.canvas/outgoing-calls call-tree
           :seon.canvas/canvas-tx canvas-tx
           :seon.canvas/reply-tx reply-tx
           :seon.canvas/newer-prose? newer-prose?
           :seon.canvas/pending-prompt pending-prompt
           :seon.canvas/reply-prompt reply-prompt
           :seon.canvas/active? (or active? awaiting-calls?)
           :seon.canvas/as-of-tx as-of-tx
           :seon.canvas/history-txs hist-txs
           :seon.canvas/step-idx step-idx
           :seon.canvas/step-label (when (some? step-idx) (str "Step " (inc step-idx) " / " (count hist-txs)))
           :seon.canvas/feedback branch-feedback)))

(defn agent-canvas
  "Standard data-driven Canvas renderer for any agent entity."
  [main-db {:keys [conn viewing-id as-of-tx branch] :as opts}]
  (let [aid (or viewing-id (entry-agent-id main-db))
        branch (or branch (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        head-bdb (branch-db-for main-db conn branch)
        eff-bdb (if as-of-tx (try (d/as-of head-bdb as-of-tx) (catch Throwable _ head-bdb)) head-bdb)
        ctx-map (build-canvas-ctx main-db eff-bdb head-bdb aid opts)]
    (default-canvas ctx-map)))

(defn root-canvas
  "Alias to `agent-canvas` kept for backwards compatibility with existing callers/tests."
  [main-db opts]
  (agent-canvas main-db opts))

;; --- VIEW 2: Clean Traditional Chronological Chat + Live Context Tiles (`:view-mode :workspace`) ---

(defn- render-ws-exchanges [prefix exchanges]
  (for [[ex-idx ex] (map-indexed vector exchanges)
        :let [ex-msgs (filter #(= :msg (:kind %)) ex)
              ex-evals (map :data (filter #(= :eval (:kind %)) ex))
              prose-parts (->> ex-evals
                               (filter #(str/blank? (str (:seon.agent.eval/code %))))
                               (map :seon.agent.eval/thought)
                               (remove #(str/blank? (str %)))
                               vec)
              action-evals (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                         (not (str/blank? (str (:seon.agent.eval/reasoning %)))))
                                    ex-evals)]]
    [:div {:id (str prefix "-" ex-idx) :class "ws-exchange"}
     (for [[m-idx m-item] (map-indexed vector ex-msgs)
           :let [m (:data m-item)
                 body (str (:seon.agent.message/body m ""))
                 mtype (:seon.agent.message/type m)
                 foldable? (or (#{:call-result :call-request :system} mtype)
                               (str/starts-with? body "Resolved call results")
                               (> (count body) 600))]]
       (if foldable?
         [:details {:id (str prefix "-" ex-idx "-m-" m-idx) :class "activity-fold" :style "margin-bottom:6px"}
          [:summary {:class "activity-summary"}
           [:span {:class "activity-label"}
            (let [headline (first (str/split-lines body))]
              (str (if (> (count headline) 84) (str (subs headline 0 84) "…") headline)
                   " · " (count body) " chars"))]]
          [:pre {:class "code-box tiny" :style "white-space:pre-wrap;max-height:360px;overflow:auto;margin-top:6px"} body]]
         [:div {:id (str prefix "-" ex-idx "-m-" m-idx) :class "ws-user-bubble"}
          body]))
     (when (or (seq action-evals) (seq prose-parts))
       [:div {:class (str "ws-agent-turn" (when (seq prose-parts) " has-prose"))}
        (render-activity-fold action-evals (str prefix "-" ex-idx))
        (for [[p-idx p] (map-indexed vector prose-parts)]
          [:div {:id (str prefix "-" ex-idx "-p-" p-idx) :class "chat-body"}
           (render-markdown-blocks p)])])]))

(defn agent-workspace [main-db {:keys [conn branch-feedback viewing-id]}]
  (let [aid (or viewing-id (entry-agent-id main-db))
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        ag (pull-agent-entity main-db bdb aid)
        evals (agent-evals-in bdb aid)
        msgs (agent-messages-in main-db aid)
        seen-ids (store/agent-seen-message-ids bdb aid)
        timeline (interleave-timeline msgs evals seen-ids)
        exchanges (split-exchanges timeline)
        retracted-evals (filterv :seon.agent.eval/retracted-tx (store/agent-historical-evals bdb aid))
        retracted-exchanges (when (seq retracted-evals)
                              (let [r-seen (into #{} (mapcat :seon.agent.eval/seen-messages) retracted-evals)
                                    r-msgs (filterv #(contains? r-seen (:seon.agent.message/id %)) msgs)]
                                (split-exchanges (interleave-timeline r-msgs retracted-evals))))
        tiles (render/discover-renderable-tiles main-db bdb ag evals health/report)
        derived (when (and conn (first (d/datoms main-db :avet :seon.agent/id aid)))
                  (reconciler/derive-agent-status main-db conn {} #{} #{} aid))]
    [:div {:id "seon-view-unit" :class "workspace-grid"}
     [:section {:class "ws-left"}
      [:div {:class "ws-head"}
       [:div
        [:div {:class "pill-row"}
         [:h2 {:class "mono"} (str aid)]
         [:span {:class "badge amber"} (name (or (:seon.agent/role ag) :worker))]
         (when derived
           [:span {:class (str "badge " (case (:status derived) :merged "green" :running "amber" "blue"))}
            (name (:status derived))])]
        [:div {:class "muted tiny mono" :style "margin-top:3px"}
         (str "branch " branch " @ tx " (:max-tx bdb)
              (when (:seon.agent/scope ag) (str " · scope " (:seon.agent/scope ag))))]]
       [:div {:class "pill-row"}
        [:button {:type "button" :class "btn sm" :on-click (list 'seon.web/run-tests! branch)} "Test Branch"]
        (when (not= branch :main)
          [:button {:type "button" :class "btn sm success" :on-click (list 'seon.web/merge-branch! branch)} "Merge → :main"])]]

      (when branch-feedback
        [:div {:class (str "notice row-between " (if (:ok? branch-feedback) "green" "red"))}
         [:span (:text branch-feedback)]
         [:button {:type "button" :class "link-muted tiny" :on-click '(seon.web/dismiss-feedback!)} "×"]])

      [:div {:class "ws-timeline" :data-agent (str aid)}
       (when (not (str/blank? (str (:seon.agent/goal ag))))
         [:div {:class "goal-banner"}
          [:span {:class "kicker"} "Goal"]
          [:p {:class "sm-copy" :style "margin:3px 0 0"} (:seon.agent/goal ag)]])

       (when (seq retracted-exchanges)
         [:details {:id "ws-retracted-history" :class "activity-fold" :style "margin-bottom:10px"}
          [:summary {:class "activity-summary"}
           [:span {:class "activity-label"}
            (str "Earlier history (d/history) · " (count retracted-evals) " compacted eval(s)")]]
          [:div {:class "stack-sm" :style "padding:8px 0 4px 10px;border-left:1px solid var(--border-subtle)"}
           (render-ws-exchanges "whx" retracted-exchanges)]])

       (if (empty? exchanges)
         [:p {:class "muted sm-copy" :style "padding:12px 0"}
          (if (seq retracted-exchanges)
            "Working history compacted (expand Earlier history above or time-travel in Canvas). Send a message to continue."
            "No conversation on this branch yet. Send a message or evaluate a Clojure form below.")]
         (render-ws-exchanges "wx" exchanges))]

      [:form {:class "ws-composer" :on-submit (list 'seon.web/eval-agent-form! aid branch)}
       [:div {:class "composer-shell"}
        [:textarea {:class "composer-inp" :name "code" :rows 1
                    :data-on:keydown "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.target.value.trim()){evt.target.form.requestSubmit();}}"
                    :placeholder (str "Message " (agent-label ag aid) " or (eval Clojure)…")}]
        [:button {:type "submit" :class "send-icon-btn" :aria-label "Send" :title "Send (Enter)"}
         svg-arrow-up]]]]

     [:section {:class "ws-right"}
      [:div {:class "ws-head"}
       [:div
        [:h2 (str "Live Context Tiles (" (count tiles) ")")]
        [:span {:class "muted tiny"} "Everything pulled in @seon.ctx/ctx or returned by evals with a :seon.render/hiccup renderer."]]
       [:button {:type "button" :class "nav-pill tiny"
                 :on-click '(seon.web/set-view-mode! :debug)} "Wire Debug →"]]

      [:form {:class "row-form" :style "margin-bottom:10px" :on-submit (list 'seon.web/pin-ref-form! aid)}
       [:input {:class "inp sm mono flex-1" :name "ref"
                :placeholder "Pin ref, e.g. [:seon.code.ns/name 'seon.web]"}]
       [:button {:type "submit" :class "btn sm"} "+ Pin to Walk"]]

      [:div {:class "tiles-scroll"}
       (if (empty? tiles)
         [:div {:class "card muted sm-copy"}
          "No renderable tiles in this agent's walk yet. Evaluate " [:code "(user/health)"] " or "
          [:code "[:seon.code.ns/name 'seon.render]"] " on the left to watch it appear here live."]
         (for [tile tiles]
           (render-tile-card bdb main-db tile aid)))]]]))

;; --- VIEW 3: Zero-Cost Wire & System Debug (`:view-mode :debug`) ---

(defn- current-test-verdicts
  "Linear AEVT scan of live test verdicts (zero per-entity `d/pull` overhead)."
  [db]
  (if-not (and db (contains? (:schema db) :seon.test/digest))
    {:current [] :historical []}
    (let [test-eids (if (contains? (:schema db) :seon.code.var/test?)
                      (reduce (fn [s dt] (if (:v dt) (conj s (:e dt)) s)) #{} (d/datoms db :aevt :seon.code.var/test?))
                      #{})
          live-test-vars (if (and (seq test-eids) (contains? (:schema db) :seon.code.var/name))
                           (reduce (fn [s dt]
                                     (if (contains? test-eids (:e dt)) (conj s (:v dt)) s))
                                   #{}
                                   (d/datoms db :aevt :seon.code.var/name))
                           #{})
          digs (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/digest))
          vars (if (contains? (:schema db) :seon.test/var)
                 (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/var))
                 {})
          pass (if (contains? (:schema db) :seon.test/pass?)
                 (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/pass?))
                 {})
          ms   (if (contains? (:schema db) :seon.test/ms)
                 (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/ms))
                 {})
          tks  (if (contains? (:schema db) :seon.test/ticks)
                 (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/ticks))
                 {})
          all (reduce-kv (fn [acc e d]
                           (let [v (get vars e)]
                             (if (contains? live-test-vars v)
                               (conj acc {:seon.test/digest d
                                          :seon.test/var v
                                          :seon.test/pass? (get pass e)
                                          :seon.test/ms (get ms e)
                                          :seon.test/ticks (get tks e)})
                               acc)))
                         []
                         digs)
          latest-by-var (->> all
                             (group-by :seon.test/var)
                             (map (fn [[_ vs]] (last (sort-by #(or (:seon.test/pass? %) false) vs))))
                             (sort-by (juxt #(if (:seon.test/pass? %) 1 0) #(str (:seon.test/var %))))
                             vec)]
      {:current latest-by-var
       :historical (vec (sort-by #(str (:seon.test/var %)) all))})))

(defn- wire-role-badge [role]
  (case role
    "system"    "badge amber"
    "user"      "badge blue"
    "assistant" "badge green"
    "tool"      "badge purple"
    "badge"))

(defn- detect-xml-tags [s]
  (when (string? s)
    (vec (distinct (map second (re-seq #"<(/?(?:session|agent|walk-context|walk-diff|message|live-viewport))\b" s))))))

(defn- pretty-edn [x]
  (try
    (let [s (with-out-str (fipp/pprint x {:width 88}))]
      (if (> (count s) 12000) (str (subs s 0 12000) "\n… (truncated)") s))
    (catch Throwable _ (pr-str x))))

(defn debug-view [main-db {:keys [conn walk-summary branch-feedback viewing-id selected-branch viewport-screenshot]}]
  (let [aid (or viewing-id (entry-agent-id main-db))
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        wire (build-live-wire-request main-db bdb aid {:viewport-screenshot viewport-screenshot
                                                       :vision? (boolean viewport-screenshot)})
        ctx-snap (or (try (ctx/build bdb [:seon.agent/id aid]) (catch Throwable _ nil))
                     (try (ctx/build main-db [:seon.agent/id aid]) (catch Throwable _ nil)))
        req-body (model/build-request-body {:model (store/config-val main-db aid :seon.config/ai-model)
                                            :messages (:messages wire)
                                            :thinking (store/config-val main-db aid :seon.config/ai-thinking)}
                                           nil nil nil)
        {:keys [current historical]} (current-test-verdicts bdb)
        branches (when conn (sort (d/branches conn)))
        sel-br selected-branch
        diff-info (when (and conn sel-br (not= sel-br :main))
                    (try (merge/diff-branch conn :main sel-br) (catch Throwable _ nil)))
        h (health/report bdb)]
    [:div {:id "seon-view-unit" :class "debug-wrap"}
     (when branch-feedback
       [:div {:class (str "notice " (if (:ok? branch-feedback) "green" "red"))}
        (:text branch-feedback)])

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:div
        [:h2 (str "Exact Wire Request for " aid " (branch " branch ")")]
        [:span {:class "muted tiny"} "Built by model/build-agent-wire-messages — zero second interpretation. Prefix messages stay byte-identical for KV cache."]]
       [:div {:class "pill-row"}
        (when viewport-screenshot
          [:span {:class "badge amber mono"}
           (str "WKWebView " (:width viewport-screenshot) "×" (:height viewport-screenshot)
                (when (:ms viewport-screenshot) (str " · " (:ms viewport-screenshot) "ms")))])
        [:span {:class "badge blue mono"} (str "~" (:total-tokens wire) " tokens")]
        [:span {:class "badge green mono"} (str (count (:messages wire)) " wire msgs")]
        [:span {:class "badge mono"} (str "model: " (:model req-body))]]]

      [:div {:class "wire-list"}
       (for [[idx [m toks]] (map-indexed vector (map vector (:messages wire) (:message-tokens wire)))
             :let [role (:role m)
                   content (:content m)
                   img-url (when (sequential? content)
                             (some #(when (= "image_url" (:type %)) (get-in % [:image_url :url])) content))
                   text-body (if (sequential? content)
                               (str/join "\n" (map #(if (= "image_url" (:type %))
                                                      (str "[IMAGE_URL: ephemeral live WKWebView viewport"
                                                           (when (:bytes viewport-screenshot)
                                                             (str " · " (quot (:bytes viewport-screenshot) 1024) " KB"))
                                                           "]")
                                                      (:text %))
                                                   content))
                               (str content))
                   xml-tags (detect-xml-tags text-body)
                   preview (let [line (first (remove str/blank? (str/split-lines text-body)))]
                             (if (> (count (str line)) 120) (str (subs line 0 120) "…") (str line)))]]
         [:details {:id (str "wm-" idx) :class "wire-row" :open (or (< idx 4) (some? img-url))}
          [:summary {:class "wire-summary"}
           [:span {:class "muted tiny mono"} (str "#" idx)]
           [:span {:class (wire-role-badge role)} (str/upper-case role)]
           (for [tag xml-tags]
             [:span {:class "badge mono xml-tag"} (str "<" tag ">")])
           (when img-url
             [:span {:class "badge amber mono"} "image_url"])
           (when (seq (:tool_calls m))
             [:span {:class "badge green mono"} (str (count (:tool_calls m)) " tool_call(s)")])
           (when (:tool_call_id m)
             [:span {:class "badge mono"} (str "call_id=" (:tool_call_id m))])
           [:span {:class "muted tiny truncate flex-1"} preview]
           [:span {:class "mono tiny muted"} (str "~" toks " tok")]]
          [:div {:class "wire-body"}
           (when (:reasoning_content m)
             [:div {:style "margin-bottom:6px"}
              [:span {:class "kicker"} "reasoning_content"]
              [:pre {:class "code-box tiny"} (:reasoning_content m)]])
           (when (not (str/blank? text-body))
             [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} text-body])
           (when img-url
             [:img {:class "wire-img" :src img-url :alt "Live WKWebView ephemeral tail screenshot"}])
           (when (seq (:tool_calls m))
             [:div {:style "margin-top:6px"}
              [:span {:class "kicker"} "tool_calls"]
              [:pre {:class "code-box tiny"} (json/write-str (:tool_calls m))]])]])]

      [:details {:class "fold" :style "margin-top:10px"}
       [:summary {:class "muted tiny"} "Raw OpenAI-Compat / DeepSeek Request JSON"]
       [:pre {:class "code-box tiny" :style "max-height:360px;overflow:auto"}
        (str/replace (json/write-str req-body)
                     #"data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]{80,}"
                     "data:image/jpeg;base64,<live-wkwebview-screenshot-redacted-for-display>")]]]

     [:div {:class "grid-2"}
      [:section {:class "card"}
       [:div {:class "card-head"}
        [:div
         [:h2 (str "Live @seon.ctx/ctx Atom Value (" aid ")")]
         [:span {:class "muted tiny"} "Live incremental Datahike pull backing @seon.ctx/ctx inside this agent's REPL"]]
        [:div {:class "pill-row"}
         [:span {:class "badge amber mono"} (str (count (:entries ctx-snap)) " pull clauses")]
         [:span {:class "badge mono"} (str "tx " (or (:tx ctx-snap) (:max-tx bdb)))]]]
       (if ctx-snap
         [:div {:class "stack-sm"}
          [:details {:class "fold"}
           [:summary {:class "muted tiny mono"}
            (str "Active :seon.ctx/pattern (" (count (:pattern ctx-snap)) " items)")]
           [:pre {:class "code-box tiny ctx-pre"} [:code (pretty-edn (:pattern ctx-snap))]]]
          [:pre {:class "code-box tiny ctx-pre"} [:code (pretty-edn (:val ctx-snap))]]]
         [:p {:class "muted sm-copy"} "No agent entity found for @seon.ctx/ctx."])]

      [:section {:class "card"}
       [:div {:class "card-head"}
        [:h2 "Live datahike.reads Read-Sets (Incremental Walk)"]
        [:span {:class "muted tiny"} "0 DB work on untouched units"]]
       [:div {:class "table-scroll"}
        [:table {:class "data-table"}
         [:thead [:tr [:th "Mounted Unit"] [:th "Reads"] [:th "Sample Read Index Keys"]]]
         [:tbody
          (for [{:keys [id reads-count reads-idx]} walk-summary]
            [:tr
             [:td {:class "mono"} (str "#" id)]
             [:td {:class "mono"} (str (or reads-count 0))]
             [:td {:class "mono tiny muted"} (pr-str (vec (take 6 reads-idx)))]])]]]]]

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:div
        [:h2 (str "Rendered Versions — Human HTML vs Model Text (" (count (:tiles wire)) " tiles)")]
        [:span {:class "muted tiny"} "Single-source Hiccup rendered live on the left (Datastar HTML) and projected via render/hiccup->text on the right (sent to LLM)"]]
       [:span {:class "badge purple mono"} "1 Hiccup → 2 targets"]]
      (if (empty? (:tiles wire))
        [:p {:class "muted sm-copy"} "No renderable tiles discovered for this agent yet."]
        [:div {:class "stack"}
         (for [[idx t] (map-indexed vector (:tiles wire))]
           [:div {:id (str "dbg-tile-" idx) :class "debug-tile-pair"}
            [:div {:class "debug-tile-col"}
             [:div {:class "row-between"}
              [:span {:class "kicker"} "Human HTML (Browser)"]
              [:div {:class "pill-row"}
               [:span {:class (str "badge " (case (:source t) :eval "green" :pinned "amber" :child "blue" "muted"))}
                (name (:source t))]
               (when (:handle t)
                 [:span {:class "badge mono handle-pill"} (str (:handle t))])
               [:span {:class "badge mono"} (str (:schema t))]]]
             (render/render-value bdb (:val t) {:agent-id aid :main-db main-db})]
            [:div {:class "debug-tile-col"}
             [:div {:class "row-between"}
              [:span {:class "kicker"} "Model Text (render/hiccup->text)"]
              [:span {:class "mono tiny muted"} (str (count (:text t "")) " chars")]]
             [:pre {:class "code-box tiny ctx-pre"} (:text t)]]])])]

     [:div {:class "grid-2"}
      (render/render-value bdb h)

      [:section {:class "card"}
       [:div {:class "card-head"}
        [:h2 (str "Branches & 3-Way Git Diff (" (count branches) ")")]
        [:span {:class "muted tiny"} "0 worktrees · refs/heads/seon/*"]]
       [:div {:class "pill-row" :style "margin-bottom:8px"}
        (for [b branches]
          [:button {:type "button"
                    :class (str "btn sm" (when (= b sel-br) " primary"))
                    :on-click (list 'seon.web/select-branch! b)}
           (str b)])]
       (if diff-info
         [:div {:class "stack-sm"}
          [:div {:class "pill-row"}
           [:span {:class "badge green"} (str "+" (count (:added diff-info)) " added")]
           [:span {:class "badge blue"} (str "~" (count (:changed diff-info)) " changed")]
           [:span {:class "badge red"} (str "-" (count (:removed diff-info)) " removed")]
           [:button {:type "button" :class "btn sm success" :on-click (list 'seon.web/merge-branch! sel-br)}
            (str "Merge " sel-br " → :main")]]
          (when (seq (:changed diff-info))
            [:pre {:class "code-box tiny"} (pr-str (:changed diff-info))])]
         [:p {:class "muted sm-copy"} "Select a branch above to inspect its 3-way diff."])]]

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:h2 "Register Global Malli Schema (→ Auto Datahike)"]
       [:span {:class "muted tiny"} (str (count (d/datoms main-db :aevt :seon.schema/key)) " schemas · " (count (:schema main-db)) " DH attrs")]]
      [:form {:class "form-stack" :on-submit '(seon.web/register-schema-form!)}
       [:div {:class "row-form"}
        [:input {:class "inp sm mono flex-1" :name "key" :placeholder ":seon.app/amount"}]
        [:input {:class "inp sm mono flex-1" :name "form" :placeholder "[:double {:min 0.0}]"}]
        [:input {:class "inp sm flex-1" :name "doc" :placeholder "Description..."}]
        [:button {:type "submit" :class "btn sm primary"} "Register Schema"]]]]

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:h2 (str "Current Reach-Cached Test Verdicts (" (count current) ")")]
       [:span {:class "muted tiny"} "Filtered to live test vars"]]
      (if (empty? current)
        [:p {:class "muted sm-copy"} "No test digests recorded yet."]
        [:div {:class "table-scroll"}
         [:table {:class "data-table"}
          [:thead [:tr [:th "Test Var"] [:th "Verdict"] [:th "Cost"]]]
          [:tbody
           (for [t current]
             [:tr
              [:td {:class "mono tiny"} (str (:seon.test/var t))]
              [:td [:span {:class (str "badge " (if (:seon.test/pass? t) "green" "red"))}
                    (if (:seon.test/pass? t) "PASS" "FAIL")]]
              [:td {:class "mono tiny muted"} (str (:seon.test/ms t) " ms · " (quot (long (:seon.test/ticks t 0)) 1000) "k ticks")]])]]])
      (when (> (count historical) (count current))
        [:details {:class "fold" :style "margin-top:8px"}
         [:summary {:class "muted tiny"} (str "Show " (count historical) " historical cached digests")]
         [:pre {:class "code-box tiny"}
          (str/join "\n" (map #(str (:seon.test/var %) " " (subs (:seon.test/digest %) 0 10) " " (if (:seon.test/pass? %) "PASS" "FAIL")) historical))]])]

     [:details {:class "card fold"}
      [:summary {:class "muted tiny mono"} "Diagnostics & Crash-Recovery Drill"]
      [:div {:class "row-between" :style "margin-top:10px"}
       [:span {:class "muted tiny"} "Simulates sudden power loss (SIGKILLs forked children and cold-reboots the store while keeping live SSE tabs connected)."]
       [:button {:type "button" :class "btn sm danger" :on-click '(seon.web/power-loss-reboot!)} "Cold Reboot Drill"]]]]))

;; --- Active View Unit Dispatching Purely From Datahike `:user/default` ---

(defn- invoke-canvas-render-fn [rfn ctx-map db opts]
  (try
    (rfn ctx-map)
    (catch clojure.lang.ArityException _
      (rfn db opts))))

(defn view-unit [db {:keys [conn branch-renders] :as opts}]
  (let [{:keys [viewing-id view-mode selected-branch as-of-tx]} (user-view-state db)
        view-opts (assoc opts :viewing-id viewing-id :selected-branch selected-branch :as-of-tx as-of-tx)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        head-bdb (branch-db-for db conn branch)
        eff-bdb (if (and (= view-mode :canvas) as-of-tx)
                  (try (d/as-of head-bdb as-of-tx) (catch Throwable _ head-bdb))
                  head-bdb)
        ag (pull-agent-entity db eff-bdb viewing-id)
        rfn-sym (:seon.agent/render-fn ag 'seon.render.ui/agent-canvas)
        custom? (and (qualified-symbol? rfn-sym) (not (contains? default-canvas-syms rfn-sym)))
        br-render (when (and (= view-mode :canvas) (nil? as-of-tx))
                    (get branch-renders viewing-id))
        use-br-render? (and br-render
                            (= (:branch br-render) branch)
                            (or (nil? rfn-sym) (= (:render-fn br-render) rfn-sym)))
        hist-fn (when (and (= view-mode :canvas) as-of-tx custom?)
                  (eval-historical-render-fn head-bdb eff-bdb rfn-sym))
        custom-fn (or hist-fn
                      (when (and (= view-mode :canvas) (not use-br-render?) custom?)
                        (or (try (resolve rfn-sym) (catch Throwable _ nil))
                            (try (requiring-resolve rfn-sym) (catch Throwable _ nil)))))]
    (cond
      use-br-render?
      [:div {:id "seon-view-unit" :class "root-canvas"}
       [:div {:class "branch-preview-bar"}
        [:span (str "Live branch UI · " (:render-fn br-render) " on " branch " @ tx " (:branch-tx br-render))]
        [:div {:class "pill-row"}
         [:button {:type "button" :class "btn sm success"
                   :on-click (list 'seon.web/merge-branch! branch)} "Merge → :main"]
         [:button {:type "button" :class "btn sm"
                   :on-click '(seon.web/set-view-mode! :workspace)} "Workspace"]]]
       (if (:error br-render)
         [:div {:class "card notice red"}
          [:strong (str "Branch render fault (" (:render-fn br-render) "): ")]
          [:span {:class "mono"} (:error br-render)]]
         (:hiccup br-render))]

      custom-fn
      (let [ctx-map (build-canvas-ctx db eff-bdb head-bdb viewing-id view-opts)
            rendered (invoke-canvas-render-fn custom-fn ctx-map db (assoc view-opts :branch-db eff-bdb :agent ag))
            tl (render-canvas-timeline (:seon.canvas/history-txs ctx-map) (:seon.canvas/step-idx ctx-map) as-of-tx)]
        (if tl
          [:div {:id "seon-view-unit" :class "root-canvas"}
           tl
           rendered]
          rendered))

      (= view-mode :canvas)    ((var-get #'agent-canvas) db view-opts)
      (= view-mode :workspace) ((var-get #'agent-workspace) db view-opts)
      (= view-mode :debug)     ((var-get #'debug-view) db view-opts)
      :else                    ((var-get #'agent-canvas) db view-opts))))

;; --- Incremental Walk Unit Specs (5 Units: Header, Sidebar, View, 120fps Stream, Docked Composer) ---

(defn page-unit-specs
  "Returns the 5 mounted incremental-walk units — 100% driven by Datahike `:user/default`:
   1. `seon-header`: quiet single-row Phosphor top bar (clean even at half-screen)
   2. `seon-sidebar`: collapsible recursive agent-tree sidebar (floating drawer <= 960px)
   3. `seon-view-unit`: active `:canvas` (with interactive timeline scrubber), `:workspace`, or `:debug`
   4. `seon-stream-unit`: 120fps zero-flicker live stream dock (0 DB reads)
   5. `seon-composer-unit`: sleek minimal message bar (never overlaps Canvas)."
  [st]
  (let [vm (when-let [c (:conn st)] (:view-mode (user-view-state @c)))
        view-keys (if (= vm :debug)
                    [:conn :branch-feedback :walk-summary :agent-states :branch-renders :viewport-screenshot]
                    [:conn :branch-feedback :agent-states :branch-renders])]
    [{:id "seon-header"
      :comp #'header-unit
      :args [(select-keys st [:boot-seq :agent-states])]}
     {:id "seon-sidebar"
      :comp #'sidebar-unit
      :args [(select-keys st [:agent-states])]}
     {:id "seon-view-unit"
      :comp #'view-unit
      :args [(select-keys st view-keys)]}
     {:id "seon-stream-unit"
      :comp #'stream-unit
      :args [(select-keys st [:live-streams :errors])]}
     {:id "seon-composer-unit"
      :comp #'composer-unit
      :args [{}]}]))
