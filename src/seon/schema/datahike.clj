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
                     })))
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
                      "A stored attribute needs a storable Datahike value type, and this Malli form has none. Register a concrete storable shape.")]
        (throw (ex-info message {::attr attribute ::form form
                                 reason (if (= reason ::nilable-attribute) ::form true)
                                 }))))
    (when (and secondary? (not (#{:db.type/float :db.type/double} value-type)))
      (throw (ex-info "A secondary-only attribute must contain floats."
                      {::attr attribute ::invalid-secondary-attribute attribute
                       })))
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

(defn- declared-storable?
  "Whether `attribute`'s declaration maps to a Datahike attribute.
  False only for this owner's declared refusals, each of which names the
  attribute under `::attr` (`malli->datahike-attr-in`); any other failure
  propagates whole."
  {:malli/schema [:=> [:cat :map :qualified-keyword] :boolean]}
  [projection attribute]
  (try
    (malli->datahike-attr-in projection attribute)
    true
    (catch clojure.lang.ExceptionInfo failure
      (if (= attribute (::attr (ex-data failure)))
        false
        (throw failure)))))

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
  "Select core and storable property attributes from retained canonical roots."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
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
     (declared-storable? projection attr))))

(defn storable-properties-in
  "Namespaced properties whose own declarations are database-storable.

   A key absent from the projection refuses by name: `mr/schema` answers nil
   for it, which Malli then reported as an anonymous invalid nil schema."
  {:malli/schema [:=> [:cat :map :keyword] :map]}
  [projection schema-key]
  (let [compiled (mr/schema (:seon.schema.projection/registry projection) schema-key)]
    (when-not compiled
      (throw (ex-info (str "Missing schema declaration " schema-key
                           ": the supplied projection does not declare it.")
                      {:seon.schema/invalid-schema schema-key
                       :seon.schema/key schema-key
                       :seon.schema/missing-reference schema-key
                       :seon.schema/missing-reference-namespace (namespace schema-key)})))
    (into {}
          (filter (fn [[property v]]
                    (and (qualified-keyword? property) (some? v)
                         (storable-attribute-in? projection property))))
          (m/properties compiled))))

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

(defn vector-shaped-attr-in?
  "Whether `attr` is declared `[:vector T]` or `[:sequential T]` in `projection`.

   The declared shape alone: a vector attribute read from storage that has no
   bridge cells (an as-of value older than the cells) is still a vector in
   datom order. [[vector-attribute-in?]] answers whether it is stored in cells."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword] :boolean]}
  [projection attr]
  (schema/projection-cache-value
   projection [::vector-shaped attr]
   #(boolean
     (when-let [compiled (mr/schema (:seon.schema.projection/registry projection) attr)]
       (::vector? (compiled-storage compiled))))))

(defn vector-attribute-in?
  "Whether `attr` stores its elements in bridge cells under `projection`.

   True for a vector-shaped attribute when the projection also declares
   `:seon.db.vector/cells`. The second condition is temporal, not a staging
   flag: a value older than the cells carries a projection without them
   (`seon.db/carried-projection`) and reads its members as they were stored.
   A writer whose database already installed the bridge attributes, before its
   schema rows declare them, sets `::cells-installed?` on the projection it
   encodes by (`seon.db/prepared-write`)."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword] :boolean]}
  [projection attr]
  (and (or (contains? (:seon.schema.projection/forms projection) :seon.db.vector/cells)
           (true? (::cells-installed? projection)))
       (vector-shaped-attr-in? projection attr)))

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

(declare encode-entity-in encode-transaction-data-in)

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

(defn- canonical-print-string
  {:seon/tasks #{"befc26639ad0"}}
  [value]
  (binding [*print-length* nil
            *print-level* nil
            *print-meta* false
            *print-readably* true
            *print-dup* false
            *print-namespace-maps* true]
    (pr-str value)))

(defn- storage-compare
  {:seon/tasks #{"befc26639ad0"}}
  [left right]
  (compare (canonical-print-string left)
           (canonical-print-string right)))

(defn- reader-round-trips?
  [value]
  (try
    (= value (edn/read-string (canonical-print-string value)))
    ;; clojure.edn declares "not readable EDN" as a RuntimeException (EdnReader.java:130, :174-177); every other failure propagates.
    (catch RuntimeException _ false)))

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
  "Encode transaction data returned by one Datahike transaction function.
   `vectors-fn` is the writer's vector transaction function, so vector writes
   in the output batch into one call after it (nil when none is supplied)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.schema/projection
                       [:or :nil :seon.instrument/callable] :seon.instrument/callable
                       [:* :seon.schema/value]]
                  :seon.store/transaction-data]}
  [db projection vectors-fn f & args]
  (encode-transaction-data-in
   projection vectors-fn
   (apply f db args)))

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

(defn- vector-request
  "One vector write for the writer's transaction function."
  {:malli/schema [:=> [:cat :seon.schema/value :qualified-keyword [:enum :assert :add :retract :clear]
                       :seon.schema/value]
                  [:vector :seon.schema/value]]}
  [handle attr operation argument]
  [::vector-request handle attr operation argument])

(defn- vector-request?
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [operation]
  (and (vector? operation) (= ::vector-request (first operation))))

(declare encode-vector-map)

(defn- encode-vector-value
  "`value` with every nested entity map encoded: [value operations]."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value]
                  [:tuple :seon.schema/value :seon.store/transaction-data]]}
  [projection value]
  (cond
    (map? value) (encode-vector-map projection value)
    (and (coll? value) (some map? value))
    (let [pairs (map #(encode-vector-value projection %) value)]
      [(if (set? value) (into #{} (map first) pairs) (mapv first pairs))
       (into [] (mapcat second) pairs)])
    :else [value []]))

(defn- inline-cells
  "A new entity's vector `elements` of `attr` as cells: [cells operations]."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword [:sequential :seon.schema/value]]
                  [:tuple [:vector :map] :seon.store/transaction-data]]}
  [projection attr elements]
  (reduce (fn [[cells operations] [position element]]
            (let [[value more] (encode-vector-value projection element)]
              [(conj cells {:seon.db.vector/position position attr [value]})
               (into operations more)]))
          [[] []] (map-indexed vector elements)))

(defn- encode-vector-map
  "`entity` with its vectors encoded: [entity operations]. A new entity's
   vectors become inline cells and presence; a possibly existing entity's
   become requests. A map carrying `:seon.db.vector/position` is a cell: its
   vector attribute holds its one element."
  {:malli/schema [:=> [:cat :seon.schema/projection :map] [:tuple :map :seon.store/transaction-data]]}
  [projection entity]
  (let [cell? (contains? entity :seon.db.vector/position)
        handle (when-not cell? (vector-handle projection entity))]
    (reduce-kv
     (fn [[encoded operations] attr value]
       (cond
         (or cell? (not (vector-attribute-in? projection attr)))
         (let [[value more] (encode-vector-value projection value)]
           [(assoc encoded attr value) (into operations more)])

         (not (sequential? value))
         (refuse-slot! ::vector-value-not-sequential attr value)

         handle
         [encoded (conj operations (vector-request handle attr :assert (vec value)))]

         :else
         (let [[cells more] (inline-cells projection attr value)]
           [(-> encoded
                (update :seon.db.vector/present (fnil conj #{}) attr)
                (update :seon.db.vector/cells (fnil into []) cells))
            (into operations more)])))
     [{} []] entity)))

(defn- raw-vector-operation
  "A raw operation on a vector attribute as its request, or nil for any other
   operation: `:db/add` appends, `[:db/retract e a v]` removes every
   occurrence, `[:db/retract e a]` and retractAttribute make it absent."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.store/transaction-operation]
                  [:or :nil [:vector :seon.schema/value]]]}
  [projection operation]
  (when (and (vector? operation)
             (#{:db/add :db/retract :db.fn/retractAttribute :db/retractAttribute} (first operation))
             (keyword? (nth operation 2 nil))
             (vector-attribute-in? projection (nth operation 2)))
    (let [[f entity attr value] operation]
      (when (new-entity-id? entity)
        (refuse-slot! ::vector-tempid-operation attr operation))
      (case f
        :db/add (vector-request entity attr :add value)
        :db/retract (if (= 4 (count operation))
                      (vector-request entity attr :retract value)
                      (vector-request entity attr :clear nil))
        (vector-request entity attr :clear nil)))))

(defn- batched-vector-requests
  "`operations` with every vector request moved into ONE call of `vectors-fn`
   at the end, routed through this codec so its output is encoded too."
  {:malli/schema [:=> [:cat :seon.schema/projection [:or :nil :seon.instrument/callable]
                       :seon.store/transaction-data]
                  :seon.store/transaction-data]}
  [projection vectors-fn operations]
  (let [requests (into [] (comp (filter vector-request?) (map #(subvec % 1))) operations)]
    (cond-> (into [] (remove vector-request?) operations)
      (seq requests)
      (conj (if vectors-fn
              (mark-encoded [:db.fn/call #'encode-call-output-in projection vectors-fn
                             vectors-fn projection requests])
              (throw (ex-info "Vector writes need the writer's vector transaction function."
                              {::vector-requests requests})))))))

;;; ---------------------------------------------------------------------------
;;; Vectors read back: logical selectors lowered, physical pulls decoded
;;; ---------------------------------------------------------------------------

(defn- selector-spec
  "One pull selector element as {:attr :key :sub :opts}, or :wildcard. Accepts
   keywords, `[a :as k :limit n :default d]`, the legacy `(limit a n)` and
   `(default a d)`, and `{spec sub}`."
  {:malli/schema [:=> [:cat :seon.schema/value]
                  [:or [:= :wildcard]
                   [:map [:attr :keyword] [:key :seon.schema/value]
                    [:sub {:optional true} :seon.schema/value]
                    [:opts {:optional true} [:map-of :keyword :seon.schema/value]]]]]}
  [element]
  (cond
    (contains? #{'* "*"} element) :wildcard
    (keyword? element) {:attr element :key element}
    (and (seq? element) (contains? #{'limit 'default "limit" "default"} (first element)))
    (let [[f attr argument] element]
      {:attr attr :key attr :opts {(keyword (name f)) argument}})
    (and (sequential? element) (keyword? (first element)))
    (let [[attr & options] element
          opts (apply hash-map options)]
      {:attr attr :key (get opts :as attr) :opts opts})
    (map? element)
    (let [[k sub] (first element)]
      (assoc (selector-spec k) :sub sub))
    :else (throw (ex-info "Unsupported pull selector element." {::selector-element element}))))

(defn- reverse-vector-attr
  "The vector attribute `attr` names in reverse (`:x/_a`), or nil."
  {:malli/schema [:=> [:cat :seon.schema/projection :keyword] [:or :nil :keyword]]}
  [projection attr]
  (when (.startsWith (name attr) "_")
    (let [forward (keyword (namespace attr) (subs (name attr) 1))]
      (when (vector-attribute-in? projection forward) forward))))

(defn- sub-selector?
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [sub]
  (boolean (and (sequential? sub) (not (seq? sub)))))

(defn lower-selector
  "The physical pull selector for a logical one. Every vector attribute reads
   through one shared `{:seon.db.vector/cells [position a ...]}` plus
   `:seon.db.vector/present`; its options (`:as :limit :default`) apply to the
   logical vector after [[decode-vectors]], so a limit never cuts another
   vector's cells. A reverse `:x/_a` reads the cells' owning parents."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/pull-selector] :seon.schema/pull-selector]}
  [projection selector]
  (let [specs (map selector-spec selector)
        wildcard? (some #{:wildcard} specs)
        specs (remove #{:wildcard} specs)
        lower-sub #(if (sub-selector? %) (lower-selector projection %) %)
        vector-spec? #(vector-attribute-in? projection (:attr %))
        reverse? #(reverse-vector-attr projection (:attr %))
        plain (for [{:keys [attr sub opts] :as spec} specs
                    :when (not (or (vector-spec? spec) (reverse? spec)))]
                (let [base (if (seq opts) (into [attr] (mapcat identity) opts) attr)]
                  (if (some? sub) {base (lower-sub sub)} base)))
        cell (into [:seon.db.vector/position]
                   (for [{:keys [attr sub] :as spec} specs :when (vector-spec? spec)]
                     (if (some? sub) {attr (lower-sub sub)} attr)))
        reversed (for [{:keys [attr sub] :as spec} specs :when (reverse? spec)]
                  {attr [{:seon.db.vector/_cells (lower-sub (or sub [:db/id]))}]})]
    (vec (concat (when wildcard? ['*])
                 (when (some #(contains? (:opts %) :default) specs) [:db/id])
                 plain
                 reversed
                 (when (some vector-spec? specs)
                   [:seon.db.vector/present {:seon.db.vector/cells cell}])))))

(defn vector-selector?
  "Whether pulling `selector` under `projection` needs [[lower-selector]] and
   [[decode-vectors]]: the projection stores vectors in cells and the selector
   names a vector attribute, a legacy form or a wildcard at any depth."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/pull-selector] :boolean]}
  [projection selector]
  (and (contains? (:seon.schema.projection/forms projection) :seon.db.vector/cells)
       (boolean (or (not= selector (lower-selector projection selector))
                    (some #(contains? #{'* "*"} %) (tree-seq coll? seq selector))))))

(declare decode-vectors)

(defn- decode-pulled
  "One pulled value under sub-selector `sub`: entity maps decode recursively."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/value :seon.schema/value] :seon.schema/value]}
  [projection sub value]
  (cond
    (not (sub-selector? sub)) value
    (map? value) (decode-vectors projection sub value)
    (sequential? value) (mapv #(decode-pulled projection sub %) value)
    :else value))

(defn decode-vectors
  "The logical entity for `pulled`, the physical pull of
   `(lower-selector projection selector)`: each present vector's cells grouped
   by the one vector attribute each carries, sorted by position, elements
   decoded; bridge keys removed; options applied to the logical vector."
  {:malli/schema [:=> [:cat :seon.schema/projection :seon.schema/pull-selector [:or :nil :map]]
                  [:or :nil :map]]}
  [projection selector pulled]
  (when pulled
    (let [specs (map selector-spec selector)
          wildcard? (some #{:wildcard} specs)
          specs (remove #{:wildcard} specs)
          grouped (group-by (fn [cell] (some #(when (vector-attribute-in? projection %) %) (keys cell)))
                            (:seon.db.vector/cells pulled))
          present (set (:seon.db.vector/present pulled))
          logical (fn [attr sub]
                    (when (contains? present attr)
                      (->> (get grouped attr)
                           (sort-by :seon.db.vector/position)
                           (mapv #(decode-pulled projection (or sub (when wildcard? '[*]))
                                                 (first (get % attr)))))))
          base (apply dissoc pulled :seon.db.vector/cells :seon.db.vector/present (map :key specs))
          base (if (or wildcard? (some #(= :db/id (:attr %)) specs)) base (dissoc base :db/id))
          base (if wildcard?
                 (reduce (fn [entity attr]
                           (if-some [value (logical attr nil)] (assoc entity attr value) entity))
                         (into {} (map (fn [[k v]] [k (decode-pulled projection '[*] v)])) base)
                         present)
                 base)]
      (reduce
       (fn [entity {:keys [attr sub opts] out-key :key}]
         (let [forward (reverse-vector-attr projection attr)
               value (cond
                       (vector-attribute-in? projection attr)
                       (let [v (logical attr sub)]
                         (cond->> v (and v (:limit opts)) (into [] (take (:limit opts)))))
                       forward
                       (let [owners (into [] (comp (keep :seon.db.vector/_cells) (distinct)) (get pulled attr))]
                         (when (seq owners) (mapv #(decode-pulled projection (or sub [:db/id]) %) owners)))
                       :else (decode-pulled projection sub (get pulled out-key)))
               value (if (and (nil? value) (contains? opts :default)) (:default opts) value)]
           (if (some? value) (assoc entity out-key value) entity)))
       base specs))))

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

(declare lower-clauses)

(defn- lower-pattern
  "One data pattern: through cells for a vector attribute (constant, or an
   input bound only to vector attributes), unchanged for a non-vector one, a
   union of both for a free or mixed attribute variable."
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :symbol [:set :seon.schema/value]] [:vector :seon.schema/value]]
                  [:vector :seon.schema/value]]}
  [projection bound pattern]
  (let [[head & more] pattern
        [source [e a v & tail]] (if (query-source? head) [head more] [nil pattern])
        with-source #(if source (into [source] %) %)
        cell (gensym "?vector-cell")
        values (get bound a)
        v (if (nil? v) '_ v)]
    (cond
      (or (and (keyword? a) (vector-attribute-in? projection a))
          (and (query-variable? a) (seq values)
               (every? #(and (keyword? %) (vector-attribute-in? projection %)) values)))
      [(with-source [e :seon.db.vector/cells cell]) (with-source (into [cell a v] tail))]

      (or (not (query-variable? a))
          (and (seq values) (not-any? #(and (keyword? %) (vector-attribute-in? projection %)) values)))
      [pattern]

      :else
      ;; `missing?`, not `not`: Datahike inlines a scalar input into ?e, and
      ;; a `not` with no free variable is refused ("Insufficient bindings").
      [(apply list (concat (if source [source 'or-join] ['or-join])
                           [(clause-variables [e a v tail])
                            (list 'and (with-source (into [e a v] tail))
                                  [(list `logical-attribute? a)]
                                  [(list 'missing? (or source '$) e :seon.db.vector/position)])
                            (list 'and (with-source [e :seon.db.vector/cells cell])
                                  (with-source (into [cell a v] tail))
                                  [(list `logical-attribute? a)])]))])))

(defn- lower-branch
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :symbol [:set :seon.schema/value]] :seon.schema/value]
                  :seon.schema/value]}
  [projection bound clause]
  (let [lowered (lower-clauses projection bound [clause])]
    (if (= 1 (count lowered)) (first lowered) (list* 'and lowered))))

(defn- lower-clause
  "One where clause lowered; `not`/`or` become `not-join`/`or-join` over their
   original variables, so a cell variable stays local to its branch."
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :symbol [:set :seon.schema/value]] :seon.schema/value]
                  [:vector :seon.schema/value]]}
  [projection bound clause]
  (cond
    (and (vector? clause) (seq? (first clause)))
    (let [[[f & arguments]] clause
          vector-argument? #(and (keyword? %) (vector-attribute-in? projection %))]
      (cond
        (and (= 'missing? f) (vector-argument? (last arguments)))
        (let [[source e a] (if (= 3 (count arguments)) arguments (cons '$ arguments))]
          [(list 'not [source e :seon.db.vector/present a])])

        (and (contains? #{'get-else 'get-some} f) (some vector-argument? arguments))
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
        not [(with-source (list* 'not-join (clause-variables body) (lower-clauses projection bound body)))]
        not-join [(with-source (list* 'not-join (first body) (lower-clauses projection bound (rest body))))]
        or [(with-source (list* 'or-join (clause-variables body) (map #(lower-branch projection bound %) body)))]
        or-join [(with-source (list* 'or-join (first body) (map #(lower-branch projection bound %) (rest body))))]
        and [(list* 'and (lower-clauses projection bound body))]
        [clause]))

    :else [clause]))

(defn- lower-clauses
  {:malli/schema [:=> [:cat :seon.schema/projection [:map-of :symbol [:set :seon.schema/value]] [:sequential :seon.schema/value]]
                  [:vector :seon.schema/value]]}
  [projection bound clauses]
  (into [] (mapcat #(lower-clause projection bound %)) clauses))

(defn- query-map
  "A vector query as its map form."
  {:malli/schema [:=> [:cat [:or :map [:vector :seon.schema/value]]] :map]}
  [query]
  (if (map? query)
    query
    (first (reduce (fn [[out k] x] (if (keyword? x) [(assoc out x []) x] [(update out k conj x) k]))
                   [{} nil] query))))

(defn lower-query
  "The physical Datalog request for a logical one over vector attributes:
   `{:query q :args args :pulls {find-index logical-selector}}`, where every
   data pattern over a vector attribute is membership through cells, every
   `missing?` of one asks presence, rule bodies in the `%` input are lowered
   alike, and pull selectors (literal or input-bound) are lowered before
   execution; `:pulls` names the find elements [[decode-vectors]] must decode.
   `get-else`/`get-some` over a vector attribute throws
   `::vector-scalar-function`. Cell variables are numbered in order, so equal
   queries lower to equal forms and share Datahike's query cache."
  {:malli/schema [:=> [:cat :seon.schema/projection [:or :map [:vector :seon.schema/value]] [:vector :seon.schema/value]]
                  [:map [:query :map] [:args [:vector :seon.schema/value]]
                   [:pulls [:map-of :int :seon.schema/value]]]]}
  [projection query args]
  (let [{find-spec :find in :in where :where :as logical} (query-map query)
        in (vec (or in '[$]))
        position-of #(.indexOf ^java.util.List in %)
        bound (into {}
                    (keep (fn [[input argument]]
                            (cond
                              (query-variable? input) [input #{argument}]
                              (and (vector? input) (= '... (second input)) (query-variable? (first input)))
                              [(first input) (set argument)])))
                    (map vector in args))
        rules (position-of '%)
        args (cond-> (vec args)
               (not (neg? rules))
               (update rules (fn [rule-set]
                               (mapv (fn [[head & body]] (into [head] (lower-clauses projection bound body))) rule-set))))
        pull? #(and (seq? %) (= 'pull (first %)))
        elements (let [[f] find-spec] (if (and (vector? f) (= 1 (count find-spec))) (vec (remove #{'...} f)) (vec (remove #{'.} find-spec))))
        pulls (into {} (keep-indexed (fn [i element]
                                       (when (pull? element)
                                         (let [selector (last element)]
                                           [i (if (query-variable? selector) (get args (position-of selector)) selector)]))))
                    elements)
        find-spec (walk/prewalk #(if (and (pull? %) (not (query-variable? (last %))))
                              (concat (butlast %) [(lower-selector projection (last %))])
                              %)
                           find-spec)
        args (reduce (fn [args element]
                       (if (and (pull? element) (query-variable? (last element)))
                         (update args (position-of (last element)) #(lower-selector projection %))
                         args))
                     args elements)
        lowered (assoc logical :find (vec find-spec) :in in :where (lower-clauses projection bound where))
        cells (into [] (comp (filter #(and (symbol? %) (.startsWith (name %) "?vector-cell"))) (distinct))
                    (tree-seq coll? seq [lowered (when-not (neg? rules) (get args rules))]))
        numbered (zipmap cells (map #(symbol (str "?vector-cell-" %)) (range)))]
    {:query (walk/postwalk #(get numbered % %) lowered)
     :args (cond-> args (not (neg? rules)) (update rules #(walk/postwalk (fn [x] (get numbered x x)) %)))
     :pulls (into {} (filter (fn [[_ selector]] (vector-selector? projection selector))) pulls)}))

(defn vector-query?
  "Whether `query` needs [[lower-query]] under `projection`: cells are declared
   and a clause names a vector attribute, an attribute variable, `missing?`,
   `get-else`/`get-some`, a rule input or a pull."
  {:malli/schema [:=> [:cat :seon.schema/projection [:or :map [:vector :seon.schema/value]]] :boolean]}
  [projection query]
  (and (contains? (:seon.schema.projection/forms projection) :seon.db.vector/cells)
       (let [{find-spec :find in :in where :where} (query-map query)]
         (boolean
          (or (some #{(symbol "%")} in)
              (some #(and (seq? %) (= 'pull (first %))) (tree-seq coll? seq find-spec))
              (some (fn [x] (or (and (keyword? x) (vector-attribute-in? projection x))
                                (contains? #{'missing? 'get-else 'get-some} x)))
                    (tree-seq coll? seq where))
              (some (fn [clause]
                      (some #(and (vector? %) (not (seq? (first %)))
                                  (let [[head & more] %
                                        [_ a] (if (query-source? head) more %)]
                                    (query-variable? a)))
                            (tree-seq coll? seq clause)))
                    where))))))

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
  "Encode one transaction's operations: EDN slots, vector cells and requests
   (batched into one `vectors-fn` call), and transaction-function output."
  {:malli/schema [:=> [:cat :seon.schema/projection [:or :nil :seon.instrument/callable]
                       :seon.store/transaction-data]
                  :seon.store/transaction-data]}
  [projection vectors-fn transaction-data]
  (let [;; A tempid whose identity another operation asserts
        ;; (`[:db/add "t" :seon.fn/sym x]`, a reconciling writer's form) may
        ;; name an existing entity: its map carries that identity, so its
        ;; vectors are written as requests, never as a new entity's cells.
        named (into {}
                    (keep (fn [operation]
                            (when (and (vector? operation) (= :db/add (first operation))
                                       (string? (second operation))
                                       (keyword? (nth operation 2 nil))
                                       (identity-attr-in? projection (nth operation 2)))
                              [(second operation) [(nth operation 2) (nth operation 3)]])))
                    transaction-data)]
  (batched-vector-requests
   projection vectors-fn
   (into [] (mapcat
    (fn [operation]
      (cond
        (encoded-operation? operation)
        [operation]

        (map? operation)
        (let [[attr value] (get named (:db/id operation))
              [entity requests] (encode-vector-map projection (cond-> operation attr (assoc attr value)))]
          (into (explicit-value-members projection (encode-entity-in projection entity))
                requests))

        (raw-vector-operation projection operation)
        [(raw-vector-operation projection operation)]

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
                 projection vectors-fn (second operation)]
                (nnext operation)))]

        :else [operation])))
    transaction-data))))

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
