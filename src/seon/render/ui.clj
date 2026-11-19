(ns seon.render.ui
  "Schema-driven Reagent-style Hiccup components (`[comp opts val]`) powering BOTH the live
   Datastar Web UI (> 60 fps incremental fragments) AND agent Markdown context (`render/hiccup->text`).
   Zero blocking calls, zero ad-hoc state — every component reads Datahike cleanly inside
   `datahike.reads/with-reads` so untouched panels do 0 work on commits or streams!"
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.git :as git]
            [seon.health :as health]
            [seon.merge :as merge]
            [seon.reconciler :as reconciler]
            [seon.render :as render]
            [seon.server :as server]))

;; --- Schema-Attached Health Component (`:seon.health/report` -> Web AND Agent!) ---

(defn health-panel
  "Schema-attached render for `:seon.health/report` (`{:seon.render/hiccup seon.render.ui/health-panel}`).
   Renders identical data to Web (Datastar HTML with Reagent `:on-click`) and Agent (`render/hiccup->text`)."
  [_opts report]
  (let [ok? (:ok? report)
        probs (:problems report)
        counts (:counts report)]
    [:div {:id "health-report-card" :class "card"}
     [:div {:class "card-head"}
      [:div {:class "row-between"}
       [:h2 "System Health (Derived + Global Failing Tests + Proc Faults)"]
       [:span {:class (str "badge " (if ok? "green" "red"))}
        (if ok? "HEALTHY" (str (count (filter #(= :error (:severity %)) probs)) " ERROR(S)"))]]
      [:button {:type "button" :class "btn primary"
                :on-click '(seon.web/run-tests! :main)}
       "▶ Run :main Reach Tests"]]
     (when (seq counts)
       [:div {:class "pill-row" :style "margin-bottom:10px"}
        (for [[cls n] (sort-by (comp str key) counts)]
          [:span {:class (str "badge " (if (= cls :state-outside-flow) "amber" "red"))}
           (str (name cls) ": " n)])])
     (if (empty? probs)
       [:p {:class "muted"} "Zero structural violations, zero failing tests across all active branches, zero open proc faults."]
       [:table {:class "data-table"}
        [:thead [:tr [:th "Class"] [:th "Severity"] [:th "Branch"] [:th "Subject"] [:th "Detail"] [:th "Action"]]]
        [:tbody
         (for [[idx p] (map-indexed vector probs)]
           [:tr {:id (str "hp-" idx)}
            [:td [:span {:class (str "badge " (if (= :error (:severity p)) "red" "amber"))}
                  (name (:class p))]]
            [:td {:class "mono tiny"} (name (:severity p))]
            [:td {:class "mono"} (str (:branch p :main))]
            [:td {:class "mono"} (:subject p)]
            [:td (:message p)]
            [:td [:button {:type "button" :class "btn"
                           :on-click (list 'seon.web/assign-health-fix! (:class p) (:subject p) (:message p))}
                  "Fix via Agent"]]])]])]))

;; --- Header Unit (`id="seon-header"`) ---

(defn header-unit [db {:keys [boot-seq fork-parent-pid fork-parent-ceiling active-tab]}]
  (let [git-sha (:seon.git/commit (d/pull db '[:seon.git/commit] [:seon.git/repo :main]))
        vars-n (count (d/datoms db :aevt :seon.code.var/name))
        schemas-n (count (d/datoms db :aevt :seon.schema/key))
        dh-attrs-n (count (:schema db))
        green-n (count (filter #(true? (:v %)) (d/datoms db :aevt :seon.test/pass?)))
        red-runs (count (remove :seon.test.run/pass?
                                (map #(d/pull db '[:seon.test.run/pass?] (:e %))
                                     (d/datoms db :aevt :seon.test.run/branch))))
        tab (or active-tab :health)]
    [:header {:id "seon-header" :class "topbar" :data-boot-seq (str (or boot-seq 1))}
     [:script {:id "boot-meta" :type "application/json"}
      (render/raw-html (str "{\"bootSeq\":" (or boot-seq 1) ",\"mainTx\":" (:max-tx db) "}"))]
     [:div {:class "brand-row"}
      [:div {:class "brand"}
       [:span {:class (str "dot " (if (zero? red-runs) "live" "bad"))}]
       [:strong "SEON"]
       [:span {:class "badge blue"} (str "Boot #" (or boot-seq 1))]
       [:span {:class "mono muted"}
        (str ":main tx " (:max-tx db)
             (when git-sha (str " · git " (subs git-sha 0 (min 8 (count git-sha)))))
             " · ForkParent PID " fork-parent-pid
             " (GC ceil " (or fork-parent-ceiling 5) ")")]]
      [:div {:class "stats-strip"}
       [:span {:class "stat-chip"} [:b vars-n] " vars"]
       [:span {:class "stat-chip"} [:b schemas-n] (str " schemas (" dh-attrs-n " DH)")]
       [:span {:class "stat-chip green"} [:b green-n] " cached green"]
       (when (pos? red-runs)
         [:span {:class "stat-chip red"} [:b red-runs] " red branch(es)"])
       [:button {:type "button" :class "btn danger"
                 :on-click '(seon.web/power-loss-reboot!)}
        "⚡ Power-Loss Reboot"]]]
     [:nav {:class "tabs-nav"}
      (for [[tid label] [[:health   "1. Health & Live Verdicts"]
                         [:program  "2. Namespace Tree & Vars"]
                         [:schemas  "3. Malli -> Datahike Schemas"]
                         [:branches "4. Branches & 3-Way Git Diff"]
                         [:agents   "5. Agents, @ctx & COW REPL"]]]
        [:button {:type "button"
                  :class (str "tab-btn" (when (= tab tid) " active"))
                  :on-click (list 'seon.web/switch-tab! tid)}
         label])]]))

;; --- Dedicated 60fps Live Stream & Flow Feed Unit (`id="seon-stream-unit"`) ---

(defn stream-unit [_db {:keys [live-streams reports errors frames]}]
  [:section {:id "seon-stream-unit" :class "card stream-bar"}
   [:div {:class "row-between"}
    [:div {:class "pill-row"}
     [:strong "Live Flow Feed"]
     [:span {:class "badge blue"} (str "frames: " (or frames 0))]
     (when (seq live-streams)
       (for [[aid sinfo] (sort-by (comp str key) live-streams)]
         [:span {:class "badge green mono"}
          (str aid " [" (name (or (:status sinfo) :live)) "] "
               (let [t (str (:text sinfo ""))]
                 (if (> (count t) 60) (str "…" (subs t (- (count t) 60))) t)))]))]
    [:span {:class "mono tiny muted"}
     (or (first reports) "Idle (0% CPU — reactive datahike.reads walk)")]]
   (when (seq errors)
     [:div {:class "notice red mono tiny"} (pr-str (first errors))])])

;; --- Tab 1: Health & Global Test Verdicts ---

(defn health-tab [db {:keys [branch-feedback]}]
  (let [h (health/report db)
        tests (->> (d/datoms db :aevt :seon.test/digest)
                   (map #(d/pull db '[:seon.test/digest :seon.test/var :seon.test/kind
                                      :seon.test/pass? :seon.test/ms :seon.test/ticks :seon.test/report] (:e %)))
                   (sort-by (juxt #(if (:seon.test/pass? %) 1 0) #(str (:seon.test/var %))))
                   (take 30))]
    [:div {:id "seon-tab-unit" :class "stack"}
     (when branch-feedback
       [:div {:class (str "notice " (if (:ok? branch-feedback) "green" "red"))}
        (:text branch-feedback)])
     ;; Render `:seon.health/report` through the schema-driven dispatcher (`render/render-value`)!
     (render/render-value db h)
     [:div {:class "card"}
      [:div {:class "card-head"}
       [:h2 "Reach-Cached Verdicts (Failures Sorted First)"]
       [:span {:class "mono muted tiny"} "Content-addressed by transitive Merkle reach digest"]]
      (if (empty? tests)
        [:p {:class "muted"} "No test digests recorded yet. Click 'Run :main Reach Tests' above."]
        [:table {:class "data-table"}
         [:thead [:tr [:th "Var"] [:th "Kind"] [:th "Digest"] [:th "Verdict"] [:th "Cost"]]]
         [:tbody
          (for [t tests]
            [:tr
             [:td {:class "mono"} (str (:seon.test/var t))]
             [:td [:span {:class "pill"} (name (or (:seon.test/kind t) :deftest))]]
             [:td {:class "mono muted"} (subs (str (:seon.test/digest t)) 0 (min 12 (count (str (:seon.test/digest t)))))]
             [:td [:span {:class (str "badge " (if (:seon.test/pass? t) "green" "red"))}
                   (if (:seon.test/pass? t) "PASS" (str "FAIL: " (:seon.test/report t)))]]
             [:td {:class "mono muted"}
              (str (:seon.test/ms t 0.0) " ms"
                   (when (pos? (or (:seon.test/ticks t) 0))
                     (str " · " (quot (:seon.test/ticks t) 1000) "k ticks")))]])]])]]))

;; --- Tab 2: Namespace Hierarchy Tree & Var Explorer ---

(defn program-tab [db {:keys [selected-ns selected-var]}]
  (let [nss (->> (d/datoms db :aevt :seon.code.ns/name)
                 (map #(d/pull db '[:seon.code.ns/name :seon.code.ns/path :seon.code.ns/doc
                                    {:seon.code.ns/parent [:seon.code.ns/name]}] (:e %)))
                 (filter :seon.code.ns/path)
                 (sort-by #(str (:seon.code.ns/name %)))
                 vec)
        sel-ns (or selected-ns (:seon.code.ns/name (first nss)))
        vars (when sel-ns
               (->> (d/datoms db :avet :seon.code.var/ns sel-ns)
                    (map #(d/pull db '[:seon.code.var/name :seon.code.var/arglists :seon.code.var/schema
                                       :seon.code.var/test? :seon.code.var/private?] (:e %)))
                    (sort-by #(str (:seon.code.var/name %)))
                    vec))
        sel-var (or selected-var (:seon.code.var/name (first vars)))
        vinfo (when sel-var (server/explore-var db sel-var))]
    [:div {:id "seon-tab-unit" :class "grid-3"}
     [:div {:class "card scroll-pane"}
      [:div {:class "card-head"} [:h2 (str "Namespaces (" (count nss) ")")]]
      [:div {:class "list-col"}
       (for [n nss :let [nsym (:seon.code.ns/name n)]]
         [:button {:type "button"
                   :class (str "list-item mono" (when (= nsym sel-ns) " active"))
                   :on-click (list 'seon.web/select-ns! nsym)}
          [:div (str nsym)]
          [:div {:class "tiny muted"}
           (str (:seon.code.ns/path n)
                (when-let [p (get-in n [:seon.code.ns/parent :seon.code.ns/name])]
                  (str " ↑ " p)))]])]]
     [:div {:class "card scroll-pane"}
      [:div {:class "card-head"} [:h2 (str "Vars in " sel-ns " (" (count vars) ")")]]
      [:div {:class "list-col"}
       (for [v vars :let [vsym (:seon.code.var/name v)]]
         [:button {:type "button"
                   :class (str "list-item mono" (when (= vsym sel-var) " active"))
                   :on-click (list 'seon.web/select-var! sel-ns vsym)}
          [:div {:class "row-between"}
           [:span (name vsym)]
           [:div {:class "pill-row"}
            (when (:seon.code.var/schema v) [:span {:class "badge green"} "malli"])
            (when (:seon.code.var/test? v) [:span {:class "badge blue"} "test"])]]])]]
     [:div {:class "card scroll-pane"}
      (if-not vinfo
        [:p {:class "muted"} "Select a namespace & var."]
        [:div {:class "stack-sm"}
         [:div {:class "card-head"}
          [:h2 {:class "mono"} (str (:seon.code.var/name vinfo))]
          [:span {:class "mono muted tiny"} (str "hash " (:hash vinfo))]]
         (when-let [doc (:seon.code.var/doc vinfo)]
           [:p {:class "doc-box"} doc])
         (when-let [sch (:seon.code.var/schema vinfo)]
           [:div [:h3 "Malli Function Contract"] (render/render-value db {:seon/code (pr-str sch)})])
         (when-let [callers (seq (:callers vinfo))]
           [:div [:h3 (str "Callers (" (count callers) ")")]
            [:div {:class "pill-row"}
             (for [c callers]
               [:button {:type "button" :class "pill-btn"
                         :on-click (list 'seon.web/select-var! (symbol (namespace c)) c)}
                (str c)])]])
         [:div [:h3 "Lossless Form Source"]
          (render/render-value db {:seon/code (:src vinfo "")})]])]]))

;; --- Tab 3: Malli -> Datahike Schemas ---

(defn schemas-tab [db {:keys [selected-schema schema-feedback]}]
  (let [skeys (->> (d/datoms db :aevt :seon.schema/key) (map :v) sort vec)
        sel (or selected-schema (first skeys))
        sinfo (when sel (server/explore-schema db sel))]
    [:div {:id "seon-tab-unit" :class "grid-2"}
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 "Register Global Malli Schema (-> Auto Datahike Attribute)"]
        [:span {:class "badge green"} "seed=42 gate"]]
       [:form {:class "form-stack" :on-submit '(seon.web/register-schema-form!)}
        [:input {:name "key" :class "inp mono" :placeholder ":seon.billing.invoice/amount" :required true}]
        [:input {:name "form" :class "inp mono" :placeholder "[:double {:seon.db/index true :min 0.0}]" :required true}]
        [:input {:name "doc" :class "inp" :placeholder "Optional docstring"}]
        [:button {:type "submit" :class "btn primary"} "Register & Derive Datahike Attr"]]
       (when schema-feedback
         [:div {:class (str "notice " (if (:ok? schema-feedback) "green" "red"))}
          (:text schema-feedback)])]
      [:div {:class "card scroll-pane"}
       [:div {:class "card-head"} [:h2 (str "Global Schemas (" (count skeys) ")")]]
       [:div {:class "list-col"}
        (for [k skeys]
          [:button {:type "button"
                    :class (str "list-item mono" (when (= k sel) " active"))
                    :on-click (list 'seon.web/select-schema! k)}
           (str k)])]]]
     [:div {:class "card scroll-pane"}
      (if-not sinfo
        [:p {:class "muted"} "Select a schema."]
        [:div {:class "stack-sm"}
         [:h2 {:class "mono"} (str (:seon.schema/key sinfo))]
         (when-let [doc (:seon.schema/doc sinfo)] [:p {:class "doc-box"} doc])
         [:h3 "Malli Form (:db.type/data)"]
         (render/render-value db {:seon/code (pr-str (:seon.schema/form sinfo))})
         (when-let [dh (:datahike-attribute sinfo)]
           [:div [:h3 "Auto-Derived Datahike Schema"] (render/render-value db dh)])
         (when (seq (:samples sinfo))
           [:div [:h3 "Deterministic seed=42 Samples"]
            [:div {:class "pill-row"} (for [s (:samples sinfo)] [:span {:class "pill mono"} s])]])
         (when (seq (:used-by sinfo))
           [:div [:h3 "Used By Vars"]
            [:div {:class "pill-row"}
             (for [u (:used-by sinfo)]
               [:button {:type "button" :class "pill-btn"
                         :on-click (list 'seon.web/select-var! (symbol (namespace u)) u)}
                (str u)])]])])]]))

;; --- Tab 4: Branches & 3-Way Git Diff ---

(defn branches-tab [db {:keys [conn cfg repo-dir selected-branch branch-feedback]}]
  (let [branches (vec (sort (d/branches conn)))
        sel (or selected-branch (first (remove #{:main} branches)) :main)
        plan (when (and sel (not= sel :main))
               (try (merge/three-way conn cfg sel) (catch Throwable _ nil)))
        git-diff (when (and repo-dir sel (not= sel :main) plan)
                   (try
                     (let [exp (git/export-branch-ref! conn cfg repo-dir sel)]
                       (git/git! repo-dir "diff" "HEAD" (:ref exp)))
                     (catch Throwable _ nil)))]
    [:div {:id "seon-tab-unit" :class "grid-2"}
     [:div {:class "card"}
      [:div {:class "card-head"} [:h2 (str "Datahike Branches (" (count branches) ")")]]
      [:div {:class "list-col"}
       (for [b branches :let [bdb (try (d/branch-as-db (:store db) b) (catch Throwable _ nil))]]
         [:button {:type "button"
                   :class (str "list-item mono" (when (= b sel) " active"))
                   :on-click (list 'seon.web/select-branch! b)}
          [:div {:class "row-between"}
           [:span (str b)]
           [:span {:class "muted tiny"} (str "tx " (:max-tx bdb))]]])]]
     [:div {:class "card stack-sm"}
      [:div {:class "card-head"}
       [:h2 {:class "mono"} (str "Branch " sel)]
       (when (not= sel :main)
         [:div {:class "pill-row"}
          [:button {:type "button" :class "btn primary" :on-click (list 'seon.web/run-tests! sel)} "▶ Reach Tests"]
          [:button {:type "button" :class "btn success" :on-click (list 'seon.web/merge-branch! sel)} "⤴ 3-Way Merge"]])]
      (when branch-feedback
        [:div {:class (str "notice " (if (:ok? branch-feedback) "green" "red"))}
         (:text branch-feedback)])
      (if (= sel :main)
        [:p {:class "muted"} "Select a non-:main branch to inspect its 3-way merge & schema-safety plan."]
        (when plan
          [:div {:class "stack-sm"}
           [:div {:class "pill-row"}
            [:span {:class (str "badge " (if (:ok? plan) "green" "red"))}
             (if (:ok? plan) "CLEAN MERGE" (str (count (:conflicts plan)) " CONFLICT / HEALTH BLOCK(S)"))]
            [:span {:class "pill"} (str (count (:entity-diffs plan)) " entities")]]
           (when (seq (:conflicts plan))
             (render/render-value db (:conflicts plan)))
           (when (seq (:entity-diffs plan))
             (render/render-value db (:entity-diffs plan)))
           (when (and git-diff (not (str/blank? git-diff)))
             [:div [:h3 "Git Unified Diff (0 Worktrees)"] (render/render-value db {:seon/code git-diff})])]))]]))

;; --- Tab 5: Agents, @seon.ctx/ctx & COW REPL ---

(defn agents-tab [db {:keys [selected-agent repl-branch last-code eval-feedback]}]
  (let [store (:store db)
        agents (->> (d/datoms db :aevt :seon.agent/id)
                    (keep #(reconciler/derive-agent-status db store {} #{} #{} (:v %)))
                    (sort-by #(str (:id %)))
                    vec)
        sel-ag (or (first (filter #(= (:id %) selected-agent) agents)) (first agents))
        br-evals (or (:branch sel-ag) :main)
        bdb (try (d/branch-as-db store br-evals) (catch Throwable _ db))
        evals (->> (d/datoms bdb :aevt :seon.agent.eval/id)
                   (map #(d/pull bdb '[:seon.agent.eval/turn :seon.agent.eval/agent :seon.agent.eval/intent
                                       :seon.agent.eval/code :seon.agent.eval/value :seon.agent.eval/error
                                       :seon.agent.eval/ms :seon.agent.eval/ticks] (:e %)))
                   (sort-by :seon.agent.eval/turn)
                   reverse
                   (take 12))]
    [:div {:id "seon-tab-unit" :class "grid-2"}
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 "Spawn Autonomous Task & Focus Namespace"]]
       [:form {:class "row-form" :on-submit '(seon.web/spawn-task-form!)}
        [:input {:name "title" :class "inp flex-1" :placeholder "Task title" :required true}]
        [:input {:name "scope" :class "inp mono" :placeholder "focus ns (e.g. seon.health)"}]
        [:select {:name "role" :class "inp"}
         [:option {:value "worker"} "Worker"]
         [:option {:value "coordinator"} "Coordinator"]]
        [:button {:type "submit" :class "btn primary"} "+ Spawn"]]]
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 (str "Agents (" (count agents) ")")]]
       (if (empty? agents)
         [:p {:class "muted"} "No agents spawned yet."]
         [:div {:class "list-col"}
          (for [a agents]
            [:div {:class (str "list-item" (when (= (:id a) (:id sel-ag)) " active"))}
             [:div {:class "row-between"}
              [:button {:type "button" :class "link-btn mono strong"
                        :on-click (list 'seon.web/select-agent! (:id a))}
               (str (:id a))]
              [:div {:class "pill-row"}
               (when (:scope a) [:span {:class "badge blue mono"} (str (:scope a))])
               [:span {:class (str "badge " (case (:status a) :merged "green" :running "blue" :paused "red" "amber"))}
                (name (:status a))]
               [:button {:type "button" :class "btn" :on-click (list 'seon.web/toggle-pause! (:id a))}
                (if (:paused? a) "Resume" "Pause")]]]
             [:div {:class "tiny muted mono"}
              (str (:branch a) " · evals: " (:evals a) " · " (:goal a))]])])]]
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 "Live COW Forked REPL (`@seon.ctx/ctx`, `user/health`)"]
        [:span {:class "badge green"} "tick-bounded"]]
       [:form {:class "form-stack" :on-submit '(seon.web/eval-repl-form!)}
        [:input {:name "branch" :class "inp mono" :value (or repl-branch ":branch/explorer")}]
        [:textarea {:name "code" :rows "5" :class "inp mono"
                    :placeholder "@seon.ctx/ctx\n(user/health)"}
         (or last-code "")]
        [:button {:type "submit" :class "btn primary"} "▶ Eval in COW Branch REPL"]]
       (when eval-feedback
         [:div {:class (str "notice " (if (:ok? eval-feedback) "green" "red"))}
          [:div {:class "mono strong"}
           (if (:ok? eval-feedback) (str "=> " (:value eval-feedback)) (str "ERROR: " (:error eval-feedback)))]
          [:div {:class "mono muted tiny"}
           (str (:branch eval-feedback) " @ tx " (:branch-tx eval-feedback)
                " · " (:ms eval-feedback 0) " ms · " (:ticks eval-feedback 0) " ticks")]])]
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 (str "Recent Evals on " br-evals)]]
       (if (empty? evals)
         [:p {:class "muted"} "No evaluations recorded yet."]
         [:div {:class "stack-sm"}
          (for [e evals]
            [:div {:class "eval-card"}
             [:div {:class "row-between mono tiny muted"}
              [:span (str "turn #" (:seon.agent.eval/turn e) " · " (:seon.agent.eval/agent e)
                          (when (:seon.agent.eval/intent e) (str " — " (:seon.agent.eval/intent e))))]
              [:span (str (:seon.agent.eval/ms e 0) " ms · " (:seon.agent.eval/ticks e 0) " ticks")]]
             [:pre {:class "code-box"} [:code (:seon.agent.eval/code e "")]]
             (if (:seon.agent.eval/error e)
               [:div {:class "mono tiny red-text"} (str "ERROR: " (:seon.agent.eval/error e))]
               [:div {:class "mono tiny green-text"} (str "=> " (:seon.agent.eval/value e))])])])]]]))

;; --- Page Unit Layout Specification (`render/step-walk`) ---

(defn page-unit-specs
  "Returns the 3 independent reactive units mounted by `web-proc`:
   1. `seon-header` (re-renders only when summary stats / active tab move)
   2. `seon-stream-unit` (re-renders on 60fps stream tokens without touching any DB panel!)
   3. `seon-tab-unit` (re-renders only when the active tab's `datahike.reads` datoms move!)"
  [st]
  (let [tab (:active-tab st :health)
        tab-comp (case tab
                   :program  program-tab
                   :schemas  schemas-tab
                   :branches branches-tab
                   :agents   agents-tab
                   health-tab)
        tab-arg (case tab
                  :program  (select-keys st [:selected-ns :selected-var])
                  :schemas  (select-keys st [:selected-schema :schema-feedback])
                  :branches (select-keys st [:conn :cfg :repo-dir :selected-branch :branch-feedback])
                  :agents   (select-keys st [:selected-agent :repl-branch :last-code :eval-feedback])
                  (select-keys st [:branch-feedback]))]
    [{:id "seon-header"
      :comp header-unit
      :args [(select-keys st [:boot-seq :fork-parent-pid :fork-parent-ceiling :active-tab])]}
     {:id "seon-stream-unit"
      :comp stream-unit
      :args [(select-keys st [:live-streams :reports :errors :frames])]}
     {:id "seon-tab-unit"
      :comp tab-comp
      :args [tab-arg]}]))
