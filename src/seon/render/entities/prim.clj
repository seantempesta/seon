(ns seon.render.entities.prim
  "Extracted child namespace of `seon.render.entities`."
  (:require [clojure.string :as str]
            [seon.render.links :as rlinks :refer [badge kv-dl link-pill meta-link pill-list]]
            [seon.render.text :refer [clip nonblank one-line]])
  (:import [java.util.regex Pattern]))

(defn fmt-pr
  {:malli/schema [:=> [:cat :any [:int {:min 1}]] :string], :cost/ticks 500000}
  [x n] (clip (pr-str x) n))

(defn kw-label
  {:malli/schema [:=> [:cat :any :any] :any], :cost/ticks 200000}
  [x fb] (cond (ident? x) (name x) (some? x) (str x) :else fb))

(defn seq-items
  {:malli/schema [:=> [:cat :any] :any], :cost/ticks 200000}
  [x] (when (and (coll? x) (not (map? x))) (seq x)))

(defn fmt-ms
  {:malli/schema [:=> [:cat :any] [:maybe :string]], :cost/ticks 200000}
  [x]
  (when (number? x)
    (let [d (double x)] (if (or (Double/isNaN d) (Double/isInfinite d)) "—" (str (/ (Math/round (* d 10.0)) 10.0) " ms")))))

(defn fmt-num
  {:malli/schema [:=> [:cat :any] [:maybe :string]], :cost/ticks 200000}
  [x]
  (when (number? x)
    (let [d (double x)]
      (cond (or (Double/isNaN d) (Double/isInfinite d)) "—" (integer? x) (str x) :else (str (/ (Math/round (* d 10.0)) 10.0))))))

(defn bl
  {:malli/schema [:=> [:cat :map :int] :int], :cost/ticks 200000}
  [m n]
  (let [tb (:seon.view/token-budget m)]
    (if (and (integer? tb) (pos? (long tb)))
      (long (max 80 (min (* 2 (long n)) (Math/round (* (double n) (/ (double tb) 2400.0))))))
      (long n))))

(defn hl-terms
  {:malli/schema [:=> [:cat :map] [:maybe [:vector :string]]], :cost/ticks 300000}
  [m]
  (let [r (:seon.ui/highlight-terms m)]
    (when (sequential? r) (->> r (filter string?) (map str/trim) (filter #(>= (count %) 2)) distinct (take 6) vec not-empty))))

(defn hl
  {:malli/schema [:=> [:cat [:maybe :string] [:maybe [:sequential [:string {:min 1}]]]] :any], :cost/ticks 1000000}
  [s terms]
  (if (or (empty? terms) (str/blank? s))
    s
    (let [pat (re-pattern (str "(?i)(" (str/join "|" (map #(Pattern/quote ^String %) terms)) ")"))
          mt (re-matcher pat s)
          out (loop [i 0 acc []]
                (if (.find mt)
                  (let [st (.start mt) en (.end mt)]
                    (recur en (cond-> acc (> st i) (conj (subs s i st)) true (conj [:mark {:class "hl-term"} (subs s st en)]))))
                  (cond-> acc (< i (count s)) (conj (subs s i)))))]
      (if (= 1 (count out)) (first out) (into [:span] out)))))

(defn el
  {:malli/schema [:function [:=> [:cat :keyword :any] :any] [:=> [:cat :keyword :any :string] :any] [:=> [:cat :keyword :any :string :string] :any]], :cost/ticks 400000}
  ([a v] (rlinks/ent-link a v (str v) "mono"))
  ([a v lbl] (rlinks/ent-link a v lbl "mono strong"))
  ([a v lbl cls] (rlinks/ent-link a v lbl cls)))

(defn code-block
  {:malli/schema [:function [:=> [:cat :any] :any] [:=> [:cat :any :boolean] :any]], :cost/ticks 300000}
  ([txt] (code-block txt false))
  ([txt exp?] (when (and (some? txt) (not (str/blank? (str txt))))
                [:pre {:class (str "code-box ent-code tiny" (when exp? " is-expanded"))} [:code (str txt)]])))

(defn doc-box
  {:malli/schema [:function [:=> [:cat :any [:int {:min 1}]] :any] [:=> [:cat :any [:int {:min 1}] [:maybe [:sequential [:string {:min 1}]]]] :any]], :cost/ticks 500000}
  ([x n] (doc-box x n nil))
  ([x n terms] (when-let [s (nonblank x)] [:p {:class "doc-box sm-copy"} (hl (clip s n) terms)])))

(defn chip
  {:malli/schema [:=> [:cat :int :string [:* :any]] [:map [:seon.ui/hiccup :any]]], :cost/ticks 300000}
  [min-w cls & xs]
  {:seon.ui/hiccup (into [:span {:class (str "ent-chip " cls) :data-min-width min-w}] (remove nil? xs))})

(defn row
  {:malli/schema [:=> [:cat :int :string [:* :any]] [:map [:seon.ui/hiccup :any]]], :cost/ticks 300000}
  [min-w cls & xs]
  {:seon.ui/hiccup (into [:div {:class (str "ent-row " cls) :data-min-width min-w}] (remove nil? xs))})

(defn box
  {:malli/schema [:=> [:cat :string :int :string [:sequential :any] :any [:* :any]] [:map [:seon.ui/hiccup :any]]], :cost/ticks 500000}
  [tag-cls min-w cls left right & body]
  {:seon.ui/hiccup
   (into [:div {:class (str "card " tag-cls " " cls) :data-min-width min-w}
          [:div {:class "card-head ent-head"}
           (into [:div {:class "pill-row"}] (remove nil? left))
           (cond (vector? right) right
                 (and (some? right) (not (str/blank? (str right)))) [:span {:class "muted tiny mono ent-meta"} (str right)]
                 :else nil)]]
         (remove nil? body))})

(defn ent-box
  "Card primitive with `ent-card` class, consolidating shared card headers across `seon.render.entities.*`."
  {:malli/schema [:=> [:cat :int :string [:sequential :any] :any [:* :any]] [:map [:seon.ui/hiccup :any]]]
   :cost/ticks 500000}
  [min-w cls left right & body]
  (apply box "ent-card" min-w cls left right body))
