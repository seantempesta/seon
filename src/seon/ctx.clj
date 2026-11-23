(ns seon.ctx
  "Reactive `@seon.ctx/ctx` live bidirectional Datahike projection (`IDeref`/`IRef`/`IAtom2`,
   `O(|Δ|)` refresh, filtered `:seon.ctx/neighborhood`, `swap!` write-back) and unified context engine."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.code.files :as files]
            [seon.ctx.project :as proj]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks :refer [live-cid]]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge ctx-rules-cache (store/lru-cache 16))
(defonce ^:private ^:seon.flow/edge ctx-ru-cache (store/lru-cache 24))

(def default-agent-pattern
  "Default pull pattern for an agent entity and its focus namespace subtree."
  [:db/id :seon.agent/id :seon.agent/parent :seon.agent/scope :seon.agent/branch
   :seon.agent/task :seon.agent/role :seon.agent/goal :seon.agent/pinned
   :seon.agent/auto-merge? :seon.agent/waiting? :seon.agent/ready? :seon.agent/awaiting
   :seon.agent/memory :seon.agent/context-prefs :seon.ctx/pattern
   {:seon.agent/focus
    [:db/id :seon.code.ns/name :seon.code.ns/doc :seon.code.ns/path
     {:seon.code.var/_ns-ref
      [:db/id :seon.code.var/name :seon.code.var/doc :seon.code.var/arglists
       :seon.code.var/schema :seon.code.var/refs
       {:seon.code.var/input [:seon.schema/key]} {:seon.code.var/output [:seon.schema/key]}
       :seon.code.var/keywords]}
     {:seon.code.ns/_parent [:db/id :seon.code.ns/name :seon.code.ns/doc :seon.code.ns/path]}]}])

(declare gather-agent-candidates)

(defn- clause-key [clause]
  (cond (keyword? clause) clause (map? clause) (ffirst clause) (vector? clause) (first clause) :else clause))

(defn- pull-clause [db eid clause]
  (let [[res rs] (reads/with-reads #(d/pull db [clause] eid))
        k (clause-key clause)]
    {:clause clause :key k :val (get res k ::absent) :reads-idx (reads/reads-index rs)}))

(defn- effective-pattern [db eid override-pattern]
  (or override-pattern
      (let [p (:seon.ctx/pattern (d/pull db '[:seon.ctx/pattern] eid))]
        (when (and (vector? p) (seq p)) p))
      default-agent-pattern))

(defn- neighborhood-node-key [ent]
  (or (:seon.task/id ent) (:seon.code.var/name ent) (:seon.error/id ent)
      (:seon.code.ns/name ent) (:seon.schema/key ent) (:seon.code.doc/id ent)
      (:seon.rule/id ent) (:seon.fix/id ent) (:seon.agent/id ent) (:db/id ent)))

(defn- build-filtered-neighborhood [db aid]
  (let [{:keys [candidates]} (gather-agent-candidates db db aid {:target :wire :enrich? false :situational? false
                                                                 :include-evals? false :semantic? true :use-cache? false})
        ranked (take 8 (filter #(and (integer? (:entity %)) (map? (:data %)) (integer? (get-in % [:data :db/id])))
                               (fit/rank-candidates candidates)))]
    (reduce (fn [acc item]
              (let [ent (:data item)
                    grp (cond (:seon.task/id ent) :tasks
                              (:seon.code.var/name ent) :vars
                              (:seon.error/id ent) :errors
                              :else :pinned)
                    nk (neighborhood-node-key ent)]
                (assoc-in acc [grp nk] ent)))
            {:tasks {} :vars {} :errors {} :pinned {}}
            ranked)))

(defn- pull-neighborhood-entry [db aid]
  (when (qualified-keyword? aid)
    (let [[nb rs] (reads/with-reads #(build-filtered-neighborhood db aid))]
      {:key :seon.ctx/neighborhood :val nb :reads-idx (reads/reads-index rs)})))

(defn- assemble-map [db eid entries nb-entry]
  (let [base (reduce (fn [acc {:keys [key val]}] (if (= val ::absent) acc (assoc acc key val))) {:db/id eid} entries)]
    (with-meta
      (cond-> (assoc base
                     :seon.ctx/branch (get-in db [:config :branch] :main)
                     :seon.ctx/tx (:max-tx db)
                     :seon.ctx/commit (d/commit-id db))
        nb-entry (assoc :seon.ctx/neighborhood (:val nb-entry)))
      {:seon/schema :seon.agent/entity})))

(defn build
  "Builds an incremental pull snapshot `{:eid :pattern :entries :nb-entry :val :tx :commit}` for `eid` on `db`.
   Agent entities also carry a filtered `:seon.ctx/neighborhood` tree (`:tasks`/`:vars`/`:errors`/`:pinned`)
   projected through the exact same 2-read + `:context` rules + 4-signal rank pipeline."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :any] [:maybe :map]]
                  [:=> [:cat :seon.host/db :any [:maybe :any]] [:maybe :map]]]
   :cost/ticks 25000000}
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
             aid (if has-star? (:seon.agent/id (:val (first entries)))
                     (some #(when (= :seon.agent/id (:key %)) (when-not (= ::absent (:val %)) (:val %))) entries))
             nb-entry (when (qualified-keyword? aid) (pull-neighborhood-entry db aid))
             val (if has-star?
                   (with-meta
                     (cond-> (assoc (:val (first entries))
                                    :seon.ctx/branch (get-in db [:config :branch] :main)
                                    :seon.ctx/tx (:max-tx db)
                                    :seon.ctx/commit (d/commit-id db))
                       nb-entry (assoc :seon.ctx/neighborhood (:val nb-entry)))
                     {:seon/schema :seon.agent/entity})
                   (assemble-map db resolved-eid entries nb-entry))]
         {:eid resolved-eid :agent-id aid :pattern pat :has-star? has-star?
          :entries entries :nb-entry nb-entry :val val :tx (:max-tx db) :commit (d/commit-id db)})))))

(defn advance
  "Incrementally updates `snap` to `db'` given `tx-datoms` (`O(|Δ|)`): re-pulls ONLY clauses
   (and `:seon.ctx/neighborhood`) whose `reads-index` intersects `tx-datoms`, keeping untouched subtrees `identical?`."
  {:malli/schema [:=> [:cat [:maybe :map] :seon.host/db [:maybe :any]] [:maybe :map]]
   :cost/ticks 25000000}
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
                               (if (or (nil? tx-datoms) (reads/reads-touched? (:reads-idx entry) tx-datoms))
                                 (let [fresh (pull-clause db' eid (:clause entry))]
                                   (when (not= (:val fresh) (:val entry)) (vreset! changed? true))
                                   fresh)
                                 entry))
                             (:entries snap))
              nb0 (:nb-entry snap)
              aid (or (:agent-id snap)
                      (some #(when (= :seon.agent/id (:key %)) (when-not (= ::absent (:val %)) (:val %))) entries'))
              nb' (when (qualified-keyword? aid)
                    (if (or (nil? nb0) (nil? tx-datoms) (reads/reads-touched? (:reads-idx nb0) tx-datoms))
                      (let [fresh (pull-neighborhood-entry db' aid)]
                        (when (not= (:val fresh) (:val nb0)) (vreset! changed? true))
                        fresh)
                      nb0))
              val' (if @changed?
                     (assemble-map db' eid entries' nb')
                     (with-meta
                       (assoc (:val snap)
                              :seon.ctx/branch (get-in db' [:config :branch] :main)
                              :seon.ctx/tx (:max-tx db')
                              :seon.ctx/commit (d/commit-id db'))
                       (meta (:val snap))))]
          (assoc snap :agent-id aid :entries entries' :nb-entry nb' :val val'
                 :tx (:max-tx db') :commit (d/commit-id db')))))))

(def ^:private readonly-env-keys
  #{:db/id :seon.ctx/branch :seon.ctx/tx :seon.ctx/commit})

(defn- reverse-attr? [k]
  (and (qualified-keyword? k) (.startsWith (name k) "_")))

(declare diff-entity-tx)

(defn- diff-nested-entities [db ov nv]
  (cond
    (or (identical? ov nv) (= ov nv)) []

    (and (map? ov) (map? nv) (integer? (:db/id ov)) (= (:db/id ov) (:db/id nv)))
    (diff-entity-tx db (:db/id ov) ov nv)

    (and (map? ov) (map? nv))
    (reduce-kv (fn [acc k nv']
                 (if-let [ov' (get ov k)]
                   (into acc (diff-nested-entities db ov' nv'))
                   acc))
               [] nv)

    (and (coll? ov) (coll? nv))
    (let [old-by-id (into {} (keep #(when (and (map? %) (integer? (:db/id %))) [(:db/id %) %])) ov)]
      (reduce (fn [acc nm]
                (if-let [om (when (and (map? nm) (integer? (:db/id nm))) (get old-by-id (:db/id nm)))]
                  (into acc (diff-entity-tx db (:db/id om) om nm))
                  acc))
              [] nv))

    :else []))

(defn diff-entity-tx
  "Diffs pulled maps `old-m`/`new-m` for `eid` into Datahike tx-data, including `:seon.ctx/neighborhood` nodes."
  {:malli/schema [:=> [:cat :seon.host/db :int :map :map] [:vector :any]]
   :cost/ticks 5000000}
  [db eid old-m new-m]
  (let [schema (rlinks/db-schema db)
        all-ks (disj (set/union (set (keys old-m)) (set (keys new-m)))
                     :db/id :seon.ctx/branch :seon.ctx/tx :seon.ctx/commit)]
    (reduce
     (fn [acc k]
       (cond
         (or (contains? readonly-env-keys k) (reverse-attr? k)) acc

         (= :seon.ctx/neighborhood k)
         (into acc (diff-nested-entities db (get old-m k) (get new-m k)))

         :else
         (let [ov (get old-m k ::absent) nv (get new-m k ::absent)]
           (cond
             (or (identical? ov nv) (= ov nv)) acc

             (and (map? ov) (map? nv) (integer? (:db/id ov)) (= (:db/id ov) (:db/id nv)))
             (into acc (diff-entity-tx db (:db/id ov) ov nv))

             (and (or (map? ov) (coll? ov)) (or (map? nv) (coll? nv))
                  (nil? (get schema k)) (seq (diff-nested-entities db ov nv)))
             (into acc (diff-nested-entities db ov nv))

             (= nv ::absent)
             (if (= :db.cardinality/many (get-in schema [k :db/cardinality]))
               (into acc (map (fn [item] [:db/retract eid k item]) ov))
               (conj acc [:db/retract eid k ov]))

             (= :db.cardinality/many (get-in schema [k :db/cardinality]))
             (let [os (set (when-not (= ov ::absent) ov))
                   ns (set (if (coll? nv) nv [nv]))]
               (-> acc
                   (into (map (fn [item] [:db/retract eid k item]) (set/difference os ns)))
                   (into (map (fn [item] [:db/add eid k item]) (set/difference ns os)))))

             :else (conj acc [:db/add eid k nv])))))
     [] all-ks)))

(defprotocol ICtxAtom
  (-advance! [this db' tx-datoms] "Advance internal snapshot incrementally from committed `db'` and `tx-datoms`.")
  (-snapshot [this] "Return internal incremental snapshot map (with per-clause `reads-idx`)."))

(deftype CtxAtom [conn ^:volatile-mutable snap ^:volatile-mutable watches ^:volatile-mutable validator]
  clojure.lang.IDeref
  (deref [this]
    (let [cur-db @conn]
      (when (and snap (not= (:commit snap) (d/commit-id cur-db)))
        (-advance! this cur-db nil))
      (:val snap)))

  clojure.lang.IRef
  (addWatch [this k f] (set! watches (assoc watches k f)) this)
  (removeWatch [this k] (set! watches (dissoc watches k)) this)
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

  clojure.lang.IAtom2
  (reset [this new-val]
    (when-not (map? new-val)
      (throw (ex-info "ctx value must be a map" {:error :seon.ctx/invalid :got new-val})))
    (when validator
      (when-not (validator new-val)
        (throw (ex-info "ctx validator rejected value" {:error :seon.ctx/invalid}))))
    (let [old-val (.deref this)
          db @conn
          tx (diff-entity-tx db (:eid snap) old-val new-val)]
      (if (empty? tx)
        old-val
        (let [res (d/transact conn {:tx-data tx})]
          (-advance! this (:db-after res) (:tx-data res))))))
  (swap [this f] (.reset this (f (.deref this))))
  (swap [this f a] (.reset this (f (.deref this) a)))
  (swap [this f a b] (.reset this (f (.deref this) a b)))
  (swap [this f a b args] (.reset this (apply f (.deref this) a b args)))
  (compareAndSet [this oldv newv]
    (if (= oldv (.deref this)) (do (.reset this newv) true) false))
  (resetVals [this newv]
    (let [old (.deref this)] [old (.reset this newv)]))
  (swapVals [this f]
    (let [old (.deref this)] [old (.reset this (f old))]))
  (swapVals [this f a]
    (let [old (.deref this)] [old (.reset this (f old a))]))
  (swapVals [this f a b]
    (let [old (.deref this)] [old (.reset this (f old a b))]))
  (swapVals [this f a b args]
    (let [old (.deref this)] [old (.reset this (apply f old a b args))])))

(defn ctx-atom
  "Creates a live bidirectional `CtxAtom` projection for entity `eid` on `conn`."
  {:malli/schema [:function [:=> [:cat :seon.host/conn :any] :any] [:=> [:cat :seon.host/conn :any [:maybe :any]] :any]]
   :cost/ticks 25000000}
  ([conn eid] (ctx-atom conn eid nil))
  ([conn eid pattern] (CtxAtom. conn (build @conn eid pattern) {} nil)))

(def ^:dynamic ctx
  "Active agent's private live bidirectional `CtxAtom` (aliased as `user/ctx`)."
  nil)

(defn refresh-active!
  "Advances `seon.ctx/ctx` incrementally if bound and `conn` moved."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :any]] :any] :cost/ticks 25000000}
  [db' tx-datoms]
  (when (and ctx (satisfies? ICtxAtom ctx))
    (-advance! ctx db' tx-datoms)))

;; Delegations to seon.ctx.project (preserving public API)

(defn agent-world-datoms
  "Generic 2-read agent world discovery."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map] :cost/ticks 8000000}
  [main-db branch-db agent-id] (proj/agent-world-datoms main-db branch-db agent-id))

(defn merge-pull-patterns
  "Monotonic union of Datahike `:pull` patterns."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]] :cost/ticks 500000}
  [patterns] (proj/merge-pull-patterns patterns))

(defn merge-projections
  "Merges rule projections per entity with `d/pull` and provenance."
  {:malli/schema [:=> [:cat :map [:vector :map]] [:vector :map]] :cost/ticks 15000000}
  [dbs raw-projections] (proj/merge-projections dbs raw-projections))

(defn compute-shown-delta
  "Zero-repetition / upgrade / re-warming delta."
  {:malli/schema [:=> [:cat [:maybe :map] [:vector :map]] [:map [:projections [:vector :map]] [:shown :map]]] :cost/ticks 5000000}
  [already-shown projections] (proj/compute-shown-delta already-shown projections))

(defn load-context-rules
  "Loads active `:context` rules from `main-db` + `branch-db` (cached per rule-db signature, replaying `datahike.reads`)."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] [:maybe [:sequential :map]]] [:vector :map]]
   :cost/ticks 5000000}
  [main-db branch-db override]
  (if override
    (vec override)
    (let [bdb (or branch-db main-db)
          ms (rules/rules-db-sig main-db)
          bs (if (proj/same-db? main-db bdb) ms (rules/rules-db-sig bdb))
          load* (fn []
                  (let [fb (rseed/starter-rules)
                        m-rs (rules/load-rules main-db fb)
                        all (if (= ms bs) m-rs (rules/load-rules bdb m-rs))]
                    (filterv (fn [r] (or (= :context (:seon.rule/category r))
                                         (some map? (:seon.rule/assert r))))
                             all)))]
      (if (and ms bs) (store/lru-get! ctx-rules-cache 16 [ms bs] load*) (load*)))))

(defn- seed-world-wm [world]
  (let [conn-facts (mapcat (fn [[e tx]]
                             (cond-> [[e :world/connected true]]
                               (pos? (long tx)) (conj [e :world/tx (long tx)])))
                           (:connected world))
        pin-facts (map (fn [e] [e :world/pinned true]) (:pinned world))
        ex-facts (map (fn [e] [e :world/extra-ns true]) (:extra-nss world))]
    (rules/add-facts (rules/empty-wm) (concat conn-facts pin-facts ex-facts) {:rule :seed/world :stratum 0})))

(defn related-updates
  "Normal `ns-map -> ns-map` incremental context engine over native Datahike `d/q` + `d/pull` + `datahike.reads`."
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
        eff-task (or (:seon.db/task req) eff-branch)
        dbs {:main main :branch eff-branch :code eff-branch :task eff-task}
        eff-target (or target :wire)
        budget (long (or token-budget 16000))
        ctx-rules (load-context-rules main eff-branch (:seon.ctx/rules req))
        world (or (:seon.ctx/world req) (agent-world-datoms main eff-branch id))
        base-wm (seed-world-wm world)
        step-res (rules/step-rules (:rule-state prev-session) dbs {:agent-id id :base-wm base-wm} ctx-rules tx-datoms eff-target)
        merged (merge-projections dbs (:projections step-res))
        {:keys [projections shown]} (compute-shown-delta (:shown prev-session) merged)
        {:keys [selected dropped tokens]} (proj/budget-and-order-projections projections budget)
        runs-sorted (sort-by (juxt :stratum #(str (:rule-id %))) (vals (:runs step-res)))
        trace {:agent id :target eff-target :token-budget budget :tokens tokens
               :rules-total (count ctx-rules) :recomputed (:recomputed step-res) :skipped (:skipped step-res)
               :fired-rules (vec (keep #(when (pos? (long (:fired % 0)))
                                          (select-keys % [:rule-id :stratum :category :fired :reads-count :ms :skipped?]))
                                       runs-sorted))
               :skipped-rules (vec (keep #(when (:skipped? %) (:rule-id %)) runs-sorted))
               :world-discovery {:agent-eid (:agent-eid world)
                                 :forward-attrs (vec (sort (distinct (map :a (:forward world)))))
                                 :reverse-attrs (:reverse-attrs world)
                                 :reverse-hits (count (:reverse world))}
               :blocks (mapv #(select-keys % [:entity :rules :fn :pull :params :zone :parent :priority :status :tokens :tx]) selected)
               :dropped (mapv #(select-keys % [:entity :rules :priority :tokens]) dropped)}
        shown' (reduce #(assoc %1 (:entity %2) (get shown (:entity %2))) (or (:shown prev-session) {}) selected)]
    {:seon.ctx/projections selected :seon.ctx/trace trace
     :seon.ctx/session {:rule-state step-res :shown shown' :agent-id id :target eff-target}
     :seon.ctx/tokens tokens}))

(defn gather-agent-candidates
  "ONE unified candidate-pool builder used by `surface.clj`, `wire.clj`, `entity.clj`, `context_lab.clj`,
   `signals/touches.clj`, AND `seon.ctx/build` (`:seon.ctx/neighborhood`)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword [:maybe :map]] :map]]
   :cost/ticks 45000000}
  ([main-db branch-db agent-id] (gather-agent-candidates main-db branch-db agent-id nil))
  ([main-db branch-db agent-id opts]
   (let [o (if (map? opts) opts {})
         bdb (or branch-db main-db)
         ag (or (:agent o) (rdata/pull-agent-entity main-db bdb agent-id) {:seon.agent/id agent-id :seon.agent/role :worker})
         tdb (or (:task-db o)
                 (when (and (:seon.agent/task ag) ((rlinks/db-schema bdb) :seon.task/id)
                            (first (d/datoms bdb :avet :seon.task/id (:seon.agent/task ag))))
                   bdb)
                 main-db)
         target (case (:target o)
                  (:canvas :workspace :debug :wire :any) (:target o)
                  :html :canvas
                  :wire)
         disabled (if (coll? (:disabled-rules o)) (set (:disabled-rules o)) #{})
         mc (live-cid main-db) bc (live-cid bdb)
         same? (proj/same-db? main-db bdb)
         all-rs (load-context-rules main-db bdb (:rules o))
         skip (cond-> (set/union disabled (proj/fast-skip-rule-ids main-db bdb ag same?))
                (false? (:include-evals? o)) (conj :rule.ctx/recent-eval-results))
         eff-rs (filterv #(not (skip (:seon.rule/id %))) all-rs)
         world (agent-world-datoms main-db bdb agent-id)
         run-ru (fn []
                  (if (empty? eff-rs)
                    {:projs [] :trace {:agent agent-id :target target :rules-total (count all-rs)
                                       :fired-rules [] :skipped-rules [] :recomputed 0 :skipped (count all-rs)}}
                    (let [dbs {:main main-db :branch bdb :code bdb :task tdb}
                          sr (rules/step-rules nil dbs {:agent-id agent-id :base-wm (seed-world-wm world)} eff-rs nil target)
                          runs-s (sort-by (juxt :stratum #(str (:rule-id %))) (vals (:runs sr)))]
                      {:projs (proj/collapse-rule-projections (:projections sr))
                       :trace {:agent agent-id :target target :rules-total (count all-rs)
                               :recomputed (:recomputed sr) :skipped (:skipped sr)
                               :fired-rules (vec (keep #(when (pos? (long (:fired % 0)))
                                                          (select-keys % [:rule-id :stratum :category :fired :reads-count :ms :skipped?]))
                                                       runs-s))
                               :skipped-rules (vec (keep #(when (:skipped? %) (:rule-id %)) runs-s))}})))
         ck (when (and (not (false? (:use-cache? o))) mc bc (empty? disabled) (nil? (:rules o)))
              [(get-in main-db [:config :store :id]) mc (:max-tx main-db) bc (:max-tx bdb)
               agent-id target (count eff-rs) (:seon.agent/task ag) (:seon.agent/scope ag)])
         ru (if ck (store/lru-get! ctx-ru-cache 24 ck run-ru) (run-ru))
         cands (proj/augment-and-hydrate-candidates main-db bdb tdb ag world (:projs ru) o)]
     {:agent ag :world world :candidates cands :ctx-trace (:trace ru)})))

;; Self-Introspection & REPL Helpers (incl. rules / put-rule! / toggle-rule! / curate!)

(defn context-trace
  "Returns full self-introspection trace for `agent-id` showing fired/skipped rules, reads, world discovery, and blocks."
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
  ([main-db branch-db agent-id rules-a rules-b] (diff-context main-db branch-db agent-id rules-a rules-b nil))
  ([main-db branch-db agent-id rules-a rules-b opts]
   (let [o (if (map? opts) opts {})
         base (cond-> {:seon.db/main main-db :seon.db/branch (or branch-db main-db) :seon.agent/id agent-id}
                (:target o) (assoc :seon.view/target (:target o))
                (:token-budget o) (assoc :seon.view/token-budget (:token-budget o)))
         ra (related-updates (cond-> base rules-a (assoc :seon.ctx/rules rules-a)))
         rb (related-updates (cond-> base rules-b (assoc :seon.ctx/rules rules-b)))
         ma (into {} (map (juxt :entity identity)) (:seon.ctx/projections ra))
         mb (into {} (map (juxt :entity identity)) (:seon.ctx/projections rb))
         ks-a (set (keys ma)) ks-b (set (keys mb))]
     {:agent agent-id :target (get-in ra [:seon.ctx/trace :target])
      :tokens-before (:seon.ctx/tokens ra) :tokens-after (:seon.ctx/tokens rb)
      :tokens-delta (- (long (:seon.ctx/tokens rb)) (long (:seon.ctx/tokens ra)))
      :added-entities (vec (sort-by str (set/difference ks-b ks-a)))
      :removed-entities (vec (sort-by str (set/difference ks-a ks-b)))
      :changed-pulls (vec (keep #(let [pa (:pull (get ma %)) pb (:pull (get mb %))]
                                   (when (not= pa pb) {:entity % :before pa :after pb}))
                                (sort-by str (set/intersection ks-a ks-b))))
      :rule-diffs (:rule-diffs (rules/diff-sessions (get-in ra [:seon.ctx/session :rule-state])
                                                    (get-in rb [:seon.ctx/session :rule-state])))
      :trace-before (:seon.ctx/trace ra) :trace-after (:seon.ctx/trace rb)})))

(defn install-ctx-helpers!
  "Installs `user/context-trace`, `user/diff-context`, `user/rules`, `user/put-rule!`, `user/toggle-rule!`, `user/curate!`."
  {:seon.flow/edge true :malli/schema [:=> [:cat :any :any] :any]}
  [u ctx-fn]
  (when (and (instance? clojure.lang.Namespace u) (fn? ctx-fn))
    (let [dbs (fn [] (let [{:keys [conn branch agent-id]} (ctx-fn) bdb @conn
                           mdb (if (= branch :main) bdb (or (store/head conn :main) bdb))]
                       {:conn conn :mdb mdb :bdb bdb :aid (or agent-id :agent/root)}))
          add! (fn [sym f meta-m] (let [v (intern u sym f)] (alter-meta! v merge meta-m) v))]
      [(add! 'context-trace
             (fn ct ([] (ct nil {})) ([a] (if (map? a) (ct nil a) (ct a {})))
               ([t o] (let [{:keys [mdb bdb aid]} (dbs)] (context-trace mdb bdb (if (qualified-keyword? t) t aid) o))))
             {:arglists '([] [opts-or-agent] [agent-id opts]) :doc "Inspect context rules, reads & blocks."})
       (add! 'diff-context
             (fn ([] (let [{:keys [mdb bdb aid]} (dbs)] (diff-context mdb bdb aid (load-context-rules mdb mdb nil) (load-context-rules mdb bdb nil) nil)))
               ([a] (let [{:keys [mdb bdb aid]} (dbs)]
                      (if (map? a) (diff-context mdb bdb (or (:agent a) aid) (:rules-a a) (:rules-b a) a)
                          (diff-context mdb bdb aid nil (vec a) nil))))
               ([ra rb] (let [{:keys [mdb bdb aid]} (dbs)] (diff-context mdb bdb aid ra rb nil))))
             {:arglists '([] [rules-override-or-opts] [rules-a rules-b]) :doc "A/B diff context projections."})
       (add! 'rules
             (fn ([] (rules/load-rules @(:conn (ctx-fn)) (rseed/starter-rules)))
               ([cat] (filterv #(= cat (:seon.rule/category %)) (rules/load-rules @(:conn (ctx-fn)) (rseed/starter-rules)))))
             {:arglists '([] [category]) :doc "List active :seon.rule/* entities."})
       (add! 'put-rule!
             (fn [rm] (let [{:keys [conn]} (ctx-fn)
                            r (merge {:seon.rule/enabled? true :seon.rule/stratum 50 :seon.rule/target :any :seon.rule/category :context} rm)]
                        (when-not (:seon.rule/id r) (throw (ex-info "put-rule! requires :seon.rule/id" {:rule r})))
                        (d/transact conn {:tx-data (files/enrich-rule-tx @conn r)})
                        {:saved (:seon.rule/id r) :stratum (:seon.rule/stratum r) :enabled? (:seon.rule/enabled? r)}))
             {:arglists '([rule-map]) :doc "Upsert a :seon.rule/* entity."})
       (add! 'toggle-rule!
             (fn [rid en?] (d/transact (:conn (ctx-fn)) {:tx-data [{:seon.rule/id rid :seon.rule/enabled? (boolean en?)}]})
               {:rule rid :enabled? (boolean en?)})
             {:arglists '([rule-id enabled?]) :doc "Toggle a :seon.rule/* entity."})
       (add! 'curate!
             (fn [refs] (let [{:keys [conn agent-id]} (ctx-fn) db @conn
                              rs (vec (if (and (vector? refs) (= 2 (count refs)) (qualified-keyword? (first refs))) [refs] refs))
                              ag (d/pull db '[:seon.agent/curated :seon.agent/task] [:seon.agent/id agent-id])
                              merged (vec (distinct (concat (:seon.agent/curated ag) rs))) tid (:seon.agent/task ag)
                              tx (cond-> [{:seon.agent/id agent-id :seon.agent/curated merged}]
                                   (and tid (first (d/datoms db :avet :seon.task/id tid)))
                                   (conj {:seon.task/id tid
                                          :seon.task/curated (vec (distinct (concat (:seon.task/curated (d/pull db '[:seon.task/curated] [:seon.task/id tid])) rs)))}))]
                          (d/transact conn {:tx-data tx}) {:curated merged :task tid}))
             {:arglists '([refs]) :doc "Curate lookup refs onto agent & task."})])))
