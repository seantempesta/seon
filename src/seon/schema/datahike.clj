(ns seon.schema.datahike
  "Automatic derivation of Datahike attribute declarations from Malli schemas (ported & simplified
   from `seon.schema.datahike` on `main`):
   - The Malli registry is the ONE shape authority; Datahike schema (`:db/ident`, `:db/valueType`,
     `:db/cardinality`, `:db/unique`, `:db/index`, `:db/noHistory`, `:db/contentId`) is a pure
     derivation over the registry. Zero duplicate attribute tables!
   - Conventions:
     * `[:and {:seon.db/identity true :seon.db/content-id true} T]` -> `:db/unique :db.unique/identity`, `:db/contentId true`
     * `{:seon.db/unique true}` -> `:db/unique :db.unique/value`
     * `{:seon.db/index true}`, `{:seon.db/component true}`, `{:seon.db/no-history? true}` -> as named
     * `:seon.store/ref` -> `:db.type/ref`
     * `[:set T]` -> `:db.cardinality/many`
     * Vectors, maps, tuples, `:any`, and mixed `:or` unions -> `:db.type/data` (native FASL data in Datahike!)"
  (:require [malli.core :as m]
            [malli.registry :as mr]))

(def type->datahike
  {:string :db.type/string
   :re :db.type/string
   :int :db.type/long
   :double :db.type/double
   :float :db.type/float
   :keyword :db.type/keyword
   :qualified-keyword :db.type/keyword
   :boolean :db.type/boolean
   :uuid :db.type/uuid
   :symbol :db.type/symbol
   :qualified-symbol :db.type/symbol
   :inst :db.type/instant
   'inst? :db.type/instant
   'pos-int? :db.type/long
   'nat-int? :db.type/long
   'int? :db.type/long
   'bytes? :db.type/bytes})

(def ^:private storage-properties
  #{:seon.db/identity :seon.db/unique :seon.db/index :seon.db/component
    :seon.db/no-history? :seon.db/content-id :seon.db/storable})

(def ^:private data-types
  #{:vector :sequential :tuple :map :map-of :any})

(defn- literal-type [x]
  (cond
    (string? x) :db.type/string
    (boolean? x) :db.type/boolean
    (keyword? x) :db.type/keyword
    (symbol? x) :db.type/symbol
    (uuid? x) :db.type/uuid
    (inst? x) :db.type/instant
    (int? x) :db.type/long
    (double? x) :db.type/double))

(defn storage
  "Derives `{:type :card :props}` (or `{:invalid [reason form]}`) from compiled Malli `node`."
  [node active]
  (let [t (m/type node)
        props (m/properties node)]
    (cond
      (m/-ref-schema? node)
      (let [r (m/-ref node)]
        (cond
          (= :seon.store/ref r) {:type :db.type/ref :card :one :props props}
          (and r (contains? active r)) {:invalid [::cyclic (m/form node)]}
          :else
          (let [target (storage (m/deref node) (cond-> active r (conj r)))]
            (assoc target :props (merge (dissoc (:props target)
                                                :seon.db/identity :seon.db/unique :seon.db/content-id)
                                        props)))))

      (= :and t)
      (let [child (storage (first (m/children node)) active)]
        (assoc child :props (merge (:props child) props)))

      (= :set t)
      (let [child (storage (first (m/children node)) active)]
        (cond
          (:invalid child) child
          (or (= :many (:card child)) (= :db.type/data (:type child)))
          {:invalid [::nested-collection (m/form node)]}
          :else (assoc child :card :many :props (merge (:props child) props))))

      (contains? data-types t)
      {:type :db.type/data :card :one :props props}

      (= :or t)
      (let [arms (map #(storage % active) (m/children node))]
        (cond
          (some :invalid arms) (first (filter :invalid arms))
          (and (= :one (:card (first arms))) (apply = (map (juxt :type :card) arms)))
          (assoc (first arms) :props props)
          :else {:type :db.type/data :card :one :props props}))

      (= :maybe t)
      {:invalid [::nilable (m/form node)]}

      (= := t)
      (if-let [vt (literal-type (first (m/children node)))]
        {:type vt :card :one :props props}
        {:invalid [::literal (m/form node)]})

      (= :enum t)
      (if (every? keyword? (m/children node))
        {:type :db.type/keyword :card :one :props props}
        {:invalid [::enum (m/form node)]})

      :else
      (if-let [vt (get type->datahike t)]
        {:type vt :card :one :props props}
        {:invalid [::unstorable (m/form node)]}))))

(defn storable? [registry attr]
  (boolean
   (when-let [root (mr/schema registry attr)]
     (let [{:keys [invalid type]} (storage root #{attr})]
       (and (nil? invalid) (some? type))))))

(defn declaration
  "Returns the Datahike attribute map for `attr` in `registry`, or throws `ex-info` if unstorable."
  [registry attr]
  (let [root (mr/schema registry attr)
        {:keys [type card props invalid]} (if root (storage root #{attr}) {:invalid [::absent attr]})]
    (when invalid
      (throw (ex-info (str "Cannot derive Datahike attribute for " attr ": " (pr-str invalid))
                      {:error :seon.schema/unstorable :attribute attr :detail invalid})))
    (cond-> {:db/ident attr
             :db/valueType type
             :db/cardinality (if (= :many card) :db.cardinality/many :db.cardinality/one)}
      (:seon.db/identity props)    (assoc :db/unique :db.unique/identity)
      (:seon.db/unique props)      (assoc :db/unique :db.unique/value)
      (:seon.db/index props)       (assoc :db/index true)
      (:seon.db/component props)   (assoc :db/isComponent true)
      (:seon.db/no-history? props) (assoc :db/noHistory true)
      (:seon.db/content-id props)  (assoc :db/contentId true))))

(defn- entity-maps [compiled active]
  (let [r (when (m/-ref-schema? compiled) (m/-ref compiled))]
    (if (and r (contains? active r))
      []
      (let [active' (cond-> active r (conj r))]
        (cond
          (m/-ref-schema? compiled) (entity-maps (m/deref compiled) active')
          (= :map (m/type compiled)) [compiled]
          (= :and (m/type compiled)) (into [] (mapcat #(entity-maps % active')) (m/children compiled))
          :else [])))))

(defn- declared-members [registry compiled]
  (let [maps (entity-maps compiled #{})]
    (if (some #(true? (:seon.db/attributes (m/properties %))) maps)
      (into []
            (comp (mapcat m/children)
                  (filter (fn [[k props _]]
                            (and (qualified-keyword? k)
                                 (or (not (:optional props)) (storable? registry k)))))
                  (map first))
            maps)
      [])))

(defn attribute-keys
  "Returns sorted vector of all qualified keywords in `registry` that declare Datahike attributes
   (either members of a `{:seon.db/attributes true}` entity map or carrying `:seon.db/*` properties)."
  [registry]
  (let [all-schemas (filter (fn [[k s]] (and (qualified-keyword? k) (m/schema? s)))
                            (mr/schemas registry))]
    (vec (sort-by str
                  (into #{}
                        (mapcat (fn [[k s]]
                                  (let [props (:props (storage s #{k}))]
                                    (concat (declared-members registry s)
                                            (when (some storage-properties (keys props)) [k])))))
                        all-schemas)))))

(defn declarations
  "Derives the full vector of Datahike attribute schema maps from `registry`."
  [registry]
  (mapv #(declaration registry %) (attribute-keys registry)))

(defn merge-metadata
  "Derives `{:identity-attrs [...] :many-attrs #{...} :skipped-attrs #{...}}` directly from `registry`!"
  [registry]
  (reduce
   (fn [acc k]
     (let [root (mr/schema registry k)
           {:keys [card props]} (storage root #{k})]
       (cond-> acc
         (and (:seon.db/identity props) (not (:seon.merge/skip props)))
         (update :identity-attrs conj k)

         (= :many card)
         (update :many-attrs conj k)

         (:seon.merge/skip props)
         (update :skipped-attrs conj k))))
   {:identity-attrs []
    :many-attrs #{}
    :skipped-attrs #{:db/id :db/txInstant}}
   (attribute-keys registry)))
