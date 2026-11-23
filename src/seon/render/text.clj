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
