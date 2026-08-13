(ns seon.fn.schema-shape
  "Content-addressed, queryable Malli schema shapes."
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.schema :as schema])
  (:import (java.nio.charset StandardCharsets)))

(def normalization-revision
  "The Malli pin and P12 normalization contract."
  "malli-80138076960e7820523b4cb932c5b5d1936d4e7f/p12-v2")

(defn- map-properties
  [properties]
  (cond-> properties
    (false? (:closed properties)) (dissoc :closed)))

(declare canonical-form)

(defn- split-schema-form
  [form]
  (let [[schema-type & tail] form
        properties (when (map? (first tail)) (first tail))]
    {:seon.schema.shape/type schema-type
     :seon.schema.shape/properties properties
     :seon.schema.shape/children (if properties (rest tail) tail)}))

(defn- canonical-map-entry
  [entry]
  (let [[entry-key a b] entry
        [properties child] (if (map? a) [a b] [nil a])
        properties (cond-> properties
                     (false? (:optional properties)) (dissoc :optional))]
    (cond-> [entry-key]
      (seq properties) (conj (canonical-form properties))
      true (conj (canonical-form child)))))

(defn- canonical-form
  [value]
  (cond
    (and (vector? value) (= :map (first value)))
    (let [{properties :seon.schema.shape/properties
           entries :seon.schema.shape/children}
          (split-schema-form value)]
      (into (cond-> [:map]
              (seq (map-properties properties))
              (conj (canonical-form (map-properties properties))))
            (sort-by (comp schema/canonical-data-string first)
                     (map canonical-map-entry entries))))

    (vector? value) (mapv canonical-form value)
    (map? value) (into {} (map (fn [[k v]] [(canonical-form k)
                                             (canonical-form v)])) value)
    (set? value) (into #{} (map canonical-form) value)
    (sequential? value) (apply list (map canonical-form value))
    :else value))

(defn- local-registry-form?
  [form]
  (boolean
   (some (fn [value]
           (or (and (map? value) (contains? value :registry))
               (and (vector? value) (= :ref (first value)))))
         (tree-seq coll? seq form))))

(declare expand-schema-form)

(defn- expand-entry-form
  [entry forms registry seen]
  (let [[entry-key a b] entry
        [properties child] (if (map? a) [a b] [nil a])]
    (cond-> [entry-key]
      properties (conj properties)
      true (conj (expand-schema-form child forms registry seen)))))

(defn- expand-schema-form
  [form forms registry seen]
  (cond
    (and (qualified-keyword? form)
         registry
         (not (contains? seen form)))
    (if-let [definition (if (contains? forms form)
                          (get forms form)
                          (mr/schema registry form))]
      (let [definition (if (m/schema? definition)
                         (m/form definition) definition)]
        (if (= definition form)
          form
          (expand-schema-form definition forms registry (conj seen form))))
      form)

    (vector? form)
    (let [{schema-type :seon.schema.shape/type
           properties :seon.schema.shape/properties
           children :seon.schema.shape/children}
          (split-schema-form form)
          prefix (cond-> [schema-type] properties (conj properties))]
      (cond
        (= :map schema-type)
        (into prefix (map #(expand-entry-form % forms registry seen)) children)
        (contains? #{:catn :altn :orn} schema-type)
        (into prefix (map #(expand-entry-form % forms registry seen)) children)
        (contains? #{:enum := :fn :re} schema-type)
        form
        :else
        (into prefix (map #(expand-schema-form % forms registry seen)) children)))

    :else form))

(defn normalized-form
  "Canonical normalized form for one compiled Malli schema."
  {:malli/schema
   [:function
    [:=> [:cat :seon.schema/value] :seon.schema/value]
    [:=> [:cat :seon.schema/value :map] :seon.schema/value]
    [:=> [:cat :seon.schema/value :map :map] :seon.schema/value]]}
  ([compiled]
   (normalized-form compiled {} {}))
  ([compiled forms]
   (normalized-form compiled forms {}))
  ([compiled forms predicate-functions]
  (let [authored (m/form compiled)
        structural-only? (local-registry-form? authored)
        registry (:registry (m/options compiled))
        expanded (if structural-only?
                   authored
                   (expand-schema-form authored forms registry #{}))
        canonical
        (try
          (schema/canonical-definition expanded predicate-functions)
          (catch Throwable error
            (throw
             (ex-info "A compiled schema did not retain canonical EDN shape data."
                      {:seon.error/kind
                       :seon.schema.shape/noncanonical-compiled-form
                       :seon.schema.shape/authored-form authored
                       :seon.schema.shape/expanded-form expanded :seon.schema.shape/noncanonical-compiled-form true}
                      error))))]
    {:seon.schema.shape/form
     (canonical-form canonical)
     :seon.schema.shape/comparison
     (if structural-only? :structural-only :exact)})))

(defn fingerprint
  "SHA-256 identity for one canonical normalized schema form."
  {:malli/schema [:=> [:cat :seon.schema/value] :string]}
  [form]
  (schema/sha-256
   [(.getBytes ^String (schema/canonical-data-string form)
               StandardCharsets/UTF_8)]))

(defn- key-kind
  [value]
  (cond
    (nil? value) :nil
    (keyword? value) :keyword
    (string? value) :string
    (symbol? value) :symbol
    (boolean? value) :boolean
    (integer? value) :int
    (double? value) :double
    (uuid? value) :uuid
    (inst? value) :inst
    (vector? value) :vector
    (map? value) :map
    (set? value) :set
    (sequential? value) :sequential
    :else
    (throw
     (ex-info "Schema map entry has an unsupported non-EDN key."
              {:seon.error/kind :seon.schema.shape/unsupported-map-key
               :seon.schema.map-entry/key-edn (pr-str value) :seon.schema.shape/unsupported-map-key true}))))

(defn typed-key-facts
  "Typed database facts for one Malli map-entry key."
  {:malli/schema [:=> [:cat :seon.schema/value] :map]}
  [value]
  (let [kind (key-kind value)
        base {:seon.schema.map-entry/key-kind kind
              :seon.schema.map-entry/key-fingerprint
              (fingerprint value)
              :seon.schema.map-entry/key-edn (pr-str value)}]
    (case kind
      :keyword (assoc base :seon.schema.map-entry/key-keyword value)
      :string (assoc base :seon.schema.map-entry/key-string value)
      :symbol (assoc base :seon.schema.map-entry/key-symbol value)
      :boolean (assoc base :seon.schema.map-entry/key-boolean value)
      :int (assoc base :seon.schema.map-entry/key-int (long value))
      :double (assoc base :seon.schema.map-entry/key-double value)
      :uuid (assoc base :seon.schema.map-entry/key-uuid value)
      :inst (assoc base :seon.schema.map-entry/key-inst value)
      base)))

(defn- schema-form?
  [form]
  (or (keyword? form) (symbol? form) (seq? form)
      (and (vector? form) (seq form))))

(defn- child-row
  "`[row seen]` for one non-map child of a shape."
  [shape-fingerprint order child schema-child? seen encode]
  (let [child-id (str "seon.schema.shape.child/" shape-fingerprint "/" order)
        base {:db/id child-id
              :seon.schema.shape.child/id child-id
              :seon.schema.shape.child/order (long order)}]
    (if (and schema-child? (schema-form? child))
      (let [[schema seen] (encode child seen)]
        [(assoc base :seon.schema.shape.child/schema schema) seen])
      [(assoc base :seon.schema.shape.child/value-edn (pr-str child)) seen])))

(defn- entry-row
  "`[row seen]` for one map entry of a shape."
  [shape-fingerprint order entry seen encode]
  (let [[entry-key a b] entry
        [properties child] (if (map? a) [a b] [nil a])
        entry-id (str "seon.schema.shape.entry/" shape-fingerprint "/" order)
        [schema seen] (encode child seen)]
    [(cond->
      (merge
       {:db/id entry-id
        :seon.schema.shape.entry/id entry-id
        :seon.schema.shape.entry/order (long order)
        :seon.schema.shape.entry/optional? (true? (:optional properties))
        :seon.schema.shape.entry/schema schema}
       (typed-key-facts entry-key))
       (seq properties)
       (assoc :seon.schema.shape.entry/properties
              (pr-str (canonical-form properties))))
     seen]))

(defn- ordered-rows
  "`[rows seen]` from `row-of` over ordered `children`, threading `seen`."
  [row-of seen children]
  (reduce
   (fn [[rows seen] [order child]]
     (let [[row seen] (row-of order child seen)]
       [(conj rows row) seen]))
   [[] seen]
   (map-indexed vector children)))

(defn- form-parts
  [form]
  (if (vector? form)
    (let [{shape-type :seon.schema.shape/type
           properties :seon.schema.shape/properties
           children :seon.schema.shape/children}
          (split-schema-form form)]
      {:seon.schema.shape/type shape-type
       :seon.schema.shape/properties properties
       :seon.schema.shape/children children
       :seon.schema.shape/schema-children?
       (not (contains? #{:enum := :fn :re :> :>= :< :<=} shape-type))})
    {:seon.schema.shape/type
     (if (keyword? form) form :malli.core/predicate)
     :seon.schema.shape/children []}))

;;; The already-encoded fingerprint set is the ENCODING'S RETURN VALUE,
;;; threaded child to child and back out to the parent, so a recursive
;;; schema is deduplicated without any reference outliving the call.
(defn- encode-form
  "`[row-or-lookup seen]` for one normalized schema form."
  [form comparison seen]
  (let [shape-fingerprint (fingerprint form)
        lookup [:seon.schema.shape/fingerprint shape-fingerprint]
        {shape-type :seon.schema.shape/type
         properties :seon.schema.shape/properties
         children :seon.schema.shape/children
         schema-children? :seon.schema.shape/schema-children?}
        (form-parts form)]
    (if (contains? seen shape-fingerprint)
      [lookup seen]
      (let [seen (conj seen shape-fingerprint)
            encode #(encode-form %1 comparison %2)
            row (cond-> {:seon.schema.shape/fingerprint shape-fingerprint
                         :seon.schema.shape/normalization-revision
                         normalization-revision
                         :seon.schema.shape/form (pr-str form)
                         :seon.schema.shape/comparison comparison
                         :seon.schema.shape/type shape-type}
                  (seq properties)
                  (assoc :seon.schema.shape/properties (pr-str properties)))]
        (cond
          (and (= :map shape-type) (seq children))
          (let [[entries seen]
                (ordered-rows
                 (fn [order entry seen]
                   (entry-row shape-fingerprint order entry seen encode))
                 seen children)]
            [(assoc row :seon.schema.shape/entries entries) seen])

          (and (not= :map shape-type) (seq children))
          (let [[child-rows seen]
                (ordered-rows
                 (fn [order child seen]
                   (child-row shape-fingerprint order child
                              schema-children? seen encode))
                 seen children)]
            [(assoc row :seon.schema.shape/children child-rows) seen])

          :else [row seen])))))

(defn shape-row
  "Shared content-addressed row for one compiled Malli schema."
  {:malli/schema
   [:function
    [:=> [:cat :seon.schema/value] :map]
    [:=> [:cat :seon.schema/value :map] :map]
    [:=> [:cat :seon.schema/value :map :map] :map]]}
  ([compiled]
   (shape-row compiled {} {}))
  ([compiled forms]
   (shape-row compiled forms {}))
  ([compiled forms predicate-functions]
   (let [{form :seon.schema.shape/form
          comparison :seon.schema.shape/comparison}
         (normalized-form compiled forms predicate-functions)]
     (first (encode-form form comparison #{})))))

(defn row-form
  "Canonical normalized form retained by a schema-shape row."
  {:malli/schema [:=> [:cat :map] :seon.schema/value]}
  [row]
  (edn/read-string (:seon.schema.shape/form row)))

(defn assert-consistent!
  "Refuse fingerprint reuse for distinct normalized forms."
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential :map]]}
  [rows]
  (doseq [[shape-fingerprint matching]
          (group-by :seon.schema.shape/fingerprint rows)
          :let [forms (into #{} (map :seon.schema.shape/form) matching)]
          :when (> (count forms) 1)]
    (throw
     (ex-info "A schema fingerprint identifies distinct normalized forms."
              {:seon.error/kind :seon.schema.shape/fingerprint-collision
               :seon.schema.shape/fingerprint shape-fingerprint
               :seon.schema.shape/forms forms :seon.schema.shape/fingerprint-collision true})))
  rows)
