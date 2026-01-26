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

(def jetbrains-mono-cdn
  "Google Fonts CDN for JetBrains Mono - excellent readability at small sizes."
  "https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;600&display=swap")

;; ========================================
;; Custom Theme (Tailwind v4 @theme)
;; ========================================

(def custom-theme
  "Custom theme tokens using Tailwind v4 @theme directive.
   Phosphor Terminal theme - warm blacks, cream text, amber accents.
   See docs/prds/namespace-ui/design-system.md for details."
  "
  @theme {
    /* Fonts - JetBrains Mono for terminal aesthetic */
    --font-mono: 'JetBrains Mono', 'SF Mono', ui-monospace, Menlo, Monaco, 'Cascadia Mono', monospace;

    /* Base colors (warm blacks) */
    --color-base-950: #0d0d0c;
    --color-base-900: #1a1918;
    --color-base-850: #252422;
    --color-base-800: #302e2b;
    --color-base-700: #3d3a36;

    /* Text colors (cream, not white) */
    --color-text-50: #faf9f7;
    --color-text-200: #d4d0c8;
    --color-text-400: #8c8578;
    --color-text-500: #6b6459;

    /* Semantic colors */
    --color-signal: #f0b429;
    --color-success: #34d399;
    --color-error: #f87171;
    --color-warning: #fbbf24;
    --color-info: #60a5fa;
    --color-eval: #c084fc;

    /* Log type colors */
    --color-log-launch: #a78bfa;
    --color-log-message: #60a5fa;
    --color-log-tool: #fbbf24;
    --color-log-result: #34d399;
    --color-log-hook: #22d3ee;
    --color-log-done: #4ade80;
    --color-log-error: #f87171;
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
  "Minimal tab bar navigation. Active: text-50 + 2px amber underline. No background pills."
  [active-page]
  [:nav {:class "flex gap-6 mb-4 border-b border-base-700"}
   (for [[page label] [[:dashboard "dashboard"] [:agents "agents"] [:logs "logs"]]]
     [:a {:href (case page :dashboard "/" :agents "/agents" :logs "/logs")
          :class (str "pb-2 text-sm font-medium transition-colors "
                      (if (= active-page page)
                        "text-text-50 border-b-2 border-signal -mb-px"
                        "text-text-400 hover:text-text-200"))}
      label])])

(defn status-badge
  "Status badge with Phosphor theme colors."
  [status]
  (let [base-class "inline-block px-3 py-1 rounded text-xs font-semibold uppercase tracking-wide"
        status-class (case status
                       :running "bg-info/20 text-info"
                       :completed "bg-success/20 text-success"
                       :failed "bg-error/20 text-error"
                       :cancelled "bg-base-700 text-text-500"
                       :stopping "bg-warning/20 text-warning"
                       "bg-base-700 text-text-500")]
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
      ;; JetBrains Mono font for terminal aesthetic
      [:link {:rel "preconnect" :href "https://fonts.googleapis.com"}]
      [:link {:rel "preconnect" :href "https://fonts.gstatic.com" :crossorigin "anonymous"}]
      [:link {:rel "stylesheet" :href jetbrains-mono-cdn}]
      ;; Highlight.js for syntax highlighting in code blocks
      [:link {:rel "stylesheet"
              :href "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/styles/github-dark.min.css"}]
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
     [:body {:class "bg-base-950 text-text-50 min-h-screen p-4 font-mono antialiased"}
      ;; Highlight.js scripts at end of body for faster page load
      [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/highlight.min.js"}]
      [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/clojure.min.js"}]
      [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/bash.min.js"}]
      [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.9.0/languages/diff.min.js"}]
      ;; Initialize highlight.js and re-run after Datastar morphs
      [:script "
        document.addEventListener('DOMContentLoaded', () => hljs.highlightAll());
        document.addEventListener('datastar-morph', () => {
          document.querySelectorAll('pre code:not(.hljs)').forEach(el => hljs.highlightElement(el));
        });
      "]
      ;; Hover card positioning - fixes overflow clipping by using fixed positioning
      [:script "
        document.addEventListener('mouseover', (e) => {
          const line = e.target.closest('.log-line');
          if (!line) return;
          const card = line.querySelector('.hover-card');
          if (!card) return;

          const rect = line.getBoundingClientRect();
          card.style.left = rect.left + 'px';

          // Position below if room, else above
          const spaceBelow = window.innerHeight - rect.bottom;
          if (spaceBelow > 200) {
            card.style.top = (rect.bottom + 4) + 'px';
            card.style.bottom = 'auto';
          } else {
            card.style.top = 'auto';
            card.style.bottom = (window.innerHeight - rect.top + 4) + 'px';
          }
        });
      "]
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
   [:div {:class "mb-4"}
    [:h1 {:class "text-lg font-bold tracking-tight"} "Seon"]
    [:p {:class "text-text-400 mt-1 text-sm"} "Personal operating system for life"]]
   ;; Skeleton sections
   [:div {:class "grid grid-cols-1 lg:grid-cols-2 gap-4"}
    ;; Agents skeleton
    [:div {:class "bg-base-850 rounded p-3"}
     [:div {:class "h-4 w-20 bg-base-700 rounded animate-skeleton mb-2"}]
     [:div {:class "h-5 w-24 bg-base-700 rounded animate-skeleton mb-2"}]
     [:div {:class "h-4 w-28 bg-base-700 rounded animate-skeleton"}]]
    ;; Namespaces skeleton
    [:div {:class "bg-base-850 rounded p-3"}
     [:div {:class "h-4 w-24 bg-base-700 rounded animate-skeleton mb-2"}]
     (for [_ (range 5)]
       [:div {:class "h-3 w-40 bg-base-700 rounded animate-skeleton my-1"}])]]])

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
        [:div {:class "font-medium text-text-200 text-sm"} prefix]
        [:div {:class "pl-4 space-y-0.5"}
         (for [ns-sym ns-list]
           [:div {:class "font-mono text-xs text-text-400 hover:text-text-200 cursor-default"}
            (str ns-sym)])]])]))

(defn dashboard-content
  "Renders the namespace-focused dashboard content for SSE updates."
  [running-agents]
  (let [agent-count (count running-agents)
        namespaces (all-seon-namespaces)]
    (h/html
     [:main#morph
      ;; Header
      [:div {:class "mb-4"}
       [:h1 {:class "text-lg font-bold tracking-tight"} "Seon"]
       [:p {:class "text-text-400 mt-1 text-sm"} "Personal operating system for life"]]

      ;; Main content grid
      [:div {:class "grid grid-cols-1 lg:grid-cols-2 gap-4"}
       ;; Agents Section
       [:div {:class "bg-base-850 rounded p-3"}
        [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-2"}
         "Agents"]
        [:div {:class "flex items-baseline gap-2"}
         [:span {:class "text-lg font-semibold font-mono"} agent-count]
         [:span {:class "text-xs text-text-400"} (if (zero? agent-count) "agents" "running")]]
        [:a {:href "/agents"
             :class "inline-flex items-center gap-2 text-sm font-medium text-signal hover:text-warning transition-colors mt-2"}
         "View Observatory"
         [:span {:class "text-lg"} "\u2192"]]]

       ;; Namespaces Section
       [:div {:class "bg-base-850 rounded p-3"}
        [:h2 {:class "text-xs font-semibold text-text-400 uppercase tracking-wider mb-2"}
         "Namespaces"]
        [:p {:class "text-text-400 text-sm mb-2"}
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
   [:p {:class "text-text-400 text-sm mt-1 mb-4"} "Connecting..."]
   [:div {:class "bg-base-850 rounded p-4 mb-4"}
    [:div {:class "flex gap-3 items-center"}
     [:div {:class "h-9 w-32 bg-base-700 rounded animate-skeleton"}]
     [:div {:class "h-9 w-28 bg-base-700 rounded animate-skeleton"}]
     [:div {:class "h-9 w-36 bg-base-700 rounded animate-skeleton"}]]]
   [:div {:class "bg-base-900 rounded p-4 h-96"}
    [:div {:class "h-4 w-3/4 bg-base-700 rounded animate-skeleton mb-2"}]
    [:div {:class "h-4 w-1/2 bg-base-700 rounded animate-skeleton mb-2"}]
    [:div {:class "h-4 w-2/3 bg-base-700 rounded animate-skeleton"}]]])

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
  "Render a single log entry with Phosphor terminal styling."
  [{:keys [timestamp level logger message]}]
  (let [level-class (case level
                      :error "text-log-error"
                      :warn "text-warning"
                      :info "text-info"
                      :debug "text-eval"
                      "text-text-400")]
    [:div {:class "flex gap-3 my-0.5 text-xs leading-tight py-0.5 hover:bg-base-800"}
     [:span {:class "text-text-400 shrink-0 w-[75px]"} (subs timestamp 11 19)]
     [:span {:class (str "font-semibold uppercase shrink-0 w-[50px] " level-class)}
      (str/upper-case (name level))]
     [:span {:class "text-text-500 text-xs shrink-0 w-[200px] truncate"} logger]
     [:span {:class "text-text-50 flex-1 break-words"} message]]))

(defn log-viewer-content
  "Renders the log viewer content for SSE updates."
  [{:keys [entries level-filter]}]
  (let [last-entry (last entries)
        last-time (when last-entry
                    (try
                      (java.time.LocalTime/parse (subs (:timestamp last-entry) 11 19))
                      (catch Exception _ nil)))
        now (java.time.LocalTime/now)
        seconds-ago (when last-time
                      (.getSeconds (java.time.Duration/between last-time now)))]
    (h/html
     [:main#morph
      ;; Header with status
      [:div {:class "flex items-center justify-between mb-4"}
       [:div
        [:h1 {:class "text-3xl font-bold tracking-tight"} "Log Viewer"]
        [:p {:class "text-text-400 text-sm mt-1"}
         (str "Showing " (count entries) " recent entries")]]
       ;; Status indicator
       (when last-entry
         [:div {:class "flex items-center gap-2"}
          [:span {:class (str "w-2 h-2 rounded-full "
                              (if (and seconds-ago (< seconds-ago 10))
                                "bg-success animate-pulse"
                                "bg-text-500"))}]
          [:span {:class "text-sm text-text-400"}
           (if seconds-ago
             (str "Last log: " seconds-ago "s ago")
             "Live")]])]

      ;; Controls
      [:div {:class "bg-base-850 rounded p-4 mb-4 flex flex-wrap gap-3 items-center"}
       [:label {:for "level-filter" :class "text-sm font-medium text-text-400 mr-2"} "Level:"]
       [:select {:id "level-filter"
                 :class "px-3 py-2 border border-base-700 rounded text-sm bg-base-900 text-text-50 focus:outline-none focus:ring-2 focus:ring-signal"
                 :data-on-change "@post('/api/logs/filter', {contentType: 'form'})"}
        [:option {:value "all" :selected (= :all level-filter)} "All Levels"]
        [:option {:value "error" :selected (= :error level-filter)} "Error"]
        [:option {:value "warn" :selected (= :warn level-filter)} "Warning"]
        [:option {:value "info" :selected (= :info level-filter)} "Info"]
        [:option {:value "debug" :selected (= :debug level-filter)} "Debug"]]

       [:button {:class "px-4 py-2 border border-base-700 rounded text-sm font-medium text-text-200 hover:bg-base-800 transition-colors"
                 :data-on-click "@post('/api/logs/refresh')"}
        "Refresh Now"]]

      ;; Log Container - dark terminal-style with flex-col-reverse for auto-scroll to bottom
      [:div {:class "bg-base-900 rounded p-4 font-mono overflow-y-auto flex flex-col-reverse"
             :style "height: calc(100vh - 280px); min-height: 400px;"}
       [:div
        (if (seq entries)
          (for [entry entries]
            (render-log-entry entry))
          [:div {:class "text-text-500 text-center py-12"}
           "No log entries match the current filter."])]]])))
