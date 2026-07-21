(ns seon.eval.result-var-test
  "Process-local result values independent of database recording."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.config :as config]
   [seon.eval :as eval]
   [seon.render.value :as render.value]
   [seon.repl :as repl]))

(def ^:private configuration (config/resolve-config-singleton {}))
(def ^:private result-cap (deref #'eval/result-vars-cap))
(def ^:private replace-live-result! (deref #'eval/replace-live-result!))
(def ^:private unbind-result-var! (deref #'eval/unbind-result-var!))
(def ^:private bind-admitted-result-var!
  (deref #'eval/bind-admitted-result-var!))

(defn- sampling-database [basis commit]
  {:db-name "sampling-test"
   :store-id [#uuid "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" :db]
   :t basis
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id commit})

(defn- evaluate-result [compile-state namespace id]
  (eval/eval compile-state (str "result/" id)
             {:seon.config/configuration configuration
              :seon.eval/starting-ns namespace
              :seon.eval/analyze-deps? false}))

(deftest result-var-ref-recognizes-only-one-bare-result-symbol
  (is (true? (eval/result-var-ref? "result/auC-2606181147")))
  (is (true? (eval/result-var-ref? "  result/foe-2606181326  ")))
  (is (false? (eval/result-var-ref? "(result :auC-2606181147)")))
  (is (false? (eval/result-var-ref? "(+ 1 2)")))
  (is (false? (eval/result-var-ref? "my.kb/something")))
  (is (false? (eval/result-var-ref? "result/a result/b"))))

(deftest retained-slot-keeps-policy-and-producing-database-atomically
  (let [compile-state (atom {})
        id-a "basis-a"
        id-b "basis-b"
        limits-a (config/effective-value-drill-limits
                  {:seon.config/configuration configuration})
        limits-b (assoc limits-a :seon.render.value/page-size 2)
        database-a (sampling-database
                    41 #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        database-b (sampling-database
                    42 #uuid "cccccccc-cccc-4ccc-8ccc-cccccccccccc")]
    (try
      (bind-admitted-result-var! compile-state id-a :a limits-a database-a)
      (bind-admitted-result-var! compile-state id-b :b limits-b database-b)
      (is (= {:seon.eval/found? true
              :seon.eval/metadata-valid? true
              :seon.eval/sampling-limits limits-a
              :seon.eval/sampling-database database-a}
             (eval/result-sampling-entry compile-state id-a)))
      (is (= {:seon.eval/found? true
              :seon.eval/metadata-valid? true
              :seon.eval/sampling-limits limits-b
              :seon.eval/sampling-database database-b}
             (eval/result-sampling-entry compile-state id-b)))
      (is (render.value/effective-limits-within? limits-b limits-a))
      (is (not (render.value/effective-limits-within? limits-a limits-b)))
      (unbind-result-var! compile-state id-a)
      (is (= {:seon.eval/found? false}
             (eval/result-sampling-entry compile-state id-a)))
      (finally
        (unbind-result-var! compile-state id-a)
        (unbind-result-var! compile-state id-b)))))

(deftest ordinary-form-evaluation-returns-its-value
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
         (fn [compile-state]
           (eval/eval compile-state "(+ 20 22)"
                      {:seon.config/configuration configuration
                       :seon.eval/starting-ns 'cljs.user
                       :seon.eval/analyze-deps? false})))
        (.then
         (fn [result]
           (is (:seon.eval/ok? result))
           (is (= 42 (:seon.eval/value result)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest unknown-result-id-is-a-graceful-value
  (async done
    (-> (repl/ensure-bootstrap!)
        (.then
         (fn [compile-state]
           (-> (eval/eval compile-state "(ns probe.resultmiss)"
                          {:seon.config/configuration configuration
                           :seon.eval/starting-ns 'cljs.user
                           :seon.eval/analyze-deps? true})
               (.then
                (fn [_]
                  (evaluate-result compile-state 'probe.resultmiss
                                   "zzz-9999999999")))
               (.then
                (fn [result]
                  (is (:seon.eval/ok? result))
                  (is (string? (:seon.eval/value result))))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest result-runtime-prunes-oldest-and-keeps-recent
  (async done
    (let [prior-results (js/Reflect.get js/globalThis (str eval/result-ns-sym))
          cleanup (atom nil)]
      (-> (repl/ensure-bootstrap!)
          (.then
           (fn [compile-state]
             (-> (eval/eval compile-state "(ns probe.resultcap)"
                            {:seon.config/configuration configuration
                             :seon.eval/starting-ns 'cljs.user
                             :seon.eval/analyze-deps? false})
                 (.then
                  (fn [_]
                    (let [n (+ result-cap 5)
                          ids (mapv #(str "cz" (mod % 26)
                                          "-99999999" (+ 10 %))
                                    (range n))]
                      (js/Reflect.set js/globalThis (str eval/result-ns-sym)
                                      (js/Object.create nil))
                      (reset! cleanup [compile-state ids])
                      (doseq [[index id] (map-indexed vector ids)]
                        (eval/bind-result-var! compile-state id (* 100 index)))
                      (is (= result-cap
                             (count
                              (js/Object.keys
                               (js/Reflect.get js/globalThis
                                               (str eval/result-ns-sym))))))
                      (is (false? (replace-live-result! (first ids) -1)))
                      (is (true? (replace-live-result!
                                  (last ids) (* 100 (dec n)))))
                      (-> (js/Promise.all
                           #js [(evaluate-result compile-state
                                                 'probe.resultcap (first ids))
                                (evaluate-result compile-state
                                                 'probe.resultcap (nth ids 5))
                                (evaluate-result compile-state
                                                 'probe.resultcap (last ids))])
                          (.then
                           (fn [results]
                             (-> (eval/lookup-result (first ids))
                                 (.then
                                  (fn [evicted]
                                    (testing "evicted values become readable misses"
                                      (is (string? (:seon.eval/value
                                                    (aget results 0))))
                                      (is (false? (:seon.eval/ok? evicted))))
                                    (testing "surviving values resolve"
                                      (is (= 500 (:seon.eval/value
                                                  (aget results 1))))
                                      (is (= (* 100 (dec n))
                                             (:seon.eval/value
                                              (aget results 2)))))))))))))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (when-let [[compile-state ids] @cleanup]
               (doseq [id ids]
                 (unbind-result-var! compile-state id)))
             (if prior-results
               (js/Reflect.set js/globalThis (str eval/result-ns-sym)
                               prior-results)
               (js/Reflect.deleteProperty js/globalThis
                                          (str eval/result-ns-sym)))
             (done)))))))

(deftest pending-settlement-applies-retained-value-admission
  (async done
    (let [eval-id "settled-oversize-9999999999"
          compile-state (atom {:cljs.analyzer/namespaces {}})
          prior-results (js/Reflect.get js/globalThis (str eval/result-ns-sym))]
      (js/Reflect.set js/globalThis (str eval/result-ns-sym)
                      (js/Object.create nil))
      (eval/bind-result-var! compile-state eval-id :pending)
      (is (true? (replace-live-result!
                  eval-id (apply str (repeat (* 1024 1024) "z")))))
      (-> (eval/lookup-result eval-id)
          (.then
           (fn [retained]
             (is (= :seon.eval/weight-cap-exceeded
                    (:seon.eval/retained-reason retained)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (unbind-result-var! compile-state eval-id)
             (if prior-results
               (js/Reflect.set js/globalThis (str eval/result-ns-sym)
                               prior-results)
               (js/Reflect.deleteProperty js/globalThis
                                          (str eval/result-ns-sym)))
             (done)))))))
