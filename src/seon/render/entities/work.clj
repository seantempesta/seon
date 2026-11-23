(ns seon.render.entities.work
  "Cohesive pure `ns-map -> ns-map` uniform Hiccup renderers ported from legacy tiles/ns-tile/lineage:
   - `render-ns-full` (`:seon.code.ns/entity`): Turn-0 `Requires:` aliases, Public API + `:malli/schema`,
     `Private helpers`, `Covering tests` + `Test ns requires:`, `Linked Docs`, `Working Schemas`,
     `Health & Efficiency Alerts`, `Context hygiene`, and Router Subsystem Table.
   - `render-ctx-chip` & `render-ctx-snapshot` (`:seon.ctx/snapshot`): Core-12 REPL & Harbor `:container` idioms.
   - `render-summary-chip` & `render-summary-card` (`:seon.summary/entity`): Chronicle tx-anchored cards.
   - `render-bench-run-card` (`:seon.bench.run/entity`): live parallel benchmark monitor cards.
   - `render-health-report` (`:seon.health/report`): System Health snapshot cards.
   - `render-task-lineage` (`:seon.task/lineage`): Task DAG chain, history, fixes & templates."
  (:require [clojure.string :as str]
            [seon.render.data :refer [first-doc-line]]
            [seon.render.links :as rlinks :refer [badge btn ent-link kicker kv-dl mut pill-list]]
            [seon.render.markdown :as rmd]))

(defn- clip [x n] (let [s (if (nil? x) "" (str x))] (if (> (count s) n) (str (subs s 0 (max 1 (dec n))) "…") s)))
(defn- one-line [x n] (clip (-> (if (nil? x) "" (str x)) (str/replace #"\s+" " ") str/trim) n))
(defn- nonblank [x] (when (and (string? x) (not (str/blank? x))) x))
(defn- seq-items [x] (when (and (coll? x) (not (map? x))) (seq x)))
(defn- safe-long [x d] (if (number? x) (let [v (double x)] (if (and (== v v) (not (Double/isInfinite v))) (long v) d)) d))

(defn- ns-children-block [children router?]
  (when (seq children)
    (if router?
      (let [prod (remove #(str/ends-with? (name (:seon.code.ns/name %)) "-test") children)
            tests (filter #(str/ends-with? (name (:seon.code.ns/name %)) "-test") children)]
        [:div {:class "stack-sm"}
         [:div {:class "table-scroll"}
          [:table {:class "data-table"}
           [:thead [:tr [:th "Subsystem"] [:th "Vars"] [:th "Schemas"] [:th "Specialist"] [:th "Role"]]]
           [:tbody
            (for [c prod :let [cn (:seon.code.ns/name c) vc (:var-count c 0)
                               sp (:schema-prefixes c) spec (:specialist c)]]
              [:tr
               [:td (ent-link [:seon.code.ns/name cn] (str cn) "mono tiny")]
               [:td {:class "mono tiny"} (str vc)]
               [:td {:class "mono tiny muted"} (if (seq sp) (str/join ", " sp) "—")]
               [:td (if spec (ent-link [:seon.agent/id spec] (str spec) "mono tiny")
                        [:span {:class "mono tiny muted"} (str ":ns/" cn " (lazy)")])]
               [:td {:class "tiny"} (or (first-doc-line (:seon.code.ns/doc c) 95) "—")]])]]]
         (when (seq tests)
           [:p {:class "muted tiny"}
            (str "Test namespaces: "
                 (str/join ", " (map #(str (:seon.code.ns/name %) " (" (:var-count % 0) ")") tests)))])])
      [:div {:class "stack-xs"}
       (kicker (str "Sub-namespaces (" (count children) ")"))
       [:ul {:class "compact-list"}
        (for [c children :let [cn (:seon.code.ns/name c)]]
          [:li {:class "tiny"} (ent-link [:seon.code.ns/name cn] (str cn) "mono strong")
           (str " (" (:var-count c 0) " vars): " (or (first-doc-line (:seon.code.ns/doc c) 110) "—"))])]])))

(defn- ns-pub-vars-block [pub-vars max-vars]
  (when (seq pub-vars)
    [:div {:class "stack-xs"}
     (kicker (str "Public API (" (min max-vars (count pub-vars)) "/" (count pub-vars) ")"))
     [:ul {:class "compact-list"}
      (for [v (take max-vars pub-vars)
            :let [vn (:seon.code.var/name v)
                  sc (:seon.code.var/schema v)
                  ex-list (when (and (vector? sc) (map? (second sc))) (:examples (second sc)))
                  clean-sc (if (and (vector? sc) (map? (second sc)) (contains? (second sc) :examples))
                             (let [p (dissoc (second sc) :examples)]
                               (if (empty? p) (into [(first sc)] (drop 2 sc)) (assoc sc 1 p)))
                             sc)
                  sc-str (when clean-sc (clip (pr-str clean-sc) 76))
                  ex-str (when-let [[ex-args ex-out] (first ex-list)]
                           (clip (str "(" (name vn) (when (seq ex-args) (str " " (str/join " " (map pr-str ex-args))))
                                      ") => " (pr-str ex-out)) 88))]]
        [:li {:class "tiny"}
         (ent-link [:seon.code.var/name vn]
                   (str (name vn) (when (seq (:seon.code.var/arglists v)) (str " " (pr-str (:seon.code.var/arglists v)))))
                   "mono")
         (when sc-str (str " " sc-str))
         (when-let [d (first-doc-line (:seon.code.var/doc v) 95)] (str " — " d))
         (when ex-str (str " · ex: `" ex-str "`"))])]]))

(defn render-ns-full
  "Full Turn-0 namespace card (~560px) for `:seon.code.ns/entity` honoring enriched `:seon.ui/ns-*`
   params (`Requires:` aliases, Public API, Private helpers, Covering tests, Linked Docs, Working Schemas,
   Static Health Alerts, Hygiene, Router Table) and compact `:seon.ui/summary-strip?` demotion."
  {:malli/schema [:=> [:cat [:map [:seon.code.ns/entity :seon.code.ns/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 2000000}
  [{{:seon.code.ns/keys [name path doc requires]} :seon.code.ns/entity :as m}]
  (let [kids (seq-items (:seon.ui/ns-children m))
        var-n (long (or (:seon.ui/ns-var-count m) (count (or (:seon.ui/ns-pub-vars m) []))))]
    (if (:seon.ui/summary-strip? m)
      {:seon.ui/hiccup
       [:div {:class "card ent-card ent-ns compact-tile" :data-min-width 360}
        [:div {:class "card-head ent-head"}
         [:div {:class "pill-row"} (badge "muted" "ns summary") (ent-link [:seon.code.ns/name name] (str name) "mono strong")]
         (mut (str var-n " vars · " (or path "")))]
        (when-let [d (first-doc-line doc 140)] [:p {:class "muted sm-copy"} d])
        (when kids (pill-list "Sub-namespaces:" (mapv :seon.code.ns/name kids) :seon.code.ns/name 10))]}
      (let [router? (boolean (:seon.ui/router-view? m))
            pub (seq-items (:seon.ui/ns-pub-vars m))
            priv (seq-items (:seon.ui/ns-priv-vars m))
            tests (seq-items (:seon.ui/ns-covering-tests m))
            own-t? (boolean (:seon.ui/ns-own-tests? m))
            treqs (seq-items (:seon.ui/ns-test-reqs m))
            docs (seq-items (:seon.ui/ns-linked-docs m))
            req-al (seq-items (:seon.ui/ns-req-aliases m))
            reqs (seq-items requires)
            callers (seq-items (:seon.ui/ns-caller-nss m))
            wschs (seq-items (:seon.ui/ns-working-schemas m))
            alerts (seq-items (:seon.ui/ns-static-alerts m))
            hyg (:seon.ui/ns-hygiene m)
            max-v (max 1 (safe-long (:max-vars m) 24))]
        {:seon.ui/hiccup
         [:div {:class "card ent-full ent-ns" :data-min-width 560}
          [:div {:class "card-head ent-head"}
           [:div {:class "pill-row"}
            (badge "blue" (if router? "subtree" "ns"))
            (ent-link [:seon.code.ns/name name] (str name) "mono strong")]
           (mut (if router? (str (count kids) " subsystems")
                    (str (count pub) " public · " var-n " total · " (or path ""))))]
          (when-let [d (first-doc-line doc 180)] [:p {:class "doc-box sm-copy"} d])
          (ns-children-block kids router?)
          (when (not router?) (ns-pub-vars-block pub max-v))
          (when (and (not router?) (or tests pub))
            [:div {:class "stack-xs"}
             (if tests
               (pill-list (if own-t? (str "Tests (" (count tests) "):") (str "Covering tests (" (count tests) "):"))
                          tests :seon.code.var/name 10)
               [:div {:class "pill-row"} (mut "Covering tests (0): none — add a reaching deftest!")])
             (when treqs [:p {:class "tiny muted mono"} (str "Test ns requires: " (str/join " | " treqs))])])
          (when (and (not router?) docs)
            [:div {:class "stack-xs"}
             (kicker (str "Linked Docs (" (count docs) ")"))
             [:ul {:class "compact-list"}
              (for [{:keys [id heading]} docs]
                [:li {:class "tiny"} (ent-link [:seon.code.doc/id id] id "mono")
                 (when (nonblank heading) (str " — " heading))])]])
          (when (and (not router?) priv)
            [:p {:class "tiny muted mono"}
             (str "Private helpers (" (count priv) "): " (str/join ", " (take 20 priv)))])
          (when (and (not router?) (or req-al reqs callers))
            [:div {:class "pill-row"}
             (when (or req-al reqs)
               [:span {:class "muted tiny mono"}
                (if req-al (str "Requires: " (str/join " " (take 14 req-al)))
                    (str "Calls: " (str/join ", " (map str (take 10 reqs)))))])
             (when callers [:span {:class "muted tiny"} (str " · Callers: " (str/join ", " (map str callers)))])])
          (when (and (not router?) wschs)
            [:div {:class "stack-xs"}
             (kicker (str "Working Schemas (" (count wschs) ")"))
             [:ul {:class "compact-list"}
              (for [{:keys [key form doc dh-type dh-card]} wschs]
                [:li {:class "tiny"} (ent-link [:seon.schema/key key] (str key) "mono")
                 (when dh-type (str " [" (clojure.core/name dh-type) (when (= :db.cardinality/many dh-card) " many") "]"))
                 (str " " (clip (pr-str form) 80))
                 (when-let [dl (first-doc-line doc 72)] (str " — " dl))])]])
          (when alerts
            [:div {:class "stack-xs"}
             (kicker (str "Health & Efficiency Alerts (" (count alerts) ")"))
             [:ul {:class "compact-list"}
              (for [p (take 8 alerts)]
                [:li {:class "tiny"} (badge (if (= :error (:severity p)) "red" "amber") (clojure.core/name (:class p)))
                 " " [:code {:class "mono"} (:subject p)] (str " — " (:message p))])]])
          (when (and (not router?) (map? hyg) (pos? (long (:pub-n hyg 0))))
            [:p {:class "tiny muted"}
             (str "Context hygiene: " (:pub-n hyg) " public vars (" (:doc-n hyg 0) " documented, "
                  (:schemed-n hyg 0) " :malli/schema) · " (:tests-n hyg 0) " covering tests"
                  (when (pos? (long (:est-tokens hyg 0)))
                    (str " · ~" (:est-tokens hyg) " tok"
                         (when (> (long (:est-tokens hyg)) 6000) " (oversized: split into child sub-namespaces!)"))))])
          [:p {:class "tiny muted"}
           (if router?
             "Route subsystem questions/work via (user/ask! :ns/<subsystem> \"...\") + (user/await! [...]). Specialist agents lazy-spawn on first call."
             (str "REPL: (user/source '" name "/fn | \"docs/...\" | :seon.schema/k), (user/callers '" name "/fn), (user/tests-for '" name "), (user/explore! ref)."))]]}))))

;; @ctx Snapshot (:seon.ctx/snapshot) — Core-12 REPL & Harbor Container idioms

(defn render-ctx-chip
  "Compact chip (~180px) for `:seon.ctx/snapshot`."
  {:malli/schema [:=> [:cat [:map [:seon.ctx/snapshot :seon.ctx/snapshot]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{{:seon.agent/keys [id branch role]} :seon.ctx/snapshot}]
  {:seon.ui/hiccup
   [:span {:class "ent-chip ent-ctx" :data-min-width 180}
    (badge "amber" "@ctx") (ent-link [:seon.agent/id id] (str id) "mono strong")
    (mut (str (or role :worker) " · " (or branch :main)))]})

(defn render-ctx-snapshot
  "Full `@ctx` runtime card (~420px) for `:seon.ctx/snapshot` carrying durable memory, context-prefs,
   and static Core-12 REPL idioms (or Harbor `:container` workspace tools when `:mode :container`)."
  {:malli/schema [:=> [:cat [:map [:seon.ctx/snapshot :seon.ctx/snapshot]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1000000}
  [{{:seon.agent/keys [id branch role memory context-prefs] :as snap} :seon.ctx/snapshot}]
  (let [mem (vec (or memory []))
        prefs (when (map? context-prefs) context-prefs)
        container? (= :container (or (:seon.ctx/mode snap) (:mode prefs)))]
    {:seon.ui/hiccup
     [:div {:class "card ent-card ent-ctx" :data-min-width 420}
      [:div {:class "card-head ent-head"}
       [:div {:class "pill-row"} (badge "amber" "@ctx") (ent-link [:seon.agent/id id] (str id) "mono strong")]
       (mut (str (or branch :main)))]
      (kv-dl [[":seon.agent/role" (str (or role :worker))]
              [":seon.agent/context-prefs" (when (seq prefs) (pr-str prefs))]])
      (when (seq mem)
        [:div {:class "doc-box sm-copy"}
         [:p "Durable Datahike memory:"]
         (into [:ul] (for [note mem] [:li (if (string? note) note (pr-str note))]))])
      (if container?
        [:div {:class "doc-box sm-copy"}
         [:p "Container Workspace REPL tools (isolated Linux container; batch/parse output in Clojure!):"]
         [:ul
          [:li "Shell: `(user/sh! \"cmd\")` or `(user/sh! \"cmd\" {:cwd \"/app\" :timeout-sec 60})` -> `{:exit :out :err}` (extract `(:out res)`). Chain commands with `&&` or write scripts via `(user/fs-write \"/tmp/solve.py\" ...)`."]
          [:li "Files: `(user/fs-read \"/app/file\")` (or `{:lines [1 120]}`) -> `{:ok? :lines :content}` (extract `(:content res)` — it returns a map, NOT a raw string!), `(user/fs-write \"/app/file\" \"content\")`, `(user/fs-patch \"/app/file\" \"old\" \"new\")` (`{:all? true}` for all matches)."]
          [:li "Verify & finish: Solve strictly from local container files and installed tools — never search the web or fetch external repos/solutions. Match exact output path/format, verify via `(user/sh! ...)`, then stop concisely. Do NOT call `(user/ready!)`."]]]
        [:div {:class "doc-box sm-copy"}
         [:p "Core REPL tools (Datahike `d` pre-aliased; `@ctx` is your live reactive CtxAtom with `:seon.ctx/neighborhood` — never shadow it):"]
         [:ul
          [:li "Read & context (batch in 1 turn!): `(user/context 'ns | :ns/task)`, `(user/explore! [:attr val])`, `(user/source 'a/b 'c/d)`, `(user/grep-src [\"p1\" \"p2\"] {:ns 'ns})`, `(user/ns-outline 'ns1 'ns2)`, `(user/callers 'a/b)`, `(user/search \"term\")`, `(user/db)` / `(user/main-db)` (prefer indexed `(d/datoms db :aevt/:avet :attr)`)."]
          [:li "Edit & transact: `(user/edit-fn! 'ns/fn [[old1 new1] ...])`, `(user/add-fn! 'ns \"(defn f ...)\")`, `(user/set-meta! 'ns/fn {:malli/schema [...] :cost/ticks ...})`, `(user/add-require! 'ns '[lib :as a])`, `(user/prune-requires! 'ns)`, `(user/move-vars! {...})`, `(user/remove-var! 'ns/v)`, `(user/doc! \"docs/f.md#sec\" \"body\")`, `(user/register-schema! :seon.x/k form {:doc \"...\"})`, `(user/create-task! {...})`, `(user/transact! tx-data)`."]
          [:li "Verify & ship: `(user/run-tests! 'ns-test/var | 'ns)` (use `(seon.test-fixture/with-test-store [{:keys [conn]}] ...)`), `(user/health)`, `(user/diff)`, `(user/metrics)`, `(user/autofix-health!)`, `(user/record-summary! {...})`, `(user/ready!)`."]
          [:li "Delegate & context: `(user/ask! :ns/<subsys> \"...\")` + `(user/await! [c1 ...])`, `(user/answer! cid val)`, `(user/remember! \"fact\")`, `(user/tune-context! {:extra-namespaces [...]})`, `(user/compact! \"summary\")`, `(user/rules)` / `(user/curate! [...])`, `(user/help)`."]
          [:li "Idioms & traps: Malli predicates are symbols (`number?`, `pos-int?`, `map?`, `fn?`), NEVER `:number`; persist tests via `(user/add-fn! 'ns-test \"(deftest ...)\")` (raw `deftest` evals do not persist); Datahike `:store :id` requires `(java.util.UUID/randomUUID)`/`(random-uuid)`; attach `:malli/schema` + `:cost/ticks` to new pure helpers; batch related edits + `(user/run-tests! ...)` in one `(do ...)` eval."]]])]}))

;; Chronicle Summary (:seon.summary/entity)

(defn render-summary-chip
  "Compact chip (~180px) for `:seon.summary/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.summary/entity :seon.summary/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 400000}
  [{{:seon.summary/keys [id kind title to-tx]} :seon.summary/entity}]
  {:seon.ui/hiccup
   [:span {:class "ent-chip ent-summary" :data-min-width 180}
    (badge "amber" (if (keyword? kind) (name kind) "summary"))
    (ent-link [:seon.summary/id id] (one-line (or title "Summary") 44) "strong")
    (when (integer? to-tx) (mut (str "tx " to-tx)))]})

(defn render-summary-card
  "Time- and tx-anchored Chronicle card (~420px) for `:seon.summary/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.summary/entity :seon.summary/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.summary/keys [id kind title body from-tx to-tx agent refs visual]} :seon.summary/entity}]
  (let [k (if (keyword? kind) kind :system-pulse)
        bcls (case k (:milestone :architecture) "amber" :compaction "purple" :system-pulse "blue" "muted")
        ftx (when (integer? from-tx) (long from-tx))
        ttx (when (integer? to-tx) (long to-tx))
        tx-lbl (cond (and ftx ttx (not= ftx ttx)) (str "tx " ftx ".." ttx) ttx (str "tx " ttx) :else nil)]
    {:seon.ui/hiccup
     [:article {:class "card ent-card ent-summary stack-sm" :data-min-width 420}
      [:header {:class "card-head ent-head"}
       [:div {:class "pill-row"} (badge bcls (name k)) (ent-link [:seon.summary/id id] (or (nonblank title) "System Summary") "strong")]
       [:div {:class "pill-row"}
        (when (keyword? agent) (btn "link-muted tiny mono" (list 'seon.web/view-agent! agent :canvas) (str agent)))
        (when (and ttx tx-lbl) (btn "btn sm mono" (list 'seon.web/jump-canvas-tx! ttx) tx-lbl))]]
      (when (nonblank body) [:div {:class "sm-copy"} (rmd/render-markdown-blocks body)])
      (when (and (vector? visual) (keyword? (first visual))) [:div {:class "canvas-surface"} visual])
      (when-let [rs (seq-items refs)]
        [:footer {:class "pill-row"}
         (for [rf (take 10 rs)]
           (cond
             (and (keyword? rf) (not= "branch" (namespace rf)))
             (btn "btn sm mono" (list 'seon.web/view-agent! rf :canvas) (str rf))
             (and (vector? rf) (= 2 (count rf)) (= :seon.agent/id (first rf)) (keyword? (second rf)))
             (btn "btn sm mono" (list 'seon.web/view-agent! (second rf) :canvas) (str (second rf)))
             :else [:span {:class "pill mono"} (str rf)]))])
      (when ttx [:p {:class "tiny muted mono"} (str "Drill in: (d/as-of (user/main-db) " ttx ")")])]}))

;; Benchmark Run (:seon.bench.run/entity)

(defn render-bench-run-card
  "Live/completed benchmark monitor card (~420px) for `:seon.bench.run/entity`."
  {:malli/schema [:=> [:cat [:map [:seon.bench.run/entity :seon.bench.run/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:seon.bench.run/keys [id title status started-ms updated-ms finished-ms total passed failed proxy-stats trials]}
    :seon.bench.run/entity}]
  (let [st (or status :running) running? (= :running st)
        ts (if (sequential? trials) (filterv map? trials) [])
        tot (safe-long total (count ts))
        pas (safe-long passed 0) fld (safe-long failed 0)
        pct (if (pos? tot) (min 100 (Math/round (* 100.0 (/ (double (+ pas fld)) (double tot))))) 0)
        s-ms (safe-long started-ms 0) e-ms (safe-long (or finished-ms updated-ms started-ms) s-ms)
        es (if (and (pos? s-ms) (>= e-ms s-ms)) (/ (Math/round (* 10.0 (/ (double (- e-ms s-ms)) 1000.0))) 10.0) 0.0)
        ps (when (map? proxy-stats) proxy-stats)
        pt (safe-long (:prompt-tokens ps) 0) ct (safe-long (:completion-tokens ps) 0) ht (safe-long (:cache-hit-tokens ps) 0)
        kv (when (pos? pt) (Math/round (* 100.0 (/ (double ht) (double pt)))))
        fmt-k (fn [n] (if (>= n 1000) (str (/ (Math/round (/ (double n) 100.0)) 10.0) "k") (str n)))
        t-cls (fn [tst] (case tst (:merged :passed) "green mono" (:timeout :failed :error) "red mono" (:testing :merging) "purple mono" "amber mono"))]
    {:seon.ui/hiccup
     [:article {:id (str "bench-run-" (or id "live")) :class (str "ch-hero bench-run-card" (when running? " is-running")) :data-min-width 420}
      [:header {:class "card-head"}
       [:div {:class "pill-row"}
        [:span {:class (str "dot " (if running? "pulsing" (when (= :passed st) "live"))) :aria-hidden "true"}]
        [:span (str "BENCHMARK · " (str/upper-case (if (keyword? st) (name st) (str st))))]
        (mut (str pas "/" tot " passed · " es "s"))]
       (when ps (mut (str (safe-long (:requests ps) 0) " req · " (fmt-k (+ pt ct)) " tok" (when kv (str " · " kv "% KV")))))]
      [:h2 {:class "ch-hero-title"} (or (nonblank title) "Benchmark Trial Run")]
      [:div {:class "heat-bar"} [:div {:class (str "heat-fill " (case st :passed "sig-calm" :failed "sig-hot" "sig-warm"))
                                       :style {:width (str (if (and running? (zero? pct)) 8 pct) "%")}}]]
      (when (seq ts)
        [:div {:class "stack-xs"}
         (for [t ts :let [tid (:id t) tst (or (:status t) :booting) err (:last-error t) snip (:last-snippet t)]]
           [:div {:class "ent-row"}
            (badge (t-cls tst) (if (keyword? tst) (name tst) (str tst)))
            [:strong {:class "mono tiny"} (if (keyword? tid) (name tid) (str tid))]
            (mut (str (safe-long (:evals t) 0) " evals · " (/ (Math/round (/ (double (safe-long (:elapsed-ms t) 0)) 100.0)) 10.0) "s"))
            (when (and (:alive? t) (:port t))
              [:a {:href (str "http://127.0.0.1:" (:port t) "/") :target "_blank" :rel "noopener" :class "tiny mono"}
               (str "Open :" (:port t) " ↗")])
            (when (nonblank (str (or err snip))) [:span {:class (str "mono tiny " (if err "red-text" "muted"))} (one-line (or err snip) 80)])])])]}))

;; Health Report (:seon.health/report) & Task Lineage (:seon.task/lineage)

(defn render-health-report
  "Uniform card (~360px) for `:seon.health/report`."
  {:malli/schema [:=> [:cat [:map [:seon.health/report :seon.health/report]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1200000}
  [{{:keys [ok? counts problems]} :seon.health/report}]
  (let [probs (vec (or problems []))
        err-n (count (filter #(= :error (:severity %)) probs))
        warn-n (- (count probs) err-n)
        err-cs (into #{} (comp (filter #(= :error (:severity %))) (map :class)) probs)]
    {:seon.ui/hiccup
     [:div {:class "card ent-card ent-health stack-xs" :data-min-width 360}
      [:div {:class "card-head ent-head"}
       [:div {:class "pill-row"}
        [:strong "System Health"]
        (badge (if ok? "green" "red") (if ok? "HEALTHY" (str err-n " ERROR(S)")))
        (when (pos? warn-n) (badge "amber" (str warn-n " ADVISORY")))]
       (btn "btn primary sm" '(seon.web/run-tests! :main) "▶ Run Reach Tests")]
      (when (seq counts)
        [:div {:class "pill-row"}
         (for [[cls n] (sort-by (comp str key) counts)]
           (badge (if (err-cs cls) "red" "amber") (str (name cls) ": " n)))])
      (if (empty? probs)
        [:p {:class "muted sm-copy"} "All structural checks green, zero failing branch tests, zero open faults."]
        [:div {:class "stack-xs"}
         (for [p (take 8 probs)]
           [:div {:class "ent-row"}
            (badge (if (= :error (:severity p)) "red" "amber") (name (or (:class p) :issue)))
            [:span {:class "mono tiny strong"} (str (:subject p))]
            [:span {:class "tiny muted"} (one-line (:message p) 100)]
            (btn "btn sm" (list 'seon.web/assign-health-fix! (:class p) (:subject p) (:message p)) "Fix")])])
      [:p {:class "tiny muted"} "REPL: (user/health) recomputes live health."]]}))

(defn- fmt-lineage-task [prefix t]
  (let [st (cond (:seon.task/template? t) "template" (:seon.task/done? t) "done" :else "open")]
    (str prefix "[" st "] " (:seon.task/id t) " — " (:seon.task/title t)
         (when-let [sc (:seon.task/scope t)] (str " · scope " sc))
         (when-let [o (:seon.task/owner t)] (str " · owner " o))
         (when-let [sm (nonblank (:seon.task/summary t))] (str "\n    summary: " (first-doc-line sm 180))))))

(defn render-task-lineage
  "Uniform card (~420px) for `:seon.task/lineage` emitting `<task-chain>`, `<historical-tasks>`, and `<task-templates>`."
  {:malli/schema [:=> [:cat [:map [:seon.task/lineage :seon.task/lineage]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]
   :cost/ticks 1500000}
  [{{:keys [current ancestors children historical fixes templates]} :seon.task/lineage :as m}]
  (let [omit-plan? (boolean (:omit-current-plan? m))
        omit-tpl? (boolean (:omit-templates? m))]
    {:seon.ui/hiccup
     [:div {:class "card ent-card ent-lineage stack-xs" :data-min-width 420}
      [:div {:class "card-head ent-head"}
       [:div {:class "pill-row"} (badge "purple" "task chain")
        [:strong (or (:seon.task/title current) "Task Lineage & History")]]
       (when-let [tid (:seon.task/id current)] (ent-link [:seon.task/id tid] (str tid) "muted tiny mono"))]
      (when (or current (seq ancestors) (seq children))
        [:div {:class "stack-xs"}
         [:span {:class "tiny muted mono"} "<task-chain>"]
         (for [[i a] (map-indexed vector ancestors)]
           [:div {:class "tiny mono"} (fmt-lineage-task (str (apply str (repeat i "  ")) "↑ needed-by: ") a)])
         (when current
           [:div {:class "tiny mono"}
            (str "● current: " (:seon.task/id current) " — " (:seon.task/title current)
                 (when (:seon.task/scope current) (str " · scope " (:seon.task/scope current)))
                 (when (and (not omit-plan?) (nonblank (:seon.task/plan current))) (str "\n  plan: " (:seon.task/plan current)))
                 (when (seq (:seon.task/tests current)) (str "\n  tests: " (str/join ", " (map str (:seon.task/tests current))))))])
         (for [c children] [:div {:class "tiny mono"} (fmt-lineage-task "  ↓ needs: " c)])
         [:span {:class "tiny muted mono"} "</task-chain>"]])
      (when (or (seq historical) (seq fixes))
        [:div {:class "stack-xs"}
         [:span {:class "tiny muted mono"} "<historical-tasks>"]
         (for [h historical] [:div {:class "tiny"} (fmt-lineage-task "- " h)])
         (for [fx fixes]
           [:div {:class "tiny"}
            (str "- [fix] " (:seon.fix/id fx) " (" (:seon.fix/error-class fx) "): "
                 (first-doc-line (or (:seon.fix/change-summary fx) (:seon.fix/root-cause fx)) 160))])
         [:span {:class "tiny muted mono"} "</historical-tasks>"]])
      (when (and (not omit-tpl?) (seq templates))
        [:div {:class "stack-xs"}
         [:span {:class "tiny muted mono"} "<task-templates>"]
         (for [t templates]
           [:div {:class "tiny mono"}
            (str "- " (:seon.task/id t) " — " (:seon.task/title t)
                 " · use `(user/create-task! {:id :ns/topic :from-template " (:seon.task/id t) " :title \"...\" :scope 'ns :plan \"...\"})`")])
         [:span {:class "tiny muted mono"} "</task-templates>"]])]}))
