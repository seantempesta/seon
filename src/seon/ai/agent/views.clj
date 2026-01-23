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

;;; ---------------------------------------------------------------------------
;;; Helper Functions
;;; ---------------------------------------------------------------------------

(defn- truncate
  "Truncate string to max-len with ellipsis if needed."
  [s max-len]
  (if (and s (> (count s) max-len))
    (str (subs s 0 (- max-len 3)) "...")
    s))

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
  "CSS-only hover card that appears below a log line on hover.
   Uses Tailwind group/group-hover pattern.

   content - Hiccup content for the hover card body"
  [content]
  [:div {:class (str "hover-card hidden group-hover:block absolute left-0 top-full z-20 "
                     "bg-base-850 border border-base-700 rounded shadow-lg "
                     "mt-1 p-2 max-w-xl min-w-64 "
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
    [:div {:class "group relative font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 hover:bg-base-800"}
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

;; MESSAGE - Blue, shows assistant text (truncated)
(defmethod view/render* [:html :agent.log/message]
  [entry _format]
  (let [{:keys [timestamp role content]} entry
        long? (and content (> (count content) preview-length))
        char-count (count (or content ""))]
    [:div {:class "group relative font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 hover:bg-base-800"}
     [:div {:class "flex gap-2"}
      [:span {:class "text-text-400 shrink-0" :title timestamp} (format-local-time timestamp)]
      [:span {:class "text-log-message shrink-0 w-16"} "MESSAGE"]
      [:span {:class "text-text-400 shrink-0 w-16"} role]
      (if long?
        [:details {:class "text-text-50 inline" :data-preserve-attr "open"}
         [:summary {:class "cursor-pointer list-none"}
          (subs content 0 preview-length)
          [:span {:class "text-info ml-1"} (str "+" (- (count content) preview-length) " more")]]
         [:div {:class "break-all mt-1 pl-2 border-l-2 border-base-700"}
          content]]
        [:span {:class "text-text-50 break-all"} content])]
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

(defmethod render-tool-html :default
  [tool-name parsed-input raw-input]
  ;; Fallback: show tool name and raw input
  (let [display-input (or raw-input (pr-str parsed-input))
        long? (and display-input (> (count display-input) preview-length))]
    [:span {:class "flex gap-2 flex-1 min-w-0"}
     [:span {:class "text-log-tool font-medium shrink-0"} tool-name]
     (when display-input
       (if long?
         [:details {:class "text-text-200 inline min-w-0" :data-preserve-attr "open"}
          [:summary {:class "cursor-pointer list-none truncate"}
           (subs display-input 0 preview-length)
           [:span {:class "text-info ml-1"} (str "+" (- (count display-input) preview-length) " more")]]
          [:div {:class "break-all mt-1 pl-2 border-l-2 border-log-tool/30 whitespace-pre-wrap"}
           display-input]]
         [:span {:class "text-text-200 truncate"} display-input]))]))

(defmethod render-tool-hover :default
  [tool-name parsed-input raw-input]
  ;; Fallback: show tool name and full input
  (let [display-input (or raw-input (pr-str parsed-input))]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "tool" tool-name "text-log-tool")
      (when display-input
        (hover-code-block "input" display-input))])))

;; Edit tool - show file path and diff stats
(defmethod render-tool-html "Edit"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [file_path old_string new_string replace_all]} parsed-input
        file-name (basename file_path)
        stats (compute-diff-stats old_string new_string)
        stats-str (format-diff-stats stats)
        old-preview (truncate old_string 60)
        new-preview (truncate new_string 60)
        ;; Detect language from file extension
        lang (when file_path
               (cond
                 (str/ends-with? file_path ".clj") "clojure"
                 (str/ends-with? file_path ".cljs") "clojure"
                 (str/ends-with? file_path ".cljc") "clojure"
                 (str/ends-with? file_path ".edn") "clojure"
                 (str/ends-with? file_path ".sh") "bash"
                 :else nil))
        lang-class (when lang (str "language-" lang))]
    [:span {:class "flex gap-2 flex-1 min-w-0 items-start"}
     [:span {:class "text-log-tool font-medium shrink-0"} "Edit"]
     [:span {:class "text-text-50 shrink-0"
             :title file_path}
      file-name]
     [:span {:class "text-text-400 shrink-0"} (str "(" stats-str ")")]
     (when replace_all
       [:span {:class "text-warning text-2xs shrink-0"} "replace-all"])
     ;; Expandable diff preview
     [:details {:class "text-text-200 min-w-0" :data-preserve-attr "open"}
      [:summary {:class "cursor-pointer list-none text-info text-2xs"}
       "diff"]
      [:div {:class "mt-1 pl-2 border-l-2 border-log-tool/30 space-y-1"}
       ;; Old string (red)
       (when (not (str/blank? old_string))
         [:div {:class "text-error/80"}
          [:span {:class "text-error font-medium"} "- "]
          (if (> (count old_string) 200)
            [:details
             [:summary {:class "cursor-pointer list-none inline"}
              [:code {:class lang-class} old-preview]
              [:span {:class "text-info ml-1"} (str "+" (- (count old_string) 60) " more")]]
             [:pre [:code {:class lang-class} old_string]]]
            [:code {:class lang-class} old_string])])
       ;; New string (green)
       (when (not (str/blank? new_string))
         [:div {:class "text-success/80"}
          [:span {:class "text-success font-medium"} "+ "]
          (if (> (count new_string) 200)
            [:details
             [:summary {:class "cursor-pointer list-none inline"}
              [:code {:class lang-class} new-preview]
              [:span {:class "text-info ml-1"} (str "+" (- (count new_string) 60) " more")]]
             [:pre [:code {:class lang-class} new_string]]]
            [:code {:class lang-class} new_string])])]]]))

(defmethod render-tool-hover "Edit"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [file_path old_string new_string replace_all]} parsed-input
        stats (compute-diff-stats old_string new_string)]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "path" file_path "text-text-50")
      (hover-line "changes" (format-diff-stats stats))
      (when replace_all
        (hover-line "mode" "replace-all" "text-warning"))
      (hover-diff-block old_string new_string)])))

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

;; Bash tool - show command with description
(defmethod render-tool-html "Bash"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [command description timeout]} parsed-input
        cmd-preview (truncate command 80)
        long? (> (count command) 80)]
    [:span {:class "flex gap-2 flex-1 min-w-0 items-start"}
     [:span {:class "text-log-tool font-medium shrink-0"} "Bash"]
     (if description
       ;; Show description as primary, command in details
       [:span {:class "flex-1 min-w-0"}
        [:span {:class "text-text-200"} description]
        [:details {:class "text-text-400 mt-0.5" :data-preserve-attr "open"}
         [:summary {:class "cursor-pointer list-none text-2xs text-info"} "cmd"]
         [:pre {:class "pl-2 border-l-2 border-log-tool/30"}
          [:code {:class "language-bash"} command]]]]
       ;; No description - show command directly
       (if long?
         [:details {:class "text-text-200 min-w-0" :data-preserve-attr "open"}
          [:summary {:class "cursor-pointer list-none truncate"}
           [:code {:class "language-bash"} cmd-preview]
           [:span {:class "text-info ml-1"} "..."]]
          [:pre {:class "pl-2 border-l-2 border-log-tool/30"}
           [:code {:class "language-bash"} command]]]
         [:code {:class "language-bash text-text-200 truncate"} command]))
     (when timeout
       [:span {:class "text-text-500 text-2xs shrink-0"} (str "timeout=" timeout "ms")])]))

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

;; mcp__seon__eval - show Clojure code
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
     [:span {:class "text-eval font-medium shrink-0"} "eval"]
     (if multi-line?
       [:details {:class "text-text-200 min-w-0 flex-1" :data-preserve-attr "open"}
        [:summary {:class "cursor-pointer list-none"}
         [:code {:class "language-clojure text-eval/70"} preview]
         [:span {:class "text-info ml-1 text-2xs"} (str (count code-lines) " lines")]]
        [:pre {:class "pl-2 border-l-2 border-eval/30 mt-1"}
         [:code {:class "language-clojure"}
          (if (> (count code-lines) code-preview-lines)
            [:span
             (str/join "\n" (take code-preview-lines code-lines))
             [:span {:class "text-info text-2xs block mt-1"}
              (str "... " (- (count code-lines) code-preview-lines) " more lines")]]
            code)]]]
       [:code {:class "language-clojure text-eval/70 truncate"} preview])
     (when timeout_ms
       [:span {:class "text-text-500 text-2xs shrink-0"} (str timeout_ms "ms")])]))

(defmethod render-tool-hover "mcp__seon__eval"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [code session_id timeout_ms]} parsed-input
        code-lines (str/split-lines (or code ""))]
    (hover-card
     [:div {:class "space-y-1"}
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

;; TodoWrite tool - show todo count
(defmethod render-tool-html "TodoWrite"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [todos]} parsed-input
        todo-count (count todos)
        in-progress (count (filter #(= "in_progress" (:status %)) todos))
        completed (count (filter #(= "completed" (:status %)) todos))]
    [:span {:class "flex gap-2 flex-1 min-w-0"}
     [:span {:class "text-log-tool font-medium shrink-0"} "TodoWrite"]
     [:span {:class "text-text-200"} (str todo-count " todos")]
     (when (pos? in-progress)
       [:span {:class "text-warning"} (str in-progress " active")])
     (when (pos? completed)
       [:span {:class "text-success"} (str completed " done")])]))

(defmethod render-tool-hover "TodoWrite"
  [_tool-name parsed-input _raw-input]
  (let [{:keys [todos]} parsed-input
        todo-count (count todos)]
    (hover-card
     [:div {:class "space-y-1"}
      (hover-line "total" (str todo-count " todos"))
      (when (seq todos)
        [:div {:class "mt-2 space-y-0.5 max-h-32 overflow-y-auto"}
         (for [{:keys [content status]} (take 8 todos)]
           [:div {:class "flex gap-2 items-center text-2xs"}
            [:span {:class (case status
                             "completed" "text-success"
                             "in_progress" "text-warning"
                             "text-text-400")}
             (case status
               "completed" "done"
               "in_progress" "active"
               "pending")]
            [:span {:class "text-text-200 truncate"} content]])
         (when (> todo-count 8)
           [:div {:class "text-text-500 text-2xs"} (str "... " (- todo-count 8) " more")])])])))

;; TOOL - Amber/yellow, uses tool-specific renderer via multimethod
(defmethod view/render* [:html :agent.log/tool]
  [entry _format]
  (let [{:keys [timestamp tool-name input]} entry
        parsed (parse-tool-input input)]
    [:div {:class "group relative font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 hover:bg-base-800"}
     [:div {:class "flex gap-2 items-start"}
      [:span {:class "text-text-400 shrink-0" :title timestamp} (format-local-time timestamp)]
      [:span {:class "text-log-tool shrink-0 w-16"} "TOOL"]
      (render-tool-html tool-name parsed input)]
     ;; Hover card with tool-specific details
     (render-tool-hover tool-name parsed input)]))

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

;; RESULT - Green, shows result preview
(defmethod view/render* [:html :agent.log/result]
  [entry _format]
  (let [{:keys [timestamp tool-name output]} entry
        long? (and output (> (count output) preview-length))
        output-len (count (or output ""))]
    [:div {:class "group relative font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 hover:bg-base-800"}
     [:div {:class "flex gap-2"}
      [:span {:class "text-text-400 shrink-0" :title timestamp} (format-local-time timestamp)]
      [:span {:class "text-log-result shrink-0 w-16"} "RESULT"]
      [:span {:class "text-text-400 shrink-0"} tool-name]
      (when output
        (if long?
          [:details {:class "text-text-200 inline ml-2" :data-preserve-attr "open"}
           [:summary {:class "cursor-pointer list-none"}
            (subs output 0 preview-length)
            [:span {:class "text-info ml-1"} (str "+" (- (count output) preview-length) " more")]]
           [:div {:class "break-all mt-1 pl-2 border-l-2 border-log-result/30"}
            output]]
          [:span {:class "text-text-200 ml-2 break-all"} output]))]
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
    [:div {:class "group relative font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 hover:bg-base-800"}
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
    [:div {:class "group relative font-mono text-xs leading-tight py-1 border-b border-base-700/50 hover:bg-base-800 bg-success/5"}
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
    [:div {:class "group relative font-mono text-xs leading-tight py-1 border-b border-base-700/50 hover:bg-base-800 bg-error/5"}
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
