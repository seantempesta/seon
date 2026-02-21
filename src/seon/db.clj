(ns seon.db
  "Seon's database API. Drop-in replacement for datalevin.core.

   Agents use this instead of d/transact! directly.
   Reads pass through directly. Writes are routed through
   flow-based writers for coordination and observability.

   API mirrors datalevin.core signatures exactly so agents
   don't need to learn new APIs. Positional args are intentional
   for drop-in compatibility — this is the one namespace where
   map-in/map-out does not apply."
  (:require [datalevin.core :as d]
            [malli.core :as m]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;;; --- Schemas ---

(def WriteStats
  "Schema for the write statistics map."
  (m/schema
   [:map
    [:total-writes :int]
    [:last-write-at [:maybe :any]]
    [:by-caller [:map-of [:maybe :string] :int]]]))

;;; --- State ---

(defonce ^:private write-stats
  (atom {:total-writes 0
         :last-write-at nil
         :by-caller {}}))

;;; --- Internal ---

(defn- caller-ns
  "Best-effort extraction of the calling namespace from the stack."
  []
  (let [frames (.getStackTrace (Thread/currentThread))]
    (->> frames
         (map #(.getClassName ^StackTraceElement %))
         (filter #(and (.startsWith ^String % "seon.")
                       (not (.startsWith ^String % "seon.db"))))
         first)))

;;; --- Public API (positional, mirrors datalevin.core) ---

(defn transact!
  "Transact data into conn. Tracks caller and write count for observability.
   Delegates to datalevin.core/transact! — will route through flow writers later."
  {:malli/schema [:=> [:cat :any [:sequential :any]] :any]}
  ([conn tx-data]
   (transact! conn tx-data nil))
  ([conn tx-data tx-meta]
   (let [caller (caller-ns)
         result (if tx-meta
                  (d/transact! conn tx-data tx-meta)
                  (d/transact! conn tx-data))]
     (swap! write-stats (fn [s]
                          (-> s
                              (update :total-writes inc)
                              (assoc :last-write-at (Instant/now))
                              (update-in [:by-caller caller] (fnil inc 0)))))
     (log/trace "transact!" {:caller caller :tx-count (count tx-data)})
     result)))

(defn q
  "Query the database. Pass-through to datalevin.core/q."
  {:malli/schema [:=> [:cat :any [:* :any]] :any]}
  [query & inputs]
  (apply d/q query inputs))

(defn pull
  "Pull an entity by selector and eid. Pass-through to datalevin.core/pull."
  {:malli/schema [:=> [:cat :any :any :any] :any]}
  [db selector eid]
  (d/pull db selector eid))

(defn pull-many
  "Pull multiple entities. Pass-through to datalevin.core/pull-many."
  {:malli/schema [:=> [:cat :any :any [:sequential :any]] :any]}
  [db selector eids]
  (d/pull-many db selector eids))

(defn entity
  "Get an entity by eid. Pass-through to datalevin.core/entity."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [db eid]
  (d/entity db eid))

(defn stats
  "Returns write statistics: total writes, last write time, writes by caller namespace."
  {:malli/schema [:=> [:cat] WriteStats]}
  []
  @write-stats)
