(ns seon.render.print.emit
  "Stage 4 of 4-Stage Printer: Dual Emitters (`tree->md` and `tree->hiccup`) from ONE Stage 3 Selection Tree."
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
  "Pure Hiccup -> clean Markdown/text converter for sub-schema `:seon.render/hiccup` outputs."
  {:malli/schema [:=> [:cat :any] :string]
   :cost/ticks 2000000
   :cost/bytes 1048576}
  [h]
  (letfn [(step [x]
            (cond
              (nil? x) ""
              (string? x) x
              (or (number? x) (boolean? x) (keyword? x) (symbol? x)) (str x)
              (and (vector? x) (not (map-entry? x)))
              (if (empty? x) ""
                  (let [t (first x)]
                    (cond
                      (or (keyword? t) (symbol? t))
                      (let [[ts _ ch] (hiccup-children x)
                            base (first (str/split (or ts "") #"[.#]"))
                            parts (remove str/blank? (map step ch))]
                        (cond
                          (contains? #{"script" "style"} base) ""
                          (= "code" base) (str "`" (apply str parts) "`")
                          (= "pre" base) (str "\n```\n" (apply str parts) "\n```\n")
                          (contains? #{"tr"} base) (str "| " (str/join " | " (map str/trim parts)) " |\n")
                          (contains? #{"li"} base) (str "- " (str/join " " (map str/trim parts)) "\n")
                          (contains? #{"div" "p" "section" "article" "h1" "h2" "h3" "h4" "header"} base)
                          (str (str/join " " (map str/trim parts)) "\n")
                          :else (str/join " " (map str/trim parts))))
                      :else (str/join " " (remove str/blank? (map step x))))))
              (sequential? x) (str/join " " (remove str/blank? (map step x)))
              :else (str x)))]
    (-> (step h) (str/replace #"[ \t]+\n" "\n") (str/replace #"\n{3,}" "\n\n") str/trim)))

(defn- flat-leaf? [nd]
  (and (map? nd) (contains? #{:nil :scalar} (:kind nd))))

(defn- format-string-md [node]
  (let [pv (:preview node)]
    (if (string? pv) pv
        (let [{:keys [head tail omitted-lines omitted-chars]} (if (map? pv) pv {})
              tot (long (or (:total node) 0)) nl (long (or (:lines node) 0)) off (long (or (:offset node) 0))
              eh (or (:expand-hint node) (get-in node [:elision :expand-hint]))]
          (if (sequential? head)
            (str "Large string (" nl " lines, " tot " chars; showing lines " (inc off) ".." (+ off (count head))
                 (when (seq tail) (str ", tail " (count tail))) "):\n"
                 (str/join "\n" head)
                 (when (pos? (long (or omitted-lines 0)))
                   (str "\n... (" omitted-lines " lines omitted" (when eh (str "; " eh)) ") ..."))
                 (when (seq tail) (str "\n" (str/join "\n" tail))))
            (str "Large string (" tot " chars; offset " off "):\n" (or head "")
                 (when (pos? (long (or omitted-chars 0)))
                   (str "\n... (" omitted-chars " chars omitted" (when eh (str "; " eh)) ") ..."))
                 (or tail "")))))))

(defn- format-db-md [node]
  (let [pv (or (:preview node) {})
        kind (:kind node)
        lbl (if (= :datahike/conn kind) "Datahike Conn" "Datahike DB")
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

(defn- node->hiccup [node opts]
  (if-not (map? node)
    [:span (pr-str node)]
    (case (:kind node)
      :nil [:span {:class "muted"} "nil"]
      :scalar [:code {:class "mono"} (or (:text node) (pr-str (:preview node)))]
      :string (let [pv (:preview node)]
                (if (string? pv)
                  (if (str/includes? pv "\n") [:pre {:class "code-box"} [:code pv]] [:span {:class "mono"} pv])
                  [:div {:class "card compact"}
                   [:div {:class "muted"} (str "Large string (" (:lines node) " lines, " (:total node) " chars)")]
                   (when (and (map? pv) (sequential? (:head pv)) (seq (:head pv)))
                     [:pre {:class "code-box"} [:code (str/join "\n" (:head pv))]])
                   (when-let [eh (:expand-hint node)] [:div {:class "muted mono"} eh])]))
      :cycle-ref [:span {:class "pill"} (str "↺ " (pr-str (:ref node)))]
      :rendered-sub (or (:hiccup node) [:span {:class "muted"} (str (:schema node))])
      :error [:div {:class "card notice red"}
              [:strong (str "Render fault in " (or (:renderer node) (:schema node)) ": ")]
              [:span {:class "mono"} (str (or (when (map? (:preview node)) (:message (:preview node))) (:preview node)))]]
      :ref-pill [:span {:class "pill"}
                 (str (or (:schema node) "ref") " · " (:total node) " "
                      (name (or (when (map? (:elision node)) (:unit (:elision node))) :items)))]
      :lookup-ref (let [pv (if (map? (:preview node)) (:preview node) {})]
                    [:div {:class "card compact"}
                     [:code {:class "mono"} (pr-str [(:attr pv) (:id pv)])]
                     (when (:schema node) [:span {:class "pill"} (str (:schema node))])])
      (:datahike/db :datahike/conn) [:div {:class "card compact"} [:span {:class "mono"} (format-db-md node)]]
      :grouped-errors [:div {:class "card"}
                       [:div [:strong (str (:total node) " errors (:seon.error/entity)")]]
                       (into [:div {:class "row gap-1"}]
                             (for [[k v] (sort-by (comp str key) (if (map? (:groups node)) (:groups node) {}))]
                               [:span {:class "pill"} (str k ": " v)]))
                       (into [:div {:class "stack"}]
                             (map #(node->hiccup % opts) (if (sequential? (:items node)) (:items node) [])))
                       (when-let [eh (:expand-hint node)] [:div {:class "muted mono"} eh])]
      (:entity :value :map)
      (let [entries (safe-entries node) fs (safe-facets node)]
        [:div {:class "card"}
         (when (seq fs) (into [:div {:class "row gap-1"}] (for [f fs] [:span {:class "pill"} (str f)])))
         (when (and (:more? node) (nil? (:schema node))) [:div {:class "muted"} (str "Large map (" (:total node) " keys; showing " (inc (long (:offset node 0))) ".." (+ (long (:offset node 0)) (count entries)) ")")])
         (into [:table {:class "kv-table"}]
               (for [[k nd] entries]
                 [:tr [:th {:class "mono"} (pr-str k)] [:td (node->hiccup nd opts)]]))
         (when (:more? node)
           [:div {:class "muted mono"} (str "+" (:remaining node) " more keys · " (:expand-hint node))])])
      (:vector :set :seq)
      (let [items (if (sequential? (:items node)) (:items node) [])]
      (if (and (not (:more? node)) (not (:element-schema node)) (<= (count items) 12)
               (every? flat-leaf? items) (<= (long (if (integer? (:tokens node)) (:tokens node) 99)) 80))
        (let [inner (str/join " " (map #(if (= :nil (:kind %)) "nil" (or (:text %) (pr-str (:preview %)))) items))]
          [:code {:class "mono"} (case (:kind node) :set (str "#{" inner "}") :seq (str "(" inner ")") (str "[" inner "]"))])
        [:div {:class "card"}
         (when (:element-schema node) [:span {:class "pill"} (str (:element-schema node) " · " (:total node))])
         (into [:ul {:class "compact-list"}] (for [it items] [:li (node->hiccup it opts)]))
         (when (:more? node)
           [:div {:class "muted mono"} (str "+" (:remaining node) " more items · " (:expand-hint node))])]))
      [:span {:class "mono"} (or (when (string? (:preview node)) (:preview node)) (pr-str (:preview node)))])))

(defn tree->hiccup
  "Stage 4 Hiccup Emitter: turns a Stage 3 Selection Tree into clean Hiccup (delegating `:rendered-sub` inline)."
  {:malli/schema [:function [:=> [:cat :map] :any] [:=> [:cat :map :map] :any]]
   :cost/ticks 3000000
   :cost/bytes 2097152}
  ([tree] (tree->hiccup tree {}))
  ([tree opts] (node->hiccup tree (pexp/norm-opts opts))))

(declare node->md node->hiccup)

(defn- format-map-md [node indent]
  (let [entries (safe-entries node)
        tot (long (if (integer? (:total node)) (:total node) (count entries)))
        off (long (if (integer? (:offset node)) (:offset node) 0))
        sch (:schema node) fs (safe-facets node)
        more? (boolean (:more? node))
        eh (or (:expand-hint node) (when (map? (:elision node)) (:expand-hint (:elision node))))
        pad (apply str (repeat indent "  "))]
    (cond
      (and (zero? indent) (nil? sch) (not more?) (<= (count entries) 8)
           (<= (long (if (integer? (:tokens node)) (:tokens node) 99)) 80)
           (every? #(flat-leaf? (second %)) entries))
      (str "{" (str/join ", " (map (fn [[k nd]] (str (pr-str k) " " (node->md nd 0))) entries)) "}")

      :else
      (let [hdr (cond
                  sch (let [extras (vec (remove #(= sch %) fs))]
                        (str "[" sch (when (seq extras) (str " +facets " (str/join ", " extras)))
                             " · " tot " keys"
                             (when more? (str "; showing " (inc off) ".." (+ off (count entries)))) "]"))
                  more? (str "Large map (" tot " keys; showing " (inc off) ".." (+ off (count entries)) "):")
                  :else (str "Map (" tot " keys):"))
            body (map (fn [[k nd]]
                        (let [ks (if (keyword? k) (str k) (pr-str k))
                              vs (node->md nd (inc indent))]
                          (if (str/includes? vs "\n")
                            (str pad "  " ks ":\n" vs)
                            (str pad "  " ks ": " vs))))
                      entries)
            ftr (when more?
                  (str pad "  ... (" (or (:remaining node) (max 0 (- tot (+ off (count entries)))))
                       " more keys" (when eh (str "; " eh)) ")"))]
        (str/join "\n" (remove nil? (concat [(str pad hdr)] body [ftr])))))))

(defn- format-coll-md [node indent]
  (let [items (if (sequential? (:items node)) (:items node) [])
        kind (:kind node)
        tot (long (if (integer? (:total node)) (:total node) (count items)))
        off (long (if (integer? (:offset node)) (:offset node) 0))
        more? (boolean (:more? node))
        esh (:element-schema node)
        eh (or (:expand-hint node) (when (map? (:elision node)) (:expand-hint (:elision node))))
        pad (apply str (repeat indent "  "))
        lbl (case kind :set "Set" :seq "Seq" "Vector")]
    (cond
      (and (zero? indent) (not more?) (<= (count items) 12) (every? flat-leaf? items)
           (<= (long (if (integer? (:tokens node)) (:tokens node) 99)) 80))
      (let [inner (str/join " " (map #(node->md % 0) items))]
        (case kind :set (str "#{" inner "}") :seq (str "(" inner ")") (str "[" inner "]")))

      :else
      (let [hdr (str lbl " (" tot " items" (when esh (str "; element-schema " esh))
                     (when more? (str "; showing " (inc off) ".." (+ off (count items)))) "):")
            body (map-indexed (fn [i nd]
                                (let [vs (node->md nd (inc indent))]
                                  (if (str/includes? vs "\n")
                                    (str pad "  [" (+ off i) "]\n" vs)
                                    (str pad "  [" (+ off i) "] " vs))))
                              items)
            ftr (when more?
                  (str pad "  ... (" (or (:remaining node) (max 0 (- tot (+ off (count items)))))
                       " more items" (when eh (str "; " eh)) ")"))]
        (str/join "\n" (remove nil? (concat [(str pad hdr)] body [ftr])))))))

(defn- format-grouped-errors-md [node indent]
  (let [pad (apply str (repeat indent "  "))
        tot (long (if (integer? (:total node)) (:total node) 0))
        groups (if (map? (:groups node)) (:groups node) {})
        items (if (sequential? (:items node)) (:items node) [])
        eh (or (:expand-hint node) (when (map? (:elision node)) (:expand-hint (:elision node))))
        gs (str/join ", " (map (fn [[k v]] (str k " " v)) (sort-by (comp str key) groups)))
        hdr (str pad "Grouped errors (" tot " :seon.error/entity; by kind: " gs "; newest " (count items) "):")
        body (map-indexed (fn [i nd] (str pad "  [" i "] " (node->md nd (inc indent)))) items)
        ftr (when eh (str pad "  ... (" (or (:remaining node) (max 0 (- tot (count items)))) " more errors; " eh ")"))]
    (str/join "\n" (remove nil? (concat [hdr] body [ftr])))))

(defn- node->md [node indent]
  (if-not (map? node)
    (pr-str node)
    (case (:kind node)
      :nil "nil"
      :scalar (or (:text node) (pr-str (:preview node)))
      :string (format-string-md node)
      :cycle-ref (str "↺ ref " (pr-str (:ref node)) (when (:schema node) (str " (" (:schema node) ")")))
      :rendered-sub (str "[" (:schema node) " · " (name (or (:layout node) :compact)) "] " (hiccup->md (:hiccup node)))
      :ref-pill (let [eh (get-in node [:elision :expand-hint])]
                  (str "{" (when (:schema node) (str (:schema node) " · ")) (:total node) " items"
                       (when eh (str "; " eh)) "}"))
      :lookup-ref (let [pv (or (:preview node) {})]
                    (str "LookupRef " (pr-str [(:attr pv) (:id pv)])
                         (when (:schema node) (str " -> " (:schema node)))
                         (when (contains? pv :exists?) (if (:exists? pv) (str " (eid " (:eid pv) ")") " (missing)"))
                         (when (seq (:summary pv)) (str " " (pr-str (:summary pv))))
                         (when-let [eh (:expand-hint node)] (str " · " eh))))
      (:datahike/db :datahike/conn) (format-db-md node)
      :grouped-errors (format-grouped-errors-md node indent)
      (:entity :value :map) (format-map-md node indent)
      (:vector :set :seq) (format-coll-md node indent)
      :elision (str "... (" (:remaining node) " more " (name (or (:unit node) :items)) "; " (:expand-hint node) ")")
      :error (str "Error (" (:schema node) "): " (pr-str (:preview node)))
      (or (when (string? (:preview node)) (:preview node)) (pr-str (:preview node))))))

(defn tree->md
  "Stage 4 Markdown Emitter: turns a Stage 3 Selection Tree into token-efficient, zero-clipping Markdown."
  {:malli/schema [:function [:=> [:cat :map] :string] [:=> [:cat :map :map] :string]]
   :cost/ticks 3000000
   :cost/bytes 2097152}
  ([tree] (tree->md tree {}))
  ([tree opts]
   (let [cap (long (max 256 (:max-chars (pexp/norm-opts opts) 8000)))
         s (node->md tree 0)
         eh (or (:expand-hint tree) (when (map? (:elision tree)) (:expand-hint (:elision tree))))]
     (if (<= (count s) cap)
       s
       (let [tail (str "\n... (" (- (count s) (max 100 (- cap 180))) " chars omitted"
                       (when eh (str "; " eh)) ")")
             keep-n (max 0 (min (count s) (- cap (count tail))))]
         (if (pos? keep-n)
           (str (subs s 0 keep-n) tail)
           (subs tail 0 (min cap (count tail)))))))))
