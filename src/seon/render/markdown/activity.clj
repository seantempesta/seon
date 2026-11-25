(ns seon.render.markdown.activity
  "Timeline interleaving, exchange splitting, and progressive activity-fold Hiccup renderers."
  (:require [clojure.string :as str]
            [seon.render.links :as rlinks]))

(defn interleave-timeline
  "Interleaves inbox messages and evals in true conversational order using `:seon.agent.eval/seen-messages`:
   messages consumed by turn T appear directly above turn T's evals/reply; unseen messages appear at the end.
   When `already-seen-ids` (from Datahike `d/history`) is provided, messages consumed by retracted/compacted
   evals are excluded from trailing unread bubbles."
  {:malli/schema [:function [:=> {:examples [[[[{:seon.agent.message/id :m1, :seon.agent.message/seq 1}] []] [{:kind :msg, :data {:seon.agent.message/id :m1, :seon.agent.message/seq 1}}]]]} [:cat [:sequential :map] [:sequential :map]] [:vector :map]] [:=> [:cat [:sequential :map] [:sequential :map] [:maybe [:set :any]]] [:vector :map]]], :cost/ticks 2000000}
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

(defn prompt-msg?
  {:malli/schema [:=> {:examples [[[{:kind :msg, :data {:seon.agent.message/from :user}}] true] [[{:kind :eval, :data {}}] false]]} [:cat [:maybe :map]] :boolean], :cost/ticks 50000}
  [item]
  (and (= :msg (:kind item))
       (let [d (:data item)]
         (or (= :user (:seon.agent.message/from d))
             (contains? #{:delegate :task} (:seon.agent.message/type d))))))

(defn split-exchanges
  "Groups an interleaved timeline into conversational exchanges (each starting at a user/task/delegate prompt)."
  {:malli/schema [:=> {:examples [[[[{:kind :msg, :data {:seon.agent.message/from :user}} {:kind :eval, :data {}}]] [[{:kind :msg, :data {:seon.agent.message/from :user}} {:kind :eval, :data {}}]]] [[[]] []]]} [:cat [:sequential :map]] [:vector [:vector :map]]], :cost/ticks 500000}
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

(defn- activity-step-facts
  "Derives display facts for one activity step."
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
  "Renders the sub-drawer list for one activity step."
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
  "Renders one collapsible activity step drawer."
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
  "Collapsed `▸ Thinking & actions` fold above prose with expandable per-step drawers."
  {:malli/schema [:=> [:cat [:maybe [:sequential :map]] :string] :any]}
  [action-evals id-prefix]
  (when (seq action-evals)
    [:details {:id (str id-prefix "-act") :class "activity-fold"}
     [:summary
      [:span (activity-fold-summary-text action-evals)]]
     [:div {:class "activity-list"}
      (for [[idx e] (map-indexed vector action-evals)]
        (render-activity-step e id-prefix idx))]]))