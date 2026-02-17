(ns seon.web.flows
  "Flow monitor page — shows all registered flows with status,
   topology, process metrics, and errors.

   Follows the same GET shim + POST SSE pattern as seon.web.agents."
  (:require [clojure.core.protocols :as protocols]
            [seon.flow.status :as status]
            [seon.flow.registry :as registry]
            [seon.web.sse :as sse]
            [seon.web.html :as html]
            [seon.web.components :as ui]
            [dev.onionpancakes.chassis.core :as h]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- format-uptime
  "Format uptime-ms as human-readable string."
  [ms]
  (when ms
    (let [seconds (quot ms 1000)
          minutes (quot seconds 60)
          hours   (quot minutes 60)
          mins    (mod minutes 60)
          secs    (mod seconds 60)]
      (cond
        (pos? hours) (format "%dh %dm" hours mins)
        (pos? minutes) (format "%dm %ds" minutes secs)
        :else (format "%ds" secs)))))

(defn- format-rate
  "Format msgs/sec with 1 decimal."
  [rate]
  (when rate
    (format "%.1f/s" (double rate))))

;;; ---------------------------------------------------------------------------
;;; Topology Visualization
;;; ---------------------------------------------------------------------------

(defn- get-topology
  "Get topology data (procs + conns) from a flow via datafy.
   Returns nil if flow not available."
  [flow-id]
  (when-let [entry (registry/get-flow {::registry/id flow-id})]
    (let [fl (::registry/flow entry)]
      (try
        (let [d (protocols/datafy fl)]
          {:procs (:procs d)
           :conns (:conns d)})
        (catch Exception _ nil)))))

(defn- render-topology
  "Render a simple topology diagram showing process boxes with arrows.
   Uses flexbox with horizontal layout."
  [flow-id process-statuses]
  (let [topo (get-topology flow-id)]
    (if (and topo (seq (:conns topo)))
      (let [conns (:conns topo)
            ;; Build adjacency: source-pid -> [target-pid ...]
            edges (reduce (fn [m [[src-pid _] [tgt-pid _]]]
                            (update m src-pid (fnil conj []) tgt-pid))
                          {} conns)
            ;; All pids from procs
            all-pids (keys (:procs topo))
            ;; Find roots (pids that are not targets)
            targets (set (mapcat val edges))
            roots (remove targets all-pids)]
        [:div {:class "flex flex-wrap items-center gap-2 py-2 px-3 bg-base-900 rounded text-xs font-mono"}
         (interpose
          [:span {:class "text-text-500"} "\u2192"]
          (for [pid (concat roots (remove (set roots) all-pids))]
            (let [st (get-in process-statuses [pid ::status/status] :unknown)
                  bg (case st
                       :running "bg-success/20 border-success/40"
                       :paused "bg-warning/20 border-warning/40"
                       "bg-base-800 border-base-600")]
              [:span {:class (str "inline-flex items-center gap-1 px-2 py-1 rounded border " bg)}
               (ui/status-dot st)
               [:span {:class "text-text-200"} (name pid)]])))])
      ;; No topology available — just list processes
      (when (seq process-statuses)
        [:div {:class "flex flex-wrap gap-2 py-2 px-3 bg-base-900 rounded text-xs font-mono"}
         (for [[pid ps] process-statuses]
           [:span {:class "inline-flex items-center gap-1 px-2 py-1 rounded border bg-base-800 border-base-600"}
            (ui/status-dot (::status/status ps))
            [:span {:class "text-text-200"} (name pid)]])]))))

;;; ---------------------------------------------------------------------------
;;; Process Table
;;; ---------------------------------------------------------------------------

(defn- render-process-table
  "Render a table of process metrics."
  [processes]
  (when (seq processes)
    [:div {:class "bg-base-850 rounded overflow-hidden"}
     [:table {:class "w-full"}
      [:thead
       [:tr {:class "border-b border-base-700"}
        (ui/table-header "Process")
        (ui/table-header "Status")
        (ui/table-header "Count" true)
        (ui/table-header "Rate" true)
        (ui/table-header "State")]]
      [:tbody
       (for [[pid ps] (sort-by first processes)]
         [:tr {:class "border-b border-base-700 last:border-0"}
          (ui/table-cell (name pid))
          [:td {:class "py-2 px-3"} (ui/status-dot (::status/status ps))]
          (ui/table-cell (str (::status/count ps 0)) {:right-align? true :muted? true})
          (ui/table-cell (or (format-rate (::status/msgs-per-sec ps)) "-") {:right-align? true :muted? true})
          (ui/table-cell
           (let [summary (::status/state-summary ps)]
             (if (seq summary)
               [:span {:class "text-text-500 text-xs"} (pr-str summary)]
               "-"))
           {:muted? true})])]]]))

;;; ---------------------------------------------------------------------------
;;; Error Feed
;;; ---------------------------------------------------------------------------

(defn- render-errors
  "Render recent errors using log-line components."
  [{::status/keys [total recent]}]
  (when (and total (pos? total))
    [:div
     [:div {:class "flex items-center gap-2 mb-1"}
      (ui/section-header "ERRORS")
      [:span {:class "text-error text-xs font-mono"} (str total " total")]]
     (if (seq recent)
       (ui/log-container
        (for [err recent]
          {:timestamp (str (::status/received-at err ""))
           :type "ERROR"
           :details (pr-str (dissoc err ::status/received-at))})
        "20vh")
       (ui/empty-state "No recent errors"))]))

;;; ---------------------------------------------------------------------------
;;; Flow Card
;;; ---------------------------------------------------------------------------

(defn- render-flow-card
  "Render a single flow's status card."
  [[flow-id flow-status]]
  (when flow-status
    (let [{::status/keys [label status uptime-ms processes errors]} flow-status]
      (ui/card
       ;; Header: label + status + uptime
       [:div {:class "flex items-center justify-between mb-3"}
        [:div {:class "flex items-center gap-2"}
         [:span {:class "text-sm font-semibold text-text-50"} (or label (name flow-id))]
         (ui/status-dot status)]
        (when uptime-ms
          [:span {:class "text-text-500 text-xs font-mono"} (format-uptime uptime-ms)])]

       ;; Topology
       (render-topology flow-id processes)

       ;; Process table
       [:div {:class "mt-3"}
        (render-process-table processes)]

       ;; Errors
       (when errors
         [:div {:class "mt-3"}
          (render-errors errors)])))))

;;; ---------------------------------------------------------------------------
;;; Page Content
;;; ---------------------------------------------------------------------------

(defn- flows-skeleton
  "Skeleton loading state for flows page."
  []
  [:div
   (ui/page-header "Flow Monitor" "Connecting...")
   (ui/card
    [:div {:class "h-4 w-32 bg-base-700 rounded animate-skeleton mb-3"}]
    [:div {:class "h-20 w-full bg-base-700 rounded animate-skeleton"}])])

(defn- flows-content
  "Render the main flows page content."
  []
  (let [{::status/keys [flows alerts]} (status/collect-status)
        running-count (count (filter (fn [[_ s]] (= :running (::status/status s))) flows))]
    (h/html
     [:main#morph
      ;; Header
      (ui/page-header "Flow Monitor"
                      (str running-count " running"
                           (when (> (count flows) running-count)
                             (str " / " (count flows) " total"))))

      ;; Alerts banner
      (when (seq alerts)
        [:div {:class "mb-4 p-3 bg-warning/10 border border-warning/30 rounded"}
         (for [alert alerts]
           [:div {:class "flex items-center gap-2 text-xs font-mono text-warning"}
            [:span "!"]
            [:span (::status/message alert)]])])

      ;; Flow cards
      (if (seq flows)
        [:div {:class "space-y-4"}
         (for [entry (sort-by first flows)]
           (render-flow-card entry))]
        (ui/empty-state "No flows registered"
                        "Start a flow to see it here."))])))

;;; ---------------------------------------------------------------------------
;;; Handlers
;;; ---------------------------------------------------------------------------

(defn flows-page
  "Serve the flows monitor HTML shim page."
  [_request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (html/base-page
          {:title "Flow Monitor - Seon"
           :active-page :flows
           :skeleton (flows-skeleton)})})

(defn- flows-sse-render
  "Render function for flows SSE."
  [_request]
  (flows-content))

(def flows-sse
  (sse/render-handler #'flows-sse-render :poll-ms 1000))

;;; ---------------------------------------------------------------------------
;;; Hot Reload Support
;;; ---------------------------------------------------------------------------

(defn after-ns-reload
  "Called by clj-reload after namespace reload."
  []
  (alter-var-root #'flows-sse
                  (constantly (sse/render-handler #'flows-sse-render :poll-ms 1000))))
