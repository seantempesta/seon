(ns seon.render.tiles
  "Schema-attached entity tile renderers (`ctx-tile`, `health-panel`, `var-tile`, `doc-tile`, `schema-tile`, `call-tile`, `task-tile`, `agent-tile`)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.render :as render]))

(defn ctx-tile
  "Schema-attached render for `:seon.ctx/snapshot` — shows the agent's live `@seon.ctx/ctx` state
   in `:mode :human`, and in `:mode :agent` keeps the `<walk-context>` prefix 100% static/byte-identical
   across turns (for 90%+ LLM KV-cache hit rates)."
  [{:keys [mode]} snap]
  (let [aid (:seon.agent/id snap)
        branch (:seon.agent/branch snap :main)
        rfn (:seon.agent/render-fn snap 'seon.render.ui/agent-canvas)
        canvas (:seon.agent/canvas snap)
        pinned (vec (or (:seon.agent/pinned snap) []))
        memory (vec (or (:seon.agent/memory snap) []))
        prefs (:seon.agent/context-prefs snap)]
    (if (= :agent mode)
      [:div {:class "card tile-card"}
       [:div {:class "card-head"}
        [:div {:class "pill-row"}
         [:span {:class "badge amber"} "@ctx"]
         [:strong {:class "mono"} (str aid)]]
        [:span {:class "muted tiny mono"} (str branch)]]
       [:dl {:class "kv-grid"}
        [:dt ":seon.agent/role"] [:dd (str (:seon.agent/role snap :worker))]
        (when (seq prefs)
          (list [:dt ":seon.agent/context-prefs"] [:dd (pr-str prefs)]))]
       (when (seq memory)
         [:div {:class "doc-box sm-copy" :style "margin-top:8px"}
          [:p "Durable Datahike memory (persisted across compactions):"]
          [:ul
           (for [m memory] [:li (if (string? m) m (pr-str m))])]])
       [:div {:class "doc-box sm-copy" :style "margin-top:8px"}
        [:p "Live REPL affordances colocated on @seon.ctx/ctx (also bound as user/ctx):"]
        [:ul
         [:li "@ctx is a reactive Datahike CtxAtom — read with @ctx, update with (swap! ctx assoc ...). Never shadow it with (def ctx ...)."]
         [:li "Replace the user's live Canvas cleanly in 1 call (every update automatically becomes a Back/Forward step in Datahike history!): (user/canvas! {:title \"Headline\" :summary \"Concise takeaway\" :body [:svg {:viewBox \"0 0 640 280\"} ...]}) (Phosphor CSS vars: var(--signal), var(--text-100), var(--base-800), var(--success), var(--info))."]
         [:li "Or replace the entire Canvas render function live on your branch: define (ns my.ui) (defn canvas [ctx] [:div ...]) and call (user/set-render! 'my.ui/canvas)."]
         [:li "Query & navigate Datahike directly: (user/help) / (user/help 'edit-fn!), (d/datoms (user/db) :aevt :attr), (d/pull (user/db) '[*] ref), (user/source 'ns/fn | ['a/b 'c/d] | 'ns#ns | :seon.schema/key | \"docs/file.md#sec\"), (user/ns-requires 'ns), (user/search \"term\"), (user/grep-src \"pat\" {:ns 'my.ns}), (user/callers 'ns/fn), (user/callees 'ns/fn), (user/tests-for 'ns), (user/ns-outline 'ns), (user/unused-requires 'ns)."]
         [:li "When a <live-viewport> screenshot is attached at the end of your context, inspect the actual rendered pixels & DOM telemetry and fix any clipping, overlap, contrast, or stray SVG artifacts before finishing."]
         [:li "Delegate work or questions via Flow-native calls (zero timers/waits): (user/ask! :ns/seon.render \"question\") returns {:call-id ...} asynchronously (lazy-spawning :ns/<ns> if needed), (user/await! [c1 c2]) suspends cleanly until all settle, (user/call! :ns/seon.render \"...\") is ask!+await!. Settle incoming calls with (user/answer! call-id val) or (user/fail! call-id {:class :out-of-scope :message \"...\"})."]
         [:li "Refine context via Datahike & trim stale history cleanly: (user/remember! \"durable fact\"), (user/tune-context! {:extra-namespaces ['seon.web] :extra-schemas [:seon.user/viewing] :recent-data-limit 5}), (user/compact! \"concise summary\") (retracts old evals from current DB; full history remains queryable via Datahike time-travel: (user/past-evals {:grep \"...\"}) / (user/as-of tx) / (user/history 'ns/fn))."]
         [:li "Split oversized namespaces losslessly in 1 call (zero manual re-typing!): inspect form sizes & local deps with (user/ns-outline 'my.ns), then move cohesive forms with (user/move-vars! {:from 'my.ns :to 'my.ns.child :alias 'child :doc \"...\" :vars '[fn1 fn2 ...]}). It checks unresolved local deps upfront, promotes moved defn- helpers, adds :as/:refer to :from, re-exports public vars so callers stay green, and hot-reloads both namespaces."]
         [:li "Improve code, schemas, docs & tests directly in Datahike (never spit files on disk, never :reload-all): patch any existing function structurally (ignoring whitespace/tabs/comments) via (user/edit-fn! 'ns/fn old-subform new-subform), prune unused requires via (user/prune-requires! 'ns), create a NEW child namespace with (ns target.child (:require ...)) + its (defn ...)s in ONE eval, remove moved vars safely via (user/remove-var! 'ns/var), inspect unified diffs & health via (user/diff), update docs via (user/doc! \"docs/file.md#section\" \"body\"), register schemas via (user/register-schema! :seon.area/key form {:doc \"...\"}), run branch tests live via (user/run-tests! 'ns-test/var | 'ns), settle calls via (user/answer! ...), and call (user/ready!) to auto-test, 3-way merge, hot-reload, and write back to disk & Git. Move the user's view with (user/show-user! {:viewing " (str aid) " :mode :canvas|:workspace|:debug})."]]]]
      [:div {:class "card tile-card"}
       [:div {:class "card-head"}
        [:div {:class "pill-row"}
         [:span {:class "badge amber"} "@ctx"]
         [:strong {:class "mono"} (str aid)]]
        [:span {:class "muted tiny mono"} (str branch " · tx " (:seon.ctx/tx snap 0))]]
       [:dl {:class "kv-grid"}
        [:dt ":seon.agent/role"] [:dd (str (:seon.agent/role snap :worker))]
        [:dt ":seon.agent/render-fn"] [:dd (str rfn)]
        [:dt ":seon.agent/canvas"] [:dd (if (seq canvas)
                                          (pr-str (select-keys canvas [:title :summary]))
                                          "{} — call (user/canvas! {:title \"...\" :summary \"...\" :body [:svg ...]})")]
        [:dt ":seon.agent/pinned"] [:dd (pr-str pinned)]
        (when (seq memory)
          (list [:dt ":seon.agent/memory"] [:dd (str (count memory) " durable note(s)")]))]])))

(defn health-panel
  "Schema-attached render for `:seon.health/report` (`{:seon.render/hiccup seon.render.ui/health-panel}`)."
  [{:keys [mode]} report]
  (let [ok? (:ok? report)
        probs (:problems report)
        counts (:counts report)
        err-n (count (filter #(= :error (:severity %)) probs))
        warn-n (- (count probs) err-n)
        err-classes (into #{} (comp (filter #(= :error (:severity %))) (map :class)) probs)]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:h2 "System Health"]
       [:span {:class (str "badge " (if ok? "green" "red"))}
        (if ok? "HEALTHY" (str err-n " ERROR(S)"))]
       (when (pos? warn-n)
         [:span {:class "badge amber"} (str warn-n " ADVISORY")])]
      [:button {:type "button" :class "btn primary sm"
                :on-click '(seon.web/run-tests! :main)}
       "▶ Run Reach Tests"]]
     (when (seq counts)
       [:div {:class "pill-row" :style "margin-bottom:8px"}
        (for [[cls n] (sort-by (comp str key) counts)]
          [:span {:class (str "badge " (if (contains? err-classes cls) "red" "amber"))}
           (str (name cls) ": " n)])])
     (if (empty? probs)
       [:p {:class "muted sm-copy"} "All structural checks green, zero failing branch tests, zero open faults or efficiency warnings."]
       [:div {:class "table-scroll"}
        [:table {:class "data-table"}
         [:thead [:tr [:th {:style "white-space:nowrap"} "Class"]
                      [:th {:style "white-space:nowrap"} "Owner"]
                      [:th {:style "white-space:nowrap"} "Subject"]
                      [:th "Detail"]
                      [:th ""]]]
         [:tbody
          (for [[idx p] (map-indexed vector probs)
                :let [owner (or (health/owner-specialist-id (:ns p)) (:agent p) (:branch p :main))]]
            [:tr {:id (str "hp-" idx)}
             [:td {:style "white-space:nowrap"} [:span {:class (str "badge " (if (= :error (:severity p)) "red" "amber"))} (name (:class p))]]
             [:td {:class "mono tiny" :style "white-space:nowrap"} (str owner)]
             [:td {:class "mono tiny"} (:subject p)]
             [:td {:class "tiny"} (:message p)]
             [:td [:button {:type "button" :class "btn sm"
                            :on-click (list 'seon.web/assign-health-fix! (:class p) (:subject p) (:message p))}
                   "Fix"]]])]]])
     (when (= :agent mode)
       [:p {:class "tiny muted"} "REPL: (user/health) recomputes live health; delegate advisories via (user/ask! :ns/<owner> \"...\")."])]))

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

(defn call-tile
  "Schema-attached render for `:seon.agent.call/entity`."
  [_opts c]
  (let [st (:seon.agent.call/status c :pending)
        badge-cls (case st :resolved "green" :failed "red" :cancelled "muted" "amber")]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class (str "badge " badge-cls)} (name st)]
       [:strong {:class "mono"} (str (:seon.agent.call/caller c) " → " (:seon.agent.call/callee c))]]
      [:span {:class "muted tiny mono"} (str (:seon.agent.call/id c))]]
     (when-let [p (:seon.agent.call/prompt c)]
       [:p {:class "sm-copy"} p])
     (when (contains? c :seon.agent.call/result)
       [:pre {:class "code-box tiny green-text"} [:code (pr-str (:seon.agent.call/result c))]])
     (when (contains? c :seon.agent.call/error)
       [:div {:class "notice red mono tiny"} (pr-str (:seon.agent.call/error c))])]))

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