(ns seon.schema.form
  "Reusable inspection of authored Malli schema forms."
  (:require [clojure.walk :as walk]))

(def primitive-schema-forms
  "Seon's canonical primitive aliases missing from Malli's built-in registry."
  {:inst 'inst?})

(def ^:private database-attribute-properties
  #{:seon.db/identity
    :seon.db/unique
    :seon.db/index
    :seon.db/component
    :seon.db/no-history?
    :db.secondary/only})

(defn attr-form-properties
  "The Malli properties map from an attribute-schema form, or nil."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]] [:maybe :map]]}
  [form]
  (when (vector? form)
    (some (fn [x] (when (map? x) x)) (rest form))))

(defn- entity-map-form
  "Resolve conjunctive entity maps without changing authored reference edges."
  {:malli/schema [:=> [:cat :map :seon.schema/value] [:or :nil [:vector :seon.schema/value]]]}
  [forms definition]
  (letfn [(collect [node active]
            (cond
              (and (keyword? node) (get forms node))
              (if (contains? active node)
                (throw (ex-info "Cyclic entity declaration."
                                {:seon.schema/identity node}))
                (collect (get forms node) (conj active node)))
              (vector? node)
              (case (first node)
                :map [node]
                :and (mapcat #(collect % active) (remove map? (rest node)))
                (:ref :schema) (collect (last node) active)
                [])
              :else []))
          (entries [node]
            (let [body (rest node)] (if (map? (first body)) (rest body) body)))
          (optional? [entry]
            (true? (when (map? (second entry)) (:optional (second entry)))))
          (scalar [node seen]
            (if (and (keyword? node) (get forms node) (not (contains? seen node)))
              (scalar (get forms node) (conj seen node)) node))]
    (when-let [maps (seq (collect definition #{}))]
      (let [merged
            (reduce
             (fn [result entry]
               (if-let [prior (get result (first entry))]
                 (do
                   (when (or (not= (scalar (peek prior) #{}) (scalar (peek entry) #{}))
                             (and (not (optional? prior)) (optional? entry)))
                     (throw (ex-info "Conflicting inherited entity member or optionalized required member."
                                     {:seon.schema/member (first entry)
                                      :seon.schema/expected prior
                                      :seon.schema/offending entry})))
                   (assoc result (first entry) (if (optional? entry) prior entry)))
                 (assoc result (first entry) entry)))
             {} (mapcat entries maps))]
        (into [:map (apply merge (map attr-form-properties maps))]
              (map merged (distinct (map first (mapcat entries maps)))))))))

(defn map-shape?
  "True for an entity map reached through aliases and every conjunction arm."
  {:malli/schema [:function
                  [:=> [:cat :seon.schema/value] :boolean]
                  [:=> [:cat :map :seon.schema/value] :boolean]]}
  ([definition] (map-shape? {} definition))
  ([forms definition] (boolean (entity-map-form forms definition))))

(defn map-entries
  "All inherited map entries, with requiredness strengthened and conflicts refused."
  {:malli/schema [:function
                  [:=> [:cat :seon.schema/value] [:vector :seon.schema/value]]
                  [:=> [:cat :map :seon.schema/value] [:vector :seon.schema/value]]]}
  ([definition] (map-entries {} definition))
  ([forms definition]
   (if-let [entity (entity-map-form forms definition)]
     (subvec entity 2)
     [])))

(defn schema-properties
  "Properties of an entity declaration, including nested constrained maps."
  {:malli/schema [:function
                  [:=> [:cat :seon.schema/value] [:or :nil :map]]
                  [:=> [:cat :map :seon.schema/value] [:or :nil :map]]]}
  ([definition] (schema-properties {} definition))
  ([forms definition]
   (when-let [entity (entity-map-form forms definition)]
     (not-empty (merge (attr-form-properties entity)
                       (attr-form-properties definition))))))

(defn extends-schema?
  "True only for declared alias/conjunction extension edges, never payload mentions."
  {:malli/schema [:=> [:cat :map :seon.schema/value :qualified-keyword] :boolean]}
  [forms definition ancestor]
  (letfn [(extends? [node seen]
            (cond
              (= node ancestor) true
              (and (keyword? node) (get forms node) (not (contains? seen node)))
              (extends? (get forms node) (conj seen node))
              (and (vector? node) (#{:and :ref :schema} (first node)))
              (boolean (some #(extends? % seen) (remove map? (rest node))))
              :else false))]
    (extends? definition #{})))

(defn namespaced-properties
  "Qualified Malli properties carried by one authored schema form."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]] :map]}
  [form]
  (into {}
        (comp
         (filter (comp qualified-keyword? key))
         (remove (comp nil? val)))
        (or (attr-form-properties form) {})))

(defn property-attributes
  "Qualified Malli property keys present across one schema population."
  {:malli/schema
   [:=> [:cat [:fn clojure.core/map?]] [:set :qualified-keyword]]}
  [forms]
  (into #{}
        (mapcat (comp keys namespaced-properties val))
        forms))

(defn database-attributes
  "Compute persisted database attributes from one immutable schema-form map."
  {:malli/schema
   [:=> [:cat [:fn clojure.core/map?]] [:vector :qualified-keyword]]}
  [forms]
  (->> forms
       (reduce-kv
        (fn [attributes schema-key definition]
          (let [properties (attr-form-properties definition)
                declared-attributes
                (when (and (map-shape? forms definition)
                           (true? (:seon.db/attributes
                                   (schema-properties forms definition))))
                  (into #{}
                        (keep (fn [entry]
                                (let [attribute
                                      (when (vector? entry) (first entry))]
                                  (when (qualified-keyword? attribute)
                                    attribute))))
                        (map-entries forms definition)))]
            (cond-> (into attributes declared-attributes)
              (and (qualified-keyword? schema-key)
                   (some #(contains? properties %)
                         database-attribute-properties))
              (conj schema-key))))
        #{})
       (sort-by str)
       vec))

(def component-entity-key
  "The registry key for one component's own entity map as transaction data."
  :seon.db/component-entity)

(defn- widened-component-child
  "One child position of a component attribute, as ref OR the component."
  [child]
  (if (and (vector? child)
           (= :or (first child))
           (some #{component-entity-key} child))
    child
    [:or child component-entity-key]))

(defn widen-component-children
  "Compile a component attribute's child as a ref OR the component's entity.

   A component attribute declares `[<collection> {:seon.db/component true}
   :seon.db/ref]`, and `:seon.db/ref` admits an entity id, a string, or a
   lookup ref — none of which is the value a producer of that row actually
   builds. Datahike's transaction-data grammar accepts a component's OWN
   entity map wherever it accepts a ref, so a row carrying its components was
   refused by its own declared shape
   (`docs/seon/issues/a-component-value-is-refused-by-its-own-ref-shape.md`,
   owner ruling 2026-08-08, option 1).

   The widening is DERIVED from the `:seon.db/component true` property the
   form already declares, at compilation only: the authored declaration, the
   canonical EDN, and the Datahike bridge all keep reading the narrow form,
   and a component attribute declared tomorrow is widened without an edit.
   Idempotent, so a form may pass through more than once."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]] [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli declarations contain arbitrary literal values and predicates; schema inspection preserves that data, and body wrappers return the caller's result unchanged.", :gen/elements [nil false 0 "" :k [] {}]}]]}
  [form]
  (walk/postwalk
   (fn [value]
     (if (and (vector? value)
              (map? (second value))
              (true? (:seon.db/component (second value))))
       (into [(first value) (second value)]
             (map widened-component-child)
             (drop 2 value))
       value))
   form))

(defn enum-members
  "Members of an `:enum` form after its optional properties map, or []."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli enum members are arbitrary literal values; non-enum candidate forms return an empty vector.", :gen/elements [nil false 0 "" :k [] {}]}]] [:vector [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli enum members are arbitrary literal values; non-enum candidate forms return an empty vector.", :gen/elements [nil false 0 "" :k [] {}]}]]]}
  [form]
  (if (and (vector? form) (= :enum (first form)))
    (let [body (rest form)
          body (if (and (seq body) (map? (first body))) (rest body) body)]
      (vec body))
    []))

(defn nilable-value-schema?
  "True when `v` is a top-level `[:maybe X]` value registration."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [v]
  (and (vector? v) (= :maybe (first v))))
