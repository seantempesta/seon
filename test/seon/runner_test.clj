(ns seon.runner-test
  "bin/test's and bin/prove's plan (test-runner) refuses a selection that cannot select: `bin/test
  --reach` once took --reach for a namespace, ran nothing and passed (a check that could not fail)."
  (:require [clojure.test :refer [deftest is]] [test-runner] [clojure.edn] [seon.config] [seon.lib] [seon.fx] [seon.edn] [seon.program] [seon.system] [seon.proofs]))

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

(deftest a-step-on-a-binary-current-for-the-plan-is-stored-and-reused
  ;; docs/source.md#the-binary-records-its-program: whether the steps ran on this plan's binary is seon.system/binary-current's
  ;; answer, the rule the build asked, never a compare of tree ids. A landing that built nothing (a merge whose change reaches
  ;; nothing the binary bakes) ran its 37 steps green and stored none: target/seon.tree named the older tree (tests' land,
  ;; 2026-11-12 12:44), so every next prove ran 35 of them again. Red before: the green step not stored
  (let [built (get-in (seon.program/binary-program) [:inputs :tree])
        pn (str "probe-" (random-uuid))
        out (str (seon.config/checkout) "/target/tmp/store-steps-" pn)
        judged (fn [tree] (#'seon.system/judged-path built tree))
        stored? (fn [dg] (contains? (test-runner/read-results {"config" dg}) dg))
        run (fn [tree dg answer]
              (seon.fx/write-file {:req {:path (judged tree) :text (seon.edn/encode {:answer answer})}})
              (seon.fx/write-file {:req {:path (str out "/steps.edn") :text (pr-str {:tree tree :digests {"config" dg}})}})
              (seon.fx/write-file {:req {:path (str out "/green") :text "check-config\n"}})
              (#'test-runner/store-steps! out))
        files (atom [])]
    (is (some? built) "the checkout's binary recorded its tree")
    (try
      (let [tree (str pn "-current") dg (str pn "-a")]
        (swap! files conj (judged tree) (str (seon.config/value :seon.config.proofs/results-dir) "/steps/config/" dg ".pass.edn"))
        (is (= 0 (run tree dg nil)))
        (is (stored? dg) "the binary is current for the plan's tree (another tree, nothing it bakes differs): its green step is stored")
        (is (= [[] ["config"]] ((juxt :run (comp vec keys :reused)) (seon.proofs/plan nil {"config" dg} (test-runner/read-results {"config" dg}) #{})))
            "and the next plan reuses it"))
      (let [tree (str pn "-stale") dg (str pn "-b")]
        (swap! files conj (judged tree))
        (run tree dg {:binary built :tree tree :reaches ['seon.main/-main]})
        (is (not (stored? dg)) "a binary not current for the plan's tree: the step that ran on it is not stored"))
      (finally (seon.lib/remove-tree! out) (doseq [f @files] (.delete (java.io.File. ^String f)))))))
