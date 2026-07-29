(ns seon.schema.datahike
  "Derive Datahike attribute declarations from registered Malli schemas.

   The schema registry is the one shape authority; this namespace is its
   database bridge — registered attribute forms in, ordinary Datahike
   schema maps out. Pure derivation: no connection, no session, no
  transaction. The store owner transacts the returned declarations."
  (:require #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [seon.schema :as schema]
            [seon.schema.form :as schema.form]))

(defn form-children
  "The non-property children of one Malli form."
  {:malli/schema
   [:=> [:cat :seon.schema/definition] [:vector :seon.schema/value]]}
  [form]
  (if (vector? form)
    (into [] (remove map?) (rest form))
    []))

(defn resolve-malli-form
  "Resolve registered Malli aliases without crossing into compiled schemas."
  {:malli/schema
   [:=> [:cat :seon.schema/definition] :seon.schema/definition]}
  [form]
  (cond
    (= :seon.db/ref form) form
    (and (keyword? form) (schema/registered? form))
    (let [definition (schema/schema-definition form)]
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
  {:malli/schema
   [:=> [:cat :seon.schema/definition] :seon.schema/value]}
  [form]
  (if (vector? form) (first form) form))

(defn resolve-datahike-form
  "Resolve aliases and wrappers to the form stored by Datahike."
  {:malli/schema
   [:=> [:cat :seon.schema/definition] :seon.schema/definition]}
  [form]
  (let [resolved (resolve-malli-form form)]
    (if (= :and (form-head resolved))
      (resolve-datahike-form (first (form-children resolved)))
      resolved)))

(defn- registration-form
  [attr schema-form]
  (pr-str (list 'schema/register! attr schema-form)))

(declare form->datahike-value-type)

(defn- literal->datahike-value-type
  [literal]
  (cond
    (string? literal) :db.type/string
    (boolean? literal) :db.type/boolean
    (keyword? literal) :db.type/keyword
    (symbol? literal) :db.type/symbol
    (uuid? literal) :db.type/uuid
    (inst? literal) :db.type/instant
    #?(:clj (instance? Long literal)
       :cljs (and (number? literal) (js/Number.isSafeInteger literal)))
    :db.type/long
    #?(:clj (instance? Double literal)
       :cljs (number? literal))
    :db.type/double
    #?(:clj (instance? Float literal)
       :cljs false)
    :db.type/float
    :else nil))

(defn form->datahike-value-type
  "The Datahike value-type keyword represented by a Malli form."
  {:malli/schema [:=> [:cat :seon.schema/definition] :keyword]}
  [form]
  (let [resolved (resolve-datahike-form form)
        head (form-head resolved)]
    (cond
      (= :seon.db/ref head) :db.type/ref
      (= := head)
      (or (some-> resolved form-children first
                  literal->datahike-value-type)
          (throw
           (ex-info
            (str "Only scalar Malli literals are storable. Register a "
                 "literal whose value has a native Datahike type, for "
                 "example "
                 (registration-form :my.domain/enabled [:= true]) ".")
            {::form resolved :seon.error/kind :user-input})))
      (= :enum head)
      (if (every? keyword? (form-children resolved))
        :db.type/keyword
        (throw (ex-info
                (str "Only keyword Malli enums are storable. Register keyword "
                     "members, for example "
                     (registration-form :my.domain/status
                                        [:enum :open :done]) ".")
                {::form resolved :seon.error/kind :user-input})))
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
            ;; Mixed unions deliberately store their logical values as EDN
            ;; strings. `edn-encoded-attr?`, `encode-transaction`, and
            ;; `decode-attribute-value` own the matching codec at this bridge.
            :db.type/string))
      (schema.form/nilable-value-schema? resolved)
      (throw (ex-info (str "Stored attributes cannot use `:maybe`. Register the "
                           "non-nil base shape, then omit an absent key or mark "
                           "its entity-map entry `{:optional true}`.")
                      {::form resolved :seon.error/kind :user-input}))
      :else
      (or (malli-type->datahike-type head)
          (throw (ex-info
                  (str "The Malli form has no Datahike value type. Register a "
                       "concrete storable shape, for example "
                       (registration-form :my.domain/value :string) ".")
                  {::form resolved :seon.error/kind :user-input}))))))

(defn form->cardinality
  "The Datahike cardinality represented by one Malli form."
  {:malli/schema [:=> [:cat :seon.schema/definition] :keyword]}
  [form]
  (let [resolved (resolve-datahike-form form)]
    (if (and (vector? resolved)
             (#{:vector :set :sequential} (form-head resolved)))
      :db.cardinality/many
      :db.cardinality/one)))

(defn form->child-form
  "The stored child form for a collection schema, or the scalar form."
  {:malli/schema
   [:=> [:cat :seon.schema/definition] :seon.schema/definition]}
  [form]
  (let [resolved (resolve-datahike-form form)]
    (if (and (vector? resolved)
             (#{:vector :set :sequential} (form-head resolved)))
      (first (form-children resolved))
      resolved)))

(defn malli->datahike-attr
  "Derive one ordinary Datahike attribute declaration."
  {:malli/schema [:=> [:cat :keyword] :map]}
  [attr]
  (let [raw (or (schema/schema-definition attr)
                (throw (ex-info
                        (str "The attribute has no registered schema. Run "
                             (registration-form attr :string)
                             " with the intended concrete type before "
                             "transacting it.")
                        {::attr attr :seon.error/kind :user-input})))
        resolved (resolve-malli-form raw)
        props (schema.form/attr-form-properties resolved)
        value-form (form->child-form resolved)
        value-type (form->datahike-value-type value-form)
        secondary? (boolean (:db.secondary/only props))]
    (when (and secondary?
               (not (contains? #{:db.type/float :db.type/double} value-type)))
      (throw (ex-info
              (str "A secondary-only attribute must contain floats. Register "
                   (registration-form attr
                                      [:float {:db.secondary/only true}]) ".")
              {::attr attr :seon.error/kind :user-input})))
    (cond-> {:db/ident attr
             :db/valueType (if secondary?
                             :db.type/tuple
                             value-type)
             :db/cardinality (if secondary?
                               :db.cardinality/one
                               (form->cardinality resolved))}
      secondary? (assoc :db.secondary/only true)
      (:seon.db/identity props) (assoc :db/unique :db.unique/identity)
      (:seon.db/unique props) (assoc :db/unique :db.unique/value)
      (:seon.db/index props) (assoc :db/index true)
      (:seon.db/component props) (assoc :db/isComponent true)
      (:seon.db/no-history? props) (assoc :db/noHistory true))))

(defn malli->datahike-schema
  "Derive ordered Datahike attribute declarations."
  {:malli/schema [:=> [:cat [:sequential :keyword]] [:vector :map]]}
  [attrs]
  (mapv malli->datahike-attr attrs))

(defn edn-encoded-attr?
  "True when a heterogeneous Malli union uses the EDN string fallback."
  {:malli/schema [:=> [:cat :keyword] :boolean]}
  [attr]
  (boolean
   (when (schema/registered? attr)
     (let [form (resolve-malli-form (schema/schema-definition attr))
           explicit
           (:seon.db/value-type (schema.form/attr-form-properties form))
           types
           (when (= :or (form-head form))
             (into #{}
                   (map #(try
                           (form->datahike-value-type %)
                           (catch #?(:clj Throwable :cljs :default) _
                             ::unmappable)))
                   (form-children form)))]
       (and types
            (nil? explicit)
            (or (> (count types) 1)
                (contains? types ::unmappable)))))))

(defn- refuse-slot!
  [rule attr value]
  (throw
   (ex-info
    (str "The EDN-backed attribute " attr " has an invalid logical value.")
    {::rule rule
     ::attr attr
     ::value value
     :seon.error/kind :user-input})))

(defn- validate-logical-slot!
  [attr value]
  (when-not (schema/valid-candidate-value? attr value)
    (refuse-slot! ::schema-invalid attr value))
  value)

(defn- encode-entity
  [entity]
  (reduce-kv
   (fn [encoded attr value]
     (assoc encoded attr
            (cond
              (edn-encoded-attr? attr)
              (pr-str (validate-logical-slot! attr value))

              (map? value)
              (encode-entity value)

              (and (vector? value) (some map? value))
              (mapv #(if (map? %) (encode-entity %) %) value)

              (and (set? value) (some map? value))
              (into #{} (map #(if (map? %) (encode-entity %) %)) value)

              (and (sequential? value) (some map? value))
              (mapv #(if (map? %) (encode-entity %) %) value)

              :else value)))
   (empty entity)
   entity))

(defn- encode-transaction-data
  [transaction-data]
  (mapv
   (fn [operation]
     (cond
       (map? operation)
       (encode-entity operation)

       (and (vector? operation)
            (= :db/add (first operation))
            (edn-encoded-attr? (nth operation 2 nil)))
       (update operation 3
               #(pr-str
                 (validate-logical-slot! (nth operation 2) %)))

       :else operation))
   transaction-data))

(defn encode-transaction
  "Encode heterogeneous union slots once at the Datahike transaction seam."
  {:malli/schema [:=> [:cat :seon.store/transaction]
                  :seon.store/transaction]}
  [transaction]
  (if (map? transaction)
    (update transaction :tx-data encode-transaction-data)
    (encode-transaction-data transaction)))

(defn decode-attribute-value
  "Decode and validate one value read from an EDN-backed attribute."
  {:malli/schema [:=> [:cat :keyword :seon.schema/value]
                  :seon.schema/value]}
  [attr value]
  (if-not (edn-encoded-attr? attr)
    value
    (do
      (when-not (string? value)
        (refuse-slot! ::storage-not-string attr value))
      (let [decoded
            (try
              (edn/read-string value)
              (catch #?(:clj Throwable :cljs :default) _
                (refuse-slot! ::malformed-edn attr value)))]
        (when-not (= value (pr-str decoded))
          (refuse-slot! ::noncanonical-edn attr value))
        (validate-logical-slot! attr decoded)))))
