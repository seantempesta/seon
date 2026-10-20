(ns seon.print-test
  "Laws of the value printer (Seon's `seon.render.value-law-test`, on the slice): the text stays
  inside the budget, every string cut continues exactly, and an uncut text reads back as the value."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.print :as seon]
            [seon.print.select :as select] [seon.contract]))

(defn- step
  "The member one path step names in `parent` (the typed steps cuts carry)."
  [parent s]
  (let [tag (when (vector? s) (first s))]
    (cond (= tag :seon.render.data/entry) (let [e (nth (seq parent) (second s))] (if (= :seon.render.data/key (nth s 2)) (key e) (val e)))
          (= tag :seon.render.data/nth) (nth (seq parent) (second s))
          (map? parent) (get parent s)
          :else (nth parent s))))

(defn- lawful? [raw budget]
  (let [{:keys [text cuts]} (seon/print-value raw {:budget budget :handle 'result/e0123456789ab})]
    (boolean
     (and (<= (select/estimate text) budget)
          (every? (fn [c] (let [s (reduce step raw (:seon.render.data/path c))]
                            (or (not (:seon.print/prefix c))
                                (= s (str (:seon.print/prefix c) (subs s (:seon.render.data/next-offset c)))))))
                  cuts)
          (or (seq cuts) (= raw (edn/read-string text)))))))

(def ^:private line-gen
  (gen/fmap str/join (gen/vector (gen/elements [\a \space \" \\ \tab \return \( \ü \日]) 0 90)))

(def ^:private value-gen
  (gen/recursive-gen
   (fn [inner] (gen/one-of [(gen/vector inner 0 12) (gen/list inner) (gen/map gen/keyword inner {:max-elements 8})]))
   (gen/one-of [gen/small-integer gen/keyword-ns (gen/return nil)
                (gen/fmap #(str/join "\n" %) (gen/vector line-gen 0 40))])))

(deftest the-text-stays-inside-its-budget
  (is (lawful? [(list 0 [(apply str "aaaaaa" (repeat 82 "a"))])
                {:A (apply str (repeat 66 "\"a")) :B {:A nil :B 0}}]
               70))
  (doseq [budget [40 120 400]]
    (is (lawful? {:a {:b (str/join "\n" (repeat 30 "a line of text")) :c [1 2 {:d "x\ny" :e {:f (vec (range 40))}}]}
                  :g (str/join "\n" (repeat 5 "zz")) :h {:i {:j {:k "deep"}}}}
                 budget)))
  (let [r (tc/quick-check 150 (prop/for-all [raw value-gen budget (gen/elements [60 200 800 1500])] (lawful? raw budget))
                          :seed 202610071 :max-size 40)]
    (is (:pass? r) "a printed value broke its budget or a cut's continuation")))

(deftest escaped-strings-stay-printable-under-a-small-budget
  (doseq [raw [(apply str (repeat 64 "\"")) (apply str (repeat 64 "\\")) (apply str (repeat 64 "\t")) [(apply str (repeat 64 "\""))]]]
    (let [{:keys [text cuts]} (seon/print-value raw {:budget 40})
          s (if (vector? raw) (first raw) raw)
          c (first cuts)]
      (is (string? text) "the value prints rather than refusing the budget")
      (is (= s (str (:seon.print/prefix c) (subs s (:seon.render.data/next-offset c)))) "the cut continues exactly"))))

;; ---- the one printer, at every place a value is shown ----------------------------------

(deftest a-reply-value-cut-reads-its-rest-through-its-handle
  (let [{:keys [text truncated? cuts]} (seon/print-value (vec (range 5000)) {:handle 'result/h7 :budget 100})]
    (is truncated?)
    (is (re-find #"\(drop \d+ result/h7\)" text) "the cut names the form that reads the rest")
    (let [c (first cuts)]
      (is (= (list 'drop (:seon.render.data/next-offset c) 'result/h7) (:seon.print/continuation c)) (pr-str c))
      (is (= 5000 (+ (:seon.render.data/next-offset c) (:seon.print/omitted c))) "shown + omitted is the whole"))))

(deftest a-refused-argument-is-shown-by-the-one-printer
  ;; seon.contract's refusal shows the offending value within the part budget, cut the printer's way
  (let [f (seon.contract/checked 'seon.print-test/takes-an-int [:=> [:cat :int] :int] inc)
        e (try (f (vec (range 10000))) nil (catch Exception e (ex-data e)))
        shown (:value (first (:seon.error/problems e)))]
    (is (= :seon.instrument/contract-error (:seon.error/type e)))
    (is (re-find #"…\d+" shown) "a long value is cut with the printer's marker")
    (is (<= (select/estimate shown) (:part seon/budgets)))))

(deftest text-is-the-printed-text-or-the-refusal
  (is (= "{:a 1}" (seon/text {:a 1})))
  (is (string? (seon/text (vec (range 100)) 1))))

(deftest hiccup-and-text-come-from-one-selection
  (let [{:keys [text html]} (seon/print-value {:a [1 2 3] :b "x"} {:outputs #{:text :hiccup}})]
    (is (= "{:b \"x\", :a [1 2 3]}" text) "short members first, then by name (Seon's rule)")
    (is (= :div (first html)))
    (is (re-find #"seon-print-keyword" (pr-str html)))))
