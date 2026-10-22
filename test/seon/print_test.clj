(ns seon.print-test
  "Laws of the value printer (Seon's `seon.render.value-law-test`, on the slice): the text stays
  inside the budget, every string cut continues exactly, and an uncut text reads back as the value."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.print :as seon]
            [seon.print.select :as select] [seon.contract]
            [fipp.engine :as fipp]))

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

(deftest a-cut-reads-its-rest-through-a-form-handle
  ;; a value read by a form rather than held by a result var (one of the last turn's evals):
  ;; every cut names that form, and the form reads exactly the rest
  (let [it {:form "(pp (code-of 3))" :out (apply str (repeat 400 "line 3 of output\n"))}
        evals [{} {} {} it]
        h '(nth (my.turn/evals) 3)
        {:keys [text cuts]} (seon/print-value it {:handle h :budget 60})
        c (first cuts)]
    (is (= (list 'subs (list 'get-in h [:out]) (:seon.render.data/next-offset c)) (:seon.print/continuation c)) (pr-str c))
    (is (re-find #"\(subs \(get-in \(nth \(my.turn/evals\) 3\) \[:out\]\) \d+\)" text) text)
    (is (= (:out it) (str (:seon.print/prefix c) (subs (get-in (nth evals 3) [:out]) (:seon.render.data/next-offset c))))
        "the form reads exactly the rest")))

(deftest a-refused-argument-is-shown-by-the-one-printer
  ;; seon.contract's error shows the offending value within the part budget, cut the printer's way
  (let [f (seon.contract/checked 'seon.print-test/takes-an-int [:=> [:cat :int] :int] inc)
        e (try (f (vec (range 10000))) nil (catch Exception e (ex-data e)))
        shown (:value (first (:seon.error/problems e)))]
    (is (= :seon.instrument/contract-error (:seon.error/type e)))
    (is (re-find #"…\d+" shown) "a long value is cut with the printer's marker")
    (is (<= (select/estimate shown) (:part seon/budgets)))))

(deftest text-is-the-printed-text-or-the-error
  (is (= "{:a 1}" (seon/text {:a 1})))
  (is (string? (seon/text (vec (range 100)) 1))))

(deftest hiccup-and-text-come-from-one-selection
  (let [{:keys [text html]} (seon/print-value {:a [1 2 3] :b "x"} {:outputs #{:text :hiccup}})]
    (is (= "{:b \"x\", :a [1 2 3]}" text) "short members first, then by name (Seon's rule)")
    (is (= :div (first html)))
    (is (re-find #"seon-print-keyword" (pr-str html)))))

;; ---- the layout is fipp's ---------------------------------------------------------------
;; seon.print.emit lays its op stream out itself, in two passes over the vector; fipp's own
;; streaming stages are the oracle, on random documents (groups, nests, lines with inline and
;; terminate texts) and on the documents where fipp prunes a group for holding more than
;; `width` open groups, though it would fit.

(defn- fipp-text [ops width]
  (let [out (StringBuilder.)]
    (transduce (comp fipp/annotate-rights (fipp/annotate-begins {:width width}) (fipp/format-nodes {:width width}))
               (fn ([sb] sb) ([^StringBuilder sb ^String s] (.append sb s))) out ops)
    (str out)))

(defn- random-doc [^java.util.Random r d]
  (let [k (.nextInt r (if (> d 12) 3 7))
        many (fn [m depth] (repeatedly (inc (.nextInt r m)) #(random-doc r depth)))]
    (case k
      0 (apply str (repeat (.nextInt r 6) "x"))
      1 :line
      2 [:line (apply str (repeat (.nextInt r 3) " ")) (apply str (repeat (.nextInt r 2) ","))]
      (3 4) (into [:group] (many 4 (inc d)))
      5 (into [:nest (.nextInt r 4)] (many 3 (inc d)))
      6 (into [:group "("] (many 2 (+ d 3))))))

(deftest the-layout-is-fipps
  (let [layout @#'seon.print.emit/layout
        r (java.util.Random. 20261020)
        docs (concat (for [_ (range 600)] [(vec (fipp/serialize (random-doc r 0))) (inc (.nextInt r 40))])
                     ;; a group holding more than `width` open groups breaks, though it fits
                     (for [w [3 5 8] depth [2 5 8 12]]
                       [(vec (fipp/serialize [:group "a" :line (reduce (fn [d _] [:group d]) "b" (range depth))])) w]))
        wrong (for [[ops width] docs :when (seq ops) :when (not= (fipp-text ops width) (layout ops width))] [ops width])]
    (is (empty? wrong) (str "laid out unlike fipp: " (pr-str (first wrong))))
    (is (= "a\nb" (layout (vec (fipp/serialize [:group "a" :line (reduce (fn [d _] [:group d]) "b" (range 8))])) 5))
        "the pruned group breaks")))

;; ---- prose: text read as itself, cut the printer's way -----------------------------------

(deftest prose-says-what-it-omitted
  (let [msg (str/join "\n" (for [i (range 40)] (str "line " i " of a long message that says what went wrong")))
        {:keys [text shown truncated? omitted lines offset continuation]} (seon/prose msg 60 {:source '(:message e)})]
    (is (= "a short message" (:text (seon/prose "a short message" 60))) "text that fits is itself, unquoted")
    (is (not (:truncated? (seon/prose "a short message" 60))))
    (is truncated?)
    (is (<= (select/estimate text) 60) "the text with its cut stays inside the budget")
    (is (str/starts-with? msg shown) "what is shown is the text's own beginning")
    (is (= (count msg) (+ (count shown) omitted)) "shown + omitted is the whole")
    (is (str/ends-with? shown "\n") "a multi-line text is cut after a whole line")
    (is (= (count (str/split-lines (subs msg offset))) lines) "the cut counts the lines it left")
    (is (str/includes? text (str "…+" omitted " chars in " lines " more lines")) "the cut says what it omitted")
    (is (= (list 'subs '(:message e) offset) continuation))
    (is (str/includes? text "(subs (:message e) ") "the cut names the form that reads the rest")
    (is (= msg (str shown (subs msg offset))) "the form reads exactly the rest"))
  (testing "one line: cut after a word, no form without a source"
    (let [s (str/join " " (repeat 60 "word"))
          {:keys [text shown omitted]} (seon/prose s 20)]
      (is (str/ends-with? shown " ") "cut at a space, not mid-word")
      (is (= (str shown "…+" omitted " chars") text) "no lines left: characters only, and no form")))
  (testing "one-line reads whitespace runs as one space"
    (is (= "a b c" (:text (seon/prose "a\n  b\t\tc" 20 {:one-line? true}))))))

(deftest more-says-how-many-and-where
  (is (nil? (seon/more 3 3 "x")))
  (is (= "+7 more: (seon.task/attempts db)" (seon/more 12 5 "(seon.task/attempts db)")))
  (is (= "+1 more" (seon/more 2 1 nil))))

(deftest a-one-line-cut-reads-the-source-rest
  (let [s (str "  first   line\n\n" (str/join "\n" (repeat 40 "a line of  the message")) " ")
        {:keys [offset omitted continuation]} (seon/prose s 20 {:one-line? true :source 'msg})]
    (is (= (list 'subs 'msg offset) continuation))
    (is (= (count s) (+ offset omitted)) "omitted counts what the form reads")
    (is (str/starts-with? (str/replace (subs s offset) #"\s+" " ") (str/trim (str/replace (subs s offset) #"\s+" " ")))
        "the form reads from a word, not the middle of one")
    (is (= (str/replace (str/trim s) #"\s+" " ")
           (str/trim (str (:shown (seon/prose s 20 {:one-line? true :source 'msg})) (str/replace (str/trim (subs s offset)) #"\s+" " "))))
        "shown, then the rest the form reads, is the whole line")))
