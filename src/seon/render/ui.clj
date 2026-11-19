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
            [fipp.edn :as fipp]
            [seon.ctx :as ctx]
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
      (assoc base
             :seon.agent/pinned (or (latest-attr-val main-db branch-db ma ba :seon.agent/pinned)
                                    (:seon.agent/pinned base))
             :seon.agent/stage (or (latest-attr-val main-db branch-db ma ba :seon.agent/stage)
                                   (:seon.agent/stage base))
             :seon.agent/render-fn (latest-attr-val main-db branch-db ma ba :seon.agent/render-fn))
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

(defn- evals->turns
  ([evals] (evals->turns evals nil))
  ([evals msgs-by-id]
   (->> (group-by :seon.agent.eval/turn evals)
        (sort-by key)
        (mapv (fn [[turn es]]
                (let [ordered (sort-by #(or (:seon.agent.eval/call-index %) 0) es)
                      first-e (first ordered)
                      seen-ids (into #{} (mapcat :seon.agent.eval/seen-messages) ordered)
                      turn-msgs (when (and msgs-by-id (seq seen-ids))
                                  (->> seen-ids
                                       (keep #(get msgs-by-id %))
                                       (sort-by :seon.agent.message/seq)
                                       vec))]
                  (cond-> {:turn turn
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
                                        ordered)}
                    (seq turn-msgs) (assoc :messages turn-msgs))))))))

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
               :history (evals->turns evals (into {} (map (juxt :seon.agent.message/id identity)) msgs))
               :unread unread
               :viewport-screenshot viewport-screenshot})]
    (assoc wire
           :agent ag
           :tiles tile-projections
           :evals evals
           :messages-inbox msgs
           :unread unread)))

;; --- Schema-Attached Entity Tile Renderers (`:seon.render/hiccup`) ---

(defn ctx-tile
  "Schema-attached render for `:seon.ctx/snapshot` — shows the agent's live `@seon.ctx/ctx` state
   and, in `:mode :agent`, colocates the exact REPL & Canvas affordances right on the data."
  [{:keys [mode]} snap]
  (let [aid (:seon.agent/id snap)
        branch (:seon.agent/branch snap :main)
        rfn (:seon.agent/render-fn snap 'seon.render.ui/root-canvas)
        stage (vec (or (:seon.agent/stage snap) []))
        pinned (vec (or (:seon.agent/pinned snap) []))]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class "badge amber"} "@ctx"]
       [:strong {:class "mono"} (str aid)]]
      [:span {:class "muted tiny mono"} (str branch " · tx " (:seon.ctx/tx snap 0))]]
     [:dl {:class "kv-grid"}
      [:dt ":seon.agent/role"] [:dd (str (:seon.agent/role snap :worker))]
      [:dt ":seon.agent/render-fn"] [:dd (str rfn)]
      [:dt ":seon.agent/stage"] [:dd (if (seq stage)
                                       (pr-str (mapv (fn [s] [(:key s) (:title s)]) stage))
                                       "[] (empty — use (user/present! :k \"Title\" [:div ...]))")]
      [:dt ":seon.agent/pinned"] [:dd (pr-str pinned)]]
     (when (= :agent mode)
       [:div {:class "doc-box sm-copy" :style "margin-top:8px"}
        [:p "Live REPL affordances colocated on @seon.ctx/ctx (also bound as user/ctx):"]
        [:ul
         [:li "@ctx is a reactive Datahike CtxAtom — read with @ctx, update with (swap! ctx assoc ...). Never shadow it with (def ctx ...)."]
         [:li "Present any live HTML/SVG/chart visual directly onto the user's Canvas in 1 call: (user/present! :my-chart \"Chart Title\" [:div [:svg {:viewBox \"0 0 640 280\"} ...]]) (supports Seon Phosphor CSS vars var(--signal), var(--text-100), var(--base-800), var(--success), var(--info)). Clear with (user/unpresent! :k) or (user/clear-stage!)."]
         [:li "Replace the entire Canvas renderer live on your branch (takes effect in realtime, fault-isolated!): define (ns my.ui) (defn canvas [db opts] [:div ...]) then (user/set-render! 'my.ui/canvas). Merge UI refinements to :main with (user/merge-self!)."]
         [:li "Query Datahike with (d/q ... (user/db)) or (d/pull (user/db) '[*] ref) — `d` (datahike.api) and `str` (clojure.string) are already aliased in `user`."]
         [:li "Read any var or namespace source straight from Datahike: (user/source 'seon.render.ui/root-canvas) or (user/search \"term\")."]
         [:li "Pin entity tiles with (user/pin! [:seon.code.ns/name 'ns]) or (user/pin! result/e1); move the user's screen with (user/show-user! {:viewing " (str aid) " :mode :canvas|:workspace|:debug})."]]])]))

(defn health-panel
  "Schema-attached render for `:seon.health/report` (`{:seon.render/hiccup seon.render.ui/health-panel}`)."
  [{:keys [mode]} report]
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
                  "Fix"]]])]])
     (when (= :agent mode)
       [:p {:class "tiny muted"} "REPL: (user/health) recomputes live health on your branch DB."])]))

(defn ns-tile
  "Schema-attached render for `:seon.code.ns/entity`."
  [{:keys [db mode]} ns-ent]
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
            (name (:seon.code.var/name v))])]])
     (when (= :agent mode)
       [:p {:class "tiny muted"} (str "REPL: (user/source '" nsym ") reads full ns source; (user/source '" nsym "/fn) reads one var.")])]))

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
  [{:keys [mode]} ag]
  (let [aid (:seon.agent/id ag)]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class "badge blue"} (name (or (:seon.agent/role ag) :worker))]
       [:strong {:class "mono"} (str aid)]]
      [:div {:class "pill-row"}
       [:button {:type "button" :class "btn sm primary"
                 :on-click (list 'seon.web/view-agent! aid :canvas)} "Canvas"]
       [:button {:type "button" :class "btn sm"
                 :on-click (list 'seon.web/view-agent! aid :workspace)} "Workspace"]
       [:button {:type "button" :class "btn sm"
                 :on-click (list 'seon.web/view-agent! aid :debug)} "Debug"]]]
     [:p {:class "muted tiny mono" :style "margin:2px 0 6px"}
      (str "branch " (:seon.agent/branch ag :main)
           (when (:seon.agent/scope ag) (str " · scope " (:seon.agent/scope ag))))]
     (when (not (str/blank? (str (:seon.agent/goal ag))))
       [:p {:class "sm-copy"} (:seon.agent/goal ag)])
     (when (= :agent mode)
       [:p {:class "tiny muted"} (str "REPL: (user/send-message! " aid " :chat \"...\") or (user/show-user! {:viewing " aid " :mode :canvas}).")])]))

;; --- Quiet Phosphor Top Bar (`id="seon-header"`) ---

(defn header-unit [db {:keys [boot-seq agent-states]}]
  (let [{:keys [viewing-id view-mode sidebar-open?]} (user-view-state db)
        h (health/report db)
        ok? (:ok? h)
        issue-n (count (:problems h))
        active? (= :active (get agent-states viewing-id))
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
             " · " (name view-mode))]
       (when active?
         [:span {:class "live-pill"}
          [:span {:class "dot pulsing"}]
          "working"])]
      [:div {:class "stats-strip"}
       [:button {:type "button"
                 :class (str "status-pill " (if ok? "ok" "bad"))
                 :on-click '(seon.web/set-view-mode! :debug)}
        [:span {:class (str "dot " (if ok? "live" "bad"))}]
        (if ok? "Healthy" (str issue-n " issue" (when (not= 1 issue-n) "s")))]
       [:div {:class "mode-group"}
        (mode-btn :canvas "Canvas")
        (mode-btn :workspace "Workspace")
        (mode-btn :debug "Debug")]]]]))

;; --- Collapsible Recursive Left Agent-Tree Sidebar (`id="seon-sidebar"`) ---

(defn- render-agent-tree-node [children-by-parent viewing-id expanded-set agent-states depth ag]
  (let [aid (:seon.agent/id ag)
        kids (get children-by-parent aid)
        has-kids? (boolean (seq kids))
        expanded? (contains? expanded-set aid)
        selected? (= aid viewing-id)
        working? (= :active (get agent-states aid))]
    [:div {:class "tree-branch"}
     [:div {:class (str "tree-row" (when selected? " active"))
            :style (str "padding-left:" (+ 8 (* depth 14)) "px")}
      (if has-kids?
        [:button {:type "button" :class "tree-exp"
                  :on-click (list 'seon.web/toggle-expand! aid)}
         (if expanded? "▾" "▸")]
        [:span {:class "tree-exp-spacer"} "·"])
      [:button {:type "button" :class "tree-select"
                :on-click (list 'seon.web/view-agent! aid :canvas)}
       [:span {:class (str "dot " (if working? "pulsing" "live"))}]
       [:span {:class "tree-name mono"} (if (= aid :agent/root) "root" (str (or (namespace aid) "") (when (namespace aid) "/") (name aid)))]
       (cond
         working?
         [:span {:class "badge amber tree-role"} "live"]
         (and (:seon.agent/role ag) (not= aid :agent/root))
         [:span {:class "tree-role muted"} (name (:seon.agent/role ag))])]]
     (when (and has-kids? expanded?)
       (for [child kids]
         (render-agent-tree-node children-by-parent viewing-id expanded-set agent-states (inc depth) child)))]))

(defn sidebar-unit [db {:keys [agent-states]}]
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
             (render-agent-tree-node children-by-parent viewing-id expanded-agents agent-states 0 r)))]
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

;; --- In-Place 120fps Live Stream Dock (`id="seon-stream-unit"` — 0 DB Reads!) ---

(defn stream-unit [_db {:keys [live-streams errors]}]
  (let [active? (or (seq live-streams) (seq errors))]
    [:section {:id "seon-stream-unit" :class (str "stream-dock" (if active? " active" " idle"))}
     (when active?
       [:div {:class "stack-sm"}
        (for [[aid sinfo] (sort-by (comp str key) live-streams)
              :let [rtxt (str (:reasoning sinfo ""))
                    txt  (str (:text sinfo ""))
                    has-r? (not (str/blank? rtxt))
                    has-t? (not (str/blank? txt))
                    phase (case (:status sinfo)
                            :reasoning "Thinking"
                            :token "Writing"
                            "Thinking")]]
          [:div {:class "live-stream-card"}
           [:details {:class "live-thinking-fold" :open (and has-r? (not has-t?))}
            [:summary
             [:span {:class "dot pulsing"}]
             [:span {:class "strong"} (if (= aid :agent/root) "root" (str aid))]
             [:span {:class "muted"} (str "· " phase "…")]]
            (when has-r?
              [:div {:class "live-reasoning"}
               (if (> (count rtxt) 1200) (str "…" (subs rtxt (- (count rtxt) 1200))) rtxt)])]
           (if has-t?
             [:div {:class "live-prose"} txt [:span {:class "cursor"} "▍"]]
             (when-not has-r?
               [:div {:class "muted tiny mono"} "preparing response… " [:span {:class "cursor"} "▍"]]))])
        (when (seq errors)
          [:div {:class "notice red mono tiny"} (pr-str (first errors))])])]))

;; --- Dedicated Sticky Canvas Composer (`id="seon-composer-unit"` — Never Interrupted by Streaming!) ---

(defn composer-unit [db _opts]
  (let [{:keys [viewing-id view-mode]} (user-view-state db)
        show? (= view-mode :canvas)
        branch (when show?
                 (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id]))
                     (if (= viewing-id :agent/root) :branch/root :main)))
        label (if (= viewing-id :agent/root) "Root" (str viewing-id))]
    [:section {:id "seon-composer-unit" :class (str "composer-dock" (if show? " active" " idle"))}
     (when show?
       [:form {:class "root-composer" :on-submit (list 'seon.web/chat-root-form! viewing-id)}
        [:textarea {:class "composer-inp" :name "text" :rows 2
                    :data-on:keydown "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.target.value.trim()){evt.target.form.requestSubmit();}}"
                    :placeholder (str "Ask " label " to visualize, explore, or refine this view — or start with ( to eval Clojure on " branch "...")}]
        [:div {:class "composer-actions"}
         [:span {:class "muted tiny mono"} "Enter sends · Shift+Enter newline · Canvas stays in the present · Workspace holds full log"]
         [:button {:type "submit" :class "btn primary"} "Send"]]])]))

;; --- Helper: Tile Wrapper ---

(defn- render-tile-card [branch-db {:keys [id source handle ref val]} agent-id]
  [:div {:id id :class "tile-wrap"}
   [:div {:class "tile-meta"}
    [:div {:class "pill-row"}
     [:span {:class (str "badge " (case source :eval "green" :pinned "amber" :ctx "amber" :child "blue" "muted"))}
      (name source)]
     (when handle
       [:span {:class "badge mono handle-pill"} (str handle)])]
    (when (and (= source :pinned) ref)
      [:button {:type "button" :class "link-muted tiny stage-remove"
                :on-click (list 'seon.web/unpin-tile! agent-id ref)}
       "× unpin"])]
   (render/render-value branch-db val {:agent-id agent-id})])

(defn- interleave-timeline
  "Interleaves inbox messages and evals in true conversational order using `:seon.agent.eval/seen-messages`:
   messages consumed by turn T appear directly above turn T's evals/reply; unseen messages appear at the end."
  [msgs evals]
  (let [msgs-by-id (into {} (map (juxt :seon.agent.message/id identity)) msgs)
        by-turn (->> (group-by #(or (:seon.agent.eval/turn %) 0) evals)
                     (sort-by key))
        [items emitted-ids]
        (reduce
         (fn [[acc seen] [_turn es]]
           (let [ordered (sort-by #(or (:seon.agent.eval/call-index %) 0) es)
                 turn-seen-ids (->> (mapcat :seon.agent.eval/seen-messages ordered)
                                    distinct
                                    (remove seen))
                 turn-msgs (->> turn-seen-ids
                                (keep #(get msgs-by-id %))
                                (sort-by :seon.agent.message/seq))]
             [(-> acc
                  (into (map (fn [m] {:kind :msg :data m}) turn-msgs))
                  (into (map (fn [e] {:kind :eval :data e}) ordered)))
              (into seen (map :seon.agent.message/id turn-msgs))]))
         [[] #{}]
         by-turn)
        trailing (->> msgs
                      (remove #(contains? emitted-ids (:seon.agent.message/id %)))
                      (sort-by :seon.agent.message/seq)
                      (map (fn [m] {:kind :msg :data m})))]
    (into items trailing)))

(defn- render-inline-prose
  "Formats inline backtick code spans (e.g. `:branch/root`) inside editorial prose into crisp monospace spans."
  [s]
  (let [parts (str/split (str s) #"`" -1)]
    (if (< (count parts) 3)
      (str s)
      (vec (cons :span
                 (map-indexed (fn [i seg]
                                (if (odd? i)
                                  [:code {:class "inline-code"} seg]
                                  seg))
                              parts))))))

(defn- split-exchanges
  "Groups an interleaved timeline into conversational exchanges (each starting at a `:user` message)."
  [timeline]
  (reduce
   (fn [acc item]
     (if (and (= :msg (:kind item)) (= :user (:seon.agent.message/from (:data item))))
       (conj acc [item])
       (if (seq acc)
         (conj (pop acc) (conj (peek acc) item))
         [[item]])))
   []
   timeline))

;; --- VIEW 1: Present-Moment Editorial Canvas (`:view-mode :canvas` — For ALL Agents!) ---

(defn root-canvas [main-db {:keys [conn branch-feedback viewing-id agent-states]}]
  (let [aid (or viewing-id :agent/root)
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        ag (pull-agent-entity main-db bdb aid)
        evals (agent-evals-in bdb aid)
        msgs (agent-messages-in main-db aid)
        stage (vec (or (:seon.agent/stage ag) []))
        ;; Canvas ONLY displays presented stage visuals + explicitly pinned entity tiles (never raw scope dumps!).
        canvas-tiles (filterv #(= :pinned (:source %))
                              (render/discover-renderable-tiles main-db bdb ag [] health/report))
        timeline (interleave-timeline msgs evals)
        exchanges (split-exchanges timeline)
        current-ex (peek exchanges)
        past-count (max 0 (dec (count exchanges)))
        cur-msgs (filter #(= :msg (:kind %)) current-ex)
        cur-evals (map :data (filter #(= :eval (:kind %)) current-ex))
        prose-parts (->> cur-evals (map :seon.agent.eval/thought) (remove #(str/blank? (str %))) vec)
        action-evals (filterv #(or (not (str/blank? (str (:seon.agent.eval/code %))))
                                   (not (str/blank? (str (:seon.agent.eval/reasoning %)))))
                              cur-evals)
        total-ms (Math/round (double (reduce + 0.0 (map #(or (:seon.agent.eval/ms %) 0.0) action-evals))))
        active? (= :active (get agent-states aid))
        fresh? (and (empty? stage) (empty? canvas-tiles) (empty? timeline))]
    [:div {:id "seon-view-unit" :class "root-canvas"}
     [:div {:class "canvas-eyebrow"}
      [:div {:class "pill-row"}
       [:span {:class (str "dot " (if active? "pulsing" "live"))}]
       [:span {:class "mono tiny strong"} (if (= aid :agent/root) "root" (str aid))]
       [:span {:class "muted tiny mono"} (str branch " · tx " (:max-tx bdb))]
       (when (and (:seon.agent/role ag) (not= aid :agent/root))
         [:span {:class "badge muted"} (name (:seon.agent/role ag))])]
      (when (seq timeline)
        [:button {:type "button" :class "nav-pill tiny"
                  :on-click '(seon.web/set-view-mode! :workspace)}
         (str "Workspace (" (count evals) " evals) →")])]

     (when (and branch-feedback (not (:ok? branch-feedback)))
       [:div {:class "notice red row-between"}
        [:span (:text branch-feedback)]
        [:button {:type "button" :class "link-muted tiny" :on-click '(seon.web/dismiss-feedback!)} "×"]])

     (when fresh?
       [:div {:class "root-hero"}
        [:div {:class "root-kicker"} (str aid " · " branch)]
        [:h1 {:class "root-title"}
         (if (= aid :agent/root)
           "What would you like to build or visualize?"
           (str (name aid) " — ready on " branch))]
        [:p {:class "root-sub"}
         (or (not-empty (:seon.agent/goal ag))
             "Ask in plain language or evaluate Clojure directly. Visuals appear live on this canvas; full conversation and REPL history stay in Workspace.")]])

     (when (or (seq stage) (seq canvas-tiles))
       [:section {:class "canvas-stage"}
        (for [{:keys [key title hiccup]} stage]
          [:article {:id (str "stage-" (render/sanitize-id (or key "v"))) :class "stage-card"}
           [:div {:class "stage-card-head"}
            [:span {:class "kicker"} (or (not-empty title) (name (or key :visual)))]
            [:button {:type "button" :class "link-muted tiny stage-remove"
                      :title "Remove from stage"
                      :on-click (list 'seon.web/unpresent-stage! aid key)}
             "×"]]
           hiccup])
        (when (seq canvas-tiles)
          [:div {:class "root-visuals"}
           (for [tile canvas-tiles]
             (render-tile-card bdb tile aid))])])

     (when (seq current-ex)
       [:section {:class "canvas-present"}
        (for [[idx m-item] (map-indexed vector cur-msgs)
              :let [m (:data m-item)]]
          [:div {:id (str "pm-" idx) :class "present-prompt"}
           (:seon.agent.message/body m)])

        (when (or (seq prose-parts) (seq action-evals))
          [:div {:class "present-answer"}
           (for [[idx p] (map-indexed vector prose-parts)]
             [:div {:id (str "pa-" idx) :class "chat-body"}
              (render-inline-prose p)])

           (when (seq action-evals)
             [:details {:class "activity-fold"}
              [:summary
               [:span (str "Thinking & actions · "
                           (count action-evals) " step" (when (not= 1 (count action-evals)) "s")
                           (when (pos? total-ms) (str " · " total-ms " ms")))]]
              [:div {:class "activity-body"}
               (for [[idx e] (map-indexed vector action-evals)
                     :let [has-code? (not (str/blank? (str (:seon.agent.eval/code e))))]]
                 [:div {:id (str "act-" idx) :class "stack-sm"}
                  (when (not (str/blank? (str (:seon.agent.eval/reasoning e))))
                    [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} (:seon.agent.eval/reasoning e)])
                  (when has-code?
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
                        [:pre {:class "code-box tiny green-text"} [:code (str "=> " v)]])]])])]])])

        (when (pos? past-count)
          [:div {:class "history-bar"}
           [:span {:class "muted tiny mono"}
            (str past-count " earlier exchange" (when (not= 1 past-count) "s") " archived in Workspace")]
           [:button {:type "button" :class "nav-pill tiny"
                     :on-click '(seon.web/set-view-mode! :workspace)}
            "Open Workspace →"]])])]))

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

       (let [timeline (interleave-timeline msgs evals)]
         (if (empty? timeline)
           [:p {:class "muted sm-copy" :style "padding:12px 0"}
            "No messages or evals on this branch yet. Send a message or run a Clojure form below — every result binds to result/e<n> and renderable values appear in the right panel."]
           (for [[idx item] (map-indexed vector timeline)]
             (if (= :msg (:kind item))
               (let [m (:data item)]
                 [:div {:id (str "am-" idx) :class "ws-msg"}
                  [:div {:class "row-between tiny muted mono"}
                   [:span (str "from " (:seon.agent.message/from m) " · " (name (or (:seon.agent.message/type m) :chat)))]
                   [:span (str "#" (:seon.agent.message/seq m))]]
                  [:div {:class "sm-copy" :style "margin-top:4px;white-space:pre-wrap"} (:seon.agent.message/body m)]])
               (let [e (:data item)
                     has-code? (not (str/blank? (str (:seon.agent.eval/code e))))]
                 [:div {:id (str "ae-" idx) :class "ws-eval-card"}
                  (when (not (str/blank? (str (:seon.agent.eval/thought e))))
                    [:p {:class "sm-copy" :style "margin:0 0 6px;white-space:pre-wrap"} (:seon.agent.eval/thought e)])
                  (when (not (str/blank? (str (:seon.agent.eval/reasoning e))))
                    [:details {:class "fold"}
                     [:summary {:class "muted tiny"} "Reasoning trace"]
                     [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} (:seon.agent.eval/reasoning e)]])
                  (when has-code?
                    [:details {:class "eval-fold" :open (>= idx (max 0 (- (count timeline) 3)))}
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
                        [:pre {:class "code-box tiny green-text"} [:code (str "=> " (:seon.agent.eval/value e))]])]])])))))]

      [:form {:class "ws-composer" :on-submit (list 'seon.web/eval-agent-form! aid branch)}
       [:textarea {:class "inp mono" :name "code" :rows 2
                   :data-on:keydown "if(evt.key==='Enter'&&!evt.shiftKey){evt.preventDefault();if(evt.target.value.trim()){evt.target.form.requestSubmit();}}"
                   :placeholder (str "Message " aid " or eval Clojure on " branch " (e.g. (user/health), [:seon.code.doc/id \"docs/architecture.md#0\"])...")}]
       [:div {:class "row-between" :style "margin-top:6px"}
        [:span {:class "muted tiny mono"} (str "Enter sends · Shift+Enter newline · COW fork on " branch " · binds result/e<n>")]
        [:button {:type "submit" :class "btn primary sm"} "Send / Eval"]]]]

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

(defn- pretty-edn [x]
  (try
    (let [s (with-out-str (fipp/pprint x {:width 88}))]
      (if (> (count s) 12000) (str (subs s 0 12000) "\n… (truncated)") s))
    (catch Throwable _ (pr-str x))))

(defn debug-view [main-db {:keys [conn walk-summary branch-feedback viewing-id selected-branch]}]
  (let [aid (or viewing-id :agent/root)
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        wire (build-live-wire-request main-db bdb aid {:viewport-screenshot nil :vision? false})
        ctx-snap (or (try (ctx/build bdb [:seon.agent/id aid]) (catch Throwable _ nil))
                     (try (ctx/build main-db [:seon.agent/id aid]) (catch Throwable _ nil)))
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
        [:div
         [:h2 (str "Live @seon.ctx/ctx Atom Value (" aid ")")]
         [:span {:class "muted tiny"} "Live incremental Datahike pull backing @seon.ctx/ctx inside this agent's REPL"]]
        [:div {:class "pill-row"}
         [:span {:class "badge amber mono"} (str (count (:entries ctx-snap)) " pull clauses")]
         [:span {:class "badge mono"} (str "tx " (or (:tx ctx-snap) (:max-tx bdb)))]]]
       (if ctx-snap
         [:div {:class "stack-sm"}
          [:details {:class "fold"}
           [:summary {:class "muted tiny mono"}
            (str "Active :seon.ctx/pattern (" (count (:pattern ctx-snap)) " items)")]
           [:pre {:class "code-box tiny ctx-pre"} [:code (pretty-edn (:pattern ctx-snap))]]]
          [:pre {:class "code-box tiny ctx-pre"} [:code (pretty-edn (:val ctx-snap))]]]
         [:p {:class "muted sm-copy"} "No agent entity found for @seon.ctx/ctx."])]

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

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:div
        [:h2 (str "Rendered Versions — Human HTML vs Model Text (" (count (:tiles wire)) " tiles)")]
        [:span {:class "muted tiny"} "Single-source Hiccup rendered live on the left (Datastar HTML) and projected via render/hiccup->text on the right (sent to LLM)"]]
       [:span {:class "badge purple mono"} "1 Hiccup → 2 targets"]]
      (if (empty? (:tiles wire))
        [:p {:class "muted sm-copy"} "No renderable tiles discovered for this agent yet."]
        [:div {:class "stack"}
         (for [[idx t] (map-indexed vector (:tiles wire))]
           [:div {:id (str "dbg-tile-" idx) :class "debug-tile-pair"}
            [:div {:class "debug-tile-col"}
             [:div {:class "row-between"}
              [:span {:class "kicker"} "Human HTML (Browser)"]
              [:div {:class "pill-row"}
               [:span {:class (str "badge " (case (:source t) :eval "green" :pinned "amber" :child "blue" "muted"))}
                (name (:source t))]
               (when (:handle t)
                 [:span {:class "badge mono handle-pill"} (str (:handle t))])
               [:span {:class "badge mono"} (str (:schema t))]]]
             (render/render-value bdb (:val t) {:agent-id aid})]
            [:div {:class "debug-tile-col"}
             [:div {:class "row-between"}
              [:span {:class "kicker"} "Model Text (render/hiccup->text)"]
              [:span {:class "mono tiny muted"} (str (count (:text t "")) " chars")]]
             [:pre {:class "code-box tiny ctx-pre"} (:text t)]]])])]

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

(defn view-unit [db {:keys [conn branch-renders] :as opts}]
  (let [{:keys [viewing-id view-mode selected-branch]} (user-view-state db)
        view-opts (assoc opts :viewing-id viewing-id :selected-branch selected-branch)
        branch (or (:seon.agent/branch (d/pull db '[:seon.agent/branch] [:seon.agent/id viewing-id])) :main)
        bdb (branch-db-for db conn branch)
        ag (pull-agent-entity db bdb viewing-id)
        custom-sym (:seon.agent/render-fn ag)
        br-render (when (= view-mode :canvas) (get branch-renders viewing-id))
        use-br-render? (and br-render
                            (= (:branch br-render) branch)
                            (or (nil? custom-sym) (= (:render-fn br-render) custom-sym)))
        custom-fn (when (and (= view-mode :canvas)
                             (not use-br-render?)
                             (qualified-symbol? custom-sym)
                             (not= custom-sym 'seon.render.ui/root-canvas))
                    (or (try (resolve custom-sym) (catch Throwable _ nil))
                        (try (requiring-resolve custom-sym) (catch Throwable _ nil))))]
    (cond
      use-br-render?
      [:div {:id "seon-view-unit" :class "root-canvas"}
       [:div {:class "branch-preview-bar"}
        [:span (str "Live branch UI · " (:render-fn br-render) " on " branch " @ tx " (:branch-tx br-render))]
        [:div {:class "pill-row"}
         [:button {:type "button" :class "btn sm success"
                   :on-click (list 'seon.web/merge-branch! branch)} "Merge → :main"]
         [:button {:type "button" :class "btn sm"
                   :on-click '(seon.web/set-view-mode! :workspace)} "Workspace"]]]
       (if (:error br-render)
         [:div {:class "card notice red"}
          [:strong (str "Branch render fault (" (:render-fn br-render) "): ")]
          [:span {:class "mono"} (:error br-render)]]
         (:hiccup br-render))]

      custom-fn
      (custom-fn db (assoc view-opts :branch-db bdb :agent ag))

      (= view-mode :canvas)    ((var-get #'root-canvas) db view-opts)
      (= view-mode :workspace) ((var-get #'agent-workspace) db view-opts)
      (= view-mode :debug)     ((var-get #'debug-view) db view-opts)
      :else                    ((var-get #'root-canvas) db view-opts))))

;; --- Incremental Walk Unit Specs (5 Units: Header, Sidebar, View, 120fps Stream, Sticky Composer) ---

(defn page-unit-specs
  "Returns the 5 mounted incremental-walk units — 100% driven by Datahike `:user/default`:
   1. `seon-header`: quiet Phosphor top bar + live agent status + mode switcher
   2. `seon-sidebar`: collapsible recursive agent-tree sidebar with live pulsing status
   3. `seon-view-unit`: active `:canvas`, `:workspace`, or `:debug` projection for `:seon.user/viewing`
   4. `seon-stream-unit`: 120fps in-place live stream dock (0 DB reads -> skips ALL 4 DB units on tokens!)
   5. `seon-composer-unit`: sticky bottom Canvas composer (never disturbed by live streaming!)."
  [st]
  [{:id "seon-header"
    :comp #'header-unit
    :args [(select-keys st [:boot-seq :agent-states])]}
   {:id "seon-sidebar"
    :comp #'sidebar-unit
    :args [(select-keys st [:agent-states])]}
   {:id "seon-view-unit"
    :comp #'view-unit
    :args [(select-keys st [:conn :branch-feedback :walk-summary :agent-states :branch-renders])]}
   {:id "seon-stream-unit"
    :comp #'stream-unit
    :args [(select-keys st [:live-streams :errors])]}
   {:id "seon-composer-unit"
    :comp #'composer-unit
    :args [{}]}])
