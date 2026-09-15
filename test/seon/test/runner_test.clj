(ns seon.test.runner-test
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.instrument :as instrument]
            [seon.test.arm :as arm]
            [seon.test.runner :as runner]
            [seon.test-runner-failure-fixture]
            [seon.test-support :as test-support]))

(deftest default-red-does-not-launch-confirmation
  (let [task {::runner/task-id "default-red"
              ::runner/task-ordinal 0
              ::runner/task-namespace "seon.test-runner-failure-fixture"
              ::runner/task-symbols ["seon.test-runner-failure-fixture/failing-example"]}
        red (assoc (#'runner/run-task! task) ::runner/executed-by "pool-1")
        launches (atom 0)
        outcome (atom nil)]
    (with-out-str
      (with-redefs-fn
        {#'runner/confirmation-symbols (constantly #{})
         #'runner/run-task-pool! (fn [& _] [red])
         #'runner/confirm-parallel-failure! (fn [& _] (swap! launches inc))}
        #(reset! outcome
                 (#'runner/run-parallel-stage!
                  [] nil {:seon.fn.manifest/artifacts []} [] nil [task]))))
    (is (= 0 @launches))
    (is (= [red] (::runner/task-results @outcome)))
    (is (= 1 (get-in @outcome [::runner/task-summary ::runner/fail-count])))
    (is (= "pool-1" (::runner/executed-by red)))
    (is (= 1 (count (:seon.test/failing-assertions
                    (first (::runner/task-results red))))))
    (is (str/includes? (::runner/task-output red) "deliberate broken-test evidence"))
    (is (= [#'seon.test-runner-failure-fixture/failing-example]
           (#'runner/confirmation-vars
            [#'seon.test-runner-failure-fixture/passing-example
             #'seon.test-runner-failure-fixture/failing-example]
            #{"seon.test-runner-failure-fixture/failing-example"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'runner/confirmation-vars [] #{"missing/test"})))))

(deftest initialization-acquires-one-projection
  (test-support/preserving-instrumentation-state
   (fn []
     (doseq [initialize [#'arm/initialize-contracts! #'runner/initialize-contracts!]]
       (let [acquire @#'arm/packaged-test-projection
             acquisitions (atom [])
             initialized
             (with-redefs-fn
               {#'arm/packaged-test-projection
                (fn [role]
                  (let [projection (acquire role)]
                    (swap! acquisitions conj projection)
                    projection))}
               #(initialize "one-projection" ['seon.test.runner-test]))
             program (#'arm/declared-program-namespaces)
             armable (instrument/armable program)
             installed (instrument/instrumented)]
         (is (= 1 (count @acquisitions)) (str initialize))
         (is (identical? (first @acquisitions)
                         (:seon.test.runner/projection initialized)))
         (is (seq armable) "An absent program cannot prove complete arming.")
         (is (empty? (set/difference armable installed))
             "Every armable program Var carries its real contract wrapper."))))))
