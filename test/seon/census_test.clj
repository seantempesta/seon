(ns seon.census-test
  "The censuses read what the branch holds, not files an agent cannot change."
  (:require [clojure.test :refer [deftest is]]))

(deftest ^{:seon/tasks #{'seon.census/a-census-reads-the-program-its-branch-holds}}
  a-docstring-census-reads-the-branchs-database
  (let [docstrings (requiring-resolve 'seon.census/docstrings)]
    (is (some? docstrings) "seon.census answers a namespace's docstrings from the database")
    (when docstrings
      (let [rows (docstrings 'seon.problems)]
        (is (> (count rows) 20) "the census read the namespace's functions")
        (is (some #(and (= 'seon.problems/failed-tests (:in %)) (string? (:text %))) rows)
            "a row names its function and holds its docstring")))))
