(ns seon.ai.agent.views
  "View renderers for agent data types.

   Provides multimethod implementations for rendering agent-related data
   in multiple formats: :html (Hiccup), :ai (LLM-optimized strings),
   :human (readable strings), and :raw (pr-str).

   View Types Defined:
   - :seon.ai.agent/summary      - Agent row for list views
   - :seon.ai.agent/detail       - Full agent detail with log lines
   - :agent.log/launch           - Purple, shows namespace + port
   - :agent.log/message          - Blue, shows assistant text
   - :agent.log/tool             - Amber, shows tool name + params
   - :agent.log/result           - Green, shows result preview
   - :agent.log/hook             - Cyan, shows hook output
   - :agent.log/complete         - Green bold, shows stats
   - :agent.log/error            - Red, shows error

   Tool-Specific Renderers:
   Each tool type has a specialized renderer with 3-tier display:
   - Inline: Succinct one-liner summary
   - Hover: More detail via title attribute
   - Expanded: Full rich UI via <details> (native HTML expand/collapse)

   Usage:
     (require '[seon.ai.agent.views])
     (require '[seon.ns.view :as view])

     (def agent-data {:seon.ai.agent/session-id \"e077\"
                      :seon.ai.agent/namespace \"seon.web.agents\"
                      :seon.ai.agent/agent-status :running})

     ;; Using map-in API
     (view/render-value {::view/value (view/typed {::view/view-type :seon.ai.agent/summary
                                                   ::view/value agent-data})
                         ::view/format :html})

     ;; Using convenience wrappers for view implementations
     (view/render (view/typed {::view/view-type :seon.ai.agent/summary
                               ::view/value agent-data})
                  :html)

   Note: This namespace registers render* multimethod implementations.
   View implementations use the internal render*/typed helpers for efficiency."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.ns.view :as view]
            [seon.web.components :as ui])
  (:import [java.time Instant ZoneId LocalDate ZonedDateTime]
           [java.time.format TextStyle DateTimeFormatter]
           [java.time.temporal ChronoUnit]
           [java.util Locale]))

;;; ---------------------------------------------------------------------------
;;; Constants
;;; ---------------------------------------------------------------------------

(def ^:private preview-length
  "Max characters to show before truncating log content."
  120)

(def ^:private code-preview-lines
  "Max lines to show in code preview before truncating."
  8)

(def ^:private diff-context-lines
  "Lines of context to show around diff changes."
  2)

(def ^:private hover-preview-lines
  "Max lines to show in hover card code preview."
  6)

(def ^:private max-inline-lines
  "Max lines to show inline in log content before truncating."
  4)

;;; ---------------------------------------------------------------------------
;;; Helper Functions
;;; ---------------------------------------------------------------------------

(defn- truncate
  "Truncate string to max-len with ellipsis if needed."
  [s max-len]
  (if (and s (> (count s) max-len))
    (str (subs s 0 (- max-len 3)) "...")
    s))

(defn- truncate-lines
  "Truncate content to max lines for inline display.
   Returns {:truncated? bool :preview string :hidden-lines int}."
  [content max-lines]
  (if (str/blank? content)
    {:truncated? false :preview content :hidden-lines 0}
    (let [lines (str/split-lines content)
          total (count lines)]
      (if (> total max-lines)
        {:truncated? true
         :preview (str/join "\n" (take max-lines lines))
         :hidden-lines (- total max-lines)}
        {:truncated? false
         :preview content
         :hidden-lines 0}))))

(defn- format-local-time
  "Format ISO timestamp as compact local time for display.
   Today: '14:23'
   This week: 'Mon 14:23'
   Older: 'Jan 15 14:23'
   Returns nil for nil/invalid input."
  [iso-timestamp]
  (when (and iso-timestamp (string? iso-timestamp) (seq iso-timestamp))
    (try
      (let [instant (Instant/parse iso-timestamp)
            zone (ZoneId/systemDefault)
            zdt (ZonedDateTime/ofInstant instant zone)
            today (LocalDate/now zone)
            ts-date (.toLocalDate zdt)
            days-ago (.between ChronoUnit/DAYS ts-date today)
            time-fmt (DateTimeFormatter/ofPattern "HH:mm")]
        (cond
          ;; Today: just time
          (= ts-date today)
          (.format zdt time-fmt)

          ;; Within last 7 days: day name + time
          (and (>= days-ago 0) (<= days-ago 6))
          (str (.getDisplayName (.getDayOfWeek zdt) TextStyle/SHORT (Locale/getDefault))
               " "
               (.format zdt time-fmt))

          ;; Older: month day + time
          :else
          (let [month-day-fmt (DateTimeFormatter/ofPattern "MMM d HH:mm")]
            (.format zdt month-day-fmt))))
      (catch Exception _
        ;; Return original on parse failure
        iso-timestamp))))

(defn parse-tool-input
  "Parse tool input EDN string into a map.
   Handles both raw EDN and quoted strings (from log format).
   Returns nil on parse failure.
   Public so agents.clj can use it."
  [input-str]
  (when (and input-str (string? input-str))
    (try
      ;; Log format wraps EDN in quotes: "{:file_path ...}"
      ;; Strip outer quotes first if present, then parse
      (let [s (str/trim input-str)
            stripped (if (and (str/starts-with? s "\"")
                              (str/ends-with? s "\""))
                       (subs s 1 (dec (count s)))
                       s)]
        (when (str/starts-with? stripped "{")
          (edn/read-string stripped)))
      (catch Exception _ nil))))

(defn- basename
  "Extract filename from path."
  [path]
  (when path
    (last (str/split path #"/"))))

(defn- count-lines
  "Count lines in a string."
  [s]
  (if (str/blank? s)
    0
    (count (str/split-lines s))))

(defn- compute-diff-stats
  "Compute +/- line counts for an edit.
   Returns {:added n :removed n}."
  [old-str new-str]
  (let [old-lines (if (str/blank? old-str) 0 (count-lines old-str))
        new-lines (if (str/blank? new-str) 0 (count-lines new-str))
        diff (- new-lines old-lines)]
    {:added (if (pos? diff) diff 0)
     :removed (if (neg? diff) (- diff) 0)
     :old-lines old-lines
     :new-lines new-lines}))

(defn- format-diff-stats
  "Format diff stats as +N/-M string."
  [{:keys [added removed old-lines new-lines]}]
  (cond
    (and (zero? added) (zero? removed))
    (str old-lines " lines")

    (zero? removed)
    (str "+" added)

    (zero? added)
    (str "-" removed)

    :else
    (str "+" added "/-" removed)))

(defn- format-cost
  "Format cost as $X.XX"
  [cost]
  (when cost
    (format "$%.2f" (double cost))))

(defn- format-duration
  "Format duration-ms as human readable."
  [duration-ms]
  (when duration-ms
    (let [seconds (/ duration-ms 1000)]
      (if (> seconds 60)
        (format "%.1fm" (/ seconds 60.0))
        (format "%ds" (int seconds))))))

(defn- typed-value
  "Helper to create typed value for nested rendering.
   Wraps view/typed with positional args for internal use."
  [view-type value]
  (view/typed {::view/view-type view-type ::view/value value}))

;;; ---------------------------------------------------------------------------
;;; Hover Card Components
;;; ---------------------------------------------------------------------------

(defn- hover-card
  "Hover card that appears on log line hover.
   Uses Tailwind group/group-hover pattern with fixed positioning.
   Position is set by JavaScript in html.clj to avoid overflow clipping.

   content - Hiccup content for the hover card body"
  [content]
  [:div {:class (str "hover-card hidden group-hover:block fixed z-50 "
                     "bg-base-850 border border-base-700 rounded shadow-lg "
                     "p-2 max-w-xl min-w-64 "
                     "text-xs font-mono")}
   content])

(defn- hover-line
  "A label:value line for hover cards."
  ([label value] (hover-line label value nil))
  ([label value value-class]
   [:div {:class "flex gap-2"}
    [:span {:class "text-text-400 shrink-0"} (str label ":")]
    [:span {:class (or value-class "text-text-200 break-all")} value]]))

(defn- hover-code-block
  "Code block for hover cards with optional label and language for syntax highlighting.
   language - one of \"clojure\", \"bash\", \"diff\" or nil for plain text"
  ([code] (hover-code-block nil code nil))
  ([label code] (hover-code-block label code nil))
  ([label code language]
   (let [lines (str/split-lines (or code ""))
         truncated? (> (count lines) hover-preview-lines)
         display-lines (if truncated? (take hover-preview-lines lines) lines)
         lang-class (when language (str "language-" language))]
     [:div {:class "mt-1"}
      (when label
        [:div {:class "text-text-400 text-2xs mb-0.5"} label])
      [:pre {:class "bg-base-900 rounded p-1.5 overflow-x-auto text-2xs"}
       [:code {:class lang-class}
        (str/join "\n" display-lines)
        (when truncated?
          [:span {:class "text-text-500 block mt-1"} (str "... " (- (count lines) hover-preview-lines) " more lines")])]]])))

(defn- hover-diff-block
  "Diff block for hover cards showing old/new strings."
  [old-str new-str]
  [:div {:class "mt-1 space-y-1"}
   (when (not (str/blank? old-str))
     (let [lines (str/split-lines old-str)
           truncated? (> (count lines) hover-preview-lines)
           display (if truncated?
                     (str (str/join "\n" (take hover-preview-lines lines)) "\n...")
                     old-str)]
       [:pre {:class "bg-error/10 rounded p-1.5 overflow-x-auto text-2xs text-error/80"}
        [:code {:class "language-clojure"}
         [:span {:class "text-error font-medium"} "- "]
         display]]))
   (when (not (str/blank? new-str))
     (let [lines (str/split-lines new-str)
           truncated? (> (count lines) hover-preview-lines)
           display (if truncated?
                     (str (str/join "\n" (take hover-preview-lines lines)) "\n...")
                     new-str)]
       [:pre {:class "bg-success/10 rounded p-1.5 overflow-x-auto text-2xs text-success/80"}
        [:code {:class "language-clojure"}
         [:span {:class "text-success font-medium"} "+ "]
         display]]))])

;;; ---------------------------------------------------------------------------
;;; Agent Summary View - :seon.ai.agent/summary
;;; ---------------------------------------------------------------------------

(defmethod view/render* [:html :seon.ai.agent/summary]
  [agent _format]
  (let [{:seon.ai.agent/keys [session-id namespace agent-status nrepl-port cost-usd]} agent
        detail-href (view/detail-url {::view/view-type :seon.ai.agent/summary
                                      ::view/id session-id})]
    [:tr {:class "hover:bg-base-800 cursor-pointer transition-colors"
          :data-on:click (str "window.location.href='" detail-href "'")}
     ;; Session ID
     [:td {:class "py-2 px-3 font-mono text-xs text-signal"}
      session-id]
     ;; Namespace
     [:td {:class "py-2 px-3 font-mono text-xs text-text-50"}
      namespace]
     ;; Status with dot
     [:td {:class "py-2 px-3"}
      (ui/status-dot agent-status)]
     ;; nREPL port (optional)
     [:td {:class "py-2 px-3 font-mono text-xs text-text-400 text-right"}
      (when nrepl-port nrepl-port)]
     ;; Cost (optional)
     [:td {:class "py-2 px-3 font-mono text-xs text-text-200 text-right"}
      (format-cost cost-usd)]]))

(defmethod view/render* [:ai :seon.ai.agent/summary]
  [agent _format]
  (let [{:seon.ai.agent/keys [session-id namespace agent-status]} agent]
    (str session-id " " namespace " [" (name agent-status) "]")))

(defmethod view/render* [:human :seon.ai.agent/summary]
  [agent _format]
  (let [{:seon.ai.agent/keys [session-id namespace agent-status cost-usd]} agent]
    (str session-id ": " namespace
         " (" (name agent-status) ")"
         (when cost-usd (str " " (format-cost cost-usd))))))

(defmethod view/render* [:raw :seon.ai.agent/summary]
  [agent _format]
  (pr-str agent))

;;; ---------------------------------------------------------------------------
;;; Log Line Views - :agent.log/*
;;; ---------------------------------------------------------------------------

;; LAUNCH - Purple, shows namespace + port
(defmethod view/render* [:html :agent.log/launch]
  [entry _format]
  (let [{:keys [timestamp namespace port]} entry]
    [:div {:class "log-line group relative font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 hover:bg-base-800"}
     [:div {:class "flex gap-2"}
      [:span {:class "text-text-400 shrink-0" :title timestamp} (format-local-time timestamp)]
      [:span {:class "text-log-launch font-semibold shrink-0 w-16"} "LAUNCH"]
      [:span {:class "text-text-50"}
       [:span {:class "text-log-launch"} namespace]
       (when port
         [:span {:class "text-text-400 ml-2"} (str "port=" port)])]]
     ;; Hover card with full details
     (hover-card
      [:div {:class "space-y-1"}
       (hover-line "namespace" namespace "text-log-launch")
       (when port (hover-line "nREPL port" (str port)))
       (hover-line "timestamp" timestamp "text-text-400")])]))

(defmethod view/render* [:ai :agent.log/launch]
  [entry _format]
  (let [{:keys [timestamp namespace port]} entry]
    (str timestamp " LAUNCH " namespace (when port (str " port=" port)))))

;; MESSAGE - Blue, shows assistant text (truncated to max-inline-lines)
(defmethod view/render* [:html :agent.log/message]
  [entry _format]
  (let [{:keys [timestamp role content]} entry
        {:keys [truncated? preview hidden-lines]} (truncate-lines content max-inline-lines)
        char-count (count (or content ""))]
    [:div {:class "log-line group relative font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 hover:bg-base-800"}
     [:div {:class "flex gap-2 items-start"}
      [:span {:class "text-text-400 shrink-0" :title timestamp} (format-local-time timestamp)]
      [:span {:class "text-log-message shrink-0 w-16"} "MESSAGE"]
      [:span {:class "text-text-400 shrink-0 w-16"} role]
      [:div {:class "text-text-50 min-w-0 flex-1"}
       [:pre {:class "whitespace-pre-wrap break-words"} preview]
       (when truncated?
         [:span {:class "text-text-500"} (str "... " hidden-lines " more lines")])]]
     ;; Hover card with more message text
     (hover-card
      [:div {:class "space-y-1"}
       (hover-line "role" role "text-log-message")
       (hover-line "length" (str char-count " chars"))
       [:div {:class "mt-2 text-text-200 break-words max-h-32 overflow-y-auto"}
        (if (> char-count 500)
          (str (subs content 0 500) "...")
          content)]])]))

(defmethod view/render* [:ai :agent.log/message]
  [entry _format]
  (let [{:keys [timestamp role content]} entry]
    (str timestamp " MESSAGE " role " \"" (truncate content 200) "\"")))

;;; ---------------------------------------------------------------------------
;;; Tool-Specific Renderers
;;; ---------------------------------------------------------------------------
;;
;; Each tool type has a specialized renderer with 3-tier display:
;; 1. Inline summary - shown in the log stream
;; 2. Hover detail - title attribute with more info
;; 3. Expanded view - full UI via <details> element

(defmulti render-tool-html
  "Render tool call for HTML display.
   Dispatches on tool-name string.
   Returns hiccup for the tool content (everything after timestamp and TOOL label)."
  (fn [tool-name _parsed-input _raw-input] tool-name))

(defmulti render-tool-hover
  "Render hover card content for a tool call.
   Dispatches on tool-name string.
   Returns hiccup wrapped in hover-card, or nil for no hover."
  (fn [tool-name _parsed-input _raw-input] tool-name))

;;; ---------------------------------------------------------------------------
;;; New Unified Tool Rendering Multimethods
;;; ---------------------------------------------------------------------------
;;
;; These three multimethods provide the new collapsed/expanded UI:
;; - render-tool-header: Concise one-liner (icon + name + file/key info)
;; - render-tool-preview: Clipped preview (first 3 lines, shown in summary)
;; - render-tool-input: Full input (shown when expanded)

(def ^:private tool-preview-lines
  "Max lines to show in collapsed preview."
  3)

(defmulti render-tool-header
  "Render tool header for collapsed view.
   Returns hiccup span with icon, tool name, and key info (filename, etc).
   Should be a concise one-liner."
  (fn [tool-name _parsed-input] tool-name))

(defmulti render-tool-preview
  "Render tool input preview for collapsed view.
   Returns hiccup with first 3 lines of input (diff, code, command).
   Returns nil if no preview is appropriate for this tool."
  (fn [tool-name _parsed-input] tool-name))

(defmulti render-tool-input
  "Render full tool input for expanded view.
   Returns hiccup with complete input content."
  (fn [tool-name _parsed-input] tool-name))

(defmethod render-tool-html :default
  [tool-name parsed-input raw-input]
  ;; Fallback: show tool name and raw input (truncated to max-inline-lines)
  (let [display-input (or raw-input (pr-str parsed-input))
        {:keys [truncated? preview hidden-lines]} (truncate-lines display-input max-inline-lines)]
    [:span {:class "flex gap-2 flex-1 min-w-0 items-start"}
     [:span {:class "text-log-tool font-medium shrink-0"} tool-name]
     (when display-input
       [:div {:class "text-text-200 min-w-0 flex-1"}
        [:pre {:class "whitespace-pre-wrap break-words"} preview]
        (when truncated?
          [:span {:class "text-text-500"} (str "... " hidden-lines " more lines")])])]))

;; Default implementations for new unified rendering multimethods

(defmethod render-tool-header :default
  [tool-name _parsed-input]
  [:span {:class "flex items-center gap-2"}
   [:span {:class "text-log-tool"} "●"]
   [:span {:class "text-text-300"} tool-name]])

(defmethod render-tool-preview :default
  [_tool-name _parsed-input]
  ;; Most tools don't need a preview in collapsed state
  nil)

(defmethod render-tool-input :default
  [tool-name parsed-input]
  ;; Fallback: show raw input
  (when parsed-input
    [:pre {:class "bg-base-900 p-2 rounded text-2xs font-mono overflow-x-auto"}
     [:code (pr-str parsed-input)]]))

(defmethod render-tool-hover :default
  [tool-name parsed-input raw-input]
  ;; Fallback: show tool name and full input
  (let [display-input (or raw-input (pr-str parsed-input))]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "tool" tool-name "text-log-tool")
      (when display-input
        (hover-code-block "input" display-input))])))

(defn- render-diff-lines
  "Render diff lines with - prefix for old, + prefix for new."
  [old-lines new-lines]
  [:code
   (when (seq old-lines)
     [:span {:class "text-error/80 block"}
      (str/join "\n" (map #(str "- " %) old-lines))])
   (when (seq new-lines)
     [:span {:class "text-success/80 block"}
      (str/join "\n" (map #(str "+ " %) new-lines))])])

(def ^:private diff-preview-lines
  "Max lines to show in diff preview before truncating."
  3)

;; Edit tool - show file path with inline diff preview, expandable for long diffs
(defmethod render-tool-html "Edit"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [file_path old_string new_string replace_all]} parsed-input
        file-name (basename file_path)
        stats (compute-diff-stats old_string new_string)
        stats-str (format-diff-stats stats)
        ;; Format full diff content upfront
        old-lines (when (not (str/blank? old_string)) (str/split-lines old_string))
        new-lines (when (not (str/blank? new_string)) (str/split-lines new_string))
        ;; Check if we need truncation (> 8 total lines)
        total-lines (+ (count old-lines) (count new-lines))
        needs-truncation? (> total-lines 8)
        ;; Preview lines for truncated view
        old-preview (take diff-preview-lines old-lines)
        new-preview (take diff-preview-lines new-lines)
        hidden-lines (- total-lines (+ (count old-preview) (count new-preview)))]
    [:span {:class "flex-1 min-w-0"}
     ;; Header line with icon, tool name, file, and stats
     [:span {:class "flex items-center gap-2"}
      [:span {:class "text-info"} "✎"]
      [:span {:class "text-text-300"} "Edit"]
      [:code {:class "text-text-200 text-2xs"} file-name]
      [:span {:class "text-text-500 text-2xs"} (str "(" stats-str ")")]
      (when replace_all
        [:span {:class "text-warning text-2xs"} "replace-all"])]
     ;; Diff display
     (if needs-truncation?
       ;; Truncated: use details/summary where summary IS the preview
       ;; When closed: shows preview + "N more lines"
       ;; When open: shows full diff (replaces preview)
       [:details {:class "mt-1 edit-diff" :data-preserve-attr "open"}
        [:summary {:class "cursor-pointer list-none"}
         [:pre {:class "text-2xs bg-base-900 p-2 rounded overflow-x-auto font-mono inline-block w-full"}
          (render-diff-lines old-preview new-preview)
          [:span {:class "text-info text-2xs block mt-1"}
           (str "... " hidden-lines " more lines ▸")]]]
        ;; Full diff shown when expanded (CSS hides the summary content)
        [:pre {:class "text-2xs bg-base-900 p-2 rounded overflow-x-auto font-mono"}
         (render-diff-lines old-lines new-lines)]]
       ;; Short enough: show full diff inline
       [:pre {:class "mt-1 text-2xs bg-base-900 p-2 rounded overflow-x-auto font-mono"}
        (render-diff-lines old-lines new-lines)])]))

(defmethod render-tool-hover "Edit"
  [_tool-name _parsed-input _raw-input]
  ;; No hover card for Edit - diff is shown inline with expand
  nil)

;; Read tool - show file path and line range
(defmethod render-tool-html "Read"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [file_path offset limit]} parsed-input
        file-name (basename file_path)
        range-str (cond
                    (and offset limit) (str ":" offset "-" (+ offset limit))
                    offset (str ":" offset "+")
                    limit (str ":1-" limit)
                    :else "")]
    [:span {:class "flex gap-2 flex-1 min-w-0"}
     [:span {:class "text-log-tool font-medium shrink-0"} "Read"]
     [:span {:class "text-text-50"
             :title file_path}
      (str file-name range-str)]]))

(defmethod render-tool-hover "Read"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [file_path offset limit]} parsed-input]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "path" file_path "text-text-50")
      (when offset (hover-line "offset" (str offset)))
      (when limit (hover-line "limit" (str limit " lines")))])))

;; Grep tool - show pattern and path
(defmethod render-tool-html "Grep"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [pattern path output_mode glob type head_limit]} parsed-input
        path-display (or (basename path) ".")
        mode-str (case output_mode
                   "content" "content"
                   "count" "count"
                   "files")]
    [:span {:class "flex gap-2 flex-1 min-w-0 items-center"}
     [:span {:class "text-log-tool font-medium shrink-0"} "Grep"]
     [:span {:class "text-eval font-medium"} (str "\"" (truncate pattern 40) "\"")]
     [:span {:class "text-text-400 shrink-0"} "in"]
     [:span {:class "text-text-200" :title path} path-display]
     (when glob
       [:span {:class "text-text-400"} (str "(" glob ")")])
     (when type
       [:span {:class "text-text-400"} (str "[" type "]")])
     [:span {:class "text-text-500 text-2xs"} mode-str]
     (when head_limit
       [:span {:class "text-text-500 text-2xs"} (str "limit=" head_limit)])]))

(defmethod render-tool-hover "Grep"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [pattern path output_mode glob type head_limit]} parsed-input]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "pattern" pattern "text-eval")
      (hover-line "path" (or path ".") "text-text-50")
      (when glob (hover-line "glob" glob))
      (when type (hover-line "type" type))
      (hover-line "mode" (or output_mode "files_with_matches"))
      (when head_limit (hover-line "limit" (str head_limit)))])))

;; Bash tool - show command inline with clipping
(defmethod render-tool-html "Bash"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [command description timeout]} parsed-input
        ;; Use description if available, otherwise command
        display-text (or description command "")
        max-len 100
        clipped (if (> (count display-text) max-len)
                  (str (subs display-text 0 max-len) "...")
                  display-text)]
    [:span {:class "flex items-center gap-2 flex-1 min-w-0"}
     [:span {:class "text-warning shrink-0"} "⚡"]
     [:span {:class "text-text-300 shrink-0"} "Bash"]
     [:code {:class "text-text-200 truncate text-2xs bg-base-800 px-1.5 py-0.5 rounded font-mono"}
      clipped]
     (when timeout
       [:span {:class "text-text-500 text-2xs shrink-0"} (str timeout "ms")])]))

(defmethod render-tool-hover "Bash"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [command description timeout]} parsed-input]
    (hover-card
     [:div {:class "space-y-1"}
      (when description
        (hover-line "description" description "text-text-200"))
      (hover-code-block "command" command "bash")
      (when timeout
        (hover-line "timeout" (str timeout "ms")))])))

;; Glob tool - show pattern and path
(defmethod render-tool-html "Glob"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [pattern path]} parsed-input
        path-display (or (basename path) ".")]
    [:span {:class "flex gap-2 flex-1 min-w-0"}
     [:span {:class "text-log-tool font-medium shrink-0"} "Glob"]
     [:span {:class "text-eval font-medium"} (str "\"" pattern "\"")]
     (when path
       [:span {:class "text-text-400"} (str "in " path-display)])]))

(defmethod render-tool-hover "Glob"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [pattern path]} parsed-input]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "pattern" pattern "text-eval")
      (hover-line "path" (or path ".") "text-text-50")])))

;; Write tool - show file path
(defmethod render-tool-html "Write"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [file_path content]} parsed-input
        file-name (basename file_path)
        line-count (count-lines content)]
    [:span {:class "flex gap-2 flex-1 min-w-0"}
     [:span {:class "text-log-tool font-medium shrink-0"} "Write"]
     [:span {:class "text-text-50" :title file_path} file-name]
     [:span {:class "text-text-400"} (str "(" line-count " lines)")]]))

(defmethod render-tool-hover "Write"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [file_path content]} parsed-input
        line-count (count-lines content)
        ;; Detect language from file extension
        lang (when file_path
               (cond
                 (str/ends-with? file_path ".clj") "clojure"
                 (str/ends-with? file_path ".cljs") "clojure"
                 (str/ends-with? file_path ".cljc") "clojure"
                 (str/ends-with? file_path ".edn") "clojure"
                 (str/ends-with? file_path ".sh") "bash"
                 :else nil))]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "path" file_path "text-text-50")
      (hover-line "lines" (str line-count))
      (hover-code-block "content preview" content lang)])))

;; mcp__seon__eval - show Clojure code (displayed as "REPL" for friendlier UI)
(defmethod render-tool-html "mcp__seon__eval"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [code timeout_ms]} parsed-input
        code-lines (str/split-lines (or code ""))
        first-line (first code-lines)
        multi-line? (> (count code-lines) 1)
        preview (if (> (count first-line) 60)
                  (str (subs first-line 0 57) "...")
                  first-line)]
    [:span {:class "flex gap-2 flex-1 min-w-0 items-start"}
     [:span {:class "text-eval font-medium shrink-0"} "REPL"]
     (if multi-line?
       [:details {:class "text-text-200 min-w-0 flex-1" :data-preserve-attr "open"}
        [:summary {:class "cursor-pointer list-none"}
         [:code {:class "language-clojure text-eval/70"} preview]
         [:span {:class "text-info ml-1 text-2xs"} (str (count code-lines) " lines")]]
        [:pre {:class "pl-2 border-l-2 border-eval/30 mt-1 font-mono"}
         [:code {:class "language-clojure"}
          (if (> (count code-lines) code-preview-lines)
            [:span
             (str/join "\n" (take code-preview-lines code-lines))
             [:details {:class "inline" :data-preserve-attr "open"}
              [:summary {:class "cursor-pointer list-none text-info text-2xs block mt-1"}
               (str "... " (- (count code-lines) code-preview-lines) " more lines ▸")]
              [:span {:class "block"} (str/join "\n" (drop code-preview-lines code-lines))]]]
            code)]]]
       [:code {:class "language-clojure text-eval/70 truncate font-mono"} preview])
     (when timeout_ms
       [:span {:class "text-text-500 text-2xs shrink-0"} (str timeout_ms "ms")])]))

(defmethod render-tool-hover "mcp__seon__eval"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [code session_id timeout_ms]} parsed-input
        code-lines (str/split-lines (or code ""))]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "tool" "REPL (mcp__seon__eval)" "text-eval")
      (when session_id (hover-line "session" session_id))
      (hover-line "lines" (str (count code-lines)))
      (when timeout_ms (hover-line "timeout" (str timeout_ms "ms")))
      (hover-code-block "code" code "clojure")])))

;; Task tool - show agent launch info
(defmethod render-tool-html "Task"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [description prompt subagent_type]} parsed-input]
    [:span {:class "flex gap-2 flex-1 min-w-0 items-start"}
     [:span {:class "text-log-tool font-medium shrink-0"} "Task"]
     [:span {:class "text-log-launch"} (or subagent_type "agent")]
     (when description
       [:span {:class "text-text-200"} description])
     (when (and prompt (not description))
       [:span {:class "text-text-400 truncate"} (truncate prompt 60)])]))

(defmethod render-tool-hover "Task"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [description prompt subagent_type]} parsed-input]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "type" (or subagent_type "agent") "text-log-launch")
      (when description
        (hover-line "description" description))
      (when prompt
        ;; Prompts are typically plain text, not code
        (hover-code-block "prompt" prompt nil))])))

;;; ---------------------------------------------------------------------------
;;; Task Tool Renderers (TaskCreate, TaskList, TaskUpdate, TaskGet)
;;; ---------------------------------------------------------------------------
;;
;; Status indicators:
;;   ✓ - completed (green)
;;   ● - in_progress (amber)
;;   ○ - pending (gray)

(defn- task-status-indicator
  "Return status indicator character and class for a task status."
  [status]
  (case (str status)
    "completed" ["✓" "text-success"]
    "in_progress" ["●" "text-warning"]
    "pending" ["○" "text-text-400"]
    ["?" "text-text-500"]))

;; TaskList tool - show numbered task list with status
(defmethod render-tool-html "TaskList"
  [_tool-name _parsed-input _raw-input]
  [:span {:class "flex gap-2 flex-1 min-w-0"}
   [:span {:class "text-log-tool font-medium shrink-0"} "TaskList"]
   [:span {:class "text-text-400"} "checking tasks..."]])

(defmethod render-tool-hover "TaskList"
  [_tool-name _parsed-input _raw-input]
  (hover-card
   [:div {:class "space-y-1"}
    (hover-line "tool" "TaskList" "text-log-tool")
    [:div {:class "text-text-400 text-2xs"} "Lists all tasks in the current session"]]))

;; TaskCreate tool - show new task being added
(defmethod render-tool-html "TaskCreate"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [subject description]} parsed-input]
    [:span {:class "flex gap-2 flex-1 min-w-0 items-start"}
     [:span {:class "text-log-tool font-medium shrink-0"} "TaskCreate"]
     [:span {:class "text-success font-medium shrink-0"} "+"]
     [:span {:class "text-text-200 truncate"} (or subject (truncate description 60))]]))

(defmethod render-tool-hover "TaskCreate"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [subject description]} parsed-input]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "tool" "TaskCreate" "text-log-tool")
      (hover-line "subject" subject "text-text-200")
      (when description
        [:div {:class "mt-2"}
         [:div {:class "text-text-400 text-2xs mb-0.5"} "description:"]
         [:div {:class "text-text-300 text-2xs max-h-24 overflow-y-auto whitespace-pre-wrap"}
          description]])])))

;; TaskUpdate tool - show status transitions
(defmethod render-tool-html "TaskUpdate"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [taskId status subject]} parsed-input
        [indicator indicator-class] (when status (task-status-indicator status))]
    [:span {:class "flex gap-2 flex-1 min-w-0 items-center"}
     [:span {:class "text-log-tool font-medium shrink-0"} "TaskUpdate"]
     [:span {:class "text-text-400 shrink-0"} (str "#" taskId)]
     (when status
       [:span {:class "flex items-center gap-1"}
        [:span {:class "text-text-500"} "→"]
        [:span {:class indicator-class} indicator]
        [:span {:class indicator-class} status]])
     (when subject
       [:span {:class "text-text-300 truncate"} (str "\"" (truncate subject 40) "\"")])]))

(defmethod render-tool-hover "TaskUpdate"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [taskId status subject description]} parsed-input]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "tool" "TaskUpdate" "text-log-tool")
      (hover-line "task ID" taskId)
      (when status
        (let [[indicator indicator-class] (task-status-indicator status)]
          (hover-line "new status" [:span {:class indicator-class} (str indicator " " status)])))
      (when subject
        (hover-line "subject" subject "text-text-200"))
      (when description
        [:div {:class "mt-2"}
         [:div {:class "text-text-400 text-2xs mb-0.5"} "description:"]
         [:div {:class "text-text-300 text-2xs max-h-24 overflow-y-auto whitespace-pre-wrap"}
          description]])])))

;; TaskGet tool - show task retrieval
(defmethod render-tool-html "TaskGet"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [taskId]} parsed-input]
    [:span {:class "flex gap-2 flex-1 min-w-0"}
     [:span {:class "text-log-tool font-medium shrink-0"} "TaskGet"]
     [:span {:class "text-text-400"} (str "#" taskId)]]))

(defmethod render-tool-hover "TaskGet"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [taskId]} parsed-input]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "tool" "TaskGet" "text-log-tool")
      (hover-line "task ID" taskId)])))

;;; ---------------------------------------------------------------------------
;;; Legacy Todo Tool (TodoWrite)
;;; ---------------------------------------------------------------------------

;; TodoWrite tool - show full task list inline with status indicators
;; Status: ✓ completed (green), ● in_progress (amber), ○ pending (gray)
(defmethod render-tool-html "TodoWrite"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [todos]} parsed-input]
    [:span {:class "flex flex-col gap-0.5 flex-1 min-w-0"}
     [:span {:class "text-log-tool font-medium"} "TodoWrite"]
     (when (seq todos)
       [:div {:class "ml-2 space-y-0.5"}
        (for [[idx {:keys [content status]}] (map-indexed vector todos)]
          [:div {:class "flex items-center gap-2 text-xs font-mono"}
           [:span {:class "text-text-500 w-4 text-right"} (inc idx)]
           [:span {:class (case status
                            "completed" "text-success"
                            "in_progress" "text-warning"
                            "text-text-400")}
            (case status
              "completed" "✓"
              "in_progress" "●"
              "○")]
           [:span {:class "text-text-200"} content]])])]))

(defmethod render-tool-hover "TodoWrite"
  [_tool-name _parsed-input _raw-input]
  ;; No hover needed - full list shown inline
  nil)

;;; ---------------------------------------------------------------------------
;;; New Unified Tool Rendering Implementations
;;; ---------------------------------------------------------------------------

;; Edit tool - header, preview, and full input
(defmethod render-tool-header "Edit"
  [_name {:keys [file_path old_string new_string replace_all]}]
  (let [stats (compute-diff-stats old_string new_string)
        stats-str (format-diff-stats stats)]
    [:span {:class "flex items-center gap-2"}
     [:span {:class "text-info"} "✎"]
     [:span {:class "text-text-300"} "Edit"]
     [:code {:class "text-text-200 text-2xs"} (basename file_path)]
     [:span {:class "text-text-500 text-2xs"} (str "(" stats-str ")")]
     (when replace_all
       [:span {:class "text-warning text-2xs"} "replace-all"])]))

(defmethod render-tool-preview "Edit"
  [_name {:keys [old_string new_string]}]
  (let [old-lines (when (not (str/blank? old_string))
                    (take tool-preview-lines (str/split-lines old_string)))
        new-lines (when (not (str/blank? new_string))
                    (take tool-preview-lines (str/split-lines new_string)))]
    (when (or (seq old-lines) (seq new-lines))
      [:pre {:class "mt-1 text-2xs bg-base-900 p-2 rounded font-mono overflow-x-auto"}
       (render-diff-lines old-lines new-lines)])))

(defmethod render-tool-input "Edit"
  [_name {:keys [old_string new_string]}]
  (let [old-lines (when (not (str/blank? old_string)) (str/split-lines old_string))
        new-lines (when (not (str/blank? new_string)) (str/split-lines new_string))]
    [:pre {:class "bg-base-900 p-2 rounded text-2xs font-mono overflow-x-auto"}
     (render-diff-lines old-lines new-lines)]))

;; Read tool - header only
(defmethod render-tool-header "Read"
  [_name {:keys [file_path offset limit]}]
  (let [range-str (cond
                    (and offset limit) (str ":" offset "-" (+ offset limit))
                    offset (str ":" offset "+")
                    limit (str ":1-" limit)
                    :else "")]
    [:span {:class "flex items-center gap-2"}
     [:span {:class "text-info"} "📖"]
     [:span {:class "text-text-300"} "Read"]
     [:code {:class "text-text-200 text-2xs"} (str (basename file_path) range-str)]]))

(defmethod render-tool-input "Read"
  [_name {:keys [file_path offset limit]}]
  [:div {:class "text-2xs text-text-400"}
   [:div "Path: " [:span {:class "text-text-200"} file_path]]
   (when offset [:div "Offset: " [:span {:class "text-text-200"} offset]])
   (when limit [:div "Limit: " [:span {:class "text-text-200"} (str limit " lines")]])])

;; Grep tool - header and input
(defmethod render-tool-header "Grep"
  [_name {:keys [pattern path glob type]}]
  [:span {:class "flex items-center gap-2"}
   [:span {:class "text-info"} "🔍"]
   [:span {:class "text-text-300"} "Grep"]
   [:span {:class "text-eval font-medium text-2xs"} (str "\"" (truncate pattern 30) "\"")]
   [:span {:class "text-text-500 text-2xs"} (str "in " (or (basename path) "."))]
   (when glob [:span {:class "text-text-500 text-2xs"} (str "(" glob ")")])
   (when type [:span {:class "text-text-500 text-2xs"} (str "[" type "]")])])

(defmethod render-tool-input "Grep"
  [_name {:keys [pattern path output_mode glob type head_limit]}]
  [:div {:class "text-2xs space-y-0.5"}
   [:div "Pattern: " [:span {:class "text-eval"} pattern]]
   [:div "Path: " [:span {:class "text-text-200"} (or path ".")]]
   (when glob [:div "Glob: " [:span {:class "text-text-200"} glob]])
   (when type [:div "Type: " [:span {:class "text-text-200"} type]])
   [:div "Mode: " [:span {:class "text-text-200"} (or output_mode "files_with_matches")]]
   (when head_limit [:div "Limit: " [:span {:class "text-text-200"} head_limit]])])

;; Glob tool - header only
(defmethod render-tool-header "Glob"
  [_name {:keys [pattern path]}]
  [:span {:class "flex items-center gap-2"}
   [:span {:class "text-info"} "📁"]
   [:span {:class "text-text-300"} "Glob"]
   [:span {:class "text-eval font-medium text-2xs"} (str "\"" pattern "\"")]
   (when path [:span {:class "text-text-500 text-2xs"} (str "in " (basename path))])])

(defmethod render-tool-input "Glob"
  [_name {:keys [pattern path]}]
  [:div {:class "text-2xs space-y-0.5"}
   [:div "Pattern: " [:span {:class "text-eval"} pattern]]
   [:div "Path: " [:span {:class "text-text-200"} (or path ".")]]])

;; Bash tool - header, preview, and full input
(defmethod render-tool-header "Bash"
  [_name {:keys [description command timeout]}]
  (let [display (or description (truncate command 50))]
    [:span {:class "flex items-center gap-2"}
     [:span {:class "text-warning"} "⚡"]
     [:span {:class "text-text-300"} "Bash"]
     [:span {:class "text-text-200 text-2xs truncate max-w-md"} display]
     (when timeout [:span {:class "text-text-500 text-2xs"} (str timeout "ms")])]))

(defmethod render-tool-preview "Bash"
  [_name {:keys [command]}]
  (when command
    (let [lines (str/split-lines command)
          preview-lines (take tool-preview-lines lines)]
      (when (> (count lines) 1)  ; Only show preview if multi-line
        [:pre {:class "mt-1 text-2xs bg-base-900 p-2 rounded font-mono overflow-x-auto"}
         [:code {:class "language-bash"}
          (str/join "\n" preview-lines)
          (when (> (count lines) tool-preview-lines)
            [:span {:class "text-text-500 block"} (str "... " (- (count lines) tool-preview-lines) " more")])]]))))

(defmethod render-tool-input "Bash"
  [_name {:keys [command description timeout]}]
  [:div {:class "space-y-2"}
   (when description
     [:div {:class "text-2xs text-text-400"} "Description: " [:span {:class "text-text-200"} description]])
   [:pre {:class "bg-base-900 p-2 rounded text-2xs font-mono overflow-x-auto"}
    [:code {:class "language-bash"} command]]
   (when timeout
     [:div {:class "text-2xs text-text-400"} "Timeout: " [:span {:class "text-text-200"} (str timeout "ms")]])])

;; Write tool - header, preview, and full input
(defmethod render-tool-header "Write"
  [_name {:keys [file_path content]}]
  (let [line-count (count-lines content)]
    [:span {:class "flex items-center gap-2"}
     [:span {:class "text-info"} "✎"]
     [:span {:class "text-text-300"} "Write"]
     [:code {:class "text-text-200 text-2xs"} (basename file_path)]
     [:span {:class "text-text-500 text-2xs"} (str "(" line-count " lines)")]]))

(defmethod render-tool-preview "Write"
  [_name {:keys [content file_path]}]
  (when content
    (let [lines (str/split-lines content)
          preview-lines (take tool-preview-lines lines)
          lang (when file_path
                 (cond
                   (re-find #"\.(clj[scx]?|edn)$" file_path) "clojure"
                   (re-find #"\.(js|jsx|ts|tsx)$" file_path) "javascript"
                   (re-find #"\.py$" file_path) "python"
                   (re-find #"\.(sh|bash)$" file_path) "bash"
                   :else nil))]
      [:pre {:class "mt-1 text-2xs bg-base-900 p-2 rounded font-mono overflow-x-auto"}
       [:code {:class (when lang (str "language-" lang))}
        (str/join "\n" preview-lines)
        (when (> (count lines) tool-preview-lines)
          [:span {:class "text-text-500 block"} (str "... " (- (count lines) tool-preview-lines) " more")])]])))

(defmethod render-tool-input "Write"
  [_name {:keys [content file_path]}]
  (let [lang (when file_path
               (cond
                 (re-find #"\.(clj[scx]?|edn)$" file_path) "clojure"
                 (re-find #"\.(js|jsx|ts|tsx)$" file_path) "javascript"
                 (re-find #"\.py$" file_path) "python"
                 (re-find #"\.(sh|bash)$" file_path) "bash"
                 :else nil))]
    [:pre {:class "bg-base-900 p-2 rounded text-2xs font-mono overflow-x-auto max-h-96 overflow-y-auto"}
     [:code {:class (when lang (str "language-" lang))} content]]))

;; REPL (mcp__seon__eval) - header, preview, and full input
(defmethod render-tool-header "mcp__seon__eval"
  [_name {:keys [code timeout_ms]}]
  (let [first-line (first (str/split-lines (or code "")))
        preview (if (> (count first-line) 50) (str (subs first-line 0 47) "...") first-line)]
    [:span {:class "flex items-center gap-2"}
     [:span {:class "text-eval"} "λ"]
     [:span {:class "text-text-300"} "REPL"]
     [:code {:class "text-eval/70 text-2xs truncate max-w-md"} preview]
     (when timeout_ms [:span {:class "text-text-500 text-2xs"} (str timeout_ms "ms")])]))

(defmethod render-tool-preview "mcp__seon__eval"
  [_name {:keys [code]}]
  (when code
    (let [lines (str/split-lines code)
          preview-lines (take tool-preview-lines lines)]
      (when (> (count lines) 1)  ; Only show preview if multi-line
        [:pre {:class "mt-1 text-2xs bg-base-900 p-2 rounded font-mono overflow-x-auto"}
         [:code {:class "language-clojure"}
          (str/join "\n" preview-lines)
          (when (> (count lines) tool-preview-lines)
            [:span {:class "text-text-500 block"} (str "... " (- (count lines) tool-preview-lines) " more")])]]))))

(defmethod render-tool-input "mcp__seon__eval"
  [_name {:keys [code session_id timeout_ms]}]
  [:div {:class "space-y-2"}
   (when session_id
     [:div {:class "text-2xs text-text-400"} "Session: " [:span {:class "text-text-200"} session_id]])
   [:pre {:class "bg-base-900 p-2 rounded text-2xs font-mono overflow-x-auto"}
    [:code {:class "language-clojure"} code]]
   (when timeout_ms
     [:div {:class "text-2xs text-text-400"} "Timeout: " [:span {:class "text-text-200"} (str timeout_ms "ms")]])])

;; Task tool - header and input
(defmethod render-tool-header "Task"
  [_name {:keys [description subagent_type]}]
  [:span {:class "flex items-center gap-2"}
   [:span {:class "text-log-launch"} "🔀"]
   [:span {:class "text-text-300"} "Task"]
   [:span {:class "text-log-launch text-2xs"} (or subagent_type "agent")]
   (when description
     [:span {:class "text-text-200 text-2xs truncate max-w-md"} description])])

(defmethod render-tool-input "Task"
  [_name {:keys [description prompt subagent_type]}]
  [:div {:class "space-y-2 text-2xs"}
   [:div {:class "text-text-400"} "Type: " [:span {:class "text-log-launch"} (or subagent_type "agent")]]
   (when description
     [:div {:class "text-text-400"} "Description: " [:span {:class "text-text-200"} description]])
   (when prompt
     [:div
      [:div {:class "text-text-400 mb-1"} "Prompt:"]
      [:pre {:class "bg-base-900 p-2 rounded font-mono overflow-x-auto max-h-48 overflow-y-auto"}
       prompt]])])

;; TaskCreate - header and input
(defmethod render-tool-header "TaskCreate"
  [_name {:keys [subject]}]
  [:span {:class "flex items-center gap-2"}
   [:span {:class "text-success"} "+"]
   [:span {:class "text-text-300"} "TaskCreate"]
   [:span {:class "text-text-200 text-2xs truncate max-w-md"} (str "\"" subject "\"")]])

(defmethod render-tool-input "TaskCreate"
  [_name {:keys [subject description]}]
  [:div {:class "space-y-2 text-2xs"}
   [:div {:class "text-text-400"} "Subject: " [:span {:class "text-text-200"} subject]]
   (when description
     [:div
      [:div {:class "text-text-400 mb-1"} "Description:"]
      [:pre {:class "bg-base-900 p-2 rounded font-mono overflow-x-auto whitespace-pre-wrap"}
       description]])])

;; TaskUpdate - header and input
(defmethod render-tool-header "TaskUpdate"
  [_name {:keys [taskId status]}]
  (let [[indicator indicator-class] (when status (task-status-indicator status))]
    [:span {:class "flex items-center gap-2"}
     [:span {:class "text-info"} "→"]
     [:span {:class "text-text-300"} "TaskUpdate"]
     [:span {:class "text-text-400 text-2xs"} (str "#" taskId)]
     (when status
       [:span {:class "flex items-center gap-1"}
        [:span {:class "text-text-500"} "→"]
        [:span {:class indicator-class} indicator]
        [:span {:class (str indicator-class " text-2xs")} status]])]))

(defmethod render-tool-input "TaskUpdate"
  [_name {:keys [taskId status subject description]}]
  [:div {:class "space-y-1 text-2xs"}
   [:div {:class "text-text-400"} "Task ID: " [:span {:class "text-text-200"} taskId]]
   (when status
     (let [[indicator indicator-class] (task-status-indicator status)]
       [:div {:class "text-text-400"} "Status: " [:span {:class indicator-class} (str indicator " " status)]]))
   (when subject [:div {:class "text-text-400"} "Subject: " [:span {:class "text-text-200"} subject]])
   (when description
     [:div
      [:div {:class "text-text-400 mb-1"} "Description:"]
      [:pre {:class "bg-base-900 p-2 rounded font-mono overflow-x-auto whitespace-pre-wrap"}
       description]])])

;; TaskList - header only
(defmethod render-tool-header "TaskList"
  [_name _parsed-input]
  [:span {:class "flex items-center gap-2"}
   [:span {:class "text-info"} "☰"]
   [:span {:class "text-text-300"} "TaskList"]])

;; TaskGet - header and input
(defmethod render-tool-header "TaskGet"
  [_name {:keys [taskId]}]
  [:span {:class "flex items-center gap-2"}
   [:span {:class "text-info"} "→"]
   [:span {:class "text-text-300"} "TaskGet"]
   [:span {:class "text-text-400 text-2xs"} (str "#" taskId)]])

(defmethod render-tool-input "TaskGet"
  [_name {:keys [taskId]}]
  [:div {:class "text-2xs text-text-400"}
   "Task ID: " [:span {:class "text-text-200"} taskId]])

;; TodoWrite (legacy) - header, preview with task list, full input
(def ^:private max-preview-todos
  "Max todos to show in collapsed preview."
  10)

(defn- render-todo-item
  "Render a single todo item with index and status indicator."
  [idx {:keys [content status]}]
  [:div {:class "flex items-center gap-2 text-xs font-mono"}
   [:span {:class "text-text-500 w-4 text-right"} (inc idx)]
   [:span {:class (case status
                    "completed" "text-success"
                    "in_progress" "text-warning"
                    "text-text-400")}
    (case status
      "completed" "✓"
      "in_progress" "●"
      "○")]
   [:span {:class "text-text-200"} content]])

(defmethod render-tool-header "TodoWrite"
  [_name {:keys [todos]}]
  (let [total (count todos)
        completed (count (filter #(= "completed" (:status %)) todos))
        in-progress (count (filter #(= "in_progress" (:status %)) todos))]
    [:span {:class "flex items-center gap-2"}
     [:span {:class "text-success"} "☑"]
     [:span {:class "text-text-300"} "TodoWrite"]
     [:span {:class "text-text-500 text-2xs"}
      (str completed "/" total " done"
           (when (pos? in-progress) (str ", " in-progress " active")))]]))

(defmethod render-tool-preview "TodoWrite"
  [_name {:keys [todos]}]
  (when (seq todos)
    (let [total (count todos)
          needs-truncation? (> total max-preview-todos)
          ;; Show last N items (the active/pending ones, not old completed)
          hidden-count (- total max-preview-todos)
          visible-todos (if needs-truncation?
                          (drop hidden-count todos)
                          todos)
          ;; Preserve original indices for display
          start-idx (if needs-truncation? hidden-count 0)]
      [:div {:class "mt-1 space-y-0.5"}
       (when needs-truncation?
         [:div {:class "text-text-500 text-xs font-mono pl-6"}
          (str "... " hidden-count " more items")])
       (for [[offset todo] (map-indexed vector visible-todos)]
         ^{:key offset}
         (render-todo-item (+ start-idx offset) todo))])))

(defmethod render-tool-input "TodoWrite"
  [_name {:keys [todos]}]
  ;; Full list (only shown in expanded view, meaningful when > 10 items)
  (when (seq todos)
    [:div {:class "space-y-0.5"}
     (for [[idx todo] (map-indexed vector todos)]
       ^{:key idx}
       (render-todo-item idx todo))]))

;; TOOL - Amber/yellow, uses tool-specific renderer via multimethod
(defmethod view/render* [:html :agent.log/tool]
  [entry _format]
  (let [{:keys [timestamp tool-name input]} entry
        parsed (parse-tool-input input)]
    [:div {:class "log-line group relative font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 hover:bg-base-800"}
     [:div {:class "flex gap-2 items-start"}
      [:span {:class "text-text-400 shrink-0" :title timestamp} (format-local-time timestamp)]
      [:span {:class "text-log-tool shrink-0 w-16"} "TOOL"]
      (render-tool-html tool-name parsed input)]]))

(defmethod view/render* [:ai :agent.log/tool]
  [entry _format]
  (let [{:keys [timestamp tool-name input]} entry
        parsed (parse-tool-input input)]
    (str timestamp " TOOL " tool-name " "
         (case tool-name
           "Edit" (str (basename (:file_path parsed)) " "
                       (format-diff-stats (compute-diff-stats (:old_string parsed) (:new_string parsed))))
           "Read" (str (basename (:file_path parsed))
                       (when-let [o (:offset parsed)] (str ":" o)))
           "Grep" (str "\"" (:pattern parsed) "\" in " (or (basename (:path parsed)) "."))
           "Bash" (or (:description parsed) (truncate (:command parsed) 60))
           "mcp__seon__eval" (truncate (:code parsed) 80)
           (truncate input 100)))))

;; RESULT - Green, shows result preview (truncated to max-inline-lines)
(defmethod view/render* [:html :agent.log/result]
  [entry _format]
  (let [{:keys [timestamp tool-name output]} entry
        {:keys [truncated? preview hidden-lines]} (truncate-lines output max-inline-lines)
        output-len (count (or output ""))]
    [:div {:class "log-line group relative font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 hover:bg-base-800"}
     [:div {:class "flex gap-2 items-start"}
      [:span {:class "text-text-400 shrink-0" :title timestamp} (format-local-time timestamp)]
      [:span {:class "text-log-result shrink-0 w-16"} "RESULT"]
      [:span {:class "text-text-400 shrink-0"} tool-name]
      (when output
        [:div {:class "text-text-200 ml-2 min-w-0 flex-1"}
         [:pre {:class "whitespace-pre-wrap break-words"} preview]
         (when truncated?
           [:span {:class "text-text-500"} (str "... " hidden-lines " more lines")])])]
     ;; Hover card with result details
     (hover-card
      [:div {:class "space-y-1"}
       (hover-line "tool" tool-name "text-log-result")
       (hover-line "length" (str output-len " chars"))
       (when output
         (hover-code-block "output" output))])]))

(defmethod view/render* [:ai :agent.log/result]
  [entry _format]
  (let [{:keys [timestamp tool-name output]} entry]
    (str timestamp " RESULT " tool-name " " (truncate output 100))))

;; HOOK - Cyan, shows hook output
(defmethod view/render* [:html :agent.log/hook]
  [entry _format]
  (let [{:keys [timestamp file-type tests-status gemini-status test-output gemini-feedback]} entry]
    [:div {:class "log-line group relative font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 hover:bg-base-800"}
     [:div {:class "flex gap-2"}
      [:span {:class "text-text-400 shrink-0" :title timestamp} (format-local-time timestamp)]
      [:span {:class "text-log-hook shrink-0 w-16"} "HOOK"]
      [:span {:class "text-text-50"} file-type]
      [:span {:class (if (= tests-status "pass") "text-success" "text-warning")}
       (str "tests=" tests-status)]
      [:span {:class (if (= gemini-status "pass") "text-success" "text-text-400")}
       (str "gemini=" gemini-status)]]
     ;; Hover card with hook details
     (hover-card
      [:div {:class "space-y-1"}
       (hover-line "file type" file-type "text-log-hook")
       (hover-line "tests" tests-status (if (= tests-status "pass") "text-success" "text-warning"))
       (hover-line "gemini" gemini-status (if (= gemini-status "pass") "text-success" "text-text-400"))
       (when test-output
         (hover-code-block "test output" test-output))
       (when gemini-feedback
         [:div {:class "mt-2"}
          [:div {:class "text-text-400 text-2xs mb-0.5"} "gemini feedback:"]
          [:div {:class "text-text-200 text-2xs max-h-24 overflow-y-auto"}
           gemini-feedback]])])]))

(defmethod view/render* [:ai :agent.log/hook]
  [entry _format]
  (let [{:keys [timestamp file-type tests-status gemini-status]} entry]
    (str timestamp " HOOK " file-type " tests=" tests-status " gemini=" gemini-status)))

;; COMPLETE - Green bold, shows stats
(defmethod view/render* [:html :agent.log/complete]
  [entry _format]
  (let [{:keys [timestamp subtype cost messages duration-ms input-tokens output-tokens]} entry]
    [:div {:class "log-line group relative font-mono text-xs leading-tight py-1 border-b border-base-700/50 hover:bg-base-800 bg-success/5"}
     [:div {:class "flex gap-2"}
      [:span {:class "text-text-400 shrink-0" :title timestamp} (format-local-time timestamp)]
      [:span {:class "text-log-done font-semibold shrink-0 w-16"} "COMPLETE"]
      (when subtype
        [:span {:class "text-success"} subtype])
      (when cost
        [:span {:class "text-text-50"} (str "cost=" (format-cost cost))])
      (when messages
        [:span {:class "text-text-200"} (str "messages=" messages)])
      (when duration-ms
        [:span {:class "text-text-400"} (str "duration=" (format-duration duration-ms))])]
     ;; Hover card with completion details
     (hover-card
      [:div {:class "space-y-1"}
       (when subtype (hover-line "subtype" subtype "text-success"))
       (when cost (hover-line "cost" (format-cost cost) "text-text-50"))
       (when messages (hover-line "messages" (str messages)))
       (when duration-ms (hover-line "duration" (format-duration duration-ms)))
       (when input-tokens (hover-line "input tokens" (str input-tokens)))
       (when output-tokens (hover-line "output tokens" (str output-tokens)))
       (hover-line "timestamp" timestamp "text-text-400")])]))

(defmethod view/render* [:ai :agent.log/complete]
  [entry _format]
  (let [{:keys [timestamp subtype cost messages duration-ms]} entry]
    (str timestamp " COMPLETE"
         (when subtype (str " " subtype))
         (when cost (str " cost=" (format-cost cost)))
         (when messages (str " messages=" messages))
         (when duration-ms (str " duration=" (format-duration duration-ms))))))

;; ERROR - Red, shows error
(defmethod view/render* [:html :agent.log/error]
  [entry _format]
  (let [{:keys [timestamp error]} entry]
    [:div {:class "log-line group relative font-mono text-xs leading-tight py-1 border-b border-base-700/50 hover:bg-base-800 bg-error/5"}
     [:div {:class "flex gap-2"}
      [:span {:class "text-text-400 shrink-0" :title timestamp} (format-local-time timestamp)]
      [:span {:class "text-log-error font-semibold shrink-0 w-16"} "ERROR"]
      [:span {:class "text-error break-all"} (truncate error 100)]]
     ;; Hover card with full error
     (hover-card
      [:div {:class "space-y-1"}
       (hover-line "timestamp" timestamp "text-text-400")
       [:div {:class "mt-2 bg-error/10 rounded p-2 text-error text-2xs max-h-32 overflow-y-auto whitespace-pre-wrap break-all"}
        error]])]))

(defmethod view/render* [:ai :agent.log/error]
  [entry _format]
  (let [{:keys [timestamp error]} entry]
    (str timestamp " ERROR " error)))

;;; ---------------------------------------------------------------------------
;;; Generic Log Line (for parsed log files)
;;; ---------------------------------------------------------------------------

(defmethod view/render* [:html :agent.log/line]
  [entry _format]
  (let [{:keys [type]} entry
        vtype (case type
                "LAUNCH" :agent.log/launch
                "MESSAGE" :agent.log/message
                "TOOL" :agent.log/tool
                "RESULT" :agent.log/result
                "HOOK" :agent.log/hook
                "COMPLETE" :agent.log/complete
                "ERROR" :agent.log/error
                nil)]
    (if vtype
      (view/render (typed-value vtype entry) :html)
      ;; Fallback for unknown types
      [:div {:class "font-mono text-xs leading-tight py-0.5 border-b border-base-700/50"}
       [:span {:class "text-text-400"} (pr-str entry)]])))

(defmethod view/render* [:ai :agent.log/line]
  [entry _format]
  (let [{:keys [type]} entry
        vtype (case type
                "LAUNCH" :agent.log/launch
                "MESSAGE" :agent.log/message
                "TOOL" :agent.log/tool
                "RESULT" :agent.log/result
                "HOOK" :agent.log/hook
                "COMPLETE" :agent.log/complete
                "ERROR" :agent.log/error
                nil)]
    (if vtype
      (view/render (typed-value vtype entry) :ai)
      (pr-str entry))))

;;; ---------------------------------------------------------------------------
;;; Agent Detail View - :seon.ai.agent/detail
;;; ---------------------------------------------------------------------------

(defmethod view/render* [:html :seon.ai.agent/detail]
  [agent _format]
  (let [{:seon.ai.agent/keys [session-id namespace agent-status nrepl-port cost-usd log-lines]} agent
        back-href (view/list-url {::view/view-type :seon.ai.agent/detail})]
    [:div {:class "space-y-4"}
     ;; Header with back link
     [:div {:class "flex items-center justify-between"}
      [:div {:class "flex items-center gap-4"}
       [:a {:href back-href
            :class "text-text-400 hover:text-text-200 text-xs"}
        "← back"]
       [:span {:class "font-mono text-lg text-signal font-semibold"} session-id]
       [:span {:class "font-mono text-sm text-text-200"} namespace]
       (ui/status-dot agent-status)]
      [:div {:class "flex items-center gap-4 text-xs text-text-400"}
       (when nrepl-port
         [:span (str "nREPL: " nrepl-port)])
       (when cost-usd
         [:span {:class "text-text-200"} (format-cost cost-usd)])]]
     ;; Log lines
     (when (seq log-lines)
       [:div {:class "bg-base-900 rounded overflow-hidden"}
        [:div {:class "p-3 overflow-y-auto flex flex-col-reverse" :style "max-height: 70vh"}
         [:div
          (for [line log-lines]
            (view/render (typed-value :agent.log/line line) :html))]]])]))

(defmethod view/render* [:ai :seon.ai.agent/detail]
  [agent _format]
  (let [{:seon.ai.agent/keys [session-id namespace agent-status log-lines]} agent]
    (str "Agent " session-id " (" namespace ") [" (name agent-status) "]\n"
         (when (seq log-lines)
           (str "Log:\n"
                (->> log-lines
                     (map #(str "  " (view/render (typed-value :agent.log/line %) :ai)))
                     (str/join "\n")))))))

;;; ---------------------------------------------------------------------------
;;; REPL Exploration
;;; ---------------------------------------------------------------------------

(comment
  (require '[seon.ns.view :as view])

  ;; Test agent summary rendering
  (def agent-data {:seon.ai.agent/session-id "e077"
                   :seon.ai.agent/namespace "seon.web.agents"
                   :seon.ai.agent/provider :claude
                   :seon.ai.agent/agent-status :running
                   :seon.ai.agent/nrepl-port 7889
                   :seon.ai.agent/cost-usd 0.12})

  ;; Using map-in API
  (view/render-value {::view/value (view/typed {::view/view-type :seon.ai.agent/summary
                                                ::view/value agent-data})
                      ::view/format :html})

  ;; Using convenience wrapper
  (view/render (view/typed {::view/view-type :seon.ai.agent/summary
                            ::view/value agent-data})
               :html)

  ;; Test log line rendering
  (def launch-line {:type "LAUNCH"
                    :timestamp "14:23:45"
                    :namespace "seon.trading"
                    :port 7892})

  (view/render (view/typed {::view/view-type :agent.log/launch
                            ::view/value launch-line})
               :html)

  nil)
