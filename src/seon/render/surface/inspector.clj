(ns seon.render.surface.inspector
  "Fractal Universal Hyperlinked Entity Surface: 2-read graph discovery (`:eavt` + reverse `:avet`
   + `:seon.system/id` live overview) + live `seon.vector/near` + Upgrade Ladder."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.browser.preview :as preview]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks :refer [badge db-schema ent-link kicker mut]]
            [seon.render.wire :as rwire]
            [seon.signals :as sig]
            [seon.web.router]
            [seon.ctx :as ctx]))

(defn- btn [cls oc lbl & [dis?]] [:button (cond-> {:type "button" :class cls :on-click oc} dis? (assoc :disabled true)) lbl])

(defn- trunc [s n] (if (> (count s) n) (str (subs s 0 n) "…") s))

(def ^:private value-ref-targets
  {:seon.code.var/ns :seon.code.ns/name :seon.code.var/form :seon.code.form/key
   :seon.code.form/ns :seon.code.ns/name :seon.code.ns/requires :seon.code.ns/name
   :seon.task/scope :seon.code.ns/name :seon.task/owner :seon.agent/id
   :seon.task/created-by :seon.agent/id :seon.task/template :seon.task/id
   :seon.task/needs :seon.task/id :seon.task/tests :seon.code.var/name
   :seon.agent/scope :seon.code.ns/name
   :seon.agent/parent :seon.agent/id :seon.agent.call/caller :seon.agent/id
   :seon.agent.call/callee :seon.agent/id :seon.agent.call/result-schema :seon.schema/key
   :seon.agent.call/parent-call :seon.agent.call/id :seon.error/agent :seon.agent/id
   :seon.error/var :seon.code.var/name :seon.error/eval :seon.agent.eval/id
   :seon.error/kind :seon.fix/error-class :seon.fix/regression-test :seon.code.var/name
   :seon.agent.eval/agent :seon.agent/id :seon.agent.eval/result-schema :seon.schema/key
   :seon.test/var :seon.code.var/name :seon.schema/render :seon.code.var/name
   :seon.schema/ref-targets :seon.schema/key})

(defn resolve-eid
  "Resolves lookup ref `[attr val]` to an integer EID in `bdb` or fallback `mdb`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :any] [:maybe :int]]
   :cost/ticks 500000}
  [bdb mdb ref]
  (when (and (vector? ref) (= 2 (count ref)))
    (let [[a v] ref]
      (cond (= a :db/id) (when (integer? v) v)
            (and bdb ((db-schema bdb) a)) (or (:e (first (d/datoms bdb :avet a v)))
                                              (when (and mdb (not= mdb bdb) ((db-schema mdb) a))
                                                (:e (first (d/datoms mdb :avet a v)))))
            (and mdb ((db-schema mdb) a)) (:e (first (d/datoms mdb :avet a v)))
            :else nil))))

(defn pull-entity-best
  "Pulls entity map for `eid` from `bdb` with fallback to `mdb`."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] :any] [:maybe :map]]
   :cost/ticks 500000}
  [bdb mdb eid]
  (when (integer? eid)
    (let [b (when bdb (d/pull bdb '[*] eid))]
      (if (and (map? b) (seq (dissoc b :db/id)))
        b
        (when (and mdb (not= mdb bdb))
          (let [m (d/pull mdb '[*] eid)]
            (when (and (map? m) (seq (dissoc m :db/id))) m)))))))

(defn entity-world-candidates
  "Thin adapter over the ONE unified `seon.ctx/project-entity` kernel for any focal lookup ref."
  {:malli/schema [:function
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any] :map]
                  [:=> [:cat :seon.host/db [:maybe :seon.host/db] :any [:maybe :map]] :map]]
   :cost/ticks 45000000}
  ([main-db branch-db ref-or-eid] (entity-world-candidates main-db branch-db ref-or-eid nil))
  ([main-db branch-db ref-or-eid opts]
   (let [o (if (map? opts) opts {})
         ref (if (integer? ref-or-eid) [:db/id ref-or-eid] (rdata/normalize-inspect-ref ref-or-eid))
         bdb (if (= :seon.system/id (first ref)) main-db (or branch-db main-db))
         proj (ctx/project-entity main-db bdb ref (merge {:target :html :include-unmatched? true} o))
         [ra rv] (or (:focus-ref proj) ref)
         dh (db-schema main-db)
         virt? (contains? #{:seon.system/id :seon.branch/name :seon.signal/kind} ra)
         trun (when (and (= ra :seon.branch/name) (dh :seon.test.run/branch))
                (when-let [e (:e (first (d/datoms main-db :avet :seon.test.run/branch rv)))]
                  (d/pull main-db (quote [*]) e)))
         sigs (when (contains? #{:seon.system/id :seon.signal/kind} ra) (sig/all-signals main-db))
         focal-data (or (:focus-data proj)
                        (case ra
                          :seon.branch/name {:seon.branch/name rv :test-run trun}
                          :seon.signal/kind sigs
                          nil))
         focal-item (cond
                      (and (map? (:focal-fit proj)) (map? (:data (:focal-fit proj))))
                      {:entity (or (:focus-eid proj) ref) :schema (:focus-schema proj) :data (:data (:focal-fit proj)) :priority 100}
                      (map? focal-data)
                      (cond-> {:entity (or (:focus-eid proj) ref) :data focal-data :priority 100}
                        (:focus-schema proj) (assoc :schema (:focus-schema proj))
                        (= ra :seon.signal/kind) (assoc :schema :seon.signal/snapshot))
                      :else nil)]
     (cond-> {:ref (or (:focus-ref proj) ref)
              :eid (:focus-eid proj)
              :entity (:focus-data proj)
              :kind (:focus-kind proj)
              :virtual? virt?
              :focal-item focal-item
              :focal-fit (:focal-fit proj)
              :candidates (vec (:candidates proj))
              :unmatched (vec (:unmatched proj))
              :fitted (:fitted proj)
              :rule-trace (:rule-trace proj)
              :ctx-trace (:ctx-trace proj)
              :world (:world proj)
              :project proj}
       (= ra :seon.branch/name) (assoc :branch-name rv :test-run trun)
       (= ra :seon.signal/kind) (assoc :signal-kind rv :signals sigs)
       (= ra :seon.system/id) (assoc :signals sigs)))))

(defn render-variations-panel
  "Renders all renderer variations side-by-side (`Browser HTML` vs `Model Text`)."
  {:malli/schema [:function [:=> [:cat :map :map :boolean] [:vector :any]]
                  [:=> [:cat :map :map :boolean :boolean] [:vector :any]]]
   :cost/ticks 10000000}
  ([explained lab-prefs stack?] (render-variations-panel explained lab-prefs stack? false))
  ([explained lab-prefs stack? expanded?]
   (let [vars (:variations explained)
         it (:inspect-target lab-prefs) ib (:inspect-budget lab-prefs) iw (:inspect-width lab-prefs)
         win-fn (some #(when (:chosen? %) (:fn %)) vars)
         chip (fn [act? p lbl] [:button {:type "button" :class (str "lab-chip" (when act? " active"))
                                         :on-click (list 'seon.web/update-lab-prefs! p)} lbl])
         ctrls [:div {:class "lab-controls"}
                (chip (= it :html) {:inspect-target :html} "Browser UI")
                (chip (= it :wire) {:inspect-target :wire} "Model Wire")
                (for [b [200 600 2400]] (chip (= ib b) {:inspect-budget b} (str b " tok")))
                (for [w [360 640 1100]] (chip (= iw w) {:inspect-width w} (str w "px")))]
         body (if (empty? vars)
                [:p {:class "muted sm-copy"} "No `:seon.ui/hiccup` variations discovered for this entity."]
                [:div {:class "stack-sm"}
                 (for [v vars :let [w? (:chosen? v) f (:fn v)]]
                   [:div {:class (str "var-card" (when w? " is-winner"))}
                    [:div {:class "card-head"}
                     [:div {:class "pill-row"}
                      (when w? (badge "green" "WINNER"))
                      (if (qualified-symbol? f) (ent-link [:seon.code.var/name f] (str f) "mono strong") [:span {:class "mono strong"} (str f)])
                      (badge (if w? "green" "muted") (:reason v))]
                     (mut (str (:tokens v 0) " tok · min " (:min-width v 0) "px · " (:area v 0) " px² · " (:lines v 0) "L"))]
                    [:div {:class (if stack? "debug-tile-pair is-stack" "debug-tile-pair")}
                     [:div {:class "debug-tile-col"} (kicker "Browser HTML") (or (:hiccup v) (mut "(empty)"))]
                     [:div {:class "debug-tile-col"} (kicker "Model Text (hiccup->text)") [:pre {:class "code-box tiny"} (trunc (str (:text v)) 900)]]]])])]
     (if expanded?
       [:section {:id "insp-variations-unit" :class "card stack-sm"}
        [:div {:class "card-head"}
         [:div {:class "pill-row"} [:h2 (str "All Renderer Variations (" (count vars) ")")]
          (badge "purple mono" (if (= it :wire) "Model Wire" "Browser UI"))
          (mut (str "budget " ib " tok · width " iw "px"))] ctrls]
        body]
       [:details {:id "insp-variations-unit" :class "card fold"}
        [:summary {:class "row-between mono tiny"}
         [:span (str "Renderer Variations (" (count vars) ")" (when win-fn (str " · Winner: " (name win-fn))))]
         (mut (str (if (= it :wire) "Model Wire" "Browser UI") " · " ib " tok · " iw "px"))]
        [:div {:class "stack-sm" :style {:margin-top "10px"}} ctrls body]]))))

(defn- render-focal-extras
  ([world main-db] (render-focal-extras world main-db nil))
  ([world main-db branch-db]
   (let [ent (:entity world)]
     (cond
       (= :signal (:kind world))
       (let [sk (:signal-kind world) snap (get-in world [:focal-item :data]) sm (get snap sk)]
         [:section {:class "card stack-sm"}
          [:div {:class "card-head"} [:h2 (str (str/capitalize (name sk)) " Signal")]
           [:div {:class "pill-row"}
            (when-let [br (:branch snap)] (ent-link [:seon.branch/name br] (str br) "pill mono tiny"))
            (badge (case (:level sm) :hot "red" :warm "amber" "green") (name (or (:level sm) :calm)))]]
          [:div {:class "pill-row"}
           (for [k [:flow :pressure :context :proof :health]]
             (ent-link [:seon.signal/kind k] (str/capitalize (name k)) (str "pill mono tiny" (when (= k sk) " strong"))))]
          [:p {:class "mono sm-copy"} (str (:summary sm))]
          [:pre {:class "code-box tiny"} (pr-str (dissoc sm :summary))]])

       (= :branch (:kind world))
       [:section {:class "card stack-sm"}
        [:div {:class "card-head"} [:h2 (str "Branch " (:branch-name world))]
         [:div {:class "pill-row"}
          (when-let [ag (get-in world [:test-run :seon.test.run/agent])] (ent-link [:seon.agent/id ag] (str ag) "pill mono tiny"))
          (when-let [tr (:test-run world)] (badge (if (:seon.test.run/pass? tr) "green" "red") (if (:seon.test.run/pass? tr) "Tests PASS" "Tests FAIL")))]]
        (when-let [fds (seq (get-in world [:test-run :seon.test.run/failed-digests]))] (rlinks/pill-list "failed verdicts:" fds :seon.test/digest 8))
        (when-let [tr (:test-run world)] [:pre {:class "code-box tiny"} (pr-str (dissoc tr :db/id))])]

       (:seon.agent.eval/id ent)
       (let [pt (long (or (:seon.agent.eval/prompt-tokens ent) 0)) ht (long (or (:seon.agent.eval/cache-hit-tokens ent) 0))
             pct (if (pos? pt) (/ (Math/round (* 1000.0 (/ (double ht) (double pt)))) 10.0) 0.0)]
         [:section {:class "card stack-sm"}
          [:div {:class "card-head"} [:h2 "Eval Telemetry"]
           [:div {:class "pill-row"}
            (when-let [ag (:seon.agent.eval/agent ent)] (ent-link [:seon.agent/id ag] (str ag) "pill mono tiny"))
            (when-let [rs (:seon.agent.eval/result-schema ent)] (ent-link [:seon.schema/key rs] (str rs) "pill mono tiny"))
            (badge (if (>= pct 80.0) "green" "amber") (str "KV hit " pct "% (" ht "/" pt " tok)"))]]
          (when-let [r (not-empty (str (:seon.agent.eval/reasoning ent "")))]
            [:details {:class "activity-fold" :open true}
             [:summary {:class "mono tiny"} (str "Reasoning (" (count r) " chars)")]
             [:pre {:class "code-box tiny"} (trunc r 2400)]])])

       (and (:seon.code.var/name ent) (preview/hiccup-renderer-var? ent))
       (preview/render-derived-preview-card (or branch-db main-db) ent)

       (:seon.schema/key ent)
       (when-let [da (get (db-schema main-db) (:seon.schema/key ent))]
         [:section {:class "card stack-xs"} (kicker "Derived Datahike Attribute") [:pre {:class "code-box tiny"} (pr-str da)]])

       :else nil))))

(defn- render-generic-focal [ent ref dh]
  (when (map? ent)
    (let [rows (for [[k v] (sort-by (comp str key) (dissoc ent :db/id)) :when (some? v)
                     :let [ta (get value-ref-targets k)
                           kn (if (contains? dh k) (ent-link [:seon.schema/key k] (str k) "mono tiny") (str k))
                           vn (cond (and ta (not (coll? v))) (ent-link [ta v] (str v) "mono tiny")
                                    (and (vector? v) (rdata/normalize-inspect-ref v)) (ent-link v (rdata/entity-short-label v) "mono tiny")
                                    (and (map? v) (integer? (:db/id v))) (ent-link [:db/id (:db/id v)] (str "#" (:db/id v)) "mono tiny")
                                    :else (trunc (pr-str v) 240))]]
                 [[:dt kn] [:dd vn]])]
      [:div {:class "card ent-card stack-sm"}
       [:div {:class "card-head"} (ent-link ref (rdata/entity-short-label (or ent ref)) "mono strong")]
       (when (seq rows) (into [:dl {:class "kv-grid"}] (mapcat identity rows)))])))

(defn- render-entity-spawn-composer [ref ent]
  (when (and ref (not= :seon.agent/id (first ref)))
    (let [lbl (rdata/entity-short-label (or ent ref))]
      [:form {:id "insp-spawn-composer"
              :class "ws-composer"
              :on-submit (list 'seon.web/spawn-on-entity-form! ref)}
       [:div {:class "composer-shell"}
        [:input {:class "composer-inp" :type "text" :name "prompt"
                 :placeholder (str "Start an agent focused on " lbl "…")}]
        [:button {:type "submit" :class "btn sm primary"} "Start agent"]]])))

(defn- render-entity-actions [viewing-id agent-lbl ref lp]
  (let [[ra rv] ref
        rule-dis? (and (= ra :seon.rule/id) (contains? (:disabled-rules lp) rv))]
    [:div {:class "entity-actions"}
     (when (= ra :seon.agent/id)
       (list (btn "btn sm primary" (list 'seon.web/view-agent! rv :canvas) "Open Canvas")
             (btn "btn sm" (list 'seon.web/view-agent! rv :session) "Open Chat")))
     (when (= ra :seon.rule/id)
       (btn (if rule-dis? "btn sm success" "btn sm danger") (list 'seon.web/toggle-lab-rule! rv)
            (if rule-dis? "Enable in Lab" "Disable in Lab")))
     (when (= ra :seon.branch/name)
       (list (btn "btn sm" (list 'seon.web/run-tests! rv) "Test Branch")
             (when (not= rv :main) (btn "btn sm success" (list 'seon.web/merge-branch! rv) "Merge → :main"))))
     (when (and ref (not (#{:seon.signal/kind :seon.branch/name :seon.system/id} ra)))
       (btn "btn sm" (list 'seon.web/pin-to-agent! viewing-id ref) (str "+ Pin to " agent-lbl)))
     (btn "btn sm" '(seon.web/inspect-close!) "Close ×")]))

(defn- render-structural-prov
  "Plain structural provenance bar: rule fired -> actual renderer var -> rule pinned :fn vs auto-fit -> real tokens/px."
  [item ref]
  (let [cfn (:chosen-fn item)
        pinned? (and (qualified-symbol? (:fn item)) (= (:fn item) cfn))
        r0 (first (filter qualified-keyword? (:rules item)))
        mb (name (or (:measured-by item) :static))
        dims (when (and (pos? (long (or (:w item) 0))) (pos? (long (or (:h item) 0))))
               (str " · " (:w item) "×" (:h item)))]
    [:div {:class "ent-prov-bar"}
     [:div {:class "pill-row"}
      (when r0 (ent-link [:seon.rule/id r0] (name r0) "badge blue tiny mono"))
      (when (qualified-symbol? cfn)
        (ent-link [:seon.code.var/name cfn] (name cfn) "mono tiny strong"))
      (badge (if pinned? "amber" "muted") (if pinned? "rule pinned :fn" "auto-fit"))
      (mut (str (:tokens item 0) " tok" dims " · " mb))]
     (when ref (ent-link ref "Open →" "tiny mono"))]))

(defn- render-rule-groups [selected dh]
  (let [by-rule (group-by #(or (first (filter qualified-keyword? (:rules %))) :direct/ref) selected)]
    (for [[rk items] (sort-by (comp str key) by-rule)]
      [:section {:class "ctx-group stack-sm"}
       [:div {:class "ctx-group-head"}
        (if (qualified-keyword? rk)
          (ent-link [:seon.rule/id rk] (str rk) "mono tiny strong")
          (kicker "Direct Context"))
        (mut (str (count items)))]
       (for [it items
             :let [ent (:data it)
                   r (or (:ref it) (rdata/entity-lookup-ref ent)
                         (when (integer? (:entity it)) [:db/id (:entity it)]))]]
         [:div {:class "surface-entity-item"
                :data-entity (str (:entity it))
                :data-chosen-fn (str (:chosen-fn it))
                :data-measured-by (name (or (:measured-by it) :static))}
          (render-structural-prov it r)
          (or (:hiccup it)
              (when (map? ent) (render-generic-focal ent r dh))
              [:div {:class "ent-row mono tiny"} (ent-link r (rdata/entity-short-label (or ent r)) "mono tiny strong")])])])))

(defn- render-unmatched-neighbors [unmatched]
  (let [vis (filterv #(not (#{:internal :never} (:policy %))) unmatched)]
    (when (seq vis)
      (let [by-attr (group-by #(or (:via-attr %) :graph/neighbor) vis)]
        [:section {:id "insp-unmatched-unit" :class "card stack-sm"}
         [:div {:class "row-between"}
          [:div {:class "pill-row"}
           (kicker (str "Direct Graph Neighbors (" (count vis) ")"))
           (badge "muted" "1–2 hop · unmatched by rules")]
          (mut "Edges in Datahike not claimed by active perspective rules")]
         [:div {:class "stack-sm"}
          (for [[attr xs] (take 10 (sort-by #(- (count (val %))) by-attr))
                :let [shown (take 16 xs) more (- (count xs) (count shown))]]
            [:div {:class "stack-xs"}
             [:div {:class "row-between"}
              [:span {:class "mono tiny strong"} (str attr)]
              (mut (str (count xs)))]
             [:div {:class "pill-row"}
              (for [u shown
                    :let [r (or (:ref u) (when (integer? (:entity u)) [:db/id (:entity u)]))
                          lbl (or (not-empty (str (:label u))) (rdata/entity-short-label r))]]
                (if r
                  (ent-link r lbl "pill mono tiny")
                  [:span {:class "pill mono tiny"} lbl]))
              (when (pos? more)
                [:span {:class "mono tiny muted"} (str "+" more " more")])]])]]))))

(defn render-inspector-body
  "Perspective-aware General Entity Browser over the ONE `seon.ctx/project-entity` pipeline."
  {:malli/schema [:=> [:cat :map] [:vector :any]] :cost/ticks 45000000}
  [{:keys [main-db branch-db viewing-id agent inspect-ref lab-prefs slot-budget branch-feedback]}]
  (if-not (or main-db branch-db)
    [:div {:id "seon-inspector-unit" :class "card muted"} "No database connected."]
    (let [main-db (or main-db branch-db)
          bdb (or branch-db main-db)
          lp (rdata/normalize-lab-prefs lab-prefs)
          ref (or inspect-ref [:seon.agent/id viewing-id])
          aw (long (:avail-w slot-budget 1000))
          ab (max 420000 (long (:area-budget slot-budget 800000)))
          pib (:per-item-budget slot-budget)
          stack? (< aw 720)
          dh (db-schema main-db)
          world (entity-world-candidates main-db bdb ref
                  (cond-> {:target :html :avail-w aw :area-budget ab :max-items 12
                           :perspective-agent viewing-id :agent agent :include-unmatched? true}
                    pib (assoc :per-item-budget pib)))
          ex-opts {:target (:inspect-target lp) :token-budget (:inspect-budget lp) :avail-w (:inspect-width lp)
                   :main-db main-db :area-budget (* (long (:inspect-width lp)) 1200)
                   :fallback-renderers rwire/fallback-entity-renderers}
          explained (when-let [fi (:focal-item world)]
                      (fit/explain-entity-fit bdb fi ex-opts))
          sel-nb (vec (get-in world [:fitted :selected]))
          drop-nb (vec (get-in world [:fitted :dropped]))
          unmatched (vec (:unmatched world))
          winner-h (or (some #(when (:chosen? %) (:hiccup %)) (:variations explained))
                       (get-in world [:focal-fit :hiccup]))
          ag-lbl (rdata/agent-label agent viewing-id)
          acts (render-entity-actions viewing-id ag-lbl (:ref world) lp)]
      [:div {:id "seon-inspector-unit" :class "stack" :data-perspective-agent (str viewing-id)}
       (when branch-feedback
         [:div {:class (str "notice row-between " (if (:ok? branch-feedback) "green" "red"))}
          [:span (:text branch-feedback)] (btn "link-muted tiny" (quote (seon.web/dismiss-feedback!)) "×")])
       (if (and (not (:virtual? world)) (nil? (:entity world)))
         [:section {:class "card stack-sm"}
          [:div {:class "row-between"} [:h2 "Entity not found"] acts]
          [:p {:class "muted sm-copy mono"} (str "No entity matched " (pr-str ref) " on current DB.")]]
         (list
          [:section {:id "insp-focal-unit" :class "stack-xs"}
           [:div {:class "row-between insp-perspective-bar"}
            [:div {:class "pill-row"}
             (badge "blue" (str "Perspective: " ag-lbl))
             (when-let [sk (get-in world [:focal-item :schema])]
               (ent-link [:seon.schema/key sk] (str sk) "pill mono tiny"))
             (btn "nav-pill tiny" (list (quote seon.web/set-explain-lens!) true (:ref world)) "Explain →")]
            acts]
           (or winner-h (render-generic-focal (:entity world) (:ref world) dh))]
          (render-focal-extras world main-db bdb)
          [:section {:id "insp-neighborhood-unit" :class "stack"}
           [:div {:class "row-between"}
            [:div {:class "pill-row"}
             [:h2 (str "Rule-Matched Context (" (count sel-nb) ")")]
             (when (seq drop-nb) (badge "muted" (str (count drop-nb) " trimmed by budget")))]
            (mut (str "From " ag-lbl " perspective · " (count (:candidates world)) " rule matches"))]
           (if (empty? sel-nb)
             [:div {:class "card muted sm-copy"} "No active perspective rules matched entities here."]
             [:div {:class (if stack? "stack" "insp-neighbors")}
              (render-rule-groups sel-nb dh)])]
          (render-unmatched-neighbors unmatched)
          (when explained (render-variations-panel explained lp stack? false))
          (render-entity-spawn-composer (:ref world) (:entity world))))])))