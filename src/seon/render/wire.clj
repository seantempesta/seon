(ns seon.render.wire
  "Single-source LLM wire context & unified KV-cache `<context-delta>` builder over ONE `seon.ctx/project-entity`."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.ctx.diff :as diff]
            [seon.model :as model]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.entities]
            [seon.render.entities.infra]
            [seon.render.entities.work]
            [seon.render.fit :as fit]
            [seon.render.links :refer [db-schema live-cid]]
            [seon.render.status :as rstatus]
            [seon.rule :as rules]
            [seon.store :as store]))

(defonce ^:seon.flow/edge wire-request-cache (store/lru-cache 64))

(defonce ^:private ^:seon.flow/edge walk-prefix-cache (store/lru-cache 64))

(def fallback-entity-renderers
  "Re-exported canonical schema -> default pure Hiccup renderer fallback map."
  fit/fallback-entity-renderers)

(defn wire-cache-key
  "Commit-id cache key for `build-live-wire-request`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :any :any] :any]
   :cost/ticks 500000}
  [main-db branch-db agent-id vision? viewport-screenshot]
  (let [mcid (live-cid main-db)
        bcid (live-cid branch-db)]
    (when (and mcid bcid)
      [(get-in main-db [:config :store :id]) agent-id mcid (:max-tx main-db)
       (if (= mcid bcid) :same-db [bcid (:max-tx branch-db)]) (boolean vision?)
       (when viewport-screenshot
         [(:width viewport-screenshot) (:height viewport-screenshot) (:bytes viewport-screenshot)
          (:captured-at viewport-screenshot) (hash (:data-url viewport-screenshot))
          (hash (:base64 viewport-screenshot))])])))

(defn- fmt-wire-tile [{:keys [schema source handle layout chosen-fn text]}]
  (str "### [" (name (or source :rule)) (when handle (str " " handle))
       " · " (or schema chosen-fn :entity)
       (when (and layout (not= layout :expanded-card)) (str " · " (name layout))) "]\n" text))

(defn- lean-wire-item [item]
  (if (and (map? item) (map? (:data item)))
    (update item :data dissoc :seon.code.form/src :seon.code.form/form)
    item))

(defn- render-selected-text [walk-db main-db selected explain? ent-by-id]
  (mapv (fn [it]
          (if explain?
            (let [win (some #(when (:chosen? %) %) (:variations it))]
              (assoc it
                     :data (get ent-by-id (:entity it))
                     :hiccup (:hiccup win)
                     :text (if (= :unchanged (:status it)) "" (str (:text win)))))
            (let [txt (cond
                        (= :unchanged (:status it)) ""
                        (some? (:hiccup it)) (render/hiccup->text (:hiccup it))
                        (and (map? (:data it)) (seq (dissoc (:data it) :db/id)))
                        (let [d (:data it) sk (:schema it)
                              val (if sk (with-meta d {:seon/schema sk}) d)
                              vh (render/render-value walk-db val {:mode :agent :main-db main-db})
                              bh (when sk (render/render-schema-breadcrumbs walk-db sk val {:mode :agent :main-db main-db}))]
                          (render/hiccup->text (if bh [:div vh bh] vh)))
                        :else "")]
              (assoc it :text txt))))
        selected))

(defn- project-wire [main-db walk-db ag opts]
  (let [prefs (if (map? (:seon.agent/context-prefs ag)) (:seon.agent/context-prefs ag) {})
        budget (long (or (:token-budget opts) (:token-budget prefs) 16000))
        per-item (long (or (:per-item-budget opts) 4000))
        strategy (or (:strategy opts) (:strategy prefs) :depth-first)
        explain? (boolean (:explain? opts))
        res (ctx/project-entity
             main-db walk-db [:seon.agent/id (:seon.agent/id ag)]
             (merge {:agent ag :target :wire :token-budget budget :per-item-budget per-item
                     :strategy strategy :include-unmatched? false :include-focal-fit? false :include-rule-trace? false
                     :include-evals? false :fallback-renderers fallback-entity-renderers}
                    (select-keys opts [:task-db :turn :already-shown :disabled-rules :weights
                                       :explain? :semantic? :enrich? :use-live-touches? :use-cache?])))
        all-cands (:candidates res)
        fitted (:fitted res)
        ent-by-id (when explain? (into {} (map (juxt :entity :data)) all-cands))
        selected (render-selected-text walk-db main-db (:selected fitted) explain? ent-by-id)
        dropped (vec (:dropped fitted))
        active (filterv #(not= :unchanged (:status %)) selected)
        ctx-trace (:ctx-trace res)
        rtrace {:rules-count (long (or (:rules-total ctx-trace) 0))
                :facts-count (count all-cands)
                :ms (reduce + 0.0 (map #(double (or (:ms %) 0.0)) (:fired-rules ctx-trace)))
                :runs (vec (:fired-rules ctx-trace))
                :recomputed (long (or (:recomputed ctx-trace) 0))
                :skipped (long (or (:skipped ctx-trace) 0))}
        tot-tok (long (:tokens fitted 0))
        explained (when explain?
                    {:target :wire :strategy strategy :budget budget :tokens tot-tok
                     :selected-count (count selected) :dropped-count (count dropped)
                     :items (vec (:items fitted)) :selected selected :dropped dropped})]
    (cond-> {:tile-projections selected :active-tiles active :candidates (mapv lean-wire-item all-cands)
             :prefix-items (filterv #(= :agent-prefix (:zone %)) all-cands)
             :walk-text (str/join "\n\n---\n\n" (map fmt-wire-tile active))
             :walk-db walk-db :rule-trace rtrace
             :fit-trace {:tokens tot-tok :budget budget :strategy strategy
                         :selected-count (count selected) :dropped-count (count dropped)
                         :items (mapv #(select-keys % [:entity :id :schema :zone :status :chosen-fn :tokens
                                                       :measured-tokens :rank/score :rank/signals :candidates :tx]) selected)}
             :shown (or (:shown fitted) {})
             :surface-stats {:selected (count selected) :dropped (count dropped)
                             :unchanged (count (filter #(= :unchanged (:status %)) selected))
                             :tokens tot-tok :budget budget}}
      explained (assoc :explained explained))))

(defn- resolve-walk-db [main-db branch-db conn ag]
  (let [branch (:seon.agent/branch ag :main)
        read-main? (rdata/reads-main? ag)
        store-or-conn (or conn (:store branch-db))
        reset-tx (rstatus/branch-reset-tx (if read-main? main-db branch-db) (:seon.agent/id ag))
        anchor-cid (cond
                     (and (pos? reset-tx) (not read-main?) (not= branch :main))
                     (or (:commit (diff/commit-holding-tx-cached branch-db reset-tx)) (live-cid branch-db))
                     (and (:seon.agent/forked-from ag) (not read-main?) (empty? (:datahike/uncommitted (meta branch-db))))
                     (let [ag-tx (or (:tx (first (d/datoms branch-db :avet :seon.agent/id (:seon.agent/id ag))))
                                    (some #(:tx (first (d/datoms branch-db :avet :seon.task/id %)))
                                    (rdata/agent-task-ids main-db ag))
                                    0)]
                     (or (when (pos? ag-tx) (:commit (diff/commit-holding-tx-cached branch-db ag-tx)))
                         (:seon.agent/fork-commit ag))))
        base-cid (when (and (nil? anchor-cid) (nil? (:seon.agent/forked-from ag)) (not= branch :main) (not read-main?))
                   (diff/fast-merge-base main-db branch-db))]
    {:read-main? read-main? :reset-tx reset-tx :anchor-cid anchor-cid :base-cid base-cid :store-or-conn store-or-conn}))

(defn- ^:seon.flow/edge derive-wire-walk-prefix [main-db branch-db conn ag anchor-turn opts]
  (let [branch (:seon.agent/branch ag :main)
        {:keys [read-main? reset-tx anchor-cid base-cid store-or-conn]} (resolve-walk-db main-db branch-db conn ag)
        eff-cid (cond anchor-cid anchor-cid base-cid base-cid (pos? reset-tx) [:main-reset reset-tx] (seq (:already-shown opts)) (let [wdb (if read-main? main-db branch-db)] [(live-cid wdb) (:max-tx wdb)]) :else :main-session)
        task-ids (vec (sort-by str (distinct (concat (rdata/agent-task-ids branch-db ag) (rdata/agent-task-ids main-db ag)))))
        task? (boolean (and (seq task-ids) (some #(first (d/datoms branch-db :avet :seon.task/id %)) task-ids)))
        task-sig (when (and (seq task-ids) (or (= eff-cid :main-session) (= branch :main)))
                   (let [tdb (if task? branch-db main-db)]
                     (mapv #(when-let [eid (:e (first (d/datoms tdb :avet :seon.task/id %)))]
                              (d/pull tdb '[:seon.task/title :seon.task/description :seon.task/plan
                                            :seon.task/summary :seon.task/done? :seon.task/scope] eid))
                           task-ids)))
        dis-key (when (seq (:disabled-rules opts)) (vec (sort (:disabled-rules opts))))
        msig (rules/rules-db-sig main-db)
        bsig (if (identical? main-db branch-db) msig (rules/rules-db-sig branch-db))
        walk-key [(get-in main-db [:config :store :id]) (:seon.agent/id ag) eff-cid branch reset-tx msig bsig
                  (:seon.agent/scope ag) task-ids task-sig (:seon.agent/goal ag)
                  (vec (:seon.agent/pinned ag)) (when-not (:seon.agent/forked-from ag) (:seon.agent/summary ag))
                  (vec (:seon.agent/curated ag)) (:seon.agent/context-prefs ag) (:seon.agent/forked-from ag)
                  (:seon.agent/fork-commit ag) (vec (sort-by pr-str (:seon.agent/examples ag)))
                  (vec (sort-by pr-str (:seon.agent/example-refs ag))) anchor-turn task? dis-key]
        cached (store/lru-get! walk-prefix-cache 64 walk-key
                               (fn []
                                 (let [anchor-db (when anchor-cid
                                                   (if (= anchor-cid (live-cid branch-db)) branch-db
                                                       (try (d/commit-as-db store-or-conn anchor-cid) (catch Throwable _ branch-db))))
                                       base-db (when (and (nil? anchor-db) store-or-conn base-cid)
                                                 (if (= base-cid (live-cid main-db)) main-db
                                                     (try (d/commit-as-db store-or-conn base-cid) (catch Throwable _ nil))))
                                       scope (:seon.agent/scope ag)
                                       scope-in-base? (or (nil? scope) (and base-db (boolean (first (d/datoms base-db :avet :seon.code.ns/name scope)))))
                                       walk-db (cond read-main? main-db anchor-db anchor-db (and base-db scope-in-base?) base-db :else branch-db)
                                       walk-main (if (and (not read-main?) (or (some? anchor-db) (and (some? base-db) scope-in-base?))
                                                        (first (d/datoms walk-db :avet :seon.agent/id (:seon.agent/id ag))))
                                                 walk-db main-db)
                                       tdb (cond (and task? (some #(first (d/datoms walk-db :avet :seon.task/id %)) task-ids)) walk-db task? branch-db :else main-db)]
                                   {:walk-db walk-db :walk-main walk-main :task-db tdb :walk-main-cid (live-cid walk-main)
                                    :default-fit (delay (project-wire walk-main walk-db ag {:task-db tdb :turn (max 0 (dec (long anchor-turn)))
                                                                                            :use-live-touches? false}))})))
        dflt? (and (empty? (:already-shown opts)) (nil? (:token-budget opts)) (nil? (:per-item-budget opts))
                   (nil? (:strategy opts)) (nil? (:weights opts)) (empty? (:disabled-rules opts)) (not (:explain? opts)))]
    (assoc (if dflt?
             @(:default-fit cached)
             (project-wire (or (:walk-main cached) main-db) (:walk-db cached) ag
                           (assoc opts :task-db (:task-db cached) :turn (max 0 (dec (long anchor-turn))) :use-live-touches? false)))
           :walk-main-cid (:walk-main-cid cached)
           :walk-db (:walk-db cached)
           :task-db (:task-db cached))))

(defn- build-viewport-block [main-db ag viewport-screenshot vision? force-vision?]
  (when viewport-screenshot
    (let [cfg? (and main-db ((db-schema main-db) :seon.config/viewport-screenshot?))
          en? (cond force-vision? true
                    cfg? (boolean (store/config-val main-db (:seon.agent/id ag) :seon.config/viewport-screenshot?))
                    :else (boolean vision?))
          dom (when-let [d (:dom viewport-screenshot)]
                (when (seq d) (str "\nDOM: " (pr-str d))))]
      (when en?
        (cond-> {:tag "live-viewport"
                 :attrs {:route (or (:route viewport-screenshot) "/")
                         :size (str (or (:width viewport-screenshot) 1280) "x" (or (:height viewport-screenshot) 820))}
                 :text (str "Current live WKWebView screenshot of what the user sees right now." dom)}
          (and vision? (:base64 viewport-screenshot))
          (assoc :image {:mime (or (:mime viewport-screenshot) "image/jpeg")
                         :base64 (:base64 viewport-screenshot)
                         :raw viewport-screenshot}))))))

(defn- build-live-delta-blocks [main-db branch-db ag cur-turn prev-shown opts]
  (let [delta-res (project-wire main-db branch-db ag
                                (merge (select-keys opts [:disabled-rules :weights])
                                       {:token-budget (long (or (:delta-token-budget opts) (:tail-token-budget opts) 4000))
                                        :per-item-budget (long (or (:delta-per-item-budget opts) 1500))
                                        :strategy (or (:delta-strategy opts) :breadth-first)
                                        :turn (long (max 1 cur-turn))
                                        :already-shown prev-shown
                                        :use-live-touches? true}))
        active (:active-tiles delta-res)
        msg-eids (into #{} (comp (filter #(= :seon.agent.message/entity (:schema %))) (keep :entity)) active)]
    {:blocks (when (seq active)
               [{:tag "context-delta"
                 :contains-unread? (boolean (seq msg-eids))
                 :hiccup (into [:section {:class "tail-block context-delta"}] (keep :hiccup active))
                 :text (str/join "\n\n---\n\n" (map fmt-wire-tile active))}])
     :delta-tiles (:tile-projections delta-res)
     :delta-active active
     :delta-shown (:shown delta-res)
     :delta-tokens (long (get-in delta-res [:fit-trace :tokens] 0))
     :rendered-msg-eids msg-eids}))

(defn render-agent-context
  "Renders `<walk-context>` for any agent through ONE `seon.ctx/project-entity` pipeline."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :seon.host/db :keyword] :string]
                  [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :string]]
   :cost/ticks 50000000}
  ([main-db branch-db agent-id] (render-agent-context main-db branch-db agent-id {}))
  ([main-db branch-db agent-id opts]
   (let [base-ag (or (rdata/pull-agent-entity main-db branch-db agent-id) {:seon.agent/id agent-id})
         base-tids (vec (distinct (concat (rdata/agent-task-ids branch-db base-ag) (rdata/agent-task-ids main-db base-ag))))
         tid (or (:task opts) (first base-tids))
         task-ids (vec (distinct (concat (when tid [tid]) base-tids)))
         task-scopes (into [] (keep (fn [t] (:seon.task/scope (or (d/pull branch-db '[:seon.task/scope] [:seon.task/id t])
                                                                  (d/pull main-db '[:seon.task/scope] [:seon.task/id t]))))) task-ids)
         scope (or (:scope opts) (:seon.agent/scope base-ag) (first task-scopes))
         extra-nss (vec (distinct (concat (get-in base-ag [:seon.agent/context-prefs :extra-namespaces])
                                          (:extra-namespaces opts) (remove #(= % scope) task-scopes))))
         pinned (vec (distinct (concat (:seon.agent/pinned base-ag) (:pinned opts))))
         ag (cond-> base-ag
              (seq task-ids) (assoc :seon.agent/tasks (into #{} (keep #(or (:e (first (d/datoms branch-db :avet :seon.task/id %)))
                                                                           (:e (first (d/datoms main-db :avet :seon.task/id %)))
                                                                           [:seon.task/id %])) task-ids))
              scope (assoc :seon.agent/scope scope)
              (seq extra-nss) (assoc-in [:seon.agent/context-prefs :extra-namespaces] extra-nss)
              (seq pinned) (assoc :seon.agent/pinned pinned))
         tdb (if (and tid (first (d/datoms branch-db :avet :seon.task/id tid))) branch-db main-db)
         {:keys [walk-text]} (project-wire main-db branch-db ag (assoc opts :task-db tdb))]
     (str "<walk-context>\n" walk-text "\n</walk-context>"))))

(defn- build-budget-block [main-db branch-db ag cur-turn evals]
  (when (and (seq evals) (not (:seon.agent/interactive? ag)))
    (let [max-tt (long (or (store/config-val main-db (:seon.agent/id ag) :seon.config/max-tool-turns) 32))]
      (when (pos? max-tt)
        (let [rem-t (max 0 (- max-tt (long cur-turn)))
              chgs (diff/branch-changes main-db branch-db)
              n-edits (count (into #{}
                                   (keep (fn [dt]
                                           (let [a (:a dt)
                                                 ns-s (when (keyword? a) (namespace a))]
                                             (when (and ns-s (or (str/starts-with? ns-s "seon.code.")
                                                                 (= ns-s "seon.schema")
                                                                 (= ns-s "seon.rule")))
                                               (:e dt)))))
                                   chgs))]
          {:tag "session-budget"
           :text (str "Turn " cur-turn " / " max-tt " (" rem-t " left) · branch code/schema/rule edits: " n-edits
                      (when (and (zero? n-edits) (>= (long cur-turn) (max 2 (quot max-tt 2))))
                        " · halfway through budget with 0 persisted edits — begin implementing and testing now"))})))))

(defn- resolve-merge-target-id [main-db branch-db ag]
  (when-let [mt (:seon.agent/merge-target ag)]
    (let [aid (cond
                (qualified-keyword? mt) mt
                (and (vector? mt) (= :seon.agent/id (first mt))) (second mt)
                (map? mt) (or (:seon.agent/id mt)
                              (when (integer? (:db/id mt))
                                (or (:seon.agent/id (d/pull branch-db '[:seon.agent/id] (:db/id mt)))
                                    (:seon.agent/id (d/pull main-db '[:seon.agent/id] (:db/id mt))))))
                (integer? mt) (or (:seon.agent/id (d/pull branch-db '[:seon.agent/id] mt))
                                  (:seon.agent/id (d/pull main-db '[:seon.agent/id] mt)))
                :else nil)]
      (when (and (qualified-keyword? aid) (not= aid (:seon.agent/id ag))) aid))))

(defn- build-target-preview-block [main-db branch-db ag evals depth]
  (when (and (zero? (long depth)) (seq evals))
    (when-let [taid (resolve-merge-target-id main-db branch-db ag)]
      (let [tag (or (rdata/pull-agent-entity main-db branch-db taid) {:seon.agent/id taid})
            tevs (rdata/agent-evals-in branch-db taid)
            pw (project-wire main-db branch-db tag {:use-live-touches? false})
            m-tag (when (not (identical? main-db branch-db))
                    (rdata/pull-agent-entity main-db main-db taid))
            mpw (when m-tag (project-wire main-db main-db m-tag {:use-live-touches? false}))
            tiles (:active-tiles pw)
            tkey (fn [t] (or (:handle t) (:id t) (:entity t)))
            cur-ks (into #{} (map tkey) tiles)
            base-ks (if mpw (into #{} (map tkey) (:active-tiles mpw)) #{})
            added (when mpw (vec (sort-by str (remove base-ks cur-ks))))
            removed (when mpw (vec (sort-by str (remove cur-ks base-ks))))
            dropped (get-in pw [:surface-stats :dropped] 0)
            tot (long (get-in pw [:surface-stats :tokens] 0))
            bud (long (get-in pw [:surface-stats :budget] 16000))
            rows (mapv (fn [t]
                         (str "- " (tkey t) " (" (or (:schema t) :entity) " · " (or (:chosen-fn t) :default)
                              " · " (long (or (:tokens t) 0)) " tok)"))
                       tiles)
            wtxt (str (:walk-text pw))
            excerpt (when-not (str/blank? wtxt)
                      (if (> (count wtxt) 1800) (str (subs wtxt 0 1800) "\n… [call (user/simulate-context " taid " {:render-text? true}) for full text]") wtxt))]
        {:tag "target-context-preview"
         :attrs {:for (str taid) :mode (if (seq tevs) "mid-session" "turn-0")
                 :tokens (str tot) :tiles (str (count tiles))}
         :text (str "Live projected context for " taid " (" (count tiles) " tiles, " tot " / " bud " tok; " dropped " dropped)"
                    (when mpw (str " · diff vs :main: +" (count added) " " (pr-str added) " / -" (count removed) " " (pr-str removed)))
                    ":\n"
                    (if (seq rows) (str/join "\n" rows) "(none)")
                    (when excerpt (str "\n\nRendered preview:\n" excerpt))
                    "\n\nFull text: (user/simulate-context " taid " {:render-text? true}) | 12-scenario check: (user/check-corpus)")}))))

(defn build-live-wire-request
  "Single source of truth for LLM wire context & Context Lab: ONE KV-cache prefix + ONE `<context-delta>` via `seon.ctx/project-entity`."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :map]
   :cost/ticks 60000000}
  [main-db branch-db agent-id {:keys [viewport-screenshot vision? conn already-shown token-budget strategy
                                      disabled-rules weights explain? include-tail? depth] :as opts}]
  (let [inc-tail? (not= false include-tail?)
        d0 (long (or depth 0))
        base-default? (and (empty? already-shown) (nil? token-budget) (nil? strategy)
                           (empty? disabled-rules) (nil? weights) (nil? (:now-ms opts)))
        ck (when (and base-default? (not explain?))
             (when-let [k (wire-cache-key main-db branch-db agent-id vision? viewport-screenshot)]
               [k inc-tail? (>= d0 4)]))
        compute
        (fn []
          (let [ag (or (rdata/pull-agent-entity main-db branch-db agent-id) {:seon.agent/id agent-id})
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
                cur-turn (inc (count (distinct (keep :seon.agent.eval/turn evals))))
                w0 (derive-wire-walk-prefix main-db branch-db conn ag anchor-turn (assoc opts :already-shown already-shown))
                pfx (when (seq (:prefix-items w0))
                      (rstatus/resolve-prefix-agents main-db branch-db conn ag (:prefix-items w0) d0 build-live-wire-request))
                w (if (seq (:parent-shown pfx))
                    (derive-wire-walk-prefix main-db branch-db conn ag anchor-turn
                                             (assoc opts :already-shown (merge (:parent-shown pfx) already-shown)))
                    w0)
                {:keys [tile-projections active-tiles walk-text walk-db walk-main-cid rule-trace fit-trace shown
                        surface-stats explained candidates]} w
                manifest (when-let [fid (:forked-from pfx)] (rstatus/format-forked-eval-manifest walk-db fid))
                eff-walk (if manifest (str walk-text (when-not (str/blank? walk-text) "\n\n---\n\n") manifest) walk-text)
                prev-shown (merge (:parent-shown pfx) already-shown shown)
                db-moved? (or (not= walk-main-cid (live-cid main-db)) (not= (live-cid walk-db) (live-cid branch-db)))
                need-delta? (and inc-tail? (not explain?) (or db-moved? (seq evals)))
                delta-res (when need-delta? (build-live-delta-blocks main-db branch-db ag cur-turn prev-shown opts))
                tprev-block (when (and inc-tail? (not explain?)) (build-target-preview-block main-db branch-db ag evals d0))
                vp-block (when inc-tail? (build-viewport-block main-db ag viewport-screenshot (boolean vision?) (:force-vision? opts)))
                budget-block (when inc-tail? (build-budget-block main-db branch-db ag cur-turn evals))
                tail-blocks (when inc-tail? (vec (concat (:blocks delta-res) (when tprev-block [tprev-block]) (when budget-block [budget-block]) (when vp-block [vp-block]))))
                activity-text (when inc-tail? (not-empty (str/join "\n\n" (keep :text tail-blocks))))
                prefix-msg-eids (into #{} (comp (filter #(= :seon.agent.message/entity (:schema %))) (keep :entity)) active-tiles)
                rendered-msg-eids (into (into prefix-msg-eids (keys prev-shown)) (:rendered-msg-eids delta-res))
                rem-unread (filterv #(not (contains? rendered-msg-eids (:db/id %))) unread)
                all-shown (merge prev-shown (:delta-shown delta-res))
                wire (model/build-agent-wire-messages
                      {:thinking? true :replay-reasoning? true :vision? (boolean vision?)
                       :out-max-chars (store/config-val main-db agent-id :seon.config/eval-out-chars)
                       :format-tool-fn rstatus/cached-format-tool-content}
                      {:agent-id agent-id :branch branch
                       :goal (:seon.agent/goal ag) :summary (:seon.agent/summary ag) :system-prompt (:seon.agent/system-prompt ag)
                       :prefix-agents (:entries pfx) :forked-from (:forked-from pfx) :include-tail? inc-tail?
                       :walk-text eff-walk :tail-blocks tail-blocks :ephemeral-image (:image vp-block)
                       :history (rdata/evals->turns evals (into {} (map (juxt :seon.agent.message/id identity)) msgs))
                       :unread rem-unread :eval-db walk-db :main-db main-db :fallback-db branch-db
                       :viewport-screenshot viewport-screenshot})]
            (cond-> (assoc wire :agent ag :tiles tile-projections :candidates candidates :rule-trace rule-trace
                           :fit-trace (assoc fit-trace :delta-tokens (long (:delta-tokens delta-res 0)))
                           :shown all-shown :surface-stats surface-stats :walk-text eff-walk
                           :tail-blocks (or tail-blocks []) :activity-text activity-text :walk-db walk-db
                           :delta-tiles (or (:delta-tiles delta-res) [])
                           :evals evals :messages-inbox msgs :unread unread)
              explained (assoc :explained explained))))]
    (if ck (store/lru-get! wire-request-cache 64 ck compute) (compute))))
