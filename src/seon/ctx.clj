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
            [seon.store :as store]
            [seon.ctx.tree :as tree]))

(defonce ^:private ^:seon.flow/edge ctx-rules-cache (store/lru-cache 16))

(defonce ^:private ^:seon.flow/edge ctx-ru-cache (store/lru-cache 24))

(defn- neighborhood-node-key [ent]
  (or (:seon.task/id ent) (:seon.code.var/name ent) (:seon.error/id ent)
      (:seon.code.ns/name ent) (:seon.schema/key ent) (:seon.code.doc/id ent)
      (:seon.rule/id ent) (:seon.fix/id ent) (:seon.agent/id ent) (:db/id ent)))

(defprotocol ICtxAtom
  (-advance! [this db' tx-datoms] "Advance internal snapshot incrementally from committed `db'` and `tx-datoms`.")
  (-snapshot [this] "Return internal incremental snapshot map (with per-clause `reads-idx`)."))

(def ^:dynamic ctx
  "Active agent's private live bidirectional `CtxAtom` (aliased as `user/ctx`)."
  nil)

(defn refresh-active!
  "Advances `seon.ctx/ctx` incrementally if bound and `conn` moved."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :any]] :any] :cost/ticks 25000000}
  [db' tx-datoms]
  (when (and ctx (satisfies? ICtxAtom ctx) (seq (.getWatches ^clojure.lang.IRef ctx)))
  (-advance! ctx db' tx-datoms)))

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
         ag (or (:agent o) (rdata/pull-agent-entity main-db bdb agent-id) {:seon.agent/id agent-id})
         tdb (or (:task-db o)
                 (let [tids (rdata/agent-task-ids bdb ag)] (when (and (seq tids) ((rlinks/db-schema bdb) :seon.task/id) (some #(first (d/datoms bdb :avet :seon.task/id %)) tids)) bdb))
                 main-db)
         target (case (:target o)
                  (:canvas :workspace :debug :wire :any) (:target o)
                  :html :canvas
                  :wire)
         prefs (if (map? (:seon.agent/context-prefs ag)) (:seon.agent/context-prefs ag) {})
         p-dis (:disabled-rules prefs)
         disabled (into #{} (concat (when (coll? p-dis) p-dis) (when (coll? (:disabled-rules o)) (:disabled-rules o))))
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
                      {:projs (proj/collapse-rule-projections (if-let [zf (:zone-filter o)] (filterv #(= zf (:zone %)) (:projections sr)) (:projections sr)))
                       :trace {:agent agent-id :target target :rules-total (count all-rs)
                               :recomputed (:recomputed sr) :skipped (:skipped sr)
                               :fired-rules (vec (keep #(when (pos? (long (:fired % 0)))
                                                          (select-keys % [:rule-id :stratum :category :fired :reads-count :ms :skipped?]))
                                                       runs-s))
                               :skipped-rules (vec (keep #(when (:skipped? %) (:rule-id %)) runs-s))}})))
         ck (when (and (not (false? (:use-cache? o))) mc bc (empty? disabled) (or (nil? (:rules o)) (keyword? (:rules-tag o))))
            [(get-in main-db [:config :store :id]) mc (:max-tx main-db) bc (:max-tx bdb)
             agent-id target (:rules-tag o) (:zone-filter o) (count eff-rs)
             (rdata/agent-task-ids bdb ag) (:seon.agent/scope ag) (:seon.agent/context-prefs ag) (:seon.agent/target ag) (:seon.agent/pinned ag) (when rules/*base-rules-override* (System/identityHashCode rules/*base-rules-override*))])
         ru (if ck (store/lru-get! ctx-ru-cache 24 ck run-ru) (run-ru))
         cands (proj/augment-and-hydrate-candidates main-db bdb tdb ag world (:projs ru) o)]
     {:agent ag :world world :candidates cands :ctx-trace (:trace ru)})))

(defn- build-filtered-neighborhood [db aid]
  (let [{:keys [candidates]} (gather-agent-candidates db db aid {:target :wire :enrich? false :situational? false
                                                                :include-evals? false :semantic? true :use-cache? true})
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
                              ag (d/pull db '[:seon.agent/curated {:seon.agent/tasks [:seon.task/id]}] [:seon.agent/id agent-id])
                              merged (vec (distinct (concat (:seon.agent/curated ag) rs))) tid (first (keep :seon.task/id (:seon.agent/tasks ag)))
                              tx (cond-> [{:seon.agent/id agent-id :seon.agent/curated merged}]
                                   (and tid (first (d/datoms db :avet :seon.task/id tid)))
                                   (conj {:seon.task/id tid
                                          :seon.task/curated (vec (distinct (concat (:seon.task/curated (d/pull db '[:seon.task/curated] [:seon.task/id tid])) rs)))}))]
                          (d/transact conn {:tx-data tx}) {:curated merged :task tid}))
             {:arglists '([refs]) :doc "Curate lookup refs onto agent & task."})])))

(defn build
  "Builds an incremental pure 2-index (`:eavt` forward + `:avet` reverse across all ref attrs) tree snapshot for `eid` on `db`.
   Honors explicit `:seon.ctx/pattern` override when supplied; zero hand-curated attribute whitelists."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :any] [:maybe :map]]
                  [:=> [:cat :seon.host/db :any [:maybe :any]] [:maybe :map]]
                  [:=> [:cat :seon.host/db :any [:maybe :any] [:maybe :seon.host/conn]] [:maybe :map]]]
   :cost/ticks 25000000}
  ([db eid] (build db eid nil nil))
  ([db eid pattern] (build db eid pattern nil))
  ([db eid pattern conn]
   (let [resolved-eid (if (vector? eid) (:db/id (d/pull db '[:db/id] eid)) eid)]
     (when resolved-eid
       (let [stored-pat (when (contains? (rlinks/db-schema db) :seon.ctx/pattern)
                          (let [p (:seon.ctx/pattern (d/pull db '[:seon.ctx/pattern] resolved-eid))]
                            (when (and (vector? p) (seq p)) p)))
             pat (or pattern stored-pat)]
         (if pat
           (let [[res rs] (reads/with-reads #(d/pull db pat resolved-eid))
                 aid (:seon.agent/id res)
                 rev-entries (mapv #(tree/pull-reverse-entry db resolved-eid % #{resolved-eid}) (tree/all-ref-attrs db))
                 nb-entry (when (qualified-keyword? aid) (pull-neighborhood-entry db aid))
                 val (tree/assemble-tree-map db resolved-eid (if (map? res) res {}) rev-entries nb-entry)]
             {:eid resolved-eid :agent-id aid :pattern pat :fwd-entry {:key :seon.ctx/forward :val res :reads-idx (reads/reads-index rs)}
              :rev-entries rev-entries :nb-entry nb-entry :val val :tx (:max-tx db) :commit (d/commit-id db)})
           (let [fwd-entry (tree/pull-forward-entry db resolved-eid conn)
                 fwd-val (:val fwd-entry)
                 aid (:seon.agent/id fwd-val)
                 rev-entries (mapv #(tree/pull-reverse-entry db resolved-eid % #{resolved-eid}) (tree/all-ref-attrs db))
                 nb-entry (when (qualified-keyword? aid) (pull-neighborhood-entry db aid))
                 val (tree/assemble-tree-map db resolved-eid fwd-val rev-entries nb-entry)]
             {:eid resolved-eid :agent-id aid :pattern nil :fwd-entry fwd-entry
              :rev-entries rev-entries :nb-entry nb-entry :val val :tx (:max-tx db) :commit (d/commit-id db)})))))))

(defn advance
  "Incrementally updates 2-index tree `snap` to `db'` given `tx-datoms` (`O(|Δ|)`): re-reads ONLY forward or reverse slices touched by `tx-datoms`."
  {:malli/schema [:function
                  [:=> [:cat [:maybe :map] :seon.host/db [:maybe :any]] [:maybe :map]]
                  [:=> [:cat [:maybe :map] :seon.host/db [:maybe :any] [:maybe :seon.host/conn]] [:maybe :map]]]
   :cost/ticks 25000000}
  ([snap db' tx-datoms] (advance snap db' tx-datoms nil))
  ([snap db' tx-datoms conn]
   (cond
     (nil? snap) nil
     (and (= (:commit snap) (d/commit-id db')) (= (:tx snap) (:max-tx db'))) snap
     :else
     (let [eid (:eid snap)
           pattern-moved? (boolean (some #(and (= (:e %) eid) (= (:a %) :seon.ctx/pattern)) tx-datoms))]
       (if (or pattern-moved? (some? (:pattern snap)))
         (build db' eid nil conn)
         (let [changed? (volatile! false)
               fwd0 (:fwd-entry snap)
               fwd' (if (or (nil? fwd0) (nil? tx-datoms) (reads/reads-touched? (:reads-idx fwd0) tx-datoms))
                      (let [fresh (tree/pull-forward-entry db' eid conn)]
                        (when (not= (:val fresh) (:val fwd0)) (vreset! changed? true))
                        fresh)
                      fwd0)
               aid (or (:agent-id snap) (:seon.agent/id (:val fwd')))
               by-a (into {} (map (juxt :rev-attr identity)) (:rev-entries snap))
               rev' (mapv (fn [ra]
                            (let [prev (get by-a ra)]
                              (if (or (nil? prev) (nil? tx-datoms) (reads/reads-touched? (:reads-idx prev) tx-datoms))
                                (let [fresh (tree/pull-reverse-entry db' eid ra #{eid})]
                                  (when (not= (:val fresh) (:val prev)) (vreset! changed? true))
                                  fresh)
                                prev)))
                          (tree/all-ref-attrs db'))
               nb0 (:nb-entry snap)
               nb' (when (qualified-keyword? aid)
                     (if (or (nil? nb0) (nil? tx-datoms) (reads/reads-touched? (:reads-idx nb0) tx-datoms))
                       (let [fresh (pull-neighborhood-entry db' aid)]
                         (when (not= (:val fresh) (:val nb0)) (vreset! changed? true))
                         fresh)
                       nb0))
               val' (if @changed?
                      (tree/assemble-tree-map db' eid (:val fwd') rev' nb')
                      (with-meta
                        (assoc (:val snap)
                               :seon.ctx/branch (get-in db' [:config :branch] :main)
                               :seon.ctx/tx (:max-tx db')
                               :seon.ctx/commit (d/commit-id db'))
                        (meta (:val snap))))]
           (assoc snap :agent-id aid :fwd-entry fwd' :rev-entries rev' :nb-entry nb' :val val'
                  :tx (:max-tx db') :commit (d/commit-id db'))))))))

(defn ctx-atom
  "Creates a live bidirectional 2-index tree `CtxAtom` projection for entity `eid` on `conn`."
  {:malli/schema [:function [:=> [:cat :seon.host/conn :any] :any] [:=> [:cat :seon.host/conn :any [:maybe :any]] :any]]
   :cost/ticks 25000000}
  ([conn eid] (ctx-atom conn eid nil))
  ([conn eid pattern] (CtxAtom. conn (build @conn eid pattern conn) {} nil)))

(defn diff-entity-tx
  "Diffs pulled 2-index tree maps `old-m`/`new-m` for `eid` into Datahike tx-data (delegates to `seon.ctx.tree/diff-entity-tx`)."
  {:malli/schema [:=> [:cat :seon.host/db :int :map :map] [:vector :any]]
   :cost/ticks 10000000}
  [db eid old-m new-m]
  (tree/diff-entity-tx db eid old-m new-m))

(defn tail-rules-with-producers
  "Returns the ordered subset of `all-rs` needed to evaluate `:ephemeral-tail` rules on Turn 2+, including transitive `$wm` fact producers."
  {:malli/schema [:=> [:cat [:sequential :map]] [:maybe [:vector :map]]] :cost/ticks 500000}
  [all-rs]
  (let [tail-rs (filterv (fn [r]
                           (let [as (:seon.rule/assert r)]
                             (and (sequential? as) (seq as)
                                  (some #(and (map? %) (= :ephemeral-tail (:zone %))) as))))
                         all-rs)
        asserted-attrs (fn [r]
                         (into #{} (keep #(when (and (vector? %) (>= (count %) 2) (keyword? (second %))) (second %)))
                               (:seon.rule/assert r)))
        by-attr (reduce (fn [m r]
                          (reduce (fn [acc a] (update acc a (fnil conj []) r)) m (asserted-attrs r)))
                        {} all-rs)]
    (when (seq tail-rs)
      (loop [chosen (into {} (map (juxt :seon.rule/id identity)) tail-rs)
             frontier tail-rs]
        (let [need-attrs (into #{} (mapcat rules/rule-wm-read-attrs) frontier)
              add-rs (into [] (comp (mapcat #(get by-attr %))
                                    (remove #(contains? chosen (:seon.rule/id %))))
                           need-attrs)]
          (if (empty? add-rs)
            (vec (sort-by (juxt #(or (:seon.rule/stratum %) 0) #(str (:seon.rule/id %))) (vals chosen)))
            (recur (into chosen (map (juxt :seon.rule/id identity)) add-rs) add-rs)))))))

(deftype CtxAtom [conn ^:volatile-mutable snap ^:volatile-mutable watches ^:volatile-mutable validator]
  clojure.lang.IDeref
  (deref [this]
    (let [cur-db @conn]
      (when (and snap (or (not= (:commit snap) (d/commit-id cur-db)) (not= (:tx snap) (:max-tx cur-db))))
        (-advance! this cur-db nil))
      (:val snap)))

  clojure.lang.IRef
  (addWatch [this k f]
    (let [cur-db @conn]
      (when (and snap (or (not= (:commit snap) (d/commit-id cur-db)) (not= (:tx snap) (:max-tx cur-db))))
        (-advance! this cur-db nil)))
    (set! watches (assoc watches k f))
    this)
  (removeWatch [this k] (set! watches (dissoc watches k)) this)
  (getWatches [_] watches)
  (getValidator [_] validator)
  (setValidator [_ vf] (set! validator vf))

  ICtxAtom
  (-snapshot [_] snap)
  (-advance! [this db' tx-datoms]
    (let [old-val (:val snap)
          snap' (advance snap db' tx-datoms conn)]
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
          res (tree/apply-ctx-reset! conn (:eid snap) old-val new-val)]
      (if res (-advance! this (:db-after res) (:tx-data res)) old-val)))
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