(ns seon.fn.schema-shape
  "Content-addressed, queryable Malli schema shapes."
  (:require [clojure.edn :as edn]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.db :as db]
            [seon.schema :as schema])
  (:import (java.nio.charset StandardCharsets)))

(def normalization-revision
  "The Malli pin and P12 normalization contract."
  "malli-80138076960e7820523b4cb932c5b5d1936d4e7f/authored-v3")

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

(defn fingerprint
  "SHA-256 identity for one canonical normalized schema form."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli's form/AST boundary accepts both compiled Schema objects and raw forms; their embedded literals may have arbitrary Clojure shapes.", :gen/elements [nil false 0 "" :k [] {}]}]] :string]}
  [form]
  (schema/sha-256
   [(.getBytes ^String (schema/canonical-data-string form)
               StandardCharsets/UTF_8)]))

(defn- authored-form
  {:malli/schema [:=> [:cat :seon.schema/value :map] :seon.schema/value]}
  [form predicate-functions]
  (let [form (if (m/schema? form) (m/form form) form)]
    (if (or (keyword? form) (symbol? form))
      form
      (canonical-form (schema/canonical-definition form predicate-functions)))))

(defn- compiled-references
  "Read canonical references and their already compiled targets from Malli.
  Local references are walked within their own scope; literals are never names."
  {:malli/schema [:=> [:cat [:fn malli.core/schema?] :map]
                  [:map-of :keyword [:fn malli.core/schema?]]]}
  [compiled forms]
  (let [references (volatile! {})
        canonical? #(contains? forms %)]
    (m/walk compiled
            (fn [node _path _children _options]
              (when (and (m/-ref-schema? node) (canonical? (m/-ref node)))
                (vswap! references assoc (m/-ref node) (m/deref node)))
              node)
            {::m/walk-schema-refs (complement canonical?)
             ::m/walk-refs (complement canonical?)})
    @references))

(defn- form-fingerprint
  {:malli/schema [:=> [:cat :seon.schema/value :map :map] :string]}
  [form projection references]
  (if-let [named (and (qualified-keyword? form) (get references form))]
    named
    (let [dependencies (when (and (vector? form) (seq references))
                         (select-keys references
                           (keys
                            (compiled-references
                             (m/schema
                              (schema/compilable-form form (schema/predicate-functions-in projection))
                              {:registry (:seon.schema.projection/registry projection)})
                             (:seon.schema.projection/forms projection)))))]
      (fingerprint (if (seq dependencies) [form dependencies] form)))))

(defn- reference-fingerprints
  "Derive each reachable definition once; canonical registry cycles are refused
  by projection construction. Local recursive registries stay in authored data."
  {:malli/schema [:=> [:cat :map [:set :keyword]] :map]}
  [projection roots]
  (let [forms (:seon.schema.projection/forms projection)
        registry (:seon.schema.projection/registry projection)
        predicates (schema/predicate-functions-in projection)]
    (letfn [(visit [result reference]
              (if (find result reference)
                result
                (let [definition (get forms reference)
                      form (if (m/schema? definition)
                             (authored-form definition predicates)
                             (canonical-form definition))
                      dependencies (or (get (:seon.schema.projection/schema-dependencies projection)
                                            reference)
                                       (keys (compiled-references
                                              (m/schema (mr/schema registry reference)
                                                        {:registry registry}) forms)))
                      result (reduce visit result (sort dependencies))]
                  (assoc result reference
                         (fingerprint
                          [reference
                           {reference
                            (if-let [named (and (qualified-keyword? form) (get result form))]
                              named
                              (fingerprint
                               (if (seq dependencies)
                                 [form (select-keys result dependencies)] form)))}])))))]
      (reduce visit {} (sort roots)))))

(defn prepare-forms
  "Carry definition fingerprints on this immutable publication's forms.
  This is a batch derivation, not a cache or another stored registry."
  {:malli/schema [:=> [:cat :map] :map]}
  [projection]
  (let [forms (:seon.schema.projection/forms projection)]
    (with-meta forms
      (assoc (meta forms) ::reference-fingerprints
             (reference-fingerprints projection (set (keys forms)))))))

(defn- form-projection
  {:malli/schema [:=> [:cat [:fn malli.core/schema?] :map :map] :map]}
  [compiled forms predicate-functions]
  (let [registry (:registry (m/options compiled))
        local-keys (into #{} (mapcat #(keys (:registry %)))
                         (filter map? (tree-seq coll? seq (m/form compiled))))]
    {:seon.schema.projection/forms
     (if (seq forms) forms
         (into {} (filter #(and (qualified-keyword? (key %))
                                (not (contains? local-keys (key %)))))
               (when registry (mr/schemas registry))))
     :seon.schema.projection/registry (or registry (m/default-schemas))
     :seon.schema.projection/predicate-functions predicate-functions}))

(defn normalized-form
  "Authored Malli form and its dependency-aware fingerprint.
  References remain
  names; their definition fingerprints move identity when the registry changes."
  {:malli/schema
   [:function
    [:=> [:cat [:fn malli.core/schema?]] :map]
    [:=> [:cat [:fn malli.core/schema?] :map] :map]
    [:=> [:cat [:fn malli.core/schema?] :map :map] :map]]}
  ([compiled] (normalized-form compiled {} {}))
  ([compiled forms] (normalized-form compiled forms {}))
  ([compiled forms predicate-functions]
   (let [projection (form-projection compiled forms predicate-functions)
         form (authored-form compiled predicate-functions)
         references (or (::reference-fingerprints (meta forms))
                        (reference-fingerprints
                         projection (if (seq (:seon.schema.projection/forms projection))
                                      (set (keys (compiled-references compiled
                                                   (:seon.schema.projection/forms projection))))
                                      #{})))]
     {:seon.schema.shape/form form
      :seon.schema.shape/fingerprint (form-fingerprint form projection references)
      :seon.schema.shape/comparison
      (if (local-registry-form? form) :structural-only :exact)
      ::projection projection
      ::reference-fingerprints references})))

(defn- key-kind
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.schema.map-entry/key-kind]}
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
              {:seon.schema.map-entry/key-edn (pr-str value) :seon.schema.shape/unsupported-map-key true}))))

(defn typed-key-facts
  "Typed database facts for one Malli map-entry key."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli's form/AST boundary accepts both compiled Schema objects and raw forms; their embedded literals may have arbitrary Clojure shapes.", :gen/elements [nil false 0 "" :k [] {}]}]] :map]}
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
  [form comparison projection references seen]
  (let [shape-fingerprint (form-fingerprint form projection references)
        lookup [:seon.schema.shape/fingerprint shape-fingerprint]
        {shape-type :seon.schema.shape/type
         properties :seon.schema.shape/properties
         children :seon.schema.shape/children
         schema-children? :seon.schema.shape/schema-children?}
        (form-parts form)]
    (if (contains? seen shape-fingerprint)
      [lookup seen]
      (let [seen (conj seen shape-fingerprint)
            encode #(encode-form %1 comparison projection references %2)
            row (cond-> {:seon.schema.shape/fingerprint shape-fingerprint
                         :seon.schema.shape/normalization-revision
                         normalization-revision
                         :seon.schema.shape/form (pr-str (if (vector? form) [(first form)] form))
                         :seon.schema.shape/comparison comparison
                         :seon.schema.shape/type shape-type}
                  (seq properties)
                  (assoc :seon.schema.shape/properties (pr-str properties)))]
        (cond
          (local-registry-form? form)
          [(-> row
               (assoc :seon.schema.shape/form (pr-str form))
               (dissoc :seon.schema.shape/properties)) seen]

          (and (contains? #{:map :catn :altn :orn} shape-type) (seq children))
          (let [[entries seen]
                (ordered-rows
                 (fn [order entry seen]
                   (entry-row shape-fingerprint order entry seen encode))
                 seen children)]
            [(assoc row :seon.schema.shape/entries entries) seen])

          (seq children)
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
   [:function [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli's form/AST boundary accepts both compiled Schema objects and raw forms; their embedded literals may have arbitrary Clojure shapes.", :gen/elements [nil false 0 "" :k [] {}]}]] :map] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli's form/AST boundary accepts both compiled Schema objects and raw forms; their embedded literals may have arbitrary Clojure shapes.", :gen/elements [nil false 0 "" :k [] {}]}] :map] :map] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Malli's form/AST boundary accepts both compiled Schema objects and raw forms; their embedded literals may have arbitrary Clojure shapes.", :gen/elements [nil false 0 "" :k [] {}]}] :map :map] :map]]}
  ([compiled]
   (shape-row compiled {} {}))
  ([compiled forms]
   (shape-row compiled forms {}))
  ([compiled forms predicate-functions]
   (let [{form :seon.schema.shape/form
          comparison :seon.schema.shape/comparison
          projection ::projection
          references ::reference-fingerprints}
         (normalized-form compiled forms predicate-functions)]
     (first (encode-form form comparison projection references #{})))))

(defn- read-form
  "Reconstruct an authored node with a resolver for its stored child refs."
  {:malli/schema [:=> [:cat :map [:fn clojure.core/ifn?]] :seon.schema/value]}
  [row resolve-row]
   (let [form (edn/read-string (:seon.schema.shape/form row))]
     (if (or (not (vector? form)) (local-registry-form? form))
       form
       (into (cond-> form
               (:seon.schema.shape/properties row)
               (conj (edn/read-string (:seon.schema.shape/properties row))))
             (if (contains? #{:map :catn :altn :orn} (first form))
               (map (fn [entry]
                      (cond-> [(edn/read-string (:seon.schema.map-entry/key-edn entry))]
                        (:seon.schema.shape.entry/properties entry)
                        (conj (edn/read-string (:seon.schema.shape.entry/properties entry)))
                        true (conj (read-form (resolve-row (:seon.schema.shape.entry/schema entry))
                                             resolve-row))))
                    (sort-by :seon.schema.shape.entry/order (:seon.schema.shape/entries row)))
               (map (fn [child]
                      (if-let [value (:seon.schema.shape.child/value-edn child)]
                        (edn/read-string value)
                        (read-form (resolve-row (:seon.schema.shape.child/schema child)) resolve-row)))
                    (sort-by :seon.schema.shape.child/order (:seon.schema.shape/children row))))))))

(defn row-form
  "Reconstruct an authored form from a complete nested shape row."
  {:malli/schema [:=> [:cat :map] :seon.schema/value]}
  [row]
  (let [rows (into {} (keep (fn [value]
                             (when (and (map? value) (:seon.schema.shape/form value))
                               [(:seon.schema.shape/fingerprint value) value])))
                   (tree-seq coll? seq row))]
    (read-form row #(if (vector? %) (get rows (second %)) %))))

(defn database-form
  "Read an authored shape using complete component edges from one database.
  No wildcard pull or default cardinality limit may truncate a contract."
  {:malli/schema [:=> [:cat :seon.db/database-value :int] :seon.schema/value]}
  [database entity]
  (letfn [(read-row [entity]
            (let [entity (if (map? entity) (:db/id entity) entity)]
              (reduce (fn [row datom]
                        (let [attribute (:a datom) value (:v datom)]
                          (if (contains? #{:seon.schema.shape/entries :seon.schema.shape/children}
                                         attribute)
                            (update row attribute (fnil conj []) (read-row value))
                            (assoc row attribute value))))
                      {} (db/datoms database :eavt entity))))]
    (read-form (read-row entity) read-row)))

(defn compiled-in
  "Resolve an authored shape through the immutable database's compiled registry.
  Named definitions and their validators are already retained by Malli."
  {:malli/schema [:=> [:cat :seon.db/database-value :int] [:fn malli.core/schema?]]}
  [database entity]
  (let [projection (db/carried-projection database)
        form (database-form database entity)
        registry (:seon.schema.projection/registry projection)]
    (if (qualified-keyword? form)
      (m/schema (mr/schema registry form))
      (m/schema (schema/compilable-form form (schema/predicate-functions-in projection))
                (:seon.schema.projection/compile-options projection)))))

(defn- row-signature
  "Compare stored nodes by child identity, independent of nested-map/upsert syntax."
  {:malli/schema [:=> [:cat :map] :map]}
  [row]
  (let [reference (fn [value]
                    (if (vector? value) (second value)
                        (:seon.schema.shape/fingerprint value)))
        edges (fn [rows schema-key order-key]
                (mapv #(cond-> (dissoc % :db/id)
                         (get % schema-key) (update schema-key reference))
                      (sort-by order-key rows)))]
    (cond-> (select-keys row [:seon.schema.shape/form :seon.schema.shape/properties
                             :seon.schema.shape/type :seon.schema.shape/comparison
                             :seon.schema.shape/normalization-revision])
      (seq (:seon.schema.shape/entries row))
      (assoc :seon.schema.shape/entries
             (edges (:seon.schema.shape/entries row)
                    :seon.schema.shape.entry/schema :seon.schema.shape.entry/order))
      (seq (:seon.schema.shape/children row))
      (assoc :seon.schema.shape/children
             (edges (:seon.schema.shape/children row)
                    :seon.schema.shape.child/schema :seon.schema.shape.child/order)))))

(defn assert-consistent!
  "Refuse fingerprint reuse for distinct normalized forms."
  {:malli/schema [:=> [:cat [:sequential :map]] [:sequential :map]]}
  [rows]
  (doseq [[shape-fingerprint matching]
          (group-by :seon.schema.shape/fingerprint rows)
          :let [forms (into #{} (map row-signature) matching)]
          :when (> (count forms) 1)]
    (throw
     (ex-info "A schema fingerprint identifies distinct normalized forms."
              {:seon.schema.shape/fingerprint shape-fingerprint
               :seon.schema.shape/forms forms :seon.schema.shape/fingerprint-collision true})))
  rows)
