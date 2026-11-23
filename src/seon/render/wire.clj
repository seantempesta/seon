(ns seon.render.wire
  "ONE unified single-source LLM wire context & pure `ns-map -> ns-map` Hiccup ephemeral tail builder.
   Uses Datahike commit-id + `d/merge-base` KV-cache stability, ONE `seon.ctx/gather-agent-candidates`,
   and `seon.render.fit/fit-projections` (`:target :wire`, `already-shown` zero-repetition queue)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.health :as health]
            [seon.model :as model]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.entities]
            [seon.render.entities.work]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks :refer [db-schema live-cid]]
            [seon.render.tails :as rtails]
            [seon.rule :as rules]
            [seon.schema.index :as six]
            [seon.signals :as sig]
            [seon.store :as store]
            [seon.render.status :as rstatus]))

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
   :seon.code.ns/entity    ['seon.render.entities/render-ns-chip 'seon.render.entities/render-ns-card 'seon.render.entities.work/render-ns-full]
   :seon.agent/entity      ['seon.render.entities/render-agent-row 'seon.render.entities/render-agent-card]
   :seon.rule/entity       ['seon.render.entities/render-rule-chip 'seon.render.entities/render-rule-detail]
   :seon.fix/entity        ['seon.render.entities/render-fix-chip 'seon.render.entities/render-fix-card]
   :seon.agent.call/entity ['seon.render.entities/render-call-row 'seon.render.entities/render-call-card]
   :seon.system/entity     ['seon.render.entities/render-system-chip 'seon.render.entities/render-system-card]
   :seon.signal/snapshot   ['seon.signals/render-signals-bar 'seon.signals/render-signals-panel]
   :seon.ctx/snapshot      ['seon.render.entities.work/render-ctx-chip 'seon.render.entities.work/render-ctx-snapshot]
   :seon.summary/entity    ['seon.render.entities.work/render-summary-chip 'seon.render.entities.work/render-summary-card]
   :seon.bench.run/entity  ['seon.render.entities.work/render-bench-run-card]
   :seon.health/report     ['seon.render.entities.work/render-health-report]
   :seon.task/lineage      ['seon.render.entities.work/render-task-lineage]
   :seon.media/image       ['seon.render.media/render-image-chip 'seon.render.media/render-image-card]
   :seon.ui.status/entity  ['seon.render.status/render-status-chip 'seon.render.status/render-status-card]})

(defn- cached-form-meta [db]
  (let [dh (db-schema db)]
    (if-not (and db (dh :seon.code.form/key) (dh :seon.code.form/hash))
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
          (compute))))))

(defn- gather-scoped-health-data [main-db branch-db ag evals branch-status]
  (when-let [scope (and main-db (:seon.agent/scope ag))]
    {:scope scope
     :problems (health/fast-scoped-alerts main-db branch-db scope (:seon.agent/id ag) evals
                                          {:branch-edited (:branch-edited branch-status)})}))

(defn- gather-activity-data [main-db branch-db ag]
  (let [aid (:seon.agent/id ag) scope (:seon.agent/scope ag)
        prefs (or (:seon.agent/context-prefs ag) {})
        rec-lim (long (or (:recent-data-limit prefs) 4))
        mdh (db-schema main-db) bdh (db-schema branch-db)
        incoming (when (and aid main-db (mdh :seon.agent.call/callee))
                   (->> (d/datoms main-db :avet :seon.agent.call/callee aid)
                        (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller :seon.agent.call/status
                                                :seon.agent.call/prompt :seon.agent.call/result-schema] (:e %)))
                        (filter #(= :pending (:seon.agent.call/status %))) vec))
        outgoing (when (and aid main-db (mdh :seon.agent.call/caller))
                   (->> (d/datoms main-db :avet :seon.agent.call/caller aid)
                        (map #(d/pull main-db '[:seon.agent.call/callee :seon.agent.call/status
                                                :seon.agent.call/prompt :seon.agent.call/created-tx] (:e %)))
                        (filter #(= :pending (:seon.agent.call/status %)))
                        (take-last 5) vec))
        open-errs (when (and aid branch-db (bdh :seon.error/agent))
                    (->> (d/datoms branch-db :avet :seon.error/agent aid)
                         (map #(d/pull branch-db '[*] (:e %)))
                         (filter #(= :open (:seon.error/status %))) vec))
        samples (when (and main-db scope (pos? rec-lim))
                  (let [rdefs (when (rdata/router-agent? ag)
                                [:seon.agent.call/id :seon.task/id :seon.test.run/branch :seon.error/id :seon.agent/id])
                        cands (->> (concat (:extra-schemas prefs) rdefs) distinct
                                   (filter #(and (mdh %) (first (take 1 (d/datoms main-db :avet %))))) (take 5))]
                    (->> cands
                         (mapcat (fn [a] (map #(hash-map :attr a :tx (:tx %) :eid (:e %))
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

(defn- gather-chronicle-tail [main-db]
  (when (and main-db ((db-schema main-db) :seon.summary/id))
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
                                  (:seon.summary/title s))]))]}))))

(defn neighborhood-recent-activity
  "Renders neighborhood activity through the unified pure Hiccup tail pipeline."
  {:malli/schema [:=> [:cat :any :any :map] [:maybe :string]] :cost/ticks 5000000}
  [main-db branch-db ag]
  (when-let [res (rtails/render-activity-tail {:seon.tail/activity (gather-activity-data main-db branch-db ag)})]
    (render/hiccup->text (:seon.ui/hiccup res))))

(defn wire-cache-key
  "Datahike commit-id cache key for `build-live-wire-request` (zero manual AEVT DB scans)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :any :any] :any] :cost/ticks 500000}
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

(defn- fast-health-summary [db]
  (let [{:keys [branch health]} (sig/all-signals db)]
    (with-meta
      {:ok? (:ok? health) :branch branch
       :counts {:errors (:errors health) :warnings (:warnings health)
                :open-errors (:open-errors health) :oversized-ns (:oversized-ns health)}
       :problems []}
      {:seon/schema :seon.health/report})))

(defn- ensure-fallback-candidates [main-db walk-db ag items]
  (let [ix (:entity-index (fit/cached-registry-info walk-db))]
    (mapv (fn [item]
            (if (seq (:candidates item))
              item
              (let [ent (:data item)
                    sk (or (:schema item)
                           (when (instance? clojure.lang.IObj ent) (:seon/schema (meta ent)))
                           (when (and (map? ent) ix) (:schema (first (six/facets-of ix ent)))))]
                (if (or (nil? sk) (contains? fallback-entity-renderers sk)
                        (seq (fit/discover-renderers walk-db [sk]))
                        (and main-db (not= main-db walk-db) (seq (fit/discover-renderers main-db [sk]))))
                  (cond-> item sk (assoc :schema sk))
                  (let [v (if (and (map? ent) (not (:seon/schema (meta ent)))) (with-meta ent {:seon/schema sk}) ent)
                        h (render/render-value walk-db v {:mode :agent :compact? true :main-db main-db :agent ag :params (:params item)})
                        crumb (when (qualified-keyword? sk)
                                (render/render-schema-breadcrumbs walk-db sk v {:mode :agent :main-db main-db}))
                        full-h (if crumb [:div {:class "tile-with-crumbs stack-xs"} h crumb] h)
                        m (fit/measure-hiccup full-h)]
                    (assoc item :schema sk :candidates [(assoc m :fn :render/value :hiccup full-h)]))))))
          items)))

(defn- build-wire-candidate-pool [main-db walk-db task-db ag opts]
(let [prefs (if (map? (:seon.agent/context-prefs ag)) (:seon.agent/context-prefs ag) {})
      minimal? (boolean (if (contains? opts :minimal?) (:minimal? opts) (:minimal? prefs)))
      p-dis (:disabled-rules prefs)
      o-dis (:disabled-rules opts)
      eff-disabled (into #{} (concat (when (coll? p-dis) p-dis) (when (coll? o-dis) o-dis)))
      sem? (if (contains? opts :semantic?) (boolean (:semantic? opts)) (if (contains? prefs :semantic?) (boolean (:semantic? prefs)) (not minimal?)))
      sit? (if (contains? opts :situational?) (boolean (:situational? opts)) (if (contains? prefs :situational?) (boolean (:situational? prefs)) (not minimal?)))
      rich? (if (contains? opts :rich-ns?) (boolean (:rich-ns? opts)) (if (contains? prefs :rich-ns?) (boolean (:rich-ns? prefs)) (not minimal?)))
      {:keys [candidates ctx-trace]}
      (ctx/gather-agent-candidates main-db walk-db (:seon.agent/id ag)
                                   {:agent ag :task-db task-db :target :wire
                                    :include-evals? false :include-ctx-snapshot? true
                                    :health-summary-fn fast-health-summary
                                    :disabled-rules eff-disabled
                                    :minimal? minimal? :semantic? sem? :enrich? (not minimal?)
                                    :situational? sit? :rich-ns? rich?})
      items (ensure-fallback-candidates main-db walk-db ag candidates)]
  {:all-items items
   :walk-db walk-db
   :ctx-trace ctx-trace
   :rule-trace {:rules-count (long (or (:rules-total ctx-trace) 0))
                :facts-count (count items)
                :ms (reduce + 0.0 (map #(double (or (:ms %) 0.0)) (:fired-rules ctx-trace)))
                :runs (vec (:fired-rules ctx-trace))
                :recomputed (long (or (:recomputed ctx-trace) 0))
                :skipped (long (or (:skipped ctx-trace) 0))}}))

(defn- fit-wire-pool [{:keys [all-items walk-db rule-trace]} main-db opts]
  (let [budget (long (or (:token-budget opts) 16000))
        strategy (or (:strategy opts) :breadth-first)
        fit-opts {:target :wire :token-budget budget :strategy strategy :main-db main-db
                  :already-shown (:already-shown opts) :weights (:weights opts)
                  :fallback-renderers fallback-entity-renderers}
        explain? (boolean (:explain? opts))
        explained (when explain? (fit/explain-candidates walk-db all-items fit-opts))
        fit-res (if explained
                  (let [ent-by-id (into {} (map (juxt :entity :data)) all-items)
                        sel (mapv (fn [it]
                                    (let [win (some #(when (:chosen? %) %) (:variations it))]
                                      (assoc it :data (get ent-by-id (:entity it))
                                             :hiccup (:hiccup win)
                                             :text (if (= :unchanged (:status it)) "" (str (:text win))))))
                                  (:selected explained))]
                    (assoc explained :selected sel))
                  (fit/fit-projections walk-db all-items fit-opts))
        selected (if explained
                   (:selected fit-res)
                   (mapv #(assoc % :text (if (= :unchanged (:status %)) "" (render/hiccup->text (:hiccup %))))
                         (:selected fit-res)))
        fmt-p (fn [{:keys [schema source handle layout chosen-fn text]}]
                (str "### [" (name (or source :rule)) (when handle (str " " handle))
                     " · " (or schema chosen-fn :entity)
                     (when (and layout (not= layout :expanded-card)) (str " · " (name layout))) "]\n" text))
        pref-sel (filterv #(and (not= :ephemeral-tail (:zone %)) (not= :unchanged (:status %))) selected)
        tail-sel (filterv #(and (= :ephemeral-tail (:zone %)) (not= :unchanged (:status %))) selected)]
    (cond-> {:tile-projections selected
             :walk-text (str/join "\n\n---\n\n" (map fmt-p pref-sel))
             :walk-db walk-db
             :tail-tile-blocks (when (seq tail-sel)
                                 [{:tag "rule-tail"
                                   :hiccup (into [:section {:class "tail-block"}] (keep :hiccup tail-sel))
                                   :text (str/join "\n\n---\n\n" (map fmt-p tail-sel))}])
             :rule-trace rule-trace
             :fit-trace {:tokens (:tokens fit-res) :budget (:budget fit-res) :strategy (:strategy fit-res)
                         :selected-count (count selected) :dropped-count (count (:dropped fit-res))
                         :items (mapv #(select-keys % [:entity :id :schema :zone :status :chosen-fn :tokens
                                                       :measured-tokens :rank/score :rank/signals :candidates :tx])
                                      selected)}
             :shown (:shown fit-res)
             :surface-stats {:selected (count selected) :dropped (count (:dropped fit-res))
                             :unchanged (count (filter #(= :unchanged (:status %)) selected))
                             :tokens (:tokens fit-res) :budget (:budget fit-res)}}
      explained (assoc :explained explained :candidates all-items))))

(defn- derive-wire-walk-prefix
{:seon.flow/edge true}
[main-db branch-db conn ag anchor-turn opts]
(let [branch (:seon.agent/branch ag :main)
      prefs (if (map? (:seon.agent/context-prefs ag)) (:seon.agent/context-prefs ag) {})
      pref-fit (select-keys prefs [:token-budget :strategy :weights])
      read-main? (or (= :main (:seon.agent/read-branch ag))
                     (:seon.agent/router? ag)
                     (= :coordinator (:seon.agent/role ag)))
      base-cid (when (and conn (not= branch :main) (not read-main?) (= anchor-turn 1))
                 (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
      eff-cid (cond
                (seq (:already-shown opts))
                (let [wdb (if read-main? main-db branch-db)] [(live-cid wdb) (:max-tx wdb)])
                base-cid base-cid
                :else :main-session)
      task? (boolean (and (:seon.agent/task ag) (first (d/datoms branch-db :avet :seon.task/id (:seon.agent/task ag)))))
      dis-key (when (seq (:disabled-rules opts)) (vec (sort (:disabled-rules opts))))
      walk-key [(get-in main-db [:config :store :id]) (:seon.agent/id ag) eff-cid branch
                (rules/rules-db-sig main-db) (rules/rules-db-sig branch-db)
                (:seon.agent/role ag) (:seon.agent/scope ag) (:seon.agent/task ag) (:seon.agent/goal ag)
                (vec (:seon.agent/pinned ag)) (vec (:seon.agent/memory ag)) (vec (:seon.agent/curated ag))
                (:seon.agent/context-prefs ag) anchor-turn task? dis-key]
      cached (store/lru-get!
              walk-prefix-cache 64 walk-key
              (fn []
                (let [base-db (when (and conn base-cid)
                                (if (= base-cid (live-cid main-db))
                                  main-db
                                  (try (d/commit-as-db conn base-cid) (catch Throwable _ nil))))
                      scope (:seon.agent/scope ag)
                      scope-in-base? (or (nil? scope)
                                         (and base-db (boolean (first (d/datoms base-db :avet :seon.code.ns/name scope)))))
                      walk-db (cond read-main? main-db
                                    (and base-db scope-in-base?) base-db
                                    :else branch-db)
                      pool (build-wire-candidate-pool main-db walk-db (if task? branch-db main-db) ag opts)]
                  {:pool pool :default-fit (delay (fit-wire-pool pool main-db pref-fit))})))
      default-opts? (and (empty? (:already-shown opts))
                         (nil? (:token-budget opts))
                         (nil? (:strategy opts))
                         (nil? (:weights opts))
                         (not (:explain? opts)))]
  (if default-opts?
    @(:default-fit cached)
    (fit-wire-pool (:pool cached) main-db (merge pref-fit opts)))))

(defonce ^:private ^:seon.flow/edge branch-errors-cache (store/lru-cache 32))

(defn- gather-branch-status-data [main-db branch-db ag]
  (let [branch (:seon.agent/branch ag :main) aid (:seon.agent/id ag)]
    (when (and main-db branch-db (not= branch :main))
      (let [mcid (live-cid main-db) bcid (live-cid branch-db)
            turns (if (and aid ((db-schema branch-db) :seon.agent.eval/agent))
                    (->> (d/datoms branch-db :avet :seon.agent.eval/agent aid)
                         (keep #(:seon.agent.eval/turn (d/pull branch-db '[:seon.agent.eval/turn] (:e %))))
                         distinct count) 0)
            max-t (long (or (when (and aid ((db-schema main-db) :seon.config/max-tool-turns))
                              (store/config-val main-db aid :seon.config/max-tool-turns)) 32))
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
                                            (when-not (str/blank? p) (symbol p))) edited)))]
            {:branch branch :max-tx (:max-tx branch-db) :branch-edited edited :main-drifted drifted
             :branch-errors (when (seq edited)
             (let [esig (mapv (fn [k] [k (get bh k)]) (sort edited))]
               (store/lru-get! branch-errors-cache 32
                               [(get-in main-db [:config :store :id]) mcid esig]
                               #(health/caused-by-branch main-db branch-db {} nss))))
             :last-proofs (:seon.agent/last-proofs ag) :show-budget? show-b?
             :turns-used turns :max-turns max-t :rem-turns rem-t}))))))

(defn- assemble-unified-tail [main-db branch-db ag evals opts]
  (let [aid (:seon.agent/id ag) router? (rdata/router-agent? ag)
        cfg? (and main-db ((db-schema main-db) :seon.config/viewport-screenshot?))
        v-en? (cond (:force-vision? opts) true
                    cfg? (boolean (store/config-val main-db aid :seon.config/viewport-screenshot?))
                    :else (boolean (:vision? opts true)))
        vp (when (:viewport-screenshot opts)
             (rtails/render-viewport-tail {:seon.tail/viewport {:snapshot (:viewport-screenshot opts)
                                                                :enabled? v-en?
                                                                :vision? (boolean (:vision? opts true))}}))
        act (rtails/render-activity-tail {:seon.tail/activity (gather-activity-data main-db branch-db ag)})
        now-ms (long (or (:now-ms opts) (System/currentTimeMillis)))
        wn (when-let [wd (rstatus/compute-wait-nudge-data main-db branch-db ag evals now-ms)]
             (rtails/render-wait-nudge-tail {:seon.tail/wait-nudge wd}))
        raw (if router?
              [(when main-db (rtails/render-system-health-tail {:seon.tail/system-health (sig/all-signals main-db)}))
               act wn vp (gather-chronicle-tail main-db)]
              (let [bs (gather-branch-status-data main-db branch-db ag)]
                [(when bs (rtails/render-branch-status-tail {:seon.tail/branch-status bs}))
                 (when-let [sh (gather-scoped-health-data main-db branch-db ag evals bs)]
                   (rtails/render-scoped-health-tail {:seon.tail/scoped-health sh}))
                 act wn vp]))
        blocks (vec (keep tail-block-from-res raw))
        ordered (vec (sort-by #(if (:image %) 1 0) blocks))]
    {:blocks ordered :image (some :image ordered)}))

(defn render-agent-context
  "Renders the unified Upgrade-Ladder `<walk-context>` + Hiccup `<ephemeral-tail>` for any agent."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :keyword] :string]
                  [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :string]]
   :cost/ticks 50000000}
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
         {:keys [walk-text tail-tile-blocks]} (fit-wire-pool (build-wire-candidate-pool main-db branch-db tdb ag opts) main-db opts)
         include-tail? (if (contains? opts :include-tail?) (boolean (:include-tail? opts)) true)
         tblocks (when include-tail?
                   (:blocks (assemble-unified-tail main-db branch-db ag (rdata/agent-evals-in branch-db agent-id) {:vision? false :now-ms (:now-ms opts)})))
         rtail (when (seq tail-tile-blocks)
                 (str "<rule-tail>\n" (str/join "\n\n" (keep :text tail-tile-blocks)) "\n</rule-tail>"))
         ttext (not-empty (str/join "\n\n" (remove str/blank? (concat (keep :text tblocks) [rtail]))))]
     (str "<walk-context>\n" walk-text "\n</walk-context>"
          (when-not (str/blank? ttext) (str "\n\n<ephemeral-tail>\n" ttext "\n</ephemeral-tail>"))))))

(defn build-live-wire-request
  "ONE single source of truth for an agent's LLM context AND Context Lab:
   Datahike commit-id + `d/merge-base` KV-cache stability, ONE `seon.ctx/gather-agent-candidates`,
   `seon.render.fit/fit-projections`, and pure `ns-map -> ns-map` Hiccup ephemeral tail renderers."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :map]
   :cost/ticks 60000000}
  [main-db branch-db agent-id {:keys [viewport-screenshot vision? conn already-shown token-budget strategy
                                      disabled-rules weights explain?] :as opts}]
  (let [ck (when (and (empty? already-shown) (nil? token-budget) (nil? strategy) (empty? disabled-rules) (nil? weights) (not explain?) (nil? (:now-ms opts))) (wire-cache-key main-db branch-db agent-id vision? viewport-screenshot))
        compute
        (fn []
          (let [ag (rdata/pull-agent-entity main-db branch-db agent-id)
                evals (rdata/agent-evals-in branch-db agent-id)
                msgs (rdata/agent-messages-in main-db agent-id)
                seen-ids (store/agent-seen-message-ids branch-db agent-id)
                unread (filterv #(not (contains? seen-ids (:seon.agent.message/id %))) msgs)
                branch (:seon.agent/branch ag :main)
                anchor-turn (let [t (:seon.agent.eval/turn (first evals))] (if (and t (> t 1)) t 1))
                {:keys [tile-projections walk-text walk-db tail-tile-blocks rule-trace fit-trace shown
                        surface-stats explained candidates]}
                (derive-wire-walk-prefix main-db branch-db conn ag anchor-turn opts)
                tail-res (assemble-unified-tail main-db branch-db ag evals
                         {:viewport-screenshot viewport-screenshot :vision? (boolean vision?) :now-ms (:now-ms opts)})
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
            (cond-> (assoc wire :agent ag :tiles tile-projections :rule-trace rule-trace
                           :fit-trace fit-trace :shown shown :surface-stats surface-stats
                           :tail-blocks tail-blocks :activity-text activity-text
                           :evals evals :messages-inbox msgs :unread unread)
              explained (assoc :explained explained :candidates candidates))))]
    (if ck (store/lru-get! wire-request-cache 8 ck compute) (compute))))