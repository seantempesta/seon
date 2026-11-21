(ns seon.render.print.emit
  "Pure Markdown & Reagent/Hiccup emission over `seon.render.print` value descriptors (zero silent clipping)."
  (:require [clojure.string :as str]))

(defn snippet-str
  "Collapses whitespace in `s` and snaps cleanly at `max-len` chars."
  {:malli/schema [:=> [:cat :string pos-int?] :string]
   :cost/ticks 50000
   :cost/bytes 65536}
  [^String s max-len]
  (let [c (str/replace s #"\s+" " ")]
    (if (> (count c) max-len) (str (subs c 0 max-len) "…") c)))

(defn md-inline-val
  "Formats a scalar or nested `seon.print/nested` descriptor inline without silent clipping."
  {:malli/schema [:=> [:cat :any] :string]
   :cost/ticks 500000
   :cost/bytes 524288}
  [v]
  (if (and (map? v) (:seon.print/nested v))
    (case (:kind v)
      :string (str "string(" (:total v) " chars) " (pr-str (:preview v)) " -> " (:expand-hint v))
      :map (str "map(" (:total v) " keys) "
                (when (map? (:preview v)) (snippet-str (pr-str (keys (:preview v))) 100))
                (when (:more? v) (str " … +" (:remaining v) " -> " (:expand-hint v))))
      (:entity :value)
      (str (name (:kind v)) "<" (:schema v) ">(" (:total v) " keys)"
           (when (:more? v) (str " … +" (:remaining v) " -> " (:expand-hint v))))
      (:vector :set :seq)
      (str (name (:kind v)) (when (:element-schema v) (str "<" (:element-schema v) ">"))
           "(" (:total v) " items) " (snippet-str (pr-str (:preview v)) 120)
           (when (:more? v) (str " … +" (:remaining v) " -> " (:expand-hint v))))
      (:datahike/db :datahike/conn)
      (str "#" (if (= :datahike/conn (:kind v)) "Conn" "DB") "{" (:branch v) " tx=" (:max-tx v) "}")
      (snippet-str (pr-str v) 140))
    (if (string? v) (pr-str (snippet-str v 140)) (snippet-str (pr-str v) 180))))

(defn- format-string-md [desc h-str p]
  (cond
    (string? p) p
    (and (map? p) (or (sequential? (:head p)) (string? (:head p))))
    (let [lines? (sequential? (:head p))
          om (long (if (integer? (if lines? (:omitted-lines p) (:omitted-chars p)))
                     (if lines? (:omitted-lines p) (:omitted-chars p)) 0))
          tl (when (and lines? (sequential? (:tail p))) (:tail p))]
      (str "; Large string (" (:lines desc) " lines, " (:total desc) " chars) — full string in " h-str
           (when (:expand-hint desc) (str "\n; expand: " (:expand-hint desc)))
           "\n" (if lines? (str/join "\n" (:head p)) (:head p))
           (when (pos? om) (str "\n... (" om (if lines? " lines" " chars") " omitted) ..."))
           (if lines? (when (seq tl) (str "\n" (str/join "\n" tl))) (when (string? (:tail p)) (:tail p)))))
    :else (pr-str desc)))

(defn- format-map-md [desc h-str p]
  (let [tot (long (:total desc))
        off (long (if (integer? (:offset desc)) (:offset desc) 0))
        ks (if (sequential? (:key-order desc)) (:key-order desc) (keys p))
        fs (:facets desc)
        more? (:more? desc)]
    (cond
      (zero? tot) "{}"
      (and (= (:kind desc) :map) (empty? fs) (not more?) (not (:nested-more? desc)) (<= tot 10) (<= (count (pr-str p)) 360))
      (pr-str p)
      :else
      (str (cond
             (and (sequential? fs) (seq fs))
             (str "; " (name (:kind desc)) " " (:schema desc)
                  (when (> (count fs) 1) (str " · facets: " (str/join ", " (map str fs))))
                  (when (:ambiguous desc) (str " · ambiguous: " (pr-str (:ambiguous desc))))
                  " (" tot " keys" (when more? (str ", showing " off ".." (dec (+ off (count ks))))) ") — full live map in " h-str)
             more?
             (str "; Large map (" tot " keys, showing " off ".." (dec (+ off (count ks))) ") — full live map in " h-str)
             :else
             (str "; map (" tot " keys)"))
           "\n" (str/join "\n" (map #(str "  " (pr-str %) " => " (md-inline-val (get p %))) ks))
           (when more? (str "\n  ... +" (:remaining desc) " more keys — " (:expand-hint desc)))))))

(defn- format-coll-md [desc h-str p]
  (let [kind (:kind desc)
        tot (long (:total desc))
        off (long (if (integer? (:offset desc)) (:offset desc) 0))
        more? (:more? desc)
        nested? (some #(and (map? %) (:seon.print/nested %)) p)]
    (cond
      (zero? tot) (case kind :vector "[]" :set "#{}" "()")
      (and (nil? (:element-schema desc)) (not more?) (not nested?) (<= tot 20)
           (let [s (pr-str (case kind :set (set p) :seq (apply list p) (vec p)))] (when (<= (count s) 360) s)))
      (pr-str (case kind :set (set p) :seq (apply list p) (vec p)))
      :else
      (str "; " (when more? "Large ") (name kind) (when (:element-schema desc) (str "<" (:element-schema desc) ">"))
           " (" (if (false? (:exact? desc)) (str tot "+") tot) " items"
           (when more? (str ", showing " off ".." (dec (+ off (count p))))) ") — full live coll in " h-str
           "\n" (str/join "\n" (map-indexed (fn [i it] (str "  [" (+ off i) "] " (md-inline-val it))) p))
           (when more? (str "\n  ... +" (:remaining desc) " more items — " (:expand-hint desc)))))))

(defn format-descriptor-md
  "Renders a `describe-value` descriptor map into clean, token-efficient Markdown with zero silent clipping."
  {:malli/schema [:=> [:cat :map :map] :string]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [desc opts]
  (let [kind (:kind desc)
        h-str (if-let [h (:handle (if (map? opts) opts {}))] (str h) "result/eN")
        p (:preview desc)]
    (cond
      (= kind :nil) "nil"
      (= kind :scalar) (pr-str p)
      (= kind :string) (format-string-md desc h-str p)
      (and (= kind :lookup-ref) (map? p))
      (str "; Lookup ref [" (pr-str (:attr p)) " " (pr-str (:id p)) "] -> " (or (:schema desc) :unknown)
           (when (contains? p :exists?) (if (:exists? p) (str " (exists, eid " (:eid p) ")") " (not found)"))
           (when (:summary p) (str "\n  " (pr-str (:summary p))))
           (when (:expand-hint desc) (str "\n  expand: " (:expand-hint desc))))
      (and (contains? #{:datahike/db :datahike/conn} kind) (map? p))
      (let [br (or (:branch p) (when (vector? (:store-id p)) (second (:store-id p)))
                   (when (vector? (get-in p [:origin :store-id])) (second (get-in p [:origin :store-id]))))
            mtx (or (:max-tx p) (get-in p [:origin :max-tx]))
            meid (or (:max-eid p) (get-in p [:origin :max-eid]))]
        (str "; Datahike " (if (= kind :datahike/conn) "Connection" "DB") " ("
             (str/join ", " (remove nil? [(when br (str br)) (when mtx (str "tx " mtx))
                                          (when meid (str "max-eid " meid))
                                          (when (:attrs-count p) (str (:attrs-count p) " attrs"))
                                          (when (:time-point p) (str "as-of " (:time-point p)))]))
             ") — slice via " (:expand-hint desc)))
      (and (contains? #{:entity :value :map} kind) (map? p) (integer? (:total desc)))
      (format-map-md desc h-str p)
      (and (contains? #{:vector :set :seq} kind) (sequential? p) (integer? (:total desc)))
      (format-coll-md desc h-str p)
      (and (= kind :error) (map? p))
      (str "ERROR (" (:class p) "): " (:message p) (when (:data p) (str " " (pr-str (:data p)))))
      :else (pr-str desc))))

(defn- format-nested-hiccup [v]
  (cond
    (and (map? v) (:seon.print/nested v)) [:span {:class "badge muted mono"} (md-inline-val v)]
    (nil? v) [:span {:class "muted"} "nil"]
    (string? v) [:span (if (> (count v) 240) (str (subs v 0 240) "…") v)]
    :else [:span {:class "mono"} (pr-str v)]))

(defn format-descriptor-hiccup
  "Renders a `describe-value` descriptor map into structured Reagent/Hiccup with zero silent clipping."
  {:malli/schema [:=> [:cat :map :map] :any]
   :cost/ticks 5000000
   :cost/bytes 4194304}
  [desc _opts]
  (let [kind (:kind desc)
        p (:preview desc)]
    (cond
      (= kind :nil) [:span {:class "muted"} "nil"]
      (= kind :scalar) [:span (str p)]
      (= kind :string)
      (cond (string? p) [:span p]
            (map? p) [:div {:class "stack-xs"}
                      [:span {:class "badge muted mono"} (str "string · " (:lines desc) " lines · " (:total desc) " chars")]
                      [:pre {:class "code-box tiny"} [:code (if (sequential? (:head p)) (str/join "\n" (:head p)) (str (:head p)))]]
                      (when (:more? desc) [:div {:class "muted tiny mono"} (str "… +" (:remaining desc) " omitted · " (:expand-hint desc))])]
            :else [:span (pr-str desc)])
      (and (= kind :lookup-ref) (map? p))
      [:div {:class "pill-row"}
       [:span {:class "badge blue mono"} (str (or (:schema desc) :lookup-ref))]
       [:span {:class "mono"} (pr-str [(:attr p) (:id p)])]
       (when (contains? p :exists?) [:span {:class (str "badge " (if (:exists? p) "green" "muted"))} (if (:exists? p) (str "eid " (:eid p)) "missing")])]
      (and (contains? #{:datahike/db :datahike/conn} kind) (map? p))
      (let [br (or (:branch p) (when (vector? (:store-id p)) (second (:store-id p)))
                   (when (vector? (get-in p [:origin :store-id])) (second (get-in p [:origin :store-id]))))
            mtx (or (:max-tx p) (get-in p [:origin :max-tx]))]
        [:div {:class "pill-row"}
         [:span {:class "badge purple mono"} (if (= kind :datahike/conn) "Conn" "DB")]
         [:span {:class "mono"} (str/join " · " (remove nil? [(when br (str br)) (when mtx (str "tx " mtx)) (when (:attrs-count p) (str (:attrs-count p) " attrs"))]))]])
      (and (contains? #{:entity :value :map} kind) (map? p) (integer? (:total desc)))
      (let [ks (if (sequential? (:key-order desc)) (:key-order desc) (keys p))
            fs (when (sequential? (:facets desc)) (:facets desc))
            tot (long (:total desc))]
        [:div {:class "stack-xs"}
         (when (or (seq fs) (:more? desc))
           [:div {:class "pill-row"}
            (when (:schema desc) [:span {:class "badge blue mono"} (str (:schema desc))])
            (for [f (rest fs)] [:span {:class "badge muted mono"} (str f)])
            (when (:more? desc) [:span {:class "badge amber mono"} (str tot " keys")])])
         [:dl {:class "kv-grid"} (for [k ks] (list [:dt (str k)] [:dd (format-nested-hiccup (get p k))]))]
         (when (:more? desc) [:div {:class "muted tiny mono"} (str "Showing " (count ks) " of " tot " keys (+" (:remaining desc) " more) · " (:expand-hint desc))])])
      (and (contains? #{:vector :set :seq} kind) (sequential? p) (integer? (:total desc)))
      (let [tot (long (:total desc))]
        (if (zero? tot)
          [:span {:class "muted"} (case kind :vector "[]" :set "#{}" "()")]
          [:div {:class "stack-xs"}
           (when (or (:element-schema desc) (:more? desc))
             [:div {:class "pill-row"}
              (when (:element-schema desc) [:span {:class "badge blue mono"} (str (:element-schema desc))])
              [:span {:class "badge muted mono"} (str (name kind) " · " tot " items")]])
           (if (and (:tabular? desc) (sequential? (:columns desc)) (seq (:columns desc)))
             (let [cols (:columns desc)]
               [:table {:class "data-table"}
                [:thead [:tr (for [c cols] [:th (str c)])]]
                [:tbody (for [row p] [:tr (for [c cols] [:td (format-nested-hiccup (when (map? row) (get row c)))])])]])
             [:div {:class "pill-row"}
              (for [it p] [:span {:class "pill"} (if (and (map? it) (:seon.print/nested it)) (md-inline-val it) (pr-str it))])])
           (when (:more? desc) [:div {:class "muted tiny mono"} (str "Showing " (count p) " of " tot " items (+" (:remaining desc) " more) · " (:expand-hint desc))])]))
      (and (= kind :error) (map? p)) [:div {:class "notice red mono tiny"} (str (:class p) ": " (:message p))]
      :else [:span (pr-str desc)])))
