(ns seon.web.styles
  "Pure-Clojure runtime CSS compiler and modular Clojure-data style sheets.
   Mounted live via `<style id=\"seon-css\">` so style edits hot-patch open browsers via Datastar with zero refresh."
  (:require [clojure.string :as str]
            [seon.web.css :as css]))

(defn- css-val [v]
  (cond
    (keyword? v) (name v)
    (ratio? v) (str (/ (Math/round (* (double v) 1000.0)) 1000.0))
    :else (str v)))

(defn- css-name [x]
  (if (ident? x) (name x) (str x)))

(defn compile-css
  "Compiles Clojure-data CSS rules into a minified stylesheet string.
   Each rule is `[selector props-map]` or `[\"@media(...)\" nested-rules-vec]`."
  {:malli/schema [:=> [:cat [:vector [:tuple [:or :string :keyword] [:map-of [:or :string :keyword] [:or :string :int :keyword]]]]] :string]
   :cost/ticks 500000
   :cost/bytes 2097152}
  [rules]
  (str/join
   "\n"
   (keep (fn [rule]
           (when (and (vector? rule) (= 2 (count rule)))
             (let [[sel body] rule
                   sel-s (if (sequential? sel) (str/join "," (map css-name sel)) (css-name sel))]
               (cond
                 (map? body)
                 (let [decls (keep (fn [[k v]]
                                     (when (and (some? k) (some? v))
                                       (str (css-name k) ":" (css-val v))))
                                   body)]
                   (when (seq decls)
                     (str sel-s "{" (str/join ";" decls) "}")))

                 (sequential? body)
                 (let [inner (compile-css body)]
                   (when-not (str/blank? inner)
                     (str sel-s "{" inner "}")))))))
         rules)))

(def activity-rules
  "Connected vertical list styling for `Thinking & actions` steps and their expandable detail drawers."
  [[".activity-fold" {:background "var(--base-900)" :border "1px solid var(--base-800)"
                      :border-radius "8px" :padding "7px 12px"}]
   [".activity-list" {:margin-top "8px" :border-top "1px solid var(--base-800)"
                      :display "flex" :flex-direction "column"}]
   [".step-row" {:border-bottom "1px solid var(--base-850)" :padding "0"}]
   [".step-row:last-child" {:border-bottom "none"}]
   [".step-summary" {:display "flex" :align-items "baseline" :gap "9px"
                     :padding "7px 6px" :border-radius "5px" :cursor "pointer"
                     :list-style "none" :min-width "0"
                     :transition "background .12s,color .12s"}]
   [".step-summary::-webkit-details-marker" {:display "none"}]
   [".step-summary:hover" {:background "var(--base-850)"}]
   [".step-summary::before" {:content "'▸'" :color "var(--text-500)"
                             :font-family "var(--font-mono)" :font-size "11px"
                             :flex-shrink "0" :transition "color .12s"}]
   [".step-row[open]>.step-summary::before" {:content "'▾'" :color "var(--signal)"}]
   [".step-row[open]>.step-summary" {:color "var(--text-50)"}]
   [".step-title" {:flex "1" :min-width "0" :overflow "hidden"
                   :text-overflow "ellipsis" :white-space "nowrap"
                   :font-family "var(--font-text)" :font-size "13px"
                   :font-weight "450" :line-height "1.45"
                   :color "var(--text-100)" :letter-spacing "-.006em"}]
   [".step-row.is-thought .step-title" {:color "var(--text-400)" :font-style "italic"}]
   [".step-row.has-error .step-title" {:color "var(--error)"}]
   [".step-err-tag" {:font-family "var(--font-mono)" :font-size "10.5px"
                     :color "var(--error)" :flex-shrink "0"}]
   [".step-ms" {:flex-shrink "0" :margin-left "auto" :font-family "var(--font-mono)"
                :font-size "11px" :font-variant-numeric "tabular-nums"
                :color "var(--text-400)"}]
   [".step-drawer" {:margin "2px 0 10px 18px" :padding "6px 0 2px 12px"
                    :border-left "1px solid var(--base-700)"
                    :display "flex" :flex-direction "column" :gap "5px"}]
   [".step-meta-row" {:display "flex" :align-items "center" :gap "7px" :padding "0 2px 2px"}]
   [".step-subs" {:display "flex" :flex-direction "column" :gap "3px"}]
   [".sub-fold" {:border-radius "5px" :padding "2px 0"}]
   [".sub-summary" {:display "flex" :align-items "baseline" :gap "8px"
                    :padding "4px 6px" :border-radius "5px" :cursor "pointer"
                    :list-style "none" :min-width "0" :font-size "12px"
                    :color "var(--text-300)" :transition "background .12s"}]
   [".sub-summary::-webkit-details-marker" {:display "none"}]
   [".sub-summary:hover" {:background "var(--base-850)" :color "var(--text-100)"}]
   [".sub-summary::before" {:content "'▸'" :color "var(--text-500)"
                            :font-family "var(--font-mono)" :font-size "10.5px" :flex-shrink "0"}]
   [".sub-fold[open]>.sub-summary::before" {:content "'▾'" :color "var(--signal)"}]
   [".sub-label" {:font-family "var(--font-mono)" :font-size "11px" :font-weight "500"
                  :color "var(--text-300)" :min-width "58px" :flex-shrink "0"}]
   [".sub-lead" {:flex "1" :min-width "0" :overflow "hidden"
                 :text-overflow "ellipsis" :white-space "nowrap"
                 :font-family "var(--font-mono)" :font-size "11.5px"
                 :color "var(--text-400)"}]
   [".sub-lead.is-result" {:color "var(--success)"}]
   [".sub-lead.is-error" {:color "var(--error)"}]
   [".sub-meta" {:flex-shrink "0" :margin-left "auto"}]
   [".sub-body" {:margin "5px 0 4px 16px"}]])

(def stability-rules
  "Modern CSS Grid layout (`#seon-sidebar` | `#seon-main`+in-flow `.live-thinking-panel`+composer | collapsible 300px `#seon-status-sidebar`), fixed 60px opaque 2-line status cards with color-coded phases & tabular-nums tok/s, scroll isolation, containment, and `<details>` stability."
  [[".app-body"
    {:display "grid"
     :grid-template-columns "auto minmax(0,1fr) auto"
     :grid-template-rows "minmax(0,1fr) auto auto"}]
   ["#seon-sidebar" {:grid-column "1" :grid-row "1 / -1"}]
   ["#seon-main" {:grid-column "2" :grid-row "1" :min-width "0" :min-height "0"}]
   ["#seon-bottom-dock" {:grid-column "2" :grid-row "3" :min-width "0"}]
   ["#seon-stream-unit" {:display "contents"}]
   [".stream-center-slot"
    {:grid-column "2"
     :grid-row "2"
     :min-width "0"
     :display "flex"
     :flex-direction "column"}]
   [".stage-scroll,.ws-timeline,.tiles-scroll,.tree-scroll,.status-list,.live-drawer-body,.live-reasoning,.live-prose"
    {:overscroll-behavior "contain"
     :overflow-anchor "auto"}]
   [".stage-scroll,.ws-timeline,.tree-scroll,.status-list"
    {:scrollbar-gutter "stable"}]
   [".sidebar.open,.status-sidebar" {:contain "layout style"}]
   ["details::details-content" {:contain "layout"}]
   [".phase-thinking" {:--phase-color "var(--signal)"}]
   [".phase-tools" {:--phase-color "var(--info)"}]
   [".phase-writing" {:--phase-color "var(--eval)"}]
   [".phase-testing,.phase-merging" {:--phase-color "var(--success)"}]
   [".phase-working" {:--phase-color "var(--text-400)"}]
   [".status-toggle-pill"
    {:display "inline-flex"
     :align-items "center"
     :gap "6px"
     :height "28px"
     :padding "0 10px"
     :border-radius "999px"
     :border "1px solid rgba(240,180,41,.42)"
     :background "rgba(240,180,41,.09)"
     :color "var(--signal)"
     :font-family "var(--font-mono)"
     :font-size "11px"
     :font-weight "500"
     :cursor "pointer"
     :flex-shrink "0"
     :white-space "nowrap"
     :transition "border-color .15s,background .15s"}]
   [".status-toggle-pill:hover,.status-toggle-pill.active"
    {:border-color "var(--signal)"
     :background "rgba(240,180,41,.18)"}]
   [".status-toggle-count,.status-toggle-rate"
    {:font-variant-numeric "tabular-nums"}]
   [".status-toggle-rate" {:color "var(--text-200)"}]
   [".status-toggle-idle"
    {:display "inline-flex"
     :align-items "center"
     :gap "6px"
     :height "28px"
     :padding "0 9px"
     :border-radius "999px"
     :border "1px solid var(--base-800)"
     :background "transparent"
     :color "var(--text-400)"
     :font-family "var(--font-mono)"
     :font-size "11px"
     :cursor "pointer"
     :flex-shrink "0"
     :white-space "nowrap"
     :transition "border-color .15s,color .15s,background .15s"}]
   [".status-toggle-idle:hover,.status-toggle-idle.active"
    {:border-color "var(--signal)"
     :color "var(--signal)"
     :background "rgba(240,180,41,.07)"}]
   ["#seon-app[data-status='closed'] .status-sidebar" {:display "none"}]
   ["#seon-app[data-status='open'] .status-sidebar" {:display "flex"}]
   [".status-sidebar"
    {:grid-column "3"
     :grid-row "1 / -1"
     :width "300px"
     :flex-shrink "0"
     :background "var(--base-900)"
     :opacity "1"
     :border-left "1px solid var(--base-800)"
     :flex-direction "column"
     :overflow "hidden"}]
   [".status-sidebar-inner"
    {:display "flex"
     :flex-direction "column"
     :height "100%"
     :padding "12px 10px"
     :gap "10px"
     :overflow "hidden"}]
   [".status-close-btn"
    {:width "24px"
     :height "24px"
     :font-size "14px"
     :line-height "1"}]
   [".status-list"
    {:flex "1"
     :min-height "0"
     :overflow-y "auto"
     :display "flex"
     :flex-direction "column"
     :gap "8px"
     :padding "2px 0"}]
   [".status-empty"
    {:padding "14px 8px"
     :display "flex"
     :flex-direction "column"
     :gap "4px"}]
   [".status-row"
    {:display "flex"
     :flex-direction "column"
     :justify-content "center"
     :gap "4px"
     :height "60px"
     :min-height "60px"
     :max-height "60px"
     :box-sizing "border-box"
     :padding "8px 10px"
     :overflow "hidden"
     :background "var(--base-950)"
     :opacity "1"
     :border "1px solid var(--base-800)"
     :border-left "3px solid var(--phase-color,var(--signal))"
     :border-radius "8px"
     :cursor "pointer"
     :flex-shrink "0"
     :position "relative"
     :contain "layout style"
     :transition "border-color .14s,background .14s"}]
   [".status-row:hover"
    {:background "var(--base-850)"
     :border-color "var(--base-700)"
     :border-left-color "var(--phase-color,var(--signal))"}]
   [".status-row.is-viewed"
    {:border-color "color-mix(in srgb,var(--phase-color,var(--signal)) 55%,var(--base-800))"
     :border-left-color "var(--phase-color,var(--signal))"}]
   [".status-row-top"
    {:display "flex"
     :align-items "center"
     :flex-wrap "nowrap"
     :gap "6px"
     :height "22px"
     :min-height "22px"
     :max-height "22px"
     :white-space "nowrap"
     :overflow "hidden"}]
   [".stream-dot"
    {:width "8px"
     :height "8px"
     :border-radius "50%"
     :flex-shrink "0"
     :color "var(--phase-color,var(--signal))"
     :background "currentColor"
     :animation "seon-ring 1.6s ease-out infinite"}]
   [".stream-agent-name"
    {:flex "1 1 0"
     :min-width "0"
     :overflow "hidden"
     :text-overflow "ellipsis"
     :white-space "nowrap"
     :font-family "var(--font-mono)"
     :font-size "12px"
     :font-weight "600"
     :color "var(--text-100)"}]
   [".stream-rate"
    {:flex-shrink "0"
     :min-width "54px"
     :text-align "right"
     :white-space "nowrap"
     :font-family "var(--font-mono)"
     :font-size "10.5px"
     :font-variant-numeric "tabular-nums"
     :color "var(--text-300)"}]
   [".stream-phase-pill,.stream-phase-badge"
    {:flex-shrink "0"
     :min-width "62px"
     :text-align "center"
     :white-space "nowrap"
     :font-family "var(--font-mono)"
     :font-size "10px"
     :font-weight "600"
     :line-height "1.25"
     :padding "2px 7px"
     :border-radius "999px"
     :color "var(--phase-color,var(--signal))"
     :border "1px solid color-mix(in srgb,var(--phase-color,var(--signal)) 44%,transparent)"
     :background "color-mix(in srgb,var(--phase-color,var(--signal)) 14%,var(--base-900))"}]
   [".stream-phase-pill"
    {:cursor "pointer"
     :transition "background .14s,border-color .14s"}]
   [".stream-phase-pill:hover,#seon-app[data-thinking='open'] .stream-phase-pill.is-viewed-pill"
    {:border-color "var(--phase-color,var(--signal))"
     :background "color-mix(in srgb,var(--phase-color,var(--signal)) 24%,var(--base-900))"}]
   [".status-ctx-bar"
    {:position "absolute"
     :left "0"
     :bottom "0"
     :height "2px"
     :background "var(--signal)"
     :border-radius "0 2px 0 0"
     :pointer-events "none"
     :transition "width .25s ease"}]
   [".status-ctx-bar.is-warm" {:background "#f59e0b"}]
   [".status-ctx-bar.is-hot" {:background "var(--err,#f87171)"}]
   [".status-row-snippet"
    {:height "18px"
     :min-height "18px"
     :max-height "18px"
     :line-height "18px"
     :white-space "nowrap"
     :overflow "hidden"
     :text-overflow "ellipsis"
     :font-family "var(--font-text)"
     :font-size "11.5px"
     :color "var(--text-400)"}]
   ["#seon-app[data-thinking='closed'] .live-thinking-panel" {:display "none"}]
   ["#seon-app[data-thinking='open'] .live-thinking-panel" {:display "flex"}]
   [".live-thinking-panel"
    {:flex-direction "column"
     :padding "10px 24px 12px"
     :background "var(--base-900)"
     :opacity "1"
     :border-top "1px solid var(--base-800)"
     :border-left "3px solid var(--phase-color,var(--signal))"
     :contain "layout style"}]
   [".live-drawer-inner"
    {:max-width "860px"
     :width "100%"
     :margin "0 auto"
     :display "flex"
     :flex-direction "column"
     :gap "8px"}]
   [".live-drawer-head"
    {:display "flex"
     :align-items "center"
     :justify-content "space-between"
     :gap "10px"
     :flex-wrap "nowrap"
     :min-height "24px"}]
   [".live-drawer-meta"
    {:display "flex"
     :align-items "center"
     :gap "8px"
     :min-width "0"
     :flex "1 1 0"
     :overflow "hidden"}]
   [".stream-drawer-name"
    {:overflow "hidden"
     :text-overflow "ellipsis"
     :white-space "nowrap"
     :color "var(--text-50)"}]
   [".live-drawer-body"
    {:max-height "min(220px,30vh)"
     :overflow-y "auto"
     :padding "10px 12px"
     :background "var(--base-950)"
     :border "1px solid var(--base-800)"
     :border-radius "6px"
     :contain "layout style paint"
     :display "flex"
     :flex-direction "column"
     :gap "10px"}]
   [".live-drawer-body .live-reasoning"
    {:max-height "none"
     :overflow "visible"
     :padding "0"
     :background "transparent"
     :border "none"
     :border-radius "0"
     :contain "none"}]
   [".live-drawer-body .live-prose"
    {:max-height "none"
     :overflow "visible"
     :contain "none"
     :font-size "14px"
     :line-height "1.62"}]
   [".live-drawer-body .live-reasoning+.live-prose"
    {:border-top "1px solid var(--base-800)"
     :padding-top "10px"}]
   [".live-drawer-head .thinking-toggle.is-close"
    {:flex-shrink "0"
     :height "24px"
     :padding "0 10px"
     :display "inline-flex"
     :align-items "center"
     :justify-content "center"
     :white-space "nowrap"
     :background "var(--base-850)"
     :border "1px solid var(--base-700)"
     :color "var(--text-200)"
     :border-radius "5px"}]
   [".live-drawer-head .thinking-toggle.is-close:hover"
    {:border-color "var(--phase-color,var(--signal))"
     :color "var(--text-50)"}]
   [".stream-error-bar" {:margin "8px 24px"}]])

(def surface-layout-rules
  "Fluid overflow containment, Datahike `:viewport-w` narrow header/sidebar collapse, and 2-column `.workspace-grid` spanning rules."
  [["html,body,.app-shell,.app-body,main,#seon-main"
    {:max-width "100%" :box-sizing "border-box"}]
   ["main,#seon-main" {:min-width "0" :overflow-x "hidden"}]
   [".sidebar.closed,.sidebar.sidebar-collapsed" {:display "none"}]
   [".topbar" {:max-width "100%" :box-sizing "border-box"}]
   [".topbar.is-narrow" {:padding "4px 8px" :height "auto" :min-height "44px"}]
   [".topbar.is-narrow .brand-row" {:gap "6px" :flex-wrap "wrap"}]
   [".topbar.is-narrow .brand" {:gap "5px"}]
   [".topbar.is-narrow .stats-strip" {:gap "4px" :min-width "0" :max-width "100%"}]
   [".topbar.is-narrow .icon-btn" {:width "26px" :height "26px"}]
   [".topbar.is-narrow .status-pill,.topbar.is-narrow .status-toggle-idle,.topbar.is-narrow .status-toggle-pill"
    {:height "26px" :padding "0 7px"}]
   [".topbar.is-narrow .nav-pill" {:padding "3px 7px" :font-size "10.5px"}]
   [".workspace-grid"
    {:display "grid"
     :grid-template-columns "minmax(0,1.25fr) minmax(0,1fr)"
     :align-items "start"
     :height "100%"
     :min-height "0"
     :min-width "0"
     :max-width "100%"
     :box-sizing "border-box"}]
   [".workspace-grid:has(>.canvas-timeline-bar)" {:grid-template-rows "auto minmax(0,1fr)"}]
   [".workspace-grid:not(:has(>.canvas-timeline-bar))" {:grid-template-rows "minmax(0,1fr)"}]
   [".workspace-grid>.canvas-timeline-bar" {:grid-column "1 / -1" :align-self "start" :width "100%"}]
   [".workspace-grid>.ws-left,.workspace-grid>.ws-right"
    {:align-self "stretch" :min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [".workspace-grid.surface-stack"
    {:display "flex"
     :flex-direction "column"
     :gap "12px"
     :height "auto"
     :min-height "0"
     :min-width "0"
     :max-width "100%"
     :box-sizing "border-box"}]
   [".workspace-grid.surface-stack>.ws-left,.workspace-grid.surface-stack>.ws-right"
    {:width "100%" :min-width "0" :max-width "100%" :min-height "0" :max-height "none"}]
   [".surface-stack,.ws-left,.ws-right,.debug-wrap,.root-canvas,.surface-entities,.surface-entity-item,.surface-signals,.card,.stack,.stack-sm"
    {:min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [".canvas-timeline-bar"
    {:flex-wrap "wrap" :gap "6px" :min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [".tl-track,.tl-meta,.ws-head,.wire-summary"
    {:min-width "0" :max-width "100%" :flex-wrap "wrap"}]
   [".wire-summary .flex-1" {:min-width "0"}]
   [".table-scroll"
    {:width "100%" :max-width "100%" :overflow-x "auto" :box-sizing "border-box"}]
   [".grid-2" {:grid-template-columns "minmax(0,1fr) minmax(0,1fr)"}]
   [".debug-tile-pair"
    {:grid-template-columns "minmax(0,1fr) minmax(0,1fr)"
     :min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [".grid-2.is-stack,.debug-tile-pair.is-stack" {:grid-template-columns "minmax(0,1fr)"}]
   [".grid-2>*,.debug-tile-pair>*,.debug-tile-col"
    {:min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [":root" {:view-transition-name "none"}]
   ["::view-transition" {:pointer-events "none"}]
   [".surface-entity-item" {:view-transition-class "surface-card"}]
   ["::view-transition-group(.surface-card)"
    {:animation-duration "0.24s" :animation-timing-function "cubic-bezier(.22,1,.36,1)"}]
   ["#seon-app #cv-live-reasoning[open] .live-reasoning" {:display "block" :margin-top "8px"}]
   ["#cv-live-prose" {:margin-top "4px"}]])

(def ambient-rules
  "Phosphor styles for ambient operational signals, hairline heat bars, uniform entity views, and schema breadcrumbs."
  [[".sig-strip" {:display "flex" :flex-wrap "wrap" :align-items "center" :gap "6px" :min-width "0" :max-width "100%"}]
   [".sig-pill" {:display "inline-flex" :align-items "center" :gap "5px" :padding "2px 8px" :border-radius "999px" :border "1px solid var(--base-800)" :background "var(--base-900)" :font-family "var(--font-mono)" :font-size "11px" :font-variant-numeric "tabular-nums" :color "var(--text-200)" :white-space "nowrap" :min-width "0"}]
   [".sig-label" {:color "var(--text-400)" :font-weight "500"}]
   [".sig-panel" {:padding "10px 12px" :border-radius "10px" :border "1px solid var(--base-800)" :background "var(--base-900)" :color "var(--text-200)" :display "flex" :flex-direction "column" :gap "8px" :min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [".sig-panel-head,.sig-cell-head" {:display "flex" :align-items "center" :gap "6px" :flex-wrap "wrap" :min-width "0"}]
   [".sig-panel-head strong,.sig-cell-head strong" {:color "var(--text-50)" :font-size "12px" :font-weight "600"}]
   [".sig-grid" {:display "grid" :grid-template-columns "repeat(auto-fit,minmax(150px,1fr))" :gap "8px" :min-width "0" :max-width "100%"}]
   [".sig-cell" {:padding "8px 10px" :border-radius "8px" :border "1px solid var(--base-800)" :background "var(--base-950)" :color "var(--text-200)" :display "flex" :flex-direction "column" :gap "5px" :min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [".sig-dot" {:width "7px" :height "7px" :border-radius "50%" :display "inline-block" :flex-shrink "0" :background "currentColor"}]
   [".sig-calm" {:color "var(--success)" :border-color "rgba(52,211,153,.32)"}]
   [".sig-warm" {:color "var(--signal)" :border-color "rgba(240,180,41,.48)"}]
   [".sig-hot" {:color "var(--error)" :border-color "rgba(248,113,113,.58)"}]
   [".sig-panel.sig-calm,.sig-panel.sig-warm,.sig-panel.sig-hot,.sig-cell.sig-calm,.sig-cell.sig-warm,.sig-cell.sig-hot"
    {:color "var(--text-200)"}]
   [".sig-dot.sig-warm,.sig-dot.sig-pulse" {:animation "sig-pulse 2.4s ease-out infinite"}]
   [".sig-dot.sig-hot" {:animation "sig-pulse 1.5s ease-out infinite"}]
   [".heat-bar" {:height "3px" :width "100%" :border-radius "2px" :overflow "hidden" :background "rgba(128,128,128,0.18)" :flex-shrink "0"}]
   [".heat-fill" {:height "100%" :border-radius "2px" :background "var(--text-400)" :transition "width .25s ease,background .25s ease"}]
   [".heat-fill.sig-calm" {:background "var(--success)"}]
   [".heat-fill.sig-warm" {:background "var(--signal)"}]
   [".heat-fill.sig-hot" {:background "var(--error)"}]
   [".ent-chip" {:display "inline-flex" :align-items "center" :gap "6px" :padding "3px 8px" :border-radius "6px" :background "var(--base-900)" :border "1px solid var(--base-800)" :font-size "12px" :min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [".ent-row" {:display "flex" :align-items "center" :gap "8px" :padding "6px 10px" :border-radius "6px" :background "var(--base-900)" :border "1px solid var(--base-800)" :min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [".ent-card" {:display "flex" :flex-direction "column" :gap "8px" :padding "12px 14px" :min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [".ent-full" {:display "flex" :flex-direction "column" :gap "10px" :padding "14px 16px" :min-width "0" :max-width "100%" :box-sizing "border-box"}]
   [".ent-head" {:margin-bottom "2px"}]
   [".ent-meta" {:margin-left "auto" :flex-shrink "0"}]
   [".ent-nested" {:margin-top "4px" :padding "8px 10px" :border-left "2px solid var(--success)" :background "var(--base-950)" :border-radius "0 6px 6px 0" :display "flex" :flex-direction "column" :gap "6px"}]
   [".ent-code" {:max-height "280px" :overflow "auto" :max-width "100%"}]
   [".crumb-bar" {:display "flex" :flex-direction "column" :gap "5px" :padding "8px 11px" :background "var(--base-950)" :border "1px solid var(--base-800)" :border-radius "7px" :font-size "11.5px"}]
   [".crumb-row" {:display "flex" :flex-wrap "wrap" :align-items "center" :gap "5px"}]
   [".crumb-label" {:font-family "var(--font-mono)" :font-size "10px" :text-transform "uppercase" :letter-spacing ".1em" :color "var(--text-500)" :min-width "48px"}]
   [".crumb-btn" {:background "var(--base-900)" :border "1px solid var(--base-700)" :color "var(--text-200)" :padding "2px 8px" :border-radius "4px" :cursor "pointer" :font-size "11px" :transition "border-color .14s,color .14s"}]
   [".crumb-btn:hover" {:border-color "var(--signal)" :color "var(--text-50)"}]
   [".crumb-btn.accent" {:border-color "rgba(240,180,41,.45)" :color "var(--signal)"}]
   [".crumb-btn.success" {:border-color "rgba(52,211,153,.45)" :color "var(--success)"}]])

(defn full-css
  "Assembles the complete live stylesheet from base `seon.web.css/app-css` plus compiled Clojure-data rules."
  {:malli/schema [:=> [:cat] :string]
   :cost/ticks 2000000
   :cost/bytes 4194304}
  []
  (str css/app-css "\n"
       (compile-css activity-rules) "\n"
       (compile-css stability-rules) "\n"
       (compile-css surface-layout-rules) "\n"
       (compile-css ambient-rules) "\n"
       "@keyframes seon-ring{0%{box-shadow:0 0 0 0 color-mix(in srgb,currentColor 55%,transparent)}70%{box-shadow:0 0 0 6px transparent}100%{box-shadow:0 0 0 0 transparent}}\n"
       "@keyframes sig-pulse{0%{box-shadow:0 0 0 0 color-mix(in srgb,currentColor 45%,transparent)}70%{box-shadow:0 0 0 6px transparent}100%{box-shadow:0 0 0 0 transparent}}\n"
       "@keyframes seon-sheet-up{from{transform:translateY(100%)}to{transform:translateY(0)}}\n"
       "@media(prefers-reduced-motion:reduce){.stream-dot,.sig-dot,.status-sidebar{animation:none}::view-transition-group(*),::view-transition-old(*),::view-transition-new(*){animation:none!important}}\n"
       "@media(max-width:1080px){.workspace-grid{grid-template-columns:minmax(0,1fr);height:auto}.grid-2,.debug-tile-pair{grid-template-columns:minmax(0,1fr)}}\n"
       "@media(max-width:960px){.status-sidebar{position:fixed;top:auto;left:0;right:0;bottom:0;z-index:40;width:100%;max-width:100%;height:auto;max-height:42vh;border-left:none;border-top:1px solid var(--base-800);border-radius:14px 14px 0 0;box-shadow:0 -12px 36px rgba(0,0,0,.48);animation:seon-sheet-up .18s ease-out}.live-thinking-panel{padding:10px 16px}}\n"
       "@media(max-width:740px){.status-toggle-idle .status-label,.status-toggle-rate{display:none}.status-toggle-pill{padding:0 8px}}"))
