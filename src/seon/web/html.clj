(ns seon.web.html
  "HTML templating using Chassis (compile-time Hiccup).

   Uses Tailwind CSS v4 via CDN for styling. All pages share a common
   base layout with unified theme."
  (:require [dev.onionpancakes.chassis.core :as h]
            [clojure.string :as str]))

;; ========================================
;; CDN Resources
;; ========================================

(def datastar-cdn
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.6/bundles/datastar.js")

(def tailwind-cdn
  "https://cdn.jsdelivr.net/npm/@tailwindcss/browser@4")

;; ========================================
;; Custom Theme (Tailwind v4 @theme)
;; ========================================

(def custom-theme
  "Custom theme tokens using Tailwind v4 @theme directive.
   Extends Tailwind's built-in design tokens."
  "
  @theme {
    --font-mono: 'SF Mono', ui-monospace, Menlo, Monaco, 'Cascadia Mono', 'Consolas', monospace;
    --color-success: #22c55e;
    --color-error: #ef4444;
    --color-warning: #f59e0b;
    --color-running: #8b5cf6;
  }
  ")

;; ========================================
;; Datastar SSE Init
;; ========================================

(def on-load-js
  ;; Quirk with browsers: cache settings are per URL not per URL + METHOD.
  ;; This means GET and POST cache headers can interfere with each other.
  ;; To work around this, add an unused query param to differentiate.
  ;;
  ;; Retry Infinity means we always try to reconnect. The defaults mean
  ;; this will at most take 30s (default max backoff).
  "@post(window.location.pathname + (window.location.search + '&u=').replace(/^&/,'?'), {retryMaxCount: Infinity})")

;; ========================================
;; Shared Components
;; ========================================

(defn nav-bar
  "Shared navigation component using Tailwind. active-page is :dashboard or :logs"
  [active-page]
  [:nav {:class "flex gap-1 mb-6 bg-zinc-200 p-1 rounded-lg w-fit"}
   [:a {:href "/"
        :class (str "px-4 py-2 rounded-md text-sm font-medium transition-all "
                    (if (= active-page :dashboard)
                      "bg-white text-zinc-900 shadow-sm"
                      "text-zinc-600 hover:text-zinc-900 hover:bg-white/50"))}
    "Dashboard"]
   [:a {:href "/logs"
        :class (str "px-4 py-2 rounded-md text-sm font-medium transition-all "
                    (if (= active-page :logs)
                      "bg-white text-zinc-900 shadow-sm"
                      "text-zinc-600 hover:text-zinc-900 hover:bg-white/50"))}
    "Logs"]])

(defn status-badge
  "Status badge with appropriate colors."
  [status]
  (let [base-class "inline-block px-3 py-1 rounded-full text-xs font-semibold uppercase tracking-wide"
        status-class (case status
                       :running "bg-violet-100 text-violet-600"
                       :completed "bg-green-100 text-green-600"
                       :failed "bg-red-100 text-red-600"
                       :cancelled "bg-zinc-100 text-zinc-500"
                       :stopping "bg-amber-100 text-amber-600"
                       "bg-zinc-100 text-zinc-500")]
    [:span {:class (str base-class " " status-class)}
     (case status
       :running "Running"
       :completed "Completed"
       :failed "Failed"
       :cancelled "Cancelled"
       :stopping "Stopping"
       "Unknown")]))

;; ========================================
;; Base Page Template
;; ========================================

(defn base-page
  "Shared HTML shell for all pages.
   Options:
   - :title - Page title
   - :active-page - :dashboard or :logs for nav highlighting
   - :skeleton - Hiccup content to show while loading"
  [{:keys [title active-page skeleton]}]
  (h/html
   [h/doctype-html5
    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
      [:title title]
      [:script {:src tailwind-cdn}]
      [:script {:defer "defer" :type "module" :src datastar-cdn}]
      [:style {:type "text/tailwindcss"} custom-theme]
      ;; Skeleton animation keyframes
      [:style "
        @keyframes skeleton-pulse {
          0%, 100% { opacity: 1; }
          50% { opacity: 0.5; }
        }
        .animate-skeleton { animation: skeleton-pulse 1.5s ease-in-out infinite; }
      "]]
     [:body {:class "bg-zinc-50 text-zinc-900 min-h-screen p-4 font-sans antialiased"}
      [:div {:class "max-w-7xl mx-auto"}
       ;; Datastar init div - auto-POSTs on load and reconnects on online event
       [:div {:data-init on-load-js
              :data-on:online__window on-load-js}]
       ;; Fallback for browsers without JavaScript
       [:noscript {:class "block p-4 bg-amber-100 text-amber-800 rounded-lg mb-4"}
        "This application requires JavaScript to be enabled."]
       ;; Navigation
       (nav-bar active-page)
       ;; Main content area - will be populated via SSE
       [:main#morph skeleton]]]]]))

;; ========================================
;; Utility Functions
;; ========================================

(defn format-number
  "Format number with thousand separators."
  [n]
  (when n
    (let [s (str n)]
      (str/replace s #"\B(?=(\d{3})+(?!\d))" ","))))

(defn format-percentage
  "Format percentage with 1 decimal place."
  [n]
  (when n
    (format "%.1f%%" (double n))))

;; ========================================
;; Dashboard Skeleton
;; ========================================

(defn dashboard-skeleton
  "Skeleton loading state for dashboard."
  []
  [:div
   ;; Header
   [:div {:class "flex items-center justify-between mb-6"}
    [:div
     [:h1 {:class "text-3xl font-bold tracking-tight"} "ML Options Import Dashboard"]
     [:p {:class "text-zinc-500 text-sm mt-1"} "Real-time bulk import management for options data"]]]
   ;; Skeleton stats section
   [:section {:class "mb-6"}
    [:h3 {:class "text-xs font-semibold text-zinc-500 uppercase tracking-wider mb-3"}
     "Database Statistics"]
    [:div {:class "grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4"}
     (for [_ (range 4)]
       [:div {:class "bg-white rounded-lg shadow-sm p-5"}
        [:div {:class "h-3 w-24 bg-zinc-200 rounded animate-skeleton mb-2"}]
        [:div {:class "h-8 w-20 bg-zinc-200 rounded animate-skeleton my-2"}]
        [:div {:class "h-3 w-32 bg-zinc-200 rounded animate-skeleton mt-2"}]])]]
   ;; Skeleton card for job section
   [:div {:class "bg-white rounded-lg shadow-sm p-6"}
    [:div {:class "h-6 w-48 bg-zinc-200 rounded animate-skeleton mb-4"}]
    [:div {:class "h-4 w-3/4 bg-zinc-200 rounded animate-skeleton mb-2"}]
    [:div {:class "h-4 w-1/2 bg-zinc-200 rounded animate-skeleton"}]]])

;; ========================================
;; Dashboard Shim Page
;; ========================================

(defn shim-page
  "Returns the HTML shell for the dashboard that Datastar will populate via SSE."
  []
  (base-page
   {:title "ML Options Trading - Import Dashboard"
    :active-page :dashboard
    :skeleton (dashboard-skeleton)}))

;; ========================================
;; Dashboard Content (SSE Updates)
;; ========================================

(defn stat-card
  "Reusable stat card component."
  [{:keys [label value meta]}]
  [:div {:class "bg-white rounded-lg shadow-sm p-5"}
   [:div {:class "text-zinc-500 text-sm font-medium"} label]
   [:div {:class "text-3xl font-bold font-mono my-2"} value]
   [:div {:class "text-zinc-400 text-xs"} meta]])

(defn dashboard-content
  "Renders the main dashboard content for SSE updates."
  [{:keys [current history-count]} db-stats]
  (let [progress (get current :progress {})
        days-completed (get progress :days-completed 0)
        total-days (get progress :total-days 1)
        progress-pct (if (pos? total-days)
                       (* 100.0 (/ days-completed total-days))
                       0)]
    (h/html
     [:main#morph
      ;; Header
      [:div {:class "flex items-center justify-between mb-6"}
       [:div
        [:h1 {:class "text-3xl font-bold tracking-tight"} "ML Options Import Dashboard"]
        [:p {:class "text-zinc-500 text-sm mt-1"} "Real-time bulk import management for options data"]]
       (when current
         (status-badge (:status current)))]

      ;; Database Stats Panel
      (when db-stats
        [:section {:class "mb-6"}
         [:h3 {:class "text-xs font-semibold text-zinc-500 uppercase tracking-wider mb-3"}
          "Database Statistics"]
         [:div {:class "grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-5 gap-4"}
          ;; Total Records
          (stat-card {:label "Total Records"
                      :value (format-number (:total-records db-stats))
                      :meta "Option greeks in database"})

          ;; Symbols Loaded
          (stat-card {:label "Symbols Loaded"
                      :value (count (:by-symbol db-stats))
                      :meta "Unique tickers with data"})

          ;; Date Range
          (stat-card {:label "Date Coverage"
                      :value (if-let [range (:date-range db-stats)]
                               (str (:min-date range))
                               "N/A")
                      :meta (if-let [range (:date-range db-stats)]
                              (str "to " (:max-date range))
                              "No data yet")})

          ;; Disk Usage
          (stat-card {:label "Disk Usage"
                      :value (get-in db-stats [:disk-usage :formatted] "N/A")
                      :meta "XTDB data directory"})

          ;; Active Import Progress or System Status
          (if (and current (= :running (:status current)))
            (stat-card {:label "Import Progress"
                        :value (format-percentage progress-pct)
                        :meta (str days-completed " of " total-days " days")})
            (stat-card {:label "System Status"
                        :value "Idle"
                        :meta "No active imports"}))]])

      ;; Symbol Breakdown Table
      (when (seq (:by-symbol db-stats))
        (let [date-ranges (into {} (map (fn [r] [(:asset/ticker r) r])
                                        (:date-ranges-by-symbol db-stats)))]
          [:div {:class "bg-white rounded-lg shadow-sm p-6 mb-4"}
           [:h3 {:class "text-xs font-semibold text-zinc-500 uppercase tracking-wider mb-4"}
            "Records by Symbol"]
           [:table {:class "w-full text-sm"}
            [:thead
             [:tr {:class "border-b border-zinc-200"}
              [:th {:class "text-left py-3 px-2 text-xs font-semibold text-zinc-500 uppercase tracking-wider"} "Symbol"]
              [:th {:class "text-left py-3 px-2 text-xs font-semibold text-zinc-500 uppercase tracking-wider"} "Records"]
              [:th {:class "text-left py-3 px-2 text-xs font-semibold text-zinc-500 uppercase tracking-wider"} "From"]
              [:th {:class "text-left py-3 px-2 text-xs font-semibold text-zinc-500 uppercase tracking-wider"} "To"]]]
            [:tbody
             (for [row (:by-symbol db-stats)
                   :let [ticker (:asset/ticker row)
                         dr (get date-ranges ticker)]]
               [:tr {:class "border-b border-zinc-100 hover:bg-zinc-50"}
                [:td {:class "py-3 px-2 font-mono font-medium"} ticker]
                [:td {:class "py-3 px-2 font-mono"} (format-number (:count row))]
                [:td {:class "py-3 px-2 font-mono text-zinc-500"} (or (some-> dr :min-date str) "-")]
                [:td {:class "py-3 px-2 font-mono text-zinc-500"} (or (some-> dr :max-date str) "-")]])]]]))

      ;; Current Job Status
      (if current
        [:div {:class "bg-white rounded-lg shadow-sm p-6 mb-4"}
         [:div {:class "flex items-center justify-between mb-4"}
          [:h2 {:class "text-lg font-semibold"}
           (case (:status current)
             :running "Active Import Job"
             :completed "Import Completed"
             :failed "Import Failed"
             :cancelled "Import Cancelled"
             :stopping "Stopping Import..."
             "Import Status")]
          (when (= :running (:status current))
            [:button {:class "px-4 py-2 bg-red-500 hover:bg-red-600 text-white text-sm font-medium rounded-lg transition-colors"
                      :data-on-click "@post('/api/import/stop')"}
             "Stop Import"])]

         ;; Job Details
         [:div {:class "grid gap-3 text-sm mb-4"}
          [:div
           [:span {:class "text-zinc-500 mr-2"} "Job ID:"]
           [:span {:class "font-mono"} (:id current)]]
          [:div
           [:span {:class "text-zinc-500 mr-2"} "Symbols:"]
           [:span {:class "font-mono"} (str/join ", " (:symbols current))]]
          [:div
           [:span {:class "text-zinc-500 mr-2"} "Date Range:"]
           [:span {:class "font-mono"} (str (:start-date current) " to " (:end-date current))]]
          (when (:started-at current)
            [:div
             [:span {:class "text-zinc-500 mr-2"} "Started:"]
             [:span {:class "font-mono"} (:started-at current)]])]

         ;; Progress Bar
         (when (= :running (:status current))
           [:div {:class "mt-4"}
            [:div {:class "h-2 bg-zinc-200 rounded-full overflow-hidden"}
             [:div {:class "h-full bg-gradient-to-r from-blue-500 to-violet-500 transition-all duration-300"
                    :style (str "width: " progress-pct "%")}]]
            [:div {:class "font-mono text-sm text-zinc-500 mt-2"}
             (str days-completed " of " total-days " days completed ("
                  (format-percentage progress-pct) ")")]])

         ;; Current Activity
         (when (and (= :running (:status current))
                    (or (:current-symbol progress) (:current-day progress)))
           [:div {:class "mt-4"}
            [:div {:class "text-zinc-500 text-sm"} "Currently Processing:"]
            [:div {:class "font-mono text-sm mt-1"}
             (when (:current-symbol progress)
               (str "Symbol: " (:current-symbol progress)))
             (when (:current-day progress)
               (str " | Day: " (:current-day progress)))
             (when (:records-loaded progress)
               (str " | Records: " (format-number (:records-loaded progress))))]])

         ;; Error Display
         (when (and (= :failed (:status current)) (:error current))
           [:div {:class "mt-4 p-4 bg-red-50 border border-red-200 rounded-lg"}
            [:div {:class "text-red-600 font-semibold mb-2"} "Import Failed"]
            [:div {:class "font-mono text-sm text-red-800"} (:error current)]
            (when (:stack-trace current)
              [:details {:class "mt-2"}
               [:summary {:class "text-sm text-zinc-500 cursor-pointer"} "View stack trace"]
               [:pre {:class "mt-2 text-xs text-zinc-600 overflow-x-auto"} (:stack-trace current)]])])]

        ;; No job running - show start form
        [:div {:class "bg-white rounded-lg shadow-sm p-6 mb-4"}
         [:h2 {:class "text-lg font-semibold mb-2"} "Start New Import"]
         [:p {:class "text-zinc-500 text-sm mb-4"}
          "Import historical options data from ThetaData. Ensure ThetaData Terminal is running before starting."]

         [:form {:id "import-form"
                 :action ""
                 :data-on-submit "@post('/api/import/start', {contentType: 'form'})"}
          [:div {:class "grid gap-4 mb-4"}
           [:div {:class "flex flex-col gap-2"}
            [:label {:for "symbols" :class "text-sm font-medium"} "Symbols"]
            [:input {:id "symbols"
                     :type "text"
                     :name "symbols"
                     :required "required"
                     :placeholder "e.g., SPY,AAPL,NVDA,GOOGL,MSFT"
                     :class "px-3 py-2 border border-zinc-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"}]]

           [:div {:class "grid grid-cols-2 gap-4"}
            [:div {:class "flex flex-col gap-2"}
             [:label {:for "start-date" :class "text-sm font-medium"} "Start Date"]
             [:input {:id "start-date"
                      :type "date"
                      :name "startDate"
                      :required "required"
                      :value "2024-01-01"
                      :class "px-3 py-2 border border-zinc-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"}]]

            [:div {:class "flex flex-col gap-2"}
             [:label {:for "end-date" :class "text-sm font-medium"} "End Date"]
             [:input {:id "end-date"
                      :type "date"
                      :name "endDate"
                      :required "required"
                      :value "2024-12-01"
                      :class "px-3 py-2 border border-zinc-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"}]]]]

          [:button {:type "submit"
                    :class "px-5 py-2 bg-blue-500 hover:bg-blue-600 text-white text-sm font-medium rounded-lg transition-colors"}
           "Start Import"]]])

      ;; Recent Activity Log
      (when (and current (seq (:log current)))
        [:div {:class "bg-white rounded-lg shadow-sm p-6 mb-4"}
         [:h3 {:class "text-xs font-semibold text-zinc-500 uppercase tracking-wider mb-4"}
          "Recent Activity"]
         [:div {:class "bg-zinc-900 rounded-lg p-4 max-h-96 overflow-y-auto font-mono text-[13px] leading-relaxed"}
          (for [entry (take-last 50 (:log current))]
            [:div {:class "text-zinc-300 my-0.5"}
             [:span {:class "text-zinc-500 mr-2"} (subs (:timestamp entry) 11 19)]
             [:span (:message entry)]])]])

      ;; Job History
      (when (pos? history-count)
        [:div {:class "bg-white rounded-lg shadow-sm p-6"}
         [:h3 {:class "text-xs font-semibold text-zinc-500 uppercase tracking-wider mb-2"}
          (str "Job History (" history-count " completed jobs)")]
         [:p {:class "text-zinc-500 text-sm"} "View past import jobs and their results."]])])))

;; ========================================
;; Log Viewer Skeleton
;; ========================================

(defn log-viewer-skeleton
  "Skeleton loading state for log viewer."
  []
  [:div
   [:h1 {:class "text-3xl font-bold tracking-tight"} "Log Viewer"]
   [:p {:class "text-zinc-500 text-sm mt-1 mb-4"} "Connecting..."]
   [:div {:class "bg-white rounded-lg shadow-sm p-4 mb-4"}
    [:div {:class "flex gap-3 items-center"}
     [:div {:class "h-9 w-32 bg-zinc-200 rounded animate-skeleton"}]
     [:div {:class "h-9 w-28 bg-zinc-200 rounded animate-skeleton"}]
     [:div {:class "h-9 w-36 bg-zinc-200 rounded animate-skeleton"}]]]
   [:div {:class "bg-zinc-900 rounded-lg p-4 h-96"}
    [:div {:class "h-4 w-3/4 bg-zinc-700 rounded animate-skeleton mb-2"}]
    [:div {:class "h-4 w-1/2 bg-zinc-700 rounded animate-skeleton mb-2"}]
    [:div {:class "h-4 w-2/3 bg-zinc-700 rounded animate-skeleton"}]]])

;; ========================================
;; Log Viewer Shim Page
;; ========================================

(defn log-viewer-shim
  "Returns the HTML shell for the log viewer page."
  []
  (base-page
   {:title "ML Options Trading - Log Viewer"
    :active-page :logs
    :skeleton (log-viewer-skeleton)}))

;; ========================================
;; Log Viewer Content (SSE Updates)
;; ========================================

(defn render-log-entry
  "Render a single log entry with syntax highlighting."
  [{:keys [timestamp level logger message]}]
  (let [level-class (case level
                      :error "text-red-400"
                      :warn "text-amber-400"
                      :info "text-blue-400"
                      :debug "text-violet-400"
                      "text-zinc-400")]
    [:div {:class "flex gap-3 my-0.5 text-[13px]"}
     [:span {:class "text-zinc-500 shrink-0 w-[75px]"} (subs timestamp 11 19)]
     [:span {:class (str "font-semibold uppercase shrink-0 w-[50px] " level-class)}
      (str/upper-case (name level))]
     [:span {:class "text-zinc-500 text-xs shrink-0 w-[200px] truncate opacity-80"} logger]
     [:span {:class "text-zinc-300 flex-1 break-words"} message]]))

(defn log-viewer-content
  "Renders the log viewer content for SSE updates."
  [{:keys [entries level-filter auto-scroll]}]
  (h/html
   [:main#morph
    ;; Header
    [:h1 {:class "text-3xl font-bold tracking-tight"} "Log Viewer"]
    [:p {:class "text-zinc-500 text-sm mt-1 mb-4"}
     (str "Showing " (count entries) " recent entries")]

    ;; Controls
    [:div {:class "bg-white rounded-lg shadow-sm p-4 mb-4 flex flex-wrap gap-3 items-center"}
     [:label {:for "level-filter" :class "text-sm font-medium text-zinc-600 mr-2"} "Level:"]
     [:select {:id "level-filter"
               :class "px-3 py-2 border border-zinc-300 rounded-lg text-sm bg-white focus:outline-none focus:ring-2 focus:ring-blue-500"
               :data-on-change "@post('/api/logs/filter', {contentType: 'form'})"}
      [:option {:value "all" :selected (= :all level-filter)} "All Levels"]
      [:option {:value "error" :selected (= :error level-filter)} "Error"]
      [:option {:value "warn" :selected (= :warn level-filter)} "Warning"]
      [:option {:value "info" :selected (= :info level-filter)} "Info"]
      [:option {:value "debug" :selected (= :debug level-filter)} "Debug"]]

     [:button {:class "px-4 py-2 border border-zinc-300 rounded-lg text-sm font-medium hover:bg-zinc-50 transition-colors"
               :data-on-click "@post('/api/logs/refresh')"}
      "Refresh Now"]

     [:button {:class (str "px-4 py-2 rounded-lg text-sm font-medium transition-colors "
                           (if auto-scroll
                             "bg-blue-500 text-white"
                             "border border-zinc-300 hover:bg-zinc-50"))
               :data-on-click "@post('/api/logs/toggle-scroll')"}
      (if auto-scroll "Auto-scroll: ON" "Auto-scroll: OFF")]]

    ;; Log Container - dark terminal-style
    [:div {:class "bg-zinc-900 rounded-lg shadow-sm p-4 font-mono overflow-y-auto"
           :style "height: calc(100vh - 280px); min-height: 400px;"}
     (if (seq entries)
       (for [entry entries]
         (render-log-entry entry))
       [:div {:class "text-zinc-500 text-center py-12"}
        "No log entries match the current filter."])]]))
