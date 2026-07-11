(ns seon.ui.components
  "Shared UI component library for Phosphor Terminal styling. CLJC port
   of the JVM `seon.web.components` — zero JVM interop, runs in pod-side
   CLJS today AND in JVM seon when the cljc-migration-plan converges.

   Functions return hiccup data structures — they don't render to
   strings. The render boundary belongs to `seon.ui.html/->string`
   (pod-side) or `seon.web.html` / chassis (JVM-side); either consumer
   works against the same hiccup output.

   Design principles (same as JVM sibling):
   - Warm blacks (`bg-base-{850,900,950}`) for backgrounds
   - Cream text (`text-{50,200,400}`) not white
   - Monospace everywhere (JetBrains Mono fallback)
   - Information density over whitespace (`p-3` not `p-6`)
   - Tables over cards when appropriate
   - Status dots with pulse for active states

   The full design system is documented in
   `docs/prds/namespace-ui/design-system.md` (JVM repo). When the JVM
   migration plan reaches Stage 3 this file MERGES with the JVM
   `seon.web.components.clj` into one `.cljc` — most of the body is
   already byte-identical because the originals were pure hiccup
   factories."
  (:require [seon.schema :as schema]))

;; ============================================================
;; Design system constants
;; ============================================================

(def type-colors
  "Color classes for different log/message types. Used by `log-line`
   and related components."
  {"LAUNCH"   "text-log-launch"
   "MESSAGE"  "text-log-message"
   "TOOL"     "text-log-tool"
   "RESULT"   "text-log-result"
   "HOOK"     "text-log-hook"
   "COMPLETE" "text-log-done"
   "ERROR"    "text-log-error"})

(def status-styles
  "Status indicator styling by state. Each entry: `:dot` (bg color),
   `:text` (text color), `:pulse?` (animate)."
  {:running     {:dot "bg-signal"   :text "text-signal"   :pulse? true}
   :active      {:dot "bg-info"     :text "text-info"     :pulse? true}
   :paused      {:dot "bg-warning"  :text "text-warning"  :pulse? false}
   :idle        {:dot "bg-text-500" :text "text-text-500" :pulse? false}
   :waiting     {:dot "bg-warning"  :text "text-warning"  :pulse? false}
   :done        {:dot "bg-success"  :text "text-success"  :pulse? false}
   :completed   {:dot "bg-success"  :text "text-success"  :pulse? false}
   :stuck       {:dot "bg-warning"  :text "text-warning"  :pulse? false}
   :error       {:dot "bg-error"    :text "text-error"    :pulse? false}
   :failed      {:dot "bg-error"    :text "text-error"    :pulse? false}
   :interrupted {:dot "bg-warning"  :text "text-warning"  :pulse? false}
   :terminated  {:dot "bg-text-500" :text "text-text-500" :pulse? false}
   :unknown     {:dot "bg-text-500" :text "text-text-500" :pulse? false}})

;; ============================================================
;; Layout
;; ============================================================

(defn page-header
  "Consistent page header — title plus optional subtitle.

   Title is `text-lg`, subtitle `text-xs`."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [title subtitle]
  [:div {:class "mb-4"}
   [:h1 {:class "text-lg font-semibold tracking-tight"} title]
   (when subtitle
     [:p {:class "text-text-400 text-xs mt-0.5"} subtitle])])

(defn section-header
  "Uppercase section label used in dashboard cards.

   `text-xs`, uppercase, wider letter-spacing."
  {:malli/schema [:=> [:cat :any] :any]}
  [text]
  [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-2"}
   text])

(defn card
  "Card container — `bg-base-850` with `p-3` padding, rounded corners."
  {:malli/schema [:=> [:cat [:* :any]] :any]}
  [& children]
  (into [:div {:class "bg-base-850 rounded p-3"}] children))

;; ============================================================
;; Status indicators
;; ============================================================

(defn status-dot
  "Status indicator — 6px colored dot + text label.

   Active states
   pulse. Falls back to `:unknown` styling for unrecognized statuses.

   status — keyword from `status-styles`
   label  — optional display override (defaults to status name)"
  {:malli/schema [:function
                  [:=> [:cat :any] :any]
                  [:=> [:cat :any :any] :any]]}
  ([status] (status-dot status nil))
  ([status label]
   (let [{:keys [dot text pulse?]}
         (get status-styles status (:unknown status-styles))
         display-label (or label (name (or status :unknown)))]
     [:span {:class "inline-flex items-center gap-1.5"}
      [:span {:class (str "w-1.5 h-1.5 rounded-full " dot
                          (when pulse? " animate-pulse"))}]
      [:span {:class (str "text-xs font-medium " text)} display-label]])))

;; ============================================================
;; Tables
;; ============================================================

(defn table-header
  "Table header cell — `text-xs`, uppercase, standard padding.

   text         — header text
   right-align? — align text right (for numeric columns)"
  {:malli/schema [:function
                  [:=> [:cat :any] :any]
                  [:=> [:cat :any :any] :any]]}
  ([text] (table-header text false))
  ([text right-align?]
   [:th {:class (str "text-left py-1.5 px-3 text-xs font-medium "
                     "text-text-400 uppercase tracking-wider"
                     (when right-align? " text-right"))}
    text]))

(defn table-cell
  "Table data cell — `text-sm`, monospace by default.

   content — cell content
   opts    — `{:right-align? :mono? :muted?}`. `:mono?` defaults true."
  {:malli/schema [:function
                  [:=> [:cat :any] :any]
                  [:=> [:cat :any :any] :any]]}
  ([content] (table-cell content {}))
  ([content {:keys [right-align? mono? muted?] :or {mono? true}}]
   [:td {:class (str "py-2 px-3 text-sm"
                     (when mono? " font-mono")
                     (when muted? " text-text-400")
                     (when right-align? " text-right"))}
    content]))

;; ============================================================
;; Log lines
;; ============================================================

(def ^:private preview-length
  "Max characters to show before truncating log details."
  120)

;; A single log entry — parsed (`::timestamp`/`::type`/`::details`) or
;; unparsed (`::raw`). Open map, every field optional (the two variants
;; are disjoint); primitive leaf types inlined (not shared shapes).
(schema/register! :seon.ui.components/log-entry
  [:map
   [:seon.ui.components/timestamp {:optional true} :string]
   [:seon.ui.components/type      {:optional true} :string]
   [:seon.ui.components/details   {:optional true} :string]
   [:seon.ui.components/raw       {:optional true} :string]])

(defn log-line
  "Single log line — timestamp, type, content.

   Type drives the color
   via `type-colors`. Long content (>120 chars) uses native
   `<details>` for expand/collapse; the `data-preserve-attr=\"open\"`
   tells Datastar to preserve the open-state across SSE morphs.

   entry — `{::timestamp ::type ::details}` for parsed lines, or
           `{::raw \"...\"}` for unparsed."
  {:malli/schema [:=> [:cat :seon.ui.components/log-entry] :any]}
  [{:seon.ui.components/keys [timestamp type details raw]}]
  (let [type-class      (or (get type-colors type) "text-text-400")
        emphasis?       (contains? #{"LAUNCH" "COMPLETE" "ERROR"} type)
        full-type-class (str type-class (when emphasis? " font-semibold"))
        long?           (and details (> (count details) preview-length))]
    [:div {:class (str "font-mono text-xs leading-tight py-0.5 "
                       "border-b border-base-700/50 last:border-0 "
                       "hover:bg-base-800")}
     (if timestamp
       [:div {:class "flex gap-2"}
        [:span {:class "text-text-400 shrink-0"} timestamp]
        [:span {:class (str "shrink-0 w-16 " full-type-class)} type]
        (if long?
          [:details {:class "text-text-50 inline"
                     :data-preserve-attr "open"}
           [:summary {:class "cursor-pointer list-none"}
            (subs details 0 preview-length)
            [:span {:class "text-info ml-1"}
             (str "+" (- (count details) preview-length) " more")]]
           [:div {:class "break-all mt-1 pl-2 border-l-2 border-base-700"}
            details]]
          [:span {:class "text-text-50 break-all"} details])]
       [:span {:class "text-text-400"} raw])]))

(defn log-container
  "Container for log lines — terminal styling, auto-scroll to bottom.

   Uses flex-col-reverse for auto-scroll-to-bottom.

   lines      — seq of log entry maps
   max-height — CSS max-height (default \"70vh\")"
  {:malli/schema [:function
                  [:=> [:cat :any] :any]
                  [:=> [:cat :any :any] :any]]}
  ([lines] (log-container lines "70vh"))
  ([lines max-height]
   [:div {:class "bg-base-900 rounded overflow-hidden"}
    [:div {:class "p-3 overflow-y-auto flex flex-col-reverse"
           :style (str "max-height: " max-height)}
     [:div
      (for [line lines]
        (log-line line))]]]))

;; ============================================================
;; Empty states
;; ============================================================

(defn empty-state
  "Centered empty-state message for tables and lists.

   message  — primary text
   subtitle — optional secondary text"
  {:malli/schema [:function
                  [:=> [:cat :any] :any]
                  [:=> [:cat :any :any] :any]]}
  ([message] (empty-state message nil))
  ([message subtitle]
   [:div {:class "py-8 px-4 text-center text-text-500"}
    [:p {:class "text-sm font-medium"} message]
    (when subtitle
      [:p {:class "text-xs mt-2 text-text-400"} subtitle])]))

;; ============================================================
;; Buttons
;; ============================================================

(defn filter-button
  "Filter / toggle button — active vs inactive states.

   label    — button text
   active?  — currently active?
   on-click — Datastar `data-on-click` handler string"
  {:malli/schema [:=> [:cat :any :any :any] :any]}
  [label active? on-click]
  [:button {:class (str "px-2 py-1 text-xs font-mono rounded border "
                        "transition-colors "
                        (if active?
                          "text-text-200 border-base-600 bg-base-800"
                          "text-text-500 border-base-700 hover:border-base-600"))
            :data-on-click on-click}
   label])

(defn action-button
  "Action button — `:primary` (amber) or `:secondary` (gray, default).

   label    — button text
   on-click — Datastar `data-on-click` handler string
   variant  — `:primary` | `:secondary`"
  {:malli/schema [:function
                  [:=> [:cat :any :any] :any]
                  [:=> [:cat :any :any :any] :any]]}
  ([label on-click] (action-button label on-click :secondary))
  ([label on-click variant]
   (let [class (case variant
                 :primary
                 (str "px-3 py-1.5 text-sm font-medium rounded "
                      "bg-signal text-base-950 hover:bg-warning "
                      "transition-colors")
                 :secondary
                 (str "px-3 py-1.5 text-sm font-medium rounded "
                      "bg-base-800 text-text-200 hover:bg-base-700 "
                      "transition-colors"))]
     [:button {:class class
               :data-on-click on-click}
      label])))
