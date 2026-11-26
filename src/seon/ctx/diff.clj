(ns seon.ctx.diff
  "Shared O(1) commit-anchored Datahike branch diff, merge-base, commit-tx lookup,
   and code/rules DB signature caches for `seon.ctx*`, `seon.rule`, `seon.render.*`,
   and `seon.health.*`."
  (:require [clojure.string :as str]
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
                 hit (store/lru-peek merge-base-lru ck ::miss)]
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
                 (store/lru-put! merge-base-lru 128 ck (if (some? base) base ::none))
                 (when base
                   (bounded-assoc! merge-base-anchors 64 ak {:mc mc :bc bc :base base}))
                 base))))))))
  ([db-or-store mc bc]
   (when (and db-or-store mc bc)
     (if (= mc bc)
       mc
       (let [sid (or (store/store-id db-or-store) (System/identityHashCode db-or-store))
             ck [sid mc bc]
             hit (store/lru-peek merge-base-lru ck ::miss)]
         (if-not (identical? ::miss hit)
           (when-not (identical? ::none hit) hit)
           (let [base (store/without-agent-eval-reads
                       #(try (d/merge-base db-or-store mc bc) (catch Throwable _ nil)))]
             (store/lru-put! merge-base-lru 128 ck (if (some? base) base ::none))
             base)))))))

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
              hit (store/lru-peek commit-tx-lru ck ::miss)]
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
                  (store/lru-put! commit-tx-lru 128 ck anc-hit)
                  (when-not (identical? ::none anc-hit) anc-hit))
                (let [res (store/without-agent-eval-reads
                           #(try (dv/commit-holding-tx branch-db txl) (catch Throwable _ nil)))
                      stored (if (some? res) res ::none)
                      next-by (if same-lin? (assoc (:by-tx anc) txl stored) {txl stored})]
                  (bounded-assoc! commit-tx-anchors 64 ak {:cid bc :max-tx mtx :by-tx next-by})
                  (store/lru-put! commit-tx-lru 128 ck stored)
                  res)))))))))

(defn- code-or-schema-attr? [a]
  (and (qualified-keyword? a)
       (let [ns-s (namespace a)]
         (or (str/starts-with? ns-s "seon.code.")
             (= ns-s "seon.schema")))))

(defn- rule-attr? [a]
  (and (qualified-keyword? a) (= "seon.rule" (namespace a))))

(defonce ^:private ^:seon.flow/edge commit-parent-lru (store/lru-cache 512))

(defn- ^:seon.flow/edge commit-parents-cid [db store sid cid]
  (if (and (instance? datahike.db.DB db) (= cid (live-cid db)))
    (let [ps (get-in db [:meta :datahike/parents])]
      (if (set? ps) (vec ps) []))
    (let [cache-key [sid cid]
          hit (store/lru-peek commit-parent-lru cache-key ::miss)]
      (if-not (identical? ::miss hit)
        (if (vector? hit) hit (if (identical? ::none hit) [] [hit]))
        (let [record (store/without-agent-eval-reads
                      #(try (dv/commit-graph-record store cid) (catch Throwable _ nil)))
              parents (:datahike/parents record)
              pvec (if (and (set? parents) (seq parents)) (vec parents) [])]
          (store/lru-put! commit-parent-lru 512 cache-key pvec)
          pvec)))))

(defn ^:seon.flow/edge ancestor-within?
  "True iff `anc-cid` is reachable from `cur-cid` via at most `max-hops` commit-graph hops (including multi-parent merge commits).
   Uses in-memory parents for hop 1 and cached `datahike.versioning/commit-graph-record` thereafter so callers never trigger `:datahike/changes-not-ancestor` throws."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :any :any] :boolean]
                  [:=> [:cat [:maybe :seon.host/db] :any :any :int] :boolean]]
   :cost/ticks 4000000}
  ([db anc-cid cur-cid]
   (ancestor-within? db anc-cid cur-cid 16))
  ([db anc-cid cur-cid max-hops]
   (cond
     (or (nil? db) (nil? anc-cid) (nil? cur-cid)) false
     (= anc-cid cur-cid) true
     (not (instance? datahike.db.DB db)) false
     :else
     (let [store (:store db)
           sid (store/store-id db)
           limit (long (max 0 (or max-hops 0)))]
       (if-not (and store sid (pos? limit))
         false
         (loop [q (conj clojure.lang.PersistentQueue/EMPTY [cur-cid 0])
                seen #{cur-cid}]
           (if (or (empty? q) (> (count seen) 256))
             false
             (let [[cid depth] (peek q)
                   q1 (pop q)]
               (if (>= (long depth) limit)
                 (recur q1 seen)
                 (let [ps (commit-parents-cid db store sid cid)]
                   (if (some #(= % anc-cid) ps)
                     true
                     (let [[q2 seen2] (reduce (fn [[aq as] p]
                                                (if (or (nil? p) (contains? as p))
                                                  [aq as]
                                                  [(conj aq [p (inc (long depth))]) (conj as p)]))
                                              [q1 seen] ps)]
                       (recur q2 seen2)))))))))))))

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
              hit (store/lru-peek attr-sig-lru ck ::miss)]
          (if (and (map? hit) (= scnt (:schema-cnt hit)))
            [sid kind (:sig hit)]
            (let [bbr (get-in db [:config :branch] :main)
                  mtx (long (or (:max-tx db) 0))
                  ak [sid kind bbr]
                  p1 (single-parent-of db)
                  p-hit (when p1 (store/lru-peek attr-sig-lru [sid kind p1] ::miss))
                  anc (get @attr-sig-anchors ak)
                  anc-same? (and (map? anc) (= bc (:cid anc)) (= scnt (:schema-cnt anc)))
                  prev (cond
                         anc-same? nil
                         (and (map? p-hit) (= scnt (:schema-cnt p-hit)))
                         {:cid p1 :sig (:sig p-hit)}
                         (and (map? anc) (not= bc (:cid anc)) (= scnt (:schema-cnt anc)))
                         {:cid (:cid anc) :sig (:sig anc)}
                         :else nil)
                  unchanged? (when (and prev (ancestor-within? db (:cid prev) bc 64))
                               (store/without-agent-eval-reads
                                #(try
                                   (not-any? (fn [dt] (pred (resolve-attr db (:a dt))))
                                             (d/changes db (:cid prev) bc))
                                   (catch Throwable _ false))))
                  eff-sig (cond anc-same? (:sig anc) unchanged? (:sig prev) :else bc)
                  entry {:sig eff-sig :schema-cnt scnt}]
              (store/lru-put! attr-sig-lru 256 ck entry)
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

(defn- sort-change-datoms [datoms]
  (vec (sort-by (fn [dt]
                  [(long (or (:tx dt) 0))
                   (long (or (:e dt) 0))
                   (str (:a dt))])
                datoms)))

(defn ^:seon.flow/edge branch-changes
  "Incremental `d/changes` between merge-base and `branch-db`: appends ONLY `(d/changes branch-db prev-bc bc)`
   when `branch-db` advances by one single-parent commit, preserving deterministic `[tx e a]` ordering.
   Returns datom vector or nil if unavailable."
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
             hit (store/lru-peek branch-changes-lru ck ::miss)]
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
                                 {:by-eav by-eav :datoms (sort-change-datoms (vals by-eav))})
                               (catch Throwable _ nil))))
                 full-res (or inc-res
                              (store/without-agent-eval-reads
                               #(try
                                  (let [ds (sort-change-datoms (d/changes branch-db base-cid bc))
                                        by-eav (into {} (map (fn [dt] [[(:e dt) (:a dt) (:v dt)] dt])) ds)]
                                    {:by-eav by-eav :datoms ds})
                                  (catch Throwable _ nil))))]
             (if full-res
               (let [ds (:datoms full-res)]
                 (bounded-assoc! branch-changes-anchors 64 ak (assoc full-res :cid bc))
                 (store/lru-put! branch-changes-lru 64 ck ds))
               (do (store/lru-put! branch-changes-lru 64 ck ::none)
                   nil)))))))))

(def ^:private domain-edit-attrs
  #{:seon.code.form/key :seon.code.form/src :seon.code.form/hash
    :seon.code.var/name :seon.code.ns/name :seon.code.doc/id :seon.code.doc/body
    :seon.code.file/path :seon.schema/key :seon.schema/form
    :seon.rule/id :seon.rule/query :seon.rule/assert :seon.rule/enabled?})

(def ^:private target-session-attrs
  #{:seon.agent/pinned :seon.agent/curated :seon.agent/context-prefs :seon.agent/summary
    :seon.task/plan :seon.task/test-vars :seon.task/tests :seon.task/summary})

(defn ^:seon.flow/edge branch-latest-edit-info
  "Returns `{:has-edits? :last-edit-tx :last-edit-inst}` for domain/rule/schema/task (and forked target-session) changes on `branch-db` since base."
  {:malli/schema [:function [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] :map]
                            [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :map]] :map]]
   :cost/ticks 6000000}
  ([main-db branch-db] (branch-latest-edit-info main-db branch-db nil))
  ([main-db branch-db ma]
   (let [none {:has-edits? false :last-edit-tx nil :last-edit-inst nil}]
     (if-not (and (instance? datahike.db.DB main-db) (instance? datahike.db.DB branch-db) (not (identical? main-db branch-db)))
       none
       (let [mc (d/commit-id main-db) bc (d/commit-id branch-db)
             fc (:seon.agent/fork-commit ma)
             forked? (boolean (or fc (:seon.agent/forked-from ma) (:seon.agent/target ma) (:seon.agent/merge-target ma)))
             base (cond
                    (and fc bc (not= fc bc) (ancestor-within? branch-db fc bc 256)) fc
                    (and mc bc (not= mc bc)) (fast-merge-base main-db branch-db)
                    :else nil)]
         (if-not (and base bc (not= base bc))
           none
           (let [dh (or (:schema branch-db) {})
                 res-a #(if (keyword? %) % (or (get-in branch-db [:ref-ident-map %]) %))
                 aid (:seon.agent/id ma)
                 self-eid (when (and aid (contains? dh :seon.agent/id)) (:e (first (d/datoms branch-db :avet :seon.agent/id aid))))
                 max-tx (reduce
                          (fn [mx dt]
                            (let [a (res-a (:a dt)) e (:e dt) added? (boolean (:added dt))
                                  hit? (or (or (contains? domain-edit-attrs a)
                                                (when-let [ans (when (keyword? a) (namespace a))]
                                                  (or (str/starts-with? ans "seon.code.")
                                                      (contains? #{"seon.schema" "seon.rule" "seon.fix" "seon.config"} ans))))
                                           (and forked?
                                                (or (and (not added?) (= a :seon.agent.eval/agent) (not= (:v dt) aid))
                                                    (and (not added?) (= a :seon.error/id))
                                                    (and added? (= a :seon.error/status) (= :resolved (:v dt)))
                                                    (and added? (contains? target-session-attrs a) (not= e self-eid)))))]
                              (if hit? (max mx (long (or (:tx dt) 0))) mx)))
                          0 (try (d/changes branch-db base bc) (catch Throwable _ [])))]
             (if (pos? max-tx)
               (let [inst (:v (first (d/datoms branch-db :eavt max-tx :db/txInstant)))
                     ms (cond (instance? java.util.Date inst) (.getTime ^java.util.Date inst)
                              (number? inst) (long inst) :else nil)]
                 {:has-edits? true :last-edit-tx max-tx :last-edit-inst ms})
               none))))))))