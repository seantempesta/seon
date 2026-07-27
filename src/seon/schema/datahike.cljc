(ns seon.schema.datahike
  "Derive Datahike attribute declarations from registered Malli schemas.

   The schema registry is the one shape authority; this namespace is its
   database bridge — registered attribute forms in, ordinary Datahike
   schema maps out. Pure derivation: no connection, no session, no
   transaction. The store owner transacts the returned declarations."
  (:require [seon.schema :as schema]
            [seon.schema.form :as schema.form]))

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
  [form]
  (if (vector? form) (first form) form))

(defn- registration-form
  [attr schema-form]
  (pr-str (list 'schema/register! attr schema-form)))

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
                {::form resolved :seon.error/kind :user-input})))
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
  (let [raw (or (schema/schema-definition attr)
                (throw (ex-info
                        (str "The attribute has no registered schema. Run "
                             (registration-form attr :string)
                             " with the intended concrete type before "
                             "transacting it.")
                        {::attr attr :seon.error/kind :user-input})))
        props (schema.form/attr-form-properties raw)
        value-form (-> raw resolve-malli-form form->child-form resolve-malli-form)
        secondary? (boolean (:db.secondary/only props))]
    (when (and secondary?
               (not (contains? #{:float :double} (form-head value-form))))
      (throw (ex-info
              (str "A secondary-only attribute must contain floats. Register "
                   (registration-form attr
                                      [:float {:db.secondary/only true}]) ".")
              {::attr attr :seon.error/kind :user-input})))
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
