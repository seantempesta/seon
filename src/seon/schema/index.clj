(ns seon.schema.index
  "Pure Schema Entity-Index & Multi-Facet Fingerprinting engine (ported & generalized from `jolt/data:src/seon/db.clj`):
   - Derives an immutable entity/facet index once per compiled Malli registry (`build-entity-index`).
   - Supports multi-facet entities (`facets-of`: an entity map can simultaneously satisfy multiple schemas!).
   - Resolves single most-specific schema for rendering (`most-specific`), inheritance (`extends?`, `parents-of`),
     ref targets (`targets` from `{:seon.db/to [...]}`), reverse-ref discovery (`reaching`), and nearest
     inherited schema properties (`schema-property` for `:seon.render/hiccup`, `:seon.ctx/pattern`, etc.)."
  (:require [clojure.set :as set]))

(defn form-props
  "Returns the Malli properties map attached to `form` (or `{}` if none)."
  {:malli/schema [:=> [:cat :any] :map]
   :cost/ticks 5000}
  [form]
  (if (and (vector? form) (>= (count form) 2) (map? (second form)))
    (second form)
    {}))

(defn parents-in
  "Returns direct parent schema keys extended by `k` via `[:and parent ...]`, `[:merge parent ...]`,
   or direct alias keyword in `forms`."
  {:malli/schema [:=> [:cat :map :any] [:vector :qualified-keyword]]
   :cost/ticks 50000}
  [forms k]
  (let [f (get forms k)]
    (cond
      (qualified-keyword? f) (if (contains? forms f) [f] [])
      (and (vector? f) (contains? #{:and :merge} (first f)))
      (vec (distinct (filter #(and (qualified-keyword? %) (contains? forms %)) (rest f))))
      :else [])))

(defn own-entries
  "Returns the `[:key props? schema]` map entries declared directly by `form` (excluding inherited parents)."
  {:malli/schema [:=> [:cat :any] [:vector :any]]
   :cost/ticks 50000}
  [form]
  (cond
    (and (vector? form) (= :map (first form)))
    (filterv vector? (rest form))

    (and (vector? form) (contains? #{:and :merge} (first form)))
    (vec (mapcat #(when-not (keyword? %) (own-entries %)) (rest form)))

    :else []))

(defn own-keys
  "Returns the attribute/field keys declared directly by `form`."
  {:malli/schema [:=> [:cat :any] [:vector :any]]
   :cost/ticks 50000}
  [form]
  (mapv first (own-entries form)))

(defn- own-required-keys [form]
  (vec (keep (fn [entry]
               (when (and (vector? entry) (seq entry))
                 (let [p (second entry)]
                   (when-not (and (map? p) (:optional p))
                     (first entry)))))
             (own-entries form))))

(defn- ancestor-chain [forms k]
  (loop [queue [k]
         seen #{}
         ordered []]
    (if (empty? queue)
      ordered
      (let [cur (first queue)
            rst (subvec queue 1)]
        (if (contains? seen cur)
          (recur rst seen ordered)
          (let [ps (parents-in forms cur)]
            (recur (into rst ps) (conj seen cur) (conj ordered cur))))))))

(defn parents-of
  "Direct parent schemas extended by `s` in `ix`."
  {:malli/schema [:=> [:cat :map :any] [:vector :qualified-keyword]]
   :cost/ticks 5000}
  [ix s]
  (get-in ix [:parents s] []))

(defn extends?
  "True when schema `a` is `b` or extends `b` (transitively) in `ix`."
  {:malli/schema [:=> [:cat :map :any :any] :boolean]
   :cost/ticks 5000}
  [ix a b]
  (boolean (or (= a b) (contains? (get-in ix [:ancestors a]) b))))

(defn targets
  "Returns the vector of target entity schemas declared via `{:seon.db/to [...]}` for ref attribute `attr`."
  {:malli/schema [:=> [:cat :map :any] [:vector :qualified-keyword]]
   :cost/ticks 5000}
  [ix attr]
  (get-in ix [:targets attr] []))

(defn reaching
  "Returns sorted vector of ref attributes whose `{:seon.db/to [...]}` targets schema `s` (or an ancestor)."
  {:malli/schema [:=> [:cat :map :any] [:vector :qualified-keyword]]
   :cost/ticks 5000}
  [ix s]
  (get-in ix [:reaching s] []))

(defn schema-property
  "Walks `s` and its ancestor chain in `ix` returning the nearest value of property `prop` (e.g. `:seon.render/hiccup`)."
  {:malli/schema [:=> [:cat :map :any :keyword] :any]
   :cost/ticks 50000}
  [ix s prop]
  (let [forms (:forms ix)]
    (when (and (map? forms) (qualified-keyword? s))
      (some #(get (form-props (get forms %)) prop)
            (ancestor-chain forms s)))))

(defn owners
  "Returns `{attr #{schema-key ...}}` for every attribute in `ks` known to an entity schema in `ix`."
  {:malli/schema [:=> [:cat :map :any] :map]
   :cost/ticks 100000}
  [ix ks]
  (let [es (:entity-schemas ix)]
    (if (and (map? es) (coll? ks) (not (map? ks)))
      (reduce (fn [acc k]
                (if-let [os (get es k)]
                  (assoc acc k os)
                  acc))
              {}
              ks)
      {})))

(defn build-entity-index
  "Builds the pure entity-fingerprint & ref-target index from a `{schema-key malli-form}` map once."
  {:malli/schema [:=> [:cat :map] :map]
   :cost/ticks 20000000}
  [forms]
  (let [valid-forms (into {} (filter (fn [[k _]] (qualified-keyword? k))) forms)
        entity? (fn [k] (true? (:seon.db/attributes (form-props (get valid-forms k)))))
        entity-ks (vec (sort-by str (filter entity? (keys valid-forms))))
        ;; Map each attribute -> set of owning entity schemas (supports multi-facet/shared attrs!)
        attr->owners
        (reduce (fn [acc ek]
                  (reduce (fn [m a]
                            (if (qualified-keyword? a)
                              (update m a (fnil conj #{}) ek)
                              m))
                          acc
                          (own-keys (get valid-forms ek))))
                {}
                entity-ks)
        ;; Also keep primary singular owner map for fast O(1) identity lookup
        identity-schemas
        (reduce (fn [acc [a oset]]
                  (if (:seon.db/identity (form-props (get valid-forms a)))
                    (assoc acc a (first (sort-by str oset)))
                    acc))
                {}
                attr->owners)
        schema-identities (into {} (map (fn [[a s]] [s a])) identity-schemas)
        ancestors (into {}
                        (map (fn [k] [k (set (ancestor-chain valid-forms k))]))
                        (keys valid-forms))
        parents-map (into {}
                          (keep (fn [k]
                                  (let [ps (parents-in valid-forms k)]
                                    (when (seq ps) [k ps]))))
                          (keys valid-forms))
        target-map (reduce (fn [acc [k f]]
                             (let [to (:seon.db/to (form-props f))]
                               (if (seq to)
                                 (assoc acc k (if (coll? to) (vec to) [to]))
                                 acc)))
                           {}
                           valid-forms)
        reaching-map (let [all-targets (into (set (keys parents-map))
                                             (mapcat identity (vals target-map)))]
                       (into {}
                             (keep (fn [s]
                                     (let [anc (get ancestors s #{s})
                                           refs (->> target-map
                                                     (keep (fn [[a ts]] (when (some anc ts) a)))
                                                     sort vec)]
                                       (when (seq refs) [s refs]))))
                             all-targets))
        drawn-schemas (into #{}
                            (filter (fn [k]
                                      (some #(:seon.render/hiccup (form-props (get valid-forms %)))
                                            (ancestor-chain valid-forms k))))
                            (keys valid-forms))
        required-keys (into {}
                            (map (fn [ek]
                                   [ek (vec (distinct
                                             (mapcat #(own-required-keys (get valid-forms %))
                                                     (filter entity? (ancestor-chain valid-forms ek)))))]))
                            entity-ks)
        ;; Rendered non-entity value maps (e.g. :seon.health/report, :seon.ctx/snapshot)
        value-schemas (into {}
                            (keep (fn [k]
                                    (when (and (not (entity? k))
                                               (contains? drawn-schemas k))
                                      (let [req (set (own-required-keys (get valid-forms k)))]
                                        (when (seq req) [k req])))))
                            (keys valid-forms))]
    {:forms valid-forms
     :entity-schemas attr->owners
     :identity-schemas identity-schemas
     :schema-identities schema-identities
     :targets target-map
     :parents parents-map
     :ancestors ancestors
     :reaching reaching-map
     :drawn-schemas drawn-schemas
     :required-keys required-keys
     :value-schemas value-schemas}))

(defn facets-of
  "Returns all matching schema facets of map `e` in `ix`, ranked most-specific first.
   An entity holding attributes from multiple schemas returns ALL of its facets!"
  {:malli/schema [:=> [:cat :map :any] [:vector :map]]
   :cost/ticks 500000}
  [ix e]
  (if-not (and (map? ix) (map? e) (seq e))
    []
    (let [ks (set (keys e))
          own-counts (reduce (fn [acc [_ owner-set]]
                               (reduce (fn [m s] (update m s (fnil inc 0))) acc owner-set))
                             {}
                             (owners ix (keys e)))
          id-schemas (:identity-schemas ix)
          drawn (:drawn-schemas ix)
          ;; Score entity facets: own + inherited attr hits, bonus for holding the schema's identity attr
          ent-scored
          (mapv (fn [s]
                  (let [anc (get-in ix [:ancestors s] #{s})
                        hits (reduce + 0 (map #(get own-counts % 0) anc))
                        has-id? (boolean (some #(when-let [id-a (get-in ix [:schema-identities %])] (contains? ks id-a)) anc))
                        req (get-in ix [:required-keys s] [])
                        complete? (every? ks req)]
                    {:schema s
                     :kind :entity
                     :hits hits
                     :has-identity? has-id?
                     :complete? complete?
                     :drawn? (contains? drawn s)}))
                (keys own-counts))
          ;; Also check non-entity rendered value schemas (e.g. :seon.health/report)
          val-scored
          (vec (keep (fn [[s req]]
                       (when (and (seq req) (set/subset? req ks))
                         {:schema s
                          :kind :value
                          :hits (count req)
                          :has-identity? false
                          :complete? true
                          :drawn? true}))
                     (:value-schemas ix)))
          ;; Prefer identity matches first, then higher hit count, then drawn, then complete
          all (concat ent-scored val-scored)]
      (->> all
           (sort-by (fn [{:keys [schema has-identity? hits drawn? complete?]}]
                      [(if has-identity? 0 1)
                       (- (long hits))
                       (if drawn? 0 1)
                       (if complete? 0 1)
                       (str schema)]))
           vec))))

(defn most-specific
  "Returns the single most-specific schema keyword for map `e` in `ix` (or `nil` if none).
   When two distinct entity schemas tie identically in identity, hits, drawn, and completeness,
   returns `{:ambiguous [s1 s2 ...]}`."
  {:malli/schema [:=> [:cat :map :any] :any]
   :cost/ticks 500000}
  [ix e]
  (let [fs (facets-of ix e)]
    (when (seq fs)
      (let [rank-key (fn [f] [(:has-identity? f) (:hits f) (:drawn? f) (:complete? f)])
            top (first fs)
            top-rk (rank-key top)
            tied (filterv #(= top-rk (rank-key %)) fs)]
        (if (> (count tied) 1)
          {:ambiguous (mapv :schema tied)}
          (:schema top))))))
