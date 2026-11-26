(ns seon.transactor.rebase
  "Datahike-native commit-DAG rebasing (`d/force-branch!` with `:changes {:base M1 :datoms ...}`
   and monotonic store IDs) for coordinator/router branches and post-review worker syncs."
  (:require [datahike.api :as d]
            [datahike.core :as dcore]
            [datahike.ids :as dids]
            [datahike.writing :as dwriting]
            [seon.merge :as merge]
            [seon.store :as store]))

(def ^:private sync-domain-id-attrs
  #{:seon.code.form/key :seon.code.var/name :seon.code.ns/name
    :seon.code.doc/id :seon.code.file/path :seon.schema/key
    :seon.rule/id :seon.fix/id :seon.config/key :seon.task/id :seon.summary/id})

(def ^:private branch-session-attrs
  #{:seon.agent/context-prefs :seon.agent/curated :seon.agent/pinned
    :seon.agent/ready? :seon.agent/summary :seon.agent/waiting?})

(defn sync-domain-op?
  "True when `op` targets a shared domain entity synced across branches."
  {:malli/schema [:=> [:cat :any] :boolean] :cost/ticks 50000}
  [op]
  (cond
    (map? op)
    (boolean (some #(contains? op %) sync-domain-id-attrs))
    (and (vector? op) (contains? #{:db/add :db/retract :db/retractEntity} (first op)) (vector? (second op)))
    (contains? sync-domain-id-attrs (first (second op)))
    :else false))

(defn rebind-committed-schema-tempids
  "Rewrites string tempids referencing committed `:seon.schema/key` entities into lookup refs."
  {:malli/schema [:=> [:cat [:sequential :any] [:sequential :any]] [:vector :any]] :cost/ticks 2000000}
  [schema-ops rest-ops]
  (let [tid->lref (into {}
                        (keep (fn [op]
                                (when (and (map? op) (string? (:db/id op)) (:seon.schema/key op))
                                  [(:db/id op) [:seon.schema/key (:seon.schema/key op)]])))
                        schema-ops)]
    (if (empty? tid->lref)
      (vec rest-ops)
      (let [rw (fn rw [x]
                 (cond
                   (and (string? x) (contains? tid->lref x)) (get tid->lref x)
                   (set? x) (into #{} (map rw) x)
                   (vector? x) (mapv rw x)
                   (map? x) (reduce-kv (fn [a k v] (assoc a k (rw v))) {} x)
                   :else x))]
        (mapv rw rest-ops)))))

(defn split-schema-ops
  "Splits `tx-ops` into `[schema-ops rest-ops]` with committed schema tempids rebound."
  {:malli/schema [:=> [:cat [:sequential :any]] [:tuple [:vector :any] [:vector :any]]] :cost/ticks 2000000}
  [tx-ops]
  (let [s-op? #(and (map? %) (contains? % :seon.schema/key))
        schema-ops (filterv s-op? tx-ops)]
    [schema-ops (rebind-committed-schema-tempids schema-ops (filterv (complement s-op?) tx-ops))]))

(defn ^:seon.flow/edge with-store-ids
  "Applies `tx-data` (and optional `tx-meta`) onto immutable `db` allocating monotonic store-unique
   tx/eids from `datahike.writing/ids` and enforcing Malli write rails via `store/validate-tx-data!`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :any] :seon.host/db]
                  [:=> [:cat :seon.host/db :any [:maybe :map]] :seon.host/db]]}
  ([db tx-data] (with-store-ids db tx-data nil))
  ([db tx-data tx-meta]
   (let [ops (vec (or tx-data []))
         _ (when (seq ops) (store/validate-tx-data! db ops))
         ids (dwriting/ids db)
         dh (or (:schema db) {})
         eff-meta (when (map? tx-meta)
                    (not-empty (select-keys tx-meta (conj (set (keys dh)) :db/txInstant))))
         report (dcore/with db ops eff-meta ids)
         db-after (:db-after report)]
     (when ids
       (dids/-reach! ids :eid (:max-eid db-after))
       (dids/-reach! ids :tx (:max-tx db-after)))
     db-after)))

(defn- tx-instant-of [db tx]
  (when (and db tx)
    (let [v (:v (first (d/datoms db :eavt tx :db/txInstant)))]
      (when (instance? java.util.Date v) v))))

(defn- branch-agent-ids [mdb bdb target-branch]
  (let [from-db (fn [db]
                  (when (and db (contains? (:schema db) :seon.agent/branch))
                    (keep (fn [dt]
                            (when (= target-branch (:v dt))
                              (:v (first (d/datoms db :eavt (:e dt) :seon.agent/id)))))
                          (d/datoms db :aevt :seon.agent/branch))))]
    (vec (distinct (concat (from-db mdb) (from-db bdb))))))

(defn- session-carry-ops [mdb bdb db target-branch extra-attrs]
  (let [attrs (into branch-session-attrs extra-attrs)
        dh (or (:schema db) {})
        live-attrs (filterv #(contains? dh %) attrs)]
    (reduce
     (fn [acc aid]
       (if-let [be (:e (first (d/datoms bdb :avet :seon.agent/id aid)))]
         (let [b-ent (d/pull bdb live-attrs be)
               d-ent (when-let [de (:e (first (d/datoms db :avet :seon.agent/id aid)))]
                       (d/pull db live-attrs de))]
           (reduce-kv
            (fn [a k v]
              (if (or (nil? v) (= v (get d-ent k)))
                a
                (let [op {:seon.agent/id aid k v}]
                  (if (contains? #{:seon.agent/ready? :seon.agent/waiting?} k)
                    (let [orig-tx (:tx (first (d/datoms bdb :eavt be k)))
                          orig-inst (tx-instant-of bdb orig-tx)]
                      (update a :timed conj [(long (or orig-tx 0)) orig-inst op]))
                    (update a :plain conj op)))))
            acc
            (dissoc b-ent :db/id)))
         acc))
     {:plain [] :timed []}
     (branch-agent-ids mdb bdb target-branch))))

(defn- split-eval-ops-by-instant [bdb rest-ops]
  (let [eval-op? #(and (map? %) (contains? % :seon.agent.eval/id))
        ev-ops (filterv eval-op? rest-ops)
        other-ops (filterv (complement eval-op?) rest-ops)
        grouped (->> ev-ops
                     (map (fn [op]
                            (let [dt (first (d/datoms bdb :avet :seon.agent.eval/id (:seon.agent.eval/id op)))
                                  tx (long (or (:tx dt) 0))
                                  inst (tx-instant-of bdb (:tx dt))]
                              [tx inst op])))
                     (group-by (fn [[tx inst _]] [tx inst]))
                     (sort-by (comp first key))
                     (mapv (fn [[[_ inst] xs]] [inst (mapv #(nth % 2) xs)])))]
    {:other-ops other-ops :eval-groups grouped}))

(defn- apply-domain-sync-tx! [bconn plan tx-meta]
  (let [s-tx (vec (:schema-tx plan))
        d-ops (filterv sync-domain-op? (:tx-data plan))
        [schema-ops rest-ops] (split-schema-ops d-ops)
        tx-with (fn [ops]
                  (if tx-meta
                    (binding [store/*tx-meta* tx-meta] (d/transact bconn {:tx-data ops}))
                    (d/transact bconn {:tx-data ops})))]
    (when (seq s-tx) (d/transact bconn {:tx-data s-tx}))
    (when (seq schema-ops) (tx-with schema-ops))
    (cond
      (seq rest-ops) (tx-with rest-ops)
      tx-meta (let [allowed (select-keys tx-meta (keys (:schema @bconn)))]
                (when (seq allowed)
                  (d/transact bconn {:tx-data [(assoc allowed :db/id :db/current-tx)]}))))
    (+ (count s-tx) (count d-ops))))

(defn- commit-rebased-db! [db target-branch m-cid b-cid min-tx marker-meta]
  (let [needs-step? (or (nil? (:datahike/uncommitted (meta db)))
                        (<= (long (:max-tx db 0)) (long min-tx)))
        db1 (if needs-step? (with-store-ids db [] marker-meta) db)
        datoms (vec (or (:datahike/uncommitted (meta db1)) []))
        db2 (assoc-in db1 [:meta :datahike/updated-at] (java.util.Date.))]
    (d/force-branch! db2 target-branch #{m-cid}
                     {:expected-current-commit b-cid
                      :changes {:base m-cid :datoms datoms}})
    {:db db2 :ops (count datoms)}))

(defn ^:seon.flow/edge rebase-branch-onto-main!
  "Rebases `target-branch` cleanly onto `:main` (`M1`) via `d/force-branch!` with `parents = #{M1}`
   and `:changes {:base M1 :datoms ...}` when conflict-free. Preserves 100% of `M1`, branch edits,
   evals (with original `:db/txInstant`), and branch-owned session attrs. Never rebases over conflicts."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/conn :map :keyword] :map]
                  [:=> [:cat :seon.host/conn :map :keyword [:maybe :map]] :map]]}
  ([main-conn cfg target-branch] (rebase-branch-onto-main! main-conn cfg target-branch nil))
  ([main-conn cfg target-branch opts]
   (if (or (nil? target-branch) (= target-branch :main) (not (store/branch? main-conn target-branch)))
     {:synced? false :rebased? false :ops 0 :conflicts []}
     (let [mdb @main-conn
           m-cid (d/commit-id mdb)
           bdb (store/head main-conn target-branch)
           b-cid (d/commit-id bdb)
           plan (merge/three-way main-conn cfg target-branch {:skip-health? true})
           conflicts (vec (:conflicts plan))
           reset-marker? (boolean (:reset-marker? opts))
           reviewer (:reviewer opts)
           reason (or (:reason opts)
                      (str "Sync :main into " target-branch
                           (when reviewer (str " after review by " reviewer))))]
       (cond
         (seq conflicts)
         {:synced? false :rebased? false :ops 0 :conflicts conflicts :synced-commit m-cid}

         (and (= (:base-commit plan) m-cid) (not reset-marker?))
         {:synced? true :rebased? false :up-to-date? true :ops 0 :conflicts []
          :synced-commit m-cid :branch-tx (:max-tx bdb)}

         :else
         (let [[schema-ops rest-ops] (split-schema-ops (:tx-data plan))
               {:keys [other-ops eval-groups]} (split-eval-ops-by-instant bdb rest-ops)
               db0 (if reset-marker?
                     (with-store-ids mdb []
                       (cond-> {:seon.tx/merged :main :seon.tx/synced-commit m-cid :seon.tx/reason reason}
                         reviewer (assoc :seon.tx/agent reviewer)))
                     mdb)
               db1 (if (seq (:schema-tx plan)) (with-store-ids db0 (:schema-tx plan) nil) db0)
               db2 (if (seq schema-ops) (with-store-ids db1 schema-ops nil) db1)
               db3 (if (seq other-ops) (with-store-ids db2 other-ops nil) db2)
               db4 (reduce (fn [d [inst ops]] (with-store-ids d ops (when inst {:db/txInstant inst})))
                           db3 eval-groups)
               {:keys [plain timed]} (session-carry-ops mdb bdb db4 target-branch nil)
               db5 (if (seq plain) (with-store-ids db4 plain nil) db4)
               db6 (reduce (fn [d [_ inst op]] (with-store-ids d [op] (when inst {:db/txInstant inst})))
                           db5 (sort-by first timed))
               min-tx (max (long (:max-tx mdb 0)) (long (:max-tx bdb 0)))
               marker-meta {:seon.tx/reason reason :seon.tx/synced-commit m-cid}
               {:keys [db ops]} (commit-rebased-db! db6 target-branch m-cid b-cid min-tx marker-meta)]
           {:synced? true :rebased? true :ops ops :conflicts []
            :synced-commit m-cid :branch-tx (:max-tx db)}))))))

(defn- clean-branch-entity [bdb mdb eid ref-id-attrs]
  (let [dh (or (:schema mdb) {})
        raw (dissoc (d/pull bdb '[*] eid) :db/id)]
    (reduce-kv
     (fn [acc k v]
       (if-not (contains? dh k)
         acc
         (if (= :db.type/ref (get-in dh [k :db/valueType]))
           (let [ref-id-attr (get ref-id-attrs k :seon.agent/id)
                 norm1 (fn [x]
                         (when-let [id-v (when (and (map? x) (:db/id x))
                                           (:v (first (d/datoms bdb :eavt (:db/id x) ref-id-attr))))]
                           (when (first (d/datoms mdb :avet ref-id-attr id-v))
                             [ref-id-attr id-v])))]
             (cond
               (and (map? v) (:db/id v)) (if-let [r (norm1 v)] (assoc acc k r) acc)
               (coll? v) (let [rs (vec (keep norm1 v))] (if (seq rs) (assoc acc k rs) acc))
               :else acc))
           (assoc acc k v))))
     {} raw)))

(defn ^:seon.flow/edge reset-router-branch-onto-main!
  "Heals a stale or drifted router branch onto `:main` (`M1`) via `d/force-branch!`, dropping any
   drifted code forms while preserving 100% of router evals (with original `:db/txInstant`),
   UI status entities, and branch-owned session attrs."
  {:malli/schema [:=> [:cat :seon.host/conn :keyword] :map]}
  [main-conn target-branch]
  (if (or (nil? target-branch) (= target-branch :main) (not (store/branch? main-conn target-branch)))
    {:healed? false :ops 0}
    (let [mdb @main-conn
          m-cid (d/commit-id mdb)
          bdb (store/head main-conn target-branch)
          b-cid (d/commit-id bdb)
          aids (set (branch-agent-ids mdb bdb target-branch))
          eval-groups
          (when (and (seq aids) (contains? (:schema bdb) :seon.agent.eval/agent))
            (->> aids
                 (mapcat #(d/datoms bdb :avet :seon.agent.eval/agent %))
                 (keep (fn [dt]
                         (let [eid (:e dt)
                               ev-id (:v (first (d/datoms bdb :eavt eid :seon.agent.eval/id)))]
                           (when (and ev-id (nil? (first (d/datoms mdb :avet :seon.agent.eval/id ev-id))))
                             (let [ent (clean-branch-entity bdb mdb eid {:seon.agent.eval/agent-ref :seon.agent/id})]
                               (when (:seon.agent.eval/id ent)
                                 [(long (:tx dt 0)) (tx-instant-of bdb (:tx dt)) ent]))))))
                 (group-by (fn [[tx inst _]] [tx inst]))
                 (sort-by (comp first key))
                 (mapv (fn [[[_ inst] xs]] [inst (mapv #(nth % 2) xs)]))))
          status-ops
          (when (and (seq aids) (contains? (:schema bdb) :seon.ui.status/agent))
            (vec (for [aid aids
                       dt (d/datoms bdb :avet :seon.ui.status/agent aid)
                       :let [ent (clean-branch-entity bdb mdb (:e dt) {:seon.ui.status/agent-ref :seon.agent/id})]
                       :when (:seon.ui.status/id ent)]
                   ent)))
          db1 (reduce (fn [d [inst ops]] (with-store-ids d ops (when inst {:db/txInstant inst})))
                      mdb eval-groups)
          db2 (if (seq status-ops) (with-store-ids db1 status-ops nil) db1)
          {:keys [plain timed]} (session-carry-ops mdb bdb db2 target-branch
                                                   #{:seon.agent/last-proofs :seon.agent/memory})
          db3 (if (seq plain) (with-store-ids db2 plain nil) db2)
          db4 (reduce (fn [d [_ inst op]] (with-store-ids d [op] (when inst {:db/txInstant inst})))
                      db3 (sort-by first timed))
          min-tx (max (long (:max-tx mdb 0)) (long (:max-tx bdb 0)))
          marker-meta {:seon.tx/reason (str "Heal router branch " target-branch " onto :main")
                       :seon.tx/synced-commit m-cid}
          {:keys [db ops]} (commit-rebased-db! db4 target-branch m-cid b-cid min-tx marker-meta)]
      {:healed? true :branch target-branch :ops ops :synced-commit m-cid :branch-tx (:max-tx db)})))

(defn ^:seon.flow/edge heal-stale-router-branches!
  "Boot-time self-heal: rebases any active router branch whose `d/merge-base` is behind `:main`."
  {:malli/schema [:=> [:cat :seon.host/conn] :int]}
  [main-conn]
  (let [mdb @main-conn
        m-cid (d/commit-id mdb)
        router-brs (when (contains? (:schema mdb) :seon.agent/router?)
                     (->> (d/datoms mdb :avet :seon.agent/router? true)
                          (keep (fn [dt]
                                  (let [a (d/pull mdb '[:seon.agent/branch :seon.agent/archived?] (:e dt))
                                        br (:seon.agent/branch a)]
                                    (when (and br (not= br :main) (not (:seon.agent/archived? a))
                                               (store/branch? main-conn br))
                                      br))))
                          distinct vec))]
    (reduce
     (fn [n br]
       (let [base (try (d/merge-base main-conn :main br) (catch Throwable _ nil))]
         (if (= base m-cid)
           n
           (try
             (reset-router-branch-onto-main! main-conn br)
             (inc n)
             (catch Throwable _ n)))))
     0 router-brs)))

(defn ^:seon.flow/edge sync-main-into-branch!
  "Syncs `:main` into `target-branch`: uses conflict-free commit-DAG rebase (`d/force-branch!`)
   so `d/merge-base` advances to `:main` and all 12 merge-base callers stay O(1); falls back to
   1-parent domain sync ONLY when unresolved merge conflicts exist (preserving conflict detection)."
  {:malli/schema [:=> [:cat :seon.host/conn :map :keyword [:maybe :keyword]] :map]}
  [main-conn cfg target-branch reviewer]
  (if (or (nil? target-branch) (= target-branch :main) (not (store/branch? main-conn target-branch)))
    {:synced? false :ops 0 :conflicts []}
    (let [res (rebase-branch-onto-main! main-conn cfg target-branch
                                       {:reviewer reviewer :reset-marker? true})]
      (if (or (:rebased? res) (:up-to-date? res))
        res
        (let [bconn (store/connect cfg target-branch)]
          (try
            (let [plan (merge/three-way bconn cfg :main {:skip-health? true})
                  dom-conflicts (filterv #(and (vector? (:entity %))
                                               (contains? sync-domain-id-attrs (first (:entity %))))
                                         (:conflicts plan))
                  tx-meta (cond-> {:seon.tx/merged :main
                                   :seon.tx/reason (str "Sync :main into " target-branch
                                                        (when reviewer (str " after review by " reviewer)))}
                            (:theirs-commit plan) (assoc :seon.tx/synced-commit (:theirs-commit plan))
                            reviewer (assoc :seon.tx/agent reviewer))
                  n-ops (apply-domain-sync-tx! bconn plan tx-meta)]
              {:synced? true :rebased? false :ops n-ops :conflicts dom-conflicts
               :synced-commit (:theirs-commit plan) :branch-tx (:max-tx @bconn)})
            (finally
              (d/release bconn))))))))

(defn ^:seon.flow/edge sync-coordinator-branches!
  "Rebases active router branches onto `:main` after a merge, advancing `d/merge-base` to `:main`.
   Skips safely on `:stale-branch-head` if a router committed concurrently; heals drifted routers."
  {:malli/schema [:=> [:cat :seon.host/conn :map :map :keyword] :int]}
  [main-conn cfg plan merged-branch]
  (let [mdb @main-conn
        has-ops? (or (seq (:schema-tx plan)) (some sync-domain-op? (:tx-data plan)))]
    (if-not has-ops?
      0
      (let [coord-brs (->> (when (contains? (:schema mdb) :seon.agent/router?) (d/datoms mdb :avet :seon.agent/router? true))
                           (keep (fn [dt]
                                   (let [a (d/pull mdb '[:seon.agent/branch :seon.agent/archived?] (:e dt))
                                         br (:seon.agent/branch a)]
                                     (when (and br (not= br :main) (not= br merged-branch)
                                                (not (:seon.agent/archived? a))
                                                (store/branch? main-conn br))
                                       br))))
                           distinct vec)]
        (reduce
         (fn [n cbr]
           (try
             (let [res (rebase-branch-onto-main! main-conn cfg cbr
                                                 {:reason (str "Sync :main into " cbr " after " merged-branch)})]
               (if (seq (:conflicts res))
                 (do (reset-router-branch-onto-main! main-conn cbr) (inc n))
                 (inc n)))
             (catch Throwable _ n)))
         0 coord-brs)))))
