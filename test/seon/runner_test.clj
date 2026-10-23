(ns seon.runner-test
  "bin/test's and bin/prove's plan (test-runner) refuses a selection that cannot select: `bin/test
  --reach` once took --reach for a namespace, ran nothing and passed (a check that could not fail)."
  (:require [clojure.test :refer [deftest is]] [test-runner]))

(deftest a-selection-that-cannot-select-is-refused
  (is (re-find #"no flag --reach" (str (#'test-runner/refused-names ["--reach"]))) "a flag it does not know")
  (is (re-find #"no flag --all" (str (#'test-runner/refused-names ["seon.runner-test" "--all"]))) "--all after a name")
  (is (re-find #"no test namespace no.such-test" (str (#'test-runner/refused-names ["no.such-test"]))) "a name that is no test namespace")
  (is (nil? (#'test-runner/refused-names ["seon.runner-test"])) "a test namespace")
  (is (nil? (#'test-runner/refused-names [])) "every test")
  (is (= 1 (#'test-runner/prove! "tests" false "target/tmp/runner-test" ["--reach"])) "the plan's exit status: bin/test ends on TEST FAILED")
  (is (= 1 (#'test-runner/prove! "tests" false "target/tmp/runner-test" ["no.such-test"]))))
