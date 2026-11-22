(ns seon.schema.index
  "Pure Schema Entity-Index & Multi-Facet Fingerprinting engine (ported & generalized from `jolt/data:src/seon/db.clj`):
   - Derives an immutable entity/facet index once per compiled Malli registry (`build-entity-index`).
   - Supports multi-facet entities (`facets-of`: an entity map can simultaneously satisfy multiple schemas!).
   - Resolves single most-specific schema for rendering (`most-specific`), inheritance (`extends?`, `parents-of`),
     ref targets (`targets` from `{:seon.db/to [...]}`), reverse-ref discovery (`reaching`), and nearest
     inherited schema properties (`schema-property` for `:seon.render/hiccup`, `:seon.ctx/pattern`, etc.)."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.registry :as mr]))

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

(defn schema-tempid
  "Deterministic string tempid for a `:seon.schema/entity` key `k`."
  {:malli/schema [:=> [:cat :qualified-keyword] :string],
   :cost/ticks 5000}
  [k]
  (str "schema/" (namespace k) "/" (name k)))

(defn extract-schema-ast-edges
  "Extracts `{:type :keys :optional-keys :ref-targets}` from Malli `form` via `m/ast` (fast-pathed for scalar keywords)."
  {:malli/schema [:function
                  [:=> [:cat :any] :map]
                  [:=> [:cat :any :any] :map]]
   :cost/ticks 500000}
  ([form] (extract-schema-ast-edges form nil))
  ([form registry]
   (cond
     (and (keyword? form) (nil? (namespace form)))
     {:type form :keys #{} :optional-keys #{} :ref-targets #{}}

     :else
     (let [opts (if (and registry (satisfies? mr/Registry registry)) {:registry registry} {})
           ast (when (or (keyword? form) (symbol? form) (vector? form))
                 (try (m/ast form opts)
                      (catch clojure.lang.ExceptionInfo _ nil)))
           raw-t (:type ast)
           ast-type (cond
                      (keyword? raw-t) raw-t
                      (symbol? raw-t) (keyword (namespace raw-t) (name raw-t))
                      :else nil)
           walk (fn walk [node]
                  (when (map? node)
                    (let [t (:type node)
                          to (get-in node [:properties :seon.db/to])
                          ref-ts (cond
                                   (coll? to) (into #{} (filter qualified-keyword?) to)
                                   (qualified-keyword? to) #{to}
                                   :else #{})
                          own (if (and (= :map t) (map? (:keys node)))
                                (reduce-kv
                                 (fn [acc k entry]
                                   (if (qualified-keyword? k)
                                     (if (get-in entry [:properties :optional])
                                       (update acc :optional-keys conj k)
                                       (update acc :keys conj k))
                                     acc))
                                 {:keys #{} :optional-keys #{} :ref-targets ref-ts}
                                 (:keys node))
                                {:keys #{} :optional-keys #{} :ref-targets ref-ts})]
                      (if (contains? #{:and :merge} t)
                        (reduce (fn [acc ch]
                                  (let [r (walk ch)]
                                    {:keys (into (:keys acc) (:keys r))
                                     :optional-keys (into (:optional-keys acc) (:optional-keys r))
                                     :ref-targets (into (:ref-targets acc) (:ref-targets r))}))
                                own
                                (:children node))
                        own))))
           res (or (walk ast) {:keys #{} :optional-keys #{} :ref-targets #{}})]
       (cond-> (update res :optional-keys #(into #{} (remove (:keys res)) %))
         ast-type (assoc :type ast-type))))))

(defn enrich-schema-entity-tx
  "Builds an enriched `:seon.schema/*` entity map preserving `:doc`, `:examples`,
   and populating `:seon.schema/{type,keys,optional-keys,ref-targets}`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :map :any] :map]
                  [:=> [:cat [:maybe :seon.host/db] :map :any [:maybe :map]] :map]]}
  ([db entry registry] (enrich-schema-entity-tx db entry registry nil))
  ([db entry registry batch-ctx]
   (let [k (or (:seon.schema/key entry) (:key entry))
         f (if (contains? entry :seon.schema/form) (:seon.schema/form entry) (:form entry))
         props (form-props f)
         doc (or (:seon.schema/doc entry) (:doc entry) (:seon.schema/doc props))
         exs (or (:seon.schema/examples entry) (:examples entry))
         dh-sch (when db (:schema db))
         has-attr? (fn [a] (or (nil? dh-sch) (contains? dh-sch a)))
         existing-by-k (:existing-by-k batch-ctx)
         existing-eid (if existing-by-k
                        (get existing-by-k k)
                        (when (and db (contains? dh-sch :seon.schema/key))
                          (:e (first (d/datoms db :avet :seon.schema/key k)))))
         schema->ref (:schema->ref batch-ctx)
         resolve-sk (fn [sk]
                      (or (if existing-by-k
                            (get existing-by-k sk)
                            (when (and db (contains? dh-sch :seon.schema/key))
                              (:e (first (d/datoms db :avet :seon.schema/key sk)))))
                          (get schema->ref sk)))
         {:keys [type keys optional-keys ref-targets]}
         (or (get (:ast-edges batch-ctx) k) (extract-schema-ast-edges f registry))
         req-refs (into #{} (keep resolve-sk) keys)
         opt-refs (into #{} (keep resolve-sk) optional-keys)
         tgt-refs (into #{} (keep resolve-sk) ref-targets)]
     (cond-> {:seon.schema/key k
              :seon.schema/form f}
       (and (nil? existing-eid) (contains? schema->ref k)) (assoc :db/id (get schema->ref k))
       doc (assoc :seon.schema/doc (str doc))
       (seq exs) (assoc :seon.schema/examples (vec exs))
       (and type (has-attr? :seon.schema/type)) (assoc :seon.schema/type type)
       (and (seq req-refs) (has-attr? :seon.schema/keys)) (assoc :seon.schema/keys req-refs)
       (and (seq opt-refs) (has-attr? :seon.schema/optional-keys)) (assoc :seon.schema/optional-keys opt-refs)
       (and (seq tgt-refs) (has-attr? :seon.schema/ref-targets)) (assoc :seon.schema/ref-targets tgt-refs)))))

(defn stale-schema-edge-retracts
  "Computes `:db/retract` ops for removed `:seon.schema/{keys,optional-keys,ref-targets}` refs."
  {:malli/schema
   [:=>
    [:cat [:maybe :seon.host/db] [:sequential :map]]
    [:vector :any]]}
  [db ents]
  (if-not (and db (contains? (:schema db) :seon.schema/key))
    []
    (let [attrs (filterv
                  #(contains? (:schema db) %)
                  [:seon.schema/keys
                   :seon.schema/optional-keys
                   :seon.schema/ref-targets])]
      (vec
        (mapcat
          (fn [ent]
            (when-let [eid (:e
                             (first
                               (d/datoms
                                 db
                                 :avet
                                 :seon.schema/key
                                 (:seon.schema/key ent))))]
              (mapcat
                (fn [a]
                  (let [old-s (into
                                #{}
                                (map :v)
                                (d/datoms db :eavt eid a))
                        new-s (set (get ent a #{}))]
                    (for
                      [gone (remove new-s old-s)]
                      [:db/retract eid a gone])))
                attrs)))
          ents)))))

(defn enrich-schema-entities-tx
  "Enriches a `{schema-key malli-form}` map into tx-data with cross-schema ref edges and stale retractions."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :map :any] [:vector :any]]
                  [:=> [:cat [:maybe :seon.host/db] :map :any [:maybe :map]] [:vector :any]]]}
  ([db forms-map registry] (enrich-schema-entities-tx db forms-map registry nil))
  ([db forms-map registry ast-edges]
   (let [dh-sch (when db (:schema db))
         existing-by-k (if (and db (contains? dh-sch :seon.schema/key))
                         (into {} (map (juxt :v :e)) (d/datoms db :aevt :seon.schema/key))
                         {})
         schema->ref (into {}
                           (map (fn [k] [k (or (get existing-by-k k) (schema-tempid k))]))
                           (keys forms-map))
         batch-ctx {:schema->ref schema->ref
                    :existing-by-k existing-by-k
                    :ast-edges ast-edges}
         ents (mapv (fn [[k f]]
                      (enrich-schema-entity-tx db {:seon.schema/key k :seon.schema/form f} registry batch-ctx))
                    (sort-by key forms-map))
         retracts (if (empty? existing-by-k) [] (stale-schema-edge-retracts db ents))]
     (vec (concat retracts ents)))))
