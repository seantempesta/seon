(ns seon.render.ui
  "Inside-Out Reagent/Hiccup UI driven 100% by Datahike (`:user/default` -> `:seon.user/*`).
   Mounts `#seon-header`, `#seon-sidebar`, `#seon-view-unit`, `#seon-stream-unit`, and `#seon-composer-unit`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render :as render]
            [seon.store :as store]
            [seon.render.data :as rdata :refer [friendly-agent-name]]
            [seon.render.markdown :as rmd :refer [svg-arrow-up svg-eye svg-eye-off svg-moon svg-sidebar svg-sun]]
            [seon.render.wire :as rwire]
            [seon.render.surface :as surface]
            [seon.signals :as sig]
            [seon.web.styles :as styles]
            [seon.render.links :as rlinks]
            [seon.web.router :as router]))

(defn entry-agent-id [& args] (apply rdata/entry-agent-id args))

(defn user-view-state
  "Reads the user's persistent viewport state (`:user/default`), enriched with `:expert-mode?`."
  {:malli/schema [:=> [:cat :any] :map] :cost/ticks 5000000}
  [db]
  (let [base (rdata/user-view-state db)
        dh (rlinks/db-schema db)
        uid (:user-id base :user/default)
        expert? (boolean
                 (when (and db (contains? dh :seon.user/expert-mode?) (contains? dh :seon.user/id)
                            (first (d/datoms db :avet :seon.user/id uid)))
                   (:seon.user/expert-mode?
                    (d/pull db '[:seon.user/expert-mode?] [:seon.user/id uid]))))]
    (assoc base :expert-mode? expert?)))

(defn- branch-db-for [& args] (apply rdata/branch-db-for args))

(defn canvas-history-txs [& args] (apply rdata/canvas-history-txs args))

(defn build-live-wire-request [& args] (apply rwire/build-live-wire-request args))

(defn- sig-lvl-cls [lvl] (case lvl :hot "sig-hot" :warm "sig-warm" "sig-calm"))

(defn- header-signals-strip [db sigs]
  (let [{:keys [flow pressure context proof health]} sigs
      pill-a (fn [label lvl val pulse? href oc preview-ref]
               [:a (cond-> {:href href
                            :class (str "sig-pill sig-pill-btn nav-link " (sig-lvl-cls lvl) (when pulse? " sig-pulse"))
                            :title (str label " (" (name (or lvl :calm)) ") — click to inspect") :on-click oc}
                     preview-ref (assoc :data-preview-ref (pr-str preview-ref) :interestfor "seon-hovercard"))
                [:span {:class (str "sig-dot " (sig-lvl-cls lvl))}]
                [:span {:class "sig-label"} label]
                [:span {:class "mono"} val]])
      sig-a (fn [label lvl val pulse? sk]
              (let [r [:seon.signal/kind sk]]
                (pill-a label lvl val pulse? (router/entity-ref->path db r) (list 'seon.web/inspect-entity! r) r)))]
  [:div {:class "sig-strip" :data-min-width 220}
   (sig-a "Flow" (:level flow) (str (:running flow) "/" (:active flow)) (:pulse? flow) :flow)
   (sig-a "Pressure" (:level pressure) (str (:bytes-pct pressure) "%") false :pressure)
   (pill-a "Context" (:level context) (str (:prefix-reuse-pct context) "%") false "/context" '(seon.web/set-view-mode! :context) [:seon.signal/kind :context])
   (sig-a "Proof" (:level proof) (if (:merge-ready? proof) (str (:green proof) " ok") (str (:failing proof) " fail")) false :proof)
   (sig-a "Health" (:level health) (if (:ok? health) "ok" (str (:errors health) " err")) false :health)]))

(defn- render-agent-tree-node [kids-by-p viewing-id expanded-set agent-states target-mode expert? depth ag]
  (let [aid (:seon.agent/id ag)
        kids (get kids-by-p aid)
        has-kids? (boolean (seq kids))
        exp? (contains? expanded-set aid)
        sel? (= aid viewing-id)
        work? (= :active (get agent-states aid))]
    [:div {:id (str "nav-ag-" (rdata/agent-id->slug aid)) :class "tree-branch"}
     [:div {:class (str "tree-row" (when sel? " active")) :style (str "padding-left:" (+ 8 (* depth 14)) "px")}
      (if has-kids?
        [:button {:type "button" :class "tree-exp" :on-click (list 'seon.web/toggle-expand! aid)} (if exp? "▾" "▸")]
        [:span {:class "tree-exp-spacer"} "·"])
      [:a (cond-> {:href (or (router/path-for nil :seon.route/agent-session {:slug (rdata/agent-id->slug aid)}) "/")
                   :class "tree-select nav-link"
                   :on-click (list 'seon.web/view-agent! aid target-mode)}
            expert? (assoc :title (str aid) :data-preview-ref (pr-str [:seon.agent/id aid]) :interestfor "seon-hovercard"))
       [:span {:class (str "dot " (if work? "pulsing" "live"))}]
       [:span {:class "tree-name"} (rdata/agent-label ag aid)]
       (if expert?
         (when work? [:span {:class "badge amber tree-role"} "live"])
         (cond work? [:span {:class "badge amber tree-role"} "Working…"]
               (:seon.agent/ready? ag) [:span {:class "badge green tree-role"} "Ready"]))]]
     (when (and has-kids? exp?)
       (for [c kids] (render-agent-tree-node kids-by-p viewing-id expanded-set agent-states target-mode expert? (inc depth) c)))]))

(defn sidebar-unit
  "Slide-out conversation/agent sidebar; auto-collapses when `viewport-w < 760`."
  {:malli/schema [:=> [:cat :seon.host/db :map] :any] :cost/ticks 5000000}
  [db {:keys [agent-states]}]
  (let [{:keys [viewing-id view-mode sidebar-open? expanded-agents viewport-w expert-mode?]} (user-view-state db)
        vw (long (or viewport-w 1280))
        narrow? (< vw 760)
        target-mode (if (= :session (rdata/canonical-view-mode view-mode)) :session :canvas)]
    (if-not (and sidebar-open? (not narrow?))
      [:aside {:id "seon-sidebar" :class (str "sidebar closed" (when narrow? " sidebar-collapsed")) :data-viewport-w (str vw)}]
      (let [all (->> (d/datoms db :aevt :seon.agent/id)
                     (map #(assoc (d/pull db '[:seon.agent/id :seon.agent/parent :seon.agent/interactive?
                                               :seon.agent/label :seon.agent/entry? :seon.agent/archived?
                                               :seon.agent/ready?] (:e %))
                                  :seon.agent/entity-tx (:tx %)))
                     (remove #(or (:seon.agent/archived? %) (:seon.agent/interactive? %)
                                  (str/starts-with? (str (namespace (:seon.agent/id %))) "example")))
                     (sort-by #(vector (if (:seon.agent/entry? %) 0 1) (- (long (or (:seon.agent/entity-tx %) 0))) (str (:seon.agent/id %))))
                     vec)
            by-id (into {} (map (juxt :seon.agent/id identity)) all)
            by-p (group-by :seon.agent/parent all)
            roots (filterv #(let [p (:seon.agent/parent %)] (or (nil? p) (not (contains? by-id p)))) all)]
        [:aside {:id "seon-sidebar" :class "sidebar open" :data-viewport-w (str vw)}
         [:div {:class "sidebar-inner"}
          [:div {:class "sidebar-head"}
           (if expert-mode?
             [:a {:href "/system" :class "ent-link nav-link kicker" :title "Open Seon Runtime System Entity"
                  :data-preview-ref "[:seon.system/id :root]" :interestfor "seon-hovercard"
                  :on-click '(seon.web/set-view-mode! :system)} (str "Agents · " (count all))]
             [:span {:class "kicker"} "Conversations"])]
          [:div {:class "tree-scroll"}
           (if (empty? roots)
             [:p {:class "muted tiny"} (if expert-mode? "No agents yet." "No conversations yet.")]
             (for [r roots] (render-agent-tree-node by-p viewing-id expanded-agents agent-states target-mode expert-mode? 0 r)))]
          [:details {:id "sidebar-spawn" :class "sidebar-spawn"}
           [:summary {:class "kicker"} (if expert-mode? "+ New agent" "+ New conversation")]
           [:form {:class "form-stack" :style "margin-top:8px" :on-submit (list 'seon.web/spawn-agent-form! viewing-id)}
            (if expert-mode?
              (list [:input {:class "inp sm" :name "agent_id" :placeholder "Name (e.g. :ui/polish)"}]
                    [:input {:class "inp sm" :name "scope" :placeholder "Scope (e.g. seon.web)"}]
                    [:input {:class "inp sm" :name "goal" :placeholder "What should it work on?"}]
                    [:button {:type "submit" :class "btn primary sm"} (str "Create under " (rdata/agent-label db viewing-id))])
              (list [:input {:class "inp sm" :name "goal" :placeholder "New conversation name…"}]
                    [:button {:type "submit" :class "btn primary sm"} "Start"]))]]]]))))

(defn composer-unit
  "Bottom docked message composer adapted for Normal User vs Developer Mode."
  {:malli/schema [:=> [:cat :seon.host/db :map] :any] :cost/ticks 2000000}
  [db _opts]
  (let [{:keys [viewing-id entry-id view-mode inspect-ref expert-mode?]} (user-view-state db)
        show? (and (nil? inspect-ref) (contains? #{:canvas :session} (rdata/canonical-view-mode view-mode)))
        label (rdata/agent-label db viewing-id)
        ph (if expert-mode?
             (str "Message " label " or (eval Clojure)…")
             (if (= viewing-id entry-id) "Ask anything…" (str "Ask " label "…")))]
    [:section {:id "seon-composer-unit" :class (str "composer-dock" (if show? " active" " idle"))}
     (when show?
       [:form {:class "composer-shell" :on-submit (list 'seon.web/chat-agent-form! viewing-id)}
        [:textarea {:class "composer-inp" :name "text" :rows 1
                    :data-on:keydown "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.isComposing||evt.keyCode===229)return;if(evt.target.value.trim()){evt.target.form.requestSubmit();}}"
                    :placeholder ph}]
        [:button {:type "submit" :class "send-icon-btn" :aria-label "Send" :title "Send (Enter)"} svg-arrow-up]])]))

(defn view-unit
  "Unified mounted view unit (`#seon-view-unit`) delegating to `seon.render.surface/render-surface`."
  {:malli/schema [:=> [:cat :seon.host/db :map] :any] :cost/ticks 15000000}
  [db {:keys [conn] :as opts}]
  (let [{:keys [viewing-id view-mode selected-branch as-of-tx inspect-ref inspect-trail inspect-forward lab-prefs expert-mode?]} (user-view-state db)
        view-opts (assoc opts :viewing-id viewing-id :selected-branch selected-branch
                         :as-of-tx as-of-tx :view-mode view-mode
                         :inspect-ref inspect-ref :inspect-trail inspect-trail
                         :inspect-forward inspect-forward :lab-prefs lab-prefs
                         :expert-mode? expert-mode?
                         :include-composer? false)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        head-bdb (branch-db-for db conn branch)]
    (surface/render-surface db head-bdb view-opts)))

(defn css-unit
  "Mounted live `<style id=\"seon-css\">` unit (0 DB reads)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :string] [:vector :any]], :cost/ticks 50000}
  [_db css-text]
  [:style {:id "seon-css"} (render/raw-html css-text)])

(defn stream-phase-info
  "Classifies a live-stream entry into phase metadata."
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

(defn stream-live-snippet
  "Extracts a 1-line live preview snippet for a fixed 60px status card."
  {:malli/schema [:=> [:cat [:maybe :map]] :string] :cost/ticks 100000 :cost/bytes 32768}
  [sinfo]
  (if-not (map? sinfo)
    "\u00A0"
    (let [{:keys [phase]} (stream-phase-info sinfo)
          ll #(when (and (string? %) (not (str/blank? %)))
                (some->> (str/split-lines %) (map str/trim) (remove (fn [x] (or (str/blank? x) (str/starts-with? x "──")))) last not-empty))
          ts (when (string? (:tool-snippet sinfo)) (not-empty (str/trim (:tool-snippet sinfo))))
          raw (case phase
                :writing  (or (ll (:text sinfo)) (ll (:reasoning sinfo)) "Writing response…")
                :thinking (or (ll (:reasoning sinfo)) (ll (:text sinfo)) "Reasoning…")
                :tools    (or ts (ll (:text sinfo)) (ll (:reasoning sinfo)) "Running REPL tools…")
                :testing  (or ts "Running reach tests…")
                :merging  (or ts "Merging verified branch…")
                (or ts (ll (:text sinfo)) (ll (:reasoning sinfo)) "Working…"))]
      (if (str/blank? raw) "\u00A0" (if (> (count raw) 140) (subs raw 0 140) raw)))))

(defn- header-focal-crumbs [db inspect-ref inspect-trail canon viewing-id expert-mode?]
  (if-not expert-mode?
    [[:span {:class "crumb"} (if inspect-ref (rdata/entity-short-label inspect-ref) (rdata/agent-label db viewing-id))]]
    (cond
      inspect-ref
      (let [tr-tail (->> (map-indexed vector inspect-trail)
                         (remove #(= (second %) inspect-ref))
                         (take-last 2) vec)]
        (concat
         (mapcat (fn [[idx tr]]
                   [[:a {:href (router/entity-ref->path db tr) :class "crumb ent-link" :data-ref (pr-str tr)
                         :data-preview-ref (pr-str tr) :interestfor "seon-hovercard"
                         :on-click (list 'seon.web/jump-inspect-trail! idx)}
                     [:span (rdata/entity-short-label tr)]]
                    [:span {:class "crumb-sep"} "›"]])
                 tr-tail)
         [(rlinks/ent-link inspect-ref (rdata/entity-short-label inspect-ref) "crumb")]))
      (= canon :system)
      [(rlinks/ent-link [:seon.system/id :root] "Seon Runtime" "crumb")]
      :else
      [(rlinks/ent-link [:seon.agent/id viewing-id] (rdata/agent-label db viewing-id) "crumb")])))

(defn header-unit
  "Renders `#seon-header` with Two-Audience split, unified breadcrumbs, and History sync attrs."
  {:malli/schema [:=> [:cat :seon.host/db :map] [:vector :any]] :cost/ticks 5000000}
  [db {:keys [boot-seq agent-states active-count]}]
  (let [vs (user-view-state db)
        {:keys [viewing-id view-mode sidebar-open? theme viewport-w inspect-ref inspect-trail inspect-forward lab-prefs expert-mode?]} vs
        canon (rdata/canonical-view-mode view-mode)
        explain? (= canon :context)
        vw (long (or viewport-w 1280))
        narrow? (< vw 760)
        sb-open? (and sidebar-open? (not narrow?))
        can-back? (boolean (or (seq (:nav-back lab-prefs)) (seq inspect-trail)))
        can-fwd? (boolean (or (seq (:nav-forward lab-prefs)) (seq inspect-forward) (seq (:inspect-forward lab-prefs))))
        nav-seq (:nav-seq lab-prefs)
        nav-op (when-let [op (:nav-op lab-prefs)] (name op))
        sigs (when expert-mode? (sig/all-signals db))
        h (:health sigs)
        ok? (boolean (:ok? h))
        err-n (long (or (:errors h) 0))
        active-n (long (or active-count (count (filter #(= :active (val %)) agent-states))))
        vision-on? (and expert-mode? (contains? (or (:schema db) (d/schema db) {}) :seon.config/viewport-screenshot?)
                        (boolean (store/config-val db viewing-id :seon.config/viewport-screenshot?)))
        slug (rdata/agent-id->slug viewing-id)
        href-for (fn [r fb] (or (when slug (router/path-for db r {:slug slug})) fb))
        explain-ref (or inspect-ref (when (= canon :system) [:seon.system/id :root]))
        explain-href (if inspect-ref (str (router/entity-ref->path db inspect-ref) "?explain=1") (href-for :seon.route/agent-explain "/context"))
        ibtn (fn [cls lbl oc body & [dis?]]
               [:button (cond-> {:type "button" :class cls :aria-label lbl :title lbl :on-click oc} dis? (assoc :disabled true)) body])
        tab-a (fn [href act? ttl oc lbl]
                [:a {:href href :class (str "nav-pill nav-link" (when act? " active")) :title ttl :on-click oc} lbl])
        svg-act [:svg {:width "14" :height "14" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
                 [:polyline {:points "1.5 8.5 4.2 8.5 6 3.5 9.6 12.5 11.4 8.5 14.5 8.5"}]]]
    [:header (cond-> {:id "seon-header"
                      :class (str "topbar" (when narrow? " is-narrow") (when expert-mode? " is-dev-mode"))
                      :data-canonical-url (router/user-state->path db vs)
                      :data-expert-mode (if expert-mode? "true" "false")
                      :data-viewport-w (str vw) :data-boot-seq (str (or boot-seq 1)) :data-theme (name (or theme :auto))}
               (some? nav-seq) (assoc :data-nav-seq (str nav-seq))
               nav-op (assoc :data-nav-op nav-op))
     [:script {:id "boot-meta" :type "application/json"}
      (render/raw-html (str "{\"bootSeq\":" (or boot-seq 1) ",\"mainTx\":" (:max-tx db) "}"))]
     [:div {:class "brand-row"}
      [:div {:class "brand"}
       (ibtn (str "icon-btn sidebar-toggle" (when sb-open? " active")) (if sb-open? "Hide sidebar" "Show sidebar") '(seon.web/toggle-sidebar!) svg-sidebar)
       (when (or expert-mode? can-back? can-fwd?)
         [:div {:class "hdr-nav"}
          (ibtn "icon-btn" "Back" '(seon.web/inspect-back!) "←" (not can-back?))
          (ibtn "icon-btn" "Forward" '(seon.web/inspect-forward!) "→" (not can-fwd?))])
       [:a {:href "/" :class "brand-link nav-link" :title "Home" :on-click '(seon.web/set-view-mode! :canvas)}
        [:span {:class "diamond"} "◆"]
        (when-not narrow? [:strong {:class "wordmark"} "seon"])]
       (when-not narrow? [:span {:class "crumb-sep"} "/"])
       (header-focal-crumbs db inspect-ref inspect-trail canon viewing-id expert-mode?)]
      [:div {:class "stats-strip"}
       (when (and expert-mode? (>= vw 900)) (header-signals-strip db sigs))
       (when (and expert-mode? (< vw 900))
         [:a {:href (router/entity-ref->path db [:seon.signal/kind :health]) :class (str "status-pill nav-link " (if ok? "ok" "bad"))
              :data-preview-ref "[:seon.signal/kind :health]" :interestfor "seon-hovercard"
              :aria-label (if ok? "System healthy" (str err-n " error(s)")) :on-click '(seon.web/inspect-entity! [:seon.signal/kind :health])}
          [:span {:class (str "dot " (if ok? "live" "bad"))}]])
       (when expert-mode?
         (ibtn (str "icon-btn vision-toggle" (when vision-on? " active"))
               (str "Live viewport screenshot " (if vision-on? "ON" "OFF")) '(seon.web/toggle-vision!)
               (if vision-on? svg-eye svg-eye-off)))
       (ibtn "icon-btn theme-toggle" (if (= theme :light) "Switch to dark theme" "Switch to light theme")
             '(seon.web/toggle-theme!) (if (= theme :light) svg-moon svg-sun))
       (when (or expert-mode? (pos? active-n))
         [:button {:type "button" :id "status-sidebar-toggle"
                   :class (if (pos? active-n) "status-toggle-pill" "status-toggle-idle")
                   :aria-label (if (pos? active-n) (if expert-mode? (str active-n " active") "Working") "Activity")
                   :data-class:active "$statusOpen" :data-on:click "$statusOpen = !$statusOpen"}
          (cond
            (and expert-mode? (pos? active-n))
            (list [:span {:class "dot pulsing"}]
                  [:span {:class "status-toggle-count"} (str active-n (when-not narrow? " active"))]
                  (when-not narrow? [:span {:class "status-toggle-rate" :data-show "$aggRate" :data-text "$aggRate"}]))
            expert-mode? (list svg-act (when-not narrow? [:span {:class "status-label"} "Activity"]))
            :else (list [:span {:class "dot pulsing"}]
                        [:span {:class "status-toggle-count"} (if (= 1 active-n) "Working…" (str active-n " working…"))]))])
       [:div {:class "mode-group"}
        (tab-a (href-for :seon.route/agent-canvas "/canvas") (and (not explain?) (nil? inspect-ref) (= canon :canvas)) "Canvas" '(seon.web/set-view-mode! :canvas) "Canvas")
        (tab-a (href-for :seon.route/agent-chat "/chat") (and (not explain?) (nil? inspect-ref) (= canon :session)) "Chat" '(seon.web/set-view-mode! :session) "Chat")
        (when expert-mode?
          (list (tab-a explain-href explain? "Explain Context & Cache" (list 'seon.web/set-explain-lens! true explain-ref) "Explain")
                (tab-a "/system" (and (not explain?) (nil? inspect-ref) (= canon :system)) "System" '(seon.web/set-view-mode! :system) "System")))]
       [:button {:type "button" :id "expert-mode-toggle"
                 :class (str "nav-pill expert-toggle" (when expert-mode? " active is-dev-pill"))
                 :aria-pressed (if expert-mode? "true" "false")
                 :title (if expert-mode? "Switch to Normal User Mode" "Switch to Developer Mode")
                 :on-click '(seon.web/toggle-expert-mode!)}
        (if expert-mode? "Dev Mode · ON" "Dev")]]]]))

(defn- num-or [m k d] (let [v (get m k)] (if (number? v) (max 0 (long v)) d)))

(defn- calc-tps [phase step-ch st lm]
  (when (and (contains? #{:thinking :writing} phase) (pos? step-ch) (pos? st) (>= lm st))
    (max 1 (Math/round (/ (/ (double step-ch) 4.0) (max 0.5 (/ (double (- lm st)) 1000.0)))))))

(defn aggregate-stream-rate
  "Computes aggregate live `tok/s` string (e.g. `\"· 142 tok/s\"`) across actively streaming entries."
  {:malli/schema [:=> [:cat [:maybe :map]] :string] :cost/ticks 100000 :cost/bytes 32768}
  [streams]
  (if-not (map? streams)
    ""
    (let [tot (reduce-kv
               (fn [acc _aid sinfo]
                 (if-not (map? sinfo)
                   acc
                   (let [{:keys [phase]} (stream-phase-info sinfo)
                         sc (num-or sinfo :step-chars (num-or sinfo :chars 0))
                         tps (calc-tps phase sc (num-or sinfo :started-ms 0) (num-or sinfo :last-ms 0))]
                     (if tps (+ acc tps) acc))))
               0 streams)]
      (if (pos? tot) (str "· " tot " tok/s") ""))))

(defn format-stream-rate
  "Formats live streaming throughput (`48 tok/s`) or total tokens (`1.2k tok`) / `—`."
  {:malli/schema [:=> [:cat [:maybe :map]] :string] :cost/ticks 50000 :cost/bytes 16384}
  [sinfo]
  (if-not (map? sinfo)
    "—"
    (let [{:keys [phase]} (stream-phase-info sinfo)
          chars (num-or sinfo :chars (+ (count (str (:reasoning sinfo ""))) (count (str (:text sinfo "")))))
          step-ch (num-or sinfo :step-chars chars)
          ctx-toks (num-or sinfo :context-tokens 0)
          run-toks (if (pos? ctx-toks) (+ ctx-toks (quot step-ch 4)) (Math/round (/ (double chars) 4.0)))
          fmt (fn [n] (cond (>= n 1000000) (str (/ (Math/round (/ (double n) 100000.0)) 10.0) "M")
                            (>= n 1000) (str (/ (Math/round (/ (double n) 100.0)) 10.0) "k")
                            :else (str (max 1 n))))
          tps (calc-tps phase step-ch (num-or sinfo :started-ms 0) (num-or sinfo :last-ms 0))]
      (cond (and tps (pos? run-toks)) (str (fmt run-toks) " (" tps "/s)")
            tps (str tps " tok/s")
            (pos? run-toks) (str (fmt run-toks) " tok")
            :else "—"))))

(defn stream-unit
  "Live streaming & collapsible status sidebar (`#seon-stream-unit`, 0 DB reads when `:expert-mode?` is supplied)."
  {:malli/schema [:=> [:cat :any :map] [:vector :any]] :cost/ticks 5000000}
  [db {:keys [live-streams errors viewing-id view-mode] :as opts}]
  (let [streams (if (map? live-streams) live-streams {})
        ordered (vec (sort-by (comp str key) streams))
        n-live (count ordered)
        active? (or (pos? n-live) (seq errors))
        expert? (if (contains? opts :expert-mode?) (boolean (:expert-mode? opts))
                    (if db (boolean (:expert-mode? (user-view-state db))) true))
        eff-vid (or viewing-id (when db (:viewing-id (user-view-state db))) (ffirst ordered))
        vs-raw (when eff-vid (get streams eff-vid))
        vs-map (when (map? vs-raw) vs-raw)
        target-mode (if (= :session (rdata/canonical-view-mode view-mode)) :session :canvas)]
    [:div {:id "seon-stream-unit" :class (str "stream-unit-root" (when active? " active"))}
     [:div {:id "seon-live-thinking-slot" :class "stream-center-slot"}
      (when vs-map
        (let [slug (rdata/agent-id->slug eff-vid)
              ph (stream-phase-info vs-map)
              rtxt (str (:reasoning vs-map ""))
              txt (str (:text vs-map ""))
              vname (friendly-agent-name eff-vid)]
          [:section {:id (str "stream-drawer-" slug) :class (str "live-thinking-panel " (:css-class ph)) :data-agent (str eff-vid)}
           [:div {:class "live-drawer-inner"}
            [:div {:class "live-drawer-head"}
             [:div {:class "live-drawer-meta"}
              [:span {:class (str "stream-dot " (:css-class ph))}]
              (if expert? (rlinks/ent-link [:seon.agent/id eff-vid] vname "mono tiny strong stream-drawer-name")
                  [:span {:class "tiny strong stream-drawer-name"} vname])
              [:span {:class (str "stream-phase-badge " (:css-class ph))} (:label ph)]
              (when expert? [:span {:class "stream-rate"} (format-stream-rate vs-map)])]
             [:button {:type "button" :class "thinking-toggle is-close" :data-on:click "$showThinking = false"} "Hide thinking"]]
            [:div {:id (str "stream-" slug "-scroll") :class "live-drawer-body" :data-agent (str eff-vid)}
             (if (or (not (str/blank? rtxt)) (not (str/blank? txt)))
               (list (when-not (str/blank? rtxt) [:div {:id (str "stream-" slug "-reasoning") :class "live-reasoning"} rtxt])
                     (when-not (str/blank? txt) [:div {:id (str "stream-" slug "-prose") :class "live-prose"} txt [:span {:class "cursor"} "▍"]]))
               [:div {:id (str "stream-" slug "-reasoning") :class "live-reasoning muted"} (str (:label ph) "… waiting for output.")])]]]))
      (when (seq errors)
        (let [e (first errors)
              msg (if expert? (pr-str e)
                      (if (map? e) (str (or (:message e) (:error e) "Something went wrong. Please try again.")) (str e)))]
          [:div {:id "stream-error-notice" :class (str "notice red tiny stream-error-bar" (when expert? " mono"))} msg]))]
     [:aside {:id "seon-status-sidebar" :class "status-sidebar" :aria-label "Active status"}
      [:div {:class "status-sidebar-inner"}
       [:div {:class "sidebar-head"}
        [:span {:class "kicker"} (if (pos? n-live) (str "Active · " n-live) "Activity")]
        [:button {:type "button" :class "icon-btn status-close-btn" :aria-label "Hide status sidebar" :title "Hide status sidebar" :data-on:click "$statusOpen = false"} "×"]]
       [:div {:class "status-list"}
        (if (empty? ordered)
          [:div {:class "status-empty"}
           (if expert?
             (list [:p {:class "muted tiny"} "No agents actively running."]
                   [:p {:class "muted tiny"} "Live phases, tok/s, and quick thinking links appear here while agents work."])
             [:p {:class "muted tiny"} "Live helper activity"])]
          (for [[aid raw] ordered
                :let [si (if (map? raw) raw {})
                      slug (rdata/agent-id->slug aid)
                      fname (friendly-agent-name aid)
                      {:keys [label css-class]} (stream-phase-info si)
                      ctx-t (num-or si :context-tokens 0)
                      run-c (if (pos? ctx-t) (+ ctx-t (quot (num-or si :step-chars 0) 4)) 0)
                      max-c (num-or si :max-context-tokens 0)
                      ctx-pct (when (and expert? (pos? max-c) (pos? run-c))
                                (min 100.0 (max 0.6 (/ (Math/round (* 1000.0 (/ (double run-c) (double max-c)))) 10.0))))
                      viewed? (= aid eff-vid)
                      vurl (str "/call?form=" (render/url-enc (pr-str (list 'seon.web/view-agent! aid target-mode))))
                      act (str "@post('" vurl "')")
                      pact (if viewed? "evt.stopPropagation(); $showThinking = !$showThinking"
                               (str "evt.stopPropagation(); $showThinking = true; @post('" vurl "')"))]]
            [:div {:id (str "stream-" slug) :class (str "status-row " css-class (when viewed? " is-viewed"))
                   :role "button" :tabindex "0" :title (str "Open " (if expert? aid fname)) :data-on:click act
                   :data-on:keydown (str "if(evt.key==='Enter'||evt.key===' '){evt.preventDefault();" act "}")}
             [:div {:class "status-row-top"}
              [:span {:class (str "stream-dot " css-class)}]
              [:span (cond-> {:class "stream-agent-name"} expert? (assoc :title (str aid))) fname]
              (when expert? [:span {:class "stream-rate"} (format-stream-rate si)])
              [:button {:type "button" :class (str "stream-phase-pill " css-class (when viewed? " is-viewed-pill"))
                        :title (if expert? (str "Open " aid " & show live thinking") "Show live activity")
                        :data-on:click pact} label]]
             [:div {:class "status-row-snippet"} (stream-live-snippet si)]
             (when ctx-pct
               [:div {:class (str "status-ctx-bar" (cond (>= ctx-pct 80.0) " is-hot" (>= ctx-pct 60.0) " is-warm" :else ""))
                      :style (str "width:" ctx-pct "%") :title (str run-c " / " max-c " context tokens (" ctx-pct "%)")}])]))]]]]))

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
        viewed-stream (when (and (contains? #{:canvas :session} canon) (not insp?) vid (map? (:live-streams st)))
                        (get (:live-streams st) vid))
        sys-focus? (or (= canon :system) (= :seon.system/id (first (:inspect-ref vs))))
        base (assoc (select-keys st [:conn :branch-feedback]) :expert-mode? (boolean (:expert-mode? vs)))
        view-args (cond
                    (= canon :context) (merge base (select-keys st [:walk-summary :viewport-screenshot]) {:agent-states viewed-states})
                    sys-focus? (merge base (select-keys st [:vector-status :semantic-search]) {:agent-states (or agent-states {})})
                    insp? (assoc base :agent-states viewed-states)
                    :else (cond-> (assoc base :agent-states viewed-states) viewed-stream (assoc :live-stream viewed-stream)))]
    [{:id "seon-css" :comp #'css-unit :args [((var-get #'styles/full-css))]}
     {:id "seon-header" :comp #'header-unit :args [{:boot-seq (:boot-seq st) :agent-states viewed-states :active-count active-count}]}
     {:id "seon-sidebar" :comp #'sidebar-unit :args [{:agent-states (if sidebar-open? (or agent-states {}) {})}]}
     {:id "seon-view-unit" :comp #'view-unit :args [view-args]}
     {:id "seon-stream-unit" :comp #'stream-unit :args [(-> (select-keys st [:live-streams :errors]) (assoc :viewing-id vid :view-mode vm :expert-mode? (boolean (:expert-mode? vs))))]}
     {:id "seon-composer-unit" :comp #'composer-unit :args [{}]}]))