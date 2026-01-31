(ns seon.web.agents
  "Agent observatory state and handlers for /agents route.

   Combines running agents from the registry with completed sessions from XTDB
   to provide a unified view of all agent activity.

   Note: HTTP handlers follow Ring conventions (request -> response maps),
   not the map-in/map-out pattern used for domain APIs. HTML rendering
   functions are private helpers."
  (:require [taoensso.timbre :as log]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [clojure.data.json :as json]
            [markdown.core :as md]
            [seon.ai :as ai]
            [seon.ai.agent :as agent]
            [seon.ai.agent.views :as agent-views]
            [seon.ai.claude :as claude]
            [seon.db.node :as db]
            [seon.web.sse :as sse]
            [seon.web.html :as html]
            [dev.onionpancakes.chassis.core :as h])
  (:import [java.time Instant ZonedDateTime ZoneId LocalDate]
           [java.time.format DateTimeFormatter TextStyle]
           [java.time.temporal ChronoUnit]
           [java.util Locale]))

;; XTDB node reference - initialized at startup via init!
(defonce xtdb-node (atom nil))

;; UI state - hide completed by default
(defonce ui-state (atom {:show-completed false}))

(defn init!
  "Initialize the agents module with the XTDB node.
   Called by the server component at startup."
  [node]
  (reset! xtdb-node node)
  (log/info "Agents module initialized with XTDB node"))

(defn get-node
  "Get the XTDB node reference."
  []
  @xtdb-node)

(defn toggle-show-completed!
  "Toggle the show-completed filter."
  []
  (swap! ui-state update :show-completed not))


;;; ---------------------------------------------------------------------------
;;; XTDB Message Queries (Phase 1)
;;; ---------------------------------------------------------------------------

(defn- find-ai-session-id
  "Find the AI session ID (ses-xxx) for a given agent session ID (4-char hex).
   Returns nil if not found."
  [agent-session-id]
  (when-let [node (get-node)]
    (let [results (db/q node
                        "SELECT _id FROM ai_sessions
                         WHERE seon$ai$agent_session_id = ?
                         LIMIT 1"
                        [agent-session-id])]
      (:xt/id (first results)))))

(defn- load-session-messages
  "Load all messages for an AI session from XTDB.
   Returns messages ordered by timestamp."
  [ai-session-id]
  (when-let [node (get-node)]
    (db/q node
          "SELECT * FROM ai_messages
           WHERE seon$ai$session_id = ?
           ORDER BY seon$ai$timestamp"
          [ai-session-id])))

(defn- load-session-info
  "Load session metadata (status, timestamps, cost, initial-context) from XTDB.
   Returns map with :status, :started-at, :ended-at, :cost-usd, :initial-context."
  [ai-session-id]
  (when-let [node (get-node)]
    (first (db/q node
                 "SELECT seon$ai$status, seon$ai$started_at, seon$ai$ended_at, seon$ai$cost_usd, seon$ai$initial_context
                  FROM ai_sessions
                  WHERE _id = ?"
                 [ai-session-id]))))

(defn- aggregate-message-stats
  "Aggregate token counts and compute turns from messages.
   Returns map with :input-tokens, :output-tokens, :num-turns, :duration-ms."
  [messages]
  (let [;; Sum tokens across all messages
        input-tokens (->> messages
                          (keep ::ai/input-tokens)
                          (reduce + 0))
        output-tokens (->> messages
                           (keep ::ai/output-tokens)
                           (reduce + 0))
        ;; Count assistant messages as turns
        num-turns (->> messages
                       (filter #(= "assistant" (::ai/role %)))
                       count)
        ;; Compute duration from first to last message
        timestamps (->> messages
                        (keep ::ai/timestamp)
                        (map #(cond
                                (instance? Instant %) %
                                (instance? ZonedDateTime %) (.toInstant %)
                                :else nil))
                        (remove nil?)
                        sort)
        duration-ms (when (>= (count timestamps) 2)
                      (- (.toEpochMilli (last timestamps))
                         (.toEpochMilli (first timestamps))))]
    {:input-tokens (when (pos? input-tokens) input-tokens)
     :output-tokens (when (pos? output-tokens) output-tokens)
     :num-turns (when (pos? num-turns) num-turns)
     :duration-ms duration-ms}))

(defn- pair-tool-calls-with-results
  "Match tool calls with their results by tool_use_id.
   Modifies messages with tool-calls to include :result on each call."
  [messages]
  (let [;; Build index of tool-use-id -> result content
        results-by-id (->> messages
                           (filter ::claude/tool-results)
                           (mapcat (fn [msg]
                                     (for [r (::claude/tool-results msg)]
                                       [(:tool-use-id r) r])))
                           (into {}))]
    ;; Attach results to tool calls
    (map (fn [msg]
           (if-let [calls (::claude/tool-calls msg)]
             (assoc msg ::claude/tool-calls
                    (mapv #(assoc % :result (get results-by-id (:id %))) calls))
             msg))
         messages)))

;;; ---------------------------------------------------------------------------
;;; Message Rendering Helpers
;;; ---------------------------------------------------------------------------

(defn- format-local-time
  "Format timestamp as compact local time for display.
   Today: '14:23'
   This week: 'Mon 14:23'
   Older: 'Jan 15 14:23'
   Returns nil for nil/invalid input."
  [timestamp]
  (when timestamp
    (try
      (let [instant (cond
                      (instance? Instant timestamp) timestamp
                      (instance? ZonedDateTime timestamp) (.toInstant timestamp)
                      (string? timestamp) (Instant/parse timestamp)
                      :else nil)
            _ (when-not instant (throw (ex-info "Unknown timestamp type" {})))
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
        nil))))

(def ^:private max-preview-lines
  "Max lines to show in content preview. Higher value shows more context like Claude Code."
  8)

(defn- truncate-lines
  "Truncate content to max lines for display.
   Returns {:truncated? bool :preview string :hidden-lines int :full-content string}."
  [content max-lines]
  (if (str/blank? content)
    {:truncated? false :preview content :hidden-lines 0 :full-content content}
    (let [lines (str/split-lines content)
          total (count lines)]
      (if (> total max-lines)
        {:truncated? true
         :preview (str/join "\n" (take max-lines lines))
         :hidden-lines (- total max-lines)
         :full-content content}
        {:truncated? false
         :preview content
         :hidden-lines 0
         :full-content content}))))

(defn- detect-language
  "Detect syntax highlighting language from file path or tool name."
  [file-path tool-name]
  (cond
    ;; Clojure files
    (and file-path (re-find #"\.(clj[scx]?|edn)$" file-path)) "clojure"
    ;; JavaScript/TypeScript
    (and file-path (re-find #"\.(js|jsx|ts|tsx)$" file-path)) "javascript"
    ;; Python
    (and file-path (re-find #"\.py$" file-path)) "python"
    ;; Shell
    (and file-path (re-find #"\.(sh|bash|zsh)$" file-path)) "bash"
    ;; Markdown
    (and file-path (re-find #"\.md$" file-path)) "markdown"
    ;; Tool-based detection
    (= tool-name "Bash") "bash"
    (= tool-name "mcp__seon__eval") "clojure"
    :else nil))

(defn- pp-str
  "Pretty print a Clojure value to string."
  [v]
  (with-out-str (pprint/pprint v)))

(defn- filter-boilerplate
  "Remove common boilerplate messages from tool results.
   These messages add noise without useful information."
  [text]
  (when text
    (-> text
        (str/replace #"Todos have been modified successfully\..*?Please proceed with the current tasks if applicable\.?\s*" "")
        str/trim)))

(defn- render-task-list-result
  "Render TaskList result as formatted task list with status indicators.
   Filters out boilerplate messages like 'Todos have been modified successfully'."
  [result-text]
  (try
    ;; Filter out boilerplate message before parsing
    (let [filtered-text (filter-boilerplate result-text)
          _ (when (str/blank? filtered-text) (throw (ex-info "Empty after filter" {})))
          data (json/read-str filtered-text :key-fn keyword)
          tasks (cond
                  ;; Direct array of tasks
                  (sequential? data) data
                  ;; Wrapped in {:tasks [...]}
                  (:tasks data) (:tasks data)
                  :else nil)]
      (when (seq tasks)
        [:div {:class "space-y-0.5"}
         (for [{:keys [id subject status blockedBy]} tasks]
           (let [[indicator indicator-class]
                 (case (str status)
                   "completed" ["✓" "text-success"]
                   "in_progress" ["●" "text-warning"]
                   "pending" ["○" "text-text-400"]
                   ["?" "text-text-500"])]
             [:div {:class "flex items-center gap-2 text-xs font-mono"}
              [:span {:class "text-text-500 w-4"} id]
              [:span {:class indicator-class} indicator]
              [:span {:class "text-text-200 flex-1 truncate"} subject]
              (when (seq blockedBy)
                [:span {:class "text-text-500 text-2xs"} (str "blocked by: " (str/join ", " blockedBy))])]))]))
    (catch Exception _
      ;; Fall back to raw display on parse error
      nil)))

;;; ---------------------------------------------------------------------------
;;; Message-Centric View Components
;;; ---------------------------------------------------------------------------

(defn- extract-error-preview
  "Extract first line of error message for inline display."
  [result-text]
  (when result-text
    (let [text (str/trim result-text)
          ;; Strip <tool_use_error> tags if present
          clean-text (-> text
                         (str/replace #"^<tool_use_error>\s*" "")
                         (str/replace #"\s*</tool_use_error>$" ""))
          first-line (first (str/split-lines clean-text))]
      (when (and first-line (> (count first-line) 0))
        (if (> (count first-line) 60)
          (str (subs first-line 0 57) "...")
          first-line)))))

(defn- render-result-content
  "Render result content for expanded view with appropriate formatting."
  [result-text tool-name has-error? lang]
  (cond
    ;; REPL results - pretty print Clojure data
    (= tool-name "mcp__seon__eval")
    (let [pretty-result (try
                          (-> result-text read-string pp-str)
                          (catch Exception _ result-text))
          {:keys [truncated? preview hidden-lines]} (truncate-lines pretty-result 8)]
      [:div
       [:span {:class "text-text-500 text-2xs block mb-1"} "→ result:"]
       [:pre {:class (str "bg-base-900 p-2 rounded text-2xs overflow-x-auto font-mono "
                          (when has-error? "border border-error/30"))}
        [:code {:class "language-clojure"}
         preview
         (when truncated?
           [:details {:class "block mt-1" :data-preserve-attr "open"}
            [:summary {:class "cursor-pointer list-none text-info text-2xs hover:text-info/80"}
             (str "... " hidden-lines " more lines ▸")]
            [:code {:class "language-clojure block mt-1"}
             (subs pretty-result (count preview))]])]]])

    ;; TaskList results - formatted task list
    (= tool-name "TaskList")
    (if-let [task-list-view (render-task-list-result result-text)]
      [:div {:class "bg-base-900 p-2 rounded text-2xs"} task-list-view]
      [:pre {:class "bg-base-900 p-2 rounded text-2xs overflow-x-auto font-mono"}
       [:code result-text]])

    ;; Default: show as code block with truncation
    :else
    (let [{:keys [truncated? preview hidden-lines full-content]} (truncate-lines result-text max-preview-lines)]
      [:pre {:class (str "bg-base-900 p-2 rounded text-2xs overflow-x-auto font-mono "
                         (when has-error? "border border-error/30"))}
       [:code {:class (when lang (str "language-" lang))}
        preview
        (when truncated?
          [:details {:class "inline" :data-preserve-attr "open"}
           [:summary {:class "cursor-pointer list-none text-info hover:text-info/80 block mt-1"}
            (str "... " hidden-lines " more lines ▸")]
           [:span {:class "block mt-1"} (subs full-content (count preview))]])]])))

(defn- render-tool-call
  "Render a single tool call with its result as a unified collapsible block.

   New structure (single expand level):
   - Summary: arrow + timestamp + header + error preview + status
   - Collapsed: shows 3-line preview of input
   - Expanded: shows full input + separator + full result

   The expand-default? parameter controls initial open state."
  [{:keys [id name input result]} timestamp expand-default?]
  (let [;; Extract result text
        result-content (:content result)
        raw-result-text (cond
                          (string? result-content) result-content
                          (sequential? result-content)
                          (->> result-content
                               (filter #(= "text" (:type %)))
                               (map :text)
                               (str/join "\n"))
                          :else (str result-content))
        result-text (filter-boilerplate raw-result-text)
        ;; Check for errors
        has-error? (or (:is_error result)
                       (str/starts-with? (str/trim (str result-text)) "<tool_use_error>"))
        error-preview (when has-error? (extract-error-preview result-text))
        ;; Parse input for multimethod dispatch
        parsed-input (into {} (map (fn [[k v]] [(keyword (str/replace (clojure.core/name k) "-" "_")) v]) input))
        ;; For result display
        file-path (or (:file-path input) (:file_path input))
        lang (detect-language file-path name)]
    [:div {:class "tool-call mb-2"}
     [:details {:class "tool-call-details" :open expand-default? :data-preserve-attr "open"}
      ;; SUMMARY: Header line + preview (always visible in collapsed state)
      [:summary {:class "cursor-pointer list-none"}
       [:div {:class (str "flex items-start gap-2 py-1 px-2 rounded "
                          "hover:bg-base-800 text-xs font-mono")}
        ;; Arrow (rotates when open)
        [:span {:class "tool-arrow text-text-400 shrink-0 transition-transform duration-150"} "▶"]
        ;; Timestamp
        [:span {:class "text-text-500 shrink-0"} (format-local-time timestamp)]
        ;; Tool-specific header via new multimethod
        (agent-views/render-tool-header name parsed-input)
        ;; Error preview (inline in header)
        (when error-preview
          [:span {:class "text-error text-2xs truncate max-w-xs ml-2"}
           (str "| ✗ " error-preview)])
        ;; Status indicator (right side)
        [:span {:class (str "shrink-0 ml-auto " (if has-error? "text-error" "text-success"))}
         (if has-error? "✗" "✓")]]
       ;; Preview (clipped input, part of summary - visible when collapsed)
       [:div {:class "tool-preview ml-6 mt-1"}
        (agent-views/render-tool-preview name parsed-input)]]

      ;; EXPANDED: Full input + separator + result
      [:div {:class "tool-expanded ml-6 mt-2 pl-3 border-l-2 border-base-700"}
       ;; Full input
       [:div {:class "mb-2"}
        [:span {:class "text-text-500 text-2xs block mb-1"} "input:"]
        (agent-views/render-tool-input name parsed-input)]
       ;; Separator
       [:hr {:class "border-base-700 my-2"}]
       ;; Result
       (when (and result-text (not (str/blank? result-text)))
         [:div
          [:span {:class "text-text-500 text-2xs block mb-1"} "result:"]
          (render-result-content result-text name has-error? lang)])]]]))

(def ^:private prose-classes
  "Shared prose/markdown classes for consistent rendering."
  "prose prose-sm prose-invert max-w-none
   prose-headings:text-text-100 prose-headings:font-semibold prose-headings:mt-2 prose-headings:mb-1
   prose-p:my-1 prose-p:text-text-200
   prose-strong:text-text-100 prose-strong:font-semibold
   prose-code:text-signal prose-code:bg-base-800 prose-code:px-1 prose-code:rounded prose-code:text-xs
   prose-pre:bg-base-900 prose-pre:p-2 prose-pre:rounded prose-pre:text-xs
   prose-ul:my-1 prose-ol:my-1 prose-li:my-0")

(defn- render-assistant-text
  "Render assistant message text as markdown prose.
   Converts markdown to HTML for proper formatting of headers, bold, code, etc.
   Truncation is expandable via details element."
  [content timestamp]
  (when (and content (not (str/blank? content)))
    (let [{:keys [truncated? preview hidden-lines full-content]} (truncate-lines content max-preview-lines)
          ;; Convert markdown to HTML - markdown-clj wraps in <p> tags
          html-content (md/md-to-html-string preview)
          ;; For expansion, render full content (not remainder) to avoid breaking mid-list
          full-html (when truncated? (md/md-to-html-string full-content))]
      [:div {:class "py-2 px-3 text-text-200 text-sm font-mono"}
       [:span {:class "text-text-500 text-xs mr-2"} (format-local-time timestamp)]
       ;; Inject rendered markdown as raw HTML with prose styling
       [:div {:class prose-classes}
        (h/raw html-content)]
       ;; Expandable truncation - shows full content, not just remainder
       (when truncated?
         [:details {:class "mt-1" :data-preserve-attr "open"}
          [:summary {:class "cursor-pointer text-info text-xs hover:text-info/80"}
           (str "... " hidden-lines " more lines ▸")]
          [:div {:class (str prose-classes " mt-2")}
           (h/raw full-html)]])])))

(defn- render-result-summary
  "Render the final result/summary message with full markdown, no truncation.
   The content typically starts with '## Summary' so we don't add another label."
  [content timestamp]
  (when (and content (not (str/blank? content)))
    (let [html-content (md/md-to-html-string content)]
      [:div {:class "py-3 px-4 bg-base-850 rounded border border-success/30 mt-4"}
       [:div {:class "flex items-center gap-2 mb-2"}
        [:span {:class "text-success"} "✓"]
        [:span {:class "text-text-500 text-xs"} (format-local-time timestamp)]]
       [:div {:class prose-classes}
        (h/raw html-content)]])))

(defn- render-message
  "Render a single message based on its type.
   expand-tools? controls whether tool calls default to expanded (for first/last messages)."
  [msg expand-tools?]
  (let [role (::ai/role msg)
        content (::ai/content msg)
        timestamp (::ai/timestamp msg)
        tool-calls (::claude/tool-calls msg)
        msg-type (::claude/message-type msg)]
    (cond
      ;; Skip system messages
      (= role "system") nil

      ;; Result messages - show as full summary with no truncation
      (= msg-type "result")
      (render-result-summary content timestamp)

      ;; User messages with tool results are handled by tool-call pairing
      (and (= role "user") (seq (::claude/tool-results msg))) nil

      ;; Assistant message with tool calls
      (seq tool-calls)
      [:div
       ;; Show any text content first
       (when (and content (not (str/blank? content)))
         (render-assistant-text content timestamp))
       ;; Then each tool call (collapsed by default unless expand-tools?)
       (for [call tool-calls]
         ^{:key (:id call)}
         (render-tool-call call timestamp expand-tools?))]

      ;; Pure text assistant message
      (and (= role "assistant") content (not (str/blank? content)))
      (render-assistant-text content timestamp)

      ;; Other messages (shouldn't happen often)
      :else nil)))

(defn- render-messages-view
  "Render all messages in message-centric view.
   First and last messages have tools expanded by default; middle messages are collapsed."
  [messages]
  (let [paired-messages (pair-tool-calls-with-results messages)
        msg-count (count paired-messages)]
    [:div {:class "space-y-1"}
     (for [[idx msg] (map-indexed vector paired-messages)
           :let [is-first? (zero? idx)
                 is-last? (= idx (dec msg-count))
                 ;; First message (initial prompt) and last message expanded
                 expand-tools? (or is-first? is-last?)
                 rendered (render-message msg expand-tools?)]
           :when rendered]
       ^{:key (:xt/id msg)}
       rendered)]))

;;; ---------------------------------------------------------------------------
;;; Data Queries (private)
;;; ---------------------------------------------------------------------------

(defn- running-agents
  "Get all currently running agents from the registry."
  []
  (agent/agents {}))

(defn- completed-sessions
  "Get recent completed/failed sessions from XTDB."
  [limit]
  (when-let [node (get-node)]
    (ai/list-sessions {::ai/node node
                       ::ai/limit limit})))

(defn- message-stats-by-session
  "Get message counts and latest timestamps for all sessions in one query.
   Returns map of session-id -> {:count n :latest-ts ZonedDateTime}."
  []
  (when-let [node (get-node)]
    (let [results (db/q node
                    "SELECT seon$ai$session_id as session_id,
                            COUNT(*) as cnt,
                            MAX(seon$ai$timestamp) as latest_ts
                     FROM ai_messages
                     GROUP BY seon$ai$session_id"
                    [])]
      (into {} (map (fn [r] [(:session-id r) {:count (:cnt r)
                                               :latest-ts (:latest-ts r)}])
                    results)))))

(defn- latest-activity-ms
  "Get the latest activity timestamp in epoch milliseconds for a session.
   Returns nil if no messages found."
  [session-id message-stats]
  (when-let [stats (get message-stats session-id)]
    (when-let [ts (:latest-ts stats)]
      (.toEpochMilli (.toInstant ts)))))

(def ^:private stuck-threshold-ms
  "Milliseconds without activity before considering an agent stuck (2 minutes)."
  (* 120 1000))

(defn- compute-effective-status
  "Compute effective status for an agent, detecting stuck state from XTDB message timestamps.

   This is the SINGLE source of truth for agent status computation.
   Both the list view and detail view use this function.

   Arguments:
     session-id    - Full AI session ID (ses-xxx)
     base-status   - Status from registry or XTDB (:running, :completed, :failed, etc.)
     message-stats - Map of session-id -> {:count n :latest-ts ZonedDateTime}

   Returns:
     Effective status keyword - may return :stuck if a running agent has no
     activity for stuck-threshold-ms (2 minutes).

   Logic:
   - Terminal statuses (:completed, :failed, :interrupted) pass through unchanged
   - Running agents are checked against latest message timestamp from XTDB
   - If last message is stale (> 2 min), returns :stuck
   - If no messages found, returns base-status as-is"
  [session-id base-status message-stats]
  ;; Only apply stuck detection to running agents
  (if (= :running base-status)
    (if-let [latest-ms (latest-activity-ms session-id message-stats)]
      (let [age-ms (- (System/currentTimeMillis) latest-ms)]
        (if (> age-ms stuck-threshold-ms)
          :stuck
          base-status))
      base-status)
    ;; Non-running statuses are terminal - return as-is
    base-status))

(defn- all-agents
  "Get combined list of running agents and recent completed sessions."
  []
  (let [running (running-agents)
        running-ids (set (map ::agent/ai-session-id running))
        completed (completed-sessions 50)
        ;; Filter out sessions that are currently running or don't have agent-session-id
        completed-not-running (->> completed
                                   (remove #(running-ids (:xt/id %)))
                                   (filter ::ai/agent-session-id))
        ;; Batch fetch message stats (counts + timestamps) to avoid N+1
        msg-stats (or (message-stats-by-session) {})]
    {:running running
     :completed completed-not-running
     :message-stats msg-stats
     :show-completed (:show-completed @ui-state)}))

;;; ---------------------------------------------------------------------------
;;; HTML Components (private)
;;; ---------------------------------------------------------------------------

(defn- format-cost
  "Format cost as USD string."
  [cost]
  (if cost
    (format "$%.2f" (double cost))
    "-"))

(defn- format-tokens
  "Format token count with K suffix for thousands."
  [tokens]
  (when tokens
    (if (>= tokens 1000)
      (format "%.1fk" (/ tokens 1000.0))
      (str tokens))))

(defn- format-duration
  "Format duration-ms as human readable string."
  [duration-ms]
  (when duration-ms
    (let [seconds (quot duration-ms 1000)
          minutes (quot seconds 60)
          secs (mod seconds 60)]
      (if (pos? minutes)
        (format "%dm %ds" minutes secs)
        (format "%ds" secs)))))

(defn- agent-status-badge
  "Render a status badge with Phosphor pattern: dot + word, pulse for active states.
   Design system: 6px dot, text-xs, semantic colors from theme."
  [status]
  (let [;; Phosphor status design: [dot-color text-color label pulse?]
        [dot-class text-class label pulse?]
        (case status
          :running ["bg-signal" "text-signal" "running" true]
          :active ["bg-info" "text-info" "active" true]
          :stuck ["bg-warning" "text-warning" "stuck" false]
          :completed ["bg-success" "text-success" "done" false]
          :failed ["bg-error" "text-error" "failed" false]
          :interrupted ["bg-warning" "text-warning" "interrupted" false]
          ["bg-text-500" "text-text-500" (name (or status :unknown)) false])]
    [:span {:class "inline-flex items-center gap-1.5"}
     [:span {:class (str "w-1.5 h-1.5 rounded-full " dot-class
                         (when pulse? " animate-pulse"))}]
     [:span {:class (str "text-xs font-medium " text-class)} label]]))

(defn- agents-skeleton
  "Skeleton loading state for agents page."
  []
  [:div
   [:h1 {:class "text-base font-bold tracking-tight"} "Agent Observatory"]
   [:p {:class "text-text-400 text-xs mt-0.5 mb-4"} "Connecting..."]
   [:div {:class "bg-base-850 rounded overflow-hidden"}
    [:div {:class "p-4 border-b border-base-700"}
     [:div {:class "h-4 w-32 bg-base-700 rounded animate-skeleton"}]]
    [:div {:class "p-4"}
     (for [_ (range 3)]
       [:div {:class "flex gap-4 py-3 border-b border-base-700 last:border-0"}
        [:div {:class "h-5 w-12 bg-base-700 rounded animate-skeleton"}]
        [:div {:class "h-5 w-40 bg-base-700 rounded animate-skeleton"}]
        [:div {:class "h-5 w-20 bg-base-700 rounded animate-skeleton"}]
        [:div {:class "h-5 w-12 bg-base-700 rounded animate-skeleton"}]
        [:div {:class "h-5 w-16 bg-base-700 rounded animate-skeleton"}]])]]])

(defn- agents-table
  "Render the agents table with Phosphor terminal styling.
   Design: table over cards, monospace, dense rows, warm colors."
  [{:keys [running completed message-stats show-completed]}]
  (let [;; Build running agent rows using XTDB timestamps for sorting
        running-rows (for [agent running
                           :let [id (::agent/session-id agent)
                                 session-id (::agent/ai-session-id agent)
                                 base-status (::agent/agent-status agent)
                                 latest-ms (latest-activity-ms session-id message-stats)]]
                       {:id id
                        :namespace (::agent/namespace agent)
                        :status (compute-effective-status session-id base-status message-stats)
                        :session-id session-id
                        :provider (::agent/provider agent)
                        :type :running
                        ;; Use XTDB message timestamp for sorting, fallback to max long (newest first)
                        :sort-time (or latest-ms Long/MAX_VALUE)})
        ;; Build completed rows using XTDB timestamps
        completed-rows (for [session completed
                             :let [agent-sid (::ai/agent-session-id session)
                                   session-id (:xt/id session)
                                   started-at (::ai/started-at session)
                                   latest-ms (latest-activity-ms session-id message-stats)]]
                         {:id agent-sid
                          :namespace (::ai/namespace session)
                          :status (::ai/status session)
                          :session-id session-id
                          :cost (::ai/cost-usd session)
                          :started-at started-at
                          :type :completed
                          ;; Use XTDB message timestamp, then started-at, then 0
                          :sort-time (or latest-ms
                                         (when started-at (.toEpochMilli (.toInstant started-at)))
                                         0)})
        ;; Filter completed if hidden
        visible-completed (if show-completed completed-rows [])
        ;; Combine and sort by most recent first
        all-rows (->> (concat running-rows visible-completed)
                      (sort-by :sort-time >))]
    ;; Phosphor table: warm blacks, dense rows, monospace
    [:div {:class "bg-base-850 rounded overflow-hidden"}
     [:table {:class "w-full"}
      [:thead
       [:tr {:class "border-b border-base-700"}
        [:th {:class "text-left py-1.5 px-4 text-xs font-medium text-text-400 uppercase tracking-wider"} "ID"]
        [:th {:class "text-left py-1.5 px-4 text-xs font-medium text-text-400 uppercase tracking-wider"} "Namespace"]
        [:th {:class "text-left py-1.5 px-4 text-xs font-medium text-text-400 uppercase tracking-wider"} "Status"]
        [:th {:class "text-left py-1.5 px-4 text-xs font-medium text-text-400 uppercase tracking-wider"} "Msgs"]
        [:th {:class "text-right py-1.5 px-4 text-xs font-medium text-text-400 uppercase tracking-wider"} "Cost"]]]
      [:tbody
       (if (empty? all-rows)
         [:tr
          [:td {:class "py-8 px-4 text-center text-text-500 italic" :colspan "5"}
           "No agents found. Start an agent to see it here."]]
         (for [{:keys [id namespace status session-id cost]} all-rows]
           [:tr {:class "border-b border-base-700 hover:bg-base-800 cursor-pointer transition-colors"
                 :data-on:click (str "window.location.href='/agents/" id "'")}
            [:td {:class "py-2 px-4 font-mono text-sm font-medium text-text-50"} id]
            [:td {:class "py-2 px-4 font-mono text-sm text-text-200"} (or namespace "-")]
            [:td {:class "py-2 px-4"} (agent-status-badge status)]
            [:td {:class "py-2 px-4 font-mono text-sm text-text-400"}
             (if-let [stats (get message-stats session-id)]
               (:count stats)
               "-")]
            [:td {:class "py-2 px-4 font-mono text-sm text-text-400 text-right"}
             (format-cost cost)]]))]]]))

(defn- agents-content
  "Render the main agents page content with Phosphor styling."
  []
  (let [data (all-agents)
        running-count (count (:running data))
        completed-count (count (:completed data))
        show-completed (:show-completed data)]
    (h/html
     [:main#morph
      ;; Header
      [:div {:class "flex items-center justify-between mb-4"}
       [:div
        [:h1 {:class "text-base font-bold tracking-tight"} "Agent Observatory"]
        [:p {:class "text-text-400 text-xs mt-0.5"}
         (str running-count " running"
              (when (pos? completed-count)
                (str " ● " completed-count " completed"
                     (when-not show-completed " (hidden)"))))]]
       ;; Toggle button
       [:button {:class (str "px-3 py-1.5 text-sm font-medium rounded transition-colors "
                             (if show-completed
                               "bg-base-800 text-text-200 hover:bg-base-700"
                               "bg-base-850 text-text-400 hover:bg-base-800"))
                 :data-on:click "@post('/api/agents/toggle-completed')"}
        (if show-completed "Hide Completed" "Show Completed")]]

      ;; Agents table
      (agents-table data)])))

;;; ---------------------------------------------------------------------------
;;; Handlers
;;; ---------------------------------------------------------------------------

(defn agents-page
  "Serve the agents observatory HTML shim page."
  [_request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (html/base-page
          {:title "Agent Observatory - Seon"
           :active-page :agents
           :skeleton (agents-skeleton)})})

(defn- agents-sse-render
  "Render function for agents SSE. Defined separately so var reference
   can be used, enabling live reload without server restart."
  [_request]
  (agents-content))

;; Handler uses var reference for hot reload support
(def agents-sse
  (sse/render-handler #'agents-sse-render :poll-ms 2000))

(defn toggle-completed-handler
  "Toggle show/hide completed agents and trigger SSE refresh."
  [_request]
  (toggle-show-completed!)
  (sse/refresh-all!)
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body "{\"ok\": true}"})

;;; ---------------------------------------------------------------------------
;;; Agent Detail View
;;; ---------------------------------------------------------------------------


(defn- get-registry-status
  "Get agent status from registry. Returns :running, :completed, or nil."
  [agent-id]
  (let [running-agents (agent/agents {})]
    (when-let [a (first (filter #(= agent-id (:seon.ai.agent/session-id %)) running-agents))]
      (:seon.ai.agent/agent-status a))))


(defn- extract-current-activity
  "Extract the current activity summary from the most recent messages.
   Returns a map with :activity-type and :activity-text for display."
  [messages]
  (when (seq messages)
    (let [;; Look at the last few messages to find current activity
          recent (->> messages reverse (take 5))
          ;; Find most recent tool call or assistant message
          latest-with-activity
          (first
           (for [msg recent
                 :let [tool-calls (::claude/tool-calls msg)
                       content (::ai/content msg)
                       role (::ai/role msg)]
                 :when (or (seq tool-calls)
                           (and (= "assistant" role) (not (str/blank? content))))]
             (cond
               ;; Has tool calls - show the most recent tool
               (seq tool-calls)
               (let [tool (last tool-calls)
                     tool-name (:name tool)
                     input (:input tool)]
                 (case tool-name
                   "Edit" {:type :edit
                           :text (str "Edit " (or (:file-path input) (:file_path input) "file"))}
                   "Read" {:type :read
                           :text (str "Read " (or (:file-path input) (:file_path input) "file"))}
                   "Write" {:type :write
                            :text (str "Write " (or (:file-path input) (:file_path input) "file"))}
                   "Bash" {:type :bash
                           :text (or (:description input) "Running command")}
                   "Grep" {:type :grep
                           :text (str "Grep \"" (:pattern input) "\"")}
                   "Glob" {:type :glob
                           :text (str "Glob " (:pattern input))}
                   "mcp__seon__eval" {:type :repl
                                      :text "Evaluating in REPL"}
                   "Task" {:type :task
                           :text (or (:description input) "Launching subagent")}
                   "TaskCreate" {:type :task
                                 :text (str "Creating task: " (:subject input))}
                   "TaskUpdate" {:type :task
                                 :text (str "Updating task #" (:taskId input))}
                   ;; Default
                   {:type :tool
                    :text tool-name}))
               ;; Assistant thinking/responding
               :else
               {:type :thinking
                :text (let [preview (subs content 0 (min 60 (count content)))]
                        (if (> (count content) 60)
                          (str preview "...")
                          preview))})))]
      latest-with-activity)))

(defn- progress-summary
  "Render a sticky progress line showing current activity."
  [{:keys [status activity]}]
  (when (and activity (= status :running))
    (let [{:keys [type text]} activity
          icon (case type
                 :edit "✎"
                 :read "📖"
                 :write "✎"
                 :bash "⚡"
                 :grep "🔍"
                 :glob "📁"
                 :repl "λ"
                 :task "🔀"
                 :thinking "💭"
                 "●")]
      [:div {:class "flex items-center gap-2 text-xs font-mono bg-base-800 rounded px-3 py-1.5 mb-3"}
       [:span {:class "text-signal animate-pulse"} icon]
       [:span {:class "text-text-300"} "Working on:"]
       [:span {:class "text-text-100 truncate"} text]])))

(defn- metrics-display
  "Render metrics row for agent detail header.
   Shows tokens, turns, duration, and cost in a compact format."
  [{:keys [input-tokens output-tokens num-turns duration-ms cost]}]
  [:div {:class "flex items-center gap-4 text-xs font-mono"}
   ;; Tokens: ↑ input  ↓ output
   (when (or input-tokens output-tokens)
     [:span {:class "text-text-400"}
      (when input-tokens
        [:span {:title "Input tokens"} "↑ " (format-tokens input-tokens)])
      (when (and input-tokens output-tokens) "  ")
      (when output-tokens
        [:span {:title "Output tokens"} "↓ " (format-tokens output-tokens)])])
   ;; Turns
   (when num-turns
     [:span {:class "text-text-400" :title "Conversation turns"}
      (str "Turn " num-turns)])
   ;; Duration
   (when duration-ms
     [:span {:class "text-text-400" :title "Duration"}
      (format-duration duration-ms)])
   ;; Cost
   (when cost
     [:span {:class "text-text-200" :title "Cost"}
      (format-cost cost)])])

(defn- status-badge
  "Render a status badge with Phosphor pattern for agent detail header.
   Uses dot + word style with pulse for active states."
  [{:keys [status time-ago seconds-ago]}]
  (let [;; Phosphor status design: [dot-color text-color label pulse?]
        [dot-class text-class label pulse?]
        (case status
          :running ["bg-signal" "text-signal" "running" true]
          :completed ["bg-success" "text-success" "done" false]
          :stuck ["bg-warning" "text-warning" "stuck" false]
          :error ["bg-error" "text-error" "error" false]
          ["bg-text-500" "text-text-500" (name (or status :unknown)) false])]
    [:div {:class "flex items-center gap-3"}
     [:span {:class "inline-flex items-center gap-1.5"}
      [:span {:class (str "w-1.5 h-1.5 rounded-full " dot-class
                          (when pulse? " animate-pulse"))}]
      [:span {:class (str "text-xs font-medium " text-class)} label]]
     (when time-ago
       [:span {:class (str "text-xs " (if (and seconds-ago (> seconds-ago (quot stuck-threshold-ms 1000)))
                                         "text-warning font-medium"
                                         "text-text-400"))}
        (str "Last activity: " time-ago)])]))

;;; ---------------------------------------------------------------------------
;;; Initial Context Components
;;; ---------------------------------------------------------------------------

(defn- format-byte-size
  "Format byte count as human-readable size."
  [bytes]
  (cond
    (nil? bytes) ""
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (format "%.1f KB" (/ bytes 1024.0))
    :else (format "%.1f MB" (/ bytes (* 1024.0 1024)))))

(defn- render-file-context
  "Render a single file context as a collapsible block.
   Shows path + size in summary, full content with syntax highlighting when expanded."
  [{:keys [seon.ai/path seon.ai/content seon.ai/language seon.ai/byte-count seon.ai/read-success seon.ai/error]}]
  (let [lang (or language "")
        size-str (format-byte-size byte-count)
        ;; Generate unique ID for highlight.js targeting
        code-id (str "code-" (hash path))]
    [:details {:class "border border-base-700 rounded mb-2"
               :data-preserve-attr "open"
               ;; Trigger syntax highlighting when expanded
               :data-on-toggle (str "if(this.open){const el=document.getElementById('" code-id "');if(el&&!el.classList.contains('hljs'))hljs.highlightElement(el)}")}
     [:summary {:class "cursor-pointer px-3 py-2 bg-base-800 hover:bg-base-750 flex items-center gap-2 text-xs font-mono"}
      [:span {:class "text-text-400"} "▶"]
      [:span {:class "text-text-200 flex-1 truncate"} path]
      [:span {:class "text-text-500"} size-str]
      (when (not read-success)
        [:span {:class "text-error"} "✗"])]
     [:div {:class "p-0 max-h-96 overflow-auto"}
      (if read-success
        [:pre {:class "bg-base-900 p-3 text-2xs font-mono overflow-x-auto m-0"}
         [:code {:id code-id
                 :class (when (seq lang) (str "language-" lang))}
          content]]
        [:div {:class "p-3 text-error text-xs"}
         (str "Error reading file: " error)])]]))

(defn- render-initial-context
  "Render the initial context section with task prompt and collapsible files.
   Shows above messages in agent detail view. Task prompt and agent instructions
   are rendered as markdown."
  [initial-context]
  (when initial-context
    (let [{:keys [seon.ai/task-prompt seon.ai/files-context seon.ai/agent-instructions]} initial-context
          file-count (count files-context)]
      [:div {:class "mb-4"}
       ;; Section header
       [:div {:class "flex items-center gap-2 mb-2"}
        [:span {:class "text-xs font-semibold text-text-400 uppercase tracking-wider"} "Initial Context"]]

       ;; Task prompt (always visible, rendered as markdown)
       (when (and task-prompt (not (str/blank? task-prompt)))
         [:div {:class "bg-base-850 rounded p-3 mb-3 border border-base-700"}
          [:div {:class "text-2xs text-text-500 uppercase tracking-wider mb-1"} "Task"]
          [:div {:class prose-classes}
           (h/raw (md/md-to-html-string task-prompt))]])

       ;; Reference files (collapsed by default)
       (when (seq files-context)
         [:details {:class "mb-3" :data-preserve-attr "open"}
          [:summary {:class "cursor-pointer text-xs text-text-300 hover:text-text-200 flex items-center gap-2"}
           [:span {:class "text-text-400"} "▶"]
           [:span "Reference Files"]
           [:span {:class "text-text-500"} (str "(" file-count " file" (when (> file-count 1) "s") ")")]]
          [:div {:class "mt-2 ml-4"}
           (for [fc files-context]
             ^{:key (::ai/path fc)}
             (render-file-context fc))]])

       ;; System instructions (collapsed by default, rendered as markdown)
       (when (and agent-instructions (not (str/blank? agent-instructions)))
         [:details {:class "mb-3" :data-preserve-attr "open"}
          [:summary {:class "cursor-pointer text-xs text-text-300 hover:text-text-200 flex items-center gap-2"}
           [:span {:class "text-text-400"} "▶"]
           [:span "System Instructions"]
           [:span {:class "text-text-500"} "(AGENT.md)"]]
          [:div {:class "mt-2 ml-4 max-h-64 overflow-auto"}
           [:div {:class prose-classes}
            (h/raw (md/md-to-html-string agent-instructions))]]])])))

(def ^:private auto-scroll-script
  "JavaScript for smart auto-scroll behavior.
   Scrolls to bottom when new content arrives, but only if user is already near bottom.
   Respects user scroll position when reading history."
  "
  (function() {
    let pinnedToBottom = true;
    const THRESHOLD = 150; // pixels from bottom to consider 'pinned'

    // Track scroll state
    function updatePinned(el) {
      pinnedToBottom = (el.scrollHeight - el.scrollTop - el.clientHeight) < THRESHOLD;
    }

    // Scroll to bottom if pinned
    function maybeScroll(el) {
      if (pinnedToBottom) {
        el.scrollTop = el.scrollHeight;
      }
    }

    // Set up observer for content changes
    const observer = new MutationObserver(function(mutations) {
      const el = document.getElementById('messages-scroll');
      if (el) maybeScroll(el);
    });

    // Watch for the container to appear, then observe it
    function setupObserver() {
      const el = document.getElementById('messages-scroll');
      if (el) {
        el.addEventListener('scroll', function() { updatePinned(el); });
        observer.observe(el, { childList: true, subtree: true });
        // Initial scroll to bottom
        el.scrollTop = el.scrollHeight;
      } else {
        // Container not ready yet, try again
        setTimeout(setupObserver, 100);
      }
    }

    setupObserver();
  })();
  ")

(defn- agent-detail-skeleton
  "Skeleton for agent detail page with auto-scroll script."
  [agent-id]
  [:div
   [:div {:class "flex items-center gap-4 mb-6"}
    [:a {:href "/agents"
         :class "text-text-400 hover:text-text-200"}
     "← Back"]
    [:h1 {:class "text-3xl font-bold tracking-tight font-mono"} agent-id]]
   [:div {:class "bg-base-850 rounded p-4"}
    [:div {:class "h-4 w-32 bg-base-700 rounded animate-skeleton mb-4"}]
    (for [_ (range 10)]
      [:div {:class "h-3 w-full bg-base-700 rounded animate-skeleton my-2"}])]
   ;; Auto-scroll script - runs once on page load, sets up MutationObserver
   [:script (h/raw auto-scroll-script)]])


(defn- agent-detail-content
  "Render the agent detail page content with Phosphor terminal styling.
   Uses XTDB exclusively for message data."
  [agent-id]
  (let [;; Load data from XTDB
        ai-session-id (find-ai-session-id agent-id)
        messages (when ai-session-id (load-session-messages ai-session-id))
        session-info (when ai-session-id (load-session-info ai-session-id))
        ;; Get message stats for stuck detection
        message-stats (or (message-stats-by-session) {})
        ;; Compute aggregated metrics from messages
        agg-stats (when (seq messages) (aggregate-message-stats messages))
        ;; Compute status
        base-status (or (get-registry-status agent-id)  ; Running agent in registry
                        (::ai/status session-info)      ; Status from XTDB session
                        :unknown)
        effective-status (compute-effective-status ai-session-id base-status message-stats)
        status-info {:status effective-status
                     :time-ago nil
                     :cost (::ai/cost-usd session-info)}
        ;; Merge session cost into metrics
        metrics {:input-tokens (:input-tokens agg-stats)
                 :output-tokens (:output-tokens agg-stats)
                 :num-turns (:num-turns agg-stats)
                 :duration-ms (:duration-ms agg-stats)
                 :cost (::ai/cost-usd session-info)}
        ;; Extract current activity for progress line
        current-activity (when (seq messages) (extract-current-activity messages))
        message-count (count messages)
        ;; Extract initial context from session info
        initial-context (::ai/initial-context session-info)]
    (h/html
     [:main#morph
      ;; Header with back link and status
      [:div {:class "flex items-center justify-between mb-4"}
       [:div {:class "flex items-center gap-4"}
        [:a {:href "/agents"
             :class "text-text-400 hover:text-text-200 transition-colors"}
         "← Back"]
        [:h1 {:class "text-2xl font-semibold tracking-tight font-mono text-text-50"} agent-id]
        [:span {:class "text-text-400 text-xs"}
         (str message-count " messages")]]
       ;; Status badge on the right
       (status-badge status-info)]
      ;; Metrics row
      [:div {:class "mb-4"}
       (metrics-display metrics)]
      ;; Progress summary (only for running agents)
      (progress-summary {:status effective-status :activity current-activity})

      ;; Main content - single scroll container for initial context + messages
      [:div {:class "bg-base-900 rounded overflow-hidden"}
       (cond
         ;; Has messages - render initial context + messages in one scroll area
         (seq messages)
         [:div {:id "messages-scroll"
                :class "p-3 max-h-[calc(100vh-180px)] overflow-y-auto"}
          ;; Initial context as first item (scrolls with messages)
          (render-initial-context initial-context)
          (render-messages-view messages)]

         ;; Session exists but no messages yet (agent just started)
         ai-session-id
         [:div {:id "messages-scroll"
                :class "p-3 max-h-[calc(100vh-180px)] overflow-y-auto"}
          ;; Show initial context if available
          (render-initial-context initial-context)
          [:div {:class "p-8 text-center text-text-400"}
           [:p {:class "text-sm font-medium"} "Waiting for messages..."]
           [:p {:class "text-xs mt-2 text-text-500"} "Agent is starting up"]]]

         ;; No session found at all
         :else
         [:div {:class "p-8 text-center text-text-400"}
          [:p {:class "text-sm font-medium"} "Agent not found"]
          [:p {:class "text-xs mt-2 text-text-500"} (str "No session found for agent " agent-id)]])]])))

(defn agent-detail-page
  "Serve the agent detail HTML shim page."
  [request]
  (let [agent-id (get-in request [:path-params :agent-id])]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (html/base-page
            {:title (str "Agent " agent-id " - Seon")
             :active-page :agents
             :skeleton (agent-detail-skeleton agent-id)})}))

;; No handler cache needed - fresh handler each request guarantees current bindings.
;; This is the permanent fix for SSE live reload issues.

(defn agent-detail-sse
  "SSE handler for agent detail page - streams log updates.
   Creates a fresh handler per request to ensure current function bindings."
  [request]
  (let [agent-id (get-in request [:path-params :agent-id])
        render-fn (fn [_req] (agent-detail-content agent-id))
        handler (sse/render-handler render-fn :poll-ms 1000)]
    (handler request)))

;;; ---------------------------------------------------------------------------
;;; Custom Render for /ns/seon.web.agents
;;; ---------------------------------------------------------------------------
;;; Enables `/ns/seon.web.agents?id=XXXX` to show the same agent detail view
;;; as `/agents/XXXX`. See docs/prds/namespace-render-toggle/prd.md.

(defn render
  "Custom render for Observatory agent view.
   Called by /ns/seon.web.agents?id=session_id via seon.ns.routes.

   Params:
     :format - :html, :ai, or nil (defaults to :html)
     :id     - Agent session ID (4-char hex string), or nil for list view

   Returns HTML content for either:
   - Agent detail view (when id provided)
   - Agents list view (when no id)"
  [{:keys [format id]}]
  (if id
    ;; Show specific agent detail
    (agent-detail-content id)
    ;; Show agents list (same as /agents page)
    (agents-content)))

;;; ---------------------------------------------------------------------------
;;; Hot Reload Support
;;; ---------------------------------------------------------------------------
;;; clj-reload calls this after reloading the namespace.
;;; Recreates SSE handler objects so they use updated render functions.

(defn after-ns-reload
  "Called by clj-reload after namespace reload. Recreates list view SSE handler."
  []
  (log/debug "Recreating agents-sse handler after namespace reload")
  ;; Recreate agents-sse with current var reference
  (alter-var-root #'agents-sse
                  (constantly (sse/render-handler #'agents-sse-render :poll-ms 2000))))
