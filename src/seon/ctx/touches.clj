(ns seon.ctx.touches
  "Layered derived context-touch & working-set engine: combines source-isolated in-memory
   `datahike.reads`, incremental `seon.ctx.diff/branch-changes` writes, attention-conserved working-set
   energy, and `seon.ctx.frontier` with ZERO persisted derived touch datoms."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx.diff :as diff]
            [seon.ctx.frontier :as frontier]
            [seon.store :as store :refer [db-schema live-cid]]
            [seon.render.text :refer [safe-long]]))

(def ^:private energy-decay 0.55)

(defonce ^:private ^:seon.flow/edge live-reads-state
  (atom {:by-key {} :key-gen {}}))

(defonce ^:private ^:seon.flow/edge branch-writes-cache
  (store/lru-cache 64))

(defonce ^:private ^:seon.flow/edge derived-touches-cache
  (store/lru-cache 64))

(defonce ^:private ^:seon.flow/edge eval-parse-cache
  (store/lru-cache 512))

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
  "Appends a per-call uncapped live read event into process RAM for `[store-id branch agent-id]`."
  {:malli/schema [:function
                  [:=> [:cat :any [:maybe :keyword] :keyword :int :any] :map]
                  [:=> [:cat :any [:maybe :keyword] :keyword :int :any [:maybe :map]] :map]]
   :cost/ticks 2000000}
  ([store-id branch agent-id turn eids]
   (record-live-reads-by-key! store-id branch agent-id turn eids nil))
  ([store-id branch agent-id turn eids meta-map]
   (let [es (into #{} (filter #(and (integer? %) (pos? (long %)))) (if (coll? eids) eids []))]
     (if (or (nil? agent-id) (empty? es))
       {:ok? true :recorded 0}
       (let [k (live-key store-id branch agent-id)
             t (max 1 (long turn))
             code-s (when (and (map? meta-map) (string? (:code meta-map))) (:code meta-map))
             ev (cond-> {:eids es} code-s (assoc :code code-s))]
         (swap! live-reads-state
                (fn [{:keys [by-key key-gen]}]
                  {:by-key (update-in by-key [k t] (fn [old] (cond (vector? old) (conj old ev) (set? old) [{:eids old} ev] :else [ev])))
                   :key-gen (update key-gen k (fnil inc 0))}))
         {:ok? true :recorded (count es)})))))

(defn ^:seon.flow/edge record-live-reads!
  "Records an uncapped live `datahike.reads` call for `agent-id` at `turn` in process RAM (zero LMDB writes)."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :keyword :int :any] :map]
                  [:=> [:cat [:maybe :seon.host/db] :keyword :int :any [:maybe :map]] :map]]
   :cost/ticks 2000000}
  ([db agent-id turn eids]
   (record-live-reads! db agent-id turn eids nil))
  ([db agent-id turn eids meta-map]
   (record-live-reads-by-key!
    (get-in db [:config :store :id])
    (get-in db [:config :branch] :main)
    agent-id turn eids meta-map)))

(defn ^:seon.flow/edge live-read-calls-for
  "Returns `{turn [{:eids #{...} :code ...} ...]}` of per-call uncapped live reads for `agent-id`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] :map]
   :cost/ticks 500000}
  [db agent-id]
  (if-not (and db agent-id)
    {}
    (let [k (live-key (get-in db [:config :store :id]) (get-in db [:config :branch] :main) agent-id)]
      (or (get-in @live-reads-state [:by-key k]) {}))))

(defn ^:seon.flow/edge live-reads-for
  "Returns `{turn #{eid ...}}` union of uncapped in-memory live `datahike.reads` for `agent-id`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :keyword]] :map]
   :cost/ticks 500000}
  [db agent-id]
  (let [by-t (live-read-calls-for db agent-id)]
    (if (empty? by-t)
      {}
      (reduce-kv (fn [acc t calls]
                   (assoc acc t (if (set? calls) calls (reduce (fn [s c] (set/union s (if (map? c) (:eids c) #{}))) #{} calls))))
                 {} by-t))))

(defn ^:seon.flow/edge clear-live-reads!
  "Evicts in-memory live `datahike.reads` for `agent-id` on merge/archive or test cleanup."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :seon.host/db] :keyword] :boolean]
                  [:=> [:cat :any [:maybe :keyword] :keyword] :boolean]]
   :cost/ticks 500000}
  ([db agent-id]
   (clear-live-reads! (get-in db [:config :store :id]) (get-in db [:config :branch] :main) agent-id))
  ([store-id branch agent-id]
   (when agent-id
     (let [k (live-key store-id branch agent-id)]
       (swap! live-reads-state
              (fn [{:keys [by-key key-gen]}]
                {:by-key (dissoc by-key k)
                 :key-gen (update key-gen k (fnil inc 0))}))))
   true))

(defn- resolve-change-attr [branch-db raw-a]
  (if (keyword? raw-a)
    raw-a
    (or (get-in branch-db [:ref-ident-map raw-a]) raw-a)))

(defn- canonicalize-eid [db dh e]
  (if (and (integer? e) (dh :seon.code.form/key))
    (if-let [fk (:v (first (d/datoms db :eavt e :seon.code.form/key)))]
      (cond
        (and (string? fk) (str/ends-with? fk "#ns") (dh :seon.code.ns/name))
        (or (:e (first (d/datoms db :avet :seon.code.ns/name (symbol (subs fk 0 (- (count fk) 3)))))) e)

        (and (string? fk) (str/includes? fk "/") (dh :seon.code.var/name))
        (let [[n s] (str/split fk #"/" 2)]
          (or (when (and (seq n) (seq s))
                (:e (first (d/datoms db :avet :seon.code.var/name (symbol n s)))))
              e))

        :else e)
      e)
    e))

(defn- owner-ns-eid [db dh e]
  (when (and (integer? e) (dh :seon.code.var/ns) (dh :seon.code.ns/name))
    (when-let [nsym (:v (first (d/datoms db :eavt e :seon.code.var/ns)))]
      (:e (first (d/datoms db :avet :seon.code.ns/name nsym))))))

(defn analyze-touch-sets
  "Pure set-algebra & attention-conserved working-set progression over chronological per-turn
   entries and branch writes: computes per-turn novelty, recurrence, `:counts`, `:written`,
   `:max-tx`, `:total-unique`, and exponential-decayed (`gamma = 0.55`) `:energy`."
  {:malli/schema [:=> [:cat [:vector :map] [:maybe :map]]
                  [:map [:counts :map] [:read-counts :map] [:written [:set :int]]
                   [:max-tx :map] [:by-turn [:vector :map]] [:total-unique :int] [:energy :map]]]
   :cost/ticks 20000000}
  [per-turn-sets written-map]
  (let [wm (if (map? written-map) written-map {})
        sorted-turns (vec (sort-by #(safe-long (:turn %) 0) per-turn-sets))
        max-turn (reduce (fn [m e] (max m (safe-long (:turn e) 0))) 0 sorted-turns)
        init {:seen #{} :read-counts {} :max-tx {} :by-turn [] :energy {}}
        {:keys [seen read-counts max-tx by-turn energy]}
        (reduce (fn [{:keys [seen read-counts max-tx by-turn energy]} entry]
                  (let [t (safe-long (:turn entry) 0)
                        tx (safe-long (:tx entry) 0)
                        age (max 0 (- max-turn t))
                        decay (Math/pow energy-decay (double age))
                        reids (into #{} (filter integer?) (or (:read-eids entry) (:eids entry) #{}))
                        explicit (into #{} (filter integer?) (or (:explicit-eids entry) #{}))
                        owners (if (map? (:owners entry)) (:owners entry) {})
                        calls (let [cs (:calls entry)]
                                (if (and (sequential? cs) (seq cs))
                                  cs
                                  (when (seq reids) [{:eids reids}])))
                        novel (set/difference reids seen)
                        recurr (set/intersection reids seen)
                        seen' (set/union seen reids)
                        rc' (reduce #(update %1 %2 (fnil inc 0)) read-counts reids)
                        mtx' (if (pos? tx)
                               (reduce #(update %1 %2 (fn [old] (max (safe-long old 0) tx))) max-tx reids)
                               max-tx)
                        eng1 (reduce (fn [em call]
                                       (let [ceids (into #{} (filter integer?) (:eids call))
                                             n (count ceids)]
                                         (if (zero? n)
                                           em
                                           (let [share (/ decay (double n))
                                                 em' (reduce #(update %1 %2 (fnil + 0.0) share) em ceids)
                                                 ns-shares (reduce (fn [nm e]
                                                                     (if-let [ne (get owners e)]
                                                                       (if (and (integer? ne) (not (contains? ceids ne)))
                                                                         (update nm ne (fnil + 0.0) (* 0.5 share))
                                                                         nm)
                                                                       nm))
                                                                   {} ceids)]
                                             (reduce-kv #(update %1 %2 (fnil + 0.0) %3) em' ns-shares)))))
                                     energy calls)
                        eng2 (if (seq explicit)
                               (let [eb (/ (* 1.5 decay) (Math/sqrt (double (count explicit))))]
                                 (reduce #(update %1 %2 (fnil + 0.0) eb) eng1 explicit))
                               eng1)]
                    {:seen seen' :read-counts rc' :max-tx mtx' :energy eng2
                     :by-turn (conj by-turn {:turn t :tx tx :novel novel :recurrent recurr :total (count reids)})}))
                init
                sorted-turns)
        written-set (into #{} (filter integer?) (keys wm))
        counts (reduce-kv
                (fn [acc e info]
                  (if (integer? e)
                    (let [w (max 1 (safe-long (when (map? info) (:writes info)) 1))]
                      (update acc e (fn [old] (+ (safe-long old 0) (* 2 w)))))
                    acc))
                read-counts
                wm)
        final-energy (reduce-kv
                      (fn [acc e info]
                        (if (integer? e)
                          (let [w (max 1 (safe-long (when (map? info) (:writes info)) 1))
                                boost (* 2.5 (+ 1.0 (Math/log1p (double w))))]
                            (update acc e (fnil + 0.0) boost))
                          acc))
                      energy
                      wm)
        final-max-tx (reduce-kv
                      (fn [acc e info]
                        (let [tx (safe-long (when (map? info) (:max-tx info)) 0)]
                          (if (and (integer? e) (pos? tx))
                            (update acc e (fn [old] (max (safe-long old 0) tx)))
                            acc)))
                      max-tx
                      wm)
        all-eids (set/union seen written-set)]
    {:counts counts
     :read-counts read-counts
     :written written-set
     :max-tx final-max-tx
     :by-turn by-turn
     :total-unique (count all-eids)
     :energy final-energy}))

(defn- eval-explicit-eids [db dh ev]
  (let [rs (:seon.agent.eval/result-schemas ev)
        rsk (:seon.agent.eval/result-schema ev)
        from-rs (when (coll? rs)
                  (keep #(cond (integer? %) % (and (map? %) (integer? (:db/id %))) (:db/id %) :else nil) rs))
        from-rsk (when (and (qualified-keyword? rsk) (dh :seon.schema/key))
                   (when-let [e (:e (first (d/datoms db :avet :seon.schema/key rsk)))] [e]))
        from-code (:all (frontier/extract-text-domain-eids db dh (:seon.agent.eval/code ev)))]
    (into #{} (concat from-rs from-rsk from-code))))

(defn- legacy-stored-eval-eids [db dh ee]
  (let [has-refs? (boolean (dh :seon.agent.eval/touched-refs))
        has-vec? (boolean (dh :seon.agent.eval/touched))
        r-ds (when has-refs? (seq (d/datoms db :eavt ee :seon.agent.eval/touched-refs)))]
    (cond
      r-ds (vec (keep #(let [e (:v %)] (when (integer? e) e)) r-ds))
      has-vec? (when-let [tdt (first (d/datoms db :eavt ee :seon.agent.eval/touched))]
                 (let [ts (:v tdt)] (when (coll? ts) (filterv integer? ts))))
      :else nil)))

(defn- ^:seon.flow/edge compute-branch-writes-for-base [branch-db base bc]
  (store/without-agent-eval-reads
   (fn []
     (if (or (nil? base) (= base bc))
       {}
       (let [dh (db-schema branch-db)
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
                            (or (diff/branch-changes branch-db base bc) []))]
         (reduce-kv
          (fn [acc e {:keys [txs max-tx]}]
            (let [w (max 1 (count txs))
                  ce (canonicalize-eid branch-db dh e)]
              (update acc ce (fn [prev]
                               {:writes (max w (long (:writes prev 0)))
                                :max-tx (max max-tx (long (:max-tx prev 0)))}))))
          {}
          per-tx))))))

(defn ^:seon.flow/edge branch-write-eids
  "Derives canonicalized domain entity EIDs written on `branch-db` since `diff/fast-merge-base` with `main-db`
   directly from incremental `diff/branch-changes` (O(1) single-parent diff + immutable `[sid base bc]` cache)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :int]] :map]
   :cost/ticks 10000000}
  [main-db branch-db self-agent-eid]
  (if-not branch-db
    {}
    (let [mc (live-cid main-db)
          bc (live-cid branch-db)
          sid (get-in branch-db [:config :store :id])
          same? (or (nil? main-db) (identical? main-db branch-db)
                    (and mc bc (= mc bc) (= (:max-tx main-db) (:max-tx branch-db))))
          raw (if (or same? (not (and mc bc sid (= sid (get-in main-db [:config :store :id])))))
                {}
                (if-let [base (diff/fast-merge-base main-db branch-db)]
                  (if (= base bc)
                    {}
                    (store/lru-get! branch-writes-cache 64 [sid base bc]
                                    #(compute-branch-writes-for-base branch-db base bc)))
                  {}))]
      (if (integer? self-agent-eid)
        (dissoc raw (long self-agent-eid))
        raw))))

(def ^:private ev-meta-pat
  '[:seon.agent.eval/turn :seon.agent.eval/code
    :seon.agent.eval/result-schema :seon.agent.eval/result-schemas])

(def ^:private ev-latest-pat
  '[:seon.agent.eval/turn :seon.agent.eval/code :seon.agent.eval/value
    :seon.agent.eval/out :seon.agent.eval/error :seon.agent.eval/reasoning
    :seon.agent.eval/thought :seon.agent.eval/result-schema :seon.agent.eval/result-schemas])

(defn- ^:seon.flow/edge parse-eval-meta [bdb dh csig canon ee tx]
  (let [compute (fn []
                  (let [ev (d/pull bdb ev-meta-pat ee)
                        t (max 1 (safe-long (:seon.agent.eval/turn ev) 1))
                        m-eids (into #{} (map canon) (eval-explicit-eids bdb dh ev))
                        leg (legacy-stored-eval-eids bdb dh ee)
                        leg-cnts (when (seq leg) (frequencies (mapv canon leg)))]
                    {:turn t :m-eids m-eids :leg-cnts leg-cnts}))]
    (if csig
      (store/lru-get! eval-parse-cache 512 [csig ee tx] compute)
      (compute))))

(defn- compute-derived-touches [mdb bdb agent-id o]
  (store/without-agent-eval-reads
   (fn []
     (let [dh (db-schema bdb)
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
           canon #(canonicalize-eid bdb dh %)
           csig (diff/code-db-sig bdb)
           live-calls-by-t (if (false? (:use-live-reads? o)) {} (live-read-calls-for bdb agent-id))
           eval-dts (when (dh :seon.agent.eval/agent)
                      (vec (d/datoms bdb :avet :seon.agent.eval/agent agent-id)))
           raw-writes (if (or same? (false? (:include-writes? o)))
                        {}
                        (into {} (remove #(archived? (key %))) (branch-write-eids mdb bdb self-eid)))
           written-nss (into #{}
                             (keep (fn [e]
                                     (or (when (dh :seon.code.ns/name) (:v (first (d/datoms bdb :eavt e :seon.code.ns/name))))
                                         (when (dh :seon.code.var/ns) (:v (first (d/datoms bdb :eavt e :seon.code.var/ns)))))))
                             (keys raw-writes))
           ev-data (reduce (fn [acc dt]
                             (let [ee (:e dt)
                                   tx (long (or (:tx dt) 0))
                                   {:keys [turn m-eids leg-cnts]} (parse-eval-meta bdb dh csig canon ee tx)
                                   m-clean (into #{} (remove archived?) m-eids)
                                   l-clean (when (seq leg-cnts)
                                             (reduce-kv (fn [a e c] (if (archived? e) a (assoc a e c))) {} leg-cnts))]
                               (-> acc
                                   (update-in [:turns turn :tx] #(max (safe-long % 0) tx))
                                   (update-in [:turns turn :eids-raw] (fnil conj []) ee)
                                   (update-in [:turns turn :explicit] (fn [old] (set/union (or old #{}) m-clean)))
                                   (update-in [:turns turn :eids] (fn [old] (set/union (or old #{}) m-clean (set (keys l-clean)))))
                                   (update :legacy-extra (fn [m]
                                                           (reduce-kv (fn [a e c] (if (> c 1) (update a e (fnil + 0) (dec c)) a))
                                                                      (or m {}) (or l-clean {})))))))
                           {:turns {} :legacy-extra {}}
                           eval-dts)
           all-turns (vec (sort (set/union (set (keys (:turns ev-data))) (set (keys live-calls-by-t)))))
           owners-cache (java.util.HashMap.)
           get-owner (fn [e]
                       (if (.containsKey owners-cache e)
                         (.get owners-cache e)
                         (let [ne (owner-ns-eid bdb dh e)]
                           (.put owners-cache e ne) ne)))
           explicit-all (volatile! #{})
           per-turn (mapv (fn [t]
                            (let [ev-t (get-in ev-data [:turns t])
                                  raw-calls (let [rc (get live-calls-by-t t [])] (if (set? rc) [{:eids rc}] rc))
                                  canon-calls (mapv (fn [c]
                                                      {:eids (into #{} (comp (map canon) (remove archived?)) (:eids c))})
                                                    raw-calls)
                                  l-union (reduce (fn [s c] (set/union s (:eids c))) #{} canon-calls)
                                  ex-eids (or (:explicit ev-t) #{})
                                  _ (vswap! explicit-all set/union ex-eids)
                                  reids (set/union (or (:eids ev-t) #{}) l-union)
                                  owners (reduce (fn [om e]
                                                   (if-let [ne (get-owner e)]
                                                     (assoc om e ne) om))
                                                 {} reids)]
                              {:turn t
                               :tx (long (or (:tx ev-t) (:max-tx bdb) 0))
                               :read-eids reids
                               :explicit-eids ex-eids
                               :calls (if (seq canon-calls) canon-calls (when (seq reids) [{:eids reids}]))
                               :owners owners}))
                          all-turns)
           base (analyze-touch-sets per-turn raw-writes)
           counts (reduce-kv (fn [m e extra] (update m e (fnil + 0) extra)) (:counts base) (:legacy-extra ev-data))
           latest-t (last all-turns)
           latest-evs (if latest-t
                        (mapv #(d/pull bdb ev-latest-pat %) (get-in ev-data [:turns latest-t :eids-raw] []))
                        [])
           latest-live (if latest-t (get live-calls-by-t latest-t []) [])
           turn-events (frontier/derive-turn-events bdb dh latest-evs latest-live)
           cont-scores (frontier/compute-continuation-scores bdb dh latest-evs canon archived?)
           frontier-scores (frontier/compute-novel-frontier bdb (:energy base) (:written base) @explicit-all cont-scores archived?)
           sig [(count counts) (count (:written base)) (count frontier-scores) (count (:error-schemas turn-events)) (reduce + 0 (vals counts))]]
       (assoc base
              :counts counts
              :written-nss written-nss
              :continuation cont-scores
              :novel-frontier frontier-scores
              :turn-events turn-events
              :sig sig)))))

(defn ^:seon.flow/edge derived-agent-touches
  "Incrementally cached layered working-set & frontier derivation for `agent-id`: combines live
   `datahike.reads`, `diff/branch-changes` writes, attention-conserved `:energy`, Turn `t-1`
   `:continuation`, ACL-PPR + RRF `:novel-frontier`, and `:turn-events` with ZERO persisted bloat."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword] [:? [:maybe :map]]] :map]
   :cost/ticks 25000000}
  ([main-db branch-db agent-id] (derived-agent-touches main-db branch-db agent-id nil))
  ([main-db branch-db agent-id opts]
   (let [bdb (or branch-db main-db)
         mdb (or main-db bdb)]
     (if-not (and bdb agent-id)
       {:counts {} :read-counts {} :written #{} :written-nss #{} :max-tx {} :by-turn []
        :total-unique 0 :energy {} :continuation {} :novel-frontier {}
        :turn-events {:has-error? false :error-schemas #{} :large-result? false} :sig [0 0 0 0 0]}
       (let [o (if (map? opts) opts {})
             sid (get-in bdb [:config :store :id])
             br (get-in bdb [:config :branch] :main)
             mc (live-cid mdb)
             bc (live-cid bdb)
             same? (or (identical? mdb bdb) (and mc bc (= mc bc)))
             base (when (and sid mc bc) (if same? bc (diff/fast-merge-base mdb bdb)))
             use-live? (not (false? (:use-live-reads? o)))
             inc-writes? (not (false? (:include-writes? o)))
             lgen (if use-live? (get-in @live-reads-state [:key-gen (live-key sid br agent-id)] 0) -1)]
         (if (and sid bc base)
           (store/lru-get! derived-touches-cache 64
                           [sid base bc (:max-tx bdb) agent-id lgen use-live? inc-writes?]
                           #(compute-derived-touches mdb bdb agent-id o))
           (compute-derived-touches mdb bdb agent-id o)))))))