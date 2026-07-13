(ns seon.schema.internal
  "Malli-form mechanics and register!-time gates for `seon.schema`.

   Private engine internals, factored out of the public registry surface
   so they stay indexed + grep-able WITHOUT rendering into agent context
   (the `*.internal` convention drops them from the curated namespaces
   body — see `seon.agent.ctx.namespaces/hidden-ns-name?`).

   Everything here is pure form-shape inspection over a Malli schema form,
   plus the two `register!` gates. The registry atom lives in `seon.schema`;
   the identity check ([[identity-attr?]]) reads it through a passed-in
   `schemas` map so this namespace never requires `seon.schema` (no cycle:
   schema → schema.internal only)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.registry :as mr]))

(defn attr-form-properties
  "The Malli props map from an attr-schema form, or nil. Mirrors
   `seon.db/form-properties` (kept here to avoid a db→schema cycle)."
  [form]
  (when (vector? form)
    (some (fn [x] (when (map? x) x)) (rest form))))

(defn identity-attr?
  "True when the schema form for `attr-key` in `schemas` carries
   `{:seon.db/identity true}`. Covers the three shapes Seon uses:
     [:string  {:seon.db/identity true}]
     [:keyword {:seon.db/identity true}]
     [:and {:seon.db/identity true} :seon.db/id]"
  [schemas attr-key]
  (boolean (some-> (get schemas attr-key) attr-form-properties :seon.db/identity)))

(defn map-shape?
  "True if `v` looks like a Malli `:map` schema form."
  [v]
  (and (vector? v) (= :map (first v))))

(defn map-entries
  "Entries of a `:map` schema form — vector of `[entry-key (props?)
   entry-schema]`, with the head and optional schema-level props stripped."
  [v]
  (let [body (rest v)
        body (if (and (seq body) (map? (first body))) (rest body) body)]
    (vec body)))

(defn schema-properties
  "The `:map` schema's properties map (between head and entries), or nil."
  [v]
  (when (map-shape? v)
    (let [body (rest v)]
      (when (and (seq body) (map? (first body)))
        (first body)))))

(defn enum-members
  "Members of an `:enum` form, or [] when `form` is not an enum. Strips an
   optional leading props map (`[:enum {…} :a :b]`)."
  [form]
  (if (and (vector? form) (= :enum (first form)))
    (let [body (rest form)
          body (if (and (seq body) (map? (first body))) (rest body) body)]
      (vec body))
    []))

(defn- map-identity-entry-key
  "The first entry key of `:map` schema `v` that is itself an identity
   attr in `schemas` (`{:seon.db/identity true}`), or nil."
  [schemas v]
  (when (map-shape? v)
    (some (fn [entry]
            (when-let [k (and (vector? entry) (first entry))]
              (when (identity-attr? schemas k) k)))
          (map-entries v))))

(defn derive-entity-id-attr
  "Identity-attr entry key of `v` when `v` is a `:map` DECLARED a stored
   entity kind (`{:seon.db/entity true}` in its own props); else nil.

   Entity-kind-ness is DECLARED, never inferred: a request/response
   envelope that merely carries an id entry must NOT become a catalogued
   kind. The derived id-attr makes a declared schema self-describing for
   the renderer's discovery walk — no per-row `:seon.entity/kind` stamp."
  [schemas v]
  (when (:seon.db/entity (schema-properties v))
    (map-identity-entry-key schemas v)))

(defn map-required-attrs
  "Entry keys of `:map` form `v` whose props do NOT carry `{:optional
   true}` (excluding the `::m/default` sentinel) — the required-attrs
   index for schemas-as-queryable-data."
  [v]
  (when (map-shape? v)
    (into []
          (keep (fn [entry]
                  (when (vector? entry)
                    (let [k     (first entry)
                          props (let [p (second entry)] (when (map? p) p))]
                      (when (and (keyword? k)
                                 (not= k :malli.core/default)
                                 (not (:optional props)))
                        k)))))
          (map-entries v))))

(defn with-entity-id-attr
  "Attach `{:seon.entity/id-attr <k>}` to `v`'s props when `v` is a
   DECLARED entity kind with an identity-attr entry; preserves existing
   props (`:seon.render/ai`, etc). Pass-through otherwise."
  [schemas v]
  (if-let [id-attr (derive-entity-id-attr schemas v)]
    (let [head     (first v)
          body     (rest v)
          [props body] (if (and (seq body) (map? (first body)))
                         [(first body) (rest body)]
                         [{} body])]
      (into [head (assoc props :seon.entity/id-attr id-attr)] body))
    v))

(defn assert-compilable-schema!
  "register!-time gate: reject invalid Malli forms so an agent never
   'successfully' registers something the system can't use. Compiles `k`
   against a complete candidate containing `schemas` plus `[k v]`; failure
   throws a legible `:user-input`
   ex-info naming the key, the bad form, and common storable types.
   Requires any referenced schema to exist in that candidate (the load-order
   convention), without depending on Malli's process-global default."
  [schemas k v]
  (try
    (let [registry (mr/composite-registry
                     (m/default-schemas)
                     (mr/fast-registry (assoc schemas k v)))]
      (m/schema k {:registry registry}))
    nil
    (catch #?(:clj Exception :cljs :default) e
      (throw (ex-info
               (str "schema/register! " k ": " (pr-str v)
                    " is not a valid Malli schema (" (ex-message e) "). "
                    "Common storable attr types: :string :int :double "
                    ":float :boolean :keyword :inst :uuid :symbol "
                    ":seon.db/ref, [:enum :a :b], or a container "
                    "[:vector <type>] / [:set <type>]. (:number is NOT "
                    "a type — use :int or :double.) If the form "
                    "references another schema keyword, register that "
                    "keyword first.")
               {:seon.schema/error :seon.schema/invalid-schema
                :seon.schema/key   k
                :seon.schema/definition v
                :seon.error/kind   :user-input}
               e)))))

(defn maybe-inner
  "The child form of a top-level `[:maybe X]` schema, else nil.

   Skips an optional leading props map (`[:maybe {…} X]`). nil when `v`
   is not a top-level nilable form — the caller treats nil as 'nothing to
   reject'."
  [v]
  (when (and (vector? v) (= :maybe (first v)))
    (let [body (rest v)
          body (if (and (seq body) (map? (first body))) (rest body) body)]
      (first body))))

(defn assert-non-nilable-value-schema!
  "register!-time gate: reject a top-level nilable value schema whose inner
   is a raw Malli built-in type — e.g. `[:maybe :int]`. In seon a stored
   value is NEVER nil (absent = the key is simply omitted, never stored as
   nil), so a NAMED value schema must not be nilable. Throws a guiding
   `:user-input` ex-info that hands back the copy-pasteable fix: register
   the base type, then mark the FIELD optional at its map site.

   `schemas` is the seon registry map — a keyword NOT in it is a Malli
   built-in (`:int`/`:string`/…), which is exactly the mis-modeled-attr
   case; a `:maybe` around an already-registered DOMAIN type (`::view-id`)
   or a composite (`[:or …]`) is a deliberate nullable fn-slot/return type
   and is permitted here. A stored nilable that slips past this narrow
   check is still rejected by the datahike bridge
   (`seon.db.internal/form->datahike-value-type`)."
  [schemas k v]
  (when-let [inner (maybe-inner v)]
    (when (and (keyword? inner) (not (contains? schemas inner)))
      (throw (ex-info
               (str "schema/register! " k ": " (pr-str v)
                    " — a stored value is never nil in seon (absent = the key "
                    "is simply omitted, never stored as nil), so a value schema "
                    "may not be nilable/[:maybe …]. Register the BASE type: "
                    "(schema/register! " k " " (pr-str inner) ") — then mark the "
                    "FIELD optional where it appears in a :map schema: [" k
                    " {:optional true} " (pr-str inner) "], or just omit the key "
                    "entirely when there is no value.")
               {:seon.schema/error :seon.schema/nilable-value-schema
                :seon.schema/key   k
                :seon.schema/definition v
                :seon.error/kind   :user-input})))))

(defn assert-multi-segment-namespace!
  "register!-time gate: reject attrs whose keyword NAMESPACE is
   single-segment (`:workout/date`). Keyword namespaces are DOMAINS with
   ≥2 segments — a single-segment namespace collides with code-namespace
   roots and fragments the reuse surface. Throws a guiding `:user-input`
   ex-info naming a corrected multi-segment example."
  [k]
  (let [ns-str (namespace k)]
    (when (and ns-str (not (str/includes? ns-str ".")))
      (throw (ex-info
               (str "schema/register! " k ": single-segment keyword "
                    "namespace " (pr-str ns-str) " is not allowed. "
                    "Keyword namespaces are data DOMAINS and need ≥2 "
                    "segments — e.g. :" ns-str "/" (name k) " → :kb."
                    ns-str "/" (name k) " or :fitness." ns-str "/"
                    (name k) ". FIRST inspect the installed schema: "
                    "if an attr for this fact already exists, reuse "
                    "its EXACT keyword instead of registering a new "
                    "one.")
               {:seon.schema/error :seon.schema/single-segment-namespace
                :seon.schema/key   k
                :seon.error/kind   :user-input})))))
