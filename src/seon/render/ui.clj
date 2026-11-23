(ns seon.render.ui
  "Inside-Out Reagent/Hiccup UI driven 100% by Datahike (`:user/default` -> `:seon.user/*`).
   Mounts `#seon-header`, `#seon-sidebar`, `#seon-view-unit`, `#seon-stream-unit`, and `#seon-composer-unit`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render :as render]
            [seon.store :as store]
            [seon.render.data :as rdata :refer [friendly-agent-name]]
            [seon.render.markdown :as rmd :refer [svg-arrow-up svg-eye svg-eye-off svg-moon svg-sidebar svg-sun]]
            [seon.render.tiles :as rtiles]
            [seon.render.ns-tile :as rnstile]
            [seon.render.wire :as rwire]
            [seon.render.surface :as surface]
            [seon.signals :as sig]
            [seon.web.styles :as styles]
            [seon.render.links :as rlinks]))

(defn entry-agent-id [& args] (apply rdata/entry-agent-id args))

(defn user-view-state [& args] (apply rdata/user-view-state args))

(defn- branch-db-for [& args] (apply rdata/branch-db-for args))

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

(defn- sig-lvl-cls [lvl] (case lvl :hot "sig-hot" :warm "sig-warm" "sig-calm"))

(defn- header-signals-strip [sigs]
  (let [{:keys [flow pressure context proof health]} sigs
        pill-btn (fn [label lvl val pulse? oc]
                   [:button {:type "button"
                             :class (str "sig-pill sig-pill-btn " (sig-lvl-cls lvl) (when pulse? " sig-pulse"))
                             :title (str label " (" (name (or lvl :calm)) ") — click to inspect")
                             :on-click oc}
                    [:span {:class (str "sig-dot " (sig-lvl-cls lvl))}]
                    [:span {:class "sig-label"} label]
                    [:span {:class "mono"} val]])]
    [:div {:class "sig-strip" :data-min-width 220}
     (pill-btn "Flow" (:level flow) (str (:running flow) "/" (:active flow)) (:pulse? flow)
               '(seon.web/inspect-entity! [:seon.signal/kind :flow]))
     (pill-btn "Pressure" (:level pressure) (str (:bytes-pct pressure) "%") false
               '(seon.web/inspect-entity! [:seon.signal/kind :pressure]))
     (pill-btn "Context" (:level context) (str (:prefix-reuse-pct context) "%") false
               '(seon.web/set-view-mode! :context))
     (pill-btn "Proof" (:level proof) (if (:merge-ready? proof) (str (:green proof) " ok") (str (:failing proof) " fail")) false
               '(seon.web/inspect-entity! [:seon.signal/kind :proof]))
     (pill-btn "Health" (:level health) (if (:ok? health) "ok" (str (:errors health) " err")) false
               '(seon.web/inspect-entity! [:seon.signal/kind :health]))]))

(defn header-unit
{:malli/schema [:=> [:cat :seon.host/db :map] [:vector :any]]}
[db {:keys [boot-seq agent-states active-count]}]
(let [{:keys [viewing-id view-mode sidebar-open? theme viewport-w inspect-ref inspect-trail inspect-forward]} (user-view-state db)
      canon (rdata/canonical-view-mode view-mode)
      explain? (= canon :context)
      vw (long (or viewport-w 1280))
      narrow? (< vw 760)
      sb-open? (and sidebar-open? (not narrow?))
      sigs (sig/all-signals db)
      h (:health sigs)
      ok? (boolean (:ok? h))
      err-n (long (or (:errors h) 0))
      sig-bar (when (>= vw 900) (header-signals-strip sigs))
      active-n (long (or active-count (count (filter #(= :active (val %)) agent-states))))
      vision-on? (and (contains? (or (:schema db) (d/schema db) {}) :seon.config/viewport-screenshot?)
                      (boolean (store/config-val db viewing-id :seon.config/viewport-screenshot?)))
      svg-activity [:svg {:width "14" :height "14" :viewBox "0 0 16 16" :fill "none"
                          :stroke "currentColor" :stroke-width "1.5"
                          :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
                    [:polyline {:points "1.5 8.5 4.2 8.5 6 3.5 9.6 12.5 11.4 8.5 14.5 8.5"}]]
      explain-ref (or inspect-ref (when (= canon :system) [:seon.system/id :root]))
      focal-crumbs (cond
                     inspect-ref
                     (let [tr-tail (vec (take-last 2 (map-indexed vector inspect-trail)))]
                       (concat
                        (mapcat (fn [[idx tr]]
                                  [[:button {:type "button" :class "crumb ent-link" :data-preview-ref (pr-str tr)
                                             :interestfor "seon-hovercard"
                                             :on-click (list 'seon.web/jump-inspect-trail! idx)}
                                    (rdata/entity-short-label tr)]
                                   [:span {:class "crumb-sep"} "›"]])
                                tr-tail)
                        [(rlinks/ent-link inspect-ref (rdata/entity-short-label inspect-ref) "crumb")]))
                     (= canon :system)
                     [(rlinks/ent-link [:seon.system/id :root] "Seon Runtime" "crumb")]
                     :else
                     [(rlinks/ent-link [:seon.agent/id viewing-id] (rdata/agent-label db viewing-id) "crumb")])]
  [:header {:id "seon-header"
            :class (str "topbar" (when narrow? " is-narrow"))
            :data-viewport-w (str vw)
            :data-boot-seq (str (or boot-seq 1))
            :data-theme (name (or theme :auto))}
   [:script {:id "boot-meta" :type "application/json"}
    (render/raw-html (str "{\"bootSeq\":" (or boot-seq 1) ",\"mainTx\":" (:max-tx db) "}"))]
   [:div {:class "brand-row"}
    [:div {:class "brand"}
     [:button {:type "button"
               :class (str "icon-btn sidebar-toggle" (when sb-open? " active"))
               :aria-label (if sb-open? "Hide sidebar" "Show sidebar")
               :title (if sb-open? "Hide sidebar" "Show sidebar")
               :on-click '(seon.web/toggle-sidebar!)}
      svg-sidebar]
     [:div {:class "hdr-nav"}
      [:button {:type "button" :class "icon-btn" :aria-label "Back" :title "Back"
                :disabled (empty? inspect-trail) :on-click '(seon.web/inspect-back!)} "←"]
      [:button {:type "button" :class "icon-btn" :aria-label "Forward" :title "Forward"
                :disabled (empty? inspect-forward) :on-click '(seon.web/inspect-forward!)} "→"]]
     [:button {:type "button" :class "brand-link"
               :title "Seon Runtime — System Entity"
               :data-preview-ref "[:seon.system/id :root]"
               :interestfor "seon-hovercard"
               :on-click '(seon.web/inspect-entity! [:seon.system/id :root])}
      [:span {:class "diamond"} "◆"]
      (when-not narrow? [:strong {:class "wordmark"} "seon"])]
     (when-not narrow? [:span {:class "crumb-sep"} "/"])
     focal-crumbs]
    [:div {:class "stats-strip"}
     sig-bar
     (when (< vw 900)
       [:button {:type "button"
                 :class (str "status-pill " (if ok? "ok" "bad"))
                 :aria-label (if ok? "System healthy" (str err-n " error(s)"))
                 :title (if ok? "System healthy — inspect Health" (str err-n " error(s) — inspect Health"))
                 :on-click '(seon.web/inspect-entity! [:seon.signal/kind :health])}
        [:span {:class (str "dot " (if ok? "live" "bad"))}]])
     [:button {:type "button"
               :class (str "icon-btn vision-toggle" (when vision-on? " active"))
               :aria-label (if vision-on? "Disable viewport screenshot" "Enable viewport screenshot")
               :title (str "Live viewport screenshot " (if vision-on? "ON" "OFF") " for " (rdata/agent-label db viewing-id))
               :on-click '(seon.web/toggle-vision!)}
      (if vision-on? svg-eye svg-eye-off)]
     [:button {:type "button"
               :class "icon-btn theme-toggle"
               :aria-label (if (= theme :light) "Dark theme" "Light theme")
               :title (if (= theme :light) "Switch to dark theme" "Switch to light theme")
               :on-click '(seon.web/toggle-theme!)}
      (if (= theme :light) svg-moon svg-sun)]
     [:button {:type "button"
               :id "status-sidebar-toggle"
               :class (if (pos? active-n) "status-toggle-pill" "status-toggle-idle")
               :aria-label (if (pos? active-n)
                             (str active-n " active agent" (when (not= 1 active-n) "s"))
                             "Activity")
               :title (if (pos? active-n)
                        (str active-n " active agent" (when (not= 1 active-n) "s") " — toggle status sidebar")
                        "Activity — toggle status sidebar")
               :data-class:active "$statusOpen"
               :data-on:click "$statusOpen = !$statusOpen"}
      (if (pos? active-n)
        (list [:span {:class "dot pulsing"}]
              [:span {:class "status-toggle-count"} (str active-n (when-not narrow? " active"))]
              (when-not narrow? [:span {:class "status-toggle-rate" :data-show "$aggRate" :data-text "$aggRate"}]))
        (list svg-activity
              (when-not narrow? [:span {:class "status-label"} "Activity"])))]
     [:div {:class "mode-group"}
      [:button {:type "button"
                :class (str "nav-pill" (when-not explain? " active"))
                :on-click '(seon.web/set-explain-lens! false)}
       "Explore"]
      [:button {:type "button"
                :class (str "nav-pill" (when explain? " active"))
                :on-click (list 'seon.web/set-explain-lens! true explain-ref)}
       "Explain"]]]]]))

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
                :on-click (list 'seon.web/view-agent! aid :session)}
       [:span {:class (str "dot " (if working? "pulsing" "live"))}]
       [:span {:class "tree-name"} (rdata/agent-label ag aid)]
       (when working?
         [:span {:class "badge amber tree-role"} "live"])]]
     (when (and has-kids? expanded?)
       (for [child kids]
         (render-agent-tree-node children-by-parent viewing-id expanded-set agent-states (inc depth) child)))]))

(defn sidebar-unit
  "Slide-out agent tree panel: collapses automatically on narrow viewports (`< 760px`) from Datahike `:viewport-w`."
  {:malli/schema [:=> [:cat :seon.host/db :map] :any]}
  [db {:keys [agent-states]}]
  (let [{:keys [viewing-id sidebar-open? expanded-agents viewport-w]} (user-view-state db)
        vw (long (or viewport-w 1280))
        narrow? (< vw 760)
        open? (and sidebar-open? (not narrow?))]
    (if-not open?
      [:aside {:id "seon-sidebar"
               :class (str "sidebar closed" (when narrow? " sidebar-collapsed"))
               :data-viewport-w (str vw)}]
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
        [:aside {:id "seon-sidebar" :class "sidebar open" :data-viewport-w (str vw)}
         [:div {:class "sidebar-inner"}
          [:div {:class "sidebar-head"}
           [:button {:type "button" :class "ent-link kicker"
                    :title "Open Seon Runtime System Entity"
                    :data-preview-ref "[:seon.system/id :root]"
                    :interestfor "seon-hovercard"
                    :on-click '(seon.web/set-view-mode! :system)}
           (str "Agents · " (count all-agents))]]
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
  (let [{:keys [viewing-id view-mode inspect-ref]} (user-view-state db)
        show? (and (nil? inspect-ref) (= :session (rdata/canonical-view-mode view-mode)))
        label (rdata/agent-label db viewing-id)]
    [:section {:id "seon-composer-unit" :class (str "composer-dock" (if show? " active" " idle"))}
     (when show?
       [:form {:class "composer-shell" :on-submit (list 'seon.web/chat-agent-form! viewing-id)}
        [:textarea {:class "composer-inp" :name "text" :rows 1
                    :data-on:keydown "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.isComposing||evt.keyCode===229)return;if(evt.target.value.trim()){evt.target.form.requestSubmit();}}"
                    :placeholder (str "Message " label " or (eval Clojure)…")}]
        [:button {:type "submit" :class "send-icon-btn" :aria-label "Send" :title "Send (Enter)"}
         svg-arrow-up]])]))

(defn view-unit
  "Unified mounted view unit (`#seon-view-unit`): delegates `:session`, `:context`, `:system`,
   `:inspect` (and legacy `:canvas`, `:workspace`, `:debug`) directly to `seon.render.surface/render-surface`."
  {:malli/schema [:=> [:cat :seon.host/db :map] :any]}
  [db {:keys [conn] :as opts}]
  (let [{:keys [viewing-id view-mode selected-branch as-of-tx inspect-ref inspect-trail inspect-forward lab-prefs]} (user-view-state db)
        view-opts (assoc opts :viewing-id viewing-id :selected-branch selected-branch
                         :as-of-tx as-of-tx :view-mode view-mode
                         :inspect-ref inspect-ref :inspect-trail inspect-trail
                         :inspect-forward inspect-forward :lab-prefs lab-prefs
                         :include-composer? false)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        head-bdb (branch-db-for db conn branch)]
    (surface/render-surface db head-bdb view-opts)))

(defn css-unit
  "Mounted live `<style id=\"seon-css\">` unit (0 DB reads). Hot-patches open browsers via Datastar whenever CSS changes."
  [_db css-text]
  [:style {:id "seon-css"} (render/raw-html css-text)])

(defn stream-phase-info
  "Classifies a live-stream entry into its UI phase metadata `{:phase :label :css-class}`."
  {:malli/schema [:=> [:cat [:maybe :map]] [:map [:phase :keyword] [:label :string] [:css-class :string]]]
   :cost/ticks 50000 :cost/bytes 16384}
  [sinfo]
  (let [raw (when (map? sinfo) (or (:phase sinfo) (:status sinfo)))]
    (case raw
      (:thinking :reasoning :reasoning-token) {:phase :thinking :label "Thinking" :css-class "phase-thinking"}
      (:tools :eval :running-tools :done)     {:phase :tools :label "Tools" :css-class "phase-tools"}
      (:writing :token)                       {:phase :writing :label "Writing" :css-class "phase-writing"}
      :testing                                {:phase :testing :label "Testing" :css-class "phase-testing"}
      :merging                                {:phase :merging :label "Merging" :css-class "phase-merging"}
      {:phase :working :label "Working" :css-class "phase-working"})))

(defn format-stream-rate
  "Formats live streaming throughput (`48 tok/s`) or total tokens (`1.2k tok`) / `—`."
  {:malli/schema [:=> [:cat [:maybe :map]] :string] :cost/ticks 50000 :cost/bytes 16384}
  [sinfo]
  (if-not (map? sinfo)
    "—"
    (let [{:keys [phase]} (stream-phase-info sinfo)
          n-or (fn [k d] (let [v (get sinfo k)] (if (number? v) (max 0 (long v)) d)))
          chars (n-or :chars (+ (count (str (:reasoning sinfo ""))) (count (str (:text sinfo "")))))
          step-ch (n-or :step-chars chars)
          ctx-toks (n-or :context-tokens 0)
          run-toks (if (pos? ctx-toks) (+ ctx-toks (quot step-ch 4)) (Math/round (/ (double chars) 4.0)))
          fmt (fn [n] (cond (>= n 1000000) (str (/ (Math/round (/ (double n) 100000.0)) 10.0) "M")
                            (>= n 1000) (str (/ (Math/round (/ (double n) 100.0)) 10.0) "k")
                            :else (str (max 1 n))))
          st (n-or :started-ms 0) lm (n-or :last-ms 0)
          tps (when (and (#{:thinking :writing} phase) (pos? step-ch) (pos? st) (>= lm st))
                (max 1 (Math/round (/ (/ (double step-ch) 4.0) (max 0.5 (/ (double (- lm st)) 1000.0))))))]
      (cond (and tps (pos? run-toks)) (str (fmt run-toks) " (" tps "/s)")
            tps (str tps " tok/s")
            (pos? run-toks) (str (fmt run-toks) " tok")
            :else "—"))))

(defn aggregate-stream-rate
  "Computes aggregate live `tok/s` string (e.g. `\"· 142 tok/s\"`) across actively streaming entries."
  {:malli/schema [:=> [:cat [:maybe :map]] :string] :cost/ticks 100000 :cost/bytes 32768}
  [streams]
  (if-not (map? streams)
    ""
    (let [total-tps
          (reduce-kv
           (fn [acc _aid sinfo]
             (if-not (map? sinfo)
               acc
               (let [{:keys [phase]} (stream-phase-info sinfo)
                     step-chars (long (if (number? (:step-chars sinfo)) (max 0 (long (:step-chars sinfo)))
                                          (if (number? (:chars sinfo)) (max 0 (long (:chars sinfo))) 0)))
                     started (if (number? (:started-ms sinfo)) (long (:started-ms sinfo)) 0)
                     last-ms (if (number? (:last-ms sinfo)) (long (:last-ms sinfo)) 0)]
                 (if (and (contains? #{:thinking :writing} phase) (pos? step-chars) (pos? started) (>= last-ms started))
                   (+ acc (max 1 (Math/round (/ (/ (double step-chars) 4.0) (max 0.5 (/ (double (- last-ms started)) 1000.0))))))
                   acc))))
           0 streams)]
      (if (pos? total-tps) (str "· " total-tps " tok/s") ""))))

(defn stream-live-snippet
  "Extracts a 1-line live preview snippet for a fixed 60px status card."
  {:malli/schema [:=> [:cat [:maybe :map]] :string] :cost/ticks 100000 :cost/bytes 32768}
  [sinfo]
  (if-not (map? sinfo)
    "\u00A0"
    (let [{:keys [phase]} (stream-phase-info sinfo)
          last-line (fn [s] (when (and (string? s) (not (str/blank? s)))
                              (some->> (str/split-lines s) (map str/trim)
                                       (remove #(or (str/blank? %) (str/starts-with? % "──"))) last not-empty)))
          tool-s (when (string? (:tool-snippet sinfo)) (not-empty (str/trim (:tool-snippet sinfo))))
          raw (case phase
                :writing  (or (last-line (:text sinfo)) (last-line (:reasoning sinfo)) "Writing response…")
                :thinking (or (last-line (:reasoning sinfo)) (last-line (:text sinfo)) "Reasoning…")
                :tools    (or tool-s (last-line (:text sinfo)) (last-line (:reasoning sinfo)) "Running REPL tools…")
                :testing  (or tool-s "Running reach tests…")
                :merging  (or tool-s "Merging verified branch…")
                (or tool-s (last-line (:text sinfo)) (last-line (:reasoning sinfo)) "Working…"))]
      (if (str/blank? raw) "\u00A0" (if (> (count raw) 140) (subs raw 0 140) raw)))))

(defn stream-unit
  "Live streaming & collapsible status sidebar (`#seon-stream-unit`, 0 DB reads)."
  {:malli/schema [:=> [:cat :any :map] [:vector :any]]}
  [db {:keys [live-streams errors viewing-id view-mode]}]
  (let [streams (if (map? live-streams) live-streams {})
        ordered (vec (sort-by (comp str key) streams))
        n-live (count ordered)
        active? (or (pos? n-live) (seq errors))
        eff-viewing (or viewing-id (when db (:viewing-id (user-view-state db))) (ffirst ordered))
        viewed-raw (when eff-viewing (get streams eff-viewing))
        viewed-stream (when (map? viewed-raw) viewed-raw)
        target-mode :session]
    [:div {:id "seon-stream-unit" :class (str "stream-unit-root" (when active? " active"))}
     [:div {:id "seon-live-thinking-slot" :class "stream-center-slot"}
      (when viewed-stream
        (let [v-slug (rdata/agent-id->slug eff-viewing)
              v-phase (stream-phase-info viewed-stream)
              v-rtxt (str (:reasoning viewed-stream ""))
              v-txt  (str (:text viewed-stream ""))
              v-has-r? (not (str/blank? v-rtxt))
              v-has-t? (not (str/blank? v-txt))
              v-rate (format-stream-rate viewed-stream)]
          [:section {:id (str "stream-drawer-" v-slug) :class (str "live-thinking-panel " (:css-class v-phase))
                     :data-agent (str eff-viewing)}
           [:div {:class "live-drawer-inner"}
            [:div {:class "live-drawer-head"}
             [:div {:class "live-drawer-meta"}
              [:span {:class (str "stream-dot " (:css-class v-phase))}]
              (rlinks/ent-link [:seon.agent/id eff-viewing] (friendly-agent-name eff-viewing) "mono tiny strong stream-drawer-name")
              [:span {:class (str "stream-phase-badge " (:css-class v-phase))} (:label v-phase)]
              [:span {:class "stream-rate"} v-rate]]
             [:button {:type "button" :class "thinking-toggle is-close" :data-on:click "$showThinking = false"}
              "Hide thinking"]]
            [:div {:id (str "stream-" v-slug "-scroll") :class "live-drawer-body" :data-agent (str eff-viewing)}
             (if (or v-has-r? v-has-t?)
               (list (when v-has-r? [:div {:id (str "stream-" v-slug "-reasoning") :class "live-reasoning"} v-rtxt])
                     (when v-has-t? [:div {:id (str "stream-" v-slug "-prose") :class "live-prose"} v-txt [:span {:class "cursor"} "▍"]]))
               [:div {:id (str "stream-" v-slug "-reasoning") :class "live-reasoning muted"}
                (str (:label v-phase) "… waiting for stream output.")])]]]))
      (when (seq errors)
        [:div {:id "stream-error-notice" :class "notice red mono tiny stream-error-bar"} (pr-str (first errors))])]
     [:aside {:id "seon-status-sidebar" :class "status-sidebar" :aria-label "Active agent status"}
      [:div {:class "status-sidebar-inner"}
       [:div {:class "sidebar-head"}
        [:span {:class "kicker"} (if (pos? n-live) (str "Active · " n-live) "Activity")]
        [:button {:type "button" :class "icon-btn status-close-btn" :aria-label "Hide status sidebar"
                  :title "Hide status sidebar" :data-on:click "$statusOpen = false"} "×"]]
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
                      ctx-toks (if (number? (:context-tokens sinfo)) (max 0 (long (:context-tokens sinfo))) 0)
                      run-ctx (if (pos? ctx-toks) (+ ctx-toks (quot (if (number? (:step-chars sinfo)) (max 0 (long (:step-chars sinfo))) 0) 4)) 0)
                      max-ctx (if (number? (:max-context-tokens sinfo)) (max 0 (long (:max-context-tokens sinfo))) 0)
                      ctx-pct (when (and (pos? max-ctx) (pos? run-ctx)) (min 100.0 (max 0.6 (/ (Math/round (* 1000.0 (/ (double run-ctx) (double max-ctx)))) 10.0))))
                      is-viewed? (= aid eff-viewing)
                      view-url (str "/call?form=" (render/url-enc (pr-str (list 'seon.web/view-agent! aid target-mode))))
                      card-action (str "@post('" view-url "')")
                      pill-action (if is-viewed?
                                    "evt.stopPropagation(); $showThinking = !$showThinking"
                                    (str "evt.stopPropagation(); $showThinking = true; @post('" view-url "')"))]]
            [:div {:id (str "stream-" slug) :class (str "status-row " css-class (when is-viewed? " is-viewed"))
                   :role "button" :tabindex "0" :title (str "Open " aid) :data-on:click card-action
                   :data-on:keydown (str "if(evt.key==='Enter'||evt.key===' '){evt.preventDefault();" card-action "}")}
             [:div {:class "status-row-top"}
              [:span {:class (str "stream-dot " css-class)}]
              [:span {:class "stream-agent-name" :title (str aid)} (friendly-agent-name aid)]
              [:span {:class "stream-rate"} rate]
              [:button {:type "button" :class (str "stream-phase-pill " css-class (when is-viewed? " is-viewed-pill"))
                        :title (str "Open " aid " & show live thinking") :data-on:click pill-action} label]]
             [:div {:class "status-row-snippet"} snippet]
             (when ctx-pct
               [:div {:class (str "status-ctx-bar" (cond (>= ctx-pct 80.0) " is-hot" (>= ctx-pct 60.0) " is-warm" :else ""))
                      :style (str "width:" ctx-pct "%") :title (str run-ctx " / " max-ctx " context tokens (" ctx-pct "%)")}])]))]]]]))

(defn page-unit-specs
  "Returns the 6 mounted incremental-walk units — 100% driven by Datahike `:user/default`."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [st]
  (let [db (when-let [c (:conn st)] @c)
        vs (when db (user-view-state db))
        vm (:view-mode vs)
        canon (rdata/canonical-view-mode vm)
        insp? (boolean (or (:inspect-ref vs) (= vm :inspect)))
        vid (:viewing-id vs)
        sidebar-open? (and (boolean (:sidebar-open? vs)) (>= (long (or (:viewport-w vs) 1280)) 760))
        agent-states (:agent-states st)
        active-count (if (map? agent-states) (count (filter #(= :active (val %)) agent-states)) 0)
        viewed-states (if (and vid (map? agent-states)) (select-keys agent-states [vid]) {})
        viewed-stream (when (and (= canon :session) (not insp?) vid (map? (:live-streams st)))
                        (get (:live-streams st) vid))
        sys-focus? (or (= canon :system) (= :seon.system/id (first (:inspect-ref vs))))
        view-args (cond
                    (= canon :context) (-> (select-keys st [:conn :branch-feedback :walk-summary :viewport-screenshot])
                                           (assoc :agent-states viewed-states))
                    sys-focus? (-> (select-keys st [:conn :branch-feedback :vector-status :semantic-search])
                                   (assoc :agent-states (or agent-states {})))
                    insp? (-> (select-keys st [:conn :branch-feedback]) (assoc :agent-states viewed-states))
                    :else (cond-> (-> (select-keys st [:conn :branch-feedback]) (assoc :agent-states viewed-states))
                            viewed-stream (assoc :live-stream viewed-stream)))]
    [{:id "seon-css" :comp #'css-unit :args [((var-get #'styles/full-css))]}
     {:id "seon-header" :comp #'header-unit :args [{:boot-seq (:boot-seq st) :agent-states viewed-states :active-count active-count}]}
     {:id "seon-sidebar" :comp #'sidebar-unit :args [{:agent-states (if sidebar-open? (or agent-states {}) {})}]}
     {:id "seon-view-unit" :comp #'view-unit :args [view-args]}
     {:id "seon-stream-unit" :comp #'stream-unit :args [(-> (select-keys st [:live-streams :errors]) (assoc :viewing-id vid :view-mode vm))]}
     {:id "seon-composer-unit" :comp #'composer-unit :args [{}]}]))

(defn rule-tile [& args] (apply rtiles/rule-tile args))