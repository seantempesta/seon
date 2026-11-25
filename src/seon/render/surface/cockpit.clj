(ns seon.render.surface.cockpit
  "Live Watchable Self-Building Context Cockpit: Pause-and-Fork Tuning Loop (3 Levers + live Before/After diff),
   3-Bucket 1–2 Hop Candidate Universe (Selected / Trimmed / Unmatched), and Live NASA Completeness Matrix."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.ctx.touches :as ctouches]
            [seon.health.coverage :as cov]
            [seon.render.data :as rdata]
            [seon.render.links :as rlinks :refer [badge btn db-schema ent-link kicker mut]]
            [seon.render.status :as rstatus]
            [seon.store :as store :refer [live-cid]]
            [seon.store.query :as sq]))

(defonce ^:private ^:seon.flow/edge tuner-diff-cache (store/lru-cache 16))

(defn- r2 [x] (/ (Math/round (* (double (or x 0.0)) 100.0)) 100.0))

(defn- focal-agent-id [main-db v]
  (cond
    (qualified-keyword? v) v
    (and (vector? v) (= :seon.agent/id (first v))) (second v)
    (and (map? v) (qualified-keyword? (:seon.agent/id v))) (:seon.agent/id v)
    (and (map? v) (integer? (:db/id v)) main-db)
    (:v (first (d/datoms main-db :eavt (:db/id v) :seon.agent/id)))
    :else nil))

(defn- classify-branch-levers [main-db bdb]
  (let [writes (when (and main-db bdb (not= main-db bdb))
                 (ctouches/branch-write-eids main-db bdb nil))
        dh (when bdb (db-schema bdb))]
    (reduce
     (fn [acc eid]
       (let [sk (when (and dh (dh :seon.schema/key)) (:v (first (d/datoms bdb :eavt eid :seon.schema/key))))
             rk (when (and dh (dh :seon.rule/id)) (:v (first (d/datoms bdb :eavt eid :seon.rule/id))))
             vn (when (and dh (dh :seon.code.var/name)) (:v (first (d/datoms bdb :eavt eid :seon.code.var/name))))
             rend? (and (qualified-symbol? vn)
                        (or (str/starts-with? (namespace vn) "seon.render")
                            (and (dh :seon.code.var/output)
                                 (some #(= :seon.ui/hiccup (:v %)) (d/datoms bdb :eavt eid :seon.code.var/output)))))]
         (cond
           sk (update acc :data-model conj [:seon.schema/key sk])
           rk (update acc :rules conj [:seon.rule/id rk])
           rend? (update acc :renderers conj [:seon.code.var/name vn])
           vn (update acc :code conj [:seon.code.var/name vn])
           :else acc)))
     {:data-model [] :rules [] :renderers [] :code []}
     (keys writes))))

(defn- compute-tuner-diff [main-db tuner-bdb focal-aid]
  (when (and main-db tuner-bdb focal-aid (not= main-db tuner-bdb))
    (let [mc (live-cid main-db) tc (live-cid tuner-bdb)
          ck (when (and mc tc) [(get-in main-db [:config :store :id]) mc (:max-tx main-db) tc (:max-tx tuner-bdb) focal-aid])
          run* (fn []
                 (let [ref [:seon.agent/id focal-aid]
                       bp (ctx/project-entity main-db main-db ref {:target :wire :include-unmatched? true})
                       ap (ctx/project-entity main-db tuner-bdb ref {:target :wire :include-unmatched? true})
                       bsel (into {} (map (juxt :entity identity)) (get-in bp [:fitted :selected]))
                       bdrop (into #{} (map :entity) (get-in bp [:fitted :dropped]))
                       bunm (into #{} (map :entity) (:unmatched bp))
                       asel (into {} (map (juxt :entity identity)) (get-in ap [:fitted :selected]))
                       added (vec (for [[eid item] asel :when (not (contains? bsel eid))]
                                    (assoc item :from (cond (contains? bunm eid) :unmatched
                                                            (contains? bdrop eid) :trimmed
                                                            :else :new))))
                       removed (vec (for [[eid item] bsel :when (not (contains? asel eid))] item))
                       changed (vec (for [[eid aitem] asel
                                          :let [bitem (get bsel eid)]
                                          :when (and bitem (not= (:chosen-fn bitem) (:chosen-fn aitem)))]
                                      {:entity eid :ref (or (:ref aitem) (rdata/entity-lookup-ref (:data aitem)))
                                       :data (:data aitem) :before-fn (:chosen-fn bitem) :after-fn (:chosen-fn aitem)
                                       :before-tok (:tokens bitem 0) :after-tok (:tokens aitem 0) :rules (:rules aitem)}))]
                   {:before-tokens (long (get-in bp [:fitted :tokens] 0))
                    :after-tokens (long (get-in ap [:fitted :tokens] 0))
                    :before-count (count bsel)
                    :after-count (count asel)
                    :added added :removed removed :changed-renderer changed}))]
      (if ck (store/lru-get! tuner-diff-cache 16 ck run*) (run*)))))

(defn- pull-empirical-misses [main-db]
  [])

(defn discover-tuning-sessions
  "Discovers active/recent Pause-and-Fork Context Tuning sessions purely from `:seon.agent/forked-from`
   and `:seon.agent/paused?` graph edges (zero role enums)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :any] :map]
   :cost/ticks 20000000}
  [main-db conn]
  (if-not (and main-db (instance? datahike.db.interface.ISearch main-db))
    {:active [] :recent [] :paused [] :misses []}
    (let [dh (db-schema main-db)
          pat (quote [:seon.agent/id :seon.agent/label :seon.agent/branch :seon.agent/forked-from
                      :seon.agent/target :seon.agent/paused? :seon.agent/ready?
                      :seon.agent/merged-tx :seon.agent/archived? :seon.agent/summary])
          fork-eids (when (dh :seon.agent/forked-from)
                      (distinct (map :e (sq/slice-datoms main-db [nil :seon.agent/forked-from nil] {:limit 24 :reverse? true}))))
          diff-n (volatile! 0)
          forks (vec (keep (fn [e]
                             (let [ag (d/pull main-db pat e)
                                   tid (:seon.agent/id ag)
                                   faid (focal-agent-id main-db (:seon.agent/forked-from ag))]
                               (when (and tid faid)
                                 (let [br (:seon.agent/branch ag)
                                       merged? (pos? (long (or (:seon.agent/merged-tx ag) 0)))
                                       done? (or merged? (:seon.agent/archived? ag))
                                       bdb (when (and (not done?) br (not= br :main)) (rdata/branch-db-for main-db conn br))
                                       focal-ag (d/pull main-db pat [:seon.agent/id faid])
                                       live-br? (and bdb (not= bdb main-db) (not done?) (not (:seon.agent/archived? focal-ag)))
                                       levers (when live-br? (classify-branch-levers main-db bdb))
                                       diff (when (and live-br? (< @diff-n 3))
                                              (vswap! diff-n inc)
                                              (compute-tuner-diff main-db bdb faid))
                                       st (when-not done? (rstatus/latest-agent-status main-db bdb tid))]
                                   {:tuner ag :focal focal-ag :focal-id faid :branch br
                                    :done? (boolean done?) :merged? merged?
                                    :levers levers :diff diff :status st}))))
                           fork-eids))
          paused (when (dh :seon.agent/paused?)
                   (let [dts (d/datoms main-db :avet :seon.agent/paused? true)
                         raw (if (seq dts) dts (filter #(true? (:v %)) (d/datoms main-db :aevt :seon.agent/paused?)))]
                     (vec (keep (fn [d*]
                                  (let [ag (d/pull main-db pat (:e d*))]
                                    (when (and (:seon.agent/id ag) (:seon.agent/paused? ag) (not (:seon.agent/archived? ag))) ag)))
                                raw))))]
      {:active (vec (remove :done? forks))
       :recent (vec (take 6 (filter :done? forks)))
       :paused (or paused [])
       :misses (pull-empirical-misses main-db)})))

(defn- render-tuner-diff [diff]
  (when (map? diff)
    (let [{:keys [before-tokens after-tokens before-count after-count added removed changed-renderer]} diff
          delta (- (long (or after-tokens 0)) (long (or before-tokens 0)))]
      [:div {:class "cockpit-diff stack-xs"}
       [:div {:class "row-between"}
        (kicker "Live Before (:main) → After (Tuner Branch)")
        [:div {:class "pill-row"}
         (badge "blue" (str before-count " → " after-count " items"))
         (badge (if (<= delta 0) "green" "amber")
                (str before-tokens " → " after-tokens " tok (" (if (pos? delta) "+" "") delta ")"))]]
       (if (and (empty? added) (empty? removed) (empty? changed-renderer))
         [:p {:class "muted tiny mono"} "No context projection delta yet — waiting for Tuner branch edit."]
         [:div {:class "stack-xs"}
          (for [it (take 8 added)
                :let [r (or (:ref it) (rdata/entity-lookup-ref (:data it)))
                      cfn (:chosen-fn it)
                      r0 (first (filter qualified-keyword? (:rules it)))]]
            [:div {:class "ent-row row-between mono tiny"}
             [:div {:class "pill-row"}
              (badge "green" (case (:from it) :unmatched "Unmatched → Selected" :trimmed "Trimmed → Selected" "+ Added"))
              (if r (ent-link r (rdata/entity-short-label (or (:data it) r)) "mono tiny strong") (str (:entity it)))]
             [:div {:class "pill-row"}
              (when r0 (ent-link [:seon.rule/id r0] (name r0) "badge blue tiny mono"))
              (when (qualified-symbol? cfn) (ent-link [:seon.code.var/name cfn] (name cfn) "mono tiny"))]])
          (for [ch (take 8 changed-renderer)
                :let [r (:ref ch)]]
            [:div {:class "ent-row row-between mono tiny"}
             [:div {:class "pill-row"}
              (badge "amber" "Renderer Changed")
              (if r (ent-link r (rdata/entity-short-label (or (:data ch) r)) "mono tiny strong") (str (:entity ch)))]
             [:span {:class "mono tiny"}
              (str (some-> (:before-fn ch) name) " → " (some-> (:after-fn ch) name) " (" (:before-tok ch) " → " (:after-tok ch) " tok)")]])
          (for [it (take 6 removed)
                :let [r (or (:ref it) (rdata/entity-lookup-ref (:data it)))]]
            [:div {:class "ent-row row-between mono tiny"}
             [:div {:class "pill-row"}
              (badge "muted" "- Removed")
              (if r (ent-link r (rdata/entity-short-label (or (:data it) r)) "mono tiny muted") (str (:entity it)))]
             (mut (str (:tokens it 0) " tok"))])])])))

(defn render-tuning-cockpit
  "Renders the live SSE Pause-and-Fork Context Tuning Cockpit (3 Levers + live Before/After diff + empirical misses)."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] [:maybe :seon.host/db] [:maybe :keyword] :any [:maybe :map]] [:vector :any]]
   :cost/ticks 25000000}
  [main-db _branch-db viewing-id conn _opts]
  (let [{:keys [active recent paused misses]} (discover-tuning-sessions main-db conn)]
    [:section {:id "cockpit-tuning-unit" :class "card stack-sm"}
     [:div {:class "row-between"}
      [:div {:class "pill-row"}
       (kicker "Self-Building Context · Pause & Fork Loop")
       (badge (if (seq active) "amber" "green")
              (if (seq active) (str (count active) " active tuner" (when (> (count active) 1) "s")) "steady"))
       (when (seq paused) (badge "purple" (str (count paused) " paused")))
       (when (seq misses) (badge "red" (str (count misses) " empirical miss" (when (> (count misses) 1) "es"))))]
      [:div {:class "pill-row"}
       (mut "3 Levers:")
       (badge "muted" "Data Model")
       (badge "muted" "Rules")
       (badge "muted" "Renderers")]]
     (if (seq active)
       [:div {:class "stack-sm"}
        (for [{:keys [tuner focal focal-id branch levers diff status]} active
              :let [tid (:seon.agent/id tuner)
                    tlbl (rdata/agent-label tuner tid)
                    flbl (rdata/agent-label focal focal-id)
                    lm (or levers {:data-model [] :rules [] :renderers [] :code []})]]
          [:div {:class "cockpit-tuner-card stack-sm" :data-tuner (str tid) :data-focal (str focal-id)}
           [:div {:class "row-between"}
            [:div {:class "pill-row"}
             (badge (if (:seon.agent/paused? focal) "purple" "muted") (if (:seon.agent/paused? focal) "Paused" "Focal"))
             (ent-link [:seon.agent/id focal-id] flbl "mono tiny strong")
             [:span {:class "muted"} "→"]
             (badge "amber" "Tuner Fork")
             (ent-link [:seon.agent/id tid] tlbl "mono tiny strong")
             (when branch (ent-link [:seon.branch/name branch] (str branch) "pill mono tiny"))]
            (btn "btn sm" (list (quote seon.web/view-agent!) tid :session) "Watch Tuner →")]
           (when (and (map? status) (:seon.ui.status/label status))
             [:div {:class "pill-row"}
              (badge "blue" (name (or (:seon.ui.status/stage status) :running)))
              [:span {:class "sm-copy"} (:seon.ui.status/label status)]])
           [:div {:class "pill-row"}
            (kicker "Levers Edited:")
            (for [[lk lbl cls] [[:data-model "Data Model" "purple"]
                                [:rules "Rules" "blue"]
                                [:renderers "Renderers" "green"]
                                [:code "Runtime" "muted"]]
                  :let [xs (get lm lk)] :when (seq xs)]
              [:span {:class "pill-row"}
               (badge cls (str lbl " (" (count xs) ")"))
               (for [r (take 4 xs)]
                 (ent-link r (rdata/entity-short-label r) "pill mono tiny"))])
            (when (every? empty? (vals lm))
              (mut "Diagnosing 1–2 hop universe — no branch edits yet"))]
           (render-tuner-diff diff)])]
       [:div {:class "row-between"}
        [:span {:class "muted sm-copy"}
         (str "Watching " (or viewing-id :agent/root) " — when an agent misses nearby context, Seon pauses it, forks a Tuner on the 3 levers, verifies via corpus, merges, and resumes.")]
        (when (seq recent)
          [:div {:class "pill-row"}
           (mut "Recent tuners:")
           (for [{:keys [tuner merged?]} (take 3 recent)]
             (ent-link [:seon.agent/id (:seon.agent/id tuner)]
                       (str (rdata/agent-label tuner (:seon.agent/id tuner)) (if merged? " ✓" ""))
                       "pill mono tiny"))])])
     (when (seq misses)
       [:div {:class "stack-xs"}
        (kicker "Open Empirical Context Misses (agent fetched entity already ≤2 hops away)")
        (for [m (take 6 misses)
              :let [r (:seon.ctx.miss/ref m)]]
          [:div {:class "notice amber row-between mono tiny"}
           [:div {:class "pill-row"}
            (when-let [ag (:seon.ctx.miss/agent m)] (ent-link [:seon.agent/id ag] (str ag) "mono tiny strong"))
            (when-let [t (:seon.ctx.miss/turn m)] (mut (str "turn " t)))
            [:span "missed"]
            (if r (ent-link r (rdata/entity-short-label r) "mono tiny strong") (str (:seon.ctx.miss/schema m)))]
           (mut (str (or (:seon.ctx.miss/hop m) 1) "-hop via " (:seon.ctx.miss/via-attr m)))])])]))

(defn- render-lab-bar [lp semantic? suppressed-n]
  (let [chip (fn [act? p lbl]
               [:button {:type "button" :class (str "lab-chip" (when act? " active"))
                         :on-click (list (quote seon.web/update-lab-prefs!) p)} lbl])
        dis-n (count (:disabled-rules lp))]
    [:div {:class "lab-controls"}
     (kicker "Target")
     (chip (= :wire (:target lp)) {:target :wire} "Model Wire")
     (chip (= :html (:target lp)) {:target :html} "Browser UI")
     (kicker "Budget")
     (for [[b l] [[600 "600"] [2400 "2.4k"] [8000 "8k"] [16000 "16k"]]]
       (chip (= b (:token-budget lp)) {:token-budget b} l))
     (kicker "Strategy")
     (chip (= :breadth-first (:strategy lp)) {:strategy :breadth-first} "Breadth-First")
     (chip (= :depth-first (:strategy lp)) {:strategy :depth-first} "Depth-First")
     (kicker "Weights")
     (for [[k l] [[:balanced "Balanced"] [:urgency-heavy "Urgency-First"] [:recency-heavy "Recency-First"] [:semantic-heavy "Semantic-Heavy"]]]
       (chip (= k (:weight-preset lp)) {:weight-preset k} l))
     (when semantic? (badge "purple" "semantic on"))
     (when (pos? dis-n) (badge "amber" (str dis-n " lab-disabled")))
     (when (pos? (long (or suppressed-n 0))) (badge "muted" (str suppressed-n " suppressed")))
     (btn "btn sm" (quote (seon.web/reset-lab-prefs!)) "Reset")]))

(defn render-candidate-universe
  "Renders the 3-Bucket 1–2 Hop Candidate Universe:
   1. Selected (rule -> actual renderer var -> rule pinned :fn vs auto-fit -> real tokens/px)
   2. Trimmed by Budget (rule-matched but cut by budget/width/per-item cap)
   3. Unmatched in 1–2 Hop Neighborhood (direct graph edges not claimed by active rules)."
  {:malli/schema [:=> [:cat :map [:maybe [:vector :any]] :map :map :boolean :boolean :int] [:vector :any]]
   :cost/ticks 25000000}
  [explained unmatched ent-by-id lp semantic? _stack? suppressed-n]
  (let [items (vec (:items explained))
        sel (filterv :selected? items)
        trimmed (filterv (complement :selected?) items)
        unm (filterv #(not (#{:internal :never} (:policy %))) (or unmatched []))
        by-attr (group-by #(or (:via-attr %) :graph/neighbor) unm)
        unit-lbl (if (= :html (:target explained)) " px²" " tok")]
    [:section {:id "ctx-candidates-unit" :class "card stack-sm"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:h2 "1–2 Hop Candidate Universe"]
       (badge "green" (str (count sel) " selected"))
       (badge "amber" (str (count trimmed) " trimmed"))
       (badge "muted" (str (count unm) " unmatched"))]
      (badge "blue" (str (:tokens explained 0) " / " (:budget explained 0) unit-lbl))]
     (render-lab-bar lp semantic? suppressed-n)
     [:div {:class "stack-sm"}
      (kicker (str "1 · Selected into Context (" (count sel) ")"))
      (if (empty? sel)
        [:p {:class "muted tiny mono"} "No candidates selected under current budget/rules."]
        [:div {:class "table-scroll"}
         [:table {:class "data-table"}
          [:thead [:tr [:th "Entity"] [:th "Rule Fired"] [:th "Renderer & Choice"] [:th "Measured Cost"]]]
          [:tbody
           (for [it sel
                 :let [ent (get ent-by-id (:entity it))
                       r (or (:ref it) (rdata/entity-lookup-ref ent)
                             (when (integer? (:entity it)) [:db/id (:entity it)]))
                       win (or (some #(when (:chosen? %) %) (:variations it)) (first (:variations it)))
                       cfn (or (:chosen-fn it) (:fn win))
                       pinned? (and (qualified-symbol? (:fn it)) (= (:fn it) cfn))
                       rules (filterv qualified-keyword? (:rules it))
                       s (:rank/signals it)
                       mb (name (or (:measured-by win) (:measured-by it) :static))
                       dims (when (and (pos? (long (or (:w win) 0))) (pos? (long (or (:h win) 0))))
                              (str " · " (:w win) "×" (:h win)))]]
             [:tr
              [:td [:div {:class "stack-xs"}
                    (if r (ent-link r (rdata/entity-short-label (or ent r)) "mono tiny strong") (str (:entity it)))
                    (when-let [sk (:schema it)] (mut (str sk)))]]
              [:td [:div {:class "stack-xs"}
                    [:div {:class "pill-row"}
                     (for [rk (take 2 rules)]
                       (ent-link [:seon.rule/id rk] (name rk) "badge blue tiny mono"))]
                    (mut (str "rank " (r2 (:rank/score it)) " (U" (r2 (:urgency s)) " R" (r2 (:recency s)) " C" (r2 (:convergence s)) ")"))]]
              [:td [:div {:class "stack-xs"}
                    [:div {:class "pill-row"}
                     (when (qualified-symbol? cfn) (ent-link [:seon.code.var/name cfn] (name cfn) "mono tiny strong"))
                     (badge (if pinned? "amber" "muted") (if pinned? "rule pinned :fn" "auto-fit"))]
                    (when-let [rsn (:reason win)] (mut rsn))
                    (when (seq (:variations it))
                      [:details {:class "activity-fold"}
                       [:summary {:class "mono tiny muted"} (str "Variations (" (count (:variations it)) ")")]
                       [:div {:class "pill-row" :style {:margin-top "4px"}}
                        (for [v (:variations it)]
                          [:span {:class (str "pill mono tiny" (when (:chosen? v) " strong"))}
                           (some-> (:fn v) name) " · " (:tokens v 0) " tok"
                           (when (and (pos? (long (or (:w v) 0))) (pos? (long (or (:h v) 0))))
                             (str " " (:w v) "×" (:h v)))])]])]]
              [:td {:class "mono tiny muted"}
               (str (:tokens it 0) " tok" dims " · " mb)]])]]])]
     (when (seq trimmed)
       [:details {:class "activity-fold" :open (<= (count trimmed) 6)}
        [:summary {:class "mono tiny"} (str "2 · Trimmed by Budget (" (count trimmed) ")")]
        [:div {:class "table-scroll" :style {:margin-top "6px"}}
         [:table {:class "data-table"}
          [:thead [:tr [:th "Entity"] [:th "Rule Fired"] [:th "Why Trimmed"] [:th "Min Cost"]]]
          [:tbody
           (for [it (take 24 trimmed)
                 :let [ent (get ent-by-id (:entity it))
                       r (or (:ref it) (rdata/entity-lookup-ref ent)
                             (when (integer? (:entity it)) [:db/id (:entity it)]))
                       win (first (:variations it))
                       rules (filterv qualified-keyword? (:rules it))]]
             [:tr
              [:td (if r (ent-link r (rdata/entity-short-label (or ent r)) "mono tiny") (str (:entity it)))]
              [:td [:div {:class "pill-row"}
                    (for [rk (take 2 rules)]
                      (ent-link [:seon.rule/id rk] (name rk) "badge muted tiny mono"))]]
              [:td {:class "mono tiny muted"} (or (:reason win) "Exceeded budget")]
              [:td {:class "mono tiny muted"} (str (:tokens it 0) " tok")]])]]]])
     [:details {:class "activity-fold" :open (and (pos? (count unm)) (<= (count unm) 12))}
      [:summary {:class "mono tiny"} (str "3 · Unmatched in 1–2 Hop Neighborhood (" (count unm) " graph neighbors)")]
      (if (empty? unm)
        [:p {:class "muted tiny mono" :style {:margin-top "6px"}} "All visible 1–2 hop graph neighbors are claimed by rules."]
        [:div {:class "stack-xs" :style {:margin-top "8px"}}
         (for [[attr xs] (take 10 (sort-by #(- (count (val %))) by-attr))
               :let [shown (take 14 xs) more (- (count xs) (count shown))]]
           [:div {:class "stack-xs"}
            [:div {:class "row-between"}
             [:span {:class "mono tiny strong"} (str attr)]
             (mut (str (count xs) " entities"))]
            [:div {:class "pill-row"}
             (for [u shown
                   :let [r (or (:ref u) (when (integer? (:entity u)) [:db/id (:entity u)]))
                         lbl (or (not-empty (str (:label u))) (rdata/entity-short-label r))]]
               (if r (ent-link r lbl "pill mono tiny") [:span {:class "pill mono tiny"} lbl]))
             (when (pos? more) (mut (str "+" more " more")))]])])]]))

(defn- pull-recent-bench-runs [main-db]
  (let [dh (when main-db (db-schema main-db))]
    (if-not (and dh (dh :seon.bench.run/id))
      []
      (->> (sq/slice-datoms main-db [nil :seon.bench.run/id nil] {:limit 16 :reverse? true})
           (map #(d/pull main-db (quote [:seon.bench.run/id :seon.bench.run/title :seon.bench.run/status
                                         :seon.bench.run/passed :seon.bench.run/failed :seon.bench.run/total
                                         :seon.bench.run/updated-ms]) (:e %)))
           (sort-by #(- (long (or (:seon.bench.run/updated-ms %) 0))))
           (take 4)
           vec))))

(defn render-nasa-completeness-panel
  "Live NASA Completeness Matrix (Schema -> actual Renderers by measured tok/w×h + Rules + Corpus + Golden Suite).
   Zero rigid :compact/:medium/:full tier enums."
  {:malli/schema [:=> [:cat [:maybe :seon.host/db]] [:vector :any]]
   :cost/ticks 25000000}
  [main-db]
  (if-not (and main-db (instance? datahike.db.interface.ISearch main-db))
    [:section {:id "cockpit-nasa-unit" :class "card muted"} "No database connected."]
    (let [rep (cov/coverage-report main-db {:include-atlas? true})
          cnts (:counts rep)
          atlas (vec (:rule-atlas rep))
          by-schema-rules (reduce (fn [m r]
                                    (reduce #(update %1 %2 (fnil conj []) (:id r)) m (or (:produces r) (:schemas r) [])))
                                  {} atlas)
          rows (vec (or (seq (:schema-matrix rep))
                        (map (fn [row]
                               (let [sk (:schema row)
                                     rs (sort-by #(long (or (:tokens %) 0)) (:renderers row))]
                                 {:schema sk :policy :default
                                  :renderers (mapv (fn [r] {:var (or (:var r) (:fn r)) :tokens (:tokens r) :w (:min-width r) :h nil}) rs)
                                  :covering-rules (vec (distinct (get by-schema-rules sk [])))
                                  :status (cond (zero? (count rs)) :missing-renderer
                                                (= 1 (count rs)) :single-footprint
                                                :else :complete)}))
                             (cov/schema-renderer-matrix main-db))))
          corpus (vec (:corpus rep))
          bench-runs (pull-recent-bench-runs main-db)]
      [:section {:id "cockpit-nasa-unit" :class "card stack-sm"}
       [:div {:class "row-between"}
        [:div {:class "pill-row"}
         (kicker "NASA Completeness Matrix")
         (badge (if (:ok? rep) "green" "amber") (if (:ok? rep) "checks green" "attention"))
         (badge "blue" (str (:rendered-schemas cnts 0) "/" (:entity-schemas cnts 0) " schemas rendered"))
         (badge "muted" (str (:enabled-rules cnts 0) "/" (:rules cnts 0) " rules"))
         (when (pos? (long (or (:corpus-total cnts) (count corpus))))
           (badge (if (zero? (long (or (:corpus-failed cnts) 0))) "green" "red")
                  (str "Corpus " (or (:corpus-passed cnts) (count (filter :pass? corpus))) "/"
                       (or (:corpus-total cnts) (count corpus)))))]
        (mut (str (:total-renderers cnts 0) " renderers · " (:rule-holes cnts 0) " rule holes"))]
       (when (seq bench-runs)
         [:div {:class "pill-row"}
          (kicker "Golden Suite / Bench:")
          (for [br bench-runs
                :let [st (:seon.bench.run/status br)
                      cls (case st :passed "green" :running "amber" :failed "red" "muted")]]
            [:span {:class "pill mono tiny"}
             (badge cls (name (or st :unknown)))
             " " (or (not-empty (str (:seon.bench.run/title br))) (subs (str (:seon.bench.run/id br)) 0 8))
             (when (pos? (long (or (:seon.bench.run/total br) 0)))
               (str " · " (:seon.bench.run/passed br 0) "/" (:seon.bench.run/total br 0)))])])
       (when (seq corpus)
         [:details {:class "activity-fold" :open (some (complement :pass?) corpus)}
          [:summary {:class "mono tiny"} (str "Situation Corpus (" (count (filter :pass? corpus)) "/" (count corpus) " passing)")]
          [:div {:class "table-scroll" :style {:margin-top "6px"}}
           [:table {:class "data-table"}
            [:thead [:tr [:th "Situation"] [:th "Status"] [:th "Focus"] [:th "Budget"]]]
            [:tbody
             (for [c corpus]
               [:tr
                [:td {:class "mono tiny"} (or (:title c) (str (:id c)))]
                [:td (badge (if (:pass? c) "green" "red") (if (:pass? c) "PASS" "FAIL"))]
                [:td (when-let [r (:focus-ref c)] (ent-link r (rdata/entity-short-label r) "mono tiny"))]
                [:td {:class "mono tiny muted"} (str (:tokens c 0) " / " (:budget c 0) " tok")]])]]]])
       [:details {:class "activity-fold" :open false}
        [:summary {:class "mono tiny"} (str "Schema → Renderers & Rules Matrix (" (count rows) " schemas)")]
        [:div {:class "table-scroll" :style {:margin-top "6px"}}
         [:table {:class "data-table"}
          [:thead [:tr [:th "Schema"] [:th "Renderers (measured tok · w×h)"] [:th "Rules / Policy"]]]
          [:tbody
           (for [row (sort-by (comp str :schema) rows)
                 :let [sk (:schema row)
                       rs (sort-by #(long (or (:tokens %) 0)) (:renderers row))
                       crules (:covering-rules row)
                       pol (:policy row)]]
             [:tr
              [:td (ent-link [:seon.schema/key sk] (str sk) "mono tiny strong")]
              [:td [:div {:class "pill-row"}
                    (if (empty? rs)
                      (badge (if (= :never pol) "muted" "red") (if (= :never pol) "policy :never" "missing renderer"))
                      (for [r rs
                            :let [v (or (:var r) (:fn r))
                                  dims (when (and (pos? (long (or (:w r) 0))) (pos? (long (or (:h r) 0))))
                                         (str " " (:w r) "×" (:h r)))]]
                        [:span {:class "pill mono tiny"}
                         (if (qualified-symbol? v)
                           (ent-link [:seon.code.var/name v] (name v) "mono tiny strong")
                           (str v))
                         " · " (str (or (:tokens r) "?") " tok" dims)]))]]
              [:td [:div {:class "pill-row"}
                    (when (= :never pol) (badge "muted" "policy :never"))
                    (for [rk (take 4 crules)]
                      (ent-link [:seon.rule/id rk] (name rk) "badge blue tiny mono"))
                    (when (and (empty? crules) (not= :never pol))
                      (mut "unassigned"))]]])]]]]])))