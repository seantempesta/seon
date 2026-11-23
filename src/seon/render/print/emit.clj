(ns seon.render.print.emit
  "Stage 4 of 4-Stage Printer: Single Hiccup Emitter (`tree->hiccup`) feeding both HTML and Markdown (`tree->md`)."
  (:require [clojure.string :as str]
            [seon.render.print.expand :as pexp]))

(defn- hiccup-children [node]
  (let [el (vec node)
        tag (first el)
        tag-str (when (or (keyword? tag) (symbol? tag)) (name tag))
        has-attrs? (map? (second el))
        children (subvec el (if has-attrs? 2 1))]
    [tag-str (second el) children]))

(defn hiccup->md
  "Pure Hiccup -> clean Markdown/text converter for Stage 4 & sub-schema `:seon.render/hiccup` outputs."
  {:malli/schema [:=> [:cat :any] :string]
   :cost/ticks 2000000
   :cost/bytes 1048576}
  [h]
  (letfn [(step [x in-pre?]
            (cond
              (nil? x) ""
              (string? x) x
              (or (number? x) (boolean? x) (keyword? x) (symbol? x)) (str x)
              (and (vector? x) (not (map-entry? x)))
              (if (empty? x) ""
                  (let [t (first x)]
                    (cond
                      (or (keyword? t) (symbol? t))
                      (let [[ts attrs ch] (hiccup-children x)
                            base (first (str/split (or ts "") #"[.#]"))
                            ent-btn? (and (= "button" base) (map? attrs)
                                          (string? (:class attrs)) (str/includes? (:class attrs) "ent-link"))
                            parts (remove str/blank? (map #(step % (or in-pre? (= "pre" base))) ch))]
                        (cond
                          (and (= "button" base) (not ent-btn?)) ""
                          (contains? #{"script" "style" "svg" "form" "input" "textarea" "select"} base) ""
                          (= "pre" base) (str "\n```\n" (apply str parts) "\n```\n")
                          (= "code" base) (if in-pre? (apply str parts) (str "`" (apply str parts) "`"))
                          (= "tr" base) (str "| " (str/join " | " (map str/trim parts)) " |\n")
                          (= "li" base) (str "- " (str/join " " (map str/trim parts)) "\n")
                          (contains? #{"div" "p" "section" "article" "h1" "h2" "h3" "h4" "header"} base)
                          (str (str/join " " (map str/trim parts)) "\n")
                          :else (str/join " " (map str/trim parts))))
                      :else (str/join " " (remove str/blank? (map #(step % in-pre?) x))))))
              (sequential? x) (str/join " " (remove str/blank? (map #(step % in-pre?) x)))
              :else (str x)))]
    (-> (step h false) (str/replace #"[ \t]+\n" "\n") (str/replace #"\n{3,}" "\n\n") str/trim)))

(defn- flat-leaf? [nd]
  (and (map? nd) (contains? #{:nil :scalar} (:kind nd))))

(defn- format-db-label [node]
  (let [pv (if (map? (:preview node)) (:preview node) {})
        lbl (if (= :datahike/conn (:kind node)) "Datahike Conn" "Datahike DB")
        parts (remove nil? [(some-> (:db-type pv) name)
                            (when (:branch pv) (str "branch " (:branch pv)))
                            (when (:max-tx pv) (str "tx " (:max-tx pv)))
                            (when (:attrs-count pv) (str (:attrs-count pv) " attrs"))])]
    (str lbl " (" (str/join " · " parts) ")"
         (when-let [eh (:expand-hint node)] (str " · " eh)))))

(defn- safe-entries [node]
  (if (sequential? (:entries node))
    (vec (filter #(and (sequential? %) (>= (count %) 2)) (:entries node)))
    []))

(defn- safe-facets [node]
  (if (sequential? (:facets node)) (:facets node) []))

(defn- expand-hint-of [node]
  (or (:expand-hint node)
      (when (map? (:elision node)) (:expand-hint (:elision node)))))

(defn- node->hiccup [node opts]
  (if-not (map? node)
    [:span (pr-str node)]
    (case (:kind node)
      :nil [:span {:class "muted"} "nil"]
      :scalar [:code {:class "mono"} (or (:text node) (pr-str (:preview node)))]
      :string (let [pv (:preview node)
                    off (long (if (integer? (:offset node)) (:offset node) 0))
                    eh (expand-hint-of node)]
                (if (string? pv)
                  (if (str/includes? pv "\n") [:pre {:class "code-box"} [:code pv]] [:span {:class "mono"} pv])
                  (let [pm (if (map? pv) pv {})
                        hd (:head pm)
                        tl (:tail pm)]
                    [:div {:class "card compact"}
                     [:div {:class "muted"}
                      (if (sequential? hd)
                        (str "Large string (" (:lines node) " lines, " (:total node) " chars; showing lines "
                             (inc off) ".." (+ off (count hd)) ")")
                        (str "Large string (" (:total node) " chars; offset " off ")"))]
                     (when (and (sequential? hd) (seq hd))
                       [:pre {:class "code-box"} [:code (str/join "\n" hd)]])
                     (when (and (string? hd) (not (str/blank? hd)))
                       [:pre {:class "code-box"} [:code hd]])
                     (when eh [:div {:class "muted mono"} eh])
                     (when (and (sequential? tl) (seq tl))
                       [:pre {:class "code-box"} [:code (str/join "\n" tl)]])])))
      :cycle-ref [:span {:class "pill"}
                  (str "↺ ref " (pr-str (:ref node))
                       (when (:schema node) (str " (" (:schema node) ")")))]
      :rendered-sub (or (:hiccup node) [:span {:class "muted"} (str (:schema node))])
      :error [:div {:class "card notice red"}
              [:strong (str "Render fault in " (or (:renderer node) (:schema node)) ": ")]
              [:span {:class "mono"} (str (or (when (map? (:preview node)) (:message (:preview node))) (:preview node)))]]
      :ref-pill [:span {:class "pill"}
                 (str (or (:schema node) "ref") " · " (:total node) " "
                      (name (or (when (map? (:elision node)) (:unit (:elision node))) :items))
                      (when-let [eh (expand-hint-of node)] (str " · " eh)))]
      :elision [:span {:class "muted mono"}
                (str "... (" (:remaining node) " more " (name (or (:unit node) :items)) "; " (:expand-hint node) ")")]
      :lookup-ref (let [pv (if (map? (:preview node)) (:preview node) {})]
                    [:div {:class "card compact"}
                     [:code {:class "mono"} (pr-str [(:attr pv) (:id pv)])]
                     (when (:schema node) [:span {:class "pill"} (str (:schema node))])
                     (when-let [eh (expand-hint-of node)] [:span {:class "muted mono"} eh])])
      (:datahike/db :datahike/conn) [:div {:class "card compact"} [:span {:class "mono"} (format-db-label node)]]
      :grouped-errors [:div {:class "card"}
                       [:div [:strong (str "Grouped errors (" (:total node) " :seon.error/entity · " (:total node) " errors)")]]
                       (into [:div {:class "row gap-1"}]
                             (for [[k v] (sort-by (comp str key) (if (map? (:groups node)) (:groups node) {}))]
                               [:span {:class "pill"} (str k ": " v)]))
                       (into [:div {:class "stack"}]
                             (map #(node->hiccup % opts) (if (sequential? (:items node)) (:items node) [])))
                       (when-let [eh (expand-hint-of node)] [:div {:class "muted mono"} eh])]
      (:entity :value :map)
      (let [entries (safe-entries node)
            fs (safe-facets node)
            sch (:schema node)
            extras (vec (remove #(= sch %) fs))
            tot (long (if (integer? (:total node)) (:total node) (count entries)))
            off (long (if (integer? (:offset node)) (:offset node) 0))
            eh (expand-hint-of node)]
        [:div {:class "card"}
         (when (seq fs)
           (into [:div {:class "row gap-1"}]
                 (concat (for [f fs] [:span {:class "pill"} (str f)])
                         (when (seq extras) [[:span {:class "pill"} (str "+facets " (str/join ", " extras))]]))))
         (when (and (:more? node) (nil? sch))
           [:div {:class "muted"}
            (str "Large map (" tot " keys; showing " (inc off) ".." (+ off (count entries)) ")")])
         (into [:table {:class "kv-table"}]
               (for [[k nd] entries]
                 [:tr [:th {:class "mono"} (pr-str k)] [:td (node->hiccup nd opts)]]))
         (when (:more? node)
           [:div {:class "muted mono"}
            (str "+" (or (:remaining node) (max 0 (- tot (+ off (count entries))))) " more keys"
                 (when eh (str " · " eh)))])])
      (:vector :set :seq)
      (let [items (if (sequential? (:items node)) (:items node) [])
            tot (long (if (integer? (:total node)) (:total node) (count items)))
            off (long (if (integer? (:offset node)) (:offset node) 0))
            lbl (case (:kind node) :set "Set" :seq "Seq" "Vector")
            eh (expand-hint-of node)]
        (if (and (not (:more? node)) (not (:element-schema node)) (zero? off) (<= (count items) 12)
                 (every? flat-leaf? items) (<= (long (if (integer? (:tokens node)) (:tokens node) 99)) 80))
          (let [inner (str/join " " (map #(if (= :nil (:kind %)) "nil" (or (:text %) (pr-str (:preview %)))) items))]
            [:code {:class "mono"} (case (:kind node) :set (str "#{" inner "}") :seq (str "(" inner ")") (str "[" inner "]"))])
          [:div {:class "card"}
           (when (or (:element-schema node) (:more? node) (pos? off))
             [:div {:class "muted"}
              (str lbl " (" tot " items"
                   (when (:element-schema node) (str "; " (:element-schema node)))
                   (when (or (:more? node) (pos? off)) (str "; showing " (inc off) ".." (+ off (count items))))
                   ")")])
           (into [:ul {:class "compact-list"}] (for [it items] [:li (node->hiccup it opts)]))
           (when (:more? node)
             [:div {:class "muted mono"}
              (str "+" (or (:remaining node) (max 0 (- tot (+ off (count items))))) " more items"
                   (when eh (str " · " eh)))])]))
      [:span {:class "mono"} (or (when (string? (:preview node)) (:preview node)) (pr-str (:preview node)))])))

(defn tree->hiccup
  "Stage 4 Hiccup Emitter: turns a Stage 3 Selection Tree into clean Hiccup (delegating `:rendered-sub` inline)."
  {:malli/schema [:function [:=> [:cat :map] :any] [:=> [:cat :map :map] :any]]
   :cost/ticks 3000000
   :cost/bytes 2097152}
  ([tree] (tree->hiccup tree {}))
  ([tree opts] (node->hiccup tree (pexp/norm-opts opts))))

(defn tree->md
  "Stage 4 Markdown Emitter: routes cleanly through `tree->hiccup` -> `hiccup->md` (single Hiccup emitter)."
  {:malli/schema [:function [:=> [:cat :map] :string] [:=> [:cat :map :map] :string]]
   :cost/ticks 3000000
   :cost/bytes 2097152}
  ([tree] (tree->md tree {}))
  ([tree opts]
   (let [nopts (pexp/norm-opts opts)
         cap (long (max 256 (:max-chars nopts 8000)))
         s (hiccup->md (tree->hiccup tree nopts))
         eh (expand-hint-of tree)]
     (if (<= (count s) cap)
       s
       (let [tail (str "\n... (" (- (count s) (max 100 (- cap 180))) " chars omitted"
                       (when eh (str "; " eh)) ")")
             keep-n (max 0 (min (count s) (- cap (count tail))))]
         (if (pos? keep-n)
           (str (subs s 0 keep-n) tail)
           (subs tail 0 (min cap (count tail)))))))))
