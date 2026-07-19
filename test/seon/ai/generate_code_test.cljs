(ns seon.ai.generate-code-test
  "Focused contracts for generated-code claims and reactive roots."
  (:require
    [cljs.test :refer [async deftest is]]
    [my.plan :as plan]
    [seon.agent.message :as message]
    [seon.ai.generate-code :as generate]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.reactive :as reactive]))

(def ^:private database
  {:db-name "generate-code-test"
   :t 536870912
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000010"})

(deftest claim-commits-cas-message-and-plan-link-together
  (async done
    (let [original-message message/message-transaction-for
          original-allocate db.id/allocate!
          allocation-request (atom nil)]
      (set! message/message-transaction-for
            (fn [_database _request]
              (js/Promise.resolve
               {:seon.agent.message/allocations
                [{::db.id/key :seon.agent.message/id
                  ::db.id/identity-attr :seon.agent.message/id}]
                :seon.agent.message/transaction-builder
                (fn [ids]
                  {::db/tx-data
                   [{:seon.agent.message/id
                     (get ids :seon.agent.message/id)
                     :seon.agent.message/content "repair"}]})})))
      (set! db.id/allocate!
            (fn [request]
              (reset! allocation-request request)
              (let [ids {:seon.agent.message/id "assignment-1"}]
                (js/Promise.resolve
                 {::db.id/ids ids
                  ::db/tx-data ((::db.id/transaction-builder request) ids)}))))
      (-> (generate/claim-namespace-step!
           {::db/db database
            :my.plan/id "namespace-step"
            :seon.agent/id "worker"
            :seon.agent.message/from [:seon.agent/id "caller"]
            :seon.agent.message/content "repair"})
          (.then
           (fn [result]
             (is (= {:seon.ai.generate-code/claimed? true
                     :my.plan/id "namespace-step"
                     :seon.agent.message/id "assignment-1"}
                    result))
             (is (= database (::db/db @allocation-request)))
             (is (=
                  [[:db.fn/cas [:my.plan/id "namespace-step"]
                    :my.plan/claim nil "assignment-1"]
                   {:seon.agent.message/id "assignment-1"
                    :seon.agent.message/content "repair"}
                   {:my.plan/id "namespace-step"
                    :my.plan/message
                    [:seon.agent.message/id "assignment-1"]}]
                  (::db/tx-data
                   ((::db.id/transaction-builder @allocation-request)
                    {:seon.agent.message/id "assignment-1"}))))))
          (.finally
           (fn []
             (set! message/message-transaction-for original-message)
             (set! db.id/allocate! original-allocate)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest observer-computes-the-complete-stable-root-state
  (async done
    (let [original-observe reactive/observe!
          original-state plan/generated-root-state
          observed (atom nil)
          state {:my.plan/id "root"
                 :my.plan/status :open
                 :my.plan/progress
                 {:my.plan/done 0 :my.plan/total 1 :my.plan/done? false}
                 :my.plan/blocked? false
                 :my.plan.internal/namespace-steps []
                 :my.plan.internal/ready-steps []}]
      (set! plan/generated-root-state
            (fn [_request] (js/Promise.resolve state)))
      (set! reactive/observe!
            (fn [request]
              (reset! observed request)
              (-> ((::reactive/compute request) database)
                  (.then (fn [computed]
                           ((::reactive/notify request) (::db/value computed))
                           (::reactive/consumer-key request))))))
      (let [delivered (atom [])]
        (-> (generate/observe-root!
             {::db/db database
              :my.plan/id "root"
              :seon.ai.generate-code/notify #(swap! delivered conj %)})
            (.then
             (fn [consumer-key]
               (is (= "root" consumer-key))
               (is (= [:seon.ai.generate-code/root "root"]
                      (::reactive/key @observed)))
               (is (= [state] @delivered))))
            (.finally
             (fn []
               (set! reactive/observe! original-observe)
               (set! plan/generated-root-state original-state)))
            (.then (fn [_] (done)))
            (.catch (fn [error] (is false (str error)) (done))))))))
