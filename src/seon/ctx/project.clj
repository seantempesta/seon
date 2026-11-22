(ns seon.ctx.project
  "Pure generic 2-read agent world discovery, monotonic pull-key-set union,
   Datahike entity projection pulling, zero-repetition / re-warming deltas,
   and 3-signal rank + 2-pass Upgrade Ladder budgeting for `seon.ctx/related-updates`."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.render.fit :as fit]
            [seon.schema :as schema]
            [seon.schema.index :as six]))

(defn- db-schema [db]
  (or (db-schema db) (when db (d/schema db)) {}))

(defn- reverse-agent-ref-attrs [db]
  (let [dh-sch (db-schema db)
        sch-eid (when (contains? dh-sch :seon.schema/key)
                  (:e (first (d/datoms db :avet :seon.schema/key :seon.agent/entity))))
        from-db (when (and sch-eid (contains? dh-sch :seon.schema/ref-targets))
                  (keep #(:v (first (d/datoms db :eavt (:e %) :seon.schema/key)))
                        (d/datoms db :avet :seon.schema/ref-targets sch-eid)))
        from-ix (six/reaching (schema/entity-index db) :seon.agent/entity)]
    (->> (concat from-db from-ix)
         (filter #(= :db.type/ref (get-in dh-sch [% :db/valueType])))
         distinct sort vec)))

(defn- merge-forward-datoms [main-db branch-db eid]
  (let [m-ds (when main-db (vec (d/datoms main-db :eavt eid)))
        b-ds (when (and branch-db (not= branch-db main-db)) (vec (d/datoms branch-db :eavt eid)))]
    (if (empty? b-ds)
      (or m-ds [])
      (let [m-by-a (group-by :a m-ds)
            b-by-a (group-by :a b-ds)
            all-a (sort (set/union (set (keys m-by-a)) (set (keys b-by-a))))
            max-tx (fn [ds] (reduce max 0 (map #(long (or (:tx %) 0)) ds)))]
        (vec (mapcat (fn [a]
                       (let [ms (get m-by-a a) bs (get b-by-a a)]
                         (cond
                           (empty? ms) bs
                           (empty? bs) ms
                           (>= (max-tx bs) (max-tx ms)) bs
                           :else ms)))
                     all-a))))))

(defn- resolve-lookup-eid [main-db branch-db ref]
  (when (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)))
    (let [[a v] ref]
      (or (when (and branch-db (contains? (db-schema branch-db) a))
            (:e (first (d/datoms branch-db :avet a v))))
          (when (and main-db (not= main-db branch-db) (contains? (db-schema main-db) a))
            (:e (first (d/datoms main-db :avet a v))))))))

(defn agent-world-datoms
  "Generic 2-read agent discovery (zero hardcoded pull list):
   1. Forward `:eavt` read on `agent-eid` merged across `main-db` + `branch-db` by latest `:tx`.
   2. Reverse `:avet` reads for EVERY `:db.type/ref` attr whose `:seon.schema/ref-targets` points to `:seon.agent/entity`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map]
   :cost/ticks 8000000}
  [main-db branch-db agent-id]
  (let [bdb (or branch-db main-db)
        agent-eid (or (when (contains? (db-schema bdb) :seon.agent/id)
                        (:e (first (d/datoms bdb :avet :seon.agent/id agent-id))))
                      (when (contains? (db-schema main-db) :seon.agent/id)
                        (:e (first (d/datoms main-db :avet :seon.agent/id agent-id)))))]
    (if-not agent-eid
      {:agent-eid nil :forward [] :reverse [] :reverse-attrs [] :connected {} :pinned #{}}
      (let [fwd (merge-forward-datoms main-db bdb agent-eid)
            rev-attrs (reverse-agent-ref-attrs bdb)
            rev-ds (vec (distinct
                         (mapcat (fn [a]
                                   (concat
                                    (when (contains? (db-schema main-db) a) (d/datoms main-db :avet a agent-eid))
                                    (when (and (not= bdb main-db) (contains? (db-schema bdb) a))
                                      (d/datoms bdb :avet a agent-eid))))
                                 rev-attrs)))
            dh-sch (merge (db-schema main-db) (db-schema bdb))
            pinned-eids (volatile! #{})
            fwd-max-tx (reduce max 0 (map #(long (or (:tx %) 0)) fwd))
            conn-map (volatile! {agent-eid fwd-max-tx})
            add-conn! (fn [e tx]
                        (when (integer? e)
                          (vswap! conn-map update e #(max (long (or % 0)) (long (or tx 0))))))]
        (doseq [dt fwd
                :let [a (:a dt) v (:v dt) tx (:tx dt)]]
          (cond
            (= :db.type/ref (get-in dh-sch [a :db/valueType]))
            (add-conn! v tx)

            (contains? #{:seon.agent/pinned :seon.agent/curated} a)
            (doseq [item (if (coll? v) v [v])]
              (when-let [e (resolve-lookup-eid main-db bdb item)]
                (add-conn! e tx)
                (when (= :seon.agent/pinned a) (vswap! pinned-eids conj e))))))
        (doseq [dt rev-ds] (add-conn! (:e dt) (:tx dt)))
        {:agent-eid agent-eid
         :forward fwd
         :reverse rev-ds
         :reverse-attrs rev-attrs
         :connected @conn-map
         :pinned @pinned-eids}))))

(defn merge-pull-patterns
  "Pure, commutative, associative, deterministic union of Datahike `:pull` patterns (recursively merging nested `{:ref [...]}` maps)."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]]
   :cost/ticks 500000}
  [patterns]
  (let [step (fn [{:keys [star? scalars nested other] :as acc} clause]
               (cond
                 (= '* clause) (assoc acc :star? true)
                 (keyword? clause) (update acc :scalars conj clause)
                 (map? clause)
                 (reduce-kv (fn [a k sub]
                              (if (sequential? sub)
                                (update-in a [:nested k] (fnil conj []) (vec sub))
                                a))
                            acc clause)
                 (some? clause) (update acc :other conj clause)
                 :else acc))
        {:keys [star? scalars nested other]}
        (reduce (fn [acc pat] (reduce step acc pat))
                {:star? false :scalars #{} :nested {} :other #{}}
                patterns)
        merged-nested (into {} (map (fn [[k subs]] [k (merge-pull-patterns subs)])) nested)
        nested-vec (mapv (fn [[k sub]] {k sub}) (sort-by (comp str key) merged-nested))
        scalar-vec (if star? ['*] (vec (sort (remove #(contains? merged-nested %) scalars))))
        other-vec (vec (sort-by pr-str other))]
    (vec (concat scalar-vec nested-vec other-vec))))

(defn- validate-projection-params [db fn-sym params]
  (if (or (empty? params) (nil? db) (not (qualified-symbol? fn-sym))
          (not (contains? (db-schema db) :seon.code.var/name)))
    params
    (let [ve (:e (first (d/datoms db :avet :seon.code.var/name fn-sym)))
          opt-ks (when (and ve (contains? (db-schema db) :seon.code.var/optional-input))
                   (into #{} (keep #(:v (first (d/datoms db :eavt (:v %) :seon.schema/key))))
                         (d/datoms db :eavt ve :seon.code.var/optional-input)))]
      (if (empty? opt-ks)
        params
        (let [known-forms (:forms (schema/registry-for-db db))]
          (reduce-kv (fn [acc k v]
                       (if (contains? opt-ks k)
                         (let [ok? (or (not (contains? known-forms k))
                                       (:ok? (schema/validate-against db k v)))]
                           (if ok? (assoc acc k v) acc))
                         acc))
                     {}
                     params))))))

(defn- pull-entity-for-projection [dbs eid pull-pat]
  (let [bdb (:branch dbs)
        mdb (:main dbs)
        eff-pat (if (seq pull-pat) pull-pat '[:db/id])]
    (if-not (integer? eid)
      {:data {} :tx 0}
      (let [b-ds (when bdb (d/datoms bdb :eavt eid))
            use-db (if (seq b-ds) bdb mdb)
            ds (if (seq b-ds) b-ds (when mdb (d/datoms mdb :eavt eid)))
            tx (reduce max 0 (map #(long (or (:tx %) 0)) ds))
            data (if (and use-db (seq ds)) (d/pull use-db eff-pat eid) {})]
        {:data (if (map? data) data {}) :tx tx}))))

(defn merge-projections
  "Merges multiple rule projections per entity via monotonic `merge-pull-patterns`, pulls exact Datahike maps,
   validates `:params` against `:seon.code.var/optional-input`, and attaches full provenance."
  {:malli/schema [:=> [:cat :map [:vector :map]] [:vector :map]]
   :cost/ticks 15000000}
  [dbs raw-projections]
  (let [eff-db (or (:branch dbs) (:main dbs))
        grouped (group-by :entity raw-projections)]
    (->> grouped
         (mapv (fn [[eid ps]]
                 (let [ordered (sort-by (fn [p] [(long (or (:stratum p) 50))
                                                 (long (or (:priority p) 50))
                                                 (str (:rule p))])
                                        ps)
                       merged-pull (merge-pull-patterns (keep :pull ordered))
                       win-fn (or (last (keep :fn ordered)) 'seon.render/render-entity-value)
                       win-zone (or (last (keep :zone ordered)) :workspace)
                       win-parent (last (keep :parent ordered))
                       max-pri (reduce max 50 (map #(long (or (:priority %) 50)) ordered))
                       raw-params (reduce merge {} (keep :params ordered))
                       params (validate-projection-params eff-db win-fn raw-params)
                       rules-v (vec (sort-by str (distinct (keep :rule ordered))))
                       why-v (vec (distinct (keep :why ordered)))
                       tags-s (into #{} (mapcat #(let [t (:tags %)] (if (coll? t) t (when t [t])))) ordered)
                       {:keys [data tx]} (pull-entity-for-projection dbs eid merged-pull)
                       max-tx (reduce max (long tx) (map #(long (or (:tx %) 0)) ordered))]
                   {:entity eid :data data :fn win-fn :pull merged-pull :params params
                    :priority max-pri :parent win-parent :zone win-zone
                    :rules rules-v :why why-v :tags tags-s :tx max-tx})))
         (sort-by (fn [p] [(- (long (:priority p 50))) (str (:entity p))]))
         vec)))

(defn compute-shown-delta
  "Pure zero-repetition / upgrade / re-warming delta over `already-shown` (`{eid {:keys :hash :tx}}`) and `projections`."
  {:malli/schema [:=> [:cat [:maybe :map] [:vector :map]] [:map [:projections [:vector :map]] [:shown :map]]]
   :cost/ticks 5000000}
  [already-shown projections]
  (let [shown0 (if (map? already-shown) already-shown {})
        id-key-of (fn [m] (first (filter #(and (keyword? %) (not= :db/id %) (contains? #{"id" "name" "key"} (name %)))
                                         (sort (keys m)))))]
    (reduce
     (fn [{:keys [projections shown]} p]
       (let [eid (:entity p)
             data (if (map? (:data p)) (:data p) {})
             wanted-ks (disj (set (keys data)) :db/id)
             cur-tx (long (or (:tx p) 0))
             prev (get shown0 eid)
             shown-ks (set (:keys prev))
             prev-tx (long (or (:tx prev) 0))
             new-ks (set/difference wanted-ks shown-ks)
             k-hashes (into {} (map (fn [k] [k (hash (get data k))])) wanted-ks)
             overlap-changed?
             (when prev
               (if-let [pkh (:key-hashes prev)]
                 (boolean (some (fn [k] (and (contains? pkh k) (not= (get pkh k) (get k-hashes k)))) wanted-ks))
                 (let [overlap (set/intersection wanted-ks shown-ks)]
                   (and (some? (:hash prev))
                        (= overlap shown-ks)
                        (not= (:hash prev) (hash (select-keys data overlap)))))))
             [status delta-data delta-ks]
             (cond
               (nil? prev) [:new data wanted-ks]
               (or overlap-changed? (> cur-tx prev-tx)) [:rewarmed data wanted-ks]
               (seq new-ks)
               (let [id-k (id-key-of data)
                     keep-ks (cond-> new-ks id-k (conj id-k) (contains? data :db/id) (conj :db/id))]
                 [:upgraded (select-keys data keep-ks) new-ks])
               :else [:unchanged {} #{}])
             p' (assoc p :status status :delta-data delta-data :delta-keys delta-ks)
             next-entry (if (= :unchanged status)
                          prev
                          (let [all-ks (if (= :rewarmed status) wanted-ks (set/union shown-ks wanted-ks))
                                merged-kh (if (= :rewarmed status) k-hashes (merge (:key-hashes prev) k-hashes))]
                            {:keys all-ks
                             :hash (hash (dissoc data :db/id))
                             :key-hashes merged-kh
                             :tx (max prev-tx cur-tx)}))]
         {:projections (conj projections p')
          :shown (assoc shown eid next-entry)}))
     {:projections [] :shown shown0}
     projections)))

(defn budget-and-order-projections
  "Ranks projections with `seon.render.fit/rank-candidates` and enforces token or viewport/slot
   budgets via `seon.render.fit/upgrade-ladder`. Preserves 2-arg `[projs budget]` callers while
   accepting optional `fit-opts` (`:strategy`, `:target`, `:viewport-w`, `:viewport-h`,
   `:sidebar-open?`, `:slot`, `:weights`, `:already-shown`, `:max-items`), tracking
   `:chosen-fn` and `:candidates` for `(user/context-trace)`."
  {:malli/schema [:=> [:cat [:vector :map] :int [:? [:maybe :map]]]
                  [:map [:selected [:vector :map]] [:dropped [:vector :map]] [:tokens :int]]]
   :cost/ticks 15000000}
  ([projs budget] (budget-and-order-projections projs budget nil))
  ([projs budget fit-opts]
   (let [opts (if (map? fit-opts) fit-opts {})
         eff-budget (max 0 (long budget))
         with-toks (mapv (fn [p]
                           (let [cands (:candidates p)]
                             (if (and (sequential? cands) (seq cands) (every? map? cands))
                               p
                               (let [tok (if (= :unchanged (:status p))
                                           0
                                           (if (integer? (:tokens p))
                                             (max 0 (long (:tokens p)))
                                             (max 1 (quot (count (pr-str (:delta-data p))) 4))))]
                                 (assoc p :tokens tok)))))
                         projs)
         ranked (fit/rank-candidates with-toks (select-keys opts [:weights]))
         ladder-opts (merge {:target :wire
                             :strategy :breadth-first
                             :token-budget eff-budget}
                            (select-keys opts [:strategy :target :viewport-w :viewport-h
                                               :sidebar-open? :slot :avail-w :area-budget
                                               :max-items :already-shown :token-budget]))
         res (fit/upgrade-ladder ranked ladder-opts)]
     {:selected (:selected res)
      :dropped (:dropped res)
      :tokens (long (:tokens res 0))
      :area (long (:area res 0))
      :target (:target res)
      :strategy (:strategy res)
      :shown (:shown res)})))
