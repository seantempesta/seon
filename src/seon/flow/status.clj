(ns seon.flow.status
  "Collects runtime status from all registered flows.

   State (throughput history, error windows, drain go-loops) lives inside a
   `:seon.flow/status-collector` process owned by the `:seon.flow/infrastructure`
   flow. The public API funcs are thin wrappers that inject request envelopes
   into the collector and await replies via `seon.flow.topology/pending-promises`.

   Synchronous wrappers follow the same pattern as `seon.repl/eval-via-flow!`:
   register a promise, `flow/inject`, deref with timeout, deliver from the
   reply-router."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [seon.flow.msg :as msg]
            [seon.runtime :as runtime]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
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
                                [::recent [:vector :map]]]]])

(schema/register! ::alert
  [:map
   [::flow-id :keyword]
   [::type [:enum :high-error-rate :process-paused :ping-failed]]
   [::message :string]])

(schema/register! ::op
  [:enum ::collect-flow-status ::collect-status])

(schema/register! ::collect-status-result
  [:map
   [::flows [:map-of :keyword ::flow-status]]
   [::alerts [:vector ::alert]]])

(def ^:private collector-pid :seon.flow/status-collector)
(def ^:private infra-flow-id :seon.flow/infrastructure)
(def ^:private max-errors-per-flow 100)
(def ^:private default-timeout-ms 5000)

;;; ---------------------------------------------------------------------------
;;; Pure helpers (used inside step-fn transform)
;;; ---------------------------------------------------------------------------

(defn- summarize-state
  "Extract first 3 keys of state for a compact summary."
  [state]
  (when (map? state)
    (into {} (take 3 state))))

(defn- compute-throughput
  "Return [new-prev-counts msgs-per-sec] given previous counts and current count."
  [prev-counts flow-id pid current-count ^Instant now]
  (let [prev (get-in prev-counts [flow-id pid])
        rate (when prev
               (let [dt-ms (- (.toEpochMilli now)
                              (.toEpochMilli ^Instant (:time prev)))
                     delta (- current-count (:count prev))]
                 (if (pos? dt-ms)
                   (double (/ (* delta 1000) dt-ms))
                   0.0)))
        prev' (assoc-in prev-counts [flow-id pid] {:count current-count :time now})]
    [prev' (or rate 0.0)]))

(defn- snapshot-flow
  "Synthesize a flow status map from a runtime handle. Returns
   [new-prev-counts status-map] or [prev-counts nil] if the flow is gone.

   Reads errors and prev-counts from the supplied collector state, returns the
   updated prev-counts for the caller to write back."
  [prev-counts errors-map flow-id]
  (if-let [handle (runtime/get-flow {::runtime/flow-id flow-id})]
    (let [fl (:flow handle)
          now (Instant/now)
          uptime-ms (.toMillis (Duration/between (:started-at handle) now))
          ping-result (try (flow/ping fl :timeout-ms 2000) (catch Exception _ nil))
          errors (get errors-map flow-id [])
          [prev' processes]
          (if ping-result
            (reduce (fn [[pc acc] [pid proc-status]]
                      (let [cnt (::flow/count proc-status)
                            [pc' rate] (if cnt
                                         (compute-throughput pc flow-id pid cnt now)
                                         [pc 0.0])]
                        [pc' (assoc acc pid
                                    {::pid pid
                                     ::status (or (::flow/status proc-status) :unknown)
                                     ::count (or cnt 0)
                                     ::msgs-per-sec rate
                                     ::state-summary (or (summarize-state
                                                          (::flow/state proc-status))
                                                         {})})]))
                    [prev-counts {}]
                    ping-result)
            [prev-counts {}])
          alerts (into []
                       (keep (fn [[pid ps]]
                               (when (= :paused (::status ps))
                                 {::flow-id flow-id
                                  ::type :process-paused
                                  ::message (str "Process " (name pid) " is paused")})))
                       processes)
          status-map (cond-> {::id flow-id
                              ::label (:label handle)
                              ::status (if ping-result :running :stopped)
                              ::uptime-ms uptime-ms
                              ::errors {::total (count errors)
                                        ::recent (vec (take-last 10 errors))}}
                       (seq processes) (assoc ::processes processes)
                       (seq alerts) (assoc ::alerts alerts))]
      [prev' status-map])
    [prev-counts nil]))

(defn- snapshot-all
  "Synthesize statuses for every registered flow. Returns
   [new-prev-counts {::flows ... ::alerts ...}]."
  [prev-counts errors-map]
  (let [flows (runtime/list-flows {})
        [prev' statuses]
        (reduce (fn [[pc acc] [fid _]]
                  (let [[pc' s] (snapshot-flow pc errors-map fid)]
                    [pc' (cond-> acc s (assoc fid s))]))
                [prev-counts {}]
                flows)
        all-alerts (into [] (mapcat (fn [[_ s]] (::alerts s [])) statuses))]
    [prev' {::flows statuses ::alerts all-alerts}]))

;;; ---------------------------------------------------------------------------
;;; Drain go-loop (lives in this JVM, injects into the collector via flow)
;;; ---------------------------------------------------------------------------

(defn- spawn-drain!
  "Read errors off `error-chan` and `flow/inject` them into the collector as
   :seon.flow.in/error-event. Returns the go-channel handle (for close!)."
  [fl flow-id error-chan]
  (async/go-loop []
    (when-let [err (async/<! error-chan)]
      (try
        (flow/inject fl [collector-pid :seon.flow.in/error-event]
                     [{::flow-id flow-id
                       ::error err
                       ::received-at (Instant/now)}])
        (catch Throwable t
          (log/warn t "Status drain failed to inject error" {:flow-id flow-id})))
      (recur))))

;;; ---------------------------------------------------------------------------
;;; Step-fn — :seon.flow/status-collector
;;; ---------------------------------------------------------------------------

(defn collector-step
  "Owns throughput history, sliding error windows, and active error-drain
   go-loops. State shape:

     {::prev-counts  {flow-id {pid {:count N :time Instant}}}
      ::errors       {flow-id [error-map ...]}   ;; max 100 per flow
      ::error-drains {flow-id <go-channel>}}

   Inputs:
     :seon.flow.in/request     - {::msg/id ..., ::msg/payload {::op ::collect-flow-status
                                                                ::id <flow-id>}}
                                 or {::op ::collect-status}. Replies via
                                 :seon.flow.out/reply for the reply-router.
     :seon.flow.in/control     - Local-only ctrl msgs:
                                 {:op :start-drain :flow-id k :error-chan ch}
                                 {:op :stop-drain  :flow-id k}
     :seon.flow.in/error-event - Errors injected by drain go-loops:
                                 {::flow-id k ::error <map> ::received-at Instant}

   Outputs:
     :seon.flow.out/reply - reply envelopes routed to the reply-router."
  ;; describe
  ([]
   {:ins {:seon.flow.in/request "Status collection request envelopes"
          :seon.flow.in/control "Local-only drain start/stop control"
          :seon.flow.in/error-event "Errors from drain go-loops"}
    :outs {:seon.flow.out/reply "Reply envelopes for reply-router"}
    :workload :compute})

  ;; init
  ([_args]
   {::prev-counts {}
    ::errors {}
    ::error-drains {}})

  ;; transition
  ([state transition]
   (case transition
     :clojure.core.async.flow/stop
     (do
       (doseq [[_ ch] (::error-drains state)]
         (try (async/close! ch) (catch Throwable _)))
       (assoc state ::error-drains {}))
     state))

  ;; transform
  ([state input-id v]
   (case input-id
     :seon.flow.in/control
     (let [{:keys [op flow-id error-chan]} v]
       (case op
         :start-drain
         (let [drains (::error-drains state)
               existing (get drains flow-id)
               _ (when existing (try (async/close! existing) (catch Throwable _)))
               fl (:flow (runtime/get-flow {::runtime/flow-id infra-flow-id}))
               ch (when (and fl error-chan)
                    (spawn-drain! fl flow-id error-chan))]
           [(cond-> state
              ch (assoc-in [::error-drains flow-id] ch))
            nil])

         :stop-drain
         (let [ch (get-in state [::error-drains flow-id])]
           (when ch (try (async/close! ch) (catch Throwable _)))
           [(-> state
                (update ::error-drains dissoc flow-id)
                (update ::errors dissoc flow-id)
                (update ::prev-counts dissoc flow-id))
            nil])

         [state nil]))

     :seon.flow.in/error-event
     (let [{::keys [flow-id error received-at]} v
           timestamped (assoc error ::received-at received-at)
           errors (::errors state)
           cur (get errors flow-id [])
           cur' (if (>= (count cur) max-errors-per-flow)
                  (conj (subvec cur 1) timestamped)
                  (conj cur timestamped))]
       [(assoc-in state [::errors flow-id] cur') nil])

     :seon.flow.in/request
     (let [request-id (::msg/id v)
           payload    (::msg/payload v)
           op         (::op payload)
           t0         (System/nanoTime)]
       (try
         (let [[prev' value]
               (case op
                 ::collect-flow-status
                 (let [[p s] (snapshot-flow (::prev-counts state)
                                            (::errors state)
                                            (::id payload))]
                   [p s])

                 ::collect-status
                 (snapshot-all (::prev-counts state) (::errors state)))

               elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
               reply {::msg/id request-id
                      ::msg/version 1
                      ::msg/type :reply
                      ::msg/status :ok
                      ::msg/value value
                      ::msg/from-ns "seon.flow.status"
                      ::msg/duration-ms elapsed-ms}]
           [(assoc state ::prev-counts prev')
            {:seon.flow.out/reply [reply]}])
         (catch Exception e
           (let [elapsed-ms (long (/ (- (System/nanoTime) t0) 1e6))
                 reply {::msg/id request-id
                        ::msg/version 1
                        ::msg/type :reply
                        ::msg/status :error
                        ::msg/error-type :execution
                        ::msg/error-class (.getName (class e))
                        ::msg/error-message (.getMessage e)
                        ::msg/from-ns "seon.flow.status"
                        ::msg/duration-ms elapsed-ms}]
             [state {:seon.flow.out/reply [reply]}]))))

     ;; unknown input
     [state nil])))

;;; ---------------------------------------------------------------------------
;;; Public API — synchronous wrappers
;;; ---------------------------------------------------------------------------

(defn- get-infra-flow
  []
  (:flow (runtime/get-flow {::runtime/flow-id infra-flow-id})))

(defn- pending-promises
  []
  @(requiring-resolve 'seon.flow.topology/pending-promises))

(defn- request-via-collector!
  "Inject a request envelope into the status-collector and block on the reply.
   Returns the ::msg/value or throws ex-info on error/timeout."
  [op payload-extra timeout-ms]
  (let [fl (get-infra-flow)]
    (when-not fl
      (throw (ex-info "Infrastructure flow not running"
                      {:fn "seon.flow.status/request-via-collector!"})))
    (let [pending (pending-promises)
          request-id (random-uuid)
          p (promise)
          request {::msg/id request-id
                   ::msg/version 1
                   ::msg/type :request
                   ::msg/from-ns "seon.flow.status"
                   ::msg/created-at (Instant/now)
                   ::msg/payload (merge {::op op} payload-extra)}]
      (swap! pending assoc request-id p)
      (try
        (flow/inject fl [collector-pid :seon.flow.in/request] [request])
        (let [reply (deref p timeout-ms ::timed-out)]
          (if (= reply ::timed-out)
            (do (swap! pending dissoc request-id)
                (throw (ex-info "Status request timed out"
                                {::msg/status :timeout
                                 ::msg/id request-id
                                 ::op op
                                 ::timeout-ms timeout-ms})))
            (case (::msg/status reply)
              :ok (::msg/value reply)
              (throw (ex-info (or (::msg/error-message reply) "Status request failed")
                              (select-keys reply [::msg/status ::msg/error-type
                                                  ::msg/error-class
                                                  ::msg/error-message]))))))
        (catch Exception e
          (swap! pending dissoc request-id)
          (throw e))))))

(schema/register! ::start-error-drain-request
  [:map
   [::id ::id]
   [::error-chan :seon.flow/dynamic]])

(schema/register! ::stop-error-drain-request
  [:map [::id ::id]])

(schema/register! ::collect-flow-status-request
  [:map [::id ::id]])

(defn start-error-drain!
  "Register an error-drain go-loop for `flow-id`. The loop reads from `error-chan`
   and forwards events into the collector process state.

   Request keys:
     ::id         - Flow identifier
     ::error-chan - Live core.async channel (a value within this JVM, not
                    serialized over the wire)

   Returns nil. The drain handle lives in collector state."
  {:malli/schema [:=> [:cat ::start-error-drain-request] :nil]}
  [{::keys [id error-chan]}]
  (when-let [fl (get-infra-flow)]
    (flow/inject fl [collector-pid :seon.flow.in/control]
                 [{:op :start-drain :flow-id id :error-chan error-chan}]))
  nil)

(defn stop-error-drain!
  "Stop an error-drain and drop its accumulated state.

   Request keys:
     ::id - Flow identifier

   Returns nil."
  {:malli/schema [:=> [:cat ::stop-error-drain-request] :nil]}
  [{::keys [id]}]
  (when-let [fl (get-infra-flow)]
    (flow/inject fl [collector-pid :seon.flow.in/control]
                 [{:op :stop-drain :flow-id id}]))
  nil)

(defn collect-flow-status
  "Status snapshot for a single flow.

   Request keys:
     ::id - Flow identifier

   Returns a flow-status map or nil if the flow is not registered."
  {:malli/schema [:=> [:cat ::collect-flow-status-request]
                  [:maybe ::flow-status]]}
  [{::keys [id]}]
  (request-via-collector! ::collect-flow-status {::id id} default-timeout-ms))

(defn collect-status
  "Snapshot all registered flows.

   Returns:
     {::flows  {flow-id -> flow-status}
      ::alerts [alert ...]}"
  {:malli/schema [:=> [:cat [:map]] ::collect-status-result]}
  [_]
  (request-via-collector! ::collect-status {} default-timeout-ms))
