(ns seon.render.surface.system
  "Holistic System & Swarm view (`:system` / `:workspace` alias):
   Expanded calibrated 5 operational signal cards, Agents & `:ns/*` delegation tree, Tasks DAG,
   live `seon.vector` semantic embeddings status & interactive search, Branches (Test/Merge),
   Open Errors & Fixes, Schemas, and Crash-Recovery Drill — every entity clickable."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :as rdata]
            [seon.store :as store]
            [seon.vector.flow :as vflow]))

(defonce ^:private ^:seon.flow/edge system-snap-cache (store/lru-cache 8))

(defn- live-cid [db] (when (and db (instance? datahike.db.DB db)) (d/commit-id db)))
(defn- db-schema [db] (or (:schema db) (when db (d/schema db)) {}))
(defn- badge [cls txt] [:span {:class (str "badge " cls)} txt])
(defn- mut [txt] [:span {:class "mono tiny muted"} txt])
(defn- kicker [txt] [:span {:class "kicker"} txt])
(defn- btn [cls oc lbl] [:button {:type "button" :class cls :on-click oc} lbl])
(defn- lvl-cls [lvl] (case lvl :hot "sig-hot" :warm "sig-warm" "sig-calm"))
(defn- clamp-pct [x] (max 0.0 (min 100.0 (double (or x 0.0)))))
(defn- short-model [s]
  (let [s (str (or s "EmbeddingGemma-2"))]
    (if (> (count s) 24) (str (subs s 0 12) "…" (subs s (- (count s) 10))) s)))

(defn- el
  ([ref lbl] (el ref lbl "mono"))
  ([ref lbl cls]
   (if (and (vector? ref) (= 2 (count ref)) (qualified-keyword? (first ref)) (some? (second ref)))
     [:button {:type "button" :class (str "ent-link" (when cls (str " " cls)))
               :on-click (list 'seon.web/inspect-entity! ref)} (str lbl)]
     [:span {:class (or cls "")} (str lbl)])))

(defn- card [id title pills & body]
  (into [:section (cond-> {:class "card stack-sm"} id (assoc :id id))
         [:div {:class "card-head"}
          [:h2 title]
          (when (seq pills) (into [:div {:class "pill-row"}] (remove nil? pills)))]]
        (remove nil? body)))

(defn- render-signal-cards [signals]
  (let [{:keys [level branch flow pressure context proof health]} signals
        p-pct (max (double (:ticks-pct pressure 0.0)) (double (:bytes-pct pressure 0.0)))
        pr-pct (if (pos? (long (:total-tests proof 0)))
                 (* 100.0 (/ (double (:green proof 0)) (double (:total-tests proof 1))))
                 100.0)
        h-pct (if (:ok? health) (min 100.0 (* 15.0 (double (:warnings health 0)))) 100.0)
        scard (fn [k title lvl summary pct pulse? oc]
                [:button {:type "button" :class (str "sig-cell sig-cell-btn " (lvl-cls lvl)) :on-click oc}
                 [:div {:class "sig-cell-head"}
                  [:span {:class (str "sig-dot " (lvl-cls lvl) (when pulse? " sig-pulse"))}]
                  [:strong title]
                  [:span {:class "mono tiny muted"} (name (or lvl :calm))]]
                 (when (some? pct)
                   [:div {:class "heat-bar"}
                    [:div {:class (str "heat-fill " (lvl-cls lvl)) :style {:width (str (clamp-pct pct) "%")}}]])
                 [:div {:class "mono tiny muted"} (str summary)]])]
    (card "sys-signals-unit" "Operational Signals"
          [(el [:seon.branch/name (or branch :main)] (str (or branch :main)) "pill mono tiny")
           (badge (case level :hot "red" :warm "amber" "green") (name (or level :calm)))]
          [:div {:class "sig-grid"}
           (scard :flow "Flow" (:level flow) (:summary flow) nil (:pulse? flow) '(seon.web/inspect-entity! [:seon.signal/kind :flow]))
           (scard :pressure "Pressure" (:level pressure) (:summary pressure) p-pct false '(seon.web/inspect-entity! [:seon.signal/kind :pressure]))
           (scard :context "Context" (:level context) (:summary context) (:prefix-reuse-pct context) false '(seon.web/set-view-mode! :context))
           (scard :proof "Proof" (:level proof) (:summary proof) pr-pct false '(seon.web/inspect-entity! [:seon.signal/kind :proof]))
           (scard :health "Health" (:level health) (:summary health) h-pct false '(seon.web/inspect-entity! [:seon.signal/kind :health]))])))

(defn- cached-system-snap [db]
  (let [cid (live-cid db)
        compute
        (fn []
          (let [dh (db-schema db)]
            {:idx-n (vflow/indexed-count db)
             :model-id (or (when (dh :seon.vector/model) (:v (first (d/datoms db :aevt :seon.vector/model))))
                           "EmbeddingGemma-2")
             :agents (if (dh :seon.agent/id)
                       (->> (d/datoms db :aevt :seon.agent/id)
                            (map #(d/pull db '[:seon.agent/id :seon.agent/label :seon.agent/role :seon.agent/parent
                                               :seon.agent/branch :seon.agent/scope :seon.agent/task :seon.agent/entry?
                                               :seon.agent/archived? :seon.agent/paused? :seon.agent/waiting?
                                               :seon.agent/ready? :seon.agent/merged-tx] (:e %)))
                            (remove #(or (:seon.agent/archived? %) (= :repl (:seon.agent/role %))
                                         (str/starts-with? (str (namespace (:seon.agent/id %))) "example")))
                            (sort-by #(vector (if (:seon.agent/entry? %) 0 1) (str (:seon.agent/id %)))) vec)
                       [])
             :delegation-calls (if (dh :seon.agent.call/id)
                                 (->> (take-last 16 (d/datoms db :aevt :seon.agent.call/id))
                                      (map #(d/pull db '[:seon.agent.call/id :seon.agent.call/caller
                                                         :seon.agent.call/callee :seon.agent.call/status
                                                         :seon.agent.call/prompt :seon.agent.call/result-schema] (:e %)))
                                      (sort-by #(if (= :pending (:seon.agent.call/status %)) 0 1))
                                      (take 10) vec)
                                 [])
             :pending-calls (if (dh :seon.agent.call/status)
                              (->> (d/datoms db :avet :seon.agent.call/status :pending)
                                   (map #(d/pull db '[:seon.agent.call/callee :seon.agent.call/caller :seon.agent.call/id] (:e %)))
                                   (group-by :seon.agent.call/callee))
                              {})
             :tasks (if (dh :seon.task/id)
                      (->> (d/datoms db :aevt :seon.task/id)
                           (map #(d/pull db '[:seon.task/id :seon.task/title :seon.task/scope :seon.task/owner
                                              :seon.task/done? :seon.task/template? :seon.task/needs :seon.task/tests] (:e %)))
                           (remove :seon.task/template?)
                           (sort-by #(str (:seon.task/id %))) vec)
                      [])
             :open-errs (if (dh :seon.error/status)
                          (mapv #(d/pull db '[:seon.error/id :seon.error/kind :seon.error/severity
                                              :seon.error/message :seon.error/agent :seon.error/var
                                              :seon.error/branch :seon.error/eval] (:e %))
                                (take 12 (d/datoms db :avet :seon.error/status :open)))
                          [])
             :recent-tests (if (dh :seon.test/pass?)
                             (mapv #(d/pull db '[:seon.test/digest :seon.test/var :seon.test/pass? :seon.test/ms] (:e %))
                                   (take 8 (concat (take 4 (d/datoms db :avet :seon.test/pass? false))
                                                   (take 6 (d/datoms db :avet :seon.test/pass? true)))))
                             [])
             :fixes (if (dh :seon.fix/id)
                      (mapv #(d/pull db '[:seon.fix/id :seon.fix/error-class :seon.fix/change-summary :seon.fix/regression-test] (:e %))
                            (take 10 (d/datoms db :aevt :seon.fix/id)))
                      [])
             :core-schemas (if (dh :seon.schema/key)
                             (->> (d/datoms db :aevt :seon.schema/key) (map :v)
                                  (filter #(= "entity" (name %))) sort (take 18) vec)
                             [])
             :dh-count (count dh)}))]
    (if cid
      (store/lru-get! system-snap-cache 8 [(get-in db [:config :store :id]) cid (:max-tx db)] compute)
      (compute))))

(defn- render-vectors-section [snap vector-status sem-search]
  (let [idx-n (long (or (:indexed-count vector-status) (:idx-n snap) 0))
        model-id (or (:model-id vector-status) (:model-id snap) "EmbeddingGemma-2")
        pend-n (long (or (:pending-count vector-status) 0))
        bp? (boolean (:backpressure-active? vector-status))
        res (:results sem-search)]
    (card "sys-vectors-unit" "Semantic Embeddings & Live Vector Search (seon.vector)"
          [(badge (if (pos? idx-n) "purple mono" "muted mono") (str idx-n " indexed"))
           (badge (if bp? "amber mono" "green mono") (if bp? (str "backpressure · " pend-n " pending") (str pend-n " pending")))
           (badge "mono" (short-model model-id))
           (btn "btn sm" '(seon.web/refresh-vector-status!) "Refresh")]
          [:form {:class "row-form" :on-submit '(seon.web/semantic-search-form!)}
           [:input {:class "inp sm flex-1" :name "query"
                    :value (str (or (:query sem-search) ""))
                    :placeholder "Semantic search across vars, docs, tasks, schemas, errors (or lookup ref [:seon.code.var/name ...])…"}]
           [:select {:class "inp sm" :name "of"}
            [:option {:value ""} "All kinds"]
            [:option {:value ":seon.code.var/name"} "Vars"]
            [:option {:value ":seon.code.doc/id"} "Docs"]
            [:option {:value ":seon.schema/key"} "Schemas"]
            [:option {:value ":seon.task/id"} "Tasks"]
            [:option {:value ":seon.error/id"} "Errors"]]
           [:button {:type "submit" :class "btn sm primary"} "Search"]]
          (when sem-search
            (if (false? (:ok? sem-search))
              [:div {:class "notice red tiny mono"} (str "Search error: " (or (:message sem-search) (:error sem-search)))]
              (if (empty? res)
                [:p {:class "muted tiny mono"} (str "No semantic matches for " (pr-str (:query sem-search)) ".")]
                [:div {:class "stack-xs"}
                 (kicker (str "Top " (count res) " Semantic Matches for \"" (:query sem-search) "\""))
                 (for [hit res
                       :let [sc (double (or (:score hit) 0.0))
                             pct (max 4.0 (min 100.0 (* 100.0 (max 0.0 sc))))]]
                   [:div {:class "ent-row"}
                    (badge "purple" (name (or (:kind hit) :entity)))
                    (el (or (:ref hit) (when (integer? (:eid hit)) [:db/id (:eid hit)])) (:label hit) "mono tiny strong flex-1")
                    [:div {:class "cos-bar"} [:div {:class "cos-fill" :style {:width (str pct "%")}}]]
                    [:span {:class "mono tiny muted"} (str (/ (Math/round (* sc 1000.0)) 1000.0))]])]))))))

(defn- agent-status-badge [ag active-set]
  (let [aid (:seon.agent/id ag)]
    (cond (contains? active-set aid) (badge "amber" "live")
          (:seon.agent/merged-tx ag) (badge "green" "merged")
          (:seon.agent/ready? ag) (badge "green" "ready")
          (:seon.agent/paused? ag) (badge "muted" "paused")
          (:seon.agent/waiting? ag) (badge "blue" "waiting")
          :else (badge "muted" "idle"))))

(defn- render-agent-rows [kids-by-p active-set calls-by-callee depth ag]
  (let [aid (:seon.agent/id ag)
        br (or (:seon.agent/branch ag) :main)
        pend-c (count (get calls-by-callee aid))]
    (cons
     [:tr
      [:td {:style {:padding-left (str (+ 8 (* depth 16)) "px")}}
       [:div {:class "pill-row"}
        (when (pos? depth) [:span {:class "muted mono tiny"} "↳"])
        (el [:seon.agent/id aid] (rdata/agent-label ag aid) "mono tiny strong")
        (when-let [p (:seon.agent/parent ag)]
          (el [:seon.agent/id p] (str "↑" (name p)) "mono tiny muted"))
        (badge "blue" (name (or (:seon.agent/role ag) :worker)))
        (agent-status-badge ag active-set)
        (when (pos? pend-c)
          (if-let [cid (:seon.agent.call/id (first (get calls-by-callee aid)))]
            (el [:seon.agent.call/id cid] (str pend-c " call(s)") "badge amber tiny mono")
            (badge "amber" (str pend-c " call(s)"))))]]
      [:td (el [:seon.branch/name br] (str br) "mono tiny")]
      [:td (if-let [sc (:seon.agent/scope ag)] (el [:seon.code.ns/name sc] (str sc) "mono tiny") (mut "—"))]
      [:td (if-let [tk (:seon.agent/task ag)] (el [:seon.task/id tk] (str tk) "mono tiny") (mut "—"))]
      [:td [:div {:class "pill-row"}
            (btn "btn sm" (list 'seon.web/view-agent! aid :session) "Session")
            (btn "btn sm" (list 'seon.web/view-agent! aid :context) "Context")]]]
     (mapcat #(render-agent-rows kids-by-p active-set calls-by-callee (inc depth) %)
             (get kids-by-p aid)))))

(defn- render-agents-section [snap agent-states]
  (let [ags (:agents snap)
        by-id (into {} (map (juxt :seon.agent/id identity)) ags)
        kids (group-by :seon.agent/parent ags)
        roots (filterv #(let [p (:seon.agent/parent %)] (or (nil? p) (not (contains? by-id p)))) ags)
        active-set (into #{} (keep (fn [[k v]] (when (= v :active) k))) agent-states)]
    (card "sys-agents-unit" (str "Agents & Swarm Delegation Tree (" (count ags) ")")
          [(badge "amber mono" (str (count active-set) " live"))]
          (if (empty? roots)
            [:p {:class "muted sm-copy"} "No active agents."]
            [:div {:class "table-scroll"}
             [:table {:class "data-table"}
              [:thead [:tr [:th "Agent"] [:th "Branch"] [:th "Scope"] [:th "Task"] [:th "Open"]]]
              [:tbody (mapcat #(render-agent-rows kids active-set (:pending-calls snap) 0 %) roots)]]])
          (when-let [calls (seq (:delegation-calls snap))]
            [:div {:class "stack-xs"}
             (kicker (str "Delegation Calls (" (count calls) ")"))
             (for [c calls
                   :let [st (or (:seon.agent.call/status c) :pending)
                         cls (case st :resolved "green" :failed "red" :cancelled "muted" "amber")]]
               [:div {:class "ent-row"}
                (el [:seon.agent.call/id (:seon.agent.call/id c)] (name st) (str "badge " cls " tiny mono"))
                (el [:seon.agent/id (:seon.agent.call/caller c)] (str (:seon.agent.call/caller c)) "mono tiny strong")
                [:span {:class "muted tiny"} "→"]
                (el [:seon.agent/id (:seon.agent.call/callee c)] (str (:seon.agent.call/callee c)) "mono tiny strong")
                (when-let [rs (:seon.agent.call/result-schema c)]
                  (el [:seon.schema/key rs] (str rs) "pill mono tiny"))])]))))

(defn- render-tasks-section [snap]
  (let [all-t (:tasks snap)
        open-t (filterv #(not (:seon.task/done? %)) all-t)
        done-t (filterv :seon.task/done? all-t)
        task-table (fn [ts]
                     [:div {:class "table-scroll"}
                      [:table {:class "data-table"}
                       [:thead [:tr [:th "Task"] [:th "Scope"] [:th "Owner"] [:th "Needs"] [:th "Tests"]]]
                       [:tbody
                        (for [t (take 24 ts)]
                          [:tr
                           [:td [:div {:class "stack-xs"}
                                 (el [:seon.task/id (:seon.task/id t)] (or (:seon.task/title t) (str (:seon.task/id t))) "strong tiny")
                                 (el [:seon.task/id (:seon.task/id t)] (str (:seon.task/id t)) "mono tiny muted")]]
                           [:td (if-let [s (:seon.task/scope t)] (el [:seon.code.ns/name s] (str s) "mono tiny") (mut "—"))]
                           [:td (if-let [o (:seon.task/owner t)] (el [:seon.agent/id o] (str o) "mono tiny") (mut "—"))]
                           [:td [:div {:class "pill-row"}
                                 (for [n (take 4 (:seon.task/needs t))] (el [:seon.task/id n] (name n) "pill mono tiny"))]]
                           [:td [:div {:class "pill-row"}
                                 (for [v (take 3 (:seon.task/tests t))] (el [:seon.code.var/name v] (name v) "pill mono tiny"))]]])]]])]
    (card "sys-tasks-unit" (str "Tasks DAG (" (count open-t) " open · " (count done-t) " done)")
          [(badge "blue mono" (str (count open-t) " open"))
           (badge "green mono" (str (count done-t) " done"))]
          (if (empty? open-t)
            [:p {:class "muted tiny mono"} "All tasks complete."]
            (task-table open-t))
          (when (seq done-t)
            [:details {:class "activity-fold"}
             [:summary {:class "mono tiny"} (str "Completed tasks (" (count done-t) ")")]
             (task-table done-t)]))))

(defn- render-infra-sections [snap conn stack?]
  (let [brs (if conn (vec (take 20 (sort (d/branches conn)))) [:main])
        open-errs (:open-errs snap)
        fixes (:fixes snap)
        fix-by-class (into {} (map (juxt :seon.fix/error-class identity)) fixes)
        core-schemas (:core-schemas snap)
        recent-tests (:recent-tests snap)
        grid-c (if stack? "grid-2 is-stack" "grid-2")]
    (list
     [:div {:class grid-c}
      (card "dbg-branches-unit" (str "Branches (" (count brs) ")") nil
            [:div {:class "stack-xs"}
             (for [b brs]
               [:div {:class "ent-row"}
                (el [:seon.branch/name b] (str b) "mono tiny strong flex-1")
                (btn "btn sm" (list 'seon.web/run-tests! b) "Test")
                (when (and (not= b :main) (not= b :db))
                  (btn "btn sm success" (list 'seon.web/merge-branch! b) "Merge → :main"))])])
      (card "dbg-health-unit" (str "Open Errors (" (count open-errs) ") & Class Fixes (" (count fixes) ")")
            [(badge (if (seq open-errs) "red" "green") (if (seq open-errs) (str (count open-errs) " open") "clean"))]
            (if (empty? open-errs)
              [:p {:class "muted tiny mono"} "Zero open errors."]
              [:div {:class "stack-xs"}
               (for [e open-errs
                     :let [mf (get fix-by-class (:seon.error/kind e))]]
                 [:div {:class "notice red tiny mono row-between"}
                  (el [:seon.error/id (:seon.error/id e)] (str (:seon.error/kind e) " — " (:seon.error/message e)) "mono tiny")
                  [:div {:class "pill-row"}
                   (when-let [ag (:seon.error/agent e)] (el [:seon.agent/id ag] (str ag) "pill mono tiny"))
                   (when-let [v (:seon.error/var e)] (el [:seon.code.var/name v] (name v) "pill mono tiny"))
                   (when-let [br (:seon.error/branch e)] (el [:seon.branch/name br] (str br) "pill mono tiny"))
                   (when mf (el [:seon.fix/id (:seon.fix/id mf)] (str "fix: " (:seon.fix/error-class mf)) "badge green tiny mono"))]])])
            (when (seq fixes)
              [:div {:class "stack-xs"}
               (kicker "Recorded Class Fixes")
               (for [f fixes]
                 [:div {:class "ent-row"}
                  (badge "green" "fix")
                  (el [:seon.fix/id (:seon.fix/id f)] (str (or (:seon.fix/error-class f) (:seon.fix/id f))) "mono tiny strong")
                  (when-let [rt (:seon.fix/regression-test f)] (el [:seon.code.var/name rt] (name rt) "mono tiny muted"))])]))]
     (card "dbg-schema-unit" (str "Schemas (" (:dh-count snap 0) " derived Datahike attributes)") nil
           [:div {:class "pill-row"}
            (for [sk core-schemas] (el [:seon.schema/key sk] (str sk) "pill mono tiny"))])
     (card "dbg-tests-unit" "Verification & Test Proofs"
           [(badge "blue mono" (str (count brs) " branches"))
            (el [:seon.signal/kind :proof] "Inspect Proof Signal →" "pill mono tiny")
            (btn "nav-pill tiny" '(seon.web/set-view-mode! :context) "Context & Cache →")]
           (if (seq recent-tests)
             [:div {:class "pill-row"}
              (for [tv recent-tests]
                [:span {:class "ent-chip"}
                 (el [:seon.test/digest (:seon.test/digest tv)] (if (:seon.test/pass? tv) "PASS" "FAIL")
                     (str "badge " (if (:seon.test/pass? tv) "green" "red") " tiny mono"))
                 (el [:seon.code.var/name (:seon.test/var tv)] (name (:seon.test/var tv)) "mono tiny")])]
             [:p {:class "muted tiny mono"} "Run reach-selected tests per branch above or inspect Proof signal."]))
     [:details {:id "dbg-drill-unit" :class "card fold"}
      [:summary {:class "muted tiny mono"} "Diagnostics & Crash-Recovery Drill"]
      [:div {:class "pill-row" :style {:margin-top "8px"}}
       (btn "btn sm danger" '(seon.web/power-loss-reboot!) "Cold Reboot Drill")]])))

(defn render-system-body
  "Renders the holistic System & Swarm view (`:system` / `:workspace`)."
  {:malli/schema [:=> [:cat :map] [:vector :any]] :cost/ticks 35000000}
  [{:keys [main-db signals conn agent-states branch-feedback vector-status semantic-search slot-budget]}]
  (let [stack? (< (long (:avail-w slot-budget 1000)) 780)
        snap (cached-system-snap main-db)]
    [:div {:id "seon-system-unit" :class "stack"}
     (when branch-feedback
       [:div {:class (str "notice row-between " (if (:ok? branch-feedback) "green" "red"))}
        [:span (:text branch-feedback)]
        (btn "link-muted tiny" '(seon.web/dismiss-feedback!) "×")])
     (render-signal-cards signals)
     (render-vectors-section snap vector-status semantic-search)
     (render-agents-section snap agent-states)
     (render-tasks-section snap)
     (render-infra-sections snap conn stack?)]))
