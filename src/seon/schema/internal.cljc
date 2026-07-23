(ns seon.schema.internal
  "Registration admission and entity-shape derivation for `seon.schema`.

   Private engine internals, factored out of the public registry surface
   so they stay indexed + grep-able WITHOUT rendering into agent context
   (the `*.internal` convention drops them from the curated namespaces
   body — see `seon.agent.ctx.ns-name/hidden-ns-name?`).

   Reusable Malli-form inspection lives in `seon.schema.form`. The registry
   atom lives in `seon.schema`;
   the identity check ([[identity-attr?]]) reads it through a passed-in
   `schemas` map so this namespace never requires `seon.schema` (no cycle:
   schema → schema.internal only)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.registry :as mr]
            [malli.util :as mu]
            [seon.schema.form :as form]))

(def ^:private undefined-types #{:any :some :nil})

(defn- contract-error!
  [identity definition path error message data]
  (throw
   (ex-info
    message
    (merge
     {:seon.schema/error error
      :seon.schema/identity identity
      :seon.schema/definition definition
      :seon.schema/path (vec path)
      :seon.error/kind :user-input}
     data))))

(defn- guarded-predicate-symbol
  [schema]
  (let [definition (m/form schema)
        body (if (map? (second definition))
               (drop 2 definition)
               (rest definition))]
    (first body)))

(defn- guarded-predicate-properties-complete?
  [properties]
  (and (or (and (string? (:error/message properties))
                (not (str/blank? (:error/message properties))))
           (some? (:error/fn properties)))
       (or (contains? properties :gen/schema)
           (contains? properties :gen/elements)
           (contains? properties :gen/return))))

(defn- map-value-maybe?
  [compiled path]
  (when (seq path)
    (some-> (mu/get-in compiled (pop (vec path)))
            m/type
            (= :map))))

(defn assert-complete-schema!
  "Reject incomplete positions in one compiled schema.

   Canonical references are not followed here; `seon.schema` recursively
   validates them with each referenced declaration's own admission source.
   Returns advisory findings that remain non-terminal."
  [{:seon.schema/keys [identity definition compiled role admission
                       pure-predicate-symbols canonical-keys]}]
  (let [authored?
        (= :agent (:seon.schema.admission/source admission))
        advisories (volatile! [])
        walk-options
        {::m/walk-schema-refs #(not (contains? canonical-keys %))
         ::m/walk-refs #(not (contains? canonical-keys %))}]
    (m/walk
     compiled
     (fn [schema path _children _options]
       (let [schema-type (m/type schema)
             properties (or (m/properties schema) {})]
         (when (and authored? (contains? undefined-types schema-type))
           (contract-error!
            identity definition path :seon.schema/undefined-contract
            (str identity " uses " schema-type
                 " in an agent-authored contract. Replace the undefined slot "
                 "with a named predicate schema, for example "
                 "(schema/register! ::value "
                 "[:fn {:error/message \"must be ...\" "
                 ":gen/schema :string} 'my.domain/value?]).")
            {:seon.schema/schema-type schema-type}))
         (when (and authored?
                    (= :input role)
                    (= :map schema-type)
                    (not (true? (:closed properties))))
           (contract-error!
            identity definition path :seon.schema/open-argument-map
            (str identity
                 " has an open agent-authored argument map. Declare "
                 "`{:closed true}` on every input map; `malli.util/closed-schema` "
                 "shows the recursively closed shape, but admission will not "
                 "rewrite the authored contract.")
            {}))
         (when (and authored? (= :fn schema-type))
           (let [predicate (guarded-predicate-symbol schema)]
             (when-not (and (qualified-symbol? predicate)
                            (guarded-predicate-properties-complete? properties))
               (contract-error!
                identity definition path
                :seon.schema/incomplete-predicate-contract
                (str identity
                     " uses a predicate schema without a qualified predicate, "
                     "a nonblank `:error/message`/`:error/fn`, and a bounded "
                     "`:gen/schema`, `:gen/elements`, or `:gen/return`.")
                {:seon.schema/predicate predicate}))
             (when (and authored?
                        (not (contains? pure-predicate-symbols predicate)))
               (contract-error!
                identity definition path
                :seon.schema/unproved-predicate-purity
                (str identity " references predicate " predicate
                     ", but its existing program-graph call edges do not yet "
                     "prove a pure, capability-free transitive call graph. "
                     "Keep the predicate as a separately schema'd corpus "
                     "function, then re-register this contract after the "
                     "execution planner admits that graph.")
                {:seon.schema/predicate predicate}))))
         (when (= :maybe schema-type)
           (cond
             (and authored? (map-value-maybe? compiled path))
             (contract-error!
              identity definition path :seon.schema/nilable-map-value
              (str identity
                   " uses `[:maybe ...]` as a map value. Optional means the "
                   "key is absent: remove `:maybe` and put "
                   "`{:optional true}` on the map entry.")
              {})

             (and authored? (= :output role) (empty? path))
             (contract-error!
              identity definition path :seon.schema/nilable-return
              (str identity
                   " has a bare nilable return. Return a closed result/error "
                   "envelope, an empty collection, or an explicit named sum.")
              {})

             :else
             (vswap! advisories conj
                     {:seon.schema.advisory/kind :seon.schema.advisory/maybe
                      :seon.schema/identity identity
                      :seon.schema/path (vec path)})))
         schema))
     walk-options)
    @advisories))

(defn identity-attr?
  "True when the schema form for `attr-key` in `schemas` carries
   `{:seon.db/identity true}`. Covers the three shapes Seon uses:
     [:string  {:seon.db/identity true}]
     [:keyword {:seon.db/identity true}]
     [:and {:seon.db/identity true} :seon.db/id]"
  [schemas attr-key]
  (boolean
   (some-> (get schemas attr-key) form/attr-form-properties :seon.db/identity)))

(defn- map-identity-entry-key
  "The first entry key of `:map` schema `v` that is itself an identity
   attr in `schemas` (`{:seon.db/identity true}`), or nil."
  [schemas v]
  (when (form/map-shape? v)
    (some (fn [entry]
            (when-let [k (and (vector? entry) (first entry))]
              (when (identity-attr? schemas k) k)))
          (form/map-entries v))))

(defn derive-entity-id-attr
  "Identity-attr entry key of `v` when `v` is a `:map` DECLARED a stored
   entity kind (`{:seon.db/entity true}` in its own props); else nil.

   Entity-kind-ness is DECLARED, never inferred: a request/response
   envelope that merely carries an id entry must NOT become a catalogued
   kind. The derived id-attr makes a declared schema self-describing for
   the renderer's discovery walk — no per-row `:seon.entity/kind` stamp."
  [schemas v]
  (when (:seon.db/entity (form/schema-properties v))
    (map-identity-entry-key schemas v)))

(defn map-required-attrs
  "Entry keys of `:map` form `v` whose props do NOT carry `{:optional
   true}` (excluding the `::m/default` sentinel) — the required-attrs
   index for schemas-as-queryable-data."
  [v]
  (when (form/map-shape? v)
    (into []
          (keep (fn [entry]
                  (when (vector? entry)
                    (let [k     (first entry)
                          props (let [p (second entry)] (when (map? p) p))]
                      (when (and (keyword? k)
                                 (not= k :malli.core/default)
                                 (not (:optional props)))
                        k)))))
          (form/map-entries v))))

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
  ([schemas k v]
   (assert-compilable-schema! schemas k v {}))
  ([schemas k v compile-options]
   (try
    (let [registry (mr/composite-registry
                    (m/default-schemas)
                    (mr/fast-registry (assoc schemas k v)))]
      (m/schema k (assoc compile-options :registry registry)))
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
               e))))))

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
  (when (form/nilable-value-schema? v)
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
