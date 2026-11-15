(ns seon.ctx.status-test
  "seon.ctx.status's done-rule input, the judge's own (docs/tasks.md#the-done-rule). Run: bin/test seon.ctx.status-test"
  (:require [clojure.test :refer [deftest is]] [seon.reconcile :as r]))

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

