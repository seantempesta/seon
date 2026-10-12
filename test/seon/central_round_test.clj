(ns seon.central-round-test
  (:require [clojure.test :refer [deftest is]]
            [seon.central-round :as round]
            [seon.db :as db]
            [seon.error.refusal :as refusal]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(deftest an-unchanged-round-reuses-its-results
  (let [form (round/batch-form
              ['seon.id-test/a-host-object-has-no-identity]
              '(assoc (seon.test-support/execution-handle nil) :seon.agent/id "root"))
        first-run (eval form)
        measured (profile/measure #(eval form))
        again (:seon.profile/value measured)]
    (is (= {:seon.test/executed-count 1 :seon.test/passed? true}
           (select-keys first-run [:seon.test/executed-count :seon.test/passed?
                                  :seon.error/type :seon.error/message])))
    (is (= 0 (:seon.test/executed-count again)))
    (is (= 1 (:seon.test/reused-count again)))
    (is (true? (:seon.test/passed? again)))
    (is (< (:seon.profile/ms measured) 1000))))

(deftest an-unknown-batch-is-recorded-once-and-never-resubmitted
  (let [unknown (refusal/error :seon.operator/operation-error
                               {:seon.error/operation 'seon.central-round/-main
                                :seon.error/message "Unknown."
                                :seon.operator/disposition :client-failed})]
    (doseq [answer [{:seon.test/results [] :seon.test/unfinished [{:seon.test/sym 'a/t}]}
                    {:seon.test/results [] :seon.test/pending ['a/t]}
                    unknown
                    {:seon.test/results [] :seon.test/recording-refusal unknown}]]
      (let [submitted (java.util.ArrayList.) recorded (java.util.ArrayList.)]
        (round/run-round! (fn [part] (.add submitted part) answer)
                          #(.add recorded %) [['a/t] ['a/u]])
        (is (= [['a/t]] (vec submitted)))
        (is (= [{:seon.central-round/symbols ['a/t] :seon.central-round/answer answer}]
               (vec recorded)))))))

(deftest terminal-batches-preserve-evidence-and-advance
  (let [submitted (java.util.ArrayList.) recorded (java.util.ArrayList.)
        answer {:seon.test/results [{:seon.test/sym 'a/t :seon.test.run/terminated? true}]}]
    (round/run-round! (fn [part] (.add submitted part) answer) #(.add recorded %)
                      [['a/t] ['a/u]])
    (is (= [['a/t] ['a/u]] (vec submitted)))
    (is (= [answer answer] (mapv :seon.central-round/answer recorded)))))

(deftest fixture-selection-follows-stored-declarations
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           fixtures (set (db/q '[:find [?s ...] :where [?e :seon.test/sym ?s]
                                 [?e :seon.test/fixture]] database))
           selected (set (db/q round/test-symbols-query database))]
       (is (seq fixtures))
       (is (seq selected))
       (is (not-any? fixtures selected))))))
