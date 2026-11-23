(ns seon.ctx.project
  "Pure generic 2-read agent world discovery, monotonic pull-key-set union,
   Datahike entity projection pulling, zero-repetition / re-warming deltas,
   and unified candidate hydration & Upgrade Ladder budgeting for `seon.ctx`."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.ctx.ground :as ground]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks :refer [db-schema db-cache-sig live-cid]]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge rev-agent-attrs-cache (store/lru-cache 16))

(defn same-db?
  "True when `a` and `b` represent the same immutable Datahike snapshot (identity or equal live commit-id)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] :boolean]
   :cost/ticks 50000}
  [a b]
  (boolean (or (= a b)
               (let [ca (live-cid a) cb (live-cid b)]
                 (and ca cb (= ca cb))))))

(defn fix-matches-scope?
  "Delegates to `seon.ctx.ground/fix-matches-scope?` (for `:rule.ctx/scope-task-history-and-fixes`)."
  {:malli/schema [:=> [:cat :any :any :any :any] :boolean]
   :cost/ticks 50000}
  [scope error-class change-summary regression-test]
  (ground/fix-matches-scope? scope error-class change-summary regression-test))

(defn- reverse-agent-ref-attrs [db]
  (let [compute (fn []
                  (let [dh-sch (db-schema db)
                        sch-eid (when (contains? dh-sch :seon.schema/key)
                                  (:e (first (d/datoms db :avet :seon.schema/key :seon.agent/entity))))
                        from-db (when (and sch-eid (contains? dh-sch :seon.schema/ref-targets))
                                  (vec (keep #(:v (first (d/datoms db :eavt (:e %) :seon.schema/key)))
                                             (d/datoms db :avet :seon.schema/ref-targets sch-eid))))
                        raw (if (seq from-db)
                              from-db
                              (six/reaching (:entity-index (fit/cached-registry-info db)) :seon.agent/entity))]
                    (->> raw
                         (filter #(= :db.type/ref (get-in dh-sch [% :db/valueType])))
                         distinct sort vec)))
        sig (db-cache-sig db)]
    (if sig (store/lru-get! rev-agent-attrs-cache 16 sig compute) (compute))))

(defn- merge-forward-datoms [main-db branch-db eid]
  (let [m-ds (when main-db (vec (d/datoms main-db :eavt eid)))
        b-ds (when (and branch-db (not (same-db? branch-db main-db))) (vec (d/datoms branch-db :eavt eid)))]
    (if (empty? b-ds)
      (or m-ds [])
      (let [m-by-a (group-by :a m-ds)
            b-by-a (group-by :a b-ds)
            all-a (sort (set/union (set (keys m-by-a)) (set (keys b-by-a))))
            max-tx (fn [ds] (reduce max 0 (map #(long (or (:tx %) 0)) ds)))]
        (vec (mapcat (fn [a]
                       (let [ms (get m-by-a a) bs (get b-by-a a)]
                         (cond (empty? ms) bs (empty? bs) ms (>= (max-tx bs) (max-tx ms)) bs :else ms)))
                     all-a))))))

(defn agent-world-datoms
  "Generic 2-read agent discovery: forward `:eavt` merged by latest `:tx` (resolving `:pinned`, `:curated`,
   virtual health pins, and `:extra-namespaces`) + reverse `:avet` refs targeting `:seon.agent/entity`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map]
   :cost/ticks 8000000}
  [main-db branch-db agent-id]
  (let [bdb (or branch-db main-db)
        agent-eid (or (when (contains? (db-schema bdb) :seon.agent/id)
                        (:e (first (d/datoms bdb :avet :seon.agent/id agent-id))))
                      (when (contains? (db-schema main-db) :seon.agent/id)
                        (:e (first (d/datoms main-db :avet :seon.agent/id agent-id)))))]
    (if-not agent-eid
      {:agent-eid nil :forward [] :reverse [] :reverse-attrs [] :connected {} :pinned #{} :curated #{} :extra-nss #{} :virtual-pins []}
      (let [fwd (merge-forward-datoms main-db bdb agent-eid)
            rev-attrs (reverse-agent-ref-attrs bdb)
            same? (same-db? bdb main-db)
            rev-ds (vec (distinct
                         (mapcat (fn [a]
                                   (concat
                                    (when (contains? (db-schema main-db) a) (d/datoms main-db :avet a agent-eid))
                                    (when (and (not same?) (contains? (db-schema bdb) a))
                                      (d/datoms bdb :avet a agent-eid))))
                                 rev-attrs)))
            dh-sch (if same? (db-schema main-db) (merge (db-schema main-db) (db-schema bdb)))
            pinned-eids (volatile! #{}) curated-eids (volatile! #{})
            extra-ns-eids (volatile! #{}) vpins (volatile! [])
            fwd-max-tx (reduce max 0 (map #(long (or (:tx %) 0)) fwd))
            conn-map (volatile! {agent-eid fwd-max-tx})
            add-conn! (fn [e tx] (when (integer? e) (vswap! conn-map update e #(max (long (or % 0)) (long (or tx 0))))))]
        (doseq [dt fwd :let [a (:a dt) v (:v dt) tx (:tx dt)]]
          (cond
            (= :db.type/ref (get-in dh-sch [a :db/valueType]))
            (add-conn! v tx)

            (contains? #{:seon.agent/pinned :seon.agent/curated} a)
            (doseq [item (if (coll? v) v [v])]
              (if (and (vector? item) (= 2 (count item)) (= :seon.health/summary (first item)))
                (vswap! vpins conj item)
                (when-let [e (ground/resolve-lookup-eid main-db bdb item)]
                  (add-conn! e tx)
                  (if (= :seon.agent/pinned a) (vswap! pinned-eids conj e) (vswap! curated-eids conj e)))))

            (and (= :seon.agent/context-prefs a) (map? v))
            (doseq [nsym (:extra-namespaces v) :when (symbol? nsym)]
              (when-let [e (ground/resolve-lookup-eid main-db bdb [:seon.code.ns/name nsym])]
                (add-conn! e tx) (vswap! extra-ns-eids conj e)))))
        (doseq [dt rev-ds] (add-conn! (:e dt) (:tx dt)))
        {:agent-eid agent-eid :forward fwd :reverse rev-ds :reverse-attrs rev-attrs
         :connected @conn-map :pinned @pinned-eids :curated @curated-eids
         :extra-nss @extra-ns-eids :virtual-pins @vpins}))))

(defn merge-pull-patterns
  "Pure, commutative, associative, deterministic union of Datahike `:pull` patterns."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]]
   :cost/ticks 500000}
  [patterns]
  (let [step (fn [{:keys [star? scalars nested other] :as acc} clause]
               (cond
                 (= '* clause) (assoc acc :star? true)
                 (keyword? clause) (update acc :scalars conj clause)
                 (map? clause) (reduce-kv (fn [a k sub] (if (sequential? sub) (update-in a [:nested k] (fnil conj []) (vec sub)) a)) acc clause)
                 (some? clause) (update acc :other conj clause)
                 :else acc))
        {:keys [star? scalars nested other]}
        (reduce (fn [acc pat] (reduce step acc pat)) {:star? false :scalars #{} :nested {} :other #{}} patterns)
        merged-nested (into {} (map (fn [[k subs]] [k (merge-pull-patterns subs)])) nested)
        nested-vec (mapv (fn [[k sub]] {k sub}) (sort-by (comp str key) merged-nested))
        scalar-vec (if star? ['*] (vec (sort (remove #(contains? merged-nested %) scalars))))]
    (vec (concat scalar-vec nested-vec (sort-by pr-str other)))))

(defn- validate-projection-params [db fn-sym params reg-cache]
  (if (or (empty? params) (nil? db) (not (qualified-symbol? fn-sym))
          (not (contains? (db-schema db) :seon.code.var/name)))
    params
    (let [ve (:e (first (d/datoms db :avet :seon.code.var/name fn-sym)))
          opt-ks (when (and ve (contains? (db-schema db) :seon.code.var/optional-input))
                   (into #{} (keep #(:v (first (d/datoms db :eavt (:v %) :seon.schema/key))))
                         (d/datoms db :eavt ve :seon.code.var/optional-input)))]
      (if (empty? opt-ks)
        params
        (let [ri (or @reg-cache (let [r (fit/cached-registry-info db)] (vreset! reg-cache r) r))
              known-forms (:forms ri)]
          (reduce-kv (fn [acc k v]
                       (if (and (contains? opt-ks k)
                                (or (not (contains? known-forms k)) (:ok? (schema/validate-against db k v))))
                         (assoc acc k v) acc))
                     {} params))))))

(defn- pull-entity-for-projection [dbs eid pull-pat]
  (let [bdb (:branch dbs) mdb (:main dbs) eff-pat (if (seq pull-pat) pull-pat '[:db/id])]
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
        reg-cache (volatile! nil)
        grouped (group-by :entity raw-projections)
        capped (if (> (count grouped) 48)
                 (->> grouped
                      (sort-by (fn [[eid ps]]
                                 [(- (reduce max 50 (map #(long (or (:priority %) 50)) ps)))
                                  (- (reduce max 0 (map #(long (or (:tx %) 0)) ps)))
                                  (str eid)]))
                      (take 48))
                 grouped)]
    (->> capped
         (mapv (fn [[eid ps]]
                 (let [ordered (sort-by (fn [p] [(long (or (:stratum p) 50)) (long (or (:priority p) 50)) (str (:rule p))]) ps)
                       merged-pull (merge-pull-patterns (keep :pull ordered))
                       win-fn (or (last (keep :fn ordered)) 'seon.render/render-entity-value)
                       params (validate-projection-params eff-db win-fn (reduce merge {} (keep :params ordered)) reg-cache)
                       {:keys [data tx]} (pull-entity-for-projection dbs eid merged-pull)]
                   {:entity eid :data data :fn win-fn :pull merged-pull :params params
                    :priority (reduce max 50 (map #(long (or (:priority %) 50)) ordered))
                    :parent (last (keep :parent ordered))
                    :zone (or (last (keep :zone ordered)) :workspace)
                    :rules (vec (sort-by str (distinct (keep :rule ordered))))
                    :why (vec (distinct (keep :why ordered)))
                    :tags (into #{} (mapcat #(let [t (:tags %)] (if (coll? t) t (when t [t])))) ordered)
                    :tx (reduce max (long tx) (map #(long (or (:tx %) 0)) ordered))})))
         (sort-by (fn [p] [(- (long (:priority p 50))) (str (:entity p))]))
         vec)))

(defn compute-shown-delta
  "Pure zero-repetition / upgrade / re-warming delta over `already-shown` and `projections`."
  {:malli/schema [:=> [:cat [:maybe :map] [:vector :map]] [:map [:projections [:vector :map]] [:shown :map]]]
   :cost/ticks 5000000}
  [already-shown projections]
  (let [shown0 (if (map? already-shown) already-shown {})
        id-key-of (fn [m] (first (filter #(and (keyword? %) (not= :db/id %) (contains? #{"id" "name" "key"} (name %))) (sort (keys m)))))]
    (reduce
     (fn [{:keys [projections shown]} p]
       (let [eid (:entity p) data (if (map? (:data p)) (:data p) {})
             wanted-ks (disj (set (keys data)) :db/id) cur-tx (long (or (:tx p) 0))
             prev (get shown0 eid) shown-ks (set (:keys prev)) prev-tx (long (or (:tx prev) 0))
             new-ks (set/difference wanted-ks shown-ks)
             k-hashes (into {} (map (fn [k] [k (hash (get data k))])) wanted-ks)
             overlap-changed?
             (when prev
               (if-let [pkh (:key-hashes prev)]
                 (boolean (some (fn [k] (and (contains? pkh k) (not= (get pkh k) (get k-hashes k)))) wanted-ks))
                 (let [overlap (set/intersection wanted-ks shown-ks)]
                   (and (some? (:hash prev)) (= overlap shown-ks) (not= (:hash prev) (hash (select-keys data overlap)))))))
             [status delta-data delta-ks]
             (cond
               (nil? prev) [:new data wanted-ks]
               (or overlap-changed? (> cur-tx prev-tx)) [:rewarmed data wanted-ks]
               (seq new-ks)
               (let [id-k (id-key-of data)
                     keep-ks (cond-> new-ks id-k (conj id-k) (contains? data :db/id) (conj :db/id))]
                 [:upgraded (select-keys data keep-ks) new-ks])
               :else [:unchanged {} #{}])
             next-entry (if (= :unchanged status) prev
                            {:keys (if (= :rewarmed status) wanted-ks (set/union shown-ks wanted-ks))
                             :hash (hash (dissoc data :db/id))
                             :key-hashes (if (= :rewarmed status) k-hashes (merge (:key-hashes prev) k-hashes))
                             :tx (max prev-tx cur-tx)})]
         {:projections (conj projections (assoc p :status status :delta-data delta-data :delta-keys delta-ks))
          :shown (assoc shown eid next-entry)}))
     {:projections [] :shown shown0}
     projections)))

(defn budget-and-order-projections
  "Ranks projections with `seon.render.fit/rank-candidates` and enforces budgets via `seon.render.fit/upgrade-ladder`."
  {:malli/schema [:=> [:cat [:vector :map] :int [:? [:maybe :map]]]
                  [:map [:selected [:vector :map]] [:dropped [:vector :map]] [:tokens :int]]]
   :cost/ticks 15000000}
  ([projs budget] (budget-and-order-projections projs budget nil))
  ([projs budget fit-opts]
   (let [opts (if (map? fit-opts) fit-opts {})
         eff-budget (max 0 (long budget))
         with-toks (mapv (fn [p]
                           (let [cands (:candidates p)]
                             (if (and (sequential? cands) (seq cands) (every? map? cands))
                               p
                               (let [tok (if (= :unchanged (:status p)) 0
                                             (if (integer? (:tokens p)) (max 0 (long (:tokens p)))
                                                 (max 1 (quot (count (pr-str (:delta-data p))) 4))))]
                                 (assoc p :tokens tok)))))
                         projs)
         ranked (fit/rank-candidates with-toks (select-keys opts [:weights]))
         ladder-opts (merge {:target :wire :strategy :breadth-first :token-budget eff-budget}
                            (select-keys opts [:strategy :target :viewport-w :viewport-h
                                               :sidebar-open? :slot :avail-w :area-budget
                                               :max-items :already-shown :token-budget]))
         res (fit/upgrade-ladder ranked ladder-opts)]
     {:selected (:selected res) :dropped (:dropped res) :tokens (long (:tokens res 0))
      :area (long (:area res 0)) :target (:target res) :strategy (:strategy res) :shown (:shown res)})))

(defn- merge-raw-candidates [all-raw]
  (reduce (fn [acc it]
            (let [e (:entity it)]
              (if-let [prev (get acc e)]
                (assoc acc e
                       (-> prev
                           (assoc :priority (max (long (:priority prev 50)) (long (:priority it 50)))
                                  :tx (max (long (:tx prev 0)) (long (:tx it 0)))
                                  :zone (if (= :ephemeral-tail (:zone it)) :ephemeral-tail (or (:zone prev) (:zone it)))
                                  :pinned? (boolean (or (:pinned? prev) (:pinned? it)))
                                  :rules (vec (distinct (concat (:rules prev) (:rules it))))
                                  :tags (into (set (:tags prev)) (:tags it))
                                  :params (merge (:params prev) (:params it)))
                           (cond-> (:ctx.match/score it) (assoc :ctx.match/score (:ctx.match/score it)))))
                (assoc acc e it))))
          {} all-raw))

(defn collapse-rule-projections
  "Fast per-entity collapse of raw rule `:projections` (metadata only — zero `d/pull` or ranking;
   used by `gather-agent-candidates` before single-pass hydration + Upgrade Ladder)."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]
   :cost/ticks 5000000}
  [raw-projections]
  (let [grouped (group-by :entity raw-projections)
        capped (if (> (count grouped) 48)
                 (take 48 (sort-by (fn [[eid ps]]
                                     [(- (reduce max 50 (map #(long (or (:priority %) 50)) ps)))
                                      (- (reduce max 0 (map #(long (or (:tx %) 0)) ps)))
                                      (str eid)])
                                   grouped))
                 grouped)]
    (mapv (fn [[eid ps]]
            (let [ordered (sort-by (fn [p] [(long (or (:stratum p) 50)) (long (or (:priority p) 50)) (str (:rule p))]) ps)
                  wfn (last (keep :fn ordered))]
              (cond-> {:entity eid
                       :params (reduce merge {} (keep :params ordered))
                       :priority (reduce max 50 (map #(long (or (:priority %) 50)) ordered))
                       :parent (last (keep :parent ordered))
                       :zone (or (last (keep :zone ordered)) :workspace)
                       :rules (vec (sort-by str (distinct (keep :rule ordered))))
                       :why (vec (distinct (keep :why ordered)))
                       :tags (into #{} (mapcat #(let [t (:tags %)] (if (coll? t) t (when t [t])))) ordered)
                       :tx (reduce max 0 (map #(long (or (:tx %) 0)) ordered))}
                wfn (assoc :fn wfn))))
          capped)))

(defn- hydrate-merged-candidates [main-db branch-db task-db ag merged demote-eid inc-evals? enrich? allow-rich-ns? max-cands]
  (let [scope-ns (:seon.agent/scope ag)
        extra-ns-set (into (if scope-ns #{scope-ns} #{}) (get-in ag [:seon.agent/context-prefs :extra-namespaces]))
        same-mb? (same-db? main-db branch-db)
        same-tb? (or (nil? task-db) (same-db? task-db branch-db))
        ordered (if (> (count merged) max-cands)
                  (take max-cands
                        (sort-by (fn [[eid item]]
                                   [(if (:pinned? item) 0 1)
                                    (- (long (:priority item 50)))
                                    (- (long (:tx item 0)))
                                    (str eid)])
                                 merged))
                  merged)]
    (vec
     (keep (fn [[eid item]]
             (when (integer? eid)
               (let [t-b (when-not same-tb? (d/pull task-db '[*] eid))
                     b (d/pull branch-db '[*] eid)
                     use-t? (boolean (and (map? t-b) (:seon.task/id t-b)))
                     from-b? (boolean (and (not use-t?) (seq (dissoc b :db/id))))
                     ent (cond use-t? t-b from-b? b same-mb? nil :else (d/pull main-db '[*] eid))
                     src-db (cond use-t? task-db from-b? branch-db :else main-db)]
                 (when (and (map? ent) (seq (dissoc ent :db/id)) (or inc-evals? (not (:seon.agent.eval/id ent))))
                   (let [dem? (= eid demote-eid)
                         rich-ns? (boolean (and allow-rich-ns? (:seon.code.ns/name ent) (not dem?)
                                                (or (extra-ns-set (:seon.code.ns/name ent))
                                                    (contains? (:tags item) :tag/mentioned)
                                                    (get-in item [:params :seon.ui/rich-ns?]))))
                         ep (when enrich?
                              (rlinks/enrich-entity-params src-db ent {:focal? false :rich-ns? rich-ns?
                                                                       :main-db main-db :agent ag :scope scope-ns}))
                         etx (max (long (:tx item 0))
                                  (reduce max 0 (map #(long (or (:tx %) 0)) (d/datoms src-db :eavt eid))))]
                     (cond-> (assoc item :entity eid :data ent :tx etx)
                       (seq ep) (update :params merge ep)
                       dem? (-> (assoc :priority 45 :rank/suppress 0.35)
                                (assoc-in [:params :seon.ui/summary-strip?] true))))))))
           ordered))))

(defn fast-skip-rule-ids
  "Pre-filters `:context` rule IDs whose source tables have 0 hits for `ag` (O(1) indexed probes)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :boolean] [:set :keyword]]
   :cost/ticks 2000000}
  [mdb bdb ag same?]
  (let [aid (:seon.agent/id ag) br (:seon.agent/branch ag :main)
        dh (db-schema bdb) mdh (db-schema mdb)
        green? (boolean (and (dh :seon.test.run/branch) (dh :seon.test.run/pass?)
                             (when-let [e (or (when br (:e (first (d/datoms bdb :avet :seon.test.run/branch br))))
                                              (when same? (:e (first (d/datoms bdb :avet :seon.test.run/branch :main)))))]
                               (true? (:v (first (d/datoms bdb :eavt e :seon.test.run/pass?)))))))
        any-tests? (boolean (and (dh :seon.test/digest) (first (d/datoms bdb :aevt :seon.test/digest))))
        errs? (boolean (or (and (mdh :seon.error/status) (first (d/datoms mdb :avet :seon.error/status :open)))
                           (and (not same?) (dh :seon.error/status) (first (d/datoms bdb :avet :seon.error/status :open)))))
        evs? (boolean (and aid (dh :seon.agent.eval/agent) (first (d/datoms bdb :avet :seon.agent.eval/agent aid))))
        has-scope? (some? (:seon.agent/scope ag))
        has-extra? (boolean (seq (get-in ag [:seon.agent/context-prefs :extra-namespaces])))
        has-task? (boolean (or (:seon.agent/task ag) (seq (:seon.agent/tasks ag))))
        any-docs? (boolean (and (mdh :seon.code.doc/id) (first (d/datoms mdb :aevt :seon.code.doc/id))))]
    (cond-> #{:rule.ctx/system-overview}
      (or green? (not any-tests?)) (conj :rule.ctx/failing-reach-tests)
      (not errs?) (conj :rule.ctx/fault-with-recommended-fix)
      same? (conj :rule.ctx/edited-var-on-branch :rule.ctx/edited-var-callers)
      (not has-task?) (conj :rule.ctx/active-task-and-lineage)
      (and (not has-scope?) (not has-extra?)) (conj :rule.ctx/scope-and-extra-namespaces)
      (and (not has-scope?) (or has-task? (not (or (:seon.agent/router? ag) (= :coordinator (:seon.agent/role ag))))))
      (conj :rule.ctx/scope-task-history-and-fixes)
      (not any-docs?) (conj :rule.ctx/citing-docs)
      (not evs?) (conj :rule.ctx/recent-eval-results :rule.ctx/eval-touched-schemas :rule.ctx/eval-compatible-fns))))

(defn augment-and-hydrate-candidates
  "Single canonical candidate-pool assembler combining `:context` rule projections, 2-read `world`,
   situational grounding/search/tree-overlap, optional `@ctx` snapshot, hydration, enrichment, and `seon.vector` scores."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :map :map [:vector :map] [:maybe :map]] [:vector :map]]
   :cost/ticks 35000000}
  [main-db branch-db task-db ag world ru-projs opts]
  (let [o (if (map? opts) opts {})
        inc-evals? (if (contains? o :include-evals?) (boolean (:include-evals? o)) true)
        inc-ctx? (boolean (:include-ctx-snapshot? o))
        enrich? (if (contains? o :enrich?) (boolean (:enrich? o)) true)
        sem? (if (contains? o :semantic?) (boolean (:semantic? o)) true)
        sit? (if (contains? o :situational?) (boolean (:situational? o)) true)
        aid (:seon.agent/id ag) aeid (:agent-eid world)
        pins (:pinned world) curated (:curated world) extra-nss (:extra-nss world)
        base-ru (into [] (comp (remove #(and (not inc-evals?)
                                             (or (contains? (:tags %) :eval)
                                                 (some #{:rule.ctx/recent-eval-results} (:rules %)))))
                               (map #(dissoc % :candidates :data)))
                      ru-projs)
        sit (if sit? (ground/situational-candidates main-db branch-db task-db ag) {:extra [] :demote-eid nil})
        all-raw (concat
                 base-ru (:extra sit)
                 (for [[e tx] (take 12 (sort-by (comp - val) (:connected world))) :when (not= e aeid)]
                   {:entity e :tx tx :priority (cond (pins e) 88 (curated e) 90 (extra-nss e) 88 :else 60)
                    :pinned? (boolean (pins e)) :zone (if (or (pins e) (curated e) (extra-nss e)) :static-prefix :workspace)
                    :tags (cond-> #{} (pins e) (conj :pinned) (curated e) (conj :tag/curated) (extra-nss e) (conj :extra-ns))
                    :rules [:seed/world]})
                 (for [nsym (get-in ag [:seon.agent/context-prefs :extra-namespaces]) :when (symbol? nsym)
                       :let [e (ground/resolve-lookup-eid main-db branch-db [:seon.code.ns/name nsym])] :when e]
                   {:entity e :priority 88 :zone :static-prefix :tags #{:extra-ns} :rules [:seed/world]})
                 (when-let [sc (:seon.agent/scope ag)]
                   (when-let [e (ground/resolve-lookup-eid main-db branch-db [:seon.code.ns/name sc])]
                     [{:entity e :priority 90 :zone :static-prefix :tags #{:scope-ns} :rules [:seed/scope]}]))
                 (when-let [tid (:seon.agent/task ag)]
                   (when-let [e (or (ground/resolve-lookup-eid task-db branch-db [:seon.task/id tid])
                                    (ground/resolve-lookup-eid main-db branch-db [:seon.task/id tid]))]
                     [{:entity e :priority 96 :zone :static-prefix :tags #{:active-task} :rules [:seed/task]}]))
                 (for [item (:seon.agent/pinned ag)
                       :when (and (vector? item) (= 2 (count item)) (not= :seon.health/summary (first item)))
                       :let [e (ground/resolve-lookup-eid main-db branch-db item)] :when e]
                   {:entity e :priority 92 :zone :static-prefix :pinned? true :tags #{:pinned} :rules [:seed/pinned]}))
        max-cands (long (or (:max-candidates o) 32))
        allow-rich-ns? (if (contains? o :rich-ns?) (boolean (:rich-ns? o)) true)
        hydrated (hydrate-merged-candidates main-db branch-db task-db ag (merge-raw-candidates all-raw) (:demote-eid sit) inc-evals? enrich? allow-rich-ns? max-cands)
        vpins (distinct (concat (:virtual-pins world)
                                (filter #(and (vector? %) (= :seon.health/summary (first %))) (:seon.agent/pinned ag))))
        h-fn (:health-summary-fn o)
        eval-health? (when (and (empty? vpins) (fn? h-fn) aid ((db-schema branch-db) :seon.agent.eval/agent))
                       (boolean (some #(= :seon.health/report
                                          (:seon.agent.eval/result-schema (d/pull branch-db '[:seon.agent.eval/result-schema] (:e %))))
                                      (take 8 (d/datoms branch-db :avet :seon.agent.eval/agent aid)))))
        eff-vpins (if (seq vpins) vpins (when eval-health? [[:seon.health/summary :branch]]))
        h-items (when (and (seq eff-vpins) (fn? h-fn))
                  (for [[_ target] eff-vpins :let [hv (h-fn (if (= :main target) main-db branch-db))] :when (map? hv)]
                    {:entity [:health target] :schema :seon.health/report :data hv
                     :priority 94 :zone :static-prefix :pinned? (boolean (seq vpins)) :tags #{:pinned} :rules [:seed/pinned]}))
        ctx-item (when inc-ctx?
                   [{:entity [:ctx/self (or aid :agent/repl)] :schema :seon.ctx/snapshot
                     :data (with-meta
                             (into {:seon.ctx/tx (:max-tx branch-db)}
                                   (filter (fn [[_ v]] (some? v)))
                                   (select-keys ag [:seon.agent/id :seon.agent/branch :seon.agent/role
                                                    :seon.agent/pinned :seon.agent/memory :seon.agent/context-prefs]))
                             {:seon/schema :seon.ctx/snapshot})
                     :priority 100 :zone :static-prefix :source :ctx :rules [:seed/ctx] :tx (:max-tx branch-db)}])
        combined (vec (concat ctx-item h-items hydrated))]
    (if sem? (ground/attach-semantic-scores main-db branch-db ag combined) combined)))
