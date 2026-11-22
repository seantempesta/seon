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

(defn- validate-projection-params [db fn-sym params]
  (if (or (empty? params) (nil? db) (not (qualified-symbol? fn-sym))
          (not (contains? (:schema db) :seon.code.var/name)))
    params
    (let [ve (:e (first (d/datoms db :avet :seon.code.var/name fn-sym)))
          opt-ks (when (and ve (contains? (:schema db) :seon.code.var/optional-input))
                   (into #{} (keep #(:v (first (d/datoms db :eavt (:v %) :seon.schema/key))))
                         (d/datoms db :eavt ve :seon.code.var/optional-input)))]
      (if (empty? opt-ks)
        params
        (let [known-forms (:forms (schema/registry-for-db db))]
          (reduce-kv (fn [acc k v]
                       (if (contains? opt-ks k)
                         (let [ok? (or (not (contains? known-forms k))
                                       (:ok? (schema/validate-against db k v)))]
                           (if ok? (assoc acc k v) acc))
                         acc))
                     {}
                     params))))))

(defn- pull-entity-for-projection [dbs eid pull-pat]
  (let [bdb (:branch dbs)
        mdb (:main dbs)
        eff-pat (if (seq pull-pat) pull-pat '[:db/id])]
    (if-not (integer? eid)
      {:data {} :tx 0}
      (let [b-ds (when bdb (d/datoms bdb :eavt eid))
            use-db (if (seq b-ds) bdb mdb)
            ds (if (seq b-ds) b-ds (when mdb (d/datoms mdb :eavt eid)))
            tx (reduce max 0 (map #(long (or (:tx %) 0)) ds))
            data (if (and use-db (seq ds)) (d/pull use-db eff-pat eid) {})]
        {:data (if (map? data) data {}) :tx tx}))))

(defn merge-projections
  "Merges multiple rule projections per entity via monotonic `merge-pull-patterns`, pulls exact Datahike maps,
   validates `:params` against `:seon.code.var/optional-input`, and attaches full provenance."
  {:malli/schema [:=> [:cat :map [:vector :map]] [:vector :map]]
   :cost/ticks 15000000}
  [dbs raw-projections]
  (let [eff-db (or (:branch dbs) (:main dbs))
        grouped (group-by :entity raw-projections)]
    (->> grouped
         (mapv (fn [[eid ps]]
                 (let [ordered (sort-by (fn [p] [(long (or (:stratum p) 50))
                                                 (long (or (:priority p) 50))
                                                 (str (:rule p))])
                                        ps)
                       merged-pull (merge-pull-patterns (keep :pull ordered))
                       win-fn (or (last (keep :fn ordered)) 'seon.render/render-entity-value)
                       win-zone (or (last (keep :zone ordered)) :workspace)
                       win-parent (last (keep :parent ordered))
                       max-pri (reduce max 50 (map #(long (or (:priority %) 50)) ordered))
                       raw-params (reduce merge {} (keep :params ordered))
                       params (validate-projection-params eff-db win-fn raw-params)
                       rules-v (vec (sort-by str (distinct (keep :rule ordered))))
                       why-v (vec (distinct (keep :why ordered)))
                       tags-s (into #{} (mapcat #(let [t (:tags %)] (if (coll? t) t (when t [t])))) ordered)
                       {:keys [data tx]} (pull-entity-for-projection dbs eid merged-pull)
                       max-tx (reduce max (long tx) (map #(long (or (:tx %) 0)) ordered))]
                   {:entity eid :data data :fn win-fn :pull merged-pull :params params
                    :priority max-pri :parent win-parent :zone win-zone
                    :rules rules-v :why why-v :tags tags-s :tx max-tx})))
         (sort-by (fn [p] [(- (long (:priority p 50))) (str (:entity p))]))
         vec)))

(defn compute-shown-delta
  "Pure zero-repetition / upgrade / re-warming delta over `already-shown` (`{eid {:keys :hash :tx}}`) and `projections`."
  {:malli/schema [:=> [:cat [:maybe :map] [:vector :map]] [:map [:projections [:vector :map]] [:shown :map]]]
   :cost/ticks 5000000}
  [already-shown projections]
  (let [shown0 (if (map? already-shown) already-shown {})
        id-key-of (fn [m] (first (filter #(and (keyword? %) (contains? #{"id" "name" "key"} (name %)))
                                         (sort (keys m)))))]
    (reduce
     (fn [{:keys [projections shown]} p]
       (let [eid (:entity p)
             data (if (map? (:data p)) (:data p) {})
             wanted-ks (disj (set (keys data)) :db/id)
             cur-tx (long (or (:tx p) 0))
             prev (get shown0 eid)
             shown-ks (set (:keys prev))
             prev-tx (long (or (:tx prev) 0))
             new-ks (set/difference wanted-ks shown-ks)
             k-hashes (into {} (map (fn [k] [k (hash (get data k))])) wanted-ks)
             overlap-changed?
             (when prev
               (if-let [pkh (:key-hashes prev)]
                 (boolean (some (fn [k] (and (contains? pkh k) (not= (get pkh k) (get k-hashes k)))) wanted-ks))
                 (let [overlap (set/intersection wanted-ks shown-ks)]
                   (and (some? (:hash prev))
                        (= overlap shown-ks)
                        (not= (:hash prev) (hash (select-keys data overlap)))))))
             [status delta-data delta-ks]
             (cond
               (nil? prev) [:new data wanted-ks]
               (or overlap-changed? (> cur-tx prev-tx)) [:rewarmed data wanted-ks]
               (seq new-ks)
               (let [id-k (id-key-of data)
                     keep-ks (cond-> new-ks id-k (conj id-k) (contains? data :db/id) (conj :db/id))]
                 [:upgraded (select-keys data keep-ks) new-ks])
               :else [:unchanged {} #{}])
             p' (assoc p :status status :delta-data delta-data :delta-keys delta-ks)
             next-entry (if (= :unchanged status)
                          prev
                          (let [all-ks (if (= :rewarmed status) wanted-ks (set/union shown-ks wanted-ks))
                                merged-kh (if (= :rewarmed status) k-hashes (merge (:key-hashes prev) k-hashes))]
                            {:keys all-ks
                             :hash (hash (dissoc data :db/id))
                             :key-hashes merged-kh
                             :tx (max prev-tx cur-tx)}))]
         {:projections (conj projections p')
          :shown (assoc shown eid next-entry)}))
     {:projections [] :shown shown0}
     projections)))

(defn- load-context-rules [main-db branch-db override]
  (let [all (or override
                (let [fb (rseed/starter-rules)
                      m-rs (rules/load-rules main-db fb)]
                  (if (or (nil? branch-db) (= main-db branch-db)
                          (= (rules/rules-db-sig main-db) (rules/rules-db-sig branch-db)))
                    m-rs
                    (rules/load-rules branch-db m-rs))))]
    (if override
      (vec all)
      (filterv (fn [r] (or (= :context (:seon.rule/category r))
                           (some map? (:seon.rule/assert r))))
               all))))

(defn- seed-world-wm [world]
  (let [conn-facts (mapcat (fn [[e tx]]
                             (cond-> [[e :world/connected true]]
                               (pos? (long tx)) (conj [e :world/tx (long tx)])))
                           (:connected world))
        pin-facts (map (fn [e] [e :world/pinned true]) (:pinned world))]
    (rules/add-facts (rules/empty-wm) (concat conn-facts pin-facts) {:rule :seed/world :stratum 0})))

(defn- budget-and-order-projections [projs budget]
  (let [zone-rank {:static 0 :static-prefix 0 :canvas 1 :workspace 2 :debug 3 :ephemeral-tail 4}
        with-toks (mapv (fn [p]
                          (let [tok (if (= :unchanged (:status p))
                                      0
                                      (max 1 (quot (count (pr-str (:delta-data p))) 4)))]
                            (assoc p :tokens tok)))
                        projs)
        by-pri (sort-by (fn [p] [(- (long (:priority p 50))) (get zone-rank (:zone p) 2) (str (:entity p))]) with-toks)
        {:keys [kept dropped used]}
        (reduce (fn [{:keys [kept dropped used] :as acc} p]
                  (let [t (long (:tokens p 0))]
                    (if (or (zero? t) (<= (+ used t) budget))
                      (-> acc (update :kept conj p) (update :used + t))
                      (update acc :dropped conj p))))
                {:kept [] :dropped [] :used 0}
                by-pri)
        ordered (vec (sort-by (fn [p] [(get zone-rank (:zone p) 2) (- (long (:priority p 50))) (str (:entity p))]) kept))]
    {:selected ordered :dropped dropped :tokens (long used)}))

(defn related-updates
  "Normal `ns-map -> ns-map` incremental context engine over native Datahike `d/q` + `d/pull` + `datahike.reads`.
   Performs generic 2-read agent discovery, incremental rule stepping, monotonic pull-key-set union,
   zero-repetition / re-warming delta computation, cache-friendly ordering, and token-budget enforcement."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.db/main :seon.host/db]
               [:seon.db/branch {:optional true} [:maybe :seon.host/db]]
               [:seon.agent/id :qualified-keyword]
               [:seon.view/target {:optional true} :seon.view/target]
               [:seon.view/token-budget {:optional true} :seon.view/token-budget]
               [:seon.ctx/prev-session {:optional true} [:maybe :map]]
               [:seon.ctx/tx-datoms {:optional true} [:maybe :any]]]]
        [:map
         [:seon.ctx/projections [:vector :map]]
         [:seon.ctx/trace :map]
         [:seon.ctx/session :map]
         [:seon.ctx/tokens :int]]]
   :cost/ticks 50000000}
  [{:seon.db/keys [main branch]
    :seon.agent/keys [id]
    :seon.view/keys [target token-budget]
    :seon.ctx/keys [prev-session tx-datoms]
    :as req}]
  (let [eff-branch (or branch main)
        dbs {:main main :branch eff-branch :code eff-branch :task eff-branch}
        eff-target (or target :wire)
        budget (long (or token-budget 16000))
        ctx-rules (load-context-rules main eff-branch (:seon.ctx/rules req))
        world (agent-world-datoms main eff-branch id)
        base-wm (seed-world-wm world)
        step-res (rules/step-rules (:rule-state prev-session) dbs {:agent-id id :base-wm base-wm} ctx-rules tx-datoms eff-target)
        merged (merge-projections dbs (:projections step-res))
        {:keys [projections shown]} (compute-shown-delta (:shown prev-session) merged)
        {:keys [selected dropped tokens]} (budget-and-order-projections projections budget)
        runs-sorted (sort-by (juxt :stratum #(str (:rule-id %))) (vals (:runs step-res)))
        trace {:agent id
               :target eff-target
               :token-budget budget
               :tokens tokens
               :rules-total (count ctx-rules)
               :recomputed (:recomputed step-res)
               :skipped (:skipped step-res)
               :fired-rules (vec (keep #(when (pos? (long (:fired % 0)))
                                          (select-keys % [:rule-id :stratum :category :fired :reads-count :ms :skipped?]))
                                       runs-sorted))
               :skipped-rules (vec (keep #(when (:skipped? %) (:rule-id %)) runs-sorted))
               :world-discovery {:agent-eid (:agent-eid world)
                                 :forward-attrs (vec (sort (distinct (map :a (:forward world)))))
                                 :reverse-attrs (:reverse-attrs world)
                                 :reverse-hits (count (:reverse world))}
               :blocks (mapv #(select-keys % [:entity :rules :fn :pull :params :zone :parent :priority :status :tokens :tx]) selected)
               :dropped (mapv #(select-keys % [:entity :rules :priority :tokens]) dropped)}]
    {:seon.ctx/projections selected
     :seon.ctx/trace trace
     :seon.ctx/session {:rule-state step-res :shown shown :agent-id id :target eff-target}
     :seon.ctx/tokens tokens}))