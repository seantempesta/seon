(ns seon.print-test
  "Laws of the value printer (Seon's `seon.render.value-law-test`, on the slice): the text stays
  inside the budget, every cut's form reads exactly what the cut says it left (read off the
  text, as an agent reads it), and an uncut text reads back as the value."
  (:require [clojure.edn :as edn] [seon.proofs :as proofs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.print :as seon]
            [clojure.walk :as walk]
            [seon.print.select :as select] [seon.print.emit :as emit] [seon.contract]
            [fipp.engine :as fipp]))

(defn cuts-of
  "Every cut in printed text, read as an agent reads it: how many it says it left (:n, nil for
  `…?`, members not yet counted), whether in characters (:chars?; a long name's cut is `…+N`
  with neither, :name?), and the form after it that reads the rest (:form, nil when none)."
  [text]
  (loop [from 0 cuts []]
    (if-let [i (str/index-of text "…" from)]
      (let [[m plus n chars?] (re-find #"^…(\+?)(\d+|\?)( chars(?: in \d+ more lines?)?)?" (subs text i))
            after (when m (subs text (+ i (count m))))
            form (when (and after (re-find #"^ [(r]" after)) (edn/read-string (subs after 1)))]
        (recur (inc i) (cond-> cuts m (conj {:n (parse-long n) :chars? (some? chars?) :name? (and (= "+" plus) (not chars?)) :form form}))))
      cuts)))

(defn read-rest
  "What form reads when its handle is v (h, the handle the printer was given)."
  [form h v]
  ((eval (list 'fn '[v] (walk/postwalk-replace {h 'v} form))) v))

(defn reads-exactly?
  "Does cut c of text, printed of v through handle h, read exactly what it says it left? A
  string's form `(subs s k)` reads its last n characters and text shows s's first k before the
  cut; a collection's `(drop k c)` reads its last n members; a collection's own form reads all
  of it: n members when it shows none, and a map or set shown sorted (its members' order is not
  the collection's) is read whole with at most n left."
  [text h v {:keys [n chars? form]}]
  (let [rest (read-rest form h v)
        drop? (and (seq? form) (= 'drop (first form)))]
    (cond
      chars? (let [[whole k] (if (and (seq? form) (= 'subs (first form))) [(read-rest (second form) h v) (nth form 2)] [rest 0])]
               (and (= n (count rest)) (= rest (subs whole k))
                    (str/includes? text (str (emit/string-literal (subs whole 0 k)) "…+" n))))
      (nil? n) (some? (seq rest))
      drop? (let [c (read-rest (nth form 2) h v)]
              (and (= (nth form 1) (- (count c) n)) (= (seq rest) (seq (drop (- (count c) n) c)))))
      (or (map? rest) (set? rest)) (<= n (count rest))
      :else (= n (count rest)))))

(defn- lawful? [raw budget]
  (let [h 'result/e0123456789ab
        {:keys [text truncated? elisions]} (seon/print-value raw {:budget budget :handle h})
        cuts (cuts-of text)]
    (boolean
     (and (<= (select/estimate text) budget)
          (= elisions (count cuts))
          ;; through a handle every cut names its form, but a long name's
          (every? #(if (:name? %) (nil? (:form %)) (and (:form %) (reads-exactly? text h raw %))) cuts)
          (or truncated? (= raw (edn/read-string text)))))))

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
  ;; a first member whose least form fits is shown, though a string four levels down cannot show
  (is (lawful? [[[["aaaaa\"a\"a\"a\"aa\"\"aa\"aa\"\"aaaa\"a\naaa\"a\"aaaaaa\"aa\"\na\"\"aaa\"aa\"\"\"\"a\"\"aa\"\"a\"aaaa\"\"\"\"aaa\"a\"\"\"a\"a\"\"\"a\"\"\"\"\"\"\"\"aaa\"\"a\"a\"a"]]]] 60))
  (doseq [budget [40 120 400]]
    (is (lawful? {:a {:b (str/join "\n" (repeat 30 "a line of text")) :c [1 2 {:d "x\ny" :e {:f (vec (range 40))}}]}
                  :g (str/join "\n" (repeat 5 "zz")) :h {:i {:j {:k "deep"}}}}
                 budget)))
  (let [r (tc/quick-check 150 (prop/for-all [raw value-gen budget (gen/elements [60 200 800 1500])] (lawful? raw budget))
                          :seed (proofs/test-seed) :max-size 40)]
    (is (:pass? r) (str "a printed value broke its budget or a cut's continuation: "
                        (pr-str (select-keys r [:seed]) (get-in r [:shrunk :smallest]))))))

(deftest a-cut-counts-the-lines-it-left
  ;; the line breaks are found once per string (select/line-breaks), and each cut counts the
  ;; lines after it by binary search: the same count as reading the text after the cut
  (let [naive (fn [^String s at] (+ (if (and (< at (count s)) (or (zero? at) (= \newline (.charAt s (dec at))))) 1 0)
                                    (count (filter #(and (= \newline (.charAt s %)) (< (inc %) (count s))) (range at (count s))))))]
    (doseq [s ["" "a" "\n" "a\n" "\na" "a\nb" "a\n\nb\n" "\n\n\n" (str/join "\n" (repeat 40 "a line"))]
            at (range (inc (count s)))]
      (is (= (naive s at) (select/lines-after (select/line-breaks s) (count s) at)) (pr-str s at)))))

(deftest escaped-strings-stay-printable-under-a-small-budget
  (doseq [raw [(apply str (repeat 64 "\"")) (apply str (repeat 64 "\\")) (apply str (repeat 64 "\t")) [(apply str (repeat 64 "\""))]]]
    (let [{:keys [text]} (seon/print-value raw {:budget 40 :handle 'result/h1})
          [c :as cs] (cuts-of text)]
      (is (string? text) "the value prints rather than refusing the budget")
      (is (= 1 (count cs)) text)
      (is (reads-exactly? text 'result/h1 raw c) "the cut continues exactly"))))

;; ---- the one printer, at every place a value is shown ----------------------------------

(deftest a-reply-value-cut-reads-its-rest-through-its-handle
  (let [{:keys [text truncated?]} (seon/print-value (vec (range 5000)) {:handle 'result/h7 :budget 100})
        [{:keys [n form]}] (cuts-of text)]
    (is truncated?)
    (is (re-find #"\(drop \d+ result/h7\)" text) "the cut names the form that reads the rest")
    (is (= (list 'drop (- 5000 n) 'result/h7) form) text)
    (is (= (range (- 5000 n) 5000) (read-rest form 'result/h7 (vec (range 5000)))) "shown + omitted is the whole")))

(deftest a-cut-reads-its-rest-through-a-form-handle
  ;; a value read by a form rather than held by a result var (one of the last turn's evals):
  ;; every cut names that form, and the form reads exactly the rest
  (let [it {:form "(pp (code-of 3))" :out (apply str (repeat 400 "line 3 of output\n"))}
        evals [{} {} {} it]
        h '(nth (my.turn/evals) 3)
        {:keys [text]} (seon/print-value it {:handle h :budget 60})
        [c] (cuts-of text)]
    (is (= ['subs (list 'get-in h [:out])] (take 2 (:form c))) (pr-str c))
    (is (re-find #"\(subs \(get-in \(nth \(my.turn/evals\) 3\) \[:out\]\) \d+\)" text) text)
    (is (reads-exactly? text '(my.turn/evals) evals c) "the form reads exactly the rest")))

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
        seed (proofs/test-seed)
        r (java.util.Random. seed)
        docs (concat (for [_ (range 600)] [(vec (fipp/serialize (random-doc r 0))) (inc (.nextInt r 40))])
                     ;; a group holding more than `width` open groups breaks, though it fits
                     (for [w [3 5 8] depth [2 5 8 12]]
                       [(vec (fipp/serialize [:group "a" :line (reduce (fn [d _] [:group d]) "b" (range depth))])) w]))
        wrong (for [[ops width] docs :when (seq ops) :when (not= (fipp-text ops width) (layout ops width))] [ops width])]
    (is (empty? wrong) (str "laid out unlike fipp (seed " seed "): " (pr-str (first wrong))))
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

(deftest members-fill-breadth-before-depth
  ;; notes/design-agent-view-2026-10-24.md §4 (a): a sequence shows every member at its least
  ;; before any one grows, as a map does (it filled in order, and the first member that did not
  ;; fit took the rest); what is left grows them in layout order
  (let [v [(apply str (repeat 300 "a")) (apply str (repeat 300 "b")) :c]
        {:keys [text]} (seon/print-value v {:handle 'result/e1 :budget 60})]
    (is (str/includes? text ":c") text)
    (is (str/includes? text "\"b") "the second long member shows its first part" )
    (is (> (count (re-find #"a+" text)) (count (re-find #"b+" text))) "the first member grows first")))

(deftest an-earlier-value-prints-as-its-handle
  ;; notes/design-agent-view-2026-10-24.md §1: a value an earlier response showed prints as the
  ;; form that reads it again, never twice
  (let [big (vec (range 200))
        {:keys [text]} (seon/print-value {:handle 'result/e2 :value {:xs big :n 1}} {:handle 'result/e2 :seen {:colls {(System/identityHashCode big) [[big '(:value result/e1)]]}}})]
    (is (str/includes? text ":xs (:value result/e1)") text)
    (is (not (str/includes? text "199")) text)
    (is (str/includes? (:text (seon/print-value {:xs (vec (range 200))} {:handle 'result/e2 :seen {:colls {(System/identityHashCode big) [[big '(:value result/e1)]]}}})) "199")
        "an equal value that is not the same one prints as itself: collections match by identity")))

(defn- problem-rows
  "n rows shaped as main's :stale-section problem rows (my.root/problems, 2026-10-25: 177 of them)."
  [n]
  (vec (for [i (range n)]
         (array-map :seon.docs/section (keyword "render" (str "section-" i)) :seon.docs/page "docs/render.md"
                    :seon.problem/class :stale-section :seon.problem/since 1761360000000 :cited 'seon.print/print-value
                    :lead "A section whose lead cites a var that moved." :n i))))

(deftest rows-show-whole-then-one-cut
  ;; root's (my.root/problems sys), 2026-10-25: :stale-section's 177 rows printed as 144 lines of
  ;; `{…13 (get-in result/e1 [... :rows N])}` and a cut, each row at its least, a line and a form
  ;; that say nothing. Rows (members of one collection class) show whole in their order, then
  ;; ONE cut that names how many are left and the form that reads them.
  (let [h 'result/e1
        v {:value {:classes {:stale-section {:n 177 :rows (problem-rows 177)}}}}
        {:keys [text elisions]} (seon/print-value v {:handle h})
        [cut :as cuts] (cuts-of text)
        shown (count (re-seq #":seon.docs/section" text))]
    (is (= 1 elisions (count cuts)) text)
    (is (not (re-find #"\{…" text)) "no row shows as a placeholder")
    (is (< 40 shown 177) "the reply budget shows dozens of rows, each whole")
    (is (= (- 177 shown) (:n cut)) "the cut counts the rows it left")
    (is (reads-exactly? text h v cut) "and its form reads exactly them")
    (testing "a map of rows: its entries whole in their order, then one cut"
      (let [m (into {} (map (juxt :n identity)) (problem-rows 177))
            {:keys [text elisions]} (seon/print-value m {:handle h})]
        (is (= 1 elisions) text)
        (is (not (re-find #"\{…" text)))))
    (testing "rows of array maps and of hash maps are one kind"
      (let [mixed (into (problem-rows 100) (map #(into % {:a 1 :b 2})) (problem-rows 100))
            {:keys [text elisions]} (seon/print-value mixed {:handle h})]
        (is (= 1 elisions) text)
        (is (not (re-find #"\{…" text)))))
    (testing "members unlike each other still share the room (members-fill-breadth-before-depth)"
      (let [{:keys [text]} (seon/print-value ["x" (problem-rows 3) :y] {:handle h :budget 60})]
        (is (str/includes? text ":y") text)
        (is (str/includes? text "\"x\"") text)))))

(deftest rows-reserve-the-cut-they-print
  ;; root's report through its render (seon.render.ai/report-data), 2026-10-25: at 13 budgets
  ;; between 2,000 and 9,000 tokens it printed 347, :stale-section's 177 rows one placeholder.
  ;; fill-rows reserved a cut without its form's path, overran its room, and the map's growth
  ;; then kept the rows at their least. A value with room left prints most of its budget.
  (let [why #(str "docs/health.md#a-stale-section describes seon.problems/stale-sections as it was (form seon.problems/"
                  (format "%08d" %) "), and it has changed")
        v {:handle 'result/e1
           :value (array-map :problems-on-main-at-t 536872831
                             :classes (sorted-map :dead-citation {:n 2 :rows [{:why (why 1)} {:why (why 2)}]}
                                                  :stale-section {:n 177 :rows (vec (for [i (range 177)] {:why (why i)}))}))}
        starved (for [b (range 1000 6200 37)
                      :let [{:keys [tokens]} (seon/print-value v {:handle 'result/e1 :budget b})]
                      :when (< tokens (* 0.8 b))]
                  [b tokens])]
    (is (empty? starved) (str "budgets that printed under 80% of themselves: " (vec (take 5 starved))))))

(def ^:private a-deftest
  "A test's source as its file holds it: a comment, a string with quotes, nested indentation."
  "(deftest a-proof-with-no-result-reads-unknown
  ;; a proof with no result never ran; the context says :unknown
  (let [db (db-with-program)
        t (task/task db \"demo/f\")]
    (is (= :unknown (status {:results {} :turn 0}))
        \"no result: it never ran, unknown (the judge's word)\")))")

(deftest code-prints-as-written
  ;; owner, 2026-10-25: \"if we reference a function we should return the quote escaped forms so
  ;; agents see more clojure code as is normally seen in files or the repl\": source its producer
  ;; marks as code (seon.print/code) prints verbatim at its place, not as an escaped string
  (let [h 'result/e1
        v {:test 'demo-test/f-test :status :fail :source (seon/code a-deftest)}
        {:keys [text elisions]} (seon/print-value v {:handle h})
        lines (str/split-lines a-deftest)]
    (is (zero? elisions) text)
    (is (not (str/includes? text "\\\"")) "no quote is escaped")
    (is (not (str/includes? text "\\n")) "no line break is escaped")
    (is (str/includes? text (first lines)) "its first line as written")
    (is (every? #(str/includes? text %) (rest lines)) "every later line as written, at its place")
    (is (= (edn/read-string a-deftest) (:source (edn/read-string text))) "and it reads back as the form")
    (testing "cut, it says what it left and reads exactly the rest"
      (let [{:keys [text]} (seon/print-value {:source (seon/code (str/join "\n" (repeat 40 a-deftest)))} {:handle h :budget 120})
            [cut] (cuts-of text)
            rest (read-rest (:form cut) h {:source (seon/code (str/join "\n" (repeat 40 a-deftest)))})]
        (is (:chars? cut) text)
        (is (= (:n cut) (count rest)) "the form reads the characters it says it left")))))


;; ---- the page's shapes: the hiccup fold over the same op stream (the text is unchanged) ---

(defn- elements
  "Every element of hiccup h whose tag is tag (and, given, whose class is cls)."
  ([h tag] (elements h tag nil))
  ([h tag cls] (filter #(and (vector? %) (= tag (first %)) (or (nil? cls) (= cls (:class (second %))))) (tree-seq coll? seq h))))

(deftest a-map-is-key-and-value-rows
  (let [{:keys [text html]} (seon/print-value {:a 1 :b "x" :c {:d 2}} {:outputs #{:text :hiccup}})
        grids (elements html :div "seon-print-grid")]
    (is (= {:a 1 :b "x" :c {:d 2}} (clojure.edn/read-string text)) "the text is the printer's, reading back as the value")
    (is (= 2 (count grids)) "the map and the map in it, each a grid")
    (is (= 3 (count (filter #(and (vector? %) (= "seon-print-entry" (:class (second %)))) (first grids)))) "a row per entry")
    (is (empty? (elements html :details)) "a map of scalars nested in the value printed folds nowhere")
    (is (empty? (elements (first grids) :span "seon-print-delimiter")) "no braces or commas: the grid is the layout")))

(deftest maps-sharing-keys-are-a-table
  (let [v [{:test 'demo/a :status :fail :n 1} {:test 'demo/b :status :pass}]
        {:keys [text html]} (seon/print-value v {:outputs #{:text :hiccup}})
        [table] (elements html :table "seon-print-table")]
    (is (= v (clojure.edn/read-string text)) "the text is the printer's, reading back as the value")
    (is table "two maps sharing two keys")
    (is (= #{":test" ":status" ":n"} (set (map #(last (last %)) (elements table :th)))) "a column per key")
    (is (= 3 (count (elements table :tr))) "a header, and a row per map")
    (is (= 6 (count (elements table :td))) "a cell per key per row, the missing :n empty")
    (is (nil? (first (elements (seon/print-value [{:a 1} {:a 2}] {:outputs #{:hiccup}}) :table))) "one shared key: no table")))

(deftest code-and-prose-read-as-themselves
  (let [src "(defn f\n  \"doc\"\n  [x] ; a comment\n  x)"
        code (seon/print-value {:source (seon/code src)} {:outputs #{:text :hiccup}})
        prose (seon/print-value {:doc {:seon.print/prose "Says one thing.\nThen another."}} {:outputs #{:text :hiccup}})]
    (is (str/includes? (:text code) "; a comment") "code prints verbatim")
    (is (= [src] (map last (elements (:html code) :code "language-clojure"))) "on the page, verbatim in <pre><code>, highlighted as Clojure")
    (is (str/includes? (:text prose) "Says one thing.\n") "prose prints unquoted, its line breaks kept")
    (is (not (str/includes? (:text prose) "\"Says")))
    (is (= ["Says one thing.\nThen another."] (map last (elements (:html prose) :p "seon-print-prose"))) "on the page, a paragraph")))

(deftest a-cut-of-prose-reads-its-rest-as-prose
  (let [s (str/join "\n" (repeat 400 "a line of the plan"))
        text (:text (seon/print-value {:plan {:seon.print/prose s}} {:handle 'result/e3 :budget 200}))]
    (is (str/includes? text "(:seon.print/prose (get-in result/e3 [:plan]))") text)))

(deftest a-handle-links-to-its-row
  (let [{:keys [html]} (seon/print-value {:read 'result/e12 :name 'demo/f} {:outputs #{:hiccup}})]
    (is (= [{:class "seon-print-symbol" :href "#eval-e12"}] (map second (elements html :a))) "result/e12 links to #eval-e12; demo/f is a symbol")))

(deftest a-table-keeps-its-cuts-and-nested-cells
  ;; self-review: a cut in a table is a row of its own, a nested map is its cell, and cells are
  ;; found by key whatever order each map prints its keys in
  (let [v (vec (for [i (range 40)] {:test (symbol "demo-test" (str "t" i)) :status (if (even? i) :pass :fail) :at {:line i :file "f.clj"}}))
        {:keys [html]} (seon/print-value v {:outputs #{:hiccup} :budget 300})
        [table] (elements html :table "seon-print-table")
        rows (rest (elements table :tr))]
    (is table)
    (is (some #(str/includes? (pr-str %) "seon-print-elision") rows) "the collection's cut is a row of its own")
    (is (some #(= "seon-print-grid" (:class (second %))) (tree-seq coll? seq table)) "a nested map is its cell, a grid")
    (is (str/includes? (pr-str (second rows)) "demo-test/t0") "a row's cells under their keys")))

(deftest a-lifted-map-keeps-its-namespace
  ;; self-review: a map of one namespace's keys prints #:ns{...}; the grid drops the braces, so its
  ;; caption keeps the namespace
  (let [{:keys [text html]} (seon/print-value {:seon.run/id "r" :seon.run/status :done} {:outputs #{:text :hiccup}})]
    (is (str/starts-with? text "#:seon.run{") text)
    (is (= ["#:seon.run"] (map last (elements html :div "seon-print-caption"))))))
