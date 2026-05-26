(ns seon.test.fixture-support-probes
  "Synthetic probes driven by `seon.test.fixture-support-test`.

   Three deftest vars (alphabetical so the runner iterates them in a
   known order) plus :once and :each fixtures that all push markers
   onto `lifecycle`. The test in the sibling ns asserts the exact
   order of markers — any regression in the runner's fixture-walking
   code reorders or omits markers and the assertion fails.

   The third probe uses `(async done …)` so the test exercises that
   the :each :after fixture fires AFTER the async body resolves (not
   when the body returns its CPS continuation)."
  (:require
    [cljs.test :as t :refer [deftest is async use-fixtures]]))

(def lifecycle
  "Ordered vector of lifecycle markers, reset by the driving test."
  (atom []))

(defn- mark! [k] (swap! lifecycle conj k))

(use-fixtures :once
  {:before (fn [] (mark! :once-before))
   :after  (fn [] (mark! :once-after))})

(use-fixtures :each
  {:before (fn [] (mark! :each-before))
   :after  (fn [] (mark! :each-after))})

(deftest probe-a (mark! :probe-a-body) (is true))

(deftest probe-b (mark! :probe-b-body) (is true))

(deftest probe-c-async
  (async done
    (js/setTimeout
      (fn []
        (mark! :probe-c-async-body)
        (is true)
        (done))
      10)))
