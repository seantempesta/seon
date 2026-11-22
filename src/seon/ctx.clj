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
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.store :as store]
            [seon.ctx.project :as proj]))

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

(defn agent-world-datoms
  "Generic 2-read agent world discovery (delegates to `seon.ctx.project/agent-world-datoms`)."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map]
   :cost/ticks 8000000}
  [main-db branch-db agent-id]
  (proj/agent-world-datoms main-db branch-db agent-id))

(defn merge-pull-patterns
  "Monotonic union of Datahike `:pull` patterns (delegates to `seon.ctx.project/merge-pull-patterns`)."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]]
   :cost/ticks 500000}
  [patterns]
  (proj/merge-pull-patterns patterns))

(defn merge-projections
  "Merges rule projections per entity with `d/pull` and provenance (delegates to `seon.ctx.project/merge-projections`)."
  {:malli/schema [:=> [:cat :map [:vector :map]] [:vector :map]]
   :cost/ticks 15000000}
  [dbs raw-projections]
  (proj/merge-projections dbs raw-projections))

(defn compute-shown-delta
  "Zero-repetition / upgrade / re-warming delta (delegates to `seon.ctx.project/compute-shown-delta`)."
  {:malli/schema [:=> [:cat [:maybe :map] [:vector :map]] [:map [:projections [:vector :map]] [:shown :map]]]
   :cost/ticks 5000000}
  [already-shown projections]
  (proj/compute-shown-delta already-shown projections))

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
        {:keys [selected dropped tokens]} (proj/budget-and-order-projections projections budget)
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
    (let [shown' (reduce
                 (fn [acc p]
                   (assoc acc (:entity p) (get shown (:entity p))))
                 (or (:shown prev-session) {})
                 selected)]
    {:seon.ctx/projections selected,
     :seon.ctx/trace trace,
     :seon.ctx/session
     {:rule-state step-res,
      :shown shown',
      :agent-id id,
      :target eff-target},
     :seon.ctx/tokens tokens})))

(defn context-trace
  "Returns the full self-introspection trace for `agent-id` showing fired/skipped rules, `datahike.reads`,
   generic world discovery, and every projected block (`:entity`, `:rules`, `:fn`, `:pull`, `:params`, `:tokens`)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword [:maybe :map]] :map]]
   :cost/ticks 50000000}
  ([main-db branch-db agent-id] (context-trace main-db branch-db agent-id nil))
  ([main-db branch-db agent-id opts]
   (let [o (if (map? opts) opts {})
         req (cond-> {:seon.db/main main-db :seon.db/branch (or branch-db main-db) :seon.agent/id agent-id}
               (:target o) (assoc :seon.view/target (:target o))
               (:token-budget o) (assoc :seon.view/token-budget (:token-budget o))
               (:rules o) (assoc :seon.ctx/rules (:rules o)))]
     (:seon.ctx/trace (related-updates req)))))

(defn diff-context
  "A/B diffs context projections and token counts between `rules-a` and `rules-b` for `agent-id`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword [:maybe [:vector :map]] [:maybe [:vector :map]]] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword [:maybe [:vector :map]] [:maybe [:vector :map]] [:maybe :map]] :map]]
   :cost/ticks 60000000}
  ([main-db branch-db agent-id rules-a rules-b]
   (diff-context main-db branch-db agent-id rules-a rules-b nil))
  ([main-db branch-db agent-id rules-a rules-b opts]
   (let [o (if (map? opts) opts {})
         base (cond-> {:seon.db/main main-db :seon.db/branch (or branch-db main-db) :seon.agent/id agent-id}
                (:target o) (assoc :seon.view/target (:target o))
                (:token-budget o) (assoc :seon.view/token-budget (:token-budget o)))
         ra (related-updates (cond-> base rules-a (assoc :seon.ctx/rules rules-a)))
         rb (related-updates (cond-> base rules-b (assoc :seon.ctx/rules rules-b)))
         ma (into {} (map (juxt :entity identity)) (:seon.ctx/projections ra))
         mb (into {} (map (juxt :entity identity)) (:seon.ctx/projections rb))
         ks-a (set (keys ma))
         ks-b (set (keys mb))
         changed-pulls (vec (keep (fn [e]
                                    (let [pa (:pull (get ma e)) pb (:pull (get mb e))]
                                      (when (not= pa pb) {:entity e :before pa :after pb})))
                                  (sort-by str (set/intersection ks-a ks-b))))]
     {:agent agent-id
      :target (get-in ra [:seon.ctx/trace :target])
      :tokens-before (:seon.ctx/tokens ra)
      :tokens-after (:seon.ctx/tokens rb)
      :tokens-delta (- (long (:seon.ctx/tokens rb)) (long (:seon.ctx/tokens ra)))
      :added-entities (vec (sort-by str (set/difference ks-b ks-a)))
      :removed-entities (vec (sort-by str (set/difference ks-a ks-b)))
      :changed-pulls changed-pulls
      :rule-diffs (:rule-diffs (rules/diff-sessions (get-in ra [:seon.ctx/session :rule-state])
                                                    (get-in rb [:seon.ctx/session :rule-state])))
      :trace-before (:seon.ctx/trace ra)
      :trace-after (:seon.ctx/trace rb)})))

(defn install-ctx-helpers!
  "Installs `user/context-trace` and `user/diff-context` REPL self-introspection helpers into namespace `u`."
  {:seon.flow/edge true :malli/schema [:=> [:cat :any :any] :any]}
  [u ctx-fn]
  (when (and (instance? clojure.lang.Namespace u) (fn? ctx-fn))
    (let [get-dbs (fn []
                    (let [{:keys [conn branch agent-id]} (ctx-fn)
                          bdb @conn
                          mdb (if (= branch :main) bdb (or (store/head conn :main) bdb))]
                      {:mdb mdb :bdb bdb :default-aid (or agent-id :agent/root)}))
          v1 (intern u 'context-trace
                     (fn
                       ([] ((resolve 'user/context-trace) nil {}))
                       ([arg] (if (map? arg) ((resolve 'user/context-trace) nil arg) ((resolve 'user/context-trace) arg {})))
                       ([target-aid opts]
                        (let [{:keys [mdb bdb default-aid]} (get-dbs)]
                          (context-trace mdb bdb (or (when (qualified-keyword? target-aid) target-aid) default-aid) opts)))))
          v2 (intern u 'diff-context
                     (fn
                       ([]
                        (let [{:keys [mdb bdb default-aid]} (get-dbs)
                              rs-main (load-context-rules mdb mdb nil)
                              rs-branch (load-context-rules mdb bdb nil)]
                          (diff-context mdb bdb default-aid rs-main rs-branch nil)))
                       ([arg]
                        (let [{:keys [mdb bdb default-aid]} (get-dbs)]
                          (if (map? arg)
                            (diff-context mdb bdb (or (:agent arg) default-aid) (:rules-a arg) (:rules-b arg) arg)
                            (diff-context mdb bdb default-aid nil (vec arg) nil))))
                       ([rules-a rules-b]
                        (let [{:keys [mdb bdb default-aid]} (get-dbs)]
                          (diff-context mdb bdb default-aid rules-a rules-b nil)))))]
      (alter-meta! v1 merge {:arglists '([] [opts-or-agent] [agent-id opts])
                             :doc "Inspect every native Datalog rule, datahike.reads skip count, pull pattern, and token cost building context."})
      (alter-meta! v2 merge {:arglists '([] [rules-override-or-opts] [rules-a rules-b])
                             :doc "A/B diff context projections, pull patterns, and token budgets across rule sets."})
      [v1 v2])))