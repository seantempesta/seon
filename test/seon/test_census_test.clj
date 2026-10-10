(ns seon.test-census-test
  "Census of the laws about tests: no test or script redefines a Var."
  (:require [clojure.test :refer [deftest is]]
            [seon.census :as census]))

(def ^:private redefining-forms
  "Core forms that change a Var's root, bound by the law against Var redefinition."
  #{"with-redefs" "with-redefs-fn" "alter-var-root" "intern" "load-file"})

(deftest ^{:seon/tasks #{'seon.test-census-test/no-test-redefines-a-var}}
  no-test-redefines-a-var
  (let [scanned (census/files ["test" "script"] [".clj" ".cljc" ".bb"])
        {:keys [unreadable sites]} (census/scan scanned (fn [used _] (contains? redefining-forms (census/bare used))))]
    (is (> (count scanned) 100) "the census read the test and script trees")
    (is (= [] unreadable) "every file was read")
    (is (= [] (census/without-forms sites)) "each site is a top-level form redefining a Var")))
