(ns seon.ctx
  "Reactive `@seon.ctx/ctx` live bidirectional Datahike projection (`IDeref`/`IRef`/`IAtom2`,
   `O(|Δ|)` refresh, filtered `:seon.ctx/neighborhood`, `swap!` write-back) and ONE unified
   `project-entity` context engine."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [datahike.reads :as reads]
            [seon.code.files :as files]
            [seon.ctx.ground :as ground]
            [seon.ctx.project :as proj]
            [seon.ctx.touches :as ctouches]
            [seon.ctx.tree :as tree]
            [seon.health :as health]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks :refer [live-cid]]
            [seon.rule :as rules]
            [seon.rule.seed :as rseed]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge ctx-ru-cache (store/lru-cache 64))

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
    (store/without-agent-eval-reads #(-advance! ctx db' tx-datoms))))

(defn agent-world-datoms
  "Generic 2-read agent world discovery."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map] :cost/ticks 12000000}
  [main-db branch-db agent-id]
  (proj/agent-world-datoms main-db branch-db agent-id))

(defn entity-world-datoms
  "Generic 2-read focal world discovery for any entity lookup ref."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any] :map] :cost/ticks 12000000}
  [main-db branch-db focal-ref]
  (proj/entity-world-datoms main-db branch-db focal-ref))

(defn merge-pull-patterns
  "Monotonic union of Datahike `:pull` patterns."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]] :cost/ticks 500000}
  [patterns]
  (proj/merge-pull-patterns patterns))

(defn load-context-rules
  "Loads active `:context` rules from `main-db` + `branch-db` via `seon.rule/load-rules`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] [:maybe [:sequential :map]]] [:vector :map]]
   :cost/ticks 5000000}
  [main-db branch-db override]
  (if override
    (vec override)
    (let [bdb (or branch-db main-db)
          ms (rules/rules-db-sig main-db)
          bs (if (proj/same-db? main-db bdb) ms (rules/rules-db-sig bdb))
          m-rs (rules/load-rules main-db (rseed/starter-rules))
          all (if (= ms bs) m-rs (rules/load-rules bdb m-rs))]
      (filterv (fn [r] (or (= :context (:seon.rule/category r)) (some map? (:seon.rule/assert r)))) all))))

(defn diff-entity-tx
  "Diffs pulled 2-index tree maps `old-m`/`new-m` for `eid` into Datahike tx-data."
  {:malli/schema [:=> [:cat :seon.host/db :int :map :map] [:vector :any]] :cost/ticks 10000000}
  [db eid old-m new-m]
  (tree/diff-entity-tx db eid old-m new-m))

(defn- ^:seon.flow/edge branch-caused-health-facts [main-db branch-db derived-touches]
  (let [wnss (:written-nss derived-touches)]
    (when (and main-db branch-db (seq wnss) (not (proj/same-db? main-db branch-db)))
      (store/without-agent-eval-reads
       (fn []
         (let [dh (store/db-schema branch-db)
               struct-errs (try (health/caused-by-branch main-db branch-db {} wnss) (catch Throwable _ []))
               eff-errs (try
                          (let [br-errs (into [] (comp (mapcat #(health/static-efficiency-warnings branch-db %))
                                                       (filter #(= :error (:severity %)))) wnss)]
                            (if (empty? br-errs)
                              []
                              (let [p-key (juxt :class :subject)
                                    base-keys (into #{} (map p-key) (mapcat #(health/static-efficiency-warnings main-db %) wnss))]
                                (filterv #(not (contains? base-keys (p-key %))) br-errs))))
                          (catch Throwable _ []))
               resolve-p (fn [p]
                           (or (when (and (:var p) (dh :seon.code.var/name)) (:e (first (d/datoms branch-db :avet :seon.code.var/name (:var p)))))
                               (when (and (:ns p) (dh :seon.code.ns/name)) (:e (first (d/datoms branch-db :avet :seon.code.ns/name (:ns p)))))
                               (when (and (:schema p) (dh :seon.schema/key)) (:e (first (d/datoms branch-db :avet :seon.schema/key (:schema p)))))))]
           (into [] (keep (fn [p] (when-let [e (resolve-p p)] [e :world/branch-caused-issue (or (:class p) :health-error)])))
                 (concat struct-errs eff-errs))))))))

(defn- eval-entity-rules [main-db bdb tdb world aid ag eff-rs disabled rule-target derived-t o]
  (let [mc (live-cid main-db) bc (live-cid bdb)
        run-ru (fn []
                 (if (empty? eff-rs)
                   {:projs [] :runs {} :recomputed 0 :skipped 0}
                   (let [dbs {:main main-db :branch bdb :code bdb :task tdb}
                         extra (branch-caused-health-facts main-db bdb derived-t)
                         sr (rules/step-rules nil dbs
                                              {:agent-id aid :focus-ref (:focus-ref world) :focus-eid (:focus-eid world)
                                               :base-wm (proj/seed-world-wm world derived-t extra)}
                                              eff-rs nil rule-target)]
                     {:projs (proj/collapse-rule-projections (:projections sr))
                      :runs (:runs sr) :recomputed (:recomputed sr) :skipped (:skipped sr)})))
        ck (when (and (not (false? (:use-cache? o))) mc bc (empty? disabled) (nil? (:rules o)))
             [(get-in main-db [:config :store :id]) mc (:max-tx main-db) bc (:max-tx bdb)
              (:focus-ref world) aid rule-target (count eff-rs)
              (when ag (rdata/agent-task-ids bdb ag)) (:seon.agent/scope ag) (:seon.agent/pinned ag)
              (:seon.agent/target ag) (:seon.agent/context-prefs ag) (:sig derived-t)
              (when rules/*base-rules-override* (System/identityHashCode rules/*base-rules-override*))])]
    (if ck (store/lru-get! ctx-ru-cache 64 ck run-ru) (run-ru))))

(defn- fit-entity-results [main-db bdb world ranked fit-target o]
  (let [fit-items (filterv #(not= :agent-prefix (:zone %)) ranked)
        fb (or (:fallback-renderers o) fit/fallback-entity-renderers)
        fopts (merge {:target fit-target :main-db main-db :fallback-renderers fb}
                     (select-keys o [:avail-w :area-budget :per-item-budget :token-budget :max-items
                                     :strategy :weights :turn :already-shown :viewport-w :viewport-h
                                     :sidebar-open? :slot :browser-boxes :text-fn]))
        fitted (if (:skip-fit? o)
                 {:selected [] :dropped [] :tokens 0 :area 0 :budget (long (or (:token-budget o) (:area-budget o) 0))
                  :target fit-target :strategy (or (:strategy o) :breadth-first) :shown (or (:already-shown o) {})}
                 (if (:explain? o)
                   (fit/explain-candidates bdb fit-items fopts)
                   (fit/fit-projections bdb fit-items fopts)))
        focal-fit (when (and (not (false? (:include-focal-fit? o))) (not (:skip-fit? o))
                             (map? (:focus-data world)) (seq (dissoc (:focus-data world) :db/id)))
                    (let [fi {:entity (or (:focus-eid world) (:focus-ref world))
                              :ref (:focus-ref world) :schema (:focus-schema world)
                              :data (:focus-data world) :priority 100}
                          ff-opts (merge {:target fit-target :max-items 1 :main-db main-db :fallback-renderers fb}
                                         (if (= :html fit-target)
                                           {:avail-w (long (or (:avail-w o) 960)) :area-budget (long (or (:area-budget o) 600000))}
                                           {:token-budget (long (or (:focal-token-budget o) 1200))}))]
                      (first (:selected (fit/fit-projections bdb [fi] ff-opts)))))]
    {:fitted fitted :focal-fit focal-fit}))

(defn project-entity
  "ONE unified rule-projected entity context engine for BOTH humans (`:target :html`) and agents (`:target :wire`).
   Works identically for any `focal-ref` (`[:seon.agent/id ...]`, `[:seon.system/id :root]`, tasks, vars, namespaces,
   schemas, errors, branches, signals) with optional `:perspective-agent`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any [:maybe :map]] :map]]
   :cost/ticks 50000000}
  ([main-db branch-db focal-ref] (project-entity main-db branch-db focal-ref nil))
  ([main-db branch-db focal-ref opts]
   (let [o (if (map? opts) opts {})
         raw-bdb (or branch-db main-db)
         hop2? (boolean (:include-hop2-unmatched? o))
         init-world (proj/entity-world-datoms main-db raw-bdb focal-ref hop2?)
         focus-kind (:focus-kind init-world)
         aid (or (:perspective-agent o) (when (= :agent focus-kind) (second (:focus-ref init-world))))
         ag (when aid (or (:agent o)
                          (when (= :agent focus-kind) (:focus-data init-world))
                          (rdata/pull-agent-entity main-db raw-bdb aid)
                          {:seon.agent/id aid}))
         bdb (if (and (= :agent focus-kind) (rdata/reads-main? ag)) main-db raw-bdb)
         world (if (identical? bdb raw-bdb) init-world (proj/entity-world-datoms main-db bdb focal-ref hop2?))
         tdb (or (:task-db o)
                 (when ag
                   (let [tids (rdata/agent-task-ids bdb ag)]
                     (when (and (seq tids) ((rlinks/db-schema bdb) :seon.task/id)
                                (some #(first (d/datoms bdb :avet :seon.task/id %)) tids))
                       bdb)))
                 main-db)
         rule-target (case (:target o) (:wire :html :canvas :workspace :debug :any) (:target o) :any)
         fit-target (if (= :wire (:target o)) :wire :html)
         prefs (if (map? (:seon.agent/context-prefs ag)) (:seon.agent/context-prefs ag) {})
         disabled (into #{} (concat (when (coll? (:disabled-rules prefs)) (:disabled-rules prefs))
                                    (when (coll? (:disabled-rules o)) (:disabled-rules o))))
         all-rs (load-context-rules main-db bdb (:rules o))
         eff-rs (filterv #(not (contains? disabled (:seon.rule/id %))) all-rs)
         use-lt? (if (contains? o :use-live-touches?) (boolean (:use-live-touches? o)) (some? aid))
         derived-t (or (:derived-touches o) (when (and aid use-lt?) (ctouches/derived-agent-touches main-db bdb aid)))
         same-mb? (proj/same-db? main-db bdb)
         branch-writes (into (set (:written derived-t)) (when-not same-mb? (rules/edited-form-eids main-db bdb)))
         ru (eval-entity-rules main-db bdb tdb world aid ag eff-rs disabled rule-target derived-t o)
         tc-m (or (:counts derived-t) {}) eng-m (or (:energy derived-t) {})
         fs-m (or (:novel-frontier derived-t) {}) wr-s (or (:written derived-t) #{})
         hyd0 (ground/hydrate-candidates main-db bdb tdb (ground/merge-raw-candidates (:projs ru)) o)
         hyd1 (mapv (fn [c]
                      (let [e (:entity c) tc (long (get tc-m e 0)) eng (double (get eng-m e 0.0))
                            fs (double (get fs-m e 0.0))]
                        (cond-> c
                          (pos? tc) (assoc :touches (max tc (long (:touches c 0))))
                          (pos? eng) (assoc :touch-energy (max eng (double (or (:touch-energy c) 0.0))))
                          (pos? fs) (assoc :frontier-score (max fs (double (or (:frontier-score c) 0.0))))
                          (contains? wr-s e) (assoc :written? true))))
                    hyd0)
         hydrated (if (not (false? (:semantic? o)))
                    (ground/attach-semantic-scores main-db bdb (or ag (:focus-ref world)) hyd1)
                    hyd1)
         r-opts (cond-> (select-keys o [:turn])
                  (or (:weights o) (nil? (:turn o))) (assoc :weights (or (:weights o) (fit/read-rank-weights bdb))))
         ranked (fit/rank-candidates hydrated r-opts)
         {:keys [fitted focal-fit]} (fit-entity-results main-db bdb world ranked fit-target o)
         matched-eids (into #{} (keep :entity) ranked)
         unmatched (if (false? (:include-unmatched? o))
                     []
                     (proj/build-unmatched-neighbors main-db bdb world matched-eids (boolean (:include-hop2-unmatched? o))))
         r-trace (if (false? (:include-rule-trace? o)) [] (proj/build-rule-trace all-rs (:runs ru) disabled))
         runs-s (sort-by (juxt :stratum #(str (:rule-id %))) (vals (:runs ru)))
         ctx-trace {:agent aid :focus-ref (:focus-ref world) :target rule-target
                    :rules-total (count all-rs) :recomputed (:recomputed ru) :skipped (:skipped ru)
                    :fired-rules (vec (keep #(when (pos? (long (:fired % 0)))
                                               (select-keys % [:rule-id :stratum :category :fired :reads-count :ms :skipped?]))
                                            runs-s))
                    :skipped-rules (vec (keep #(when (:skipped? %) (:rule-id %)) runs-s))
                    :world-discovery {:focus-eid (:focus-eid world) :datoms-count (:datoms-count world)
                                      :hop1-count (count (:hop1-eids world)) :hop2-count (count (:hop2-eids world))
                                      :reverse-attrs (:reverse-attrs world)}}]
     {:focus-ref (:focus-ref world) :focus-eid (:focus-eid world) :focus-kind focus-kind
      :focus-data (:focus-data world) :focus-schema (:focus-schema world) :focal-fit focal-fit
      :world {:datoms-count (:datoms-count world) :hop1-eids (:hop1-eids world)
              :hop2-eids (:hop2-eids world) :branch-writes branch-writes :agent-eid (:agent-eid world)}
      :candidates ranked :unmatched unmatched :fitted fitted
      :rule-trace r-trace :ctx-trace ctx-trace :agent ag :derived-touches derived-t})))

(defn gather-agent-candidates
  "Compatibility wrapper delegating directly to `project-entity` (used by `surface*.clj` and `signals/touches.clj`)."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword [:maybe :map]] :map]]
   :cost/ticks 45000000}
  ([main-db branch-db agent-id] (gather-agent-candidates main-db branch-db agent-id nil))
  ([main-db branch-db agent-id opts]
   (let [o (if (map? opts) opts {})
         res (project-entity main-db branch-db [:seon.agent/id agent-id]
                             (assoc o :skip-fit? true :include-focal-fit? false :include-unmatched? false))]
     (select-keys res [:agent :world :candidates :ctx-trace :derived-touches :rule-trace]))))

(defn- build-filtered-neighborhood [db aid]
  (let [{:keys [candidates]} (project-entity db db [:seon.agent/id aid]
                                             {:target :wire :enrich? false :include-evals? false
                                              :semantic? true :skip-fit? true :include-focal-fit? false
                                              :include-unmatched? false})
        ranked (take 8 (filter #(and (integer? (:entity %)) (map? (:data %)) (integer? (get-in % [:data :db/id])))
                               candidates))]
    (reduce (fn [acc item]
              (let [ent (:data item)
                    grp (cond (:seon.task/id ent) :tasks (:seon.code.var/name ent) :vars (:seon.error/id ent) :errors :else :pinned)]
                (assoc-in acc [grp (neighborhood-node-key ent)] ent)))
            {:tasks {} :vars {} :errors {} :pinned {}} ranked)))

(defn- pull-neighborhood-entry [db aid]
  (when (qualified-keyword? aid)
    (let [[nb rs] (reads/with-reads #(build-filtered-neighborhood db aid))]
      {:key :seon.ctx/neighborhood :val nb :reads-idx (reads/reads-index rs)})))

(defn context-trace
  "Self-introspection trace for `target-ref` (agent keyword or lookup ref) via `project-entity`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any [:maybe :map]] :map]]
   :cost/ticks 50000000}
  ([main-db branch-db target-ref] (context-trace main-db branch-db target-ref nil))
  ([main-db branch-db target-ref opts]
   (let [o (if (map? opts) opts {})
         ref (if (vector? target-ref) target-ref [:seon.agent/id target-ref])
         res (project-entity main-db (or branch-db main-db) ref
                             (merge {:target :wire :token-budget 16000} o))
         fitted (:fitted res)
         sel (:selected fitted)
         drp (:dropped fitted)]
     (assoc (:ctx-trace res)
            :token-budget (:budget fitted)
            :tokens (:tokens fitted)
            :rule-trace (:rule-trace res)
            :unmatched (:unmatched res)
            :blocks (mapv #(select-keys % [:entity :ref :schema :rules :chosen-fn :pull :params :zone :parent :priority :status :tokens :tx :rank/score]) sel)
            :dropped (mapv #(select-keys % [:entity :ref :schema :rules :priority :tokens]) drp)))))

(defn diff-context
  "A/B diffs context projections and token counts between `rules-a` and `rules-b` via `project-entity`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any [:maybe [:vector :map]] [:maybe [:vector :map]]] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any [:maybe [:vector :map]] [:maybe [:vector :map]] [:maybe :map]] :map]]
   :cost/ticks 60000000}
  ([main-db branch-db target-ref rules-a rules-b] (diff-context main-db branch-db target-ref rules-a rules-b nil))
  ([main-db branch-db target-ref rules-a rules-b opts]
   (let [o (if (map? opts) opts {})
         ref (if (vector? target-ref) target-ref [:seon.agent/id target-ref])
         base (merge {:target :wire :token-budget 16000 :use-cache? false} o)
         ra (project-entity main-db (or branch-db main-db) ref (cond-> base rules-a (assoc :rules rules-a)))
         rb (project-entity main-db (or branch-db main-db) ref (cond-> base rules-b (assoc :rules rules-b)))
         sel-a (get-in ra [:fitted :selected])
         sel-b (get-in rb [:fitted :selected])
         ma (into {} (map (juxt :entity identity)) sel-a)
         mb (into {} (map (juxt :entity identity)) sel-b)
         ks-a (set (keys ma)) ks-b (set (keys mb))
         tok-a (long (get-in ra [:fitted :tokens] 0))
         tok-b (long (get-in rb [:fitted :tokens] 0))
         rta (into {} (map (juxt :rule :matched-count)) (:rule-trace ra))
         rtb (into {} (map (juxt :rule :matched-count)) (:rule-trace rb))
         rids (sort (set/union (set (keys rta)) (set (keys rtb))))]
     {:agent (when (= :seon.agent/id (first ref)) (second ref))
      :focus-ref ref
      :target (get-in ra [:fitted :target])
      :tokens-before tok-a
      :tokens-after tok-b
      :tokens-delta (- tok-b tok-a)
      :added-entities (vec (sort-by str (set/difference ks-b ks-a)))
      :removed-entities (vec (sort-by str (set/difference ks-a ks-b)))
      :changed-pulls (vec (keep #(let [pa (:pull (get ma %)) pb (:pull (get mb %))]
                                   (when (not= pa pb) {:entity % :before pa :after pb}))
                                (sort-by str (set/intersection ks-a ks-b))))
      :rule-diffs (vec (keep #(let [a (get rta % 0) b (get rtb % 0)]
                                (when (not= a b) {:rule % :before a :after b}))
                             rids))
      :trace-before (:ctx-trace ra)
      :trace-after (:ctx-trace rb)})))

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
               ([t o] (let [{:keys [mdb bdb aid]} (dbs)] (context-trace mdb bdb (if (or (qualified-keyword? t) (vector? t)) t aid) o))))
             {:arglists '([] [opts-or-agent] [agent-or-ref opts]) :doc "Inspect context rules, unmatched neighbors & fitted blocks."})
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
  "Builds an incremental pure 2-index tree snapshot for `eid` on `db`."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :any] [:maybe :map]]
                  [:=> [:cat :seon.host/db :any [:maybe :any]] [:maybe :map]]
                  [:=> [:cat :seon.host/db :any [:maybe :any] [:maybe :seon.host/conn]] [:maybe :map]]]
   :cost/ticks 25000000}
  ([db eid] (build db eid nil nil))
  ([db eid pattern] (build db eid pattern nil))
  ([db eid pattern conn]
   (when-let [reid (if (vector? eid) (:db/id (d/pull db '[:db/id] eid)) eid)]
     (let [stored (when (contains? (rlinks/db-schema db) :seon.ctx/pattern)
                    (let [p (:seon.ctx/pattern (d/pull db '[:seon.ctx/pattern] reid))] (when (and (vector? p) (seq p)) p)))
           pat (or pattern stored)
           fwd-entry (if pat
                       (let [[res rs] (reads/with-reads #(d/pull db pat reid))]
                         {:key :seon.ctx/forward :val (if (map? res) res {}) :reads-idx (reads/reads-index rs)})
                       (tree/pull-forward-entry db reid conn))
           fwd-val (:val fwd-entry) aid (:seon.agent/id fwd-val)
           rev-entries (mapv #(tree/pull-reverse-entry db reid % #{reid}) (tree/all-ref-attrs db))
           nb-entry (when (qualified-keyword? aid) (pull-neighborhood-entry db aid))]
       {:eid reid :agent-id aid :pattern pat :fwd-entry fwd-entry :rev-entries rev-entries :nb-entry nb-entry
        :val (tree/assemble-tree-map db reid fwd-val rev-entries nb-entry) :tx (:max-tx db) :commit (d/commit-id db)}))))

(defn advance
  "Incrementally updates 2-index tree `snap` to `db'` given `tx-datoms` (`O(|Δ|)`)."
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
                        (when (not= (:val fresh) (:val fwd0)) (vreset! changed? true)) fresh)
                      fwd0)
               aid (or (:agent-id snap) (:seon.agent/id (:val fwd')))
               by-a (into {} (map (juxt :rev-attr identity)) (:rev-entries snap))
               rev' (mapv (fn [ra]
                            (let [prev (get by-a ra)]
                              (if (or (nil? prev) (nil? tx-datoms) (reads/reads-touched? (:reads-idx prev) tx-datoms))
                                (let [fresh (tree/pull-reverse-entry db' eid ra #{eid})]
                                  (when (not= (:val fresh) (:val prev)) (vreset! changed? true)) fresh)
                                prev)))
                          (tree/all-ref-attrs db'))
               nb0 (:nb-entry snap)
               nb' (when (qualified-keyword? aid)
                     (if (or (nil? nb0) (nil? tx-datoms) (reads/reads-touched? (:reads-idx nb0) tx-datoms))
                       (let [fresh (pull-neighborhood-entry db' aid)]
                         (when (not= (:val fresh) (:val nb0)) (vreset! changed? true)) fresh)
                       nb0))
               val' (if @changed?
                      (tree/assemble-tree-map db' eid (:val fwd') rev' nb')
                      (with-meta (assoc (:val snap) :seon.ctx/branch (get-in db' [:config :branch] :main)
                                        :seon.ctx/tx (:max-tx db') :seon.ctx/commit (d/commit-id db'))
                                 (meta (:val snap))))]
           (assoc snap :agent-id aid :fwd-entry fwd' :rev-entries rev' :nb-entry nb' :val val'
                  :tx (:max-tx db') :commit (d/commit-id db'))))))))

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
    (set! watches (assoc watches k f)) this)
  (removeWatch [this k] (set! watches (dissoc watches k)) this)
  (getWatches [_] watches)
  (getValidator [_] validator)
  (setValidator [_ vf] (set! validator vf))
  ICtxAtom
  (-snapshot [_] snap)
  (-advance! [this db' tx-datoms]
    (let [old-val (:val snap) snap' (advance snap db' tx-datoms conn)]
      (set! snap snap')
      (let [new-val (:val snap')]
        (when (and (seq watches) (not= old-val new-val))
          (doseq [[k wf] watches] (wf k this old-val new-val)))
        new-val)))
  clojure.lang.IAtom2
  (reset [this new-val]
    (when-not (map? new-val) (throw (ex-info "ctx value must be a map" {:error :seon.ctx/invalid :got new-val})))
    (when (and validator (not (validator new-val))) (throw (ex-info "ctx validator rejected value" {:error :seon.ctx/invalid})))
    (let [old-val (.deref this) res (tree/apply-ctx-reset! conn (:eid snap) old-val new-val)]
      (if res (-advance! this (:db-after res) (:tx-data res)) old-val)))
  (swap [this f] (.reset this (f (.deref this))))
  (swap [this f a] (.reset this (f (.deref this) a)))
  (swap [this f a b] (.reset this (f (.deref this) a b)))
  (swap [this f a b args] (.reset this (apply f (.deref this) a b args)))
  (compareAndSet [this oldv newv] (if (= oldv (.deref this)) (do (.reset this newv) true) false))
  (resetVals [this newv] (let [old (.deref this)] [old (.reset this newv)]))
  (swapVals [this f] (let [old (.deref this)] [old (.reset this (f old))]))
  (swapVals [this f a] (let [old (.deref this)] [old (.reset this (f old a))]))
  (swapVals [this f a b] (let [old (.deref this)] [old (.reset this (f old a b))]))
  (swapVals [this f a b args] (let [old (.deref this)] [old (.reset this (apply f old a b args))])))

(defn ctx-atom
  "Creates a live bidirectional 2-index tree `CtxAtom` projection for entity `eid` on `conn`."
  {:malli/schema [:function [:=> [:cat :seon.host/conn :any] :any] [:=> [:cat :seon.host/conn :any [:maybe :any]] :any]]
   :cost/ticks 25000000}
  ([conn eid] (ctx-atom conn eid nil))
  ([conn eid pattern] (CtxAtom. conn (build @conn eid pattern conn) {} nil)))
