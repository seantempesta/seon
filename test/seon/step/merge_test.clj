(ns seon.step.merge-test
  "The merge step's gate (`docs/seon/schedule/cards/MERGE-STEP.md`)."
  (:require [clojure.test :refer [deftest is]]
            [seon.step.merge :as sut]
            [seon.test]
            [seon.test-support :as test-support]))

(def ^:private source
  (str "(ns tq.mrg (:require [clojure.test :refer [deftest is]]))\n"
       "(deftest green (is (= 1 1)))\n"
       "(deftest red (is (= 1 2)))\n"
       "(deftest never-run (is (= 1 1)))\n"))

(def ^:private rows
  "Row name, the refused tests the merge step carries, and the exact refusals: each check with what it names."
  [["nothing refused" nil []]
   ["a refused test now green" '#{tq.mrg/green} []]
   ["a refused test still red" '#{tq.mrg/red} [[:red '[tq.mrg/red]]]]
   ["a refused test never run here" '#{tq.mrg/never-run} [[:red '[tq.mrg/never-run]]]]
   ["every red test is named" '#{tq.mrg/green tq.mrg/red tq.mrg/never-run} [[:red '[tq.mrg/never-run tq.mrg/red]]]]])

(deftest gate
  (test-support/with-database
   (fn [connection]
     (test-support/declare-source! connection 'tq.mrg source)
     (seon.test/run {:seon.test/execution (test-support/execution-handle connection)
                     :seon.test/recording-connection connection
                     :seon.test/policy :named :seon.test/rerun? true
                     :seon.test/identities '#{tq.mrg/green tq.mrg/red}})
     (let [numbered (map-indexed (fn [i row] (into [(inc i)] row)) rows)
           input (partial test-support/task-input connection)
           _ (input (into [] (map (fn [[i _ refused]]
                                    (cond-> {:db/id (- i) :seon.task/ns 'tq.mrg :seon.task/name (str "m" i)}
                                      (seq refused) (assoc :seon.task/refused-tests refused))))
                          numbered)
                    'tq.mrg/m1)]
       (doseq [[i row-name _ expected] numbered
               :let [{task :seon.test/task database :seon.test/branch} (input [] (symbol "tq.mrg" (str "m" i)))]]
         (is (= expected (mapv (juxt :seon.step.merge/check :seon.error/offending) (sut/gate database task))) row-name)))))
  ;; the merge step the runner supplies, when it supplies one: its merge branch passes the gate
  (when-let [{task :seon.test/task branch :seon.test/branch} (seon.test/task)]
    (is (= [] (mapv (juxt :seon.step.merge/check :seon.error/offending) (sut/gate branch task)))
        "the merge step the runner supplies passes its gate")))
