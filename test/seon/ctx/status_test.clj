(ns seon.ctx.status-test
  "seon.ctx.status's done-rule input, the judge's own (docs/tasks.md#the-done-rule). Run: bin/test seon.ctx.status-test"
  (:require [clojure.test :refer [deftest is]] [seon.reconcile :as r] [seon.ctx.status :as status] [my.test]))

(deftest ^{:seon.test/estimate {:ticks 200000}} the-status-reads-the-judges-done-input
  ;; the status built the done rule's input by hand when no test outside the proofs was red, beside the judge's
  ;; seon.reconcile/done-input, so the two could drift (a key the judge adds the status lacked). Both call seon.reconcile/input now:
  ;; for a branch with a proof red, one green, a contract gap and no stray red, the status's input and its reasons are the judge's
  (let [id "app/t" proofs '#{app.t-test/red app.t-test/green}
        results '{app.t-test/red :fail app.t-test/green :pass}
        gaps '#{app.t/f} unmerged [] deferred #{}
        idx {:root nil :tasks {id {:seon.task/id id :seon.task/kind :work}} :keys {} :needed-by {} :acc {id proofs} :depth {} :claims {}
             :agents {} :done {} :ready #{} :ready-key {} :live #{} :reap #{} :kill {} :judge #{} :merging #{}}
        judge (r/done-input idx id results gaps unmerged nil nil deferred)
        status (r/input {:proofs proofs :kind :work} results gaps unmerged deferred (constantly false) (constantly false))]
    (is (= judge status) (str "one input, whoever reads it: " (pr-str judge) " " (pr-str status)))
    (is (= (r/done-explain judge) (r/done-explain status)))
    (is (= #{:proof-red :contract-gap} (into #{} (map :reason) (r/done-explain status)))
        (str "the red proof and the gap are its reasons: " (pr-str (r/done-explain status))))))

;; docs/render.md#how-the-work-stands: round 2 (2026-11-15): an agent whose change reddened another task's test spent four turns of db
;; queries learning whose it was. A red test outside the task's proofs carries the tasks claiming it. Estimate: one refused call
;; over two reasons, ~50k ticks; 1,000,000
(deftest ^{:seon.test/estimate {:ticks 1000000 :bytes 1000000}} the-status-names-each-reached-tests-claiming-task
  (let [refused @#'seon.ctx.status/refused
        reasons [{:reason :stray-red :symbol 'app.b-test/other} {:reason :stray-red :symbol 'app.c-test/nobodys}]
        claims {'app.b-test/other [{:task "app.b/the-other-task" :outcome :merged}]}
        rows (refused reasons [] (constantly nil) (constantly nil) (constantly false) #(get claims % []))
        by (into {} (map (juxt :test identity)) rows)]
    (is (= [{:task "app.b/the-other-task" :outcome :merged}] (:claimed-by (by 'app.b-test/other))) (pr-str rows))
    (is (not (contains? (by 'app.c-test/nobodys) :claimed-by)) "a test nobody claims says nothing of claims")))


(deftest ^{:seon.test/estimate {:ticks 200000}} an-open-problem-blocker-carries-the-form-that-clears-it
  ;; docs/health.md#a-key-with-no-schema: round 4's runs reached their fix and stopped on open problems told in words; the status's
  ;; :open-problem blocker carries each of its problems' clearing forms (the caused rows' :do, joined by message), as a red proof its own
  (let [clearing (deref #'seon.ctx.status/clearing-forms)
        rows [{:message "key :a/b has no schema" :do '(my.schema/register! :a/b [:int])}
              {:message "key :a/c has no schema" :do '(my.schema/register! :a/c [:int])}
              {:message "told in words only"}]
        [one several none red] (clearing rows [{:reason :open-problem :problems ["key :a/b has no schema"]}
                                               {:reason :open-problem :problems ["key :a/b has no schema" "key :a/c has no schema"]}
                                               {:reason :open-problem :problems ["told in words only"]}
                                               {:reason :proof-red :problems ["key :a/b has no schema"]}])]
    (is (= '(my.schema/register! :a/b [:int]) (:do one)) (pr-str one))
    (is (= '(do (my.schema/register! :a/b [:int]) (my.schema/register! :a/c [:int])) (:do several)) "several, one do of their forms")
    (is (not (contains? none :do)) "a problem with no form says none")
    (is (not (contains? red :do)) "only an open problem's")))

;; docs/render.md#how-the-work-stands: a proof to write is shown one test of its namespace to copy. A seon-driver copied the shortest,
;; which had no estimate, and its prove then asked for one: a turn lost (2026-11-16). The example is the shortest that states its
;; estimate, as a new test must. Red before: the shortest, unestimated. Estimate: a world (~2M) and one test-shape read (~0.2M): 3,000,000
(deftest ^{:seon.test/estimate {:ticks 3000000 :bytes 30000000}} a-proof-to-write-is-shown-a-test-that-states-its-estimate
  (let [db (my.test/with (my.test/world)
                         (my.test/code 'app.shape-test (str "(ns app.shape-test (:require [clojure.test :refer [deftest is]]))\n"
                                                            "(deftest short (is true))\n"
                                                            "(deftest ^{:seon.test/estimate {:ticks 100000}} with-estimate (is (= 1 1)))\n")))
        shape (status/test-shape db 'app.shape-test/new-proof)]
    (is (clojure.string/includes? (str (:example shape)) ":seon.test/estimate") (str "the example states its estimate: " (pr-str (:example shape))))))
