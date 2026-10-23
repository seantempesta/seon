(ns seon.union
  "U, the union of every branch's history: every datom any branch of the store committed,
  held once, in one ordinary Datahike db, queried with plain d/q, d/pull and d/entity over
  (d/history U). Ids are issued once across the store and identities take content ids
  (docs/storage.md#ids), and merges carry datoms as they are, so the union is a set union
  with nothing to resolve: an equal datom is the same datom. A branch's own view stays its
  head; U answers what happened across branches (who wrote what, where), and each datom's
  transaction names its branch (:seon.tx/branch).

  U holds assertions in its current indexes and retractions in its temporal ones, so
  (d/history U) reads both; constraints belong to a branch, so U's schema lifts every
  attribute to cardinality many with no uniqueness. It is kept by insertion: a commit adds
  its datoms, O(Δ log N), and a branch U has not seen since commit c adds the datoms that
  differ between c and its head (datoms-diff over shared trees). docs/storage.md#the-union."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [datahike.api :as d] [datahike.index.interface :as di]
            [datahike.core]))

(defn lifted-schema
  "db's schema as U holds it: each attribute's value type kept, cardinality many, no
  uniqueness, every attribute indexed (a union holds what branches assert, whatever their
  constraints)."
  {:malli/schema [:=> [:cat :seon.host/db] :map]}
  [db]
  (into {} (for [[k v] (d/schema db)
                 :when (and (keyword? k) (map? v) (:db/valueType v) (not= "db" (namespace k)))]
             [k (-> (select-keys v [:db/valueType])
                    (assoc :db/cardinality :db.cardinality/many :db/index true))])))

(defn empty-union
  "An empty U over db's schema: {:db U :applied {}}, :applied naming, per branch, the commit
  whose datoms U holds."
  {:malli/schema [:=> [:cat :seon.host/db] :seon.union/union]}
  [db]
  {:db (datahike.core/empty-db (lifted-schema db) {:keep-history? true :schema-flexibility :write}) :applied {}})

(defn- insert-index
  {:malli/schema [:=> [:cat :any :keyword [:sequential :any]] :any]}
  [idx k ds]
  (di/-persistent! (reduce #(di/-insert %1 %2 k 0) (di/-transient idx) ds)))

(defn insert
  "U with datoms ds added: assertions to its current indexes, retractions to its temporal
  ones; a datom U holds already is held once. O(|ds| log N)."
  {:malli/schema [:=> [:cat :seon.union/union [:sequential :any]] :seon.union/union]}
  [u ds]
  (let [{as true rs false} (group-by (comp boolean :added) ds)
        db (:db u)
        db (cond-> db
             (seq as) (-> (update :eavt insert-index :eavt as) (update :aevt insert-index :aevt as) (update :avet insert-index :avet as))
             (seq rs) (-> (update :temporal-eavt insert-index :eavt rs) (update :temporal-aevt insert-index :aevt rs)
                          (update :temporal-avet insert-index :avet rs))
             (seq ds) (assoc :max-tx (reduce max (:max-tx db) (map :tx ds))))]
    (assoc u :db db)))

(defn changes
  "The datoms branch db `to` committed since db `from` (an earlier commit of it, or its merge
  base): what it asserted, and the retractions it made of what `from` held, each with the
  transaction that made it. O(Δ log N): datoms-diff skips the subtrees both share."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db] [:vector :any]]}
  [from to]
  (let [[gone added] (d/datoms-diff from to :eavt [])]
    (into (vec added)
          (for [g gone
                :let [r (last (filter #(and (not (:added %)) (> (:tx %) (:tx g)))
                                      (d/datoms (d/history to) :eavt (:e g) (:a g) (:v g))))]
                :when r]
            r))))

(defn catch-up
  "U holding branch b's datoms up to its commit `to`, from `from`, the commit of it U holds
  already (or, for a branch U has not seen, its merge base with a branch U holds)."
  {:malli/schema [:=> [:cat :seon.union/union :keyword :seon.host/db :seon.host/db] :seon.union/union]}
  [u b from to]
  (-> (insert u (changes from to))
      (assoc-in [:applied b] (d/commit-id to))))

(defn of-history
  "U holding every datom of db's history (a main's, at boot): its assertions and retractions."
  {:malli/schema [:=> [:cat :seon.union/union :keyword :seon.host/db] :seon.union/union]}
  [u b db]
  (-> (insert u (vec (d/datoms (d/history db) :eavt)))
      (assoc-in [:applied b] (d/commit-id db))))
