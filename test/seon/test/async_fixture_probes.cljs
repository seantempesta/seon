(ns seon.test.async-fixture-probes
  "Probes for verifier probe 2 + 3: async :once :before and :each :after.
   These are driven by seon.test.async-fixture-test."
  (:require [cljs.test :as t :refer [deftest is use-fixtures]]))

(def lifecycle (atom []))

(defn- mark! [k] (swap! lifecycle conj k))

;; :once :before returns a Promise — should be awaited before test body runs
(use-fixtures :once
  {:before (fn []
             (js/Promise.
               (fn [resolve _]
                 (js/setTimeout
                   (fn []
                     (mark! :once-before-async-resolved)
                     (resolve nil))
                   50))))
   :after (fn []
            (mark! :once-after))})

;; :each :after returns a Promise — should be awaited before next test starts
(use-fixtures :each
  {:before (fn [] (mark! :each-before))
   :after  (fn []
             (js/Promise.
               (fn [resolve _]
                 (js/setTimeout
                   (fn []
                     (mark! :each-after-async-resolved)
                     (resolve nil))
                   20))))})

(deftest probe-async-fix-a
  ;; Must see :once-before-async-resolved in lifecycle already
  (mark! :probe-a-body)
  (is (some #{:once-before-async-resolved} @lifecycle)
      (str "async :once :before must resolve before test body; lifecycle="
           (pr-str @lifecycle))))

(deftest probe-async-fix-b
  ;; :each-after from probe-a must have resolved before probe-b runs
  (mark! :probe-b-body)
  (is (= 1 (count (filter #{:each-after-async-resolved} @lifecycle)))
      (str "async :each :after from probe-a must fire before probe-b; lifecycle="
           (pr-str @lifecycle))))
