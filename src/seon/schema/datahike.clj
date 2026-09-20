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
            [seon.schema.internal :as internal]))

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

(defn- registration-form
  [attr schema-form]
  (pr-str (list 'schema/register! attr schema-form)))

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

(defn storage-schema
  "The compiled native value shape, preserving the named reference token."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] [:fn malli.core/schema?]]}
  [compiled]
  (loop [node compiled seen #{}]
    (let [reference (when (m/-ref-schema? node) (m/-ref node))
          identity [(m/options node) reference]]
      (cond
        (= :seon.db/ref reference) node
        (and reference (contains? seen identity))
        (throw (ex-info "Cyclic native storage declaration."
                        {:seon.schema/definition (m/form compiled)}))
        (m/-ref-schema? node) (recur (m/deref node) (conj seen identity))
        (= :and (m/type node)) (recur (first (m/children node)) seen)
        :else node))))

(defn value-schema
  "The compiled child of native-many collections, otherwise the scalar schema."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] [:fn malli.core/schema?]]}
  [compiled]
  (let [node (storage-schema compiled)]
    (if (#{:set :vector :sequential} (m/type node))
      (first (m/children node))
      node)))

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
                          ::properties properties ::edn? false ::collection? true
                          ::value-type (when-not (::collection? child) (::value-type child)))

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

             (= := t) (if-let [value-type (literal->datahike-value-type child)]
                        {::value-type value-type}
                        {::refusal [::literal-not-storable (m/form node)]})
             (= :enum t) (if (every? keyword? children)
                           {::value-type :db.type/keyword}
                           {::refusal [::enum-not-storable (m/form node)]})
             (= :maybe t) {::refusal [::nilable-attribute (m/form node)]}
             (= :tuple t) (if-let [invalid (some #(when (or (::refusal %) (::collection? %) (nil? (::value-type %))) %) children)]
                            {::refusal (or (::refusal invalid) [::value-type-unavailable (m/form node)])}
                            {::value-type :db.type/tuple
                             ::tuple-types (mapv ::value-type children)})
             (= 'inst? t) {::value-type :db.type/instant}
             :else {::value-type (malli-type->datahike-type t)})]
       (merge {::cardinality :db.cardinality/one ::properties properties} result))))) ]
    (fold compiled #{})))

(defn malli->datahike-attr-in
  "Derive a native declaration from the supplied generation's retained root."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :map]}
  [projection attribute]
  (let [root (mr/schema (:seon.schema.projection/registry projection) attribute)
        _ (when-not (m/schema? root)
            (throw (ex-info
                    (str "The attribute has no registered schema. Run "
                         (registration-form attribute :string)
                         " with the intended concrete type before transacting it.")
                    {::attr attribute ::attribute-absent attribute
                     :seon.error/kind :user-input})))
        {::keys [value-type cardinality properties tuple-types refusal]}
        (compiled-storage root)
        value-type (if (= :seon.db/ref attribute) :db.type/ref value-type)
        secondary? (:db.secondary/only properties)]
    (when (or refusal (nil? value-type))
      (let [[reason form] (or refusal [::value-type-unavailable (m/form (value-schema root))])
            message (case reason
                      ::literal-not-storable "Only scalar Malli literals are storable. Register a literal whose value has a native Datahike type."
                      ::enum-not-storable "Only keyword Malli enums are storable. Register keyword members."
                      ::nilable-attribute "Stored attributes cannot use `:maybe`. Register the non-nil base shape, then omit an absent key or mark its entity-map entry `{:optional true}`."
                      "The Malli form has no Datahike value type. Register a concrete storable shape.")]
        (throw (ex-info message {::attr attribute ::form form
                                 reason (if (= reason ::nilable-attribute) ::form true)
                                 :seon.error/kind :user-input}))))
    (when (and secondary? (not (#{:db.type/float :db.type/double} value-type)))
      (throw (ex-info "A secondary-only attribute must contain floats."
                      {::attr attribute ::invalid-secondary-attribute attribute
                       :seon.error/kind :user-input})))
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

(defn assert-storable-schema!
  "Refuse an error declaration whose members could upsert another entity."
  {:malli/schema [:=> [:cat :keyword [:fn malli.core/schema?]] :nil]}
  [schema-key compiled]
    (when (internal/extends-schema? compiled :seon.error/base)
      (doseq [[attribute _ member] (internal/entity-entries compiled)
              :let [registry (:registry (m/options member))
                    declaration (mr/schema registry attribute)]
              :when (and declaration
                         (:seon.db/identity
                          (m/properties (m/schema declaration {:registry registry}))))]
        (throw (ex-info
                "An error observation cannot carry an entity's upsert identity."
                {:seon.error/kind :user-input
                 :seon.schema/error :seon.schema/invalid-schema
                 :seon.schema/identity schema-key
                 :seon.schema/member attribute})))))

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
                                            (try (malli->datahike-attr-in projection attribute)
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
                    (filter #(try (malli->datahike-attr-in projection %) true
                                  (catch clojure.lang.ExceptionInfo _ false))))
              roots)]
    {::core (vec (sort-by str core))
     ::properties properties
     ::attributes (vec (sort-by str (into core properties)))}))

(defn malli->datahike-schema-in
  "Derive ordered Datahike declarations from one projection."
  {:malli/schema [:=> [:cat :map [:sequential :keyword]] [:vector :map]]}
  [projection attrs]
  (mapv #(malli->datahike-attr-in projection %) attrs))

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
  {:malli/schema [:=> [:cat :map :keyword] :map]}
  [projection schema-key]
  (into {}
        (filter (fn [[property _]]
                  (storable-attribute-in? projection property)))
        (into {} (filter (fn [[k v]] (and (qualified-keyword? k) (some? v))))
              (m/properties (mr/schema (:seon.schema.projection/registry projection) schema-key)))))

(defn database-attributes-core-in
  "Entity members and independently persisted attributes, without metadata properties."
  {:malli/schema [:=> [:cat :map] [:vector :qualified-keyword]]}
  [projection]
  (::core (compiled-attribute-selection projection)))

(defn database-attributes-for-in
  "Select attributes of authored rows using the complete supplied generation."
  {:malli/schema [:=> [:cat :map :map] [:vector :qualified-keyword]]}
  [projection forms]
  (::attributes (compiled-attribute-selection
                 (assoc projection :seon.schema.projection/forms forms))))

(defn database-attributes-in
  "Database attributes plus bridge-storable schema-row properties."
  {:malli/schema [:=> [:cat :map] [:vector :qualified-keyword]]}
  [projection]
  (::attributes (compiled-attribute-selection projection)))

(defn edn-encoded-attr-in?
  "Whether a retained attribute uses the heterogeneous-union EDN string codec."
  {:malli/schema [:=> [:cat :map :keyword] :boolean]}
  [projection attr]
  (boolean
   (when-let [compiled (mr/schema (:seon.schema.projection/registry projection) attr)]
     (::edn? (compiled-storage compiled)))))

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
