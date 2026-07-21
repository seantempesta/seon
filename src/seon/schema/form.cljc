(ns seon.schema.form
  "Reusable inspection of authored Malli schema forms.")

(def primitive-schema-forms
  "Seon's canonical primitive aliases missing from Malli's built-in registry."
  {:inst 'inst?})

(defn attr-form-properties
  "The Malli properties map from an attribute-schema form, or nil."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [form]
  (when (vector? form)
    (some (fn [x] (when (map? x) x)) (rest form))))

(defn map-shape?
  "True if `v` looks like a Malli `:map` schema form."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [v]
  (and (vector? v) (= :map (first v))))

(defn map-entries
  "Entries of a `:map` form with its head and optional properties stripped."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [v]
  (let [body (rest v)
        body (if (and (seq body) (map? (first body))) (rest body) body)]
    (vec body)))

(defn schema-properties
  "The `:map` schema's properties map between its head and entries, or nil."
  {:malli/schema [:=> [:cat :any] [:maybe :map]]}
  [v]
  (when (map-shape? v)
    (let [body (rest v)]
      (when (and (seq body) (map? (first body)))
        (first body)))))

(defn enum-members
  "Members of an `:enum` form after its optional properties map, or []."
  {:malli/schema [:=> [:cat :any] [:vector :any]]}
  [form]
  (if (and (vector? form) (= :enum (first form)))
    (let [body (rest form)
          body (if (and (seq body) (map? (first body))) (rest body) body)]
      (vec body))
    []))

(defn nilable-value-schema?
  "True when `v` is a top-level `[:maybe X]` value registration."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [v]
  (and (vector? v) (= :maybe (first v))))
