(ns seon.test.runner-probes
  "Synthetic `deftest` vars that exist solely so the real
   `seon.test.runner-test` self-tests have something to discover and
   drive — without recursing into themselves.

   We keep these in a separate namespace from the real self-tests so
   that `(r/run! {::ns 'seon.test.runner-test})` doesn't infinitely
   recurse (each real test calls `r/run!`; if those re-selected the
   enclosing ns, the run would never terminate).

   This namespace contains ONLY probes — no driver invocations, no
   recording. Loaded into the live pod via `seon.dev.test-preload`."
  (:require
    [cljs.test :as t :refer [deftest is async]]))

(def armed?
  "shadow's node-test runner registers EVERY ns with deftest vars in
   the build (shadow.test.env/get-test-data ignores :ns-regexp for
   required namespaces), so these probes ALSO run directly in
   `bin/test-cljs` — where an intentional failure would pollute the
   real suite totals. The driving test (`seon.test.runner-test`) arms
   the failure before invoking `r/run!` and disarms after; the outer
   direct run sees `false` and the failing probe passes vacuously."
  (atom false))

(def async-evidence
  "Mutated by `probe-async-test` from inside a `js/setTimeout` callback
   so the runner self-test can verify the driver awaited the body. The
   inner `(is true)` assertion's `:pass` event leaks to the outer
   builder (CLJS dynamic bindings don't survive the JS event-loop boundary),
   so we don't rely on the inner pass count — only this atom."
  (atom nil))

(deftest probe-passing-test
  (is (= 4 (+ 2 2))))

(deftest probe-failing-test
  ;; Intentionally false WHEN ARMED. Only meaningful via `r/run!`
  ;; driven from a real test that EXPECTS to see a fail event in the
  ;; returned data; the outer direct run (unarmed) passes vacuously.
  (if @armed?
    (is (= :expected :actual-mismatch))
    (is true "unarmed — direct (outer-runner) invocation is a no-op")))

(deftest probe-async-test
  (async done
    (js/setTimeout
      (fn []
        (reset! async-evidence :body-ran)
        (is true)
        (done))
      25)))

;; ============================================================
;; B9 usage-example probes — a `defn` with a `:test` var-meta thunk (NOT
;; a `deftest`). The compiler emits the thunk to the fn object's
;; `cljs$lang$test` slot (not `cljs$lang$var`), so `resolve-test-fn` must
;; read that slot to drive the EXAMPLE (its `is` assertions) instead of
;; the IMPLEMENTATION fn. The driving self-test (`runner-test`) runs
;; these via `r/run!` and asserts the example's assertion fired — proving
;; the example RUNS (Part 4 of B9), not the impl at the wrong arity.
;; ============================================================

(defn probe-example-add
  "A defn whose `:test` is a PASSING usage example: it asserts on the
   fn's own result. Driven via `r/run!` it must produce a `:pass`."
  {:test (fn [] (is (= 5 (probe-example-add 2 3))))}
  [a b]
  (+ a b))

(defn probe-example-armed
  "A defn whose `:test` example FAILS only when `armed?` — same arming
   trick as `probe-failing-test`, so the outer direct `bin/test-cljs`
   run (unarmed) passes vacuously while the runner-driven invocation
   sees the example's `is` FAIL. Proves a failing example surfaces."
  {:test (fn []
           (if @armed?
             (is (= 999 (probe-example-armed 1 1)))
             (is true "unarmed — direct invocation is a vacuous pass")))}
  [a b]
  (+ a b))
