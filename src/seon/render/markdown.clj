(ns seon.render.markdown
  "Pure Clojure Markdown -> Hiccup renderer, timeline interleaving, activity folds, and Phosphor SVG icons."
  (:require [clojure.string :as str]
            [seon.render :as render]))

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

(defn render-tile-card
  ([branch-db tile agent-id] (render-tile-card branch-db nil tile agent-id))
  ([branch-db main-db {:keys [id source handle ref val]} agent-id]
   [:div {:id id :class "tile-wrap"}
    [:div {:class "tile-meta"}
     [:div {:class "pill-row"}
      [:span {:class (str "badge " (case source :eval "green" :pinned "amber" :ctx "amber" :child "blue" "muted"))}
       (name source)]
      (when handle
        [:span {:class "badge mono handle-pill"} (str handle)])]
     (when (and (= source :pinned) ref)
       [:button {:type "button" :class "link-muted tiny stage-remove"
                 :on-click (list 'seon.web/unpin-tile! agent-id ref)}
        "× unpin"])]
    (render/render-value branch-db val {:agent-id agent-id :main-db main-db})]))

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

(defn render-emphasis
  "Parses `**bold**`, `*italic*`, and `~~strikethrough~~` inside non-code prose segments into Hiccup nodes."
  [^String s]
  (if (or (str/blank? s) (not (or (str/includes? s "*") (str/includes? s "~~"))))
    [s]
    (let [pat #"\*\*([^\s*](?:[^*]*?[^\s*])?)\*\*|\*([^\s*](?:[^*]*?[^\s*])?)\*|~~([^\s~](?:[^~]*?[^\s~])?)~~"
          m (re-matcher pat s)]
      (loop [pos 0 acc []]
        (if (.find m)
          (let [start (.start m)
                end (.end m)
                bold (.group m 1)
                ital (.group m 2)
                strike (.group m 3)
                acc (if (> start pos) (conj acc (subs s pos start)) acc)
                node (cond bold [:strong bold]
                           ital [:em ital]
                           strike [:del strike])]
            (recur end (conj acc node)))
          (if (< pos (count s))
            (conj acc (subs s pos))
            acc))))))

(defn render-inline-prose
  "Formats inline backtick code spans, `**bold**`, and `*italic*` inside editorial prose."
  [s]
  (let [text (str s)
        parts (str/split text #"`" -1)
        nodes (vec
               (mapcat (fn [[i seg]]
                         (if (and (odd? i) (< i (dec (count parts))))
                           [[:code {:class "inline-code"} seg]]
                           (render-emphasis seg)))
                       (map-indexed vector parts)))]
    (if (and (= 1 (count nodes)) (string? (first nodes)))
      (first nodes)
      (vec (cons :span nodes)))))

(defn render-markdown-blocks
  "Lightweight pure-Clojure Markdown -> Hiccup block renderer for editorial Canvas & Workspace prose:
   handles paragraphs, bullet lists (`- ` / `* `), numbered lists (`1. `), headings (`#`/`##`/`###`),
   fenced code blocks (```), inline `` `code` ``, `**bold**`, and `*italic*`."
  [s]
  (let [lines (str/split-lines (str (or s "")))
        flush-para (fn [acc para]
                     (if (seq para)
                       (let [joined (vec (interpose [:br] (map render-inline-prose para)))]
                         (conj acc (vec (cons :p (cons {:class "prose-p"} joined)))))
                       acc))
        flush-list (fn [acc ltype items]
                     (if (seq items)
                       (conj acc (vec (cons ltype (cons {:class "prose-list"}
                                                        (mapv (fn [it] [:li (render-inline-prose it)]) items)))))
                       acc))
        blocks
        (loop [ls lines
               acc []
               para []
               ltype nil
               litems []
               in-code? false
               code-lines []]
          (if (empty? ls)
            (let [acc (if in-code?
                        (conj acc [:pre {:class "code-box tiny"} [:code (str/join "\n" code-lines)]])
                        acc)]
              (-> acc (flush-para para) (flush-list ltype litems)))
            (let [line (first ls)
                  trimmed (str/trim line)
                  rest-ls (rest ls)]
              (cond
                in-code?
                (if (str/starts-with? trimmed "```")
                  (recur rest-ls
                         (conj acc [:pre {:class "code-box tiny"} [:code (str/join "\n" code-lines)]])
                         [] nil [] false [])
                  (recur rest-ls acc [] nil [] true (conj code-lines line)))

                (str/starts-with? trimmed "```")
                (recur rest-ls (-> acc (flush-para para) (flush-list ltype litems))
                       [] nil [] true [])

                (str/blank? trimmed)
                (recur rest-ls (-> acc (flush-para para) (flush-list ltype litems))
                       [] nil [] false [])

                (re-find #"^#{1,3}\s+" trimmed)
                (let [heading (str/replace-first trimmed #"^#{1,3}\s+" "")]
                  (recur rest-ls
                         (-> acc (flush-para para) (flush-list ltype litems)
                             (conj [:h3 {:class "prose-h3"} (render-inline-prose heading)]))
                         [] nil [] false []))

                (re-find #"^[-*]\s+" trimmed)
                (let [item (str/replace-first trimmed #"^[-*]\s+" "")
                      acc (cond-> (flush-para acc para)
                            (and ltype (not= ltype :ul)) (flush-list ltype litems))]
                  (recur rest-ls acc [] :ul (if (= ltype :ul) (conj litems item) [item]) false []))

                (re-find #"^\d+\.\s+" trimmed)
                (let [item (str/replace-first trimmed #"^\d+\.\s+" "")
                      acc (cond-> (flush-para acc para)
                            (and ltype (not= ltype :ol)) (flush-list ltype litems))]
                  (recur rest-ls acc [] :ol (if (= ltype :ol) (conj litems item) [item]) false []))

                :else
                (recur rest-ls (flush-list acc ltype litems)
                       (conj para line) nil [] false [])))))]
    (if (= 1 (count blocks))
      (first blocks)
      (vec (cons :div (cons {:class "prose-blocks"} blocks))))))

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

(defn render-activity-fold
  "Chronological collapsed `▸ Thinking & actions` summary pill placed ABOVE the response prose
   (both in Canvas and in Workspace). When expanded, renders a single connected vertical list
   of steps showing only the eval description in readable UI typography and right-aligned
   tabular timing; clicking `▸` on any step opens handle metadata plus separately expandable
   drawers for Thinking, Note, Code, Output, and Result/Error."
  [action-evals id-prefix]
  (when (seq action-evals)
    (let [total-ms (Math/round (double (reduce + 0.0 (map #(or (:seon.agent.eval/ms %) 0.0) action-evals))))
          n (count action-evals)
          sub-drawer (fn [id label lead-node meta-str body-node & [{:keys [extra-class]}]]
                       [:details {:id id :class (str "sub-fold" (when extra-class (str " " extra-class)))}
                        [:summary {:class "sub-summary"}
                         [:span {:class "sub-label"} label]
                         (when lead-node
                           [:span {:class (str "sub-lead" (when extra-class (str " " extra-class)))} lead-node])
                         (when (not (str/blank? (str meta-str)))
                           [:span {:class "muted tiny mono sub-meta"} meta-str])]
                        [:div {:class "sub-body"} body-node]])]
      [:details {:id (str id-prefix "-act") :class "activity-fold"}
       [:summary
        [:span (str "Thinking & actions · " n " step" (when (not= 1 n) "s")
                    (when (pos? total-ms) (str " · " total-ms " ms")))]]
       [:div {:class "activity-list"}
        (for [[idx e] (map-indexed vector action-evals)
              :let [code-str (str (or (:seon.agent.eval/code e) ""))
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
                                 :else nil)]]
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
              [:div {:class "step-subs"}
               (when (or (:seon.agent.eval/handle e) (:seon.agent.eval/result-schema e) err-str)
                 [:div {:class "step-meta-row"}
                  (when-let [h (:seon.agent.eval/handle e)]
                    [:span {:class "mono tiny muted"} (str h)])
                  (when-let [rs (:seon.agent.eval/result-schema e)]
                    [:span {:class "badge green tiny mono"} (name rs)])
                  (when err-str
                    [:span {:class "badge red tiny mono"} "error"])])
               (when reasoning
                 (sub-drawer (str step-id "-reason") "Thinking"
                             (lead-snippet reasoning 96)
                             (compact-chars-label (count reasoning))
                             [:pre {:class "code-box tiny bounded-box" :style "white-space:pre-wrap"} reasoning]))
               (when show-note?
                 (sub-drawer (str step-id "-note") "Note"
                             (lead-snippet narration 96)
                             (compact-chars-label (count narration))
                             [:pre {:class "code-box tiny bounded-box" :style "white-space:pre-wrap"} narration]))
               (when has-code?
                 (sub-drawer (str step-id "-code") "Code"
                             (lead-snippet (first (str/split-lines code-str)) 84)
                             (if (> code-lines 1)
                               (str code-lines " lines")
                               (compact-chars-label (count code-str)))
                             (render-bounded-payload code-str
                                                     {:id (str step-id "-code-full") :label "code"
                                                      :max-chars 520 :max-lines 12})))
               (when out-str
                 (sub-drawer (str step-id "-out") "Output"
                             (lead-snippet out-str 84)
                             (compact-chars-label (count out-str))
                             (render-bounded-payload out-str
                                                     {:id (str step-id "-out-full") :label "output"
                                                      :max-chars 420 :max-lines 10})))
               (when err-str
                 (sub-drawer (str step-id "-err") "Error"
                             (lead-snippet err-str 90)
                             (compact-chars-label (count err-str))
                             (if (<= (count err-str) 600)
                               [:div {:class "notice red mono tiny"} err-str]
                               (render-bounded-payload err-str {:id (str step-id "-err-full") :label "error"
                                                                :max-chars 520 :max-lines 10 :extra-class "red-text"}))
                             {:extra-class "is-error"}))
               (when (and val-str (not err-str))
                 (sub-drawer (str step-id "-val") "Result"
                             (str "=> " (lead-snippet val-str 84))
                             (compact-chars-label (count val-str))
                             (render-bounded-payload val-str
                                                     {:id (str step-id "-val-full") :label "result"
                                                      :max-chars 460 :max-lines 10
                                                      :extra-class "green-text" :prefix "=> "})
                             {:extra-class "is-result"}))])]])]])))