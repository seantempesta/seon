(ns seon.await-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [seon.await :as await]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test :as test]
            [seon.test-support :as support]))

(defn- observation
  [member]
  {:seon.error/layer :runtime
   :seon.error/operation ::test-await
   :seon.error/expected ::published
   :seon.error/offending ::absent
   :seon.error/data (merge {:test/member member} {:seon.error/member member})})

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
      (is (<= 20 (:seon.await/elapsed-ms result)))
      (is (= 20 (:seon.await/config-value result)))
      (is (= ::matching-package
             (get-in result
                     [:seon.error/data :seon.error/member])))
      (is (= :seon.config.eval/time-limit-ms
             (get-in result
                     [:seon.await/config-attribute]))))))

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
        (is (<= 20 (:seon.await/elapsed-ms result)))
        (is (= 20 (:seon.await/config-value result)))
        (is (= label
               (get-in result
                       [:seon.error/data
                        :seon.error/member])))))))

(deftest a-port-closing-before-publication-is-not-health
  (let [completion (async/promise-chan)
        _ (async/close! completion)
        result
        (await/await!
         {:seon.await/bound (bound 1000)
          :seon.await/diagnostic (observation ::completion)
          :seon.await/port-operations [completion]})]
    (is (= :take (:seon.await/closed-operation result)))
    (is (= 0 (:seon.await/operation-index result)))
    (is (= ::completion
           (get-in result
                   [:seon.error/data :seon.error/member])))))

(deftest check-completion-distinguishes-expiry-from-a-completed-failure
  (let [projection (schema/handed-projection)
           failure (#'test/unknown ::selection "Selection was unavailable.")
           completed (java.util.concurrent.FutureTask.
                      ^java.util.concurrent.Callable (fn [] failure))
           request {:seon.await/bound (bound 20)
                    :seon.await/diagnostic (observation ::check)}
           started (System/nanoTime)
           _ (.run completed)
           returned (await/await! (assoc request :seon.await/future completed))
           timeout (await/await! (assoc request :seon.await/blocking-deref (promise)))
           expiry (#'test/check-completion {:seon.test/progress "selection"} started timeout)]
       (is (identical? failure (#'test/check-completion {} started returned)))
       (is ((schema/projection-validator projection :seon.test/unknown-error) returned))
       (is (not ((schema/projection-validator projection :seon.test/expired) returned)))
       (is ((schema/projection-validator projection :seon.await/timeout-error) timeout))
       (is ((schema/projection-validator projection :seon.test/expired) expiry))
       (is (not ((schema/projection-validator projection :seon.test/unknown-error) expiry)))
       (is (= :seon.config.eval/time-limit-ms (:seon.await/config-attribute expiry)))
       (is (= 20 (:seon.await/config-value expiry)))
       (is (<= (:seon.await/elapsed-ms timeout) (:seon.test/elapsed-ms expiry)))
       (is (= 'seon.test/expired-result (:seon.error/operation expiry)))
       (is (= ::check (get-in expiry [:seon.error/data :seon.error/member])))))
