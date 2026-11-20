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
            [seon.render.data :as rdata :refer [eval-historical-render-fn friendly-agent-name]]
            [seon.render.markdown :as rmd :refer [svg-arrow-up svg-moon svg-sidebar svg-sun]]
            [seon.render.tiles :as rtiles]
            [seon.render.ns-tile :as rnstile]
            [seon.render.wire :as rwire]
            [seon.render.debug :as rdebug]
            [seon.render.canvas :as rcanvas :refer [render-canvas-timeline]]
            [seon.web.styles :as styles]))

(def default-canvas-syms rdata/default-canvas-syms)

(defn entry-agent-id [& args] (apply rdata/entry-agent-id args))

(defn agent-id->slug [& args] (apply rdata/agent-id->slug args))

(defn slug->agent-id [& args] (apply rdata/slug->agent-id args))

(defn agent-href [& args] (apply rdata/agent-href args))

(defn debug-href [& args] (apply rdata/debug-href args))

(defn agent-label [& args] (apply rdata/agent-label args))

(defn- router-agent? [& args] (apply rdata/router-agent? args))

(defn- reads-main? [& args] (apply rdata/reads-main? args))

(defn user-view-state [& args] (apply rdata/user-view-state args))

(defn- branch-db-for [& args] (apply rdata/branch-db-for args))

(defn- pull-agent-entity [& args] (apply rdata/pull-agent-entity args))

(defn canvas-history-txs [& args] (apply rdata/canvas-history-txs args))

(defn- agent-evals-in [& args] (apply rdata/agent-evals-in args))

(defn- agent-messages-in [& args] (apply rdata/agent-messages-in args))

(defn- evals->turns [& args] (apply rdata/evals->turns args))

(defn- first-doc-line [& args] (apply rdata/first-doc-line args))

(defn build-live-wire-request [& args] (apply rwire/build-live-wire-request args))

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

(defn default-canvas [& args] (apply rcanvas/default-canvas args))

(defn- build-canvas-ctx [& args] (apply rcanvas/build-canvas-ctx args))

(defn agent-canvas [& args] (apply rcanvas/agent-canvas args))

(defn root-canvas [& args] (apply rcanvas/root-canvas args))

(defn agent-workspace [& args] (apply rcanvas/agent-workspace args))

(defn debug-view [& args] (apply rdebug/debug-view args))

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

(defn- render-emphasis [& args] (apply rmd/render-emphasis args))