(ns seon.merge
  "Generic Malli-driven O(|Delta_ours| + |Delta_theirs|) 3-way merge over ALL Datahike entities:
   - Zero hardcoded entity tables: reads `:identity-attrs`, `:many-attrs`, and `:skipped-attrs`
     directly from `seon.schema/registry-for-db` (including any runtime-registered schemas!).
   - Automatically carries over any new Malli-derived Datahike `:db/ident` declarations created on
     `theirs-branch` (`:schema-tx`) before merging the entity datoms!
   - Performs attribute-level 3-way merge (`:db.cardinality/one` value 3-way + `:db.cardinality/many`
     conflict-free set union/retraction)."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.schema :as schema]))

(defn- pull-clean-entity [db id-attr id-val many-attrs skipped-attrs]
  (when (contains? (:schema db) id-attr)
    (when-let [dt (first (d/datoms db :avet id-attr id-val))]
      (let [raw (d/pull db '[*] (:e dt))]
        (reduce-kv
         (fn [acc k v]
           (if (or (contains? skipped-attrs k) (nil? v))
             acc
             (assoc acc k (if (contains? many-attrs k) (set v) v))))
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

(defn three-way
  {:malli/schema [:=> [:cat :seon.host/conn :map :seon.store/branch] :seon.merge/plan]}
  [ours-conn _cfg theirs-branch]
  (let [ours-branch (get-in @ours-conn [:config :branch] :main)
        ours-db @ours-conn
        store (:store ours-db)
        ours-cid (d/commit-id ours-db)
        theirs-db (d/branch-as-db store theirs-branch)
        theirs-cid (d/commit-id theirs-db)
        base-cid (d/merge-base ours-conn ours-branch theirs-branch)]
    (when-not base-cid
      (throw (ex-info (str "No common merge-base between " ours-branch " and " theirs-branch)
                      {:ours ours-branch :theirs theirs-branch})))
    (let [base-db (d/commit-as-db ours-conn base-cid)
          reg-union (schema/registry-for-db ours-db (schema/db-schema-forms theirs-db))
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
          {:keys [tx-data touched-paths entity-diffs conflicts]}
          (reduce
           (fn [acc [id-attr id-val :as lref]]
             (let [b-val (pull-clean-entity base-db id-attr id-val many-attrs skipped-attrs)
                   t-val (pull-clean-entity theirs-db id-attr id-val many-attrs skipped-attrs)
                   o-val (if (contains? ours-keys lref)
                           (pull-clean-entity ours-db id-attr id-val many-attrs skipped-attrs)
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
           (sort-by pr-str theirs-keys))]
      {:ok? (empty? conflicts)
       :base-commit base-cid
       :ours-commit ours-cid
       :theirs-commit theirs-cid
       :schema-tx new-dh-decls
       :tx-data tx-data
       :touched-paths touched-paths
       :entity-diffs entity-diffs
       :conflicts conflicts})))
