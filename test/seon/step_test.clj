(ns seon.step-test
  "The checks the stage templates' handoffs share (`docs/seon/schedule/cards/TASK-CHAIN.md#4-templates-and-their-proofs`)."
  (:require [clojure.test :refer [are deftest is]]
            [seon.db :as db]
            [seon.step :as step]
            [seon.test-support :as test-support]))

(deftest sections-split-a-plan-at-its-headings
  (are [plan expected] (= expected (step/sections plan))
    "" {}
    "## Problem\n\nIt is wrong." {"problem" "\nIt is wrong."}
    "## Problem\n\nA.\n\n## What exists\n\nB.\n### Deeper\nC." {"problem" "\nA.\n" "what-exists" "\nB." "deeper" "C."}))

(deftest problems-are-the-references-that-do-not-resolve
  (let [database (db/db)]
    (are [text kinds] (= kinds (mapv :seon.ref/problem-kind (step/problems database text)))
      "No reference here." []
      "Cites `seon.task/template`." []
      "Cites `seon.step/no-such-function-anywhere`." [:unresolved])))

(deftest a-cited-function-and-a-deftest-are-told-apart
  (let [database (db/db)]
    (is (= '#{seon.task/template} (step/cited-fns database "Cites `seon.task/template` and `seon.fs-test/under-path-tells-what-lies-under-a-root`.")))
    (is (step/deftest? database 'seon.fs-test/under-path-tells-what-lies-under-a-root))
    (is (not (step/deftest? database 'seon.task/template)))))

(deftest results-hold-only-the-tests-that-ran-at-their-reach
  (is (= {} (step/results (db/db) ['seon.step-test/no-such-test]))))

(deftest the-task-input-fixture-supplies-the-task-and-its-branch
  (test-support/with-database
   (fn [connection]
     (let [input (test-support/task-input connection
                                          [{:db/id -1 :seon.task/ns 'tq.step :seon.task/name "one" :seon.task/plan "## Problem\n\nP." :seon.task/next -2}
                                           {:db/id -2 :seon.task/ns 'tq.step :seon.task/name "two"}]
                                          'tq.step/one)]
       (is (= 'tq.step/one (get-in input [:seon.test/task :seon.task/sym])))
       (is (= 'tq.step/two (:seon.task/sym (db/pull (:seon.test/branch input) [:seon.task/sym] [:seon.task/sym 'tq.step/two])))
           "the branch value holds the written chain")))))
