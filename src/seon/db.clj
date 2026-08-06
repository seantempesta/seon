(ns seon.db
  "The one database namespace for all things Datahike. Reads and writes use
  an explicit immutable database value or connection, or, when custody is
  elided, the current connection of the calling agent's cluster (`*conn*`,
  bound per evaluation). Failures return flat `:seon.error` values."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [datahike.api :as d]
            [datahike.connector :as connector]
            [datahike.db.interface :as dbi]
            [datahike.db.utils :as db.utils]
            [datahike.query :as query]
            [datahike.store :as datahike.store]
            [datalog.parser.impl.proto :as parser]
            [datalog.parser.pull :as pull.parser]
            [clojure.test.check.generators :as gen]
            [seon.error.refusal :as error.refusal]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike])
  (:import [datalog.parser.type BindScalar Constant FindColl FindRel FindScalar
            FindTuple Pattern Variable]))

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

(def ^:dynamic *read-evidence-sink*
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

(defn connection-identity
  "Plain-data identity of a live Datahike connection."
  {:malli/schema
   [:=> [:cat [:or :seon.db/connection :seon.error/value]]
    [:or :seon.db/connection-identity :seon.error/value]]}
  [connection]
  (if (error-value? connection)
    connection
    {:datahike/connection-id (connection-id connection)}))

(defn database-value-identity
  "Plain-data identity of an immutable Datahike database value."
  {:malli/schema
   [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
    [:or :seon.db/database-value-identity :seon.error/value]]}
  [database]
  (if (error-value? database)
    database
    (let [configuration (dbi/-config database)]
      {:db-name (:branch configuration)
       :t (dbi/-max-tx database)
       :datahike/commit-id (d/commit-id database)})))

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
  (when *read-evidence-sink*
    (swap! *read-evidence-sink* conj evidence))
  nil)

(defn- append-database-evidence!
  [database plan]
  (when (db.utils/db? database)
    (append-read-evidence!
     {:seon.db/db database
      :seon.db/source-argument-position 0
      :datahike.read/dependency-plan plan})))

(defn- stable-read-result
  [result]
  (walk/postwalk (fn [value]
                   (if (map? value)
                     (dissoc value :seon.db/db)
                     value))
                 result))

(defn- append-query-evidence!
  [request response result]
  (when *read-evidence-sink*
    (let [arguments (:args request)
          database-positions (into []
                                   (keep-indexed
                                    (fn [position argument]
                                      (when (db.utils/db? argument) position)))
                                   arguments)
          replayable? (= 1 (count database-positions))
          replay-request
          (when replayable?
            (update request :args
                    (fn [values]
                      (mapv (fn [value]
                              (if (db.utils/db? value) ::database value))
                            values))))
          plan (:datahike.read/dependency-plan response)
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
         (cond->
          {:seon.db/db database
           :seon.db/source-argument-position position
           :datahike.read/dependency-plan plan}
           replayable?
           (assoc :seon.db/read-request
                  {:seon.db/read-operation :q
                   :seon.db/query-request replay-request}
                  :seon.db/read-result (stable-read-result result)))))))
  nil)

(defn- append-pull-evidence!
  [database arguments operation-key response result]
  (append-read-evidence!
   {:seon.db/db database
    :seon.db/source-argument-position 0
    :datahike.read/dependency-plan (:datahike.read/dependency-plan response)
    :seon.db/read-request {:seon.db/read-operation operation-key
                           :seon.db/pull-arguments arguments}
    :seon.db/read-result (stable-read-result result)}))

(defn- dependency-revision
  [database plan source-position]
  (let [context (:cache-context database)
        attributes (d/dependency-plan-attributes plan source-position)
        source-identity (select-keys context
                                     [:datahike.cache/connection-id
                                      :datahike.cache/generation])]
    (if (= :all attributes)
      (assoc source-identity
             :datahike.read/attributes :all
             :datahike.read/revision
             (:datahike.cache/commit-id context))
      (cond->
       (assoc source-identity
              :datahike.read/attributes attributes
              :datahike.cache/attribute-revisions
              (select-keys (:datahike.cache/attribute-revisions context)
                           attributes))
        (:datahike.cache/conservative-revision context)
        (assoc :datahike.cache/conservative-revision
               (:datahike.cache/conservative-revision context))))))

(defn read-evidence
  "Retain dependency revisions without retaining database values."
  {:malli/schema [:=> [:cat [:vector :seon.db/captured-read]]
                  [:vector :seon.db/read-evidence]]}
  [captured]
  (mapv (fn [{database :seon.db/db
              source-position :seon.db/source-argument-position
              plan :datahike.read/dependency-plan
              :as entry}]
          (cond->
           {:seon.db/source-argument-position source-position
            :datahike.read/dependency-plan plan
            :datahike.read/revision
            (dependency-revision database plan source-position)}
            (:seon.db/read-request entry)
            (assoc :seon.db/read-request (:seon.db/read-request entry)
                   :seon.db/read-result (:seon.db/read-result entry))))
        captured))

(declare decode-query-result decode-pull-result pull-selector)

(defn- replay-read
  [database request]
  (case (:seon.db/read-operation request)
    :q
    (let [query-request
          (update (:seon.db/query-request request) :args
                  (fn [arguments]
                    (mapv #(if (= ::database %) database %) arguments)))
          response (d/q-with-evidence query-request)]
      (decode-query-result query-request
                           (:datahike.query/result response)))

    :pull
    (let [arguments (:seon.db/pull-arguments request)
          response (apply d/pull-with-evidence database arguments)]
      (decode-pull-result (pull-selector arguments)
                          :datahike.pull/result
                          (:datahike.pull/result response)))

    :pull-many
    (let [arguments (:seon.db/pull-arguments request)
          response (apply d/pull-many-with-evidence database arguments)]
      (decode-pull-result (pull-selector arguments)
                          :datahike.pull-many/result
                          (:datahike.pull-many/result response)))))

(defn read-evidence-current?
  "True when `database` still satisfies every retained dependency revision."
  {:malli/schema [:=> [:cat [:or :seon.db/database-value :seon.error/value]
                       [:vector :seon.db/read-evidence]]
                  [:or :boolean :seon.error/value]]}
  [database retained]
  (if (error-value? database)
    database
    (every?
     (fn [{source-position :seon.db/source-argument-position
           plan :datahike.read/dependency-plan
           revision :datahike.read/revision
           :as evidence}]
       (or (= revision (dependency-revision database plan source-position))
           (when-let [request (:seon.db/read-request evidence)]
             (try
               (= (:seon.db/read-result evidence)
                  (stable-read-result (replay-read database request)))
               (catch Throwable _ false)))))
     retained)))

(defn- decode-attribute-value
  [attribute value]
  (schema.datahike/decode-attribute-value attribute value))

(defn- decode-attribute-maps
  [value]
  (cond
    (map? value)
    (reduce-kv
     (fn [decoded attribute child]
       (assoc decoded attribute
              (if (and (keyword? attribute)
                       (schema.datahike/edn-encoded-attr? attribute))
                (decode-attribute-value attribute child)
                (decode-attribute-maps child))))
     (empty value)
     value)

    (vector? value)
    (mapv decode-attribute-maps value)

    (set? value)
    (into #{} (map decode-attribute-maps) value)

    (sequential? value)
    (doall (map decode-attribute-maps value))

    :else value))

(defn- parsed-nodes
  [root]
  (tree-seq #(or (map? %) (coll? %))
            #(cond
               (map? %) (vals %)
               (coll? %) %
               :else nil)
            root))

(defn- query-input-bindings
  [parsed-query arguments]
  (into {}
        (keep (fn [[input-binding value]]
                (let [variable (:variable input-binding)]
                  (when (and (instance? BindScalar input-binding)
                             (instance? Variable variable))
                    [(:symbol variable) value]))))
        (map vector (:qin parsed-query) arguments)))

(defn- query-variable-attributes
  [parsed-query arguments]
  (let [input-bindings (query-input-bindings parsed-query arguments)]
    (reduce
     (fn [attributes pattern]
       (let [attribute-node (nth (:pattern pattern) 1 nil)
             value-node (nth (:pattern pattern) 2 nil)
             attribute (if (instance? Constant attribute-node)
                         (:value attribute-node)
                         (get input-bindings (:symbol attribute-node)))
             variable (:symbol value-node)]
         (if (and (keyword? attribute)
                  (instance? Variable value-node))
           (update attributes variable (fnil conj #{}) attribute)
           attributes)))
     {}
     (filter #(instance? Pattern %) (parsed-nodes (:qwhere parsed-query))))))

(defn- query-find-attributes
  [parsed-query arguments]
  (let [variable-attributes
        (query-variable-attributes parsed-query arguments)]
    (mapv
     (fn [element]
       (when (instance? Variable element)
         (let [attributes (get variable-attributes (:symbol element))]
           (when (= 1 (count attributes))
             (let [attribute (first attributes)]
               (when (schema.datahike/edn-encoded-attr? attribute)
                 attribute))))))
     (parser/find-elements (:qfind parsed-query)))))

(defn- decode-query-field
  [attribute value]
  (if (and attribute (some? value))
    (decode-attribute-value attribute value)
    (decode-attribute-maps value)))

(defn- decode-query-tuple
  [attributes tuple]
  (mapv decode-query-field attributes tuple))

(defn- query-return-map-keys
  [return-maps]
  (let [mapping-keys (map :mapping-key (:mapping-keys return-maps))]
    (case (:mapping-type return-maps)
      :keys (mapv keyword mapping-keys)
      :strs (mapv str mapping-keys)
      :syms (mapv symbol mapping-keys)
      [])))

(defn- decode-query-return-maps
  [return-maps attributes result]
  (let [mapping-keys (query-return-map-keys return-maps)
        attributes-by-key (zipmap mapping-keys attributes)]
    (mapv
     (fn [row]
       (reduce-kv
        (fn [decoded mapping-key value]
          (assoc decoded mapping-key
                 (decode-query-field
                  (get attributes-by-key mapping-key) value)))
        (empty row)
        row))
     result)))

(defn- decode-query-result
  [normalized result]
  (let [parsed-query (query/memoized-parse-query (:query normalized))
        attributes (query-find-attributes parsed-query (:args normalized))
        find-clause (:qfind parsed-query)
        return-maps (:qreturnmaps parsed-query)
        decode-result
        (fn [value]
          (cond
            return-maps
            (decode-query-return-maps return-maps attributes value)

            (instance? FindRel find-clause)
            (into (empty value) (map #(decode-query-tuple attributes %)) value)

            (instance? FindColl find-clause)
            (mapv #(decode-query-field (first attributes) %) value)

            (instance? FindScalar find-clause)
            (decode-query-field (first attributes) value)

            (instance? FindTuple find-clause)
            (some->> value (decode-query-tuple attributes))

            :else
            (decode-attribute-maps value)))]
    (if (and (map? result) (contains? result :ret))
      (update result :ret decode-result)
      (decode-result result))))

(defn- pull-selector
  [arguments]
  (let [selector-or-options (first arguments)]
    (if (map? selector-or-options)
      (:selector selector-or-options)
      selector-or-options)))

(defn- pull-output-options
  [spec]
  (into {}
        (map (fn [[display-key options]]
               [(or (:as options) display-key)
                [display-key options]]))
        (:attrs spec)))

(declare decode-pull-entity)

(defn- decode-pull-child
  [spec value]
  (cond
    (map? value) (decode-pull-entity spec value)
    (vector? value) (mapv #(decode-pull-child spec %) value)
    (set? value) (into #{} (map #(decode-pull-child spec %)) value)
    (sequential? value) (doall (map #(decode-pull-child spec %) value))
    :else value))

(defn- decode-pull-entity
  [spec entity]
  (let [options-by-output (pull-output-options spec)]
    (reduce-kv
     (fn [decoded output-key value]
       (let [[display-key options] (get options-by-output output-key)
             attribute (:attr options)
             subpattern (:subpattern options)]
         (assoc decoded output-key
                (cond
                  subpattern
                  (decode-pull-child subpattern value)

                  (and (keyword? attribute)
                       (= display-key attribute)
                       (schema.datahike/edn-encoded-attr? attribute))
                  (decode-attribute-value attribute value)

                  (and (keyword? output-key)
                       (schema.datahike/edn-encoded-attr? output-key))
                  (decode-attribute-value output-key value)

                  :else
                  (decode-attribute-maps value)))))
     (empty entity)
     entity)))

(defn- decode-pull-result
  [selector result-key result]
  (let [spec (pull.parser/parse-pull selector)]
    (if (= :datahike.pull-many/result result-key)
      (mapv #(when % (decode-pull-entity spec %)) result)
      (when result (decode-pull-entity spec result)))))

;;; ---------------------------------------------------------------------------
;;; Reads over immutable database values
;;; ---------------------------------------------------------------------------

(defn db
  "Current immutable database value for an explicit or ambient connection."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :seon.db/database-value :seon.error/value]]
    [:=> [:cat [:or :seon.db/connection :seon.error/value]]
     [:or :seon.db/database-value :seon.error/value]]]}
  ([]
   (current-database-value))
  ([connection]
   (if (error-value? connection)
     connection
     (resolve-database-value connection))))

(defn- source-argument-error
  [source-bindings arguments]
  (some (fn [source]
          (let [position (:datahike.query.source/argument-position source)]
            (when (< position (count arguments))
              (let [argument (nth arguments position)]
                (when (error-value? argument)
                  argument)))))
        source-bindings))

(defn- aligned-query-arguments
  [explicit-database query-form arguments]
  (let [arguments (vec arguments)
        input-count (d/query-input-count query-form)
        source-bindings (d/query-source-bindings query-form)
        upstream-error (source-argument-error source-bindings arguments)
        default-sources
        (filterv #(= '$ (:datahike.query.source/symbol %))
                 source-bindings)]
    (cond
      upstream-error
      upstream-error

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

      ;; Datahike owns every other positional shape. Passing the normalized
      ;; arguments through is deliberately weaker than the dependency's own
      ;; acceptance; `q-with-evidence` alone decides whether they are valid.
      :else arguments)))

(defn q
  "Run a Datalog query over explicit inputs or the current database value."
  {:malli/schema
   [:=>
    [:catn
     [::query-or-database
      [:or
       :seon.db/database-value
       :seon.error/value
       :seon.db/query
       :seon.db/query-args]]
     [::arguments [:* :seon.schema/value]]]
    [:or :seon.schema/value :seon.error/value]]}
  [query-or-database & arguments]
  (if (error-value? query-or-database)
    query-or-database
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
        (if (error-value? aligned)
          aligned
          (let [request (assoc normalized :args aligned)
                response (d/q-with-evidence request)
                result (decode-query-result
                        request (:datahike.query/result response))]
            (append-query-evidence! request response result)
            result)))
        (catch Throwable cause
          (when explicit-database?
            (append-database-evidence! query-or-database :all))
          (dependency-error ::q cause))))))

(defn- pull-call
  [database arguments operation operation-key result-key]
  (if (error-value? database)
    database
    (try
      (let [response (apply operation database arguments)
            selector (pull-selector arguments)
            result (decode-pull-result selector result-key
                                       (get response result-key))]
        (append-pull-evidence! database arguments operation-key response result)
        result)
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
          [:or :seon.db/database-value :seon.error/value
           :seon.db/pull-selector]
          [:or :seon.db/pull-options :seon.db/entity-id]]
     [:or :nil :map :seon.error/value]]
    [:=>
     [:cat [:or :seon.db/database-value :seon.error/value]
      :seon.db/pull-selector
      :seon.db/entity-id]
     [:or :nil :map :seon.error/value]]]}
  ([options]
   (pull-call (current-database-value)
              [options]
              d/pull-with-evidence
              :pull
              :datahike.pull/result))
  ([database-or-selector options-or-eid]
   (if (or (db.utils/db? database-or-selector)
           (error-value? database-or-selector))
     (pull-call database-or-selector
                [options-or-eid]
                d/pull-with-evidence
                :pull
                :datahike.pull/result)
     (pull-call (current-database-value)
                [database-or-selector options-or-eid]
                d/pull-with-evidence
                :pull
                :datahike.pull/result)))
  ([database selector entity-id]
   (pull-call database
              [selector entity-id]
              d/pull-with-evidence
              :pull
              :datahike.pull/result)))

(defn pull-many
  "Pull aligned entities over an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/pull-many-options]
     [:or [:vector [:or :nil :map]] :seon.error/value]]
    [:=>
     [:cat
      [:or :seon.db/database-value :seon.error/value
       :seon.db/pull-selector]
      [:or :seon.db/pull-many-options
       [:sequential :seon.db/entity-id]]]
     [:or [:vector [:or :nil :map]] :seon.error/value]]
    [:=>
     [:cat [:or :seon.db/database-value :seon.error/value]
      :seon.db/pull-selector
      [:sequential :seon.db/entity-id]]
     [:or [:vector [:or :nil :map]] :seon.error/value]]]}
  ([options]
   (pull-call (current-database-value)
              [options]
              d/pull-many-with-evidence
              :pull-many
              :datahike.pull-many/result))
  ([database-or-selector options-or-eids]
   (if (or (db.utils/db? database-or-selector)
           (error-value? database-or-selector))
     (pull-call database-or-selector
                [options-or-eids]
                d/pull-many-with-evidence
                :pull-many
                :datahike.pull-many/result)
     (pull-call (current-database-value)
                [database-or-selector options-or-eids]
                d/pull-many-with-evidence
                :pull-many
                :datahike.pull-many/result)))
  ([database selector entity-ids]
   (pull-call database
              [selector entity-ids]
              d/pull-many-with-evidence
              :pull-many
              :datahike.pull-many/result)))

(defn- entity-call
  [database entity-id]
  ;; Wildcard pull is Datahike's eager ordinary-data form: component refs
  ;; expand recursively and ordinary refs remain plain {:db/id ...} maps.
  (pull-call database
             [['*] entity-id]
             d/pull-with-evidence
             :pull
             :datahike.pull/result))

(defn entity
  "Eager ordinary data for one entity in an explicit or current database."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/entity-id]
     [:or :nil :map :seon.error/value]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
          :seon.db/entity-id]
     [:or :nil :map :seon.error/value]]]}
  ([entity-id]
   (entity-call (current-database-value) entity-id))
  ([database entity-id]
   (entity-call database entity-id)))

(defn- datom->data
  [database datom]
  (let [stored-attribute (:a datom)
        attribute (:ident (db.utils/attr-info database stored-attribute))
        value (:v datom)]
    {:e (:e datom)
     :a stored-attribute
     :v (if (and (keyword? attribute)
                 (schema.datahike/edn-encoded-attr? attribute))
          (decode-attribute-value attribute value)
          (decode-attribute-maps value))
     :tx (:tx datom)
     :added (:added datom)}))

(defn- datoms-call
  [database arguments]
  (if (error-value? database)
    database
    (try
      ;; Datahike's index cursor is lazy and each element is a host Datom.
      ;; Realize both layers here so no process-local cursor escapes to SCI.
      (let [result (mapv #(datom->data database %)
                         (apply d/datoms database arguments))]
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
     [:or :seon.db/database-value :seon.error/value
      :seon.db/index-lookup :keyword]
     [:* :seon.schema/value]]
    [:or :seon.db/datoms :seon.error/value]]}
  [database-or-index & arguments]
  (if (or (db.utils/db? database-or-index)
          (error-value? database-or-index))
    (datoms-call database-or-index arguments)
    (datoms-call (current-database-value)
                 (cons database-or-index arguments))))

(defn- database-view
  [operation database arguments]
  (cond
    (error-value? database)
    database

    (not (dbi/-temporal-index? database))
    (do
      (append-database-evidence! database :all)
      (error-value
       ::non-temporal-database
       "The database does not retain temporal indices."
       {::operation ::temporal-read}))

    :else
    (try
      (let [result (apply operation database arguments)]
        (append-database-evidence! database :all)
        result)
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error ::temporal-read cause)))))

(defn- database-identity
  [operation operation-name database]
  (if (error-value? database)
    database
    (try
      (let [result (operation database)]
        (append-database-evidence! database :all)
        result)
      (catch Throwable cause
        (append-database-evidence! database :all)
        (dependency-error operation-name cause)))))

(defn commit-id
  "Commit ID of an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :nil :uuid :seon.error/value]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
     [:or :nil :uuid :seon.error/value]]]}
  ([]
   (database-identity d/commit-id ::commit-id (current-database-value)))
  ([database]
   (database-identity d/commit-id ::commit-id database)))

(defn committed-value-identity
  "Process-local identity of an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :nil :map :seon.error/value]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
     [:or :nil :map :seon.error/value]]]}
  ([]
   (database-identity d/committed-value-identity
                      ::committed-value-identity
                      (current-database-value)))
  ([database]
   (database-identity d/committed-value-identity
                      ::committed-value-identity
                      database)))

(defn history
  "Historical view of an explicit or current database value."
  {:malli/schema
   [:function
    [:=> [:cat]
     [:or :seon.db/database-value :seon.error/value]]
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]]
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
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
          :seon.db/time-point]
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
    [:=> [:cat [:or :seon.db/database-value :seon.error/value]
          :seon.db/time-point]
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

(def ^:private entity-identity-query
  '[:find ?identity-attribute ?identity-value
    :in $ ?entity
    :where
    [?schema :db/ident ?identity-attribute]
    [?schema :db/unique :db.unique/identity]
    [?entity ?identity-attribute ?identity-value]])

(defn- entity-identity
  [database entity-id]
  (some->> (d/q entity-identity-query database entity-id)
           (sort-by (comp str first))
           first
           vec))

(defn- conflict-value
  [database attribute value]
  (if (db.utils/ref? database attribute)
    (or (entity-identity database value) value)
    value))

(defn- unique-conflict
  [database data]
  (when (= :transact/unique (:error data))
    (let [attribute (:attribute data)
          datom (:datom data)
          stored-value (:v datom)
          owner
          (d/q '[:find ?owner .
                 :in $ ?attribute ?value
                 :where [?owner ?attribute ?value]]
               database attribute stored-value)]
      (cond-> {::conflict-attribute attribute
               ::conflict-value
               (conflict-value database attribute stored-value)}
        owner (assoc ::conflict-owner
                     (or (entity-identity database owner) owner))))))

(defn- rejection-message
  [conflict throwable]
  (if conflict
    (str "Transaction rejected: "
         (pr-str (::conflict-attribute conflict))
         " value " (pr-str (::conflict-value conflict))
         " is already held by "
         (pr-str (or (::conflict-owner conflict) "an existing entity"))
         ".")
    (loop [failure throwable]
      (if-let [cause (ex-cause failure)]
        (recur cause)
        (or (ex-message failure) "Transaction rejected.")))))

(defn- rejected-value
  [connection throwable data]
  (let [conflict
        (try
          (unique-conflict (d/db connection) data)
          (catch Throwable _
            nil))]
    {:seon.error/kind ::rejected
     :seon.error/message (rejection-message conflict throwable)
     :seon.error/data (merge data conflict)
     ::transaction-refused true}))

(declare agent-transaction-report)

(defn- transact-call
  [connection transaction]
  (if (error-value? connection)
    connection
    (try
      (let [report
            (d/transact connection
                        (schema.datahike/encode-transaction
                         (jdk-integers->long transaction)))]
        ;; Ambient custody marks an agent-facing call. Both public arities
        ;; therefore return the same declared semantic report inside an eval;
        ;; unbound system callers retain Datahike's exact report for reducers
        ;; and listeners.
        (if (some? *conn*)
          (agent-transaction-report report)
          report))
      (catch Throwable throwable
        (let [data (error.refusal/refusal throwable)]
          (cond
            ;; A Seon transition refusal returns its own value verbatim.
            (some? (:seon.error/kind data))
            data

            ;; A Datahike abort keeps the dependency's classification.
            (some? (:error data))
            (rejected-value connection throwable data)

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

(defn- transaction-report-limit
  [database]
  (try
    (long
     (or (d/q '[:find ?limit .
                :where
                [_ :seon.config.eval.result/max-collection ?limit]]
              database)
         0))
    (catch Throwable _
      0)))

(defn- agent-transaction-report
  [report]
  (let [database (:db-after report)
        datoms (:tx-data report)
        limit (transaction-report-limit database)]
    {:tx (:tx (first datoms))
     :datahike/commit-id (or (get-in report [:tx-meta :db/commitId])
                             (d/commit-id database))
     ::datom-count (count datoms)
     :tx-data (into []
                    (map #(datom->data database %))
                    (take limit datoms))
     :tempids (:tempids report)}))

(defn- rendered-value
  [unit]
  (if (map? (:seon.render/value unit))
    (:seon.render/value unit)
    unit))

(defn render-transaction-ai
  "Render a committed transaction report as bounded readable text."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-report] [:string {:min 1}]]}
  [unit]
  (let [{transaction :tx
         commit-id :datahike/commit-id
         datom-count ::datom-count
         datoms :tx-data
         tempids :tempids}
        (rendered-value unit)
        shown (count datoms)]
    (str "Committed transaction " transaction
         " at commit " commit-id
         " with " datom-count " datoms"
         (when (< shown datom-count)
           (str " (showing " shown ")"))
         "."
         (when (seq tempids)
           (str "\nTempids: " (pr-str tempids)))
         (when (seq datoms)
           (str "\nCommitted datoms:\n"
                (str/join "\n" (map pr-str datoms)))))))

(defn render-transaction-html
  "Render a committed transaction report as bounded readable Hiccup."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-report] :seon.render/hiccup]}
  [unit]
  (let [{transaction :tx
         commit-id :datahike/commit-id
         datom-count ::datom-count
         datoms :tx-data
         tempids :tempids}
        (rendered-value unit)
        shown (count datoms)]
    [:article {:class "seon-family-entry seon-db-transaction-entry"}
     [:h3 "Committed transaction"]
     [:dl
      [:div [:dt "Transaction"] [:dd (str transaction)]]
      [:div [:dt "Commit ID"] [:dd (str commit-id)]]
      [:div [:dt "Datoms"]
       [:dd (str datom-count
                 (when (< shown datom-count)
                   (str " (showing " shown ")")))]]
      [:div [:dt "Tempids"] [:dd (pr-str tempids)]]]
     (when (seq datoms)
       (into [:ol {:class "seon-db-transaction-datoms"}]
             (map (fn [datom] [:li [:code (pr-str datom)]]))
             datoms))]))

(defn render-rejection-ai
  "Render a rejected database transaction as readable steering text."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-refused-error] [:string {:min 1}]]}
  [unit]
  (:seon.error/message (rendered-value unit)))

(defn render-rejection-html
  "Render a rejected database transaction as readable Hiccup."
  {:malli/schema
   [:=> [:cat :seon.db/transaction-refused-error] :seon.render/hiccup]}
  [unit]
  (let [value (rendered-value unit)
        conflict (:seon.error/data value)]
    [:article {:class "seon-family-entry seon-db-rejection-entry"}
     [:h3 (:seon.error/message value)]
     (when (::conflict-attribute conflict)
       [:dl
        [:div [:dt "Attribute"]
         [:dd (pr-str (::conflict-attribute conflict))]]
        [:div [:dt "Value"]
         [:dd (pr-str (::conflict-value conflict))]]
        [:div [:dt "Existing owner"]
         [:dd (pr-str (::conflict-owner conflict))]]])]))

(defn transact!
  "Commit a transaction through an explicit or ambient connection.

  When `*conn*` is bound, an explicit connection must have the same Datahike
  connection ID. An absent binding means the caller is outside an agent
  evaluation, so a live explicit connection is allowed."
  {:malli/schema
   [:function
    [:=> [:cat :seon.store/transaction]
     [:or :seon.db/transaction-report :seon.error/value]]
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
