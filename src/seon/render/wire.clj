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
            [seon.render.canvas.live :as clive]
            [seon.render.data :as rdata :refer [agent-evals-in agent-messages-in compact-entity-preview evals->turns first-doc-line pull-agent-entity router-agent?]]
            [seon.render.entities :as rent]
            [seon.render.fit :as fit]
            [seon.render.tail :as tail]
            [seon.rule :as rules]
            [seon.rule.context :as rctx]
            [seon.store :as store]))

(defonce ^:seon.flow/edge wire-request-cache
  (store/lru-cache 8))

(defonce ^:private ^:seon.flow/edge walk-prefix-cache
  (store/lru-cache 64))

(defonce ^:private ^:seon.flow/edge form-meta-cache
  (store/lru-cache 16))

(def ^:private fallback-entity-renderers
  {:seon.code.var/entity    ['seon.render.entities/render-var-chip 'seon.render.entities/render-var-sig 'seon.render.entities/render-var-full]
   :seon.error/entity       ['seon.render.entities/render-error-badge 'seon.render.entities/render-error-card]
   :seon.task/entity        ['seon.render.entities/render-task-chip 'seon.render.entities/render-task-card 'seon.render.entities/render-task-full]
   :seon.schema/entity      ['seon.render.entities/render-schema-chip 'seon.render.entities/render-schema-detail]
   :seon.code.doc/entity    ['seon.render.entities/render-doc-snippet 'seon.render.entities/render-doc-full]
   :seon.test/verdict       ['seon.render.entities/render-test-badge 'seon.render.entities/render-test-card]
   :seon.agent.eval/entity  ['seon.render.entities/render-eval-row 'seon.render.entities/render-eval-card]
   :seon.code.ns/entity     ['seon.render.entities/render-ns-chip 'seon.render.entities/render-ns-card]
   :seon.agent/entity       ['seon.render.entities/render-agent-row 'seon.render.entities/render-agent-card]
   :seon.rule/entity        ['seon.render.entities/render-rule-chip 'seon.render.entities/render-rule-detail]})

(defn- live-db-commit-id [db]
  (when (and db (instance? datahike.db.DB db))
    (d/commit-id db)))

;; Pure `ns-map -> ns-map` Hiccup ephemeral tail renderers

(defn render-branch-status-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for branch delta, turn budget, auto-proofs, and merge-blocking errors."
  {:malli/schema [:=> [:cat [:map [:seon.tail/branch-status :map]]]
                  [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{bs :seon.tail/branch-status}]
  (let [{:keys [branch max-tx branch-edited main-drifted branch-errors last-proofs
                show-budget? turns-used max-turns rem-turns]} bs]
    (when (or (seq branch-edited) (seq branch-errors) last-proofs (seq main-drifted) show-budget?)
      (let [ed-n (count branch-edited)
            ed-str (when (pos? ed-n)
                     (str " (" (str/join ", " (take 6 (sort branch-edited)))
                          (when (> ed-n 6) (str " +" (- ed-n 6) " more")) ")"))
            sync-str (if (empty? main-drifted)
                       "up-to-date (non-conflicting :main changes sync lazily)"
                       (str (count main-drifted) " form(s) moved on :main"))]
        {:tag "branch-status"
         :seon.ui/hiccup
         [:section {:class "tail-block" :data-min-width 240}
          [:p (str "branch=" branch " @ tx " max-tx " | edited-forms=" ed-n ed-str " | main-sync=" sync-str)]
          (when show-budget?
            [:p (str "TURN BUDGET: turn " turns-used " / " max-turns " (" rem-turns " remaining)."
                     (cond
                       (and (<= (long (or rem-turns 0)) 10) (empty? branch-edited))
                       " PACING ALERT: 0 persisted code edits yet — write/persist changes + run tests NOW."
                       (<= (long (or rem-turns 0)) 5)
                       " FINAL TURNS: finish edits, run tests, and call (user/ready!)."
                       :else ""))])
          (when (seq main-drifted)
            [:p (str "MAIN DRIFT: :main updated " (str/join ", " (take 5 (sort main-drifted)))
                     " — check (user/diff) to verify no conflicts before merging.")])
          (when last-proofs
            [:p (str "AUTO-PROOFS (tx " (:tx last-proofs) "): "
                     (if (:ok? last-proofs) "PASS" "FAIL")
                     " (" (:passed last-proofs 0) "/" (:ran last-proofs 0)
                     " in " (:ms last-proofs 0.0) " ms)"
                     (when (seq (:failures last-proofs))
                       (str " — " (str/join "; " (take 3 (:failures last-proofs))))))])
          (when (seq branch-errors)
            [:div
             [:p (str "MERGE-BLOCKING HEALTH ERRORS (" (count branch-errors) "):")]
             (into [:ul]
                   (for [e (take 5 branch-errors)]
                     [:li (str "[:" (name (:class e)) "] " (:subject e) " — " (:message e))]))])]}))))

(defn render-scoped-health-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for scope-routed health alerts (nil = 0 tokens when clean)."
  {:malli/schema [:=> [:cat [:map [:seon.tail/scoped-health :map]]]
                  [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [scope problems]} :seon.tail/scoped-health}]
  (when (seq problems)
    {:tag "scoped-health"
     :seon.ui/hiccup
     [:section {:class "tail-block" :data-min-width 220}
      [:p (str "Health & efficiency alerts for " scope ":")]
      (into [:ul]
            (for [p (take 6 problems)]
              [:li (str "[" (name (:severity p :warning)) " :" (name (:class p)) "] "
                        (:subject p) " — " (:message p)
                        (when (:fix p) " (auto-fixable via (user/autofix-health!))"))]))]}))

(defn render-activity-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for parent request, open calls, sub-agents, errors, and live records."
  {:malli/schema [:=> [:cat [:map [:seon.tail/activity :map]]]
                  [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 1000000}
  [{{:keys [scope parent-request incoming work-tree open-errors samples]} :seon.tail/activity}]
  (let [p-h (when parent-request (clive/parent-request-section {:seon.canvas/parent-request parent-request}))
        w-h (when (seq work-tree) (clive/render-work-progress work-tree))
        e-hs (keep #(when (map? %) (:seon.ui/hiccup (rent/render-error-badge {:seon.error/entity %})))
                   (take 4 open-errors))]
    (when (or p-h (seq incoming) w-h (seq e-hs) (seq samples))
      {:tag "neighborhood-activity"
       :seon.ui/hiccup
       [:section {:class "tail-block" :data-min-width 240}
        (when p-h [:div [:p "Parent request:"] p-h])
        (when (seq incoming)
          [:div
           [:p "Open incoming calls to answer via (user/answer! call-id val):"]
           (into [:ul]
                 (for [c incoming]
                   [:li (str (pr-str (:seon.agent.call/id c)) " from " (:seon.agent.call/caller c)
                             (when (:seon.agent.call/result-schema c) (str " schema=" (:seon.agent.call/result-schema c)))
                             ": " (first-doc-line (:seon.agent.call/prompt c) 100))]))])
        (when w-h [:div [:p "Sub-agent progress:"] w-h])
        (when (seq e-hs) (into [:div [:p "Open errors:"]] e-hs))
        (when (seq samples)
          [:div
           [:p (str "Recent live data matching " scope " schemas:")]
           (into [:ul]
                 (for [{:keys [attr tx preview]} samples]
                   [:li (str "tx " tx " (" attr "): " preview)]))])]})))

(defn render-inbox-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for unread inbox message summaries."
  {:malli/schema [:=> [:cat [:map [:seon.tail/inbox :map]]]
                  [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [unread]} :seon.tail/inbox}]
  (when (seq unread)
    {:tag "unread-inbox"
     :seon.ui/hiccup
     [:section {:class "tail-block" :data-min-width 200}
      [:p (str "Unread inbox (" (count unread) "):")]
      (into [:ul]
            (for [m (take 6 unread)]
              [:li (str (or (:seon.agent.message/from m) :user) " [" (name (or (:seon.agent.message/type m) :chat)) "]: "
                        (first-doc-line (:seon.agent.message/body m "") 120))]))]}))

(defn render-viewport-tail
  "Pure `ns-map -> ns-map` Hiccup renderer for live WKWebView viewport screenshot & DOM summary."
  {:malli/schema [:=> [:cat [:map [:seon.tail/viewport :map]]]
                  [:maybe [:map [:seon.ui/hiccup :seon.ui/hiccup]]]]
   :cost/ticks 500000}
  [{{:keys [snapshot enabled? vision?]} :seon.tail/viewport}]
  (when (and (map? snapshot) enabled?)
    (let [dom (:dom snapshot)
          dom-s (when (map? dom)
                  (let [parts (remove nil?
                                      [(when-not (str/blank? (str (:viewing dom))) (str "viewing=" (:viewing dom)))
                                       (when-not (str/blank? (str (:mode dom))) (str "mode=" (:mode dom)))
                                       (when (:theme dom) (str "theme=" (:theme dom)))
                                       (when (some? (:overflowX dom)) (str "overflowX=" (:overflowX dom)))
                                       (when (some? (:svgCount dom)) (str "svgs=" (:svgCount dom)))])]
                    (when (seq parts) (str/join " · " parts))))]
      (cond-> {:tag "live-viewport"
               :attrs {:route (:route snapshot "/")
                       :size (str (:width snapshot 1280) "x" (:height snapshot 820))}
               :seon.ui/hiccup
               [:section {:class "tail-block" :data-min-width 220}
                [:p "Current live WKWebView screenshot of what the user sees right now (always replaced at end of context — inspect closely and fix any clipping, overlap, contrast, or stray artifacts)."]
                (when dom-s [:p (str "DOM: " dom-s)])]}
        (and vision? (:base64 snapshot))
        (assoc :image {:mime (or (:mime snapshot) "image/jpeg")
                       :base64 (:base64 snapshot)
                       :raw snapshot})))))

;; Fast indexed tail data gatherers

(defn- cached-form-meta [db]
  (if-not (and db (contains? (:schema db) :seon.code.form/key) (contains? (:schema db) :seon.code.form/hash))
    {:hashes {} :txs {}}
    (let [cid (live-db-commit-id db)
          compute (fn []
                    (let [ks (reduce (fn [a dt] (assoc a (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.code.form/key))]
                      (reduce (fn [acc dt]
                                (if-let [k (get ks (:e dt))]
                                  (-> acc (assoc-in [:hashes k] (:v dt)) (assoc-in [:txs k] (long (:tx dt 0))))
                                  acc))
                              {:hashes {} :txs {}}
                              (d/datoms db :aevt :seon.code.form/hash))))]
      (if cid
        (store/lru-get! form-meta-cache 16 [(get-in db [:config :store :id]) (get-in db [:config :branch] :main) cid (:max-tx db)] compute)
        (compute)))))

(defn- gather-branch-status-data [main-db branch-db ag]
  (let [branch (:seon.agent/branch ag :main)
        aid (:seon.agent/id ag)]
    (when (and main-db branch-db (not= branch :main))
      (let [mcid (live-db-commit-id main-db)
            bcid (live-db-commit-id branch-db)
            turns-used (if (and aid (contains? (:schema branch-db) :seon.agent.eval/agent))
                         (->> (d/datoms branch-db :avet :seon.agent.eval/agent aid)
                              (keep #(:seon.agent.eval/turn (d/pull branch-db '[:seon.agent.eval/turn] (:e %))))
                              distinct count)
                         0)
            max-turns (long (or (when (and aid (contains? (:schema main-db) :seon.config/max-tool-turns))
                                  (store/config-val main-db aid :seon.config/max-tool-turns))
                                32))
            rem-turns (when (pos? max-turns) (max 0 (- max-turns turns-used)))
            show-budget? (and (pos? max-turns) (pos? turns-used) (or (>= turns-used 10) (<= rem-turns 12)))]
        (when (or show-budget? (not= mcid bcid))
          (let [same? (and mcid bcid (= mcid bcid))
                m-meta (if same? {:hashes {} :txs {}} (cached-form-meta main-db))
                b-meta (if same? {:hashes {} :txs {}} (cached-form-meta branch-db))
                mh (:hashes m-meta) bh (:hashes b-meta) mt (:txs m-meta) bt (:txs b-meta)
                diff-ks (filterv #(not= (get bh %) (get mh %)) (into (set (keys bh)) (keys mh)))
                edited (filterv #(and (contains? bh %) (> (get bt % 0) (get mt % 0))) diff-ks)
                drifted (filterv #(and (contains? mh %) (contains? bh %) (> (get mt % 0) (get bt % 0))) diff-ks)
                nss (vec (distinct (keep #(let [p (first (str/split (str %) #"[#/]" 2))]
                                            (when-not (str/blank? p) (symbol p)))
                                         edited)))
                b-errs (when (seq edited) (health/caused-by-branch main-db branch-db {} nss))]
            {:branch branch :max-tx (:max-tx branch-db) :branch-edited edited :main-drifted drifted
             :branch-errors b-errs :last-proofs (:seon.agent/last-proofs ag)
             :show-budget? show-budget? :turns-used turns-used :max-turns max-turns :rem-turns rem-turns}))))))

(defn- gather-scoped-health-data [main-db branch-db ag evals]
  (let [aid (:seon.agent/id ag)
        scope (:seon.agent/scope ag)]
    (when (and main-db scope)
      (let [live-p (health/live-problems-for-scope main-db branch-db scope aid evals)
            hdb (if (or (nil? branch-db) (= branch-db main-db)
                        (<= (long (:max-tx branch-db 0)) (long (or (:seon.agent/merged-tx ag) 0))))
                  main-db branch-db)
            fix-p (filterv #(or (= :error (:severity %)) (:fix %))
                           (health/static-problems-for-scope hdb scope))]
        {:scope scope :problems (vec (distinct (concat live-p fix-p)))}))))

(defn- gather-activity-data [main-db branch-db ag]
  (let [aid (:seon.agent/id ag)
        scope (:seon.agent/scope ag)
        router? (router-agent? ag)
        prefs (or (:seon.agent/context-prefs ag) {})
        rec-lim (long (or (:recent-data-limit prefs) 4))
        has-calls? (and aid main-db (contains? (:schema main-db) :seon.agent.call/callee))
        incoming (when has-calls?
                   (->> (d/datoms main-db :avet :seon.agent.call/callee aid)
                        (map #(d/pull main-db '[:seon.agent.call/id :seon.agent.call/caller
                                                :seon.agent.call/status :seon.agent.call/prompt
                                                :seon.agent.call/result-schema] (:e %)))
                        (filter #(= :pending (:seon.agent.call/status %)))
                        vec))
        preq (when has-calls? (clive/parent-request-data main-db aid))
        wtree (when has-calls? (clive/delegation-progress-tree main-db nil aid))
        open-errs (when (and aid branch-db (contains? (:schema branch-db) :seon.error/agent))
                    (->> (d/datoms branch-db :avet :seon.error/agent aid)
                         (map #(d/pull branch-db '[*] (:e %)))
                         (filter #(= :open (:seon.error/status %)))
                         vec))
        samples (when (and main-db scope (pos? rec-lim))
                  (let [rdefs (when router? [:seon.agent.call/id :seon.task/id :seon.test.run/branch :seon.error/id :seon.agent/id])
                        cands (->> (concat (:extra-schemas prefs) rdefs)
                                   distinct
                                   (filter #(and (contains? (:schema main-db) %)
                                                 (first (take 1 (d/datoms main-db :avet %)))))
                                   (take 5))]
                    (->> cands
                         (mapcat (fn [a]
                                   (map (fn [dt] {:attr a :tx (:tx dt) :eid (:e dt)})
                                        (take-last 2 (sort-by :tx (take 250 (d/datoms main-db :avet a)))))))
                         (sort-by (comp - :tx))
                         (take rec-lim)
                         (mapv (fn [{:keys [attr tx eid]}]
                                 {:attr attr :tx tx :preview (compact-entity-preview (d/pull main-db '[*] eid) 150)})))))]
    {:scope scope :parent-request preq :incoming incoming :work-tree wtree
     :open-errors open-errs :samples samples}))

(defn- assemble-unified-tail [main-db branch-db ag evals opts]
  (if (seq (:seon.agent/tail-renders ag))
    (tail/render-tail-blocks {:main-db main-db :branch-db branch-db :agent ag :evals evals :opts opts})
    (let [aid (:seon.agent/id ag)
          bs (gather-branch-status-data main-db branch-db ag)
          sh (gather-scoped-health-data main-db branch-db ag evals)
          act (gather-activity-data main-db branch-db ag)
          cfg-has? (and main-db (contains? (:schema main-db) :seon.config/viewport-screenshot?))
          v-en? (cond (:force-vision? opts) true
                      cfg-has? (boolean (store/config-val main-db aid :seon.config/viewport-screenshot?))
                      :else (boolean (:vision? opts true)))
          raw (remove nil?
                      [(when bs (render-branch-status-tail {:seon.tail/branch-status bs}))
                       (when sh (render-scoped-health-tail {:seon.tail/scoped-health sh}))
                       (render-activity-tail {:seon.tail/activity act})
                       (when (:viewport-screenshot opts)
                         (render-viewport-tail {:seon.tail/viewport {:snapshot (:viewport-screenshot opts)
                                                                     :enabled? v-en?
                                                                     :vision? (boolean (:vision? opts true))}}))])
          blocks (mapv (fn [{:keys [tag attrs image] h :seon.ui/hiccup}]
                         (cond-> {:tag tag :hiccup h :text (render/hiccup->text h)}
                           attrs (assoc :attrs attrs)
                           image (assoc :image image)))
                       raw)
          img (some :image blocks)]
      {:blocks blocks :image img})))

(defn neighborhood-recent-activity
  "Renders neighborhood activity through the unified pure Hiccup tail pipeline."
  {:malli/schema [:=> [:cat :any :any :map] [:maybe :string]]}
  [main-db branch-db ag]
  (when-let [res (render-activity-tail {:seon.tail/activity (gather-activity-data main-db branch-db ag)})]
    (render/hiccup->text (:seon.ui/hiccup res))))

(defn wire-cache-key
  "Datahike commit-id cache key for `build-live-wire-request` (zero manual AEVT DB scans)."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :any :any] :any]}
  [main-db branch-db agent-id vision? viewport-screenshot]
  (let [mcid (live-db-commit-id main-db)
        bcid (live-db-commit-id branch-db)]
    (when (and mcid bcid)
      [(get-in main-db [:config :store :id])
       agent-id
       mcid (:max-tx main-db)
       (if (= mcid bcid) :same-db [bcid (:max-tx branch-db)])
       (boolean vision?)
       (when viewport-screenshot
         [(:width viewport-screenshot) (:height viewport-screenshot)
          (:bytes viewport-screenshot) (:captured-at viewport-screenshot)
          (hash (:data-url viewport-screenshot)) (hash (:base64 viewport-screenshot))])])))

(defn- build-wire-examples [main-db ag]
  (vec
   (for [ex-id (sort (:seon.agent/examples ag))
         :when (first (d/datoms main-db :avet :seon.agent/id ex-id))
         :let [ex-ag (d/pull main-db '[:seon.agent/id :seon.agent/goal] [:seon.agent/id ex-id])
               ex-evals (agent-evals-in main-db ex-id)]]
     {:id ex-id :goal (:seon.agent/goal ex-ag) :turns (evals->turns ex-evals)})))

(defn- entity-max-tx [db eid]
  (if (integer? eid)
    (reduce max 0 (map #(long (or (:tx %) 0)) (d/datoms db :eavt eid)))
    (long (or (:max-tx db) 0))))

(defn- tile->fit-item [main-db walk-db ag t]
  (let [h (render/render-tile-node walk-db t {:mode :agent :compact? true :main-db main-db :agent ag})
        m (fit/measure-hiccup h)
        txt (render/hiccup->text h)
        eid (or (:eid t) (:id t))
        sk (:schema t)
        tile-cand (assoc m :fn (or (:view-fn t) :tile/default) :hiccup h)
        disc (when (and (integer? eid) (qualified-keyword? sk))
               (or (seq (fit/discover-renderers walk-db [sk]))
                   (get fallback-entity-renderers sk)))
        ent-data (when (seq disc) (if (map? (:val t)) (:val t) (d/pull walk-db '[*] eid)))
        dh (:schema walk-db)
        norm-ent (when (map? ent-data)
                   (into {} (map (fn [[k v]] [k (if (and (= :db.cardinality/many (get-in dh [k :db/cardinality]))
                                                         (sequential? v))
                                                  (set v) v)]))
                         ent-data))
        extra-cands (when (and (seq disc) (map? norm-ent))
                      (keep (fn [rsym]
                              (when-let [f (requiring-resolve rsym)]
                                (let [res (f {sk norm-ent})
                                      eh (:seon.ui/hiccup res)]
                                  (when eh
                                    (let [em (fit/measure-hiccup eh)]
                                      (when (< (:tokens em) (:tokens m))
                                        (assoc em :fn rsym :hiccup eh)))))))
                            disc))
        cands (vec (concat extra-cands [tile-cand]))]
    (assoc t
           :entity eid
           :tx (entity-max-tx walk-db eid)
           :hash (hash [(:id t) sk txt])
           :candidates cands)))

(defn- run-unified-wire-fit [main-db walk-db task-db ag opts]
  (let [aid (:seon.agent/id ag)
        budget (long (or (:token-budget opts) 16000))
        strategy (or (:strategy opts) :breadth-first)
        shown0 (:already-shown opts)
        derived (rctx/derive-context
                 main-db walk-db ag [] health/report
                 {:include-evals? false :include-children? false
                  :task-db task-db :target :wire})
        ru (ctx/related-updates
            {:seon.db/main main-db :seon.db/branch walk-db :seon.agent/id aid
             :seon.view/target :wire :seon.view/token-budget budget})
        tile-items (mapv #(tile->fit-item main-db walk-db ag %)
                         (concat (:tiles derived) (:tail-tiles derived)))
        seen-eids (into #{} (keep :entity) tile-items)
        extra-ru (into []
                       (comp (remove #(contains? seen-eids (:entity %)))
                             (remove #(contains? (:tags %) :eval))
                             (map #(-> % (dissoc :candidates :data) (assoc :source :rule))))
                       (:seon.ctx/projections ru))
        all-items (into tile-items extra-ru)
        fit-res (fit/fit-projections walk-db all-items
                                     {:target :wire :token-budget budget :strategy strategy
                                      :already-shown shown0 :weights (:weights opts)
                                      :fallback-renderers fallback-entity-renderers})
        enrich (fn [p]
                 (let [h (:hiccup p)
                       txt (if (= :unchanged (:status p)) "" (render/hiccup->text h))]
                   (assoc p :text txt)))
        selected (mapv enrich (:selected fit-res))
        fmt-proj (fn [{:keys [schema source handle layout chosen-fn text]}]
                   (str "### [" (name (or source :rule))
                        (when handle (str " " handle))
                        " · " (or schema chosen-fn :entity)
                        (when (and layout (not= layout :expanded-card)) (str " · " (name layout)))
                        "]\n" text))
        prefix-sel (filterv #(and (not= :ephemeral-tail (:zone %)) (not= :unchanged (:status %))) selected)
        tail-sel (filterv #(and (= :ephemeral-tail (:zone %)) (not= :unchanged (:status %))) selected)
        ru-trace (:seon.ctx/trace ru)
        merged-runs (vec (concat (:runs derived) (:fired-rules ru-trace)))]
    {:tile-projections selected
     :walk-text (str/join "\n\n---\n\n" (map fmt-proj prefix-sel))
     :walk-db walk-db
     :tail-tile-blocks (when (seq tail-sel)
                         [{:tag "rule-tail" :text (str/join "\n\n---\n\n" (map fmt-proj tail-sel))}])
     :rule-trace {:rules-count (+ (long (:rules-count derived 0)) (long (:rules-total ru-trace 0)))
                  :facts-count (:facts-count derived 0)
                  :ms (:ms derived 0.0)
                  :runs merged-runs
                  :recomputed (:recomputed ru-trace 0)
                  :skipped (:skipped ru-trace 0)}
     :fit-trace {:tokens (:tokens fit-res)
                 :budget (:budget fit-res)
                 :strategy (:strategy fit-res)
                 :selected-count (count selected)
                 :dropped-count (count (:dropped fit-res))
                 :items (mapv #(select-keys % [:entity :id :schema :zone :status :chosen-fn :tokens
                                               :measured-tokens :rank/score :rank/signals :candidates :tx])
                              selected)}
     :shown (:shown fit-res)
     :surface-stats {:selected (count selected)
                     :dropped (count (:dropped fit-res))
                     :unchanged (count (filter #(= :unchanged (:status %)) selected))
                     :tokens (:tokens fit-res)
                     :budget (:budget fit-res)}}))

(defn render-agent-context
  "Renders the unified Upgrade-Ladder `<walk-context>` + Hiccup `<ephemeral-tail>` for any agent."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db :seon.host/db :keyword] :string]
                  [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :string]]}
  ([main-db branch-db agent-id]
   (render-agent-context main-db branch-db agent-id {}))
  ([main-db branch-db agent-id opts]
   (let [base-ag (or (pull-agent-entity main-db branch-db agent-id)
                     {:seon.agent/id agent-id :seon.agent/role :worker})
         task-id (or (:task opts) (:seon.agent/task base-ag))
         task-scope (when (and task-id (nil? (:scope opts)) (nil? (:seon.agent/scope base-ag)))
                      (:seon.task/scope (or (d/pull branch-db '[:seon.task/scope] [:seon.task/id task-id])
                                            (d/pull main-db '[:seon.task/scope] [:seon.task/id task-id]))))
         eff-scope (or (:scope opts) (:seon.agent/scope base-ag) task-scope)
         extra-nss (vec (distinct (concat (get-in base-ag [:seon.agent/context-prefs :extra-namespaces])
                                          (:extra-namespaces opts))))
         eff-pinned (vec (distinct (concat (:seon.agent/pinned base-ag) (:pinned opts))))
         ag (cond-> base-ag
              task-id (assoc :seon.agent/task task-id)
              eff-scope (assoc :seon.agent/scope eff-scope)
              (seq extra-nss) (assoc-in [:seon.agent/context-prefs :extra-namespaces] extra-nss)
              (seq eff-pinned) (assoc :seon.agent/pinned eff-pinned))
         task-db (if (and task-id (first (d/datoms branch-db :avet :seon.task/id task-id))) branch-db main-db)
         {:keys [walk-text tail-tile-blocks]} (run-unified-wire-fit main-db branch-db task-db ag opts)
         include-tail? (if (contains? opts :include-tail?) (boolean (:include-tail? opts)) true)
         tail-blocks (when include-tail?
                       (:blocks (assemble-unified-tail main-db branch-db ag (agent-evals-in branch-db agent-id) {:vision? false})))
         rule-tail (when (seq tail-tile-blocks)
                     (str "<rule-tail>\n" (str/join "\n\n" (keep :text tail-tile-blocks)) "\n</rule-tail>"))
         tail-text (not-empty (str/join "\n\n" (remove str/blank? (concat (keep :text tail-blocks) [rule-tail]))))]
     (str "<walk-context>\n" walk-text "\n</walk-context>"
          (when-not (str/blank? tail-text)
            (str "\n\n<ephemeral-tail>\n" tail-text "\n</ephemeral-tail>"))))))

(defn- derive-wire-walk-prefix
  {:seon.flow/edge true}
  [main-db branch-db conn ag anchor-turn opts]
  (let [branch (:seon.agent/branch ag :main)
        read-main? (or (= :main (:seon.agent/read-branch ag))
                       (:seon.agent/router? ag)
                       (= :coordinator (:seon.agent/role ag)))
        base-cid (when (and conn (not= branch :main) (not read-main?) (= anchor-turn 1))
                   (try (d/merge-base conn :main branch) (catch Throwable _ nil)))
        store-id (get-in main-db [:config :store :id])
        task? (boolean (and (:seon.agent/task ag)
                            (first (d/datoms branch-db :avet :seon.task/id (:seon.agent/task ag)))))
        has-shown? (boolean (seq (:already-shown opts)))
        walk-key (when-not has-shown?
                   [store-id (:seon.agent/id ag) base-cid branch
                    (rules/rules-db-sig main-db) (rules/rules-db-sig branch-db)
                    (:seon.agent/role ag) (:seon.agent/scope ag)
                    (:seon.agent/task ag) (:seon.agent/goal ag)
                    (vec (:seon.agent/pinned ag)) (vec (:seon.agent/memory ag))
                    (vec (:seon.agent/curated ag)) (:seon.agent/context-prefs ag)
                    (:seon.agent/render-fn ag) anchor-turn task?
                    (:token-budget opts) (:strategy opts)])
        compute
        (fn []
          (let [base-db (when (and conn base-cid)
                          (try (d/commit-as-db conn base-cid) (catch Throwable _ nil)))
                scope (:seon.agent/scope ag)
                scope-in-base? (or (nil? scope)
                                   (and base-db (boolean (first (d/datoms base-db :avet :seon.code.ns/name scope)))))
                walk-db (cond read-main? main-db
                              (and base-db scope-in-base?) base-db
                              :else branch-db)
                task-db (if task? branch-db main-db)]
            (run-unified-wire-fit main-db walk-db task-db ag opts)))]
    (if walk-key
      (store/lru-get! walk-prefix-cache 64 walk-key compute)
      (compute))))

(defn build-live-wire-request
  "Single source of truth for an agent's LLM context AND Debug View:
   Uses Datahike commit-id caching, `seon.ctx/related-updates`, and `seon.render.fit/fit-projections`
   (`:target :wire`, `already-shown` self-emptying queue, least-volatile -> most-volatile ordering)
   plus pure `ns-map -> ns-map` Hiccup ephemeral tail renderers."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map] :map]}
  [main-db branch-db agent-id {:keys [viewport-screenshot vision? conn already-shown token-budget strategy] :as opts}]
  (let [ck (when (and (empty? already-shown) (nil? token-budget) (nil? strategy))
             (wire-cache-key main-db branch-db agent-id vision? viewport-screenshot))
        compute
        (fn []
          (let [ag (pull-agent-entity main-db branch-db agent-id)
                evals (agent-evals-in branch-db agent-id)
                msgs (agent-messages-in main-db agent-id)
                seen-ids (store/agent-seen-message-ids branch-db agent-id)
                unread (filterv #(not (contains? seen-ids (:seon.agent.message/id %))) msgs)
                branch (:seon.agent/branch ag :main)
                anchor-turn (let [t (:seon.agent.eval/turn (first evals))]
                              (if (and t (> t 1)) t 1))
                {:keys [tile-projections walk-text walk-db tail-tile-blocks rule-trace fit-trace shown surface-stats]}
                (derive-wire-walk-prefix main-db branch-db conn ag anchor-turn opts)
                tail-res (assemble-unified-tail main-db branch-db ag evals
                                                {:viewport-screenshot viewport-screenshot
                                                 :vision? (boolean vision?)})
                tail-blocks (vec (concat (:blocks tail-res) tail-tile-blocks))
                activity-text (not-empty (str/join "\n\n" (keep :text tail-blocks)))
                examples (build-wire-examples main-db ag)
                wire (model/build-agent-wire-messages
                      {:thinking? true :replay-reasoning? true :vision? (boolean vision?)}
                      {:agent-id agent-id
                       :branch branch
                       :role (:seon.agent/role ag :worker)
                       :goal (:seon.agent/goal ag)
                       :system-prompt (:seon.agent/system-prompt ag)
                       :examples examples
                       :walk-text walk-text
                       :tail-blocks tail-blocks
                       :ephemeral-image (:image tail-res)
                       :history (evals->turns evals (into {} (map (juxt :seon.agent.message/id identity)) msgs))
                       :unread unread
                       :eval-db walk-db
                       :main-db main-db
                       :fallback-db branch-db
                       :viewport-screenshot viewport-screenshot})]
            (assoc wire
                   :agent ag
                   :tiles tile-projections
                   :rule-trace rule-trace
                   :fit-trace fit-trace
                   :shown shown
                   :surface-stats surface-stats
                   :tail-blocks tail-blocks
                   :activity-text activity-text
                   :evals evals
                   :messages-inbox msgs
                   :unread unread)))]
    (if ck
      (store/lru-get! wire-request-cache 8 ck compute)
      (compute))))
