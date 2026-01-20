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
            [seon.ai :as ai]
            [seon.ai.agent :as agent]
            [seon.db.node :as db]
            [seon.web.sse :as sse]
            [seon.web.html :as html]
            [dev.onionpancakes.chassis.core :as h])
  (:import [java.io File]))

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
  "Render a status badge for an agent."
  [status]
  (let [base-class "inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium"
        [icon-class text-class text] (case status
                                       :running ["bg-violet-500" "bg-violet-100 text-violet-700" "Running"]
                                       :completed ["bg-green-500" "bg-green-100 text-green-700" "Completed"]
                                       :failed ["bg-red-500" "bg-red-100 text-red-700" "Failed"]
                                       :interrupted ["bg-amber-500" "bg-amber-100 text-amber-700" "Interrupted"]
                                       :active ["bg-blue-500" "bg-blue-100 text-blue-700" "Active"]
                                       ["bg-zinc-500" "bg-zinc-100 text-zinc-700" (name (or status :unknown))])]
    [:span {:class (str base-class " " text-class)}
     [:span {:class (str "w-2 h-2 rounded-full " icon-class)}]
     text]))

(defn- agents-skeleton
  "Skeleton loading state for agents page."
  []
  [:div
   [:h1 {:class "text-3xl font-bold tracking-tight"} "Agent Observatory"]
   [:p {:class "text-zinc-500 text-sm mt-1 mb-6"} "Connecting..."]
   [:div {:class "bg-white rounded-lg shadow-sm overflow-hidden"}
    [:div {:class "p-4 border-b border-zinc-100"}
     [:div {:class "h-4 w-32 bg-zinc-200 rounded animate-skeleton"}]]
    [:div {:class "p-4"}
     (for [_ (range 3)]
       [:div {:class "flex gap-4 py-3 border-b border-zinc-100 last:border-0"}
        [:div {:class "h-5 w-12 bg-zinc-200 rounded animate-skeleton"}]
        [:div {:class "h-5 w-40 bg-zinc-200 rounded animate-skeleton"}]
        [:div {:class "h-5 w-20 bg-zinc-200 rounded animate-skeleton"}]
        [:div {:class "h-5 w-12 bg-zinc-200 rounded animate-skeleton"}]
        [:div {:class "h-5 w-16 bg-zinc-200 rounded animate-skeleton"}]])]]])

(defn- agents-table
  "Render the agents table."
  [{:keys [running completed message-counts show-completed]}]
  (let [;; Build running agent rows with log file mtime for sorting
        running-rows (for [agent running
                           :let [id (::agent/session-id agent)]]
                       {:id id
                        :namespace (::agent/namespace agent)
                        :status (::agent/agent-status agent)
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
    [:div {:class "bg-white rounded-lg shadow-sm overflow-hidden"}
     [:table {:class "w-full"}
      [:thead
       [:tr {:class "bg-zinc-50 border-b border-zinc-200"}
        [:th {:class "text-left py-3 px-4 text-xs font-semibold text-zinc-500 uppercase tracking-wider"} "ID"]
        [:th {:class "text-left py-3 px-4 text-xs font-semibold text-zinc-500 uppercase tracking-wider"} "Namespace"]
        [:th {:class "text-left py-3 px-4 text-xs font-semibold text-zinc-500 uppercase tracking-wider"} "Status"]
        [:th {:class "text-left py-3 px-4 text-xs font-semibold text-zinc-500 uppercase tracking-wider"} "Messages"]
        [:th {:class "text-left py-3 px-4 text-xs font-semibold text-zinc-500 uppercase tracking-wider"} "Cost"]]]
      [:tbody
       (if (empty? all-rows)
         [:tr
          [:td {:class "py-8 px-4 text-center text-zinc-500 italic" :colspan "5"}
           "No agents found. Start an agent to see it here."]]
         (for [{:keys [id namespace status session-id cost]} all-rows]
           [:tr {:class "border-b border-zinc-100 hover:bg-zinc-50 cursor-pointer transition-colors"
                 :data-on:click (str "window.location.href='/agents/" id "'")}
            [:td {:class "py-3 px-4 font-mono text-sm font-medium text-zinc-900"} id]
            [:td {:class "py-3 px-4 font-mono text-sm text-zinc-600"} (or namespace "-")]
            [:td {:class "py-3 px-4"} (agent-status-badge status)]
            [:td {:class "py-3 px-4 font-mono text-sm text-zinc-600"}
             (if-let [cnt (get message-counts session-id)]
               cnt
               "-")]
            [:td {:class "py-3 px-4 font-mono text-sm text-zinc-600"}
             (format-cost cost)]]))]]]))

(defn- agents-content
  "Render the main agents page content."
  []
  (let [data (all-agents)
        running-count (count (:running data))
        completed-count (count (:completed data))
        show-completed (:show-completed data)]
    (h/html
     [:main#morph
      ;; Header
      [:div {:class "flex items-center justify-between mb-6"}
       [:div
        [:h1 {:class "text-3xl font-bold tracking-tight"} "Agent Observatory"]
        [:p {:class "text-zinc-500 text-sm mt-1"}
         (str running-count " running"
              (when (pos? completed-count)
                (str " ● " completed-count " completed"
                     (when-not show-completed " (hidden)"))))]]
       ;; Toggle button
       [:button {:class (str "px-3 py-1.5 text-sm font-medium rounded-md transition-colors "
                             (if show-completed
                               "bg-zinc-200 text-zinc-700 hover:bg-zinc-300"
                               "bg-zinc-100 text-zinc-500 hover:bg-zinc-200"))
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

(def agents-sse
  "SSE handler for live agent updates."
  (sse/render-handler
   (fn [_request]
     (agents-content))))

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

(defn- read-agent-log
  "Read the last N lines from an agent's log file."
  [agent-id max-lines]
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
      {:lines [] :exists false})))

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

(defn- log-line-component
  "Render a single log line with appropriate styling."
  [{:keys [timestamp type details raw]}]
  (let [type-class (case type
                     "LAUNCH" "text-violet-600 font-semibold"
                     "MESSAGE" "text-blue-600"
                     "TOOL" "text-amber-600"
                     "RESULT" "text-green-600"
                     "HOOK" "text-cyan-600"
                     "COMPLETE" "text-emerald-600 font-semibold"
                     "ERROR" "text-red-600 font-semibold"
                     "text-zinc-600")]
    [:div {:class "font-mono text-xs py-1 border-b border-zinc-100 last:border-0 hover:bg-zinc-50"}
     (if timestamp
       [:div {:class "flex gap-2"}
        [:span {:class "text-zinc-400 shrink-0"} timestamp]
        [:span {:class (str "shrink-0 w-16 " type-class)} type]
        [:span {:class "text-zinc-700 break-all"} details]]
       [:span {:class "text-zinc-500"} raw])]))

(defn- agent-detail-skeleton
  "Skeleton for agent detail page."
  [agent-id]
  [:div
   [:div {:class "flex items-center gap-4 mb-6"}
    [:a {:href "/agents"
         :class "text-zinc-500 hover:text-zinc-700"}
     "← Back"]
    [:h1 {:class "text-3xl font-bold tracking-tight font-mono"} agent-id]]
   [:div {:class "bg-white rounded-lg shadow-sm p-4"}
    [:div {:class "h-4 w-32 bg-zinc-200 rounded animate-skeleton mb-4"}]
    (for [_ (range 10)]
      [:div {:class "h-3 w-full bg-zinc-200 rounded animate-skeleton my-2"}])]])

(defn- agent-detail-content
  "Render the agent detail page content."
  [agent-id]
  (let [{:keys [lines exists error]} (read-agent-log agent-id 200)
        parsed-lines (keep parse-log-line lines)]
    (h/html
     [:main#morph
      ;; Header with back link
      [:div {:class "flex items-center gap-4 mb-6"}
       [:a {:href "/agents"
            :class "text-zinc-500 hover:text-zinc-700 transition-colors"}
        "← Back"]
       [:h1 {:class "text-3xl font-bold tracking-tight font-mono"} agent-id]
       (when exists
         [:span {:class "text-zinc-400 text-sm"}
          (str (count lines) " lines")])]

      ;; Log content
      [:div {:class "bg-white rounded-lg shadow-sm overflow-hidden"}
       (cond
         (not exists)
         [:div {:class "p-8 text-center text-zinc-500"}
          [:p {:class "text-lg font-medium"} "Log file not found"]
          [:p {:class "text-sm mt-2"} (str "logs/agents/" agent-id ".log")]]

         error
         [:div {:class "p-4 bg-red-50 text-red-700"}
          [:p {:class "font-medium"} "Error reading log"]
          [:p {:class "text-sm"} error]]

         (empty? parsed-lines)
         [:div {:class "p-8 text-center text-zinc-500"}
          "No log entries yet"]

         :else
         [:div {:class "p-4 max-h-[70vh] overflow-y-auto flex flex-col-reverse"}
          ;; flex-col-reverse for auto-scroll to bottom
          [:div
           (for [line parsed-lines]
             (log-line-component line))]])]])))

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

(defn agent-detail-sse
  "SSE handler for agent detail page - streams log updates."
  [request]
  (let [agent-id (get-in request [:path-params :agent-id])]
    ((sse/render-handler
      (fn [_req]
        (agent-detail-content agent-id)))
     request)))
