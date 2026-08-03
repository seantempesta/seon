(ns seon.db
  "The one database namespace for all things Datahike. Reads and writes use
  an explicit immutable database value or connection, or, when custody is
  elided, the current connection of the calling agent's cluster (`*conn*`,
  bound per evaluation). Failures return flat `:seon.error` values."
  (:require [clojure.walk :as walk]
            [datahike.api :as d]
            [datahike.connector :as connector]
            [datahike.db.utils :as db.utils]
            [datahike.query :as query]
            [datahike.store :as datahike.store]
            [clojure.test.check.generators :as gen]
            [seon.error.refusal :as error.refusal]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]))

;;; ---------------------------------------------------------------------------
;;; Ambient custody and optional read evidence
;;; ---------------------------------------------------------------------------

(defn connection?
  "True for a live (unreleased) Datahike connection."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (connector/connection? value)
       (some? (:wrapped-atom value))
       (not= @(:wrapped-atom value) :released)))

(defn database-value?
  "True for any Datahike database value."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (db.utils/db? value))

(schema/register-core-predicate! 'seon.db/connection? connection?)
(schema/register-core-predicate! 'seon.db/database-value? database-value?)

(defn- fresh-connection
  []
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}]
    (d/create-database configuration)
    (d/connect configuration)))

(def connection-generator
  (gen/fmap (fn [_] (fresh-connection)) (gen/return nil)))

(def database-value-generator
  (gen/fmap
   (fn [variant]
     (let [database @(fresh-connection)]
       (case variant
         :current database
         :as-of (d/as-of database (:max-tx database))
         :since (d/since database 0)
         :history (d/history database))))
   (gen/elements [:current :as-of :since :history])))

(def ^:dynamic *conn*
  "The current cluster's live branch connection, bound by its owning pass."
  nil)

(def ^:dynamic *capture-context*
  "An optional invocation-local atom collecting Datahike read evidence."
  nil)

(defn- error-value
  [kind message data]
  {:seon.error/kind kind
   :seon.error/message message
   :seon.error/data data})

(defn- dependency-error
  [operation error]
  (error-value
   ::invalid-read
   (or (ex-message error) "Datahike refused the database read.")
   (cond-> {::operation operation
            ::exception-class (.getName (class error))}
     (map? (ex-data error))
     (assoc ::dependency-data (ex-data error)))))

(defn- error-value?
  [value]
  (and (map? value)
       (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))

(defn- resolve-database-value
  [connection]
  (try
    ;; Resolve latest exactly once at the public-call boundary.
    (d/db connection)
    (catch Throwable cause
      (dependency-error ::db cause))))

(defn- current-database-value
  []
  (if (nil? *conn*)
    (error-value
     ::missing-connection-binding
     "No current cluster connection is bound to seon.db/*conn*."
     {::binding 'seon.db/*conn*})
    (resolve-database-value *conn*)))

(defn- current-connection
  []
  (if (nil? *conn*)
    (error-value
     ::missing-connection-binding
     "No current cluster connection is bound to seon.db/*conn*."
     {::binding 'seon.db/*conn*})
    *conn*))

(defn- connection-id
  [connection]
  (datahike.store/connection-id (:config @connection)))

(defn- foreign-connection-error
  [connection]
  (when (some? *conn*)
    (let [ambient-connection-id (connection-id *conn*)
          explicit-connection-id (connection-id connection)]
      (when-not (= ambient-connection-id explicit-connection-id)
        (error-value
         ::foreign-connection
         (str "The explicit transaction connection does not belong to "
              "the calling agent's cluster.")
         {::ambient-connection-id ambient-connection-id
          ::explicit-connection-id explicit-connection-id})))))

(defn- append-read-evidence!
  [evidence]
  (when *capture-context*
    (swap! *capture-context* conj evidence))
  nil)

(defn- append-database-evidence!
  [database plan]
  (when (db.utils/db? database)
    (append-read-evidence!
     {:seon.db/db database
      :seon.db/source-argument-position 0
      :datahike.read/dependency-plan plan})))

(defn- append-query-evidence!
  [arguments response]
  (when *capture-context*
    (let [plan (:datahike.read/dependency-plan response)
          positions
          (if (= :all plan)
            (keep-indexed
             (fn [position argument]
               (when (db.utils/db? argument) position))
             arguments)
            (map :datahike.query.source/argument-position
                 (:datahike.query.dependency/sources plan)))]
      (doseq [position (distinct positions)
              :let [database (nth arguments position nil)]
              :when (db.utils/db? database)]
        (append-read-evidence!
         {:seon.db/db database
          :seon.db/source-argument-position position
          :datahike.read/dependency-plan plan}))))
  nil)

(defn- append-pull-evidence!
  [database response]
  (append-database-evidence!
   database
   (:datahike.read/dependency-plan response)))

;;; ---------------------------------------------------------------------------
;;; Reads over immutable database values
;;; ---------------------------------------------------------------------------

(defn db
  "Current immutable database value for an explicit or ambient connection."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :seon.db/database-value :seon.error/value]]
    [:=> [:cat :seon.db/connection]
     [:or :seon.db/database-value :seon.error/value]]]}
  ([]
   (current-database-value))
  ([connection]
   (resolve-database-value connection)))

(defn- aligned-query-arguments
  [explicit-database query-form arguments]
  (let [arguments (vec arguments)
        input-count (d/query-input-count query-form)
        source-bindings (d/query-source-bindings query-form)
        default-sources
        (filterv #(= '$ (:datahike.query.source/symbol %))
                 source-bindings)]
    (cond
      (= input-count (count arguments))
      arguments

      (and (= input-count (inc (count arguments)))
           (= 1 (count default-sources)))
      (let [position
            (:datahike.query.source/argument-position
             (first default-sources))
            database (or explicit-database (current-database-value))]
        (if (error-value? database)
          database
          (when (<= 0 position (count arguments))
            (into (conj (subvec arguments 0 position) database)
                  (subvec arguments position)))))

      :else nil)))

(defn q
  "Run a Datalog query over explicit inputs or the current database value."
  {:malli/schema
   [:=>
    [:catn
     [::query-or-database
      [:or
       :seon.db/database-value
       :seon.db/query
       :seon.db/query-args]]
     [::arguments [:* :seon.schema/value]]]
    [:or :seon.schema/value :seon.error/value]]}
  [query-or-database & arguments]
  (let [explicit-database? (db.utils/db? query-or-database)
        query-input
        (if explicit-database?
          (first arguments)
          query-or-database)
        argument-inputs
        (if explicit-database?
          (rest arguments)
          arguments)]
    (try
      ;; This disambiguates a Datalog map query from Datahike's argument map
      ;; before Seon decides where the ambient database belongs.
      (let [normalized (query/normalize-q-input query-input argument-inputs)
            aligned
            (aligned-query-arguments
             (when explicit-database? query-or-database)
             (:query normalized)
             (:args normalized))]
        (cond
          (error-value? aligned)
          aligned

          aligned
          (let [request (assoc normalized :args aligned)
                response (d/q-with-evidence request)]
            (append-query-evidence! aligned response)
            (:datahike.query/result response))

          :else
          (error-value
           ::invalid-read
           "The query arguments do not align with its declared inputs."
           {::query-form (:query normalized)
            ::argument-count (count (:args normalized))})))
      (catch Throwable cause
        (when explicit-database?
          (append-database-evidence! query-or-database :all))
        (dependency-error ::q cause)))))

(defn- pull-call
  [database arguments operation result-key]
  (if (error-value? database)
    database
    (try
      (let [response (apply operation database arguments)]
        (append-pull-evidence! database response)
        (get response result-key))
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error result-key cause)))))

(defn pull
  "Pull one entity over an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/pull-options]
     [:or :nil :map :seon.error/value]]
    [:=> [:cat
          [:or :seon.db/database-value :seon.db/pull-selector]
          [:or :seon.db/pull-options :seon.db/entity-id]]
     [:or :nil :map :seon.error/value]]
    [:=>
     [:cat :seon.db/database-value
      :seon.db/pull-selector
      :seon.db/entity-id]
     [:or :nil :map :seon.error/value]]]}
  ([options]
   (pull-call (current-database-value)
              [options]
              d/pull-with-evidence
              :datahike.pull/result))
  ([database-or-selector options-or-eid]
   (if (db.utils/db? database-or-selector)
     (pull-call database-or-selector
                [options-or-eid]
                d/pull-with-evidence
                :datahike.pull/result)
     (pull-call (current-database-value)
                [database-or-selector options-or-eid]
                d/pull-with-evidence
                :datahike.pull/result)))
  ([database selector entity-id]
   (pull-call database
              [selector entity-id]
              d/pull-with-evidence
              :datahike.pull/result)))

(defn pull-many
  "Pull aligned entities over an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/pull-many-options]
     [:or [:vector [:or :nil :map]] :seon.error/value]]
    [:=>
     [:cat
      [:or :seon.db/database-value :seon.db/pull-selector]
      [:or :seon.db/pull-many-options
       [:sequential :seon.db/entity-id]]]
     [:or [:vector [:or :nil :map]] :seon.error/value]]
    [:=>
     [:cat :seon.db/database-value
      :seon.db/pull-selector
      [:sequential :seon.db/entity-id]]
     [:or [:vector [:or :nil :map]] :seon.error/value]]]}
  ([options]
   (pull-call (current-database-value)
              [options]
              d/pull-many-with-evidence
              :datahike.pull-many/result))
  ([database-or-selector options-or-eids]
   (if (db.utils/db? database-or-selector)
     (pull-call database-or-selector
                [options-or-eids]
                d/pull-many-with-evidence
                :datahike.pull-many/result)
     (pull-call (current-database-value)
                [database-or-selector options-or-eids]
                d/pull-many-with-evidence
                :datahike.pull-many/result)))
  ([database selector entity-ids]
   (pull-call database
              [selector entity-ids]
              d/pull-many-with-evidence
              :datahike.pull-many/result)))

(defn- entity-call
  [database entity-id]
  ;; Wildcard pull is Datahike's eager ordinary-data form: component refs
  ;; expand recursively and ordinary refs remain plain {:db/id ...} maps.
  (pull-call database
             [['*] entity-id]
             d/pull-with-evidence
             :datahike.pull/result))

(defn entity
  "Eager ordinary data for one entity in an explicit or current database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/entity-id]
     [:or :nil :map :seon.error/value]]
    [:=> [:cat :seon.db/database-value :seon.db/entity-id]
     [:or :nil :map :seon.error/value]]]}
  ([entity-id]
   (entity-call (current-database-value) entity-id))
  ([database entity-id]
   (entity-call database entity-id)))

(defn- datom->data
  [datom]
  {:e (:e datom)
   :a (:a datom)
   :v (:v datom)
   :tx (:tx datom)
   :added (:added datom)})

(defn- datoms-call
  [database arguments]
  (if (error-value? database)
    database
    (try
      ;; Datahike's index cursor is lazy and each element is a host Datom.
      ;; Realize both layers here so no process-local cursor escapes to SCI.
      (let [result (mapv datom->data (apply d/datoms database arguments))]
        (append-database-evidence! database :all)
        result)
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error ::datoms cause)))))

(defn datoms
  "Eager ordinary datoms from an explicit or current database value."
  {:malli/schema
   [:=>
    [:cat
     [:or :seon.db/database-value :seon.db/index-lookup :keyword]
     [:* :seon.schema/value]]
    [:or :seon.db/datoms :seon.error/value]]}
  [database-or-index & arguments]
  (if (db.utils/db? database-or-index)
    (datoms-call database-or-index arguments)
    (datoms-call (current-database-value)
                 (cons database-or-index arguments))))

(defn- database-view
  [operation database arguments]
  (if (error-value? database)
    database
    (try
      (let [result (apply operation database arguments)]
        (append-database-evidence! database :all)
        result)
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error ::temporal-read cause)))))

(defn history
  "Historical view of an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :seon.db/database-value :seon.error/value]]
    [:=> [:cat :seon.db/database-value]
     [:or :seon.db/database-value :seon.error/value]]]}
  ([]
   (database-view d/history (current-database-value) []))
  ([database]
   (database-view d/history database [])))

(defn as-of
  "Database view at a time point from an explicit or current database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/time-point]
     [:or :seon.db/database-value :seon.error/value]]
    [:=> [:cat :seon.db/database-value :seon.db/time-point]
     [:or :seon.db/database-value :seon.error/value]]]}
  ([time-point]
   (database-view d/as-of (current-database-value) [time-point]))
  ([database time-point]
   (database-view d/as-of database [time-point])))

(defn since
  "Database view since a time point from an explicit or current database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/time-point]
     [:or :seon.db/database-value :seon.error/value]]
    [:=> [:cat :seon.db/database-value :seon.db/time-point]
     [:or :seon.db/database-value :seon.error/value]]]}
  ([time-point]
   (database-view d/since (current-database-value) [time-point]))
  ([database time-point]
   (database-view d/since database [time-point])))

;;; ---------------------------------------------------------------------------
;;; Writes through the one synchronous transaction boundary
;;; ---------------------------------------------------------------------------

(defn- panic-on-core-error?
  [connection]
  (= :panic
     (d/q '[:find ?mode .
            :where [_ :seon.config/on-core-error ?mode]]
          (d/db connection))))

(defn- jdk-integers->long
  [transaction]
  (walk/postwalk
   (fn [value]
     (if (instance? Integer value)
       (long value)
       value))
   transaction))

(defn- transact-call
  [connection transaction]
  (if (error-value? connection)
    connection
    (try
      (d/transact connection
                  (schema.datahike/encode-transaction
                   (jdk-integers->long transaction)))
      (catch Throwable throwable
        (let [data (error.refusal/refusal throwable)]
          (cond
            ;; A Seon transition refusal returns its own value verbatim.
            (some? (:seon.error/kind data))
            data

            ;; A Datahike abort keeps the dependency's classification.
            (some? (:error data))
            {:seon.error/kind :seon.db/rejected
             :seon.error/message
             (or (ex-message throwable) "transaction rejected")
             :seon.error/data data}

            :else
            (let [failure
                  {:seon.error/kind :seon.db/unknown-failure
                   :seon.error/message
                   (or (ex-message throwable)
                       (.getName (class throwable)))
                   :seon.error/data (or data {})}]
              (when (panic-on-core-error? connection)
                (throw
                 (ex-info (:seon.error/message failure)
                          failure
                          throwable)))
              failure)))))))

(defn transact!
  "Commit a transaction through an explicit or ambient connection.

  When `*conn*` is bound, an explicit connection must have the same Datahike
  connection ID. An absent binding means the caller is outside an agent
  evaluation, so a live explicit connection is allowed."
  {:malli/schema
   [:function
    [:=> [:cat :seon.store/transaction]
     [:or :map :seon.error/value]]
    [:=> [:cat :seon.db/connection :seon.store/transaction]
     [:or :map :seon.error/value]]]}
  ([transaction]
   (transact-call (current-connection) transaction))
  ([connection transaction]
   (cond
     (not (connection? connection))
     (dependency-error
      ::transact!
      (ex-info "The explicit transaction connection is not live."
               {::connection connection}))

     :else
     (or (foreign-connection-error connection)
         (transact-call connection transaction)))))
