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
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.render :as render]
            [seon.store :as store]
            [seon.render.data :as rdata :refer [eval-historical-render-fn friendly-agent-name]]
            [seon.render.markdown :as rmd :refer [svg-arrow-up svg-eye svg-eye-off svg-moon svg-sidebar svg-sun]]
            [seon.render.tiles :as rtiles]
            [seon.render.ns-tile :as rnstile]
            [seon.render.wire :as rwire]
            [seon.render.debug :as rdebug]
            [seon.render.canvas :as rcanvas :refer [render-canvas-timeline]]
            [seon.web.styles :as styles]))

(def default-canvas-syms rdata/default-canvas-syms)

(defn entry-agent-id [& args] (apply rdata/entry-agent-id args))

(defn user-view-state [& args] (apply rdata/user-view-state args))

(defn- branch-db-for [& args] (apply rdata/branch-db-for args))

(defn- pull-agent-entity [& args] (apply rdata/pull-agent-entity args))

(defn canvas-history-txs [& args] (apply rdata/canvas-history-txs args))

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

(defn header-unit
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :any]]}
  [db {:keys [boot-seq agent-states active-count]}]
  (let [{:keys [entry-id viewing-id view-mode sidebar-open? theme]} (user-view-state db)
        h (health/report db)
        ok? (:ok? h)
        err-n (count (filter #(= :error (:severity %)) (:problems h)))
        warn-n (- (count (:problems h)) err-n)
        active? (= :active (get agent-states viewing-id))
        active-n (long (or active-count (count (filter #(= :active (val %)) agent-states))))
        vision-on? (and (contains? (:schema db) :seon.config/viewport-screenshot?)
                        (boolean (store/config-val db viewing-id :seon.config/viewport-screenshot?)))
        svg-activity [:svg {:width "14" :height "14" :viewBox "0 0 16 16" :fill "none"
                            :stroke "currentColor" :stroke-width "1.5"
                            :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
                      [:polyline {:points "1.5 8.5 4.2 8.5 6 3.5 9.6 12.5 11.4 8.5 14.5 8.5"}]]
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
       [:span {:class "crumb" :title (str viewing-id)} (rdata/agent-label db viewing-id)]]
      [:div {:class "stats-strip"}
       [:button {:type "button"
                 :class (str "icon-btn vision-toggle" (when vision-on? " active"))
                 :aria-label (if vision-on? "Disable viewport screenshot" "Enable viewport screenshot")
                 :title (str "Live viewport screenshot " (if vision-on? "ON" "OFF") " for " (rdata/agent-label db viewing-id) " (click to toggle)")
                 :on-click '(seon.web/toggle-vision!)}
        (if vision-on? svg-eye svg-eye-off)]
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
       [:button {:type "button"
                 :id "status-sidebar-toggle"
                 :class (if (pos? active-n) "status-toggle-pill" "status-toggle-idle")
                 :aria-label (if (pos? active-n)
                               (str active-n " active agent" (when (not= 1 active-n) "s") " — toggle right status sidebar")
                               "Activity — toggle right status sidebar")
                 :title (if (pos? active-n)
                          (str active-n " active agent" (when (not= 1 active-n) "s") " — click to show/hide live status sidebar")
                          "Activity — show/hide live agent status sidebar")
                 :data-class:active "$statusOpen"
                 :data-on:click "$statusOpen = !$statusOpen"}
        (if (pos? active-n)
          (list [:span {:class "dot pulsing"}]
                [:span {:class "status-toggle-count"} (str active-n " active")]
                [:span {:class "status-toggle-rate" :data-show "$aggRate" :data-text "$aggRate"}])
          (list svg-activity
                [:span {:class "status-label"} "Activity"]))]
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
    [:div {:id (str "nav-ag-" (rdata/agent-id->slug aid)) :class "tree-branch"}
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
       [:span {:class "tree-name"} (rdata/agent-label ag aid)]
       (when working?
         [:span {:class "badge amber tree-role"} "live"])]]
     (when (and has-kids? expanded?)
       (for [child kids]
         (render-agent-tree-node children-by-parent viewing-id expanded-set agent-states (inc depth) child)))]))

(defn sidebar-unit
  "Slide-out agent tree panel: hides archived, example, and internal `:repl` agents,
   pins entry agent at top, then sorts deterministically by immutable creation time
   descending (`entity-tx`) — zero jumping/swapping during live streaming. Shows active
   running status calmly in-place via pulsing dot + `live` badge."
  {:malli/schema [:=> [:cat :seon.host/db :map] :any]}
  [db {:keys [agent-states]}]
  (let [{:keys [viewing-id sidebar-open? expanded-agents]} (user-view-state db)]
    (if-not sidebar-open?
      [:aside {:id "seon-sidebar" :class "sidebar closed"}]
      (let [all-agents (->> (d/datoms db :aevt :seon.agent/id)
                            (map (fn [dt]
                                   (assoc (d/pull db '[:seon.agent/id :seon.agent/parent
                                                       :seon.agent/role :seon.agent/label
                                                       :seon.agent/entry? :seon.agent/archived?] (:e dt))
                                          :seon.agent/entity-tx (:tx dt))))
                            (remove #(or (:seon.agent/archived? %)
                                         (= :repl (:seon.agent/role %))
                                         (str/starts-with? (str (namespace (:seon.agent/id %))) "example")))
                            (sort-by (fn [a]
                                       [(if (:seon.agent/entry? a) 0 1)
                                        (- (long (or (:seon.agent/entity-tx a) 0)))
                                        (str (:seon.agent/id a))]))
                            vec)
            by-id (into {} (map (juxt :seon.agent/id identity)) all-agents)
            children-by-parent (group-by :seon.agent/parent all-agents)
            roots (filterv (fn [a]
                             (let [p (:seon.agent/parent a)]
                               (or (nil? p) (not (contains? by-id p)))))
                           all-agents)]
        [:aside {:id "seon-sidebar" :class "sidebar open"}
         [:div {:class "sidebar-inner"}
          [:div {:class "sidebar-head"}
           [:span {:class "kicker"} (str "Agents · " (count all-agents))]]
          [:div {:class "tree-scroll"}
           (if (empty? roots)
             [:p {:class "muted tiny"} "No agents yet."]
             (for [r roots]
               (render-agent-tree-node children-by-parent viewing-id expanded-agents agent-states 0 r)))]
          [:details {:id "sidebar-spawn" :class "sidebar-spawn"}
           [:summary {:class "kicker"} "+ New agent"]
           [:form {:class "form-stack" :style "margin-top:8px"
                   :on-submit (list 'seon.web/spawn-agent-form! viewing-id)}
            [:input {:class "inp sm" :name "agent_id" :placeholder "Name (e.g. :ui/polish)"}]
            [:input {:class "inp sm" :name "scope" :placeholder "Scope (e.g. seon.web)"}]
            [:input {:class "inp sm" :name "goal" :placeholder "What should it work on?"}]
            [:button {:type "submit" :class "btn primary sm"} (str "Create under " (rdata/agent-label db viewing-id))]]]]]))))

(defn composer-unit
  {:malli/schema [:=> [:cat :seon.host/db :map] :any]}
  [db _opts]
  (let [{:keys [viewing-id view-mode]} (user-view-state db)
        show? (= view-mode :canvas)
        label (rdata/agent-label db viewing-id)]
    [:section {:id "seon-composer-unit" :class (str "composer-dock" (if show? " active" " idle"))}
     (when show?
       [:form {:class "composer-shell" :on-submit (list 'seon.web/chat-agent-form! viewing-id)}
        [:textarea {:class "composer-inp" :name "text" :rows 1
                    :data-on:keydown "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.target.value.trim()){evt.target.form.requestSubmit();}}"
                    :placeholder (str "Message " label "…")}]
        [:button {:type "submit" :class "send-icon-btn" :aria-label "Send" :title "Send (Enter)"}
         svg-arrow-up]])]))

(defn default-canvas [& args] (apply rcanvas/default-canvas args))

(defn- build-canvas-ctx [& args] (apply rcanvas/build-canvas-ctx args))

(defn agent-canvas [& args] (apply rcanvas/agent-canvas args))

(defn root-canvas [& args] (apply rcanvas/root-canvas args))

(defn- invoke-canvas-render-fn [rfn ctx-map db opts]
  (try
    (rfn ctx-map)
    (catch clojure.lang.ArityException _
      (rfn db opts))))

(defn view-unit
  {:malli/schema [:=> [:cat :seon.host/db :map] :any]}
  [db {:keys [conn branch-renders] :as opts}]
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
      [:div {:id "seon-view-unit" :class "root-canvas" :data-agent (str viewing-id) :data-view-mode (name view-mode)}
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
          [:div {:id "seon-view-unit" :class "root-canvas" :data-agent (str viewing-id) :data-view-mode (name view-mode)}
           tl
           rendered]
          rendered))

      (= view-mode :canvas)    ((var-get #'agent-canvas) db view-opts)
      (= view-mode :workspace) (rcanvas/agent-workspace db view-opts)
      (= view-mode :debug)     (rdebug/debug-view db view-opts)
      :else                    ((var-get #'agent-canvas) db view-opts))))

(defn css-unit
  "Mounted live `<style id=\"seon-css\">` unit (0 DB reads). Hot-patches open browsers via Datastar whenever CSS changes."
  [_db css-text]
  [:style {:id "seon-css"} (render/raw-html css-text)])

(defn- render-emphasis [& args] (apply rmd/render-emphasis args))

(defn stream-phase-info
  "Classifies a live-stream entry into its UI phase metadata `{:phase :label :css-class}`."
  {:malli/schema [:=> [:cat [:maybe :map]]
                  [:map [:phase :keyword] [:label :string] [:css-class :string]]]
   :cost/ticks 50000
   :cost/bytes 16384}
  [sinfo]
  (let [raw (when (map? sinfo) (or (:phase sinfo) (:status sinfo)))]
    (case raw
      (:thinking :reasoning :reasoning-token)
      {:phase :thinking :label "Thinking" :css-class "phase-thinking"}

      (:tools :eval :running-tools :done)
      {:phase :tools :label "Tools" :css-class "phase-tools"}

      (:writing :token)
      {:phase :writing :label "Writing" :css-class "phase-writing"}

      :testing
      {:phase :testing :label "Testing" :css-class "phase-testing"}

      :merging
      {:phase :merging :label "Merging" :css-class "phase-merging"}

      {:phase :working :label "Working" :css-class "phase-working"})))

(defn format-stream-rate
  "Formats live streaming throughput (`48 tok/s`) during active token streaming, or total tokens
   (`1.2k tok`) / `—` during non-streaming tool/test phases, for stable tabular-nums display."
  {:malli/schema [:=> [:cat [:maybe :map]] :string]
   :cost/ticks 50000
   :cost/bytes 16384}
  [sinfo]
  (if-not (map? sinfo)
    "—"
    (let [{:keys [phase]} (stream-phase-info sinfo)
        chars (long (if (number? (:chars sinfo))
                      (max 0 (long (:chars sinfo)))
                      (+ (count (str (:reasoning sinfo "")))
                         (count (str (:text sinfo ""))))))
        step-chars (long (if (number? (:step-chars sinfo))
                           (max 0 (long (:step-chars sinfo)))
                           chars))
        ctx-toks (long (if (and (number? (:context-tokens sinfo)) (pos? (long (:context-tokens sinfo))))
                         (long (:context-tokens sinfo))
                         0))
        step-toks (long (Math/round (/ (double step-chars) 4.0)))
        run-toks (if (pos? ctx-toks)
                   (+ ctx-toks step-toks)
                   (long (Math/round (/ (double chars) 4.0))))
        fmt-toks (fn [n]
                   (cond
                     (>= n 1000000) (str (/ (Math/round (/ (double n) 100000.0)) 10.0) "M")
                     (>= n 1000) (str (/ (Math/round (/ (double n) 100.0)) 10.0) "k")
                     :else (str (max 1 n))))
        started (if (number? (:started-ms sinfo)) (long (:started-ms sinfo)) 0)
        last-ms (if (number? (:last-ms sinfo)) (long (:last-ms sinfo)) 0)
        streaming? (contains? #{:thinking :writing} phase)
        tps (when (and streaming? (pos? step-chars) (pos? started) (>= last-ms started))
              (let [secs (max 0.5 (/ (double (- last-ms started)) 1000.0))]
                (max 1 (Math/round (/ (/ (double step-chars) 4.0) secs)))))]
    (cond
      (and tps (pos? run-toks)) (str (fmt-toks run-toks) " (" tps "/s)")
      tps (str tps " tok/s")
      (pos? run-toks) (str (fmt-toks run-toks) " tok")
      :else "—"))))

(defn aggregate-stream-rate
  "Computes aggregate live `tok/s` string (e.g. `\"· 142 tok/s\"`) across all actively streaming
   entries in `streams`, or `\"\"` when no agent is currently streaming tokens."
  {:malli/schema [:=> [:cat [:maybe :map]] :string]
   :cost/ticks 100000
   :cost/bytes 32768}
  [streams]
  (if-not (map? streams)
    ""
    (let [total-tps
          (reduce-kv
           (fn [acc _aid sinfo]
             (if-not (map? sinfo)
               acc
               (let [{:keys [phase]} (stream-phase-info sinfo)
                     step-chars (long (if (number? (:step-chars sinfo))
                                        (max 0 (long (:step-chars sinfo)))
                                        (if (number? (:chars sinfo))
                                          (max 0 (long (:chars sinfo)))
                                          0)))
                     started (if (number? (:started-ms sinfo)) (long (:started-ms sinfo)) 0)
                     last-ms (if (number? (:last-ms sinfo)) (long (:last-ms sinfo)) 0)]
                 (if (and (contains? #{:thinking :writing} phase)
                          (pos? step-chars) (pos? started) (>= last-ms started))
                   (let [secs (max 0.5 (/ (double (- last-ms started)) 1000.0))]
                     (+ acc (max 1 (Math/round (/ (/ (double step-chars) 4.0) secs)))))
                   acc))))
           0
           streams)]
      (if (pos? total-tps)
        (str "· " total-tps " tok/s")
        ""))))

(defn stream-live-snippet
  "Extracts a 1-line live preview snippet for a fixed 60px status card (prose tail when writing,
   reasoning tail when thinking, tool/status text otherwise; returns non-breaking space when empty
   so the 2nd line never collapses)."
  {:malli/schema [:=> [:cat [:maybe :map]] :string]
   :cost/ticks 100000
   :cost/bytes 32768}
  [sinfo]
  (if-not (map? sinfo)
    "\u00A0"
    (let [{:keys [phase]} (stream-phase-info sinfo)
          last-line (fn [s]
                      (when (and (string? s) (not (str/blank? s)))
                        (some->> (str/split-lines s)
                                 (map str/trim)
                                 (remove #(or (str/blank? %) (str/starts-with? % "──")))
                                 last
                                 not-empty)))
          tool-s (when (string? (:tool-snippet sinfo)) (not-empty (str/trim (:tool-snippet sinfo))))
          raw (case phase
                :writing  (or (last-line (:text sinfo)) (last-line (:reasoning sinfo)) "Writing response…")
                :thinking (or (last-line (:reasoning sinfo)) (last-line (:text sinfo)) "Reasoning…")
                :tools    (or tool-s (last-line (:text sinfo)) (last-line (:reasoning sinfo)) "Running REPL tools…")
                :testing  (or tool-s "Running reach tests…")
                :merging  (or tool-s "Merging verified branch…")
                (or tool-s (last-line (:text sinfo)) (last-line (:reasoning sinfo)) "Working…"))]
      (if (str/blank? raw)
        "\u00A0"
        (if (> (count raw) 140)
          (subs raw 0 140)
          raw)))))

(defn stream-unit
  "Live status & streaming unit (`#seon-stream-unit`, 0 DB reads, `display: contents` in `.app-body` grid):
   1. `#seon-live-thinking-slot` (in-flow center column above composer — never floating over Canvas):
      renders the viewed agent's live reasoning/prose drawer when `$showThinking` is true + error bar.
   2. `#seon-status-sidebar` (collapsible 300px right sidebar, defaults closed via `$statusOpen = false`):
      fixed 60px opaque 2-line clickable cards with color-coded dot/phase pill, tabular-nums tok/s,
      1-line live snippet, and phase-pill click (`stopPropagation` + `$showThinking = true` + jump)."
  {:malli/schema [:=> [:cat :any :map] [:vector :any]]}
  [db {:keys [live-streams errors viewing-id view-mode]}]
  (let [streams (if (map? live-streams) live-streams {})
        ordered (vec (sort-by (comp str key) streams))
        n-live (count ordered)
        active? (or (pos? n-live) (seq errors))
        agg-rate (aggregate-stream-rate streams)
        eff-viewing (or viewing-id
                        (when db (:viewing-id (user-view-state db)))
                        (ffirst ordered))
        viewed-raw (when eff-viewing (get streams eff-viewing))
        viewed-stream (when (map? viewed-raw) viewed-raw)
        target-mode (if (= view-mode :workspace) :workspace :canvas)]
    [:div {:id "seon-stream-unit"
           :class (str "stream-unit-root" (when active? " active"))
           :data-signals:agg-rate (str "'" agg-rate "'")}
     [:div {:id "seon-live-thinking-slot" :class "stream-center-slot"}
      (when viewed-stream
        (let [v-slug (rdata/agent-id->slug eff-viewing)
              v-phase (stream-phase-info viewed-stream)
              v-rtxt (str (:reasoning viewed-stream ""))
              v-txt  (str (:text viewed-stream ""))
              v-has-r? (not (str/blank? v-rtxt))
              v-has-t? (not (str/blank? v-txt))
              v-rate (format-stream-rate viewed-stream)]
          [:section {:id (str "stream-drawer-" v-slug)
                     :class (str "live-thinking-panel " (:css-class v-phase))
                     :data-agent (str eff-viewing)}
           [:div {:class "live-drawer-inner"}
            [:div {:class "live-drawer-head"}
             [:div {:class "live-drawer-meta"}
              [:span {:class (str "stream-dot " (:css-class v-phase))}]
              [:span {:class "mono tiny strong stream-drawer-name"} (friendly-agent-name eff-viewing)]
              [:span {:class (str "stream-phase-badge " (:css-class v-phase))} (:label v-phase)]
              [:span {:class "stream-rate"} v-rate]]
             [:button {:type "button" :class "thinking-toggle is-close"
                       :data-on:click "$showThinking = false"}
              "Hide thinking"]]
            [:div {:id (str "stream-" v-slug "-scroll")
                   :class "live-drawer-body"
                   :data-agent (str eff-viewing)}
             (if (or v-has-r? v-has-t?)
               (list
                (when v-has-r?
                  [:div {:id (str "stream-" v-slug "-reasoning") :class "live-reasoning"} v-rtxt])
                (when v-has-t?
                  [:div {:id (str "stream-" v-slug "-prose") :class "live-prose"} v-txt [:span {:class "cursor"} "▍"]]))
               [:div {:id (str "stream-" v-slug "-reasoning") :class "live-reasoning muted"}
                (str (:label v-phase) "… waiting for stream output.")])]]]))
      (when (seq errors)
        [:div {:id "stream-error-notice" :class "notice red mono tiny stream-error-bar"}
         (pr-str (first errors))])]

     [:aside {:id "seon-status-sidebar" :class "status-sidebar" :aria-label "Active agent status"}
      [:div {:class "status-sidebar-inner"}
       [:div {:class "sidebar-head"}
        [:span {:class "kicker"}
         (if (pos? n-live) (str "Active · " n-live) "Activity")]
        [:button {:type "button" :class "icon-btn status-close-btn"
                  :aria-label "Hide status sidebar"
                  :title "Hide status sidebar"
                  :data-on:click "$statusOpen = false"}
         "×"]]
       [:div {:class "status-list"}
        (if (empty? ordered)
          [:div {:class "status-empty"}
           [:p {:class "muted tiny"} "No agents actively running."]
           [:p {:class "muted tiny"} "Live phases, tok/s, and quick thinking links appear here while agents work."]]
          (for [[aid raw-info] ordered
                :let [sinfo (if (map? raw-info) raw-info {})
                      slug (rdata/agent-id->slug aid)
                      {:keys [label css-class]} (stream-phase-info sinfo)
                      rate (format-stream-rate sinfo)
                      snippet (stream-live-snippet sinfo)
                      is-viewed? (= aid eff-viewing)
                      view-url (str "/call?form=" (render/url-enc (pr-str (list 'seon.web/view-agent! aid target-mode))))
                      card-action (str "@post('" view-url "')")
                      pill-action (if is-viewed?
                                    "evt.stopPropagation(); $showThinking = !$showThinking"
                                    (str "evt.stopPropagation(); $showThinking = true; @post('" view-url "')"))]]
            [:div {:id (str "stream-" slug)
                   :class (str "status-row " css-class (when is-viewed? " is-viewed"))
                   :role "button"
                   :tabindex "0"
                   :title (str "Open " aid)
                   :data-on:click card-action
                   :data-on:keydown (str "if(evt.key==='Enter'||evt.key===' '){evt.preventDefault();" card-action "}")}
             [:div {:class "status-row-top"}
              [:span {:class (str "stream-dot " css-class)}]
              [:span {:class "stream-agent-name" :title (str aid)} (friendly-agent-name aid)]
              [:span {:class "stream-rate"} rate]
              [:button {:type "button"
                        :class (str "stream-phase-pill " css-class (when is-viewed? " is-viewed-pill"))
                        :title (str "Open " aid " & show live thinking")
                        :data-on:click pill-action}
               label]]
             [:div {:class "status-row-snippet"} snippet]]))]]]]))

(defn page-unit-specs
  "Returns the 6 mounted incremental-walk units — 100% driven by Datahike `:user/default`.
   Scopes `:agent-states` & `:branch-renders` to the viewed agent, passes `:active-count` to
   `#seon-header`, and passes `:viewing-id`/`:view-mode` to `#seon-stream-unit` so 60ms stream
   ticks trigger 0 DB seeks on untouched units."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [st]
  (let [vs (when-let [c (:conn st)] (user-view-state @c))
        vm (:view-mode vs)
        vid (:viewing-id vs)
        sidebar-open? (boolean (:sidebar-open? vs))
        agent-states (:agent-states st)
        active-count (if (map? agent-states)
                       (count (filter #(= :active (val %)) agent-states))
                       0)
        viewed-states (if (and vid (map? agent-states)) (select-keys agent-states [vid]) {})
        viewed-renders (if (and vid (map? (:branch-renders st))) (select-keys (:branch-renders st) [vid]) {})
        view-arg (if (= vm :debug)
                   (select-keys st [:conn :branch-feedback :walk-summary :agent-states :branch-renders :viewport-screenshot])
                   (-> (select-keys st [:conn :branch-feedback])
                       (assoc :agent-states viewed-states
                              :branch-renders viewed-renders)))]
    [{:id "seon-css"
      :comp #'css-unit
      :args [(styles/full-css)]}
     {:id "seon-header"
      :comp #'header-unit
      :args [{:boot-seq (:boot-seq st)
              :agent-states viewed-states
              :active-count active-count}]}
     {:id "seon-sidebar"
      :comp #'sidebar-unit
      :args [{:agent-states (if sidebar-open? (or agent-states {}) {})}]}
     {:id "seon-view-unit"
      :comp #'view-unit
      :args [view-arg]}
     {:id "seon-stream-unit"
      :comp #'stream-unit
      :args [(-> (select-keys st [:live-streams :errors])
                 (assoc :viewing-id vid :view-mode vm))]}
     {:id "seon-composer-unit"
      :comp #'composer-unit
      :args [{}]}]))