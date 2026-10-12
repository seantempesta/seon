(ns seon.schema.datahike
  "Derive Datahike attribute declarations from registered Malli schemas.

   The schema registry is the one shape authority; this namespace is its
   database bridge — registered attribute forms in, ordinary Datahike
   schema maps out. Pure derivation: no connection, no session, no
  transaction. The store owner transacts the returned declarations."
  (:require [seon.print :as print]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.cache]
            [seon.error.refusal]
            [seon.schema :as schema]
            [seon.id :as id]
            [seon.schema.internal :as internal]
            [datahike.db.interface :as dbi]
            [datalog.parser.pull :as pull-parser]))

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
  "Storage facts retained by the compiled schema; reference identity precedes dereference."
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
               ;; Identity is a property of the attribute being declared: an
               ;; attribute that reuses an identity's value shape by name
               ;; (`:seon.turn/compaction-agent-id :seon.agent/id`) is not an
               ;; upsert key, so the target's identity never crosses the ref.
               (assoc target ::properties
                      (merge (dissoc (::properties target) :seon.db/identity :seon.db/unique)
                             properties)))

             (= :and t)
             (assoc child ::properties properties)

             ;; A vector keeps the installed cardinality-many attribute; its
             ;; order, duplicates and presence live in bridge cells
             ;; (`seon.db.vector.edn`), so `::vector?` names the shape only.
             (#{:set :vector :sequential} t)
             (assoc child ::cardinality :db.cardinality/many
                          ::properties properties ::edn? false ::collection? true
                          ::vector? (contains? #{:vector :sequential} t)
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
    (m/-cached compiled ::compiled-storage #(fold % #{}))))

(defn attribute-declaration
  "A native declaration, or the diagnostic explaining why the attribute is unstorable."
  {:malli/schema [:=> [:cat :map :qualified-keyword]
                  [:or :map :seon.schema.datahike/unstorable-attribute-error]]}
  [projection attribute]
  (let [root (mr/schema (:seon.schema.projection/registry projection) attribute)
        refused (fn [message data]
                  (seon.error.refusal/diagnostic
                   (merge data {:seon.error/at (java.util.Date.) :seon.error/layer ::storage
                                :seon.error/operation 'seon.schema.datahike/attribute-declaration
                                :seon.error/message message
                                ::attr attribute ::unstorable-attribute attribute})))]
   (if-not (m/schema? root)
    (refused "The attribute has no registered schema."
             {::attribute-absent attribute})
   (let [{::keys [value-type cardinality properties tuple-types refusal]}
        (compiled-storage root)
        value-type (if (= :seon.db/ref attribute) :db.type/ref value-type)
        secondary? (:db.secondary/only properties)]
    (cond
     (or refusal (nil? value-type))
      (let [[reason form] (or refusal [::value-type-unavailable (m/form (value-schema root))])
            message (case reason
                      ::literal-not-storable "Only scalar Malli literals are storable. Register a literal whose value has a native Datahike type."
                      ::enum-not-storable "Only keyword Malli enums are storable. Register keyword members."
                      ::nilable-attribute "Stored attributes cannot use `:maybe`. Register the non-nil base shape, then omit an absent key or mark its entity-map entry `{:optional true}`."
                      "A stored attribute needs a storable Datahike value type, and this Malli form has none. Register a concrete storable shape.")]
        (refused message {::form form
                          reason (if (= reason ::nilable-attribute) ::form true)}))
     (and secondary? (not (#{:db.type/float :db.type/double} value-type)))
     (refused "A secondary-only attribute must contain floats."
              {::invalid-secondary-attribute attribute})
     :else
    (cond-> {:db/ident attribute
             :db/valueType (if secondary? :db.type/tuple value-type)
             :db/cardinality (if secondary? :db.cardinality/one cardinality)}
      tuple-types (assoc :db/tupleTypes tuple-types)
      secondary? (assoc :db.secondary/only true)
      (:seon.db/identity properties) (assoc :db/unique :db.unique/identity)
      (:seon.db/unique properties) (assoc :db/unique :db.unique/value)
      (:seon.db/index properties) (assoc :db/index true)
      (:seon.db/component properties) (assoc :db/isComponent true)
      (:seon.db/no-history? properties) (assoc :db/noHistory true)))))))

(defn malli->datahike-attr-in
  "Derive a native declaration from the supplied generation's retained root;
  its refusal (`attribute-declaration`) is thrown whole to the callers that
  have not yet taken the value (task: convert them and delete this arity)."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :map]}
  [projection attribute]
  (let [declaration (attribute-declaration projection attribute)]
    (if (:seon.error/at declaration)
      (throw (ex-info (:seon.error/message declaration) declaration))
      declaration)))

(defn- declared-storable?
  "Whether the compiled attribute has a native storage type, without constructing a diagnostic."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :boolean]}
  [projection attribute]
  (boolean
   (when-let [root (mr/schema (:seon.schema.projection/registry projection) attribute)]
     (when (m/schema? root)
       (let [{::keys [value-type properties refusal]} (compiled-storage root)
             value-type (if (= :seon.db/ref attribute) :db.type/ref value-type)]
         (and (not refusal) (some? value-type)
              (or (not (:db.secondary/only properties))
                  (contains? #{:db.type/float :db.type/double} value-type))))))))

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
                {:seon.schema/error :seon.schema/invalid-schema
                 :seon.schema/identity schema-key
                 :seon.schema/member attribute})))))

(defn- compiled-attribute-selection
  "Core and storable property attributes retained for this registry and form selection."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
  (schema/projection-cache-value
   projection [::compiled-attribute-selection
               (:seon.schema.projection/registry projection)
               (:seon.schema.projection/forms projection)]
   (fn []
  (let [registry (:seon.schema.projection/registry projection)
        roots (mapv #(mr/schema registry %) (keys (:seon.schema.projection/forms projection)))
        storage-properties #{:seon.db/identity :seon.db/unique :seon.db/index
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
                                            (declared-storable? projection attribute))))
                              (map first) (filter qualified-keyword?))
                             (internal/entity-entries root))
                       attributes)
               (and (qualified-keyword? k) (some #(contains? properties %) storage-properties))
               (conj k))))
         #{} (map vector (keys (:seon.schema.projection/forms projection)) roots))
        properties
        (into (sorted-set)
              (comp (mapcat #(keys (m/properties %)))
                    (filter qualified-keyword?)
                    (filter #(declared-storable? projection %)))
              roots)]
    {::core (vec (sort-by str core))
     ::properties properties
     ::attributes (vec (sort-by str (into core properties)))}))))

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
     (declared-storable? projection attr))))

(defn- chain-properties
  "Property maps along Malli's reference chain, nearest first: the schema's
  own, its type's, then a registry pointer's or `:ref`'s target
  (`m/deref`, malli/core.cljc:2864-2870) and an `:and`'s arms with the later
  arm first, as `mu/merge` lets the later arm win (malli/util.cljc:77, 96)."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] [:vector [:maybe :map]]]}
  [compiled]
  (into [(m/properties compiled) (m/type-properties compiled)]
        (cond
          (m/-ref-schema? compiled) (chain-properties (m/deref compiled))
          (= :and (m/type compiled)) (into [] (mapcat chain-properties) (rseq (vec (m/children compiled))))
          :else [])))

(defn declared-reference
  "The function `property` names for `compiled`'s values, own or inherited.

  The nearest value along Malli's reference chain (`chain-properties`), when
  it is a qualified symbol, else nil. A render pair resolves this way, so a
  derived error takes its own arm's pair before `:seon.error/base`'s."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?] :qualified-keyword] [:maybe :qualified-symbol]]}
  [compiled property]
  (let [found (some #(get % property) (chain-properties compiled))]
    (when (qualified-symbol? found) found)))

(defn storable-properties-in
  "Namespaced properties whose own declarations are database-storable.

   A property whose attribute declares `:seon.fn/reference-to` names a
   function: it is stored as its EFFECTIVE value along the reference chain
   (`declared-reference`), a ref to the entity that identity attribute
   names, so the writer refuses a declaration whose function is absent and
   \"which schemas render with f\" is one AVET read. Every other property is
   the schema's own.

   A key absent from the projection refuses by name: `mr/schema` answers nil
   for it, which Malli then reported as an anonymous invalid nil schema."
  {:malli/schema [:=> [:cat :map :keyword] :map]}
  [projection schema-key]
  (let [registry (:seon.schema.projection/registry projection)
        compiled (mr/schema registry schema-key)]
    (when-not compiled
      (throw (ex-info (str "Missing schema declaration " schema-key
                           ": the supplied projection does not declare it.")
                      {:seon.schema/invalid-schema schema-key
                       :seon.schema/key schema-key
                       :seon.schema/missing-reference schema-key
                       :seon.schema/missing-reference-namespace (namespace schema-key)})))
    (let [storable? #(and (qualified-keyword? %) (storable-attribute-in? projection %))
          identity-of #(when-let [identity-attribute (and (qualified-keyword? %)
                                                          (:seon.fn/reference-to (some-> (mr/schema registry %) m/properties)))]
                         (when (storable? %) identity-attribute))
          references (into {}
                           (keep (fn [property]
                                   (when-let [identity-attribute (identity-of property)]
                                     (when-let [function (declared-reference compiled property)]
                                       [property {identity-attribute function}]))))
                           (distinct (mapcat keys (chain-properties compiled))))]
      (into references
            (filter (fn [[property v]]
                      (and (some? v) (storable? property) (not (identity-of property)))))
            (m/properties compiled)))))

(defn property-dependents-in
  "The schema keys whose rows lift one of `attributes` as a property.

  A row's lifted properties are a function of those attributes' declarations
  (`storable-properties-in`): a retyped render face changes every row that
  declares or inherits it, though no row's form changed. A partial
  publication re-derives them beside the schemas its changed declarations
  reach (`seon.schema/dependent-schema-keys`).

  ## Costs

  Empty and free unless a changed key is a storable property attribute; then
  one walk of every schema's reference chain (about 15 ms for 3,664 schemas,
  default, 2026-09-27)."
  {:malli/schema [:=> [:cat :map [:set :keyword]] [:set :keyword]]}
  [projection attributes]
  (let [registry (:seon.schema.projection/registry projection)
        properties (into #{} (filter #(and (qualified-keyword? %) (storable-attribute-in? projection %))) attributes)]
    (if (empty? properties)
      #{}
      (into #{}
            (filter (fn [schema-key]
                      (when-let [compiled (mr/schema registry schema-key)]
                        (some #(some properties (keys %)) (chain-properties compiled)))))
            (filter keyword? (keys (:seon.schema.projection/forms projection)))))))

(defn database-attributes-core-in
  "Entity members and independently persisted attributes, without metadata properties."
  {:malli/schema [:=> [:cat :map] [:vector :qualified-keyword]]}
  [projection]
  (::core (compiled-attribute-selection projection)))

(defn database-attributes-for-in
  "Select attributes of authored entities using the complete supplied generation."
  {:malli/schema [:=> [:cat :map :map] [:vector :qualified-keyword]]}
  [projection forms]
  (::attributes (compiled-attribute-selection
                 (assoc projection :seon.schema.projection/forms forms))))

(defn database-attributes-in
  "Database attributes plus bridge-storable schema-entity properties."
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

(defn- edn-encoded-slot?
  "Whether a write encodes `attr` as its EDN string: its declaration uses the
  codec and, when the writer supplied the installed schema
  (`::installed-schema`), the installed attribute stores strings. A
  publication retypes an installed attribute before the rows that declare its
  new form (the render faces' EDN-to-ref retype): until those rows land the
  projection still reads the old declaration, so the installed type decides,
  as `::cells-installed?` decides for vector cells."
  {:malli/schema [:=> [:cat :map :keyword] :boolean]}
  [projection attr]
  (and (edn-encoded-attr-in? projection attr)
       (if-let [value-type (get-in (::installed-schema projection) [attr :db/valueType])]
         (= :db.type/string value-type)
         true)))

(defn vector-shaped-attr-in?
  "Whether `attr` is declared `[:vector T]` or `[:sequential T]`, stored in
   cells or not ([[vector-attribute-in?]])."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword] :boolean]}
  [projection attr]
  (schema/projection-cache-value
   projection [::vector-shaped attr]
   #(boolean
     (when-let [compiled (mr/schema (:seon.schema.projection/registry projection) attr)]
       (::vector? (compiled-storage compiled))))))

(defn vector-attribute-in?
  "Whether `attr` stores its elements in bridge cells under `projection`: a
   vector-shaped attribute whose projection declares `:seon.db.vector/cells`
   (or whose writer set `::cells-installed?`, `seon.db/prepared-write`). A value
   older than the cells carries a projection without them and reads its
   members as stored."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword] :boolean]}
  [projection attr]
  (and (or (contains? (:seon.schema.projection/forms projection) :seon.db.vector/cells)
           (true? (::cells-installed? projection)))
       (vector-shaped-attr-in? projection attr)))

(defn vector-attributes-in
  "The members of `attributes` that `projection` stores in bridge cells:
   exactly those for which [[vector-attribute-in?]] answers true, asked once
   per distinct attribute."
  {:malli/schema [:=> [:cat :seon.schema/projection [:sequential [:or :keyword :int]]] [:set :keyword]]}
  [projection attributes]
  (if (or (contains? (:seon.schema.projection/forms projection) :seon.db.vector/cells)
          (true? (::cells-installed? projection)))
    (into #{} (comp (filter keyword?) (distinct) (filter #(vector-shaped-attr-in? projection %)))
          attributes)
    #{}))

(defn logical-collection
  "`values` of a cardinality-many attribute in its declared collection:
   a vector for a vector-shaped attribute, otherwise a set."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword
                       [:or [:sequential :seon.schema/value] [:set :seon.schema/value]]]
                  [:or [:vector :seon.schema/value] [:set :seon.schema/value]]]}
  [projection attr values]
  (if (vector-shaped-attr-in? projection attr) (vec values) (set values)))

(defn- refuse-slot!
  {:malli/schema [:=> [:cat :qualified-keyword :qualified-keyword :seon.schema/value] :nil]}
  [rule attr value]
  (throw
   (ex-info
    (case rule
      ::vector-tempid-operation
      (str "A raw operation on vector attribute " attr " names a new entity. "
           "Assert a new entity's vector in its entity map.")
      ::vector-value-not-sequential
      (str "Vector attribute " attr " takes the whole sequence, e.g. [x y]; "
           "append one element with [:db/add e " attr " x].")
      (str "The EDN-backed attribute " attr " has an invalid logical value."))
    {::rule rule
     ::attr attr
     ::value value
     rule (if (= ::schema-invalid rule) attr true)
     })))

(defn- validate-logical-slot-in!
  [projection attr value]
  (when-not ((schema/projection-validator projection attr) value)
    (refuse-slot! ::schema-invalid attr value))
  value)

(declare encode-transaction-data-in)

(def ^:private encoded-operation-key ::encoded-operation)

(defn- encoded-operation?
  [operation]
  (true? (get (meta operation) encoded-operation-key)))

(defn- mark-encoded
  [operation]
  (vary-meta operation assoc encoded-operation-key true))

(defn already-encoded
  "`operations` marked as already encoded: physical storage operations (the
   vector migration) this codec passes through unchanged."
  {:malli/schema [:=> [:cat :seon.store/transaction-data] :seon.store/transaction-data]}
  [operations]
  (mapv mark-encoded operations))

(def ^:private storage-readers
  {'seon.schema.datahike/keyword
   (fn [[namespace-name local-name]]
     (keyword namespace-name local-name))
   'seon.schema.datahike/symbol
   (fn [[namespace-name local-name]]
     (symbol namespace-name local-name))})

(defn- storage-compare
  "Canonical storage order: the values' structure (`seon.print/compare-values`);
  only leaves that order cannot tell apart (same class, not Comparable, such as
  a tagged literal) fall back to their printed forms, so distinct members are
  never merged."
  {:malli/schema [:=> [:cat :seon.schema/value :seon.schema/value] :int]}
  [left right]
  (let [order (print/compare-values left right)]
    (if (zero? order)
      (compare (id/printed left) (id/printed right))
      order)))

(defn- reader-round-trips?
  [value]
  (try
    (= value (edn/read-string (id/printed value)))
    ;; clojure.edn declares "not readable EDN" as a RuntimeException (EdnReader.java:130, :174-177); every other failure propagates.
    (catch RuntimeException _ false)))

(defn- readable-identifier?
  "Whether keyword or symbol `identifier` reads back as itself. Identifiers
  are few and recur in every value, so the reader decides each once, under
  `seon.cache`."
  {:malli/schema [:=> [:cat [:or :keyword :symbol]] :boolean]}
  [identifier]
  (seon.cache/lookup ::readable-identifier identifier (constantly 64)
                     #(reader-round-trips? identifier) some?))

(def ^:private natural-order-classes
  "Classes whose `compare` is total and consistent with `=`, and is exactly
  the order `seon.print/compare-values` gives two members of one class."
  #{clojure.lang.Keyword clojure.lang.Symbol String Long java.util.UUID})

(defn- natural-order?
  "Whether `members` are all of one natural-order class, so `compare` sorts
  them as `storage-compare` would."
  {:malli/schema [:=> [:cat [:or :nil [:sequential :seon.schema/value]]] :boolean]}
  [members]
  (let [member-class (class (first members))]
    (and (contains? natural-order-classes member-class)
         (every? #(identical? member-class (class %)) members))))

(defn- storage-data
  "Replace inexpressible identifiers and impose canonical collection order."
  [value]
  (walk/postwalk
   (fn [element]
     (cond
       (and (keyword? element) (not (readable-identifier? element)))
       (tagged-literal 'seon.schema.datahike/keyword
                       [(namespace element) (name element)])

       (and (symbol? element) (not (readable-identifier? element)))
       (tagged-literal 'seon.schema.datahike/symbol
                       [(namespace element) (name element)])

       (map? element)
       (into (if (natural-order? (keys element)) (sorted-map) (sorted-map-by storage-compare)) element)

       (set? element)
       (into (if (natural-order? (seq element)) (sorted-set) (sorted-set-by storage-compare)) element)

       :else element))
   value))

(defn- storage-string
  [value]
  (id/printed (storage-data value)))

(defn- encode-call-output-in
  "Encode transaction data returned by one Datahike transaction function.
   `vectors-fn` is the writer's vector transaction function, so vector writes
   in the output batch into one call after it (nil when none is supplied).

   ## Errors

   A function that answers an error value (`:seon.error/base`) refuses the
   transaction by throwing that value as `ex-data`; Datahike aborts it and the
   writer answers it (datahike/writer.cljc:139-171)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection
                       [:or :nil :seon.instrument/callable] :seon.instrument/callable
                       [:* :seon.schema/value]]
                  :seon.store/transaction-data]}
  [db projection vectors-fn f & args]
  (let [output (apply f db args)]
    ;; A transaction function that answers an error value (its own refusal,
    ;; or an armed contract's) refuses the transaction with THAT error: it is
    ;; never encoded as transaction data, where a second refusal would hide it.
    (when (and (map? output)
               (contains? (:seon.schema.projection/forms projection) :seon.error/base)
               ((schema/projection-validator projection :seon.error/base) output))
      (throw (ex-info (or (:seon.error/message output) "A transaction function refused.") output)))
    ;; The in-transaction value's installed attributes decide each slot's
    ;; storage: an earlier operation of this transaction may have retyped one.
    (encode-transaction-data-in
     (assoc projection ::installed-schema (dbi/-schema db)) vectors-fn
     output)))

;;; ---------------------------------------------------------------------------
;;; Vectors: a `[:vector T]` attribute's elements live in bridge cells
;;; ---------------------------------------------------------------------------
;;; Each element is one identity-less cell owned by its parent through
;;; `:seon.db.vector/cells`, holding `:seon.db.vector/position` and exactly one
;;; value of the attribute itself; the parent names every present vector in
;;; `:seon.db.vector/present` (vector-storage design §2). A new entity's
;;; vectors become inline cells here. Every other vector write becomes a
;;; request the writer's transaction function applies on the in-transaction
;;; value, all of one transaction in ONE call at its end: each `:db.fn/call`
;;; costs O(effective datoms so far) in the pinned fork (design §5.2).

(defn- identity-attr-in?
  "Whether `attr` is declared an upsert identity in `projection`."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword] :boolean]}
  [projection attr]
  (schema/projection-cache-value
   projection [::identity attr]
   #(boolean
     (when-let [compiled (mr/schema (:seon.schema.projection/registry projection) attr)]
       (:seon.db/identity (::properties (compiled-storage compiled)))))))

(defn- ref-attr-in?
  "Whether `attr` stores entity references in `projection`: its pulled values
   are entities, never data maps."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword] :boolean]}
  [projection attr]
  (schema/projection-cache-value
   projection [::ref attr]
   #(boolean
     (when-let [compiled (mr/schema (:seon.schema.projection/registry projection) attr)]
       (= :db.type/ref (::value-type (compiled-storage compiled)))))))

(defn- new-entity-id?
  "True for an entity id Datahike allocates in this transaction."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [id]
  (boolean (or (nil? id) (string? id) (and (number? id) (neg? id)))))

(defn- vector-handle
  "How the writer finds `entity` after its map ran, or nil when the map always
   makes a new entity (no id or a tempid, and no identity attribute)."
  {:malli/schema [:=> [:cat :seon.schema/projection :map] :seon.schema/value]}
  [projection entity]
  (let [id (:db/id entity)]
    (if-not (new-entity-id? id)
      id
      (some (fn [[attr value]] (when (identity-attr-in? projection attr) [attr value])) entity))))

(defn- two-keyword-members?
  "Whether Datahike would read `value` of `attr` as one lookup ref, not two
   members: two keywords under a declared cardinality-many value attribute."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword :seon.schema/value] :boolean]}
  [projection attr value]
  (boolean
   (and (coll? value) (not (map? value)) (= 2 (count value)) (keyword? (first value))
        (get (:seon.schema.projection/forms projection) attr)
        (let [installed (malli->datahike-attr-in projection attr)]
          (and (= :db.cardinality/many (:db/cardinality installed))
               (not= :db.type/ref (:db/valueType installed)))))))

(declare encode-entity)

(defn- encode-value
  "`value` with every nested entity map encoded: [value operations requests]."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value]
                  [:tuple :seon.schema/value :seon.store/transaction-data :seon.schema.datahike/vector-requests]]}
  [projection value]
  (cond
    (map? value) (encode-entity projection value)
    (and (coll? value) (some map? value))
    (let [parts (map #(encode-value projection %) value)]
      [(if (set? value) (into #{} (map first) parts) (mapv first parts))
       (into [] (mapcat second) parts)
       (into [] (mapcat #(nth % 2)) parts)])
    :else [value [] []]))

(defn- encode-entity
  "`entity` encoded in one fold: [entity operations requests]. EDN slots become
   their storage string; a new entity's vectors become inline cells and
   presence, a possibly existing entity's become requests; members Datahike
   would misread as a lookup ref become explicit operations. A map carrying
   `:seon.db.vector/position` is a cell: its vector attribute holds its one
   element."
  {:malli/schema [:=> [:cat :seon.schema/projection :map]
                  [:tuple :map :seon.store/transaction-data :seon.schema.datahike/vector-requests]]}
  [projection entity]
  (let [cell? (contains? entity :seon.db.vector/position)
        handle (when-not cell? (vector-handle projection entity))
        [encoded operations requests]
        (reduce-kv
         (fn [[encoded operations requests] attr value]
           (cond
             (edn-encoded-slot? projection attr)
             [(assoc encoded attr (storage-string (validate-logical-slot-in! projection attr value))) operations requests]

             (or cell? (not (vector-attribute-in? projection attr)))
             (let [[value more wanted] (encode-value projection value)]
               [(assoc encoded attr value) (into operations more) (into requests wanted)])

             (not (sequential? value))
             (refuse-slot! ::vector-value-not-sequential attr value)

             handle
             [encoded operations (conj requests [handle attr :assert (vec value)])]

             :else
             (let [[cells more wanted] (encode-value projection (into [] (map-indexed #(hash-map :seon.db.vector/position %1 attr [%2])) value))]
               [(-> encoded
                    (update :seon.db.vector/present (fnil conj #{}) attr)
                    (update :seon.db.vector/cells (fnil into []) cells))
                (into operations more) (into requests wanted)])))
         [(empty entity) [] []] entity)
        members (into {} (filter (fn [[attr value]] (two-keyword-members? projection attr value))) encoded)]
    (if (empty? members)
      [encoded operations requests]
      (let [entity-id (or (:db/id encoded) (str "seon.schema.datahike/" (id/id)))]
        [(assoc (apply dissoc encoded (keys members)) :db/id entity-id)
         (into operations (for [[attr values] members member values] (mark-encoded [:db/add entity-id attr member])))
         requests]))))

(defn- vector-request
  "A raw operation on a vector attribute as its request, or nil for any other
   operation: `:db/add` appends, `[:db/retract e a v]` removes every
   occurrence, `[:db/retract e a]` and retractAttribute make it absent."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.store/transaction-operation]
                  [:or :nil :seon.schema.datahike/vector-request]]}
  [projection operation]
  (when (and (vector? operation)
             (#{:db/add :db/retract :db.fn/retractAttribute :db/retractAttribute} (first operation))
             (keyword? (nth operation 2 nil))
             (vector-attribute-in? projection (nth operation 2)))
    (let [[f entity attr value] operation]
      (when (new-entity-id? entity)
        (refuse-slot! ::vector-tempid-operation attr operation))
      (cond (= :db/add f) [entity attr :add value]
            (and (= :db/retract f) (= 4 (count operation))) [entity attr :retract value]
            :else [entity attr :clear nil]))))

;;; ---------------------------------------------------------------------------
;;; Vectors read back: logical selectors lowered, physical pulls decoded
;;; ---------------------------------------------------------------------------
;;; Both sides read Datahike's own parse of the logical selector
;;; (`datalog.parser.pull/parse-pull`): `{:wildcard? w :attrs {key options}}`,
;;; where a reverse `:x/_a` is the key of options naming `:x/a`.

(def ^:private wildcard-spec (pull-parser/parse-pull '[*]))
(def ^:private id-spec (pull-parser/parse-pull [:db/id]))

(defn- cell-attribute
  "`:vector` when spec entry `[k options]` reads a vector attribute, `:reverse`
   when it reads one in reverse, `:plain` otherwise."
  {:malli/schema [:=> [:cat :seon.schema/projection [:tuple :seon.schema/value :map]] [:enum :vector :reverse :plain]]}
  [projection [k {:keys [attr]}]]
  (cond (not (and (keyword? attr) (vector-attribute-in? projection attr))) :plain
        (= k attr) :vector
        :else :reverse))

(defn- reads-cells?
  {:malli/schema [:=> [:cat :seon.schema/projection :map] :boolean]}
  [projection {:keys [wildcard? attrs]}]
  (boolean (or wildcard?
               (some (fn [[_ {:keys [subpattern]} :as entry]]
                       (or (not= :plain (cell-attribute projection entry))
                           (and subpattern (reads-cells? projection subpattern))))
                     attrs))))

(defn- physical-selector
  "The selector reading `spec` from storage: every vector through one shared
   `{:seon.db.vector/cells [position a ...]}` plus presence, its options applied
   later to the logical vector, so a limit never cuts another vector's cells."
  {:malli/schema [:=> [:cat :seon.schema/projection :map] :seon.schema/pull-selector]}
  [projection {:keys [wildcard? attrs]}]
  (let [element (fn [k {:keys [subpattern] :as options}]
                  (let [base (if-some [opts (not-empty (select-keys options [:as :limit :default]))]
                               (into [k] cat opts)
                               k)]
                    (cond subpattern {base (physical-selector projection subpattern)}
                          (contains? options :recursion) {base (or (:recursion options) '...)}
                          :else base)))
        {plain :plain vectors :vector reversed :reverse} (group-by #(cell-attribute projection %) attrs)]
    (cond-> (if wildcard? ['*] [])
      (some #(contains? (val %) :default) (concat vectors reversed)) (conj :db/id)
      true (into (map (fn [[k options]] (element k options))) plain)
      true (into (map (fn [[k {:keys [subpattern]}]]
                        {k [{:seon.db.vector/_cells (physical-selector projection (or subpattern id-spec))}]}))
                 reversed)
      (seq vectors) (conj :seon.db.vector/present
                          {:seon.db.vector/cells
                           (into [:seon.db.vector/position]
                                 (map (fn [[k options]] (element k (dissoc options :as :limit :default))))
                                 vectors)}))))

(defn vector-pull
  "`[physical-selector logical-spec]` for pulling logical `selector` under
   `projection`, or nil when the pull reads no cells (none declared, or no
   vector attribute or wildcard at any depth). [[decode-vectors]] takes the spec.

   ## Costs

   A function of the projection's content and the selector, so derived once
   per pair in `seon.cache`: the render walk's root selector (289 K
   characters) took 18 ms unarmed, and a cold agent turn lowered it 63 times,
   1.2 s (default, 2026-09-27)."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/pull-selector]
                  [:or :nil [:tuple :seon.schema/pull-selector :map]]]}
  [projection selector]
  (when (contains? (:seon.schema.projection/forms projection) :seon.db.vector/cells)
    ;; nil is never retained, so the answer is held as the one-element vector
    (first
     (seon.cache/lookup ::vector-pull
                        [(schema/projection-identity projection)
                         (true? (::cells-installed? projection)) selector]
                        #(* 48 (count (tree-seq coll? seq %)))
                        #(let [spec (pull-parser/parse-pull selector)]
                           [(when (reads-cells? projection spec)
                              [(physical-selector projection spec) spec])])))))

(declare decode-vectors)

(defn- decode-pulled
  {:malli/schema [:=> [:cat :seon.schema/projection [:or :nil :map] :seon.schema/value] :seon.schema/value]}
  [projection spec value]
  (cond (nil? spec) value
        (map? value) (decode-vectors projection spec value)
        (sequential? value) (mapv #(decode-pulled projection spec %) value)
        :else value))

(defn decode-vectors
  "The logical entity for `pulled`, read by the physical selector of
   [[vector-pull]]: each present vector's cells in position order, bridge keys
   removed, the spec's options applied to the logical vector."
  {:malli/schema [:=> [:cat :seon.schema/projection :map [:or :nil :map]] [:or :nil :map]]}
  [projection {:keys [wildcard? attrs]} pulled]
  (when pulled
    (let [;; A reference without a sub-selector is an entity too: an expanded
          ;; component holds its own cells. Any other value is data, never walked.
          nested (fn [attr spec] (or spec (when (ref-attr-in? projection attr) wildcard-spec)))
          grouped (group-by (fn [cell] (some #(when (vector-attribute-in? projection %) %) (keys cell)))
                            (:seon.db.vector/cells pulled))
          present (set (:seon.db.vector/present pulled))
          logical (fn [attr spec]
                    (when (contains? present attr)
                      (mapv #(decode-pulled projection (nested attr spec) (first (get % attr)))
                            (sort-by :seon.db.vector/position (get grouped attr)))))
          base (cond-> (apply dissoc pulled :seon.db.vector/cells :seon.db.vector/present
                              (map (fn [[k options]] (get options :as k)) attrs))
                 (not (or wildcard? (contains? attrs :db/id))) (dissoc :db/id))
          base (if wildcard?
                 (reduce (fn [entity attr] (if-some [value (logical attr nil)] (assoc entity attr value) entity))
                         (into {} (map (fn [[k v]] [k (cond->> v (coll? v) (decode-pulled projection (nested k nil)))])) base)
                         present)
                 base)]
      (reduce
       (fn [entity [k {:keys [attr subpattern limit] :as options} :as entry]]
         (let [value (case (cell-attribute projection entry)
                       :vector (let [v (logical attr subpattern)] (cond->> v (and v limit) (into [] (take limit))))
                       :reverse (not-empty (into [] (comp (keep :seon.db.vector/_cells) (distinct)
                                                          (map #(decode-pulled projection (or subpattern id-spec) %)))
                                                 (get pulled k)))
                       :plain (decode-pulled projection (nested attr subpattern) (get pulled (get options :as k))))
               value (if (and (nil? value) (contains? options :default)) (:default options) value)]
           (if (some? value) (assoc entity (get options :as k) value) entity)))
       base attrs))))

;;; ---------------------------------------------------------------------------
;;; Vectors in Datalog: a pattern over a vector attribute means membership
;;; ---------------------------------------------------------------------------
;;; `[?e a ?v]` binds each element of `?e`'s vector `a`, exactly as Datalog
;;; means a cardinality-many attribute: results are relations, order and
;;; multiplicity are a pull concern (vector-storage design §3.3).

(defn logical-attribute?
  "Datalog predicate: `attribute` is one an agent reads, not bridge storage."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [attribute]
  (not (and (keyword? attribute) (= "seon.db.vector" (namespace attribute)))))

(defn- query-variable?
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [x]
  (boolean (and (symbol? x) (.startsWith (name x) "?"))))

(defn- query-source?
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [x]
  (boolean (and (symbol? x) (.startsWith (name x) "$"))))

(defn- clause-variables
  {:malli/schema [:=> [:cat :seon.schema/value] [:vector :symbol]]}
  [form]
  (into [] (comp (filter query-variable?) (distinct)) (tree-seq coll? seq form)))

(defn- vector-position?
  "Whether attribute position `a` reads vector attributes: true when every
   attribute it can name is one, false when none is, nil when unknown (a
   variable no input binds, or inputs of both kinds)."
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :symbol [:set :seon.schema/value]] :seon.schema/value]
                  [:or :nil :boolean]]}
  [projection bound a]
  (let [kinds (into #{} (map #(boolean (and (keyword? %) (vector-attribute-in? projection %))))
                    (if (keyword? a) [a] (get bound a)))]
    (when (= 1 (count kinds)) (first kinds))))

(declare lower-clauses)

(defn- lower-pattern
  "One data pattern: through cells for a vector attribute, unchanged for a
   non-vector one, a union of both when the attribute is unknown."
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :symbol [:set :seon.schema/value]] [:vector :seon.schema/value]]
                  [:vector :seon.schema/value]]}
  [projection bound pattern]
  (let [[head & more] pattern
        [source [e a v & tail]] (if (query-source? head) [head more] [nil pattern])
        with-source #(if source (into [source] %) %)
        cell (gensym "?vector-cell")
        v (if (nil? v) '_ v)]
    (case (when (or (keyword? a) (query-variable? a)) (vector-position? projection bound a))
      true [(with-source [e :seon.db.vector/cells cell]) (with-source (into [cell a v] tail))]
      false [pattern]
      ;; A `_` or constant non-keyword attribute is Datahike's own concern.
      nil (if-not (query-variable? a)
            [pattern]
            ;; `missing?`, not `not`: Datahike inlines a scalar input into ?e, and
            ;; a `not` with no free variable is refused ("Insufficient bindings").
            [(apply list (concat (if source [source 'or-join] ['or-join])
                                 [(clause-variables [e a v tail])
                                  (list 'and (with-source (into [e a v] tail))
                                        [(list `logical-attribute? a)]
                                        [(list 'missing? (or source '$) e :seon.db.vector/position)])
                                  (list 'and (with-source [e :seon.db.vector/cells cell])
                                        (with-source (into [cell a v] tail))
                                        [(list `logical-attribute? a)])]))]))))

(defn- lower-branch
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :symbol [:set :seon.schema/value]]
                       [:set :symbol] :seon.schema/value]
                  :seon.schema/value]}
  [projection bound outer clause]
  (let [lowered (lower-clauses projection bound outer [clause])]
    (if (= 1 (count lowered)) (first lowered) (list* 'and lowered))))

(defn- lower-clause
  "One where clause lowered; `not`/`or` become `not-join`/`or-join` over their
   original variables, so a cell variable stays local to its branch. A `not`
   joins only the variables `outer` binds (its sibling clauses, enclosing
   clauses and inputs), as Datahike's `not` does: a variable local to its body
   stays local, never a join on an unbound variable. `missing?`
   of an attribute that may be a vector asks `seon.db/logical-missing?`, which
   reads presence (an empty vector is not missing) and any other attribute alike."
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :symbol [:set :seon.schema/value]]
                       [:set :symbol] :seon.schema/value]
                  [:vector :seon.schema/value]]}
  [projection bound outer clause]
  (cond
    (and (vector? clause) (seq? (first clause)))
    (let [[[f & arguments]] clause]
      (cond
        (and (= 'missing? f) (not (false? (vector-position? projection bound (last arguments)))))
        [(assoc clause 0 (list* 'seon.db/logical-missing? (if (= 3 (count arguments)) arguments (cons '$ arguments))))]

        (and (contains? #{'get-else 'get-some} f) (some #(true? (vector-position? projection bound %)) arguments))
        (throw (ex-info (str f " reads one value; a vector attribute holds a sequence. "
                             "Pull the vector, or join its members with a data pattern.")
                        {::vector-scalar-function f ::clause clause}))

        :else [clause]))

    (vector? clause) (lower-pattern projection bound clause)

    (seq? clause)
    (let [[head & body] clause
          [source operator body] (if (query-source? head) [head (first body) (rest body)] [nil head body])
          with-source #(if source (cons source %) %)]
      (case operator
        not [(with-source (list* 'not-join (filterv outer (clause-variables body))
                                 (lower-clauses projection bound outer body)))]
        not-join [(with-source (list* 'not-join (first body) (lower-clauses projection bound (set (first body)) (rest body))))]
        or [(with-source (list* 'or-join (clause-variables body) (map #(lower-branch projection bound outer %) body)))]
        or-join [(with-source (list* 'or-join (first body) (map #(lower-branch projection bound (set (first body)) %) (rest body))))]
        and [(list* 'and (lower-clauses projection bound outer body))]
        [clause]))

    :else [clause]))

(defn- lower-clauses
  "Each clause lowered with the variables bound outside it: `outer` plus every
   variable its sibling clauses name."
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :symbol [:set :seon.schema/value]]
                       [:set :symbol] [:sequential :seon.schema/value]]
                  [:vector :seon.schema/value]]}
  [projection bound outer clauses]
  (let [clauses (vec clauses)]
    (into []
          (comp (map-indexed
                 (fn [index clause]
                   (lower-clause projection bound
                                 (into outer (clause-variables (concat (subvec clauses 0 index)
                                                                       (subvec clauses (inc index)))))
                                 clause)))
                cat)
          clauses)))

(defn lower-where
  "`clauses` (a query's `:where`, or a rule body with no inputs) with every
   data pattern over a vector attribute read as membership through cells.
   `bound` maps each input variable to the values its input binds. Introduced
   cell variables are numbered in order, avoiding the caller's own, so equal
   queries lower to equal forms and share Datahike's query cache. Answers
   `clauses` itself when nothing names a vector. `get-else`/`get-some` over a
   vector attribute throws `::vector-scalar-function`."
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :symbol [:set :seon.schema/value]] [:sequential :seon.schema/value]]
                  [:sequential :seon.schema/value]]}
  [projection bound clauses]
  (let [lowered (lower-clauses projection bound (set (keys bound)) clauses)]
    (if (= lowered clauses)
      clauses
      (let [used (into (set (keys bound)) (filter symbol?) (tree-seq coll? seq clauses))
            introduced (into [] (comp (filter #(and (symbol? %) (not (used %)) (.startsWith (name %) "?vector-cell")))
                                      (distinct))
                             (tree-seq coll? seq lowered))]
        (walk/postwalk-replace
         (zipmap introduced (remove used (map #(symbol (str "?vector-cell-" %)) (range))))
         lowered)))))

(defn- encode-transaction-data-in
  "Encode one transaction's operations: EDN slots, vector cells, and vector
   requests batched into ONE `vectors-fn` call at the end (each `:db.fn/call`
   costs O(effective datoms so far) in the pinned fork, design §5.2), whose
   output, like any transaction function's, runs through this codec."
  {:malli/schema [:=> [:cat :seon.schema/projection [:or :nil :seon.instrument/callable]
                       :seon.store/transaction-data]
                  :seon.store/transaction-data]}
  [projection vectors-fn transaction-data]
  (let [;; A tempid whose identity another operation asserts
        ;; (`[:db/add "t" :seon.fn/sym x]`, a reconciling writer's form) may
        ;; name an existing entity: its vectors are requests, never new cells.
        named (into {}
                    (keep (fn [operation]
                            (when (and (vector? operation) (= :db/add (first operation))
                                       (string? (second operation))
                                       (keyword? (nth operation 2 nil))
                                       (identity-attr-in? projection (nth operation 2)))
                              [(second operation) [(nth operation 2) (nth operation 3)]])))
                    transaction-data)
        [operations requests]
        (reduce
         (fn [[operations requests] operation]
           (if (and (map? operation) (not (encoded-operation? operation)))
             (let [[attr value] (get named (:db/id operation))
                   [entity more wanted] (encode-entity projection (cond-> operation attr (assoc attr value)))]
               [(into (conj operations (mark-encoded entity)) more) (into requests wanted)])
             (if-let [request (when-not (encoded-operation? operation) (vector-request projection operation))]
               [operations (conj requests request)]
               [(conj operations
                      (cond
                        (encoded-operation? operation) operation

                        (and (vector? operation) (= :db/add (first operation))
                             (edn-encoded-slot? projection (nth operation 2 nil)))
                        (mark-encoded (update operation 3 #(storage-string (validate-logical-slot-in! projection (nth operation 2) %))))

                        ;; Datahike splices a transaction function's output into
                        ;; this transaction after this encoder ran: route it
                        ;; through the codec. The process-local marker keeps an
                        ;; owner that already encoded from being encoded twice.
                        (and (vector? operation) (= :db.fn/call (first operation)))
                        (mark-encoded (into [:db.fn/call #'encode-call-output-in projection vectors-fn (second operation)]
                                            (nnext operation)))

                        :else operation))
                requests])))
         [[] []] transaction-data)]
    (cond-> operations
      (seq requests)
      (conj (if vectors-fn
              (mark-encoded [:db.fn/call #'encode-call-output-in projection vectors-fn vectors-fn projection requests])
              (throw (ex-info "Vector writes need the writer's vector transaction function."
                              {::vector-requests requests})))))))

(defn encode-transaction-in
  "Encode heterogeneous union slots and vectors against exactly one projection.

   `vectors-fn` is the writer's transaction function applying vector requests
   (`seon.db/vectors-tx`); without it a vector write on a possibly existing
   entity refuses."
  {:malli/schema [:function
                  [:=> [:cat :seon.schema/projection :seon.store/transaction]
                   :seon.store/transaction]
                  [:=> [:cat :seon.schema/projection [:or :nil :seon.instrument/callable]
                        :seon.store/transaction]
                   :seon.store/transaction]]}
  ([projection transaction] (encode-transaction-in projection nil transaction))
  ([projection vectors-fn transaction]
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
                 (encode-transaction-data-in projection vectors-fn transaction-data)))
       (encode-transaction-data-in projection vectors-fn transaction)))))

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
   #(if (edn-encoded-attr-in? projection attr)
      (storage-string (validate-logical-slot-in! projection attr value))
      value)))

(defn- decode-edn
  "Decode stored EDN once. The tuple retains successful nil; failures throw
  with this attribute and are never retained by the cache. The writer made
  the text canonical; a read never re-prints it to judge it again."
  {:malli/schema [:=> [:cat :qualified-keyword :string] [:tuple :seon.schema/value]]}
  [attr value]
  [(try
     (edn/read-string {:readers storage-readers} value)
     (catch Throwable _
       (refuse-slot! ::malformed-edn attr value)))])

(defn attribute-decoder-in
  "Select an attribute's storage decoder once for a held projection.
  Writers retain it in their attribute plan; each value still goes through
  the EDN decode and its existing content-keyed cache."
  {:malli/schema [:=> [:cat :map :keyword] :seon.instrument/callable]}
  [projection attr]
  (if-not (edn-encoded-attr-in? projection attr)
    identity
    (fn [value]
      (when-not (string? value)
        (refuse-slot! ::storage-not-string attr value))
      (first
       (seon.cache/lookup
        ::decoded-edn [decode-edn storage-readers attr value]
        ;; Stored text, decoded nodes and the key; no second value walk to weigh.
        (constantly (* 10 (count value)))
        #(decode-edn attr value))))))

(defn decode-attribute-value-in
  "Decode one attribute value against exactly one projection.

   Refuses storage that is not EDN. A committed value is returned as
   written: admission judges writes, and a read never re-applies the current
   write schema to it (a dependency plan stored before its pattern bound
   reads whole). A caller choosing among candidate attributes validates the
   decoded value itself (`seon.db/decode-query-field`)."
  {:malli/schema [:=> [:cat :map :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value]}
  [projection attr value]
  ((attribute-decoder-in projection attr) value))

(defn decode-attribute-value
  "Decode and validate one value read from an EDN-backed attribute.

   Resolves the declaration population ONCE; a caller decoding more than one
   attribute resolves it itself and calls [[decode-attribute-value-in]]."
  {:malli/schema [:=> [:cat :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :seon.schema/value]}
  [attr value]
  (decode-attribute-value-in (schema/declaration-projection) attr value))
