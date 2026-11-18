(ns seon.web
  "The `:web` Flow proc (`:io` workload) — Datastar + Pure Hiccup System Explorer:
   - Zero client-side JS state framework: serves `resources/public/datastar.js` (`33 KB`) and streams
     `datastar-patch-elements` over SSE (`GET /events`), morphing DOM fragments by stable `id`.
   - Callbacks are data (`:ui/op` + `:ui/args` in Hiccup -> `POST /ui/act`), routed through `:server`'s
     validated EDN ops so UI actions, CLI (`bin/seon`), and agents use the exact same rails.
   - Schema-driven value rendering (`render-value`): any `:schema/key` with `:schema/render` renders via
     its Clojure Hiccup component; maps sharing keys render as `<table>`; code renders as `<pre><code>`.
   - Full System Explorer tabs:
     1. `:flow`     — Live Native Jolt `core.async.flow` SVG DAG (`::flow/site`, `::flow/in-fill`, COW PIDs)
     2. `:program`  — Namespaces & Vars (`:var/refs` callers/callees, `:var/schema`, `:cost/*`, source)
     3. `:schemas`  — Malli Schemas (`seed=42` sample generator, `:var/schema-refs` users, live registration)
     4. `:branches` — Datahike Branches + `refs/heads/seon/*` Git diffs + 3-way entity diffs + `:test`/`:merge`
     5. `:agents`   — Agents, `:system/root` Aero config inheritance, multi-tool turns & persistent COW REPL"
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.datafy :as datafy]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [ring-chez.adapter :as adapter]
            [seon.code :as code]
            [seon.git :as git]
            [seon.merge :as merge]
            [seon.reconciler :as reconciler]
            [seon.server :as server]
            [seon.store :as store]))

;; --- Pure Hiccup -> HTML Serializer with Data-Driven Datastar Callbacks (`:ui/op`) ---

(defn esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn url-enc [^String s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn raw-html [s]
  {::raw (str s)})

(defn- expand-ui-attrs [tag attrs]
  (if-let [op (:ui/op attrs)]
    (let [args (:ui/args attrs)
          qs (str "op=" (url-enc (name op))
                  (when args (str "&args=" (url-enc (pr-str args)))))
          clean (dissoc attrs :ui/op :ui/args)]
      (if (= tag "form")
        (assoc clean (keyword "data-on:submit")
               (str "evt.preventDefault(); @post('/ui/act?" qs "', {contentType: 'form'})"))
        (assoc clean (keyword "data-on:click")
               (str "@post('/ui/act?" qs "')"))))
    attrs))

(def ^:private void-tags
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "param" "source" "track" "wbr"})

(defn hiccup->html [node]
  (cond
    (nil? node) ""
    (map? node) (or (::raw node) (esc (pr-str node)))
    (or (string? node) (number? node) (keyword? node) (symbol? node) (boolean? node)) (esc (str node))
    (seq? node) (apply str (map hiccup->html node))
    (vector? node)
    (let [hd (first node)]
      (if (fn? hd)
        (hiccup->html (apply hd (rest node)))
        (let [tag (name hd)
              has-attrs? (and (map? (second node)) (not (contains? (second node) ::raw)))
              attrs (when has-attrs? (expand-ui-attrs tag (second node)))
              children (if has-attrs? (drop 2 node) (rest node))
              attr-str (when (seq attrs)
                         (apply str
                                (keep (fn [[k v]]
                                        (when (and (some? v) (not (false? v)))
                                          (let [aname (if (namespace k)
                                                        (str (namespace k) ":" (name k))
                                                        (name k))]
                                            (if (true? v)
                                              (str " " aname)
                                              (str " " aname "=\"" (esc v) "\"")))))
                                      attrs)))]
          (if (contains? void-tags tag)
            (str "<" tag attr-str ">")
            (str "<" tag attr-str ">" (apply str (map hiccup->html children)) "</" tag ">")))))
    :else (esc (pr-str node))))

(defn datastar-patch-event
  "Frames Hiccup/HTML into a Datastar v1 `datastar-patch-elements` SSE event."
  ([hiccup-or-html] (datastar-patch-event hiccup-or-html nil nil))
  ([hiccup-or-html selector mode]
   (let [html (if (string? hiccup-or-html) hiccup-or-html (hiccup->html hiccup-or-html))]
     (str "event: datastar-patch-elements\n"
          (when selector (str "data: selector " selector "\n"))
          (when mode (str "data: mode " mode "\n"))
          (apply str (for [line (str/split-lines html) :when (not (str/blank? line))]
                       (str "data: elements " line "\n")))
          "\n"))))

;; --- Schema-Driven Data Visualization (`render-value`) ---

(defn render-value
  "Renders any Clojure value as semantic Hiccup without custom throwaway UIs:
   - Checks `:schema/render` if the value carries `:seon/schema` metadata;
   - Renders homogeneous vectors of maps sharing >= 2 keys as a clean HTML `<table>`;
   - Renders maps as `<dl>` key-value grids, sets/vectors as compact lists, and code as `<pre><code>`."
  [db v]
  (cond
    (nil? v) [:span {:class "muted"} "nil"]

    (and (map? v) (:seon/code v))
    [:pre {:class "code-box"} [:code (:seon/code v)]]

    ;; Custom Hiccup component registered on a `:schema/key`!
    (and db (meta v) (:seon/schema (meta v)))
    (let [sk (:seon/schema (meta v))
          rsym (:schema/render (d/pull db '[:schema/render] [:schema/key sk]))
          rfn (when rsym (try (requiring-resolve rsym) (catch Throwable _ nil)))]
      (if rfn
        (try (rfn {:db db} v)
             (catch Throwable ex [:div {:class "badge red"} (str "Render error in " rsym ": " (.getMessage ex))]))
        (render-value nil (with-meta v nil))))

    ;; Vector of >= 2 maps sharing >= 2 keys -> automatic semantic `<table>`!
    (and (sequential? v) (>= (count v) 2) (every? map? v)
         (>= (count (apply clojure.set/intersection (map #(set (keys %)) (take 5 v)))) 2))
    (let [cols (vec (sort-by str (distinct (mapcat keys (take 10 v)))))]
      [:table {:class "data-table"}
       [:thead [:tr (for [c cols] [:th (str c)])]]
       [:tbody
        (for [row (take 50 v)]
          [:tr (for [c cols] [:td (render-value db (get row c))])])]])

    (map? v)
    [:dl {:class "kv-grid"}
     (for [[k val] (sort-by (comp str key) v)]
       (list [:dt (str k)] [:dd (render-value db val)]))]

    (or (vector? v) (set? v) (seq? v))
    (if (empty? v)
      [:span {:class "muted"} (pr-str v)]
      [:div {:class "pill-row"}
       (for [item (take 40 v)]
         [:span {:class "pill"} (str item)])])

    :else
    [:span (str v)]))

;; --- SVG Flow Graph Renderer (Pure Server-Side SVG Hiccup -> Morphed by Datastar!) ---

(def ^:private proc-coords
  {:server     {:x 40  :y 130}
   :transactor {:x 250 :y 130}
   :reconciler {:x 470 :y 45}
   :test       {:x 470 :y 145}
   :git        {:x 470 :y 245}
   :web        {:x 250 :y 255}
   :agents     {:x 690 :y 65}
   :model      {:x 690 :y 210}})

(defn- render-flow-svg [df pings self-frames]
  (let [conns (:conns df)]
    [:svg {:id "flow-topology-svg" :viewBox "0 0 880 325" :class "flow-svg"}
     [:defs
      [:marker {:id "arrow" :viewBox "0 0 10 10" :refX "9" :refY "5"
                :markerWidth "6" :markerHeight "6" :orient "auto-start-reverse"}
       [:path {:d "M 0 1 L 10 5 L 0 9 z" :fill "#475569"}]]]
     ;; Edges
     (for [[[fp fport] [tp tport]] conns
           :let [c1 (get proc-coords fp {:x 40 :y 40})
                 c2 (get proc-coords tp {:x 200 :y 40})
                 x1 (+ (:x c1) 150) y1 (+ (:y c1) 30)
                 x2 (:x c2)         y2 (+ (:y c2) 30)
                 mx (quot (+ x1 x2) 2)
                 fill-cnt (get-in pings [tp ::flow/in-fill tport :count] 0)
                 hot? (pos? fill-cnt)]]
       [:g {:key (str fp "-" fport "->" tp "-" tport)}
        [:path {:d (str "M " x1 " " y1 " C " mx " " y1 ", " mx " " y2 ", " x2 " " y2)
                :fill "none"
                :stroke (if hot? "#38bdf8" "#334155")
                :stroke-width (if hot? "2.4" "1.4")
                :marker-end "url(#arrow)"}]])
     ;; Proc Nodes
     (for [[pid {:keys [x y]}] proc-coords
           :let [p (get pings pid)
                 desc (get-in df [:procs pid :desc])
                 wload (or (:workload desc) :io)
                 cnt (if (= pid :web) self-frames (or (::flow/count p) 0))
                 site (if (= pid :web) {:stepping :sse} (::flow/site p))
                 stepping? (contains? site :stepping)
                 in-q (reduce + 0 (map :count (vals (::flow/in-fill p))))
                 stroke (cond stepping? "#38bdf8"
                              (= wload :compute) "#a78bfa"
                              (= wload :mixed) "#f59e0b"
                              :else "#10b981")]]
       [:g {:id (str "flow-node-" (name pid))
            :transform (str "translate(" x "," y ")")
            :class "flow-node"
            :ui/op :select-proc
            :ui/args {:proc pid}}
        [:rect {:width "150" :height "58" :rx "8"
                :fill "#0f172a" :stroke stroke :stroke-width (if stepping? "2.2" "1.5")}]
        [:text {:x "10" :y "20" :fill "#f8fafc" :font-size "12" :font-weight "700" :font-family "monospace"}
         (str ":" (name pid))]
        [:text {:x "140" :y "20" :text-anchor "end" :fill stroke :font-size "10" :font-family "monospace"}
         (name wload)]
        [:text {:x "10" :y "38" :fill "#94a3b8" :font-size "10" :font-family "monospace"}
         (str "steps: " cnt (when (pos? in-q) (str " | q:" in-q)))]
        [:text {:x "10" :y "51" :fill (if stepping? "#38bdf8" "#64748b") :font-size "9.5" :font-family "monospace"}
         (cond
           (:stepping site) (str "stepping " (:stepping site))
           (:writing site)  (str "writing " (pr-str (:writing site)))
           :else            "parked (0% CPU)")]])]))

;; --- Explorer View Builders (Pure Functions of `db` + UI View State) ---

(defn- render-header [st db pings]
  (let [boot-seq (:boot-seq st 1)
        git-sha (:git/commit (d/pull db '[:git/commit] [:git/repo :main]))
        forms-n (count (d/datoms db :aevt :form/key))
        vars-n (count (d/datoms db :aevt :var/name))
        schemas-n (count (d/datoms db :aevt :schema/key))
        docs-n (count (d/datoms db :aevt :doc/id))
        green-n (count (filter #(true? (:v %)) (d/datoms db :aevt :test/pass?)))
        live-ags (get-in pings [:agents ::flow/state :live] {})
        tab (:active-tab st :flow)]
    [:header {:id "seon-header" :class "topbar" :data-boot-seq (str boot-seq)}
     [:script {:id "boot-meta" :type "application/json"}
      (raw-html (str "{\"bootSeq\":" boot-seq ",\"mainTx\":" (:max-tx db) "}"))]
     [:div {:class "brand-row"}
      [:div {:class "brand"}
       [:span {:class "dot live"}]
       [:strong "SEON"]
       [:span {:class "badge blue"} (str "Boot #" boot-seq)]
       [:span {:class "mono muted"}
        (str ":main tx " (:max-tx db)
             (when git-sha (str " · git " (subs git-sha 0 (min 8 (count git-sha)))))
             " · ForkParent PID " (:fork-parent-pid st)
             " (GC ceil " (or (:fork-parent-ceiling st) 5) ")"
             " · " (count live-ags) " warm COW PID(s)")]]
      [:div {:class "stats-strip"}
       [:span {:class "stat-chip"} [:b vars-n] " vars"]
       [:span {:class "stat-chip"} [:b forms-n] " forms"]
       [:span {:class "stat-chip"} [:b schemas-n] " schemas"]
       [:span {:class "stat-chip"} [:b docs-n] " doc sections"]
       [:span {:class "stat-chip green"} [:b green-n] " cached green"]
       [:button {:type "button" :class "btn danger" :ui/op :power-loss} "⚡ Power-Loss Reboot"]]]
     [:nav {:class "tabs-nav"}
      (for [[tid label] [[:flow     "1. Flow & OS Topology"]
                         [:program  "2. Program & Vars"]
                         [:schemas  "3. Malli Schemas & Seed=42"]
                         [:branches "4. Branches & 3-Way Git Diff"]
                         [:agents   "5. Agents, Config & Multi-Tool REPL"]]]
        [:button {:type "button"
                  :class (str "tab-btn" (when (= tab tid) " active"))
                  :ui/op :switch-tab
                  :ui/args {:tab tid}}
         label])]]))

(defn- render-flow-tab [st db df pings]
  (let [sel-proc (:selected-proc st :transactor)
        pinfo (get pings sel-proc)
        pstate (if (= sel-proc :web)
                 {:tabs (count (:tabs st)) :port (:bound-port st) :frames (:frames st)}
                 (::flow/state pinfo))
        live-ags (get-in pings [:agents ::flow/state :live] {})
        tests (->> (d/datoms db :aevt :test/digest)
                   (map #(d/pull db '[:test/digest :test/var :test/kind :test/pass? :test/ms :test/ticks :test/report] (:e %)))
                   (sort-by #(str (:test/var %)))
                   (take 25))]
    [:div {:id "tab-panel" :class "grid-2"}
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 "Static 8-Proc clojure.core.async.flow Graph"]
        [:span {:class "muted mono"} "click any proc node to inspect state & queues"]]
       (render-flow-svg df pings (:frames st 0))]
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 "Reach-Cached clojure.test & Seed=42 Generative Verdicts"]
        [:button {:type "button" :class "btn primary" :ui/op :run-tests :ui/args {:branch :main}}
         "▶ Run :main Reach Tests"]]
       (if (empty? tests)
         [:p {:class "muted"} "No test digests recorded yet. Click 'Run :main Reach Tests' or run a branch test."]
         [:table {:class "data-table"}
          [:thead [:tr [:th "Var"] [:th "Kind"] [:th "Digest"] [:th "Verdict"] [:th "Cost"]]]
          [:tbody
           (for [t tests]
             [:tr
              [:td {:class "mono"} (str (:test/var t))]
              [:td [:span {:class "pill"} (name (or (:test/kind t) :deftest))]]
              [:td {:class "mono muted"} (subs (str (:test/digest t)) 0 (min 12 (count (str (:test/digest t)))))]
              [:td [:span {:class (str "badge " (if (:test/pass? t) "green" "red"))}
                    (if (:test/pass? t) "PASS" (str "FAIL: " (:test/report t)))]]
              [:td {:class "mono muted"}
               (str (:test/ms t 0.0) " ms"
                    (when (pos? (or (:test/ticks t) 0))
                      (str " · " (quot (:test/ticks t) 1000) "k ticks")))]])]])]]
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 (str "Proc Inspector — :" (name sel-proc))]
        [:span {:class "badge blue"} (str "steps: " (or (::flow/count pinfo) (:frames st 0)))]]
       (render-value db {:site (::flow/site pinfo)
                         :in-fill (::flow/in-fill pinfo)
                         :state pstate})]
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 "Live COW Forked OS Processes"]
        [:span {:class "mono muted"} (str (count live-ags) " active / warm")]]
       (if (empty? live-ags)
         [:p {:class "muted"} "0 child OS processes running — idle agents hold 0 OS threads and 0 MB RAM."]
         (render-value db (mapv (fn [[aid m]] (assoc m :agent aid)) live-ags)))]
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 "Recent ::flow/report & ::flow/error Stream"]]
       [:div {:class "log-box"}
        (for [err (take 8 (:errors st))]
          [:div {:class "log-line red"} (str "ERROR: " (pr-str err))])
        (for [rep (take 18 (:reports st))]
          [:div {:class "log-line"} (str rep)])]]]]))

(defn- render-program-tab [st db]
  (let [nss (->> (d/datoms db :aevt :ns/name)
                 (map #(d/pull db '[:ns/name :ns/path :ns/doc] (:e %)))
                 (sort-by #(str (:ns/name %)))
                 vec)
        sel-ns (or (:selected-ns st) (:ns/name (first nss)))
        vars-in-ns (when sel-ns (code/ns-vars db sel-ns))
        sel-var (or (:selected-var st) (:var/name (first vars-in-ns)))
        var-detail (when sel-var (server/explore-var db sel-var))]
    [:div {:id "tab-panel" :class "grid-3"}
     [:div {:class "card scroll-pane"}
      [:div {:class "card-head"} [:h2 (str "Namespaces (" (count nss) ")")]]
      [:div {:class "list-col"}
       (for [n nss]
         [:button {:type "button"
                   :class (str "list-item" (when (= sel-ns (:ns/name n)) " active"))
                   :ui/op :select-ns
                   :ui/args {:ns (:ns/name n)}}
          [:div {:class "mono strong"} (str (:ns/name n))]
          [:div {:class "muted tiny"} (:ns/path n)]])]]
     [:div {:class "card scroll-pane"}
      [:div {:class "card-head"} [:h2 (str "Vars in " (or sel-ns "—") " (" (count vars-in-ns) ")")]]
      [:div {:class "list-col"}
       (for [v vars-in-ns]
         [:button {:type "button"
                   :class (str "list-item" (when (= sel-var (:var/name v)) " active"))
                   :ui/op :select-var
                   :ui/args {:ns sel-ns :var (:var/name v)}}
          [:div {:class "row-between"}
           [:span {:class "mono strong"} (name (:var/name v))]
           [:span {:class "pill-row"}
            (when (:var/test? v) [:span {:class "badge green"} (if (:var/platform-test? v) "platform-test" "deftest")])
            (when (:var/schema v) [:span {:class "badge blue"} "malli"])
            (when (:var/ticks-budget v) [:span {:class "badge amber"} "cost"])]]
          (when (:var/arglists v)
            [:div {:class "mono muted tiny"} (:var/arglists v)])])]]
     [:div {:class "card scroll-pane"}
      [:div {:class "card-head"} [:h2 (str "Var Fact Sheet — " (or sel-var "Select a var"))]]
      (if-not var-detail
        [:p {:class "muted"} "Select a namespace and var to inspect its indexed Datahike facts."]
        [:div {:class "stack-sm"}
         (when (:var/doc var-detail)
           [:p {:class "doc-box"} (:var/doc var-detail)])
         (render-value db (select-keys var-detail
                                       [:var/name :var/arglists :var/schema :var/schema-refs
                                        :var/ticks-budget :var/bytes-budget :var/big-o
                                        :var/test? :var/platform-test? :hash]))
         [:div
          [:h3 "Direct Callers (1 AVET seek on :var/refs)"]
          (if (empty? (:callers var-detail))
            [:span {:class "muted tiny"} "No callers in Datahike."]
            [:div {:class "pill-row"}
             (for [c (:callers var-detail)]
               [:button {:type "button" :class "pill-btn"
                         :ui/op :select-var
                         :ui/args {:ns (symbol (namespace c)) :var c}}
                (str c)])])]
         [:div
          [:h3 "Direct Callees (:var/refs)"]
          (if (empty? (:var/refs var-detail))
            [:span {:class "muted tiny"} "None."]
            [:div {:class "pill-row"}
             (for [r (:var/refs var-detail)]
               [:button {:type "button" :class "pill-btn"
                         :ui/op :select-var
                         :ui/args {:ns (symbol (namespace r)) :var r}}
                (str r)])])]
         [:div
          [:h3 "Exact Stored Source (:form/src)"]
          [:pre {:class "code-box"} [:code (or (:src var-detail) "")]]]])]]))

(defn- render-schemas-tab [st db]
  (let [schemas (->> (d/datoms db :aevt :schema/key)
                     (map #(d/pull db '[:schema/key :schema/form :schema/doc] (:e %)))
                     (sort-by #(str (:schema/key %)))
                     vec)
        sel-key (or (:selected-schema st) (:schema/key (first schemas)))
        detail (when sel-key (server/explore-schema db sel-key))]
    [:div {:id "tab-panel" :class "grid-2"}
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 (str "Registered Malli Schemas (" (count schemas) ")")]]
       [:div {:class "list-col"}
        (for [s schemas]
          [:button {:type "button"
                    :class (str "list-item" (when (= sel-key (:schema/key s)) " active"))
                    :ui/op :select-schema
                    :ui/args {:key (:schema/key s)}}
           [:div {:class "mono strong"} (str (:schema/key s))]
           [:div {:class "mono muted tiny"} (:schema/form s)]])]]
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 "Register & Generatively Validate New Schema (Seed = 42)"]]
       [:form {:class "form-stack" :ui/op :register-schema}
        [:input {:name "key" :placeholder ":domain/price (qualified keyword)" :required true :class "inp mono"}]
        [:input {:name "form" :placeholder "[:double {:min 0.0 :max 10000.0}]" :required true :class "inp mono"}]
        [:input {:name "doc" :placeholder "Description of this schema contract" :class "inp"}]
        [:input {:name "examples" :placeholder "Optional EDN examples vector if custom host pred, e.g. [1.5 9.99]" :class "inp mono"}]
        [:button {:type "submit" :class "btn primary"} "Validate Generator (Seed=42) & Register on :main"]]
       (when-let [msg (:schema-feedback st)]
         [:div {:class (str "notice " (if (:ok? msg) "green" "red"))} (:text msg)])]]
     [:div {:class "card"}
      [:div {:class "card-head"} [:h2 (str "Schema Contract Inspector — " (or sel-key "—"))]]
      (if-not detail
        [:p {:class "muted"} "Select a schema on the left."]
        [:div {:class "stack-sm"}
         (when (:schema/doc detail) [:p {:class "doc-box"} (:schema/doc detail)])
         (render-value db (select-keys detail [:schema/key :schema/form :schema/examples :schema/render]))
         [:div
          [:h3 "Deterministic Generated Samples (seed = 42)"]
          [:div {:class "pill-row"}
           (for [smp (:samples detail)]
             [:span {:class "pill mono"} smp])]]
         [:div
          [:h3 "Functions Using This Schema (1 AVET seek on :var/schema-refs)"]
          (if (empty? (:used-by detail))
            [:p {:class "muted tiny"} "No functions reference this schema yet."]
            [:div {:class "pill-row"}
             (for [u (:used-by detail)]
               [:button {:type "button" :class "pill-btn"
                         :ui/op :jump-to-var
                         :ui/args {:var u}}
                (str u)])])]])]]))

(defn- render-branches-tab [st db]
  (let [conn (:conn st)
        cfg (:cfg st)
        repo-dir (:repo-dir st)
        branches (vec (sort (d/branches conn)))
        non-main (filterv #(not= :main %) branches)
        sel-b (or (:selected-branch st) (first non-main) :main)
        plan (when (and sel-b (not= sel-b :main) (store/branch? conn sel-b))
               (try (merge/three-way conn cfg sel-b) (catch Throwable _ nil)))
        git-ref (when (and repo-dir sel-b (not= sel-b :main))
                  (let [r (git/branch-git-ref sel-b)]
                    (when (git/ref-commit repo-dir r) r)))
        git-diff (when (and repo-dir git-ref)
                   (try (git/git! repo-dir "diff" "HEAD" git-ref) (catch Throwable _ nil)))]
    [:div {:id "tab-panel" :class "grid-2"}
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 (str "Datahike Branches (" (count branches) ")")]]
       [:div {:class "list-col"}
        (for [b branches
              :let [bdb (try (store/head conn b) (catch Throwable _ nil))
                    gref (when (and repo-dir (not= b :main)) (git/branch-git-ref b))
                    gsha (when gref (git/ref-commit repo-dir gref))]]
          [:button {:type "button"
                    :class (str "list-item" (when (= sel-b b) " active"))
                    :ui/op :select-branch
                    :ui/args {:branch b}}
           [:div {:class "row-between"}
            [:span {:class "mono strong"} (str b)]
            [:span {:class "mono muted tiny"} (str "tx " (:max-tx bdb))]]
           (when gsha
             [:div {:class "mono muted tiny"} (str gref " @ " (subs gsha 0 8))])])]]]
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 (str "3-Way Entity & Git Diff — " sel-b " vs :main")]
        (when (not= sel-b :main)
          [:div {:class "pill-row"}
           [:button {:type "button" :class "btn primary" :ui/op :run-tests :ui/args {:branch sel-b}}
            "▶ Run Reach + Seed=42 Tests"]
           [:button {:type "button" :class "btn success" :ui/op :merge-branch :ui/args {:branch sel-b}}
            "⇄ 3-Way Merge -> :main"]])]
       (when-let [fb (:branch-feedback st)]
         [:div {:class (str "notice " (if (:ok? fb) "green" "red"))} (:text fb)])
       (cond
         (= sel-b :main)
         [:p {:class "muted"} "Select or create a non-:main branch (via REPL eval or spawning an agent) to inspect its 3-way diff."]

         (nil? plan)
         [:p {:class "muted"} "Branch has no divergence."]

         :else
         [:div {:class "stack-sm"}
          (when (seq (:conflicts plan))
            [:div {:class "notice red"}
             [:strong "Attribute-Level 3-Way Conflicts: "]
             (render-value db (:conflicts plan))])
          [:h3 (str "Changed Datahike Entities (" (count (:entity-diffs plan)) ")")]
          (if (empty? (:entity-diffs plan))
            [:p {:class "muted tiny"} "0 entity diffs vs :main."]
            (render-value db (:entity-diffs plan)))
          (when (not (str/blank? git-diff))
            [:div
             [:h3 (str "Local Git Diff (`git diff HEAD.." git-ref "`, 0 worktrees)")]
             [:pre {:class "code-box"} [:code git-diff]]])])]]]))

(defn- render-agents-tab [st db pings]
  (let [store (:store db)
        live-ags (get-in pings [:agents ::flow/state :live] {})
        agents (->> (d/datoms db :aevt :agent/id)
                    (keep #(reconciler/derive-agent-status db store live-ags #{} #{} (:v %)))
                    (sort-by #(str (:id %)))
                    vec)
        sel-aid (or (:selected-agent st) (:id (first agents)))
        sel-ag (some #(when (= sel-aid (:id %)) %) agents)
        cfg-info (store/config-map db sel-aid)
        branch-for-evals (or (:branch sel-ag) (:repl-branch st :branch/explorer))
        eval-db (or (try (store/head (:conn st) branch-for-evals) (catch Throwable _ nil)) db)
        evals (->> (d/datoms eval-db :aevt :eval/id)
                   (map #(d/pull eval-db '[:eval/turn :eval/call-index :eval/call-id :eval/agent
                                           :eval/thought :eval/code :eval/value :eval/error
                                           :eval/ticks :eval/ms] (:e %)))
                   (sort-by (juxt #(unchecked-negate (or (:eval/turn %) 0))
                                  #(or (:eval/call-index %) 0)))
                   (take 15)
                   vec)]
    [:div {:id "tab-panel" :class "grid-2"}
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 "Spawn Autonomous Coordinator / Worker Tree"]]
       [:form {:class "row-form" :ui/op :spawn-demo-task}
        [:input {:name "title" :placeholder "Goal, e.g. Build order discount module" :required true :class "inp flex-1"}]
        [:select {:name "role" :class "inp"}
         [:option {:value "coordinator"} ":coordinator (spawns 2 workers)"]
         [:option {:value "worker"} ":worker (1 branch)"]]
        [:button {:type "submit" :class "btn primary"} "+ Spawn"]]]
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 (str "Logical Agents (" (count agents) ")")]]
       (if (empty? agents)
         [:p {:class "muted"} "No agents spawned yet."]
         [:div {:class "list-col"}
          (for [a agents
                :let [li (get live-ags (:id a))]]
            [:div {:class (str "list-item" (when (= sel-aid (:id a)) " active"))}
             [:div {:class "row-between"}
              [:button {:type "button" :class "link-btn mono strong"
                        :ui/op :select-agent :ui/args {:agent-id (:id a)}}
               (str (:id a))]
              [:div {:class "pill-row"}
               [:span {:class (str "badge " (case (:status a)
                                              :merged "green"
                                              :running "blue"
                                              :paused "amber"
                                              "slate"))}
                (name (:status a))]
               (when (number? (:pid li))
                 [:span {:class "pill mono"} (str "PID " (:pid li) " · " (:mem-mb li 0) "MB")])
               [:button {:type "button" :class "pill-btn"
                         :ui/op :toggle-pause :ui/args {:agent-id (:id a)}}
                (if (:paused? a) "Resume" "Pause")]]]
             [:div {:class "mono muted tiny"}
              (str (:branch a) " @ tx " (:branch-tx a) " · evals: " (:evals a)
                   (when (:parent a) (str " · parent: " (:parent a))))]])])]
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 (str "Aero Config Inheritance — " (or sel-aid ":system/root"))]
        (when (seq (:overrides cfg-info))
          [:span {:class "badge amber"} (str (count (:overrides cfg-info)) " agent override(s)")])]
       (render-value db (:effective cfg-info))]]
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 "Persistent COW Branch REPL (Supports Contracted defns & Multi-Form)"]]
       [:form {:class "form-stack" :ui/op :eval-repl}
        [:input {:name "branch" :value (str (or (:repl-branch st) (:branch sel-ag) ":branch/explorer"))
                 :class "inp mono" :placeholder ":branch/explorer"}]
        [:textarea {:name "code" :rows "5" :class "inp mono"
                    :placeholder "(ns demo.pricing)\n(defn net-price {:malli/schema [:=> [:cat [:double {:min 0.0}]] [:double {:min 0.0}]] :cost/ticks 50000} [x] (* x 0.9))\n(net-price 100.0)"}
         (or (:last-code st) "")]
        [:button {:type "submit" :class "btn primary"} "▶ Eval in COW Forked Branch REPL"]]
       (when-let [er (:eval-feedback st)]
         [:div {:class (str "notice " (if (:ok? er) "green" "red"))}
          [:div {:class "mono strong"}
           (if (:ok? er) (str "=> " (:value er)) (str "ERROR: " (:error er)))]
          [:div {:class "mono muted tiny"}
           (str (:branch er) " @ tx " (:branch-tx er)
                " · " (:ms er 0) " ms · " (:ticks er 0) " ticks")]])]
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 (str "Recorded Evals & Multi-Tool Calls on " branch-for-evals)]]
       (if (empty? evals)
         [:p {:class "muted"} "No evaluations recorded on this branch yet."]
         [:div {:class "stack-sm"}
          (for [e evals]
            [:div {:class "eval-card"}
             [:div {:class "row-between mono tiny muted"}
              [:span (str "turn #" (:eval/turn e)
                          (when (:eval/call-id e) (str " [" (:eval/call-id e) "]"))
                          " by " (:eval/agent e))]
              [:span (str (:eval/ms e 0) " ms · " (:eval/ticks e 0) " ticks")]]
             [:pre {:class "code-box"} [:code (:eval/code e "")]]
             (if (:eval/error e)
               [:div {:class "mono tiny red-text"} (str "ERROR: " (:eval/error e))]
               [:div {:class "mono tiny green-text"} (str "=> " (:eval/value e))])])])]]]))

(defn- render-app-root [st]
  (let [g (when-let [gf (:flow-fn st)] (gf))]
    (if-not g
      [:div {:id "seon-app"}
       [:header {:id "seon-header" :class "topbar" :data-boot-seq (str (:boot-seq st 1))}
        [:script {:id "boot-meta" :type "application/json"}
         (raw-html (str "{\"bootSeq\":" (:boot-seq st 1) ",\"rebooting\":true}"))]
        [:strong "Rebooting Seon Flow..."]]]
      (let [db @(:conn st)
            df (datafy/datafy g)
            pings (try (flow/ping g :timeout-ms 15) (catch Throwable _ {}))
            tab (:active-tab st :flow)]
        [:div {:id "seon-app"}
         (render-header st db pings)
         [:main {:id "seon-main" :class "main-wrap"}
          (case tab
            :program  (render-program-tab st db)
            :schemas  (render-schemas-tab st db)
            :branches (render-branches-tab st db)
            :agents   (render-agents-tab st db pings)
            (render-flow-tab st db df pings))]]))))

(def ^:private explorer-css
  ":root{color-scheme:dark;--bg:#090d16;--card:#111827;--border:#1e293b;--txt:#f1f5f9;--muted:#94a3b8;--blue:#38bdf8;--green:#10b981;--amber:#f59e0b;--red:#ef4444}
   *{box-sizing:border-box}
   body{margin:0;background:var(--bg);color:var(--txt);font-family:-apple-system,BlinkMacSystemFont,'Inter',sans-serif;font-size:13px;line-height:1.45}
   .mono{font-family:'SFMono-Regular',ui-monospace,Menlo,monospace}
   .muted{color:var(--muted)} .tiny{font-size:11px} .strong{font-weight:600}
   .topbar{background:#0b1120;border-bottom:1px solid var(--border);padding:10px 18px;position:sticky;top:0;z-index:20}
   .brand-row{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}
   .brand{display:flex;align-items:center;gap:10px}
   .dot.live{width:9px;height:9px;border-radius:50%;background:var(--green);box-shadow:0 0 8px var(--green)}
   .stats-strip{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
   .stat-chip{background:#1e293b;padding:3px 9px;border-radius:999px;font-size:11.5px;color:#cbd5e1}
   .stat-chip.green{background:rgba(16,185,129,.15);color:#34d399}
   .tabs-nav{display:flex;gap:6px;margin-top:10px;flex-wrap:wrap}
   .tab-btn{background:#162033;color:var(--muted);border:1px solid var(--border);padding:6px 12px;border-radius:6px;cursor:pointer;font-weight:600;font-size:12px}
   .tab-btn.active,.tab-btn:hover{background:#1e293b;color:#fff;border-color:var(--blue)}
   .main-wrap{padding:16px 18px;max-width:1640px;margin:0 auto}
   .grid-2{display:grid;grid-template-columns:1.1fr 1fr;gap:14px;align-items:start}
   .grid-3{display:grid;grid-template-columns:260px 310px 1fr;gap:14px;align-items:start}
   @media(max-width:1100px){.grid-2,.grid-3{grid-template-columns:1fr}}
   .stack{display:flex;flex-direction:column;gap:14px}
   .stack-sm{display:flex;flex-direction:column;gap:10px}
   .card{background:var(--card);border:1px solid var(--border);border-radius:10px;padding:13px}
   .scroll-pane{max-height:78vh;overflow:auto}
   .card-head{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:10px}
   h2{margin:0;font-size:13.5px;font-weight:700;color:#f8fafc}
   h3{margin:6px 0 4px;font-size:11.5px;text-transform:uppercase;letter-spacing:.04em;color:var(--muted)}
   .flow-svg{width:100%;height:auto;background:#090d16;border-radius:8px;border:1px solid #1e293b}
   .flow-node{cursor:pointer}
   .btn{border:1px solid var(--border);background:#1e293b;color:#fff;padding:5px 11px;border-radius:6px;cursor:pointer;font-size:12px;font-weight:600}
   .btn.primary{background:#0284c7;border-color:#0369a1}
   .btn.success{background:#059669;border-color:#047857}
   .btn.danger{background:#991b1b;border-color:#b91c1c}
   .badge{padding:2px 7px;border-radius:999px;font-size:10.5px;font-weight:700}
   .badge.blue{background:rgba(56,189,248,.16);color:#38bdf8}
   .badge.green{background:rgba(16,185,129,.18);color:#34d399}
   .badge.amber{background:rgba(245,158,11,.18);color:#fbbf24}
   .badge.red{background:rgba(239,68,68,.18);color:#f87171}
   .badge.slate{background:#1e293b;color:#cbd5e1}
   .list-col{display:flex;flex-direction:column;gap:5px}
   .list-item{text-align:left;background:#0b1120;border:1px solid #1e293b;color:var(--txt);padding:8px 10px;border-radius:7px;cursor:pointer}
   .list-item.active,.list-item:hover{border-color:var(--blue);background:#131f35}
   .row-between{display:flex;justify-content:space-between;align-items:center;gap:8px}
   .pill-row{display:flex;flex-wrap:wrap;gap:5px;align-items:center}
   .pill{background:#1e293b;color:#cbd5e1;padding:2px 7px;border-radius:5px;font-size:11px}
   .pill-btn{background:#172554;color:#93c5fd;border:1px solid #1e3a8a;padding:2px 8px;border-radius:5px;font-size:11px;cursor:pointer;font-family:monospace}
   .link-btn{background:none;border:none;color:#38bdf8;cursor:pointer;padding:0;font-size:12.5px}
   .kv-grid{display:grid;grid-template-columns:165px 1fr;gap:5px 10px;margin:0}
   .kv-grid dt{color:var(--muted);font-family:monospace;font-size:11.5px}
   .kv-grid dd{margin:0;word-break:break-word;font-family:monospace;font-size:11.5px}
   .data-table{width:100%;border-collapse:collapse;font-size:11.5px}
   .data-table th,.data-table td{text-align:left;padding:6px 8px;border-bottom:1px solid #1e293b}
   .data-table th{color:var(--muted);font-weight:600}
   .code-box{background:#060911;border:1px solid #1e293b;border-radius:7px;padding:10px;margin:0;overflow-x:auto;font-family:'SFMono-Regular',ui-monospace,Menlo,monospace;font-size:11.5px;color:#e2e8f0}
   .doc-box{background:#0b1120;border-left:3px solid var(--blue);padding:8px 10px;margin:0;color:#cbd5e1}
   .form-stack{display:flex;flex-direction:column;gap:8px}
   .row-form{display:flex;gap:8px;flex-wrap:wrap}
   .flex-1{flex:1;min-width:180px}
   .inp{background:#090d16;border:1px solid #334155;color:#fff;padding:7px 9px;border-radius:6px;font-size:12px;width:100%}
   .row-form .inp{width:auto}
   .notice{padding:8px 10px;border-radius:7px;margin-top:8px;font-size:12px}
   .notice.green{background:rgba(16,185,129,.15);border:1px solid #059669;color:#6ee7b7}
   .notice.red{background:rgba(239,68,68,.15);border:1px solid #b91c1c;color:#fca5a5}
   .eval-card{background:#0b1120;border:1px solid #1e293b;border-radius:7px;padding:8px;display:flex;flex-direction:column;gap:5px}
   .green-text{color:#34d399} .red-text{color:#f87171}
   .log-box{max-height:220px;overflow:auto;display:flex;flex-direction:column;gap:3px;font-family:monospace;font-size:11px}
   .log-line{color:#94a3b8;padding:2px 0;border-bottom:1px solid #0f172a}
   .log-line.red{color:#f87171}")

(defn- page-shell [st]
  (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>Seon — Native Jolt System Explorer</title>"
       "<style>" explorer-css "</style>"
       "<script type=\"module\" src=\"/datastar.js\"></script>"
       "</head><body>"
       (hiccup->html (render-app-root st))
       "<div style=\"display:none\" data-init=\"@get('/events', {retry: 'always', retryMaxCount: Infinity, openWhenHidden: true})\"></div>"
       "</body></html>"))

;; --- Query & Form Parsing Helpers ---

(defn- parse-kv-pairs [s]
  (if (str/blank? s)
    {}
    (into {}
          (keep (fn [part]
                  (let [[k v] (str/split part #"=" 2)]
                    (when k
                      [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                       (java.net.URLDecoder/decode (or v "") "UTF-8")]))))
          (str/split s #"&"))))

(defn- call-server-sync! [server-in-ch req]
  (let [rch (a/chan 1)]
    (a/>!! server-in-ch {:req req :reply-ch rch})
    (first (a/alts!! [rch (a/timeout 25000)]))))

(defn- parse-kw-input [^String s default-kw]
  (let [t (str/trim (str s))]
    (cond
      (str/blank? t) default-kw
      (str/starts-with? t ":") (edn/read-string t)
      :else (keyword t))))

;; --- Persistent HTTP Listener (Survives Power-Loss Flow Reboots with Live SSE Tabs!) ---

(defn start-http-listener!
  "Starts a persistent `ring-chez` HTTP listener whose handler delegates to the currently active `:web` proc."
  [port]
  (let [delegate (volatile! nil)
        sse-tabs (volatile! #{})
        handler (fn [req]
                  (if (= [:get "/events"] [(:request-method req) (:uri req)])
                    (let [ch (a/chan (a/sliding-buffer 16))]
                      (vswap! sse-tabs conj ch)
                      (when-let [d @delegate]
                        ((:on-new-tab d) ch))
                      {:status 200
                       :headers {"Content-Type" "text/event-stream"
                                 "Cache-Control" "no-cache"
                                 "Connection" "keep-alive"}
                       :body ch})
                    (if-let [d @delegate]
                      ((:handle d) req)
                      {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon rebooting..."})))
        srv (when (not= 0 port)
              (adapter/run-server handler {:port (if (neg? port) 0 port) :host "127.0.0.1"}))
        bound-port (if srv (:port srv) 0)]
    {:server srv
     :port bound-port
     :handler handler
     :tabs-vol sse-tabs
     :set-delegate! (fn [d]
                      (vreset! delegate d)
                      (when d ((:broadcast! d))))
     :stop! (fn []
              (doseq [ch @sse-tabs] (a/close! ch))
              (vreset! sse-tabs #{})
              (when srv
                (try (adapter/stop-server srv {:drain-timeout-ms 1500}) (catch Throwable _))))}))

;; --- The `:web` Flow Proc ---

(defn- broadcast-frame! [tabs-vol st]
  (when tabs-vol
    (let [frame (datastar-patch-event (render-app-root st))]
      (doseq [ch @tabs-vol]
        (when-not (a/offer! ch frame)
          nil)))))

(def ^:private datastar-js-text
  (delay
    (let [f (io/file store/checkout-root "resources/public/datastar.js")]
      (if (.isFile f) (slurp f) "// datastar.js missing"))))

(defn web-proc
  ([] {:params {:port "HTTP port (0 disables)"
                :listener "Persistent HTTP listener map"
                :conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :repo-dir "Git repository dir"
                :store-path "LMDB store path"
                :fork-parent-pid "PID of 1-thread Fork Parent"
                :fork-parent-ceiling "Chez GC ceiling of Fork Parent"
                :server-in-ch "Channel to :server"
                :model-ctrl-ch "Channel to :model"
                :report-chan "Flow report channel"
                :error-chan "Flow error channel"
                :flow-fn "0-arg fn returning live Flow graph"
                :on-power-loss "0-arg callback for Power-Loss reboot"
                :boot-seq "Boot sequence integer"}
       :ins {:db "Commit notices from :transactor"
             :stream "Streaming model/agent notices"}
       :outs {}
       :workload :io
       :doc "Datastar + pure Hiccup System Explorer UI & `/op` EDN endpoint."
       :ping-map-fn (fn [st]
                      {:port (:bound-port st)
                       :active-tab (:active-tab st)
                       :frames (:frames st)})})
  ([{:keys [port listener conn cfg repo-dir store-path fork-parent-pid fork-parent-ceiling
            server-in-ch report-chan error-chan flow-fn on-power-loss boot-seq]}]
   (let [ui-ch (a/chan 64)
         own-listener (when (and (nil? listener) (pos? (or port 0)))
                        (start-http-listener! port))
         eff-listener (or listener own-listener)
         tabs-vol (:tabs-vol eff-listener)
         st0 {:conn conn
              :cfg cfg
              :repo-dir repo-dir
              :store-path store-path
              :fork-parent-pid fork-parent-pid
              :fork-parent-ceiling fork-parent-ceiling
              :server-in-ch server-in-ch
              :flow-fn flow-fn
              :on-power-loss on-power-loss
              :boot-seq (or boot-seq 1)
              :bound-port (:port eff-listener 0)
              :own-listener own-listener
              :tabs-vol tabs-vol
              :active-tab :flow
              :selected-proc :transactor
              :selected-ns nil
              :selected-var nil
              :selected-schema nil
              :selected-branch nil
              :selected-agent nil
              :repl-branch ":branch/explorer"
              :last-code ""
              :reports []
              :errors []
              :frames 0}]
     (when eff-listener
       ((:set-delegate! eff-listener)
        {:on-new-tab (fn [ch]
                       (a/put! ui-ch {:ev :new-tab :ch ch}))
         :broadcast! (fn []
                       (a/put! ui-ch {:ev :broadcast}))
         :handle
         (fn [req]
           (let [m (:request-method req)
                 uri (:uri req)]
             (cond
               (and (= m :get) (= uri "/datastar.js"))
               {:status 200
                :headers {"Content-Type" "text/javascript; charset=utf-8"
                          "Cache-Control" "public, max-age=3600"}
                :body @datastar-js-text}

               (and (= m :get) (= uri "/"))
               (let [p (promise)]
                 (a/put! ui-ch {:ev :render-page :reply p})
                 {:status 200
                  :headers {"Content-Type" "text/html; charset=utf-8"}
                  :body (deref p 5000 "Timeout rendering page")})

               (and (= m :post) (= uri "/op"))
               (let [body-str (if (string? (:body req)) (:body req) (slurp (:body req)))
                     edn-req (edn/read-string body-str)
                     ans (call-server-sync! server-in-ch edn-req)]
                 {:status 200
                  :headers {"Content-Type" "application/edn; charset=utf-8"}
                  :body (pr-str ans)})

               (and (= m :post) (= uri "/ui/act"))
               (let [qmap (parse-kv-pairs (:query-string req))
                     body-str (cond
                                (nil? (:body req)) ""
                                (string? (:body req)) (:body req)
                                :else (slurp (:body req)))
                     form-map (parse-kv-pairs body-str)
                     op-kw (keyword (:op qmap "noop"))
                     args (when-let [as (:args qmap)] (edn/read-string as))
                     p (promise)]
                 (a/put! ui-ch {:ev :ui-act :op op-kw :args args :form form-map :reply p})
                 {:status 200
                  :headers {"Content-Type" "text/event-stream; charset=utf-8"
                            "Cache-Control" "no-cache"}
                  :body (deref p 25000 "")})

               :else
               {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found"})))}))
     (assoc st0
            ::flow/in-ports (cond-> {:ui ui-ch}
                              report-chan (assoc :report report-chan)
                              error-chan  (assoc :error error-chan)))))
  ([st transition]
   (when (and (= transition ::flow/stop) (:own-listener st))
     ((:stop! (:own-listener st))))
   st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (case cid
       :report
       (let [st' (-> st
                     (update :reports #(vec (take 40 (cons msg %))))
                     (update :frames inc))]
         (broadcast-frame! (:tabs-vol st') st')
         [st' nil])

       :error
       (let [st' (-> st
                     (update :errors #(vec (take 20 (cons msg %))))
                     (update :frames inc))]
         (broadcast-frame! (:tabs-vol st') st')
         [st' nil])

       (:db :stream)
       (let [st' (update st :frames inc)]
         (broadcast-frame! (:tabs-vol st') st')
         [st' nil])

       :ui
       (case (:ev msg)
         :new-tab
         (let [st' (update st :frames inc)]
           (a/offer! (:ch msg) (datastar-patch-event (render-app-root st')))
           [st' nil])

         :broadcast
         (let [st' (update st :frames inc)]
           (broadcast-frame! (:tabs-vol st') st')
           [st' nil])

         :render-page
         (do (deliver (:reply msg) (page-shell st))
             [st nil])

         :ui-act
         (let [{:keys [op args form reply]} msg
               server-ch (:server-in-ch st)
               st'
               (case op
                 :switch-tab    (assoc st :active-tab (:tab args :flow))
                 :select-proc   (assoc st :selected-proc (:proc args :transactor))
                 :select-ns     (assoc st :selected-ns (:ns args) :selected-var nil)
                 :select-var    (assoc st :selected-ns (:ns args) :selected-var (:var args))
                 :jump-to-var   (let [v (:var args)]
                                  (assoc st
                                         :active-tab :program
                                         :selected-ns (symbol (namespace v))
                                         :selected-var v))
                 :select-schema (assoc st :selected-schema (:key args))
                 :select-branch (assoc st :selected-branch (:branch args) :branch-feedback nil)
                 :select-agent  (assoc st :selected-agent (:agent-id args))

                 :power-loss
                 (do (when-let [pl (:on-power-loss st)]
                       (a/thread (pl)))
                     st)

                 :register-schema
                 (let [k (parse-kw-input (:key form) :custom/item)
                       ans (call-server-sync! server-ch
                                              (cond-> {:op :register-schema
                                                       :key k
                                                       :form (:form form "")}
                                                (not (str/blank? (:doc form))) (assoc :doc (:doc form))
                                                (not (str/blank? (:examples form))) (assoc :examples (:examples form))))]
                   (assoc st
                          :selected-schema (if (:ok? ans) (:key ans) (:selected-schema st))
                          :schema-feedback {:ok? (:ok? ans)
                                            :text (if (:ok? ans)
                                                    (str "Registered " (:key ans) " — seed=42 sample: " (:sample ans))
                                                    (str "Rejected: " (:error ans)))}))

                 :run-tests
                 (let [b (:branch args :main)
                       ans (call-server-sync! server-ch {:op :test :branch b})]
                   (assoc st
                          :branch-feedback {:ok? (:ok? ans)
                                            :text (str "Tests on " b ": " (if (:ok? ans) "PASS" "FAIL")
                                                       " (" (:ran ans 0) " ran, " (:cached ans 0) " cached"
                                                       (when (:boot-affected? ans) " · BOOT-AFFECTED") ")")}))

                 :merge-branch
                 (let [b (:branch args)
                       ans (call-server-sync! server-ch {:op :merge :branch b})]
                   (assoc st
                          :branch-feedback {:ok? (:ok? ans)
                                            :text (if (:ok? ans)
                                                    (str "Merged " b " -> :main @ tx " (:tx ans)
                                                         " (" (count (:touched-paths ans)) " files)")
                                                    (str "Conflict: " (pr-str (:conflicts ans))))}))

                 :spawn-demo-task
                 (let [title (str/trim (:title form "Build feature"))
                       role (keyword (:role form "coordinator"))
                       tid (keyword "demo" (str "t" (mod (System/currentTimeMillis) 100000)))
                       _ (call-server-sync! server-ch {:op :create-task
                                                       :id tid
                                                       :title title
                                                       :spawn-agent? true
                                                       :role role})]
                   st)

                 :toggle-pause
                 (do (call-server-sync! server-ch {:op :toggle-pause-agent :agent-id (:agent-id args)})
                     st)

                 :eval-repl
                 (let [b (parse-kw-input (:branch form) :branch/explorer)
                       code-str (:code form "")
                       ans (call-server-sync! server-ch {:op :eval :branch b :code code-str :keep-warm? true})]
                   (assoc st
                          :repl-branch (str b)
                          :last-code code-str
                          :eval-feedback ans))

                 st)
               st2 (update st' :frames inc)
               patch (datastar-patch-event (render-app-root st2))]
           (deliver reply patch)
           (broadcast-frame! (:tabs-vol st2) st2)
           [st2 nil])

         [st nil])))))
