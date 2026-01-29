(ns seon.web.agents
  "Agent observatory state and handlers for /agents route.

   Combines running agents from the registry with completed sessions from XTDB
   to provide a unified view of all agent activity.

   Note: HTTP handlers follow Ring conventions (request -> response maps),
   not the map-in/map-out pattern used for domain APIs. HTML rendering
   functions are private helpers."
  (:require [taoensso.timbre :as log]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.pprint :as pprint]
            [markdown.core :as md]
            [seon.ai :as ai]
            [seon.ai.agent :as agent]
            [seon.ai.agent.views :as agent-views]
            [seon.ai.claude :as claude]
            [seon.db.node :as db]
            [seon.web.sse :as sse]
            [seon.web.html :as html]
            [dev.onionpancakes.chassis.core :as h])
  (:import [java.io File]
           [java.time Instant Duration ZonedDateTime ZoneId LocalDate]
           [java.time.format DateTimeFormatter TextStyle]
           [java.time.temporal ChronoUnit]
           [java.util Locale]))

;; XTDB node reference - initialized at startup via init!
(defonce xtdb-node (atom nil))

;; UI state - hide completed by default, type filter for agent detail
(defonce ui-state (atom {:show-completed false
                         :type-filter :all}))

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

(defn set-type-filter!
  "Set the type filter for agent detail view."
  [type-kw]
  (swap! ui-state assoc :type-filter type-kw))

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
  "Load session metadata (status, timestamps, cost) from XTDB.
   Returns map with :status, :started-at, :ended-at, :cost-usd."
  [ai-session-id]
  (when-let [node (get-node)]
    (first (db/q node
                 "SELECT seon$ai$status, seon$ai$started_at, seon$ai$ended_at, seon$ai$cost_usd
                  FROM ai_sessions
                  WHERE _id = ?"
                 [ai-session-id]))))

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

(defn- friendly-tool-name
  "Return a user-friendly display name for a tool."
  [tool-name]
  (case tool-name
    "mcp__seon__eval" "REPL"
    tool-name))

(defn- basename
  "Extract filename from path."
  [path]
  (when path
    (last (str/split path #"/"))))

(defn- truncate-path
  "Strip working directory prefix from path for cleaner display.
   /Users/sean/src/seon/src/foo.clj -> src/foo.clj"
  [path]
  (when path
    (let [cwd (str (System/getProperty "user.dir") "/")]
      (if (str/starts-with? path cwd)
        (subs path (count cwd))
        path))))

(defn- pp-str
  "Pretty print a Clojure value to string."
  [v]
  (with-out-str (pprint/pprint v)))

;;; ---------------------------------------------------------------------------
;;; Message-Centric View Components
;;; ---------------------------------------------------------------------------

(defn- render-tool-call
  "Render a single tool call with its result as a collapsible block.
   Uses <details> elements for expandable sections that can be toggled."
  [{:keys [id name input result]} timestamp]
  (let [file-path (or (:file-path input) (:file_path input))
        display-name (friendly-tool-name name)
        ;; Truncate paths to project-relative for cleaner display
        target (or (truncate-path file-path)
                   (:pattern input)
                   (when-let [cmd (:command input)]
                     (if (> (count cmd) 50)
                       (str (subs cmd 0 47) "...")
                       cmd))
                   (when-let [code (:code input)]
                     (let [first-line (first (str/split-lines code))]
                       (if (> (count first-line) 50)
                         (str (subs first-line 0 47) "...")
                         first-line))))
        result-content (:content result)
        result-text (cond
                      (string? result-content) result-content
                      (sequential? result-content)
                      (->> result-content
                           (filter #(= "text" (:type %)))
                           (map :text)
                           (str/join "\n"))
                      :else (str result-content))
        has-error? (or (:is_error result)
                       (str/includes? (str result-text) "<tool_use_error>"))
        lang (detect-language file-path name)
        {:keys [truncated? preview hidden-lines full-content]} (truncate-lines result-text max-preview-lines)
        ;; For REPL calls, show code input as well
        is-repl? (= name "mcp__seon__eval")
        repl-code (:code input)]
    [:div {:class "mb-2"}
     ;; Tool call - expanded by default like Claude Code
     [:details {:class "group" :open true :data-preserve-attr "open"}
      [:summary {:class (str "cursor-pointer list-none flex items-center gap-2 py-1 px-2 rounded "
                             "hover:bg-base-800 text-xs font-mono")}
       ;; Expand indicator (rotated when open)
       [:span {:class "text-text-400 group-open:rotate-90 transition-transform"} "▶"]
       ;; Timestamp
       [:span {:class "text-text-500"} (format-local-time timestamp)]
       ;; Tool name (friendly name for REPL)
       [:span {:class (if is-repl? "text-eval font-medium" "text-log-tool font-medium")} display-name]
       ;; Target
       (when target
         [:span {:class "text-text-200 truncate max-w-md"} target])
       ;; Status indicator
       [:span {:class (if has-error? "text-error" "text-success") :title (if has-error? "Error" "Success")}
        (if has-error? "✗" "✓")]]
      ;; Expanded content
      [:div {:class "ml-6 mt-1 pl-3 border-l-2 border-base-700"}
       ;; For REPL calls, show the code input
       (when (and is-repl? repl-code (not (str/blank? repl-code)))
         (let [{:keys [truncated? preview hidden-lines full-content]} (truncate-lines repl-code max-preview-lines)]
           [:div {:class "mb-2"}
            [:pre {:class "bg-eval/10 p-2 rounded text-2xs overflow-x-auto font-mono"}
             [:code {:class "language-clojure"}
              preview
              (when truncated?
                ;; Expandable "... N more lines" using nested details
                [:details {:class "inline" :data-preserve-attr "open"}
                 [:summary {:class "cursor-pointer list-none text-info hover:text-info/80"}
                  (str "... " hidden-lines " more lines ▸")]
                 [:span {:class "block mt-1"} (subs full-content (count preview))]])]]]))
       ;; Input (for Edit, show old/new strings)
       (when (= name "Edit")
         (let [old-str (:old_string input)
               new-str (:new_string input)]
           [:div {:class "mb-2 space-y-1"}
            (when (and old-str (not (str/blank? old-str)))
              (let [{:keys [truncated? preview hidden-lines full-content]} (truncate-lines old-str max-preview-lines)]
                [:pre {:class "bg-error/10 p-2 rounded text-2xs overflow-x-auto font-mono"}
                 [:code {:class (when lang (str "language-" lang))}
                  [:span {:class "text-error font-medium"} "- "]
                  preview
                  (when truncated?
                    [:details {:class "inline" :data-preserve-attr "open"}
                     [:summary {:class "cursor-pointer list-none text-info hover:text-info/80"}
                      (str "... " hidden-lines " more lines ▸")]
                     [:span {:class "block mt-1"} (subs full-content (count preview))]])]]))
            (when (and new-str (not (str/blank? new-str)))
              (let [{:keys [truncated? preview hidden-lines full-content]} (truncate-lines new-str max-preview-lines)]
                [:pre {:class "bg-success/10 p-2 rounded text-2xs overflow-x-auto font-mono"}
                 [:code {:class (when lang (str "language-" lang))}
                  [:span {:class "text-success font-medium"} "+ "]
                  preview
                  (when truncated?
                    [:details {:class "inline" :data-preserve-attr "open"}
                     [:summary {:class "cursor-pointer list-none text-info hover:text-info/80"}
                      (str "... " hidden-lines " more lines ▸")]
                     [:span {:class "block mt-1"} (subs full-content (count preview))]])]]))]))
       ;; Result preview (shown for all tools, styled consistently)
       (when (and result-text (not (str/blank? result-text)))
         [:div {:class "mt-1"}
          (when is-repl?
            [:span {:class "text-text-500 text-2xs block mb-1"} "→ result:"])
          [:pre {:class (str "bg-base-900 p-2 rounded text-2xs overflow-x-auto font-mono "
                             (when has-error? "border border-error/30"))}
           [:code {:class (when lang (str "language-" lang))}
            preview
            (when truncated?
              ;; Expandable result section
              [:details {:class "inline" :data-preserve-attr "open"}
               [:summary {:class "cursor-pointer list-none text-info hover:text-info/80 block mt-1"}
                (str "... " hidden-lines " more lines ▸")]
               [:span {:class "block mt-1"} (subs full-content (count preview))]])]]])]]]))

(defn- render-assistant-text
  "Render assistant message text as markdown prose.
   Converts markdown to HTML for proper formatting of headers, bold, code, etc."
  [content timestamp]
  (when (and content (not (str/blank? content)))
    (let [{:keys [truncated? preview hidden-lines]} (truncate-lines content max-preview-lines)
          ;; Convert markdown to HTML - markdown-clj wraps in <p> tags
          html-content (md/md-to-html-string preview)]
      [:div {:class "py-2 px-3 text-text-200 text-sm font-mono"}
       [:span {:class "text-text-500 text-xs mr-2"} (format-local-time timestamp)]
       ;; Inject rendered markdown as raw HTML with prose styling
       [:div {:class "prose prose-sm prose-invert max-w-none
                      prose-headings:text-text-100 prose-headings:font-semibold prose-headings:mt-2 prose-headings:mb-1
                      prose-p:my-1 prose-p:text-text-200
                      prose-strong:text-text-100 prose-strong:font-semibold
                      prose-code:text-signal prose-code:bg-base-800 prose-code:px-1 prose-code:rounded prose-code:text-xs
                      prose-pre:bg-base-900 prose-pre:p-2 prose-pre:rounded prose-pre:text-xs
                      prose-ul:my-1 prose-ol:my-1 prose-li:my-0"}
        (h/raw html-content)]
       (when truncated?
         [:span {:class "text-text-500 text-xs ml-1"} (str "... " hidden-lines " more lines")])])))

(defn- render-message
  "Render a single message based on its type."
  [msg]
  (let [role (::ai/role msg)
        content (::ai/content msg)
        timestamp (::ai/timestamp msg)
        tool-calls (::claude/tool-calls msg)
        msg-type (::claude/message-type msg)]
    (cond
      ;; Skip system messages
      (= role "system") nil

      ;; Skip result messages (final summary)
      (= msg-type "result") nil

      ;; User messages with tool results are handled by tool-call pairing
      (and (= role "user") (seq (::claude/tool-results msg))) nil

      ;; Assistant message with tool calls
      (seq tool-calls)
      [:div
       ;; Show any text content first
       (when (and content (not (str/blank? content)))
         (render-assistant-text content timestamp))
       ;; Then each tool call
       (for [call tool-calls]
         ^{:key (:id call)}
         (render-tool-call call timestamp))]

      ;; Pure text assistant message
      (and (= role "assistant") content (not (str/blank? content)))
      (render-assistant-text content timestamp)

      ;; Other messages (shouldn't happen often)
      :else nil)))

(defn- render-messages-view
  "Render all messages in message-centric view."
  [messages]
  (let [paired-messages (pair-tool-calls-with-results messages)]
    [:div {:class "space-y-1"}
     (for [msg paired-messages
           :let [rendered (render-message msg)]
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

(defn- message-counts-by-session
  "Get message counts for all sessions in one query."
  []
  (when-let [node (get-node)]
    (let [results (db/q node
                    "SELECT seon$ai$session_id as session_id, COUNT(*) as cnt
                     FROM ai_messages
                     GROUP BY seon$ai$session_id"
                    [])]
      (into {} (map (fn [r] [(:session-id r) (:cnt r)]) results)))))

(defn- log-file-mtime
  "Get modification time of an agent's log file, or nil if not found."
  [agent-id]
  (let [f (io/file (str "logs/agents/" agent-id ".log"))]
    (when (.exists f)
      (.lastModified f))))

(def ^:private stuck-threshold-ms
  "Milliseconds without activity before considering an agent stuck (2 minutes)."
  (* 120 1000))

(defn- compute-effective-status
  "Compute effective status for an agent, detecting stuck state from log activity.

   This is the SINGLE source of truth for agent status computation.
   Both the list view and detail view use this function.

   Arguments:
     agent-id    - 4-char hex session ID
     base-status - Status from registry or XTDB (:running, :completed, :failed, etc.)

   Returns:
     Effective status keyword - may return :stuck if a running agent has no
     activity for stuck-threshold-ms (2 minutes).

   Logic:
   - Terminal statuses (:completed, :failed, :interrupted) pass through unchanged
   - Running agents are checked against log file modification time
   - If log file is stale (> 2 min), returns :stuck
   - If no log file found, returns base-status as-is"
  [agent-id base-status]
  ;; Only apply stuck detection to running agents
  (if (= :running base-status)
    (if-let [mtime (log-file-mtime agent-id)]
      (let [age-ms (- (System/currentTimeMillis) mtime)]
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
        ;; Batch fetch message counts to avoid N+1
        msg-counts (message-counts-by-session)]
    {:running running
     :completed completed-not-running
     :message-counts (or msg-counts {})
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
  [{:keys [running completed message-counts show-completed]}]
  (let [;; Build running agent rows with log file mtime for sorting
        running-rows (for [agent running
                           :let [id (::agent/session-id agent)
                                 base-status (::agent/agent-status agent)]]
                       {:id id
                        :namespace (::agent/namespace agent)
                        :status (compute-effective-status id base-status)
                        :session-id (::agent/ai-session-id agent)
                        :provider (::agent/provider agent)
                        :type :running
                        ;; Use log file mtime for sorting, fallback to max long
                        :sort-time (or (log-file-mtime id) Long/MAX_VALUE)})
        ;; Build completed rows - use agent-session-id for log file lookup
        completed-rows (for [session completed
                             :let [;; Use the stored agent session ID (4-char hex) for log files
                                   agent-sid (::ai/agent-session-id session)
                                   started-at (::ai/started-at session)]]
                         {:id agent-sid
                          :namespace (::ai/namespace session)
                          :status (::ai/status session)
                          :session-id (:xt/id session)
                          :cost (::ai/cost-usd session)
                          :started-at started-at
                          :type :completed
                          ;; Use log file mtime, then started-at, then 0
                          :sort-time (or (when agent-sid (log-file-mtime agent-sid))
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
             (if-let [cnt (get message-counts session-id)]
               cnt
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

(defn- parse-form-body
  "Parse form-urlencoded body into a map."
  [body-str]
  (when body-str
    (into {}
          (for [pair (clojure.string/split body-str #"&")
                :let [[k v] (clojure.string/split pair #"=" 2)]]
            [(keyword (java.net.URLDecoder/decode k "UTF-8"))
             (java.net.URLDecoder/decode (or v "") "UTF-8")]))))

(defn type-filter-handler
  "Set the type filter for agent detail view and trigger SSE refresh."
  [request]
  (try
    (let [body-str (slurp (:body request))
          params (parse-form-body body-str)
          type-str (:type params)
          type-kw (keyword type-str)]
      (set-type-filter! type-kw)
      (sse/refresh-all!)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body "{\"ok\": true}"})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (str "{\"error\": \"" (.getMessage e) "\"}")})))

;;; ---------------------------------------------------------------------------
;;; Agent Detail View
;;; ---------------------------------------------------------------------------

(defn- valid-agent-id?
  "Validate agent-id is a safe hex string (4 chars).
   Prevents path traversal attacks."
  [agent-id]
  (and (string? agent-id)
       (re-matches #"[a-f0-9]{4}" agent-id)))

(defn- read-agent-log
  "Read the last N lines from an agent's log file.
   Agent ID must be a valid 4-char hex string to prevent path traversal."
  [agent-id max-lines]
  (if-not (valid-agent-id? agent-id)
    {:lines [] :exists false :error "Invalid agent ID format"}
    (let [log-path (str "logs/agents/" agent-id ".log")
          f (io/file log-path)]
      (if (.exists f)
        (try
          (let [result (shell/sh "tail" "-n" (str max-lines) log-path)]
            (if (zero? (:exit result))
              {:lines (str/split-lines (:out result))
               :exists true}
              {:lines [] :exists true :error (:err result)}))
          (catch Exception e
            {:lines [] :exists true :error (.getMessage e)}))
        {:lines [] :exists false}))))

(defn- parse-log-line
  "Parse an agent log line into structured data.
   Format: 2026-01-20T13:23:20Z | TYPE | details..."
  [line]
  (when (and line (string? line) (not (str/blank? line)))
    (let [parts (str/split line #" \| " 3)]
      (if (>= (count parts) 2)
        {:timestamp (first parts)
         :type (str/trim (second parts))
         :details (if (>= (count parts) 3) (nth parts 2) "")
         :raw line}
        {:raw line}))))

(def ^:private preview-length
  "Max chars to show before truncating with expand option."
  120)

(defn- parse-log-timestamp
  "Parse ISO timestamp from log line. Returns Instant or nil."
  [timestamp-str]
  (try
    (Instant/parse (str timestamp-str))
    (catch Exception _ nil)))

(defn- format-time-ago
  "Format duration as human-readable time ago string."
  [^Duration duration]
  (let [seconds (.toSeconds duration)
        minutes (quot seconds 60)
        hours (quot minutes 60)]
    (cond
      (< seconds 60) (str seconds "s ago")
      (< minutes 60) (str minutes "m ago")
      :else (str hours "h " (mod minutes 60) "m ago"))))

(defn- get-registry-status
  "Get agent status from registry. Returns :running, :completed, or nil."
  [agent-id]
  (let [running-agents (agent/agents {})]
    (when-let [a (first (filter #(= agent-id (:seon.ai.agent/session-id %)) running-agents))]
      (:seon.ai.agent/agent-status a))))

(defn- compute-detail-status
  "Compute status info for agent detail view.

   Uses compute-effective-status for the actual status determination,
   ensuring consistency with the list view. Adds display-only fields
   like time-ago for the detail UI.

   Returns {:status :running/:completed/:stuck/:unknown
            :last-activity Instant
            :last-type string
            :time-ago string
            :seconds-ago number}"
  [parsed-lines agent-id]
  (let [last-line (last parsed-lines)
        last-timestamp (when last-line (parse-log-timestamp (:timestamp last-line)))
        now (Instant/now)
        duration (when last-timestamp (Duration/between last-timestamp now))
        seconds-ago (when duration (.toSeconds duration))
        ;; Check for terminal status in logs first
        log-terminal-status (cond
                              (some #(= "COMPLETE" (:type %)) parsed-lines) :completed
                              (some #(= "ERROR" (:type %)) parsed-lines) :error
                              :else nil)
        ;; Get base status from registry or log terminal status
        registry-status (get-registry-status agent-id)
        base-status (or log-terminal-status registry-status :unknown)
        ;; Use unified status computation (handles stuck detection)
        final-status (compute-effective-status agent-id base-status)]
    {:status final-status
     :last-activity last-timestamp
     :last-type (:type last-line)
     :time-ago (when duration (format-time-ago duration))
     :seconds-ago seconds-ago}))

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

(def ^:private log-types
  "Valid log types for filtering."
  ["LAUNCH" "MESSAGE" "TOOL" "RESULT" "HOOK" "COMPLETE" "ERROR"])

;;; ---------------------------------------------------------------------------
;;; TOOL+RESULT Pairing (Phase 1b.6)
;;; ---------------------------------------------------------------------------

(defn- get-tool-name
  "Extract tool name from log line details.
   TOOL format: 'ToolName | {input...}'
   RESULT format: 'ToolName | output...'"
  [line]
  (when-let [details (:details line)]
    (first (str/split details #" \| " 2))))

(defn- result-indicates-error?
  "Check if a RESULT line indicates an error.
   Looks for specific error markers, not just 'error' anywhere in content."
  [result-line]
  (when-let [details (:details result-line)]
    (or
     ;; Claude tool error wrapper
     (str/includes? details "<tool_use_error>")
     ;; Error at start of result output (after tool name)
     ;; Format: "ToolName | \"Error: ..." or "ToolName | Error: ..."
     (re-find #"\| \"*Error:" details)
     ;; is_error field in JSON results
     (str/includes? details "\"is_error\": true")
     (str/includes? details "\"is_error\":true"))))

(defn- pair-tool-results
  "Group consecutive TOOL/RESULT pairs by tool name.
   Returns a seq of items where paired entries become:
   {:type :tool-with-result :tool {...} :result {...} :success? bool}

   Non-paired entries pass through unchanged.

   A TOOL is paired with the immediately following RESULT if:
   1. The RESULT comes right after the TOOL (no other entries between)
   2. The tool names match"
  [parsed-lines]
  (loop [lines parsed-lines
         result []
         pending-tool nil]
    (if-let [line (first lines)]
      (let [line-type (:type line)]
        (cond
          ;; TOOL line - save as pending
          (= "TOOL" line-type)
          (recur (rest lines)
                 ;; If there was a previous pending tool, emit it unpaired
                 (if pending-tool (conj result pending-tool) result)
                 line)

          ;; RESULT line - try to pair with pending tool
          (and (= "RESULT" line-type) pending-tool)
          (let [tool-name (get-tool-name pending-tool)
                result-name (get-tool-name line)]
            (if (= tool-name result-name)
              ;; Names match - create paired entry
              (recur (rest lines)
                     (conj result {:type :tool-with-result
                                   :tool pending-tool
                                   :result line
                                   :success? (not (result-indicates-error? line))})
                     nil)
              ;; Names don't match - emit both separately
              (recur (rest lines)
                     (conj result pending-tool line)
                     nil)))

          ;; Other line type - emit pending tool if any, then this line
          :else
          (recur (rest lines)
                 (if pending-tool
                   (conj result pending-tool line)
                   (conj result line))
                 nil)))
      ;; Done - emit any remaining pending tool
      (if pending-tool (conj result pending-tool) result))))

(defn- log-line-component
  "Render a single log line with Phosphor terminal styling.
   Long details use HTML <details>/<summary> for native expand/collapse.

   TOOL entries use tool-specific renderers from seon.ai.agent.views
   that parse the input EDN and show rich formatting (diffs, code, etc).

   Design system: py-0.5 spacing, text-xs (11px), log-type colors,
   text-text-50 for content, text-text-400 for timestamps."
  [{:keys [timestamp type details raw]} _line-idx]
  (let [type-class (case type
                     "LAUNCH" "text-log-launch font-semibold"
                     "MESSAGE" "text-log-message"
                     "TOOL" "text-log-tool"
                     "RESULT" "text-log-result"
                     "HOOK" "text-log-hook"
                     "COMPLETE" "text-log-done font-semibold"
                     "ERROR" "text-log-error font-semibold"
                     "text-text-400")
        long? (and details (> (count details) preview-length))]
    [:div {:class "font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 last:border-0 hover:bg-base-800"}
     (if timestamp
       [:div {:class "flex gap-2 items-start"}
        [:span {:class "text-text-400 shrink-0"} timestamp]
        [:span {:class (str "shrink-0 w-16 " type-class)} type]
        (cond
          ;; TOOL entries: parse the "ToolName | {input}" format and use rich renderer
          (= type "TOOL")
          (let [[tool-name input] (str/split details #" \| " 2)
                parsed (agent-views/parse-tool-input input)]
            (agent-views/render-tool-html tool-name parsed input))

          ;; Long content: use expandable details
          long?
          [:details {:class "text-text-50 inline min-w-0"
                     :data-preserve-attr "open"}
           [:summary {:class "cursor-pointer list-none"}
            (subs details 0 preview-length)
            [:span {:class "text-info ml-1"} (str "+" (- (count details) preview-length) " more ▸")]]
           [:div {:class "break-all mt-1 pl-2 border-l-2 border-base-700"}
            details]]

          ;; Short content - no expand needed
          :else
          [:span {:class "text-text-50 break-all"} details])]
       [:span {:class "text-text-400"} raw])]))

(defn- paired-log-line-component
  "Render a paired TOOL+RESULT entry as a single line with success indicator.
   Shows the tool call with a ✓ (success) or ✗ (error) indicator."
  [{:keys [tool result success?]} _line-idx]
  (let [{:keys [timestamp details]} tool
        [tool-name input] (str/split details #" \| " 2)
        parsed (agent-views/parse-tool-input input)]
    [:div {:class "font-mono text-xs leading-tight py-0.5 border-b border-base-700/50 last:border-0 hover:bg-base-800"}
     [:div {:class "flex gap-2 items-start"}
      [:span {:class "text-text-400 shrink-0"} timestamp]
      [:span {:class "shrink-0 w-16 text-log-tool"} "TOOL"]
      ;; Tool content with success/error indicator
      [:div {:class "flex-1 min-w-0 flex items-start gap-2"}
       (agent-views/render-tool-html tool-name parsed input)
       [:span {:class (str "shrink-0 " (if success? "text-success" "text-error"))}
        (if success? "✓" "✗")]]]]))

(defn- render-log-item
  "Render a log item, handling both regular lines and paired TOOL+RESULT entries."
  [item idx]
  (if (= :tool-with-result (:type item))
    (paired-log-line-component item idx)
    (log-line-component item idx)))

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

(defn- type-filter-button
  "Render a type filter button with Phosphor log-type colors."
  [type-str current-filter]
  (let [selected? (or (= current-filter :all)
                      (= current-filter (keyword type-str)))
        type-class (case type-str
                     "LAUNCH" "text-log-launch border-log-launch"
                     "MESSAGE" "text-log-message border-log-message"
                     "TOOL" "text-log-tool border-log-tool"
                     "RESULT" "text-log-result border-log-result"
                     "HOOK" "text-log-hook border-log-hook"
                     "COMPLETE" "text-log-done border-log-done"
                     "ERROR" "text-log-error border-log-error"
                     "text-text-400 border-base-700")]
    [:button {:class (str "px-2 py-1 text-xs font-mono rounded border transition-colors "
                          (if selected?
                            (str type-class " bg-base-800")
                            "text-text-500 border-base-700 hover:border-base-600"))
              :data-on-click (str "@post('/api/agents/type-filter', {contentType: 'form', body: {type: '" type-str "'}})")}
     type-str]))

(defn- agent-detail-content
  "Render the agent detail page content with Phosphor terminal styling.
   Uses XTDB to load messages when available, falls back to log files."
  [agent-id]
  (let [;; Try XTDB first
        ai-session-id (find-ai-session-id agent-id)
        messages (when ai-session-id (load-session-messages ai-session-id))
        use-xtdb? (seq messages)
        ;; Fall back to log files if no XTDB data
        {:keys [lines exists error]} (when-not use-xtdb? (read-agent-log agent-id 200))
        parsed-lines (when-not use-xtdb? (keep parse-log-line lines))
        ;; Get session info from XTDB for status
        session-info (when use-xtdb? (load-session-info ai-session-id))
        ;; Compute status using unified function for both XTDB and log file paths
        log-status (cond
                     use-xtdb?
                     (let [base-status (or (get-registry-status agent-id)  ; Running agent?
                                           (::ai/status session-info)      ; XTDB status
                                           :unknown)]
                       {:status (compute-effective-status agent-id base-status)
                        :time-ago nil
                        :cost (::ai/cost-usd session-info)})
                     (seq parsed-lines)
                     (compute-detail-status parsed-lines agent-id))
        ;; For log file fallback
        type-filter (or (:type-filter @ui-state) :all)
        filtered-lines (when-not use-xtdb?
                         (if (= type-filter :all)
                           parsed-lines
                           (filter #(= (name type-filter) (:type %)) parsed-lines)))
        display-items (when-not use-xtdb?
                        (if (= type-filter :all)
                          (pair-tool-results filtered-lines)
                          filtered-lines))
        message-count (if use-xtdb? (count messages) (count parsed-lines))]
    (h/html
     [:main#morph
      ;; Header with back link and status
      [:div {:class "flex items-center justify-between mb-6"}
       [:div {:class "flex items-center gap-4"}
        [:a {:href "/agents"
             :class "text-text-400 hover:text-text-200 transition-colors"}
         "← Back"]
        [:h1 {:class "text-2xl font-semibold tracking-tight font-mono text-text-50"} agent-id]
        [:span {:class "text-text-400 text-xs"}
         (str message-count (if use-xtdb? " messages" " lines"))]]
       ;; Status badge on the right
       (when log-status
         (status-badge log-status))]

      ;; Type filter controls - only for log file fallback
      (when (and (not use-xtdb?) (seq parsed-lines))
        [:div {:class "bg-base-850 rounded p-3 mb-4 flex flex-wrap gap-2 items-center"}
         [:span {:class "text-text-400 text-xs mr-2"} "Filter:"]
         [:button {:class (str "px-2 py-1 text-xs font-mono rounded border transition-colors "
                               (if (= type-filter :all)
                                 "text-text-200 border-base-600 bg-base-800"
                                 "text-text-500 border-base-700 hover:border-base-600"))
                   :data-on-click "@post('/api/agents/type-filter', {contentType: 'form', body: {type: 'all'}})"}
          "ALL"]
         (for [t log-types]
           (type-filter-button t type-filter))])

      ;; Main content - XTDB messages or log file fallback
      [:div {:class "bg-base-900 rounded overflow-hidden"}
       (cond
         ;; XTDB message-centric view
         use-xtdb?
         [:div {:id "messages-scroll"
                :class "p-3 max-h-[70vh] overflow-y-auto"}
          (render-messages-view messages)]

         ;; Log file fallback - file not found
         (and (not use-xtdb?) (not exists))
         [:div {:class "p-8 text-center text-text-400"}
          [:p {:class "text-sm font-medium"} "No messages found"]
          [:p {:class "text-xs mt-2 text-text-500"} (str "Agent " agent-id " has no XTDB messages")]]

         ;; Log file fallback - error
         error
         [:div {:class "p-4 bg-error/10 text-error"}
          [:p {:class "font-medium text-sm"} "Error reading log"]
          [:p {:class "text-xs"} error]]

         ;; Log file fallback - empty
         (empty? display-items)
         [:div {:class "p-8 text-center text-text-400"}
          (if (= type-filter :all)
            "No log entries yet"
            (str "No " (name type-filter) " entries"))]

         ;; Log file fallback - render log items
         :else
         [:div {:class "p-3 max-h-[70vh] overflow-y-auto flex flex-col-reverse"}
          [:div
           (map-indexed (fn [idx item] (render-log-item item idx)) display-items)]])]])))

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

;; Agent detail SSE uses a different pattern - it needs per-request state (agent-id).
;; We create handlers dynamically but cache them to enable hot reload.
(defonce ^:private agent-detail-handlers (atom {}))

(defn- get-agent-detail-handler
  "Get or create SSE handler for agent detail page.
   Handlers are cached per agent-id for connection reuse."
  [agent-id]
  (if-let [handler (get @agent-detail-handlers agent-id)]
    handler
    (let [;; Create render fn that closes over agent-id
          render-fn (fn [_req] (agent-detail-content agent-id))
          handler (sse/render-handler render-fn :poll-ms 1000)]
      (swap! agent-detail-handlers assoc agent-id handler)
      handler)))

(defn agent-detail-sse
  "SSE handler for agent detail page - streams log updates.
   Polls every 1 second to show new log lines in real-time."
  [request]
  (let [agent-id (get-in request [:path-params :agent-id])
        handler (get-agent-detail-handler agent-id)]
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
  "Called by clj-reload after namespace reload. Recreates SSE handlers."
  []
  ;; Recreate agents-sse with current var reference
  (alter-var-root #'agents-sse
                  (constantly (sse/render-handler #'agents-sse-render :poll-ms 2000)))
  ;; Clear agent detail handlers cache - they'll be recreated on next request
  (reset! agent-detail-handlers {}))
