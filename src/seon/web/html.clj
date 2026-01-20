(ns seon.web.html
  "HTML templating using Chassis (compile-time Hiccup).

   Uses Tailwind CSS v4 via CDN for styling. All pages share a common
   base layout with unified theme."
  (:require [dev.onionpancakes.chassis.core :as h]
            [clojure.string :as str]
            [seon.ai.agent :as agent]))

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
  "Shared navigation component using Tailwind. active-page is :dashboard, :logs, or :agents"
  [active-page]
  [:nav {:class "flex gap-1 mb-6 bg-zinc-200 p-1 rounded-lg w-fit"}
   [:a {:href "/"
        :class (str "px-4 py-2 rounded-md text-sm font-medium transition-all "
                    (if (= active-page :dashboard)
                      "bg-white text-zinc-900 shadow-sm"
                      "text-zinc-600 hover:text-zinc-900 hover:bg-white/50"))}
    "Dashboard"]
   [:a {:href "/agents"
        :class (str "px-4 py-2 rounded-md text-sm font-medium transition-all "
                    (if (= active-page :agents)
                      "bg-white text-zinc-900 shadow-sm"
                      "text-zinc-600 hover:text-zinc-900 hover:bg-white/50"))}
    "Agents"]
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
   [:div {:class "mb-8"}
    [:h1 {:class "text-4xl font-bold tracking-tight"} "Seon"]
    [:p {:class "text-zinc-500 mt-2"} "Personal operating system for life"]]
   ;; Skeleton sections
   [:div {:class "grid grid-cols-1 lg:grid-cols-2 gap-6"}
    ;; Agents skeleton
    [:div {:class "bg-white rounded-lg shadow-sm p-6"}
     [:div {:class "h-5 w-24 bg-zinc-200 rounded animate-skeleton mb-4"}]
     [:div {:class "h-8 w-16 bg-zinc-200 rounded animate-skeleton mb-2"}]
     [:div {:class "h-4 w-32 bg-zinc-200 rounded animate-skeleton"}]]
    ;; Namespaces skeleton
    [:div {:class "bg-white rounded-lg shadow-sm p-6"}
     [:div {:class "h-5 w-32 bg-zinc-200 rounded animate-skeleton mb-4"}]
     (for [_ (range 5)]
       [:div {:class "h-4 w-48 bg-zinc-200 rounded animate-skeleton my-2"}])]]])

;; ========================================
;; Dashboard Shim Page
;; ========================================

(defn shim-page
  "Returns the HTML shell for the dashboard that Datastar will populate via SSE."
  []
  (base-page
   {:title "Seon - Personal Operating System"
    :active-page :dashboard
    :skeleton (dashboard-skeleton)}))

;; ========================================
;; Dashboard Content (SSE Updates)
;; ========================================

(defn- all-seon-namespaces
  "Find all loaded seon.* namespaces, sorted alphabetically."
  []
  (->> (all-ns)
       (filter #(str/starts-with? (str (ns-name %)) "seon."))
       (map ns-name)
       (sort)
       (vec)))

(defn- namespace-list
  "Render a list of namespaces grouped by top-level prefix."
  [namespaces]
  (let [grouped (->> namespaces
                     (group-by (fn [ns-sym]
                                 (let [parts (str/split (str ns-sym) #"\.")]
                                   (if (> (count parts) 1)
                                     (str (first parts) "." (second parts))
                                     (first parts))))))]
    [:div {:class "space-y-2"}
     (for [[prefix ns-list] (sort-by first grouped)]
       [:div {:class "py-1"}
        [:div {:class "font-medium text-zinc-700 text-sm"} prefix]
        [:div {:class "pl-4 space-y-0.5"}
         (for [ns-sym ns-list]
           [:div {:class "font-mono text-xs text-zinc-500 hover:text-zinc-700 cursor-default"}
            (str ns-sym)])]])]))

(defn dashboard-content
  "Renders the namespace-focused dashboard content for SSE updates."
  [running-agents]
  (let [agent-count (count running-agents)
        namespaces (all-seon-namespaces)]
    (h/html
     [:main#morph
      ;; Header
      [:div {:class "mb-8"}
       [:h1 {:class "text-4xl font-bold tracking-tight"} "Seon"]
       [:p {:class "text-zinc-500 mt-2"} "Personal operating system for life"]]

      ;; Main content grid
      [:div {:class "grid grid-cols-1 lg:grid-cols-2 gap-6"}
       ;; Agents Section
       [:div {:class "bg-white rounded-lg shadow-sm p-6"}
        [:h2 {:class "text-xs font-semibold text-zinc-500 uppercase tracking-wider mb-4"}
         "Agents"]
        [:div {:class "text-4xl font-bold font-mono mb-2"}
         agent-count]
        [:p {:class "text-zinc-500 text-sm mb-4"}
         (if (zero? agent-count)
           "No agents running"
           (str agent-count " agent" (when (not= 1 agent-count) "s") " running"))]
        [:a {:href "/agents"
             :class "inline-flex items-center gap-2 text-sm font-medium text-blue-600 hover:text-blue-700 transition-colors"}
         "View Observatory"
         [:span {:class "text-lg"} "\u2192"]]]

       ;; Namespaces Section
       [:div {:class "bg-white rounded-lg shadow-sm p-6"}
        [:h2 {:class "text-xs font-semibold text-zinc-500 uppercase tracking-wider mb-4"}
         "Namespaces"]
        [:p {:class "text-zinc-500 text-sm mb-4"}
         (str (count namespaces) " loaded seon.* namespaces")]
        [:div {:class "max-h-64 overflow-y-auto"}
         (namespace-list namespaces)]]]])))

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
