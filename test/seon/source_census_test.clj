(ns seon.source-census-test
  "Census of the laws about what src may carry: no dates or file:line citations in prose, and no policy
  number outside a dial."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.census :as census]
            [seon.db :as db]))

(def ^:private source-files (delay (census/files ["src"] [".clj" ".cljc"])))

(def ^:private iso-date #"\d{4}-\d{2}-\d{2}")

(def ^:private file-line #"\.clj[cs]?:\d+")

(defn- sites-of
  "Distinct `{:file :in :kind}` sites of prose rows whose text satisfies `pattern`, with their count."
  [rows pattern]
  (vec (sort-by (juxt :file str) (distinct (for [row rows :when (re-find pattern (:text row))] (dissoc row :text))))))

(deftest ^{:seon/tasks #{'seon.source-census-test/source-text-carries-no-date-or-file-line}}
  source-text-carries-no-date-or-file-line
  (let [rows (census/prose @source-files)]
    (is (> (count rows) 1000) "the census read the comments and docstrings of the source tree")
    (is (= [] (sites-of rows iso-date)) "each site is a comment or docstring carrying an ISO date")
    (is (= [] (sites-of rows file-line)) "each site is a comment or docstring citing a file and line")))

(deftest the-render-and-flow-docstrings-once-citing-a-file-line-or-date-cite-by-symbol
  (let [subjects '#{call-reach-key call-reach fault-port}
        rows (filter #(contains? subjects (:in %)) (census/prose @source-files))]
    (is (= 3 (count (distinct (map :in rows)))) "the census read the three docstrings")
    (is (= [] (into (sites-of rows iso-date) (sites-of rows file-line)))
        "each docstring cites code by symbol and carries no date, measurement or file and line")))

(def ^:private policy-words
  "Name parts that make a numeric constant a timeout, size or limit, which belongs in a dial."
  #{"ms" "bytes" "limit" "seconds" "timeout" "threshold" "deadline" "budget" "bound"})

(defn- policy-name?
  [named]
  (boolean (some policy-words (str/split (name named) #"-"))))

(defn- literal-default?
  "An `(or x n)` form defaulting a policy-named `x` to a nonzero number."
  [form]
  (and (seq? form) (= 'or (first form))
       (some #(and (symbol? %) (policy-name? %)) (rest form))
       (some #(and (number? %) (not (contains? #{0 -1 1} %))) (rest form))))

(defn- constant?
  [form]
  (and (seq? form) (contains? #{'def 'defonce} (first form)) (number? (last form))
       (symbol? (second form)) (policy-name? (second form))))

(defn- render-or-printer?
  "True of the sources of the printer and of the render owners, where a number is a dial."
  [path]
  (or (str/starts-with? path "src/seon/render") (= "src/seon/print.cljc" path)))

(defn- numeric-def?
  "A `def` or `defonce` whose value is a number literal."
  [form]
  (and (seq? form) (contains? #{'def 'defonce} (first form)) (symbol? (second form)) (number? (last form))))

(defn sites-of-forms
  "Sites in `{:file :in :uses}` form of `forms` read from `path`: top-level constants and nested defaults naming a
  policy number, calls whose count is a literal (`seon.census/literal-count-call?`), and, in the printer and render
  sources, every numeric `def`."
  [path forms]
  (let [nested (fn nested [form] (cond (literal-default? form) [form]
                                       (coll? form) (mapcat nested (if (map? form) (mapcat identity form) form))))]
    (for [form forms
          :let [uses (concat (when (constant? form) [(second form)])
                             (when (and (numeric-def? form) (not (constant? form)) (render-or-printer? path)) [(second form)])
                             (map #(first (filter symbol? (rest %))) (nested form))
                             (map first (census/literal-count-calls form)))]
          :when (seq uses)]
      {:file path :in (second form) :uses (vec uses)})))

(defn- hardcoded
  "Sites of the source file `path`."
  [path]
  (let [forms (census/top-level-forms path)]
    (if (= ::census/unreadable forms) [] (sites-of-forms path forms))))

(deftest ^{:seon/tasks #{'seon.source-census-test/no-timeout-or-limit-is-a-hardcoded-number}}
  no-timeout-or-limit-is-a-hardcoded-number
  (let [sites (vec (mapcat hardcoded @source-files))]
    (is (> (count @source-files) 100) "the census read the source tree")
    (is (= [] sites) "each site is a constant, an or-default or a literal count naming a policy number")))

(deftest the-hardcoded-number-rule-catches-a-planted-literal-count-and-numeric-def
  (let [sites (fn [path text] (vec (sites-of-forms path (census/read-forms text))))]
    (is (seq (sites "src/seon/x.clj" "(defn f [s] (subs s 0 200))")) "a literal subs end is caught")
    (is (seq (sites "src/seon/x.clj" "(defn f [xs] (take 8 xs))")) "a literal take is caught")
    (is (seq (sites "src/seon/x.clj" "(defn f [xs] (->> xs (take-last 12)))")) "a threaded take-last is caught")
    (is (seq (sites "src/seon/x.clj" "(defn f [xs] (drop-last 3 xs))")) "a literal drop-last is caught")
    (is (= [] (sites "src/seon/x.clj" "(defn f [xs n] (take n (drop 3 xs)))")) "a computed count is spared")
    (is (= [] (sites "src/seon/x.clj" "(def width 40)")) "a numeric def outside the printer and render sources is spared")
    (is (seq (sites "src/seon/render/x.clj" "(def width 40)")) "a numeric def in a render source is caught")))

(deftest ^{:seon/tasks #{'seon.problems/problems-prose-carries-no-date-or-file-line}}
  problems-prose-carries-no-date-or-file-line
  ;; the docstrings are read from the branch the test runs on (custody supplies the database),
  ;; so a redefinition on the branch is what the census sees; a fixture database would show main's
  (let [docs (db/q '[:find ?sym ?doc
                     :where [?namespace :seon.ns/name seon.problems] [?e :seon.fn/ns ?namespace]
                     [?e :seon.fn/sym ?sym] [?e :seon.fn/doc ?doc]])]
    (is (> (count docs) 20) "the census read the docstrings of seon.problems")
    (is (= [] (vec (sort (for [[sym doc] docs :when (re-find (re-pattern (str iso-date "|" file-line)) doc)] sym))))
        "each function's docstring carries an ISO date or a file and line")))
