; Reproduce on dabd311d0 with the canonical armed runner:
; clojure -M:test -i docs/prds/context-generation/research/backstop_misc_baseline_2026_09_15.clj -m seon.test.fast seon.backstop-baseline-test
(ns seon.backstop-baseline-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent-test :as agent-test]))
(deftest existing-parallel-trial-at-head
  (let [verdict (#'agent-test/parallel-trial 1 [1])]
    (println :baseline-verdict verdict)
    (is (every? val verdict))))
