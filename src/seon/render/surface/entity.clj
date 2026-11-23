(ns seon.render.surface.entity
  "ONE General Entity-Surface Engine (`project-entity-view`) serving humans (`:target :html`)
   and agents (`:target :wire`), fast low-context hover previews (`render-entity-preview-html`),
   the unified `:seon.system/entity` view (`render-system-entity-body`), and focal-entity Explain."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks :refer [badge btn db-schema ent-link feedback-banner kicker live-cid mut]]
            [seon.render.surface.inspector :as sinsp]
            [seon.render.wire :as rwire]
            [seon.signals :as sig]
            [seon.signals.cache :as scache]
            [seon.store :as store]
            [seon.vector.flow :as vflow]))

(defonce ^:private ^:seon.flow/edge preview-html-cache (store/lru-cache 128))

(defn- r2 [x] (if (number? x) (/ (Math/round (* (double x) 100.0)) 100.0) 0.0))
(defn- lvl-cls [lvl] (case lvl :hot "sig-hot" :warm "sig-warm" "sig-calm"))
(defn- clamp-pct [x] (max 0.0 (min 100.0 (double (or x 0.0)))))

(defn- format-wire-projection [ref world focal-fit fitted]
  (let [lbl (rdata/entity-short-label (or (:entity world) ref))
        head (str "## " lbl " (" (pr-str ref) ")")
        f-txt (when-let [h (:hiccup focal-fit)] (render/hiccup->text h))
        sigs (:signals world)
        sig-txt (when (and (= :system (:kind world)) (map? sigs))
                  (let [sh (:seon.ui/hiccup (sig/render-signals-bar {:seon.signal/snapshot sigs}))]
                    (str "### Signals\n" (render/hiccup->text sh))))
        sel (:selected fitted)
        rel-lines (keep (fn [it]
                          (when (not= :unchanged (:status it))
                            (let [t (when-let [h (:hiccup it)] (render/hiccup->text h))]
                              (when (and (string? t) (not (str/blank? t)))
                                (let [tag (or (:relation it) (first (:rules it)) (:schema it) :related)]
                                  (str "### [" (name tag) "] "
                                       (rdata/entity-short-label (or (:data it) (:entity it)))
                                       "\n" t))))))
                        sel)
        parts (cond-> [head]
                (and (string? f-txt) (not (str/blank? f-txt))) (conj f-txt)
                (and (string? sig-txt) (not (str/blank? sig-txt))) (conj sig-txt)
                (seq rel-lines) (conj (str/join "\n\n" rel-lines)))]
    (str/join "\n\n" parts)))

(defn project-entity-view
  "ONE general entity-surface engine for BOTH humans (`:target :html`) and agents (`:target :wire`).
   Works identically for `[:seon.agent/id aid]`, `[:seon.system/id :root]`, and ANY entity lookup ref."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any [:maybe :map]] :map]]
   :cost/ticks 45000000}
  ([main-db branch-db focus-ref] (project-entity-view main-db branch-db focus-ref nil))
  ([main-db branch-db focus-ref opts]
   (let [o (if (map? opts) opts {})
         bdb (or branch-db main-db)
         ref (or (if (integer? focus-ref) [:db/id focus-ref] (rdata/normalize-inspect-ref focus-ref))
                 [:seon.system/id :root])
         [ra rv] ref
         target (if (= :wire (:target o)) :wire :html)
         world (cond
                 (= ra :seon.agent/id)
                 (let [res (if (fn? (:agent-gather-fn o))
                             ((:agent-gather-fn o))
                             (ctx/gather-agent-candidates main-db bdb rv {:agent (:agent o) :target target :semantic? true :enrich? true :situational? false}))
                       ag (or (:agent res) (:agent o) (rdata/pull-agent-entity main-db bdb rv))
                       aeid (or (get-in res [:world :agent-eid]) (sinsp/resolve-eid bdb main-db ref))]
                   {:ref ref :eid aeid :entity ag :kind :agent :virtual? (nil? aeid)
                    :focal-item (when (map? ag) {:entity (or aeid ref) :schema :seon.agent/entity :data ag :priority 100})
                    :candidates (:candidates res)
                    :ctx-trace (:ctx-trace res)})

                 :else
                 (sinsp/entity-world-candidates main-db bdb ref))
         default-max (case (:kind world) :agent 8 :system 12 10)
         budget (long (or (:token-budget o) 2400))
         focal-budget (max 240 (long (quot budget 3)))
         fopts (merge {:target target :main-db main-db :fallback-renderers rwire/fallback-entity-renderers}
                      (if (= target :html)
                        {:avail-w (long (or (:avail-w o) 960))
                         :area-budget (long (or (:area-budget o) 800000))
                         :max-items (long (or (:max-items o) default-max))}
                        {:token-budget (max 160 (- budget focal-budget))
                         :max-items (long (or (:max-items o) 16))
                         :already-shown (:already-shown o)})
                      (select-keys o [:strategy :weights :viewport-w :viewport-h :sidebar-open? :slot]))
         focal-fit (when-let [fi (:focal-item world)]
                     (first (:selected
                             (fit/fit-projections
                              bdb [fi]
                              (merge {:target target :main-db main-db :max-items 1
                                      :fallback-renderers rwire/fallback-entity-renderers}
                                     (if (= target :html)
                                       {:avail-w (long (or (:avail-w o) 960)) :area-budget 600000}
                                       {:token-budget focal-budget}))))))
         cands (vec (or (:candidates world) []))
         fitted (if (seq cands)
                  (fit/fit-projections bdb cands fopts)
                  {:selected [] :dropped [] :tokens 0 :area 0 :budget (or (:token-budget fopts) (:area-budget fopts) 0)
                   :target target :strategy (or (:strategy fopts) :breadth-first) :shown (or (:already-shown o) {})})
         wire-txt (when (= target :wire) (format-wire-projection (:ref world) world focal-fit fitted))]
     {:focus-ref (or (:ref world) ref)
      :focus-kind (or (:kind world) :entity)
      :target target
      :world world
      :focal-fit focal-fit
      :candidates cands
      :pool-count (count cands)
      :fitted fitted
      :selected (:selected fitted)
      :dropped (:dropped fitted)
      :signals (:signals world)
      :ctx-trace (:ctx-trace world)
      :shown (:shown fitted)
      :tokens (+ (long (:tokens focal-fit 0)) (long (:tokens fitted 0)))
      :text wire-txt})))

(defn- resolve-preview-item [mdb bdb ref]
  (let [[ra rv] ref]
    (case ra
      :seon.system/id
      (let [eid (sinsp/resolve-eid bdb mdb ref)
            ent (or (when eid (sinsp/pull-entity-best bdb mdb eid))
                    {:seon.system/id rv :seon.system/title "Seon Runtime"
                     :seon.system/description "Self-hosting agent OS — COW branches, Datahike graph, Flow runtime, EmbeddingGemma-2 vectors."})]
        {:entity (or eid ref) :schema :seon.system/entity :data ent :priority 100})

      :seon.signal/kind
      {:entity ref :schema :seon.signal/snapshot :data (sig/all-signals mdb) :priority 100}

      :seon.branch/name
      (let [tr (when ((db-schema mdb) :seon.test.run/branch)
                 (when-let [e (:e (first (d/datoms mdb :avet :seon.test.run/branch rv)))] (d/pull mdb '[*] e)))]
        {:entity ref :virtual-branch? true :branch rv :test-run tr :priority 100})

      (when-let [eid (sinsp/resolve-eid bdb mdb ref)]
        (when-let [ent (sinsp/pull-entity-best bdb mdb eid)]
          (let [ep (rlinks/enrich-entity-params (or bdb mdb) ent false)]
            (cond-> {:entity eid :data ent :priority 100} (seq ep) (assoc :params ep))))))))

(defn render-entity-preview-html
  "Fast (< 3 ms, commit-cached) low-context Upgrade-Ladder preview HTML fragment for `GET /preview?ref=...`."
  {:malli/schema [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any] :map]
   :cost/ticks 15000000}
  [main-db branch-db ref]
  (let [bdb (or branch-db main-db)
        norm (rdata/normalize-inspect-ref ref)]
    (if-not norm
      {:ok? false :status 400 :html "<div class=\"hovercard-body mono tiny muted\">Invalid entity ref.</div>"}
      (let [mc (live-cid main-db) bc (live-cid bdb)
            ck (when (and mc bc) [(get-in main-db [:config :store :id]) mc (:max-tx main-db) bc (:max-tx bdb) norm])
            compute
            (fn []
              (if-let [item (resolve-preview-item main-db bdb norm)]
                (if (:virtual-branch? item)
                  (let [tr (:test-run item)
                        h [:div {:class "hovercard-body" :data-preview-ref (pr-str norm) :data-chosen-fn "branch-preview"}
                           [:div {:class "pill-row"}
                            (badge "blue" "branch") [:strong {:class "mono tiny"} (str (:branch item))]
                            (when tr (badge (if (:seon.test.run/pass? tr) "green" "red")
                                            (if (:seon.test.run/pass? tr) "PASS" "FAIL")))]
                           [:div {:class "hovercard-foot mono tiny muted"} "branch-preview · Click for full view"]]]
                    {:ok? true :status 200 :chosen-fn :branch-preview :tokens 12 :html (render/hiccup->html h)})
                  (let [fitted (first (:selected
                                       (fit/fit-projections
                                        bdb [item]
                                        {:target :html :avail-w 360 :avail-h 220 :max-items 1 :area-budget 72000
                                         :main-db main-db :fallback-renderers rwire/fallback-entity-renderers})))
                        cfn (:chosen-fn fitted)
                        toks (long (:tokens fitted 0))
                        inner (or (:hiccup fitted)
                                  [:div {:class "ent-row mono tiny"} (rdata/entity-short-label (or (:data item) norm))])
                        h [:div {:class "hovercard-body" :data-preview-ref (pr-str norm) :data-chosen-fn (str cfn)}
                           inner
                           [:div {:class "hovercard-foot mono tiny muted"}
                            (str (or (some-> cfn name) "entity") " · " toks " tok · Click for full view")]]]
                    {:ok? true :status 200 :chosen-fn cfn :tokens toks :html (render/hiccup->html h)}))
                {:ok? false :status 404 :html (str "<div class=\"hovercard-body mono tiny muted\">Not found: " (pr-str norm) "</div>")}))]
        (if ck (store/lru-get! preview-html-cache 128 ck compute) (compute))))))

(defn- render-signals-cards [signals]
  (let [{:keys [level branch flow pressure context proof health]} signals
        p-pct (max (double (:ticks-pct pressure 0.0)) (double (:bytes-pct pressure 0.0)))
        pr-pct (if (pos? (long (:total-tests proof 0))) (* 100.0 (/ (double (:green proof 0)) (double (:total-tests proof 1)))) 100.0)
        h-pct (if (:ok? health) (min 100.0 (* 15.0 (double (:warnings health 0)))) 100.0)
        sc (fn [title lvl summary pct pulse? oc]
             [:button {:type "button" :class (str "sig-cell sig-cell-btn " (lvl-cls lvl)) :on-click oc}
              [:div {:class "sig-cell-head"}
               [:span {:class (str "sig-dot " (lvl-cls lvl) (when pulse? " sig-pulse"))}]
               [:strong title] [:span {:class "mono tiny muted"} (name (or lvl :calm))]]
              (when (some? pct) [:div {:class "heat-bar"} [:div {:class (str "heat-fill " (lvl-cls lvl)) :style {:width (str (clamp-pct pct) "%")}}]])
              [:div {:class "mono tiny muted"} (str summary)]])]
    [:section {:id "sys-signals-unit" :class "card stack-sm"}
     [:div {:class "card-head"} [:h2 "Operational Signals"]
      [:div {:class "pill-row"}
       (ent-link [:seon.branch/name (or branch :main)] (str (or branch :main)) "pill mono tiny")
       (badge (case level :hot "red" :warm "amber" "green") (name (or level :calm)))]]
     [:div {:class "sig-grid"}
      (sc "Flow" (:level flow) (:summary flow) nil (:pulse? flow) '(seon.web/inspect-entity! [:seon.signal/kind :flow]))
      (sc "Pressure" (:level pressure) (:summary pressure) p-pct false '(seon.web/inspect-entity! [:seon.signal/kind :pressure]))
      (sc "Context" (:level context) (:summary context) (:prefix-reuse-pct context) false '(seon.web/set-view-mode! :context))
      (sc "Proof" (:level proof) (:summary proof) pr-pct false '(seon.web/inspect-entity! [:seon.signal/kind :proof]))
      (sc "Health" (:level health) (:summary health) h-pct false '(seon.web/inspect-entity! [:seon.signal/kind :health]))]]))

(defn- render-semantic-search-bar [mdb vector-status sem-search]
  (let [idx-n (long (or (:indexed-count vector-status) (vflow/indexed-count mdb) 0))
        res (:results sem-search)]
    [:section {:id "sys-vectors-unit" :class "card stack-sm"}
     [:div {:class "card-head"} [:h2 "Semantic Search"]
      [:div {:class "pill-row"}
       (badge (if (pos? idx-n) "purple mono" "muted mono") (str idx-n " indexed"))
       (btn "btn sm" '(seon.web/refresh-vector-status!) "Refresh")]]
     [:form {:class "row-form" :on-submit '(seon.web/semantic-search-form!)}
      [:input {:class "inp sm flex-1" :name "query" :value (str (or (:query sem-search) ""))
               :placeholder "Jump to any var, doc, task, schema, or error via EmbeddingGemma-2…"}]
      [:select {:class "inp sm" :name "of"}
       [:option {:value ""} "All"] [:option {:value ":seon.code.var/name"} "Vars"]
       [:option {:value ":seon.code.doc/id"} "Docs"] [:option {:value ":seon.task/id"} "Tasks"]
       [:option {:value ":seon.schema/key"} "Schemas"] [:option {:value ":seon.error/id"} "Errors"]]
      [:button {:type "submit" :class "btn sm primary"} "Search"]]
     (when sem-search
       (if (false? (:ok? sem-search))
         [:div {:class "notice red tiny mono"} (str "Search error: " (or (:message sem-search) (:error sem-search)))]
         (if (empty? res)
           [:p {:class "muted tiny mono"} (str "No matches for " (pr-str (:query sem-search)) ".")]
           [:div {:class "stack-xs"}
            (for [hit (take 10 res)
                  :let [sc (double (or (:score hit) 0.0)) pct (max 4.0 (min 100.0 (* 100.0 (max 0.0 sc))))]]
              [:div {:class "ent-row"}
               (badge "purple" (name (or (:kind hit) :entity)))
               (ent-link (or (:ref hit) (when (integer? (:eid hit)) [:db/id (:eid hit)])) (:label hit) "mono tiny strong flex-1")
               [:div {:class "cos-bar"} [:div {:class "cos-fill" :style {:width (str pct "%")}}]]
               [:span {:class "mono tiny muted"} (str (/ (Math/round (* sc 1000.0)) 1000.0))]])])))]))

(defn- sys-group-key [it]
  (case (:relation it)
    (:system/open-error :system/failing-test) :alerts
    (:system/agent :system/pending-call) :agents
    :tasks))

(defn render-system-entity-body
  "Renders `[:seon.system/id :root]` through the EXACT SAME `project-entity-view` Upgrade Ladder
   (focal `:seon.system/entity` card + live 5 signals + compact semantic search + ranked high-signal live entities)."
  {:malli/schema [:=> [:cat :map] [:vector :any]] :cost/ticks 40000000}
  [{:keys [main-db branch-db signals branch-feedback vector-status semantic-search slot-budget inspect-trail inspect-forward]}]
  (if-not (or (instance? datahike.db.interface.ISearch main-db) (instance? datahike.db.interface.ISearch branch-db))
    [:div {:id "seon-system-unit" :class "card muted"} "No database connected."]
    (let [main-db (if (instance? datahike.db.interface.ISearch main-db) main-db branch-db)
          branch-db (if (instance? datahike.db.interface.ISearch branch-db) branch-db main-db)
          aw (long (:avail-w slot-budget 1000))
          stack? (< aw 760)
          proj (project-entity-view main-db branch-db [:seon.system/id :root]
                                    {:target :html :avail-w aw :area-budget (max 420000 (long (:area-budget slot-budget 900000)))
                                     :max-items 12})
          sel (:selected proj)
          by-g (group-by sys-group-key sel)
          sigs (or signals (:signals proj) (sig/all-signals main-db))]
      [:div {:id "seon-system-unit" :class "stack" :data-focus-ref "[:seon.system/id :root]"}
       (feedback-banner branch-feedback)
       (when (or (seq inspect-trail) (seq inspect-forward))
         [:div {:id "insp-nav-bar" :class "insp-bar"}
          [:div {:class "insp-nav-left"}
           (btn "btn sm" '(seon.web/inspect-back!) "← Back" (empty? inspect-trail))
           (btn "btn sm" '(seon.web/inspect-forward!) "→ Forward" (empty? inspect-forward))]
          (btn "nav-pill tiny" '(seon.web/set-explain-lens! true [:seon.system/id :root]) "Explain →")])
       [:section {:id "sys-focal-unit" :class "stack-xs"}
        (or (get-in proj [:focal-fit :hiccup])
            [:div {:class "card ent-card"} (ent-link [:seon.system/id :root] "Seon Runtime" "strong")])]
       (render-signals-cards sigs)
       (render-semantic-search-bar main-db vector-status semantic-search)
       [:section {:id "sys-entities-unit" :class "stack"}
        [:div {:class "row-between"}
         [:h2 (str "Active System Focus (" (count sel) ")")]
         [:div {:class "pill-row"}
          (mut (str (count sel) " of " (:pool-count proj 0) " · Upgrade Ladder"))
          (btn "nav-pill tiny" '(seon.web/set-explain-lens! true [:seon.system/id :root]) "Explain selection →")]]
        (if (empty? sel)
          [:div {:class "card muted sm-copy"} "System idle — no open errors, active agents, or open tasks."]
          [:div {:class (if stack? "stack" "insp-neighbors")}
           (for [[gk title] [[:alerts "Alerts & Failures"] [:agents "Active Agents"] [:tasks "Open Tasks"]]
                 :let [xs (get by-g gk)] :when (seq xs)]
             [:section {:class "ctx-group stack-sm"}
              [:div {:class "ctx-group-head"} (kicker title) (mut (str (count xs)))]
              (for [it xs
                    :let [r (or (rdata/entity-lookup-ref (:data it)) (when (integer? (:entity it)) [:db/id (:entity it)]))
                          cfn (:chosen-fn it)]]
                [:div {:class "surface-entity-item" :data-entity (str (:entity it)) :data-chosen-fn (str cfn)}
                 [:div {:class "ent-prov-bar"}
                  [:div {:class "pill-row"}
                   (when (qualified-symbol? cfn) (ent-link [:seon.code.var/name cfn] (name cfn) "mono tiny muted"))
                   (mut (str "· " (:tokens it 0) " tok"))]
                  (when (= :seon.agent/id (first r))
                    (btn "btn sm" (list 'seon.web/view-agent! (second r) :session) "Session →"))]
                 (or (:hiccup it)
                     [:div {:class "ent-row mono tiny"} (ent-link r (rdata/entity-short-label (or (:data it) r)) "mono tiny strong")])])])])]])))

(defn render-entity-explain-body
  "Explain Current Screen lens for ANY non-agent focal entity (`[:seon.system/id :root]`, var, task, rule, etc.):
   ALL renderer variations + Selected vs Dropped neighborhood ranking + live budget/width/strategy/weight controls + UI walk cache."
  {:malli/schema [:=> [:cat :map] [:vector :any]] :cost/ticks 40000000}
  [{:keys [main-db branch-db inspect-ref lab-prefs slot-budget walk-summary branch-feedback]}]
  (if-not (or (instance? datahike.db.interface.ISearch main-db) (instance? datahike.db.interface.ISearch branch-db))
    [:div {:id "seon-entity-explain-unit" :class "card muted"} "No database connected."]
    (let [main-db (if (instance? datahike.db.interface.ISearch main-db) main-db branch-db)
          bdb (or branch-db main-db)
          ref (or inspect-ref [:seon.system/id :root])
          lp (rdata/normalize-lab-prefs lab-prefs)
          aw (long (:avail-w slot-budget 1000)) stack? (< aw 720)
          world (sinsp/entity-world-candidates main-db bdb ref)
          focal-ex (when-let [fi (:focal-item world)]
                     (fit/explain-entity-fit bdb fi {:target (:inspect-target lp) :token-budget (:inspect-budget lp)
                                                     :avail-w (:inspect-width lp) :main-db main-db
                                                     :area-budget (* (long (:inspect-width lp)) 1200)
                                                     :fallback-renderers rwire/fallback-entity-renderers}))
          cands (vec (or (:candidates world) []))
          nb-ex (fit/explain-candidates bdb cands
                                        {:target (:target lp) :token-budget (:token-budget lp)
                                         :avail-w (:avail-w lp) :area-budget (* (long (:avail-w lp)) 1200)
                                         :strategy (:strategy lp) :weights (:weights lp)
                                         :main-db main-db :fallback-renderers rwire/fallback-entity-renderers})
          ent-by-id (into {} (map (juxt :entity :data)) cands)
          walk-c (scache/analyze-walk-cache walk-summary)
          chip (fn [act? p lbl] [:button {:type "button" :class (str "lab-chip" (when act? " active"))
                                          :on-click (list 'seon.web/update-lab-prefs! p)} lbl])]
      [:div {:id "seon-entity-explain-unit" :class "stack" :data-focus-ref (pr-str ref)}
       (feedback-banner branch-feedback)
       [:div {:class "insp-bar"}
        [:div {:class "pill-row"}
         (badge "purple" "EXPLAIN")
         (ent-link ref (rdata/entity-short-label (or (:entity world) ref)) "mono strong")
         (mut (pr-str ref))]
        (btn "btn sm primary" '(seon.web/set-explain-lens! false) "← Back to Explore")]
       (when focal-ex (sinsp/render-variations-panel focal-ex lp stack? true))
       [:section {:id "ctx-candidates-unit" :class "card stack-sm"}
        [:div {:class "card-head"}
         [:h2 (str "Neighborhood Selection & Ranking (" (:selected-count nb-ex 0) " selected · " (:dropped-count nb-ex 0) " dropped)")]
         [:div {:class "pill-row"}
          (badge "blue mono" (str (:tokens nb-ex 0) " / " (:budget nb-ex 0) (if (= :html (:target nb-ex)) " px²" " tok")))
          (btn "btn sm" '(seon.web/reset-lab-prefs!) "Reset")]]
        [:div {:class "lab-controls"}
         (kicker "Target") (chip (= :wire (:target lp)) {:target :wire} "Model Wire") (chip (= :html (:target lp)) {:target :html} "Browser UI")
         (kicker "Budget") (for [[b l] [[600 "600"] [2400 "2.4k"] [8000 "8k"]]] (chip (= b (:token-budget lp)) {:token-budget b} l))
         (kicker "Strategy") (chip (= :breadth-first (:strategy lp)) {:strategy :breadth-first} "Breadth-First")
         (chip (= :depth-first (:strategy lp)) {:strategy :depth-first} "Depth-First")]
        (if (empty? (:items nb-ex))
          [:p {:class "muted sm-copy"} "No related neighborhood candidates for this entity."]
          [:div {:class "table-scroll"}
           [:table {:class "data-table"}
            [:thead [:tr [:th "Entity"] [:th "Score"] [:th "Chosen / Reason"] [:th "Cost"]]]
            [:tbody
             (for [it (take 16 (:items nb-ex))
                   :let [ent (get ent-by-id (:entity it))
                         r (or (rdata/entity-lookup-ref ent) (when (integer? (:entity it)) [:db/id (:entity it)]))
                         win (or (some #(when (:chosen? %) %) (:variations it)) (first (:variations it)))
                         cfn (or (:chosen-fn it) (:fn win))
                         s (:rank/signals it)]]
               [:tr
                [:td [:div {:class "stack-xs"}
                      (ent-link r (rdata/entity-short-label (or ent r)) "mono tiny strong")
                      (badge (if (:selected? it) "green" "muted") (name (or (:status it) :dropped)))]]
                [:td {:class "mono tiny"}
                 [:div {:class "strong"} (str (r2 (:rank/score it)))]
                 (mut (str "U" (r2 (:urgency s)) " R" (r2 (:recency s)) " C" (r2 (:convergence s))
                           (when (contains? s :semantic) (str " S" (r2 (:semantic s))))))]
                [:td [:div {:class "stack-xs"}
                      (when (qualified-symbol? cfn) (ent-link [:seon.code.var/name cfn] (name cfn) "mono tiny strong"))
                      (mut (or (:reason win) (if (:selected? it) "Selected" "Dropped")))]]
                [:td {:class "mono tiny muted"} (str (:tokens it 0) " tok")]])]]])]
       (when (seq (:units walk-c))
         [:section {:class "card stack-xs"}
          [:div {:class "row-between"} (kicker "UI Incremental Walk Cache (datahike.reads)") (mut (:summary walk-c))]
          [:div {:class "table-scroll"}
           [:table {:class "data-table"}
            [:thead [:tr [:th "Unit"] [:th "Status"] [:th "Reads"] [:th "ms"]]]
            [:tbody (for [u (:units walk-c)]
                      [:tr [:td {:class "mono tiny"} (str "#" (:id u))]
                       [:td (badge (if (:hit? u) "green" "amber") (if (:hit? u) "HIT" "MISS"))]
                       [:td {:class "mono tiny"} (str (:reads-count u 0))]
                       [:td {:class "mono tiny muted"} (str (:ms u 0.0) " ms")]])]]]])])))
