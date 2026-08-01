(ns seon.db
  "The synchronous application read facade over one Datahike database value."
  (:require [datahike.api :as d]
            [datahike.db.utils :as db.utils]))

;;; ---------------------------------------------------------------------------
;;; Ambient custody and optional read evidence
;;; ---------------------------------------------------------------------------

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

(defn- current-database-value
  []
  (if (nil? *conn*)
    (error-value
     ::missing-connection-binding
     "No current cluster connection is bound to seon.db/*conn*."
     {::binding 'seon.db/*conn*})
    (try
      ;; Resolve latest exactly once at the public-call boundary.
      (d/db *conn*)
      (catch Throwable error
        (dependency-error ::resolve-current-database error)))))

(defn- append-read-evidence!
  [evidence]
  (when *capture-context*
    (swap! *capture-context* conj evidence))
  nil)

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
  (append-read-evidence!
   {:seon.db/db database
    :seon.db/source-argument-position 0
    :datahike.read/dependency-plan
    (:datahike.read/dependency-plan response)}))

;;; ---------------------------------------------------------------------------
;;; Reads over one immutable database value
;;; ---------------------------------------------------------------------------

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
  "Run a Datalog query over an explicit or current database value."
  {:malli/schema
   [:=>
    [:catn
     [::query-or-database
      [:or
       :seon.db/database-value
       [:sequential :seon.schema/value]
       :map
       :string]]
     [::arguments [:* :seon.schema/value]]]
    [:or :seon.schema/value :seon.error/value]]}
  [query-or-database & arguments]
  (let [explicit-database? (db.utils/db? query-or-database)
        query-form
        (if explicit-database?
          (first arguments)
          query-or-database)
        arguments
        (if explicit-database?
          (rest arguments)
          arguments)]
    (try
      (let [aligned
            (aligned-query-arguments
             (when explicit-database? query-or-database)
             query-form
             arguments)]
        (cond
          (error-value? aligned)
          aligned

          aligned
          (let [response (apply d/q-with-evidence query-form aligned)]
            (append-query-evidence! aligned response)
            (:datahike.query/result response))

          :else
          (error-value
           ::invalid-read
           "The query arguments do not align with its declared inputs."
           {::query-form query-form
            ::argument-count (count arguments)})))
      (catch Throwable error
        (when explicit-database?
          (append-read-evidence!
           {:seon.db/db query-or-database
            :seon.db/source-argument-position 0
            :datahike.read/dependency-plan :all}))
        (dependency-error ::q error)))))

(defn- pull*
  [database pattern entity-id]
  (if (error-value? database)
    database
    (try
      (let [response (d/pull-with-evidence database pattern entity-id)]
        (append-pull-evidence! database response)
        (:datahike.pull/result response))
      (catch Throwable error
        (append-read-evidence!
         {:seon.db/db database
          :seon.db/source-argument-position 0
          :datahike.read/dependency-plan :all})
        (dependency-error ::pull error)))))

(defn pull
  "Pull one entity over an explicit or current database value."
  {:malli/schema
   [:function
    [:=>
     [:cat
      [:vector :seon.schema/value]
      :seon.schema/value]
     [:or :nil :map :seon.error/value]]
    [:=>
     [:cat
      :seon.db/database-value
      [:vector :seon.schema/value]
      :seon.schema/value]
     [:or :nil :map :seon.error/value]]]}
  ([pattern entity-id]
   (pull* (current-database-value) pattern entity-id))
  ([database pattern entity-id]
   (pull* database pattern entity-id)))

(defn- pull-many*
  [database pattern entity-ids]
  (if (error-value? database)
    database
    (try
      (let [response
            (d/pull-many-with-evidence database pattern entity-ids)]
        (append-pull-evidence! database response)
        (:datahike.pull-many/result response))
      (catch Throwable error
        (append-read-evidence!
         {:seon.db/db database
          :seon.db/source-argument-position 0
          :datahike.read/dependency-plan :all})
        (dependency-error ::pull-many error)))))

(defn pull-many
  "Pull input-aligned entities over an explicit or current database value."
  {:malli/schema
   [:function
    [:=>
     [:cat
      [:vector :seon.schema/value]
      [:sequential :seon.schema/value]]
     [:or [:vector [:or :nil :map]] :seon.error/value]]
    [:=>
     [:cat
      :seon.db/database-value
      [:vector :seon.schema/value]
      [:sequential :seon.schema/value]]
     [:or [:vector [:or :nil :map]] :seon.error/value]]]}
  ([pattern entity-ids]
   (pull-many* (current-database-value) pattern entity-ids))
  ([database pattern entity-ids]
   (pull-many* database pattern entity-ids)))
