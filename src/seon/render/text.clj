(ns seon.render.text
  "Zero-dependency single-source Hiccup -> compact text/Markdown projection.
   The ONE implementation behind `seon.render/hiccup->text`,
   `seon.render.fit.measure/hiccup->text-fallback` and `seon.render.print.emit/hiccup->md`."
  (:require [clojure.string :as str]))

(defn hiccup->text
  "Projects any Reagent-style Hiccup tree into crisp compact Markdown for an agent's LLM context.
   Zero separate model-render functions — one Hiccup component feeds both Web and Agent!
   Semantic `.ent-link` buttons emit clean plain text while UI chrome buttons are omitted.
   Zero-dependency leaf ns: the SINGLE source of Hiccup->text shared by `seon.render/hiccup->text`,
   `seon.render.fit.measure/hiccup->text-fallback` and `seon.render.print.emit/hiccup->md`."
  {:malli/schema [:=> [:cat :any] :string]
   :cost/ticks 5000000}
  [node]
  (let [sb (StringBuilder. 256)]
    ((fn walk! [n]
       (cond
         (or (nil? n) (false? n)) nil
         (and (map? n) (contains? n :seon.render/raw)) nil
         (or (string? n) (number? n) (keyword? n) (symbol? n) (boolean? n)) (.append sb (str n))
         (seq? n) (doseq [x n] (walk! x))
         (vector? n)
         (let [hd (first n)]
           (cond
             (and (not (keyword? hd)) (not (symbol? hd)) (or (fn? hd) (var? hd)))
             (walk! (apply hd (rest n)))

             (not (or (keyword? hd) (symbol? hd) (string? hd)))
             (doseq [item n] (walk! item))

             :else
             (let [tag (name hd)
                   has-attrs? (and (map? (second n)) (not (contains? (second n) :seon.render/raw)))
                   attrs (when has-attrs? (second n))
                   children (if has-attrs? (drop 2 n) (rest n))]
               (case tag
                 ("script" "style" "svg" "form" "input" "textarea" "select") nil
                 ("button")
                 (when (and (string? (:class attrs)) (str/includes? (:class attrs) "ent-link"))
                   (let [before (.length sb)]
                     (doseq [c children] (walk! c))
                     (when (> (.length sb) before)
                       (.append sb " "))))
                 ("h1" "h2" "h3") (do (.append sb "\n## ") (doseq [c children] (walk! c)) (.append sb "\n"))
                 ("p" "div" "section" "header" "main" "article") (do (doseq [c children] (walk! c)) (.append sb "\n"))
                 ("li") (do (.append sb "- ") (doseq [c children] (walk! c)) (.append sb "\n"))
                 ("tr") (do (.append sb "| ") (doseq [c children] (walk! c) (.append sb " | ")) (.append sb "\n"))
                 ("dt") (do (.append sb "- ") (doseq [c children] (walk! c)) (.append sb ": "))
                 ("dd") (do (doseq [c children] (walk! c)) (.append sb "\n"))
                 ("pre") (do (.append sb "\n```clojure\n") (doseq [c children] (walk! c)) (.append sb "\n```\n"))
                 ("span" "strong" "code" "em" "del")
                 (let [before (.length sb)]
                   (doseq [c children] (walk! c))
                   (when (> (.length sb) before)
                     (.append sb " ")))
                 (doseq [c children] (walk! c))))))
         :else (.append sb (pr-str n))))
     node)
    (-> (.toString sb)
        (str/replace #"[ \t]+\n" "\n")
        (str/replace #" {2,}" " ")
        (str/replace #"\n{3,}" "\n\n")
        str/trim)))

(defn nonblank
  "Returns `x` when it is a non-blank String (or trimmed when `trim?` is true), else nil."
  {:malli/schema [:function
                  {:examples [[["hi"] "hi"] [["   "] nil] [[nil] nil]]}
                  [:=> [:cat :any] [:maybe :string]]
                  [:=> [:cat :any :boolean] [:maybe :string]]]
   :cost/ticks 50000 :cost/bytes 16384}
  ([x] (when (and (string? x) (not (str/blank? x))) x))
  ([x trim?] (when (and (string? x) (not (str/blank? x))) (if trim? (str/trim x) x))))

(defn clip
  "Truncates `(str x)` to at most `n` chars (min 1) appending `…` when clipped."
  {:malli/schema [:=> {:examples [[["hello world" 6] "hello…"] [["hi" 10] "hi"] [[nil 5] ""]]}
                  [:cat :any pos-int?] :string]
   :cost/ticks 50000 :cost/bytes 16384}
  [x n]
  (let [s (if (nil? x) "" (str x))
        lim (max 1 (long n))]
    (if (> (count s) lim) (str (subs s 0 (max 1 (dec lim))) "…") s)))

(defn one-line
  "Collapses whitespace in `x` to single spaces, trims, and clips to `n` chars."
  {:malli/schema [:=> {:examples [[["a\n  b\tc" 10] "a b c"]]}
                  [:cat :any pos-int?] :string]
   :cost/ticks 100000 :cost/bytes 32768}
  [x n]
  (clip (-> (if (nil? x) "" (str x)) (str/replace #"\s+" " ") str/trim) n))

(defn clamp-pct
  "Clamps numeric percentage `x` into `[0.0, 100.0]` rounded to 1 decimal place (NaN/Inf/nil -> 0.0)."
  {:malli/schema [:=> {:examples [[[105] 100.0] [[-3] 0.0] [[42.36] 42.4] [[nil] 0.0]]}
                  [:cat :any] :double]
   :cost/ticks 50000 :cost/bytes 16384}
  [x]
  (if (number? x)
    (let [v (double x)]
      (if (and (== v v) (not (Double/isInfinite v)))
        (max 0.0 (min 100.0 (/ (double (Math/round (* v 10.0))) 10.0)))
        0.0))
    0.0))

(defn clamp-pct-int
  "Clamps numeric percentage `x` into an integer in `[0, 100]`, returning nil for nil/non-number/NaN/Inf."
  {:malli/schema [:=> {:examples [[[105] 100] [[-3] 0] [[42.6] 43] [[nil] nil]]}
                  [:cat :any] [:maybe :int]]
   :cost/ticks 50000 :cost/bytes 16384}
  [x]
  (when (number? x)
    (let [dv (double x)]
      (when (and (== dv dv) (not (Double/isInfinite dv)))
        (max 0 (min 100 (long (Math/round dv))))))))
