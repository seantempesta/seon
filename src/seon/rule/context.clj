(ns seon.rule.context
  "Rule-driven context & view-tree derivation over Datahike (`seon.rules` + `seon.rules.effects`):
   - One rule engine drives BOTH `:target :html` (Living Canvas/Workspace/Debug semantic trees)
     and `:target :wire` (KV-cache-safe `:static-prefix` + `:ephemeral-tail`).
   - Rules assert fine-grained projection facts (`:view/show?`, `:view/fn`, `:view/layout`,
     `:view/parent`, `:view/order`, `:view/zone`, `:view/tag`, `:view.param/*`) so any flat bag
     of Datahike datoms self-organizes into a cycle-safe, tag-composed render tree."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.lineage :as lineage]
            [seon.rule :as rules]
            [seon.rule.effects :as effects]
            [seon.store :as store]
            [seon.rule.seed :as rseed]
            [seon.schema :as schema]
            [seon.schema.index :as six]))

(defonce ^:private ^:seon.flow/edge seed-rules-cache (atom nil))

(defn seed-rules-fallback
  "Loads declarative starter rules from `resources/seon/rules.edn` once when an ephemeral DB holds 0 `:seon.rule/*` entities."
  {:malli/schema [:=> [:cat] [:vector :map]], :cost/ticks 2000000, :seon.flow/edge true}
  []
  (or @seed-rules-cache
      (let [f (io/file store/checkout-root "resources/seon/rules.edn")
          loaded (if (.isFile f)
                   (try (let [v (edn/read-string (slurp f))] (if (vector? v) v []))
                        (catch Throwable _ []))
                   (rseed/starter-rules))]
      (when (seq loaded) (reset! seed-rules-cache loaded))
      loaded)))

(defn- sanitize-id [s]
  (-> (str s)
      (str/replace #"[^A-Za-z0-9_-]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- safe-read-edn [^String s]
  (when (and (string? s) (not (str/blank? s)) (< (count s) 12000))
    (try (edn/read-string s) (catch Throwable _ nil))))

(defn- lookup-ref-kind
([attr] (lookup-ref-kind nil attr))
([db attr]
 (if (= attr :seon.health/summary)
   :seon.health/report
   (get-in (schema/entity-index db) [:identity-schemas attr]))))

(defn- resolve-lookup-ref [db item]
  (when (and (vector? item) (= 2 (count item)) (qualified-keyword? (first item)))
    (let [[attr val] item
          kind (lookup-ref-kind db attr)]
      (cond
        (= attr :seon.health/summary)
        {:eid [:health val] :kind kind :ref item :health-val val}

        kind
        (when-let [e (:e (first (d/datoms db :avet attr val)))]
          {:eid e :kind kind :attr attr :val val :ref item})

        :else nil))))

(defn- seed-pinned-and-curated [wm main-db code-db task-db agent-ent]
(let [resolve-any (fn [ref]
                    (or (resolve-lookup-ref code-db ref)
                        (when (and main-db (not= main-db code-db))
                          (resolve-lookup-ref main-db ref))))
      tid (:seon.agent/task agent-ent)
      wm1 (reduce (fn [w p]
                    (if-let [{:keys [eid kind attr val ref health-val]} (resolve-any p)]
                      (cond-> (rules/add-facts w
                                               [[eid :ctx/kind kind]
                                                [eid :ctx/source :pinned]
                                                [eid :ctx/score 0.96]
                                                [eid :ctx/ref ref]
                                                [eid :view/tag :tag/pinned]
                                                [eid :ctx/why "Pinned on agent"]]
                                               {:rule :seed/pinned :stratum 10})
                        attr (rules/add-fact [eid attr val] {:rule :seed/pinned :stratum 10})
                        health-val (rules/add-fact [eid :ctx/health-target health-val] {:rule :seed/pinned :stratum 10}))
                      w))
                  wm
                  (:seon.agent/pinned agent-ent))
      task-curated (when (and tid (contains? (:schema task-db) :seon.task/curated))
                     (:seon.task/curated (d/pull task-db '[:seon.task/curated] [:seon.task/id tid])))]
  (reduce (fn [w c]
            (if-let [{:keys [eid kind attr val ref]} (resolve-any c)]
              (cond-> (rules/add-facts w
                                       [[eid :ctx/kind kind]
                                        [eid :rel/curated true]
                                        [eid :ctx/ref ref]
                                        [eid :view/tag :tag/curated]]
                                       {:rule :seed/curated :stratum 10})
                attr (rules/add-fact [eid attr val] {:rule :seed/curated :stratum 10}))
              w))
          wm1
          (concat (:seon.agent/curated agent-ent) task-curated))))

(defn- seed-eval-results [wm main-db branch-db evals include-evals?]
(if-not include-evals?
  {:wm wm :eval-vals {}}
  (let [eval-vals (volatile! {})
        ix (schema/entity-index (or branch-db main-db))
        resolve-any (fn [ref]
                      (or (resolve-lookup-ref branch-db ref)
                          (when (and main-db (not= main-db branch-db))
                            (resolve-lookup-ref main-db ref))))
        infer-map-schema (fn [m fallback]
                           (or fallback
                               (let [ms (six/most-specific ix m)]
                                 (when (qualified-keyword? ms) ms))))
        seed-one (fn [w item rschema handle sub-idx]
                   (cond
                     (and (vector? item) (= 2 (count item)) (qualified-keyword? (first item)))
                     (if-let [{:keys [eid kind attr val ref]} (resolve-any item)]
                       (cond-> (rules/add-facts w
                                                [[eid :ctx/kind kind]
                                                 [eid :ctx/source :eval]
                                                 [eid :ctx/score 0.74]
                                                 [eid :ctx/handle handle]
                                                 [eid :ctx/ref ref]
                                                 [eid :view/tag :tag/eval-result]
                                                 [eid :ctx/why (str "Returned by eval " handle)]]
                                                {:rule :seed/eval :stratum 10})
                         attr (rules/add-fact [eid attr val] {:rule :seed/eval :stratum 10}))
                       w)

                     (map? item)
                     (if-let [sk (infer-map-schema item rschema)]
                       (let [ek (if sub-idx [:eval handle sub-idx] [:eval (or handle (hash item))])]
                         (vswap! eval-vals assoc ek (with-meta item {:seon/schema sk}))
                         (rules/add-facts w
                                          [[ek :ctx/kind sk]
                                           [ek :ctx/source :eval]
                                           [ek :ctx/score 0.74]
                                           [ek :ctx/handle handle]
                                           [ek :view/tag :tag/eval-result]
                                           [ek :ctx/why (str "Returned by eval " handle)]]
                                          {:rule :seed/eval :stratum 10}))
                       w)

                     :else w))
        wm' (reduce (fn [w ev]
                      (let [rschema (:seon.agent.eval/result-schema ev)
                            handle (:seon.agent.eval/handle ev)
                            parsed (safe-read-edn (:seon.agent.eval/value ev))]
                        (cond
                          (nil? parsed) w
                          (or (map? parsed)
                              (and (vector? parsed) (= 2 (count parsed)) (qualified-keyword? (first parsed))))
                          (seed-one w parsed rschema handle nil)

                          (and (sequential? parsed) (seq parsed))
                          (reduce (fn [w2 [i it]] (seed-one w2 it nil handle i))
                                  w (map-indexed vector (take 4 parsed)))

                          :else w)))
                    wm
                    (reverse (take-last 16 evals)))]
    {:wm wm' :eval-vals @eval-vals})))

(defn- build-seed-wm [main-db branch-db code-db task-db agent-ent evals {:keys [include-evals? include-children?]}]
  (let [aid (:seon.agent/id agent-ent)
        tid (:seon.agent/task agent-ent)
        scope (:seon.agent/scope agent-ent)
        lin (lineage/agent-task-lineage task-db branch-db agent-ent)
        has-peers? (boolean
                    (or (and aid (contains? (:schema main-db) :seon.agent.call/caller)
                             (first (d/datoms main-db :avet :seon.agent.call/caller aid)))
                        (and tid (contains? (:schema task-db) :seon.task/needs)
                             (seq (:seon.task/needs (d/pull task-db '[:seon.task/needs] [:seon.task/id tid]))))))
        boot-tx (effects/baseline-import-tx code-db)
        base-facts (cond-> [[:env :env/boot-tx boot-tx]
                            [:env :env/max-tx (long (or (:max-tx branch-db) 0))]]
                     aid (conj [:viewer :ctx/viewer aid])
                     tid (conj [:viewer :ctx/task-id tid])
                     scope (conj [:viewer :ctx/scope scope])
                     lin (conj [:viewer :ctx/has-lineage? true])
                     include-children? (conj [:viewer :ctx/include-children? true])
                     has-peers? (conj [:viewer :ctx/has-tree-peers? true]))
        wm0 (rules/add-facts (rules/empty-wm) base-facts {:rule :seed :stratum 0})
        wm1 (reduce (fn [w nsym] (rules/add-fact w [:viewer :ctx/extra-ns nsym] {:rule :seed :stratum 0}))
                    wm0 (get-in agent-ent [:seon.agent/context-prefs :extra-namespaces]))
        wm2 (seed-pinned-and-curated wm1 main-db code-db task-db agent-ent)
        {:keys [wm eval-vals]} (seed-eval-results wm2 main-db branch-db evals include-evals?)]
    {:wm wm :lineage lin :eval-vals eval-vals}))

(defn- extract-view-params [wm e]
  (reduce-kv
   (fn [acc a _]
     (if (= "view.param" (namespace a))
       (let [k (keyword (name a))]
         (if (= k :badge)
           (assoc acc :badges (vec (sort (rules/wm-vals wm e a))))
           (assoc acc k (rules/wm-scalar wm e a))))
       acc))
   {}
   (get (:eav wm) e)))

(defn- entity-rules-trace [wm e]
  (let [prov (:provenance wm)
        e-map (get (:eav wm) e)]
    (->> (for [[a vs] e-map, v vs, p (get prov [e a v])] (:rule p))
         (remove nil?) distinct (sort-by str) vec)))

(defn- materialize-tile-val [main-db branch-db code-db task-db agent-ent health-fn lin eval-vals wm e kind]
  (cond
    (= e :ctx/self)
    {:id (str "tile-ctx-" (sanitize-id (:seon.agent/id agent-ent)))
     :val (with-meta (assoc (select-keys agent-ent
                                         [:seon.agent/id :seon.agent/branch :seon.agent/role
                                          :seon.agent/scope :seon.agent/goal :seon.agent/render-fn
                                          :seon.agent/pinned :seon.agent/stage :seon.agent/canvas
                                          :seon.agent/memory :seon.agent/context-prefs :seon.agent/curated])
                            :seon.ctx/tx (:max-tx branch-db))
                     {:seon/schema :seon.ctx/snapshot})}

    (= e :ctx/lineage)
    (when lin
      {:id (str "tile-lineage-" (sanitize-id (or (:seon.agent/id agent-ent) :agent)))
       :val lin})

    (= kind :seon.health/report)
    (let [ht (or (rules/wm-scalar wm e :ctx/health-target) :main)
          v (or (get eval-vals e) (when health-fn (health-fn (if (= :main ht) main-db branch-db))))]
      (when v
        {:id (str "tile-health-" (sanitize-id (if (vector? e) (second e) ht)))
         :val v}))

    (contains? eval-vals e)
    {:id (str "tile-eval-" (sanitize-id (if (vector? e) (second e) e)))
     :val (get eval-vals e)}

    (integer? e)
    (let [pref-db (case kind :seon.task/entity task-db :seon.agent/entity main-db code-db)
        ent0 (d/pull pref-db '[*] e)
        use-main? (and (empty? (dissoc ent0 :db/id)) (not= pref-db main-db))
        ent (if use-main? (d/pull main-db '[*] e) ent0)
        eff-db (if use-main? main-db pref-db)
        id-attr (get-in (schema/entity-index eff-db) [:schema-identities kind])
        ident (or (when id-attr (get ent id-attr))
                  (:seon.code.ns/name ent) (:seon.code.var/name ent) (:seon.schema/key ent)
                  (:seon.task/id ent) (:seon.agent/id ent) (:seon.code.doc/id ent) (:seon.rule/id ent) e)]
    (when (seq (dissoc ent :db/id))
      {:id (str "tile-" (name (or (namespace kind) "ent")) "-" (sanitize-id ident))
       :ref (first (rules/wm-vals wm e :ctx/ref))
       :val (with-meta ent {:seon/schema kind})}))

    :else nil))

(defn assemble-view-tree
  "Turns a flat vector of rule-projected tiles (each carrying `:eid`, `:parent`, `:zone`, `:priority`)
   into a deterministic, cycle-safe tree with `:children` nested under visible `:parent` nodes!"
  {:malli/schema [:=> [:cat [:vector :map]] [:vector :map]]
   :cost/ticks 2000000}
  [tiles]
  (let [by-eid (into {} (map (juxt :eid identity)) tiles)
        ;; Cycle-safe parent resolution: walk up to 8 hops; if cycle or hidden parent, attach at root!
        safe-parent (fn [t]
                      (let [p (:parent t)]
                        (when (and p (contains? by-eid p) (not= p (:eid t)))
                          (loop [cur p seen #{(:eid t)} depth 0]
                            (cond
                              (nil? cur) p
                              (contains? seen cur) nil
                              (> depth 8) nil
                              :else (recur (:parent (get by-eid cur)) (conj seen cur) (inc depth)))))))
        children-by-p (reduce (fn [acc t]
                                (if-let [p (safe-parent t)]
                                  (update acc p (fnil conj []) t)
                                  acc))
                              {}
                              tiles)
        roots (filterv #(nil? (safe-parent %)) tiles)
        attach (fn attach [node]
                 (let [kids (get children-by-p (:eid node))]
                   (if (seq kids)
                     (assoc node :children (mapv attach kids))
                     node)))]
    (mapv attach roots)))

(defn- project-tiles [main-db branch-db code-db eff-task-db agent-ent health-fn lineage eval-vals fwm]
  (let [view-eids (rules/wm-entities-with fwm :view/fn)
        raw (keep (fn [e]
                    (let [show? (rules/wm-scalar fwm e :view/show?)
                          suppress? (rules/wm-scalar fwm e :view/suppress?)]
                      (when (and (not (false? show?)) (not suppress?))
                        (let [kind (rules/wm-scalar fwm e :ctx/kind)
                              mat (materialize-tile-val main-db branch-db code-db eff-task-db agent-ent health-fn lineage eval-vals fwm e kind)]
                          (when mat
                            (let [params (extract-view-params fwm e)
                                  tags (into #{} (rules/wm-vals fwm e :view/tag))
                                  why (vec (sort (rules/wm-vals fwm e :ctx/why)))
                                  pri (long (or (rules/wm-scalar fwm e :view/priority) 50))
                                  ord (or (rules/wm-scalar fwm e :view/order) [(- pri) (str (:id mat))])]
                              (assoc mat
                                     :eid e
                                     :schema kind
                                     :source (or (rules/wm-scalar fwm e :ctx/source) :rule)
                                     :handle (rules/wm-scalar fwm e :ctx/handle)
                                     :view-fn (rules/wm-scalar fwm e :view/fn)
                                     :zone (or (rules/wm-scalar fwm e :view/zone) :static-prefix)
                                     :parent (rules/wm-scalar fwm e :view/parent)
                                     :layout (or (rules/wm-scalar fwm e :view/layout) :expanded-card)
                                     :priority pri
                                     :order ord
                                     :tags tags
                                     :params params
                                     :badges (vec (distinct (concat (:badges params) (map name (sort tags)))))
                                     :why why
                                     :fired-rules (entity-rules-trace fwm e))))))))
                  view-eids)]
    (->> raw
         (sort-by (fn [t] [(- (long (:priority t 50))) (str (:id t))]))
         (reduce (fn [[seen acc] t]
                   (if (contains? seen (:id t)) [seen acc] [(conj seen (:id t)) (conj acc t)]))
                 [#{} []])
         second)))

(defn derive-bag
  "Derives a self-organized view tree from ANY flat bag of Datahike entity IDs or lookup refs (`eids-or-refs`),
   applying the exact same `:seon.rule/*` projection rules (`:view/show?`, `:view/parent`, `:view/tag`, `:view/layout`)!"
  {:malli/schema [:=> [:cat :seon.host/db :any :map] :map]
   :cost/ticks 25000000}
  [db eids-or-refs {:keys [target rules-override] :or {target :html}}]
  (let [t0 (System/nanoTime)
        dbs {:main db :branch db :code db :task db}
        active-rules (or rules-override (rules/load-rules db (seed-rules-fallback)))
        boot-tx (effects/baseline-import-tx db)
        wm0 (rules/add-facts (rules/empty-wm)
                             [[:env :env/boot-tx boot-tx]
                              [:env :env/max-tx (long (or (:max-tx db) 0))]]
                             {:rule :seed/bag :stratum 0})
        wm1 (reduce (fn [w item]
                      (let [eid (if (vector? item) (:e (first (d/datoms db :avet (first item) (second item)))) item)]
                        (if-not (integer? eid)
                          w
                          (let [ent (d/pull db '[*] eid)
                                [kind attr val] (let [ix (schema/entity-index db)
                                                    ms (six/most-specific ix (dissoc ent :db/id))
                                                    k (if (qualified-keyword? ms) ms (:schema (first (six/facets-of ix (dissoc ent :db/id)))))
                                                    ida (when k (get-in ix [:schema-identities k]))]
                                                (when (and k ida (some? (get ent ida)))
                                                  [k ida (get ent ida)]))
                                max-tx (reduce max 0 (map :tx (d/datoms db :eavt eid)))]
                            (if-not kind
                              w
                              (rules/add-facts w
                                               [[eid :ctx/kind kind]
                                                [eid attr val]
                                                [eid :ctx/source :query]
                                                [eid :ctx/score 0.85]
                                                [eid :ctx/modified-tx max-tx]
                                                [eid :ctx/why "Matched in query bag"]]
                                               {:rule :seed/bag :stratum 10}))))))
                    wm0 eids-or-refs)
        final-run (loop [cur-wm wm1, round 0]
                    (let [res (rules/fire-rules cur-wm dbs active-rules target)
                          wm-fx (effects/execute-boundary-effects (:wm res) dbs)]
                      (if (and (< round 2) (not= (:facts wm-fx) (:facts (:wm res))))
                        (recur wm-fx (inc round))
                        res)))
        fwm (:wm final-run)
        sorted (project-tiles db db db db {} nil nil {} fwm)
        ms (/ (- (System/nanoTime) t0) 1e6)]
    {:tree (assemble-view-tree sorted)
     :all-tiles sorted
     :wm fwm
     :ms (/ (Math/round (* ms 100.0)) 100.0)}))

(defonce ^:private ^:seon.flow/edge derive-ctx-cache
  (store/lru-cache 32))

(defn- compute-derive-context
  [main-db branch-db code-db eff-task-db agent-ent evals health-fn
   {:keys [include-evals? include-children? target rules-override]}]
  (let [t0 (System/nanoTime)
        dbs {:main main-db :branch branch-db :code code-db :task eff-task-db}
        active-rules (or rules-override
        (let [fb (seed-rules-fallback)
              m-rules (rules/load-rules main-db fb)]
          (if (or (= main-db branch-db)
                  (= (rules/rules-db-sig main-db) (rules/rules-db-sig branch-db)))
            m-rules
            (rules/load-rules branch-db m-rules))))
        {:keys [wm lineage eval-vals]} (build-seed-wm main-db branch-db code-db eff-task-db agent-ent evals
                                                      {:include-evals? include-evals?
                                                       :include-children? include-children?})
        final-run (loop [cur-wm wm, round 0]
                    (let [res (rules/fire-rules cur-wm dbs active-rules target)
                          wm-fx (effects/execute-boundary-effects (:wm res) dbs)]
                      (if (and (< round 2) (not= (:facts wm-fx) (:facts (:wm res))))
                        (recur wm-fx (inc round))
                        res)))
        fwm (:wm final-run)
        sorted (project-tiles main-db branch-db code-db eff-task-db agent-ent health-fn lineage eval-vals fwm)
        prefix-tiles (filterv #(not= :ephemeral-tail (:zone %)) sorted)
        tail-tiles (filterv #(= :ephemeral-tail (:zone %)) sorted)
        run-summary (->> (vals (:runs final-run))
                         (sort-by (juxt :stratum #(str (:rule-id %))))
                         (mapv #(select-keys % [:rule-id :stratum :category :fired :asserted-count :reads-count :ms])))
        ms (/ (- (System/nanoTime) t0) 1e6)]
    {:tiles (assemble-view-tree prefix-tiles)
     :tail-tiles (assemble-view-tree tail-tiles)
     :all-tiles sorted
     :wm fwm
     :runs run-summary
     :rules-count (count active-rules)
     :facts-count (count (:facts fwm))
     :ms (/ (Math/round (* ms 100.0)) 100.0)}))

(defn derive-context
"Runs the deterministic rule engine + cached boundary effects for `agent-ent` and target (`:wire` or `:html`).
 Returns `{:tiles [...] :tail-tiles [...] :tree [...] :wm wm :runs [...] :rules-count n :ms d}`."
{:malli/schema [:=> [:cat :seon.host/db :seon.host/db :map :any :any :map] :map]
 :cost/ticks 25000000}
[main-db branch-db agent-ent evals health-fn
 {:keys [include-evals? include-children? task-db target rules-override]
  :or {include-evals? true include-children? true target :wire}
  :as opts}]
(let [read-main? (or (= :main (:seon.agent/read-branch agent-ent))
                     (:seon.agent/router? agent-ent)
                     (= :coordinator (:seon.agent/role agent-ent)))
      code-db (if read-main? main-db branch-db)
      tid (:seon.agent/task agent-ent)
      eff-task-db (or task-db
                      (when (and tid (first (d/datoms branch-db :avet :seon.task/id tid)))
                        branch-db)
                      main-db)
      eff-opts (assoc opts :include-evals? include-evals? :include-children? include-children? :target target)
      cacheable? (and (nil? rules-override)
                      (not (some :seon.agent.eval/result-schema evals))
                      (not (instance? datahike.db.AsOfDB main-db))
                      (not (instance? datahike.db.AsOfDB branch-db))
                      (not (instance? datahike.db.HistoricalDB branch-db)))
      mcid (when cacheable? (try (d/commit-id main-db) (catch Throwable _ nil)))
      bcid (when cacheable? (try (d/commit-id branch-db) (catch Throwable _ nil)))
      tcid (when cacheable? (try (d/commit-id eff-task-db) (catch Throwable _ nil)))
      ck (when (and mcid bcid tcid)
           [(get-in main-db [:config :store :id])
            mcid (:max-tx main-db)
            bcid (:max-tx branch-db)
            tcid (:max-tx eff-task-db)
            (select-keys agent-ent [:seon.agent/id :seon.agent/role :seon.agent/scope
                                    :seon.agent/task :seon.agent/goal :seon.agent/pinned
                                    :seon.agent/curated :seon.agent/context-prefs
                                    :seon.agent/read-branch :seon.agent/router?])
            target (boolean include-children?) health-fn])]
  (if ck
    (store/lru-get! derive-ctx-cache 32 ck
      #(compute-derive-context main-db branch-db code-db eff-task-db agent-ent evals health-fn eff-opts))
    (compute-derive-context main-db branch-db code-db eff-task-db agent-ent evals health-fn eff-opts))))

(defn discover-tiles
  "Single-source rule-driven tile discovery powering Workspace, Debug, and LLM Wire context."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :seon.host/db :map :any :any] [:vector :map]]
                  [:=> [:cat :seon.host/db :seon.host/db :map :any :any :map] [:vector :map]]]
   :cost/ticks 25000000}
  ([main-db branch-db agent-ent evals health-fn]
   (discover-tiles main-db branch-db agent-ent evals health-fn {}))
  ([main-db branch-db agent-ent evals health-fn opts]
   (:tiles (derive-context main-db branch-db agent-ent evals health-fn opts))))

(defn explain-context
  "Returns a compact human/REPL provenance summary explaining why every tile appeared, its layout,
   tags, parent, zone, params, and which rules fired for `agent-id`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :map]
   :cost/ticks 25000000}
  [main-db branch-db agent-id opts]
  (let [ag (or (d/pull branch-db '[*] [:seon.agent/id agent-id])
               (d/pull main-db '[*] [:seon.agent/id agent-id])
               {:seon.agent/id agent-id :seon.agent/role :worker})
        res (derive-context main-db branch-db ag [] nil (merge {:include-evals? false :include-children? true} opts))]
    {:agent agent-id
     :target (:target opts :wire)
     :ms (:ms res)
     :rules-count (:rules-count res)
     :facts-count (:facts-count res)
     :items (mapv #(select-keys % [:id :schema :source :zone :parent :layout :priority :tags :params :why :fired-rules])
                  (:all-tiles res))
     :fired-rules (filterv #(pos? (:fired %)) (:runs res))}))

(defn replay-agent-turns
  "Steps through `agent-id`'s historical eval turns via Datahike `d/as-of` under `rules-override`
   (defaulting to current `:main` rules), showing how derived context sharpens turn by turn."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword [:maybe [:vector :map]]] [:vector :map]]
   :cost/ticks 50000000}
  [main-db branch-db agent-id rules-override]
  (let [eff-rules (or rules-override (rules/load-rules main-db (seed-rules-fallback)))
        eval-txs (->> (d/datoms branch-db :avet :seon.agent.eval/agent agent-id)
                      (map (fn [dt] {:tx (:tx dt)
                                     :turn (:seon.agent.eval/turn (d/pull branch-db '[:seon.agent.eval/turn] (:e dt)))}))
                      (group-by :turn)
                      (map (fn [[turn xs]] {:turn (or turn 0) :tx (apply max (map :tx xs))}))
                      (sort-by :turn)
                      (take 8)
                      vec)]
    (mapv (fn [{:keys [turn tx]}]
            (let [b-at (d/as-of branch-db tx)
                  m-at (d/as-of main-db tx)
                  ag (merge {:seon.agent/id agent-id}
                            (d/pull m-at '[*] [:seon.agent/id agent-id])
                            (d/pull b-at '[*] [:seon.agent/id agent-id]))
                  res (derive-context m-at b-at ag [] nil
                                      {:include-evals? false :include-children? false
                                       :rules-override eff-rules :target :wire})]
              {:turn turn :tx tx :ms (:ms res)
               :items (mapv #(select-keys % [:id :schema :zone :layout :priority :tags :why]) (:all-tiles res))}))
          eval-txs)))

(defn install-rule-helpers!
  "Installs `user/rules`, `user/put-rule!`, `user/toggle-rule!`, `user/explain-context`,
   `user/diff-rules`, and `user/curate!` into namespace `u`."
  {:seon.flow/edge true, :malli/schema [:=> [:cat :any :any] :any]}
  [u ctx-fn]
  (when (and (instance? clojure.lang.Namespace u) (fn? ctx-fn))
    (intern u 'rules
            (fn
              ([] (let [{:keys [conn]} (ctx-fn)] (rules/load-rules @conn (seed-rules-fallback))))
              ([cat] (filterv #(= cat (:seon.rule/category %))
                              (let [{:keys [conn]} (ctx-fn)] (rules/load-rules @conn (seed-rules-fallback)))))))
    (intern u 'put-rule!
            (fn [rule-map]
              (let [{:keys [conn]} (ctx-fn)
                    r (merge {:seon.rule/enabled? true :seon.rule/stratum 50 :seon.rule/target :any :seon.rule/category :present}
                             rule-map)]
                (when-not (:seon.rule/id r)
                  (throw (ex-info "put-rule! requires :seon.rule/id" {:rule r})))
                (d/transact conn {:tx-data [r]})
                {:saved (:seon.rule/id r) :stratum (:seon.rule/stratum r) :enabled? (:seon.rule/enabled? r)})))
    (intern u 'toggle-rule!
            (fn [rid enabled?]
              (let [{:keys [conn]} (ctx-fn)]
                (d/transact conn {:tx-data [{:seon.rule/id rid :seon.rule/enabled? (boolean enabled?)}]})
                {:rule rid :enabled? (boolean enabled?)})))
    (intern u 'explain-context
            (fn
              ([] ((resolve 'user/explain-context) nil {}))
              ([aid] ((resolve 'user/explain-context) aid {}))
              ([aid opts]
               (let [{:keys [conn branch agent-id]} (ctx-fn)
                     bdb @conn
                     mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))]
                 (explain-context mdb bdb (or aid agent-id :agent/root) opts)))))
    (intern u 'curate!
            (fn [refs]
              (let [{:keys [conn agent-id]} (ctx-fn)
                    db @conn
                    rs (vec (if (and (vector? refs) (= 2 (count refs)) (qualified-keyword? (first refs))) [refs] refs))
                    ag (d/pull db '[:seon.agent/curated :seon.agent/task] [:seon.agent/id agent-id])
                    merged (vec (distinct (concat (:seon.agent/curated ag) rs)))
                    tid (:seon.agent/task ag)
                    tx (cond-> [{:seon.agent/id agent-id :seon.agent/curated merged}]
                         (and tid (first (d/datoms db :avet :seon.task/id tid)))
                         (conj {:seon.task/id tid
                                :seon.task/curated (vec (distinct (concat (:seon.task/curated (d/pull db '[:seon.task/curated] [:seon.task/id tid])) rs)))}))]
                (d/transact conn {:tx-data tx})
                {:curated merged :task tid})))
    (intern u 'diff-rules
            (fn [aid rules-a rules-b]
              (let [{:keys [conn branch agent-id]} (ctx-fn)
                    bdb @conn
                    mdb (if (= branch :main) bdb (or (try (store/head conn :main) (catch Throwable _ nil)) bdb))
                    eff-aid (or aid agent-id :agent/root)
                    ag (or (d/pull bdb '[*] [:seon.agent/id eff-aid]) (d/pull mdb '[*] [:seon.agent/id eff-aid]))
                    ra (derive-context mdb bdb ag [] nil {:rules-override rules-a :target :wire})
                    rb (derive-context mdb bdb ag [] nil {:rules-override rules-b :target :wire})]
                (assoc (rules/diff-sessions ra rb)
                       :items-before (mapv (juxt :id :layout :zone :priority) (:all-tiles ra))
                       :items-after  (mapv (juxt :id :layout :zone :priority) (:all-tiles rb))))))))