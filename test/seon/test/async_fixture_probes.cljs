(ns seon.test.async-fixture-probes
  "Probes for verifier probe 2 + 3: async :once :before and :each :after.
   These are driven by seon.test.async-fixture-test.

   ARMED gate: shadow's node-test runner registers every ns with
   deftest vars in the build, so these probes ALSO run directly in
   `bin/test-cljs`. Plain cljs.test does NOT await Promise-returning
   fixtures, so a direct run would (a) fail both probes and (b) leave
   pending setTimeout callbacks that pollute `lifecycle` DURING the
   later runner-driven assertion (observed live 2026-06-09: stray
   `:each-after-async-resolved` marks prefixing the driven run). When
   unarmed, fixtures and probe bodies are no-ops — no marks, no
   timers, vacuous pass."
  (:require [cljs.test :as t :refer [deftest is use-fixtures]]))

(def armed?
  "Set true by the driving test (seon.test.async-fixture-test) right
   before `r/run!`, reset after. False during the outer direct run."
  (atom false))

(def lifecycle (atom []))

(defn- mark! [k] (swap! lifecycle conj k))

;; :once :before returns a Promise — should be awaited before test body runs
(use-fixtures :once
  {:before (fn []
             (when @armed?
               (js/Promise.
                 (fn [resolve _]
                   (js/setTimeout
                     (fn []
                       (mark! :once-before-async-resolved)
                       (resolve nil))
                     50)))))
   :after (fn []
            (when @armed?
              (mark! :once-after)))})

;; :each :after returns a Promise — should be awaited before next test starts
(use-fixtures :each
  {:before (fn [] (when @armed? (mark! :each-before)))
   :after  (fn []
             (when @armed?
               (js/Promise.
                 (fn [resolve _]
                   (js/setTimeout
                     (fn []
                       (mark! :each-after-async-resolved)
                       (resolve nil))
                     20)))))})

(deftest probe-async-fix-a
  ;; Must see :once-before-async-resolved in lifecycle already
  (if @armed?
    (do (mark! :probe-a-body)
        (is (some #{:once-before-async-resolved} @lifecycle)
            (str "async :once :before must resolve before test body; lifecycle="
                 (pr-str @lifecycle))))
    (is true "unarmed — direct (outer-runner) invocation is a no-op")))

(deftest probe-async-fix-b
  ;; :each-after from probe-a must have resolved before probe-b runs
  (if @armed?
    (do (mark! :probe-b-body)
        (is (= 1 (count (filter #{:each-after-async-resolved} @lifecycle)))
            (str "async :each :after from probe-a must fire before probe-b; lifecycle="
                 (pr-str @lifecycle))))
    (is true "unarmed — direct (outer-runner) invocation is a no-op")))
