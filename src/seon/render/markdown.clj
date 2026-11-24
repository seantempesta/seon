(ns seon.render.markdown
  "Pure Clojure CommonMark/GFM -> interactive Hiccup renderer, timeline interleaving, activity folds, and Phosphor SVG icons."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [seon.render.data :as rdata]
            [seon.render.links :as rlinks]
            [seon.render.markdown.parse :as mdp]
            [seon.web.router :as router]))

(def svg-sidebar
  [:svg {:width "15" :height "15" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"}
   [:rect {:x "1.75" :y "2.5" :width "12.5" :height "11" :rx "2"}]
   [:line {:x1 "5.75" :y1 "2.5" :x2 "5.75" :y2 "13.5"}]])

(def svg-sun
  [:svg {:width "15" :height "15" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :stroke-linecap "round"}
   [:circle {:cx "8" :cy "8" :r "3"}]
   [:line {:x1 "8" :y1 "1.5" :x2 "8" :y2 "3"}]
   [:line {:x1 "8" :y1 "13" :x2 "8" :y2 "14.5"}]
   [:line {:x1 "1.5" :y1 "8" :x2 "3" :y2 "8"}]
   [:line {:x1 "13" :y1 "8" :x2 "14.5" :y2 "8"}]
   [:line {:x1 "3.4" :y1 "3.4" :x2 "4.5" :y2 "4.5"}]
   [:line {:x1 "11.5" :y1 "11.5" :x2 "12.6" :y2 "12.6"}]
   [:line {:x1 "12.6" :y1 "3.4" :x2 "11.5" :y2 "4.5"}]
   [:line {:x1 "4.5" :y1 "11.5" :x2 "3.4" :y2 "12.6"}]])

(def svg-moon
  [:svg {:width "14" :height "14" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d "M13.5 9.8A5.5 5.5 0 0 1 6.2 2.5a5.5 5.5 0 1 0 7.3 7.3Z"}]])

(def svg-chevron-left
  [:svg {:width "14" :height "14" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.75" :stroke-linecap "round" :stroke-linejoin "round"}
   [:polyline {:points "10 3.5 5.5 8 10 12.5"}]])

(def svg-chevron-right
  [:svg {:width "14" :height "14" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.75" :stroke-linecap "round" :stroke-linejoin "round"}
   [:polyline {:points "6 3.5 10.5 8 6 12.5"}]])

(def svg-arrow-up
  [:svg {:width "16" :height "16" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}
   [:line {:x1 "8" :y1 "13" :x2 "8" :y2 "3.5"}]
   [:polyline {:points "3.75 7.5 8 3.25 12.25 7.5"}]])

(defn interleave-timeline
  "Interleaves inbox messages and evals in true conversational order using `:seon.agent.eval/seen-messages`:
   messages consumed by turn T appear directly above turn T's evals/reply; unseen messages appear at the end.
   When `already-seen-ids` (from Datahike `d/history`) is provided, messages consumed by retracted/compacted
   evals are excluded from trailing unread bubbles."
  ([msgs evals] (interleave-timeline msgs evals nil))
  ([msgs evals already-seen-ids]
   (let [msgs-by-id (into {} (map (juxt :seon.agent.message/id identity)) msgs)
         by-turn (->> (group-by #(or (:seon.agent.eval/turn %) 0) evals)
                      (sort-by key))
         [items emitted-ids]
         (reduce
          (fn [[acc seen] [_turn es]]
            (let [ordered (sort-by #(or (:seon.agent.eval/call-index %) 0) es)
                  turn-seen-ids (->> (mapcat :seon.agent.eval/seen-messages ordered)
                                     distinct
                                     (remove seen))
                  turn-msgs (->> turn-seen-ids
                                 (keep #(get msgs-by-id %))
                                 (sort-by :seon.agent.message/seq))]
              [(-> acc
                   (into (map (fn [m] {:kind :msg :data m}) turn-msgs))
                   (into (map (fn [e] {:kind :eval :data e}) ordered)))
               (into seen (map :seon.agent.message/id turn-msgs))]))
          [[] #{}]
          by-turn)
         skip-ids (if (seq already-seen-ids) (into emitted-ids already-seen-ids) emitted-ids)
         trailing (->> msgs
                       (remove #(contains? skip-ids (:seon.agent.message/id %)))
                       (sort-by :seon.agent.message/seq)
                       (map (fn [m] {:kind :msg :data m})))]
     (into items trailing))))

(defn prompt-msg? [item]
  (and (= :msg (:kind item))
       (let [d (:data item)]
         (or (= :user (:seon.agent.message/from d))
             (contains? #{:delegate :task} (:seon.agent.message/type d))))))

(defn split-exchanges
  "Groups an interleaved timeline into conversational exchanges (each starting at a user/task/delegate prompt)."
  [timeline]
  (reduce
   (fn [acc item]
     (if (prompt-msg? item)
       (conj acc [item])
       (if (seq acc)
         (conj (pop acc) (conj (peek acc) item))
         [[item]])))
   []
   timeline))

(defn- compact-chars-label [n]
  (if (>= n 1000)
    (str (/ (Math/round (/ (double n) 100.0)) 10.0) "k chars")
    (str n " chars")))

(defn- lead-snippet
  "Extracts a crisp, single-line opening teaser snapped at a word boundary."
  [s max-chars]
  (let [clean (str/replace (str/trim (str (or s ""))) #"\s+" " ")]
    (if (<= (count clean) max-chars)
      clean
      (let [cut (subs clean 0 max-chars)
            sp (.lastIndexOf cut " ")
            snapped (if (> sp (long (* 0.65 max-chars))) (subs cut 0 sp) cut)]
        (str (str/trimr snapped) "…")))))

(defn- render-bounded-payload
  "Renders code/stdout/result inside an expanded action step: short payloads inline, long payloads
   as an opening preview + nested `Show full ...` fold so huge forms or EDN results never flood the view."
  [text {:keys [id label max-chars max-lines extra-class prefix]}]
  (let [s (str (or text ""))
        n (count s)
        lines (str/split-lines s)
        line-n (count lines)
        full-text (str (or prefix "") s)
        cls (str "code-box tiny bounded-box" (when extra-class (str " " extra-class)))]
    (if (and (<= n max-chars) (<= line-n max-lines))
      [:pre {:class cls} [:code full-text]]
      (let [by-lines (str/join "\n" (take max-lines lines))
            preview (if (> (count by-lines) max-chars)
                      (str (subs by-lines 0 max-chars) "\n…")
                      (str by-lines "\n…"))]
        [:div {:class "stack-xs"}
         [:pre {:class (str "code-box tiny preview-box" (when extra-class (str " " extra-class)))}
          [:code (str (or prefix "") preview)]]
         [:details {:id id :class "nested-fold"}
          [:summary {:class "nested-summary"}
           (str "Show full " label " (" (compact-chars-label n)
                (when (> line-n 1) (str " · " line-n " lines")) ")")]
          [:pre {:class cls} [:code full-text]]]]))))

(defn- step-meta-row [e err-str]
  (let [eid (:seon.agent.eval/id e)
        h (:seon.agent.eval/handle e)
        rs (:seon.agent.eval/result-schema e)
        turn (:seon.agent.eval/turn e)]
    (when (or h eid rs err-str)
      [:div {:class "step-meta-row"}
       (cond
         (and eid h) (rlinks/ent-link [:seon.agent.eval/id eid] (str h) "mono tiny muted")
         h           [:span {:class "mono tiny muted"} (str h)]
         eid         (rlinks/ent-link [:seon.agent.eval/id eid] (str "turn " (or turn "—")) "mono tiny muted"))
       (when rs
         (if (qualified-keyword? rs)
           (rlinks/ent-link [:seon.schema/key rs] (name rs) "badge green tiny mono")
           [:span {:class "badge green tiny mono"} (str rs)]))
       (when err-str
         [:span {:class "badge red tiny mono"} "error"])])))

(defn- activity-sub-drawer [id label lead-node meta-str body-node & [{:keys [extra-class]}]]
  [:details {:id id :class (str "sub-fold" (when extra-class (str " " extra-class)))}
   [:summary {:class "sub-summary"}
    [:span {:class "sub-label"} label]
    (when lead-node
      [:span {:class (str "sub-lead" (when extra-class (str " " extra-class)))} lead-node])
    (when (not (str/blank? (str meta-str)))
      [:span {:class "muted tiny mono sub-meta"} meta-str])]
   [:div {:class "sub-body"} body-node]])

(def svg-eye
  [:svg {:width "15" :height "15" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d "M1.5 8s2.4-4.25 6.5-4.25S14.5 8 14.5 8s-2.4 4.25-6.5 4.25S1.5 8 1.5 8Z"}]
   [:circle {:cx "8" :cy "8" :r "2"}]])

(def svg-eye-off
  [:svg {:width "15" :height "15" :viewBox "0 0 16 16" :fill "none" :stroke "currentColor" :stroke-width "1.5" :stroke-linecap "round" :stroke-linejoin "round"}
   [:path {:d "M2 2l12 12"}]
   [:path {:d "M6.6 6.6A2 2 0 0 0 9.4 9.4"}]
   [:path {:d "M4.1 4.4C2.6 5.5 1.5 8 1.5 8s2.4 4.25 6.5 4.25c1.3 0 2.4-.35 3.4-.9"}]
   [:path {:d "M6.5 3.9c.5-.1 1-.15 1.5-.15 4.1 0 6.5 4.25 6.5 4.25s-.8 1.4-2.1 2.5"}]])

(defn- activity-step-facts
  "Pure derivation of a single activity step's display facts (code/narration split, ids,
   title, right-meta). Extracted so step rendering stays small and testable."
  [e id-prefix idx]
  (let [code-str (str (or (:seon.agent.eval/code e) ""))
        has-code? (not (str/blank? code-str))
        intent-raw (not-empty (str/trim (str (or (:seon.agent.eval/intent e) ""))))
        narration (when has-code? (not-empty (str/trim (str (or (:seon.agent.eval/thought e) "")))))
        reasoning (not-empty (str/trim (str (or (:seon.agent.eval/reasoning e) ""))))
        out-str (not-empty (str/trim (str (or (:seon.agent.eval/out e) ""))))
        val-str (not-empty (str (or (:seon.agent.eval/value e) "")))
        err-str (not-empty (str (or (:seon.agent.eval/error e) "")))
        ms-val (:seon.agent.eval/ms e)
        step-id (str id-prefix "-step-" idx)
        step-title (lead-snippet
                    (or intent-raw
                        narration
                        (when has-code? (first (str/split-lines code-str)))
                        (when reasoning "Final reasoning")
                        "Step")
                    112)
        show-note? (and narration (not= narration intent-raw))
        code-lines (when has-code? (count (str/split-lines code-str)))
        right-meta (cond
                     (and has-code? (some? ms-val)) (str ms-val " ms")
                     reasoning (compact-chars-label (count reasoning))
                     :else nil)]
    {:code-str code-str :has-code? has-code? :narration narration :reasoning reasoning
     :out-str out-str :val-str val-str :err-str err-str :step-id step-id :step-title step-title
     :show-note? show-note? :code-lines code-lines :right-meta right-meta}))

(defn- activity-step-subs
  "Renders the connected sub-drawer list (meta row + Thinking/Note/Code/Output/Error/Result)
   for one activity step from its precomputed facts."
  [e {:keys [step-id reasoning narration show-note? has-code? code-str code-lines out-str err-str val-str]}]
  [:div {:class "step-subs"}
   (step-meta-row e err-str)
   (when reasoning
     (activity-sub-drawer (str step-id "-reason") "Thinking"
                          (lead-snippet reasoning 96)
                          (compact-chars-label (count reasoning))
                          [:pre {:class "code-box tiny bounded-box" :style "white-space:pre-wrap"} reasoning]))
   (when show-note?
     (activity-sub-drawer (str step-id "-note") "Note"
                          (lead-snippet narration 96)
                          (compact-chars-label (count narration))
                          [:pre {:class "code-box tiny bounded-box" :style "white-space:pre-wrap"} narration]))
   (when has-code?
     (activity-sub-drawer (str step-id "-code") "Code"
                          (lead-snippet (first (str/split-lines code-str)) 84)
                          (if (> code-lines 1)
                            (str code-lines " lines")
                            (compact-chars-label (count code-str)))
                          (render-bounded-payload code-str
                                                  {:id (str step-id "-code-full") :label "code"
                                                   :max-chars 520 :max-lines 12})))
   (when out-str
     (activity-sub-drawer (str step-id "-out") "Output"
                          (lead-snippet out-str 84)
                          (compact-chars-label (count out-str))
                          (render-bounded-payload out-str
                                                  {:id (str step-id "-out-full") :label "output"
                                                   :max-chars 420 :max-lines 10})))
   (when err-str
     (activity-sub-drawer (str step-id "-err") "Error"
                          (lead-snippet err-str 90)
                          (compact-chars-label (count err-str))
                          (if (<= (count err-str) 600)
                            [:div {:class "notice red mono tiny"} err-str]
                            (render-bounded-payload err-str {:id (str step-id "-err-full") :label "error"
                                                             :max-chars 520 :max-lines 10 :extra-class "red-text"}))
                          {:extra-class "is-error"}))
   (when (and val-str (not err-str))
     (activity-sub-drawer (str step-id "-val") "Result"
                          (str "=> " (lead-snippet val-str 84))
                          (compact-chars-label (count val-str))
                          (render-bounded-payload val-str
                                                  {:id (str step-id "-val-full") :label "result"
                                                   :max-chars 460 :max-lines 10
                                                   :extra-class "green-text" :prefix "=> "})
                          {:extra-class "is-result"}))])

(defn- render-activity-step
  "Renders one collapsible activity step: summary row plus an expanded drawer that is either a
   bare reasoning block (thought-only step) or the connected sub-drawer list."
  [e id-prefix idx]
  (let [f (activity-step-facts e id-prefix idx)
        {:keys [step-id step-title has-code? reasoning err-str right-meta]} f]
    [:details {:id step-id :class (str "step-row" (when err-str " has-error") (when (not has-code?) " is-thought"))}
     [:summary {:class "step-summary"}
      [:span {:class "step-title"} step-title]
      (when err-str
        [:span {:class "step-err-tag"} "error"])
      (when right-meta
        [:span {:class "step-ms"} right-meta])]
     [:div {:class "step-drawer"}
      (if (and (not has-code?) reasoning)
        [:pre {:class "code-box tiny bounded-box" :style "white-space:pre-wrap"} reasoning]
        (activity-step-subs e f))]]))

(defn- activity-fold-summary-text
  "Builds the collapsed pill label: step count, latest intent/thought teaser, and total ms."
  [action-evals]
  (let [total-ms (Math/round (double (reduce + 0.0 (map #(or (:seon.agent.eval/ms %) 0.0) action-evals))))
        n (count action-evals)
        last-e (peek (vec action-evals))
        last-msg (or (not-empty (str/trim (str (or (:seon.agent.eval/intent last-e) ""))))
                     (not-empty (str/trim (str (or (:seon.agent.eval/thought last-e) "")))))
        short-msg (when last-msg (lead-snippet last-msg 76))]
    (str "Thinking & actions · " n " step" (when (not= 1 n) "s")
         (when short-msg (str " · " short-msg))
         (when (pos? total-ms) (str " · " total-ms " ms")))))

(defn render-activity-fold
  "Chronological collapsed `▸ Thinking & actions` summary pill placed ABOVE the response prose
   (both in Canvas and in Workspace). When expanded, renders a single connected vertical list
   of steps showing only the eval description in readable UI typography and right-aligned
   tabular timing; clicking `▸` on any step opens handle metadata plus separately expandable
   drawers for Thinking, Note, Code, Output, and Result/Error."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]] :string] :any]}
  [action-evals id-prefix]
  (when (seq action-evals)
    [:details {:id (str id-prefix "-act") :class "activity-fold"}
     [:summary
      [:span (activity-fold-summary-text action-evals)]]
     [:div {:class "activity-list"}
      (for [[idx e] (map-indexed vector action-evals)]
        (render-activity-step e id-prefix idx))]]))

(defn resolve-backtick-ref
  "Resolves a backtick code token into a Seon Datahike lookup ref `[attr val]`, or nil
   (docs, qualified vars, `seon.*` namespaces, concrete entity keywords; skips `*` wildcards)."
  {:malli/schema [:function
                  {:examples [[["seon.web/navigate-url!"] [:seon.code.var/name (quote seon.web/navigate-url!)]]
                              [["seon.web.router"] [:seon.code.ns/name (quote seon.web.router)]]
                              [[":seon.summary/*"] nil] [["c"] nil]
                              [["docs/render.md#walk"] [:seon.code.doc/id "docs/render.md#walk"]]]}
                  [:=> [:cat [:maybe :string]] [:maybe [:vector :any]]]
                  [:=> [:cat [:maybe :string] :any] [:maybe [:vector :any]]]]
   :cost/ticks 500000 :cost/bytes 262144}
  ([token] (resolve-backtick-ref token nil))
  ([token db]
   (let [t (str/trim (str (or token "")))]
     (when (and (pos? (count t)) (not (re-find #"\s" t)))
       (cond
         (re-matches #"^(?:docs/[a-zA-Z0-9_./-]+\.md|AGENTS\.md)(?:#[a-zA-Z0-9_.-]+)?$" t)
         [:seon.code.doc/id t]
         (re-matches #"^[a-z][a-z0-9_.-]+\.[a-z0-9_.-]+/[a-zA-Z0-9_*!?+<>=.-]+$" t)
         (let [[ns-p nm] (str/split t #"/" 2)]
           (when (not= nm "*") [:seon.code.var/name (symbol ns-p nm)]))
         (and (str/starts-with? t "seon.")
              (re-matches #"^seon\.[a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)*$" t)
              (not (re-find #"\.(?:clj|cljs|cljc|edn|md|js|css|html)$" t)))
         [:seon.code.ns/name (symbol t)]
         (and (str/starts-with? t ":") (not (str/includes? t "*")))
         (when-let [[_ ns-p nm] (re-matches #"^:([a-z][a-z0-9_.-]*)/([a-zA-Z0-9_!?+<>=.-]+)$" t)]
           (let [kw (keyword ns-p nm)
                 dh (when (and db (instance? datahike.db.interface.ISearch db)) (rlinks/db-schema db))]
             (or (when dh
                   (some #(when (and (contains? dh %) (first (d/datoms db :avet % kw))) [% kw])
                         [:seon.task/id :seon.rule/id :seon.agent/id :seon.schema/key]))
                 (cond (#{"seon.task" "task"} ns-p) [:seon.task/id kw]
                       (or (= ns-p "rule") (str/starts-with? ns-p "rule.")) [:seon.rule/id kw]
                       (#{"agent" "ns"} ns-p) [:seon.agent/id kw]
                       (str/starts-with? ns-p "seon.") [:seon.schema/key kw])))))))))

(def ^:private copy-code-js
  "const p=evt.currentTarget.closest('.md-code-block,.md-live-card');const c=p&&p.querySelector('pre code');if(c&&navigator.clipboard){navigator.clipboard.writeText(c.textContent||'');const b=evt.currentTarget;b.textContent='Copied';setTimeout(()=>{b.textContent='Copy';},1200);}")

(defn- copy-code-btn []
  [:button {:type "button" :class "md-code-btn" :title "Copy snippet" :on-click copy-code-js} "Copy"])

(defn- render-diff-block [content]
  [:div {:class "md-code-block md-diff-block"}
   [:div {:class "md-code-head"}
    [:span {:class "md-code-lang"} "diff"]
    [:div {:class "md-code-actions"} (copy-code-btn)]]
   [:pre {:class "code-box tiny md-diff"}
    (into [:code]
          (mapv (fn [ln]
                  (let [cls (cond (or (str/starts-with? ln "@@") (str/starts-with? ln "diff ")
                                      (str/starts-with? ln "+++") (str/starts-with? ln "---")) "diff-line diff-hunk"
                                  (str/starts-with? ln "+") "diff-line diff-add"
                                  (str/starts-with? ln "-") "diff-line diff-del"
                                  :else "diff-line diff-ctx")]
                    [:span {:class cls} (str ln "\n")]))
                (str/split (str (or content "")) #"\n" -1)))]])

(defn- render-clojure-block [lang content]
  [:div {:class "md-code-block md-clj-block"}
   [:div {:class "md-code-head"}
    [:span {:class "md-code-lang"} lang]
    [:div {:class "md-code-actions"}
     (copy-code-btn)
     [:button {:type "button"
               :class "md-code-btn md-run-btn"
               :title "Run snippet in active agent branch REPL"
               :on-click (list (quote seon.web/eval-snippet!) (str (or content "")))}
      "▶ Run in REPL"]]]
   [:pre {:class "code-box tiny"}
    [:code {:class (str "language-" lang)} (str (or content ""))]]])

(defn- render-hiccup-block [content]
  (let [raw (str (or content ""))
        parsed (mdp/safe-read-edn raw)
        safe (when (:ok? parsed) (mdp/sanitize-hiccup (:val parsed)))]
    [:div {:class "md-live-card md-hiccup-card"}
     [:div {:class "md-live-head"}
      [:span {:class "badge purple tiny mono"} "live hiccup"]
      [:div {:class "md-code-actions"} (copy-code-btn)]]
     (if (and (:ok? parsed) (some? safe))
       [:div {:class "md-live-body"} safe]
       [:div {:class "notice red mono tiny"}
        (if (:ok? parsed) "Hiccup block contained no whitelisted tags." (str "Invalid Hiccup EDN: " (:error parsed)))])
     [:details {:class "md-live-source"}
      [:summary {:class "mono tiny muted"} "View Hiccup Source"]
      [:pre {:class "code-box tiny"} [:code {:class "language-clojure"} raw]]]]))

(defn- invoke-seon-view-var [var-sym data]
  (let [ns-s (when (qualified-symbol? var-sym) (namespace var-sym))]
    (if-not (and ns-s (or (= ns-s "seon.render") (str/starts-with? ns-s "seon.render.")))
      {:ok? false :error (str "Renderer var must be in seon.render.*: " var-sym)}
      (try
        (if-let [v (requiring-resolve var-sym)]
          (if (or (:macro (meta v)) (not (ifn? @v)))
            {:ok? false :error (str "Not a callable renderer var: " var-sym)}
            (let [sch (:malli/schema (meta v))
                  cf (when (and (vector? sch) (= :=> (first sch))) (if (map? (second sch)) (nth sch 2 nil) (second sch)))
                  a0 (when (and (vector? cf) (= :cat (first cf))) (second cf))
                  ek (when (and (vector? a0) (= :map (first a0)))
                       (some #(when (and (vector? %) (qualified-keyword? (first %))) (first %))
                             (if (map? (second a0)) (drop 2 a0) (rest a0))))
                  res (@v (if (and (map? data) ek (not (contains? data ek))) {ek data} (or data {})))
                  h (or (when (map? res) (:seon.ui/hiccup res)) (when (and (vector? res) (keyword? (first res))) res))]
              (if h {:ok? true :hiccup h} {:ok? false :error (str "Renderer " var-sym " returned no Hiccup.")})))
          {:ok? false :error (str "Renderer var not found: " var-sym)})
        (catch Throwable ex {:ok? false :error (or (.getMessage ex) (str ex))})))))

(defn- render-seon-view-block [content opts]
  (let [raw (str (or content ""))
        parsed (mdp/safe-read-edn raw)
        spec (when (and (:ok? parsed) (map? (:val parsed))) (:val parsed))
        rv (:var spec)
        rv (if (and (seq? rv) (= (quote quote) (first rv))) (second rv) rv)
        var-sym (when (qualified-symbol? rv) rv)
        ref (when (:ref spec) (rdata/normalize-inspect-ref (:ref spec)))
        db (:db opts)
        pulled (when (and db (instance? datahike.db.interface.ISearch db) ref)
                 (try (not-empty (dissoc (d/pull db (quote [*]) ref) :db/id))
                      (catch Throwable ex {:seon.view/error (or (.getMessage ex) (str ex))})))
        valid-pull (when (and (map? pulled) (not (:seon.view/error pulled))) pulled)
        head [:div {:class "md-live-head"}
              [:div {:class "pill-row"}
               [:span {:class "badge amber tiny mono"} "seon-view"]
               (when var-sym (rlinks/ent-link [:seon.code.var/name var-sym] (str var-sym) "badge blue tiny mono"))]
              (when ref (rlinks/ent-link ref (str "Inspect " (rdata/entity-short-label ref) " →") "mono tiny muted"))]]
    [:div {:class "md-live-card md-seon-view-card"}
     head
     (cond
       (not spec)
       [:div {:class "notice red mono tiny"}
        (if (:ok? parsed) "seon-view expects {:var ns/fn :data {...}} or {:ref [:attr val]}." (str "Invalid seon-view EDN: " (:error parsed)))]
       (and var-sym (or (contains? spec :data) valid-pull))
       (let [res (invoke-seon-view-var var-sym (if (contains? spec :data) (:data spec) valid-pull))]
         (if (:ok? res) [:div {:class "md-live-body"} (:hiccup res)] [:div {:class "notice red mono tiny"} (:error res)]))
       ref
       [:div {:class "md-live-body stack-xs"}
        (rlinks/ent-link ref (rdata/entity-short-label (or valid-pull ref)) "mono strong")
        (when valid-pull [:div {:class "mono tiny muted"} (rdata/compact-entity-preview valid-pull 180)])]
       var-sym
       [:div {:class "md-live-body"} (rlinks/ent-link [:seon.code.var/name var-sym] (str var-sym) "mono strong")]
       :else
       [:div {:class "notice red mono tiny"} "seon-view requires :var and/or :ref."])
     [:details {:class "md-live-source"}
      [:summary {:class "mono tiny muted"} "View Spec"]
      [:pre {:class "code-box tiny"} [:code {:class "language-clojure"} raw]]]]))

(defn- render-code-block [{:keys [info content]} opts]
  (let [lang-raw (first (str/split (str/trim (str (or info ""))) #"\s+"))
        lang (str/lower-case (str (or lang-raw "")))
        body (str (or content ""))]
    (case lang
      "diff" (render-diff-block body)
      ("clojure" "clj" "repl") (render-clojure-block lang body)
      "hiccup" (render-hiccup-block body)
      "seon-view" (render-seon-view-block body opts)
      [:div {:class "md-code-block"}
       [:div {:class "md-code-head"}
        [:span {:class "md-code-lang"} (if (str/blank? lang) "code" lang)]
        [:div {:class "md-code-actions"} (copy-code-btn)]]
       [:pre {:class "code-box tiny"}
        [:code (if (str/blank? lang) {} {:class (str "language-" lang)}) body]]])))

(defn- render-inline-node [node opts]
  (case (:t node)
    :str (:v node)
    :br [:br]
    :code (let [raw (:v node)
                ref (when (and (not (:in-link? opts)) (not (false? (:auto-link? opts)))) (resolve-backtick-ref raw (:db opts)))]
            (if ref
              [:a {:href (router/entity-ref->path (:db opts) ref) :class "ent-link md-code-link"
                   :data-ref (pr-str ref) :data-preview-ref (pr-str ref) :interestfor "seon-hovercard"
                   :on-click (list (quote seon.web/inspect-entity!) ref)}
               [:code {:class "inline-code"} raw]]
              [:code {:class "inline-code"} raw]))
    :strong (into [:strong] (mapv #(render-inline-node % opts) (:children node)))
    :em (into [:em] (mapv #(render-inline-node % opts) (:children node)))
    :del (into [:del] (mapv #(render-inline-node % opts) (:children node)))
    :image (let [{:keys [kind href]} (mdp/classify-url (:src node)) alt (str (or (:alt node) ""))]
             (if (#{:external :internal} kind)
               [:img (cond-> {:src href :alt alt :class "md-img" :loading "lazy"} (:title node) (assoc :title (:title node)))]
               [:span {:class "muted tiny"} (if (str/blank? alt) "[blocked image]" alt)]))
    :link (let [{:keys [kind href]} (mdp/classify-url (:href node))
                kids (mapv #(render-inline-node % (assoc opts :in-link? true)) (:children node))
                t (:title node)]
            (case kind
              :internal (let [r (:inspect-ref (router/path->show-user-op (:db opts) href))]
                          (into [:a (cond-> {:href href :class "ent-link md-link" :on-click (list (quote seon.web/navigate-url!) href)}
                                      t (assoc :title t) r (assoc :data-ref (pr-str r) :data-preview-ref (pr-str r) :interestfor "seon-hovercard"))] kids))
              :external (into [:a (cond-> {:href href :target "_blank" :rel "noopener noreferrer" :class "md-ext-link"} t (assoc :title t))] kids)
              (:mailto :fragment) (into [:a (cond-> {:href href :class "md-ext-link"} t (assoc :title t))] kids)
              (into [:span (cond-> {:class "md-unsafe-link"} t (assoc :title t))] kids)))
    ""))

(defn render-inline-prose
  "Formats inline CommonMark + GFM markup (`**bold**`, `*italic*`, `~~strike~~`, deep-linked `` `code` ``,
   interactive `[links](url)`, safe `![images](src)`, hard breaks) into Hiccup with zero raw HTML."
  {:malli/schema [:function
                  {:examples [[["plain"] "plain"] [["**bold**"] [:span [:strong "bold"]]] [["~~crossed out~~"] [:span [:del "crossed out"]]]]}
                  [:=> [:cat :any] :any] [:=> [:cat :any [:maybe :map]] :any]]
   :cost/ticks 2000000 :cost/bytes 4194304}
  ([s] (render-inline-prose s {}))
  ([s opts]
   (let [text (str s) o (if (map? opts) opts {})]
     (if (str/blank? text) text
         (let [nodes (mapv #(render-inline-node % o) (mdp/parse-inlines text))]
           (cond (empty? nodes) text
                 (and (= 1 (count nodes)) (string? (first nodes))) (first nodes)
                 :else (vec (cons :span nodes))))))))

(defn- render-block-node [node opts]
  (case (:t node)
    :heading (let [lvl (max 1 (min 6 (long (or (:level node) 3))))]
               [(keyword (str "h" lvl)) {:class (str "prose-h" lvl)} (render-inline-prose (:text node) opts)])
    :para [:p {:class "prose-p"} (render-inline-prose (:text node) opts)]
    :hr [:hr {:class "prose-hr"}]
    :code-block (render-code-block node opts)
    :blockquote (into [:blockquote {:class "md-quote"}] (mapv #(render-block-node % opts) (:children node)))
    :alert (let [k (or (:kind node) :note)]
             (into [:aside {:class (str "md-alert md-alert-" (name k))}
                    [:div {:class "md-alert-title"} (str/upper-case (name k))]]
                   (mapv #(render-block-node % opts) (:children node))))
    :table (let [als (:align node)
                 ca #(when-let [a (get als %)] {:style {:text-align (name a)}})]
             [:div {:class "table-scroll md-table-wrap"}
              [:table {:class "md-table"}
               [:thead (into [:tr] (map-indexed (fn [i h] [:th (or (ca i) {}) (render-inline-prose h opts)]) (:header node)))]
               [:tbody (mapv (fn [row] (into [:tr] (map-indexed (fn [i c] [:td (or (ca i) {}) (render-inline-prose c opts)]) row))) (:rows node))]]])
    :list (let [tag (if (:ordered node) :ol :ul)
                attrs (cond-> {:class (if (:has-tasks? node) "prose-list has-tasks" "prose-list")}
                        (and (:ordered node) (:start node) (not= 1 (:start node))) (assoc :start (:start node)))
                tight? (:tight node)]
            (into [tag attrs]
                  (mapv (fn [{:keys [checked children]}]
                          (let [chk (when (some? checked) [:input {:type "checkbox" :class "md-task-check" :disabled true :checked (boolean checked)}])
                                li (if (some? checked) [:li {:class (str "md-task-item" (when checked " is-done"))}] [:li])
                                body (if (and tight? (= 1 (count children)) (= :para (:t (first children))))
                                       [(render-inline-prose (:text (first children)) opts)]
                                       (mapv #(render-block-node % opts) children))]
                            (into li (if chk (cons chk body) body))))
                        (:items node))))
    [:p {:class "prose-p"} (str node)]))

(defn render-markdown-blocks
  "Pure CommonMark + GFM -> interactive Hiccup block renderer (headings, tables, alerts, task lists,
   backtick deep-links, safe links/images, and `diff`/`clojure`/`hiccup`/`seon-view` blocks)."
  {:malli/schema [:function [:=> [:cat :any] :any] [:=> [:cat :any [:maybe :map]] :any]]
   :cost/ticks 5000000 :cost/bytes 8388608}
  ([s] (render-markdown-blocks s {}))
  ([s opts]
   (let [o (if (map? opts) opts {})
         blocks (mapv #(render-block-node % o) (mdp/parse-document (str (or s ""))))]
     (if (= 1 (count blocks)) (first blocks) (into [:div {:class "prose-blocks"}] blocks)))))