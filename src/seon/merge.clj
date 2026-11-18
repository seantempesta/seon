(ns seon.merge
  "O(|Delta_ours| + |Delta_theirs|) Git-style 3-way merge over Datahike commits using `d/changes`.
   Zero full-DB scans: cost depends strictly on the datoms changed since `d/merge-base`, never on DB size N."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.store :as store]))

(def ^:private mergeable-identities
  "Identity attributes merged 3-way by entity value, with the stated attributes compared for conflicts."
  {:form/key   [:form/key :form/ns :form/order :form/lead :form/src :form/hash :form/deps :form/test?]
   :ns/name    [:ns/name :ns/path :ns/tail]
   :file/path  [:file/path :file/text :file/exec?]
   :task/id    [:task/id :task/title :task/plan :task/needs :task/tests :task/done?]})

(def ^:private append-only-identities
  "Content-addressed / UUID observations carried across cleanly on merge without conflict."
  {:eval/id     [:eval/id :eval/agent :eval/turn :eval/seen-messages
                 :eval/thought :eval/code :eval/value :eval/out :eval/error]
   :test/digest [:test/digest :test/var :test/pass? :test/ms :test/report]
   :message/id  [:message/id :message/seq :message/from :message/to :message/type :message/body]})

(defn- resolve-attr-kw [db a]
  (if (keyword? a)
    a
    (get-in db [:ref-ident-map a])))

(defn- pull-stated [db id-attr id-val attrs]
  (when-let [dt (first (d/datoms db :avet id-attr id-val))]
    (let [m (d/pull db attrs (:e dt))]
      ;; Normalize cardinality-many attributes to sorted vectors so `=` is order-independent
      (reduce-kv
       (fn [acc k v]
         (assoc acc k (if (and (sequential? v) (not (string? v)))
                        (vec (sort-by pr-str v))
                        v)))
       {}
       (dissoc m :db/id)))))

(defn- touched-keys-in-changes
  "Extracts `{[id-attr id-val] ...}` for all entities touched in `changes-datoms`."
  [before-db after-db changes-datoms id-map]
  (let [eids (into #{} (map :e) changes-datoms)]
    (reduce
     (fn [acc eid]
       ;; Check entity in `after-db` first, falling back to `before-db` if retracted!
       (or (some (fn [[id-attr _]]
                   (when-let [v (or (:v (first (d/datoms after-db :eavt eid id-attr)))
                                    (:v (first (d/datoms before-db :eavt eid id-attr))))]
                     (conj acc [id-attr v])))
                 id-map)
           acc))
     #{}
     eids)))

(defn- path-for-merged-entity [ours-db theirs-db id-attr id-val entity-map]
  (case id-attr
    :file/path (:file/path entity-map id-val)
    :ns/name   (or (:ns/path entity-map)
                   (:ns/path (d/pull theirs-db '[:ns/path] [:ns/name id-val]))
                   (:ns/path (d/pull ours-db '[:ns/path] [:ns/name id-val])))
    :form/key  (let [nsym (or (:form/ns entity-map)
                              (:form/ns (d/pull theirs-db '[:form/ns] [:form/key id-val]))
                              (:form/ns (d/pull ours-db '[:form/ns] [:form/key id-val])))]
                 (when nsym
                   (or (:ns/path (d/pull theirs-db '[:ns/path] [:ns/name nsym]))
                       (:ns/path (d/pull ours-db '[:ns/path] [:ns/name nsym])))))
    nil))

(defn three-way
  "Computes a pure 3-way merge of `theirs-branch` into `ours-conn` (typically `:main`).
   Uses `d/merge-base` + `d/changes` directly on `ours-conn`'s shared store (zero extra connections!)
   so work is strictly O(|Delta_ours| + |Delta_theirs|).
   Returns `{:ok? bool :base-commit uuid :ours-commit uuid :theirs-commit uuid
             :tx-data [...] :touched-paths #{...} :conflicts [...]}`."
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
          theirs-keys (touched-keys-in-changes base-db theirs-db theirs-ds mergeable-identities)
          ours-keys (if (empty? ours-ds)
                      #{}
                      (touched-keys-in-changes base-db ours-db ours-ds mergeable-identities))
          carried-keys (touched-keys-in-changes base-db theirs-db theirs-ds append-only-identities)
          carried-tx (vec
                      (keep (fn [[id-attr id-val]]
                              (when-not (first (d/datoms ours-db :avet id-attr id-val))
                                (pull-stated theirs-db id-attr id-val (get append-only-identities id-attr))))
                            carried-keys))
          {:keys [tx-data touched-paths conflicts]}
          (reduce
           (fn [acc [id-attr id-val]]
             (let [attrs (get mergeable-identities id-attr)
                   b-val (pull-stated base-db id-attr id-val attrs)
                   t-val (pull-stated theirs-db id-attr id-val attrs)
                   ours-moved? (contains? ours-keys [id-attr id-val])
                   o-val (if ours-moved?
                           (pull-stated ours-db id-attr id-val attrs)
                           b-val)]
               (cond
                 (or (= t-val b-val) (= t-val o-val))
                 acc

                 (= o-val b-val)
                 (let [p (path-for-merged-entity ours-db theirs-db id-attr id-val (or t-val b-val))
                       op (if (nil? t-val)
                            [:db/retractEntity [id-attr id-val]]
                            t-val)]
                   (cond-> (update acc :tx-data conj op)
                     p (update :touched-paths conj p)))

                 :else
                 (update acc :conflicts conj
                         {:entity [id-attr id-val]
                          :base b-val
                          :ours o-val
                          :theirs t-val}))))
           {:tx-data [] :touched-paths #{} :conflicts []}
           (sort-by pr-str theirs-keys))]
      {:ok? (empty? conflicts)
       :base-commit base-cid
       :ours-commit ours-cid
       :theirs-commit theirs-cid
       :tx-data (into tx-data carried-tx)
       :touched-paths touched-paths
       :conflicts conflicts})))
