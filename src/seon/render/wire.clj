(ns seon.render.wire
  "Unified single-source LLM wire context & pure `ns-map -> ns-map` Hiccup ephemeral tail builder.
   Uses Datahike commit-id caching, `seon.ctx/related-updates`, and `seon.render.fit/fit-projections`
   (`:target :wire`, `already-shown` zero-repetition queue, least-volatile -> most-volatile ordering)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.health :as health]
            [seon.model :as model]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.entities :as rent]
            [seon.render.fit :as fit]
            [seon.rule :as rules]
            [seon.rule.context :as rctx]
            [seon.signals :as sig]
            [seon.store :as store]))

(defonce ^:seon.flow/edge wire-request-cache (store/lru-cache 8))
(defonce ^:private ^:seon.flow/edge walk-prefix-cache (store/lru-cache 64))
(defonce ^:private ^:seon.flow/edge form-meta-cache (store/lru-cache 16))

(def fallback-entity-renderers
  "Pure `ns-map -> ns-map` fallback renderers used only when a minimal DB lacks indexed code vars."
  {:seon.code.var/entity   ['seon.render.entities/render-var-chip 'seon.render.entities/render-var-sig 'seon.render.entities/render-var-full]
   :seon.error/entity      ['seon.render.entities/render-error-badge 'seon.render.entities/render-error-card]
   :seon.task/entity       ['seon.render.entities/render-task-chip 'seon.render.entities/render-task-card 'seon.render.entities/render-task-full]
   :seon.schema/entity     ['seon.render.entities/render-schema-chip 'seon.render.entities/render-schema-detail]
   :seon.code.doc/entity   ['seon.render.entities/render-doc-snippet 'seon.render.entities/render-doc-full]
   :seon.test/verdict      ['seon.render.entities/render-test-badge 'seon.render.entities/render-test-card]
   :seon.agent.eval/entity ['seon.render.entities/render-eval-row 'seon.render.entities/render-eval-card]
   :seon.code.ns/entity    ['seon.render.entities/render-ns-chip 'seon.render.entities/render-ns-card]
   :seon.agent/entity      ['seon.render.entities/render-agent-row 'seon.render.entities/render-agent-card]
   :seon.rule/entity       ['seon.render.entities/render-rule-chip 'seon.render.entities/render-rule-detail]
   :seon.signal/snapshot   ['seon.signals/render-signals-bar 'seon.signals/render-signals-panel]})

(defn- live-cid [db]
  (when (and db (instance? datahike.db.DB db)) (d/commit-id db)))

;; Fast indexed tail data gatherers

(defn- cached-form-meta [db]
  (if-not (and db (contains? (:schema db) :seon.code.form/key) (contains? (:schema db) :seon.code.form/hash))
    {:hashes {} :txs {}}
    (let [cid (live-cid db)
          compute (fn []
                    (let [ks (reduce #(assoc %1 (:e %2) (:v %2)) {} (d/datoms db :aevt :seon.code.form/key))]
                      (reduce (fn [acc dt]
                                (if-let [k (get ks (:e dt))]
                                  (-> acc (assoc-in [:hashes k] (:v dt)) (assoc-in [:txs k] (long (:tx dt 0))))
                                  acc))
                              {:hashes {} :txs {}}
                              (d/datoms db :aevt :seon.code.form/hash))))]
      (if cid
        (store/lru-get! form-meta-cache 16
                        [(get-in db [:config :store :id]) (get-in db [:config :branch] :main) cid (:max-tx db)]
                        compute)
        (compute)))))

(defn- gather-branch-status-data [main-db branch-db ag]
  (let [branch (:seon.agent/branch ag :main)
        aid (:seon.agent/id ag)]
    (when (and main-db branch-db (not= branch :main))
      (let [mcid (live-cid main-db)
            bcid (live-cid branch-db)
            turns (if (and aid (contains? (:schema branch-db) :seon.agent.eval/agent))
                    (->> (d/datoms branch-db :avet :seon.agent.eval/agent aid)
                         (keep #(:seon.agent.eval/turn (d/pull branch-db '[:seon.agent.eval/turn] (:e %))))
                         distinct count)
                    0)
            max-t (long (or (when (and aid (contains? (:schema main-db) :seon.config/max-tool-turns))
                              (store/config-val main-db aid :seon.config/max-tool-turns))
                            32))
            rem-t (when (pos? max-t) (max 0 (- max-t turns)))
            show-b? (and (pos? max-t) (pos? turns) (or (>= turns 10) (<= rem-t 12)))]
        (when (or show-b? (not= mcid bcid))
          (let [same? (and mcid bcid (= mcid bcid))
                mm (if same? {:hashes {} :txs {}} (cached-form-meta main-db))
                bm (if same? {:hashes {} :txs {}} (cached-form-meta branch-db))
                mh (:hashes mm) bh (:hashes bm) mt (:txs mm) bt (:txs bm)
                diffs (filterv #(not= (get bh %) (get mh %)) (into (set (keys bh)) (keys mh)))
                edited (filterv #(and (contains? bh %) (> (get bt % 0) (get mt % 0))) diffs)
                drifted (filterv #(and (contains? mh %) (contains? bh %) (> (get mt % 0) (get bt % 0))) diffs)
                nss (vec (distinct (keep #(let [p (first (str/split (str %) #"[#/]" 2))]
                                            (when-not (str/blank? p) (symbol p)))
                                         edited)))]
            {:branch branch :max-tx (:max-tx branch-db) :branch-edited edited :main-drifted drifted
             :branch-errors (when (seq edited) (health/caused-by-branch main-db branch-db {} nss))
             :last-proofs (:seon.agent/last-proofs ag) :show-budget? show-b?
             :turns-used turns :max-turns max-t :rem-turns rem-t}))))))

(defn- gather-scoped-health-data [main-db branch-db ag evals]
  (when-let [scope (and main-db (:seon.agent/scope ag))]
    (let [hdb (if (or (nil? branch-db) (= branch-db main-db)
                      (<= (long (:max-tx branch-db 0)) (long (or (:seon.agent/merged-tx ag) 0))))
                main-db branch-db)]
      {:scope scope
       :problems (vec (distinct
                       (concat (health/live-problems-for-scope main-db branch-db scope (:seon.agent/id ag) evals)
                               (filterv #(or (= :error (:severity %)) (:fix %))
                                        (health/static-problems-for-scope hdb scope)))))})))

(defn- gather-activity-data [main-db branch-db ag]
  (let [aid (:seon.agent/id ag)
        scope (:seon.agent/scope ag)
        prefs (or (:seon.agent/context-prefs ag) {})
        rec-lim (long (or (:recent-data-limit prefs) 4))
        has-c? (and aid main-db (contains? (:schema main-db) :seon.agent.call/callee))
        incoming (when has-c?
                   (->> (d/datoms main-db :avet :seon.agent.call/callee aid)
                        (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller :seon.agent.call/status
                                                :seon.agent.call/prompt :seon.agent.call/result-schema] (:e %)))
                        (filter #(= :pending (:seon.agent.call/status %))) vec))
        outgoing (when (and aid main-db (contains? (:schema main-db) :seon.agent.call/caller))
                   (->> (d/datoms main-db :avet :seon.agent.call/caller aid)
                        (map #(d/pull main-db '[:seon.agent.call/callee :seon.agent.call/status
                                                :seon.agent.call/prompt :seon.agent.call/created-tx] (:e %)))
                        (filter #(= :pending (:seon.agent.call/status %)))
                        (take-last 5) vec))
        open-errs (when (and aid branch-db (contains? (:schema branch-db) :seon.error/agent))
                    (->> (d/datoms branch-db :avet :seon.error/agent aid)
                         (map #(d/pull branch-db '[*] (:e %)))
                         (filter #(= :open (:seon.error/status %))) vec))
        samples (when (and main-db scope (pos? rec-lim))
                  (let [rdefs (when (rdata/router-agent? ag)
                                [:seon.agent.call/id :seon.task/id :seon.test.run/branch :seon.error/id :seon.agent/id])
                        cands (->> (concat (:extra-schemas prefs) rdefs) distinct
                                   (filter #(and (contains? (:schema main-db) %)
                                                 (first (take 1 (d/datoms main-db :avet %)))))
                                   (take 5))]
                    (->> cands
                         (mapcat (fn [a]
                                   (map #(hash-map :attr a :tx (:tx %) :eid (:e %))
                                        (take-last 2 (sort-by :tx (take 250 (d/datoms main-db :avet a)))))))
                         (sort-by (comp - :tx)) (take rec-lim)
                         (mapv #(assoc % :preview (rdata/compact-entity-preview (d/pull main-db '[*] (:eid %)) 150))))))]
    {:scope scope :incoming incoming :outgoing outgoing :open-errors open-errs :samples samples}))

(defn- tail-block-from-res [res]
  (cond
    (and (map? res) (:seon.ui/hiccup res))
    (let [h (:seon.ui/hiccup res) txt (render/hiccup->text h)]
      (when (or (not (str/blank? txt)) (:image res))
        (cond-> {:tag (or (:tag res) "tail") :hiccup h :text txt}
          (:attrs res) (assoc :attrs (:attrs res))
          (:image res) (assoc :image (:image res)))))

    (and (map? res) (or (not (str/blank? (str (:text res)))) (:image res)))
    (cond-> {:tag (or (:tag res) "tail")
             :text (or (:text res) (when (:hiccup res) (render/hiccup->text (:hiccup res))) "")}
      (:hiccup res) (assoc :hiccup (:hiccup res))
      (:attrs res) (assoc :attrs (:attrs res))
      (:image res) (assoc :image (:image res)))

    (vector? res) {:tag "tail" :hiccup res :text (render/hiccup->text res)}
    :else nil))

(def ^:private default-tail-slots
  ['seon.render.tail/branch-status 'seon.render.tail/scoped-health
   'seon.render.tail/neighborhood-activity 'seon.render.tail/live-viewport])

(defn- eval-tail-slot [sym main-db branch-db ag evals opts]
  (let [aid (:seon.agent/id ag)
        cfg? (and main-db (contains? (:schema main-db) :seon.config/viewport-screenshot?))
        v-en? (cond (:force-vision? opts) true
                    cfg? (boolean (store/config-val main-db aid :seon.config/viewport-screenshot?))
                    :else (boolean (:vision? opts true)))]
    (case sym
      seon.render.tail/branch-status
      (when-let [bs (gather-branch-status-data main-db branch-db ag)]
        (rent/render-branch-status-tail {:seon.tail/branch-status bs}))
      seon.render.tail/scoped-health
      (when-let [sh (gather-scoped-health-data main-db branch-db ag evals)]
        (rent/render-scoped-health-tail {:seon.tail/scoped-health sh}))
      seon.render.tail/system-health
      (when main-db
        (rent/render-system-health-tail {:seon.tail/system-health (sig/all-signals main-db)}))
      seon.render.tail/neighborhood-activity
      (rent/render-activity-tail {:seon.tail/activity (gather-activity-data main-db branch-db ag)})
      seon.render.tail/live-viewport
      (when (:viewport-screenshot opts)
        (rent/render-viewport-tail {:seon.tail/viewport {:snapshot (:viewport-screenshot opts)
                                                         :enabled? v-en?
                                                         :vision? (boolean (:vision? opts true))}}))
      seon.render.chronicle/chronicle-tail
      (when (and main-db (contains? (:schema main-db) :seon.summary/id))
        (let [rec (->> (d/datoms main-db :aevt :seon.summary/id)
                       (map #(d/pull main-db '[:seon.summary/title :seon.summary/kind :seon.summary/from-tx :seon.summary/to-tx] (:e %)))
                       (sort-by #(long (or (:seon.summary/to-tx %) 0)))
                       (take-last 3) reverse vec)]
          (when (seq rec)
            {:tag "chronicle"
             :seon.ui/hiccup
             [:section {:class "tail-block" :data-min-width 220}
              [:p "Chronicle anchors:"]
              (into [:ul] (for [s rec]
                            [:li (str "[" (:seon.summary/from-tx s) ".." (:seon.summary/to-tx s)
                                      " · " (name (or (:seon.summary/kind s) :milestone)) "] "
                                      (:seon.summary/title s))]))]})))
      (when (qualified-symbol? sym)
        (when-let [f (requiring-resolve sym)]
          (f {:main-db main-db :branch-db branch-db :agent ag :evals evals :opts opts}))))))

(defn- assemble-unified-tail [main-db branch-db ag evals opts]
  (let [syms (or (seq (:seon.agent/tail-renders ag)) default-tail-slots)
        blocks (vec (keep #(tail-block-from-res (eval-tail-slot % main-db branch-db ag evals opts)) syms))
        ordered (vec (sort-by #(if (:image %) 1 0) blocks))]
    {:blocks ordered :image (some :image ordered)}))

(defn neighborhood-recent-activity
  "Renders neighborhood activity through the unified pure Hiccup tail pipeline."
  {:malli/schema [:=> [:cat :any :any :map] [:maybe :string]]}
  [main-db branch-db ag]
  (when-let [res (rent/render-activity-tail {:seon.tail/activity (gather-activity-data main-db branch-db ag)})]
    (render/hiccup->text (:seon.ui/hiccup res))))

(defn wire-cache-key
  "Datahike commit-id cache key for `build-live-wire-request` (zero manual AEVT DB scans)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :any :any] :any]}
  [main-db branch-db agent-id vision? viewport-screenshot]
  (let [mcid (live-cid main-db) bcid (live-cid branch-db)]
    (when (and mcid bcid)
      [(get-in main-db [:config :store :id]) agent-id mcid (:max-tx main-db)
       (if (= mcid bcid) :same-db [bcid (:max-tx branch-db)]) (boolean vision?)
       (when viewport-screenshot
         [(:width viewport-screenshot) (:height viewport-screenshot) (:bytes viewport-screenshot)
          (:captured-at viewport-screenshot) (hash (:data-url viewport-screenshot))
          (hash (:base64 viewport-screenshot))])])))

(defn- build-wire-examples [main-db ag]
  (vec (for [ex-id (sort (:seon.agent/examples ag))
             :when (first (d/datoms main-db :avet :seon.agent/id ex-id))
             :let [ex-ag (d/pull main-db '[:seon.agent/id :seon.agent/goal] [:seon.agent/id ex-id])]]
         {:id ex-id :goal (:seon.agent/goal ex-ag) :turns (rdata/evals->turns (rdata/agent-evals-in main-db ex-id))})))

(defn- entity-max-tx [db eid]
  (if (integer? eid)
    (reduce max 0 (map #(long (or (:tx %) 0)) (d/datoms db :eavt eid)))
    (long (or (:max-tx db) 0))))

(defn- tile->fit-item [main-db walk-db ag t]
  (let [h (render/render-tile-node walk-db t {:mode :agent :compact? true :main-db main-db :agent ag})
        m (fit/measure-hiccup h)
        eid (or (:eid t) (:id t))
        sk (:schema t)
        tile-cand (assoc m :fn (or (:view-fn t) :tile/default) :hiccup h)
        disc (when (and (integer? eid) (qualified-keyword? sk))
               (or (seq (fit/discover-renderers walk-db [sk])) (get fallback-entity-renderers sk)))
        dh (:schema walk-db)
        norm-ent (when (and (seq disc) (map? (:val t)))
                   (into {} (map (fn [[k v]]
                                   [k (if (and (= :db.cardinality/many (get-in dh [k :db/cardinality]))
                                               (sequential? v))
                                        (set v) v)]))
                         (:val t)))
        extra-cands (when (and (seq disc) (map? norm-ent))
                      (keep (fn [rsym]
                              (when-let [f (requiring-resolve rsym)]
                                (when-let [eh (:seon.ui/hiccup (f {sk norm-ent}))]
                                  (let [em (fit/measure-hiccup eh)]
                                    (when (< (:tokens em) (:tokens m))
                                      (assoc em :fn rsym :hiccup eh))))))
                            disc))]
    (assoc t :entity eid :tx (entity-max-tx walk-db eid) :hash (hash [(:id t) sk (render/hiccup->text h)])
           :candidates (vec (concat extra-cands [tile-cand])))))

(defn- build-wire-candidate-pool [main-db walk-db task-db ag]
  (let [aid (:seon.agent/id ag)
        derived (rctx/derive-context main-db walk-db ag [] health/report
                                     {:include-evals? false :include-children? false :task-db task-db :target :wire})
        ru (ctx/related-updates {:seon.db/main main-db :seon.db/branch walk-db :seon.agent/id aid
                                 :seon.view/target :wire :seon.view/token-budget 16000})
        tile-items (mapv #(tile->fit-item main-db walk-db ag %) (concat (:tiles derived) (:tail-tiles derived)))
        seen (into #{} (keep :entity) tile-items)
        extra-ru (into [] (comp (remove #(contains? seen (:entity %)))
                                (remove #(contains? (:tags %) :eval))
                                (map #(-> % (dissoc :candidates :data) (assoc :source :rule))))
                       (:seon.ctx/projections ru))
        rt (:seon.ctx/trace ru)]
    {:all-items (into tile-items extra-ru)
     :walk-db walk-db
     :rule-trace {:rules-count (+ (long (:rules-count derived 0)) (long (:rules-total rt 0)))
                  :facts-count (:facts-count derived 0)
                  :ms (:ms derived 0.0)
                  :runs (vec (concat (:runs derived) (:fired-rules rt)))
                  :recomputed (:recomputed rt 0)
                  :skipped (:skipped rt 0)}}))

(defn- fit-wire-pool [{:keys [all-items walk-db rule-trace]} opts]
  (let [budget (long (or (:token-budget opts) 16000))
        strategy (or (:strategy opts) :breadth-first)
        fit-res (fit/fit-projections walk-db all-items
                                     {:target :wire :token-budget budget :strategy strategy
                                      :already-shown (:already-shown opts) :weights (:weights opts)
                                      :fallback-renderers fallback-entity-renderers})
        selected (mapv #(assoc % :text (if (= :unchanged (:status %)) "" (render/hiccup->text (:hiccup %))))
                       (:selected fit-res))
        fmt-p (fn [{:keys [schema source handle layout chosen-fn text]}]
                (str "### [" (name (or source :rule)) (when handle (str " " handle))
                     " · " (or schema chosen-fn :entity)
                     (when (and layout (not= layout :expanded-card)) (str " · " (name layout))) "]\n" text))
        pref-sel (filterv #(and (not= :ephemeral-tail (:zone %)) (not= :unchanged (:status %))) selected)
        tail-sel (filterv #(and (= :ephemeral-tail (:zone %)) (not= :unchanged (:status %))) selected)]
    {:tile-projections selected
     :walk-text (str/join "\n\n---\n\n" (map fmt-p pref-sel))
     :walk-db walk-db
     :tail-tile-blocks (when (seq tail-sel)
                         [{:tag "rule-tail" :text (str/join "\n\n---\n\n" (map fmt-p tail-sel))}])
     :rule-trace rule-trace
     :fit-trace {:tokens (:tokens fit-res) :budget (:budget fit-res) :strategy (:strategy fit-res)
                 :selected-count (count selected) :dropped-count (count (:dropped fit-res))
                 :items (mapv #(select-keys % [:entity :id :schema :zone :status :chosen-fn :tokens
                                               :measured-tokens :rank/score :rank/signals :candidates :tx])
                              selected)}
     :shown (:shown fit-res)
     :surface-stats {:selected (count selected) :dropped (count (:dropped fit-res))
                     :unchanged (count (filter #(= :unchanged (:status %)) selected))
                     :tokens (:tokens fit-res) :budget (:budget fit-res)}}))

(defn render-agent-context
  "Renders the unified Upgrade-Ladder `<walk-context>` + Hiccup `<ephemeral-tail>` for any agent."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :keyword] :string]
                  [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :string]]}
  ([main-db branch-db agent-id] (render-agent-context main-db branch-db agent-id {}))
  ([main-db branch-db agent-id opts]
   (let [base-ag (or (rdata/pull-agent-entity main-db branch-db agent-id) {:seon.agent/id agent-id :seon.agent/role :worker})
         tid (or (:task opts) (:seon.agent/task base-ag))
         tscope (when (and tid (nil? (:scope opts)) (nil? (:seon.agent/scope base-ag)))
                  (:seon.task/scope (or (d/pull branch-db '[:seon.task/scope] [:seon.task/id tid])
                                        (d/pull main-db '[:seon.task/scope] [:seon.task/id tid]))))
         scope (or (:scope opts) (:seon.agent/scope base-ag) tscope)
         extra-nss (vec (distinct (concat (get-in base-ag [:seon.agent/context-prefs :extra-namespaces])
                                          (:extra-namespaces opts))))
         pinned (vec (distinct (concat (:seon.agent/pinned base-ag) (:pinned opts))))
         ag (cond-> base-ag tid (assoc :seon.agent/task tid) scope (assoc :seon.agent/scope scope)
                    (seq extra-nss) (assoc-in [:seon.agent/context-prefs :extra-namespaces] extra-nss)
                    (seq pinned) (assoc :seon.agent/pinned pinned))
         tdb (if (and tid (first (d/datoms branch-db :avet :seon.task/id tid))) branch-db main-db)
         {:keys [walk-text tail-tile-blocks]} (fit-wire-pool (build-wire-candidate-pool main-db branch-db tdb ag) opts)
         include-tail? (if (contains? opts :include-tail?) (boolean (:include-tail? opts)) true)
         tblocks (when include-tail?
                   (:blocks (assemble-unified-tail main-db branch-db ag (rdata/agent-evals-in branch-db agent-id) {:vision? false})))
         rtail (when (seq tail-tile-blocks)
                 (str "<rule-tail>\n" (str/join "\n\n" (keep :text tail-tile-blocks)) "\n</rule-tail>"))
         ttext (not-empty (str/join "\n\n" (remove str/blank? (concat (keep :text tblocks) [rtail]))))]
     (str "<walk-context>\n" walk-text "\n</walk-context>"
          (when-not (str/blank? ttext) (str "\n\n<ephemeral-tail>\n" ttext "\n</ephemeral-tail>"))))))

(defn- derive-wire-walk-prefix
  {:seon.flow/edge true}
  [main-db branch-db conn ag anchor-turn opts]
  (let [branch (:seon.agent/branch ag :main)
        read-main? (or (= :main (:seon.agent/read-branch ag))
                       (:seon.agent/router? ag)
                       (= :coordinator (:seon.agent/role ag)))
        base-cid (when (and conn (not= branch :main) (not read-main?) (= anchor-turn 1))
                   (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
        eff-cid (or base-cid (let [wdb (if read-main? main-db branch-db)] [(live-cid wdb) (:max-tx wdb)]))
        task? (boolean (and (:seon.agent/task ag) (first (d/datoms branch-db :avet :seon.task/id (:seon.agent/task ag)))))
        walk-key [(get-in main-db [:config :store :id]) (:seon.agent/id ag) eff-cid branch
                  (rules/rules-db-sig main-db) (rules/rules-db-sig branch-db)
                  (:seon.agent/role ag) (:seon.agent/scope ag) (:seon.agent/task ag) (:seon.agent/goal ag)
                  (vec (:seon.agent/pinned ag)) (vec (:seon.agent/memory ag)) (vec (:seon.agent/curated ag))
                  (:seon.agent/context-prefs ag) (:seon.agent/render-fn ag) anchor-turn task?]
        pool (store/lru-get!
              walk-prefix-cache 64 walk-key
              (fn []
                (let [base-db (when (and conn base-cid)
                                (try (d/commit-as-db conn base-cid) (catch Throwable _ nil)))
                      scope (:seon.agent/scope ag)
                      scope-in-base? (or (nil? scope)
                                         (and base-db (boolean (first (d/datoms base-db :avet :seon.code.ns/name scope)))))
                      walk-db (cond read-main? main-db
                                    (and base-db scope-in-base?) base-db
                                    :else branch-db)]
                  (build-wire-candidate-pool main-db walk-db (if task? branch-db main-db) ag))))]
    (fit-wire-pool pool opts)))

(defn build-live-wire-request
  "Single source of truth for an agent's LLM context AND Debug View:
   Uses Datahike commit-id caching, `seon.ctx/related-updates`, `seon.render.fit/fit-projections`
   (`:target :wire`, `already-shown` queue, least-volatile -> most-volatile ordering),
   and pure `ns-map -> ns-map` Hiccup ephemeral tail renderers."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :map]}
  [main-db branch-db agent-id {:keys [viewport-screenshot vision? conn already-shown token-budget strategy] :as opts}]
  (let [ck (when (and (empty? already-shown) (nil? token-budget) (nil? strategy))
             (wire-cache-key main-db branch-db agent-id vision? viewport-screenshot))
        compute
        (fn []
          (let [ag (rdata/pull-agent-entity main-db branch-db agent-id)
                evals (rdata/agent-evals-in branch-db agent-id)
                msgs (rdata/agent-messages-in main-db agent-id)
                seen-ids (store/agent-seen-message-ids branch-db agent-id)
                unread (filterv #(not (contains? seen-ids (:seon.agent.message/id %))) msgs)
                branch (:seon.agent/branch ag :main)
                anchor-turn (let [t (:seon.agent.eval/turn (first evals))] (if (and t (> t 1)) t 1))
                {:keys [tile-projections walk-text walk-db tail-tile-blocks rule-trace fit-trace shown surface-stats]}
                (derive-wire-walk-prefix main-db branch-db conn ag anchor-turn opts)
                tail-res (assemble-unified-tail main-db branch-db ag evals
                                                {:viewport-screenshot viewport-screenshot :vision? (boolean vision?)})
                tail-blocks (vec (concat (:blocks tail-res) tail-tile-blocks))
                activity-text (not-empty (str/join "\n\n" (keep :text tail-blocks)))
                wire (model/build-agent-wire-messages
                      {:thinking? true :replay-reasoning? true :vision? (boolean vision?)}
                      {:agent-id agent-id :branch branch :role (:seon.agent/role ag :worker)
                       :goal (:seon.agent/goal ag) :system-prompt (:seon.agent/system-prompt ag)
                       :examples (build-wire-examples main-db ag) :walk-text walk-text
                       :tail-blocks tail-blocks :ephemeral-image (:image tail-res)
                       :history (rdata/evals->turns evals (into {} (map (juxt :seon.agent.message/id identity)) msgs))
                       :unread unread :eval-db walk-db :main-db main-db :fallback-db branch-db
                       :viewport-screenshot viewport-screenshot})]
            (assoc wire :agent ag :tiles tile-projections :rule-trace rule-trace
                   :fit-trace fit-trace :shown shown :surface-stats surface-stats
                   :tail-blocks tail-blocks :activity-text activity-text
                   :evals evals :messages-inbox msgs :unread unread)))]
    (if ck (store/lru-get! wire-request-cache 8 ck compute) (compute))))
