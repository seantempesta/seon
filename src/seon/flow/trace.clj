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
  (:require [taoensso.timbre :as log]
            [seon.db :as db]
            [seon.db.schema :as dbs]
            [seon.db.tx :as tx]
            [seon.schema :as schema])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::trace-id
                  [:uuid {:description "Correlation ID for tracing a request through the flow"}])

(schema/register! ::session-id
                  [:string {:min 1 :description "Agent session ID (4-char hex)"}])

(schema/register! ::event
                  [:enum {:description "Flow event kind"}
                   :start :end :error :overload :forward :timeout])

(schema/register! ::fn
                  [:string {:min 1 :description "Fully qualified function name"}])

(schema/register! ::ns
                  [:string {:min 1 :description "Namespace"}])

(schema/register! ::elapsed-ms
                  [:int {:min 0 :description "Duration in milliseconds"}])

(schema/register! ::timestamp
                  [:inst {:description "Event timestamp"}])

(schema/register! ::status
                  [:enum {:description "Reply status"}
                   :ok :error :timeout :overload])

(schema/register! ::error-message
                  [:string {:description "Error description"}])

(schema/register! ::entity-type
                  [:keyword {:description "Entity type tag for flow events"}])

;;; ---------------------------------------------------------------------------
;;; Entity Schema & Datalevin Schema
;;; ---------------------------------------------------------------------------

(def entity-schema
  "Malli schema for flow trace entities. Single source of truth.
   Bridge derives all Datalevin types — no manual :db/valueType needed."
  [:map
   [::trace-id :uuid]
   [::session-id {:optional true} [:string {:min 1}]]
   [::event [:enum :start :end :error :overload :forward :timeout]]
   [::timestamp :inst]
   [::fn {:optional true} [:string {:min 1}]]
   [::ns {:optional true} [:string {:min 1}]]
   [::elapsed-ms {:optional true} [:int {:min 0}]]
   [::status {:optional true} [:enum :ok :error :timeout :overload]]
   [::error-message {:optional true} :string]
   [::entity-type :keyword]])

(dbs/register-entity-schema! "seon.flow.trace" entity-schema)

(def datalevin-schema
  "Datalevin schema for flow trace attributes.
   Derived from entity-schema via the bridge, merged with tx metadata."
  (merge (dbs/malli-map->datalevin-schema entity-schema)
         tx/datalevin-schema))

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
    (let [entity (cond-> {::trace-id trace-id
                          ::event event
                          ::timestamp (Date.)
                          ::entity-type :flow-event}
                   session-id (assoc ::session-id session-id)
                   fn (assoc ::fn fn)
                   ns (assoc ::ns ns)
                   elapsed-ms (assoc ::elapsed-ms elapsed-ms)
                   status (assoc ::status status)
                   error-message (assoc ::error-message error-message))]
      (db/transact! :seon.flow [entity])
      (log/trace "Persisted flow event" {:trace-id trace-id :event event :fn fn})
      true)
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
    (let [lim (or limit 100)
          results (db/query :seon.flow
                            '[:find (pull ?e [*]) ?ts
                              :in $ ?sid
                              :where
                              [?e :seon.flow.trace/entity-type :flow-event]
                              [?e :seon.flow.trace/session-id ?sid]
                              [?e :seon.flow.trace/timestamp ?ts]]
                            session-id)]
      (->> results
           (sort-by second)
           reverse
           (take lim)
           (mapv first)))
    (catch Exception e
      (log/warn "Failed to query flow events" {:session-id session-id :error (.getMessage e)})
      [])))
