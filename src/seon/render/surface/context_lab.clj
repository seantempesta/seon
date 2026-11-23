(ns seon.render.surface.context-lab
  "Holistic Context & Cache glass-box view (`:context` / `:debug` alias) inspecting REAL
   `seon.render.wire/build-live-wire-request` directly (ZERO duplicate wire builder):
   - KV-Cache Anatomy (`seon.signals.cache/analyze-wire-cache` & `analyze-walk-cache`):
     Stable Cached Prefix vs Uncached Turn Delta, per-turn `:prefix-hash` table, Prefix-Bust Explainer,
     and UI `datahike.reads` walk HIT/MISS cache table.
   - Live Candidate Selection & Variations Explorer (`seon.render.fit/explain-candidates`) wired to
     persistent `:seon.user/lab-prefs` (target, budget, width, strategy, 4-signal weights, disabled rules).
   - Rules, Provenance & Precision/Recall/Recall-Gap Debugger (`seon.signals.scoreboard` & `touches`)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.ctx :as ctx]
            [seon.render.data :as rdata]
            [seon.render.fit :as fit]
            [seon.render.links :as rlinks :refer [badge btn card db-schema feedback-banner kicker live-cid mut pill]]
            [seon.render.surface.entity :as sentity]
            [seon.render.wire :as rwire]
            [seon.signals.cache :as scache]
            [seon.signals.scoreboard :as scoreboard]
            [seon.signals.touches :as touches]
            [seon.store :as store]))

(defonce ^:private ^:seon.flow/edge context-lab-cache (store/lru-cache 12))

(def ^:private walk-unit-vars
  {"seon-css"           'seon.render.ui/css-unit
   "seon-header"        'seon.render.ui/header-unit
   "seon-sidebar"       'seon.render.ui/sidebar-unit
   "seon-view-unit"     'seon.render.ui/view-unit
   "seon-stream-unit"   'seon.render.ui/stream-unit
   "seon-composer-unit" 'seon.render.ui/composer-unit})

(defn- code-pre [s] [:pre {:class "code-box tiny"} s])
(defn- trunc [s n] (if (> (count s) n) (str (subs s 0 n) "…") s))
(defn- r2 [x] (if (number? x) (/ (Math/round (* (double x) 100.0)) 100.0) 0.0))
(defn- pct1 [x] (if (number? x) (/ (Math/round (* (double x) 1000.0)) 10.0) 0.0))
(defn- el ([ref lbl] (rlinks/ent-link ref lbl "mono")) ([ref lbl cls] (rlinks/ent-link ref lbl cls)))

(defn- pull-all-context-rules [mdb bdb]
  (let [pull-r (fn [db]
                 (let [dh (db-schema db)]
                   (when (and db (dh :seon.rule/category) (dh :seon.rule/id))
                     (vec (keep #(let [r (d/pull db '[:seon.rule/id :seon.rule/category :seon.rule/stratum
                                                      :seon.rule/enabled? :seon.rule/assert :seon.rule/doc] (:e %))]
                                   (when (:seon.rule/id r) r))
                                (d/datoms db :avet :seon.rule/category :context))))))
        mr (or (pull-r mdb) [])
        br (if (= mdb bdb) [] (or (pull-r bdb) []))]
    (->> (vals (merge (into {} (map (juxt :seon.rule/id identity)) mr)
                      (into {} (map (juxt :seon.rule/id identity)) br)))
         (sort-by (juxt #(long (or (:seon.rule/stratum %) 0)) #(str (:seon.rule/id %))))
         vec)))

(defn build-context-lab-model
  "Commit-id & lab-prefs cached Context & Cache model inspecting REAL `seon.render.wire/build-live-wire-request`
   directly (ZERO duplicate wire builder), attaching KV-cache anatomy, 4-signal explain table, and scoreboard."
  {:malli/schema [:=> [:cat :seon.host/db :seon.host/db :keyword :map :keyword :map] :map]
   :cost/ticks 60000000}
  [emdb ebdb aid ag _br o]
  (let [lp (rdata/normalize-lab-prefs (:lab-prefs o))
        target (or (:lab-target o) (:target lp))
        budget (long (or (:token-budget o) (:token-budget lp) 2400))
        avail-w (long (or (:lab-avail-w o) (:avail-w lp) 960))
        strat (or (:strategy o) (:strategy lp) :breadth-first)
        weights (:weights lp)
        dis-rules (:disabled-rules lp)
        vis? (boolean (:vision? o))
        mc (live-cid emdb) bc (live-cid ebdb)
        ck (when (and mc bc (empty? (:already-shown o)))
             [(get-in emdb [:config :store :id]) aid mc (:max-tx emdb) bc (:max-tx ebdb)
              vis? target budget avail-w strat weights dis-rules])
        build*
        (fn []
          (let [wire (rwire/build-live-wire-request
                      emdb ebdb aid
                      {:conn (:conn o) :vision? vis? :viewport-screenshot (:viewport-screenshot o)
                       :already-shown (:already-shown o) :token-budget budget :strategy strat
                       :weights weights :disabled-rules dis-rules :explain? true})
                cands (vec (or (:candidates wire) []))
                explained (if (= target :wire)
                            (:explained wire)
                            (fit/explain-candidates ebdb cands
                                                    {:target :html :token-budget budget :avail-w avail-w
                                                     :main-db emdb :area-budget (* avail-w 1200)
                                                     :strategy strat :weights weights
                                                     :fallback-renderers rwire/fallback-entity-renderers}))
                ent-by-id (into {} (map (juxt :entity :data)) cands)
                evs (or (seq (:evals wire)) (when (not= emdb ebdb) (seq (rdata/agent-evals-in emdb aid))) [])
                kv-an (scache/analyze-wire-cache emdb ebdb aid wire)
                lt (if (seq evs)
                     (touches/extract-eval-touches ebdb evs {:include-reasoning? true})
                     {:eval-count 0 :reasoning-syms #{} :touched-eids #{} :touched-syms #{} :manual-lookups #{}})
                sb (if (seq evs)
                     (assoc (scoreboard/score-shown-blocks
                             (mapv #(assoc % :data (get ent-by-id (:entity %))) (:selected explained))
                             cands lt)
                            :eval-count (:eval-count lt)
                            :reasoning-mentions (count (:reasoning-syms lt)))
                     {:level :calm :precision 0.0 :token-precision 0.0 :recall 0.0 :f1 0.0
                      :recall-gap-count 0 :recall-gap-items [] :by-rule {} :eval-count 0 :reasoning-mentions 0})
                suppressed-n (if (empty? dis-rules)
                               0
                               (let [full-n (count (:candidates (ctx/gather-agent-candidates
                                                                 emdb ebdb aid {:agent ag :target :wire
                                                                                :include-evals? false :include-ctx-snapshot? true
                                                                                :semantic? false :enrich? false :situational? true})))]
                                 (max 0 (- full-n (count cands)))))
                all-rules (pull-all-context-rules emdb ebdb)
                eval-by-turn (into {} (map (fn [e] [(long (or (:seon.agent.eval/turn e) 0)) (:seon.agent.eval/id e)])) evs)]
            (assoc wire :explained explained :ent-by-id ent-by-id :kv-cache kv-an
                   :scoreboard sb :all-rules all-rules :eval-by-turn eval-by-turn
                   :lab-suppressed-count suppressed-n :lab-prefs lp
                   :semantic-active? (boolean (some :semantic cands)))))]
    (if ck (store/lru-get! context-lab-cache 12 ck build*) (build*))))

(defn render-kv-cache-section
  "Renders KV-Cache prefix/delta bar, hyperlinked Prefix-Bust Explainer, clickable turn table,
   and UI incremental `datahike.reads` walk cache table (folded from former `kv_cache.clj`)."
  {:malli/schema [:=> [:cat :map :map [:maybe :map]] [:vector :any]]
   :cost/ticks 5000000}
  [kv walk-cache eval-by-turn]
  (let [eval-by-turn (or eval-by-turn {})
        pref (:cached-prefix kv) delta (:uncached-delta kv) zones (:zones delta)
        tot (max 1 (long (:total-tokens kv 0)))
        seg-w (fn [t] (max 0.0 (min 100.0 (* 100.0 (/ (double (long (or t 0))) (double tot))))))
        turns (:turns kv) busts (:busts kv)]
    (card "ctx-kv-cache-unit" "KV-Cache Anatomy — Cached Prefix vs Uncached Turn Delta"
          [(badge (if (>= (double (:overall-hit-pct kv 0.0)) 80.0) "green mono" "amber mono")
                  (str "KV hit " (:overall-hit-pct kv 0.0) "%"))
           (when (:prefix-hash pref) (badge "mono" (str "prefix " (:prefix-hash pref))))
           (badge (if (pos? (count busts)) "red mono" "green mono") (str (count busts) " bust(s)"))
           (badge "purple mono" (str (get-in kv [:already-shown :zero-token-count] 0) " already-shown (0 tok)"))]
          [:div {:class "kv-bar" :title (:summary kv)}
           [:div {:class "kv-seg is-prefix" :style {:width (str (seg-w (:tokens pref)) "%")}
                  :title (str "Cached Prefix: " (:tokens pref 0) " tok (" (:pct-of-total pref 0.0) "%)")}]
           [:div {:class "kv-seg is-history" :style {:width (str (seg-w (get-in zones [:history :tokens])) "%")}
                  :title (str "History: " (get-in zones [:history :tokens] 0) " tok")}]
           [:div {:class "kv-seg is-ephem" :style {:width (str (seg-w (get-in zones [:ephemeral-tail :tokens])) "%")}
                  :title (str "Ephemeral Tail: " (get-in zones [:ephemeral-tail :tokens] 0) " tok")}]
           [:div {:class "kv-seg is-unread" :style {:width (str (seg-w (get-in zones [:unread :tokens])) "%")}
                  :title (str "Unread: " (get-in zones [:unread :tokens] 0) " tok")}]
           [:div {:class "kv-seg is-vision" :style {:width (str (seg-w (get-in zones [:vision :tokens])) "%")}
                  :title (str "Vision: " (get-in zones [:vision :tokens] 0) " tok")}]]
          [:div {:class "pill-row"}
           (badge "green mono" (str "Cached Prefix: " (:tokens pref 0) " tok (" (:pct-of-total pref 0.0) "%)"))
           (badge "blue mono" (str "History: " (get-in zones [:history :tokens] 0) " tok (" (get-in zones [:history :message-count] 0) " msgs)"))
           (badge "amber mono" (str "<ephemeral-tail>: " (get-in zones [:ephemeral-tail :tokens] 0) " tok"))
           (badge "purple mono" (str "Unread: " (get-in zones [:unread :tokens] 0) " tok (" (get-in zones [:unread :count] 0) ")"))
           (when (get-in zones [:vision :present?])
             (badge "muted mono" (str "Vision: " (get-in zones [:vision :tokens] 0) " tok " (get-in zones [:vision :dimensions]))))]
          (when (seq busts)
            [:div {:class "stack-xs"}
             (kicker (str "Prefix-Bust Explainer (" (count busts) ")"))
             (for [b busts :let [f-id (get eval-by-turn (long (:from-turn b 0))) t-id (get eval-by-turn (long (:to-turn b 0)))]]
               [:div {:class "notice red tiny mono pill-row"}
                (el (when f-id [:seon.agent.eval/id f-id]) (str "Turn " (:from-turn b)) "mono tiny strong") "→"
                (el (when t-id [:seon.agent.eval/id t-id]) (str "Turn " (:to-turn b)) "mono tiny strong")
                [:span (str "· " (:from-hash b) " → " (:to-hash b) " (-" (:lost-cache-tokens b 0) " cached tok): " (:reason b))]])])
          (when (seq turns)
            [:div {:class "table-scroll"}
             [:table {:class "data-table"}
              [:thead [:tr [:th "Turn"] [:th "Prefix Hash"] [:th "Prompt"] [:th "Hit"] [:th "Miss"] [:th "Hit %"] [:th "State"]]]
              [:tbody
               (for [t (take-last 10 turns) :let [ev-id (get eval-by-turn (long (:turn t 0)))]]
                 [:tr
                  [:td (el (when ev-id [:seon.agent.eval/id ev-id]) (str "T" (:turn t)) "mono tiny strong")]
                  [:td {:class "mono tiny"} (str (or (:prefix-hash t) "—"))]
                  [:td {:class "mono tiny"} (str (:prompt-tokens t 0))]
                  [:td {:class "mono tiny green-text"} (str (:cache-hit-tokens t 0))]
                  [:td {:class "mono tiny muted"} (str (:cache-miss-tokens t 0))]
                  [:td {:class "mono tiny"} (str (:hit-pct t 0.0) "%")]
                  [:td (if (:busted? t) (badge "red" "BUST") (badge "green" "stable"))]])]]])
          [:div {:class "stack-xs"}
           [:div {:class "row-between"} (kicker "UI Incremental Walk Cache (datahike.reads)") (mut (:summary walk-cache))]
           (when (seq (:units walk-cache))
             [:div {:class "table-scroll"}
              [:table {:class "data-table"}
               [:thead [:tr [:th "Unit"] [:th "Status"] [:th "Reads"] [:th "ms"]]]
               [:tbody
                (for [u (:units walk-cache) :let [uv (get walk-unit-vars (:id u))]]
                  [:tr
                   [:td (el (when uv [:seon.code.var/name uv]) (str "#" (:id u)) "mono tiny")]
                   [:td (badge (if (:hit? u) "green" "amber") (if (:hit? u) "HIT" "MISS"))]
                   [:td {:class "mono tiny"} (str (:reads-count u 0))]
                   [:td {:class "mono tiny muted"} (str (:ms u 0.0) " ms")]])]]])])))

(defn render-debug-ladder
  "Renders the Upgrade Ladder selection summary table wrapped inside `.table-scroll`."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]]] :any] :cost/ticks 500000}
  [items]
  (when (seq items)
    [:div {:class "table-scroll"}
     [:table {:class "data-table"}
      [:thead [:tr [:th "Entity"] [:th "Score"] [:th "Chosen Renderer"] [:th "Status"] [:th "Cost"] [:th "Levels"]]]
      [:tbody
       (for [it (take 14 items)
             :let [ref (or (rdata/entity-lookup-ref (:data it)) (when (integer? (:entity it)) [:db/id (:entity it)]))]]
         [:tr
          [:td (el ref (or (rdata/entity-short-label (:data it)) (str (or (:schema it) (:entity it)))) "mono tiny")]
          [:td {:class "mono tiny"} (str (:rank/score it "—"))]
          [:td (el (when (qualified-symbol? (:chosen-fn it)) [:seon.code.var/name (:chosen-fn it)])
                   (str (:chosen-fn it)) "mono tiny")]
          [:td (badge "blue" (name (or (:status it) :new)))]
          [:td {:class "mono tiny muted"} (str (:tokens it 0) " tok · " (:min-width it 0) "px")]
          [:td {:class "mono tiny muted"} (str (count (or (:variations it) (:candidates it))))]])]]]))

(defn- render-lab-controls [lp semantic?]
  (let [chip (fn [active? patch lbl]
               [:button {:type "button" :class (str "lab-chip" (when active? " active"))
                         :on-click (list 'seon.web/update-lab-prefs! patch)} lbl])
        w (:weights lp)]
    [:div {:class "stack-sm lab-panel"}
     [:div {:class "lab-controls"}
      (kicker "Target")
      (chip (= :wire (:target lp)) {:target :wire} "Model Wire")
      (chip (= :html (:target lp)) {:target :html} "Browser UI")
      (kicker "Budget")
      (for [[b lbl] [[1000 "1k"] [2400 "2.4k"] [4000 "4k"] [8000 "8k"] [16000 "16k"]]]
        (chip (= b (:token-budget lp)) {:token-budget b} lbl))
      (kicker "Width")
      (for [px [390 640 960 1280]] (chip (= px (:avail-w lp)) {:avail-w px} (str px "px")))
      (kicker "Strategy")
      (chip (= :breadth-first (:strategy lp)) {:strategy :breadth-first} "Breadth-First")
      (chip (= :depth-first (:strategy lp)) {:strategy :depth-first} "Depth-First")]
     [:div {:class "lab-controls"}
      (kicker "4-Signal Weights")
      (for [[pk lbl] [[:balanced "Balanced"] [:urgency "Urgency"] [:recency "Recency"]
                      [:semantic-heavy "Semantic-Heavy"] [:turn0-cold "Turn-0 Cold-Start"]]]
        (chip (= pk (:weight-preset lp)) {:weight-preset pk :weights (get rdata/lab-weight-presets pk)} lbl))
      (when semantic? (badge "purple" "live Metal vectors"))
      (btn "btn sm" '(seon.web/reset-lab-prefs!) "Reset")]
     [:form {:class "row-form" :on-submit '(seon.web/update-lab-weights-form!)}
      [:label {:class "tiny mono muted"} "U" [:input {:class "inp sm mono w-inp" :name "urgency" :value (str (:urgency w))}]]
      [:label {:class "tiny mono muted"} "R" [:input {:class "inp sm mono w-inp" :name "recency" :value (str (:recency w))}]]
      [:label {:class "tiny mono muted"} "C" [:input {:class "inp sm mono w-inp" :name "convergence" :value (str (:convergence w))}]]
      [:label {:class "tiny mono muted"} "S" [:input {:class "inp sm mono w-inp" :name "semantic" :value (str (:semantic w))}]]
      [:button {:type "submit" :class "btn sm"} "Apply Weights"]]]))

(defn- render-candidates-section [explained ent-by-id lp semantic? stack? suppressed-n]
  (let [items (:items explained)]
    (card "ctx-candidates-unit" "Live Candidate Selection & Variations Explorer"
          [(badge "green mono" (str (:selected-count explained 0) " selected"))
           (badge "muted mono" (str (:dropped-count explained 0) " dropped"))
           (when (pos? (long (or suppressed-n 0))) (badge "amber mono" (str suppressed-n " lab-disabled")))
           (badge "blue mono" (str (:tokens explained 0) " / " (:budget explained 0) (if (= :html (:target explained)) " px²" " tok")))]
          (render-lab-controls lp semantic?)
          (if (empty? items)
            [:p {:class "muted sm-copy"} "No candidates projected for this agent."]
            [:div {:class "table-scroll"}
             [:table {:class "data-table"}
              [:thead [:tr [:th "Entity"] [:th "4-Signal Score"] [:th "Rules"] [:th "Chosen / Reason"] [:th "Variations"]]]
              [:tbody
               (for [it (take 16 items)
                     :let [ent (get ent-by-id (:entity it))
                           ref (or (rdata/entity-lookup-ref ent) (when (integer? (:entity it)) [:db/id (:entity it)]))
                           sigs (:rank/signals it) vars (:variations it)
                           win (or (some #(when (:chosen? %) %) vars) (first vars))
                           boost (double (or (:rank/boost it) 0.0))]]
                 [:tr
                  [:td [:div {:class "stack-xs"}
                        (el ref (rdata/entity-short-label (or ent ref)) "mono tiny strong")
                        (badge (if (:selected? it) "green" "muted") (name (or (:status it) :dropped)))]]
                  [:td {:class "mono tiny"}
                   [:div {:class "strong"} (str (r2 (:rank/score it)))]
                   (mut (str "U" (r2 (:urgency sigs)) " R" (r2 (:recency sigs))
                             " C" (r2 (:convergence sigs))
                             (when (contains? sigs :semantic) (str " S" (r2 (:semantic sigs))))))
                   (when (not (zero? boost)) (badge "amber" (str (if (pos? boost) "+" "") boost)))]
                  [:td [:div {:class "pill-row"}
                        (for [r (take 3 (:rules it))]
                          (if (rlinks/real-rule-id? r) (el [:seon.rule/id r] (name r) "pill mono tiny") (pill (str r))))]]
                  [:td [:div {:class "stack-xs"}
                        (when-let [cfn (or (:chosen-fn it) (:fn win))]
                          (el (when (qualified-symbol? cfn) [:seon.code.var/name cfn]) (name cfn) "mono tiny strong"))
                        (mut (or (:reason win) (if (:selected? it) "Selected" "Dropped")))]]
                  [:td {:class "var-col"}
                   [:details {:class "nested-fold"}
                    [:summary {:class "nested-summary"} (str "Variations (" (count vars) ")")]
                    [:div {:class "stack-xs"}
                     (when ref (el ref "Open Full Inspector →" "tiny mono"))
                     (for [v (take 4 vars)]
                       [:div {:class (str "var-card" (when (:chosen? v) " is-winner"))}
                        [:div {:class "pill-row"}
                         (when (:chosen? v) (badge "green" "WINNER"))
                         (el (when (qualified-symbol? (:fn v)) [:seon.code.var/name (:fn v)]) (str (:fn v)) "mono tiny strong")
                         (mut (str (:tokens v 0) " tok · " (:min-width v 0) "px · " (:reason v)))]
                        [:div {:class (if stack? "debug-tile-pair is-stack" "debug-tile-pair")}
                         [:div {:class "debug-tile-col"} (kicker "Browser HTML") (or (:hiccup v) (mut "—"))]
                         [:div {:class "debug-tile-col"} (kicker "Model Text") (code-pre (trunc (str (:text v)) 280))]]])]]]])]]]))))

(defn- gap-ref [g]
  (let [sym (:sym g)]
    (cond (qualified-symbol? sym) [:seon.code.var/name sym]
          (symbol? sym) [:seon.code.ns/name sym]
          (integer? (:entity g)) [:db/id (:entity g)]
          (qualified-keyword? (:schema g)) [:seon.schema/key (:schema g)]
          :else nil)))

(defn- render-rules-and-scoreboard [sb all-rules rt dis-rules kv ent-by-id]
  (let [runs (or (:runs rt) (:fired-rules rt) [])
        run-by-id (into {} (map (juxt :rule-id identity)) runs)
        by-rule (:by-rule sb) gaps (:recall-gap-items sb)
        z-items (get-in kv [:already-shown :zero-token-items])]
    [:div {:id "dbg-rules-unit" :class "stack"}
     (card "dbg-rule-trace" "Rule Firing Trace, Provenance & Scoreboard (seon.rule)"
           [(badge (case (:level sb) :hot "red mono" :warm "amber mono" "green mono")
                   (str "P " (pct1 (:precision sb)) "% · tok-P " (pct1 (:token-precision sb))
                        "% · R " (pct1 (:recall sb)) "% · F1 " (pct1 (:f1 sb)) "%"))
            (badge (if (pos? (long (:recall-gap-count sb 0))) "red mono" "green mono")
                   (str (:recall-gap-count sb 0) " recall gaps"))
            (badge "purple mono" (str (count all-rules) " rules"))
            (when (seq dis-rules) (badge "amber mono" (str (count dis-rules) " disabled in lab")))
            (mut (str (:eval-count sb 0) " evals · " (:reasoning-mentions sb 0) " reasoning syms"))]
           [:div {:class "table-scroll"}
            [:table {:class "data-table"}
             [:thead [:tr [:th "Rule"] [:th "S"] [:th "Fired"] [:th "Tokens"] [:th "Precision"] [:th "Boost / Params"] [:th "Lab"]]]
             [:tbody
              (for [r all-rules
                    :let [rid (:seon.rule/id r) rn (get run-by-id rid) br (get by-rule rid)
                          dis? (contains? dis-rules rid) asrt (:seon.rule/assert r)
                          ams (if (sequential? asrt) (filter map? asrt) (when (map? asrt) [asrt]))
                          bp (not-empty (apply merge (map #(select-keys % [:rank/boost :rank/suppress :params]) ams)))]]
                [:tr {:id (str "lab-rule-row-" (name rid)) :class (when dis? "muted")}
                 [:td (el [:seon.rule/id rid] (str rid) "mono tiny strong")]
                 [:td {:class "mono tiny"} (str (:seon.rule/stratum r 0))]
                 [:td {:class "mono tiny"} (str (:fired rn 0))]
                 [:td {:class "mono tiny muted"} (str (:tokens br 0))]
                 [:td {:class "mono tiny"} (if (:shown-count br) (str (pct1 (:precision br)) "%") "—")]
                 [:td {:class "mono tiny muted"}
                  (if (seq bp)
                    (trunc (str/join " · " (for [[k v] bp] (str (name k) "=" (if (map? v) (pr-str v) v)))) 64) "—")]
                 [:td [:button {:id (str "lab-rule-btn-" (name rid)) :type "button"
                                :class (if dis? "btn sm" "btn sm success")
                                :on-click (list 'seon.web/toggle-lab-rule! rid)}
                       (if dis? "Disabled" "Enabled")]]])]]]
           (when (seq gaps)
             [:div {:class "stack-xs"}
              (kicker (str "Recall-Gap Misses (" (count gaps) " manual lookups dropped from context)"))
              [:div {:class "stack-xs"}
               (for [g (take 12 gaps) :let [ref (gap-ref g) grs (filter rlinks/real-rule-id? (:rules g))]]
                 [:div {:class "ent-row"}
                  (el ref (str (or (:sym g) (:schema g) (:entity g))) "mono tiny strong")
                  (when (seq grs) (into [:div {:class "pill-row"}] (map #(el [:seon.rule/id %] (name %) "pill mono tiny") (take 3 grs))))])]])
           (when (seq z-items)
             [:div {:class "stack-xs"}
              (kicker (str "already-shown Zero-Token Queue (" (count z-items) " items · saved "
                           (get-in kv [:already-shown :saved-tokens] 0) " tok)"))
              [:div {:class "pill-row"}
               (for [z (take 12 z-items)
                     :let [ent (get ent-by-id (:entity z))
                           zref (or (rdata/entity-lookup-ref ent) (when (integer? (:entity z)) [:db/id (:entity z)]))
                           zlbl (or (when ent (rdata/entity-short-label ent)) (str (or (:schema z) (:entity z))))
                           zfn (:chosen-fn z)]]
                 [:span {:class "ent-chip"}
                  (el zref (str zlbl " (0 tok)") "mono tiny strong")
                  (when (qualified-symbol? zfn) (el [:seon.code.var/name zfn] (name zfn) "mono tiny muted"))])]]))]))

(defn- render-wire-and-versions [viewing-id branch wire entities tiles pair-c]
  (let [msgs (:messages wire) mtoks (:message-tokens wire) ss (:surface-stats wire)]
    (list
     (card "dbg-wire-unit" (str "Exact Wire Request for " viewing-id " (branch " branch ")")
           [(el [:seon.agent/id viewing-id] (str viewing-id) "pill mono tiny")
            (el [:seon.branch/name branch] (str branch) "pill mono tiny")
            (badge "blue mono" (str "~" (:total-tokens wire 0) " tok"))
            (when (:prefix-hash wire) (badge "mono" (str "prefix " (:prefix-hash wire))))
            (badge "green mono" (str (count msgs) " msgs"))
            (badge "purple mono" (str (:unchanged ss 0) "/" (:selected ss 0) " cached"))]
           [:div {:class "wire-list"}
            (for [[idx [m tok]] (map-indexed vector (take 10 (map vector msgs mtoks)))
                  :let [body (if (string? (:content m)) (:content m) "[multimodal]")
                        l1 (str (first (remove str/blank? (str/split-lines body))))]]
              [:details {:id (str "wm-" idx) :class "wire-row" :open (< idx 2)}
               [:summary {:class "wire-summary"}
                (mut (str "#" idx)) (badge "blue" (str/upper-case (str (:role m))))
                [:span {:class "muted tiny truncate flex-1"} (trunc l1 100)]
                (mut (str "~" tok " tok"))]
               (code-pre (trunc body 1400))])]
           (render-debug-ladder (or (get-in wire [:fit-trace :items]) entities)))
     (card "dbg-versions-unit" (str "Rendered Versions — Human HTML vs Model Text (" (count tiles) ")")
           [(badge "purple mono" "1 Hiccup → 2 targets")]
           [:div {:class "stack-sm"}
            (for [[idx t] (map-indexed vector tiles)
                  :let [tref (or (rdata/entity-lookup-ref (:data t)) (when (integer? (:entity t)) [:db/id (:entity t)]))
                        tfn (:chosen-fn t)]]
              [:div {:id (str "dbg-tile-" idx) :class "stack-xs"}
               [:div {:class "pill-row"}
                (el tref (rdata/entity-short-label (or (:data t) tref)) "mono tiny strong")
                (when (qualified-symbol? tfn) (el [:seon.code.var/name tfn] (str tfn) "mono tiny muted"))]
               [:div {:class pair-c}
                [:div {:class "debug-tile-col"} (kicker "Human HTML (Browser)") (or (:hiccup t) (mut (str (:schema t))))]
                [:div {:class "debug-tile-col"} (kicker "Model Text (render/hiccup->text)") (code-pre (trunc (str (:text t)) 1000))]]])]))))

(defn render-context-lab-body
  "Renders the ONE Explain Current Screen lens (`:context` / `:debug`): explains ANY non-agent focal
   entity via `sentity/render-entity-explain-body`, or the active Agent (KV-cache, candidates, rules, real wire)."
  {:malli/schema [:=> [:cat :map] [:vector :any]] :cost/ticks 40000000}
  [{:keys [agent viewing-id branch branch-db signals-fit entities wire ctx-trace inspect-ref
           open-errors branch-feedback walk-summary slot-budget] :as m}]
  (if (and inspect-ref (not= :seon.agent/id (first inspect-ref)))
    (sentity/render-entity-explain-body m)
    (let [rt (or (:rule-trace wire) ctx-trace)
          tiles (take 6 (:tiles wire))
          explained (:explained wire)
          kv (or (:kv-cache wire) (scache/analyze-wire-cache nil nil viewing-id wire))
          walk-c (scache/analyze-walk-cache walk-summary)
          sb (or (:scoreboard wire) {:level :calm :precision 0.0 :token-precision 0.0 :recall 0.0 :f1 0.0 :recall-gap-count 0})
          lp (or (:lab-prefs wire) (rdata/normalize-lab-prefs nil))
          stack? (< (long (:avail-w slot-budget 1000)) 720)
          pair-c (if stack? "debug-tile-pair is-stack" "debug-tile-pair")]
      [:div {:class "stack"}
       [:div {:id "dbg-feedback-unit"} (feedback-banner branch-feedback)]
       [:div {:class "insp-bar"}
        [:div {:class "pill-row"}
         (badge "purple" "EXPLAIN")
         (el [:seon.agent/id viewing-id] (rdata/agent-label agent viewing-id) "mono strong")
         (el [:seon.branch/name branch] (str branch) "pill mono tiny")]
        (btn "btn sm primary" '(seon.web/set-explain-lens! false) "← Back to Explore")]
       (render-kv-cache-section kv walk-c (or (:eval-by-turn wire) {}))
       (when explained
         (render-candidates-section explained (:ent-by-id wire) lp (:semantic-active? wire) stack? (:lab-suppressed-count wire)))
       (render-rules-and-scoreboard sb (:all-rules wire) rt (:disabled-rules lp) kv (:ent-by-id wire))
       [:section {:id "dbg-health-unit" :class "card stack-xs"}
        [:div {:class "row-between"}
         (kicker (str "Open Errors (" (count open-errors) ")"))
         (badge (if (seq open-errors) "red" "green") (if (seq open-errors) "attention" "clean"))]
        (if (empty? open-errors)
          [:p {:class "muted tiny mono"} "0 open errors on current branch."]
          (for [e open-errors]
            [:div {:class "notice red tiny mono row-between"}
             (el (when (:seon.error/id e) [:seon.error/id (:seon.error/id e)])
                 (str (:seon.error/kind e) " · " (:seon.error/message e)) "mono tiny")
             [:div {:class "pill-row"}
              (when-let [ag (:seon.error/agent e)] (el [:seon.agent/id ag] (str ag) "pill mono tiny"))
              (when-let [v (:seon.error/var e)] (el [:seon.code.var/name v] (name v) "pill mono tiny"))
              (when-let [br (:seon.error/branch e)] (el [:seon.branch/name br] (str br) "pill mono tiny"))]]))]
       (render-wire-and-versions viewing-id branch wire entities tiles pair-c)
       [:div {:id "dbg-ctx-unit" :class (if stack? "grid-2 is-stack" "grid-2")}
        (card nil (str "Live @seon.ctx/ctx Atom Value (" viewing-id ")") [(badge "mono" (str "tx " (:max-tx branch-db)))]
              (rlinks/kv-dl [["agent" (el [:seon.agent/id viewing-id] (str viewing-id) "mono tiny strong")]
                             ["branch" (el [:seon.branch/name branch] (str branch) "mono tiny")]
                             ["role" (:seon.agent/role agent)]
                             ["scope" (when-let [sc (:seon.agent/scope agent)] (el [:seon.code.ns/name sc] (str sc) "mono tiny"))]
                             ["task" (when-let [tk (:seon.agent/task agent)] (el [:seon.task/id tk] (str tk) "mono tiny"))]
                             ["parent" (when-let [p (:seon.agent/parent agent)] (el [:seon.agent/id p] (str p) "mono tiny"))]])
              (rlinks/pill-list "pinned:" (:seon.agent/pinned agent) :ref 10)
              (code-pre (pr-str (select-keys agent [:seon.agent/id :seon.agent/branch :seon.agent/role
                                                    :seon.agent/scope :seon.agent/task :seon.agent/pinned]))))
        (when (:hiccup signals-fit)
          [:section {:id "dbg-signals-card" :class "card"} (:hiccup signals-fit)])]])))
