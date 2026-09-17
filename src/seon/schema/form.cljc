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
  "The structural map in a map declaration with additional constraints."
  [form]
  (when (vector? form)
    (case (first form)
      :map form
      :and (some #(when (and (vector? %) (= :map (first %))) %) (rest form))
      nil)))

(defn map-shape?
  "True for a map, including a map constrained by an enclosing conjunction."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [form]
  (boolean (entity-map-form form)))

(defn map-entries
  "Entries of the structural map, without its head and properties."
  {:malli/schema [:=> [:cat [:or :nil [:sequential :seon.schema/value]]]
                  [:vector :seon.schema/value]]}
  [form]
  (let [body (rest (or (entity-map-form form) form))]
    (vec (if (map? (first body)) (rest body) body))))

(defn schema-properties
  "Properties of the entity declaration, including a constrained map."
  {:malli/schema [:=> [:cat :seon.schema/value] [:maybe :map]]}
  [form]
  (when-let [value-map (entity-map-form form)]
    (let [properties (merge (attr-form-properties value-map)
                            (attr-form-properties form))]
      (when (seq properties) properties))))

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
                (when (and (map-shape? definition)
                           (true? (:seon.db/attributes
                                   (schema-properties definition))))
                  (into #{}
                        (keep (fn [entry]
                                (let [attribute
                                      (when (vector? entry) (first entry))]
                                  (when (qualified-keyword? attribute)
                                    attribute))))
                        (map-entries definition)))]
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
