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
            [datahike.reads :as reads]))

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
   :seon.agent/auto-merge?
   :seon.agent/waiting?
   :seon.agent/ready?
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
       :seon.code.var/schema-refs]}
     {:seon.code.ns/_parent '...}]}])

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

;; --- Diff `old -> new` back into Datahike tx-data (`swap! seon.ctx/ctx`) ---

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

;; --- Reactive `IDeref` / `IAtom` Wrapper (`@seon.ctx/ctx`, `(swap! seon.ctx/ctx ...)`) ---

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
