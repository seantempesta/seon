(ns seon.db.schema
  "Malli -> Datalevin schema bridge.

   Derives Datalevin attribute schemas from Malli `:map` schemas.
   Define your entity once in Malli, get the Datalevin schema for free.

   - `malli-type->datalevin-type` -- leaf type mapping
   - `malli-map->datalevin-schema` -- full map schema conversion
   - `register-entity-schema!` -- register a persisted entity schema
   - `validate-persisted-schemas!` -- startup consistency check

   Annotate entries with `:db/*` properties for database concerns:
     [:map [:foo/id {:db/unique :db.unique/identity} :uuid]]"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Persisted Schema Registry
;;; ---------------------------------------------------------------------------
;;; Modules call `register-entity-schema!` to declare schemas that will be
;;; stored in Datalevin. At startup, `validate-persisted-schemas!` checks
;;; them all for banned types (:any, :some, [:maybe X], mixed enums).
;;;
;;; Only schemas explicitly registered are validated -- test schemas and
;;; wire protocol schemas are NOT registered and thus not checked.

(defonce ^:private *persisted-schemas
  (atom {}))

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

;;; ---------------------------------------------------------------------------
;;; Entity Schema Registration
;;; ---------------------------------------------------------------------------

(defn register-entity-schema!
  "Register a Malli :map schema as a persisted Datalevin entity schema.

   Only registered schemas are validated at startup. Wire protocol schemas,
   test schemas, and other non-persisted schemas should NOT be registered.

   Arguments:
     schema-name  - descriptive name for error reporting (e.g. \"seon.runtime\")
     malli-schema - a Malli :map schema vector

   Idempotent: re-registering the same schema overwrites the previous entry."
  [schema-name malli-schema]
  (swap! *persisted-schemas assoc schema-name malli-schema)
  schema-name)

(defn persisted-schemas
  "Return a map of {name schema} for all registered persisted entity schemas."
  []
  @*persisted-schemas)

;;; ---------------------------------------------------------------------------
;;; Startup Consistency Validation
;;; ---------------------------------------------------------------------------

(defn- resolve-to-leaf
  "Resolve a Malli schema through :malli.core/schema refs to its concrete type."
  [s]
  (if (= :malli.core/schema (m/type s))
    (recur (m/deref s))
    s))

(defn- check-entry-violations
  "Check a single schema entry for Datalevin persistence violations.
   Returns a seq of violation maps, or nil if valid.

   Violations checked:
   - :any -- too broad for Datalevin
   - :some -- non-nil but untyped
   - :nil -- Datalevin cannot store nil
   - :maybe -- use {:optional true} instead
   - :mixed-enum -- enum values must be same type"
  [attr-key child-schema]
  (let [resolved (resolve-to-leaf child-schema)
        t (m/type resolved)]
    (cond
      (= t :any)
      [{:attr attr-key :violation :any
        :message "Type :any is not allowed in persisted schemas"}]

      (= t :some)
      [{:attr attr-key :violation :some
        :message "Type :some is not allowed in persisted schemas"}]

      (= t :nil)
      [{:attr attr-key :violation :nil
        :message "Type :nil is not allowed (Datalevin cannot store nil)"}]

      (= t :maybe)
      [{:attr attr-key :violation :maybe
        :message "[:maybe X] not allowed in persisted schema. Use {:optional true} X instead."}]

      (= t :enum)
      (let [values (m/children resolved)
            types (set (map type values))]
        (when (> (count types) 1)
          [{:attr attr-key :violation :mixed-enum
            :message (str "Mixed-type enum not allowed: values " (pr-str values)
                          " have types " (mapv #(.getSimpleName ^Class %) types))}]))

      ;; Recurse into collections
      (#{:vector :set} t)
      (let [inner (first (m/children resolved))]
        (check-entry-violations attr-key (resolve-to-leaf inner)))

      ;; Recurse into nested maps (component refs)
      (= t :map)
      (let [entries (m/entries resolved)]
        (seq (mapcat (fn [[k es]]
                       (let [child (resolve-child-schema es)]
                         (check-entry-violations k child)))
                     entries)))

      :else nil)))

(defn validate-persisted-schema
  "Validate a single Malli :map schema for Datalevin persistence safety.

   Returns a vector of violation maps. Empty vector means valid.
   Each violation has :attr, :violation, :message, and :schema-name."
  [schema-name malli-schema]
  (let [s (if (m/schema? malli-schema) malli-schema (m/schema malli-schema))
        entries (m/entries s)]
    (vec (mapcat (fn [[k es]]
                   (let [child (resolve-child-schema es)]
                     (map #(assoc % :schema-name schema-name)
                          (check-entry-violations k child))))
                 entries))))

(defn validate-persisted-schemas!
  "Validate all registered persisted entity schemas.

   Checks every schema registered via `register-entity-schema!` for:
   - :any, :some, :nil -- banned leaf types
   - [:maybe X] -- use {:optional true} instead
   - Mixed-type enums -- all enum values must be same type

   Returns {:valid? true :violations [] :schema-count N} on success.
   Throws ex-info with structured data if any violations are found."
  []
  (let [schemas @*persisted-schemas
        schema-count (count schemas)
        all-violations (vec (mapcat (fn [[schema-name schema]]
                                      (validate-persisted-schema schema-name schema))
                                    schemas))]
    (if (seq all-violations)
      (throw (ex-info (str "Schema consistency check failed: "
                           (count all-violations) " violation(s) in "
                           schema-count " persisted schemas.\n"
                           (str/join "\n"
                             (map (fn [{:keys [schema-name attr violation message]}]
                                    (str "  [" schema-name "] " attr " -- " violation ": " message))
                                  all-violations)))
                      {:violations all-violations
                       :schema-count schema-count}))
      (do (log/info "Schema consistency check passed"
                    {:schema-count schema-count :violations 0})
          {:valid? true
           :violations []
           :schema-count schema-count}))))
