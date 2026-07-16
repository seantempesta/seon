(ns seon.db.datahike.schema
  "Malli -> Datahike schema bridge.

   Derives a Datahike schema (a vector of ident entity maps) from a Malli
   `:map` schema, targeting datahike's `d/transact`-ready shape:

     [{:db/ident :foo/bar
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one}
      ...]

   Annotate entries with `:seon.db/*` properties for persistence:
     (schema/register! ::id [:uuid {:seon.db/identity true}])
     ;; or inline on the entry:
     [:map [:foo/id {:seon.db/identity true} :uuid]]

   Nested `:map` component refs are **not supported** in phase 1 -- throws a
   clear error. Cross-namespace refs use `:seon.db/ref` (stored as UUID,
   Decision 6 of the datahike migration PRD).

   Public API:
   - `malli-type->datahike-type` -- leaf type mapping
   - `malli-map->datahike-schema` -- full schema vector derivation"
  (:require [malli.core :as m]
            [malli.registry :as mr]))

;;; ---------------------------------------------------------------------------
;;; Type Mapping
;;; ---------------------------------------------------------------------------

(def ^:private leaf-type-map
  "Maps Malli leaf types to Datahike value types.
   Includes both keyword types (:string) and predicate types (string?)."
  {:string   :db.type/string
   'string?  :db.type/string
   :int      :db.type/long
   'int?     :db.type/long
   :long     :db.type/long
   :double   :db.type/double
   'double?  :db.type/double
   :float    :db.type/float
   'float?   :db.type/float
   :boolean  :db.type/boolean
   'boolean? :db.type/boolean
   :keyword  :db.type/keyword
   'keyword? :db.type/keyword
   ;; qualified variants narrow Malli validation only — one datahike
   ;; keyword/symbol type. MIRRORS the CLJS bridge
   ;; (seon.db.internal/malli-type->datahike-type) — keep in lockstep.
   :qualified-keyword :db.type/keyword
   :symbol   :db.type/symbol
   'symbol?  :db.type/symbol
   :qualified-symbol :db.type/symbol
   :uuid     :db.type/uuid
   'uuid?    :db.type/uuid
   :inst     :db.type/instant
   'inst?    :db.type/instant})

(defn malli-type->datahike-type
  "Maps a Malli type to a Datahike `:db.type/*` keyword.
   Returns nil for unmappable types.

   Note: single-arg positional (not map-in). Pure derivation utility."
  {:malli/schema [:=> [:cat :any] [:maybe :keyword]]}
  [malli-type]
  (get leaf-type-map malli-type))

;;; ---------------------------------------------------------------------------
;;; Internal Helpers
;;; ---------------------------------------------------------------------------

(defn- infer-enum-type
  "Infer Datahike type from enum values. All values must map to the same
   `:db.type/*`. Returns nil for empty enums; throws ex-info for mixed types.
   Note: `java.lang.Integer` and `java.lang.Long` both map to `:db.type/long`."
  [attr-key values]
  (if (empty? values)
    nil
    (let [value-type (fn [v]
                       (condp instance? v
                         clojure.lang.Keyword :db.type/keyword
                         java.lang.String     :db.type/string
                         java.lang.Long       :db.type/long
                         java.lang.Integer    :db.type/long
                         java.lang.Double     :db.type/double
                         java.lang.Float      :db.type/float
                         java.lang.Boolean    :db.type/boolean
                         nil))
          types (mapv value-type values)
          unique-types (distinct (remove nil? types))]
      (cond
        (some nil? types)
        (throw (ex-info (str "Enum for attr " attr-key
                             " contains value(s) with unsupported type for datahike: "
                             (pr-str (keep-indexed (fn [i t] (when (nil? t) (nth values i))) types)))
                        {:attr attr-key :values values}))

        (> (count unique-types) 1)
        (throw (ex-info (str "Mixed-type enum not allowed for attr " attr-key
                             ": values " (pr-str values) " map to types "
                             (pr-str unique-types))
                        {:attr attr-key :values values :types unique-types}))

        :else (first unique-types)))))

(defn- resolve-child-schema
  "Unwrap :malli.core/val wrapper to get the actual child schema."
  [entry-schema]
  (if (= :malli.core/val (m/type entry-schema))
    (first (m/children entry-schema))
    entry-schema))

(defn- seon-db-props->db-props
  "Translate `:seon.db/*` Malli properties to `:db/unique`. Datahike does not
   need `:db/valueType` overrides from seon props -- value types are derived
   from the leaf schema directly."
  [props]
  (cond-> {}
    (:seon.db/identity props) (assoc :db/unique :db.unique/identity)
    (:seon.db/unique props)   (assoc :db/unique :db.unique/value)))

(declare ^:private schema->attr-partial)

(defn- schema->attr-partial
  "Convert an unwrapped Malli schema to a partial attr-map (without :db/ident).
   Returns a map containing :db/valueType, :db/cardinality, and optional
   :db/unique. Throws ex-info for unsupported shapes.

   `entry-props` carries `:seon.db/*` properties attached to the map entry
   (as opposed to the type itself). Both sources are merged with entry props
   taking precedence.

   `attr-key` is passed through for clearer error messages."
  [attr-key entry-props child-schema]
  (let [schema-type (m/type child-schema)
        combined-seon-props (merge (seon-db-props->db-props (m/properties child-schema))
                                   (seon-db-props->db-props entry-props))]
    (case schema-type
      (:string :int :long :double :float :boolean :keyword :symbol :uuid :inst
               string? int? double? float? boolean? keyword? symbol? uuid? inst?)
      (merge {:db/valueType (malli-type->datahike-type schema-type)
              :db/cardinality :db.cardinality/one}
             combined-seon-props)

      :maybe
      ;; :maybe shouldn't appear in persisted schemas (project convention
      ;; prefers {:optional true}), but defensively unwrap so we don't
      ;; silently drop the attribute.
      (let [inner (first (m/children child-schema))]
        (schema->attr-partial attr-key entry-props inner))

      (:vector :set)
      (let [inner (first (m/children child-schema))
            inner-type (m/type inner)
            float-inner? (contains? #{:float :double float? double?} inner-type)
            ;; A vector-of-floats is an EMBEDDING — a single homogeneous
            ;; `:db.type/tuple` value (cardinality/one), NEVER 1536 separate
            ;; cardinality-many scalar datoms. So the tuple shape is keyed off
            ;; the float inner-type alone. `:db.secondary/only true` is an
            ;; ORTHOGONAL property: when present the full value lives ONLY in
            ;; the secondary (Proximum) index and the primary holds a content
            ;; hash; when ABSENT the full vector persists in the primary AEVT.
            ;; `:seon/embedding` SETS `:db.secondary/only true`: the durable
            ;; home of the vector is Proximum's own konserve store, and datahike
            ;; RESTORES the HNSW from it on conn open (the P2-A.5 connect-if-exists
            ;; fork fix) — it is NOT rebuilt from AEVT (no vectors there, only a
            ;; hash). (Locked use: :seon/embedding, the embeddings-fn-retrieval
            ;; PRD.)
            secondary-only? (boolean (or (:db.secondary/only entry-props)
                                         (:db.secondary/only (m/properties child-schema))))]
        (when (and secondary-only? (not float-inner?))
          (throw (ex-info
                  (str ":db.secondary/only attr " attr-key
                       " must be a vector of :float/:double; got inner type "
                       inner-type)
                  {:attr attr-key :inner-type inner-type})))
        (when (and (not float-inner?)
                   (contains? #{:vector :set :map} inner-type))
          (throw (ex-info
                  (str "Nested collection not supported for attr " attr-key
                       " (inner type " inner-type "). Datahike cardinality-many "
                       "stores scalar values only.")
                  {:attr attr-key :inner-type inner-type})))
        (if float-inner?
          (cond-> {:db/valueType   :db.type/tuple
                   :db/cardinality :db.cardinality/one}
            secondary-only? (assoc :db.secondary/only true))
          (let [inner-attr (schema->attr-partial attr-key nil inner)]
            (merge inner-attr
                   {:db/cardinality :db.cardinality/many}
                   combined-seon-props))))

      :enum
      (let [values (m/children child-schema)
            _ (when (empty? values)
                (throw (ex-info (str "Empty enum for attr " attr-key
                                     " cannot be persisted: no values to infer "
                                     ":db/valueType from.")
                                {:attr attr-key})))
            dt (infer-enum-type attr-key values)]
        (merge {:db/valueType dt
                :db/cardinality :db.cardinality/one}
               combined-seon-props))

      :seon.db/ref
      ;; :seon.db/ref means intra-DB :db.type/ref. Values are tempids,
      ;; pos-int eids, or [unique-attr value] lookup-refs; datahike resolves
      ;; them inside the transaction. Cross-DB handles are :uuid attrs with
      ;; :seon.db/ref-to metadata and are NEVER labeled :seon.db/ref.
      ;; See docs/prds/datahike-migration/ref-model-research.md.
      (merge {:db/valueType :db.type/ref
              :db/cardinality :db.cardinality/one}
             combined-seon-props)

      :or
      ;; :or must declare :seon.db/value-type in properties to be persisted.
      (let [vt (:seon.db/value-type (m/properties child-schema))]
        (if vt
          (merge {:db/valueType vt
                  :db/cardinality :db.cardinality/one}
                 combined-seon-props)
          (throw (ex-info
                  (str ":or schema for attr " attr-key
                       " must declare :seon.db/value-type in properties "
                       "to be persisted. Example: "
                       "[:or {:seon.db/value-type :db.type/string} ...]")
                  {:attr attr-key :schema child-schema}))))

      :malli.core/schema
      ;; A registered-keyword reference (e.g. :seon.db/ref). Pre-deref, we
      ;; can see the registry keyword via (m/form). The keyword identifies
      ;; the bridge case for refs without needing the underlying schema's
      ;; type to be `:seon.db/ref` itself — important because the new
      ;; :seon.db/ref registration uses an `:or` form rather than a
      ;; `m/-simple-schema` of type `:seon.db/ref`.
      (let [form (m/form child-schema)]
        (if (= :seon.db/ref form)
          (merge {:db/valueType :db.type/ref
                  :db/cardinality :db.cardinality/one}
                 combined-seon-props)
          (schema->attr-partial attr-key entry-props (m/deref child-schema))))

      :map
      (throw (ex-info
              (str "Nested map as component ref not supported in datahike phase 1 "
                   "(attr " attr-key "). Denormalize or use a cross-DB UUID ref "
                   "(Decision 6 of datahike-migration PRD).")
              {:attr attr-key :schema child-schema}))

      ;; Fallthrough: unsupported type
      (throw (ex-info
              (str "Unsupported Malli type " schema-type " for attr " attr-key
                   " in datahike schema bridge.")
              {:attr attr-key :schema-type schema-type :schema child-schema})))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn malli-form->datahike-attribute
  "Derive one Datahike declaration from a canonical schema-form population.

   `forms` is the complete database-owned `{qualified-keyword malli-form}` map.
   Compiling against that explicit registry keeps one database's authored
   references isolated from every other database and from process-global Malli
   state."
  {:malli/schema
   [:=> [:catn [::forms :map]
               [::attribute :qualified-keyword]
               [::form :any]]
    [:map-of :keyword :any]]}
  [forms attribute form]
  (let [registry (mr/composite-registry
                  (m/default-schemas)
                  (mr/fast-registry forms))
        compiled (m/schema form {:registry registry})]
    (assoc (schema->attr-partial attribute nil compiled)
           :db/ident attribute)))

(defn malli-map->datahike-schema
  "Derive a datahike schema vector from a Malli `:map` schema.

   Each map entry produces one ident entity map:

     {:db/ident       key
      :db/valueType   :db.type/*
      :db/cardinality :db.cardinality/one | :db.cardinality/many
      :db/unique      :db.unique/identity | :db.unique/value   ;; optional}

   Returns a vector ready to pass directly to `d/transact`.

   Rules:
   - `:vector` / `:set` become `:db.cardinality/many`; nested collections
     (`[:vector [:vector X]]`, `[:vector [:map ...]]`) are rejected.
   - `:maybe` unwraps to its inner type (project convention prefers
     `{:optional true}` instead; this is defence-in-depth).
   - `:enum` infers the type from homogeneous values; throws on mixed types.
   - `:seon.db/identity true` sets `:db/unique :db.unique/identity`.
   - `:seon.db/unique true` sets `:db/unique :db.unique/value`.
   - `:seon.db/ref` becomes `:db.type/ref` (intra-DB ref; values are
     tempids, pos-int eids, or [unique-attr value] lookup-refs). Cross-DB
     handles use plain `:uuid` with `:seon.db/ref-to` metadata and are
     never labeled as `:seon.db/ref`.
   - `:or` requires `:seon.db/value-type` in properties to be persistable.
   - Nested `:map` as component ref is not supported in phase 1.

   Note: single-arg positional (not map-in). Pure derivation utility."
  {:malli/schema [:=> [:cat :any] [:vector [:map-of :keyword :any]]]}
  [malli-schema]
  (let [schema (try
                 (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))
                 (catch Exception e
                   (throw (ex-info
                           (str "Cannot resolve Malli schema in malli-map->datahike-schema. "
                                "If using a schema reference (e.g. :seon.foo/bar), ensure "
                                "schema/register! is called BEFORE entity schema defs in the file. "
                                "Original error: " (ex-message e))
                           {:schema malli-schema}
                           e))))]
    (when-not (= :map (m/type schema))
      (throw (ex-info (str "malli-map->datahike-schema expects a :map schema, got " (m/type schema))
                      {:schema-type (m/type schema) :schema malli-schema})))
    (mapv
     (fn [[k entry-schema]]
       (let [entry-props (m/properties entry-schema)
             child (resolve-child-schema entry-schema)
             partial-attr (schema->attr-partial k entry-props child)]
         (assoc partial-attr :db/ident k)))
     (m/entries schema))))
