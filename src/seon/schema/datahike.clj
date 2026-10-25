(ns seon.schema.datahike
  "Datahike attribute declarations derived from registered Malli schemas: the registry is
  the one shape authority, the Datahike schema its derivation. From session D1's
  proto.schema.datahike (d1/src/proto/schema/datahike.clj).

  Adapted from Seon's `seon.schema.datahike` (1,193 lines; `attribute-declaration`,
  `compiled-storage`, `compiled-attribute-selection`, `database-attributes-core-in`,
  `malli->datahike-schema-in`, and the EDN slot codec). Kept: the type table, cardinality,
  uniqueness, index, refs, components and no-history from the attribute schema's own
  `:seon.db/*` properties, entity maps that declare their members (`:seon.db/attributes`),
  and plain data for every value with no one Datahike type. Cut: Seon's vector cells and Datalog lowering, the
  projection (a registry generation), render-pair properties, secondary indexes, tuples, and
  the automatic EDN codec for unions (see notes/archive/option3-D1-schema-datahike.md).

  Pure derivation over a Malli registry: no connection, no transaction. The store's owner
  transacts what `declarations` answers.

  ## Conventions (Seon's, unchanged)

  - An attribute is a registered qualified keyword whose schema is its stored value.
  - `[:and {:seon.db/identity true} :string]` is an upsert key; `:seon.db/unique` a value key;
    `:seon.db/index`, `:seon.db/component`, `:seon.db/no-history?` as named;
    `{:seon.db/identity true :seon.db/content-id true}` an upsert key whose value names its
    entity's id in every branch (Datahike's `:db/contentId`, docs/storage.md#ids).
  - `:seon.db/ref` is a reference (entity id, tempid, lookup ref or nested map); `[:set X]`
    is cardinality many.
  - A map with `{:seon.db/attributes true}` declares its members as attributes.
  - A vector, sequential, tuple, map, map-of, `:any`, or an `:or` whose arms differ in type
    stores its value whole as plain data (`:db.type/data`, our Datahike's: the store's
    serializer writes it as data, no text between, and a vector keeps its order and
    duplicates; docs/storage.md#plain-values)."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn- error error?]] [malli.core :as m] [malli.registry :as mr]))

(def type->datahike
  "Malli scalar type to Datahike value type (Seon's `malli-type->datahike-type`, with the
  integer predicates our schemas already use)."
  {:string :db.type/string :re :db.type/string :int :db.type/long :double :db.type/double :float :db.type/float
   :keyword :db.type/keyword :qualified-keyword :db.type/keyword :boolean :db.type/boolean
   :uuid :db.type/uuid :symbol :db.type/symbol :qualified-symbol :db.type/symbol
   'inst? :db.type/instant :inst :db.type/instant
   'pos-int? :db.type/long 'nat-int? :db.type/long 'int? :db.type/long 'bytes? :db.type/bytes})

(defn- literal-type
  "The Datahike value type of a Malli literal (`[:= x]`), or nil (Seon's `literal->datahike-value-type`)."
  {:malli/schema [:=> [:cat :any] [:maybe :keyword]]}
  [x]
  (cond (string? x) :db.type/string (boolean? x) :db.type/boolean (keyword? x) :db.type/keyword
        (symbol? x) :db.type/symbol (uuid? x) :db.type/uuid (inst? x) :db.type/instant
        (int? x) :db.type/long (double? x) :db.type/double))

(def ^:private storage-properties
  "An attribute schema carrying one of these is a declared attribute even where no entity map lists it."
  #{:seon.db/identity :seon.db/unique :seon.db/index :seon.db/component :seon.db/no-history? :seon.db/content-id})

;; ---- what one schema stores -------------------------------------------------------
;; Seon folds the whole schema with `m/walk` (children first, cached per compiled schema).
;; Here it descends only the one path that decides the storage: an attribute's schema is a
;; few nodes deep, and a data slot's value schema is never walked.

(def ^:private data-types
  "The Malli types stored whole as plain data (`:db.type/data`)."
  #{:vector :sequential :tuple :map :map-of :any})

(defn- refused
  {:malli/schema [:=> [:cat :qualified-keyword :any] :map]}
  [reason form] {:invalid [reason form]})

(defn- storage
  "What `node` stores: {:type :card :props}, or {:invalid [reason form]}. `active` is
  the set of registry names being dereferenced (a cycle refuses)."
  {:malli/schema [:=> [:cat :seon.host/schema [:set :any]] :map]}
  [node active]
  (let [t (m/type node) props (m/properties node)]
    (cond
      (m/-ref-schema? node)
      (let [r (m/-ref node)]
        (cond
          (= :seon.db/ref r) {:type :db.type/ref :card :one :props props}
          (and r (contains? active r)) (refused ::cyclic (m/form node))
          :else
          ;; identity belongs to the attribute being declared: an attribute that reuses an
          ;; identity's value shape by name (:seon.task/id as a need) is not an upsert key
          (let [target (storage (m/deref node) (cond-> active r (conj r)))]
            (assoc target :props (merge (dissoc (:props target) :seon.db/identity :seon.db/unique :seon.db/content-id) props)))))

      (= :and t) (assoc (storage (first (m/children node)) active) :props props)

      (= :set t)
      (let [child (storage (first (m/children node)) active)]
        (cond (:invalid child) child
              (or (= :many (:card child)) (= :db.type/data (:type child))) (refused ::nested-collection (m/form node))
              :else (assoc child :card :many :props props)))

      ;; a value with no one Datahike type is stored whole as plain data: an ordered collection
      ;; (order and duplicates kept), a map, a tuple, anything; and a union whose arms differ
      (data-types t) {:type :db.type/data :card :one :props props}
      (= :or t)
      (let [arms (map #(storage % active) (m/children node))]
        (cond (some :invalid arms) (first (filter :invalid arms))
              (and (= :one (:card (first arms))) (apply = (map (juxt :type :card) arms))) (assoc (first arms) :props props)
              :else {:type :db.type/data :card :one :props props}))
      (= :maybe t) (refused ::nilable (m/form node))

      (= := t)
      (if-let [type (literal-type (first (m/children node)))]
        {:type type :card :one :props props}
        (refused ::literal (m/form node)))

      (= :enum t)
      (if (every? keyword? (m/children node))
        {:type :db.type/keyword :card :one :props props}
        (refused ::enum (m/form node)))

      :else
      (if-let [type (type->datahike t)]
        {:type type :card :one :props props}
        (refused ::value-type-unavailable (m/form node))))))

(def ^:private reasons
  {::absent "The attribute has no registered schema."
   ::cyclic "A stored attribute's schema refers to itself."
   ::nested-collection "A stored [:set T] holds scalars or refs, one datom a member; a set of collections or maps is one value: register it as a [:vector T]."
   ::nilable "Stored attributes cannot use :maybe. Omit an absent key or mark its entity-map entry {:optional true}."
   ::literal "Only scalar Malli literals are storable."
   ::enum "Only keyword enums are storable."
   ::value-type-unavailable "This Malli form has no Datahike value type. Register a scalar, a collection or a map."})

(defn- storable?
  {:malli/schema [:=> [:cat :seon.host/registry :qualified-keyword] :boolean]}
  [registry attribute]
  (boolean (when-let [root (mr/schema registry attribute)]
             (let [{:keys [invalid type]} (storage root #{attribute})]
               (and (nil? invalid) (some? type))))))

;; ---- one attribute ----------------------------------------------------------------

(defn declaration
  "The Datahike attribute map for `attribute`, or the `:seon.schema/unstorable` error that
  says why it has none."
  {:malli/schema [:=> [:cat :seon.host/registry :qualified-keyword] [:or :seon.schema/declaration :seon.schema/unstorable]]}
  [registry attribute]
  (let [root (mr/schema registry attribute)
        {:keys [type card props invalid]} (if root (storage root #{attribute}) (refused ::absent attribute))]
    (if invalid
      (let [[reason form] invalid]
        (error :seon.schema/unstorable 'seon.schema.datahike/declaration (reasons reason)
               {:seon.schema/attribute attribute :seon.schema/reason reason :seon.schema/form form}))
      (cond-> {:db/ident attribute :db/valueType type
               :db/cardinality (if (= :many card) :db.cardinality/many :db.cardinality/one)}
        (:seon.db/identity props) (assoc :db/unique :db.unique/identity)
        (:seon.db/unique props) (assoc :db/unique :db.unique/value)
        (:seon.db/index props) (assoc :db/index true)
        (:seon.db/component props) (assoc :db/isComponent true)
        (:seon.db/no-history? props) (assoc :db/noHistory true)
        (:seon.db/content-id props) (assoc :db/contentId true)))))

;; ---- which attributes -------------------------------------------------------------

(defn- entity-maps
  "The map schemas reached from `compiled` through aliases and :and arms."
  {:malli/schema [:=> [:cat :seon.host/schema [:set :any]] [:vector :seon.host/schema]]}
  [compiled active]
  (let [r (when (m/-ref-schema? compiled) (m/-ref compiled))]
    (when (and r (contains? active r))
      (throw (ex-info "Cyclic entity declaration." {:schema (m/form compiled)})))
    (let [active (cond-> active r (conj r))]
      (cond
        (m/-ref-schema? compiled) (entity-maps (m/deref compiled) active)
        (= :map (m/type compiled)) [compiled]
        (= :and (m/type compiled)) (into [] (mapcat #(entity-maps % active)) (m/children compiled))
        :else []))))

(defn- declared-members
  "The attributes an entity schema declares: its members (a required member, or an optional
  one that is storable), when the entity says `{:seon.db/attributes true}`."
  {:malli/schema [:=> [:cat :seon.host/registry :seon.host/schema] [:vector :qualified-keyword]]}
  [registry compiled]
  (let [maps (entity-maps compiled #{})]
    (if (some #(true? (:seon.db/attributes (m/properties %))) maps)
      (into []
            (comp (mapcat m/children)
                  (filter (fn [[k props _]] (and (qualified-keyword? k) (or (not (:optional props)) (storable? registry k)))))
                  (map first))
            maps)
      [])))

(defn attributes
  "The sorted attributes `registry` declares for the database: the members of every entity
  map that says `{:seon.db/attributes true}`, and every registered schema that carries a
  storage property itself. O(registered schemas)."
  {:malli/schema [:=> [:cat :seon.host/registry] [:vector :qualified-keyword]]}
  [registry]
  (let [qualified (filter (fn [[k s]] (and (qualified-keyword? k) (m/schema? s))) (mr/schemas registry))]
    (vec (sort-by str
                  (into #{}
                        (mapcat (fn [[k s]]
                                  (concat (declared-members registry s)
                                          (when (some storage-properties (keys (m/properties s))) [k]))))
                        qualified)))))

(defn declarations
  "The Datahike schema of `registry`: one declaration per attribute, sorted by name. An
  unstorable attribute is a defect in the schema (thrown with the error map as ex-data)."
  {:malli/schema [:=> [:cat :seon.host/registry] [:vector :seon.schema/declaration]]}
  [registry]
  (mapv (fn [a] (let [d (declaration registry a)]
                  (if (error? d) (throw (ex-info (:seon.error/message d) d)) d)))
        (attributes registry)))
