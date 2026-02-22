(ns seon.flow.status
  "Collects runtime status from all registered flows.

   Provides on-demand status snapshots via flow/ping, throughput
   calculation from count deltas, and error accumulation from
   error channels.

   Note: No :malli/schema on public functions because they deal with
   opaque flow objects and core.async channels that cannot be generated."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [seon.runtime :as runtime]
            [seon.schema :as schema])
  (:import [java.time Duration Instant]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::id
  [:keyword {:description "Flow identifier for status lookup"}])

(schema/register! ::process-status
  [:map
   [::pid :keyword]
   [::status [:enum :running :paused :unknown]]
   [::count {:optional true} :int]
   [::msgs-per-sec {:optional true} :double]
   [::state-summary {:optional true} :map]])

(schema/register! ::flow-status
  [:map
   [::id ::id]
   [::label :string]
   [::status [:enum :running :stopped :error]]
   [::uptime-ms {:optional true} :int]
   [::processes {:optional true} [:map-of :keyword ::process-status]]
   [::errors {:optional true} [:map
                                [::total :int]
                                [::recent [:vector :any]]]]])

(schema/register! ::alert
  [:map
   [::flow-id :keyword]
   [::type [:enum :high-error-rate :process-paused :ping-failed]]
   [::message :string]])

;;; ---------------------------------------------------------------------------
;;; Internal State
;;; ---------------------------------------------------------------------------

;; Previous counts for throughput calculation: {flow-id {pid {:count N :time Instant}}}
(defonce ^:private *prev-counts (atom {}))

;; Accumulated errors per flow: {flow-id [{error-map} ...]}
;; Sliding window of last 100 errors per flow.
(defonce ^:private *errors (atom {}))

;; Active error drain loops: {flow-id <go-channel>}
(defonce ^:private *error-drains (atom {}))

(def ^:private max-errors-per-flow 100)

;;; ---------------------------------------------------------------------------
;;; Error Drain
;;; ---------------------------------------------------------------------------

(defn start-error-drain!
  "Start a background go-loop that drains the error-chan for a flow.
   Accumulates errors into *errors atom with a sliding window.

   Request keys:
     ::id        - Flow identifier
     ::error-chan - The error channel to drain

   Returns the go channel (for cleanup)."
  [{::keys [id error-chan]}]
  (when-let [existing (get @*error-drains id)]
    (async/close! existing))
  (let [ch (async/go-loop []
             (when-let [err (async/<! error-chan)]
               (let [timestamped (assoc err ::received-at (Instant/now))]
                 (swap! *errors update id
                        (fn [errs]
                          (let [errs (or errs [])]
                            (if (>= (count errs) max-errors-per-flow)
                              (conj (subvec errs 1) timestamped)
                              (conj errs timestamped))))))
               (recur)))]
    (swap! *error-drains assoc id ch)
    ch))

(defn stop-error-drain!
  "Stop the error drain for a flow.

   Request keys:
     ::id - Flow identifier"
  [{::keys [id]}]
  (when-let [ch (get @*error-drains id)]
    (async/close! ch))
  (swap! *error-drains dissoc id)
  (swap! *errors dissoc id)
  (swap! *prev-counts dissoc id)
  nil)

;;; ---------------------------------------------------------------------------
;;; Throughput Calculation
;;; ---------------------------------------------------------------------------

(defn- compute-throughput
  "Compute msgs/sec for a process given current and previous counts."
  [flow-id pid current-count now]
  (let [prev (get-in @*prev-counts [flow-id pid])
        result (when prev
                 (let [dt-ms (- (.toEpochMilli ^Instant now)
                                (.toEpochMilli ^Instant (:time prev)))
                       delta (- current-count (:count prev))]
                   (if (pos? dt-ms)
                     (double (/ (* delta 1000) dt-ms))
                     0.0)))]
    ;; Update prev counts
    (swap! *prev-counts assoc-in [flow-id pid] {:count current-count :time now})
    result))

(defn- summarize-state
  "Extract first 3 keys of state for a compact summary."
  [state]
  (when (map? state)
    (into {} (take 3 state))))

;;; ---------------------------------------------------------------------------
;;; Status Collection
;;; ---------------------------------------------------------------------------

(defn collect-flow-status
  "Status for a single flow.

   Request keys:
     ::id - Flow identifier

   Returns a status map or nil if flow not found."
  [{::keys [id]}]
  (when-let [handle (runtime/get-flow {::runtime/flow-id id})]
    (let [fl (:flow handle)
          now (Instant/now)
          uptime-ms (.toMillis (Duration/between (:started-at handle) now))
          ping-result (try (flow/ping fl :timeout-ms 2000) (catch Exception _ nil))
          errors (get @*errors id [])
          processes
          (when ping-result
            (into {}
                  (map (fn [[pid proc-status]]
                         (let [cnt (::flow/count proc-status)
                               rate (when cnt (compute-throughput id pid cnt now))]
                           [pid {::pid pid
                                 ::status (or (::flow/status proc-status) :unknown)
                                 ::count (or cnt 0)
                                 ::msgs-per-sec (or rate 0.0)
                                 ::state-summary (summarize-state (::flow/state proc-status))}])))
                  ping-result))
          alerts (into []
                       (keep (fn [[pid ps]]
                               (when (= :paused (::status ps))
                                 {::flow-id id
                                  ::type :process-paused
                                  ::message (str "Process " (name pid) " is paused")})))
                       processes)]
      (cond-> {::id id
               ::label (:label handle)
               ::status (if ping-result :running :stopped)
               ::uptime-ms uptime-ms
               ::errors {::total (count errors)
                         ::recent (take-last 10 errors)}}
        processes (assoc ::processes processes)
        (seq alerts) (assoc ::alerts alerts)))))

(defn collect-status
  "Snapshot all registered flows. Returns structured status map.

   Returns map with:
     ::flows  - Map of flow-id -> flow status
     ::alerts - Vector of all alerts across flows"
  []
  (let [flows (runtime/list-flows {})
        statuses (into {}
                       (map (fn [[id _]]
                              [id (collect-flow-status {::id id})]))
                       flows)
        all-alerts (into []
                         (mapcat (fn [[_ s]] (::alerts s [])))
                         statuses)]
    {::flows statuses
     ::alerts all-alerts}))
