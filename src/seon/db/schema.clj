(ns seon.db.schema
  "Persisted entity schema registry + Malli-level structural validation.

   Modules call `register-entity-schema!` to declare Malli `:map` schemas
   that get persisted to the database. At startup,
   `validate-persisted-schemas!` checks them all for types that the
   datahike bridge in `seon.db.datahike.schema` cannot express
   (`:any`, `:some`, `:nil`, `[:maybe X]`, mixed-type enums).

   The Malli→Datahike schema bridge lives at
   `seon.db.datahike.schema/malli-map->datahike-schema` and is invoked
   from `seon.db.datahike.conn-process` at conn `:init`."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Persisted Schema Registry
;;; ---------------------------------------------------------------------------

(defonce ^:private *persisted-schemas
  (atom {}))

(defn register-entity-schema!
  "Register a Malli :map schema as a persisted entity schema.

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
;;; Internal helpers (shared by structural validation)
;;; ---------------------------------------------------------------------------

(defn- resolve-child-schema
  "Unwrap :malli.core/val wrapper to get the actual child schema."
  [entry-schema]
  (if (= :malli.core/val (m/type entry-schema))
    (first (m/children entry-schema))
    entry-schema))

(defn- resolve-to-leaf
  "Resolve a Malli schema through :malli.core/schema refs to its concrete type."
  [s]
  (if (= :malli.core/schema (m/type s))
    (recur (m/deref s))
    s))

;;; ---------------------------------------------------------------------------
;;; Startup Consistency Validation
;;; ---------------------------------------------------------------------------

(defn- check-entry-violations
  "Check a single schema entry for persistence violations.
   Returns a seq of violation maps, or nil if valid.

   Violations checked:
   - :any -- too broad for the bridge
   - :some -- non-nil but untyped
   - :nil -- cannot store nil
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
        :message "Type :nil is not allowed (the datahike bridge cannot store nil)"}]

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
  "Validate a single Malli :map schema for persistence safety.

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
