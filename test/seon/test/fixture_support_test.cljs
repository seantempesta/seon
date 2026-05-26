(ns seon.test.fixture-support-test
  "Regression tests for `seon.test.runner/run-vars`'s fixture support.

   Locks in the bug fix where `run-vars` previously bypassed
   `cljs.test/use-fixtures` entirely — driving each test body directly
   via `goog.getObjectByName` and skipping both `:once` and `:each`
   fixture wrappers. Symptom: 60 of 61 db_test failures collapsed onto
   a missing `:once :before` that registered Malli schemas (see
   docs/prds/agent-runtime/research/testing-infra-realignment-2026-05-26.md).

   Strategy: a single side-effect atom records every lifecycle event
   the runner SHOULD trigger, in order. The test then asserts the
   exact ordered sequence. If anyone removes the fixture-walking code
   from `run-vars`, the sequence diverges and this file lights up red.

   This test does NOT exercise async-fixture bodies (a fixture whose
   `:before` returns a Promise). The runner's `run-fixture-fn!` awaits
   thenables; coverage of that path is deferred."
  (:require
    [cljs.test :as t :refer [deftest is async use-fixtures]]
    [seon.test.runner :as r]
    [seon.test.fixture-support-probes :as probes]))

;; Probes live in a sibling ns so the runner can drive THEM with
;; THEIR `use-fixtures` registrations, without contaminating this ns
;; (running this ns would otherwise trigger our own fixtures recursively).

(deftest fixture-order-once-and-each-and-async
  (async done
    (reset! probes/lifecycle [])
    (-> (r/run! {:seon.test.runner/ns 'seon.test.fixture-support-probes})
        (.then
          (fn [result]
            (let [seq @probes/lifecycle
                  summary (:seon.test.runner/summary result)]
              ;; Headline: every probe ran AND saw the test-only schema
              ;; that :once :before installed. Zero errors proves no
              ;; assertion blew up from missing fixture state.
              (is (= 0 (:error summary))
                  (str "no probe should error; summary=" (pr-str summary)
                       " lifecycle=" (pr-str seq)))
              (is (= 3 (:test summary))
                  (str "expected 3 probe tests, summary=" (pr-str summary)))
              (is (= 3 (:pass summary))
                  (str "every probe should pass when fixtures fire; summary="
                       (pr-str summary) " lifecycle=" (pr-str seq)))
              ;; Exact ordering — this is the regression sentinel.
              ;; Probes are named alphabetically so the runner's
              ;; ordered iteration is deterministic.
              ;; cljs.test groups vars by ns and iterates in source
              ;; order via vars-in-ns — probe ordering matches define
              ;; order: a, b, c (async).
              (is (= [:once-before
                      :each-before :probe-a-body :each-after
                      :each-before :probe-b-body :each-after
                      :each-before :probe-c-async-body :each-after
                      :once-after]
                     seq)
                  (str "fixture lifecycle out of order; got=" (pr-str seq))))
            (done))))))
