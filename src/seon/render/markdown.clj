(ns seon.render.markdown
  "Pure Clojure Markdown -> Hiccup renderer, timeline interleaving, activity folds, and Phosphor SVG icons."
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
            [seon.render.data :as rdata]))

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

(defn render-activity-fold
  "Chronological collapsed `▸ Thinking & actions` summary pill placed ABOVE the response prose
   (both in Canvas and in Workspace). Collapsed by default so typography and visuals breathe."
  [action-evals id-prefix]
  (when (seq action-evals)
    (let [total-ms (Math/round (double (reduce + 0.0 (map #(or (:seon.agent.eval/ms %) 0.0) action-evals))))
          n (count action-evals)]
      [:details {:id (str id-prefix "-act") :class "activity-fold"}
       [:summary
        [:span (str "Thinking & actions · " n " step" (when (not= 1 n) "s")
                    (when (pos? total-ms) (str " · " total-ms " ms")))]]
       [:div {:class "activity-body"}
        (for [[idx e] (map-indexed vector action-evals)
              :let [has-code? (not (str/blank? (str (:seon.agent.eval/code e))))
                    narration (when has-code? (not-empty (str/trim (str (:seon.agent.eval/thought e "")))))]]
          [:div {:id (str id-prefix "-step-" idx) :class "stack-sm"}
           (when narration
             [:p {:class "muted tiny" :style "margin:0"} narration])
           (when (not (str/blank? (str (:seon.agent.eval/reasoning e))))
             [:pre {:class "code-box tiny" :style "white-space:pre-wrap"} (:seon.agent.eval/reasoning e)])
           (when has-code?
             [:details {:class "eval-fold"}
              [:summary {:class "eval-summary"}
               [:span {:class "badge mono handle-pill"} (str (or (:seon.agent.eval/handle e) 'result/e?))]
               (when-let [rs (:seon.agent.eval/result-schema e)]
                 [:span {:class "badge green tiny mono"} (name rs)])
               [:span {:class "mono tiny truncate"}
                (or (not-empty (:seon.agent.eval/intent e)) (:seon.agent.eval/code e))]
               [:span {:class (str "mono tiny " (if (:seon.agent.eval/error e) "red-text" "green-text"))}
                (if (:seon.agent.eval/error e)
                  "error"
                  (let [vs (str (:seon.agent.eval/value e ""))]
                    (str "=> " (if (> (count vs) 56) (str (subs vs 0 56) "…") vs))))]
               [:span {:class "muted tiny mono"} (str (:seon.agent.eval/ms e 0.0) " ms")]]
              [:div {:class "eval-detail"}
               [:pre {:class "code-box tiny"} [:code (:seon.agent.eval/code e)]]
               (when-let [err (:seon.agent.eval/error e)]
                 [:div {:class "notice red mono tiny"} err])
               (when (not (str/blank? (str (:seon.agent.eval/out e))))
                 [:pre {:class "code-box tiny"} [:code (:seon.agent.eval/out e)]])
               (when-let [v (:seon.agent.eval/value e)]
                 [:pre {:class "code-box tiny green-text"} [:code (str "=> " v)]])]])])]])))
