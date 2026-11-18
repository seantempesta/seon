(ns seon.merge
  "Generic schema-driven O(|Delta_ours| + |Delta_theirs|) 3-way merge over ALL Datahike entities:
   - Zero hardcoded entity-type tables: automatically discovers every `:db.unique/identity` attribute
     from `seon.store/attributes` (excluding `:merge :skip` runtime pointers).
   - Performs fine-grained attribute-level 3-way merge per entity:
       * `:db.cardinality/one` -> 3-way value merge (conflict ONLY if both branches changed the SAME
         attribute on the SAME entity to different values!).
       * `:db.cardinality/many` -> conflict-free 3-way set merge (`added = t \\ b`, `removed = b \\ t`).
   - Maps merged entities back to disk/Git paths via `entity->disk-path`."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.store :as store]))

(def ^:private skipped-attrs
  (into #{:db/id :db/txInstant}
        (keep #(when (= :skip (:merge %)) (:ident %)))
        store/attributes))

(def ^:private many-attrs
  (into #{}
        (keep #(when (= :db.cardinality/many (:card %)) (:ident %)))
        store/attributes))

(defn- pull-clean-entity
  "Pulls all non-skipped attributes of `[id-attr id-val]` on `db`, normalizing cardinality-many to sets."
  [db id-attr id-val]
  (when-let [dt (first (d/datoms db :avet id-attr id-val))]
    (let [raw (d/pull db '[*] (:e dt))]
      (reduce-kv
       (fn [acc k v]
         (if (or (contains? skipped-attrs k) (nil? v))
           acc
           (assoc acc k (if (contains? many-attrs k) (set v) v))))
       {}
       raw))))

(defn- touched-entities-in-changes
  "Extracts `{[id-attr id-val] true}` for every mergeable entity touched in `changes-datoms`."
  [before-db after-db changes-datoms]
  (let [eids (into #{} (map :e) changes-datoms)]
    (reduce
     (fn [acc eid]
       (or (some (fn [id-attr]
                   (when-let [v (or (:v (first (d/datoms after-db :eavt eid id-attr)))
                                    (:v (first (d/datoms before-db :eavt eid id-attr))))]
                     (conj acc [id-attr v])))
                 store/identity-attrs)
           acc))
     #{}
     eids)))

(defn entity->disk-path
  "Maps any file-backed entity `[id-attr id-val]` to its repository-relative file path (or `nil` for pure DB facts)."
  [ours-db theirs-db id-attr id-val ent]
  (case id-attr
    :file/path (:file/path ent id-val)
    :doc/id    (or (:doc/path ent)
                   (:doc/path (d/pull theirs-db '[:doc/path] [:doc/id id-val]))
                   (:doc/path (d/pull ours-db '[:doc/path] [:doc/id id-val])))
    :schema/key "resources/seon/schemas.edn"
    :config/key (when (= :system/root id-val) "resources/seon/config/default.edn")
    :ns/name   (or (:ns/path ent)
                   (:ns/path (d/pull theirs-db '[:ns/path] [:ns/name id-val]))
                   (:ns/path (d/pull ours-db '[:ns/path] [:ns/name id-val])))
    (:form/key :var/name)
    (let [nsym (or (:form/ns ent) (:var/ns ent)
                   (when (= :form/key id-attr)
                     (or (:form/ns (d/pull theirs-db '[:form/ns] [:form/key id-val]))
                         (:form/ns (d/pull ours-db '[:form/ns] [:form/key id-val]))))
                   (when (= :var/name id-attr)
                     (or (:var/ns (d/pull theirs-db '[:var/ns] [:var/name id-val]))
                         (:var/ns (d/pull ours-db '[:var/ns] [:var/name id-val])))))]
      (when nsym
        (or (:ns/path (d/pull theirs-db '[:ns/path] [:ns/name nsym]))
            (:ns/path (d/pull ours-db '[:ns/path] [:ns/name nsym])))))
    nil))

(defn- merge-one-entity
  "3-way merges a single `[id-attr id-val]` across `(b-map, o-map, t-map)`.
   Returns `{:ops [...] :conflicts [...]}`."
  [[id-attr id-val :as lookup-ref] b-map o-map t-map]
  (cond
    ;; No net change on `theirs`, or `ours` already has the exact same entity state
    (or (= t-map b-map) (= t-map o-map))
    {:ops [] :conflicts []}

    ;; Entity deleted on `theirs`
    (nil? t-map)
    (if (= o-map b-map)
      {:ops [[:db/retractEntity lookup-ref]] :conflicts []}
      {:ops [] :conflicts [{:entity lookup-ref :reason :deleted-vs-modified :base b-map :ours o-map :theirs nil}]})

    ;; Entity newly created on `theirs` and absent on `ours`
    (and (nil? b-map) (nil? o-map))
    {:ops [(reduce-kv (fn [m k v] (assoc m k (if (set? v) (vec (sort-by pr-str v)) v))) {} t-map)]
     :conflicts []}

    ;; Attribute-by-attribute 3-way merge!
    :else
    (let [all-ks (disj (set/union (set (keys b-map)) (set (keys o-map)) (set (keys t-map))) id-attr)]
      (reduce
       (fn [acc k]
         (if (contains? many-attrs k)
           ;; `:db.cardinality/many` -> 3-way set union / retraction!
           (let [bs (get b-map k #{})
                 os (get o-map k #{})
                 ts (get t-map k #{})
                 to-add (set/difference (set/difference ts bs) os)
                 to-rem (set/intersection (set/difference bs ts) os)
                 add-ops (mapv (fn [v] [:db/add lookup-ref k v]) (sort-by pr-str to-add))
                 rem-ops (mapv (fn [v] [:db/retract lookup-ref k v]) (sort-by pr-str to-rem))]
             (update acc :ops into (concat rem-ops add-ops)))
           ;; `:db.cardinality/one` -> 3-way scalar merge!
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
  "Computes a pure generic 3-way merge of `theirs-branch` into `ours-conn` (typically `:main`).
   Uses `d/merge-base` + `d/changes` directly on `ours-conn`'s shared store (zero extra connections!)
   so work is strictly O(|Delta_ours| + |Delta_theirs|).
   Returns `{:ok? bool :base-commit uuid :ours-commit uuid :theirs-commit uuid
             :tx-data [...] :touched-paths #{...} :entity-diffs [...] :conflicts [...]}`."
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
          ours-ds (if (= base-cid ours-cid) [] (d/changes ours-conn base-cid ours-cid))
          theirs-ds (if (= base-cid theirs-cid) [] (d/changes ours-conn base-cid theirs-cid))
          theirs-keys (touched-entities-in-changes base-db theirs-db theirs-ds)
          ours-keys (if (empty? ours-ds)
                      #{}
                      (touched-entities-in-changes base-db ours-db ours-ds))
          {:keys [tx-data touched-paths entity-diffs conflicts]}
          (reduce
           (fn [acc [id-attr id-val :as lref]]
             (let [b-val (pull-clean-entity base-db id-attr id-val)
                   t-val (pull-clean-entity theirs-db id-attr id-val)
                   o-val (if (contains? ours-keys lref)
                           (pull-clean-entity ours-db id-attr id-val)
                           b-val)
                   {:keys [ops conflicts]} (merge-one-entity lref b-val o-val t-val)
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
       :tx-data tx-data
       :touched-paths touched-paths
       :entity-diffs entity-diffs
       :conflicts conflicts})))
