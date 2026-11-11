(ns seon.runner-test
  "bin/test's and bin/prove's plan (test-runner) refuses a selection that cannot select: `bin/test
  --reach` once took --reach for a namespace, ran nothing and passed (a check that could not fail)."
  (:require [clojure.test :refer [deftest is]] [test-runner] [clojure.edn] [seon.config] [seon.lib]))

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

;; docs/clocks.md#time-decides-the-owners-exceptions: a test bin/stalls ended past its bound is a signal about the run, never a
;; verdict about the test (the owner, 2026-11-11: "The step bounds are signals not hard constraints"): bin/test stores nothing at
;; its digest, prints it OVERRAN-NO-VERDICT, and runs the shard's tests that never ended again. A red stored from an overrun at
;; load 46 made batch 89's tip flaky (2026-11-12). Not seen red first: the fix landed in batch 89 before this test
(deftest an-overrun-stores-no-verdict-and-runs-the-rest-again
  (let [dir (str (seon.config/checkout) "/target/tmp/runner-test-overran-" (random-uuid))
        file (str dir "/shard-0.edn")
        dg (str (random-uuid))
        results (seon.config/value :seon.config.proofs/results-dir)
        verdicts #(filter (fn [^java.io.File f] (.contains (.getName f) dg)) (file-seq (java.io.File. ^String results)))]
    (try
      (.mkdirs (java.io.File. dir))
      (spit file (pr-str {:tests '[a.over-test/parks a.over-test/done a.over-test/never] :digests {'a.over-test/parks dg 'a.over-test/done "d2" 'a.over-test/never "d3"}
                          :rerun #{} :mg []}))
      (spit (str dir "/shard-0.log") (str "ended a.over-test/done 12 ms\nOVERRAN a.over-test/parks 500 ms (bound 500 ms)\n"))
      (let [out (with-out-str (is (= 1 (#'test-runner/overran! file)) "the shard counts as not green"))
            rest (clojure.edn/read-string (slurp (str dir "/shard-0-rest.edn")))]
        (is (re-find #"(?m)^OVERRAN-NO-VERDICT a.over-test/parks " out) out)
        (is (empty? (verdicts)) "nothing is stored at the overran test's digest, green or red")
        (is (= '[a.over-test/never] (:tests rest)) "the test that never ended runs again; the overran one is not among them")
        (is (= {'a.over-test/never "d3"} (:digests rest))))
      (finally (seon.lib/remove-tree! dir)))))
