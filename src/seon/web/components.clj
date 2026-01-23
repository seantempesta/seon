(ns seon.web.components
  "Shared UI component library for consistent Phosphor Terminal styling.

   This namespace provides reusable components that implement the Seon design
   system documented in docs/prds/namespace-ui/design-system.md. Use these
   components to maintain visual consistency across all pages.

   Design principles:
   - Warm blacks (base-850/900/950) for backgrounds
   - Cream text (text-50/200/400) not white
   - Monospace everywhere (JetBrains Mono)
   - Information density over whitespace
   - Tables over cards when appropriate
   - Status dots with pulse for active states

   Note: Functions in this namespace are private UI rendering helpers that
   return Hiccup data structures. They follow the same pattern as seon.web.html
   rather than the public API map-in/map-out pattern, since Hiccup cannot be
   schema-validated and these are internal rendering utilities.")

;;; ---------------------------------------------------------------------------
;;; Design System Constants
;;; ---------------------------------------------------------------------------

(def type-colors
  "Color classes for different log/message types.
   Used by log-line and related components."
  {"LAUNCH"   "text-log-launch"
   "MESSAGE"  "text-log-message"
   "TOOL"     "text-log-tool"
   "RESULT"   "text-log-result"
   "HOOK"     "text-log-hook"
   "COMPLETE" "text-log-done"
   "ERROR"    "text-log-error"})

(def status-styles
  "Status indicator styling by state.
   Each entry has :dot (background color), :text (text color), and :pulse? (animate)."
  {:running     {:dot "bg-signal"   :text "text-signal"   :pulse? true}
   :active      {:dot "bg-info"     :text "text-info"     :pulse? true}
   :done        {:dot "bg-success"  :text "text-success"  :pulse? false}
   :completed   {:dot "bg-success"  :text "text-success"  :pulse? false}
   :stuck       {:dot "bg-warning"  :text "text-warning"  :pulse? false}
   :error       {:dot "bg-error"    :text "text-error"    :pulse? false}
   :failed      {:dot "bg-error"    :text "text-error"    :pulse? false}
   :interrupted {:dot "bg-warning"  :text "text-warning"  :pulse? false}
   :terminated  {:dot "bg-text-500" :text "text-text-500" :pulse? false}
   :unknown     {:dot "bg-text-500" :text "text-text-500" :pulse? false}})

;;; ---------------------------------------------------------------------------
;;; Layout Components (private rendering helpers)
;;; ---------------------------------------------------------------------------

(defn page-header
  "Consistent page header with title and optional subtitle.
   Uses text-lg for title, text-xs for subtitle."
  [title subtitle]
  [:div {:class "mb-4"}
   [:h1 {:class "text-lg font-semibold tracking-tight"} title]
   (when subtitle
     [:p {:class "text-text-400 text-xs mt-0.5"} subtitle])])

(defn section-header
  "Uppercase section label used in dashboard cards.
   Uses text-xs, uppercase, with wider letter spacing."
  [text]
  [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-2"} text])

(defn card
  "Card container with standard Phosphor styling.
   Uses bg-base-850 background with p-3 padding and rounded corners.
   Pass children as a vector or multiple args."
  [& children]
  (into [:div {:class "bg-base-850 rounded p-3"}] children))

;;; ---------------------------------------------------------------------------
;;; Status Components (private rendering helpers)
;;; ---------------------------------------------------------------------------

(defn status-dot
  "Status indicator with 6px dot and text label.
   Displays a colored dot followed by status text. Active states pulse.

   status - Keyword: :running, :active, :done, :stuck, :error, :interrupted, :terminated, :unknown
   label  - Optional override for display label (defaults to status name)"
  ([status] (status-dot status nil))
  ([status label]
   (let [{:keys [dot text pulse?]} (get status-styles status
                                        {:dot "bg-text-500" :text "text-text-500" :pulse? false})
         display-label (or label (name (or status :unknown)))]
     [:span {:class "inline-flex items-center gap-1.5"}
      [:span {:class (str "w-1.5 h-1.5 rounded-full " dot
                          (when pulse? " animate-pulse"))}]
      [:span {:class (str "text-xs font-medium " text)} display-label]])))

;;; ---------------------------------------------------------------------------
;;; Table Components (private rendering helpers)
;;; ---------------------------------------------------------------------------

(defn table-header
  "Consistent table header cell styling.
   Uses text-xs, uppercase, with standard padding.

   text         - Header text
   right-align? - Align text to the right (for numeric columns)"
  ([text] (table-header text false))
  ([text right-align?]
   [:th {:class (str "text-left py-1.5 px-3 text-xs font-medium text-text-400 uppercase tracking-wider"
                     (when right-align? " text-right"))}
    text]))

(defn table-cell
  "Standard table cell with consistent styling.

   content      - Cell content
   opts         - Map with :right-align?, :mono? (default true), :muted?"
  ([content] (table-cell content {}))
  ([content {:keys [right-align? mono? muted?] :or {mono? true}}]
   [:td {:class (str "py-2 px-3 text-sm"
                     (when mono? " font-mono")
                     (when muted? " text-text-400")
                     (when right-align? " text-right"))}
    content]))

;;; ---------------------------------------------------------------------------
;;; Log Components (private rendering helpers)
;;; ---------------------------------------------------------------------------

(def ^:private preview-length
  "Max characters to show before truncating log details."
  120)

(defn log-line
  "Single log line with timestamp, type, and content.
   Type determines color using type-colors map.

   Long content (>120 chars) uses native <details> for expand/collapse.

   entry - Map with :timestamp, :type, :details (or :raw for unparsed lines)"
  [{:keys [timestamp type details raw]}]
  (let [type-class (or (get type-colors type) "text-text-400")
        ;; Add font-semibold for important types
        emphasis? (contains? #{"LAUNCH" "COMPLETE" "ERROR"} type)
        full-type-class (str type-class (when emphasis? " font-semibold"))
        long? (and details (> (count details) preview-length))]
    [:div {:class "font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 last:border-0 hover:bg-base-800"}
     (if timestamp
       [:div {:class "flex gap-2"}
        [:span {:class "text-text-400 shrink-0"} timestamp]
        [:span {:class (str "shrink-0 w-16 " full-type-class)} type]
        (if long?
          ;; Native <details> with data-preserve-attr for SSE morph state preservation
          [:details {:class "text-text-50 inline"
                     :data-preserve-attr "open"}
           [:summary {:class "cursor-pointer list-none"}
            (subs details 0 preview-length)
            [:span {:class "text-info ml-1"} (str "+" (- (count details) preview-length) " more")]]
           [:div {:class "break-all mt-1 pl-2 border-l-2 border-base-700"}
            details]]
          ;; Short content
          [:span {:class "text-text-50 break-all"} details])]
       ;; Unparsed raw line
       [:span {:class "text-text-400"} raw])]))

(defn log-container
  "Container for log lines with terminal styling.
   Uses flex-col-reverse for auto-scroll to bottom behavior.

   lines      - Sequence of log entry maps
   max-height - CSS max-height value (default '70vh')"
  ([lines] (log-container lines "70vh"))
  ([lines max-height]
   [:div {:class "bg-base-900 rounded overflow-hidden"}
    [:div {:class "p-3 overflow-y-auto flex flex-col-reverse"
           :style (str "max-height: " max-height)}
     [:div
      (for [line lines]
        (log-line line))]]]))

;;; ---------------------------------------------------------------------------
;;; Empty States (private rendering helpers)
;;; ---------------------------------------------------------------------------

(defn empty-state
  "Centered empty state message for tables and lists.

   message  - Primary message text
   subtitle - Optional secondary text"
  ([message] (empty-state message nil))
  ([message subtitle]
   [:div {:class "py-8 px-4 text-center text-text-500"}
    [:p {:class "text-sm font-medium"} message]
    (when subtitle
      [:p {:class "text-xs mt-2 text-text-400"} subtitle])]))

;;; ---------------------------------------------------------------------------
;;; Button Components (private rendering helpers)
;;; ---------------------------------------------------------------------------

(defn filter-button
  "Filter/toggle button with active/inactive states.

   label    - Button text
   active?  - Whether this button is currently active
   on-click - Datastar click handler string"
  [label active? on-click]
  [:button {:class (str "px-2 py-1 text-xs font-mono rounded border transition-colors "
                        (if active?
                          "text-text-200 border-base-600 bg-base-800"
                          "text-text-500 border-base-700 hover:border-base-600"))
            :data-on-click on-click}
   label])

(defn action-button
  "Action button with consistent styling.

   label   - Button text
   on-click - Datastar click handler string
   variant - :primary (amber) or :secondary (gray, default)"
  ([label on-click] (action-button label on-click :secondary))
  ([label on-click variant]
   (let [class (case variant
                 :primary "px-3 py-1.5 text-sm font-medium rounded bg-signal text-base-950 hover:bg-warning transition-colors"
                 :secondary "px-3 py-1.5 text-sm font-medium rounded bg-base-800 text-text-200 hover:bg-base-700 transition-colors")]
     [:button {:class class
               :data-on-click on-click}
      label])))
