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

(defn detect-xml-tags
  {:malli/schema [:=> [:cat :any] [:maybe [:vector :string]]]}
  [s]
  (when (string? s)
    (vec (distinct (map second (re-seq #"<(/?(?:session|agent|walk-context|walk-diff|message|branch-status|scoped-health|system-health|neighborhood-activity|live-viewport|tail-render-fault))\b" s))))))

(defn pretty-edn [x]
  (try
    (let [s (with-out-str (fipp/pprint x {:width 88}))]
      (if (> (count s) 12000) (str (subs s 0 12000) "\n… (truncated)") s))
    (catch Throwable _ (pr-str x))))

(defn debug-view #:malli{:schema [:=> [:cat :seon.host/db :map] :any]} [main-db {:keys [conn walk-summary branch-feedback viewing-id selected-branch viewport-screenshot]}] (let [aid (or viewing-id (entry-agent-id main-db)) branch (or (:seon.agent/branch (d/pull main-db (quote [:seon.agent/branch]) [:seon.agent/id aid])) :main) bdb (branch-db-for main-db conn branch) wire (build-live-wire-request main-db bdb aid {:viewport-screenshot viewport-screenshot, :vision? (boolean viewport-screenshot), :conn conn}) ctx-snap (or (try (ctx/build bdb [:seon.agent/id aid]) (catch Throwable _ nil)) (try (ctx/build main-db [:seon.agent/id aid]) (catch Throwable _ nil))) req-body (model/build-request-body {:model (store/config-val main-db aid :seon.config/ai-model), :messages (:messages wire), :thinking (store/config-val main-db aid :seon.config/ai-thinking)} nil nil nil) {:keys [current historical]} (current-test-verdicts bdb) branches (visible-branches main-db conn) sel-br selected-branch diff-info (when (and conn sel-br (not= sel-br :main)) (try (merge/diff-branch conn :main sel-br) (catch Throwable _ nil))) h (health/report bdb)] [:div {:id "seon-view-unit", :class "debug-wrap"} (notice-block branch-feedback) (wire-request-card wire req-body aid branch viewport-screenshot) [:div {:class "grid-2"} (ctx-card ctx-snap bdb aid) (reads-walk-card walk-summary)] (rendered-versions-card wire bdb aid main-db) [:div {:class "grid-2"} (render/render-value bdb h) (branches-card branches sel-br diff-info)] (schema-register-card main-db) (test-verdicts-card current historical) (diagnostics-card)]))

(defn- wire-message-row "Render one wire message `[idx pair]` as an expandable `:details` row." [[idx pair] viewport-screenshot] (let [[m toks] pair role (:role m) content (:content m) img-url (when (sequential? content) (some (fn* [p__20#] (when (= "image_url" (:type p__20#)) (get-in p__20# [:image_url :url]))) content)) text-body (if (sequential? content) (str/join "\n" (map (fn* [p__21#] (if (= "image_url" (:type p__21#)) (str "[IMAGE_URL: ephemeral live WKWebView viewport" (when (:bytes viewport-screenshot) (str " · " (quot (:bytes viewport-screenshot) 1024) " KB")) "]") (:text p__21#))) content)) (str content)) xml-tags (detect-xml-tags text-body) preview (let [line (first (remove str/blank? (str/split-lines text-body)))] (if (> (count (str line)) 120) (str (subs line 0 120) "…") (str line)))] [:details {:id (str "wm-" idx), :class "wire-row", :open (or (< idx 4) (some? img-url))} [:summary {:class "wire-summary"} [:span {:class "muted tiny mono"} (str "#" idx)] [:span {:class (wire-role-badge role)} (str/upper-case role)] (for [tag xml-tags] [:span {:class "badge mono xml-tag"} (str "<" tag ">")]) (when img-url [:span {:class "badge amber mono"} "image_url"]) (when (seq (:tool_calls m)) [:span {:class "badge green mono"} (str (count (:tool_calls m)) " tool_call(s)")]) (when (:tool_call_id m) [:span {:class "badge mono"} (str "call_id=" (:tool_call_id m))]) [:span {:class "muted tiny truncate flex-1"} preview] [:span {:class "mono tiny muted"} (str "~" toks " tok")]] [:div {:class "wire-body"} (when (:reasoning_content m) [:div {:style "margin-bottom:6px"} [:span {:class "kicker"} "reasoning_content"] [:pre {:class "code-box tiny"} (let [rc (:reasoning_content m)] (if (> (count rc) 2000) (str (subs rc 0 2000) "\n… (truncated " (count rc) " chars)") rc))]]) (when (not (str/blank? text-body)) [:pre {:class "code-box tiny", :style "white-space:pre-wrap"} (if (> (count text-body) 2000) (str (subs text-body 0 2000) "\n… (truncated " (count text-body) " chars)") text-body)]) (when img-url [:img {:class "wire-img", :src img-url, :alt "Live WKWebView ephemeral tail screenshot"}]) (when (seq (:tool_calls m)) [:div {:style "margin-top:6px"} [:span {:class "kicker"} "tool_calls"] [:pre {:class "code-box tiny"} (let [tc (json/write-str (:tool_calls m))] (if (> (count tc) 1600) (str (subs tc 0 1600) "…") tc))]])]]))

(defn- wire-list "The `.wire-list` div, folding >16-message histories to first 4 + last 12." [wire viewport-screenshot] (let [all-wire (vec (map-indexed vector (map vector (:messages wire) (:message-tokens wire)))) n-wire (count all-wire) shown-wire (if (> n-wire 16) (concat (take 4 all-wire) [[:omitted (- n-wire 16)]] (take-last 12 all-wire)) all-wire)] [:div {:class "wire-list"} (for [[idx pair] shown-wire] (if (= idx :omitted) [:div {:class "muted tiny mono", :style "padding:6px 10px"} (str "… " pair " middle wire messages omitted from Debug DOM (full " n-wire "-msg history in REPL)")] (wire-message-row [idx pair] viewport-screenshot)))]))

(defn- raw-request-json "Collapsed raw OpenAI-compat request JSON with base64 viewport redaction." [req-body] [:details {:class "fold", :style "margin-top:10px"} [:summary {:class "muted tiny"} "Raw OpenAI-Compat / DeepSeek Request JSON"] [:pre {:class "code-box tiny", :style "max-height:360px;overflow:auto"} (let [raw (str/replace (json/write-str (let [ms (:messages req-body)] (if (> (count ms) 8) (assoc req-body :messages (vec (concat (take 2 ms) [{:role "system", :content (str "… " (- (count ms) 8) " middle messages omitted from UI JSON preview …")}] (take-last 6 ms)))) req-body))) #"data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]{80,}" "data:image/jpeg;base64,<live-wkwebview-screenshot-redacted-for-display>")] (if (> (count raw) 6000) (str (subs raw 0 6000) "\n… (" (count raw) " chars total)") raw))]])

(defn- notice-block "Green/red branch-feedback notice banner, or nil when absent." [branch-feedback] (when branch-feedback [:div {:class (str "notice " (if (:ok? branch-feedback) "green" "red"))} (:text branch-feedback)]))

(defn- wire-request-card "The full `Exact Wire Request` card: header, wire list, raw JSON." [wire req-body aid branch viewport-screenshot] [:section {:class "card"} [:div {:class "card-head"} [:div [:h2 (str "Exact Wire Request for " aid " (branch " branch ")")] [:span {:class "muted tiny"} "Built by model/build-agent-wire-messages — zero second interpretation. Prefix messages stay byte-identical for KV cache."]] [:div {:class "pill-row"} (when viewport-screenshot [:span {:class "badge amber mono"} (str "WKWebView " (:width viewport-screenshot) "×" (:height viewport-screenshot) (when (:ms viewport-screenshot) (str " · " (:ms viewport-screenshot) "ms")))]) [:span {:class "badge blue mono"} (str "~" (:total-tokens wire) " tokens")] (when (:prefix-hash wire) [:span {:class "badge mono", :title "Static KV-cache prefix SHA-256"} (str "prefix " (:prefix-hash wire))]) [:span {:class "badge green mono"} (str (count (:messages wire)) " wire msgs")] [:span {:class "badge mono"} (str "model: " (:model req-body))]]] (wire-list wire viewport-screenshot) (raw-request-json req-body)])

(defn- ctx-card "Live `@seon.ctx/ctx` atom value card." [ctx-snap bdb aid] [:section {:class "card"} [:div {:class "card-head"} [:div [:h2 (str "Live @seon.ctx/ctx Atom Value (" aid ")")] [:span {:class "muted tiny"} "Live incremental Datahike pull backing @seon.ctx/ctx inside this agent's REPL"]] [:div {:class "pill-row"} [:span {:class "badge amber mono"} (str (count (:entries ctx-snap)) " pull clauses")] [:span {:class "badge mono"} (str "tx " (or (:tx ctx-snap) (:max-tx bdb)))]]] (if ctx-snap [:div {:class "stack-sm"} [:details {:class "fold"} [:summary {:class "muted tiny mono"} (str "Active :seon.ctx/pattern (" (count (:pattern ctx-snap)) " items)")] [:pre {:class "code-box tiny ctx-pre"} [:code (pretty-edn (:pattern ctx-snap))]]] [:pre {:class "code-box tiny ctx-pre"} [:code (pretty-edn (:val ctx-snap))]]] [:p {:class "muted sm-copy"} "No agent entity found for @seon.ctx/ctx."])])

(defn- visible-branches "Visible (non-archived) git branches, sorted. Zero work when `conn` is nil." [main-db conn] (when conn (let [all-b (sort (d/branches conn)) arch-b (if (contains? (:schema main-db) :seon.agent/archived?) (let [aeids (into #{} (keep (fn* [p__22#] (when (:v p__22#) (:e p__22#)))) (d/datoms main-db :aevt :seon.agent/archived?)) abrs (into #{} (keep (fn* [p__23#] (:seon.agent/branch (d/pull main-db (quote [:seon.agent/branch]) p__23#)))) aeids) act-brs (into #{:db :main} (keep (fn [dt] (when-not (contains? aeids (:e dt)) (:seon.agent/branch (d/pull main-db (quote [:seon.agent/branch]) (:e dt)))))) (d/datoms main-db :aevt :seon.agent/id))] (into #{} (remove act-brs) abrs)) #{})] (vec (remove arch-b all-b)))))

(defn- diagnostics-card "Collapsed crash-recovery drill panel." [] [:details {:class "card fold"} [:summary {:class "muted tiny mono"} "Diagnostics & Crash-Recovery Drill"] [:div {:class "row-between", :style "margin-top:10px"} [:span {:class "muted tiny"} "Simulates sudden power loss (SIGKILLs forked children and cold-reboots the store while keeping live SSE tabs connected)."] [:button {:type "button", :class "btn sm danger", :on-click (quote (seon.web/power-loss-reboot!))} "Cold Reboot Drill"]]])

(defn- schema-register-card "Global Malli schema registration form." [main-db] [:section {:class "card"} [:div {:class "card-head"} [:h2 "Register Global Malli Schema (→ Auto Datahike)"] [:span {:class "muted tiny"} (str (count (d/datoms main-db :aevt :seon.schema/key)) " schemas · " (count (:schema main-db)) " DH attrs")]] [:form {:class "form-stack", :on-submit (quote (seon.web/register-schema-form!))} [:div {:class "row-form"} [:input {:class "inp sm mono flex-1", :name "key", :placeholder ":seon.app/amount"}] [:input {:class "inp sm mono flex-1", :name "form", :placeholder "[:double {:min 0.0}]"}] [:input {:class "inp sm flex-1", :name "doc", :placeholder "Description..."}] [:button {:type "submit", :class "btn sm primary"} "Register Schema"]]]])

(defn- test-verdicts-card "Current + historical reach-cached test verdicts." [current historical] [:section {:class "card"} [:div {:class "card-head"} [:h2 (str "Current Reach-Cached Test Verdicts (" (count current) ")")] [:span {:class "muted tiny"} "Filtered to live test vars"]] (if (empty? current) [:p {:class "muted sm-copy"} "No test digests recorded yet."] [:div {:class "table-scroll"} [:table {:class "data-table"} [:thead [:tr [:th "Test Var"] [:th "Verdict"] [:th "Cost"]]] [:tbody (for [t current] [:tr [:td {:class "mono tiny"} (str (:seon.test/var t))] [:td [:span {:class (str "badge " (if (:seon.test/pass? t) "green" "red"))} (if (:seon.test/pass? t) "PASS" "FAIL")]] [:td {:class "mono tiny muted"} (str (:seon.test/ms t) " ms · " (quot (long (:seon.test/ticks t 0)) 1000) "k ticks")]])]]]) (when (> (count historical) (count current)) [:details {:class "fold", :style "margin-top:8px"} [:summary {:class "muted tiny"} (str "Show " (count historical) " historical cached digests")] [:pre {:class "code-box tiny"} (str/join "\n" (map (fn* [p__24#] (str (:seon.test/var p__24#) " " (subs (:seon.test/digest p__24#) 0 10) " " (if (:seon.test/pass? p__24#) "PASS" "FAIL"))) historical))]])])

(defn- rendered-versions-card "Human HTML vs Model Text side-by-side rendered tile pairs." [wire bdb aid main-db] [:section {:class "card"} [:div {:class "card-head"} [:div [:h2 (str "Rendered Versions — Human HTML vs Model Text (" (count (:tiles wire)) " tiles)")] [:span {:class "muted tiny"} "Single-source Hiccup rendered live on the left (Datastar HTML) and projected via render/hiccup->text on the right (sent to LLM)"]] [:span {:class "badge purple mono"} "1 Hiccup → 2 targets"]] (if (empty? (:tiles wire)) [:p {:class "muted sm-copy"} "No renderable tiles discovered for this agent yet."] [:div {:class "stack"} (for [[idx t] (map-indexed vector (:tiles wire))] [:div {:id (str "dbg-tile-" idx), :class "debug-tile-pair"} [:div {:class "debug-tile-col"} [:div {:class "row-between"} [:span {:class "kicker"} "Human HTML (Browser)"] [:div {:class "pill-row"} [:span {:class (str "badge " (case (:source t) :eval "green" :pinned "amber" :child "blue" "muted"))} (name (:source t))] (when (:handle t) [:span {:class "badge mono handle-pill"} (str (:handle t))]) [:span {:class "badge mono"} (str (:schema t))]]] (or (:hiccup t) (render/render-value bdb (:val t) {:agent-id aid, :main-db main-db}))] [:div {:class "debug-tile-col"} [:div {:class "row-between"} [:span {:class "kicker"} "Model Text (render/hiccup->text)"] [:span {:class "mono tiny muted"} (str (count (:text t "")) " chars")]] [:pre {:class "code-box tiny ctx-pre"} (:text t)]]])])])

(defn- branches-card "Branch selector buttons plus 3-way git diff summary." [branches sel-br diff-info] [:section {:class "card"} [:div {:class "card-head"} [:h2 (str "Branches & 3-Way Git Diff (" (count branches) ")")] [:span {:class "muted tiny"} "0 worktrees · refs/heads/seon/*"]] [:div {:class "pill-row", :style "margin-bottom:8px"} (for [b branches] [:button {:type "button", :class (str "btn sm" (when (= b sel-br) " primary")), :on-click (list (quote seon.web/select-branch!) b)} (str b)])] (if diff-info [:div {:class "stack-sm"} [:div {:class "pill-row"} [:span {:class "badge green"} (str "+" (count (:added diff-info)) " added")] [:span {:class "badge blue"} (str "~" (count (:changed diff-info)) " changed")] [:span {:class "badge red"} (str "-" (count (:removed diff-info)) " removed")] [:button {:type "button", :class "btn sm success", :on-click (list (quote seon.web/merge-branch!) sel-br)} (str "Merge " sel-br " → :main")]] (when (seq (:changed diff-info)) [:pre {:class "code-box tiny"} (pr-str (:changed diff-info))])] [:p {:class "muted sm-copy"} "Select a branch above to inspect its 3-way diff."])])

(defn- reads-row "One read-set table row." [{:keys [id reads-count reads-idx]}] [:tr [:td {:class "mono"} (str "#" id)] [:td {:class "mono"} (str (or reads-count 0))] [:td {:class "mono tiny muted"} (pr-str (vec (take 6 reads-idx)))]])

(defn- reads-walk-card "datahike.reads incremental read-set table." [walk-summary] [:section {:class "card"} [:div {:class "card-head"} [:h2 "Live datahike.reads Read-Sets (Incremental Walk)"] [:span {:class "muted tiny"} "0 DB work on untouched units"]] [:div {:class "table-scroll"} [:table {:class "data-table"} [:thead [:tr [:th "Mounted Unit"] [:th "Reads"] [:th "Sample Read Index Keys"]]] [:tbody (map reads-row walk-summary)]]]])

(declare branches-card ctx-card diagnostics-card notice-block reads-row reads-walk-card raw-request-json rendered-versions-card schema-register-card test-verdicts-card visible-branches wire-list wire-message-row wire-request-card)

(declare pretty-edn branches-card ctx-card diagnostics-card notice-block reads-row reads-walk-card raw-request-json rendered-versions-card schema-register-card test-verdicts-card visible-branches wire-list wire-message-row wire-request-card)