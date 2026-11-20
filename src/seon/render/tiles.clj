(ns seon.render.tiles
  "Schema-attached entity tile renderers (`ctx-tile`, `health-panel`, `var-tile`, `doc-tile`, `schema-tile`, `call-tile`, `task-tile`, `agent-tile`)."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.health :as health]
            [seon.render :as render]
            [seon.render.markdown :as rmd]))

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
       (if (= :container (:mode prefs))
       [:div
        {:class "doc-box sm-copy", :style "margin-top:8px"}
        [:p
         "Container Workspace REPL tools (you are operating on an isolated Linux container; use Clojure in your REPL to batch/parse command output!):"]
        [:ul
        [:li
         "Shell: `(user/sh! \"cmd\")` or `(user/sh! \"cmd\" {:cwd \"/app\" :timeout-sec 60})` -> `{:exit :out :err}` (extract `(:out res)`). Chain quick inspection commands with `&&` or `;` to minimize turns; for complex parsing/binary/math work, write a script with `(user/fs-write \"/tmp/solve.py\" ...)` and run it instead of escaping complex bash/regex one-liners in Clojure strings."]
        [:li
         "Files: `(user/fs-read \"/app/file\")` (or `{:lines [1 120]}`) -> `{:ok? :lines :content}` (extract `(:content res)` — it returns a map, NOT a raw string!), `(user/fs-write \"/app/file\" \"content\")` (quote-safe, auto-creates dirs), `(user/fs-patch \"/app/file\" \"old\" \"new\")` (exact surgical replace; `{:all? true}` for all matches)."]
        [:li
        "Verify & finish: Solve strictly from local container files, installed tools, and your own engineering — never search the web or fetch external benchmark repositories/solutions. Match the exact required output path and format (raw answer vs prose), test thoroughly via `(user/sh! ...)`, then stop concisely (or `(user/wait-for-mail!)`). Do NOT call `(user/ready!)`."]]]
       [:div
        {:class "doc-box sm-copy", :style "margin-top:8px"}
        [:p
         "Core REPL tools (Datahike `d` pre-aliased; `@ctx` is your live reactive CtxAtom — never shadow it):"]
        [:ul
         [:li
          "Read & search (batch multiple items in 1 turn!): `(user/source 'a/b 'c/d)` (variadic, or `{:lines [1 80]}`), `(user/grep-src [\"p1\" \"p2\"] {:ns 'ns})` (cached single-pass; returns `:sym` & `:key`), `(user/ns-outline 'ns1 'ns2)`, `(user/callers 'a/b 'c/d)`, `(user/search \"term\")`, `(user/db)` (prefer indexed `(d/datoms db :aevt/:avet :attr)` over 1-clause `d/q`)."]
         [:li
          "Edit & refactor (batch per turn; never spit files on disk): `(user/edit-fn! 'ns/fn [[old1 new1] [old2 new2]])` (or 3-arg `old new`), `(user/add-fn! 'ns \"(defn f ...)\")`, `(user/set-meta! 'ns/fn {:malli/schema [...] :cost/ticks ...})` (or map batch), `(user/add-require! 'ns '[lib :as a])`, `(user/prune-requires! 'ns)`, `(user/move-vars! {:from 'a :to 'a.sub :alias 'sub :vars '[f1 f2] :include-deps? true})`, `(user/remove-var! 'ns/v)`, `(user/register-schema! :seon.x/k form {:doc \"...\"})`."]
         [:li
          "Verify & ship: `(user/run-tests! 'ns-test/var | 'ns)` (full reach; for new tests always use `(seon.test-fixture/with-test-store [{:keys [conn]}] ...)` — never hand-roll Datahike schemas), `(user/diff)` (fast; `{:health? true}` for branch health), `(user/metrics)`, `(user/autofix-health!)`, `(user/ready!)`."]
         [:li
          "Delegate & UI: `(user/ask! :ns/<subsys> \"...\")` + `(user/await! [c1 ...])`, `(user/answer! cid val)` / `(user/fail! cid {:class :out-of-scope :message \"...\"})`, `(user/canvas! {:title \"...\" :summary \"...\" :body [:svg ...]})`, `(user/show-user! {:viewing "
          (str aid)
          " :mode :canvas|:workspace|:debug})`."]
         [:li
          "Context & discovery: `(user/remember! \"fact\")`, `(user/tune-context! {:extra-namespaces [...]})`, `(user/compact! \"summary\")`, `(user/help)` / `(user/help 'edit-fn!)` for all helpers."]]])]
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

(defn summary-tile
  "Schema-attached render for `:seon.summary/entity` — renders a time- and tx-anchored Chronicle
   summary card with 1-click Datahike `d/as-of` time-travel and clickable agent/namespace refs."
  {:malli/schema [:=> [:cat :map :map] [:vector :any]]
   :cost/ticks 2000000
   :cost/bytes 4194304}
  [{:keys [mode]} s]
  (let [kind (let [k (:seon.summary/kind s)] (if (keyword? k) k :system-pulse))
        badge-cls (case kind
                    (:milestone :architecture) "amber"
                    :compaction "purple"
                    :system-pulse "blue"
                    "muted")
        title (let [t (:seon.summary/title s)]
                (if (and (string? t) (not (str/blank? t))) t "System Summary"))
        body (let [b (:seon.summary/body s)] (when (string? b) b))
        from-tx (when (integer? (:seon.summary/from-tx s)) (long (:seon.summary/from-tx s)))
        to-tx (when (integer? (:seon.summary/to-tx s)) (long (:seon.summary/to-tx s)))
        author (when (keyword? (:seon.summary/agent s)) (:seon.summary/agent s))
        refs (when (sequential? (:seon.summary/refs s)) (vec (take 10 (:seon.summary/refs s))))
        visual (:seon.summary/visual s)
        tx-label (cond
                   (and from-tx to-tx (not= from-tx to-tx)) (str "tx " from-tx ".." to-tx)
                   to-tx (str "tx " to-tx)
                   :else nil)]
    [:article {:class "card tile-card stack-sm"}
     [:header {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class (str "badge " badge-cls)} (name kind)]
       [:strong title]]
      [:div {:class "pill-row"}
       (when author
         [:button {:type "button" :class "link-muted tiny mono"
                   :on-click (list 'seon.web/view-agent! author :canvas)}
          (str author)])
       (when (and to-tx tx-label)
         [:button {:type "button" :class "btn sm mono"
                   :aria-label (str "Time-travel to " tx-label)
                   :on-click (list 'seon.web/jump-canvas-tx! to-tx)}
          tx-label])]]
     (when (and body (not (str/blank? body)))
       [:div {:class "sm-copy"} (rmd/render-markdown-blocks body)])
     (when (and (vector? visual) (keyword? (first visual)))
       [:div {:class "canvas-surface"} visual])
     (when (seq refs)
       [:footer {:class "pill-row"}
        (for [rf refs]
          (cond
            (and (keyword? rf) (not= "branch" (namespace rf)))
            [:button {:type "button" :class "btn sm mono"
                      :on-click (list 'seon.web/view-agent! rf :canvas)}
             (str rf)]
            (and (vector? rf) (= 2 (count rf)) (= :seon.agent/id (first rf)) (keyword? (second rf)))
            [:button {:type "button" :class "btn sm mono"
                      :on-click (list 'seon.web/view-agent! (second rf) :canvas)}
             (str (second rf))]
            :else
            [:span {:class "pill mono"} (str rf)]))])
     (when (and (= :agent mode) to-tx)
       [:p {:class "tiny muted mono"}
        (str "Drill in: (d/as-of (user/main-db) " to-tx ")")])]))

(defn bench-run-tile
  "Renders a live or completed `:seon.bench.run/entity` card with aggregate proxy telemetry,
   progress bar, per-trial status/evals/elapsed/error rows, and direct instance links."
  {:malli/schema [:=> [:cat :map] [:vector :any]], :cost/ticks 2000000, :cost/bytes 2097152}
  [run]
  (let [r (if (map? run) run {})
        rid (:seon.bench.run/id r)
        title (or (not-empty (str (:seon.bench.run/title r ""))) "Benchmark Trial Run")
        status (or (:seon.bench.run/status r) :running)
        running? (= :running status)
        total (long (or (:seon.bench.run/total r) (count (:seon.bench.run/trials r)) 0))
        passed (long (or (:seon.bench.run/passed r) 0))
        failed (long (or (:seon.bench.run/failed r) 0))
        done-n (+ passed failed)
        pct (if (pos? total) (min 100 (Math/round (* 100.0 (/ (double done-n) (double total))))) 0)
        st-ms (long (or (:seon.bench.run/started-ms r) 0))
        end-ms (long (or (:seon.bench.run/finished-ms r) (:seon.bench.run/updated-ms r) st-ms))
        elapsed-s (if (and (pos? st-ms) (>= end-ms st-ms))
                    (/ (Math/round (* 10.0 (/ (double (- end-ms st-ms)) 1000.0))) 10.0)
                    0.0)
        ps (when (map? (:seon.bench.run/proxy-stats r)) (:seon.bench.run/proxy-stats r))
        reqs (long (or (:requests ps) 0))
        in-fl (long (or (:in-flight ps) 0))
        ptoks (long (or (:prompt-tokens ps) 0))
        ctoks (long (or (:completion-tokens ps) 0))
        hits (long (or (:cache-hit-tokens ps) 0))
        kv-pct (when (pos? ptoks) (Math/round (* 100.0 (/ (double hits) (double ptoks)))))
        fmt-k (fn [n] (if (>= n 1000) (str (/ (Math/round (/ (double n) 100.0)) 10.0) "k") (str n)))
        trials (if (sequential? (:seon.bench.run/trials r)) (vec (filter map? (:seon.bench.run/trials r))) [])
        tone (case status :passed "green" :failed "red" :partial "amber" "amber")
        pill-cls (fn [st]
                   (case st
                     (:merged :passed) "badge green mono"
                     (:timeout :failed :error) "badge red mono"
                     (:testing :merging) "badge purple mono"
                     "badge amber mono"))]
    [:article {:id (str "bench-run-" (or rid "live"))
               :class (str "ch-hero bench-run-card" (when running? " is-running"))}
     [:header
     {:class "ch-hero-head",
      :style
      "display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap"}
     [:div
      {:class "ch-hero-kicker",
       :style
       "display:inline-flex;align-items:center;gap:8px;flex-wrap:wrap"}
      [:span
       {:class
        (str
          "dot "
          (if running? "pulsing" (if (= :passed status) "live" ""))),
        :aria-hidden "true"}]
      [:span (str "BENCHMARK · " (str/upper-case (name status)))]
      [:span
       {:class "ch-tx-pill"}
       (str passed "/" total " passed · " elapsed-s "s")]]
     (when ps
       [:span
        {:class "ch-mono-xs"}
        (str
          reqs
          " req"
          (when (pos? in-fl) (str " (" in-fl " live)"))
          " · "
          (fmt-k (+ ptoks ctoks))
          " tok"
          (when kv-pct (str " · " kv-pct "% KV")))])]
     [:h2 {:class "ch-hero-title" :style "font-size:17px"} title]
     [:div {:style "height:4px;border-radius:999px;background:var(--base-800);overflow:hidden"}
      [:div {:style (str "height:100%;width:" (if (and running? (zero? pct)) 8 pct) "%;background:var(--" (if (= :passed status) "ok,#34d399" (if (= :failed status) "err,#f87171" "signal")) ");transition:width .3s ease")}]]
     [:div {:class "stack-sm" :style "margin-top:4px"}
      (for [t trials
            :let [tid (:id t)
                  tst (or (:status t) :booting)
                  ev-n (long (or (:evals t) 0))
                  ems (long (or (:elapsed-ms t) 0))
                  port (:port t)
                  alive? (boolean (:alive? t))
                  err (:last-error t)
                  snip (:last-snippet t)]]
        [:div {:class "ch-kpi" :style "gap:4px;padding:9px 12px"}
         [:div {:class "row-between" :style "gap:8px;flex-wrap:wrap"}
          [:div {:class "row-sm" :style "gap:8px"}
           [:span {:class (pill-cls tst)} (name tst)]
           [:span {:class "mono strong" :style "font-size:12.5px"} (if (keyword? tid) (name tid) (str tid))]]
          [:div {:class "row-sm" :style "gap:10px"}
           [:span {:class "ch-mono-xs"} (str ev-n " evals · " (/ (Math/round (/ (double ems) 100.0)) 10.0) "s")]
           (when (and alive? port)
             [:a {:href (str "http://127.0.0.1:" port "/") :target "_blank" :rel "noopener"
                  :class "ch-tx-btn" :style "text-decoration:none"}
              (str "Open :" port " ↗")])]]
         (when (not (str/blank? (str (or err snip))))
           [:div {:class (str "mono tiny" (if err "" " muted"))
                  :style (str "overflow:hidden;text-overflow:ellipsis;white-space:nowrap"
                              (when err ";color:var(--err,#f87171)"))}
            (str (or err snip))])])]]))