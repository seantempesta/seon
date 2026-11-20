(ns seon.render.tiles
  "Schema-attached entity tile renderers (`ctx-tile`, `health-panel`, `var-tile`, `doc-tile`, `schema-tile`, `call-tile`, `task-tile`, `agent-tile`)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.render :as render]))

(defn ctx-tile
  "Schema-attached render for `:seon.ctx/snapshot` — shows the agent's live `@seon.ctx/ctx` state
   in `:mode :human`, and in `:mode :agent` renders a compact Core-12 REPL reference anchored
   statically in `<walk-context>` for 90%+ LLM KV-cache hit rates."
  {:malli/schema [:=> [:cat :map :map] [:vector :any]]}
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
          [:p "Durable Datahike memory:"]
          [:ul
           (for [m memory] [:li (if (string? m) m (pr-str m))])]])
       [:div {:class "doc-box sm-copy" :style "margin-top:8px"}
        [:p "Core REPL tools (Datahike `d` pre-aliased; `@ctx` is your live reactive CtxAtom — never shadow it):"]
        [:ul
         [:li "Read & search: `(user/source 'ns/fn | ['a/b ...] | 'ns#ns | :schema/k | \"docs/f.md#sec\" {:lines [1 80]})` (returns String), `(user/grep-src \"pat\" {:ns 'ns})`, `(user/ns-outline 'ns)`, `(user/ns-requires 'ns)`, `(user/search \"term\")`, `(user/callers 'ns/fn)`, `(user/db)` / `(user/main-db)`."]
         [:li "Edit & refactor (never spit files on disk): `(user/edit-fn! 'ns/fn old new)`, `(user/set-meta! 'ns/fn {:malli/schema [...] :cost/ticks ...})` (or map batch), `(user/add-require! 'ns '[lib :as a])`, `(user/prune-requires! 'ns)`, `(user/move-vars! {:from 'a :to 'a.sub :alias 'sub :vars '[f1 f2] :include-deps? true})`, `(user/remove-var! 'ns/v)`, `(user/register-schema! :seon.x/k form {:doc \"...\"})`."]
         [:li "Verify & ship: `(user/run-tests! 'ns-test/var | 'ns)` (runs full reach), `(user/diff)`, `(user/autofix-health!)`, `(user/ready!)` (tests, 3-way merges, hot-reloads & writes back to Git)."]
         [:li "Delegate & UI: `(user/ask! :ns/<subsys> \"...\")` + `(user/await! [c1 ...])`, `(user/answer! cid val)` / `(user/fail! cid {:class :out-of-scope :message \"...\"})`, `(user/canvas! {:title \"...\" :summary \"...\" :body [:svg ...]})`, `(user/show-user! {:viewing " (str aid) " :mode :canvas|:workspace|:debug})`."]
         [:li "Context & discovery: `(user/remember! \"fact\")`, `(user/tune-context! {:extra-namespaces [...]})`, `(user/compact! \"summary\")`, `(user/help)` / `(user/help 'edit-fn!)` for all helpers."]]]]
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