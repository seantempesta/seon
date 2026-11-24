(ns seon.ctx.project
  "Pure generic 2-read agent world discovery, monotonic pull-key-set union,
   Datahike entity projection pulling, zero-repetition deltas, and unified candidate hydration."
  (:require [clojure.set :as set]
            [datahike.api :as d]
            [seon.ctx.diff :as diff]
            [seon.ctx.ground :as ground]
            [seon.ctx.touches :as ctouches]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :refer [db-schema live-cid]]
            [seon.schema :as schema]
            [seon.schema.index :as six]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge rev-agent-attrs-cache (store/lru-cache 16))

(defn same-db?
  "True when `a` and `b` represent the same immutable Datahike snapshot."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db]] :boolean] :cost/ticks 50000}
  [a b]
  (boolean (or (= a b) (let [ca (live-cid a) cb (live-cid b)] (and ca cb (= ca cb))))))

(defn- reverse-agent-ref-attrs [db]
  (let [compute (fn []
                  (let [dh-sch (db-schema db)
                        sch-eid (when (contains? dh-sch :seon.schema/key)
                                  (:e (first (d/datoms db :avet :seon.schema/key :seon.agent/entity))))
                        from-db (when (and sch-eid (contains? dh-sch :seon.schema/ref-targets))
                                  (vec (keep #(:v (first (d/datoms db :eavt (:e %) :seon.schema/key)))
                                             (d/datoms db :avet :seon.schema/ref-targets sch-eid))))
                        raw (if (seq from-db) from-db (six/reaching (:entity-index (fit/cached-registry-info db)) :seon.agent/entity))]
                    (->> raw (filter #(= :db.type/ref (get-in dh-sch [% :db/valueType]))) distinct sort vec)))
        sig (diff/code-db-sig db)]
    (if sig (store/lru-get! rev-agent-attrs-cache 16 sig compute) (compute))))

(defn- merge-forward-datoms [main-db branch-db eid]
  (let [m-ds (when main-db (vec (d/datoms main-db :eavt eid)))
        b-ds (when (and branch-db (not (same-db? branch-db main-db))) (vec (d/datoms branch-db :eavt eid)))]
    (if (empty? b-ds)
      (or m-ds [])
      (let [m-by-a (group-by :a m-ds) b-by-a (group-by :a b-ds)
            all-a (sort (set/union (set (keys m-by-a)) (set (keys b-by-a))))
            max-tx (fn [ds] (reduce max 0 (map #(long (or (:tx %) 0)) ds)))]
        (vec (mapcat (fn [a]
                       (let [ms (get m-by-a a) bs (get b-by-a a)]
                         (cond (empty? ms) bs (empty? bs) ms (>= (max-tx bs) (max-tx ms)) bs :else ms)))
                     all-a))))))

(defn merge-pull-patterns
  "Pure, commutative, associative, deterministic union of Datahike `:pull` patterns."
  {:malli/schema [:=> [:cat [:sequential [:sequential :any]]] [:vector :any]] :cost/ticks 500000}
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

(defn fast-skip-rule-ids
  "Pre-filters `:context` rule IDs whose source tables have 0 hits for `ag` (O(1) indexed probes)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :boolean] [:set :keyword]] :cost/ticks 2000000}
  [mdb bdb ag same?]
  (let [aid (:seon.agent/id ag) br (:seon.agent/branch ag :main)
        dh (db-schema bdb) mdh (db-schema mdb)
        tr-pass (fn [db dhs b]
                  (when (and db b (dhs :seon.test.run/branch) (dhs :seon.test.run/pass?))
                    (when-let [e (:e (first (d/datoms db :avet :seon.test.run/branch b)))]
                      (:v (first (d/datoms db :eavt e :seon.test.run/pass?))))))
        br-m (tr-pass mdb mdh br)
        br-b (when-not same? (tr-pass bdb dh br))
        main-m (tr-pass mdb mdh :main)
        green? (and (not (false? br-m)) (not (false? br-b)) (or (true? br-m) (true? br-b) (true? main-m)))
        any-tests? (boolean (or (and (mdh :seon.test/digest) (first (d/datoms mdb :aevt :seon.test/digest)))
                                (and (not same?) (dh :seon.test/digest) (first (d/datoms bdb :aevt :seon.test/digest)))))
        errs? (boolean (or (and (mdh :seon.error/status) (first (d/datoms mdb :avet :seon.error/status :open)))
                           (and (not same?) (dh :seon.error/status) (first (d/datoms bdb :avet :seon.error/status :open)))))
        evs? (boolean (and aid (dh :seon.agent.eval/agent) (first (d/datoms bdb :avet :seon.agent.eval/agent aid))))
        has-scope? (some? (:seon.agent/scope ag))
        has-extra? (boolean (seq (get-in ag [:seon.agent/context-prefs :extra-namespaces])))
        has-task? (boolean (seq (:seon.agent/tasks ag)))
        any-docs? (boolean (and (mdh :seon.code.doc/id) (first (d/datoms mdb :aevt :seon.code.doc/id))))]
    (cond-> #{:rule.ctx/system-overview}
      (or green? (not any-tests?)) (conj :rule.ctx/failing-reach-tests)
      (not errs?) (conj :rule.ctx/fault-with-recommended-fix)
      (and (not errs?) (not evs?)) (conj :rule.ctx/error-neighborhood)
      same? (conj :rule.ctx/edited-var-on-branch :rule.ctx/edited-var-callers :rule.ctx/branch-caused-health)
      (not has-task?) (conj :rule.ctx/active-task-and-lineage)
      (and (not has-scope?) (not has-extra?) (not has-task?)) (conj :rule.ctx/scope-and-extra-namespaces)
      (and (not has-scope?) (or has-task? (not (:seon.agent/router? ag)))) (conj :rule.ctx/scope-task-history-and-fixes)
      (not any-docs?) (conj :rule.ctx/citing-docs)
      (not evs?) (conj :rule.ctx/recent-eval-results)
      (and (not (:seon.agent/target ag)) (not (:seon.agent/forked-from ag))) (conj :rule.ctx/review-target)
      (and (not (:seon.agent/forked-from ag)) (empty? (:seon.agent/example-refs ag)) (empty? (:seon.agent/examples ag)) (not has-task?))
      (conj :rule.ctx/agent-prefix-sessions)
      (and (not evs?) (not errs?) (empty? (:seon.agent/pinned ag)) (not has-task?) (not has-scope?) (empty? (ctouches/live-reads-for bdb aid)))
      (conj :rule.ctx/eval-touched-schemas :rule.ctx/eval-compatible-fns :rule.ctx/novel-working-frontier :rule.ctx/error-schema-docs-and-examples))))

(def ^:private volatile-rev-ref-attrs
  #{:seon.agent/parent-ref :seon.agent/forked-from :seon.agent/target :seon.agent/example-refs
    :seon.agent.call/caller-ref :seon.agent.call/callee-ref :seon.agent.eval/agent-ref :seon.error/agent-ref})

(defn agent-world-datoms
  "Generic 2-read agent discovery: forward `:eavt` merged by latest `:tx` + reverse `:avet` refs targeting `:seon.agent/entity`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :qualified-keyword] :map] :cost/ticks 8000000}
  [main-db branch-db agent-id]
  (let [bdb (or branch-db main-db)
        agent-eid (or (when (contains? (db-schema bdb) :seon.agent/id) (:e (first (d/datoms bdb :avet :seon.agent/id agent-id))))
                      (when (contains? (db-schema main-db) :seon.agent/id) (:e (first (d/datoms main-db :avet :seon.agent/id agent-id)))))]
    (if-not agent-eid
      {:agent-eid nil :forward [] :reverse [] :reverse-attrs [] :reverse-ephemeral {} :reverse-ephemeral-attrs {}
       :connected {} :pinned #{} :curated #{} :extra-nss #{} :virtual-pins []}
      (let [fwd (merge-forward-datoms main-db bdb agent-eid)
            rev-attrs (vec (remove #{:seon.agent/example-refs} (reverse-agent-ref-attrs bdb)))
            same? (same-db? bdb main-db)
            rev-ds (vec (distinct
                         (mapcat (fn [a]
                                   (concat (when (contains? (db-schema main-db) a) (d/datoms main-db :avet a agent-eid))
                                           (when (and (not same?) (contains? (db-schema bdb) a)) (d/datoms bdb :avet a agent-eid))))
                                 rev-attrs)))
            dh-sch (if same? (db-schema main-db) (merge (db-schema main-db) (db-schema bdb)))
            pinned-eids (volatile! #{}) curated-eids (volatile! #{})
            extra-ns-eids (volatile! #{}) vpins (volatile! [])
            fwd-max-tx (reduce max 0 (map #(long (or (:tx %) 0)) fwd))
            conn-map (volatile! {agent-eid fwd-max-tx})
            rev-ephem (volatile! {})
            rev-ephem-a (volatile! {})
            bump! (fn [vm e tx] (when (integer? e) (vswap! vm update e #(max (long (or % 0)) (long (or tx 0))))))]
        (doseq [dt fwd :let [a (:a dt) v (:v dt) tx (:tx dt)]]
          (cond
            (= :db.type/ref (get-in dh-sch [a :db/valueType])) (bump! conn-map v tx)
            (contains? #{:seon.agent/pinned :seon.agent/curated} a)
            (doseq [item (if (coll? v) v [v])]
              (if (and (vector? item) (= 2 (count item)) (= :seon.health/summary (first item)))
                (vswap! vpins conj item)
                (when-let [e (ground/resolve-lookup-eid main-db bdb item)]
                  (bump! conn-map e tx)
                  (if (= :seon.agent/pinned a) (vswap! pinned-eids conj e) (vswap! curated-eids conj e)))))
            (and (= :seon.agent/context-prefs a) (map? v))
            (doseq [nsym (:extra-namespaces v) :when (symbol? nsym)]
              (when-let [e (ground/resolve-lookup-eid main-db bdb [:seon.code.ns/name nsym])]
                (bump! conn-map e tx) (vswap! extra-ns-eids conj e)))))
        (doseq [dt rev-ds :let [a (:a dt) e (:e dt)]]
          (if (contains? volatile-rev-ref-attrs a)
            (do (bump! rev-ephem e (:tx dt)) (when (integer? e) (vswap! rev-ephem-a assoc e a)))
            (bump! conn-map e (:tx dt))))
        {:agent-eid agent-eid :forward fwd :reverse rev-ds :reverse-attrs rev-attrs
         :reverse-ephemeral @rev-ephem :reverse-ephemeral-attrs @rev-ephem-a :connected @conn-map
         :pinned @pinned-eids :curated @curated-eids :extra-nss @extra-ns-eids :virtual-pins @vpins}))))

(defn- group-and-order-projs [raw-projections]
  (mapv (fn [[eid ps]]
          (let [ordered (sort-by (fn [p] [(long (or (:stratum p) 50)) (long (or (:priority p) 50)) (str (:rule p))]) ps)]
            {:entity eid :ordered ordered
             :base {:entity eid
                    :params (reduce merge {} (keep :params ordered))
                    :priority (reduce max 50 (map #(long (or (:priority %) 50)) ordered))
                    :parent (last (keep :parent ordered))
                    :zone (let [zs (keep :zone ordered)]
                            (cond (some #{:agent-prefix} zs) :agent-prefix
                                  (some #{:ephemeral-tail} zs) :ephemeral-tail
                                  (seq zs) (last zs) :else :workspace))
                    :rules (vec (sort-by str (distinct (keep :rule ordered))))
                    :why (vec (distinct (keep :why ordered)))
                    :tags (into #{} (mapcat #(let [t (:tags %)] (if (coll? t) t (when t [t])))) ordered)
                    :tx (reduce max 0 (map #(long (or (:tx %) 0)) ordered))}}))
        (group-by :entity raw-projections)))

(defn collapse-rule-projections
  "Fast per-entity collapse of raw rule `:projections` (metadata only — zero `d/pull` or ranking)."
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]] :cost/ticks 5000000}
  [raw-projections]
  (mapv (fn [{:keys [ordered base]}]
          (if-let [wfn (last (keep :fn ordered))] (assoc base :fn wfn) base))
        (group-and-order-projs raw-projections)))

(defn merge-projections
  "Merges multiple rule projections per entity via `merge-pull-patterns`, pulls Datahike maps, and validates `:params`."
  {:malli/schema [:=> [:cat :map [:vector :map]] [:vector :map]] :cost/ticks 15000000}
  [dbs raw-projections]
  (let [eff-db (or (:branch dbs) (:main dbs)) reg-cache (volatile! nil)]
    (->> (group-and-order-projs raw-projections)
         (mapv (fn [{:keys [entity ordered base]}]
                 (let [merged-pull (merge-pull-patterns (keep :pull ordered))
                       win-fn (or (last (keep :fn ordered)) 'seon.render/render-entity-value)
                       params (validate-projection-params eff-db win-fn (:params base) reg-cache)
                       {:keys [data tx]} (pull-entity-for-projection dbs entity merged-pull)]
                   (assoc base :data data :fn win-fn :pull merged-pull :params params
                          :tx (max (long tx) (long (:tx base 0)))))))
         (sort-by (fn [p] [(- (long (:priority p 50))) (str (:entity p))]))
         vec)))

(defn- eavt-val [bdb mdb same? bdh mdh e a]
  (or (when (and bdb (bdh a)) (:v (first (d/datoms bdb :eavt e a))))
      (when (and (not same?) mdb (mdh a)) (:v (first (d/datoms mdb :eavt e a))))))

(defn- active-rev-ephemeral-items [main-db branch-db rev-ephem rev-attrs aeid zone]
  (when (seq rev-ephem)
    (let [same? (same-db? main-db branch-db)
          mdh (db-schema main-db) bdh (db-schema branch-db)
          open-errs (into #{} (map :e)
                          (concat (when (mdh :seon.error/status) (d/datoms main-db :avet :seon.error/status :open))
                                  (when (and (not same?) (bdh :seon.error/status)) (d/datoms branch-db :avet :seon.error/status :open))))
          pend-calls (into #{} (map :e)
                           (concat (when (mdh :seon.agent.call/status) (d/datoms main-db :avet :seon.agent.call/status :pending))
                                   (when (and (not same?) (bdh :seon.agent.call/status)) (d/datoms branch-db :avet :seon.agent.call/status :pending))))
          gv #(eavt-val branch-db main-db same? bdh mdh %1 %2)]
      (->> rev-ephem (sort-by (comp - val))
           (keep (fn [[e tx]]
                   (when (and (integer? e) (not= e aeid))
                     (let [ra (get rev-attrs e)
                           keep? (case ra
                                   :seon.agent.eval/agent-ref false
                                   :seon.error/agent-ref (contains? open-errs e)
                                   (:seon.agent.call/caller-ref :seon.agent.call/callee-ref)
                                   (or (contains? pend-calls e) (= :resolved (gv e :seon.agent.call/status)))
                                   (:seon.agent/parent-ref :seon.agent/forked-from :seon.agent/target)
                                   (and (some? (gv e :seon.agent/id)) (not (true? (gv e :seon.agent/archived?))))
                                   (and (nil? (gv e :seon.agent.eval/id))
                                        (or (contains? open-errs e)
                                            (contains? pend-calls e)
                                            (= :resolved (gv e :seon.agent.call/status))
                                            (and (some? (gv e :seon.agent/id)) (not (true? (gv e :seon.agent/archived?)))))))]
                       (when keep?
                         {:entity e :tx tx :priority 64 :zone zone :tags #{:reverse-ref} :rules [:seed/world-reverse]})))))
           (take 8) vec))))

(defn- raw-world-items [main-db branch-db task-db ag world prefs derived-t minimal? rev-zone]
  (let [aeid (:agent-eid world)
        {:keys [pinned curated extra-nss]} world
        res-eid #(ground/resolve-lookup-eid main-db branch-db %)
        touch-counts (or (:counts derived-t) {}) touch-energy (or (:energy derived-t) {})
        written-set (or (:written derived-t) #{}) touch-max-tx (or (:max-tx derived-t) {})]
    (concat
     (when-not minimal? (active-rev-ephemeral-items main-db branch-db (:reverse-ephemeral world) (:reverse-ephemeral-attrs world) aeid rev-zone))
     (when-not minimal?
       (for [[e tx] (sort-by (comp - val) (:connected world)) :when (not= e aeid)]
         {:entity e :tx tx :priority (cond (pinned e) 88 (curated e) 90 (extra-nss e) 88 :else 60)
          :pinned? (boolean (pinned e)) :zone (if (or (pinned e) (curated e) (extra-nss e)) :static-prefix :workspace)
          :tags (cond-> #{} (pinned e) (conj :pinned) (curated e) (conj :tag/curated) (extra-nss e) (conj :extra-ns))
          :rules [:seed/world]}))
     (when-not minimal?
       (keep #(when-let [e (and (symbol? %) (res-eid [:seon.code.ns/name %]))]
                {:entity e :priority 88 :zone :static-prefix :tags #{:extra-ns} :rules [:seed/world]})
             (:extra-namespaces prefs)))
     (when-not minimal?
       (when-let [e (some->> (:seon.agent/scope ag) (vector :seon.code.ns/name) res-eid)]
         [{:entity e :priority 90 :zone :static-prefix :tags #{:scope-ns} :rules [:seed/scope]}]))
     (for [[e cnt] (sort-by (comp - val) touch-counts) :when (and (integer? e) (not= e aeid))]
       (let [w? (contains? written-set e) eng (double (get touch-energy e 0.0))]
         (cond-> {:entity e :priority (if w? 82 72) :zone :workspace :touches (long cnt)
                  :tx (long (get touch-max-tx e 0)) :tags (if w? #{:touched :edited} #{:touched})
                  :rules (if w? [:signal/eval-touched :signal/branch-written] [:signal/eval-touched])}
           (pos? eng) (assoc :touch-energy eng)
           w? (assoc :written? true))))
     (for [tid (rdata/agent-task-ids (or task-db branch-db) ag)
           :let [e (or (ground/resolve-lookup-eid task-db branch-db [:seon.task/id tid]) (res-eid [:seon.task/id tid]))]
           :when e]
       {:entity e :priority 96 :zone :static-prefix :tags #{:active-task} :rules [:seed/task]})
     (for [item (:seon.agent/pinned ag)
           :when (and (vector? item) (= 2 (count item)) (not= :seon.health/summary (first item)))
           :let [e (res-eid item)] :when e]
       {:entity e :priority 92 :zone :static-prefix :pinned? true :tags #{:pinned} :rules [:seed/pinned]}))))

(defn augment-and-hydrate-candidates
  "Canonical candidate-pool assembler combining rule projections, 2-read `world`, situational grounding,
   derived touches, `@ctx` snapshot, hydration, enrichment, and `seon.vector` scores."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :seon.host/db :map :map [:vector :map] [:maybe :map]] [:vector :map]]
   :cost/ticks 35000000}
  [main-db branch-db task-db ag world ru-projs opts]
  (let [o (if (map? opts) opts {})
        prefs (if (map? (:seon.agent/context-prefs ag)) (:seon.agent/context-prefs ag) {})
        minimal? (boolean (if (contains? o :minimal?) (:minimal? o) (:minimal? prefs)))
        flag (fn [k d] (cond (contains? o k) (boolean (get o k)) (contains? prefs k) (boolean (get prefs k)) :else d))
        opt-bool (fn [k d] (if (contains? o k) (boolean (get o k)) d))
        inc-evals? (opt-bool :include-evals? true) enrich? (opt-bool :enrich? (not minimal?))
        sem? (flag :semantic? (not minimal?)) sit? (flag :situational? (not minimal?))
        aid (:seon.agent/id ag)
        rev-zone (if (= :wire (or (:target o) :wire)) :ephemeral-tail :workspace)
        derived-t (or (:derived-touches o) (when (opt-bool :use-live-touches? (not minimal?)) (ctouches/derived-agent-touches main-db branch-db aid)))
        touch-counts (or (:counts derived-t) {}) touch-energy (or (:energy derived-t) {})
        frontier-scores (or (:novel-frontier derived-t) {}) written-set (or (:written derived-t) #{})
        base-ru (into [] (comp (remove #(and (not inc-evals?)
                                             (or (contains? (:tags %) :eval) (some #{:rule.ctx/recent-eval-results} (:rules %)))))
                               (map #(dissoc % :candidates :data)))
                      ru-projs)
        sit (if sit? (ground/situational-candidates main-db branch-db task-db ag) {:extra [] :demote-eid nil})
        zf (:zone-filter o)
        raw-w (if (= :ephemeral-tail zf) [] (raw-world-items main-db branch-db task-db ag world prefs derived-t minimal? rev-zone))
        all-raw (let [r (concat base-ru (:extra sit) raw-w)] (if zf (filter #(= zf (:zone %)) r) r))
        hydrated (ground/hydrate-merged-candidates main-db branch-db task-db ag (ground/merge-raw-candidates all-raw)
                                                   (:demote-eid sit) inc-evals? enrich? (flag :rich-ns? (not minimal?)) (:max-candidates o))
        vpins (distinct (concat (:virtual-pins world)
                                (filter #(and (vector? %) (= :seon.health/summary (first %))) (:seon.agent/pinned ag))))
        h-fn (:health-summary-fn o)
        eval-health? (when (and (not minimal?) (empty? vpins) (fn? h-fn) aid ((db-schema branch-db) :seon.agent.eval/agent))
                       (boolean (some #(= :seon.health/report (:v (first (d/datoms branch-db :eavt (:e %) :seon.agent.eval/result-schema))))
                                      (take 8 (d/datoms branch-db :avet :seon.agent.eval/agent aid)))))
        h-items (when (fn? h-fn)
                  (for [[_ target] (or (seq vpins) (when eval-health? [[:seon.health/summary :branch]]))
                        :let [hv (h-fn (if (= :main target) main-db branch-db))] :when (map? hv)]
                    {:entity [:health target] :schema :seon.health/report :data hv
                     :priority 94 :zone :static-prefix :pinned? (boolean (seq vpins)) :tags #{:pinned} :rules [:seed/pinned]}))
        ctx-item (when (:include-ctx-snapshot? o)
                   [{:entity [:ctx/self (or aid :agent/repl)] :schema :seon.ctx/snapshot
                     :data (with-meta (into {:seon.ctx/tx (:max-tx branch-db)} (filter (comp some? val))
                                            (select-keys ag [:seon.agent/id :seon.agent/branch :seon.agent/router? :seon.agent/pinned :seon.agent/summary :seon.agent/context-prefs]))
                                      {:seon/schema :seon.ctx/snapshot})
                     :priority 100 :zone :static-prefix :source :ctx :rules [:seed/ctx] :tx (:max-tx branch-db)}])
        combined (mapv (fn [c]
                         (let [e (:entity c) tc (long (get touch-counts e 0)) eng (double (get touch-energy e 0.0))
                               fs (double (get frontier-scores e 0.0)) w? (contains? written-set e)]
                           (cond-> c
                             (pos? tc) (assoc :touches (max tc (long (:touches c 0))))
                             (pos? eng) (assoc :touch-energy (max eng (double (or (:touch-energy c) 0.0))))
                             (pos? fs) (assoc :frontier-score (max fs (double (or (:frontier-score c) 0.0))))
                             w? (assoc :written? true))))
                       (concat ctx-item h-items hydrated))]
    (if sem? (ground/attach-semantic-scores main-db branch-db ag combined) combined)))

(defn fix-matches-scope?
  "Delegates to `seon.ctx.ground/fix-matches-scope?`."
  {:malli/schema [:=> [:cat :any :any :any :any [:? :any]] :boolean] :cost/ticks 100000}
  ([scope error-class change-summary regression-test]
   (ground/fix-matches-scope? scope error-class change-summary regression-test))
  ([scope error-class change-summary regression-test ctx-text]
   (ground/fix-matches-scope? scope error-class change-summary regression-test ctx-text)))
