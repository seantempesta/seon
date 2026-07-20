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

(def primitive-schema-forms
  "Seon's canonical primitive aliases missing from Malli's built-in registry."
  {:inst 'inst?})

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
  "Projection-build gate: reject invalid Malli forms before a candidate
   population can be admitted. Compiles `k` against the complete `schemas`
   population plus `[k v]`; failure
   throws a legible `:user-input`
   ex-info naming the key, the bad form, and common storable types.
   Requires every referenced schema to exist in the complete population,
   without depending on declaration order or Malli's process-global default."
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
                    "keyword in the same admitted schema population.")
               {:seon.schema/error :seon.schema/invalid-schema
                :seon.schema/key   k
                :seon.schema/definition v
                :seon.error/kind   :user-input}
               e)))))

(defn nilable-value-schema?
  "True when `v` is a top-level `[:maybe X]` value registration.

   Optionality belongs at a map entry or function slot, never in the
   registered value shape itself. This predicate is shared by registration
   admission and the Malli→Datahike bridge so the two boundaries cannot
   drift."
  [v]
  (and (vector? v) (= :maybe (first v))))

(defn assert-non-nilable-value-schema!
  "Projection-build gate: reject a top-level nilable value schema whose inner
   is a raw Malli built-in type — e.g. `[:maybe :int]`. In seon a stored
   value is NEVER nil (absent = the key is simply omitted, never stored as
   nil), so a NAMED value schema must not be nilable. Throws a guiding
   `:user-input` ex-info that hands back the copy-pasteable fix: register
   the base type, then mark the FIELD optional at its map site.

   `schemas` is accepted because projection validation passes the complete
   population through the same gate; the decision depends only on `v`."
  [_schemas k v]
  (when (nilable-value-schema? v)
    (let [body (rest v)
          body (if (and (seq body) (map? (first body))) (rest body) body)
          inner (first body)]
      (throw (ex-info
               (str "schema/register! " k ": " (pr-str v)
                    " — a registered value is never nil in seon (absent = the "
                    "key is omitted), so a value schema may not be "
                    "nilable/[:maybe …]. Register the non-nil BASE shape: "
                    "(schema/register! " k " " (pr-str inner) "). Where a map "
                    "field may be absent, write [" k " {:optional true} "
                    (pr-str inner) "]. Where a function slot may return nil, "
                    "put [:maybe " k "] directly in that function schema.")
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
