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
  ;; Intentionally false. Only invoked by `r/run!` driven from a real
  ;; test that EXPECTS to see a fail event in the returned data.
  (is (= :expected :actual-mismatch)))

(deftest probe-async-test
  (async done
    (js/setTimeout
      (fn []
        (reset! async-evidence :body-ran)
        (is true)
        (done))
      25)))
