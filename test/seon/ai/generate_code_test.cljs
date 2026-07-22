(ns seon.ai.generate-code-test
  "Focused contracts for generated-code claims and reactive roots."
  (:require
    [cljs.reader :as reader]
    [cljs.test :refer [async deftest is]]
    [my.plan :as plan]
    [seon.agent :as agent]
    [seon.agent.ctx :as ctx]
    [seon.agent.message :as message]
    [seon.ai.generate-code :as generate]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.embed :as embed]
    [seon.reactive :as reactive]))

(defn- finish-root-promise [request]
  ((deref #'generate/finish-root!) request))

(defn- root-notify-promise
  [root-id coordinator-id model-variant root-state]
  ((deref #'generate/root-notify)
   root-id coordinator-id model-variant root-state))

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
          state-requests (atom [])
          state {:my.plan/id "root"
                 :my.plan/status :open
                 :my.plan/progress
                 {:my.plan/done 0 :my.plan/total 1 :my.plan/done? false}
                 :my.plan/blocked? false
                 :my.plan.generation/namespace-steps []
                 :my.plan.generation/ready-steps []}]
      (set! plan/generated-root-state
            (fn [request]
              (swap! state-requests conj request)
              (js/Promise.resolve state)))
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
               (is (= database (::reactive/db @observed)))
               (is (not (contains? @observed ::db/db)))
               (is (= [{::db/db database :my.plan/id "root"}]
                      @state-requests))
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
           :my.plan.generation/namespace-steps []
           :my.plan.generation/ready-steps
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

(deftest root-notify-dispatches-ready-work
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
           :my.plan.generation/ready-steps []}
          ]
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
             (is (not-any? #(= :unobserve (first %)) @events))))
          (.finally
           (fn []
             (set! generate/observe-root! original-observe)
             (set! generate/dispatch-root-state! original-dispatch)
             (set! generate/unobserve-root! original-unobserve)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest restore-replaces-observers-for-durable-nonterminal-roots
  (async done
    (let [original-candidates plan/generated-root-candidates
          original-state plan/generated-root-state
          original-start generate/start-root-scheduler!
          original-unobserve generate/unobserve-root!
          candidate-request (atom nil)
          started (atom [])
          released (atom [])
          state-for
          {"root-only"
           {:my.plan/id "root-only"
            :my.plan/status :open
            :my.plan/progress
            {:my.plan/done 0 :my.plan/total 1 :my.plan/done? false}
            :my.plan/blocked? false
            :my.plan.generation/namespace-steps []
            :my.plan.generation/ready-steps []}
           "claimed-open"
           {:my.plan/id "claimed-open"
            :my.plan/status :open
            :my.plan/progress
            {:my.plan/done 0 :my.plan/total 1 :my.plan/done? false}
            :my.plan/blocked? false
            :my.plan.generation/namespace-steps
            [{:my.plan/id "step"
              :seon.ns/name 'my.claimed
              :my.plan/status :open
              :my.plan/claim "assignment"}]
            :my.plan.generation/ready-steps []}
           "blocked"
           {:my.plan/id "blocked"
            :my.plan/status :open
            :my.plan/progress
            {:my.plan/done 0 :my.plan/total 1 :my.plan/done? false}
            :my.plan/blocked? true
            :my.plan.generation/namespace-steps
            [{:my.plan/id "blocked-step"
              :seon.ns/name 'my.blocked
              :my.plan/status :blocked}]
            :my.plan.generation/ready-steps []}}]
      (set! plan/generated-root-candidates
            (fn [request]
              (reset! candidate-request request)
              (js/Promise.resolve
               [{:my.plan/id "blocked" :seon.agent/id "coordinator"}
                {:my.plan/id "claimed-open"
                 :seon.agent/id "coordinator"}
                {:my.plan/id "root-only"
                 :seon.agent/id "coordinator"}])))
      (set! plan/generated-root-state
            (fn [{root-id :my.plan/id}]
              (js/Promise.resolve (get state-for root-id))))
      (set! generate/unobserve-root!
            (fn [request]
              (swap! released conj (:my.plan/id request))
              (js/Promise.resolve true)))
      (set! generate/start-root-scheduler!
            (fn [request]
              (swap! started conj request)
              (js/Promise.resolve (:my.plan/id request))))
      (-> (generate/restore-root-schedulers!
           {::db/db database :seon.config/model-variant :execution})
          (.then
           (fn [restored]
             (is (= ["claimed-open" "root-only"] restored))
             (is (= ["blocked" "claimed-open" "root-only"] @released)
                 "hot reload drops every prior root observer before classifying")
             (is (= ["claimed-open" "root-only"]
                    (mapv :my.plan/id @started)))
             (is (= :execution
                    (:seon.config/model-variant (first @started))))
             (is (= database (::db/db @candidate-request)))))
          (.finally
           (fn []
             (set! plan/generated-root-candidates original-candidates)
             (set! plan/generated-root-state original-state)
             (set! generate/start-root-scheduler! original-start)
             (set! generate/unobserve-root! original-unobserve)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest terminal-result-content-is-committed-through-the-plan-owner
  (async done
    (let [original-db db/db
          original-commit plan/commit-generated-terminal!
          request (atom nil)
          root-state
          {:my.plan/id "root"
           :my.plan/status :open
           :my.plan/progress
           {:my.plan/done 2 :my.plan/total 2 :my.plan/done? true}
           :my.plan/blocked? false
           :my.plan.generation/namespace-steps
           [{:my.plan/id "model-step"
             :seon.ns/name 'my.generated.model
             :my.plan/status :done}
            {:my.plan/id "service-step"
             :seon.ns/name 'my.generated.service
             :my.plan/status :done}]
           :my.plan.generation/ready-steps []}]
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! plan/commit-generated-terminal!
            (fn [value]
              (reset! request value)
              (js/Promise.resolve
               {:my.plan/ok? true
                :my.plan/id "root"
                :my.plan/status :done
                :seon.agent.message/id "terminal-message"})))
      (-> (finish-root-promise
           {:my.plan/id "root"
            :seon.ai.generate-code/root-state root-state
            :my.plan/status :done})
          (.then
           (fn [result]
             (is (true? (:my.plan/ok? result)))
             (is (= database (::db/db @request)))
             (is (= :done (:my.plan/status @request)))
             (let [content
                   (reader/read-string
                    (:seon.agent.message/content @request))]
               (is (= "root" (:my.plan/id content)))
               (is (= :done (:my.plan/status content)))
               (is (= ['my.generated.model 'my.generated.service]
                      (mapv :seon.ns/name (:my.plan/steps content)))))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! plan/commit-generated-terminal! original-commit)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest dispatch-member-error-blocks-root-and-addresses-the-caller
  (async done
    (let [original-db db/db
          original-commit plan/commit-generated-terminal!
          original-dispatch generate/dispatch-root-state!
          original-unobserve generate/unobserve-root!
          terminal-request (atom nil)
          released (atom [])
          root-state
          {:my.plan/id "root"
           :my.plan/status :open
           :my.plan/progress
           {:my.plan/done 0 :my.plan/total 1 :my.plan/done? false}
           :my.plan/blocked? false
           :my.plan.generation/namespace-steps []
           :my.plan.generation/ready-steps
           [{:my.plan/id "step" :seon.ns/name 'my.failed}]}]
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! plan/commit-generated-terminal!
            (fn [request]
              (reset! terminal-request request)
              (js/Promise.resolve
               {:my.plan/ok? true
                :my.plan/id "root"
                :my.plan/status :blocked
                :seon.agent.message/id "blocked-message"})))
      (set! generate/dispatch-root-state!
            (fn [_]
              (js/Promise.resolve
               [{:seon.error/message "resident unavailable"
                 :my.plan/id "step"}])))
      (set! generate/unobserve-root!
            (fn [request]
              (swap! released conj (:my.plan/id request))
              (js/Promise.resolve true)))
      (-> (root-notify-promise "root" "coordinator" :execution root-state)
          (.then
           (fn [result]
             (is (true? (:my.plan/ok? result)))
             (is (= :blocked (:my.plan/status @terminal-request)))
             (is (= database (::db/db @terminal-request)))
             (is (= "resident unavailable"
                    (:my.plan/error
                     (reader/read-string
                      (:seon.agent.message/content @terminal-request)))))
             (is (= ["root"] @released))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! plan/commit-generated-terminal! original-commit)
             (set! generate/dispatch-root-state! original-dispatch)
             (set! generate/unobserve-root! original-unobserve)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

;;; ───────────────────────────────────────────────────────────────────────
;;; Embedding-ranked namespace augmentation.
;;; ───────────────────────────────────────────────────────────────────────

(deftest ranked-namespaces-degrade-to-empty-outside-the-embed-gate
  (async done
    (let [original-enabled embed/enabled?
          original-search embed/search-pull
          searched (atom 0)]
      (set! embed/enabled? (fn [] false))
      (set! embed/search-pull
            (fn [_] (swap! searched inc) (js/Promise.resolve {:seon.embed/hits []})))
      (-> (generate/ranked-namespaces! {:my.plan/goal "Add order validation."})
          (.then
           (fn [ranked]
             (is (= [] ranked))
             (is (zero? @searched))))
          (.finally
           (fn []
             (set! embed/enabled? original-enabled)
             (set! embed/search-pull original-search)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest ranked-namespaces-degrade-to-empty-on-a-search-error-envelope
  (async done
    (let [original-enabled embed/enabled?
          original-search embed/search-pull]
      (set! embed/enabled? (fn [] true))
      (set! embed/search-pull
            (fn [_]
              (js/Promise.resolve
               {:seon.embed/hits []
                :seon/error {:seon.error/message "writer offline"}})))
      (-> (generate/ranked-namespaces! {:my.plan/goal "Add order validation."})
          (.then (fn [ranked] (is (= [] ranked))))
          (.finally
           (fn []
             (set! embed/enabled? original-enabled)
             (set! embed/search-pull original-search)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest ranked-namespaces-group-by-best-hit-with-deterministic-tie-break
  (async done
    (let [original-enabled embed/enabled?
          original-search embed/search-pull
          search-request (atom nil)
          hit (fn [eid distance ns-symbol]
                (cond-> {:seon.embed/eid eid
                         :seon.embed/distance distance}
                  ns-symbol
                  (assoc :seon.embed/entity
                         {:seon.fn/ns {:seon.ns/name ns-symbol}})))]
      (set! embed/enabled? (fn [] true))
      (set! embed/search-pull
            (fn [request]
              (reset! search-request request)
              (js/Promise.resolve
               {:seon.embed/hits
                [(hit 5 0.05 'my.order.model-test)   ; tests stay excluded
                 (hit 6 0.05 'my.plan.internal)      ; compact .internal excluded
                 (hit 7 0.05 nil)                    ; unusable row — no namespace
                 (hit 1 0.1 'my.order.model)
                 (hit 4 0.1 'my.aaa)                 ; distance tie → name order
                 (hit 3 0.2 'seon.db)
                 (hit 2 0.3 'my.order.model)]}))) ; worse duplicate ignored
      (-> (generate/ranked-namespaces!
           {:my.plan/goal "Add order validation."
            :my.plan/description "Reject invalid orders."
            :my.plan/expect "Tests prove rejection."})
          (.then
           (fn [ranked]
             (is (= [{:seon.ns/name 'my.aaa :seon.embed/distance 0.1}
                     {:seon.ns/name 'my.order.model :seon.embed/distance 0.1}
                     {:seon.ns/name 'seon.db :seon.embed/distance 0.2}]
                    ranked))
             (is (= (str "Add order validation.\n"
                         "Reject invalid orders.\n"
                         "Tests prove rejection.")
                    (:seon.embed/query @search-request)))))
          (.finally
           (fn []
             (set! embed/enabled? original-enabled)
             (set! embed/search-pull original-search)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest ranked-compact-selection-lets-exact-full-selections-win
  (is (= ['my.ranked.one 'seon.db]
         (generate/ranked-compact-selection
          {:seon.ai.generate-code/ranked
           [{:seon.ns/name 'my.keep.full :seon.embed/distance 0.1}
            {:seon.ns/name 'my.ranked.one :seon.embed/distance 0.2}
            {:seon.ns/name 'seon.db :seon.embed/distance 0.3}]
           :seon.agent.ctx.namespaces/full-source ['my.keep.full]}))))

(deftest reconcile-replaces-exact-compact-and-preserves-other-dials
  (async done
    (let [original-pull db/pull
          original-install ctx/install!
          installed (atom nil)]
      (set! db/pull
            (fn
              ([_request]
               (js/Promise.resolve
                {:seon.agent/ctx
                 [{:db/id 9
                   :seon.agent.ctx/name :namespaces
                   :seon.agent.ctx/priority 20
                   :seon.agent.ctx.namespaces/compact ['my.stale.old]
                   :seon.agent.ctx.namespaces/full-source ['my.keep.full]
                   :seon.agent.ctx.namespaces/with-tests ['my.keep.tests]
                   :seon.agent.ctx.namespaces/current-full? false}]}))
              ([_ _]
               (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! ctx/install!
            (fn [block]
              (reset! installed block)
              (js/Promise.resolve
               {:seon.agent.ctx/ok? true
                :seon.agent.ctx/names [:namespaces]})))
      (-> (generate/reconcile-ranked-namespaces!
           {:seon.agent/id "worker-1"
            :seon.ai.generate-code/ranked
            [{:seon.ns/name 'my.keep.full :seon.embed/distance 0.1}
             {:seon.ns/name 'my.ranked.one :seon.embed/distance 0.2}]})
          (.then
           (fn [result]
             (is (true? result))
             (is (= ['my.ranked.one]
                    (:seon.agent.ctx.namespaces/compact @installed)))
             (is (= ['my.keep.full]
                    (:seon.agent.ctx.namespaces/full-source @installed)))
             (is (= ['my.keep.tests]
                    (:seon.agent.ctx.namespaces/with-tests @installed)))
             (is (false? (:seon.agent.ctx.namespaces/current-full? @installed)))
             (is (= 20 (:seon.agent.ctx/priority @installed)))
             (is (not (contains? @installed :db/id)))))
          (.finally
           (fn []
             (set! db/pull original-pull)
             (set! ctx/install! original-install)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest reconcile-writes-nothing-without-ranked-evidence
  (async done
    (let [original-pull db/pull
          original-install ctx/install!
          touched (atom 0)]
      (set! db/pull (fn [_] (swap! touched inc) (js/Promise.resolve {})))
      (set! ctx/install! (fn [_] (swap! touched inc) (js/Promise.resolve {})))
      (-> (generate/reconcile-ranked-namespaces!
           {:seon.agent/id "worker-1" :seon.ai.generate-code/ranked []})
          (.then
           (fn [result]
             (is (false? result))
             (is (zero? @touched))))
          (.finally
           (fn []
             (set! db/pull original-pull)
             (set! ctx/install! original-install)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

;;; ───────────────────────────────────────────────────────────────────────
;;; start-generation! — the composition behind seon.ai/generate-code!.
;;; ───────────────────────────────────────────────────────────────────────

(deftest start-generation-refuses-blank-goal-and-unknown-keys
  (async done
    (-> (generate/start-generation!
         {:my.plan/goal "   " :seon.agent/id "caller-1"})
        (.then
         (fn [result]
           (is (false? (:my.plan/ok? result)))
           (is (re-find #"blank :my.plan/goal" (:my.plan/error result)))))
        (.then
         (fn [_]
           (generate/start-generation!
            {:my.plan/goals "typo" :seon.agent/id "caller-1"})))
        (.then
         (fn [result]
           (is (false? (:my.plan/ok? result)))
           (is (re-find #"unknown key :my.plan/goals"
                        (:my.plan/error result)))))
        (.then
         (fn [_]
           (generate/start-generation!
            {:my.plan.typo/goal "typo" :seon.agent/id "caller-1"})))
        (.then
         (fn [result]
           (is (false? (:my.plan/ok? result)))
           (is (re-find #"unknown key :my.plan.typo/goal"
                        (:my.plan/error result)))))
        (.then (fn [_] (done)))
        (.catch (fn [error] (is false (str error)) (done))))))

(deftest start-generation-commits-root-message-and-claim-in-one-transaction
  (async done
    (let [original-start agent/start!
          original-enabled embed/enabled?
          original-db db/db
          original-message message/message-transaction-for
          original-allocate db.id/allocate!
          original-scheduler generate/start-root-scheduler!
          start-request (atom nil)
          message-request (atom nil)
          allocation-request (atom nil)
          scheduler-request (atom nil)]
      (set! agent/start!
            (fn [request]
              (reset! start-request request)
              (js/Promise.resolve {:seon.agent/id "planner-1"})))
      (set! embed/enabled? (fn [] false))
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.resolve database))))
      (set! message/message-transaction-for
            (fn [_database request]
              (reset! message-request request)
              (js/Promise.resolve
               {:seon.agent.message/allocations
                [{::db.id/key :seon.agent.message/id
                  ::db.id/identity-attr :seon.agent.message/id}]
                :seon.agent.message/transaction-builder
                (fn [ids]
                  {::db/expected-db database
                   ::db/tx-data
                   [{:seon.agent.message/id
                     (get ids :seon.agent.message/id)
                     :seon.agent.message/content
                     (:seon.agent.message/content request)}]})})))
      (set! db.id/allocate!
            (fn [request]
              (reset! allocation-request request)
              (let [ids {:seon.agent.message/id "assignment-1"
                         :my.plan/id "root-1"}
                    build (get request ::db.id/transaction-builder)]
                (js/Promise.resolve
                 {::db.id/ids ids ::db.id/transaction (build ids)}))))
      (set! generate/start-root-scheduler!
            (fn [request]
              (reset! scheduler-request request)
              (js/Promise.resolve "root-1")))
      (-> (generate/start-generation!
           {:my.plan/goal "Add order validation."
            :my.plan/description "Reject invalid orders."
            :my.plan/expect "Tests prove rejection."
            :seon.agent/id "caller-1"})
          (.then
           (fn [result]
             (is (= {:my.plan/ok? true
                     :my.plan/id "root-1"
                     :seon.agent/id "planner-1"}
                    result))
             (is (= :planning (:seon.config/model-variant @start-request)))
             (is (= [:seon.agent/id "caller-1"]
                    (:seon.agent.message/from @message-request)))
             (is (= [[:seon.agent/id "planner-1"]]
                    (:seon.agent.message/to @message-request)))
             (is (re-find #"Add order validation\."
                          (:seon.agent.message/content @message-request)))
             (is (= [{::db.id/key :seon.agent.message/id
                      ::db.id/identity-attr :seon.agent.message/id}
                     {::db.id/key :my.plan/id
                      ::db.id/identity-attr :my.plan/id}]
                    (get @allocation-request ::db.id/allocations)))
             (let [built ((get @allocation-request
                               ::db.id/transaction-builder)
                          {:seon.agent.message/id "assignment-1"
                           :my.plan/id "root-1"})
                   tx-data (::db/tx-data built)
                   root (last tx-data)]
               (is (= 2 (count tx-data)))
               (is (= "assignment-1"
                      (:seon.agent.message/id (first tx-data))))
               (is (= {:my.plan/id "root-1"
                       :my.plan/title "Add order validation."
                       :my.plan/goal "Add order validation."
                       :my.plan/description "Reject invalid orders."
                       :my.plan/expect "Tests prove rejection."
                       :my.plan/status :open
                       :my.plan/agent [:seon.agent/id "planner-1"]
                       :my.plan/from [:seon.agent/id "caller-1"]
                       :my.plan/message [:seon.agent.message/id "assignment-1"]
                       :my.plan/claim "assignment-1"}
                      (dissoc root :my.plan/created-at)))
               (is (some? (:my.plan/created-at root))))
             (is (= "root-1" (:my.plan/id @scheduler-request)))
             (is (= "planner-1" (:seon.agent/id @scheduler-request)))
             (is (= :execution
                    (:seon.config/model-variant @scheduler-request)))))
          (.finally
           (fn []
             (set! agent/start! original-start)
             (set! embed/enabled? original-enabled)
             (set! db/db original-db)
             (set! message/message-transaction-for original-message)
             (set! db.id/allocate! original-allocate)
             (set! generate/start-root-scheduler! original-scheduler)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))
