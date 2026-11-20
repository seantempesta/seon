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
  "Modern CSS scroll isolation, layout containment, fixed-height opaque stream bubbles with color-coded phases & tabular-nums tok/s, hideable dock, dedicated live-thinking drawer, and `<details>` layout stability."
  [[".stage-scroll,.ws-timeline,.tiles-scroll,.tree-scroll,.stream-bubble-list,.live-drawer-body,.live-reasoning,.live-prose"
    {:overscroll-behavior "contain"
     :overflow-anchor "auto"}]
   [".stage-scroll,.ws-timeline,.tree-scroll"
    {:scrollbar-gutter "stable"}]
   [".sidebar.open"
    {:contain "layout style"}]
   ["details::details-content"
    {:contain "layout"}]
   [".stream-dock.active"
    {:display "flex"
     :flex-direction "column"
     :gap "8px"
     :max-height "none"
     :overflow "visible"
     :padding "0"
     :contain "layout style"}]
   [".phase-thinking" {:--phase-color "var(--signal)"}]
   [".phase-tools" {:--phase-color "var(--info)"}]
   [".phase-writing" {:--phase-color "var(--eval)"}]
   [".phase-testing,.phase-merging" {:--phase-color "var(--success)"}]
   [".phase-working" {:--phase-color "var(--text-400)"}]
   ["#seon-app[data-stream='open'] .stream-collapsed-pill" {:display "none"}]
   ["#seon-app[data-stream='collapsed'] .stream-cards" {:display "none"}]
   ["#seon-app[data-stream='collapsed'] .stream-collapsed-pill"
    {:display "inline-flex"
     :align-self "center"}]
   ["#seon-app[data-thinking='closed'][data-stream='collapsed'] .stream-dock.active"
    {:width "auto"
     :max-width "calc(100% - 32px)"}]
   [".stream-cards"
    {:display "flex"
     :flex-direction "column"
     :gap "6px"}]
   [".stream-dock-bar"
    {:display "flex"
     :align-items "center"
     :justify-content "space-between"
     :gap "8px"
     :padding "3px 10px"
     :background "var(--base-900)"
     :opacity "1"
     :border "1px solid var(--base-800)"
     :border-radius "7px"
     :box-shadow "0 4px 14px rgba(0,0,0,.32)"}]
   [".stream-dock-label"
    {:font-size "10px"
     :letter-spacing ".12em"
     :color "var(--text-300)"}]
   [".stream-dock-btn"
    {:background "var(--base-850)"
     :border "1px solid var(--base-700)"
     :color "var(--text-200)"
     :padding "2px 9px"
     :border-radius "5px"
     :font-family "var(--font-mono)"
     :font-size "10.5px"
     :font-weight "500"
     :cursor "pointer"
     :white-space "nowrap"
     :flex-shrink "0"}]
   [".stream-dock-btn:hover"
    {:border-color "var(--signal)"
     :color "var(--signal)"}]
   [".stream-collapsed-pill"
    {:align-items "center"
     :gap "10px"
     :background "var(--base-900)"
     :opacity "1"
     :border "1px solid var(--base-700)"
     :border-left "3px solid var(--signal)"
     :border-radius "999px"
     :padding "6px 12px"
     :box-shadow "0 8px 24px rgba(0,0,0,.42)"}]
   [".stream-bubble-list"
    {:display "flex"
     :flex-direction "column"
     :gap "6px"
     :max-height "186px"
     :overflow-y "auto"
     :padding "1px 0"}]
   [".stream-bubble"
    {:display "flex"
     :align-items "center"
     :flex-wrap "nowrap"
     :gap "10px"
     :height "40px"
     :min-height "40px"
     :max-height "40px"
     :padding "0 10px 0 12px"
     :overflow "hidden"
     :background "var(--base-900)"
     :opacity "1"
     :border "1px solid var(--base-700)"
     :border-left "3px solid var(--phase-color,var(--signal))"
     :border-radius "8px"
     :box-shadow "0 6px 18px rgba(0,0,0,.38)"
     :contain "layout style"}]
   [".stream-bubble.is-viewed"
    {:border-color "color-mix(in srgb,var(--phase-color,var(--signal)) 55%,var(--base-700))"
     :border-left-color "var(--phase-color,var(--signal))"}]
   [".stream-dot"
    {:width "7px"
     :height "7px"
     :border-radius "50%"
     :flex-shrink "0"
     :color "var(--phase-color,var(--signal))"
     :background "currentColor"
     :animation "seon-ring 1.6s ease-out infinite"}]
   [".stream-agent-btn"
    {:flex "1 1 0"
     :min-width "0"
     :overflow "hidden"
     :text-overflow "ellipsis"
     :white-space "nowrap"
     :text-align "left"
     :background "none"
     :border "none"
     :padding "0"
     :color "var(--text-100)"
     :cursor "pointer"
     :font-family "var(--font-mono)"
     :font-size "12px"
     :font-weight "600"}]
   [".stream-agent-btn:hover"
    {:color "var(--signal)"}]
   [".stream-phase-badge"
    {:flex-shrink "0"
     :min-width "76px"
     :text-align "center"
     :white-space "nowrap"
     :font-family "var(--font-mono)"
     :font-size "10.5px"
     :font-weight "600"
     :line-height "1.3"
     :padding "2px 8px"
     :border-radius "999px"
     :color "var(--phase-color,var(--signal))"
     :border "1px solid color-mix(in srgb,var(--phase-color,var(--signal)) 44%,transparent)"
     :background "color-mix(in srgb,var(--phase-color,var(--signal)) 13%,var(--base-950))"}]
   [".stream-rate"
    {:flex-shrink "0"
     :min-width "74px"
     :text-align "right"
     :white-space "nowrap"
     :font-family "var(--font-mono)"
     :font-size "11px"
     :font-variant-numeric "tabular-nums"
     :color "var(--text-300)"}]
   [".stream-bubble .thinking-toggle,.live-drawer-head .thinking-toggle.is-close"
    {:flex-shrink "0"
     :width "104px"
     :height "24px"
     :padding "0 8px"
     :display "inline-flex"
     :align-items "center"
     :justify-content "center"
     :white-space "nowrap"
     :background "var(--base-850)"
     :border "1px solid var(--base-700)"
     :color "var(--text-200)"
     :border-radius "5px"
     :font-family "var(--font-mono)"
     :font-size "10.5px"
     :cursor "pointer"}]
   [".stream-bubble .thinking-toggle:hover,.live-drawer-head .thinking-toggle.is-close:hover"
    {:border-color "var(--phase-color,var(--signal))"
     :color "var(--text-50)"}]
   ["#seon-app[data-thinking='open'] .stream-bubble .thinking-toggle.is-viewed-toggle"
    {:border-color "var(--phase-color,var(--signal))"
     :color "var(--phase-color,var(--signal))"}]
   ["#seon-app[data-thinking='closed'] .live-thinking-drawer" {:display "none"}]
   ["#seon-app[data-thinking='open'] .live-thinking-drawer" {:display "flex"}]
   [".live-thinking-drawer"
    {:flex-direction "column"
     :gap "8px"
     :padding "10px 12px"
     :background "var(--base-900)"
     :opacity "1"
     :border "1px solid var(--base-700)"
     :border-left "3px solid var(--phase-color,var(--signal))"
     :border-radius "10px"
     :box-shadow "0 12px 32px rgba(0,0,0,.48)"
     :contain "layout style"}]
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
    {:max-height "min(220px,32vh)"
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
   [".main-col:has(.stream-dock.active) .stage-scroll"
    {:padding-bottom "184px"}]
   ["#seon-app[data-thinking='open'] .main-col:has(.stream-dock.active) .stage-scroll"
    {:padding-bottom "420px"}]
   ["#seon-app[data-thinking='closed'][data-stream='collapsed'] .main-col:has(.stream-dock.active) .stage-scroll"
    {:padding-bottom "72px"}]
   [".main-col:has(.stream-dock.active) .ws-timeline"
    {:padding-bottom "96px"}]
   ["#seon-app[data-thinking='closed'][data-stream='collapsed'] .main-col:has(.stream-dock.active) .ws-timeline"
    {:padding-bottom "52px"}]
   [".main-col:has(.workspace-grid) .stream-dock.active"
    {:bottom "calc(100% + 78px)"}]])

(defn full-css
  "Assembles the complete live stylesheet from base `seon.web.css/app-css` plus compiled Clojure-data rules."
  {:malli/schema [:=> [:cat] :string]
   :cost/ticks 2000000
   :cost/bytes 4194304}
  []
  (str css/app-css "\n"
       (compile-css activity-rules) "\n"
       (compile-css stability-rules) "\n"
       "@keyframes seon-ring{0%{box-shadow:0 0 0 0 color-mix(in srgb,currentColor 55%,transparent)}70%{box-shadow:0 0 0 6px transparent}100%{box-shadow:0 0 0 0 transparent}}\n"
       "@media(prefers-reduced-motion:reduce){.stream-dot{animation:none}}\n"
       "@media(max-width:560px){.stream-bubble{gap:6px;padding:0 8px}.stream-phase-badge{min-width:64px;padding:2px 6px}.stream-rate{min-width:58px;font-size:10.5px}.stream-bubble .thinking-toggle{width:92px;font-size:10px}}"))
