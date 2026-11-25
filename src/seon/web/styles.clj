(ns seon.web.styles
  "Pure-Clojure runtime CSS compiler and modular Clojure-data style sheets.
   Mounted live via `<style id=\"seon-css\">` so style edits hot-patch open browsers via Datastar with zero refresh."
  (:require [clojure.string :as str]
            [seon.web.css :as css]))

(defn- css-val [v]
  (cond (keyword? v) (name v)
        (ratio? v) (str (/ (Math/round (* (double v) 1000.0)) 1000.0))
        :else (str v)))

(defn- css-name [x] (if (ident? x) (name x) (str x)))

(defn compile-css
  "Compiles Clojure-data CSS rules into a minified stylesheet string.
   Each rule is `[selector props-map]` or `[\"@media(...)\" nested-rules-vec]`."
  {:malli/schema [:=> [:cat [:vector [:tuple [:or :string :keyword] [:map-of [:or :string :keyword] [:or :string :int :keyword]]]]] :string]
   :cost/ticks 500000 :cost/bytes 2097152}
  [rules]
  (str/join
   "\n"
   (keep (fn [rule]
           (when (and (vector? rule) (= 2 (count rule)))
             (let [[sel body] rule
                   sel-s (if (sequential? sel) (str/join "," (map css-name sel)) (css-name sel))]
               (cond
                 (map? body)
                 (let [decls (keep (fn [[k v]] (when (and (some? k) (some? v)) (str (css-name k) ":" (css-val v)))) body)]
                   (when (seq decls) (str sel-s "{" (str/join ";" decls) "}")))
                 (sequential? body)
                 (let [inner (compile-css body)]
                   (when-not (str/blank? inner) (str sel-s "{" inner "}")))))))
         rules)))

(def activity-rules
  [[".activity-fold" {:background "var(--base-900)" :border "1px solid var(--base-800)" :border-radius "8px" :padding "7px 12px"}]
   [".activity-list,.step-drawer,.step-subs" {:display :flex :flex-direction :column}]
   [".activity-list" {:margin-top "8px" :border-top "1px solid var(--base-800)"}]
   [".step-row" {:border-bottom "1px solid var(--base-850)" :padding 0}] [".step-row:last-child" {:border-bottom :none}]
   [".step-summary,.sub-summary" {:display :flex :align-items :baseline :border-radius "5px" :cursor :pointer :list-style :none :min-width 0 :transition "background .12s,color .12s"}]
   [".step-summary::-webkit-details-marker,.sub-summary::-webkit-details-marker" {:display :none}]
   [".step-summary:hover,.sub-summary:hover" {:background "var(--base-850)"}]
   [".step-summary::before,.sub-summary::before,.step-err-tag,.step-ms,.sub-label,.sub-lead" {:font-family "var(--font-mono)"}]
   [".step-summary::before,.sub-summary::before" {:content "'▸'" :color "var(--text-500)" :flex-shrink 0}]
   [".step-row[open]>.step-summary::before,.sub-fold[open]>.sub-summary::before" {:content "'▾'" :color "var(--signal)"}]
   [".step-summary" {:gap "9px" :padding "7px 6px"}] [".step-summary::before" {:font-size "11px"}]
   [".step-row[open]>.step-summary,.sub-summary:hover" {:color "var(--text-50)"}]
   [".step-title,.sub-lead" {:flex 1 :min-width 0 :overflow :hidden :text-overflow :ellipsis :white-space :nowrap}]
   [".step-title" {:font-family "var(--font-text)" :font-size "13px" :font-weight 450 :line-height "1.45" :color "var(--text-100)" :letter-spacing "-.006em"}]
   [".step-row.is-thought .step-title" {:color "var(--text-400)" :font-style :italic}]
   [".step-row.has-error .step-title,.step-err-tag,.sub-lead.is-error" {:color "var(--error)"}]
   [".step-err-tag" {:font-size "10.5px" :flex-shrink 0}] [".step-ms,.sub-meta" {:flex-shrink 0 :margin-left :auto}]
   [".step-ms" {:font-size "11px" :font-variant-numeric "tabular-nums" :color "var(--text-400)"}]
   [".step-drawer" {:margin "2px 0 10px 18px" :padding "6px 0 2px 12px" :border-left "1px solid var(--base-700)" :gap "5px"}]
   [".step-meta-row" {:display :flex :align-items :center :gap "7px" :padding "0 2px 2px"}]
   [".step-subs" {:gap "3px"}] [".sub-fold" {:border-radius "5px" :padding "2px 0"}]
   [".sub-summary" {:gap "8px" :padding "4px 6px" :font-size "12px" :color "var(--text-300)"}] [".sub-summary::before" {:font-size "10.5px"}]
   [".sub-label" {:font-size "11px" :font-weight 500 :color "var(--text-300)" :min-width "58px" :flex-shrink 0}]
   [".sub-lead" {:font-size "11.5px" :color "var(--text-400)"}] [".sub-lead.is-result" {:color "var(--success)"}] [".sub-body" {:margin "5px 0 4px 16px"}]])

(def stability-rules
  [[".status-toggle-pill,.status-toggle-idle,.stream-agent-name,.stream-rate,.stream-phase-pill,.stream-phase-badge" {:font-family "var(--font-mono)" :white-space :nowrap}]
   [".status-toggle-pill,.status-toggle-idle,.status-sidebar,.status-row,.stream-dot,.stream-rate,.stream-phase-pill,.stream-phase-badge,.live-drawer-head .thinking-toggle.is-close" {:flex-shrink 0}]
   [".app-body" {:display :grid :grid-template-columns "auto minmax(0,1fr) auto" :grid-template-rows "minmax(0,1fr) auto auto"}]
   ["#seon-sidebar" {:grid-column 1 :grid-row "1 / -1"}]
   ["#seon-main" {:grid-column 2 :grid-row 1 :min-width 0 :min-height 0}]
   ["#seon-bottom-dock" {:grid-column 2 :grid-row 3 :min-width 0}]
   ["#seon-stream-unit" {:display :contents}]
   [".stream-center-slot" {:grid-column 2 :grid-row 2 :min-width 0 :display :flex :flex-direction :column}]
   [".stage-scroll,.ws-timeline,.tiles-scroll,.tree-scroll,.status-list,.live-drawer-body,.live-reasoning,.live-prose" {:overscroll-behavior :contain :overflow-anchor :auto}]
   [".stage-scroll,.ws-timeline,.tree-scroll,.status-list" {:scrollbar-gutter :stable}]
   [".sidebar.open,.status-sidebar" {:contain "layout style"}] ["details::details-content" {:contain :layout}]
   [".phase-thinking" {:--phase-color "var(--signal)"}] [".phase-tools" {:--phase-color "var(--info)"}]
   [".phase-writing" {:--phase-color "var(--eval)"}] [".phase-testing,.phase-merging" {:--phase-color "var(--success)"}]
   [".phase-working" {:--phase-color "var(--text-400)"}]
   [".status-toggle-pill,.status-toggle-idle" {:display :inline-flex :align-items :center :gap "6px" :height "28px" :border-radius "999px" :font-size "11px" :cursor :pointer :transition "border-color .15s,color .15s,background .15s"}]
   [".status-toggle-pill" {:padding "0 10px" :border "1px solid rgba(240,180,41,.42)" :background "rgba(240,180,41,.09)" :color "var(--signal)" :font-weight 500}]
   [".status-toggle-pill:hover,.status-toggle-pill.active" {:border-color "var(--signal)" :background "rgba(240,180,41,.18)"}]
   [".status-toggle-count,.status-toggle-rate,.stream-rate" {:font-variant-numeric :tabular-nums}] [".status-toggle-rate" {:color "var(--text-200)"}]
   [".status-toggle-idle" {:padding "0 9px" :border "1px solid var(--base-800)" :background :transparent :color "var(--text-400)"}]
   [".status-toggle-idle:hover,.status-toggle-idle.active" {:border-color "var(--signal)" :color "var(--signal)" :background "rgba(240,180,41,.07)"}]
   ["#seon-app[data-status='closed'] .status-sidebar" {:display :none}] ["#seon-app[data-status='open'] .status-sidebar" {:display :flex}]
   [".status-sidebar" {:grid-column 3 :grid-row "1 / -1" :width "300px" :background "var(--base-900)" :opacity 1 :border-left "1px solid var(--base-800)" :flex-direction :column :overflow :hidden}]
   [".status-sidebar-inner" {:display :flex :flex-direction :column :height "100%" :padding "12px 10px" :gap "10px" :overflow :hidden}]
   [".status-close-btn" {:width "24px" :height "24px" :font-size "14px" :line-height 1}]
   [".status-list" {:flex 1 :min-height 0 :overflow-y :auto :display :flex :flex-direction :column :gap "8px" :padding "2px 0"}]
   [".status-empty" {:padding "14px 8px" :display :flex :flex-direction :column :gap "4px"}]
   [".status-row" {:display :flex :flex-direction :column :justify-content :center :gap "4px" :height "60px" :min-height "60px" :max-height "60px" :box-sizing :border-box :padding "8px 10px" :overflow :hidden :background "var(--base-950)" :opacity 1 :border "1px solid var(--base-800)" :border-left "3px solid var(--phase-color,var(--signal))" :border-radius "8px" :cursor :pointer :position :relative :contain "layout style" :transition "border-color .14s,background .14s"}]
   [".status-row:hover" {:background "var(--base-850)" :border-color "var(--base-700)" :border-left-color "var(--phase-color,var(--signal))"}]
   [".status-row.is-viewed" {:border-color "color-mix(in srgb,var(--phase-color,var(--signal)) 55%,var(--base-800))" :border-left-color "var(--phase-color,var(--signal))"}]
   [".status-row-top" {:display :flex :align-items :center :flex-wrap :nowrap :gap "6px" :height "22px" :min-height "22px" :max-height "22px" :white-space :nowrap :overflow :hidden}]
   [".stream-dot" {:width "8px" :height "8px" :border-radius "50%" :color "var(--phase-color,var(--signal))" :background :currentColor :animation "seon-ring 1.6s ease-out infinite"}]
   [".stream-agent-name" {:flex "1 1 0" :min-width 0 :overflow :hidden :text-overflow :ellipsis :font-size "12px" :font-weight 600 :color "var(--text-100)"}]
   [".stream-rate" {:min-width "54px" :text-align :right :font-size "10.5px" :color "var(--text-300)"}]
   [".stream-phase-pill,.stream-phase-badge" {:min-width "62px" :text-align :center :font-size "10px" :font-weight 600 :line-height "1.25" :padding "2px 7px" :border-radius "999px" :color "var(--phase-color,var(--signal))" :border "1px solid color-mix(in srgb,var(--phase-color,var(--signal)) 44%,transparent)" :background "color-mix(in srgb,var(--phase-color,var(--signal)) 14%,var(--base-900))"}]
   [".stream-phase-pill" {:cursor :pointer :transition "background .14s,border-color .14s"}]
   [".stream-phase-pill:hover,#seon-app[data-thinking='open'] .stream-phase-pill.is-viewed-pill" {:border-color "var(--phase-color,var(--signal))" :background "color-mix(in srgb,var(--phase-color,var(--signal)) 24%,var(--base-900))"}]
   [".status-ctx-bar" {:position :absolute :left 0 :bottom 0 :height "2px" :background "var(--signal)" :border-radius "0 2px 0 0" :pointer-events :none :transition "width .25s ease"}]
   [".status-ctx-bar.is-warm" {:background "#f59e0b"}] [".status-ctx-bar.is-hot" {:background "var(--err,#f87171)"}]
   [".status-row-snippet" {:height "18px" :min-height "18px" :max-height "18px" :line-height "18px" :white-space :nowrap :overflow :hidden :text-overflow :ellipsis :font-family "var(--font-text)" :font-size "11.5px" :color "var(--text-400)"}]
   ["#seon-app[data-thinking='closed'] .live-thinking-panel" {:display :none}] ["#seon-app[data-thinking='open'] .live-thinking-panel" {:display :flex}]
   [".live-thinking-panel" {:flex-direction :column :padding "10px 24px 12px" :background "var(--base-900)" :opacity 1 :border-top "1px solid var(--base-800)" :border-left "3px solid var(--phase-color,var(--signal))" :contain "layout style"}]
   [".live-drawer-inner" {:max-width "860px" :width "100%" :margin "0 auto" :display :flex :flex-direction :column :gap "8px"}]
   [".live-drawer-head" {:display :flex :align-items :center :justify-content :space-between :gap "10px" :flex-wrap :nowrap :min-height "24px"}]
   [".live-drawer-meta" {:display :flex :align-items :center :gap "8px" :min-width 0 :flex "1 1 0" :overflow :hidden}]
   [".stream-drawer-name" {:overflow :hidden :text-overflow :ellipsis :white-space :nowrap :color "var(--text-50)"}]
   [".live-drawer-body" {:max-height "min(220px,30vh)" :overflow-y :auto :padding "10px 12px" :background "var(--base-950)" :border "1px solid var(--base-800)" :border-radius "6px" :contain "layout style paint" :display :flex :flex-direction :column :gap "10px"}]
   [".live-drawer-body .live-reasoning,.live-drawer-body .live-prose" {:max-height :none :overflow :visible :contain :none}]
   [".live-drawer-body .live-reasoning" {:padding 0 :background :transparent :border :none :border-radius 0}] [".live-drawer-body .live-prose" {:font-size "14px" :line-height "1.62"}]
   [".live-drawer-body .live-reasoning+.live-prose" {:border-top "1px solid var(--base-800)" :padding-top "10px"}]
   [".live-drawer-head .thinking-toggle.is-close" {:height "24px" :padding "0 10px" :display :inline-flex :align-items :center :justify-content :center :white-space :nowrap :background "var(--base-850)" :border "1px solid var(--base-700)" :color "var(--text-200)" :border-radius "5px"}]
   [".live-drawer-head .thinking-toggle.is-close:hover" {:border-color "var(--phase-color,var(--signal))" :color "var(--text-50)"}]
   [".stream-error-bar" {:margin "8px 24px"}]])

(def surface-layout-rules
  [[".app-shell" {:height "100dvh"}]
   ["html,body,.app-shell,.app-body,main,#seon-main,.workspace-grid,.surface-stack,.ws-left,.ws-right,.tiles-scroll,.ctx-group,.debug-wrap,.root-canvas,.user-chat-wrap,.surface-entities,.surface-entity-item,.surface-signals,.card,.stack,.stack-sm,.canvas-timeline-bar,.debug-tile-pair,.grid-2>*,.debug-tile-pair>*,.debug-tile-col,.topbar" {:max-width "100%" :box-sizing "border-box"}]
   ["main,#seon-main,.workspace-grid,.surface-stack,.ws-left,.ws-right,.tiles-scroll,.ctx-group,.debug-wrap,.root-canvas,.user-chat-wrap,.surface-entities,.surface-entity-item,.surface-signals,.card,.stack,.stack-sm,.canvas-timeline-bar,.tl-track,.tl-meta,.ws-head,.wire-summary,.wire-summary .flex-1,.debug-tile-pair,.grid-2>*,.debug-tile-pair>*,.debug-tile-col" {:min-width "0"}]
   ["main,#seon-main" {:overflow-x "hidden"}]
   [".sidebar.closed,.sidebar.sidebar-collapsed" {:display "none"}]
   [".topbar.is-dev-mode" {:height :auto :min-height "48px" :padding "6px 16px" :border-top "2px solid var(--signal)" :background "linear-gradient(180deg,rgba(240,180,41,.07),var(--base-950) 60%)"}]
   [".topbar.is-dev-mode .brand-row" {:flex-wrap :wrap :row-gap "6px"}]
   [".topbar.is-dev-mode .brand" {:flex "0 0 auto" :overflow :visible}]
   [".topbar.is-dev-mode .stats-strip" {:flex-wrap :wrap :row-gap "5px" :justify-content :flex-end}]
   [".nav-pill.is-dev-pill" {:background "rgba(240,180,41,.18)" :color "var(--signal)" :border "1px solid rgba(240,180,41,.55)" :font-weight 600}]
   
   [".root-canvas-wrap,.root-canvas,.user-chat-wrap,.user-chat-col,.user-welcome" {:display :flex :flex-direction :column}]
   [".root-canvas-wrap" {:min-height "100%" :width "100%" :gap "12px"}]
   [".root-canvas" {:max-width "1040px" :width "100%" :margin "0 auto" :gap "14px"}]
   [".root-canvas.is-user-canvas" {:flex 1 :min-height 0 :max-width "780px"}]
   [".user-welcome" {:margin "auto 0" :padding "12px 0" :align-items :center :text-align :center :gap "16px"}]
   [".starter-chips" {:display :flex :flex-wrap :wrap :justify-content :center :gap "8px" :max-width "640px"}]
   [".starter-chip" {:background "var(--base-900)" :color "var(--text-200)" :padding "9px 15px" :border-radius "999px" :font-size "13.5px"}]
   [".user-chat-wrap" {:max-width "780px" :width "100%" :margin "0 auto" :gap "14px" :min-height "100%"}]
   [".user-chat-col" {:gap "14px" :flex 1 :min-height 0}]
   [".user-chat-head" {:display :flex :align-items :center :justify-content :space-between :gap "8px" :padding-bottom "8px" :border-bottom "1px solid var(--base-800)"}]
   [".canvas-compact-deck" {:padding "11px 15px"}]
   [".workspace-grid" {:display "grid" :grid-template-columns "minmax(0,1.25fr) minmax(0,1fr)" :align-items "start" :height "100%" :min-height "0"}]
   [".workspace-grid:has(>.canvas-timeline-bar)" {:grid-template-rows "auto minmax(0,1fr)"}]
   [".workspace-grid:not(:has(>.canvas-timeline-bar))" {:grid-template-rows "minmax(0,1fr)"}]
   [".workspace-grid>.canvas-timeline-bar" {:grid-column "1 / -1" :align-self "start" :width "100%"}]
   [".workspace-grid>.ws-left,.workspace-grid>.ws-right" {:align-self "stretch"}]
   [".workspace-grid.surface-stack" {:display "flex" :flex-direction "column" :gap "12px" :height "auto" :min-height "0"}]
   [".workspace-grid.surface-stack>.ws-left,.workspace-grid.surface-stack>.ws-right" {:width "100%" :min-height "0" :max-height "none"}]
   [".tiles-scroll" {:overflow-x "hidden"}]
   [".canvas-timeline-bar" {:flex-wrap "wrap" :gap "6px"}]
   [".tl-track,.tl-meta,.ws-head,.wire-summary" {:max-width "100%" :flex-wrap "wrap"}]
   [".table-scroll" {:width "100%" :max-width "100%" :overflow-x :auto :box-sizing :border-box}]
   [".grid-2,.debug-tile-pair" {:grid-template-columns "minmax(0,1fr) minmax(0,1fr)"}]
   [".grid-2.is-stack,.debug-tile-pair.is-stack" {:grid-template-columns "minmax(0,1fr)"}]
   [":root" {:view-transition-name "none"}]
   ["::view-transition" {:pointer-events "none"}]
   [".surface-entity-item" {:view-transition-class "surface-card"}]
   ["::view-transition-group(.surface-card)" {:animation-duration "0.24s" :animation-timing-function "cubic-bezier(.22,1,.36,1)"}]
   ["#seon-app #cv-live-reasoning[open] .live-reasoning" {:display "block" :margin-top "8px"}]
   ["#cv-live-prose,.canvas-pending .live-prose" {:max-height :none :overflow :visible :white-space :normal :margin-top "4px"}]
   [".composer-inp" {:field-sizing :content :min-height "30px" :max-height "220px" :overflow-y :auto}]])

(def ambient-rules
  [[".crumb-bar,.ent-chip,.ent-row,.kv-bar,.lab-chip,.lab-panel,.sig-cell,.sig-panel,.sig-pill,.var-card,.starter-chip" {:border "1px solid var(--base-800)"}]
   [".sig-strip,.sig-panel,.sig-panel-head,.sig-cell-head,.sig-grid,.sig-cell,.ent-chip,.ent-row,.ent-card,.ent-full,.ent-head,.ent-meta,.ent-nested,.var-card" {:min-width 0}]
   [".sig-strip,.sig-panel,.sig-grid,.sig-cell,.ent-chip,.ent-row,.ent-card,.ent-full,.ent-meta,.ent-code,.var-card" {:max-width "100%" :box-sizing :border-box}]
   [".sig-panel,.sig-cell,.ent-card,.ent-full,.ent-nested,.var-card,.crumb-bar" {:display :flex :flex-direction :column}]
   [".ent-link" {:text-underline-offset "2px" :color :inherit :text-align :left :transition "color .12s,text-decoration-color .12s" :overflow-wrap :anywhere :background :none :cursor :pointer :max-width "100%" :font :inherit :padding 0 :text-decoration "underline dotted transparent" :border :none :margin 0}]
   [".ent-link:hover" {:color "var(--signal)" :text-decoration-color "var(--signal)"}]
   [".ent-link.pill" {:background "var(--base-850)" :border "1px solid var(--base-800)" :padding "1px 7px" :border-radius "4px" :text-decoration :none}]
   [".ent-link.badge" {:padding "1px 7px" :border-radius "999px" :border "1px solid var(--base-700)" :text-decoration :none}]
   [".ent-link.pill:hover,.ent-link.badge:hover,.sig-pill-btn:hover,.sig-cell-btn:hover,.lab-chip:hover,.crumb-btn:hover,.starter-chip:hover" {:border-color "var(--signal)"}]
   [".ent-link.pill:hover" {:color "var(--signal)"}]
   [".sig-pill-btn:hover,.sig-cell-btn:hover,.starter-chip:hover" {:background "var(--base-850)"}]
   [".sig-pill-btn:hover,.lab-chip:hover,.crumb-btn:hover,.starter-chip:hover" {:color "var(--text-50)"}]
   [".sig-strip" {:display :flex :flex-wrap :wrap :align-items :center :gap "6px"}]
   [".sig-pill" {:min-width 0 :font-variant-numeric :tabular-nums :align-items :center :color "var(--text-200)" :white-space :nowrap :font-size "11px" :background "var(--base-900)" :padding "2px 8px" :gap "5px" :display :inline-flex :border-radius "999px" :font-family "var(--font-mono)"}]
   [".sig-pill-btn,.sig-cell-btn,.lab-chip,.crumb-btn,.starter-chip" {:cursor :pointer :transition "border-color .14s,background .14s,color .14s"}]
   [".sig-pill-btn:focus-visible,.sig-cell-btn:focus-visible,.ent-link:focus-visible,.lab-chip:focus-visible" {:outline "2px solid var(--signal)" :outline-offset "1px"}]
   [".sig-label" {:color "var(--text-400)" :font-weight 500}]
   [".sig-panel" {:padding "10px 12px" :border-radius "10px" :background "var(--base-900)" :color "var(--text-200)" :gap "8px"}]
   [".sig-panel-head,.sig-cell-head" {:display :flex :align-items :center :gap "6px" :flex-wrap :wrap}]
   [".sig-panel-head strong,.sig-cell-head strong" {:color "var(--text-50)" :font-size "12px" :font-weight 600}]
   [".sig-grid" {:display :grid :grid-template-columns "repeat(auto-fit,minmax(160px,1fr))" :gap "8px"}]
   [".sig-cell" {:padding "8px 10px" :border-radius "8px" :background "var(--base-950)" :color "var(--text-200)" :gap "5px" :text-align :left}]
   [".sig-cell-btn" {:width "100%"}]
   [".sig-dot" {:width "7px" :height "7px" :border-radius "50%" :display :inline-block :flex-shrink 0 :background :currentColor}]
   [".sig-calm" {:color "var(--success)" :border-color "rgba(52,211,153,.32)"}]
   [".sig-warm" {:color "var(--signal)" :border-color "rgba(240,180,41,.48)"}]
   [".sig-hot" {:color "var(--error)" :border-color "rgba(248,113,113,.58)"}]
   [".sig-panel.sig-calm,.sig-panel.sig-warm,.sig-panel.sig-hot,.sig-cell.sig-calm,.sig-cell.sig-warm,.sig-cell.sig-hot" {:color "var(--text-200)"}]
   [".sig-dot.sig-warm,.sig-dot.sig-pulse" {:animation "sig-pulse 2.4s ease-out infinite"}]
   [".sig-dot.sig-hot" {:animation "sig-pulse 1.5s ease-out infinite"}]
   [".heat-bar" {:height "3px" :width "100%" :border-radius "2px" :overflow :hidden :background "rgba(128,128,128,0.18)" :flex-shrink 0}]
   [".heat-fill" {:height "100%" :border-radius "2px" :background "var(--text-400)" :transition "width .25s ease,background .25s ease"}]
   [".heat-fill.sig-calm" {:background "var(--success)"}] [".heat-fill.sig-warm" {:background "var(--signal)"}] [".heat-fill.sig-hot" {:background "var(--error)"}]
   [".hl-term" {:background "rgba(240,180,41,.24)" :color "var(--text-50)" :padding "0 2px" :border-radius "2px"}]
   [".ent-chip" {:align-items :center :font-size "12px" :background "var(--base-900)" :flex-wrap :wrap :padding "3px 8px" :gap "6px" :display :inline-flex :border-radius "6px"}]
   [".ent-row" {:display :flex :align-items :center :gap "8px" :padding "6px 10px" :border-radius "6px" :background "var(--base-900)" :flex-wrap :wrap}]
   [".ent-card" {:gap "8px" :padding "12px 14px" :overflow-wrap :anywhere}]
   [".ent-full" {:gap "10px" :padding "14px 16px" :overflow-wrap :anywhere}]
   [".ent-head" {:margin-bottom "2px" :flex-wrap :wrap :gap "6px"}]
   [".ent-meta" {:margin-left :auto :overflow-wrap :anywhere :word-break :break-word}]
   [".ent-nested" {:margin-top "4px" :padding "8px 10px" :border-left "2px solid var(--success)" :background "var(--base-950)" :border-radius "0 6px 6px 0" :gap "6px"}]
   [".ent-code" {:max-height "280px" :overflow :auto}]
   [".var-col" {:min-width "240px" :max-width "520px" :white-space :normal}]
   [".ent-code.is-expanded" {:max-height "520px" :border-color "rgba(240,180,41,.42)"}]
   [".ctx-group" {:padding-top "4px"}]
   [".ctx-group-head" {:display :flex :justify-content :space-between :align-items :center :padding "2px 2px 4px" :border-bottom "1px solid var(--base-800)"}]
   [".ent-prov-bar" {:display :flex :justify-content :space-between :align-items :center :gap "6px" :padding "0 2px 4px" :flex-wrap :wrap}]
   [".kv-bar" {:display :flex :width "100%" :height "14px" :border-radius "6px" :overflow :hidden :background "var(--base-950)"}]
   [".kv-seg" {:height "100%" :min-width 0 :transition "width .25s ease"}]
   [".kv-seg.is-prefix" {:background "var(--success)"}] [".kv-seg.is-history" {:background "var(--info)"}] [".kv-seg.is-ephem" {:background "var(--signal)"}]
   [".kv-seg.is-unread" {:background "var(--eval)"}] [".kv-seg.is-vision" {:background "var(--text-400)"}]
   [".lab-panel" {:padding "10px 12px" :background "var(--base-950)" :border-radius "8px"}]
   [".lab-controls" {:display :flex :flex-wrap :wrap :align-items :center :gap "6px"}]
   [".lab-chip" {:color "var(--text-300)" :font-size "11px" :background "var(--base-900)" :padding "3px 8px" :border-radius "5px" :font-family "var(--font-mono)"}]
   [".lab-chip.active" {:background "rgba(240,180,41,.14)" :border-color "var(--signal)" :color "var(--signal)" :font-weight 600}]
   [".w-inp" {:width "56px" :margin-left "4px"}]
   [".var-card" {:padding "10px 12px" :border-radius "8px" :background "var(--base-950)" :gap "8px"}]
   [".var-card.is-winner" {:border-color "rgba(52,211,153,.55)" :background "color-mix(in srgb,var(--success) 5%,var(--base-950))"}]
   [".entity-actions" {:display :flex :align-items :center :justify-content :flex-end :flex-wrap :wrap :gap "6px"}]
   [".insp-neighbors" {:display :grid :grid-template-columns "repeat(auto-fit,minmax(320px,1fr))" :gap "14px" :align-items :start}]
   [".cos-bar" {:width "72px" :height "6px" :border-radius "3px" :background "var(--base-850)" :overflow :hidden :flex-shrink 0}]
   [".cos-fill" {:height "100%" :background "var(--eval)" :border-radius "3px"}]
   [".crumb-bar" {:gap "5px" :padding "8px 11px" :background "var(--base-950)" :border-radius "7px" :font-size "11.5px"}]
   [".crumb-row" {:display :flex :flex-wrap :wrap :align-items :center :gap "5px"}]
   [".crumb-label" {:font-family "var(--font-mono)" :font-size "10px" :text-transform :uppercase :letter-spacing ".1em" :color "var(--text-500)" :min-width "48px"}]
   [".crumb-btn" {:background "var(--base-900)" :border "1px solid var(--base-700)" :color "var(--text-200)" :padding "2px 8px" :border-radius "4px" :font-size "11px"}]])

(def markdown-rules
  "GFM, diff, interactive code, alert, task, and live card CSS."
  [[".prose-h1,.prose-h2,.prose-h4,.prose-h5,.prose-h6" {:margin "8px 0 3px" :font-weight 650 :color "var(--text-50)"}]
   [".prose-h1" {:font-size "22px"}] [".prose-h2" {:font-size "19px"}] [".prose-h4,.prose-h5,.prose-h6" {:font-size "14.5px" :color "var(--text-100)"}]
   [".prose-hr" {:border :none :border-top "1px solid var(--base-800)" :margin "10px 0"}]
   [".md-table-wrap,.md-quote,.md-alert,.md-code-block,.md-live-card" {:background "var(--base-900)"}]
   [".md-table-wrap,.md-code-block,.md-live-card" {:border "1px solid var(--base-800)" :border-radius "8px" :overflow :hidden}]
   [".md-alert,.md-code-block,.md-live-card" {:display :flex :flex-direction :column}]
   [".md-table" {:width "100%" :border-collapse :collapse :font-size "13px"}]
   [".md-table th,.md-table td" {:padding "7px 10px" :border-bottom "1px solid var(--base-800)" :text-align :left :vertical-align :top}]
   [".md-table th" {:background "var(--base-850)" :color "var(--text-100)" :font-weight 600}]
   [".md-table tbody tr:last-child td" {:border-bottom :none}]
   [".md-table th,.md-alert-title,.md-code-lang,.md-code-btn" {:font-family "var(--font-mono)" :font-size "11px"}]
   [".md-quote,.md-alert" {:margin "4px 0" :padding "7px 12px" :border-left "3px solid var(--ac,var(--base-700))" :border-radius "0 7px 7px 0"}]
   [".md-quote" {:color "var(--text-300)"}] [".md-alert" {:gap "4px" :--ac "var(--info)"}] [".md-alert-title" {:font-weight 700 :letter-spacing ".08em" :color "var(--ac)"}]
   [".md-alert-tip" {:--ac "var(--success)"}] [".md-alert-important" {:--ac "var(--eval)"}] [".md-alert-warning" {:--ac "var(--warning)"}] [".md-alert-caution" {:--ac "var(--error)"}]
   [".prose-list.has-tasks" {:list-style :none :padding-left "4px"}] [".md-task-item" {:display :flex :align-items :baseline :gap "8px"}] [".md-task-item.is-done" {:color "var(--text-400)"}] [".md-task-check" {:accent-color "var(--success)" :flex-shrink 0}]
   [".md-link" {:color "var(--signal)" :text-decoration "underline dotted"}]
   [".md-ext-link" {:color "var(--info)" :text-decoration :underline :text-underline-offset "2px"}]
   [".md-code-link:hover .inline-code" {:outline "1px solid var(--signal)" :background "var(--base-800)"}]
   [".md-img" {:max-width "100%" :height :auto :border-radius "6px" :border "1px solid var(--base-800)"}]
   [".md-code-head,.md-live-head" {:display :flex :align-items :center :justify-content :space-between :gap "8px" :padding "5px 10px" :background "var(--base-850)" :border-bottom "1px solid var(--base-800)"}]
   [".md-code-lang" {:color "var(--text-400)" :text-transform :uppercase :letter-spacing ".08em"}]
   [".md-code-actions" {:display :inline-flex :align-items :center :gap "6px"}]
   [".md-code-btn" {:background "var(--base-900)" :border "1px solid var(--base-700)" :color "var(--text-300)" :padding "2px 8px" :border-radius "5px" :cursor :pointer}] [".md-code-btn:hover" {:border-color "var(--signal)" :color "var(--text-50)"}] [".md-run-btn" {:border-color "rgba(240,180,41,.48)" :color "var(--signal)"}]
   [".md-code-block pre" {:border :none :border-radius 0}]
   [".md-diff" {:padding "6px 0"}] [".diff-line" {:display :block :padding "0 11px" :white-space :pre}]
   [".diff-add,.diff-del,.diff-hunk" {:background "color-mix(in srgb,var(--dc) 13%,transparent)" :color "var(--dc)"}]
   [".diff-add" {:--dc "var(--success)"}] [".diff-del" {:--dc "var(--error)"}] [".diff-hunk" {:--dc "var(--info)"}] [".diff-ctx" {:color "var(--text-300)"}]
   [".md-live-body" {:padding "12px 14px" :min-width 0 :max-width "100%"}]
   [".md-live-source" {:border-top "1px solid var(--base-800)" :padding "6px 10px" :background "var(--base-950)"}]
   [".md-live-source>summary" {:cursor :pointer}]])

(defn full-css
  {:malli/schema [:=> [:cat] :string] :cost/ticks 3000000 :cost/bytes 4194304}
  []
  (str (str/join "\n" (into [css/app-css] (map compile-css [activity-rules stability-rules surface-layout-rules ambient-rules markdown-rules]))) "\n"
       "@keyframes seon-ring{0%{box-shadow:0 0 0 0 color-mix(in srgb,currentColor 55%,transparent)}70%{box-shadow:0 0 0 6px transparent}100%{box-shadow:0 0 0 0 transparent}}\n"
       "@keyframes sig-pulse{0%{box-shadow:0 0 0 0 color-mix(in srgb,currentColor 45%,transparent)}70%{box-shadow:0 0 0 6px transparent}100%{box-shadow:0 0 0 0 transparent}}\n"
       "@keyframes seon-sheet-up{from{transform:translateY(100%)}to{transform:translateY(0)}}\n"
       "@media(prefers-reduced-motion:reduce){.stream-dot,.sig-dot,.status-sidebar{animation:none}::view-transition-group(*),::view-transition-old(*),::view-transition-new(*){animation:none!important}}\n"
       "@media(max-width:960px){.status-sidebar{position:fixed;top:auto;left:0;right:0;bottom:0;z-index:40;width:100%;max-width:100%;height:auto;max-height:42vh;border-left:none;border-top:1px solid var(--base-800);border-radius:14px 14px 0 0;box-shadow:0 -12px 36px rgba(0,0,0,.48);animation:seon-sheet-up .18s ease-out}.live-thinking-panel{padding:10px 16px}}\n"
       "@media(max-width:759px){.sidebar.open{display:none}.surface-entity-grid{grid-template-columns:1fr}.surface-entity-item{min-width:0;max-width:100%;overflow-wrap:anywhere}.kv-grid{grid-template-columns:110px 1fr}.session-split,.view-scroll{padding-bottom:96px}}\n"
       ".hovercard{max-width:min(360px,calc(100vw - 24px));padding:10px 12px;border-radius:9px;border:1px solid var(--base-700);background:var(--base-900);color:var(--text-200);box-shadow:0 14px 36px rgba(0,0,0,.48);pointer-events:auto;z-index:80;font-size:12.5px}\n"
       ".hovercard-body{display:flex;flex-direction:column;gap:6px;min-width:0;max-width:100%}\n"
       ".hovercard-body .card{background:transparent;border:none;padding:0}\n"
       ".hovercard-foot{padding-top:6px;border-top:1px solid var(--base-800);display:flex;justify-content:space-between;align-items:center}\n"
       "@supports (position-area: block-end){.hovercard{inset:auto;margin:0;position-area:block-end span-inline-end;position-try-fallbacks:flip-block,flip-inline,flip-block flip-inline;margin-block-start:6px}}\n"
       "@media (prefers-reduced-motion: no-preference){.hovercard{opacity:0;transform:translateY(4px);transition:opacity .15s ease,transform .15s ease,display .15s allow-discrete,overlay .15s allow-discrete;transition-behavior:allow-discrete}.hovercard:popover-open{opacity:1;transform:translateY(0)}@starting-style{.hovercard:popover-open{opacity:0;transform:translateY(4px)}}}\n"
       ".hdr-nav{display:inline-flex;align-items:center;gap:4px;flex-shrink:0}.hdr-nav .icon-btn:disabled{opacity:.35;cursor:default}"))
