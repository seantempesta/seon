(ns seon.web
  "The `:web` Flow proc (`:io` workload) — Datastar + Normal Callable Clojure Forms (`^:seon/callable`):
   - Zero `:ui/op` / `:ui/args` switch tables! Interactive Hiccup elements declare normal Clojure call
     forms (`{:seon/call '(seon.web/select-var! seon.store seon.store/open!)}`), dispatched by one
     15-line `invoke-callable-form!` reader over vars marked `^:seon/callable`.
   - Serves `resources/public/datastar.js` (`33 KB`) and streams `datastar-patch-elements` over SSE."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.datafy :as datafy]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [ring-chez.adapter :as adapter]
            [seon.code :as code]
            [seon.git :as git]
            [seon.merge :as merge]
            [seon.reconciler :as reconciler]
            [seon.server :as server]
            [seon.store :as store]))

;; --- Pure Hiccup -> HTML Serializer with Normal Clojure Call Forms (`:seon/call`) ---

(defn esc [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")
      (str/replace "'" "&#39;")))

(defn url-enc [^String s]
  (java.net.URLEncoder/encode (str s) "UTF-8"))

(defn raw-html [s] {::raw (str s)})

(defn- expand-call-attrs [tag attrs]
  (if-let [call-form (:seon/call attrs)]
    (let [qs (str "form=" (url-enc (pr-str call-form)))
          clean (dissoc attrs :seon/call)]
      (if (= tag "form")
        (assoc clean (keyword "data-on:submit")
               (str "evt.preventDefault(); @post('/call?" qs "', {contentType: 'form'})"))
        (assoc clean (keyword "data-on:click")
               (str "@post('/call?" qs "')"))))
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
              attrs (when has-attrs? (expand-call-attrs tag (second node)))
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
  ([hiccup-or-html] (datastar-patch-event hiccup-or-html nil nil))
  ([hiccup-or-html selector mode]
   (let [html (if (string? hiccup-or-html) hiccup-or-html (hiccup->html hiccup-or-html))]
     (str "event: datastar-patch-elements\n"
          (when selector (str "data: selector " selector "\n"))
          (when mode (str "data: mode " mode "\n"))
          (apply str (for [line (str/split-lines html) :when (not (str/blank? line))]
                       (str "data: elements " line "\n")))
          "\n"))))

;; --- Normal Clojure Form Dispatcher (`^:seon/callable`, Zero Switch Tables!) ---

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

(defn ^:seon/callable switch-tab! [st tab]
  (assoc st :active-tab tab))

(defn ^:seon/callable select-proc! [st proc]
  (assoc st :selected-proc proc))

(defn ^:seon/callable select-ns! [st nsym]
  (assoc st :selected-ns nsym :selected-var nil))

(defn ^:seon/callable select-var! [st nsym vsym]
  (assoc st :active-tab :program :selected-ns nsym :selected-var vsym))

(defn ^:seon/callable select-schema! [st skey]
  (assoc st :active-tab :schemas :selected-schema skey))

(defn ^:seon/callable select-branch! [st branch]
  (assoc st :selected-branch branch :branch-feedback nil))

(defn ^:seon/callable select-agent! [st agent-id]
  (assoc st :selected-agent agent-id))

(defn ^:seon/callable power-loss-reboot! [st]
  (when-let [pl (:on-power-loss st)]
    (a/thread (pl)))
  st)

(defn ^:seon/callable run-tests! [st branch]
  (let [ans (call-server-sync! (:server-in-ch st) {:op :test :branch branch})]
    (assoc st
           :branch-feedback {:ok? (:ok? ans)
                             :text (str "Tests on " branch ": " (if (:ok? ans) "PASS" "FAIL")
                                        " (" (:ran ans 0) " ran, " (:cached ans 0) " cached"
                                        (when (:boot-affected? ans) " · BOOT-AFFECTED") ")")})))

(defn ^:seon/callable merge-branch! [st branch]
  (let [ans (call-server-sync! (:server-in-ch st) {:op :merge :branch branch})]
    (assoc st
           :branch-feedback {:ok? (:ok? ans)
                             :text (if (:ok? ans)
                                     (str "Merged " branch " -> :main @ tx " (:tx ans)
                                          " (" (count (:touched-paths ans)) " files)")
                                     (str "Conflict: " (pr-str (:conflicts ans))))})))

(defn ^:seon/callable toggle-pause! [st agent-id]
  (call-server-sync! (:server-in-ch st) {:op :toggle-pause-agent :agent-id agent-id})
  st)

(defn ^:seon/callable register-schema-form! [st form]
  (let [k (parse-kw-input (:key form) :seon.custom/item)
        ans (call-server-sync! (:server-in-ch st)
                               (cond-> {:op :register-schema
                                        :key k
                                        :form (:form form "")}
                                 (not (str/blank? (:doc form))) (assoc :doc (:doc form))
                                 (not (str/blank? (:examples form))) (assoc :examples (:examples form))))]
    (assoc st
           :selected-schema (if (:ok? ans) (:key ans) (:selected-schema st))
           :schema-feedback {:ok? (:ok? ans)
                             :text (if (:ok? ans)
                                     (str "Registered " (:key ans)
                                          (when (seq (:derived-attributes ans))
                                            (str " + derived Datahike attr " (:derived-attributes ans)))
                                          " — seed=42 sample: " (:sample ans))
                                     (str "Rejected: " (:error ans)))})))

(defn ^:seon/callable spawn-task-form! [st form]
  (let [title (str/trim (:title form "Build feature"))
        role (keyword (:role form "coordinator"))
        scope (when-not (str/blank? (:scope form)) (symbol (str/trim (:scope form))))
        tid (keyword "seon.demo" (str "t" (mod (System/currentTimeMillis) 100000)))]
    (call-server-sync! (:server-in-ch st)
                       (cond-> {:op :create-task
                                :id tid
                                :title title
                                :spawn-agent? true
                                :role role}
                         scope (assoc :scope scope)))
    st))

(defn ^:seon/callable eval-repl-form! [st form]
  (let [b (parse-kw-input (:branch form) :branch/explorer)
        code-str (:code form "")
        ans (call-server-sync! (:server-in-ch st) {:op :eval :branch b :code code-str :keep-warm? true})]
    (assoc st :repl-branch (str b) :last-code code-str :eval-feedback ans)))

(defn invoke-callable-form!
  "Evaluates a normal Clojure call form `(qualified-fn-sym & args)` safely:
   - Refuses as structured data unless `qualified-fn-sym` resolves to a `^:seon/callable` var!
   - Passes `st` as first arg and `form-fields` (if non-empty) as trailing arg."
  [st call-form form-fields]
  (if-not (and (seq? call-form) (qualified-symbol? (first call-form)))
    {:ok? false :error :seon.web/malformed-call :form call-form :st st}
    (let [sym (first call-form)
          v (resolve sym)]
      (if-not (and v (:seon/callable (meta v)))
        {:ok? false
         :error :seon.web/not-callable
         :message (str sym " is not marked ^:seon/callable")
         :fn sym
         :st st}
        (let [args (cond-> (vec (rest call-form))
                     (seq form-fields) (conj form-fields))
              res (apply @v st args)]
          {:ok? true
           :st (if (and (map? res) (contains? res :conn)) res st)
           :result (when-not (and (map? res) (contains? res :conn)) res)})))))

;; --- Schema-Driven Data Visualization (`render-value`) ---

(defn render-value [db v]
  (cond
    (nil? v) [:span {:class "muted"} "nil"]

    (and (map? v) (:seon/code v))
    [:pre {:class "code-box"} [:code (:seon/code v)]]

    (and db (meta v) (:seon/schema (meta v)))
    (let [sk (:seon/schema (meta v))
          rsym (:seon.schema/render (d/pull db '[:seon.schema/render] [:seon.schema/key sk]))
          rfn (when rsym (try (requiring-resolve rsym) (catch Throwable _ nil)))]
      (if rfn
        (try (rfn {:db db} v)
             (catch Throwable ex [:div {:class "badge red"} (str "Render error in " rsym ": " (.getMessage ex))]))
        (render-value nil (with-meta v nil))))

    (and (sequential? v) (>= (count v) 2) (every? map? v)
         (>= (count (apply set/intersection (map #(set (keys %)) (take 5 v)))) 2))
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
         [:span {:class "pill"} (pr-str item)])])

    :else
    [:span (str v)]))

;; --- SVG Flow Graph Renderer ---

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
            :seon/call (list 'seon.web/select-proc! pid)}
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

;; --- Explorer View Builders ---

(defn- render-header [st db pings]
  (let [boot-seq (:boot-seq st 1)
        git-sha (:seon.git/commit (d/pull db '[:seon.git/commit] [:seon.git/repo :main]))
        forms-n (count (d/datoms db :aevt :seon.code.form/key))
        vars-n (count (d/datoms db :aevt :seon.code.var/name))
        schemas-n (count (d/datoms db :aevt :seon.schema/key))
        dh-attrs-n (count (:schema db))
        docs-n (count (d/datoms db :aevt :seon.code.doc/id))
        green-n (count (filter #(true? (:v %)) (d/datoms db :aevt :seon.test/pass?)))
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
       [:span {:class "stat-chip"} [:b schemas-n] (str " schemas (" dh-attrs-n " DH attrs)")]
       [:span {:class "stat-chip"} [:b docs-n] " docs"]
       [:span {:class "stat-chip green"} [:b green-n] " cached green"]
       [:button {:type "button" :class "btn danger" :seon/call '(seon.web/power-loss-reboot!)}
        "⚡ Power-Loss Reboot"]]]
     [:nav {:class "tabs-nav"}
      (for [[tid label] [[:flow     "1. Flow & OS Topology"]
                         [:program  "2. Program & Co-Located Scopes"]
                         [:schemas  "3. Malli -> Datahike Schemas"]
                         [:branches "4. Branches & 3-Way Git Diff"]
                         [:agents   "5. Scoped Agents, Config & COW REPL"]]]
        [:button {:type "button"
                  :class (str "tab-btn" (when (= tab tid) " active"))
                  :seon/call (list 'seon.web/switch-tab! tid)}
         label])]]))

(defn- render-flow-tab [st db df pings]
  (let [sel-proc (:selected-proc st :transactor)
        pinfo (get pings sel-proc)
        pstate (if (= sel-proc :web)
                 {:port (:bound-port st) :frames (:frames st)}
                 (::flow/state pinfo))
        live-ags (get-in pings [:agents ::flow/state :live] {})
        tests (->> (d/datoms db :aevt :seon.test/digest)
                   (map #(d/pull db '[:seon.test/digest :seon.test/var :seon.test/kind
                                      :seon.test/pass? :seon.test/ms :seon.test/ticks :seon.test/report] (:e %)))
                   (sort-by #(str (:seon.test/var %)))
                   (take 25))]
    [:div {:id "tab-panel" :class "grid-2"}
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 "Static 8-Proc clojure.core.async.flow Graph"]
        [:span {:class "muted mono"} "click any proc node (`:seon/call`)"]]
       (render-flow-svg df pings (:frames st 0))]
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 "Reach-Cached clojure.test & Seed=42 Generative Verdicts"]
        [:button {:type "button" :class "btn primary" :seon/call '(seon.web/run-tests! :main)}
         "▶ Run :main Reach Tests"]]
       (if (empty? tests)
         [:p {:class "muted"} "No test digests recorded yet."]
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
                      (str " · " (quot (:seon.test/ticks t) 1000) "k ticks")))]])]])]]
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
  (let [nss (->> (d/datoms db :aevt :seon.code.ns/name)
                 (map #(d/pull db '[:seon.code.ns/name :seon.code.ns/path :seon.code.ns/doc] (:e %)))
                 (sort-by #(str (:seon.code.ns/name %)))
                 vec)
        sel-ns (or (:selected-ns st) (:seon.code.ns/name (first nss)))
        vars-in-ns (when sel-ns (code/ns-vars db sel-ns))
        co-schemas (when sel-ns (:schemas (code/scope-view db sel-ns)))
        sel-var (or (:selected-var st) (:seon.code.var/name (first vars-in-ns)))
        var-detail (when sel-var (server/explore-var db sel-var))]
    [:div {:id "tab-panel" :class "grid-3"}
     [:div {:class "card scroll-pane"}
      [:div {:class "card-head"} [:h2 (str "Namespaces (" (count nss) ")")]]
      [:div {:class "list-col"}
       (for [n nss]
         [:button {:type "button"
                   :class (str "list-item" (when (= sel-ns (:seon.code.ns/name n)) " active"))
                   :seon/call (list 'seon.web/select-ns! (:seon.code.ns/name n))}
          [:div {:class "mono strong"} (str (:seon.code.ns/name n))]
          [:div {:class "muted tiny"} (:seon.code.ns/path n)]])]]
     [:div {:class "card scroll-pane"}
      [:div {:class "card-head"} [:h2 (str "Vars & Co-Located Schemas in " (or sel-ns "—"))]]
      (when (seq co-schemas)
        [:div {:style "margin-bottom:10px"}
         [:h3 (str "Co-Located Schemas (" (count co-schemas) ")")]
         [:div {:class "pill-row"}
          (for [sk co-schemas]
            [:button {:type "button" :class "pill-btn"
                      :seon/call (list 'seon.web/select-schema! sk)}
             (str sk)])]])
      [:h3 (str "Vars (" (count vars-in-ns) ")")]
      [:div {:class "list-col"}
       (for [v vars-in-ns]
         [:button {:type "button"
                   :class (str "list-item" (when (= sel-var (:seon.code.var/name v)) " active"))
                   :seon/call (list 'seon.web/select-var! sel-ns (:seon.code.var/name v))}
          [:div {:class "row-between"}
           [:span {:class "mono strong"} (name (:seon.code.var/name v))]
           [:span {:class "pill-row"}
            (when (:seon.code.var/test? v)
              [:span {:class "badge green"} (if (:seon.code.var/platform-test? v) "platform-test" "deftest")])
            (when (:seon.code.var/schema v) [:span {:class "badge blue"} "malli"])
            (when (:seon.code.var/ticks-budget v) [:span {:class "badge amber"} "cost"])]]
          (when (:seon.code.var/arglists v)
            [:div {:class "mono muted tiny"} (pr-str (:seon.code.var/arglists v))])])]]
     [:div {:class "card scroll-pane"}
      [:div {:class "card-head"} [:h2 (str "Var Fact Sheet — " (or sel-var "Select a var"))]]
      (if-not var-detail
        [:p {:class "muted"} "Select a namespace and var to inspect its indexed Datahike facts."]
        [:div {:class "stack-sm"}
         (when (:seon.code.var/doc var-detail)
           [:p {:class "doc-box"} (:seon.code.var/doc var-detail)])
         (render-value db (select-keys var-detail
                                       [:seon.code.var/name :seon.code.var/arglists :seon.code.var/schema
                                        :seon.code.var/schema-refs :seon.code.var/ticks-budget
                                        :seon.code.var/bytes-budget :seon.code.var/big-o
                                        :seon.code.var/test? :seon.code.var/platform-test? :hash]))
         [:div
          [:h3 "Direct Callers (1 AVET seek on :seon.code.var/refs)"]
          (if (empty? (:callers var-detail))
            [:span {:class "muted tiny"} "No callers in Datahike."]
            [:div {:class "pill-row"}
             (for [c (:callers var-detail)]
               [:button {:type "button" :class "pill-btn"
                         :seon/call (list 'seon.web/select-var! (symbol (namespace c)) c)}
                (str c)])])]
         [:div
          [:h3 "Direct Callees (:seon.code.var/refs)"]
          (if (empty? (:seon.code.var/refs var-detail))
            [:span {:class "muted tiny"} "None."]
            [:div {:class "pill-row"}
             (for [r (sort (:seon.code.var/refs var-detail))]
               [:button {:type "button" :class "pill-btn"
                         :seon/call (list 'seon.web/select-var! (symbol (namespace r)) r)}
                (str r)])])]
         [:div
          [:h3 "Exact Stored Source (:seon.code.form/src)"]
          [:pre {:class "code-box"} [:code (or (:src var-detail) "")]]]])]]))

(defn- render-schemas-tab [st db]
  (let [schemas (->> (d/datoms db :aevt :seon.schema/key)
                     (map #(d/pull db '[:seon.schema/key :seon.schema/form :seon.schema/doc] (:e %)))
                     (sort-by #(str (:seon.schema/key %)))
                     vec)
        sel-key (or (:selected-schema st) (:seon.schema/key (first schemas)))
        detail (when sel-key (server/explore-schema db sel-key))]
    [:div {:id "tab-panel" :class "grid-2"}
     [:div {:class "stack"}
      [:div {:class "card scroll-pane"}
       [:div {:class "card-head"} [:h2 (str "Co-Located Multi-Segment Malli Schemas (" (count schemas) ")")]]
       [:div {:class "list-col"}
        (for [s schemas]
          [:button {:type "button"
                    :class (str "list-item" (when (= sel-key (:seon.schema/key s)) " active"))
                    :seon/call (list 'seon.web/select-schema! (:seon.schema/key s))}
           [:div {:class "row-between"}
            [:span {:class "mono strong"} (str (:seon.schema/key s))]
            (when (contains? (:schema db) (:seon.schema/key s))
              [:span {:class "badge green"} "DH attr"])]
           [:div {:class "mono muted tiny"} (pr-str (:seon.schema/form s))]])]]
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 "Register Schema (Auto-Derives Datahike Attribute + Seed=42 Check)"]]
       [:form {:class "form-stack" :seon/call '(seon.web/register-schema-form!)}
        [:input {:name "key" :placeholder ":seon.billing/amount (multi-segment co-located namespace!)" :required true :class "inp mono"}]
        [:input {:name "form" :placeholder "[:double {:seon.db/index true :min 0.0 :max 10000.0}]" :required true :class "inp mono"}]
        [:input {:name "doc" :placeholder "Description of this schema contract" :class "inp"}]
        [:input {:name "examples" :placeholder "Optional EDN examples vector if custom host pred" :class "inp mono"}]
        [:button {:type "submit" :class "btn primary"} "Validate Seed=42 & Auto-Install Datahike Attr"]]
       (when-let [msg (:schema-feedback st)]
         [:div {:class (str "notice " (if (:ok? msg) "green" "red"))} (:text msg)])]]
     [:div {:class "card"}
      [:div {:class "card-head"} [:h2 (str "Schema & Derived Datahike Inspector — " (or sel-key "—"))]]
      (if-not detail
        [:p {:class "muted"} "Select a schema on the left."]
        [:div {:class "stack-sm"}
         (when (:seon.schema/doc detail) [:p {:class "doc-box"} (:seon.schema/doc detail)])
         (render-value db (select-keys detail [:seon.schema/key :seon.schema/form
                                               :datahike-attribute :seon.schema/examples :seon.schema/render]))
         [:div
          [:h3 "Deterministic Generated Samples (seed = 42)"]
          [:div {:class "pill-row"}
           (for [smp (:samples detail)]
             [:span {:class "pill mono"} smp])]]
         [:div
          [:h3 "Functions Using This Schema (1 AVET seek on :seon.code.var/schema-refs)"]
          (if (empty? (:used-by detail))
            [:p {:class "muted tiny"} "No functions reference this schema yet."]
            [:div {:class "pill-row"}
             (for [u (:used-by detail)]
               [:button {:type "button" :class "pill-btn"
                         :seon/call (list 'seon.web/select-var! (symbol (namespace u)) u)}
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
                    :seon/call (list 'seon.web/select-branch! b)}
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
           [:button {:type "button" :class "btn primary" :seon/call (list 'seon.web/run-tests! sel-b)}
            "▶ Run Reach + Seed=42 Tests"]
           [:button {:type "button" :class "btn success" :seon/call (list 'seon.web/merge-branch! sel-b)}
            "⇄ 3-Way Merge -> :main"]])]
       (when-let [fb (:branch-feedback st)]
         [:div {:class (str "notice " (if (:ok? fb) "green" "red"))} (:text fb)])
       (cond
         (= sel-b :main)
         [:p {:class "muted"} "Select or create a non-:main branch to inspect its 3-way diff."]

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
        agents (->> (d/datoms db :aevt :seon.agent/id)
                    (keep #(reconciler/derive-agent-status db store live-ags #{} #{} (:v %)))
                    (sort-by #(str (:id %)))
                    vec)
        sel-aid (or (:selected-agent st) (:id (first agents)))
        sel-ag (some #(when (= sel-aid (:id %)) %) agents)
        cfg-info (store/config-map db sel-aid)
        branch-for-evals (or (:branch sel-ag) (parse-kw-input (:repl-branch st) :branch/explorer))
        eval-db (or (try (store/head (:conn st) branch-for-evals) (catch Throwable _ nil)) db)
        evals (->> (d/datoms eval-db :aevt :seon.agent.eval/id)
                   (map #(d/pull eval-db '[:seon.agent.eval/turn :seon.agent.eval/call-index
                                           :seon.agent.eval/call-id :seon.agent.eval/agent
                                           :seon.agent.eval/code :seon.agent.eval/value
                                           :seon.agent.eval/error :seon.agent.eval/ticks :seon.agent.eval/ms] (:e %)))
                   (sort-by (juxt #(unchecked-negate (or (:seon.agent.eval/turn %) 0))
                                  #(or (:seon.agent.eval/call-index %) 0)))
                   (take 15)
                   vec)]
    [:div {:id "tab-panel" :class "grid-2"}
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 "Spawn Scoped Coordinator / Worker Subtree"]]
       [:form {:class "form-stack" :seon/call '(seon.web/spawn-task-form!)}
        [:div {:class "row-form"}
         [:input {:name "title" :placeholder "Goal, e.g. Implement billing discounts" :required true :class "inp flex-1"}]
         [:input {:name "scope" :placeholder "Scope ns, e.g. seon.app.billing" :class "inp mono"}]]
        [:div {:class "row-form"}
         [:select {:name "role" :class "inp"}
          [:option {:value "coordinator"} ":coordinator (trees 2 child scopes)"]
          [:option {:value "worker"} ":worker (1 scoped branch)"]]
         [:button {:type "submit" :class "btn primary"} "+ Spawn Scoped Agent"]]]]
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
                        :seon/call (list 'seon.web/select-agent! (:id a))}
               (str (:id a))]
              [:div {:class "pill-row"}
               (when (:scope a) [:span {:class "pill mono"} (str "scope: " (:scope a))])
               [:span {:class (str "badge " (case (:status a)
                                              :merged "green"
                                              :running "blue"
                                              :paused "amber"
                                              "slate"))}
                (name (:status a))]
               (when (number? (:pid li))
                 [:span {:class "pill mono"} (str "PID " (:pid li) " · " (:mem-mb li 0) "MB")])
               [:button {:type "button" :class "pill-btn"
                         :seon/call (list 'seon.web/toggle-pause! (:id a))}
                (if (:paused? a) "Resume" "Pause")]]]
             [:div {:class "mono muted tiny"}
              (str (:branch a) " @ tx " (:branch-tx a) " · evals: " (:evals a)
                   (when (:parent a) (str " · parent: " (:parent a))))]])])]
      [:div {:class "card"}
       [:div {:class "card-head"}
        [:h2 (str "Aero Config Inheritance — " (or sel-aid ":seon.config/root"))]
        (when (seq (:overrides cfg-info))
          [:span {:class "badge amber"} (str (count (:overrides cfg-info)) " agent override(s)")])]
       (render-value db (:effective cfg-info))]]
     [:div {:class "stack"}
      [:div {:class "card"}
       [:div {:class "card-head"} [:h2 "Persistent COW Branch REPL"]]
       [:form {:class "form-stack" :seon/call '(seon.web/eval-repl-form!)}
        [:input {:name "branch" :value (str (or (:repl-branch st) (:branch sel-ag) ":branch/explorer"))
                 :class "inp mono" :placeholder ":branch/explorer"}]
        [:textarea {:name "code" :rows "5" :class "inp mono"
                    :placeholder "(user/register-schema! :seon.pricing/rate [:double {:seon.db/index true :min 0.0 :max 1.0}])\n(ns seon.pricing)\n(defn net {:malli/schema [:=> [:cat [:double {:min 0.0}] :seon.pricing/rate] [:double {:min 0.0}]]} [p r] (* p (- 1.0 r)))\n(net 100.0 0.2)"}
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
       [:div {:class "card-head"} [:h2 (str "Recorded Evals on " branch-for-evals)]]
       (if (empty? evals)
         [:p {:class "muted"} "No evaluations recorded on this branch yet."]
         [:div {:class "stack-sm"}
          (for [e evals]
            [:div {:class "eval-card"}
             [:div {:class "row-between mono tiny muted"}
              [:span (str "turn #" (:seon.agent.eval/turn e)
                          (when (:seon.agent.eval/call-id e) (str " [" (:seon.agent.eval/call-id e) "]"))
                          " by " (:seon.agent.eval/agent e))]
              [:span (str (:seon.agent.eval/ms e 0) " ms · " (:seon.agent.eval/ticks e 0) " ticks")]]
             [:pre {:class "code-box"} [:code (:seon.agent.eval/code e "")]]
             (if (:seon.agent.eval/error e)
               [:div {:class "mono tiny red-text"} (str "ERROR: " (:seon.agent.eval/error e))]
               [:div {:class "mono tiny green-text"} (str "=> " (:seon.agent.eval/value e))])])])]]]))

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
   .grid-3{display:grid;grid-template-columns:250px 330px 1fr;gap:14px;align-items:start}
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
   .kv-grid{display:grid;grid-template-columns:190px 1fr;gap:5px 10px;margin:0}
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

(defn- parse-kv-pairs [s]
  (if (str/blank? s)
    {}
    (into {}
          (keep (fn [part]
                  (let [[k v] (str/split part #"=" 2)]
                    (when (seq k)
                      [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                       (java.net.URLDecoder/decode (or v "") "UTF-8")]))))
          (str/split s #"&"))))

;; --- Persistent HTTP Listener ---

(defn start-http-listener! [port]
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

(defn- broadcast-frame! [tabs-vol st]
  (when tabs-vol
    (let [frame (datastar-patch-event (render-app-root st))]
      (doseq [ch @tabs-vol]
        (a/offer! ch frame)))))

(def ^:private datastar-js-text
  (delay
    (let [f (io/file store/checkout-root "resources/public/datastar.js")]
      (if (.isFile f) (slurp f) "// datastar.js missing"))))

(defn web-proc
  ([] {:params {:port "HTTP port"
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
       :doc "Datastar + normal `^:seon/callable` Clojure form Explorer UI & `/op` EDN endpoint."
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
        {:on-new-tab (fn [ch] (a/put! ui-ch {:ev :new-tab :ch ch}))
         :broadcast! (fn [] (a/put! ui-ch {:ev :broadcast}))
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

               ;; Normal Clojure Call Route (`POST /call?form=(seon.web/select-var!+...)`)
               (and (= m :post) (= uri "/call"))
               (let [qmap (parse-kv-pairs (:query-string req))
                     body-str (cond
                                (nil? (:body req)) ""
                                (string? (:body req)) (:body req)
                                :else (slurp (:body req)))
                     form-fields (parse-kv-pairs body-str)
                     call-form (when-let [fs (:form qmap)] (edn/read-string fs))
                     p (promise)]
                 (a/put! ui-ch {:ev :call :call-form call-form :form-fields form-fields :reply p})
                 (let [ans (deref p 25000 {:ok? false :error :timeout})]
                   (if (:ok? ans)
                     {:status 200
                      :headers {"Content-Type" "text/event-stream; charset=utf-8"
                                "Cache-Control" "no-cache"}
                      :body (:patch ans)}
                     {:status 403
                      :headers {"Content-Type" "application/edn; charset=utf-8"}
                      :body (pr-str (dissoc ans :st))})))

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

         :call
         (let [{:keys [call-form form-fields reply]} msg
               res (invoke-callable-form! st call-form form-fields)]
           (if-not (:ok? res)
             (do (deliver reply res)
                 [st nil])
             (let [st2 (update (:st res) :frames inc)
                   patch (datastar-patch-event (render-app-root st2))]
               (deliver reply {:ok? true :patch patch})
               (broadcast-frame! (:tabs-vol st2) st2)
               [st2 nil])))

         [st nil])))))
