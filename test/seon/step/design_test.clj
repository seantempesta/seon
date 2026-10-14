(ns seon.step.design-test
  "The Design stage's handoff (`docs/seon/schedule/cards/TASK-CHAIN.md#4-templates-and-their-proofs`)."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.step.design :as design]
            [seon.test :as test]
            [seon.test-support :as test-support]))

(def ^:private problem "## Problem\n\nA reference with no path throws.")

(def ^:private exists
  "\n\n## What exists\n\nThe producer is `seon.task/template`, covered by `seon.fs-test/under-path-tells-what-lies-under-a-root`.")

(defn- approach
  "An Approach section citing `cited`, each a backticked name."
  {:malli/schema [:=> [:cat [:* :string]] :string]}
  [& cited]
  (str "\n\n## Approach\n\nChange " (apply str (interpose " and " cited)) "."))

(defn- checks
  "The checks `design/handoff` refuses a Design task created with `problem` as its plan and holding `plan` now."
  {:malli/schema [:=> [:cat :string] [:vector :keyword]]}
  [plan]
  (test-support/with-database
   (fn [connection]
     (let [_ (test-support/transacted! connection (test-support/agent-tx (db/db connection) "root"))
           {task :seon.test/task} (test-support/task-input connection [{:db/id -1 :seon.task/ns 'tq.design :seon.task/name "one" :seon.task/plan problem
                                                                       :seon.task/created-by [:seon.agent/id "root"]}] 'tq.design/one)
           _ (db/transact! connection [{:seon.task/id (:seon.task/id task) :seon.task/plan plan}])
           database (db/db connection)
           current (db/stored-entity database (:e (first (db/datoms database :avet :seon.task/sym 'tq.design/one))))]
       (mapv :seon.step.design/check (design/handoff database current))))))

(deftest handoff
  (testing "the Design rows, each refusal set exact"
    (is (= [] (checks (str problem exists (approach "`seon.task/template`" "`:seon.ref/path`")))) "a registered schema, cited, is modeled")
    (is (= [:sections :producer-not-cited :data-not-modeled] (checks problem)) "no sections")
    (is (= [:sections :producer-not-cited :data-not-modeled] (checks (str problem "\n\n## What exists\n\n \n\n## Approach\n\n ")))
        "blank sections")
    (is (= [:problem-changed] (checks (str problem " It also fails twice." exists (approach "`seon.task/template`" "`:seon.ref/path`"))))
        "a Problem appended to")
    (is (= [:references] (checks (str problem exists (approach "`seon.task/template`" "`:seon.ref/path`" "`seon.step/no-such-function`"))))
        "a reference that does not resolve")
    (is (= [:producer-not-cited :data-not-modeled]
           (checks (str problem "\n\n## What exists\n\nIt is wrong.\n\n## Approach\n\nFix it.")))
        "no producer cited")
    (is (= [:data-not-modeled] (checks (str problem exists (approach "`seon.task/template`"))))
        "no schema key anywhere")
    (is (= [] (checks (str problem exists (approach "`seon.task/template`" "`:seon.ref/path`"))))
        "an existing-schema bug fix cites its key"))
  (testing "the runner's task"
    (when-let [{task :seon.test/task branch :seon.test/branch} (test/task)]
      (is (= [] (mapv (juxt :seon.step.design/check :seon.error/offending) (design/handoff branch task)))
          "the task is satisfied"))))
