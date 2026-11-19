(ns seon.render.ui
  "Inside-Out Reagent/Hiccup UI driven 100% by Datahike (`:user/default` -> `:seon.user/*`) +
   schema-attached entity tiles powering BOTH the live 120fps Web UI AND the agent's wire context:
   - Everything about where the user is (`:seon.user/viewing`, `:seon.user/view-mode`,
     `:seon.user/sidebar-open?`, `:seon.user/expanded-agents`, `:seon.user/selected-branch`) lives
     in Datahike — restoring LMDB restores the exact screen, and any agent can move the user's view
     via `(user/show-user! ...)`!
   - Collapsible recursive left sidebar (`#seon-sidebar`) shows the full Root -> sub-agent ->
     sub-sub-agent hierarchy via `:seon.agent/parent` refs.
   - Three views of the viewed agent: `:canvas` (clean editorial user canvas), `:workspace`
     (2-panel chat + live `@ctx`/eval tiles), and `:debug` (exact wire request + diagnostics)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.merge :as merge]
            [seon.model :as model]
            [seon.reconciler :as reconciler]
            [seon.render :as render]
            [seon.store :as store]))

;; --- Inside-Out User Viewport State (Read Directly From Datahike `:user/default`) ---

(defn agent-id->slug [aid]
  (if (= aid :agent/root)
    "root"
    (if-let [ns-part (namespace aid)]
      (str ns-part "~" (name aid))
      (name aid))))

(defn slug->agent-id [^String slug]
  (let [s (str/trim (str slug))]
    (cond
      (or (str/blank? s) (= s "root")) :agent/root
      (str/includes? s "~") (let [[ns-p nm] (str/split s #"~" 2)] (keyword ns-p nm))
      :else (keyword "agent" s))))

(defn agent-href [aid] (str "/agent/" (agent-id->slug aid)))
(defn debug-href [aid] (if (= aid :agent/root) "/root/debug" (str "/agent/" (agent-id->slug aid) "/debug")))

(defn user-view-state
  "Reads the user's persistent viewport state (`:user/default`) directly from Datahike `db`."
  [db]
  (let [u (when (and db (contains? (:schema db) :seon.user/id)
                     (first (d/datoms db :avet :seon.user/id :user/default)))
            (d/pull db '[:seon.user/id :seon.user/view-mode :seon.user/sidebar-open?
                         :seon.user/expanded-agents :seon.user/selected-branch
                         {:seon.user/viewing [:seon.agent/id]}]
                    [:seon.user/id :user/default]))]
    {:user-id (or (:seon.user/id u) :user/default)
     :viewing-id (or (get-in u [:seon.user/viewing :seon.agent/id]) :agent/root)
     :view-mode (or (:seon.user/view-mode u) :canvas)
     :sidebar-open? (boolean (:seon.user/sidebar-open? u))
     :expanded-agents (into #{:agent/root} (:seon.user/expanded-agents u))
     :selected-branch (:seon.user/selected-branch u)}))

;; --- Branch & Agent Data Helpers ---

(defn- branch-db-for [main-db conn branch]
  (if (or (nil? branch) (= branch :main) (nil? conn))
    main-db
    (or (try (store/head conn branch) (catch Throwable _ nil)) main-db)))

(defn- latest-attr-val [main-db branch-db ma ba attr]
  (let [mt (or (:tx (first (d/datoms main-db :eavt (:db/id ma) attr))) 0)
        bt (or (:tx (first (d/datoms branch-db :eavt (:db/id ba) attr))) 0)]
    (if (> bt mt) (get ba attr) (get ma attr))))

(defn- pull-agent-entity [main-db branch-db agent-id]
  (let [ma (when (first (d/datoms main-db :avet :seon.agent/id agent-id))
             (d/pull main-db '[*] [:seon.agent/id agent-id]))
        ba (when (and branch-db (not= branch-db main-db)
                      (first (d/datoms branch-db :avet :seon.agent/id agent-id)))
             (d/pull branch-db '[*] [:seon.agent/id agent-id]))
        base (merge {:seon.agent/id (or agent-id :agent/root)
                     :seon.agent/branch :main
                     :seon.agent/role (if (= agent-id :agent/root) :coordinator :worker)
                     :seon.agent/scope 'seon
                     :seon.agent/goal "Collaborate with the user, farm out work to specialist agents, and keep the system healthy."
                     :seon.agent/pinned (if (= agent-id :agent/root) [[:seon.health/summary :main]] [])}
                    ma
                    ba)]
    (if (and ma ba (not= branch-db main-db))
      (assoc base :seon.agent/pinned (or (latest-attr-val main-db branch-db ma ba :seon.agent/pinned)
                                         (:seon.agent/pinned base)))
      base)))

(defn- agent-evals-in [db agent-id]
  (->> (d/datoms db :avet :seon.agent.eval/agent agent-id)
       (map #(d/pull db '[:seon.agent.eval/id :seon.agent.eval/turn :seon.agent.eval/call-index
                          :seon.agent.eval/call-id :seon.agent.eval/handle :seon.agent.eval/result-schema
                          :seon.agent.eval/thought :seon.agent.eval/reasoning :seon.agent.eval/intent
                          :seon.agent.eval/code :seon.agent.eval/value :seon.agent.eval/out
                          :seon.agent.eval/error :seon.agent.eval/ms :seon.agent.eval/ticks
                          :seon.agent.eval/prompt-tokens :seon.agent.eval/completion-tokens
                          :seon.agent.eval/cache-hit-tokens :seon.agent.eval/cache-miss-tokens
                          :seon.agent.eval/seen-messages] (:e %)))
       (sort-by (juxt #(or (:seon.agent.eval/turn %) 0) #(or (:seon.agent.eval/call-index %) 0)))
       vec))

(defn- agent-messages-in [main-db agent-id]
  (->> (d/datoms main-db :avet :seon.agent.message/to agent-id)
       (map #(d/pull main-db '[:seon.agent.message/id :seon.agent.message/seq
                               :seon.agent.message/from :seon.agent.message/to
                               :seon.agent.message/type :seon.agent.message/body] (:e %)))
       (sort-by :seon.agent.message/seq)
       vec))

(defn- evals->turns [evals]
  (->> (group-by :seon.agent.eval/turn evals)
       (sort-by key)
       (mapv (fn [[turn es]]
               (let [ordered (sort-by #(or (:seon.agent.eval/call-index %) 0) es)
                     first-e (first ordered)]
                 {:turn turn
                  :thought (:seon.agent.eval/thought first-e)
                  :reasoning (:seon.agent.eval/reasoning first-e)
                  :calls (mapv (fn [e]
                                 {:id (or (:seon.agent.eval/call-id e) (str "t" turn "-c" (or (:seon.agent.eval/call-index e) 0)))
                                  :handle (:seon.agent.eval/handle e)
                                  :result-schema (:seon.agent.eval/result-schema e)
                                  :intent (:seon.agent.eval/intent e)
                                  :code (:seon.agent.eval/code e)
                                  :value (:seon.agent.eval/value e)
                                  :out (:seon.agent.eval/out e)
                                  :error (:seon.agent.eval/error e)
                                  :ms (:seon.agent.eval/ms e)
                                  :ticks (:seon.agent.eval/ticks e)})
                               ordered)})))))

(defn build-live-wire-request
  "Single source of truth for an agent's LLM context AND Debug View:
   Walks `branch-db`, projects discovered tiles via `render/render-value` -> `render/hiccup->text`,
   frames `:seon.agent/examples` in `<session kind=\"example\">...</session>`, and optionally attaches
   an ephemeral tail viewport snapshot without accumulating it in history."
  [main-db branch-db agent-id {:keys [viewport-screenshot vision?]}]
  (let [ag (pull-agent-entity main-db branch-db agent-id)
        evals (agent-evals-in branch-db agent-id)
        msgs (agent-messages-in main-db agent-id)
        seen-ids (into #{} (mapcat :seon.agent.eval/seen-messages) evals)
        unread (filterv #(not (contains? seen-ids (:seon.agent.message/id %))) msgs)
        tiles (render/discover-renderable-tiles main-db branch-db ag evals health/report)
        tile-projections
        (mapv (fn [{:keys [val] :as tile}]
                (let [h (render/render-value branch-db val {:mode :agent :compact? true})
                      txt (render/hiccup->text h)]
                  (assoc tile :text txt)))
              tiles)
        walk-text (str/join "\n\n---\n\n"
                            (map (fn [{:keys [schema source handle text]}]
                                   (str "### [" (name source)
                                        (when handle (str " " handle))
                                        " · " schema "]\n" text))
                                 tile-projections))
        examples (vec
                  (for [ex-id (sort (:seon.agent/examples ag))
                        :when (first (d/datoms main-db :avet :seon.agent/id ex-id))
                        :let [ex-ag (d/pull main-db '[:seon.agent/id :seon.agent/goal] [:seon.agent/id ex-id])
                              ex-evals (agent-evals-in main-db ex-id)]]
                    {:id ex-id
                     :goal (:seon.agent/goal ex-ag)
                     :turns (evals->turns ex-evals)}))
        wire (model/build-agent-wire-messages
              {:thinking? true :replay-reasoning? true :vision? (boolean vision?)}
              {:agent-id agent-id
               :branch (:seon.agent/branch ag :main)
               :role (:seon.agent/role ag :worker)
               :goal (:seon.agent/goal ag)
               :system-prompt (:seon.agent/system-prompt ag)
               :examples examples
               :walk-text walk-text
               :history (evals->turns evals)
               :unread unread
               :viewport-screenshot viewport-screenshot})]
    (assoc wire
           :agent ag
           :tiles tile-projections
           :evals evals
           :messages-inbox msgs
           :unread unread)))

;; --- Schema-Attached Entity Tile Renderers (`:seon.render/hiccup`) ---

(defn health-panel
  "Schema-attached render for `:seon.health/report` (`{:seon.render/hiccup seon.render.ui/health-panel}`)."
  [_opts report]
  (let [ok? (:ok? report)
        probs (:problems report)
        counts (:counts report)]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:h2 "System Health"]
       [:span {:class (str "badge " (if ok? "green" "red"))}
        (if ok? "HEALTHY" (str (count (filter #(= :error (:severity %)) probs)) " ISSUE(S)"))]]
      [:button {:type "button" :class "btn primary sm"
                :on-click '(seon.web/run-tests! :main)}
       "▶ Run Reach Tests"]]
     (when (seq counts)
       [:div {:class "pill-row" :style "margin-bottom:8px"}
        (for [[cls n] (sort-by (comp str key) counts)]
          [:span {:class (str "badge " (if (= cls :state-outside-flow) "amber" "red"))}
           (str (name cls) ": " n)])])
     (if (empty? probs)
       [:p {:class "muted sm-copy"} "All structural checks green, zero failing branch tests, zero open proc faults."]
       [:table {:class "data-table"}
        [:thead [:tr [:th "Class"] [:th "Branch"] [:th "Subject"] [:th "Detail"] [:th ""]]]
        [:tbody
         (for [[idx p] (map-indexed vector probs)]
           [:tr {:id (str "hp-" idx)}
            [:td [:span {:class (str "badge " (if (= :error (:severity p)) "red" "amber"))} (name (:class p))]]
            [:td {:class "mono tiny"} (str (:branch p :main))]
            [:td {:class "mono tiny"} (:subject p)]
            [:td {:class "tiny"} (:message p)]
            [:td [:button {:type "button" :class "btn sm"
                           :on-click (list 'seon.web/assign-health-fix! (:class p) (:subject p) (:message p))}
                  "Fix"]]])]])]))

(defn ns-tile
  "Schema-attached render for `:seon.code.ns/entity`."
  [{:keys [db]} ns-ent]
  (let [nsym (:seon.code.ns/name ns-ent)
        vars (when (and db nsym)
               (->> (d/datoms db :avet :seon.code.var/ns nsym)
                    (map #(d/pull db '[:seon.code.var/name :seon.code.var/arglists :seon.code.var/doc :seon.code.var/test?] (:e %)))
                    (sort-by #(str (:seon.code.var/name %)))
                    vec))
        children (when (and db (:db/id ns-ent))
                   (->> (d/datoms db :avet :seon.code.ns/parent (:db/id ns-ent))
                        (map #(:seon.code.ns/name (d/pull db '[:seon.code.ns/name] (:e %))))
                        sort vec))]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class "badge blue"} "ns"]
       [:strong {:class "mono"} (str nsym)]]
      [:span {:class "muted tiny mono"} (str (count vars) " vars · " (:seon.code.ns/path ns-ent ""))]]
     (when-let [doc (:seon.code.ns/doc ns-ent)]
       [:p {:class "doc-box sm-copy"} (first (str/split-lines doc))])
     (when (seq children)
       [:div {:class "pill-row" :style "margin-top:6px"}
        [:span {:class "muted tiny"} "Sub-namespaces:"]
        (for [c children] [:span {:class "pill mono"} (str c)])])
     (when (seq vars)
       [:details {:class "fold" :style "margin-top:8px"}
        [:summary {:class "muted tiny"} (str "Inspect " (count vars) " vars in " nsym)]
        [:div {:class "pill-row" :style "margin-top:6px"}
         (for [v vars]
           [:span {:class (str "pill mono" (when (:seon.code.var/test? v) " green-text"))}
            (name (:seon.code.var/name v))])]])]))

(defn var-tile
  "Schema-attached render for `:seon.code.var/entity`."
  [{:keys [db]} v]
  (let [vsym (:seon.code.var/name v)
        src (when (and db (:seon.code.var/form v))
              (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key (:seon.code.var/form v)])))
        callers (when (and db vsym)
                  (->> (d/datoms db :avet :seon.code.var/refs vsym)
                       (map #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                       sort vec))]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class (str "badge " (if (:seon.code.var/test? v) "green" "blue"))}
        (if (:seon.code.var/test? v) "test" "var")]
       [:strong {:class "mono"} (str vsym)]]
      (when (seq (:seon.code.var/arglists v))
        [:span {:class "muted tiny mono"} (pr-str (:seon.code.var/arglists v))])]
     (when-let [doc (:seon.code.var/doc v)]
       [:p {:class "doc-box sm-copy"} doc])
     (when-let [sc (:seon.code.var/schema v)]
       [:pre {:class "code-box tiny"} [:code (pr-str sc)]])
     (when (seq callers)
       [:div {:class "pill-row" :style "margin-top:6px"}
        [:span {:class "muted tiny"} (str "Callers (" (count callers) "):")]
        (for [c (take 8 callers)] [:span {:class "pill mono"} (str c)])])
     (when src
       [:details {:class "fold" :style "margin-top:8px"}
        [:summary {:class "muted tiny"} "Source form"]
        [:pre {:class "code-box tiny"} [:code src]]])]))

(defn doc-tile
  "Schema-attached render for `:seon.code.doc/entity`."
  [_opts doc-ent]
  [:div {:class "card tile-card"}
   [:div {:class "card-head"}
    [:div {:class "pill-row"}
     [:span {:class "badge amber"} "doc"]
     [:strong (or (:seon.code.doc/heading doc-ent) (:seon.code.doc/id doc-ent))]]
    [:span {:class "muted tiny mono"} (or (:seon.code.doc/path doc-ent) "")]]
   [:div {:class "doc-box sm-copy"}
    (let [b (str (:seon.code.doc/body doc-ent ""))]
      (if (> (count b) 700)
        [:div
         [:p (subs b 0 700) "…"]
         [:details {:class "fold"}
          [:summary {:class "muted tiny"} (str "Show full section (" (count b) " chars)")]
          [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} b]]]
        [:div {:style "white-space:pre-wrap"} b]))]])

(defn schema-tile
  "Schema-attached render for `:seon.schema/entity`."
  [{:keys [db]} sc-ent]
  (let [sk (:seon.schema/key sc-ent)
        dh (when db (get (:schema db) sk))]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class "badge blue"} "schema"]
       [:strong {:class "mono"} (str sk)]]
      (when dh
        [:span {:class "badge green mono"} (str (name (:db/valueType dh)) " · " (name (:db/cardinality dh)))])]
     (when-let [doc (:seon.schema/doc sc-ent)]
       [:p {:class "muted sm-copy"} doc])
     [:pre {:class "code-box tiny"} [:code (pr-str (:seon.schema/form sc-ent))]]]))

(defn task-tile
  "Schema-attached render for `:seon.task/entity`."
  [_opts t]
  [:div {:class "card tile-card"}
   [:div {:class "card-head"}
    [:div {:class "pill-row"}
     [:span {:class (str "badge " (if (:seon.task/done? t) "green" "blue"))}
      (if (:seon.task/done? t) "done" "task")]
     [:strong (:seon.task/title t)]]
    [:span {:class "muted tiny mono"} (str (:seon.task/id t))]]
   (when-let [p (:seon.task/plan t)]
     [:p {:class "sm-copy"} p])
   (when (seq (:seon.task/tests t))
     [:div {:class "pill-row"}
      (for [ts (:seon.task/tests t)] [:span {:class "pill mono"} (str ts)])])])

(defn agent-tile
  "Schema-attached render for `:seon.agent/entity` — updates `:seon.user/viewing` in Datahike on click!"
  [_opts ag]
  (let [aid (:seon.agent/id ag)]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class "badge blue"} (name (or (:seon.agent/role ag) :worker))]
       [:strong {:class "mono"} (str aid)]]
      [:div {:class "pill-row"}
       [:button {:type "button" :class "btn sm primary"
                 :on-click (list 'seon.web/view-agent! aid :workspace)} "Open →"]
       [:button {:type "button" :class "btn sm"
                 :on-click (list 'seon.web/view-agent! aid :debug)} "Debug"]]]
     [:p {:class "muted tiny mono" :style "margin:2px 0 6px"}
      (str "branch " (:seon.agent/branch ag :main)
           (when (:seon.agent/scope ag) (str " · scope " (:seon.agent/scope ag))))]
     (when (not (str/blank? (str (:seon.agent/goal ag))))
       [:p {:class "sm-copy"} (:seon.agent/goal ag)])]))

;; --- Quiet Phosphor Top Bar (`id="seon-header"`) ---

(defn header-unit [db {:keys [boot-seq]}]
  (let [{:keys [viewing-id view-mode sidebar-open?]} (user-view-state db)
        h (health/report db)
        ok? (:ok? h)
        issue-n (count (:problems h))
        mode-btn (fn [m label]
                   [:button {:type "button"
                             :class (str "nav-pill" (when (= view-mode m) " active"))
                             :on-click (list 'seon.web/set-view-mode! m)}
                    label])]
    [:header {:id "seon-header" :class "topbar" :data-boot-seq (str (or boot-seq 1))}
     [:script {:id "boot-meta" :type "application/json"}
      (render/raw-html (str "{\"bootSeq\":" (or boot-seq 1) ",\"mainTx\":" (:max-tx db) "}"))]
     [:div {:class "brand-row"}
      [:div {:class "brand"}
       [:button {:type "button"
                 :class (str "sidebar-toggle" (when sidebar-open? " active"))
                 :title (if sidebar-open? "Collapse agent tree sidebar" "Expand agent tree sidebar")
                 :on-click '(seon.web/toggle-sidebar!)}
        "◧"]
       [:button {:type "button" :class "brand-link"
                 :on-click '(seon.web/view-agent! :agent/root :canvas)}
        [:span {:class "diamond"} "◆"]
        [:strong {:class "wordmark"} "seon"]]
       [:span {:class "crumb"}
        (str (if (= viewing-id :agent/root) "root" (str viewing-id))
             " · " (name view-mode))]]
      [:div {:class "stats-strip"}
       [:button {:type "button"
                 :class (str "status-pill " (if ok? "ok" "bad"))
                 :on-click '(seon.web/set-view-mode! :debug)}
        [:span {:class (str "dot " (if ok? "live" "bad"))}]
        (if ok? "Healthy" (str issue-n " issue" (when (not= 1 issue-n) "s")))]
       [:div {:class "mode-group"}
        (when (= viewing-id :agent/root)
          (mode-btn :canvas "Canvas"))
        (mode-btn :workspace "Workspace")
        (mode-btn :debug "Debug")]]]]))

;; --- Collapsible Recursive Left Agent-Tree Sidebar (`id="seon-sidebar"`) ---

(defn- render-agent-tree-node [children-by-parent viewing-id expanded-set depth ag]
  (let [aid (:seon.agent/id ag)
        kids (get children-by-parent aid)
        has-kids? (boolean (seq kids))
        expanded? (contains? expanded-set aid)
        active? (= aid viewing-id)
        status-cls (case (:status ag) :merged "live" :running "warn" "live")]
    [:div {:class "tree-branch"}
     [:div {:class (str "tree-row" (when active? " active"))
            :style (str "padding-left:" (+ 8 (* depth 14)) "px")}
      (if has-kids?
        [:button {:type "button" :class "tree-exp"
                  :on-click (list 'seon.web/toggle-expand! aid)}
         (if expanded? "▾" "▸")]
        [:span {:class "tree-exp-spacer"} "·"])
      [:button {:type "button" :class "tree-select"
                :on-click (list 'seon.web/view-agent! aid (if (= aid :agent/root) :canvas :workspace))}
       [:span {:class (str "dot " status-cls)}]
       [:span {:class "tree-name mono"} (if (= aid :agent/root) "root" (str (or (namespace aid) "") (when (namespace aid) "/") (name aid)))]
       (when (and (:seon.agent/role ag) (not= aid :agent/root))
         [:span {:class "tree-role muted"} (name (:seon.agent/role ag))])]]
     (when (and has-kids? expanded?)
       (for [child kids]
         (render-agent-tree-node children-by-parent viewing-id expanded-set (inc depth) child)))]))

(defn sidebar-unit [db {:keys [conn]}]
  (let [{:keys [viewing-id sidebar-open? expanded-agents]} (user-view-state db)
        all-agents (->> (d/datoms db :aevt :seon.agent/id)
                        (map #(d/pull db '[:seon.agent/id :seon.agent/parent :seon.agent/branch
                                           :seon.agent/role :seon.agent/scope :seon.agent/goal] (:e %)))
                        (remove #(str/starts-with? (str (namespace (:seon.agent/id %))) "example"))
                        (sort-by (juxt #(if (= :agent/root (:seon.agent/id %)) 0 1) #(str (:seon.agent/id %))))
                        vec)
        by-id (into {} (map (juxt :seon.agent/id identity)) all-agents)
        children-by-parent (group-by :seon.agent/parent all-agents)
        roots (filterv (fn [a]
                         (let [p (:seon.agent/parent a)]
                           (or (nil? p) (not (contains? by-id p)))))
                       all-agents)]
    [:aside {:id "seon-sidebar" :class (str "sidebar" (if sidebar-open? " open" " closed"))}
     (when sidebar-open?
       [:div {:class "sidebar-inner"}
        [:div {:class "sidebar-head"}
         [:span {:class "kicker"} (str "Agents (" (count all-agents) ")")]
         [:span {:class "mono tiny muted"} (str "tx " (:max-tx db))]]
        [:div {:class "tree-scroll"}
         (if (empty? roots)
           [:p {:class "muted tiny"} "No agents yet."]
           (for [r roots]
             (render-agent-tree-node children-by-parent viewing-id expanded-agents 0 r)))]
        [:details {:class "sidebar-spawn"}
         [:summary {:class "kicker"} "+ Spawn Sub-Agent"]
         [:form {:class "form-stack" :style "margin-top:8px"
                 :on-submit (list 'seon.web/spawn-agent-form! viewing-id)}
          [:input {:class "inp sm" :name "agent_id" :placeholder ":area/worker-1"}]
          [:input {:class "inp sm" :name "scope" :placeholder "seon.area"}]
          [:input {:class "inp sm" :name "goal" :placeholder "Goal..."}]
          [:button {:type "submit" :class "btn primary sm"} (str "Spawn under " (name viewing-id))]]]
        [:div {:class "sidebar-foot"}
         [:button {:type "button" :class "btn sm" :on-click '(seon.web/run-tests! :main)} "▶ Tests"]
         [:button {:type "button" :class "btn sm danger" :on-click '(seon.web/power-loss-reboot!)} "⚡ Reboot"]]])]))

;; --- Dedicated 120fps Live Stream Strip (`id="seon-stream-unit"` — Hidden When Idle!) ---

(defn stream-unit [_db {:keys [live-streams errors frames]}]
  (let [active? (or (seq live-streams) (seq errors))]
    [:section {:id "seon-stream-unit" :class (str "stream-strip" (if active? " active" " idle"))}
     (when active?
       [:div {:class "row-between"}
        [:div {:class "pill-row"}
         (for [[aid sinfo] (sort-by (comp str key) live-streams)]
           [:span {:class "badge amber mono"}
            (str aid " · " (name (or (:status sinfo) :live)) " "
                 (let [t (str (:text sinfo ""))]
                   (if (> (count t) 72) (str "…" (subs t (- (count t) 72))) t)))])
         (when (seq errors)
           [:span {:class "red-text mono tiny"} (pr-str (first errors))])]
        [:span {:class "muted tiny mono"} (str "frame " (or frames 0))]])]))

;; --- Helper: Tile Wrapper ---

(defn- render-tile-card [branch-db {:keys [id source handle ref val]} agent-id]
  [:div {:id id :class "tile-wrap"}
   [:div {:class "tile-meta"}
    [:div {:class "pill-row"}
     [:span {:class (str "badge " (case source :eval "green" :pinned "amber" :child "blue" "muted"))}
      (name source)]
     (when handle
       [:span {:class "badge mono handle-pill"} (str handle)])]
    (when (and (= source :pinned) ref)
      [:button {:type "button" :class "link-muted tiny"
                :on-click (list 'seon.web/unpin-tile! agent-id ref)}
       "× unpin"])]
   (render/render-value branch-db val {:agent-id agent-id})])

;; --- VIEW 1: Clean Editorial Canvas (`:view-mode :canvas`) ---

(defn root-canvas [main-db {:keys [conn branch-feedback viewing-id]}]
  (let [aid (or viewing-id :agent/root)
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        ag (pull-agent-entity main-db bdb aid)
        evals (agent-evals-in bdb aid)
        msgs (agent-messages-in main-db aid)
        ;; Canvas ONLY displays explicitly pinned/presented visual blocks (never raw scope dumps!).
        canvas-tiles (filterv #(= :pinned (:source %))
                              (render/discover-renderable-tiles main-db bdb ag [] health/report))
        timeline (->> (concat
                       (map (fn [m] {:kind :msg :sort-k [0 (:seon.agent.message/seq m 0)] :data m}) msgs)
                       (map (fn [e] {:kind :eval :sort-k [1 (:seon.agent.eval/turn e 0) (:seon.agent.eval/call-index e 0)] :data e}) evals))
                      (sort-by :sort-k))]
    [:div {:id "seon-view-unit" :class "root-canvas"}
     [:div {:class "root-hero"}
      [:div {:class "root-kicker"} (str aid " · " branch " @ tx " (:max-tx bdb))]
      [:h1 {:class "root-title"} "What would you like to build or inspect?"]
      [:p {:class "root-sub"}
       "Talk to Root in plain language or evaluate Clojure directly. Root keeps your canvas calm, farms out work to specialist agents, and can open any agent's workspace for you."]]

     (when branch-feedback
       [:div {:class (str "notice " (if (:ok? branch-feedback) "green" "red"))}
        (:text branch-feedback)])

     (when (seq canvas-tiles)
       [:section {:class "root-visuals"}
        (for [tile canvas-tiles]
          (render-tile-card bdb tile aid))])

     [:section {:class "root-stream"}
      (if (empty? timeline)
        [:div {:class "empty-canvas"}
         [:p "Ask a question below, evaluate Clojure like " [:code "(user/health)"] ", or expand the left sidebar (" [:code "◧"] ") to browse the live agent tree."]]
        (for [[idx item] (map-indexed vector timeline)]
          (if (= :msg (:kind item))
            (let [m (:data item)
                  from-user? (= :user (:seon.agent.message/from m))]
              [:article {:id (str "rm-" idx) :class (str "chat-msg " (if from-user? "from-user" "from-agent"))}
               [:div {:class "chat-role"} (str (:seon.agent.message/from m))]
               [:div {:class "chat-body"} (:seon.agent.message/body m)]])
            (let [e (:data item)]
              [:article {:id (str "re-" idx) :class "chat-msg from-agent"}
               (when (not (str/blank? (str (:seon.agent.eval/thought e))))
                 [:div {:class "chat-body"} (:seon.agent.eval/thought e)])
               [:details {:class "eval-fold"}
                [:summary {:class "eval-summary"}
                 [:span {:class "badge mono handle-pill"} (str (or (:seon.agent.eval/handle e) 'result/e?))]
                 [:span {:class "mono tiny truncate"}
                  (or (not-empty (:seon.agent.eval/intent e)) (:seon.agent.eval/code e))]
                 [:span {:class (str "mono tiny " (if (:seon.agent.eval/error e) "red-text" "green-text"))}
                  (if (:seon.agent.eval/error e)
                    "error"
                    (let [vs (str (:seon.agent.eval/value e ""))]
                      (str "=> " (if (> (count vs) 56) (str (subs vs 0 56) "…") vs))))]
                 [:span {:class "muted tiny mono"} (str (:seon.agent.eval/ms e 0.0) " ms")]]
                [:div {:class "eval-detail"}
                 [:pre {:class "code-box tiny"} [:code (:seon.agent.eval/code e)]]
                 (when-let [err (:seon.agent.eval/error e)]
                   [:div {:class "notice red mono tiny"} err])
                 (when (not (str/blank? (str (:seon.agent.eval/out e))))
                   [:pre {:class "code-box tiny"} [:code (:seon.agent.eval/out e)]])
                 (when-let [v (:seon.agent.eval/value e)]
                   [:pre {:class "code-box tiny green-text"} [:code (str "=> " v)]])]]]))))]

     [:form {:class "root-composer" :on-submit '(seon.web/chat-root-form!)}
      [:textarea {:class "composer-inp" :name "text" :rows 2
                  :placeholder (str "Ask Root anything, or start with ( to eval Clojure on " branch "...")}]
      [:div {:class "composer-actions"}
       [:span {:class "muted tiny mono"} "Results bind to result/e<n> · (user/pin! ...) adds to canvas · (user/show-user! ...) moves your view"]
       [:button {:type "submit" :class "btn primary"} "Send"]]]]))

;; --- VIEW 2: Two-Panel Agent Workspace (`:view-mode :workspace`) ---

(defn agent-workspace [main-db {:keys [conn branch-feedback viewing-id]}]
  (let [aid (or viewing-id :agent/root)
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        ag (pull-agent-entity main-db bdb aid)
        evals (agent-evals-in bdb aid)
        msgs (agent-messages-in main-db aid)
        tiles (render/discover-renderable-tiles main-db bdb ag evals health/report)
        derived (when (and conn (first (d/datoms main-db :avet :seon.agent/id aid)))
                  (reconciler/derive-agent-status main-db conn {} #{} #{} aid))]
    [:div {:id "seon-view-unit" :class "workspace-grid"}
     [:section {:class "ws-left"}
      [:div {:class "ws-head"}
       [:div
        [:div {:class "pill-row"}
         [:h2 {:class "mono"} (str aid)]
         [:span {:class "badge amber"} (name (or (:seon.agent/role ag) :worker))]
         (when derived
           [:span {:class (str "badge " (case (:status derived) :merged "green" :running "amber" "blue"))}
            (name (:status derived))])]
        [:div {:class "muted tiny mono" :style "margin-top:3px"}
         (str "branch " branch " @ tx " (:max-tx bdb)
              (when (:seon.agent/scope ag) (str " · scope " (:seon.agent/scope ag))))]]
       [:div {:class "pill-row"}
        [:button {:type "button" :class "btn sm" :on-click (list 'seon.web/run-tests! branch)} "Test Branch"]
        (when (not= branch :main)
          [:button {:type "button" :class "btn sm success" :on-click (list 'seon.web/merge-branch! branch)} "Merge → :main"])]]

      (when branch-feedback
        [:div {:class (str "notice " (if (:ok? branch-feedback) "green" "red"))}
         (:text branch-feedback)])

      [:div {:class "ws-timeline"}
       (when (not (str/blank? (str (:seon.agent/goal ag))))
         [:div {:class "goal-banner"}
          [:span {:class "kicker"} "Goal"]
          [:p {:class "sm-copy" :style "margin:3px 0 0"} (:seon.agent/goal ag)]])

       (for [[idx m] (map-indexed vector msgs)]
         [:div {:id (str "am-" idx) :class "ws-msg"}
          [:div {:class "row-between tiny muted mono"}
           [:span (str "from " (:seon.agent.message/from m) " · " (name (or (:seon.agent.message/type m) :chat)))]
           [:span (str "#" (:seon.agent.message/seq m))]]
          [:div {:class "sm-copy" :style "margin-top:4px;white-space:pre-wrap"} (:seon.agent.message/body m)]])

       (if (empty? evals)
         [:p {:class "muted sm-copy" :style "padding:12px 0"}
          "No evals on this branch yet. Run a Clojure form below — every result binds to result/e<n> and renderable values appear in the right panel."]
         (for [[idx e] (map-indexed vector evals)]
           [:div {:id (str "ae-" idx) :class "ws-eval-card"}
            (when (not (str/blank? (str (:seon.agent.eval/thought e))))
              [:p {:class "sm-copy" :style "margin:0 0 6px"} (:seon.agent.eval/thought e)])
            (when (not (str/blank? (str (:seon.agent.eval/reasoning e))))
              [:details {:class "fold"}
               [:summary {:class "muted tiny"} "Reasoning trace"]
               [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} (:seon.agent.eval/reasoning e)]])
            [:details {:class "eval-fold" :open (>= idx (max 0 (- (count evals) 2)))}
             [:summary {:class "eval-summary"}
              [:span {:class "badge mono handle-pill"} (str (or (:seon.agent.eval/handle e) 'result/e?))]
              (when-let [rs (:seon.agent.eval/result-schema e)]
                [:span {:class "badge green tiny mono"} (name rs)])
              [:span {:class "mono tiny truncate"} (or (not-empty (:seon.agent.eval/intent e)) (:seon.agent.eval/code e))]
              [:span {:class "muted tiny mono"} (str (:seon.agent.eval/ms e 0.0) " ms · " (:seon.agent.eval/ticks e 0) " ticks")]]
             [:div {:class "eval-detail"}
              [:pre {:class "code-box tiny"} [:code (:seon.agent.eval/code e)]]
              (when (not (str/blank? (str (:seon.agent.eval/out e))))
                [:pre {:class "code-box tiny"} [:code (:seon.agent.eval/out e)]])
              (if-let [err (:seon.agent.eval/error e)]
                [:div {:class "notice red mono tiny"} err]
                [:pre {:class "code-box tiny green-text"} [:code (str "=> " (:seon.agent.eval/value e))]])]]]))]

      [:form {:class "ws-composer" :on-submit (list 'seon.web/eval-agent-form! aid branch)}
       [:textarea {:class "inp mono" :name "code" :rows 2
                   :placeholder (str "Eval Clojure on " branch " (e.g. (user/health), [:seon.code.doc/id \"docs/architecture.md#0\"], (user/show-user! {:mode :debug}))...")}]
       [:div {:class "row-between" :style "margin-top:6px"}
        [:span {:class "muted tiny mono"} (str "COW fork on " branch " · binds result/e<n>")]
        [:button {:type "submit" :class "btn primary sm"} (str "▶ Eval on " branch)]]]]

     [:section {:class "ws-right"}
      [:div {:class "ws-head"}
       [:div
        [:h2 (str "Live Context Tiles (" (count tiles) ")")]
        [:span {:class "muted tiny"} "Everything pulled in @seon.ctx/ctx or returned by evals with a :seon.render/hiccup renderer."]]
       [:button {:type "button" :class "nav-pill tiny"
                 :on-click '(seon.web/set-view-mode! :debug)} "Wire Debug →"]]

      [:form {:class "row-form" :style "margin-bottom:10px" :on-submit (list 'seon.web/pin-ref-form! aid)}
       [:input {:class "inp sm mono flex-1" :name "ref"
                :placeholder "Pin ref to walk: [:seon.code.ns/name 'seon.web] or [:seon.code.var/name 'seon.web/web-proc]"}]
       [:button {:type "submit" :class "btn sm"} "+ Pin to Walk"]]

      [:div {:class "tiles-scroll"}
       (if (empty? tiles)
         [:div {:class "card muted sm-copy"}
          "No renderable tiles in this agent's walk yet. Evaluate " [:code "(user/health)"] " or "
          [:code "[:seon.code.ns/name 'seon.render]"] " on the left to watch it appear here live."]
         (for [tile tiles]
           (render-tile-card bdb tile aid)))]]]))

;; --- VIEW 3: Zero-Cost Wire & System Debug (`:view-mode :debug`) ---

(defn- current-test-verdicts [db]
  (let [live-test-vars (into #{}
                             (keep (fn [dt]
                                     (let [v (d/pull db '[:seon.code.var/name :seon.code.var/test?] (:e dt))]
                                       (when (:seon.code.var/test? v) (:seon.code.var/name v)))))
                             (d/datoms db :aevt :seon.code.var/name))
        all (->> (d/datoms db :aevt :seon.test/digest)
                 (map #(d/pull db '[:seon.test/digest :seon.test/var :seon.test/kind
                                    :seon.test/pass? :seon.test/ms :seon.test/ticks :seon.test/report] (:e %)))
                 (filter #(contains? live-test-vars (:seon.test/var %))))
        latest-by-var (->> all
                           (group-by :seon.test/var)
                           (map (fn [[_ vs]] (last (sort-by #(or (:seon.test/pass? %) false) vs))))
                           (sort-by (juxt #(if (:seon.test/pass? %) 1 0) #(str (:seon.test/var %))))
                           vec)]
    {:current latest-by-var
     :historical (vec (sort-by #(str (:seon.test/var %)) all))}))

(defn- wire-role-badge [role]
  (case role
    "system"    "badge amber"
    "user"      "badge blue"
    "assistant" "badge green"
    "tool"      "badge purple"
    "badge"))

(defn- detect-xml-tags [s]
  (when (string? s)
    (vec (distinct (map second (re-seq #"<(/?(?:session|agent|walk-context|walk-diff|message|live-viewport))\b" s))))))

(defn debug-view [main-db {:keys [conn walk-summary branch-feedback viewing-id selected-branch]}]
  (let [aid (or viewing-id :agent/root)
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        wire (build-live-wire-request main-db bdb aid {:viewport-screenshot nil :vision? false})
        req-body (model/build-request-body {:model (store/config-val main-db aid :seon.config/ai-model)
                                            :messages (:messages wire)
                                            :thinking (store/config-val main-db aid :seon.config/ai-thinking)}
                                           nil nil nil)
        {:keys [current historical]} (current-test-verdicts bdb)
        branches (when conn (sort (d/branches conn)))
        sel-br (or selected-branch (first (remove #{:main} branches)))
        diff-info (when (and conn sel-br (not= sel-br :main))
                    (try (merge/diff-branch conn :main sel-br) (catch Throwable _ nil)))
        h (health/report bdb)]
    [:div {:id "seon-view-unit" :class "debug-wrap"}
     (when branch-feedback
       [:div {:class (str "notice " (if (:ok? branch-feedback) "green" "red"))}
        (:text branch-feedback)])

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:div
        [:h2 (str "Exact Wire Request for " aid " (branch " branch ")")]
        [:span {:class "muted tiny"} "Built by model/build-agent-wire-messages — zero second interpretation. Prefix messages stay byte-identical for KV cache."]]
       [:div {:class "pill-row"}
        [:span {:class "badge blue mono"} (str "~" (:total-tokens wire) " tokens")]
        [:span {:class "badge green mono"} (str (count (:messages wire)) " wire msgs")]
        [:span {:class "badge mono"} (str "model: " (:model req-body))]]]

      [:div {:class "wire-list"}
       (for [[idx [m toks]] (map-indexed vector (map vector (:messages wire) (:message-tokens wire)))
             :let [role (:role m)
                   content (:content m)
                   text-body (if (sequential? content)
                               (str/join "\n" (map #(if (= "image_url" (:type %)) "[IMAGE_URL: ephemeral live viewport]" (:text %)) content))
                               (str content))
                   xml-tags (detect-xml-tags text-body)
                   preview (let [line (first (remove str/blank? (str/split-lines text-body)))]
                             (if (> (count (str line)) 120) (str (subs line 0 120) "…") (str line)))]]
         [:details {:id (str "wm-" idx) :class "wire-row" :open (< idx 4)}
          [:summary {:class "wire-summary"}
           [:span {:class "muted tiny mono"} (str "#" idx)]
           [:span {:class (wire-role-badge role)} (str/upper-case role)]
           (for [tag xml-tags]
             [:span {:class "badge mono xml-tag"} (str "<" tag ">")])
           (when (seq (:tool_calls m))
             [:span {:class "badge green mono"} (str (count (:tool_calls m)) " tool_call(s)")])
           (when (:tool_call_id m)
             [:span {:class "badge mono"} (str "call_id=" (:tool_call_id m))])
           [:span {:class "muted tiny truncate flex-1"} preview]
           [:span {:class "mono tiny muted"} (str "~" toks " tok")]]
          [:div {:class "wire-body"}
           (when (:reasoning_content m)
             [:div {:style "margin-bottom:6px"}
              [:span {:class "kicker"} "reasoning_content"]
              [:pre {:class "code-box tiny"} (:reasoning_content m)]])
           (when (not (str/blank? text-body))
             [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} text-body])
           (when (seq (:tool_calls m))
             [:div {:style "margin-top:6px"}
              [:span {:class "kicker"} "tool_calls"]
              [:pre {:class "code-box tiny"} (json/write-str (:tool_calls m))]])]])]

      [:details {:class "fold" :style "margin-top:10px"}
       [:summary {:class "muted tiny"} "Raw OpenAI-Compat / DeepSeek Request JSON"]
       [:pre {:class "code-box tiny" :style "max-height:360px;overflow:auto"}
        (json/write-str req-body)]]]

     [:div {:class "grid-2"}
      [:section {:class "card"}
       [:div {:class "card-head"}
        [:h2 (str "Walk -> Model Text Projections (" (count (:tiles wire)) " tiles)")]
        [:span {:class "muted tiny"} "Same render/render-value Hiccup projected via render/hiccup->text"]]
       (if (empty? (:tiles wire))
         [:p {:class "muted sm-copy"} "No tiles discovered for this agent yet."]
         [:div {:class "stack-sm"}
          (for [t (:tiles wire)]
            [:details {:class "fold"}
             [:summary {:class "mono tiny"}
              (str "[" (name (:source t)) (when (:handle t) (str " " (:handle t))) "] " (:schema t))]
             [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} (:text t)]])])]

      [:section {:class "card"}
       [:div {:class "card-head"}
        [:h2 "Live datahike.reads Read-Sets (Incremental Walk)"]
        [:span {:class "muted tiny"} "0 DB work on untouched units"]]
       [:table {:class "data-table"}
        [:thead [:tr [:th "Mounted Unit"] [:th "Reads"] [:th "Sample Read Index Keys"]]]
        [:tbody
         (for [{:keys [id reads-count reads-idx]} walk-summary]
           [:tr
            [:td {:class "mono"} (str "#" id)]
            [:td {:class "mono"} (str (or reads-count 0))]
            [:td {:class "mono tiny muted"} (pr-str (vec (take 6 reads-idx)))]])]]]]

     [:div {:class "grid-2"}
      [:div {:class "stack"}
       (render/render-value bdb h)
       [:section {:class "card"}
        [:div {:class "card-head"}
         [:h2 (str "Current Reach-Cached Test Verdicts (" (count current) ")")]
         [:span {:class "muted tiny"} "Filtered to live test vars"]]
        (if (empty? current)
          [:p {:class "muted sm-copy"} "No test digests recorded yet."]
          [:table {:class "data-table"}
           [:thead [:tr [:th "Test Var"] [:th "Verdict"] [:th "Cost"]]]
           [:tbody
            (for [t current]
              [:tr
               [:td {:class "mono tiny"} (str (:seon.test/var t))]
               [:td [:span {:class (str "badge " (if (:seon.test/pass? t) "green" "red"))}
                     (if (:seon.test/pass? t) "PASS" "FAIL")]]
               [:td {:class "mono tiny muted"} (str (:seon.test/ms t) " ms · " (quot (long (:seon.test/ticks t 0)) 1000) "k ticks")]])]])
        (when (> (count historical) (count current))
          [:details {:class "fold" :style "margin-top:8px"}
           [:summary {:class "muted tiny"} (str "Show " (count historical) " historical cached digests")]
           [:pre {:class "code-box tiny"}
            (str/join "\n" (map #(str (:seon.test/var %) " " (subs (:seon.test/digest %) 0 10) " " (if (:seon.test/pass? %) "PASS" "FAIL")) historical))]])]]

      [:div {:class "stack"}
       [:section {:class "card"}
        [:div {:class "card-head"}
         [:h2 (str "Branches & 3-Way Git Diff (" (count branches) ")")]
         [:span {:class "muted tiny"} "0 worktrees · refs/heads/seon/*"]]
        [:div {:class "pill-row" :style "margin-bottom:8px"}
         (for [b branches]
           [:button {:type "button"
                     :class (str "btn sm" (when (= b sel-br) " primary"))
                     :on-click (list 'seon.web/select-branch! b)}
            (str b)])]
        (if diff-info
          [:div {:class "stack-sm"}
           [:div {:class "pill-row"}
            [:span {:class "badge green"} (str "+" (count (:added diff-info)) " added")]
            [:span {:class "badge blue"} (str "~" (count (:changed diff-info)) " changed")]
            [:span {:class "badge red"} (str "-" (count (:removed diff-info)) " removed")]
            [:button {:type "button" :class "btn sm success" :on-click (list 'seon.web/merge-branch! sel-br)}
             (str "Merge " sel-br " → :main")]]
           (when (seq (:changed diff-info))
             [:pre {:class "code-box tiny"} (pr-str (:changed diff-info))])]
          [:p {:class "muted sm-copy"} "All branches are synced with :main (or select a branch above to inspect its 3-way diff)."])]

       [:section {:class "card"}
        [:div {:class "card-head"}
         [:h2 "Register Global Malli Schema (→ Auto Datahike)"]
         [:span {:class "muted tiny"} (str (count (d/datoms main-db :aevt :seon.schema/key)) " schemas · " (count (:schema main-db)) " DH attrs")]]
        [:form {:class "form-stack" :on-submit '(seon.web/register-schema-form!)}
         [:div {:class "row-form"}
          [:input {:class "inp sm mono" :name "key" :placeholder ":seon.app.billing/amount"}]
          [:input {:class "inp sm mono flex-1" :name "form" :placeholder "[:double {:min 0.0}]"}]]
         [:div {:class "row-form"}
          [:input {:class "inp sm flex-1" :name "doc" :placeholder "Description..."}]
          [:button {:type "submit" :class "btn sm primary"} "Register Schema"]]]]]]]))

;; --- Active View Unit Dispatching Purely From Datahike `:user/default` ---

(defn view-unit [db opts]
  (let [{:keys [viewing-id view-mode selected-branch]} (user-view-state db)
        view-opts (assoc opts :viewing-id viewing-id :selected-branch selected-branch)]
    (case view-mode
      :canvas    (root-canvas db view-opts)
      :workspace (agent-workspace db view-opts)
      :debug     (debug-view db view-opts)
      (root-canvas db view-opts))))

;; --- Incremental Walk Unit Specs (4 Units: Header, Recursive Sidebar, 120fps Stream, DB-Driven View) ---

(defn page-unit-specs
  "Returns the 4 mounted incremental-walk units — 100% driven by Datahike `:user/default` (zero
   ephemeral navigation state in `web-proc`!):
   1. `seon-header`: quiet Phosphor top bar + mode switcher
   2. `seon-sidebar`: collapsible recursive agent-tree sidebar (`:seon.agent/parent`)
   3. `seon-stream-unit`: 120fps live stream strip (0 DB reads -> skips ALL 3 DB units on stream ticks!)
   4. `seon-view-unit`: active `:canvas`, `:workspace`, or `:debug` projection for `:seon.user/viewing`."
  [st]
  (let [walk-summary (mapv (fn [uid]
                             (let [u (get-in (:walk st) [:units uid])]
                               {:id uid :reads-count (:reads-count u 0) :reads-idx (:reads-idx u)}))
                           (remove #(= "seon-stream-unit" %) (get-in st [:walk :order])))]
    [{:id "seon-header"
      :comp header-unit
      :args [(select-keys st [:boot-seq])]}
     {:id "seon-sidebar"
      :comp sidebar-unit
      :args [(select-keys st [:conn])]}
     {:id "seon-stream-unit"
      :comp stream-unit
      :args [(select-keys st [:live-streams :errors :frames])]}
     {:id "seon-view-unit"
      :comp view-unit
      :args [(assoc (select-keys st [:conn :branch-feedback])
                    :walk-summary walk-summary)]}]))
