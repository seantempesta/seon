(ns seon.db.internal
  "Transform transaction and schema data behind `seon.db`.

   This internal namespace normalizes public requests and ordinary context
   data into protocol data; platform scope and transport live elsewhere."
  (:require
   #?(:clj [clojure.edn :as reader]
      :cljs [cljs.reader :as reader])
   [clojure.string :as str]
   [seon.ai.tokens :as tokens]
   [seon.db :as-alias db]
   [seon.schema :as schema]
   [seon.schema.form :as schema.form]))

;;; Malli to Datahike schema data. The authority installs the returned maps.

(def tx-meta-attrs #{::db/user ::db/process})

(def ^:dynamic *schema-projection* nil)

(defn with-schema-projection
  "Run `f` against one immutable committed schema projection when supplied."
  [projection f]
  (binding [*schema-projection* projection] (f)))

(defn- registered? [schema-key]
  (if *schema-projection*
    (contains? (:seon.schema.projection/forms *schema-projection*) schema-key)
    (schema/registered? schema-key)))

(defn- schema-definition [schema-key]
  (if *schema-projection*
    (get (:seon.schema.projection/forms *schema-projection*) schema-key)
    (schema/schema-definition schema-key)))

(defn- valid-value? [schema-key value]
  (if *schema-projection*
    ((schema/projection-validator *schema-projection* schema-key) value)
    (schema/valid-candidate-value? schema-key value)))

(defn form-children
  "The non-property children of one Malli form."
  [form]
  (if (vector? form)
    (into [] (remove map?) (rest form))
    []))

(defn resolve-malli-form
  "Resolve registered Malli aliases without crossing into compiled schemas."
  [form]
  (cond
    (= :seon.db/ref form) form
    (and (keyword? form) (registered? form))
    (let [definition (schema-definition form)]
      (if (or (keyword? definition) (vector? definition))
        (resolve-malli-form definition)
        form))
    :else form))

(def malli-type->datahike-type
  {:string :db.type/string
   :re :db.type/string
   :int :db.type/long
   :double :db.type/double
   :float :db.type/float
   :keyword :db.type/keyword
   :qualified-keyword :db.type/keyword
   :boolean :db.type/boolean
   :inst :db.type/instant
   :uuid :db.type/uuid
   :symbol :db.type/symbol
   :qualified-symbol :db.type/symbol})

(defn form-head
  "The head of one Malli form."
  [form]
  (if (vector? form) (first form) form))

(defn- registration-form
  [attr schema-form]
  (pr-str (list 'schema/register! attr schema-form)))

(defn- transaction-form
  [tx-data]
  (pr-str
    (list 'seon.db/transact!
          {:seon.db/tx-data tx-data})))

(declare form->datahike-value-type)

(defn form->datahike-value-type
  "The Datahike value-type keyword represented by a Malli form."
  [form]
  (let [resolved (resolve-malli-form form)
        head (form-head resolved)]
    (cond
      (= :seon.db/ref head) :db.type/ref
      (= :enum head)
      (if (every? keyword? (form-children resolved))
        :db.type/keyword
        (throw (ex-info
                 (str "Only keyword Malli enums are storable. Register keyword "
                      "members, for example "
                      (registration-form :my.domain/status
                                         [:enum :open :done]) ".")
                        {::db/form resolved :seon.error/kind :user-input})))
      (= :and head)
      (form->datahike-value-type (first (form-children resolved)))
      (= :or head)
      (let [explicit (:seon.db/value-type
                      (schema.form/attr-form-properties resolved))
            types (into #{} (map #(try
                                    (form->datahike-value-type %)
                                    (catch #?(:clj Throwable :cljs :default) _
                                      ::unmappable)))
                        (form-children resolved))]
        (or explicit
            (when (and (= 1 (count types))
                       (not (contains? types ::unmappable)))
              (first types))
            :db.type/string))
      (schema.form/nilable-value-schema? resolved)
      (throw (ex-info (str "Stored attributes cannot use `:maybe`. Register the "
                           "non-nil base shape, then omit an absent key or mark "
                           "its entity-map entry `{:optional true}`.")
                      {::db/form resolved :seon.error/kind :user-input}))
      :else
      (or (malli-type->datahike-type head)
          (throw (ex-info
                   (str "The Malli form has no Datahike value type. Register a "
                        "concrete storable shape, for example "
                        (registration-form :my.domain/value :string) ".")
                          {::db/form resolved :seon.error/kind :user-input}))))))

(defn form->cardinality
  "The Datahike cardinality represented by one Malli form."
  [form]
  (if (and (vector? form) (#{:vector :set :sequential} (first form)))
    :db.cardinality/many
    :db.cardinality/one))

(defn form->child-form
  "The stored child form for a collection schema, or the scalar form."
  [form]
  (if (and (vector? form) (#{:vector :set :sequential} (first form)))
    (first (form-children form))
    form))

(defn malli->datahike-attr
  "Derive one ordinary Datahike attribute declaration."
  [attr]
  (let [raw (or (schema-definition attr)
                (throw (ex-info
                         (str "The attribute has no registered schema. Run "
                              (registration-form attr :string)
                              " with the intended concrete type before "
                              "transacting it.")
                                {::db/attr attr :seon.error/kind :user-input})))
        props (schema.form/attr-form-properties raw)
        value-form (-> raw resolve-malli-form form->child-form resolve-malli-form)
        secondary? (boolean (:db.secondary/only props))]
    (when (and secondary?
               (not (contains? #{:float :double} (form-head value-form))))
      (throw (ex-info
               (str "A secondary-only attribute must contain floats. Register "
                    (registration-form attr
                                       [:float {:db.secondary/only true}]) ".")
                      {::db/attr attr :seon.error/kind :user-input})))
    (cond-> {:db/ident attr
             :db/valueType (if secondary?
                             :db.type/tuple
                             (form->datahike-value-type value-form))
             :db/cardinality (if secondary?
                               :db.cardinality/one
                               (form->cardinality (resolve-malli-form raw)))}
      secondary? (assoc :db.secondary/only true)
      (:seon.db/identity props) (assoc :db/unique :db.unique/identity)
      (:seon.db/unique props) (assoc :db/unique :db.unique/value)
      (:seon.db/index props) (assoc :db/index true)
      (:seon.db/component props) (assoc :db/isComponent true)
      (:seon.db/no-history? props) (assoc :db/noHistory true))))

(defn malli->datahike-schema
  "Derive ordered Datahike attribute declarations."
  [attrs]
  (mapv malli->datahike-attr attrs))

(defn tx-meta-datahike-schema
  "Derive the two transaction-provenance declarations."
  []
  (malli->datahike-schema (sort tx-meta-attrs)))

(defn edn-encoded-attr?
  "True when a mixed Malli union is stored as an EDN string."
  [attr]
  (when (and (keyword? attr) (registered? attr))
    (let [form (resolve-malli-form (schema-definition attr))]
      (and (vector? form)
           (= :or (first form))
           (= :db.type/string (form->datahike-value-type form))))))

(defn set-valued-attr?
  "True when a registered Malli :set schema stores as cardinality-many.

   Datahike materializes cardinality-many values as vectors on pull/entity;
   the registered schema is the shape authority, so the one decode boundary
   reconstructs the set. Computed from the registry — never a name list."
  [attr]
  (when (and (keyword? attr) (registered? attr))
    (let [form (resolve-malli-form (schema-definition attr))]
      (and (vector? form) (= :set (first form))))))

(defn- admits-ref-value?
  "True when a Malli value form structurally admits a database ref."
  [form]
  (let [resolved (resolve-malli-form form)
        head (form-head resolved)]
    (cond
      (= :seon.db/ref resolved) true
      (= :and head) (boolean
                     (some-> resolved form-children first admits-ref-value?))
      (= :or head) (boolean (some admits-ref-value? (form-children resolved)))
      :else false)))

(defn ref-value-form?
  "True when a Malli value form both admits and stores a database ref."
  [form]
  (let [resolved (resolve-malli-form form)]
    (and (admits-ref-value? resolved)
         (= :db.type/ref
            (try
              (form->datahike-value-type resolved)
              (catch #?(:clj Throwable :cljs :default) _ nil))))))

(defn- admits-entity-value?
  "True when a Malli value form structurally admits an entity map."
  [form]
  (let [resolved (resolve-malli-form form)
        head (form-head resolved)]
    (cond
      (schema.form/map-shape? resolved) true
      (= :and head) (boolean (some admits-entity-value? (form-children resolved)))
      (= :or head) (boolean (some admits-entity-value? (form-children resolved)))
      :else false)))

(defn component-children-attr?
  "True when a registered attribute owns acquired component child maps.

   The rule is derived from the one registered Malli form: a cardinality-many
   component collection whose child value is stored as a ref."
  [attr]
  (when (and (keyword? attr) (registered? attr))
    (let [form (resolve-malli-form (schema-definition attr))
          head (form-head form)
          props (schema.form/attr-form-properties form)
          child (some-> form form-children first)]
      (and (#{:vector :set :sequential} head)
           (:seon.db/component props)
           (ref-value-form? child)))))

(defn- acquired-component-schema?
  "True when a component ref attr also declares its acquired entity shape."
  [attr]
  (when (component-children-attr? attr)
    (let [form (resolve-malli-form (schema-definition attr))
          child (some-> form form-children first)]
      (admits-entity-value? child))))

(defn encode-edn-slot-values
  "Encode mixed-union attribute values before transport."
  [tx-data]
  (letfn [(encode-entity [entity]
            (reduce-kv (fn [result attr value]
                         (assoc result attr
                                (cond
                                  (edn-encoded-attr? attr) (pr-str value)
                                  (map? value) (encode-entity value)
                                  (and (vector? value) (some map? value))
                                  (mapv #(if (map? %) (encode-entity %) %) value)
                                  :else value)))
                       {} entity))]
    (mapv (fn [item]
            (cond
              (map? item) (encode-entity item)
              (and (vector? item) (= :db/add (first item))
                   (edn-encoded-attr? (nth item 2 nil)))
              (update item 3 pr-str)
              :else item))
          tx-data)))

(defn omit-nil-entity-values
  "Omit absent map attributes recursively before validation and transport."
  [tx-data]
  (letfn [(normalize [value]
            (cond
              (map? value)
              (reduce-kv (fn [result attr child]
                           (if (nil? child)
                             result
                             (assoc result attr (normalize child))))
                         (empty value)
                         value)
              (vector? value) (mapv normalize value)
              (set? value) (into #{} (map normalize) value)
              (sequential? value) (mapv normalize value)
              :else value))]
    (mapv normalize tx-data)))

;;; Pure transaction normalization and validation.

(defn system-attr?
  "True for Datahike's `:db/*` and `:db.*/*` attributes."
  [attr]
  (let [n (and (keyword? attr) (namespace attr))]
    (boolean (and n (or (= "db" n) (str/starts-with? n "db."))))))

(defn ref-attr-arity
  "The registered ref cardinality, or nil for a non-ref attribute."
  [form]
  (let [resolved (resolve-malli-form form)
        head (form-head resolved)]
    (cond
      (ref-value-form? resolved) :one
      (and (#{:vector :set :sequential} head)
           (some-> resolved form-children first ref-value-form?)) :many
      :else nil)))

(defn ref-slot?
  "True when `attr` names a registered ref attribute."
  [attr]
  (and (qualified-keyword? attr)
       (not (system-attr? attr))
       (registered? attr)
       (some? (ref-attr-arity (schema-definition attr)))))

(defn extract-tx-attrs
  "Collect every attribute named by transaction data."
  [tx-data]
  (letfn [(entity-attrs [attrs entity]
            (reduce-kv (fn [result attr value]
                         (cond-> (conj result attr)
                           (and (ref-slot? attr) (map? value))
                           (entity-attrs value)
                           (and (ref-slot? attr) (sequential? value))
                           (into (mapcat #(when (map? %) (entity-attrs #{} %)) value))))
                       attrs entity))]
    (reduce (fn [attrs item]
              (cond
                (map? item) (entity-attrs attrs item)
                (and (vector? item) (<= 3 (count item))) (conj attrs (nth item 2))
                :else attrs))
            #{} tx-data)))

(defn validate-attrs!
  "Reject transaction attributes absent from the schema registry."
  [attrs]
  (let [unknown (->> attrs
                     (remove #(or (system-attr? %)
                                  (registered? %)))
                     (sort-by str)
                     vec)]
    (when (seq unknown)
      (throw (ex-info
               (str "Transaction data names unregistered attributes "
                    (pr-str unknown) ". Register each intended attribute first, "
                    "for example "
                    (pr-str (list 'schema/register! (first unknown) :string))
                    " (replace `:string` with the intended concrete type), then "
                    "retry the unchanged `seon.db/transact!` form.")
                      {::db/unregistered unknown :seon.error/kind :user-input}))))
  nil)

(defn truncate-value
  "A bounded printable transaction value."
  [value]
  (tokens/bounded-pr-str value 25))

(declare validation-entity)

(defn- validation-tree
  [value]
  (cond
    (map? value) (validation-entity value)
    (vector? value) (mapv validation-tree value)
    (set? value) (into #{} (map validation-tree) value)
    (sequential? value) (mapv validation-tree value)
    :else value))

(defn- validation-value
  [attr value]
  (validation-tree
   (if (and (edn-encoded-attr? attr) (string? value))
     (try
       (reader/read-string value)
       (catch #?(:clj Throwable :cljs :default) _ ::invalid-edn-slot))
     value)))

(defn- validation-entity
  [entity]
  (reduce-kv (fn [result attr value]
               (assoc result attr (validation-value attr value)))
             {}
             entity))

(declare validate-logical-entity-values!)

(defn- validate-ref!
  [attr value]
  (cond
    (map? value) (validate-logical-entity-values! value)
    (valid-value? :seon.db/ref value) nil
    :else (throw (ex-info
                   (str "A ref attribute contains an invalid reference. Use an "
                        "entity id or lookup ref, for example "
                        (transaction-form
                          [{attr [:seon.agent/id "agent-id"]}]) ".")
                          {::db/attr attr ::db/actual-value value
                           :seon.error/kind :user-input}))))

(defn- validate-logical-entity-values!
  [entity]
  (doseq [[attr value] entity
          :when (and (not (system-attr? attr)) (registered? attr))]
    (let [logical-value value]
      (when (and (acquired-component-schema? attr)
                 (not (valid-value? attr logical-value)))
        (throw (ex-info
                (str "Transaction data fails its registered component schema "
                     (pr-str (schema-definition attr)) ". "
                     "Transact identified child entities or entity refs; for "
                     "example "
                     (transaction-form
                      [{attr 'value-matching-registered-schema}]) ".")
                {::db/attr attr ::db/actual-value logical-value
                 ::db/expected-schema (schema-definition attr)
                 :seon.error/kind :user-input})))
      (case (ref-attr-arity (schema-definition attr))
        :one (validate-ref! attr logical-value)
        :many (doseq [child logical-value] (validate-ref! attr child))
        (when-not (valid-value? attr logical-value)
          (throw (ex-info
                  (str "Transaction data fails its registered schema "
                       (pr-str (schema-definition attr)) ". "
                       (if (nil? logical-value)
                         (str "Omit the absent key; for example "
                              (transaction-form [(dissoc entity attr)]) ".")
                         (str "Transact a matching value; for example "
                              (transaction-form
                               [{attr 'value-matching-registered-schema}]) ".")))
                  {::db/attr attr ::db/actual-value logical-value
                   ::db/expected-schema (schema-definition attr)
                   :seon.error/kind :user-input}))))))
  nil)

(defn validate-entity-values!
  "Decode and validate one transaction entity using registered Malli forms."
  [entity]
  (validate-logical-entity-values! (validation-entity entity)))

(defn validate-values!
  "Validate every entity map in transaction data."
  [tx-data]
  (doseq [item tx-data :when (map? item)]
    (validate-entity-values! item))
  nil)

(defn keyword-identity-ident?
  "True for a keyword-valued identity attribute."
  [attr]
  (and (qualified-keyword? attr)
       (registered? attr)
       (schema/identity-attr? attr)
       (= :keyword
          (-> attr schema-definition resolve-malli-form
              form->child-form resolve-malli-form form-head))))

(defn coerce-lookup-ref-symbol
  "Coerce a symbol value in a keyword identity lookup ref."
  [value]
  (if (and (vector? value) (= 2 (count value))
           (keyword-identity-ident? (first value)) (symbol? (second value)))
    [(first value) (keyword (second value))]
    value))

(defn coerce-identity-symbol-idents
  "Coerce symbols only in keyword-valued identity positions."
  [tx-data]
  (letfn [(ref-value [value]
            (cond
              (map? value) (entity value)
              (vector? value) (coerce-lookup-ref-symbol value)
              (sequential? value) (mapv ref-value value)
              :else value))
          (entity [value]
            (reduce-kv (fn [result attr item]
                         (assoc result attr
                                (cond
                                  (and (keyword-identity-ident? attr) (symbol? item))
                                  (keyword item)
                                  (= :db/id attr) (coerce-lookup-ref-symbol item)
                                  (ref-slot? attr) (ref-value item)
                                  :else item)))
                       {} value))]
    (mapv (fn [item]
            (cond
              (map? item) (entity item)
              (and (vector? item) (#{:db/add :db/retract} (first item)))
              (let [attr (nth item 2 nil)]
                (cond-> (update item 1 coerce-lookup-ref-symbol)
                  (and (<= 4 (count item)) (ref-slot? attr)) (update 3 ref-value)
                  (and (<= 4 (count item)) (keyword-identity-ident? attr)
                       (symbol? (nth item 3))) (update 3 keyword)))
              :else item))
          tx-data)))

(defn normalize-entity-ref-keys
  "Translate entity-map `:seon.db/ref` keys to native `:db/id` keys."
  [tx-data]
  (letfn [(ref-value [value]
            (cond
              (map? value) (entity value)
              (sequential? value) (mapv ref-value value)
              :else value))
          (entity [value]
            (let [normalized (reduce-kv (fn [result attr item]
                                          (assoc result attr
                                                 (if (ref-slot? attr)
                                                   (ref-value item)
                                                   item)))
                                        {} value)]
              (if-not (contains? normalized ::db/ref)
                normalized
                (let [ref (::db/ref normalized)]
                  (when-not (valid-value? :seon.db/ref ref)
                    (throw (ex-info
                             (str "`:seon.db/ref` contains an invalid entity "
                                  "reference. Use one identity lookup ref, for "
                                  "example [:seon.agent/id \"agent-id\"].")
                                    {::db/actual-value ref
                                     :seon.error/kind :user-input})))
                  (when (and (contains? normalized :db/id)
                             (not= (:db/id normalized) ref))
                    (throw (ex-info
                             (str "An entity map names two different entities. "
                                  "Keep one canonical `:db/id`; for example "
                                  (transaction-form
                                    [(dissoc normalized ::db/ref)]) ".")
                                    {::db/actual-value normalized
                                     :seon.error/kind :user-input})))
                  (-> normalized (dissoc ::db/ref) (assoc :db/id ref))))))]
    (mapv (fn [item]
            (cond
              (map? item) (entity item)
              (and (vector? item) (<= 4 (count item)) (ref-slot? (nth item 2)))
              (update item 3 ref-value)
              :else item))
          tx-data)))

(defn assert-invocation-shape!
  "Require one namespaced transaction request with sequential data."
  [request]
  (when-not (and (map? request)
                 (contains? request ::db/tx-data)
                 (sequential? (::db/tx-data request)))
    (throw (ex-info
             (str "`seon.db/transact!` requires sequential "
                  "`:seon.db/tx-data`. Use "
                  (transaction-form [{:my.domain/id "value"}]) ".")
                    {::db/actual-value request :seon.error/kind :user-input})))
  nil)

(defn selected-provenance
  "Select the two durable provenance references."
  [context agent-id]
  {::db/user (or (::db/user context)
                 (if agent-id [:seon.agent/id agent-id] [:seon.user/id "user"]))
   ::db/process (or (::db/process context)
                    [:seon.db.process/id :seon.db.process/repl])})

(defn merge-tx-context-into-opts
  "Merge selected provenance into transaction metadata."
  [opts context agent-id]
  (let [provenance (selected-provenance (or context {}) agent-id)
        explicit (into {} (remove #(= "seon.db" (namespace (key %))))
                       (or (:tx-meta opts) {}))]
    (assoc (or opts {}) :tx-meta (merge explicit provenance))))
