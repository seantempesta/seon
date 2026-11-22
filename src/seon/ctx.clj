(ns seon.ctx
  "Reactive `@seon.ctx/ctx` pull atom backed by `datahike.reads` (`O(|Δ|)` refresh + `swap!` write-back):
   - Every agent runtime binds `seon.ctx/ctx` (aliased as `user/ctx`), a standard Clojure `IDeref`/`IAtom`.
   - Reading `@seon.ctx/ctx` returns the agent's entity pulled via its `:seon.ctx/pattern` (defaulting to
     its task, focus namespace tree via `:seon.code.ns/_parent` + `:seon.code.var/_ns-ref`, and unread mail).
   - Long-running agents customize what they see by `swap!`ing `:seon.ctx/pattern` directly on `@ctx`!
   - `advance!` checks `datahike.reads/reads-touched?` per pull clause so untouched subtrees stay
     `identical?` (`0` DB seeks), and `swap!`/`reset!` diffs `old -> new`, validates via Malli, and
     transacts straight to the agent's branch DB."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [clojure.string :as str]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store :as store]))

(def default-agent-pattern
  "Default Datahike pull pattern for an agent entity: pulls own fields, focus namespace subtree
   (`:seon.code.ns/_parent` + `:seon.code.var/_ns-ref`), and its customizable `:seon.ctx/pattern`."
  [:db/id
   :seon.agent/id
   :seon.agent/parent
   :seon.agent/scope
   :seon.agent/branch
   :seon.agent/task
   :seon.agent/role
   :seon.agent/goal
   :seon.agent/render-fn
   :seon.agent/pinned
   :seon.agent/stage
   :seon.agent/canvas
   :seon.agent/auto-merge?
   :seon.agent/waiting?
   :seon.agent/ready?
   :seon.agent/awaiting
   :seon.agent/memory
   :seon.agent/context-prefs
   :seon.ctx/pattern
   {:seon.agent/focus
    [:db/id
     :seon.code.ns/name
     :seon.code.ns/doc
     :seon.code.ns/path
     {:seon.code.var/_ns-ref
      [:db/id
       :seon.code.var/name
       :seon.code.var/doc
       :seon.code.var/arglists
       :seon.code.var/schema
       :seon.code.var/refs
       :seon.code.var/schema-refs
       :seon.code.var/keywords]}
     ;; Bounded 1-level child summary by default (keeps root fast & compact; agents can swap! :seon.ctx/pattern to zoom deeper!)
     {:seon.code.ns/_parent
      [:db/id
       :seon.code.ns/name
       :seon.code.ns/doc
       :seon.code.ns/path]}]}])

(defn- clause-key [clause]
  (cond
    (keyword? clause) clause
    (map? clause) (ffirst clause)
    (vector? clause) (first clause)
    :else clause))

(defn- pull-clause [db eid clause]
  (let [[res rs] (reads/with-reads #(d/pull db [clause] eid))
        k (clause-key clause)]
    {:clause clause
     :key k
     :val (get res k ::absent)
     :reads-idx (reads/reads-index rs)}))

(defn- effective-pattern [db eid override-pattern]
  (or override-pattern
      (let [p (:seon.ctx/pattern (d/pull db '[:seon.ctx/pattern] eid))]
        (when (and (vector? p) (seq p)) p))
      default-agent-pattern))

(defn- assemble-map [db eid entries]
  (let [base (reduce (fn [acc {:keys [key val]}]
                       (if (= val ::absent) acc (assoc acc key val)))
                     {:db/id eid}
                     entries)]
    (with-meta
      (assoc base
             :seon.ctx/branch (get-in db [:config :branch] :main)
             :seon.ctx/tx (:max-tx db)
             :seon.ctx/commit (d/commit-id db))
      {:seon/schema :seon.agent/entity})))

(defn build
  "Builds an incremental pull snapshot `{:eid :pattern :entries :val :tx :commit}` for entity `eid` on `db`.
   Each pull clause records its own `datahike.reads/reads-index` so subsequent `advance` calls skip untouched clauses!"
  ([db eid] (build db eid nil))
  ([db eid pattern]
   (let [resolved-eid (if (vector? eid) (:db/id (d/pull db '[:db/id] eid)) eid)]
     (when resolved-eid
       (let [pat (effective-pattern db resolved-eid pattern)
             has-star? (some #(= '* %) pat)
             entries (if has-star?
                       (let [[res rs] (reads/with-reads #(d/pull db pat resolved-eid))]
                         [{:clause '* :key '* :val res :reads-idx (reads/reads-index rs)}])
                       (mapv #(pull-clause db resolved-eid %) pat))
             val (if has-star?
                   (with-meta
                     (assoc (:val (first entries))
                            :seon.ctx/branch (get-in db [:config :branch] :main)
                            :seon.ctx/tx (:max-tx db)
                            :seon.ctx/commit (d/commit-id db))
                     {:seon/schema :seon.agent/entity})
                   (assemble-map db resolved-eid entries))]
         {:eid resolved-eid
          :pattern pat
          :has-star? has-star?
          :entries entries
          :val val
          :tx (:max-tx db)
          :commit (d/commit-id db)})))))

(defn advance
  "Incrementally updates `snap` to `db'` given `tx-datoms` (`O(|Δ|)`):
   - If `:seon.ctx/pattern` changed, rebuilds cleanly from the new pattern.
   - Otherwise re-pulls ONLY clauses whose `reads-index` intersects `tx-datoms`, keeping every
     untouched subtree `identical?`!"
  [snap db' tx-datoms]
  (cond
    (nil? snap) nil
    (= (:commit snap) (d/commit-id db')) snap
    :else
    (let [eid (:eid snap)
          pattern-moved? (boolean (some #(and (= (:e %) eid) (= (:a %) :seon.ctx/pattern)) tx-datoms))]
      (if (or pattern-moved? (:has-star? snap))
        (build db' eid nil)
        (let [changed? (volatile! false)
              entries' (mapv (fn [entry]
                               (if (or (nil? tx-datoms)
                                       (reads/reads-touched? (:reads-idx entry) tx-datoms))
                                 (let [fresh (pull-clause db' eid (:clause entry))]
                                   (when (not= (:val fresh) (:val entry))
                                     (vreset! changed? true))
                                   fresh)
                                 entry))
                             (:entries snap))
              val' (if @changed?
                     (assemble-map db' eid entries')
                     (with-meta
                       (assoc (:val snap)
                              :seon.ctx/branch (get-in db' [:config :branch] :main)
                              :seon.ctx/tx (:max-tx db')
                              :seon.ctx/commit (d/commit-id db'))
                       (meta (:val snap))))]
          (assoc snap
                 :entries entries'
                 :val val'
                 :tx (:max-tx db')
                 :commit (d/commit-id db')))))))

(def ^:private readonly-env-keys
  #{:db/id :seon.ctx/branch :seon.ctx/tx :seon.ctx/commit})

(defn- reverse-attr? [k]
  (and (qualified-keyword? k) (.startsWith (name k) "_")))

(defn diff-entity-tx
  "Diffs two pulled maps `old-m` and `new-m` for entity `eid` into Datahike tx-data (`O(|Δ|)`).
   Supports nested edits on pulled ref entities that carry `:db/id`."
  [db eid old-m new-m]
  (let [schema (:schema db)
        all-ks (disj (set/union (set (keys old-m)) (set (keys new-m)))
                     :db/id :seon.ctx/branch :seon.ctx/tx :seon.ctx/commit)]
    (reduce
     (fn [acc k]
       (if (or (contains? readonly-env-keys k) (reverse-attr? k))
         acc
         (let [ov (get old-m k ::absent)
               nv (get new-m k ::absent)]
           (cond
             (or (identical? ov nv) (= ov nv))
             acc

             (and (map? ov) (map? nv) (:db/id ov) (= (:db/id ov) (:db/id nv)))
             (into acc (diff-entity-tx db (:db/id ov) ov nv))

             (= nv ::absent)
             (if (= :db.cardinality/many (get-in schema [k :db/cardinality]))
               (into acc (map (fn [item] [:db/retract eid k item]) ov))
               (conj acc [:db/retract eid k ov]))

             (= :db.cardinality/many (get-in schema [k :db/cardinality]))
             (let [os (set (when-not (= ov ::absent) ov))
                   ns (set (if (coll? nv) nv [nv]))
                   to-rem (set/difference os ns)
                   to-add (set/difference ns os)]
               (-> acc
                   (into (map (fn [item] [:db/retract eid k item]) to-rem))
                   (into (map (fn [item] [:db/add eid k item]) to-add))))

             :else
             (conj acc [:db/add eid k nv])))))
     []
     all-ks)))

(defprotocol ICtxAtom
  (-advance! [this db' tx-datoms] "Advance internal snapshot incrementally from committed `db'` and `tx-datoms`.")
  (-snapshot [this] "Return internal incremental snapshot map (with per-clause `reads-idx`)."))

(deftype CtxAtom [conn ^:volatile-mutable snap ^:volatile-mutable watches ^:volatile-mutable validator]
  clojure.lang.IDeref
  (deref [_] (:val snap))

  clojure.lang.IRef
  (addWatch [this k f]
    (set! watches (assoc watches k f))
    this)
  (removeWatch [this k]
    (set! watches (dissoc watches k))
    this)
  (getWatches [_] watches)
  (getValidator [_] validator)
  (setValidator [_ vf] (set! validator vf))

  ICtxAtom
  (-snapshot [_] snap)
  (-advance! [this db' tx-datoms]
    (let [old-val (:val snap)
          snap' (advance snap db' tx-datoms)]
      (set! snap snap')
      (let [new-val (:val snap')]
        (when (and (seq watches) (not= old-val new-val))
          (doseq [[k wf] watches] (wf k this old-val new-val)))
        new-val)))

  clojure.lang.IAtom
  (reset [this new-val]
    (when-not (map? new-val)
      (throw (ex-info "ctx value must be a map" {:error :seon.ctx/invalid :got new-val})))
    (when validator
      (when-not (validator new-val)
        (throw (ex-info "ctx validator rejected value" {:error :seon.ctx/invalid}))))
    (let [old-val (:val snap)
          db @conn
          tx (diff-entity-tx db (:eid snap) old-val new-val)]
      (if (empty? tx)
        old-val
        (let [res (d/transact conn {:tx-data tx})]
          (-advance! this (:db-after res) (:tx-data res))))))
  (swap [this f] (.reset this (f (:val snap))))
  (swap [this f a] (.reset this (f (:val snap) a)))
  (swap [this f a b] (.reset this (f (:val snap) a b)))
  (swap [this f a b args] (.reset this (apply f (:val snap) a b args)))
  (compareAndSet [this oldv newv]
    (if (= oldv (:val snap))
      (do (.reset this newv) true)
      false)))

(defn ctx-atom
  "Creates a reactive `CtxAtom` for entity `eid` (or lookup ref `[:seon.agent/id id]`) on `conn`."
  ([conn eid] (ctx-atom conn eid nil))
  ([conn eid pattern]
   (CtxAtom. conn (build @conn eid pattern) {} nil)))

(def ^:dynamic ctx
  "The active agent's private reactive `CtxAtom` inside a forked agent runtime (also aliased as `user/ctx`)."
  nil)

(defn refresh-active!
  "Advances `seon.ctx/ctx` incrementally if bound and `conn` moved."
  [db' tx-datoms]
  (when (and ctx (satisfies? ICtxAtom ctx))
    (-advance! ctx db' tx-datoms)))

(defn- reverse-agent-ref-attrs [db]
  (let [dh-sch (:schema db)
        sch-eid (when (contains? dh-sch :seon.schema/key)
                  (:e (first (d/datoms db :avet :seon.schema/key :seon.agent/entity))))
        from-db (when (and sch-eid (contains? dh-sch :seon.schema/ref-targets))
                  (keep #(:v (first (d/datoms db :eavt (:e %) :seon.schema/key)))
                        (d/datoms db :avet :seon.schema/ref-targets sch-eid)))
        from-ix (six/reaching (schema/entity-index db) :seon.agent/entity)]
    (->> (concat from-db from-ix)
         (filter #(= :db.type/ref (get-in dh-sch [% :db/valueType])))
         distinct sort vec)))

(defn- merge-forward-datoms [main-db branch-db eid]
  (let [m-ds (when main-db (vec (d/datoms main-db :eavt eid)))
        b-ds (when (and branch-db (not= branch-db main-db)) (vec (d/datoms branch-db :eavt eid)))]
    (if (empty? b-ds)
      (or m-ds [])
      (let [m-by-a (group-by :a m-ds)
            b-by-a (group-by :a b-ds)
            all-a (sort (set/union (set (keys m-by-a)) (set (keys b-by-a))))
            max-tx (fn [ds] (reduce max 0 (map #(long (or (:tx %) 0)) ds)))]
        (vec (mapcat (fn [a]
                       (let [ms (get m-by-a a) bs (get b-by-a a)]
                         (cond
                           (empty? ms) bs
                           (empty? bs) ms
                           (>= (max-tx bs) (max-tx ms)) bs
                           :else ms)))
                     all-a))))))

(defn- resolve-lookup-eid [main-db branch-db ref]
  (when (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)))
    (let [[a v] ref]
      (or (when (and branch-db (contains? (:schema branch-db) a))
            (:e (first (d/datoms branch-db :avet a v))))
          (when (and main-db (not= main-db branch-db) (contains? (:schema main-db) a))
            (:e (first (d/datoms main-db :avet a v))))))))

(defn agent-world-datoms
  "Generic 2-read agent discovery (zero hardcoded pull list):
   1. Forward `:eavt` read on `agent-eid` merged across `main-db` + `branch-db` by latest `:tx`.
   2. Reverse `:avet` reads for EVERY `:db.type/ref` attr whose `:seon.schema/ref-targets` points to `:seon.agent/entity`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map]
   :cost/ticks 8000000}
  [main-db branch-db agent-id]
  (let [bdb (or branch-db main-db)
        agent-eid (or (when (contains? (:schema bdb) :seon.agent/id)
                        (:e (first (d/datoms bdb :avet :seon.agent/id agent-id))))
                      (when (contains? (:schema main-db) :seon.agent/id)
                        (:e (first (d/datoms main-db :avet :seon.agent/id agent-id)))))]
    (if-not agent-eid
      {:agent-eid nil :forward [] :reverse [] :reverse-attrs [] :connected {} :pinned #{}}
      (let [fwd (merge-forward-datoms main-db bdb agent-eid)
            rev-attrs (reverse-agent-ref-attrs bdb)
            rev-ds (vec (distinct
                         (mapcat (fn [a]
                                   (concat
                                    (when (contains? (:schema main-db) a) (d/datoms main-db :avet a agent-eid))
                                    (when (and (not= bdb main-db) (contains? (:schema bdb) a))
                                      (d/datoms bdb :avet a agent-eid))))
                                 rev-attrs)))
            dh-sch (merge (:schema main-db) (:schema bdb))
            pinned-eids (volatile! #{})
            fwd-max-tx (reduce max 0 (map #(long (or (:tx %) 0)) fwd))
            conn-map (volatile! {agent-eid fwd-max-tx})
            add-conn! (fn [e tx]
                        (when (integer? e)
                          (vswap! conn-map update e #(max (long (or % 0)) (long (or tx 0))))))]
        (doseq [dt fwd
                :let [a (:a dt) v (:v dt) tx (:tx dt)]]
          (cond
            (= :db.type/ref (get-in dh-sch [a :db/valueType]))
            (add-conn! v tx)

            (contains? #{:seon.agent/pinned :seon.agent/curated} a)
            (doseq [item (if (coll? v) v [v])]
              (when-let [e (resolve-lookup-eid main-db bdb item)]
                (add-conn! e tx)
                (when (= :seon.agent/pinned a) (vswap! pinned-eids conj e))))))
        (doseq [dt rev-ds] (add-conn! (:e dt) (:tx dt)))
        {:agent-eid agent-eid
         :forward fwd
         :reverse rev-ds
         :reverse-attrs rev-attrs
         :connected @conn-map
         :pinned @pinned-eids}))))

(defn merge-pull-patterns
  "Pure, commutative, associative, deterministic union of Datahike `:pull` patterns (recursively merging nested `{:ref [...]}` maps)."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]]
   :cost/ticks 500000}
  [patterns]
  (let [step (fn [{:keys [star? scalars nested other] :as acc} clause]
               (cond
                 (= '* clause) (assoc acc :star? true)
                 (keyword? clause) (update acc :scalars conj clause)
                 (map? clause)
                 (reduce-kv (fn [a k sub]
                              (if (sequential? sub)
                                (update-in a [:nested k] (fnil conj []) (vec sub))
                                a))
                            acc clause)
                 (some? clause) (update acc :other conj clause)
                 :else acc))
        {:keys [star? scalars nested other]}
        (reduce (fn [acc pat] (reduce step acc pat))
                {:star? false :scalars #{} :nested {} :other #{}}
                patterns)
        merged-nested (into {} (map (fn [[k subs]] [k (merge-pull-patterns subs)])) nested)
        nested-vec (mapv (fn [[k sub]] {k sub}) (sort-by (comp str key) merged-nested))
        scalar-vec (if star? ['*] (vec (sort (remove #(contains? merged-nested %) scalars))))
        other-vec (vec (sort-by pr-str other))]
    (vec (concat scalar-vec nested-vec other-vec))))