(ns my.test-test
  "my.test: the world and its builders (task my.test/a-test-runs-in-the-world)."
  (:require [clojure.test :refer [deftest is]] [my.test :as t] [datahike.api :as d] [seon.task]))

(deftest the-world-is-built-once-per-process
  ;; docs/testing.md#testing-in-the-world: one world a process, every test's base
  (is (identical? (t/world) (t/world)))
  (is (= '#{app.calc/add app.calc/inc2 app.calc/total app.calc/twice app.report/line app.calc-test/add-adds app.calc-test/total-sums}
         (set (d/q '[:find [?n ...] :where [_ :var/name ?n]] (t/world))))
      "it holds what its docstring names"))

(deftest a-fixture-adds-to-the-world-and-leaves-it-as-it-was
  (let [db (t/with (t/world) (t/code 'app.x "(ns app.x)\n(defn f [x] (app.calc/add x 1))\n") (t/page "docs/x.md" "# X\n\nSee app.x/f.\n"))]
    (is (some? (d/entity db [:var/name 'app.x/f])) "the fixture's var")
    (is (nil? (d/entity (t/world) [:var/name 'app.x/f])) "the world unchanged: a value")))

(deftest a-world-test-runs-far-under-the-ceiling
  ;; the per-test ceiling is :seon.config.agent/test-ticks; a world and a fixture cost a sliver of it
  (let [{:keys [ticks]} (t/cost #(t/with (t/world) (t/code 'app.y "(ns app.y)\n(defn g [] 1)\n")))]
    (is (< ticks 20000000) (str "ticks: " ticks))))

(deftest the-pages-example-runs
  ;; docs/testing.md#testing-in-the-world's example, as written there
  (let [db (t/with (t/world)
                   (t/code 'app.x "(ns app.x)\n(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))\n"))]
    (is (= "[:=> [:cat :int] :int]" (:var/contract (d/entity db [:var/name 'app.x/f]))))))

(deftest without-takes-a-var-and-its-form-out
  (let [db (t/with (t/world) (t/without 'app.calc/inc2))]
    (is (nil? (d/entity db [:var/name 'app.calc/inc2])))
    (is (empty? (d/q '[:find ?f :where [?f :form/id "app.calc/inc2"]] db)) "its form too")
    (is (some? (d/entity db [:var/name 'app.calc/add])) "and nothing else")))

(deftest a-task-fixture-takes-the-tasks-it-needs
  (let [db (t/with (t/world) (t/task 'app.calc "next" "Then this." #{'app.calc-test/add-adds} #{"app.calc/total-is-exact"}))]
    (is (= #{'app.calc-test/add-adds 'app.calc-test/total-sums}
           (:proofs (:seon.task/accumulated (seon.task/task db "app.calc/next"))))
        "it inherits the proofs of the task it needs")))
