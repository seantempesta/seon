(ns seon.await-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [seon.await :as await]))

(defn- observation
  [member]
  {:seon.error/diagnostic-layer :runtime
   :seon.error/diagnostic-operation ::test-await
   :seon.error/diagnostic-member member
   :seon.error/diagnostic-expected ::published
   :seon.error/diagnostic-offending ::absent
   :seon.error/diagnostic-evidence {:test/member member}})

(defn- bound
  [backstop-ms]
  {:seon.await/config-attribute :seon.config.eval/time-limit-ms
   :seon.await/config-value backstop-ms})

(deftest port-operations-share-one-declared-deadline
  (let [request-channel (async/chan)
        reply (async/promise-chan)
        receiver
        (future
          (let [request (async/<!! request-channel)]
            (async/>!! (:reply request) ::completed)
            ::completed))
        result
        (await/await!
         {:seon.await/bound (bound 1000)
          :seon.await/diagnostic (observation ::reply)
          :seon.await/port-operations
          [[request-channel {:reply reply}] reply]})]
    (is (= ::completed result))
    (is (= ::completed @receiver))))

(deftest filtered-port-await-does-not-reset-its-bound
  (let [events (async/chan 2)]
    (async/>!! events {:other true})
    (let [result
          (await/await!
           {:seon.await/bound (bound 20)
            :seon.await/diagnostic (observation ::matching-package)
            :seon.await/port-operations [events]
            :seon.await/accept? ::wanted})]
      (is (= ::await/backstop-fired (:seon.error/kind result)))
      (is (= ::matching-package
             (get-in result
                     [:seon.error/data :seon.error/diagnostic-member])))
      (is (= :seon.config.eval/time-limit-ms
             (get-in result
                     [:seon.error/data
                      :seon.error/diagnostic-evidence
                      :seon.await/config-attribute]))))))

(deftest future-and-promise-expiry-return-the-same-diagnostic-contract
  (doseq [[label request]
          [[::future
            {:seon.await/future
             (java.util.concurrent.FutureTask. ^java.util.concurrent.Callable
                                               (fn [] ::never-run))}]
           [::promise {:seon.await/blocking-deref (promise)}]]]
    (testing (name label)
      (let [result
            (await/await!
             (merge
              {:seon.await/bound (bound 20)
               :seon.await/diagnostic (observation label)}
              request))]
        (is (= ::await/backstop-fired (:seon.error/kind result)))
        (is (= label
               (get-in result
                       [:seon.error/data
                        :seon.error/diagnostic-member])))
        (is (= ::await/backstop-fired
               (get-in result
                       [:seon.error/data
                        :seon.error/diagnostic-cause])))))))

(deftest a-port-closing-before-publication-is-not-health
  (let [completion (async/promise-chan)
        _ (async/close! completion)
        result
        (await/await!
         {:seon.await/bound (bound 1000)
          :seon.await/diagnostic (observation ::completion)
          :seon.await/port-operations [completion]})]
    (is (= ::await/completion-closed (:seon.error/kind result)))
    (is (= ::completion
           (get-in result
                   [:seon.error/data :seon.error/diagnostic-member])))))
