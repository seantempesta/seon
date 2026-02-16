(ns seon.flow.registry
  "Central registry for discovering all running flows.

   Flows register themselves after start and unregister on stop.
   The registry is the entry point for status collection and REPL helpers.

   Note: No :malli/schema on public functions because they deal with
   opaque flow objects and core.async channels that cannot be generated."
  (:require [seon.schema :as schema])
  (:import [java.time Instant]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::id
  [:keyword {:description "Flow identifier keyword"}])

(schema/register! ::flow
  [:any {:description "Flow object (opaque, from core.async.flow)"}])

(schema/register! ::chans
  [:map {:description "Channels returned from flow/start"}
   [:error-chan :any]
   [:report-chan :any]])

(schema/register! ::label
  [:string {:min 1 :description "Human-readable flow label"}])

(schema/register! ::started-at
  [:fn {:description "When the flow was started"
        :gen/fmap (fn [_] (Instant/now))
        :gen/schema :int}
   inst?])

(schema/register! ::entry
  [:map
   [::id ::id]
   [::flow ::flow]
   [::chans ::chans]
   [::label ::label]
   [::started-at ::started-at]])

;;; ---------------------------------------------------------------------------
;;; Registry State
;;; ---------------------------------------------------------------------------

(defonce ^:private *registry (atom {}))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn register!
  "Register a flow in the global registry.

   Request keys:
     ::id         - Keyword identifier for the flow
     ::flow       - The flow object (opaque, from core.async.flow)
     ::chans      - Map with :error-chan and :report-chan
     ::label      - Human-readable label
     ::started-at - Optional. Defaults to now.

   Returns the registered entry map."
  [{::keys [id flow chans label started-at]}]
  (let [entry {::id id
               ::flow flow
               ::chans chans
               ::label label
               ::started-at (or started-at (Instant/now))}]
    (swap! *registry assoc id entry)
    entry))

(defn unregister!
  "Remove a flow from the registry.

   Request keys:
     ::id - Flow identifier to remove

   Returns the removed entry, or nil if not found."
  [{::keys [id]}]
  (let [removed (get @*registry id)]
    (swap! *registry dissoc id)
    removed))

(defn list-flows
  "Return map of flow-id -> entry for all registered flows.

   Takes no arguments (registry is global state)."
  []
  @*registry)

(defn get-flow
  "Get a single flow entry by id.

   Request keys:
     ::id - Flow identifier

   Returns the entry map or nil."
  [{::keys [id]}]
  (get @*registry id))

(defn clear!
  "Remove all flows from the registry. For testing only."
  []
  (reset! *registry {}))
