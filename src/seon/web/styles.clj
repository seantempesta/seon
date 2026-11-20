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
  "Modern CSS scroll isolation, layout containment, floating stream-dock ergonomics, and native `<details>` layout stability."
  [[".stage-scroll,.ws-timeline,.tiles-scroll,.tree-scroll,.stream-dock,.live-reasoning,.live-prose"
    {:overscroll-behavior "contain"
     :overflow-anchor "auto"}]
   [".stage-scroll,.ws-timeline,.tree-scroll"
    {:scrollbar-gutter "stable"}]
   [".sidebar.open"
    {:contain "layout style"}]
   [".stream-dock.active"
    {:contain "layout style"
     :padding "2px"}]
   [".live-reasoning,.live-prose"
    {:contain "layout style paint"}]
   ["details::details-content"
    {:contain "layout"}]
   [".main-col:has(.stream-dock.active) .stage-scroll"
    {:padding-bottom "132px"}]
   ["#seon-app[data-thinking='open'] .main-col:has(.stream-dock.active) .stage-scroll"
    {:padding-bottom "300px"}]
   ["#seon-app[data-stream='collapsed'] .main-col:has(.stream-dock.active) .stage-scroll"
    {:padding-bottom "72px"}]
   [".main-col:has(.stream-dock.active) .ws-timeline"
    {:padding-bottom "84px"}]
   ["#seon-app[data-stream='collapsed'] .main-col:has(.stream-dock.active) .ws-timeline"
    {:padding-bottom "52px"}]
   [".main-col:has(.workspace-grid) .stream-dock.active"
    {:bottom "calc(100% + 78px)"}]
   ["#seon-app[data-thinking='closed'] .live-stream-card:not(.has-prose)"
    {:padding "7px 12px"
     :gap "0"}]
   ["#seon-app[data-stream='open'] .stream-collapsed-pill"
    {:display "none"}]
   ["#seon-app[data-stream='collapsed'] .stream-cards"
    {:display "none"}]
   ["#seon-app[data-stream='collapsed'] .stream-collapsed-pill"
    {:display "inline-flex"}]
   ["#seon-app[data-stream='collapsed'] .stream-dock.active"
    {:width "auto"
     :max-width "calc(100% - 32px)"
     :overflow "visible"}]
   [".stream-collapsed-pill"
    {:align-items "center"
     :gap "10px"
     :background "var(--base-900)"
     :backdrop-filter "blur(12px)"
     :border "1px solid rgba(240,180,41,.36)"
     :border-radius "999px"
     :padding "6px 12px"
     :box-shadow "0 8px 24px rgba(0,0,0,.38)"}]
   [".stream-agent-btn"
    {:background "none"
     :border "none"
     :padding "0"
     :color "var(--text-100)"
     :cursor "pointer"
     :font-family "var(--font-mono)"
     :font-size "11.5px"
     :font-weight "600"}]
   [".stream-agent-btn:hover"
    {:color "var(--signal)"}]
   [".stream-actions"
    {:display "inline-flex"
     :align-items "center"
     :gap "6px"
     :flex-shrink "0"}]
   [".stream-min-btn"
    {:background "transparent"
     :border "1px solid var(--base-700)"
     :color "var(--text-300)"
     :width "22px"
     :height "22px"
     :border-radius "4px"
     :font-family "var(--font-mono)"
     :font-size "12px"
     :line-height "1"
     :display "inline-flex"
     :align-items "center"
     :justify-content "center"
     :cursor "pointer"
     :padding "0"}]
   [".stream-min-btn:hover"
    {:border-color "var(--signal)"
     :color "var(--signal)"}]])

(defn full-css
  "Assembles the complete live stylesheet from base `seon.web.css/app-css` plus compiled Clojure-data rules."
  {:malli/schema [:=> [:cat] :string]
   :cost/ticks 2000000
   :cost/bytes 4194304}
  []
  (str css/app-css "\n" (compile-css activity-rules) "\n" (compile-css stability-rules)))
