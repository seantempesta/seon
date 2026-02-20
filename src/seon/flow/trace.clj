(ns seon.flow.trace
  "Flow event tracing and persistence.

   Persists flow events (request forwarding, replies, errors, overloads)
   to Datalevin for querying in Observatory. Provides query functions
   for the agent detail view.

   Events are stored with:
     :seon.flow.trace/trace-id   - Correlation UUID
     :seon.flow.trace/session-id - Agent session ID (4-char hex)
     :seon.flow.trace/event      - Event kind (:start, :end, :error, :overload, :forward)
     :seon.flow.trace/fn         - Function name
     :seon.flow.trace/ns         - Namespace
     :seon.flow.trace/elapsed-ms - Duration (when available)
     :seon.flow.trace/timestamp  - When event occurred
     :seon.flow.trace/status     - Reply status (:ok, :error, etc.)
     :seon.flow.trace/error-message - Error message (when applicable)"
  (:require [clojure.tools.logging :as log]
            [integrant.repl.state :as state]
            [seon.db.datalevin.conn :as conn]
            [seon.schema :as schema])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::trace-id
  [:uuid {:description "Correlation ID for tracing a request through the flow"}])

(schema/register! ::session-id
  [:string {:min 1 :description "Agent session ID (4-char hex)"}])

(schema/register! ::event
  [:enum :start :end :error :overload :forward :timeout
   {:description "Flow event kind"}])

(schema/register! ::fn
  [:string {:min 1 :description "Fully qualified function name"}])

(schema/register! ::ns
  [:string {:min 1 :description "Namespace"}])

(schema/register! ::elapsed-ms
  [:int {:min 0 :description "Duration in milliseconds"}])

(schema/register! ::timestamp
  [:fn {:description "Event timestamp"
        :gen/fmap (fn [_] (Instant/now))
        :gen/schema :int}
   inst?])

(schema/register! ::status
  [:enum :ok :error :timeout :overload
   {:description "Reply status"}])

(schema/register! ::error-message
  [:string {:description "Error description"}])

;;; ---------------------------------------------------------------------------
;;; Connection
;;; ---------------------------------------------------------------------------

(defn- get-conn
  "Get master Datalevin connection from Integrant system."
  []
  (when-let [mgr (:seon/connection-manager state/system)]
    (try
      (conn/get-master-conn! {::conn/manager mgr})
      (catch Exception e
        (log/warn "Failed to get Datalevin connection for flow trace" {:error (.getMessage e)})
        nil))))

;;; ---------------------------------------------------------------------------
;;; Persistence
;;; ---------------------------------------------------------------------------

(defn persist-event!
  "Persist a flow event to Datalevin. Fire-and-forget: errors logged but not thrown.

   Request keys:
     ::trace-id     - Required. Correlation UUID
     ::session-id   - Optional. Agent session ID
     ::event        - Required. Event kind keyword
     ::fn           - Optional. Function name
     ::ns           - Optional. Namespace
     ::elapsed-ms   - Optional. Duration
     ::status       - Optional. Reply status
     ::error-message - Optional. Error description

   Returns true if persisted, false on error or no connection."
  [{::keys [trace-id session-id event fn ns elapsed-ms status error-message]}]
  (try
    (when-let [conn (get-conn)]
      (require 'datalevin.core)
      (let [transact! (resolve 'datalevin.core/transact!)
            entity (cond-> {::trace-id trace-id
                            ::event event
                            ::timestamp (Instant/now)
                            :seon.flow.trace/entity-type :flow-event}
                     session-id (assoc ::session-id session-id)
                     fn (assoc ::fn fn)
                     ns (assoc ::ns ns)
                     elapsed-ms (assoc ::elapsed-ms elapsed-ms)
                     status (assoc ::status status)
                     error-message (assoc ::error-message error-message))]
        (transact! conn [entity])
        (log/trace "Persisted flow event" {:trace-id trace-id :event event :fn fn})
        true))
    (catch Exception e
      (log/warn "Failed to persist flow event" {:trace-id trace-id :event event :error (.getMessage e)})
      false)))

;;; ---------------------------------------------------------------------------
;;; Queries
;;; ---------------------------------------------------------------------------

(defn events-for-session
  "Query flow events for a given agent session ID, ordered by timestamp.

   Request keys:
     ::session-id - Required. Agent session ID (4-char hex)
     ::limit      - Optional. Max results (default 100)

   Returns vector of flow event maps, newest first."
  [{::keys [session-id limit]}]
  (try
    (when-let [conn (get-conn)]
      (require 'datalevin.core)
      (let [q-fn (resolve 'datalevin.core/q)
            pull (resolve 'datalevin.core/pull)
            lim (or limit 100)
            eids (q-fn '[:find ?e ?ts
                         :in $ ?sid
                         :where
                         [?e :seon.flow.trace/entity-type :flow-event]
                         [?e :seon.flow.trace/session-id ?sid]
                         [?e :seon.flow.trace/timestamp ?ts]]
                       @conn session-id)
            sorted (->> eids
                        (sort-by second)
                        reverse
                        (take lim))]
        (mapv (fn [[eid _]] (pull @conn '[*] eid)) sorted)))
    (catch Exception e
      (log/warn "Failed to query flow events" {:session-id session-id :error (.getMessage e)})
      [])))
