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
      (is (= :interrupted (try (intr/run nil (fn [] :ran)) (catch ThreadDeath _ :interrupted)))
          "a pending interrupt stops the next eval")
      (reset! pending true)
      (is (true? (intr/forked!)))
      (is (= [false ()] [@pending @stack]) "nothing pending, no eval of the parent's")
      (is (= :ran (intr/run nil (fn [] :ran))) "the child's own eval runs")
      (finally (reset! pending false) (reset! stack held)))))

;; See docs/server.md#ops
(deftest ^{:seon.test/estimate {:ticks 200000}} an-interrupt-between-runs-stops-the-next-run
  ;; root's reviewer on a8d254ccf9: a thread's interrupt flag found at a run's start that no recorded stop explains is a real interrupt
  ;; that came between runs: the run does not start (never cleared unread); the run after it starts as usual
  (.interrupt (Thread/currentThread))
  (is (thrown? ThreadDeath (intr/run 1000000 (fn [] :ran))) "the interrupt is answered, not swallowed")
  (is (= :ran (intr/run 1000000 (fn [] :ran))) "and consumed: the next run runs"))

;; See docs/server.md#ops
(deftest ^{:seon.test/estimate {:ticks 300000}} a-stop-of-an-ended-run-leaves-no-entry
  ;; root's reviewer on 3bfa064d5c: seon.intr's stopped is emptied by the runs it names. A stop of a live run records its token; the run
  ;; ends (stopped) and its finally removes that entry and clears its thread's flag; a stop of a run already ended records nothing
  (let [tok (jolt.host/make-interrupt)
        stopped @#'seon.intr/stopped
        me (Thread/currentThread)]
    (is (thrown? ThreadDeath (intr/run nil nil tok (fn [] (intr/stop! tok) (loop [i 0] (when (< i 100000000) (recur (inc i))))))) "the live run is stopped")
    (is (not (contains? @stopped me)) "its entry is gone with it")
    (is (not (Thread/interrupted)) "and its thread's flag is cleared")
    (is (false? (intr/stop! tok)) "a stop of the ended run finds nothing live")
    (is (not (contains? @stopped me)) "and records nothing")))
