(ns my.run-test
  "Sealed acceptance draft for the two dispositions (N3, package 1).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). These are values,
  so the suite is short by construction: the whole contract is that the
  shapes validate, that they are the ONLY two, and that a bad argument
  comes back as a value an agent can read rather than a throw it
  cannot."
  (:require [clojure.test :refer [deftest is testing]]
            [my.run :as run]
            [seon.schema]))

(deftest a-disposition-is-an-ordinary-value
  (testing "wait carries its reason"
    (let [value (run/wait "waiting for the file to land")]
      (is (seon.schema/valid-candidate-value? :my.run/wait value))
      (is (= :wait (:my.run/disposition value)))))
  (testing "complete carries the reply itself — there is no result attribute"
    (let [value (run/complete "the answer is 42")]
      (is (seon.schema/valid-candidate-value? :my.run/completed value))
      (is (= :completed (:my.run/disposition value)))
      (is (= "the answer is 42" (:my.run/result value)))))
  (testing "both validate as the disposition union the loop reads"
    (is (seon.schema/valid-candidate-value?
         :my.run/value (run/wait "later")))
    (is (seon.schema/valid-candidate-value?
         :my.run/value (run/complete "done")))))

(deftest a-blank-completion-is-an-error-value-not-a-throw
  (doseq [blank ["" "   " "\n\t"]]
    (let [value (run/complete blank)]
      (is (string? (:seon.error/message value))
          "the agent gets something it can read and correct")
      (is (not (seon.schema/valid-candidate-value? :my.run/value value))
          "and the loop cannot mistake it for a disposition"))))

(deftest a-wrong-type-is-the-same-error-value-never-a-throw
  ; agent-facing boundary: (complete 123) must answer, not
  ; ClassCastException out of str/blank?
  (doseq [wrong [123 :kw {:a 1} nil [""]]]
    (is (string? (:seon.error/message (run/complete wrong))))
    (is (string? (:seon.error/message (run/wait wrong))))))

(deftest the-surface-is-exactly-two-functions
  ;; the ruling is a countable fact: no start!, no pause/resume/terminate
  ;; until an agent-lifecycle entity exists
  (is (= #{'wait 'complete}
         (set (keys (ns-publics 'my.run))))))
