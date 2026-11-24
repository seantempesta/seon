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
            [seon.render.status :as rstatus]
            [seon.ctx.ground :as ground]
            [datahike.versioning :as dv]))

(defonce ^:seon.flow/edge wire-request-cache (store/lru-cache 8))

(defonce ^:private ^:seon.flow/edge walk-prefix-cache (store/lru-cache 16))

(def fallback-entity-renderers
"Pure `ns-map -> ns-map` fallback renderers (defined in `seon.render.tails`)."
rtails/fallback-entity-renderers)

(defn- gather-scoped-health-data [main-db branch-db ag evals branch-status]
  (when-let [scope (and main-db (:seon.agent/scope ag))]
    {:scope scope
     :problems (health/fast-scoped-alerts main-db branch-db scope (:seon.agent/id ag) evals
                                          {:branch-edited (:branch-edited branch-status)})}))

(defn neighborhood-recent-activity
  "Renders neighborhood activity through the unified pure Hiccup tail pipeline."
  {:malli/schema [:=> [:cat :any :any :map] [:maybe :string]] :cost/ticks 5000000}
  [main-db branch-db ag]
  (when-let [res (rtails/render-activity-tail {:seon.tail/activity (rtails/gather-activity-data main-db branch-db ag)})]
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
                        (some fit/resolve-renderer-var (fit/discover-renderers walk-db [sk]))
                        (and main-db (not (identical? main-db walk-db)) (some fit/resolve-renderer-var (fit/discover-renderers main-db [sk]))))
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
        flag (fn [k d] (cond (contains? opts k) (boolean (get opts k)) (contains? prefs k) (boolean (get prefs k)) :else d))
        eff-disabled (into #{} (concat (when (coll? (:disabled-rules prefs)) (:disabled-rules prefs))
                                       (when (coll? (:disabled-rules opts)) (:disabled-rules opts))))
        {:keys [candidates ctx-trace]}
        (ctx/gather-agent-candidates main-db walk-db (:seon.agent/id ag)
                                     {:agent ag :task-db task-db :target :wire
                                      :include-evals? false :include-ctx-snapshot? true
                                      :health-summary-fn fast-health-summary
                                      :disabled-rules eff-disabled :minimal? minimal?
                                      :semantic? (flag :semantic? (not minimal?))
                                      :enrich? (not minimal?)
                                      :situational? (flag :situational? (not minimal?))
                                      :rich-ns? (flag :rich-ns? (not minimal?))})
        items (ensure-fallback-candidates main-db walk-db ag candidates)]
    {:all-items items :walk-db walk-db :ctx-trace ctx-trace
     :rule-trace {:rules-count (long (or (:rules-total ctx-trace) 0))
                  :facts-count (count items)
                  :ms (reduce + 0.0 (map #(double (or (:ms %) 0.0)) (:fired-rules ctx-trace)))
                  :runs (vec (:fired-rules ctx-trace))
                  :recomputed (long (or (:recomputed ctx-trace) 0))
                  :skipped (long (or (:skipped ctx-trace) 0))}}))

(defonce ^:private ^:seon.flow/edge branch-errors-cache (store/lru-cache 32))

(defn- gather-branch-status-data [main-db branch-db ag evals]
  (let [branch (:seon.agent/branch ag :main) aid (:seon.agent/id ag)]
    (when (and main-db branch-db (not= branch :main))
      (let [mcid (live-cid main-db) bcid (live-cid branch-db)
            turns (if (seq evals) (count (distinct (keep :seon.agent.eval/turn evals))) 0)
            max-t (if (pos? turns)
                    (long (or (when (and aid ((db-schema main-db) :seon.config/max-tool-turns))
                                (store/config-val main-db aid :seon.config/max-tool-turns)) 32))
                    32)
            rem-t (when (pos? max-t) (max 0 (- max-t turns)))
            show-b? (and (pos? max-t) (pos? turns) (or (>= turns 10) (<= rem-t 12)))]
        (when (or show-b? (not= mcid bcid) (some? (:seon.agent/forked-from ag)))
          (let [same? (and mcid bcid (= mcid bcid))
                eids (if same? [] (rules/edited-form-eids main-db branch-db))
                {:keys [edited hashes drifted]} (if same? {:edited [] :hashes {} :drifted []}
                                                    (ground/branch-form-drift main-db branch-db eids))
                nss (vec (distinct (keep #(let [p (first (str/split (str %) #"[#/]" 2))]
                                            (when-not (str/blank? p) (symbol p))) edited)))]
            {:branch branch :max-tx (:max-tx branch-db) :branch-edited edited :main-drifted drifted
             :branch-errors (when (seq edited)
                              (let [esig (mapv (fn [k] [k (get hashes k)]) (sort edited))]
                                (store/lru-get! branch-errors-cache 32
                                                [(get-in main-db [:config :store :id]) mcid esig]
                                                #(health/caused-by-branch main-db branch-db {} nss))))
             :last-proofs (or (:seon.agent/last-proofs ag)
             (when-let [ff (:seon.agent/forked-from ag)]
               (let [feid (if (map? ff) (:db/id ff) ff)]
                 (when (integer? feid)
                   (:seon.agent/last-proofs (d/pull branch-db '[:seon.agent/last-proofs] feid)))))) :show-budget? show-b?
             :turns-used turns :max-turns max-t :rem-turns rem-t}))))))

(def ^:private tail-specs
  [{:roles :all    :key :seon.tail/inbox         :render rtails/render-inbox-tail
    :data (fn [_ _ _ _ opts] (when-let [u (seq (:unread opts))] {:unread u}))}
   {:roles :router :key :seon.tail/system-health :render rtails/render-system-health-tail
    :data (fn [mdb _ _ _ _] (when mdb (sig/all-signals mdb)))}
   {:roles :all    :key :seon.tail/compacted-history :render rtails/render-compacted-history-tail
    :data (fn [_ bdb ag _ _] (rstatus/compacted-turns-info bdb (:seon.agent/id ag)))}
   {:roles :worker :key :seon.tail/branch-status :render rtails/render-branch-status-tail
    :data (fn [_ _ _ _ opts] (:branch-status opts))}
   {:roles :worker :key :seon.tail/scoped-health :render rtails/render-scoped-health-tail
    :data (fn [mdb bdb ag evals opts] (gather-scoped-health-data mdb bdb ag evals (:branch-status opts)))}
   {:roles :all    :key :seon.tail/activity      :render rtails/render-activity-tail
    :data (fn [mdb bdb ag _ _] (rtails/gather-activity-data mdb bdb ag))}
   {:roles :all    :key :seon.tail/wait-nudge    :render rtails/render-wait-nudge-tail
    :data (fn [mdb bdb ag evals opts]
            (when (or (not (contains? opts :messages)) (seq (:messages opts)))
              (rstatus/compute-wait-nudge-data mdb bdb ag evals (:now-ms opts))))}
   {:roles :all    :key :seon.tail/viewport      :render rtails/render-viewport-tail
    :data (fn [mdb _ ag _ opts]
            (when-let [vp (:viewport-screenshot opts)]
              (let [cfg? (and mdb ((db-schema mdb) :seon.config/viewport-screenshot?))
                    en? (cond (:force-vision? opts) true
                              cfg? (boolean (store/config-val mdb (:seon.agent/id ag) :seon.config/viewport-screenshot?))
                              :else (boolean (:vision? opts true)))]
                {:snapshot vp :enabled? en? :vision? (boolean (:vision? opts true))})))}
   {:roles :router :key :seon.tail/chronicle     :render rtails/render-chronicle-tail
    :data (fn [mdb _ _ _ _] (rtails/gather-chronicle-data mdb))}])

(defn- assemble-unified-tail [main-db branch-db ag evals opts]
  (let [role (if (rdata/router-agent? ag) :router :worker)
        o (assoc opts
                 :now-ms (long (or (:now-ms opts) (System/currentTimeMillis)))
                 :branch-status (when (= role :worker) (gather-branch-status-data main-db branch-db ag evals)))
        blocks (->> tail-specs
                    (filter #(or (= :all (:roles %)) (= role (:roles %))))
                    (keep (fn [{:keys [key render data]}]
                            (when-let [v (data main-db branch-db ag evals o)]
                              (let [rf (or (some-> (and main-db (first (fit/discover-renderers main-db [key]))) requiring-resolve)
                                           render)]
                                (rtails/tail-block-from-res (rf {key v}))))))
                    (sort-by #(if (:image %) 1 0))
                    vec)]
    {:blocks blocks :image (some :image blocks)}))

(defn- lean-wire-item
  "Strips large re-fetchable source attributes (`:seon.code.form/src`, `:seon.code.form/form`)
   from a wire candidate's `:data` so it can be retained in the bounded `walk-prefix-cache`
   without accumulating megabytes of nested source strings (cold-OOM guard)."
  {:cost/ticks 500000}
  [item]
  (if (and (map? item) (map? (:data item)))
    (update item :data dissoc :seon.code.form/src :seon.code.form/form)
    item))

(defn- fit-wire-pool [{:keys [all-items walk-db rule-trace]} main-db opts]
  (let [budget (long (or (:token-budget opts) 16000))
        tail-budget (min budget (long (or (:tail-token-budget opts) 2000)))
        strategy (or (:strategy opts) :depth-first)
        base-opts (cond-> {:target :wire :strategy strategy :main-db main-db
               :already-shown (:already-shown opts) :weights (:weights opts)
               :fallback-renderers fallback-entity-renderers}
        (integer? (:turn opts)) (assoc :turn (:turn opts)))
        explain? (boolean (:explain? opts))
        ent-by-id (when explain? (into {} (map (juxt :entity :data)) all-items))
        run-fit (fn [items b]
                  (if (empty? items)
                    {:selected [] :dropped [] :items [] :tokens 0 :budget b :strategy strategy :shown {}}
                    (let [fo (assoc base-opts :token-budget b)
                          res (if explain?
                                (fit/explain-candidates walk-db items fo)
                                (fit/fit-projections walk-db items fo))
                          sel (if explain?
                                (mapv (fn [it]
                                        (let [win (some #(when (:chosen? %) %) (:variations it))]
                                          (assoc it :data (get ent-by-id (:entity it))
                                                 :hiccup (:hiccup win)
                                                 :text (if (= :unchanged (:status it)) "" (str (:text win))))))
                                      (:selected res))
                                (mapv #(assoc % :text (if (= :unchanged (:status %)) "" (render/hiccup->text (:hiccup %))))
                                      (:selected res)))]
                      (assoc res :selected sel))))
        prefix-items (filterv #(= :agent-prefix (:zone %)) all-items)
        pref-items (filterv #(and (not= :ephemeral-tail (:zone %)) (not= :agent-prefix (:zone %))) all-items)
        tail-items (filterv #(= :ephemeral-tail (:zone %)) all-items)
        pref-res (run-fit pref-items budget)
        tail-res (run-fit tail-items tail-budget)
        selected (into (vec (:selected pref-res)) (:selected tail-res))
        dropped (into (vec (:dropped pref-res)) (:dropped tail-res))
        tot-tok (+ (long (:tokens pref-res 0)) (long (:tokens tail-res 0)))
        shown (merge (:shown pref-res) (:shown tail-res))
        fmt-p (fn [{:keys [schema source handle layout chosen-fn text]}]
                (str "### [" (name (or source :rule)) (when handle (str " " handle))
                     " · " (or schema chosen-fn :entity)
                     (when (and layout (not= layout :expanded-card)) (str " · " (name layout))) "]\n" text))
        pref-sel (filterv #(not= :unchanged (:status %)) (:selected pref-res))
        tail-sel (filterv #(not= :unchanged (:status %)) (:selected tail-res))
        explained (when explain?
                    {:target :wire :strategy strategy :budget budget :tail-budget tail-budget
                     :tokens tot-tok :selected-count (count selected) :dropped-count (count dropped)
                     :items (into (vec (:items pref-res)) (:items tail-res))
                     :selected selected :dropped dropped})]
    (cond-> {:tile-projections selected
             :candidates (mapv lean-wire-item all-items)
             :prefix-items prefix-items
             :walk-text (str/join "\n\n---\n\n" (map fmt-p pref-sel))
             :walk-db walk-db
             :tail-tile-blocks (when (seq tail-sel)
                                 [{:tag "rule-tail"
                                   :hiccup (into [:section {:class "tail-block"}] (keep :hiccup tail-sel))
                                   :text (str/join "\n\n---\n\n" (map fmt-p tail-sel))}])
             :rule-trace rule-trace
             :fit-trace {:tokens tot-tok :tail-tokens (long (:tokens tail-res 0)) :tail-budget tail-budget
                         :budget budget :strategy strategy
                         :selected-count (count selected) :dropped-count (count dropped)
                         :items (mapv #(select-keys % [:entity :id :schema :zone :status :chosen-fn :tokens
                                                       :measured-tokens :rank/score :rank/signals :candidates :tx])
                                      selected)}
             :shown shown
             :surface-stats {:selected (count selected) :dropped (count dropped)
                             :unchanged (count (filter #(= :unchanged (:status %)) selected))
                             :tokens tot-tok :tail-tokens (long (:tokens tail-res 0)) :budget budget}}
      explained (assoc :explained explained))))

(defn- derive-wire-walk-prefix
{:seon.flow/edge true}
[main-db branch-db conn ag anchor-turn opts]
(let [branch (:seon.agent/branch ag :main)
      prefs (if (map? (:seon.agent/context-prefs ag)) (:seon.agent/context-prefs ag) {})
      pref-fit (select-keys prefs [:token-budget :strategy :weights])
      read-main? (or (= :main (:seon.agent/read-branch ag)) (:seon.agent/router? ag))
      store-or-conn (or conn (:store branch-db))
      reset-tx (rstatus/branch-reset-tx (if read-main? main-db branch-db) (:seon.agent/id ag))
      anchor-cid (cond (and (pos? reset-tx) (not read-main?) (not= branch :main))
      (or (try (:commit (dv/commit-holding-tx branch-db reset-tx)) (catch Throwable _ nil)) (live-cid branch-db))
      (and (:seon.agent/forked-from ag) (not read-main?) (empty? (:datahike/uncommitted (meta branch-db))))
      (let [ag-tx (or (:tx (first (d/datoms branch-db :avet :seon.agent/id (:seon.agent/id ag)))) 0)]
        (when (pos? ag-tx)
          (try (:commit (dv/commit-holding-tx branch-db ag-tx)) (catch Throwable _ nil)))))
      base-cid (when (and (nil? anchor-cid) (nil? (:seon.agent/forked-from ag)) store-or-conn (not= branch :main) (not read-main?)
                        (contains? (try (d/branches store-or-conn) (catch Throwable _ nil)) branch))
               (try (d/merge-base store-or-conn :main branch) (catch Throwable _ nil)))
      eff-cid (cond
                (seq (:already-shown opts))
                (let [wdb (if read-main? main-db branch-db)] [(live-cid wdb) (:max-tx wdb)])
                anchor-cid anchor-cid
                base-cid base-cid
                (pos? reset-tx) [:main-reset reset-tx]
                :else :main-session)
      task-ids (vec (sort-by str (distinct (concat (rdata/agent-task-ids branch-db ag) (rdata/agent-task-ids main-db ag)))))
      task? (boolean (and (seq task-ids)
                          (some (fn [tid] (first (d/datoms branch-db :avet :seon.task/id tid))) task-ids)))
      dis-key (when (seq (:disabled-rules opts)) (vec (sort (:disabled-rules opts))))
      walk-key (let [msig (rules/rules-db-sig main-db)
                     bsig (if (identical? main-db branch-db) msig (rules/rules-db-sig branch-db))]
                 [(get-in main-db [:config :store :id]) (:seon.agent/id ag) eff-cid branch reset-tx
                 msig bsig
                 (:seon.agent/router? ag) (:seon.agent/scope ag) task-ids (:seon.agent/goal ag)
                 (vec (:seon.agent/pinned ag)) (:seon.agent/summary ag) (vec (:seon.agent/curated ag))
                 (:seon.agent/context-prefs ag) (:seon.agent/forked-from ag) (:seon.agent/fork-commit ag)
                 (vec (sort-by pr-str (:seon.agent/examples ag))) (vec (sort-by pr-str (:seon.agent/example-refs ag)))
                 anchor-turn task? dis-key])
      cached (store/lru-get!
              walk-prefix-cache 64 walk-key
              (fn []
                (let [anchor-db (when anchor-cid
                                  (if (= anchor-cid (live-cid branch-db))
                                    branch-db
                                    (try (d/commit-as-db store-or-conn anchor-cid) (catch Throwable _ branch-db))))
                      base-db (when (and (nil? anchor-db) store-or-conn base-cid)
                                (if (= base-cid (live-cid main-db))
                                  main-db
                                  (try (d/commit-as-db store-or-conn base-cid) (catch Throwable _ nil))))
                      scope (:seon.agent/scope ag)
                      scope-in-base? (or (nil? scope)
                                         (and base-db (boolean (first (d/datoms base-db :avet :seon.code.ns/name scope)))))
                      walk-db (cond read-main? main-db
                                    anchor-db anchor-db
                                    (and base-db scope-in-base?) base-db
                                    :else branch-db)
                      pool (build-wire-candidate-pool main-db walk-db (if task? branch-db main-db) ag opts)]
                  {:pool pool :walk-main-cid (live-cid main-db) :default-fit (delay (assoc (fit-wire-pool pool main-db pref-fit) :walk-main-cid (live-cid main-db)))})))
      default-opts? (and (empty? (:already-shown opts))
                         (nil? (:token-budget opts))
                         (nil? (:strategy opts))
                         (nil? (:weights opts))
                         (not (:explain? opts)))]
  (if (:defer-fit? opts)
  {:pool (:pool cached)
   :walk-main-cid (:walk-main-cid cached)
   :pref-fit pref-fit
   :default-fit (:default-fit cached)
   :prefix-items (filterv #(= :agent-prefix (:zone %)) (:all-items (:pool cached)))}
  (assoc (if default-opts?
           @(:default-fit cached)
           (assoc (fit-wire-pool (:pool cached) main-db (merge pref-fit opts)) :walk-main-cid (:walk-main-cid cached)))
         :pool (:pool cached)))))

(defn render-agent-context
  "Renders the unified Upgrade-Ladder `<walk-context>` + Hiccup `<ephemeral-tail>` for any agent."
  {:malli/schema [:function [:=> [:cat :seon.host/db :seon.host/db :keyword] :string]
                  [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :string]]
   :cost/ticks 50000000}
  ([main-db branch-db agent-id] (render-agent-context main-db branch-db agent-id {}))
  ([main-db branch-db agent-id opts]
   (let [base-ag (or (rdata/pull-agent-entity main-db branch-db agent-id) {:seon.agent/id agent-id})
         base-tids (vec (distinct (concat (rdata/agent-task-ids branch-db base-ag) (rdata/agent-task-ids main-db base-ag))))
         tid (or (:task opts) (first base-tids))
         task-ids (vec (distinct (concat (when tid [tid]) base-tids)))
         task-scopes (into [] (keep (fn [t]
                                      (:seon.task/scope (or (d/pull branch-db '[:seon.task/scope] [:seon.task/id t])
                                                            (d/pull main-db '[:seon.task/scope] [:seon.task/id t])))))
                           task-ids)
         scope (or (:scope opts) (:seon.agent/scope base-ag) (first task-scopes))
         extra-nss (vec (distinct (concat (get-in base-ag [:seon.agent/context-prefs :extra-namespaces])
                                          (:extra-namespaces opts)
                                          (remove #(= % scope) task-scopes))))
         pinned (vec (distinct (concat (:seon.agent/pinned base-ag) (:pinned opts))))
         ag (cond-> base-ag (seq task-ids) (assoc :seon.agent/tasks (into #{} (keep #(or (:e (first (d/datoms branch-db :avet :seon.task/id %))) (:e (first (d/datoms main-db :avet :seon.task/id %))) [:seon.task/id %])) task-ids)) scope (assoc :seon.agent/scope scope)
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

(defn- live-rule-tail-blocks [main-db branch-db ag opts]
  (let [tail-rs (ctx/tail-rules-with-producers (ctx/load-context-rules main-db branch-db nil))]
    (when (seq tail-rs)
      (let [{:keys [candidates]} (ctx/gather-agent-candidates
                                  main-db branch-db (:seon.agent/id ag)
                                  (cond-> {:agent ag :target :wire :rules tail-rs :rules-tag :ephemeral-tail :minimal? true
                                          :zone-filter :ephemeral-tail :enrich? true
                                          :include-evals? false :include-ctx-snapshot? false}
                                    (seq (:disabled-rules opts)) (assoc :disabled-rules (:disabled-rules opts))))
            tail-cands (filterv #(= :ephemeral-tail (:zone %)) candidates)]
        (when (seq tail-cands)
          (:tail-tile-blocks (fit-wire-pool {:all-items (ensure-fallback-candidates main-db branch-db ag tail-cands)
                                             :walk-db branch-db :rule-trace {}}
                                            main-db opts)))))))

(defn build-live-wire-request
"ONE single source of truth for an agent's LLM context AND Context Lab:
 Datahike commit-id + `d/merge-base` KV-cache stability, ONE `seon.ctx/gather-agent-candidates`,
 recursive `:zone :agent-prefix` assembly (`:continue` forks + `:example` refs),
 `seon.render.fit/fit-projections`, and pure `ns-map -> ns-map` Hiccup ephemeral tail renderers."
{:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :map]
 :cost/ticks 60000000}
[main-db branch-db agent-id {:keys [viewport-screenshot vision? conn already-shown token-budget strategy
                                    disabled-rules weights explain? include-tail? depth] :as opts}]
(let [inc-tail? (not= false include-tail?)
      d0 (long (or depth 0))
      ck (when (and (empty? already-shown) (nil? token-budget) (nil? strategy) (empty? disabled-rules) (nil? weights) (not explain?) (nil? (:now-ms opts)))
         (when-let [k (wire-cache-key main-db branch-db agent-id vision? viewport-screenshot)] [k inc-tail? (>= d0 4)]))
      compute
      (fn []
        (let [ag (rdata/pull-agent-entity main-db branch-db agent-id)
              evals (rdata/agent-evals-in branch-db agent-id)
              msgs (rdata/agent-messages-in main-db agent-id)
              unread (if (and inc-tail? (seq msgs))
                       (let [seen-ids (store/agent-seen-message-ids branch-db agent-id)]
                         (filterv #(not (contains? seen-ids (:seon.agent.message/id %))) msgs))
                       [])
              branch (:seon.agent/branch ag :main)
              anchor-turn (or (when-let [t (:seon.agent.eval/turn (first evals))] (when (> t 1) t))
                              (when (and (empty? evals) (pos? (rstatus/branch-reset-tx branch-db agent-id)))
                                (max 1 (inc (long (:max-turn (store/agent-eval-history-stats branch-db agent-id) 0)))))
                              1)
              t-opt (assoc opts :turn (max 0 (dec (long anchor-turn))))
              w0 (derive-wire-walk-prefix main-db branch-db conn ag anchor-turn (assoc t-opt :defer-fit? true))
              pfx (when (seq (:prefix-items w0))
                    (rstatus/resolve-prefix-agents main-db branch-db conn ag (:prefix-items w0) d0 build-live-wire-request))
              {:keys [tile-projections walk-text walk-db walk-main-cid tail-tile-blocks rule-trace fit-trace shown
                      surface-stats explained candidates]}
              (if (and (empty? already-shown) (empty? (:parent-shown pfx)) (nil? token-budget) (nil? strategy) (nil? weights) (not explain?))
              @(:default-fit w0)
              (assoc (fit-wire-pool (:pool w0) main-db
                       (merge (:pref-fit w0) t-opt
                              (when (seq (:parent-shown pfx))
                                {:already-shown (merge (:parent-shown pfx) already-shown)})))
                     :walk-main-cid (:walk-main-cid w0)))
              manifest (when-let [fid (:forked-from pfx)] (rstatus/format-forked-eval-manifest walk-db fid))
              eff-walk (if manifest (str walk-text (when-not (str/blank? walk-text) "\n\n---\n\n") manifest) walk-text)
              tail-res (when inc-tail?
                         (assemble-unified-tail main-db branch-db ag evals
                           {:viewport-screenshot viewport-screenshot :vision? (boolean vision?)
                            :now-ms (:now-ms opts) :messages msgs :unread unread}))
              live-tail (when inc-tail?
                          (if (and (not explain?) (or (not= walk-main-cid (live-cid main-db)) (not= (live-cid walk-db) (live-cid branch-db))))
                            (live-rule-tail-blocks main-db branch-db ag opts)
                            tail-tile-blocks))
              tail-blocks (when inc-tail? (vec (concat (:blocks tail-res) live-tail)))
              activity-text (when inc-tail? (not-empty (str/join "\n\n" (keep :text tail-blocks))))
              wire (model/build-agent-wire-messages
                    {:thinking? true :replay-reasoning? true :vision? (boolean vision?) :out-max-chars (store/config-val main-db agent-id :seon.config/eval-out-chars) :format-tool-fn rstatus/cached-format-tool-content}
                    {:agent-id agent-id :branch branch :router? (boolean (:seon.agent/router? ag))
                     :goal (:seon.agent/goal ag) :system-prompt (:seon.agent/system-prompt ag)
                     :prefix-agents (:entries pfx) :forked-from (:forked-from pfx) :include-tail? inc-tail?
                     :walk-text eff-walk :tail-blocks tail-blocks :ephemeral-image (:image tail-res)
                     :history (rdata/evals->turns evals (into {} (map (juxt :seon.agent.message/id identity)) msgs))
                     :unread unread :eval-db walk-db :main-db main-db :fallback-db branch-db
                     :viewport-screenshot viewport-screenshot})]
          (cond-> (assoc wire :agent ag :tiles tile-projections :candidates candidates :rule-trace rule-trace
                       :fit-trace fit-trace :shown (merge (:parent-shown pfx) shown) :surface-stats surface-stats
                       :tail-blocks (or tail-blocks []) :activity-text activity-text
                       :evals evals :messages-inbox msgs :unread unread)
            explained (assoc :explained explained))))]
  (if ck (store/lru-get! wire-request-cache 8 ck compute) (compute))))