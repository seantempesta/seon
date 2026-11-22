(ns seon.store.query
  "Cardinality-aware indexed Datahike queries backed by `datahike.index.interface` (`-count-slice`,
   `-slice`, `-rslice`) with automatic `datahike.reads` tracking and `AsOfDB`/`HistoricalDB` fallback.
   Powers `[:db-count]` / `[:db-slice]` in `seon.rules` and bounded 10k-result smart previews."
  (:require [datahike.api :as d]
            [datahike.constants :refer [e0 emax tx0 txmax]]
            [datahike.datom :as datom]
            [datahike.index.interface :as di]
            [datahike.index.persistent-set :as dips]))

(defn- resolve-index-bounds
  "Maps `[e a v]` (where `nil` means open bound) to `[index-key lo-datom hi-datom post-filter-fn]`
   using the cheapest available Datahike index (`:eavt`, `:avet`, `:aevt`)."
  [db [e a v]]
  (let [schema (or (:schema db) (try (d/schema db) (catch Throwable _ nil)))
        has-a? (and (keyword? a) (contains? schema a))
        indexed-v? (and has-a? (or (get-in schema [a :db/index])
                                   (get-in schema [a :db/unique])
                                   (= :db.type/ref (get-in schema [a :db/valueType]))))]
    (cond
      (and (integer? e) has-a?)
      [:eavt
       (datom/datom e a nil tx0)
       (datom/datom e a nil txmax)
       (when (some? v) #(= v (:v %)))]

      (integer? e)
      [:eavt
       (datom/datom e nil nil tx0)
       (datom/datom e nil nil txmax)
       nil]

      (and has-a? (some? v) indexed-v?)
      [:avet
       (datom/datom e0 a v tx0)
       (datom/datom emax a v txmax)
       nil]

      (and has-a? (some? v))
      [:aevt
       (datom/datom e0 a nil tx0)
       (datom/datom emax a nil txmax)
       #(= v (:v %))]

      has-a?
      [:aevt
       (datom/datom e0 a nil tx0)
       (datom/datom emax a nil txmax)
       nil]

      :else nil)))

(defn- fallback-datoms [db [e a v]]
  (let [schema (or (:schema db) (try (d/schema db) (catch Throwable _ nil)))]
    (cond
      (and (integer? e) (keyword? a) (contains? schema a))
      (let [ds (d/datoms db :eavt e a)]
        (if (some? v) (filter #(= v (:v %)) ds) ds))

      (integer? e)
      (d/datoms db :eavt e)

      (and (keyword? a) (contains? schema a) (some? v))
      (if (or
          (get-in schema [a :db/index])
          (get-in schema [a :db/unique])
          (= :db.type/ref (get-in schema [a :db/valueType])))
        (d/datoms db :avet a v)
        (filter #(= v (:v %)) (d/datoms db :aevt a)))

      (and (keyword? a) (contains? schema a))
      (d/datoms db :aevt a)

      :else [])))

(defn count-range
  "Counts matching datoms for `[e a v]` (`nil` = wildcard) using `di/-count-slice` on live DBs
   (O(log N) when subtree counts are enabled, with automatic `datahike.reads` tracking)."
  {:malli/schema [:=> [:cat :seon.host/db [:vector :any]] :int]}
  [db pattern]
  (let [[e a v] pattern]
    (if (and (instance? datahike.db.DB db) (vector? pattern) (>= (count pattern) 2))
      (if-let [[idx lo hi post-pred] (resolve-index-bounds db [e a v])]
        (if post-pred
          (count (filter post-pred (di/-slice (get db idx) lo hi idx)))
          (let [pset (get db idx)
                cmp (dips/slice-comparator-constructor idx lo hi)]
            (long (di/-count-slice pset lo hi cmp))))
        0)
      (long (count (fallback-datoms db [e a v]))))))

(defn slice-datoms
  "Returns a bounded vector of datoms matching `[e a v]` (`nil` = wildcard) via lazy
   `di/-slice` / `di/-rslice` (never materializing 10k+ datoms). Supports `{:limit :offset :reverse?}`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:vector :any]] [:vector :any]]
                  [:=> [:cat :seon.host/db [:vector :any] :map] [:vector :any]]]}
  ([db pattern] (slice-datoms db pattern {}))
  ([db pattern {:keys [limit offset reverse?] :or {limit 50 offset 0 reverse? false}}]
   (let [[e a v] pattern
         lim (max 0 (long (or limit 50)))
         off (max 0 (long (or offset 0)))]
     (if (zero? lim)
       []
       (if (and (instance? datahike.db.DB db) (vector? pattern) (>= (count pattern) 2))
         (if-let [[idx lo hi post-pred] (resolve-index-bounds db [e a v])]
           (let [pset (get db idx)
                 raw (if reverse?
                       (di/-rslice pset hi lo idx)
                       (di/-slice pset lo hi idx))
                 seq' (if post-pred (filter post-pred raw) raw)]
             (->> seq' (drop off) (take lim) vec))
           [])
         (let [raw (fallback-datoms db [e a v])
               seq' (if reverse? (reverse raw) raw)]
           (->> seq' (drop off) (take lim) vec)))))))

(defn preview-slice
  "Returns a self-describing bounded preview map `{:total :offset :limit :reverse? :more? :datoms}`
   for any `[e a v]` query without realizing the full collection."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:vector :any]] :map]
                  [:=> [:cat :seon.host/db [:vector :any] :map] :map]]}
  ([db pattern] (preview-slice db pattern {}))
  ([db pattern {:keys [limit offset reverse?] :or {limit 20 offset 0 reverse? true} :as opts}]
   (let [tot (count-range db pattern)
         lim (max 0 (long (or limit 20)))
         off (max 0 (long (or offset 0)))
         ds (slice-datoms db pattern {:limit lim :offset off :reverse? (boolean reverse?)})]
     {:total tot
      :offset off
      :limit lim
      :reverse? (boolean reverse?)
      :more? (> tot (+ off (count ds)))
      :datoms ds})))
