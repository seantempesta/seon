(ns seon.runner-test
  "bin/test's and bin/prove's plan (test-runner) refuses a selection that cannot select: `bin/test
  --reach` once took --reach for a namespace, ran nothing and passed (a check that could not fail)."
  (:require [clojure.test :refer [deftest is]] [test-runner] [clojure.edn] [seon.config] [seon.lib] [seon.fx]))

(deftest a-selection-that-cannot-select-is-refused
  (is (re-find #"no flag --reach" (str (#'test-runner/refused-names ["--reach"]))) "a flag it does not know")
  (is (re-find #"no flag --all" (str (#'test-runner/refused-names ["seon.runner-test" "--all"]))) "--all after a name")
  (is (re-find #"no test namespace no.such-test" (str (#'test-runner/refused-names ["no.such-test"]))) "a name that is no test namespace")
  (is (nil? (#'test-runner/refused-names ["seon.runner-test"])) "a test namespace")
  (is (nil? (#'test-runner/refused-names ["seon.runner-test/a-selection-that-cannot-select-is-refused"])) "NS/TEST: one deftest")
  (is (re-find #"no test namespace no.such-test/x" (str (#'test-runner/refused-names ["no.such-test/x"]))) "NS/TEST of no namespace")
  (is (nil? (#'test-runner/refused-names [])) "every test")
  (is (= 1 (#'test-runner/prove! "tests" false "target/tmp/runner-test" ["--reach"])) "the plan's exit status: bin/test ends on TEST FAILED")
  (is (= 1 (#'test-runner/prove! "tests" false "target/tmp/runner-test" ["no.such-test"]))))

;; docs/clocks.md#time-decides-the-owners-exceptions: a test bin/stalls ended past its bound is a bug, recorded as an error at
;; its digest with the OVERRAN line (the owner, 2026-11-12: "A test killed for running too long is a bug and it should be recorded
;; as an error and fixed"), and the shard's tests that never ended run again. Red before: overran! stored nothing
(deftest an-overrun-is-recorded-as-an-error-and-the-rest-runs-again
  (let [dir (str (seon.config/checkout) "/target/tmp/runner-test-overran-" (random-uuid))
        file (str dir "/shard-0.edn")
        dg (str (random-uuid))
        red (java.io.File. (str (seon.config/value :seon.config.proofs/results-dir) "/a.over-test/parks/" dg ".red.edn"))]
    (try
      (.mkdirs (java.io.File. dir))
      (spit file (pr-str {:tests '[a.over-test/parks a.over-test/done a.over-test/never] :tree (seon.fx/tree-id (seon.config/checkout))
                          :digests {'a.over-test/parks dg 'a.over-test/done "d2" 'a.over-test/never "d3"} :rerun #{} :mg []}))
      (spit (str dir "/shard-0.log") (str "ended a.over-test/done 12 ms\nOVERRAN a.over-test/parks 500 ms (bound 500 ms)\n"))
      (let [out (with-out-str (is (= 1 (#'test-runner/overran! file)) "the shard counts as red"))
            rest (clojure.edn/read-string (slurp (str dir "/shard-0-rest.edn")))]
        (is (re-find #"(?m)^RED a.over-test/parks " out) out)
        (is (.isFile red) "the overran test is stored red at its digest")
        (is (re-find #"OVERRAN a.over-test/parks 500 ms" (slurp red)) "its failure is the OVERRAN line, an error")
        (is (= '[a.over-test/never] (:tests rest)) "the test that never ended runs again; the overran one is not among them")
        (is (= {'a.over-test/never "d3"} (:digests rest))))
      (finally (seon.lib/remove-tree! dir) (.delete red)))))
