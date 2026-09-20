(ns seon.schema.datahike
  "Derive Datahike attribute declarations from registered Malli schemas.

   The schema registry is the one shape authority; this namespace is its
   database bridge — registered attribute forms in, ordinary Datahike
   schema maps out. Pure derivation: no connection, no session, no
  transaction. The store owner transacts the returned declarations."
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema :as schema]
            [seon.id :as id]
            [seon.schema.internal :as internal]
            [seon.schema.form :as schema.form]))

(defn- packaged-forms []
  ((requiring-resolve 'seon.schema.edn/packaged-forms)))

(defn form-children
  "The non-property children of one Malli form."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] [:vector :seon.schema/value]]}
  [form]
  (if (vector? form)
    (into [] (remove map?) (rest form))
    []))

(defn resolve-malli-form-in
  "Resolve registered aliases against exactly one immutable projection.

  It takes and returns any authored VALUE, not only a parseable form: the
  bridge hands it every child of a declaration — predicate symbols,
  properties maps, unregistered keywords — and answers them unchanged. The
  declared `:seon.schema/definition` was a narrower promise than the
  function keeps, which only an armed contract could say out loud."
  {:malli/schema
   [:=> [:cat :map [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value]}
  [projection form]
  (cond
    (= :seon.db/ref form) form
    (and (keyword? form)
         (contains? (:seon.schema.projection/forms projection) form))
    (let [definition (get (:seon.schema.projection/forms projection) form)]
      (if (or (keyword? definition) (vector? definition))
        (resolve-malli-form-in projection definition)
        form))
    :else form))

(defn resolve-malli-form
  "Resolve aliases against the canonical JVM declaration population."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value]}
  [form]
  (resolve-malli-form-in
   {:seon.schema.projection/forms (packaged-forms)}
   form))

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
   :qualified-symbol :db.type/symbol
   :tuple :db.type/tuple})

(defn form-head
  "The head of one Malli form.

  It takes any authored VALUE: the bridge walks every child of a
  declaration through it, and a child is often not a parseable form on its
  own."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value]}
  [form]
  (if (vector? form) (first form) form))

(defn resolve-datahike-form-in
  "Resolve aliases and wrappers in one projection to the stored form."
  {:malli/schema
   [:=> [:cat :map [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value]}
  [projection form]
  (let [resolved (resolve-malli-form-in projection form)]
    (if (= :and (form-head resolved))
      (resolve-datahike-form-in projection (first (form-children resolved)))
      resolved)))

(defn resolve-datahike-form
  "Resolve aliases and wrappers against canonical JVM declarations."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value]}
  [form]
  (resolve-datahike-form-in
   {:seon.schema.projection/forms (packaged-forms)}
   form))

(defn- registration-form
  [attr schema-form]
  (pr-str (list 'schema/register! attr schema-form)))

(declare form->datahike-value-type-in)

(defn- literal->datahike-value-type
  [literal]
  (cond
    (string? literal) :db.type/string
    (boolean? literal) :db.type/boolean
    (keyword? literal) :db.type/keyword
    (symbol? literal) :db.type/symbol
    (uuid? literal) :db.type/uuid
    (inst? literal) :db.type/instant
    (instance? Long literal)
    :db.type/long
    (instance? Double literal)
    :db.type/double
    (instance? Float literal)
    :db.type/float
    :else nil))

(defn- compiled-storage
  "Fold compiled value nodes; reference identity precedes logical dereference."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] :map]}
  [compiled]
  (letfn [(fold [compiled active]
   (m/walk
   compiled
   (fn [node _ children _]
     (let [t (m/type node)
           properties (m/properties node)
           child (first children)
           result
           (cond
             (and (m/-ref-schema? node) (= :seon.db/ref (m/-ref node)))
             {::value-type :db.type/ref ::cardinality :db.cardinality/one}

             (m/-ref-schema? node)
             (let [reference [(-> node m/options :registry) (m/-ref node)]
                   target (if (and (m/-ref node) (contains? active reference))
                            {::value-type nil}
                            (if (nil? (m/-ref node))
                              child
                              (fold (m/deref node) (conj active reference))))]
               (assoc target ::properties (merge (::properties target) properties)))

             (= :and t)
             (assoc child ::properties properties)

             (#{:set :vector :sequential} t)
             (assoc child ::cardinality :db.cardinality/many
                          ::properties properties ::edn? false)

             (and (= :or t)
                  (some #(and (m/-ref-schema? %)
                              (= :seon.db/component-entity (m/-ref %)))
                        (m/children node)))
             child

             (= :or t)
             (let [types (set (map ::value-type children))]
               {::value-type (or (:seon.db/value-type properties)
                                (when (and (= 1 (count types)) (not (contains? types nil)))
                                  (first types))
                                :db.type/string)
                ::edn? (and (nil? (:seon.db/value-type properties))
                            (or (> (count types) 1) (contains? types nil)))})

             (= := t) {::value-type (literal->datahike-value-type child)}
             (= :enum t) {::value-type (when (every? keyword? children) :db.type/keyword)}
             (= :tuple t) {::value-type :db.type/tuple
                            ::tuple-types (mapv ::value-type children)}
             (= 'inst? t) {::value-type :db.type/instant}
             :else {::value-type (malli-type->datahike-type t)})]
       (cond-> (merge {::cardinality :db.cardinality/one ::properties properties} result)
         (:seon.db/component properties)
         (assoc ::value-type :db.type/ref ::edn? false))))))]
    (fold compiled #{})))

(defn- compiled-attribute
  "Derive a native declaration from one retained root for parity verification."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :map]}
  [projection attribute]
  (let [root (mr/schema (:seon.schema.projection/registry projection) attribute)
        _ (when-not (m/schema? root)
            (throw (ex-info "Missing retained attribute declaration."
                            {:seon.schema/missing-reference attribute})))
        {::keys [value-type cardinality properties tuple-types]}
        (compiled-storage root)
        value-type (if (= :seon.db/ref attribute) :db.type/ref value-type)
        secondary? (:db.secondary/only properties)]
    (when-not value-type
      (throw (ex-info "Compiled attribute has no native storage type."
                      {::attr attribute ::value-type-unavailable true})))
    (when (and secondary? (not (#{:db.type/float :db.type/double} value-type)))
      (throw (ex-info "A secondary-only attribute must contain floats."
                      {::attr attribute ::invalid-secondary-attribute attribute})))
    (cond-> {:db/ident attribute
             :db/valueType (if secondary? :db.type/tuple value-type)
             :db/cardinality (if secondary? :db.cardinality/one cardinality)}
      tuple-types (assoc :db/tupleTypes tuple-types)
      secondary? (assoc :db.secondary/only true)
      (:seon.db/identity properties) (assoc :db/unique :db.unique/identity)
      (:seon.db/unique properties) (assoc :db/unique :db.unique/value)
      (:seon.db/index properties) (assoc :db/index true)
      (:seon.db/component properties) (assoc :db/isComponent true)
      (:seon.db/no-history? properties) (assoc :db/noHistory true))))

(defn- compiled-attribute-selection
  "Select core and storable property attributes from retained canonical roots."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
  (let [registry (:seon.schema.projection/registry projection)
        roots (mapv #(mr/schema registry %) (keys (:seon.schema.projection/forms projection)))
        facets #{:seon.db/identity :seon.db/unique :seon.db/index
                 :seon.db/component :seon.db/no-history? :db.secondary/only}
        core
        (reduce
         (fn [attributes [k root]]
           (let [properties (m/properties root)]
             (cond-> (if (true? (:seon.db/attributes (internal/entity-properties root)))
                       (into attributes
                             (comp
                              (filter (fn [[attribute properties _]]
                                        (or (not (:optional properties))
                                            (try (compiled-attribute projection attribute)
                                                 true
                                                 (catch clojure.lang.ExceptionInfo _ false)))))
                              (map first) (filter qualified-keyword?))
                             (internal/entity-entries root))
                       attributes)
               (and (qualified-keyword? k) (some #(contains? properties %) facets))
               (conj k))))
         #{} (map vector (keys (:seon.schema.projection/forms projection)) roots))
        properties
        (into (sorted-set)
              (comp (mapcat #(keys (m/properties %)))
                    (filter qualified-keyword?)
                    (filter #(try (compiled-attribute projection %) true
                                  (catch clojure.lang.ExceptionInfo _ false))))
              roots)]
    {::core (vec (sort-by str core))
     ::properties properties
     ::attributes (vec (sort-by str (into core properties)))}))

(defn form->datahike-value-type-in
  "The Datahike value type represented by a form in one projection."
  {:malli/schema [:=> [:cat :map [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :keyword]}
  [projection form]
  (let [resolved (resolve-datahike-form-in projection form)
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
            {::form resolved
             ::literal-not-storable true
             :seon.error/kind :user-input})))
      (= :enum head)
      (if (every? keyword? (form-children resolved))
        :db.type/keyword
        (throw (ex-info
                (str "Only keyword Malli enums are storable. Register keyword "
                     "members, for example "
                     (registration-form :my.domain/status
                                        [:enum :open :done]) ".")
                {::form resolved
                 ::enum-not-storable true
                 :seon.error/kind :user-input})))
      (= :or head)
      (let [explicit (:seon.db/value-type
                      (schema.form/attr-form-properties resolved))
            types (into #{} (map #(try
                                    (form->datahike-value-type-in projection %)
                                    (catch Throwable _
                                      ::unmappable)))
                        (form-children resolved))]
        (or explicit
            (when (and (= 1 (count types))
                       (not (contains? types ::unmappable)))
              (first types))
            ;; Mixed unions deliberately store their logical values as EDN
            ;; strings. `edn-encoded-attr-in?`, `encode-transaction`, and
            ;; `decode-attribute-value` own the matching codec at this bridge.
            :db.type/string))
      (schema.form/nilable-value-schema? resolved)
      (throw (ex-info (str "Stored attributes cannot use `:maybe`. Register the "
                           "non-nil base shape, then omit an absent key or mark "
                           "its entity-map entry `{:optional true}`.")
                      {::form resolved
                       ::nilable-attribute ::form
                       :seon.error/kind :user-input}))
      :else
      (or (malli-type->datahike-type head)
          (throw (ex-info
                  (str "The Malli form has no Datahike value type. Register a "
                       "concrete storable shape, for example "
                       (registration-form :my.domain/value :string) ".")
                  {::form resolved
                   ::value-type-unavailable true
                   :seon.error/kind :user-input}))))))

(defn form->datahike-value-type
  "The Datahike value type represented by a canonical JVM Malli form."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :keyword]}
  [form]
  (form->datahike-value-type-in
   {:seon.schema.projection/forms (packaged-forms)}
   form))

(defn form->cardinality
  "The Datahike cardinality represented by one Malli form."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :keyword]}
  [form]
  (let [resolved (resolve-datahike-form form)]
    (if (and (vector? resolved)
             (#{:vector :set :sequential} (form-head resolved)))
      :db.cardinality/many
      :db.cardinality/one)))

(defn- form->cardinality-in
  [projection form]
  (let [resolved (resolve-datahike-form-in projection form)]
    (if (and (vector? resolved)
             (#{:vector :set :sequential} (form-head resolved)))
      :db.cardinality/many
      :db.cardinality/one)))

(defn form->child-form
  "The stored child form for a collection schema, or the scalar form."
  {:malli/schema
   [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value]}
  [form]
  (let [resolved (resolve-datahike-form form)]
    (if (and (vector? resolved)
             (#{:vector :set :sequential} (form-head resolved)))
      (first (form-children resolved))
      resolved)))

(defn- form->child-form-in
  [projection form]
  (let [resolved (resolve-datahike-form-in projection form)]
    (if (and (vector? resolved)
             (#{:vector :set :sequential} (form-head resolved)))
      (first (form-children resolved))
      resolved)))

(defn malli->datahike-attr-in
  "Derive one Datahike attribute declaration from one projection."
  {:malli/schema [:=> [:cat :map :keyword] :map]}
  [projection attr]
  (let [raw (or (get (:seon.schema.projection/forms projection) attr)
                (throw (ex-info
                        (str "The attribute has no registered schema. Run "
                             (registration-form attr :string)
                             " with the intended concrete type before "
                             "transacting it.")
                        {::attr attr
                         ::attribute-absent attr
                         :seon.error/kind :user-input})))
        resolved (resolve-malli-form-in projection raw)
        props (schema.form/attr-form-properties resolved)
        value-form (form->child-form-in projection resolved)
        value-type (form->datahike-value-type-in projection value-form)
        secondary? (boolean (:db.secondary/only props))]
    (when (and secondary?
               (not (contains? #{:db.type/float :db.type/double} value-type)))
      (throw (ex-info
              (str "A secondary-only attribute must contain floats. Register "
                   (registration-form attr
                                      [:float {:db.secondary/only true}]) ".")
              {::attr attr
               ::invalid-secondary-attribute attr
               :seon.error/kind :user-input})))
    (cond-> {:db/ident attr
             :db/valueType (if secondary?
                             :db.type/tuple
                             value-type)
             :db/cardinality (if secondary?
                               :db.cardinality/one
                               (form->cardinality-in projection resolved))}
      (= :tuple (form-head (resolve-datahike-form-in projection value-form)))
      (assoc :db/tupleTypes
             (mapv #(form->datahike-value-type-in projection %)
                   (form-children (resolve-datahike-form-in projection value-form))))
      secondary? (assoc :db.secondary/only true)
      (:seon.db/identity props) (assoc :db/unique :db.unique/identity)
      (:seon.db/unique props) (assoc :db/unique :db.unique/value)
      (:seon.db/index props) (assoc :db/index true)
      (:seon.db/component props) (assoc :db/isComponent true)
      (:seon.db/no-history? props) (assoc :db/noHistory true))))

(defn malli->datahike-attr
  "Derive one Datahike attribute from canonical JVM declarations."
  {:malli/schema [:=> [:cat :keyword] :map]}
  [attr]
  (malli->datahike-attr-in
   {:seon.schema.projection/forms (packaged-forms)}
   attr))

(defn malli->datahike-schema-in
  "Derive ordered Datahike declarations from one projection."
  {:malli/schema [:=> [:cat :map [:sequential :keyword]] [:vector :map]]}
  [projection attrs]
  (mapv #(malli->datahike-attr-in projection %) attrs))

(defn malli->datahike-schema
  "Derive ordered Datahike attribute declarations."
  {:malli/schema [:=> [:cat [:sequential :keyword]] [:vector :map]]}
  [attrs]
  (malli->datahike-schema-in
   {:seon.schema.projection/forms (packaged-forms)}
   attrs))

(defn storable-attribute-in?
  "True when an attribute exists and the bridge maps its declared value."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :boolean]}
  [projection attr]
  (boolean
   (when (contains? (:seon.schema.projection/forms projection) attr)
     (try
       (malli->datahike-attr-in projection attr)
       true
       (catch clojure.lang.ExceptionInfo _ false)))))

(defn storable-properties-in
  "Namespaced properties whose own declarations are database-storable."
  {:malli/schema [:=> [:cat :map :seon.schema/definition] :map]}
  [projection definition]
  (into {}
        (filter (fn [[property _]]
                  (storable-attribute-in? projection property)))
        (schema.form/namespaced-properties definition)))

(defn database-attributes-for-in
  "Database attributes in `forms`, including properties storable by `projection`."
  {:malli/schema
   [:=> [:cat :map [:fn clojure.core/map?]]
    [:vector :qualified-keyword]]}
  [projection forms]
  ;; Passing the projection answers this bridge's explicit lookups. Supplying
  ;; the same forms answers registered predicates such as `malli-form?`, which
  ;; Malli invokes with the candidate value alone while the attribute walk is
  ;; in progress. Without both, one 525-attribute `/data` derivation resolved
  ;; the complete classpath population 530 times (7.1-8.2 s, 2026-08-10).
  (schema/call-with-forms
   (:seon.schema.projection/forms projection)
   #(->> (schema.form/property-attributes forms)
         (filter (fn [attribute]
                   (storable-attribute-in? projection attribute)))
         (into
          (reduce-kv
           (fn [attributes _ definition]
             (if (true? (:seon.db/attributes (schema.form/schema-properties forms definition)))
               (reduce
                (fn [selected entry]
                  (let [attribute (first entry)
                        optional? (and (= 3 (count entry)) (:optional (second entry)))]
                    (if (and optional? (not (storable-attribute-in? projection attribute)))
                      selected
                      (conj selected attribute))))
                attributes (schema.form/map-entries forms definition))
               attributes))
           (into #{}
                 (filter (fn [attribute]
                           (or (storable-attribute-in? projection attribute)
                               (some (set (keys (schema.form/attr-form-properties
                                                (get forms attribute))))
                                     [:seon.db/identity :seon.db/unique :seon.db/index
                                      :seon.db/component :seon.db/no-history? :db.secondary/only]))))
                 (schema.form/database-attributes forms))
           forms))
         (sort-by str)
         vec)))

(defn database-attributes-in
  "Database attributes plus bridge-storable schema-row properties."
  {:malli/schema [:=> [:cat :map] [:vector :qualified-keyword]]}
  [projection]
  (database-attributes-for-in
   projection (:seon.schema.projection/forms projection)))

(defn edn-encoded-attr-in?
  "True when an attribute in `projection` uses the EDN string fallback."
  {:malli/schema [:=> [:cat :map :keyword] :boolean]}
  [projection attr]
  (boolean
   (when (contains? (:seon.schema.projection/forms projection) attr)
     (let [form (resolve-datahike-form-in
                 projection
                 (get (:seon.schema.projection/forms projection) attr))
           explicit
           (:seon.db/value-type (schema.form/attr-form-properties form))
           types
           (when (= :or (form-head form))
             (into #{}
                   (map #(try
                           (form->datahike-value-type-in projection %)
                           (catch Throwable _
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
     rule (if (= ::schema-invalid rule) attr true)
     :seon.error/kind :user-input})))

(defn- validate-logical-slot-in!
  [projection attr value]
  (when-not ((schema/projection-validator projection attr) value)
    (refuse-slot! ::schema-invalid attr value))
  value)

(declare encode-entity-in encode-transaction-data-in)

(def ^:private encoded-operation-key ::encoded-operation)

(defn- encoded-operation?
  [operation]
  (true? (get (meta operation) encoded-operation-key)))

(defn- mark-encoded
  [operation]
  (vary-meta operation assoc encoded-operation-key true))

(def ^:private storage-readers
  {'seon.schema.datahike/keyword
   (fn [[namespace-name local-name]]
     (keyword namespace-name local-name))
   'seon.schema.datahike/symbol
   (fn [[namespace-name local-name]]
     (symbol namespace-name local-name))})

(defn- canonical-print-string
  [value]
  (binding [*print-length* nil
            *print-level* nil
            *print-meta* false
            *print-readably* true
            *print-dup* false
            *print-namespace-maps* true]
    (pr-str value)))

(defn- storage-compare
  [left right]
  (compare (canonical-print-string left)
           (canonical-print-string right)))

(defn- reader-round-trips?
  [value]
  (try
    (= value (edn/read-string (canonical-print-string value)))
    (catch Throwable _ false)))

(defn- storage-data
  "Replace inexpressible identifiers and impose canonical collection order."
  [value]
  (walk/postwalk
   (fn [element]
     (cond
       (and (keyword? element) (not (reader-round-trips? element)))
       (tagged-literal 'seon.schema.datahike/keyword
                       [(namespace element) (name element)])

       (and (symbol? element) (not (reader-round-trips? element)))
       (tagged-literal 'seon.schema.datahike/symbol
                       [(namespace element) (name element)])

       (map? element)
       (into (sorted-map-by storage-compare) element)

       (set? element)
       (into (sorted-set-by storage-compare) element)

       :else element))
   value))

(defn- storage-string
  [value]
  (canonical-print-string (storage-data value)))

(defn- encode-value-in
  [projection attr value]
  (cond
    (edn-encoded-attr-in? projection attr)
    (storage-string (validate-logical-slot-in! projection attr value))

    (map? value)
    (encode-entity-in projection value)

    (and (vector? value) (some map? value))
    (mapv #(if (map? %) (encode-entity-in projection %) %) value)

    (and (set? value) (some map? value))
    (into #{} (map #(if (map? %) (encode-entity-in projection %) %)) value)

    (and (sequential? value) (some map? value))
    (mapv #(if (map? %) (encode-entity-in projection %) %) value)

    :else value))

(defn- encode-entity-in
  [projection entity]
  (reduce-kv
   (fn [encoded attr value]
     (assoc encoded attr (encode-value-in projection attr value)))
   (empty entity)
   entity))

(defn- encode-call-output-in
  "Encode transaction data returned by one Datahike transaction function."
  [db projection f & args]
  (encode-transaction-data-in
   projection
   (apply f (vary-meta db assoc :seon.schema/projection projection) args)))

(defn- explicit-value-members
  "Avoid Datahike treating two keyword members as one identity lookup ref."
  [projection entity]
  (let [operations (volatile! [])
        entity
        (walk/postwalk
         (fn [value]
           (if-not (map? value)
             value
             (let [members
                   (into {}
                         (filter
                          (fn [[attribute members]]
                            (and (coll? members) (not (map? members))
                                 (= 2 (count members)) (keyword? (first members))
                                 (get (:seon.schema.projection/forms projection) attribute)
                                 (let [installed (malli->datahike-attr-in projection attribute)]
                                   (and (= :db.cardinality/many (:db/cardinality installed))
                                        (not= :db.type/ref (:db/valueType installed)))))))
                         value)]
               (if (empty? members)
                 value
                 (let [entity-id (or (:db/id value) (str "seon.schema.datahike/" (id/id)))]
                   (vswap! operations into
                           (for [[attribute values] members member values]
                             (mark-encoded [:db/add entity-id attribute member])))
                   (assoc (apply dissoc value (keys members)) :db/id entity-id))))))
         entity)]
    (into [(mark-encoded entity)] @operations)))

(defn- encode-transaction-data-in
  [projection transaction-data]
  (into [] (mapcat
   (fn [operation]
     (cond
       (encoded-operation? operation)
       [operation]

       (map? operation)
       (explicit-value-members projection (encode-entity-in projection operation))

       (and (vector? operation)
            (= :db/add (first operation))
            (edn-encoded-attr-in? projection (nth operation 2 nil)))
       [(mark-encoded
        (update operation 3
                #(storage-string
                  (validate-logical-slot-in!
                   projection (nth operation 2) %))))]

       (and (vector? operation)
            (= :db.fn/call (first operation)))
       ;; Datahike invokes the transaction function after this outer encoder
       ;; has run, then splices its returned transaction data into the same
       ;; transaction. Route that returned data through this same codec before
       ;; Datahike interprets it. The metadata marker is process-local and
       ;; prevents a nested owner that already called this codec from being
       ;; encoded twice; Datahike stores only the operation's ordinary data.
       [(mark-encoded
        (into [:db.fn/call #'encode-call-output-in
               projection (second operation)]
              (nnext operation)))]

       :else [operation])))
   transaction-data))

(defn encode-transaction-in
  "Encode heterogeneous union slots against exactly one projection."
  {:malli/schema [:=> [:cat :map :seon.store/transaction]
                  :seon.store/transaction]}
  [projection transaction]
  ;; Passing the projection answers this bridge's explicit lookups. Supplying
  ;; the same forms answers registered predicates such as `malli-form?`, which
  ;; Malli invokes with the candidate value alone while encoding each
  ;; attribute. The fault committer runs on a Flow thread with no ambient
  ;; projection, so passing without supplying made valid scalar forms such as
  ;; `:boolean` fail instrumentation before a core fault could be recorded.
  (schema/call-with-forms
   (:seon.schema.projection/forms projection)
   #(if (map? transaction)
      (update transaction :tx-data
              (fn [transaction-data]
                (encode-transaction-data-in projection transaction-data)))
      (encode-transaction-data-in projection transaction))))

(defn encode-transaction
  "Encode heterogeneous union slots once at the Datahike transaction seam.

   The declaration population is resolved EXACTLY ONCE per transaction and
   then passed explicitly to [[encode-transaction-in]]. Resolving it per
   attribute was the 2026-08-07 suite wedge: with no declaration population
   supplied on the calling thread, `schema/registered-schemas` falls through
   to `seon.schema.edn/packaged-forms`, which re-reads and re-validates all
   151 schema resources from the classpath — 14 ms per attribute, against
   0.004 ms once the population is a value in hand."
  {:malli/schema [:=> [:cat :seon.store/transaction]
                  :seon.store/transaction]}
  [transaction]
  (encode-transaction-in (schema/declaration-projection) transaction))

(defn encode-attribute-value-in
  "Encode one attribute value against exactly one projection.

   The storage counterpart of [[decode-attribute-value-in]], and the way a
   caller that holds ONE value obtains its stored form. [[encode-transaction-in]]
   answers for a whole transaction, whose declared operations are
   `[:or :map [:vector :seon.schema/value]]` (`seon.store.edn`); reading a
   single encoded value back out of one by position assumes the vector branch
   the contract does not promise."
  {:malli/schema [:=> [:cat :map :keyword :seon.schema/value]
                  :seon.schema/value]}
  [projection attr value]
  ;; `validate-logical-slot-in!` runs registered predicates such as
  ;; `malli-form?`, which Malli invokes with the candidate value alone. Supply
  ;; the projection's forms for the same reason `encode-transaction-in` does.
  (schema/call-with-forms
   (:seon.schema.projection/forms projection)
   #(encode-value-in projection attr value)))

(defn decode-attribute-value-in
  "Decode one attribute value against exactly one projection."
  {:malli/schema [:=> [:cat :map :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value]}
  [projection attr value]
  (if-not (edn-encoded-attr-in? projection attr)
    value
    (do
      (when-not (string? value)
        (refuse-slot! ::storage-not-string attr value))
      (let [decoded
            (try
              (edn/read-string {:readers storage-readers} value)
              (catch Throwable _
                (refuse-slot! ::malformed-edn attr value)))]
        (when-not (= value (storage-string decoded))
          (refuse-slot! ::noncanonical-edn attr value))
        (validate-logical-slot-in! projection attr decoded)))))

(defn decode-attribute-value
  "Decode and validate one value read from an EDN-backed attribute.

   Resolves the declaration population ONCE; a caller decoding more than one
   attribute resolves it itself and calls [[decode-attribute-value-in]]."
  {:malli/schema [:=> [:cat :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value]}
  [attr value]
  (decode-attribute-value-in (schema/declaration-projection) attr value))
