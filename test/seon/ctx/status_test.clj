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
