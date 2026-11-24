(ns seon.ctx.diff
  "Shared O(1) commit-anchored Datahike branch diff, merge-base, commit-tx lookup,
   and code/rules DB signature caches for `seon.ctx*`, `seon.rule`, `seon.render.*`,
   and `seon.health.*`."
  (:require [clojure.core.cache :as cache]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.versioning :as dv]
            [seon.store :as store :refer [live-cid]]))

(defonce ^:private ^:seon.flow/edge merge-base-lru (store/lru-cache 128))

(defonce ^:private ^:seon.flow/edge merge-base-anchors (atom {}))

(defonce ^:private ^:seon.flow/edge branch-changes-lru (store/lru-cache 64))

(defonce ^:private ^:seon.flow/edge branch-changes-anchors (atom {}))

(defonce ^:private ^:seon.flow/edge commit-tx-lru (store/lru-cache 128))

(defonce ^:private ^:seon.flow/edge commit-tx-anchors (atom {}))

(defonce ^:private ^:seon.flow/edge attr-sig-lru (store/lru-cache 256))

(defonce ^:private ^:seon.flow/edge attr-sig-anchors (atom {}))

(defn- single-parent-of [db]
  (let [ps (get-in db [:meta :datahike/parents])]
    (when (and (set? ps) (= 1 (count ps)))
      (first ps))))

(defn- resolve-attr [db raw-a]
  (if (keyword? raw-a)
    raw-a
    (or (get-in db [:ref-ident-map raw-a]) raw-a)))

(defn- ^:seon.flow/edge ensure-lru! [cache-atom cap]
  (when-not (satisfies? cache/CacheProtocol @cache-atom)
    (reset! cache-atom (cache/lru-cache-factory {} :threshold (long cap)))))

(defn- ^:seon.flow/edge lru-peek [cache-atom cap k]
  (ensure-lru! cache-atom cap)
  (let [v (get @cache-atom k ::miss)]
    (if (and (map? v) (contains? v :seon.store/lru-val))
      (:seon.store/lru-val v)
      v)))

(defn- ^:seon.flow/edge lru-put! [cache-atom cap k v]
  (ensure-lru! cache-atom cap)
  (swap! cache-atom cache/miss k v)
  v)

(defn- ^:seon.flow/edge bounded-assoc! [a cap k v]
  (swap! a (fn [m] (assoc (if (>= (count m) (long cap)) {} m) k v)))
  v)

(defn- net-datom-map [m datoms]
  (reduce (fn [acc dt]
            (let [k [(:e dt) (:a dt) (:v dt)]]
              (if-let [prev (get acc k)]
                (if (not= (:added prev) (:added dt))
                  (dissoc acc k)
                  (assoc acc k dt))
                (assoc acc k dt))))
          m
          datoms))

(defn ^:seon.flow/edge fast-merge-base
  "O(1) commit-anchored `d/merge-base` with single-parent advancement on BOTH `main-db` and `branch-db`.
   2-arg accepts `[main-db branch-db]`; 3-arg accepts `[db-or-store mc bc]`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] :any]
                  [:=> [:cat :any :any :any] :any]]
   :cost/ticks 5000000}
  ([main-db branch-db]
   (when (and (instance? datahike.db.DB main-db) (instance? datahike.db.DB branch-db))
     (let [sid (store/store-id branch-db)
           mc (live-cid main-db)
           bc (live-cid branch-db)]
       (when (and sid mc bc (= sid (store/store-id main-db)))
         (if (= mc bc)
           mc
           (let [ck [sid mc bc]
                 hit (lru-peek merge-base-lru 128 ck)]
             (if-not (identical? ::miss hit)
               (when-not (identical? ::none hit) hit)
               (let [ak [sid (get-in main-db [:config :branch] :main) (get-in branch-db [:config :branch] :main)]
                     anc (get @merge-base-anchors ak)
                     mp (single-parent-of main-db)
                     bp (single-parent-of branch-db)
                     mtx (long (or (:max-tx main-db) 0))
                     btx (long (or (:max-tx branch-db) 0))
                     fast-b (when-let [b (:base anc)]
                              (cond
                                (and (= bc (:bc anc)) (= mp (:mc anc))
                                     (or (not= b (:mc anc)) (> mtx btx))) b
                                (and (= mc (:mc anc)) (= bp (:bc anc))
                                     (or (not= b (:bc anc)) (> btx mtx))) b
                                (and (= mp (:mc anc)) (= bp (:bc anc))
                                     (not= b (:mc anc)) (not= b (:bc anc))) b
                                :else nil))
                     base (or fast-b
                              (store/without-agent-eval-reads
                               #(try (d/merge-base branch-db mc bc) (catch Throwable _ nil))))]
                 (lru-put! merge-base-lru 128 ck (if (some? base) base ::none))
                 (when base
                   (bounded-assoc! merge-base-anchors 64 ak {:mc mc :bc bc :base base}))
                 base))))))))
  ([db-or-store mc bc]
   (when (and db-or-store mc bc)
     (if (= mc bc)
       mc
       (let [sid (or (store/store-id db-or-store) (System/identityHashCode db-or-store))
             ck [sid mc bc]
             hit (lru-peek merge-base-lru 128 ck)]
         (if-not (identical? ::miss hit)
           (when-not (identical? ::none hit) hit)
           (let [base (store/without-agent-eval-reads
                       #(try (d/merge-base db-or-store mc bc) (catch Throwable _ nil)))]
             (lru-put! merge-base-lru 128 ck (if (some? base) base ::none))
             base)))))))

(defn ^:seon.flow/edge branch-changes
  "Incremental `d/changes` between merge-base and `branch-db`: appends ONLY `(d/changes branch-db prev-bc bc)`
   when `branch-db` advances by one single-parent commit. Returns datom vector or nil if unavailable."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] [:maybe [:vector :any]]]
                  [:=> [:cat [:maybe :seon.host/db] :any :any] [:maybe [:vector :any]]]]
   :cost/ticks 10000000}
  ([main-db branch-db]
   (cond
     (or (nil? branch-db) (identical? main-db branch-db)) []
     (and (live-cid main-db) (= (live-cid main-db) (live-cid branch-db))) []
     :else (when-let [base (fast-merge-base main-db branch-db)]
             (branch-changes branch-db base (live-cid branch-db)))))
  ([branch-db base-cid bc]
   (when (and (instance? datahike.db.DB branch-db) base-cid bc)
     (if (= base-cid bc)
       []
       (let [sid (store/store-id branch-db)
             ck [sid base-cid bc]
             hit (lru-peek branch-changes-lru 64 ck)]
         (if-not (identical? ::miss hit)
           (when-not (identical? ::none hit) hit)
           (let [bbr (get-in branch-db [:config :branch] :main)
                 ak [sid bbr base-cid]
                 anc (get @branch-changes-anchors ak)
                 bp (single-parent-of branch-db)
                 inc-res (when (and anc bp (= bp (:cid anc)))
                           (store/without-agent-eval-reads
                            #(try
                               (let [delta (d/changes branch-db (:cid anc) bc)
                                     by-eav (net-datom-map (:by-eav anc) delta)]
                                 {:by-eav by-eav :datoms (vec (vals by-eav))})
                               (catch Throwable _ nil))))
                 full-res (or inc-res
                              (store/without-agent-eval-reads
                               #(try
                                  (let [ds (vec (d/changes branch-db base-cid bc))
                                        by-eav (into {} (map (fn [dt] [[(:e dt) (:a dt) (:v dt)] dt])) ds)]
                                    {:by-eav by-eav :datoms ds})
                                  (catch Throwable _ nil))))]
             (if full-res
               (let [ds (:datoms full-res)]
                 (bounded-assoc! branch-changes-anchors 64 ak (assoc full-res :cid bc))
                 (lru-put! branch-changes-lru 64 ck ds))
               (do (lru-put! branch-changes-lru 64 ck ::none)
                   nil)))))))))

(defn ^:seon.flow/edge commit-holding-tx-cached
  "Cached `datahike.versioning/commit-holding-tx` keyed by `[sid branch-cid tx]` with single-parent promotion."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :int]] [:maybe :map]]
   :cost/ticks 5000000}
  [branch-db tx]
  (when (and (instance? datahike.db.DB branch-db) (integer? tx) (pos? (long tx)))
    (let [sid (store/store-id branch-db)
          bc (live-cid branch-db)
          txl (long tx)]
      (if-not (and sid bc)
        (store/without-agent-eval-reads #(try (dv/commit-holding-tx branch-db txl) (catch Throwable _ nil)))
        (let [ck [sid bc txl]
              hit (lru-peek commit-tx-lru 128 ck)]
          (if-not (identical? ::miss hit)
            (when-not (identical? ::none hit) hit)
            (let [bbr (get-in branch-db [:config :branch] :main)
                  mtx (long (or (:max-tx branch-db) 0))
                  ak [sid bbr]
                  anc (get @commit-tx-anchors ak)
                  bp (single-parent-of branch-db)
                  same-lin? (and anc (or (= bc (:cid anc)) (and bp (= bp (:cid anc)))))
                  anc-hit (when (and same-lin? (<= txl (long (:max-tx anc 0))) (contains? (:by-tx anc) txl))
                            (get (:by-tx anc) txl))]
              (if (some? anc-hit)
                (do
                  (when (not= bc (:cid anc))
                    (bounded-assoc! commit-tx-anchors 64 ak (assoc anc :cid bc :max-tx mtx)))
                  (lru-put! commit-tx-lru 128 ck anc-hit)
                  (when-not (identical? ::none anc-hit) anc-hit))
                (let [res (store/without-agent-eval-reads
                           #(try (dv/commit-holding-tx branch-db txl) (catch Throwable _ nil)))
                      stored (if (some? res) res ::none)
                      next-by (if same-lin? (assoc (:by-tx anc) txl stored) {txl stored})]
                  (bounded-assoc! commit-tx-anchors 64 ak {:cid bc :max-tx mtx :by-tx next-by})
                  (lru-put! commit-tx-lru 128 ck stored)
                  res)))))))))

(defn- code-or-schema-attr? [a]
  (and (qualified-keyword? a)
       (let [ns-s (namespace a)]
         (or (str/starts-with? ns-s "seon.code.")
             (= ns-s "seon.schema")))))

(defn- rule-attr? [a]
  (and (qualified-keyword? a) (= "seon.rule" (namespace a))))

(defn- ^:seon.flow/edge attr-ns-sig [db kind pred]
  (cond
    (not (instance? datahike.db.DB db)) (store/db-cache-sig db)
    (seq (:datahike/uncommitted (meta db))) nil
    :else
    (let [sid (store/store-id db)
          bc (live-cid db)]
      (if-not (and sid bc)
        (store/db-cache-sig db)
        (let [scnt (count (:schema db))
              ck [sid kind bc]
              hit (lru-peek attr-sig-lru 256 ck)]
          (if (and (map? hit) (= scnt (:schema-cnt hit)))
            [sid kind (:sig hit)]
            (let [bbr (get-in db [:config :branch] :main)
                  mtx (long (or (:max-tx db) 0))
                  ak [sid kind bbr]
                  p1 (single-parent-of db)
                  p-hit (when p1 (lru-peek attr-sig-lru 256 [sid kind p1]))
                  anc (get @attr-sig-anchors ak)
                  prev (cond
                         (and (map? p-hit) (= scnt (:schema-cnt p-hit)))
                         {:cid p1 :sig (:sig p-hit)}
                         (and (map? anc) (not= bc (:cid anc)) (= scnt (:schema-cnt anc))
                              (< 0 (- mtx (long (:max-tx anc 0))) 65))
                         {:cid (:cid anc) :sig (:sig anc)}
                         :else nil)
                  unchanged? (when prev
                               (store/without-agent-eval-reads
                                #(try
                                   (not-any? (fn [dt] (pred (resolve-attr db (:a dt))))
                                             (d/changes db (:cid prev) bc))
                                   (catch Throwable _ false))))
                  eff-sig (if unchanged? (:sig prev) bc)
                  entry {:sig eff-sig :schema-cnt scnt}]
              (lru-put! attr-sig-lru 256 ck entry)
              (bounded-assoc! attr-sig-anchors 64 ak (assoc entry :cid bc :max-tx mtx))
              [sid kind eff-sig])))))))

(defn ^:seon.flow/edge code-db-sig
  "Commit-anchored DB signature that changes ONLY when `:seon.code.*` or `:seon.schema/*` datoms change
   (completely immune to eval, message, error, test verdict, and task writes)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :any]
   :cost/ticks 1000000}
  [db]
  (when db (attr-ns-sig db :code code-or-schema-attr?)))

(defn ^:seon.flow/edge rules-db-sig
  "Commit-anchored DB signature that changes ONLY when `:seon.rule/*` datoms change."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] :any]
   :cost/ticks 1000000}
  [db]
  (when (and db (contains? (store/db-schema db) :seon.rule/id))
    (attr-ns-sig db :rules rule-attr?)))
