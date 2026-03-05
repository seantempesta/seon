(ns seon.db.schema
  "Malli → Datalevin schema bridge.

   Derives Datalevin attribute schemas from Malli `:map` schemas.
   Define your entity once in Malli, get the Datalevin schema for free.

   - `malli-type->datalevin-type` — leaf type mapping
   - `malli-map->datalevin-schema` — full map schema conversion

   Annotate entries with `:db/*` properties for database concerns:
     [:map [:foo/id {:db/unique :db.unique/identity} :uuid]]"
  (:require [malli.core :as m]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Type Mapping
;;; ---------------------------------------------------------------------------

(def ^:private leaf-type-map
  "Maps Malli leaf types to Datalevin value types.
   Includes both keyword types (:string) and predicate types (inst?)."
  {:string   :db.type/string
   'string?  :db.type/string
   :int      :db.type/long
   'int?     :db.type/long
   :double   :db.type/double
   'double?  :db.type/double
   :float    :db.type/float
   'float?   :db.type/float
   :boolean  :db.type/boolean
   'boolean? :db.type/boolean
   :keyword  :db.type/keyword
   'keyword? :db.type/keyword
   :symbol   :db.type/symbol
   'symbol?  :db.type/symbol
   :uuid     :db.type/uuid
   'uuid?    :db.type/uuid
   :inst     :db.type/instant
   'inst?    :db.type/instant})

(defn malli-type->datalevin-type
  "Maps a Malli type to a Datalevin `:db.type/*` keyword.
   Returns nil for unmappable types."
  {:malli/schema [:=> [:cat :any] [:maybe :keyword]]}
  [malli-type]
  (get leaf-type-map malli-type))

;;; ---------------------------------------------------------------------------
;;; Internal Helpers
;;; ---------------------------------------------------------------------------

(defn- infer-enum-type
  "Infer Datalevin type from enum values. All values must be same type."
  [values]
  (let [types (set (map type values))]
    (when (= 1 (count types))
      (condp = (first types)
        clojure.lang.Keyword :db.type/keyword
        java.lang.String     :db.type/string
        java.lang.Long       :db.type/long
        java.lang.Double     :db.type/double
        nil))))

(defn- resolve-child-schema
  "Unwrap :malli.core/val wrapper to get the actual child schema."
  [entry-schema]
  (if (= :malli.core/val (m/type entry-schema))
    (first (m/children entry-schema))
    entry-schema))

(declare malli-map->datalevin-schema)

(defn- schema->datalevin-attr
  "Convert a single Malli entry schema to [attr-map nested-schemas]."
  [child-schema]
  (let [schema-type (m/type child-schema)]
    (case schema-type
      (:string :int :double :float :boolean :keyword :symbol :uuid :inst
       string? int? double? float? boolean? keyword? symbol? uuid? inst?)
      [(when-let [dt (malli-type->datalevin-type schema-type)]
         {:db/valueType dt}) nil]

      :maybe
      (let [inner (first (m/children child-schema))]
        (schema->datalevin-attr inner))

      (:vector :set)
      (let [inner (first (m/children child-schema))
            [attr nested] (schema->datalevin-attr inner)]
        [(when attr
           (assoc attr :db/cardinality :db.cardinality/many)) nested])

      :enum
      (let [values (m/children child-schema)
            dt (infer-enum-type values)]
        [(when dt {:db/valueType dt}) nil])

      :map
      (let [nested (malli-map->datalevin-schema child-schema)]
        [{:db/valueType :db.type/ref
          :db/isComponent true} nested])

      :seon.db/ref
      [{:db/valueType :db.type/ref} nil]

      :malli.core/schema
      (schema->datalevin-attr (m/deref child-schema))

      [nil nil])))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn malli-map->datalevin-schema
  "Derive a Datalevin schema map from a Malli `:map` schema.

   Each map entry `[key props child-schema]` produces an attribute:
   - `:db/*` keys in props pass through verbatim
   - `:maybe` unwraps, `:vector`/`:set` become cardinality-many
   - `:enum` type inferred from values
   - Nested `:map` becomes `:db.type/ref` + `:db/isComponent true`
   - Unmappable types: use `:db/valueType` from props, or warn and skip

   Returns `{attr {:db/valueType ... :db/cardinality ...}}` map."
  {:malli/schema [:=> [:cat :any] [:map-of :keyword [:map-of :keyword :any]]]}
  [malli-schema]
  (let [schema (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))
        entries (m/entries schema)]
    (reduce
     (fn [acc [k entry-schema]]
       (let [child (resolve-child-schema entry-schema)
             props (m/properties entry-schema)
             db-props (into {} (filter (fn [[pk _]] (= "db" (namespace pk)))) props)
             [derived-attr nested] (schema->datalevin-attr child)
             attr (if derived-attr
                    (merge derived-attr db-props)
                    (if (seq db-props)
                      db-props
                      (do (log/warn "Skipping unmappable attribute" k
                                    "with Malli type" (m/type child)
                                    "- add :db/valueType to properties")
                          nil)))]
         (cond-> acc
           attr (assoc k attr)
           nested (merge nested))))
     {}
     entries)))
