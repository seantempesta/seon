(ns seon.merge
  "Generic Malli-driven O(|Delta_ours| + |Delta_theirs|) 3-way merge over ALL Datahike entities:
   - Zero hardcoded entity tables: reads `:identity-attrs`, `:many-attrs`, and `:skipped-attrs`
     directly from `seon.schema/registry-for-db` (including any runtime-registered schemas!).
   - Automatically carries over any new Malli-derived Datahike `:db/ident` declarations created on
     `theirs-branch` (`:schema-tx`) before merging the entity datoms!
   - Performs attribute-level 3-way merge (`:db.cardinality/one` value 3-way + `:db.cardinality/many`
     conflict-free set union/retraction)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.schema :as schema]))

(defn- eid->lookup-ref [db eid identity-attrs]
  (or (some (fn [id-attr]
              (when (contains? (:schema db) id-attr)
                (when-let [v (:v (first (d/datoms db :eavt eid id-attr)))]
                  [id-attr v])))
            identity-attrs)
      {:db/id eid}))

(defn- normalize-ref-val [db k v identity-attrs]
  (if (= :db.type/ref (get-in (:schema db) [k :db/valueType]))
    (cond
      (and (map? v) (:db/id v)) (eid->lookup-ref db (:db/id v) identity-attrs)
      (coll? v) (mapv #(if (and (map? %) (:db/id %)) (eid->lookup-ref db (:db/id %) identity-attrs) %) v)
      :else v)
    v))

(defn- pull-clean-entity [db id-attr id-val many-attrs skipped-attrs identity-attrs]
  (when (contains? (:schema db) id-attr)
    (when-let [dt (first (d/datoms db :avet id-attr id-val))]
      (let [raw (d/pull db '[*] (:e dt))]
        (reduce-kv
         (fn [acc k v]
           (if (or (contains? skipped-attrs k) (nil? v))
             acc
             (let [nv (normalize-ref-val db k v identity-attrs)]
               (assoc acc k (if (contains? many-attrs k) (set nv) nv)))))
         {}
         raw)))))

(defn- touched-entities-in-changes [before-db after-db changes-datoms identity-attrs]
  (let [eids (into #{} (map :e) changes-datoms)]
    (reduce
     (fn [acc eid]
       (or (some (fn [id-attr]
                   (when-let [v (or (when (contains? (:schema after-db) id-attr)
                                      (:v (first (d/datoms after-db :eavt eid id-attr))))
                                    (when (contains? (:schema before-db) id-attr)
                                      (:v (first (d/datoms before-db :eavt eid id-attr)))))]
                     (conj acc [id-attr v])))
                 identity-attrs)
           acc))
     #{}
     eids)))

(defn entity->disk-path [ours-db theirs-db id-attr id-val ent]
  (case id-attr
    :seon.code.file/path (:seon.code.file/path ent id-val)
    :seon.code.doc/id    (or (:seon.code.doc/path ent)
                             (:seon.code.doc/path (d/pull theirs-db '[:seon.code.doc/path] [:seon.code.doc/id id-val]))
                             (:seon.code.doc/path (d/pull ours-db '[:seon.code.doc/path] [:seon.code.doc/id id-val])))
    :seon.schema/key     (schema/schema-owner-path id-val)
    :seon.config/key     (when (= :seon.config/root id-val) "resources/seon/config/default.edn")
    :seon.task/id        (when (qualified-keyword? id-val)
                           (str "resources/seon/tasks/" (namespace id-val) ".edn"))
    :seon.agent/id       (or (:seon.agent/seed-path ent)
                             (when (contains? (:schema theirs-db) :seon.agent/seed-path)
                               (:seon.agent/seed-path (d/pull theirs-db '[:seon.agent/seed-path] [:seon.agent/id id-val])))
                             (when (contains? (:schema ours-db) :seon.agent/seed-path)
                               (:seon.agent/seed-path (d/pull ours-db '[:seon.agent/seed-path] [:seon.agent/id id-val]))))
    :seon.agent.eval/id  (let [aid (or (:seon.agent.eval/agent ent)
                                       (:seon.agent.eval/agent (d/pull theirs-db '[:seon.agent.eval/agent] [:seon.agent.eval/id id-val]))
                                       (:seon.agent.eval/agent (d/pull ours-db '[:seon.agent.eval/agent] [:seon.agent.eval/id id-val])))]
                           (when (and aid (= "example" (namespace aid)))
                             (or (when (contains? (:schema theirs-db) :seon.agent/seed-path)
                                   (:seon.agent/seed-path (d/pull theirs-db '[:seon.agent/seed-path] [:seon.agent/id aid])))
                                 (when (contains? (:schema ours-db) :seon.agent/seed-path)
                                   (:seon.agent/seed-path (d/pull ours-db '[:seon.agent/seed-path] [:seon.agent/id aid]))))))
    :seon.code.ns/name   (or (:seon.code.ns/path ent)
                             (:seon.code.ns/path (d/pull theirs-db '[:seon.code.ns/path] [:seon.code.ns/name id-val]))
                             (:seon.code.ns/path (d/pull ours-db '[:seon.code.ns/path] [:seon.code.ns/name id-val])))
    (:seon.code.form/key :seon.code.var/name)
    (let [nsym (or (:seon.code.form/ns ent) (:seon.code.var/ns ent)
                   (when (= :seon.code.form/key id-attr)
                     (or (:seon.code.form/ns (d/pull theirs-db '[:seon.code.form/ns] [:seon.code.form/key id-val]))
                         (:seon.code.form/ns (d/pull ours-db '[:seon.code.form/ns] [:seon.code.form/key id-val]))))
                   (when (= :seon.code.var/name id-attr)
                     (or (:seon.code.var/ns (d/pull theirs-db '[:seon.code.var/ns] [:seon.code.var/name id-val]))
                         (:seon.code.var/ns (d/pull ours-db '[:seon.code.var/ns] [:seon.code.var/name id-val])))))]
      (when nsym
        (or (:seon.code.ns/path (d/pull theirs-db '[:seon.code.ns/path] [:seon.code.ns/name nsym]))
            (:seon.code.ns/path (d/pull ours-db '[:seon.code.ns/path] [:seon.code.ns/name nsym])))))
    nil))

(defn- merge-one-entity [[id-attr id-val :as lookup-ref] b-map o-map t-map many-attrs]
  (cond
    (or (= t-map b-map) (= t-map o-map))
    {:ops [] :conflicts []}

    (nil? t-map)
    (if (= o-map b-map)
      {:ops [[:db/retractEntity lookup-ref]] :conflicts []}
      {:ops [] :conflicts [{:entity lookup-ref :reason :deleted-vs-modified :base b-map :ours o-map :theirs nil}]})

    (and (nil? b-map) (nil? o-map))
    {:ops [t-map] :conflicts []}

    :else
    (let [all-ks (disj (set/union (set (keys b-map)) (set (keys o-map)) (set (keys t-map))) id-attr)]
      (reduce
       (fn [acc k]
         (if (contains? many-attrs k)
           (let [bs (get b-map k #{})
                 os (get o-map k #{})
                 ts (get t-map k #{})
                 to-add (set/difference (set/difference ts bs) os)
                 to-rem (set/intersection (set/difference bs ts) os)
                 add-ops (mapv (fn [v] [:db/add lookup-ref k v]) (sort-by pr-str to-add))
                 rem-ops (mapv (fn [v] [:db/retract lookup-ref k v]) (sort-by pr-str to-rem))]
             (update acc :ops into (concat rem-ops add-ops)))
           (let [bv (get b-map k ::absent)
                 ov (get o-map k ::absent)
                 tv (get t-map k ::absent)]
             (cond
               (or (= tv bv) (= tv ov))
               acc

               (= ov bv)
               (if (= tv ::absent)
                 (update acc :ops conj [:db/retract lookup-ref k bv])
                 (update acc :ops conj {id-attr id-val k tv}))

               :else
               (update acc :conflicts conj
                       {:entity lookup-ref
                        :attribute k
                        :base (when-not (= bv ::absent) bv)
                        :ours (when-not (= ov ::absent) ov)
                        :theirs (when-not (= tv ::absent) tv)})))))
       {:ops [] :conflicts []}
       (sort all-ks)))))

(defn- bind-new-ref-tempids
  "Rewrites lookup-refs pointing to entities *created in the same `tx-data`* into deterministic
   string tempids so Datahike resolves new parent->child ref graphs atomically in one tx."
  [ours-db tx-data identity-attrs]
  (let [new-lrefs
        (into {}
              (keep (fn [op]
                      (when (map? op)
                        (some (fn [ida]
                                (when-let [v (get op ida)]
                                  (when-not (and (contains? (:schema ours-db) ida)
                                                 (first (d/datoms ours-db :avet ida v)))
                                    [[ida v] (str "new/" (namespace ida) "/" (name ida) "/" v)])))
                              identity-attrs))))
              tx-data)]
    (if (empty? new-lrefs)
      tx-data
      (let [rewrite-v (fn rw [v]
                        (cond
                          (and (vector? v) (= 2 (count v)) (contains? new-lrefs v))
                          (get new-lrefs v)
                          (set? v) (into #{} (map rw) v)
                          (vector? v) (mapv rw v)
                          :else v))]
        (mapv (fn [op]
                (cond
                  (map? op)
                  (let [self-tid (some (fn [ida]
                                         (when-let [v (get op ida)]
                                           (get new-lrefs [ida v])))
                                       identity-attrs)
                        m' (reduce-kv (fn [a k v] (assoc a k (rewrite-v v))) {} op)]
                    (cond-> m' self-tid (assoc :db/id self-tid)))

                  (vector? op)
                  (let [[cmd e a v] op]
                    (if (= cmd :db/retractEntity)
                      [cmd (rewrite-v e)]
                      [cmd (rewrite-v e) a (rewrite-v v)]))

                  :else op))
              tx-data)))))

(defn three-way
  {:malli/schema [:=> [:cat :seon.host/conn :map :seon.store/branch] :seon.merge/plan]}
  [ours-conn _cfg theirs-branch]
  (let [ours-branch (get-in @ours-conn [:config :branch] :main)
        store (:store @ours-conn)
        ours-db (or (try (d/branch-as-db store ours-branch) (catch Throwable _ nil)) @ours-conn)
        ours-cid (d/commit-id ours-db)
        theirs-db (d/branch-as-db store theirs-branch)
        theirs-cid (d/commit-id theirs-db)
        base-cid (d/merge-base ours-conn ours-branch theirs-branch)]
    (when-not base-cid
      (throw (ex-info (str "No common merge-base between " ours-branch " and " theirs-branch)
                      {:ours ours-branch :theirs theirs-branch})))
    (let [base-db (d/commit-as-db ours-conn base-cid)
          theirs-schemas (schema/db-schema-forms theirs-db)
          reg-union (schema/registry-for-db ours-db theirs-schemas)
          {:keys [identity-attrs many-attrs skipped-attrs]} (:merge-meta reg-union)
          new-dh-decls (->> (:declarations reg-union)
                            (remove #(contains? (:schema ours-db) (:db/ident %)))
                            vec)
          ours-ds (if (= base-cid ours-cid) [] (d/changes ours-conn base-cid ours-cid))
          theirs-ds (if (= base-cid theirs-cid) [] (d/changes ours-conn base-cid theirs-cid))
          theirs-keys (touched-entities-in-changes base-db theirs-db theirs-ds identity-attrs)
          ours-keys (if (empty? ours-ds)
                      #{}
                      (touched-entities-in-changes base-db ours-db ours-ds identity-attrs))
          changed-schema-forms (into {}
                                     (keep (fn [[id-attr id-val]]
                                             (when (= :seon.schema/key id-attr)
                                               (when-let [f (get theirs-schemas id-val)]
                                                 [id-val f]))))
                                     theirs-keys)
          touched-nss (into #{}
                            (keep (fn [[id-attr id-val]]
                                    (case id-attr
                                      :seon.code.ns/name id-val
                                      :seon.code.form/key
                                      (or (:seon.code.form/ns (d/pull theirs-db '[:seon.code.form/ns] [:seon.code.form/key id-val]))
                                          (:seon.code.form/ns (d/pull ours-db '[:seon.code.form/ns] [:seon.code.form/key id-val])))
                                      :seon.code.var/name
                                      (or (:seon.code.var/ns (d/pull theirs-db '[:seon.code.var/ns] [:seon.code.var/name id-val]))
                                          (:seon.code.var/ns (d/pull ours-db '[:seon.code.var/ns] [:seon.code.var/name id-val])))
                                      nil)))
                            theirs-keys)
          health-blocks (health/caused-by-branch ours-db theirs-db changed-schema-forms (vec touched-nss))
          ordered-theirs-keys (sort-by (fn [[id-attr id-val]]
                                         [(if (= :seon.code.ns/name id-attr) 0 1)
                                          (count (str/split (str id-val) #"\."))
                                          (pr-str id-attr)
                                          (pr-str id-val)])
                                       theirs-keys)
          {:keys [tx-data touched-paths entity-diffs conflicts]}
          (reduce
           (fn [acc [id-attr id-val :as lref]]
             (let [b-val (pull-clean-entity base-db id-attr id-val many-attrs skipped-attrs identity-attrs)
                   t-val (pull-clean-entity theirs-db id-attr id-val many-attrs skipped-attrs identity-attrs)
                   o-val (if (contains? ours-keys lref)
                           (pull-clean-entity ours-db id-attr id-val many-attrs skipped-attrs identity-attrs)
                           b-val)
                   {:keys [ops conflicts]} (merge-one-entity lref b-val o-val t-val many-attrs)
                   p (when (or (seq ops) (seq conflicts))
                       (entity->disk-path ours-db theirs-db id-attr id-val (or t-val b-val)))]
               (cond-> acc
                 (seq ops) (update :tx-data into ops)
                 (or (seq ops) (seq conflicts))
                 (update :entity-diffs conj {:entity lref
                                             :status (cond (seq conflicts) :conflict
                                                           (nil? t-val) :deleted
                                                           (nil? b-val) :added
                                                           :else :modified)
                                             :path p})
                 p (update :touched-paths conj p)
                 (seq conflicts) (update :conflicts into conflicts))))
           {:tx-data [] :touched-paths #{} :entity-diffs [] :conflicts []}
           ordered-theirs-keys)
          all-conflicts (into conflicts
                              (map (fn [hb]
                                     {:entity [:seon.health/problem (:subject hb)]
                                      :reason (:class hb)
                                      :message (:message hb)}))
                              health-blocks)
          final-tx (bind-new-ref-tempids ours-db tx-data identity-attrs)]
      {:ok? (empty? all-conflicts)
       :base-commit base-cid
       :ours-commit ours-cid
       :theirs-commit theirs-cid
       :schema-tx new-dh-decls
       :tx-data final-tx
       :touched-paths touched-paths
       :entity-diffs entity-diffs
       :conflicts all-conflicts})))
