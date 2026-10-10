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

(defn- hardcoded
  "Sites in `{:file :in :use}` form: top-level constants and nested defaults naming a policy number."
  [path]
  (let [forms (census/top-level-forms path)
        nested (fn nested [form] (cond (literal-default? form) [form]
                                       (coll? form) (mapcat nested (if (map? form) (mapcat identity form) form))))]
    (for [form forms
          :let [uses (concat (when (constant? form) [(second form)])
                             (map #(first (filter symbol? (rest %))) (nested form)))]
          :when (seq uses)]
      {:file path :in (second form) :uses (vec uses)})))

(deftest ^{:seon/tasks #{'seon.source-census-test/no-timeout-or-limit-is-a-hardcoded-number}}
  no-timeout-or-limit-is-a-hardcoded-number
  (let [sites (vec (mapcat hardcoded @source-files))]
    (is (> (count @source-files) 100) "the census read the source tree")
    (is (= [] sites) "each site is a top-level constant or an or-default naming a policy number")))

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
