(ns seon.eval.promise-ergonomics-test
  "Value-local defer and deadline behavior before database recording."
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.config :as config]
   [seon.eval :as eval]
   [seon.repl :as repl]))

(def configuration (config/resolve-config-singleton {}))

(def ^:private maybe-await-value (deref #'eval/maybe-await-value))
(def ^:private valid-ending-ns? (deref #'eval/valid-ending-ns?))
(def ^:private namespace-declaration?
  (deref #'eval/namespace-declaration?))
(def ^:private bare-namespace-declaration
  (deref #'eval/bare-namespace-declaration))
(def ^:private effective-require-edges
  (deref #'eval/effective-require-edges))
(def ^:private program-entry-skipped?
  (deref #'eval/program-entry-skipped?))

(deftest only-real-analyzer-namespaces-can-advance-the-eval-fold
  (let [compile-state
        (atom {:cljs.analyzer/namespaces
               {'my.agent.promise {:name 'my.agent.promise}}})]
    (is (valid-ending-ns? compile-state 'my.agent.promise))
    (is (not (valid-ending-ns? compile-state (symbol ""))))
    (is (not (valid-ending-ns? compile-state (symbol ":"))))
    (is (not (valid-ending-ns? compile-state 'invented.namespace)))))

(deftest only-an-ns-declaration-can-move-the-per-form-fold
  (is (namespace-declaration? "(ns my.agent.next)"))
  (is (not (namespace-declaration? "(js/Promise.resolve 323)")))
  (is (not (namespace-declaration? "(message/user \"done\")"))))

(deftest bare-namespace-reentry-retains-existing-require-edges
  (let [prior #{{:seon.ns.require/target 'my.orders.model
                 :seon.ns.require/alias 'model}}
        standard #{{:seon.ns.require/target 'seon.db
                    :seon.ns.require/alias 'db}}]
    (is (= 'my.orders
           (bare-namespace-declaration "(ns my.orders)")))
    (is (nil? (bare-namespace-declaration
               "(ns my.orders (:require [my.other :as other]))"))
        "an explicit require declaration remains authoritative")
    (is (= (into prior standard)
           (effective-require-edges
            'my.orders prior 'my.orders standard))
        "bare re-entry preserves prior aliases plus augmented standard requires")
    (is (= standard
           (effective-require-edges
            nil prior 'my.orders standard))
        "an explicit declaration replaces the prior require edges")))

(deftest ordered-program-skips-only-unsafe-namespace-work
  (let [entry {:seon.repl/namespace 'my.orders.service
               :seon.repl/require-edges #{'my.orders.model}}]
    (is (program-entry-skipped? #{'my.orders.model} #{} entry)
        "a failed generated requirement prevents its dependent")
    (is (program-entry-skipped? #{} #{'my.orders.service} entry)
        "a failed declaration prevents forms from leaking into another ns")
    (is (not (program-entry-skipped?
               #{} #{'my.orders.service}
               (assoc entry :seon.repl/phase :namespace)))
        "a later declaration can re-establish the same namespace safely")
    (is (not (program-entry-skipped? #{'my.unrelated} #{} entry))
        "an independent namespace remains executable")
    (is (not (program-entry-skipped? #{'my.orders.service} #{} entry))
        "an ordinary form failure does not discard later forms in that ns")))

(deftest defer-wraps-promises-and-passes-ordinary-values
  (is (instance? eval/Deferred (eval/defer (js/Promise.resolve 1))))
  (is (instance? eval/Deferred
                 (eval/defer (eval/budget 25 (js/Promise.resolve 1)))))
  (is (= 42 (eval/defer 42)))
  (is (= [:plain :data] (eval/defer [:plain :data]))))

(deftest defer-wins-in-either-budget-composition-order
  (async done
    (let [budget-outside-p (js/Promise. (fn [_ _]))
          defer-outside-p (js/Promise. (fn [_ _]))
          values [(eval/budget 25 (eval/defer budget-outside-p))
                  (eval/defer (eval/budget 25 defer-outside-p))]]
      (-> (js/Promise.all
           (clj->js (mapv maybe-await-value values)))
          (.then
           (fn [results]
             (let [[budget-outside defer-outside] (array-seq results)]
               (is (false? (::eval/ok? budget-outside)))
               (is (identical? budget-outside-p
                               (::eval/pending-promise budget-outside)))
               (is (false? (::eval/ok? defer-outside)))
               (is (identical? defer-outside-p
                               (::eval/pending-promise defer-outside))))))
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

(defn- delayed-value [ms value]
  (js/Promise.
   (fn [resolve _]
     (js/setTimeout (fn [] (resolve value)) ms))))

(defn- interleaved-budget-results [order]
  (let [short-p (delayed-value 80 :short-finished)
        values {::short (eval/budget 10 short-p)
                ::long (eval/budget 250 (delayed-value 80 :long-finished))}
        promises (mapv #(maybe-await-value (get values %)) order)]
    (-> (js/Promise.all (clj->js promises))
        (.then (fn [results]
                 {::short-p short-p
                  ::results (zipmap order (array-seq results))})))))

(deftest deadlines-belong-to-values-not-consumption-order
  (async done
    (letfn [(assert-order! [order]
              (-> (interleaved-budget-results order)
                  (.then
                   (fn [{::keys [short-p results]}]
                     (is (false? (::eval/ok? (::short results))))
                     (is (identical? short-p
                                     (::eval/pending-promise
                                      (::short results))))
                     (is (= {::eval/ok? true
                             ::eval/value :long-finished}
                            (::long results)))))))]
      (-> (assert-order! [::short ::long])
          (.then (fn [_] (assert-order! [::long ::short])))
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

(deftest promise-returning-form-preserves-its-namespace
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
         (fn [compile-state]
           (-> (eval/eval compile-state "(ns scratch.promise-namespace)"
                          {:seon.config/configuration configuration
                           ::eval/starting-ns 'cljs.user
                           ::eval/analyze-deps? false})
               (.then
                (fn [_]
                  (eval/eval compile-state "(js/Promise.resolve 323)"
                             {:seon.config/configuration configuration
                              ::eval/starting-ns 'scratch.promise-namespace
                              ::eval/analyze-deps? false}))))))
        (.then
         (fn [result]
           (is (::eval/ok? result) (pr-str result))
           (is (= 'scratch.promise-namespace (::eval/ending-ns result)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
