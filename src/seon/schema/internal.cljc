(ns seon.schema.internal
  "Registration admission and entity-shape derivation for `seon.schema`.

   Private engine internals, factored out of the public registry surface
   so they stay indexed + grep-able WITHOUT rendering into agent context
   (the `*.internal` convention drops them from the curated namespaces
   body — see `seon.agent.ctx.ns-name/hidden-ns-name?`).

   Compiled schemas carry their registry scope. This namespace never requires
   `seon.schema`; the dependency is schema → schema.internal only."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.registry :as mr]
            [malli.util :as mu]))

(def ^:private undefined-types #{:any :some :nil})

(defn- reference-id
  "A ref name together with the options carrying its resolving registry."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]]
                  [:maybe [:tuple :map [:or :keyword :string :symbol]]]]}
  [compiled]
  (when (m/-ref-schema? compiled)
    (when-let [reference (m/-ref compiled)]
      [(or (m/options compiled) {}) reference])))

(defn- same-scoped-schema?
  "Compare compiled children in their captured scopes, including local refs."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?] [:fn malli.core/schema?]] :boolean]}
  [left right]
  (letfn [(same? [left right seen]
            (cond
              (identical? left right) true
              (and (m/schema? left) (m/schema? right))
              (let [pair [(or (reference-id left) left) (or (reference-id right) right)]
                    visited? (contains? seen pair)
                    seen (conj seen pair)]
                (cond
                  visited? true
                  (m/-ref-schema? left) (same? (m/deref left) right seen)
                  (m/-ref-schema? right) (same? left (m/deref right) seen)
                  :else
                  (and (= (m/type left) (m/type right))
                       (= (dissoc (m/properties left) :registry)
                          (dissoc (m/properties right) :registry))
                       (same? (m/children left) (m/children right) seen))))
              (and (sequential? left) (sequential? right))
              (and (= (count left) (count right))
                   (every? true? (map #(same? %1 %2 seen) left right)))
              :else (= left right)))]
    (boolean (same? left right #{}))))

(defn- entity-maps
  "Only maps reached through entity aliases, wrappers and conjunction arms."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]
                           [:set [:tuple :map [:or :keyword :string :symbol]]]]
                  [:vector [:fn malli.core/schema?]]]}
  [compiled active]
  (let [reference (reference-id compiled)]
    (when (and reference (contains? active reference))
      (throw (ex-info "Cyclic entity declaration."
                      {:seon.schema/definition (m/form compiled)})))
    (let [active (cond-> active reference (conj reference))]
      (cond
        (m/-ref-schema? compiled) (entity-maps (m/deref compiled) active)
        (= :map (m/type compiled)) [compiled]
        (= :and (m/type compiled))
        (into [] (mapcat #(entity-maps % active)) (m/children compiled))
        :else []))))

(defn entity-entries
  "Ordered compiled map entries with inherited requiredness and conflict checks."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]]
                  [:vector [:tuple :seon.schema/value [:maybe :map] [:fn malli.core/schema?]]]]}
  [compiled]
  (let [entries (into [] (mapcat m/children) (entity-maps compiled #{}))
        merged
        (reduce
         (fn [result [k properties child :as entry]]
           (if-let [[_ prior-properties prior-child :as prior] (get result k)]
             (do
               (when (or (not (same-scoped-schema? prior-child child))
                         (and (not (:optional prior-properties)) (:optional properties)))
                 (throw (ex-info "Conflicting inherited entity member or optionalized required member."
                                 {:seon.schema/member k
                                  :seon.schema/expected (m/form prior-child)
                                  :seon.schema/offending (m/form child)})))
               (assoc result k (if (:optional properties) prior entry)))
             (assoc result k entry)))
         {} entries)]
    (mapv merged (distinct (map first entries)))))

(defn entity-properties
  "Entity-map properties followed by the selected root's explicit properties."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] [:maybe :map]]}
  [compiled]
  (when-let [maps (seq (entity-maps compiled #{}))]
    (not-empty (merge (apply merge (map m/properties maps))
                      (m/properties compiled)))))

(defn entity-schema?
  "Whether compiled entity composition reaches a map, including an empty map."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] :boolean]}
  [compiled]
  (boolean (seq (entity-maps compiled #{}))))

(defn extends-schema?
  "Follow only compiled alias and conjunction edges in their captured scopes."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?] :qualified-keyword] :boolean]}
  [compiled ancestor]
  (letfn [(extends? [node seen]
            (let [node-id (or (reference-id node) node)]
              (cond
                (contains? seen node-id) false
                (m/-ref-schema? node)
                (or (= ancestor (m/-ref node))
                    (extends? (m/deref node) (conj seen node-id)))
                (= :and (m/type node))
                (boolean (some #(extends? % (conj seen node-id)) (m/children node)))
                :else false)))]
    (extends? compiled #{})))

(defn permissive-positions
  "Inspect schema syntax, excluding literal enum values and property data.

   Each finding retains its exact form and path. A polymorphic slot is
   justified only by its own recorded exemption, reason and generator;
   adding another permissive slot therefore creates another finding."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{:seon.schema/keys [compiled stored?]}]
  (letfn [(visit [node path input-slot? guarded? seen]
            (let [tag (m/type node)
                  properties (m/properties node)
                  children (m/children node)
                  reference (when (m/-ref-schema? node) (m/-ref node))
                  node-id (or (reference-id node) node)
                  reason (:seon.schema.admission/reason properties)
                  justified? (and (= :seon.schema.admission/polymorphic-boundary
                                     (:seon.schema.admission/exemption properties))
                                  (string? reason) (not (str/blank? reason))
                                  (some #(contains? properties %)
                                        [:gen/schema :gen/elements :gen/gen :gen/return]))
                  problem (cond
                            (#{:any :some} tag) :undefined
                            (and stored? (= :maybe tag)) :stored-nil
                            (and input-slot?
                                 (or (= :seon.schema/value reference)
                                     (= :seon.schema/value tag))) :bare-value
                            (and (= :* tag)
                                 (some #(and (m/schema? %)
                                             (or (= :seon.schema/value (m/type %))
                                                 (and (m/-ref-schema? %)
                                                      (= :seon.schema/value (m/-ref %))))) children)) :value-tail
                            (and input-slot? (#{:* :+ :repeat} tag)
                                 (not guarded?)) :unguarded-tail)]
              (into
               (if problem
                 [(cond-> {:seon.schema/path path
                           :seon.schema/definition (m/form node)
                           :seon.schema.advisory/kind problem
                           :seon.schema/justified? (boolean (and justified? (not= :stored-nil problem)))}
                    (and justified? (not= :stored-nil problem))
                    (assoc :seon.schema.admission/reason reason))]
                 [])
               (concat
                (when (and stored? reference (not (contains? seen node-id)))
                  (visit (m/deref node) (conj path 0) false guarded? (conj seen node-id)))
               (mapcat
                (fn [[offset child]]
                  (let [child-path (conj path offset)]
                    (cond
                      (or reference (#{:enum := :fn :ref :re} tag)) []
                      (#{:map :mapn :catn :altn :orn :multi} tag)
                      (if (vector? child)
                        (visit (peek child) (conj path (first child))
                               (= :catn tag) guarded? seen) [])
                      (m/schema? child)
                      (visit child child-path (= :cat tag)
                             (if (= :=> tag) (= 3 (count children)) guarded?) seen)
                      :else [])))
                (map-indexed vector children))))))]
    (visit compiled [] false false #{})))

(defn- contract-error!
  [identity definition path error message data]
  (throw
   (ex-info
    message
    (merge
     {:seon.schema/error error
      error identity
      :seon.schema/identity identity
      :seon.schema/definition definition
      :seon.schema/path (vec path)
      :seon.error/kind :user-input}
     data))))

(defn- guarded-predicate-symbol
  [schema]
  (first (m/children schema)))

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

(defn- assert-error-declaration!
  "Validate structural error inheritance and the complete owned storage declaration."
  {:malli/schema [:=> [:cat :map] :nil]}
  [{:seon.schema/keys [identity definition compiled storable-attribute?]}]
  (when (and (keyword? identity)
             (or (= :seon.error/base identity)
                 (extends-schema? compiled :seon.error/base)))
    (let [registry (:registry (m/options compiled))
          entries (entity-entries compiled)
          required (into #{} (keep #(when-not (:optional (second %)) (first %))) entries)
          base-entries (entity-entries (mr/schema registry :seon.error/base))
          base-members (set (map first base-entries))
          base-required (into #{} (keep #(when-not (:optional (second %)) (first %))) base-entries)
          additions (remove base-members required)
          alias? (keyword? definition)
          properties (entity-properties compiled)]
      (when-not (every? required base-required)
        (contract-error! identity definition [] :seon.schema/invalid-schema
                         "An error declaration must retain every required base member."
                         {:seon.schema/member (first (remove required base-required))}))
      (when (and (not= :seon.error/base identity) (not alias?) (empty? additions))
        (contract-error! identity definition [] :seon.schema/invalid-schema
                         "An error facet requires a non-base domain member." {}))
      (letfn [(boolean-schema? [node seen]
                (cond
                  (not (m/schema? node)) false
                  (contains? seen (or (reference-id node) node)) false
                  (m/-ref-schema? node)
                  (boolean-schema? (m/deref node) (conj seen (or (reference-id node) node)))
                  :else
                  (case (m/type node)
                    :boolean true
                    := (boolean? (first (m/children node)))
                    :and (boolean (some #(boolean-schema? % (conj seen node)) (m/children node)))
                    false)))
              (owned-storage! [node seen]
                (doseq [entry (entity-entries node)
                        :let [attribute (first entry)
                              attribute-schema (mr/schema registry attribute)
                              attribute-properties (when attribute-schema (m/properties attribute-schema))
                              target (:seon.db/component-schema attribute-properties)]]
                  (when (and (not (:optional (second entry)))
                             storable-attribute? (not (storable-attribute? attribute)))
                    (contract-error! identity definition [attribute] :seon.schema/invalid-schema
                                     "A stored error member must have a storable registered attribute."
                                     {:seon.schema/member attribute}))
                  (when (:seon.db/component attribute-properties)
                    (when-not (and (qualified-keyword? target)
                                   (some-> (mr/schema registry target) entity-schema?))
                      (contract-error! identity definition [attribute] :seon.schema/invalid-schema
                                       "An owned error member must declare a complete component schema."
                                       {:seon.schema/member attribute}))
                    (when-not (contains? seen target)
                      (owned-storage! (mr/schema registry target) (conj seen target))))))]
        (when (and (seq additions) (every? #(boolean-schema? (mr/schema registry %) #{}) additions))
          (contract-error! identity definition [] :seon.schema/invalid-schema
                           "A boolean marker alone cannot define an error facet."
                           {:seon.schema/member (first additions)}))
        (when (:seon.db/attributes properties)
          (owned-storage! compiled #{identity})))
      (doseq [property [:seon.render/ai :seon.render/html]
              :let [entry (find properties property)]
              :when (and entry (not (qualified-symbol? (val entry))))]
        (contract-error! identity definition [] :seon.schema/invalid-schema
                         "An error render property names a qualified function symbol."
                         {:seon.schema/member property}))
      (when (not= (contains? properties :seon.render/ai)
                  (contains? properties :seon.render/html))
        (contract-error! identity definition [] :seon.schema/invalid-schema
                         "Declare both error render functions or neither." {}))))
  nil)

(defn assert-complete-schema!
  "Reject incomplete positions in one compiled schema.

   Canonical references are not followed here; `seon.schema` recursively
   validates them with each referenced declaration's own admission source.
   Returns advisory findings that remain non-terminal."
  {:malli/schema [:=> [:cat :map] [:vector :map]]}
  [{:seon.schema/keys [identity definition compiled role admission
                       predicate-symbols pure-predicate-symbols
                       canonical-keys] :as request}]
  (assert-error-declaration! request)
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
         (when (and authored? (= :fn schema-type))
           (let [predicate (guarded-predicate-symbol schema)
                 prebound?
                 (and (not (qualified-symbol? predicate))
                      (seq predicate-symbols)
                      (every? qualified-symbol? predicate-symbols))]
             (when-not (and (or (qualified-symbol? predicate) prebound?)
                            (guarded-predicate-properties-complete? properties))
               (contract-error!
                identity definition path
                :seon.schema/incomplete-predicate-contract
                (str identity
                     " uses a predicate schema without a qualified predicate, "
                     "a nonblank `:error/message`/`:error/fn`, and a bounded "
                     "`:gen/schema`, `:gen/elements`, or `:gen/return`.")
                {:seon.schema/predicate predicate}))
             (when-let [unproved
                        (if prebound?
                          (first (remove pure-predicate-symbols
                                         (sort predicate-symbols)))
                          (when-not (contains? pure-predicate-symbols predicate)
                            predicate))]
               (contract-error!
                identity definition path
                :seon.schema/unproved-predicate-purity
                (str identity " references predicate " unproved
                     ", but its existing program-graph call edges do not yet "
                     "prove a pure, capability-free transitive call graph. "
                     "Keep the predicate as a separately schema'd corpus "
                     "function, then re-register this contract after the "
                     "execution planner admits that graph.")
                {:seon.schema/predicate unproved}))))
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
                   " has a bare nilable return. Return an explicit result/error "
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

(defn map-required-attrs
  "Required keys of a compiled entity composition, excluding Malli's default."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?]] [:maybe [:vector :keyword]]]}
  [compiled]
  (not-empty
   (vec (sort-by str
                 (keep (fn [[k properties _]]
                         (when (and (keyword? k) (not= k :malli.core/default)
                                    (not (:optional properties))) k))
                       (entity-entries compiled))))))

(defn- missing-schema-reference
  [error]
  (loop [cause error]
    (when cause
      (let [{:keys [type data]} (ex-data cause)
            missing (:schema data)]
        (if (and (= :malli.core/invalid-schema type)
                 (or (keyword? missing) (symbol? missing))
                 (namespace missing))
          missing
          (recur (ex-cause cause)))))))

(defn assert-compilable-schema!
  "Projection-build gate: reject invalid Malli forms before a candidate
   population can be admitted. Compiles `k` against the complete `schemas`
   population plus `[k v]`; failure
   throws a legible `:user-input`
   ex-info naming the key, the bad form, and common storable types.
   Requires every referenced schema to exist in the complete population,
   without depending on declaration order or Malli's process-global default."
  {:malli/schema
   [:function
    {:registry
     {::bound-definition
      [:or :nil :boolean
       [:fn clojure.core/number?]
       [:fn clojure.core/char?]
       :string :keyword :symbol :uuid
       [:fn clojure.core/inst?]
       [:fn clojure.core/map?]
       [:fn clojure.core/vector?]
       [:fn clojure.core/set?]
       [:fn clojure.core/sequential?]]}}
    [:=> [:cat :map :keyword ::bound-definition] :nil]
    [:=> [:cat :map :keyword ::bound-definition :map] :nil]]}
  ([schemas k v]
   (assert-compilable-schema! schemas k v {}))
  ([schemas k v compile-options]
   (try
    (let [registry (or (:registry compile-options)
                       (mr/composite-registry
                        (m/default-schemas)
                        (mr/fast-registry (assoc schemas k v))))]
      (m/schema k (assoc compile-options :registry registry)))
    nil
    (catch #?(:clj Exception :cljs :default) e
      (let [missing (missing-schema-reference e)
            missing-ns (some-> missing namespace)]
        (throw (ex-info
                 (str "schema/register! " k ": " (pr-str v)
                      " is not a valid Malli schema (" (ex-message e) "). "
                      (when missing
                        (str "Missing schema reference " missing
                             " from namespace " missing-ns ". "))
                      "Common storable attr types: :string :int :double "
                      ":float :boolean :keyword :inst :uuid :symbol "
                      ":seon.db/ref, [:enum :a :b], or a container "
                      "[:vector <type>] / [:set <type>]. (:number is NOT "
                      "a type — use :int or :double.) If the form "
                      "references another schema keyword, register that "
                      "keyword in the same admitted schema population.")
                 (cond-> {:seon.schema/error :seon.schema/invalid-schema
                          :seon.schema/key   k
                          :seon.schema/definition v
                          :seon.error/kind   :user-input :seon.schema/invalid-schema k}
                   missing
                   (assoc :seon.schema/missing-reference missing
                          :seon.schema/missing-reference-namespace
                          missing-ns))
                 e)))))))

(defn assert-non-nilable-value-schema!
  "Projection-build gate: reject a top-level nilable value schema whose inner
   is a raw Malli built-in type — e.g. `[:maybe :int]`. In seon a stored
   value is NEVER nil (absent = the key is simply omitted, never stored as
   nil), so a NAMED value schema must not be nilable. Throws a guiding
   `:user-input` ex-info that hands back the copy-pasteable fix: register
   the base type, then mark the FIELD optional at its map site.

   `schemas` is accepted because projection validation passes the complete
   population through the same gate; the decision depends only on `v`.
   `v` is candidate data here, not an independently compiled definition:
   [[assert-compilable-schema!]] owns validity against that complete
   population."
  {:malli/schema
   [:=> [:cat :map :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli inspection receives arbitrary declaration children, including literals, predicates and incomplete candidate forms; this boundary cannot require an already valid compiled schema.", :gen/elements [nil false 0 "" :k [] {}]}]] :nil]}
  [_schemas k compiled]
  (when (= :maybe (m/type compiled))
    (let [v (m/form compiled)
          inner (m/form (first (m/children compiled)))]
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
                :seon.error/kind   :user-input :seon.schema/nilable-value-schema k})))))

(defn assert-multi-segment-namespace!
  "register!-time gate: reject attrs whose keyword NAMESPACE is
   single-segment (`:workout/date`). Keyword namespaces are DOMAINS with
   ≥2 segments — a single-segment namespace collides with code-namespace
   roots and fragments the reuse surface. Throws a guiding `:user-input`
   ex-info naming a corrected multi-segment example."
  {:malli/schema [:=> [:cat :keyword] :nil]}
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
                :seon.schema/single-segment-namespace k
                :seon.schema/key   k
                :seon.error/kind   :user-input})))))
