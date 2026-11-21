(ns seon.render.debug
  "Zero-cost Wire & System Debug view (`debug-view` + linear AEVT test verdicts)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [datahike.api :as d]
            [fipp.edn :as fipp]
            [seon.ctx :as ctx]
            [seon.health :as health]
            [seon.merge :as merge]
            [seon.model :as model]
            [seon.render :as render]
            [seon.store :as store]
            [seon.render.data :as rdata :refer [branch-db-for entry-agent-id]]
            [seon.render.wire :as rwire :refer [build-live-wire-request]]))

(defn wire-role-badge [role]
  (case role
    "system"    "badge amber"
    "user"      "badge blue"
    "assistant" "badge green"
    "tool"      "badge purple"
    "badge"))

(defn detect-xml-tags
  {:malli/schema [:=> [:cat :any] [:maybe [:vector :string]]]}
  [s]
  (if (string? s)
    (vec (distinct (map second (re-seq #"<(/?(?:session|agent|walk-context|walk-diff|message|branch-status|scoped-health|system-health|neighborhood-activity|live-viewport|tail-render-fault))\b" s))))
    nil))

(defn pretty-edn
  {:malli/schema [:=> [:cat :any] :string], :cost/ticks 5000000, :cost/bytes 16777216}
  [x]
(try
  (let [s (with-out-str (fipp/pprint x {:width 88 :print-length 40 :print-level 6}))]
    (if (> (count s) 12000) (str (subs s 0 12000) "\n… (truncated)") s))
  (catch Throwable _ (pr-str x))))

(defn- wire-message-row
  [idx pair viewport-screenshot n-wire]
  (if (= idx :omitted)
    [:div {:class "muted tiny mono" :style "padding:6px 10px"}
     (str "… " pair " middle wire messages omitted from Debug DOM (full " n-wire "-msg history in REPL)")]
    (let [[m toks] pair
          role (:role m)
          content (:content m)
          img-url (when (sequential? content)
                    (some #(when (= "image_url" (:type %)) (get-in % [:image_url :url])) content))
          text-body (if (sequential? content)
                      (str/join "\n" (map #(if (= "image_url" (:type %))
                                             (str "[IMAGE_URL: ephemeral live WKWebView viewport"
                                                  (when (:bytes viewport-screenshot)
                                                    (str " · " (quot (:bytes viewport-screenshot) 1024) " KB"))
                                                  "]")
                                             (:text %))
                                          content))
                      (str content))
          xml-tags (detect-xml-tags text-body)
          preview (let [line (first (remove str/blank? (str/split-lines text-body)))]
                    (if (> (count (str line)) 120) (str (subs line 0 120) "…") (str line)))]
      [:details {:id (str "wm-" idx) :class "wire-row" :open (or (< idx 4) (some? img-url))}
       [:summary {:class "wire-summary"}
        [:span {:class "muted tiny mono"} (str "#" idx)]
        [:span {:class (wire-role-badge role)} (str/upper-case role)]
        (for [tag xml-tags]
          [:span {:class "badge mono xml-tag"} (str "<" tag ">")])
        (when img-url [:span {:class "badge amber mono"} "image_url"])
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
           [:pre {:class "code-box tiny"}
            (let [rc (:reasoning_content m)]
              (if (> (count rc) 2000) (str (subs rc 0 2000) "\n… (truncated " (count rc) " chars)") rc))]])
        (when-not (str/blank? text-body)
          [:pre {:class "code-box tiny" :style "white-space:pre-wrap"}
           (if (> (count text-body) 2000) (str (subs text-body 0 2000) "\n… (truncated " (count text-body) " chars)") text-body)])
        (when img-url
          [:img {:class "wire-img" :src img-url :alt "Live WKWebView ephemeral tail screenshot"}])
        (when (seq (:tool_calls m))
          [:div {:style "margin-top:6px"}
           [:span {:class "kicker"} "tool_calls"]
           [:pre {:class "code-box tiny"}
            (let [tc (json/write-str (:tool_calls m))]
              (if (> (count tc) 1600) (str (subs tc 0 1600) "…") tc))]])]])))

(defn- wire-request-card
  [aid branch wire req-body viewport-screenshot]
  (let [all-wire (vec (map-indexed vector (map vector (:messages wire) (:message-tokens wire))))
        n-wire (count all-wire)
        shown-wire (if (> n-wire 16)
                     (concat (take 4 all-wire) [[:omitted (- n-wire 16)]] (take-last 12 all-wire))
                     all-wire)
        ms (:messages req-body)
        trimmed-req (if (> (count ms) 8)
                      (assoc req-body :messages
                             (vec (concat (take 2 ms)
                                          [{:role "system" :content (str "… " (- (count ms) 8) " middle messages omitted from UI JSON preview …")}]
                                          (take-last 6 ms))))
                      req-body)
        raw-json (str/replace (json/write-str trimmed-req)
                              #"data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]{80,}"
                              "data:image/jpeg;base64,<live-wkwebview-screenshot-redacted-for-display>")]
    [:section {:class "card"}
     [:div {:class "card-head"}
      [:div
       [:h2 (str "Exact Wire Request for " aid " (branch " branch ")")]
       [:span {:class "muted tiny"} "Built by model/build-agent-wire-messages — zero second interpretation. Prefix messages stay byte-identical for KV cache."]]
      [:div {:class "pill-row"}
       (when viewport-screenshot
         [:span {:class "badge amber mono"}
          (str "WKWebView " (:width viewport-screenshot) "×" (:height viewport-screenshot)
               (when (:ms viewport-screenshot) (str " · " (:ms viewport-screenshot) "ms")))])
       [:span {:class "badge blue mono"} (str "~" (:total-tokens wire) " tokens")]
       (when (:prefix-hash wire)
         [:span {:class "badge mono" :title "Static KV-cache prefix SHA-256"} (str "prefix " (:prefix-hash wire))])
       [:span {:class "badge green mono"} (str (count (:messages wire)) " wire msgs")]
       [:span {:class "badge mono"} (str "model: " (:model req-body))]]]
     [:div {:class "wire-list"}
      (for [[idx pair] shown-wire]
        (wire-message-row idx pair viewport-screenshot n-wire))]
     [:details {:class "fold" :style "margin-top:10px"}
      [:summary {:class "muted tiny"} "Raw OpenAI-Compat / DeepSeek Request JSON"]
      [:pre {:class "code-box tiny" :style "max-height:360px;overflow:auto"}
       (if (> (count raw-json) 6000)
         (str (subs raw-json 0 6000) "\n… (" (count raw-json) " chars total)")
         raw-json)]]]))

(defn- ctx-and-reads-grid
  [aid bdb ctx-snap walk-summary]
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
    [:div {:class "table-scroll"}
     [:table {:class "data-table"}
      [:thead [:tr [:th "Mounted Unit"] [:th "Reads"] [:th "Sample Read Index Keys"]]]
      [:tbody
       (for [{:keys [id reads-count reads-idx]} walk-summary]
         [:tr
          [:td {:class "mono"} (str "#" id)]
          [:td {:class "mono"} (str (or reads-count 0))]
          [:td {:class "mono tiny muted"} (pr-str (vec (take 6 reads-idx)))]])]]]]])

(defn- rendered-versions-card
  [main-db bdb aid wire]
  [:section {:class "card"}
   [:div {:class "card-head"}
    [:div
     [:h2 (str "Rendered Versions — Human HTML vs Model Text (" (count (:tiles wire)) " tiles)")]
     [:span {:class "muted tiny"} "Single-source Hiccup rendered live on the left (Datastar HTML) and projected via render/hiccup->text on the right (sent to LLM)"]]
    [:span {:class "badge purple mono"} "1 Hiccup → 2 targets"]]
   (if (empty? (:tiles wire))
     [:p {:class "muted sm-copy"} "No renderable tiles discovered for this agent yet."]
     [:div {:class "stack"}
      (for [[idx t] (map-indexed vector (:tiles wire))
            :let [src-name (name (or (:source t) :rule))
                  lay-name (when (and (:layout t) (not= (:layout t) :expanded-card)) (name (:layout t)))
                  extra-tags (remove #(or (= (name %) src-name) (= (name %) lay-name)) (sort (:tags t)))
                  human-tile (cond-> t (= :seon.health/report (:schema t)) (assoc :layout :compact-summary))]]
        [:div {:id (str "dbg-tile-" idx) :class "debug-tile-pair"}
         [:div {:class "debug-tile-col"}
          [:div {:class "row-between"}
           [:span {:class "kicker"} "Human HTML (Browser)"]
           [:div {:class "pill-row"}
            [:span {:class (str "badge " (case (:source t) :eval "green" :pinned "amber" :ctx "amber" :child "blue" :rule "purple" :curated "green" :tree "purple" "muted"))}
             src-name]
            (when (:handle t)
              [:span {:class "badge mono handle-pill"} (str (:handle t))])
            (when lay-name
              [:span {:class "badge muted mono"} lay-name])
            (for [tg extra-tags]
              [:span {:class "badge amber mono"} (name tg)])
            [:span {:class "badge mono"} (str (:schema t))]]]
          (when (seq (:why t))
            [:p {:class "muted tiny" :style "margin:2px 0 6px"}
             (str "Why: " (str/join " · " (:why t)))])
          (render/render-tile-node bdb human-tile {:agent-id aid :main-db main-db :mode :human :render-context :human})]
         [:div {:class "debug-tile-col"}
          [:div {:class "row-between"}
           [:span {:class "kicker"} "Model Text (render/hiccup->text)"]
           [:span {:class "mono tiny muted"} (str (count (:text t "")) " chars")]]
          [:pre {:class "code-box tiny ctx-pre"} (:text t)]]])])])

(defn- branches-card
  [branches sel-br diff-info]
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
     [:p {:class "muted sm-copy"} "Select a branch above to inspect its 3-way diff."])])

(defn- test-verdicts-card
  [current historical]
  [:section {:class "card"}
   [:div {:class "card-head"}
    [:h2 (str "Current Reach-Cached Test Verdicts (" (count current) ")")]
    [:span {:class "muted tiny"} "Filtered to live test vars"]]
   (if (empty? current)
     [:p {:class "muted sm-copy"} "No test digests recorded yet."]
     [:div {:class "table-scroll"}
      [:table {:class "data-table"}
       [:thead [:tr [:th "Test Var"] [:th "Verdict"] [:th "Cost"]]]
       [:tbody
        (for [t current]
          [:tr
           [:td {:class "mono tiny"} (str (:seon.test/var t))]
           [:td [:span {:class (str "badge " (if (:seon.test/pass? t) "green" "red"))}
                 (if (:seon.test/pass? t) "PASS" "FAIL")]]
           [:td {:class "mono tiny muted"} (str (:seon.test/ms t) " ms · " (quot (long (:seon.test/ticks t 0)) 1000) "k ticks")]])]]])
   (when (> (count historical) (count current))
     [:details {:class "fold" :style "margin-top:8px"}
      [:summary {:class "muted tiny"} (str "Show " (count historical) " historical cached digests")]
      [:pre {:class "code-box tiny"}
       (str/join "\n" (map #(str (:seon.test/var %) " " (subs (:seon.test/digest %) 0 10) " " (if (:seon.test/pass? %) "PASS" "FAIL")) historical))]])])

(defn- rule-trace-card
  [rule-trace]
  (when rule-trace
    (let [fired (filterv #(pos? (long (or (:fired %) 0))) (:runs rule-trace))]
      [:section {:id "dbg-rule-trace" :class "card"}
       [:div {:class "card-head"}
        [:div
         [:h2 "Logic Rules & Context Provenance (seon.rules)"]
         [:span {:class "muted tiny"} "Editable :seon.rule/* entities driving context selection, layout, modifier tags, and KV-cache zones"]]
        [:div {:class "pill-row"}
         [:span {:class "badge purple mono"} (str (:rules-count rule-trace 0) " active rules")]
         [:span {:class "badge green mono"} (str (count fired) " fired")]
         [:span {:class "badge blue mono"} (str (:facts-count rule-trace 0) " WM facts")]
         [:span {:class "badge mono"} (str (:ms rule-trace 0.0) " ms")]]]
       (if (empty? fired)
         [:p {:class "muted sm-copy"} "No rules fired."]
         [:div {:class "table-scroll"}
          [:table {:class "data-table"}
           [:thead [:tr [:th "Rule ID"] [:th "Stratum"] [:th "Category"] [:th "Fired"] [:th "Asserted"] [:th "DB Reads"] [:th "ms"]]]
           [:tbody
            (for [r fired]
              [:tr
               [:td {:class "mono tiny"} (str (:rule-id r))]
               [:td {:class "mono tiny"} (str (:stratum r))]
               [:td [:span {:class "badge purple"} (name (or (:category r) :present))]]
               [:td {:class "mono tiny"} (str (:fired r))]
               [:td {:class "mono tiny"} (str (:asserted-count r))]
               [:td {:class "mono tiny muted"} (str (:reads-count r))]
               [:td {:class "mono tiny muted"} (str (:ms r) " ms")]])]]])])))

(defonce ^:private ^:seon.flow/edge dbg-verdicts-cache (store/lru-cache 16))

(defonce ^:private ^:seon.flow/edge dbg-branches-cache (store/lru-cache 16))

(defonce ^:private ^:seon.flow/edge dbg-ctx-cache (store/lru-cache 16))

(defonce ^:private ^:seon.flow/edge dbg-wire-card-cache (store/lru-cache 16))

(defonce ^:private ^:seon.flow/edge dbg-versions-cache (store/lru-cache 16))

(defonce ^:private ^:seon.flow/edge dbg-health-cache (store/lru-cache 16))

(defn- dbg-snap-key [db]
  (when (and db
             (not (instance? datahike.db.AsOfDB db))
             (not (instance? datahike.db.HistoricalDB db)))
    (when-let [cid (try (d/commit-id db) (catch Throwable _ nil))]
      [(get-in db [:config :store :id]) (get-in db [:config :branch] :main) cid (:max-tx db)])))

(defn- visible-branches
[main-db conn]
(when conn
  (let [all-b (vec (sort (d/branches conn)))
        compute (fn []
                  (let [arch-b (if (contains? (:schema main-db) :seon.agent/archived?)
                                 (let [aeids (into #{} (keep #(when (:v %) (:e %))) (d/datoms main-db :aevt :seon.agent/archived?))
                                       brs-by-e (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms main-db :aevt :seon.agent/branch))
                                       abrs (into #{} (keep #(get brs-by-e %)) aeids)
                                       act-brs (into #{:db :main}
                                                     (keep (fn [dt]
                                                             (when-not (contains? aeids (:e dt))
                                                               (get brs-by-e (:e dt)))))
                                                     (d/datoms main-db :aevt :seon.agent/id))]
                                   (into #{} (remove act-brs) abrs))
                                 #{})]
                    (vec (remove arch-b all-b))))]
    (if-let [dk (dbg-snap-key main-db)]
      (store/lru-get! dbg-branches-cache 16 [dk all-b] compute)
      (compute)))))

(defn- compute-test-verdicts [db]
  (let [test-eids (if (contains? (:schema db) :seon.code.var/test?)
                    (reduce (fn [s dt] (if (:v dt) (conj s (:e dt)) s)) #{} (d/datoms db :aevt :seon.code.var/test?))
                    #{})
        live-test-vars (if (and (seq test-eids) (contains? (:schema db) :seon.code.var/name))
                         (reduce (fn [s dt] (if (contains? test-eids (:e dt)) (conj s (:v dt)) s))
                                 #{}
                                 (d/datoms db :aevt :seon.code.var/name))
                         #{})
        digs (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/digest))
        vars (if (contains? (:schema db) :seon.test/var)
               (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/var))
               {})
        pass (if (contains? (:schema db) :seon.test/pass?)
               (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/pass?))
               {})
        ms   (if (contains? (:schema db) :seon.test/ms)
               (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/ms))
               {})
        tks  (if (contains? (:schema db) :seon.test/ticks)
               (reduce (fn [m dt] (assoc m (:e dt) (:v dt))) {} (d/datoms db :aevt :seon.test/ticks))
               {})
        all (reduce-kv (fn [acc e d]
                         (let [v (get vars e)]
                           (if (contains? live-test-vars v)
                             (conj acc {:seon.test/digest d :seon.test/var v :seon.test/pass? (get pass e)
                                        :seon.test/ms (get ms e) :seon.test/ticks (get tks e)})
                             acc)))
                       []
                       digs)
        latest-by-var (->> all
                           (group-by :seon.test/var)
                           (map (fn [[_ vs]] (last (sort-by #(or (:seon.test/pass? %) false) vs))))
                           (sort-by (juxt #(if (:seon.test/pass? %) 1 0) #(str (:seon.test/var %))))
                           vec)]
    {:current latest-by-var
     :historical (vec (sort-by #(str (:seon.test/var %)) all))}))

(defn current-test-verdicts
"Linear AEVT scan of live test verdicts (zero per-entity `d/pull` overhead, cached per snapshot)."
  {:malli/schema [:=> [:cat :seon.host/db] [:map [:current [:vector :any]] [:historical [:vector :any]]]]}
[db]
(if-not (and db (contains? (:schema db) :seon.test/digest))
  {:current [] :historical []}
  (if-let [dk (dbg-snap-key db)]
    (store/lru-get! dbg-verdicts-cache 16 dk #(compute-test-verdicts db))
    (compute-test-verdicts db))))

(defn- resolve-debug-context [main-db conn viewing-id]
  (let [aid (or viewing-id (entry-agent-id main-db))
        ag (when (and main-db aid (contains? (:schema main-db) :seon.agent/id))
             (d/pull main-db '[:seon.agent/branch :seon.agent/merged-tx :seon.agent/read-branch :seon.agent/router? :seon.agent/role] [:seon.agent/id aid]))
        branch (or (:seon.agent/branch ag) :main)
        bdb (branch-db-for main-db conn branch)]
    {:aid aid :ag ag :branch branch :bdb bdb}))

(defn- effective-health-db [main-db {:keys [ag branch bdb]}]
  (let [mcid (try (d/commit-id main-db) (catch Throwable _ nil))
        bcid (try (d/commit-id bdb) (catch Throwable _ nil))
        merged-tx (long (or (:seon.agent/merged-tx ag) 0))
        bmax (long (or (:max-tx bdb) 0))
        reads-main? (or (= :main (:seon.agent/read-branch ag)) (:seon.agent/router? ag) (= :coordinator (:seon.agent/role ag)))]
    (if (or (= branch :main)
            (and mcid bcid (= mcid bcid))
            reads-main?
            (and (pos? merged-tx) (>= merged-tx bmax)))
      main-db
      bdb)))

(defn- cached-ctx-snapshot [main-db bdb aid]
  (let [compute (fn []
                  (when-let [snap (or (try (ctx/build bdb [:seon.agent/id aid]) (catch Throwable _ nil))
                                      (try (ctx/build main-db [:seon.agent/id aid]) (catch Throwable _ nil)))]
                    {:entries-count (count (:entries snap))
                     :tx (or (:tx snap) (:max-tx bdb))
                     :pattern-count (count (:pattern snap))
                     :pattern-str (pretty-edn (:pattern snap))
                     :val-str (pretty-edn (:val snap))}))
        mk (dbg-snap-key main-db)
        bk (dbg-snap-key bdb)]
    (if (and mk bk)
      (store/lru-get! dbg-ctx-cache 16 [mk bk aid] compute)
      (compute))))

(defn dbg-feedback-unit
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]}
  [_main-db {:keys [branch-feedback]}]
  [:div {:id "dbg-feedback-unit"}
   (when branch-feedback
     [:div {:class (str "notice " (if (:ok? branch-feedback) "green" "red"))}
      (:text branch-feedback)])])

(defn dbg-wire-unit
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]}
  [main-db {:keys [conn viewing-id viewport-screenshot]}]
  (let [{:keys [aid branch bdb]} (resolve-debug-context main-db conn viewing-id)
        wire (build-live-wire-request main-db bdb aid {:viewport-screenshot viewport-screenshot
                                                       :vision? (boolean viewport-screenshot)
                                                       :conn conn})
        model-name (store/config-val main-db aid :seon.config/ai-model)
        thinking-v (store/config-val main-db aid :seon.config/ai-thinking)
        mk (dbg-snap-key main-db)
        bk (dbg-snap-key bdb)
        shot-sig (when viewport-screenshot
                   [(:width viewport-screenshot) (:height viewport-screenshot) (:bytes viewport-screenshot) (:captured-at viewport-screenshot)])
        ck (when (and mk bk)
             [mk bk aid (:prefix-hash wire) (count (:messages wire)) model-name thinking-v shot-sig])
        build (fn []
                (let [req-body (model/build-request-body {:model model-name
                                                          :messages (:messages wire)
                                                          :thinking thinking-v}
                                                         nil nil nil)]
                  (wire-request-card aid branch wire req-body viewport-screenshot)))]
    (if ck
      (store/lru-get! dbg-wire-card-cache 16 ck build)
      (build))))

(defn dbg-ctx-unit
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]}
  [main-db {:keys [conn viewing-id walk-summary]}]
  (let [{:keys [aid bdb]} (resolve-debug-context main-db conn viewing-id)
        ctx-snap (cached-ctx-snapshot main-db bdb aid)]
    (ctx-and-reads-grid aid bdb ctx-snap walk-summary)))

(defn dbg-versions-unit
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]}
  [main-db {:keys [conn viewing-id viewport-screenshot]}]
  (let [{:keys [aid branch bdb]} (resolve-debug-context main-db conn viewing-id)
        wire (build-live-wire-request main-db bdb aid {:viewport-screenshot viewport-screenshot
                                                       :vision? (boolean viewport-screenshot)
                                                       :conn conn})
        mk (dbg-snap-key main-db)
        bk (dbg-snap-key bdb)
        tiles-sig (mapv (juxt :id :layout :tags :why :text) (:tiles wire))
        ck (when (and mk bk) [mk bk aid branch (:prefix-hash wire) tiles-sig])]
    (if ck
      (store/lru-get! dbg-versions-cache 16 ck #(rendered-versions-card main-db bdb aid wire))
      (rendered-versions-card main-db bdb aid wire))))

(defn dbg-health-unit
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]}
  [main-db {:keys [conn viewing-id]}]
  (let [ctx (resolve-debug-context main-db conn viewing-id)
        eff-db (effective-health-db main-db ctx)
        compute (fn []
                  (let [h (health/report eff-db)]
                    [:div {:id "dbg-health-unit"}
                     (render/render-value eff-db h {:mode :human :layout :compact-summary})]))]
    (if-let [ek (dbg-snap-key eff-db)]
      (store/lru-get! dbg-health-cache 16 ek compute)
      (compute))))

(defn dbg-branches-unit
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]}
  [main-db {:keys [conn selected-branch]}]
  (let [sel-br (or selected-branch
                   (when (and main-db (contains? (:schema main-db) :seon.user/selected-branch)
                              (first (d/datoms main-db :avet :seon.user/id :user/default)))
                     (:seon.user/selected-branch (d/pull main-db '[:seon.user/selected-branch] [:seon.user/id :user/default]))))
        branches (visible-branches main-db conn)
        diff-info (when (and conn sel-br (not= sel-br :main))
                    (try (merge/diff-branch conn :main sel-br) (catch Throwable _ nil)))]
    (branches-card branches sel-br diff-info)))

(defn dbg-schema-unit
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]}
  [main-db _opts]
  [:section {:id "dbg-schema-unit" :class "card"}
   [:div {:class "card-head"}
    [:h2 "Register Global Malli Schema (→ Auto Datahike)"]
    [:span {:class "muted tiny"} (str (count (d/datoms main-db :aevt :seon.schema/key)) " schemas · " (count (:schema main-db)) " DH attrs")]]
   [:form {:class "form-stack" :on-submit '(seon.web/register-schema-form!)}
    [:div {:class "row-form"}
     [:input {:class "inp sm mono flex-1" :name "key" :placeholder ":seon.app/amount"}]
     [:input {:class "inp sm mono flex-1" :name "form" :placeholder "[:double {:min 0.0}]"}]
     [:input {:class "inp sm flex-1" :name "doc" :placeholder "Description..."}]
     [:button {:type "submit" :class "btn sm primary"} "Register Schema"]]]])

(defn dbg-drill-unit
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]}
  [_main-db _opts]
  [:details {:id "dbg-drill-unit" :class "card fold"}
   [:summary {:class "muted tiny mono"} "Diagnostics & Crash-Recovery Drill"]
   [:div {:class "row-between" :style "margin-top:10px"}
    [:span {:class "muted tiny"} "Simulates sudden power loss (SIGKILLs forked children and cold-reboots the store while keeping live SSE tabs connected)."]
    [:button {:type "button" :class "btn sm danger" :on-click '(seon.web/power-loss-reboot!)} "Cold Reboot Drill"]]])

(defonce ^:private ^:seon.flow/edge dbg-rules-card-cache (store/lru-cache 16))

(defn dbg-rules-unit
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]}
[main-db {:keys [conn viewing-id viewport-screenshot]}]
(let [{:keys [aid branch bdb]} (resolve-debug-context main-db conn viewing-id)
      wire (build-live-wire-request main-db bdb aid {:viewport-screenshot viewport-screenshot
                                                     :vision? (boolean viewport-screenshot)
                                                     :conn conn})
      mk (dbg-snap-key main-db)
      bk (dbg-snap-key bdb)
      ck (when (and mk bk) [mk bk aid branch (:prefix-hash wire)])
      build (fn [] [:div {:id "dbg-rules-unit"} (rule-trace-card (:rule-trace wire))])]
  (if ck
    (store/lru-get! dbg-rules-card-cache 16 ck build)
    (build))))

(defonce ^:private ^:seon.flow/edge dbg-tests-card-cache (store/lru-cache 16))

(defn dbg-tests-unit
  {:malli/schema [:=> [:cat [:maybe :seon.host/db] :map] [:vector :any]]}
[main-db {:keys [conn viewing-id]}]
(let [{:keys [bdb]} (resolve-debug-context main-db conn viewing-id)
      build (fn []
              (let [{:keys [current historical]} (current-test-verdicts bdb)]
                (test-verdicts-card current historical)))]
  (if-let [bk (dbg-snap-key bdb)]
    (store/lru-get! dbg-tests-card-cache 16 bk build)
    (build))))

(defn debug-view
{:malli/schema [:=> [:cat :seon.host/db :map] :any]}
[main-db {:keys [viewing-id] :as opts}]
(let [aid (or viewing-id (entry-agent-id main-db))
      uopts (assoc opts :viewing-id aid)]
  [:div {:id "seon-view-unit" :class "debug-wrap" :data-agent (str aid) :data-view-mode "debug"}
   (dbg-feedback-unit main-db uopts)
   (dbg-wire-unit main-db uopts)
   (dbg-ctx-unit main-db uopts)
   (dbg-rules-unit main-db uopts)
   (dbg-versions-unit main-db uopts)
   [:div {:class "grid-2"}
    (dbg-health-unit main-db uopts)
    (dbg-branches-unit main-db uopts)]
   (dbg-schema-unit main-db uopts)
   (dbg-tests-unit main-db uopts)
   (dbg-drill-unit main-db uopts)]))

(defn debug-unit-specs
  "Returns fine-grained independent step-walk units for :debug view."
  {:malli/schema [:=> [:cat :seon.host/db :map :keyword] [:vector :map]]}
  [main-db st viewing-id]
  (let [{:keys [aid bdb] :as ctx} (resolve-debug-context main-db (:conn st) viewing-id)
      btx (when (not= bdb main-db) (:max-tx bdb))
      htx (when (not= (effective-health-db main-db ctx) main-db) (:max-tx bdb))
      conn (:conn st)
      shot (:viewport-screenshot st)
      shot-sig (when shot [(:width shot) (:height shot) (:bytes shot) (:captured-at shot)])
      wire-arg {:conn conn :viewing-id aid :branch-tx btx :viewport-screenshot shot :shot-sig shot-sig}]
  [{:id "dbg-feedback-unit" :comp #'dbg-feedback-unit :args [{:branch-feedback (:branch-feedback st)}]}
   {:id "dbg-wire-unit"     :comp #'dbg-wire-unit     :args [wire-arg]}
   {:id "dbg-ctx-unit"      :comp #'dbg-ctx-unit      :args [{:conn conn :viewing-id aid :branch-tx btx :walk-summary (:walk-summary st)}]}
   {:id "dbg-rules-unit"    :comp #'dbg-rules-unit    :args [wire-arg]}
   {:id "dbg-versions-unit" :comp #'dbg-versions-unit :args [wire-arg]}
   {:id "dbg-health-unit"   :comp #'dbg-health-unit   :args [{:conn conn :viewing-id aid :branch-tx htx}]}
   {:id "dbg-branches-unit" :comp #'dbg-branches-unit :args [{:conn conn}]}
   {:id "dbg-schema-unit"   :comp #'dbg-schema-unit   :args [{}]}
   {:id "dbg-tests-unit"    :comp #'dbg-tests-unit    :args [{:conn conn :viewing-id aid :branch-tx btx}]}
   {:id "dbg-drill-unit"    :comp #'dbg-drill-unit    :args [{}]}]))
