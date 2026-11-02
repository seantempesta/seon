(ns seon.intr-test
  "Root's interrupt (seon.intr): what a forked child holds of its parent's."
  (:require [clojure.test :refer [deftest is]] [seon.intr :as intr]))

(deftest a-forked-child-holds-no-interrupt-of-its-parents
  ;; task seon.agent/a-test-child-starts-as-a-test-runner-does: root's interrupt that found no eval is pending until
  ;; the next request begins; a test child forked meanwhile answered every eval "interrupted by root". forked!
  ;; clears it, and the parent's evals on the stack, so the child's own evals run. This runner's own stack is put back
  (let [stack @#'seon.intr/stack pending @#'seon.intr/pending held @stack]
    (try
      (reset! pending true)
      (is (= :interrupted (try (intr/run nil (fn [] :ran)) (catch clojure.lang.ExceptionInfo e (when (:interrupted (ex-data e)) :interrupted))))
          "a pending interrupt stops the next eval")
      (reset! pending true)
      (is (true? (intr/forked!)))
      (is (= [false ()] [@pending @stack]) "nothing pending, no eval of the parent's")
      (is (= :ran (intr/run nil (fn [] :ran))) "the child's own eval runs")
      (finally (reset! pending false) (reset! stack held)))))
