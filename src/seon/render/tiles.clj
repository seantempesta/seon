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
        rfn (or (:seon.agent/render-fn snap) 'seon.render.ui/agent-canvas)
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
         "Read & context (batch in 1 turn!): `(user/context 'ns | :ns/task)` (exact agent walk-context + tail), `(user/source 'a/b 'c/d)` (variadic or `'ns 1 80`), `(user/grep-src [\"p1\" \"p2\"] {:ns 'ns})`, `(user/ns-outline 'ns1 'ns2)`, `(user/callers 'a/b)`, `(user/search \"term\")`, `(user/db)` / `(user/main-db)` (prefer indexed `(d/datoms db :aevt/:avet :attr)` over 1-clause `d/q`)."]
         [:li
         "Edit & transact (batch per turn; never spit files on disk): `(user/edit-fn! 'ns/fn [[old1 new1] [old2 new2]])`, `(user/add-fn! 'ns \"(defn f ...)\")`, `(user/set-meta! 'ns/fn {:malli/schema [...] :cost/ticks ...})`, `(user/add-require! 'ns '[lib :as a])`, `(user/prune-requires! 'ns)`, `(user/move-vars! {:from 'a :to 'a.sub :alias 'sub :vars '[f1 f2] :include-deps? true})`, `(user/remove-var! 'ns/v)`, `(user/doc! \"docs/f.md#sec\" \"body\")`, `(user/register-schema! :seon.x/k form {:doc \"...\"})`, `(user/create-task! {...})`, `(user/transact! tx-data)`."]
         [:li
         "Verify & ship: `(user/run-tests! 'ns-test/var | 'ns)` (4 GB override; use `(seon.test-fixture/with-test-store [{:keys [conn]}] ...)`), `(user/health)`, `(user/diff)` (`{:health? true}`), `(user/metrics)`, `(user/autofix-health!)`, `(user/record-summary! {...})`, `(user/ready!)`."]
         [:li
          "Delegate & UI: `(user/ask! :ns/<subsys> \"...\")` + `(user/await! [c1 ...])`, `(user/answer! cid val)` / `(user/fail! cid {:class :out-of-scope :message \"...\"})`, `(user/canvas! {:title \"...\" :summary \"...\" :body [:svg ...]})`, `(user/show-user! {:viewing "
          (str aid)
          " :mode :canvas|:workspace|:debug})`."]
         [:li
          "Context & discovery: `(user/remember! \"fact\")`, `(user/tune-context! {:extra-namespaces [...]})`, `(user/compact! \"summary\")`, `(user/help)` / `(user/help 'edit-fn!)` for all helpers."]
         [:li
          "Idioms & traps: Malli predicates are symbols (`number?`, `pos-int?`, `map?`, `fn?`), NEVER `:number`; persist tests via `(user/add-fn! 'ns-test \"(deftest ...)\")` (raw `deftest` evals do not persist); attach `:malli/schema` + `:cost/ticks` to new pure helpers; batch related edits + `(user/run-tests! ...)` in one `(do ...)` eval."]]])]
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

(defn var-tile
"Schema-attached render for `:seon.code.var/entity` — configured dynamically by rule `:layout`, `:params`, `:tags`, and `:why`."
  {:malli/schema [:=> [:cat :map :map] :any]}
[{:keys [db mode layout params tags why]} v]
(let [vsym (:seon.code.var/name v)
      compact? (= layout :compact-row)
      show-src? (if (contains? params :show-source?) (:show-source? params) (not compact?))
      show-callers? (if (contains? params :show-callers?) (:show-callers? params) (not compact?))
      show-diff? (boolean (:show-diff? params))
      src (when (and show-src? db (:seon.code.var/form v))
            (:seon.code.form/src (d/pull db '[:seon.code.form/src] [:seon.code.form/key (:seon.code.var/form v)])))
      callers (when (and show-callers? db vsym)
                (->> (d/datoms db :avet :seon.code.var/refs vsym)
                     (map #(:seon.code.var/name (d/pull db '[:seon.code.var/name] (:e %))))
                     sort vec))
      doc (:seon.code.var/doc v)]
  [:div {:class (str "card tile-card" (when compact? " compact-tile"))}
   [:div {:class "card-head"}
    [:div {:class "pill-row"}
     [:span {:class (str "badge " (if (:seon.code.var/test? v) "green" "blue"))}
      (if (:seon.code.var/test? v) "test" "var")]
     [:strong {:class "mono"} (str vsym)]
     (for [tg (sort tags)]
       [:span {:class "badge amber mono"} (name tg)])]
    (when (seq (:seon.code.var/arglists v))
      [:span {:class "muted tiny mono"} (pr-str (:seon.code.var/arglists v))])]
   (when (and doc (not (str/blank? doc)))
     [:p {:class "doc-box sm-copy"}
      (if (and compact? (> (count doc) 140)) (str (subs doc 0 140) "…") doc)])
   (when-let [sc (:seon.code.var/schema v)]
     [:pre {:class "code-box tiny"} [:code (pr-str sc)]])
   (when (seq callers)
     [:div {:class "pill-row" :style "margin-top:6px"}
      [:span {:class "muted tiny"} (str "Callers (" (count callers) "):")]
      (for [c (take 8 callers)] [:span {:class "pill mono"} (str c)])])
   (when (and show-diff? (not (str/blank? (str src))))
     [:p {:class "tiny muted"} "Recently modified / active edit target — full source expanded:"])
   (when src
     (if (= mode :agent)
       [:pre {:class "code-box tiny"} [:code (if (> (count src) 2400) (str (subs src 0 2400) "\n;; … truncated") src)]]
       [:details {:class "fold" :open (boolean (or show-diff? (:show-source? params))) :style "margin-top:8px"}
        [:summary {:class "muted tiny"} "Source form"]
        [:pre {:class "code-box tiny"} [:code src]]]))
   (when (and (seq why) (not= mode :agent))
     [:p {:class "tiny muted" :style "margin-top:4px"} (str/join " · " why)])]))

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
"Schema-attached render for `:seon.schema/entity` — surfaces entity attributes, Datahike storage type, and touching vars."
  {:malli/schema [:=> [:cat :map :map] :any]}
[{:keys [db params tags why mode]} sc-ent]
(let [sk (:seon.schema/key sc-ent)
      form (:seon.schema/form sc-ent)
      dh (when db (get (:schema db) sk))
      map-fields (when (and (vector? form) (= :map (first form)))
                   (let [entries (if (map? (second form)) (drop 2 form) (rest form))]
                     (vec (keep #(when (and (vector? %) (keyword? (first %))) (first %)) entries))))
      touching-vars (when (and db (:show-touching-vars? params) (contains? (:schema db) :seon.code.var/keywords))
                      (let [probe-ks (if (seq map-fields) (take 4 map-fields) [sk])]
                        (->> probe-ks
                             (mapcat #(d/datoms db :avet :seon.code.var/keywords %))
                             (map :e)
                             distinct
                             (keep #(:seon.code.var/name (d/pull db '[:seon.code.var/name] %)))
                             sort (take 6) vec)))]
  [:div {:class "card tile-card"}
   [:div {:class "card-head"}
    [:div {:class "pill-row"}
     [:span {:class "badge blue"} "schema"]
     [:strong {:class "mono"} (str sk)]
     (for [tg (sort tags)] [:span {:class "badge amber mono"} (name tg)])]
    (when dh
      [:span {:class "badge green mono"} (str (name (:db/valueType dh)) " · " (name (:db/cardinality dh)))])]
   (when-let [doc (:seon.schema/doc sc-ent)]
     [:p {:class "muted sm-copy"} doc])
   (if (seq map-fields)
     [:div {:class "pill-row" :style "margin:4px 0"}
      [:span {:class "muted tiny"} "Attributes:"]
      (for [f map-fields] [:span {:class "pill mono"} (str f)])]
     [:pre {:class "code-box tiny"} [:code (pr-str form)]])
   (when (seq touching-vars)
     [:div {:class "pill-row" :style "margin-top:4px"}
      [:span {:class "muted tiny"} "Used by:"]
      (for [tv touching-vars] [:span {:class "pill mono"} (str tv)])])
   (when (and (seq why) (not= mode :agent))
     [:p {:class "tiny muted" :style "margin-top:4px"} (str/join " · " why)])]))

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
  {:malli/schema [:=> [:cat :map :map] :any]}
  [{:keys [mode]} t]
  [:div {:class "card tile-card"}
   [:div {:class "card-head"}
    [:div {:class "pill-row"}
     [:span {:class (str "badge " (cond (:seon.task/template? t) "purple"
                                        (:seon.task/done? t) "green"
                                        :else "blue"))}
      (cond (:seon.task/template? t) "template"
            (:seon.task/done? t) "done"
            :else "task")]
     [:strong (:seon.task/title t)]]
    [:span {:class "muted tiny mono"} (str (:seon.task/id t))]]
   (when (or (:seon.task/owner t) (:seon.task/template t))
     [:div {:class "pill-row"}
      (when-let [o (:seon.task/owner t)] [:span {:class "pill mono"} (str "owner " o)])
      (when-let [tpl (:seon.task/template t)] [:span {:class "pill mono"} (str "from " tpl)])])
   (when-let [p (:seon.task/plan t)]
   (if (= mode :agent)
     [:p {:class "sm-copy"} p]
     [:div {:class "sm-copy"} (rmd/render-markdown-blocks p)]))
   (when (seq (:seon.task/needs t))
     [:div {:class "pill-row"}
      [:span {:class "muted tiny"} "needs:"]
      (for [n (sort (:seon.task/needs t))] [:span {:class "pill mono"} (str n)])])
   (when-let [sm (:seon.task/summary t)]
     (when-not (str/blank? sm)
       [:p {:class "muted tiny"} (str "Summary: " sm)]))
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

(defn rule-tile
  "Schema-attached render for `:seon.rule/entity` — displays declarative logic rule metadata, `:when` clauses, and `:assert` templates."
  {:malli/schema [:=> [:cat :map :map] [:vector :any]]
   :cost/ticks 1000000}
  [{:keys [layout tags why]} r]
  (let [rid (:seon.rule/id r)
        en? (not (false? (:seon.rule/enabled? r)))
        cat-kw (:seon.rule/category r :present)
        tgt (:seon.rule/target r :any)]
    [:div {:class "card tile-card"}
     [:div {:class "card-head"}
      [:div {:class "pill-row"}
       [:span {:class (str "badge " (if en? "purple" "muted"))} (name cat-kw)]
       [:strong {:class "mono"} (str rid)]
       (for [tg (sort tags)]
         [:span {:class "badge amber mono"} (name tg)])]
      [:span {:class "muted tiny mono"} (str "stratum " (:seon.rule/stratum r 50) " · " (name tgt))]]
     (when-let [doc (:seon.rule/doc r)]
       [:p {:class "muted sm-copy"} doc])
     (when (not= layout :summary-strip)
       [:details {:class "fold" :style "margin-top:6px"}
        [:summary {:class "muted tiny"} (str "When (" (count (:seon.rule/when r)) ") → Assert (" (count (:seon.rule/assert r)) ")")]
        [:pre {:class "code-box tiny"} [:code (str ":when " (pr-str (:seon.rule/when r)) "\n:assert " (pr-str (:seon.rule/assert r)))]]])
     (when (seq why)
       [:p {:class "tiny muted"} (str/join " · " why)])]))

(defn- health-compact-list [probs]
  [:div {:class "stack-xs"}
   (for [[idx p] (map-indexed vector (take 6 probs))
         :let [owner (or (health/owner-specialist-id (:ns p)) (:agent p) (:branch p :main))]]
     [:div {:id (str "hp-c-" idx) :class "row-between" :style "gap:6px;padding:4px 0;border-top:1px solid var(--border-subtle)"}
      [:div {:style "min-width:0;flex:1"}
       [:div {:class "pill-row"}
        [:span {:class (str "badge " (if (= :error (:severity p)) "red" "amber"))} (name (:class p))]
        [:span {:class "mono tiny truncate"} (str (:subject p))]]
       [:div {:class "tiny muted"} (str owner " · " (let [m (str (:message p))] (if (> (count m) 100) (str (subs m 0 100) "…") m)))]]
      [:button {:type "button" :class "btn sm"
                :on-click (list 'seon.web/assign-health-fix! (:class p) (:subject p) (:message p))}
       "Fix"]])
   (when (> (count probs) 6)
     [:div {:class "tiny muted"} (str "+ " (- (count probs) 6) " more in Debug view")])])

(defn health-panel
  "Schema-attached render for `:seon.health/report` (`{:seon.render/hiccup seon.render.ui/health-panel}`)."
  {:malli/schema [:=> [:cat :map :map] :any]}
  [{:keys [mode layout]} report]
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
     (cond
     (empty? probs)
     [:p {:class "muted sm-copy"} "All structural checks green, zero failing branch tests, zero open faults or efficiency warnings."]

     (= layout :compact-summary)
     (health-compact-list probs)

     :else
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