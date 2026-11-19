(ns seon.render.debug
  "Zero-cost Wire & System Debug view (`debug-view` + linear AEVT test verdicts)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [datahike.api :as d]
            [fipp.edn :as fipp]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.health :as health]
            [seon.merge :as merge]
            [seon.model :as model]
            [seon.reconciler :as reconciler]
            [seon.render :as render]
            [seon.store :as store]
            [seon.render.data :as rdata :refer [branch-db-for entry-agent-id]]
            [seon.render.markdown :as rmd]
            [seon.render.tiles :as rtiles]
            [seon.render.ns-tile :as rnstile]
            [seon.render.wire :as rwire :refer [build-live-wire-request]]))

(defn current-test-verdicts
  "Linear AEVT scan of live test verdicts (zero per-entity `d/pull` overhead)."
  [db]
  (if-not (and db (contains? (:schema db) :seon.test/digest))
    {:current [] :historical []}
    (let [test-eids (if (contains? (:schema db) :seon.code.var/test?)
                      (reduce (fn [s dt] (if (:v dt) (conj s (:e dt)) s)) #{} (d/datoms db :aevt :seon.code.var/test?))
                      #{})
          live-test-vars (if (and (seq test-eids) (contains? (:schema db) :seon.code.var/name))
                           (reduce (fn [s dt]
                                     (if (contains? test-eids (:e dt)) (conj s (:v dt)) s))
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
                               (conj acc {:seon.test/digest d
                                          :seon.test/var v
                                          :seon.test/pass? (get pass e)
                                          :seon.test/ms (get ms e)
                                          :seon.test/ticks (get tks e)})
                               acc)))
                         []
                         digs)
          latest-by-var (->> all
                             (group-by :seon.test/var)
                             (map (fn [[_ vs]] (last (sort-by #(or (:seon.test/pass? %) false) vs))))
                             (sort-by (juxt #(if (:seon.test/pass? %) 1 0) #(str (:seon.test/var %))))
                             vec)]
      {:current latest-by-var
       :historical (vec (sort-by #(str (:seon.test/var %)) all))})))

(defn wire-role-badge [role]
  (case role
    "system"    "badge amber"
    "user"      "badge blue"
    "assistant" "badge green"
    "tool"      "badge purple"
    "badge"))

(defn detect-xml-tags [s]
  (when (string? s)
    (vec (distinct (map second (re-seq #"<(/?(?:session|agent|walk-context|walk-diff|message|live-viewport))\b" s))))))

(defn pretty-edn [x]
  (try
    (let [s (with-out-str (fipp/pprint x {:width 88}))]
      (if (> (count s) 12000) (str (subs s 0 12000) "\n… (truncated)") s))
    (catch Throwable _ (pr-str x))))

(defn debug-view [main-db {:keys [conn walk-summary branch-feedback viewing-id selected-branch viewport-screenshot]}]
  (let [aid (or viewing-id (entry-agent-id main-db))
        branch (or (:seon.agent/branch (d/pull main-db '[:seon.agent/branch] [:seon.agent/id aid])) :main)
        bdb (branch-db-for main-db conn branch)
        wire (build-live-wire-request main-db bdb aid {:viewport-screenshot viewport-screenshot
                                                       :vision? (boolean viewport-screenshot)})
        ctx-snap (or (try (ctx/build bdb [:seon.agent/id aid]) (catch Throwable _ nil))
                     (try (ctx/build main-db [:seon.agent/id aid]) (catch Throwable _ nil)))
        req-body (model/build-request-body {:model (store/config-val main-db aid :seon.config/ai-model)
                                            :messages (:messages wire)
                                            :thinking (store/config-val main-db aid :seon.config/ai-thinking)}
                                           nil nil nil)
        {:keys [current historical]} (current-test-verdicts bdb)
        branches (when conn (sort (d/branches conn)))
        sel-br selected-branch
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
        (when viewport-screenshot
          [:span {:class "badge amber mono"}
           (str "WKWebView " (:width viewport-screenshot) "×" (:height viewport-screenshot)
                (when (:ms viewport-screenshot) (str " · " (:ms viewport-screenshot) "ms")))])
        [:span {:class "badge blue mono"} (str "~" (:total-tokens wire) " tokens")]
        [:span {:class "badge green mono"} (str (count (:messages wire)) " wire msgs")]
        [:span {:class "badge mono"} (str "model: " (:model req-body))]]]

      [:div {:class "wire-list"}
       (for [[idx [m toks]] (map-indexed vector (map vector (:messages wire) (:message-tokens wire)))
             :let [role (:role m)
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
                             (if (> (count (str line)) 120) (str (subs line 0 120) "…") (str line)))]]
         [:details {:id (str "wm-" idx) :class "wire-row" :open (or (< idx 4) (some? img-url))}
          [:summary {:class "wire-summary"}
           [:span {:class "muted tiny mono"} (str "#" idx)]
           [:span {:class (wire-role-badge role)} (str/upper-case role)]
           (for [tag xml-tags]
             [:span {:class "badge mono xml-tag"} (str "<" tag ">")])
           (when img-url
             [:span {:class "badge amber mono"} "image_url"])
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
             [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} (if (> (count text-body) 12000) (str (subs text-body 0 12000) "\n… (truncated " (count text-body) " chars)") text-body)])
           (when img-url
             [:img {:class "wire-img" :src img-url :alt "Live WKWebView ephemeral tail screenshot"}])
           (when (seq (:tool_calls m))
             [:div {:style "margin-top:6px"}
              [:span {:class "kicker"} "tool_calls"]
              [:pre {:class "code-box tiny"} (json/write-str (:tool_calls m))]])]])]

      [:details {:class "fold" :style "margin-top:10px"}
       [:summary {:class "muted tiny"} "Raw OpenAI-Compat / DeepSeek Request JSON"]
       [:pre {:class "code-box tiny" :style "max-height:360px;overflow:auto"}
        (let [raw (str/replace (json/write-str req-body)
                                       #"data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]{80,}"
                                       "data:image/jpeg;base64,<live-wkwebview-screenshot-redacted-for-display>")]
          (if (> (count raw) 12000)
            (str (subs raw 0 12000) "\n… (" (count raw) " chars total)")
            raw))]]]

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
             [:td {:class "mono tiny muted"} (pr-str (vec (take 6 reads-idx)))]])]]]]]

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
             (render/render-value bdb (:val t) {:agent-id aid :main-db main-db})]
            [:div {:class "debug-tile-col"}
             [:div {:class "row-between"}
              [:span {:class "kicker"} "Model Text (render/hiccup->text)"]
              [:span {:class "mono tiny muted"} (str (count (:text t "")) " chars")]]
             [:pre {:class "code-box tiny ctx-pre"} (:text t)]]])])]

     [:div {:class "grid-2"}
      (render/render-value bdb h)

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
         [:p {:class "muted sm-copy"} "Select a branch above to inspect its 3-way diff."])]]

     [:section {:class "card"}
      [:div {:class "card-head"}
       [:h2 "Register Global Malli Schema (→ Auto Datahike)"]
       [:span {:class "muted tiny"} (str (count (d/datoms main-db :aevt :seon.schema/key)) " schemas · " (count (:schema main-db)) " DH attrs")]]
      [:form {:class "form-stack" :on-submit '(seon.web/register-schema-form!)}
       [:div {:class "row-form"}
        [:input {:class "inp sm mono flex-1" :name "key" :placeholder ":seon.app/amount"}]
        [:input {:class "inp sm mono flex-1" :name "form" :placeholder "[:double {:min 0.0}]"}]
        [:input {:class "inp sm flex-1" :name "doc" :placeholder "Description..."}]
        [:button {:type "submit" :class "btn sm primary"} "Register Schema"]]]]

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
          (str/join "\n" (map #(str (:seon.test/var %) " " (subs (:seon.test/digest %) 0 10) " " (if (:seon.test/pass? %) "PASS" "FAIL")) historical))]])]

     [:details {:class "card fold"}
      [:summary {:class "muted tiny mono"} "Diagnostics & Crash-Recovery Drill"]
      [:div {:class "row-between" :style "margin-top:10px"}
       [:span {:class "muted tiny"} "Simulates sudden power loss (SIGKILLs forked children and cold-reboots the store while keeping live SSE tabs connected)."]
       [:button {:type "button" :class "btn sm danger" :on-click '(seon.web/power-loss-reboot!)} "Cold Reboot Drill"]]]]))
