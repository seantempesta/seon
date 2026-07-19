(ns seon.ai.generate-code-test
  "Focused contracts for generated-code claims and reactive roots."
  (:require
    [cljs.test :refer [async deftest is]]
    [my.plan :as plan]
    [seon.agent :as agent]
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

(deftest competing-claims-commit-one-assignment-without-an-orphan-message
  (async done
    (let [original-message message/message-transaction-for
          original-allocate db.id/allocate!
          original-db db/db
          original-pull db/pull
          allocation-index (atom 0)
          claim (atom nil)
          committed (atom [])]
      (set! message/message-transaction-for
            (fn [_database request]
              (js/Promise.resolve
               {:seon.agent.message/allocations
                [{::db.id/key :seon.agent.message/id
                  ::db.id/identity-attr :seon.agent.message/id}]
                :seon.agent.message/transaction-builder
                (fn [ids]
                  {::db/tx-data
                   [{:seon.agent.message/id
                     (:seon.agent.message/id ids)
                     :seon.agent.message/content
                     (:seon.agent.message/content request)}]})})))
      (set! db.id/allocate!
            (fn [request]
              (let [candidate
                    (str "assignment-" (swap! allocation-index inc))
                    ids {:seon.agent.message/id candidate}]
                (if (compare-and-set! claim nil candidate)
                  (let [transaction
                        (::db/tx-data
                         ((::db.id/transaction-builder request) ids))]
                    (swap! committed conj transaction)
                    (js/Promise.resolve {::db.id/ids ids}))
                  (js/Promise.resolve
                   {:seon.error/message "CAS lost"})))))
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! db/pull
            (fn
              ([_request]
               (js/Promise.resolve {:my.plan/claim @claim}))
              ([_ _]
               (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _]
               (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (-> (js/Promise.all
           (into-array
            [(generate/claim-namespace-step!
              {::db/db database
               :my.plan/id "namespace-step"
               :seon.agent/id "worker"
               :seon.agent.message/from [:seon.agent/id "caller"]
               :seon.agent.message/content "repair"})
             (generate/claim-namespace-step!
              {::db/db database
               :my.plan/id "namespace-step"
               :seon.agent/id "worker"
               :seon.agent.message/from [:seon.agent/id "caller"]
               :seon.agent.message/content "repair"})]))
          (.then
           (fn [results]
             (is (= #{true false}
                    (set (map :seon.ai.generate-code/claimed? results))))
             (is (= 1 (count @committed)))
             (is (= 1
                    (count
                     (filter :seon.agent.message/id (first @committed)))))
             (is (= @claim
                    (:my.plan/claim
                     (first (filter #(false?
                                      (:seon.ai.generate-code/claimed? %))
                                    results)))))))
          (.finally
           (fn []
             (set! message/message-transaction-for original-message)
             (set! db.id/allocate! original-allocate)
             (set! db/db original-db)
             (set! db/pull original-pull)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest dispatch-ensures-resident-before-claiming-each-ready-step
  (async done
    (let [original-ensure agent/ensure-namespace-agent!
          original-db db/db
          original-claim generate/claim-namespace-step!
          ensured (atom [])
          claims (atom [])
          root-state
          {:my.plan/id "root"
           :my.plan/status :open
           :my.plan/progress
           {:my.plan/done 0 :my.plan/total 2 :my.plan/done? false}
           :my.plan/blocked? false
           :my.plan.internal/namespace-steps []
           :my.plan.internal/ready-steps
           [{:my.plan/id "alpha" :seon.ns/name 'my.alpha}
            {:my.plan/id "beta" :seon.ns/name 'my.beta}]}]
      (set! agent/ensure-namespace-agent!
            (fn [request]
              (swap! ensured conj request)
              (js/Promise.resolve
               {:seon.agent/id (str (:seon.agent/namespace request) "-worker")})))
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! generate/claim-namespace-step!
            (fn [request]
              (swap! claims conj request)
              (js/Promise.resolve
               {:seon.ai.generate-code/claimed? true
                :my.plan/id (:my.plan/id request)})))
      (-> (generate/dispatch-root-state!
           {:seon.agent/id "coordinator"
            :seon.ai.generate-code/root-state root-state
            :seon.config/model-variant :execution})
          (.then
           (fn [results]
             (is (= ["alpha" "beta"] (mapv :my.plan/id results)))
             (is (= #{'my.alpha 'my.beta}
                    (set (map :seon.agent/namespace @ensured))))
             (is (every? #(= :execution
                              (:seon.config/model-variant %))
                         @ensured))
             (is (= #{"alpha" "beta"}
                    (set (map :my.plan/id @claims))))
             (is (every? #(= [:seon.agent/id "coordinator"]
                              (:seon.agent.message/from %))
                         @claims))))
          (.finally
           (fn []
             (set! agent/ensure-namespace-agent! original-ensure)
             (set! db/db original-db)
             (set! generate/claim-namespace-step! original-claim)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest root-notify-defers-terminal-release-and-dispatches-ready-work
  (async done
    (let [original-observe generate/observe-root!
          original-dispatch generate/dispatch-root-state!
          original-unobserve generate/unobserve-root!
          events (atom [])
          observer (atom nil)
          open-state
          {:my.plan/id "root"
           :my.plan/progress {:my.plan/done? false}
           :my.plan/blocked? false
           :my.plan.internal/ready-steps []}
          done-state
          (assoc open-state :my.plan/progress {:my.plan/done? true})]
      (set! generate/dispatch-root-state!
            (fn [request]
              (swap! events conj [:dispatch request])
              (js/Promise.resolve [])))
      (set! generate/unobserve-root!
            (fn [request]
              (swap! events conj [:unobserve request])
              (js/Promise.resolve true)))
      (set! generate/observe-root!
            (fn [request]
              (reset! observer request)
              (js/Promise.resolve (:my.plan/id request))))
      (-> (generate/start-root-scheduler!
           {::db/db database
            :my.plan/id "root"
            :seon.agent/id "coordinator"
            :seon.config/model-variant :execution})
          (.then
           (fn [_]
             (is (= "root" (:my.plan/id @observer)))
             ((:seon.ai.generate-code/notify @observer) open-state)))
          (.then
           (fn [_]
             (is (= :dispatch (ffirst @events)))
             (reset! events [])
             (let [released
                   ((:seon.ai.generate-code/notify @observer) done-state)]
               (is (empty? @events)
                   "terminal release is deferred beyond observer delivery")
               released)))
          (.then
           (fn [_]
             (is (= [[:unobserve {:my.plan/id "root"}]] @events))))
          (.finally
           (fn []
             (set! generate/observe-root! original-observe)
             (set! generate/dispatch-root-state! original-dispatch)
             (set! generate/unobserve-root! original-unobserve)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))
