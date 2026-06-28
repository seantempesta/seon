(ns seon.test.runner-timeout-probes
  "Synthetic probes for `seon.test.runner-timeout-test` — test bodies that
   NEVER settle, used to prove the per-test wall-clock bound
   (`seon.test.runner/with-test-timeout`) converts a hang into a timeout
   `:error` event instead of parking the runner / agent loop. See
   docs/prds/agent-fsm/research/pod-wedge-root-cause-2026-06-28.md.

   ARMED gate: shadow's node-test runner registers EVERY ns with deftest vars
   in the build and runs them directly in `bin/test-cljs`. A never-settling
   body would hang that direct run, so the bodies only hang when `armed?` is
   true — which the driving test sets right before its BOUNDED run and resets
   after. Unarmed (the direct run) the bodies settle immediately (vacuous
   pass, no hang, no leaked timer)."
  (:require [cljs.test :as t :refer [deftest is async]]))

(def armed?
  "Set true by `seon.test.runner-timeout-test` right before driving these
   probes through the bounded runner, reset after. False during the outer
   direct `bin/test-cljs` run, so the never-settling bodies are no-ops."
  (atom false))

(deftest probe-never-resolving-async
  ;; Armed: return a Promise that never resolves (a `^:async` body awaiting
  ;; forever). The bounded runner MUST time it out, never park.
  (if @armed?
    (js/Promise. (fn [_resolve _reject] nil))
    (is true "unarmed — no-op")))

(deftest probe-async-done-never-called
  (async done
    ;; Armed: never call `done`. The bounded runner MUST time it out.
    (if @armed?
      (do (is true) nil)
      (do (is true "unarmed — no-op") (done)))))

(deftest probe-fast-passing
  (is (= 4 (+ 2 2))))
