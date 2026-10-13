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

(defn- redefined-vars
  "The symbols each `with-redefs` form under `form` binds, in reading order."
  [form]
  (->> (tree-seq coll? seq form)
       (filter #(and (seq? %) (= "with-redefs" (some-> (first %) (as-> head (when (symbol? head) (census/bare head)))))))
       (mapcat #(take-nth 2 (second %)))
       (filter symbol?)))

(deftest the-model-call-is-an-input-never-a-redefinition
  (let [scanned (census/files ["test" "script"] [".clj" ".cljc" ".bb"])
        model-call? #(and (= "complete" (name %)) (contains? #{"ai" "seon.ai"} (namespace %)))
        sites (for [path scanned
                    :let [forms (census/top-level-forms path)]
                    :when (sequential? forms)
                    form forms
                    redefined (redefined-vars form)
                    :when (model-call? redefined)]
                {:file path :in (second form)})]
    (is (= [] (vec sites)) "tests pass the completer as the handle's `:seon.turn.loop/complete`")))
