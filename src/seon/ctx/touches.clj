(ns seon.ctx.touches
  "Derived raw-Datahike datoms-touched engine: combines uncapped live in-memory `datahike.reads`,
   raw branch mutations from `d/changes` (`d/merge-base` -> head), and eval mentions via pure
   set-difference progression with ZERO persisted `:touched` / `:touched-refs` storage bloat."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.links :as rlinks :refer [db-schema live-cid]]
            [seon.store :as store]))

(def ^:private max-live-agents 64)

(defonce ^:private ^:seon.flow/edge live-reads-state
  (atom {:order [] :by-key {}}))

(defonce ^:private ^:seon.flow/edge branch-writes-cache
  (store/lru-cache 32))

(defn- excluded-attr? [a]
  (when (qualified-keyword? a)
    (let [ns-s (namespace a)]
      (or (= a :db/ident)
          (= ns-s "db")
          (= ns-s "seon.tx")
          (= ns-s "seon.schema")
          (= ns-s "seon.agent.eval")))))

(defn- live-key [store-id branch agent-id]
  [store-id (or branch :main) agent-id])

(defn ^:seon.flow/edge record-live-reads-by-key!
  "Merges an uncapped set of domain EIDs into the in-memory per-turn read log for `[store-id branch agent-id]`."
  {:malli/schema [:=> [:cat :any [:maybe :keyword] :keyword :int :any] :map]
   :cost/ticks 2000000}
  [store-id branch agent-id turn eids]
  (let [es (into #{} (filter #(and (integer? %) (pos? (long %)))) (if (coll? eids) eids []))]
    (if (or (nil? agent-id) (empty? es))
      {:ok? true :recorded 0}
      (let [k (live-key store-id branch agent-id)
            t (max 1 (long turn))]
        (swap! live-reads-state
               (fn [{:keys [order by-key]}]
                 (let [prev (get by-key k {})
                       merged (update prev t (fn [old] (if (set? old) (set/union old es) es)))
                       ord' (conj (filterv #(not= % k) order) k)
                       drop-n (max 0 (- (count ord') max-live-agents))
                       evicted (when (pos? drop-n) (subvec ord' 0 drop-n))
                       ord'' (if (pos? drop-n) (subvec ord' drop-n) ord')
                       by' (cond-> (assoc by-key k merged)
                             (seq evicted) (#(apply dissoc % evicted)))]
                   {:order ord'' :by-key by'})))
        {:ok? true :recorded (count es)}))))

(defn ^:seon.flow/edge record-live-reads!
  "Records uncapped live `datahike.reads` domain EIDs for `agent-id` at `turn` in process RAM (zero LMDB writes)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :keyword :int :any] :map]
   :cost/ticks 2000000}
  [db agent-id turn eids]
  (record-live-reads-by-key!
   (get-in db [:config :store :id])
   (get-in db [:config :branch] :main)
   agent-id turn eids))

(defn ^:seon.flow/edge live-reads-for
  "Returns `{turn #{eid ...}}` of uncapped in-memory live `datahike.reads` captured for `agent-id`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] :map]
   :cost/ticks 500000}
  [db agent-id]
  (if-not (and db agent-id)
    {}
    (let [sid (get-in db [:config :store :id])
          br (get-in db [:config :branch] :main)]
      (or (get-in @live-reads-state [:by-key (live-key sid br agent-id)]) {}))))

(defn ^:seon.flow/edge clear-live-reads!
  "Clears in-memory live `datahike.reads` for `agent-id` on `db` (test/cleanup helper)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :keyword] :boolean]
   :cost/ticks 500000}
  [db agent-id]
  (let [k (live-key (get-in db [:config :store :id]) (get-in db [:config :branch] :main) agent-id)]
    (swap! live-reads-state
           (fn [{:keys [order by-key]}]
             {:order (filterv #(not= % k) order)
              :by-key (dissoc by-key k)}))
    true))

(defn- resolve-change-attr [branch-db raw-a]
  (if (keyword? raw-a)
    raw-a
    (or (get-in branch-db [:ref-ident-map raw-a]) raw-a)))

(defn- compute-branch-writes [main-db branch-db]
  (let [mc (live-cid main-db)
        bc (live-cid branch-db)
        sid (get-in branch-db [:config :store :id])
        same? (or (nil? main-db) (identical? main-db branch-db)
                  (and mc bc (= mc bc) (= (:max-tx main-db) (:max-tx branch-db))))]
    (if (or same? (not (and mc bc (= sid (get-in main-db [:config :store :id])))))
      {}
      (let [base (try (d/merge-base branch-db mc bc) (catch Throwable _ nil))]
        (if (or (nil? base) (= base bc))
          {}
          (let [dh (db-schema branch-db)
                has-fkey? (contains? dh :seon.code.form/key)
                has-vname? (contains? dh :seon.code.var/name)
                per-tx (reduce (fn [acc dt]
                                 (let [a (resolve-change-attr branch-db (:a dt))
                                       e (:e dt)
                                       tx (long (or (:tx dt) 0))]
                                   (if (and (integer? e) (pos? (long e))
                                            (qualified-keyword? a)
                                            (not (excluded-attr? a))
                                            (not (contains? dh e)))
                                     (-> acc
                                         (update-in [e :txs] (fnil conj #{}) tx)
                                         (update-in [e :max-tx] #(max (long (or % 0)) tx)))
                                     acc)))
                               {}
                               (try (d/changes branch-db base bc) (catch Throwable _ [])))]
            (reduce-kv
             (fn [acc e {:keys [txs max-tx]}]
               (let [w (max 1 (count txs))
                     acc' (assoc acc e {:writes w :max-tx max-tx})
                     fk (when has-fkey? (:v (first (d/datoms branch-db :eavt e :seon.code.form/key))))
                     vsym (when (and (string? fk) (str/includes? fk "/") (not (str/ends-with? fk "#ns")))
                            (let [[n s] (str/split fk #"/" 2)]
                              (when (and (seq n) (seq s)) (symbol n s))))
                     ve (when (and vsym has-vname?)
                          (:e (first (d/datoms branch-db :avet :seon.code.var/name vsym))))]
                 (if (and (integer? ve) (not= ve e))
                   (update acc' ve (fn [prev]
                                     {:writes (max w (long (:writes prev 0)))
                                      :max-tx (max max-tx (long (:max-tx prev 0)))}))
                   acc')))
             {}
             per-tx)))))))

(defn ^:seon.flow/edge branch-write-eids
  "Derives domain entity EIDs written on `branch-db` since `d/merge-base` with `main-db`
   directly from raw Datahike `d/changes` (commit-cached, zero persisted derived datoms)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :int]] :map]
   :cost/ticks 10000000}
  [main-db branch-db self-agent-eid]
  (if-not branch-db
    {}
    (let [mc (live-cid main-db)
          bc (live-cid branch-db)
          sid (get-in branch-db [:config :store :id])
          raw (if (and mc bc sid)
                (store/lru-get! branch-writes-cache 32 [sid mc (:max-tx main-db) bc (:max-tx branch-db)]
                                #(compute-branch-writes main-db branch-db))
                (compute-branch-writes main-db branch-db))]
      (if (integer? self-agent-eid)
        (dissoc raw (long self-agent-eid))
        raw))))

(defn analyze-touch-sets
  "Pure set-algebra progression over chronological per-turn touched EID sets and branch writes:
   computes per-turn novelty (`set/difference`), recurrence (`set/intersection`), weighted `:counts`
   (`read-turns + 2 * writes`), `:written`, `:max-tx`, and `:total-unique`."
  {:malli/schema [:=> [:cat [:vector :map] [:maybe :map]]
                  [:map [:counts :map] [:read-counts :map] [:written [:set :int]]
                   [:max-tx :map] [:by-turn [:vector :map]] [:total-unique :int]]]
   :cost/ticks 15000000}
  [per-turn-sets written-map]
  (let [wm (if (map? written-map) written-map {})
        sorted-turns (sort-by #(long (or (:turn %) 0)) per-turn-sets)
        init {:seen #{} :read-counts {} :max-tx {} :by-turn []}
        {:keys [seen read-counts max-tx by-turn]}
        (reduce (fn [{:keys [seen read-counts max-tx by-turn]} entry]
                  (let [t (long (or (:turn entry) 0))
                        tx (long (or (:tx entry) 0))
                        reids (into #{} (filter integer?) (or (:read-eids entry) (:eids entry) #{}))
                        novel (set/difference reids seen)
                        recurr (set/intersection reids seen)
                        seen' (set/union seen reids)
                        rc' (reduce #(update %1 %2 (fnil inc 0)) read-counts reids)
                        mtx' (if (pos? tx)
                               (reduce #(update %1 %2 (fn [old] (max (long (or old 0)) tx))) max-tx reids)
                               max-tx)]
                    {:seen seen' :read-counts rc' :max-tx mtx'
                     :by-turn (conj by-turn {:turn t :tx tx :novel novel :recurrent recurr :total (count reids)})}))
                init
                sorted-turns)
        written-set (into #{} (filter integer?) (keys wm))
        counts (reduce-kv
                (fn [acc e info]
                  (if (integer? e)
                    (let [w (max 1 (long (if (map? info) (or (:writes info) 1) 1)))]
                      (update acc e (fn [old] (+ (long (or old 0)) (* 2 w)))))
                    acc))
                read-counts
                wm)
        final-max-tx (reduce-kv
                      (fn [acc e info]
                        (let [tx (long (if (map? info) (or (:max-tx info) 0) 0))]
                          (if (and (integer? e) (pos? tx))
                            (update acc e (fn [old] (max (long (or old 0)) tx)))
                            acc)))
                      max-tx
                      wm)
        all-eids (set/union seen written-set)]
    {:counts counts
     :read-counts read-counts
     :written written-set
     :max-tx final-max-tx
     :by-turn by-turn
     :total-unique (count all-eids)}))

(defn- eval-mentioned-eids [db dh ev]
  (let [rs (:seon.agent.eval/result-schemas ev)
        rsk (:seon.agent.eval/result-schema ev)
        code-s (:seon.agent.eval/code ev)
        from-rs (when (coll? rs)
                  (keep #(cond (integer? %) % (and (map? %) (integer? (:db/id %))) (:db/id %) :else nil) rs))
        from-rsk (when (and (qualified-keyword? rsk) (dh :seon.schema/key))
                   (when-let [e (:e (first (d/datoms db :avet :seon.schema/key rsk)))] [e]))
        from-code (when (and (string? code-s) (not (str/blank? code-s)))
                    (let [qsyms (keep #(let [s (or (nth % 1) (nth % 2))]
                                         (when (and (string? s) (< (count s) 128))
                                           (if (str/includes? s "/")
                                             (let [[a b] (str/split s #"/" 2)]
                                               (when (and (seq a) (seq b) (not= a "result")) (symbol a b)))
                                             (when (str/includes? s ".") (symbol s)))))
                                      (re-seq #"(?:'([a-zA-Z][\w.-]*(?:/[\w.*?!+-]+)?)|\(quote\s+([a-zA-Z][\w.-]*(?:/[\w.*?!+-]+)?)\))" code-s))]
                      (keep (fn [sym]
                              (let [a (if (namespace sym) :seon.code.var/name :seon.code.ns/name)]
                                (when (dh a) (:e (first (d/datoms db :avet a sym))))))
                            qsyms)))]
    (into #{} (concat from-rs from-rsk from-code))))

(defn- legacy-stored-eval-eids [db dh ee]
  (let [has-refs? (boolean (dh :seon.agent.eval/touched-refs))
        has-vec? (boolean (dh :seon.agent.eval/touched))
        r-ds (when has-refs? (seq (d/datoms db :eavt ee :seon.agent.eval/touched-refs)))]
    (cond
      r-ds (vec (keep #(let [e (:v %)] (when (integer? e) e)) r-ds))
      has-vec? (when-let [tdt (first (d/datoms db :eavt ee :seon.agent.eval/touched))]
                 (let [ts (:v tdt)]
                   (when (coll? ts) (filterv integer? ts))))
      :else nil)))

(defn derived-agent-touches
  "Derives uncapped per-entity touch counts, branch writes, per-turn novelty, and latest tx for `agent-id`
   from (1) live in-memory `datahike.reads`, (2) raw `d/changes` branch writes, (3) eval code/result-schema
   mentions, and (4) read-only legacy `:touched`/`:touched-refs` fallback on archived/fixture DBs."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword] [:? [:maybe :map]]] :map]
   :cost/ticks 25000000}
  ([main-db branch-db agent-id] (derived-agent-touches main-db branch-db agent-id nil))
  ([main-db branch-db agent-id opts]
   (let [bdb (or branch-db main-db)
         mdb (or main-db bdb)]
     (if-not (and bdb agent-id)
       {:counts {} :read-counts {} :written #{} :max-tx {} :by-turn [] :total-unique 0 :sig [0 0 0]}
       (let [o (if (map? opts) opts {})
             dh (db-schema bdb)
             mdh (db-schema mdb)
             same? (or (identical? mdb bdb) (= (live-cid mdb) (live-cid bdb)))
             self-eid (or (when (dh :seon.agent/id) (:e (first (d/datoms bdb :avet :seon.agent/id agent-id))))
                          (when (and (not same?) (mdh :seon.agent/id))
                            (:e (first (d/datoms mdb :avet :seon.agent/id agent-id)))))
             archived? (fn [e]
                         (boolean
                          (or (= e self-eid)
                              (and (dh :seon.agent/archived?)
                                   (true? (:v (first (d/datoms bdb :eavt e :seon.agent/archived?)))))
                              (and (not same?) (mdh :seon.agent/archived?)
                                   (true? (:v (first (d/datoms mdb :eavt e :seon.agent/archived?))))))))
             live-by-turn (if (false? (:use-live-reads? o)) {} (live-reads-for bdb agent-id))
             eval-dts (when (dh :seon.agent.eval/agent)
                        (vec (d/datoms bdb :avet :seon.agent.eval/agent agent-id)))
             raw-writes (if (or same? (false? (:include-writes? o)))
                          {}
                          (into {} (remove #(archived? (key %))) (branch-write-eids mdb bdb self-eid)))
             ev-by-turn (reduce (fn [acc dt]
                                  (let [ee (:e dt)
                                        tx (long (or (:tx dt) 0))
                                        ev (d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/code
                                                         :seon.agent.eval/result-schema :seon.agent.eval/result-schemas]
                                                   ee)
                                        t (max 1 (long (or (:seon.agent.eval/turn ev) 1)))
                                        m-eids (eval-mentioned-eids bdb dh ev)
                                        leg (legacy-stored-eval-eids bdb dh ee)
                                        ;; Preserve multi-occurrence legacy counts if explicitly present in a fixture
                                        leg-cnts (when (seq leg) (frequencies (remove archived? leg)))]
                                    (-> acc
                                        (update-in [:turns t :tx] #(max (long (or % 0)) tx))
                                        (update-in [:turns t :eids] (fn [old] (set/union (or old #{}) (into #{} (remove archived?) m-eids) (set (keys leg-cnts)))))
                                        (update :legacy-extra (fn [m]
                                                                (reduce-kv (fn [a e c] (if (> c 1) (update a e (fnil + 0) (dec c)) a))
                                                                           (or m {}) (or leg-cnts {})))))))
                                {:turns {} :legacy-extra {}}
                                eval-dts)
             all-turns (sort (set/union (set (keys (:turns ev-by-turn))) (set (keys live-by-turn))))
             per-turn (mapv (fn [t]
                              (let [ev-t (get-in ev-by-turn [:turns t])
                                    l-eids (into #{} (remove archived?) (get live-by-turn t #{}))]
                                {:turn t
                                 :tx (long (or (:tx ev-t) (:max-tx bdb) 0))
                                 :read-eids (set/union (or (:eids ev-t) #{}) l-eids)}))
                            all-turns)
             analyzed (if (and (empty? per-turn) (empty? raw-writes))
             {:counts {} :read-counts {} :written #{} :max-tx {} :by-turn [] :total-unique 0}
             (analyze-touch-sets per-turn raw-writes))
             counts' (reduce-kv (fn [acc e extra] (update acc e (fnil + 0) extra))
                                (:counts analyzed)
                                (:legacy-extra ev-by-turn))]
         (assoc analyzed
                :counts counts'
                :sig [(count counts') (reduce + 0 (vals counts')) (count (:written analyzed))]))))))
