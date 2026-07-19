(ns my.plan-test
  "Focused contracts for the writer-backed asynchronous planning surface."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [clojure.string :as str]
    [malli.core :as m]
    [my.plan :as plan]
    [my.plan.internal :as internal]
    [seon.agent.message :as message]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
    [seon.schema :as schema]))

(def ^:private database
  {:db-name "plan-test"
   :t 536870912
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "00000000-0000-0000-0000-000000000002"})

(def ^:private agent-id "agent-a")

(def ^:private rows
  [{:my.plan/id "root" :my.plan/title "Ship" :my.plan/status :open
    :my.plan/goal "working release" :my.plan/created-at (js/Date. 1)
    :my.plan/agent {:seon.agent/id agent-id}}
   {:my.plan/id "done" :my.plan/title "Prepare" :my.plan/status :done
    :my.plan/created-at (js/Date. 2)
    :my.plan/agent {:seon.agent/id agent-id}
    :my.plan/parent {:my.plan/id "root"}}
   {:my.plan/id "ready" :my.plan/title "Verify" :my.plan/status :open
    :my.plan/created-at (js/Date. 3)
    :my.plan/agent {:seon.agent/id agent-id}
    :my.plan/parent {:my.plan/id "root"}
    :my.plan/needs [{:my.plan/id "done"}]}])

(deftest ordinary-rows-derive-tree-readiness-and-progress
  (testing "one immutable row set supplies every derived planning view"
    (is (= ["ready"]
           (mapv :my.plan/id (internal/ready-leaves-from-rows rows))))
    (is (= {:my.plan/done 1 :my.plan/total 2 :my.plan/done? false}
           (internal/plan-rollup-from-rows rows "root")))
    (is (= ["done" "ready"]
           (mapv :my.plan/id
                 (:my.plan/_parent
                   (internal/subtree-from-rows rows "root")))))))

(deftest status-contract-bounds-pull-nodes-and-preserves-read-errors
  (let [pull-member ((deref #'plan/pull-member)
                     [:my.plan/id :my.plan/title :my.plan/status]
                     [:my.plan/id "root"])]
    (is (= 4096 (:datahike.resource/max-results pull-member))
        "a pull budget counts attribute/ref nodes, not one entity")
    (is (m/validate
         (schema/schema-definition :my.plan/status-response)
         {:my.plan/ok? false :my.plan/error "status read failed"})
        "database failures remain valid agent-facing values")))

(deftest generation-step-attributes-have-one-canonical-database-shape
  (is (= [{:db/ident :my.plan/namespace
           :db/valueType :db.type/ref
           :db/cardinality :db.cardinality/one}
          {:db/ident :my.plan/claim
           :db/valueType :db.type/string
           :db/cardinality :db.cardinality/one}]
         (db/malli->datahike-schema [:my.plan/namespace :my.plan/claim]))))

(deftest html-renderer-matches-the-dynamic-render-interface
  (async done
    (let [original-query db/query]
      (set! db/query (fn [_] (js/Promise.resolve rows)))
      (-> (internal/plan-block-html
           {:seon.db/db database :seon.agent/id agent-id}
           (fn [_] (js/Promise.resolve [])))
          (.then
           (fn [hiccup]
             (is (vector? hiccup))
             (is (str/includes? (pr-str hiccup) "Ship"))
             (is (protocol/ordinary-wire-value? hiccup)
                 "complete nested plan hiccup crosses the child boundary")))
          (.finally (fn [] (set! db/query original-query)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest mutation-is-fenced-by-the-read-snapshot
  (async done
    (let [request (atom nil)
          original-pull db/pull
          original-transact db/transact!
          pull-stub (fn [_database _selector _lookup-ref]
                      (js/Promise.resolve
                        (some #(when (= "ready" (:my.plan/id %)) %) rows)))]
      ;; Direct positional calls compile to the CLJS arity property.
      (set! (.-cljs$core$IFn$_invoke$arity$3 pull-stub) pull-stub)
      (set! db/pull pull-stub)
      (set! db/transact!
            (fn [& values]
              (reset! request (first values))
              (js/Promise.resolve
               {:db-before database :db-after database
                :tx-data [] :tempids {} :tx-meta {}})))
      (-> (plan/done! {:my.plan/id "ready" :seon.db/db database})
          (.then
            (fn [result]
              (is (true? (:my.plan/ok? result)))
              (is (= database (::db/db @request)))
              (is (= database (::db/expected-db @request)))
              (is (= :done (get-in @request [::db/tx-data 0 :my.plan/status])))))
          (.finally
            (fn []
              (set! db/pull original-pull)
              (set! db/transact! original-transact)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest blocked-retains-evidence-and-reopen-releases-the-claim
  (async done
    (let [requests (atom [])
          status (atom :active)
          original-pull db/pull
          original-transact db/transact!
          pull-stub
          (fn [_database _selector _lookup-ref]
            (js/Promise.resolve
             {:my.plan/id "ready"
              :my.plan/title "Verify"
              :my.plan/status @status
              :my.plan/claim "assignment-message"
              :my.plan/created-at (js/Date. 3)
              :my.plan/agent {:seon.agent/id agent-id}}))]
      (set! (.-cljs$core$IFn$_invoke$arity$3 pull-stub) pull-stub)
      (set! db/pull pull-stub)
      (set! db/transact!
            (fn [& values]
              (let [request (first values)]
                (swap! requests conj request)
                (reset! status
                        (get-in request [::db/tx-data 0 :my.plan/status]))
                (js/Promise.resolve
                 {:db-before database :db-after database
                  :tx-data (::db/tx-data request) :tempids {} :tx-meta {}}))))
      (-> (plan/blocked! {:my.plan/id "ready" :seon.db/db database})
          (.then
           (fn [result]
             (is (true? (:my.plan/ok? result)))
             (is (= [{:my.plan/id "ready" :my.plan/status :blocked}]
                    (::db/tx-data (first @requests))))
             (plan/reopen! {:my.plan/id "ready" :seon.db/db database})))
          (.then
           (fn [result]
             (is (true? (:my.plan/ok? result)))
             (is (= [{:my.plan/id "ready" :my.plan/status :open}
                     [:db/retract [:my.plan/id "ready"]
                      :my.plan/completed-at]
                     [:db/retract [:my.plan/id "ready"] :my.plan/claim]]
                    (::db/tx-data (second @requests))))))
          (.finally
           (fn []
             (set! db/pull original-pull)
             (set! db/transact! original-transact)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest reconcile-compiler-is-pure-over-ordinary-rows
  (let [document [{:my.plan/id "root" :my.plan/title "Ship"
                   :my.plan/goal "working release"
                   :my.plan/_parent
                   [{:my.plan/id "ready" :my.plan/title "Verify better"
                     :my.plan/needs [{:my.plan/id "done"}]}]}]
        compiled (internal/compile-reconcile
                   rows "reconcile!" [:seon.agent/id agent-id]
                   document {} (js/Date. 4))]
    (is (nil? (:my.plan.internal/error compiled)))
    (is (= 1 (get-in compiled
                     [:my.plan.internal/diff :my.plan/updated])))
    (is (some #(= "Verify better" (:my.plan/title %))
              (:my.plan.internal/transaction-data compiled)))))

(defn- row
  [id title status created-at & {:as attributes}]
  (merge {:my.plan/id id
          :my.plan/title title
          :my.plan/status status
          :my.plan/created-at (js/Date. created-at)
          :my.plan/agent {:seon.agent/id agent-id}}
         attributes))

(deftest generated-namespace-state-retains-terminal-transitions
  (let [generated
        [(first rows)
         (row "z-model" "my.generated.z-model" :done 11
              :my.plan/parent {:my.plan/id "root"}
              :my.plan/namespace {:seon.ns/name 'my.generated.z-model}
              :my.plan/claim "model-message")
         (row "a-service" "my.generated.a-service" :open 12
              :my.plan/parent {:my.plan/id "root"}
              :my.plan/namespace {:seon.ns/name 'my.generated.a-service}
              :my.plan/needs [{:my.plan/id "z-model"}])
         (row "other" "my.generated.other" :open 13
              :my.plan/namespace {:seon.ns/name 'my.generated.other})]
        initial (internal/namespace-root-state-from-rows generated "root")
        claimed-rows
        (mapv #(if (= "a-service" (:my.plan/id %))
                 (assoc % :my.plan/claim "service-message")
                 %)
              generated)
        claimed (internal/namespace-root-state-from-rows claimed-rows "root")
        done-rows
        (mapv #(if (= "a-service" (:my.plan/id %))
                 (assoc % :my.plan/status :done)
                 %)
              claimed-rows)
        done-state (internal/namespace-root-state-from-rows done-rows "root")
        blocked-state
        (internal/namespace-root-state-from-rows
         (mapv #(if (= "a-service" (:my.plan/id %))
                  (assoc % :my.plan/status :blocked)
                  %)
               claimed-rows)
         "root")]
    (is (= [{:my.plan/id "a-service"
             :seon.ns/name 'my.generated.a-service}]
           (:my.plan.internal/ready-steps initial)))
    (is (= ['my.generated.a-service 'my.generated.z-model]
           (mapv :seon.ns/name
                 (:my.plan.internal/namespace-steps initial)))
        "frontier and status projections sort by namespace, not transaction order")
    (is (empty? (:my.plan.internal/ready-steps claimed)))
    (is (not= claimed done-state)
        "a claimed final leaf still changes the observed value when it closes")
    (is (= {:my.plan/done 2 :my.plan/total 2 :my.plan/done? true}
           (:my.plan/progress done-state)))
    (is (= :done
           (-> done-state :my.plan.internal/namespace-steps first
               :my.plan/status)))
    (is (:my.plan/blocked? blocked-state)
        "a blocked generated leaf makes its root terminal for scheduling")))

(defn- compile-with-generated-ids
  [existing document]
  (let [preview (internal/compile-reconcile
                  existing "reconcile!" [:seon.agent/id agent-id]
                  document {} (js/Date. 10))
        ids (into {}
                  (map-indexed
                    (fn [index allocation-key]
                      [allocation-key (str "generated-" index)]))
                  (:my.plan.internal/allocation-keys preview))]
    (internal/compile-reconcile
      existing "reconcile!" [:seon.agent/id agent-id]
      document ids (js/Date. 10))))

(defn- compile-namespace-dag-with-generated-ids
  [existing projection]
  (let [preview (internal/compile-namespace-dag
                  existing "root" projection {} (js/Date. 11))
        ids (into {}
                  (map-indexed
                    (fn [index allocation-key]
                      [allocation-key (str "namespace-step-" index)]))
                  (:my.plan.internal/allocation-keys preview))]
    (internal/compile-namespace-dag
      existing "root" projection ids (js/Date. 11))))

(def ^:private namespace-projection
  {:seon.repl/namespaces
   [{:seon.ns/name 'my.generated.model :seon.ns/require-edges #{}}
    {:seon.ns/name 'my.generated.service
     :seon.ns/require-edges #{'my.generated.model 'seon.schema}}]
   :seon.repl/namespace-order ['my.generated.model 'my.generated.service]
   :seon.repl/errors []})

(deftest generated-program-publication-no-ops-only-without-a-cause-linked-root
  (async done
    (let [original-db db/db
          original-query db/query
          original-execute-many db/execute-many
          original-allocate db.id/allocate!
          downstream? (atom false)]
      (set! db/db
            (fn [& _]
              (reset! downstream? true)
              (js/Promise.reject (js/Error. "unexpected current-db read"))))
      (set! db/query
            (fn [request]
              (is (= database (::db/db request)))
              (js/Promise.resolve nil)))
      (set! db/execute-many
            (fn [_]
              (reset! downstream? true)
              (js/Promise.reject (js/Error. "unexpected subtree read"))))
      (set! db.id/allocate!
            (fn [_]
              (reset! downstream? true)
              (js/Promise.reject (js/Error. "unexpected allocation"))))
      (-> (plan/publish-generated-program!
           {:seon.agent.run/id "ordinary-run"
            :seon.agent.turn/id "turn-1"
            ::db/db database
            :my.plan/program namespace-projection
            :my.plan/eval-batch
            {:seon.eval/ids ["eval-1"]
             :seon.eval/n-ok 1
             :seon.eval/n-fail 0}})
          (.then
           (fn [result]
             (is (= {:my.plan/ok? true} result))
             (is (false? @downstream?))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/query original-query)
             (set! db/execute-many original-execute-many)
             (set! db.id/allocate! original-allocate)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest generated-root-candidates-do-not-require-a-namespace-child
  (async done
    (let [original-query db/query
          observed (atom nil)]
      (set! db/query
            (fn [request]
              (reset! observed request)
              (js/Promise.resolve
               [["root-only" "coordinator"]
                ["with-children" "coordinator"]])))
      (-> (plan/generated-root-candidates {::db/db database})
          (.then
           (fn [candidates]
             (is (= [{:my.plan/id "root-only"
                      :seon.agent/id "coordinator"}
                     {:my.plan/id "with-children"
                      :seon.agent/id "coordinator"}]
                    candidates))
             (is (= database (::db/db @observed)))
             (is (not-any? #{:my.plan/namespace}
                           (tree-seq coll? seq (::db/query @observed))))
             (is (some #{:my.plan/claim}
                       (tree-seq coll? seq (::db/args @observed))))
             (is (some #{:my.plan/message}
                       (tree-seq coll? seq (::db/args @observed))))))
          (.finally (fn [] (set! db/query original-query)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest generated-terminal-commits-status-and-addressed-message-once
  (async done
    (let [original-pull db/pull
          original-message message/message-transaction-for
          original-allocate db.id/allocate!
          status (atom :open)
          allocations (atom 0)
          writes (atom [])
          message-request (atom nil)]
      (set! db/pull
            (fn
              ([request]
               (is (= database (::db/db request)))
               (js/Promise.resolve
                {:my.plan/id "root"
                 :my.plan/status @status
                 :my.plan/agent {:seon.agent/id "coordinator"}
                 :my.plan/from {:seon.agent/id "caller"}}))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! message/message-transaction-for
            (fn [current request]
              (is (= database current))
              (reset! message-request request)
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
              (swap! allocations inc)
              (let [ids {:seon.agent.message/id "terminal-message"}
                    write ((::db.id/transaction-builder request) ids)]
                (swap! writes conj write)
                (reset! status :done)
                (js/Promise.resolve {::db.id/ids ids}))))
      (-> (plan/commit-generated-terminal!
           {::db/db database
            :my.plan/id "root"
            :my.plan/status :done
            :seon.agent.message/content "{:my.plan/id \"root\"}"})
          (.then
           (fn [first-result]
             (is (= {:my.plan/ok? true
                     :my.plan/id "root"
                     :my.plan/status :done
                     :seon.agent.message/id "terminal-message"}
                    first-result))
             (is (= [:seon.agent/id "coordinator"]
                    (:seon.agent.message/from @message-request)))
             (is (= [[:seon.agent/id "caller"]]
                    (:seon.agent.message/to @message-request)))
             (let [write (first @writes)]
               (is (= database (::db/expected-db write)))
               (is (= [:db.fn/cas [:my.plan/id "root"]
                       :my.plan/status :open :done]
                      (first (::db/tx-data write))))
               (is (some #(= "terminal-message"
                             (:seon.agent.message/id %))
                         (::db/tx-data write))))
             (plan/commit-generated-terminal!
              {::db/db database
               :my.plan/id "root"
               :my.plan/status :done
               :seon.agent.message/content "repeat"})))
          (.then
           (fn [repeated]
             (is (= {:my.plan/ok? true
                     :my.plan/id "root"
                     :my.plan/status :done}
                    repeated))
             (is (= 1 @allocations))
             (is (= 1 (count @writes)))))
          (.finally
           (fn []
             (set! db/pull original-pull)
             (set! message/message-transaction-for original-message)
             (set! db.id/allocate! original-allocate)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest generated-terminal-cas-race-rereads-committed-status
  (async done
    (let [original-db db/db
          original-pull db/pull
          original-message message/message-transaction-for
          original-allocate db.id/allocate!
          status (atom :open)
          current-reads (atom 0)
          pulls (atom 0)]
      (set! db/db
            (fn
              ([] (js/Promise.reject (js/Error. "database name required")))
              ([request]
               (is (= {::db/database-name "plan-test"} request))
               (swap! current-reads inc)
               (js/Promise.resolve database))))
      (set! db/pull
            (fn
              ([_]
               (swap! pulls inc)
               (js/Promise.resolve
                {:my.plan/id "root"
                 :my.plan/status @status
                 :my.plan/agent {:seon.agent/id "coordinator"}
                 :my.plan/from {:seon.agent/id "caller"}}))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! message/message-transaction-for
            (fn [_ _]
              (js/Promise.resolve
               {:seon.agent.message/allocations
                [{::db.id/key :seon.agent.message/id
                  ::db.id/identity-attr :seon.agent.message/id}]
                :seon.agent.message/transaction-builder
                (fn [_] {::db/tx-data []})})))
      (set! db.id/allocate!
            (fn [_]
              (reset! status :done)
              (js/Promise.resolve {:seon.error/message "CAS lost"})))
      (-> (plan/commit-generated-terminal!
           {::db/db database
            :my.plan/id "root"
            :my.plan/status :done
            :seon.agent.message/content "done"})
          (.then
           (fn [result]
             (is (= {:my.plan/ok? true
                     :my.plan/id "root"
                     :my.plan/status :done}
                    result))
             (is (= 1 @current-reads))
             (is (= 2 @pulls))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/pull original-pull)
             (set! message/message-transaction-for original-message)
             (set! db.id/allocate! original-allocate)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest generated-program-publication-reuses-ordered-evals-and-expected-db
  (async done
    (let [original-db db/db
          original-query db/query
          original-execute-many db/execute-many
          original-allocate db.id/allocate!
          allocation-request (atom nil)
          current-db (assoc database
                            :t 536870913
                            :datahike/commit-id
                            #uuid "00000000-0000-0000-0000-000000000003")
          current-requests (atom [])
          success (fn [result]
                    {::protocol/success? true ::protocol/result result})
          eval-ids ["eval-model" "eval-service"]]
      (set! db/db
            (fn
              ([]
               (js/Promise.reject
                (js/Error. "current DB acquisition must name the database")))
              ([request]
               (swap! current-requests conj request)
               (js/Promise.resolve current-db))))
      (set! db/query
            (fn [request]
              (is (= database (::db/db request)))
              (js/Promise.resolve "root")))
      (set! db/execute-many
            (fn [request]
              (is (= current-db (::db/db request)))
              (js/Promise.resolve
               {::db/results [(success (first rows)) (success [])]})))
      (set! db.id/allocate!
            (fn [request]
              (reset! allocation-request request)
              (let [ids {:my.plan.namespace/id-0 "model-step"
                         :my.plan.namespace/id-1 "service-step"}
                    write ((::db.id/transaction-builder request) ids)]
                (js/Promise.resolve
                 {::db.id/ids ids ::db/tx-data (::db/tx-data write)}))))
      (-> (plan/publish-generated-program!
           {:seon.agent.run/id "planner-run"
            :seon.agent.turn/id "turn-1"
            ::db/db database
            :my.plan/program namespace-projection
            :my.plan/eval-batch
            {:seon.eval/ids eval-ids
             :seon.eval/n-ok 2
             :seon.eval/n-fail 0}})
          (.then
           (fn [result]
             (is (true? (:my.plan/ok? result)))
             (is (= "root" (:my.plan/root result)))
             (is (= eval-ids (:seon.eval/ids result))
                 "the publisher returns the evaluator's exact ordered ids")
             (is (= [{::db/database-name "plan-test"}] @current-requests))
             (is (= current-db (::db/db @allocation-request)))
             (is (= current-db
                    (::db/expected-db
                     ((::db.id/transaction-builder @allocation-request)
                      {:my.plan.namespace/id-0 "model-step"
                       :my.plan.namespace/id-1 "service-step"}))))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/query original-query)
             (set! db/execute-many original-execute-many)
             (set! db.id/allocate! original-allocate)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest generated-program-failure-uses-the-one-terminal-owner
  (async done
    (let [original-db db/db
          original-query db/query
          original-execute-many db/execute-many
          original-terminal plan/commit-generated-terminal!
          current-db (update database :t inc)
          terminal-request (atom nil)
          success (fn [result]
                    {::protocol/success? true ::protocol/result result})]
      (set! db/db
            (fn
              ([] (js/Promise.reject (js/Error. "database name required")))
              ([_] (js/Promise.resolve current-db))))
      (set! db/query (fn [_] (js/Promise.resolve "root")))
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               {::db/results [(success (first rows)) (success [])]})))
      (set! plan/commit-generated-terminal!
            (fn [request]
              (reset! terminal-request request)
              (js/Promise.resolve
               {:my.plan/ok? true
                :my.plan/id "root"
                :my.plan/status :blocked
                :seon.agent.message/id "blocked-message"})))
      (-> (plan/publish-generated-program!
           {:seon.agent.run/id "planner-run"
            :seon.agent.turn/id "turn-1"
            ::db/db database
            :my.plan/program namespace-projection
            :my.plan/eval-batch
            {:seon.eval/ids []
             :seon.eval/n-ok 0
             :seon.eval/n-fail 0
             :seon.eval/fenced? true}})
          (.then
           (fn [result]
             (is (false? (:my.plan/ok? result)))
             (is (= "root" (:my.plan/root result)))
             (is (= current-db (::db/db @terminal-request)))
             (is (= :blocked (:my.plan/status @terminal-request)))
             (is (re-find #"lost its run fence"
                          (:seon.agent.message/content @terminal-request)))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/query original-query)
             (set! db/execute-many original-execute-many)
             (set! plan/commit-generated-terminal! original-terminal)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest namespace-dag-compiler-authors-dependency-leaves-and-is-idempotent
  (let [compiled (compile-namespace-dag-with-generated-ids
                   [(first rows)] namespace-projection)
        tx (:my.plan.internal/transaction-data compiled)
        model (some #(when (= "my.generated.model" (:my.plan/title %)) %) tx)
        service (some #(when (= "my.generated.service" (:my.plan/title %)) %) tx)
        reconciled
        [(first rows)
         (row (:my.plan/id model) "my.generated.model" :open 11
              :my.plan/parent {:my.plan/id "root"}
              :my.plan/namespace {:seon.ns/name 'my.generated.model})
         (row (:my.plan/id service) "my.generated.service" :open 11
              :my.plan/parent {:my.plan/id "root"}
              :my.plan/namespace {:seon.ns/name 'my.generated.service}
              :my.plan/needs [{:my.plan/id (:my.plan/id model)}])]
        repeated (internal/compile-namespace-dag
                   reconciled "root" namespace-projection {} (js/Date. 12))]
    (is (= {:my.plan/added 2 :my.plan/dropped 0 :my.plan/updated 0}
           (:my.plan.internal/diff compiled)))
    (is (= [[:my.plan/id (:my.plan/id model)]] (:my.plan/needs service)))
    (is (empty? (:my.plan.internal/transaction-data repeated)))
    (is (= {:my.plan/added 0 :my.plan/dropped 0 :my.plan/updated 0}
           (:my.plan.internal/diff repeated)))))

(deftest namespace-dag-compiler-preserves-done-and-drops-only-unfinished-leaves
  (let [existing
        [(first rows)
         (row "model" "my.generated.model" :done 11
              :my.plan/parent {:my.plan/id "root"}
              :my.plan/namespace {:seon.ns/name 'my.generated.model})
         (row "old" "my.generated.old" :open 11
              :my.plan/parent {:my.plan/id "root"}
              :my.plan/namespace {:seon.ns/name 'my.generated.old})]
        empty-projection {:seon.repl/namespaces []
                          :seon.repl/namespace-order []
                          :seon.repl/errors []}
        compiled (internal/compile-namespace-dag
                   existing "root" empty-projection {} (js/Date. 12))
        tx (:my.plan.internal/transaction-data compiled)]
    (is (not-any? #(= [:db.fn/retractEntity [:my.plan/id "model"]] %) tx))
    (is (some #(= [:db.fn/retractEntity [:my.plan/id "old"]] %) tx))
    (is (= {:my.plan/added 0 :my.plan/dropped 1 :my.plan/updated 0}
           (:my.plan.internal/diff compiled)))))

(deftest row-derivations-preserve-dependency-semantics
  (let [graph [(row "root" "Root" :open 0)
               (row "need" "Need" :open 1
                    :my.plan/parent {:my.plan/id "root"})
               (row "work" "Work" :open 2
                    :my.plan/parent {:my.plan/id "root"}
                    :my.plan/needs [{:my.plan/id "need"}])]]
    (is (true? (internal/ready-from-rows? graph "need")))
    (is (true? (internal/blocked-from-rows? graph "work")))
    (is (false? (internal/ready-from-rows? graph "work")))
    (let [done-graph (mapv #(if (= "need" (:my.plan/id %))
                              (assoc % :my.plan/status :done)
                              %)
                           graph)]
      (is (false? (internal/blocked-from-rows? done-graph "work")))
      (is (true? (internal/ready-from-rows? done-graph "work"))))))

(deftest drained-parent-becomes-ready-to-close
  (let [graph [(row "root" "Root" :open 0)
               (row "a" "A" :done 1 :my.plan/parent {:my.plan/id "root"})
               (row "b" "B" :done 2 :my.plan/parent {:my.plan/id "root"})]]
    (is (true? (internal/ready-from-rows? graph "root")))
    (is (= ["root"]
           (mapv :my.plan/id (internal/ready-leaves-from-rows graph))))
    (is (= {:my.plan/done 2 :my.plan/total 2 :my.plan/done? true}
           (internal/plan-rollup-from-rows graph "root")))))

(deftest active-step-wins-the-position-anchor
  (let [graph [(row "root" "Root" :open 0 :my.plan/goal "Ship")
               (row "ready" "Ready" :open 1
                    :my.plan/parent {:my.plan/id "root"})
               (row "active" "Active" :active 2
                    :my.plan/parent {:my.plan/id "root"})]
        anchor (internal/anchor-from-rows graph)]
    (is (= "active" (get-in anchor [:my.plan/step :my.plan/id])))
    (is (true? (:my.plan/active? anchor)))
    (is (= ["root" "active"]
           (mapv :my.plan/id (:my.plan/chain anchor))))))

(deftest current-run-cause-step-anchors-over-stale-authored-work
  (async done
    (let [original db/execute-many
          calls (atom 0)
          success (fn [result]
                    {::protocol/success? true ::protocol/result result})]
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               (case (swap! calls inc)
                 1 {::db/results
                    [(success [["active" "Older work" "" (js/Date. 1)
                                false 10]])
                     (success [["message" "Answer the new request" ""
                                (js/Date. 2) 77]])
                     (success [])
                     (success {:db/id 1 :seon.agent/id agent-id})
                     (success [["message" "Answer the new request" ""
                                (js/Date. 2) 77 :open 11]])]}
                 2 {::db/results
                    [(success {:my.plan/id "message"
                               :my.plan/title "Answer the new request"})
                     (success [[:open 1]])]}))))
      (-> (internal/plan-block
           {:seon.db/db database
            :seon.agent/id agent-id
            :seon.agent.run/id "run-from-message"}
           nil)
          (.then
           (fn [text]
             (is (str/includes? text
                                "next ready: message «Answer the new request»"))
             (is (and (str/includes? text "; ▶ active [")
                      (str/includes? text "] Older work"))
                 "existing authored work remains visible without displacing the current request")))
          (.finally (fn [] (set! db/execute-many original)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest public-position-and-plan-block-share-the-current-run-cause
  (async done
    (let [original-query db/query
          original-execute-many db/execute-many
          calls (atom 0)
          requests (atom [])
          success (fn [result]
                    {::protocol/success? true ::protocol/result result})
          initial
          {::db/results
           [(success [["active" "Older work" "" (js/Date. 1) false 10]])
            (success [["message" "Answer the new request" ""
                       (js/Date. 2) 77]])
            (success [])
            (success {:db/id 1 :seon.agent/id agent-id})
            (success [["message" "Answer the new request" ""
                       (js/Date. 2) 77 :open 11]])]}
          selected
          {::db/results
           [(success {:my.plan/id "message"
                      :my.plan/title "Answer the new request"})
            (success [[:open 1]])]}]
      (set! db/query
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve "run-from-message")))
      (set! db/execute-many
            (fn [request]
              (swap! requests conj request)
              (js/Promise.resolve
               (if (odd? (swap! calls inc)) initial selected))))
      (-> (plan/position
           {:seon.db/db database :seon.agent/id agent-id})
          (.then
           (fn [position]
             (is (= "message"
                    (get-in position [:my.plan/position :my.plan/step])))
             (is (false?
                  (get-in position [:my.plan/position :my.plan/active?])))
             (internal/plan-block
              {:seon.db/db database
               :seon.agent/id agent-id
               :seon.agent.run/id "run-from-message"}
              nil)))
          (.then
           (fn [text]
             (is (str/includes? text
                                "next ready: message «Answer the new request»"))
             (is (and (str/includes? text "; ▶ active [")
                      (str/includes? text "] Older work"))
                 "the unrelated active remains visible and active")
             (is (every? #(= database (::db/db %)) @requests)
                 "both interfaces read one exact immutable database value")))
          (.finally
           (fn []
             (set! db/query original-query)
             (set! db/execute-many original-execute-many)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest run-cause-selection-authorizes-by-message-not-plan-owner
  (let [where (:where @#'internal/run-cause-step-query)]
    (is (some #{'[?run :seon.agent.run/agent ?agent]} where))
    (is (some #{'[?run :seon.agent.run/cause ?message]} where))
    (is (some #{'[?step :my.plan/message ?message]} where))
    (is (not-any? #{'[?step :my.plan/agent ?agent]} where)
        "generated namespace leaves remain owned by their coordinator")))

(deftest generated-development-guidance-stays-in-the-plan-block
  (let [anchor {:my.plan/step
                {:my.plan/id "orders"
                 :my.plan/title "Implement orders"
                 :my.plan/namespace 404}
                :my.plan/chain
                [{:my.plan/id "root" :my.plan/title "Ship orders"
                  :my.plan/goal "Working order flow"}]
                :my.plan/active? false
                :my.plan/progress
                {:my.plan/done 0 :my.plan/total 1 :my.plan/done? false}}
        base {:my.plan.internal/anchor anchor
              :my.plan.internal/actives []
              :my.plan.internal/readies []
              :my.plan.internal/dones []
              :my.plan.internal/escalation-text ""}
        ordinary (@#'internal/format-plan-body
                  (assoc base :my.plan.internal/cause-step? false) false)
        repair (@#'internal/format-plan-body
                (assoc base :my.plan.internal/cause-step? true) false)
        planner (@#'internal/format-plan-body
                 (assoc base
                        :my.plan.internal/cause-step? true
                        :my.plan.internal/anchor
                        (update anchor :my.plan/step dissoc
                                :my.plan/namespace))
                 true)]
    (is (not (str/includes? ordinary "generated-code development")))
    (is (str/includes? repair "generated-code development"))
    (is (str/includes? repair "namespaces section"))
    (is (str/includes? planner "generated-code development")
        "the specialized plan renderer marks the initial planner root")
    (is (every? #(or (str/blank? %) (str/starts-with? % ";"))
                (str/split-lines internal/development-teaching))
        "developer teaching is valid inert Clojure commentary")))

(deftest completed-current-run-cause-remains-the-position-anchor
  (async done
    (let [original db/execute-many
          calls (atom 0)
          success (fn [result]
                    {::protocol/success? true ::protocol/result result})]
      (set! db/execute-many
            (fn [_]
              (js/Promise.resolve
               (case (swap! calls inc)
                 1 {::db/results
                    [(success [])
                     (success [["older" "Unrelated older work" ""
                                (js/Date. 1) false]])
                     (success [["message" "Answer the current request"
                                (js/Date. 2)]])
                     (success {:db/id 1 :seon.agent/id agent-id})
                     (success [["message" "Answer the current request" ""
                                (js/Date. 1) 77 :done 12]])]}
                 2 {::db/results
                    [(success {:my.plan/id "message"
                               :my.plan/title "Answer the current request"})
                     (success [[:done 1]])]}))))
      (-> (internal/plan-block
           {:seon.db/db database
            :seon.agent/id agent-id
            :seon.agent.run/id "run-from-message"}
           nil)
          (.then
           (fn [text]
             (is (str/includes? text
                                "CURRENT REQUEST COMPLETED: message"))
             (is (str/includes? text
                                (str "close this run now with "
                                     "(seon.agent.lifecycle/complete")))
             (is (str/includes? text "Unrelated older work")
                 "other work stays visible but cannot replace the run cause")))
          (.finally (fn [] (set! db/execute-many original)))
          (.then (fn [_] (done)))
          (.catch (fn [error] (is false (str error)) (done)))))))

(deftest tree-assembly-is-cycle-safe-and-agent-scoped
  (let [other (assoc (row "other" "Other" :open 4)
                     :my.plan/agent {:seon.agent/id "agent-b"})
        graph (conj rows other
                    (row "cycle-a" "Cycle A" :open 5
                         :my.plan/parent {:my.plan/id "cycle-b"})
                    (row "cycle-b" "Cycle B" :open 6
                         :my.plan/parent {:my.plan/id "cycle-a"}))]
    (is (= 5 (count (internal/rows-for-agent graph
                                             [:seon.agent/id agent-id]))))
    (is (= ["root" "other"]
           (mapv :my.plan/id (internal/forest-from-rows graph))))
    (is (= #{"cycle-a" "cycle-b"}
           (set (internal/descendant-ids-from-rows graph "cycle-a"))))))

(deftest open-document-prunes-completed-subtrees
  (let [tree {:my.plan/id "root" :my.plan/title "Root" :my.plan/status :open
              :my.plan/_parent
              [{:my.plan/id "done" :my.plan/title "Done" :my.plan/status :done
                :my.plan/_parent
                [{:my.plan/id "hidden" :my.plan/title "Hidden"
                  :my.plan/status :open}]}
               {:my.plan/id "kept" :my.plan/title "Kept"
                :my.plan/status :open}]}
        pruned (internal/prune-done tree)]
    (is (= ["kept"] (mapv :my.plan/id (:my.plan/_parent pruned))))
    (is (nil? (internal/prune-done
                {:my.plan/id "done" :my.plan/status :done})))))

(deftest plan-key-validation-is-schema-derived-and-recursive
  (is (nil? (internal/check-plan-keys
              "plan!" {:my.plan/title "Root"
                        :my.plan/children [{:my.plan/title "Child"}]})))
  (let [top (internal/check-plan-keys
              "plan!" {:my.plan/title "Root" :my.plan/gol "typo"})
        child (internal/check-plan-keys
                "plan!" {:my.plan/title "Root"
                          :my.plan/children
                          [{:my.plan/title "Child" :my.plan/xpect "typo"}]})]
    (is (str/includes? (:my.plan/error top) ":my.plan/gol"))
    (is (str/includes? (:my.plan/error top) ":my.plan/goal"))
    (is (str/includes? (:my.plan/error child) ":my.plan/expect")))
  (is (nil? (internal/check-request-keys
              "done!" {:my.plan/id "x" :foreign/value true}
              :my.plan/id-request))))

(deftest reconcile-schema-declares-document-prefill
  (let [definition (schema/schema-definition :my.plan/reconcile-request)
        entry (some #(when (and (vector? %)
                                (= :my.plan/tree (first %))) %)
                    (rest definition))]
    (is (= 'my.plan/document (:seon.render/prefill-fn (second entry))))))

(deftest plan-compiler-authors-one-tree-and-resolves-label-dependencies
  (let [document [{:my.plan/title "Ship" :my.plan/goal "Working release"
                   :my.plan/children
                   [{:my.plan/title "Prepare" :my.plan/ref "prepare"
                     :my.plan/description "Set up"}
                    {:my.plan/title "Verify" :my.plan/ref "verify"
                     :my.plan/after ["prepare"]}]}]
        compiled (compile-with-generated-ids [] document)
        tx (:my.plan.internal/transaction-data compiled)
        verify (some #(when (= "Verify" (:my.plan/title %)) %) tx)]
    (is (nil? (:my.plan.internal/error compiled)))
    (is (= {:my.plan/added 3 :my.plan/dropped 0 :my.plan/updated 0}
           (:my.plan.internal/diff compiled)))
    (is (= "Set up" (:my.plan/description
                       (some #(when (= "Prepare" (:my.plan/title %)) %) tx))))
    (is (= 1 (count (:my.plan/needs verify))))
    (is (= #{:root "prepare" "verify"}
           (set (keys (:my.plan.internal/labels compiled)))))))

(deftest reconcile-round-trip-has-zero-delta
  (let [document (internal/prune-done (internal/forest-from-rows rows))
        compiled (internal/compile-reconcile
                   rows "reconcile!" [:seon.agent/id agent-id]
                   document {} (js/Date. 5))]
    (is (nil? (:my.plan.internal/error compiled)))
    (is (empty? (:my.plan.internal/transaction-data compiled)))
    (is (= {:my.plan/added 0 :my.plan/dropped 0 :my.plan/updated 0}
           (:my.plan.internal/diff compiled)))))

(deftest reconcile-updates-mints-and-drops-in-one-transaction
  (let [existing (conj rows
                       (row "drop" "Drop" :open 4
                            :my.plan/parent {:my.plan/id "root"}))
        document [{:my.plan/id "root" :my.plan/title "Ship"
                   :my.plan/goal "working release"
                   :my.plan/children
                   [{:my.plan/id "ready" :my.plan/title "Verify better"
                     :my.plan/description "sharpened"}
                    {:my.plan/title "Publish"}]}]
        compiled (compile-with-generated-ids existing document)
        tx (:my.plan.internal/transaction-data compiled)]
    (is (= {:my.plan/added 1 :my.plan/dropped 1 :my.plan/updated 1}
           (:my.plan.internal/diff compiled)))
    (is (some #(= "Verify better" (:my.plan/title %)) tx))
    (is (some #(= "Publish" (:my.plan/title %)) tx))
    (is (some #(= [:db.fn/retractEntity [:my.plan/id "drop"]] %) tx))))

(deftest reconcile-protects-done-and-foreign-identities
  (let [done-doc [{:my.plan/id "done" :my.plan/title "Rewrite history"}]
        foreign [(assoc (row "foreign" "Foreign" :open 5)
                        :my.plan/agent {:seon.agent/id "agent-b"})]
        done-result (internal/compile-reconcile
                      rows "reconcile!" [:seon.agent/id agent-id]
                      done-doc {} (js/Date. 6))
        foreign-result (internal/compile-reconcile
                         (into rows foreign) "reconcile!"
                         [:seon.agent/id agent-id]
                         [{:my.plan/id "foreign" :my.plan/title "Steal"}]
                         {} (js/Date. 6))]
    (is (str/includes? (:my.plan.internal/error done-result) "reopen!"))
    (is (str/includes? (:my.plan.internal/error foreign-result)
                       "not in your open tree"))))

(deftest reconcile-resolves-one-idless-root-without-reminting
  (let [document [{:my.plan/title "Ship renamed"
                   :my.plan/children
                   [{:my.plan/id "ready" :my.plan/title "Verify"}]}]
        compiled (internal/compile-reconcile
                   rows "reconcile!" [:seon.agent/id agent-id]
                   document {} (js/Date. 7))]
    (is (nil? (:my.plan.internal/error compiled)))
    (is (true? (:my.plan.internal/resolved-root? compiled)))
    (is (= "root" (:my.plan.internal/root-id compiled)))
    (is (= 0 (:my.plan/added (:my.plan.internal/diff compiled))))))

(deftest reconcile-refuses-ambiguous-idless-roots-and-children
  (let [two-roots [(row "r1" "One" :open 0)
                   (row "r2" "Two" :open 1)]
        roots-result (internal/compile-reconcile
                       two-roots "reconcile!" [:seon.agent/id agent-id]
                       [{:my.plan/title "Third"}] {} (js/Date. 8))
        duplicate-kids [(row "root" "Root" :open 0)
                        (row "a1" "Alpha" :open 1
                             :my.plan/parent {:my.plan/id "root"})
                        (row "a2" "Alpha" :open 2
                             :my.plan/parent {:my.plan/id "root"})]
        child-result (internal/compile-reconcile
                       duplicate-kids "reconcile!" [:seon.agent/id agent-id]
                       [{:my.plan/id "root" :my.plan/title "Root"
                         :my.plan/children [{:my.plan/title "Alpha"}]}]
                       {} (js/Date. 8))]
    (is (str/includes? (:my.plan.internal/error roots-result) "r1"))
    (is (str/includes? (:my.plan.internal/error roots-result) "r2"))
    (is (str/includes? (:my.plan.internal/error child-result) "a1"))
    (is (str/includes? (:my.plan.internal/error child-result) "a2"))))

(deftest reconcile-refuses-malformed-references-and-duplicate-identities
  (let [unknown-label (compile-with-generated-ids
                        [] [{:my.plan/title "Root"
                             :my.plan/children
                             [{:my.plan/title "Child"
                               :my.plan/after ["missing"]}]}])
        bad-need (compile-with-generated-ids
                   [] [{:my.plan/title "Root"
                        :my.plan/needs [{:wrong/id "missing"}]}])
        duplicate (internal/compile-reconcile
                    rows "reconcile!" [:seon.agent/id agent-id]
                    [{:my.plan/id "root" :my.plan/title "Root"
                      :my.plan/children
                      [{:my.plan/id "root" :my.plan/title "Again"}]}]
                    {} (js/Date. 9))]
    (is (str/includes? (:my.plan.internal/error unknown-label)
                       "unknown label"))
    (is (str/includes? (:my.plan.internal/error bad-need)
                       "unrecognizable :my.plan/needs"))
    (is (str/includes? (:my.plan.internal/error duplicate)
                       "appears twice"))))

(defn- eval-row
  [id ok? source & [kind]]
  (cond-> {:seon.eval/id id :seon.eval/ok? ok? :seon.eval/source source
           :seon.eval/error "failed"}
    kind (assoc :seon.eval/error-data
                (pr-str {:seon.error/kind kind}))))

(deftest wedge-counts-live-same-call-failures
  (let [source "(schema/register! :my.rows (map [:string]))"
        rows [(eval-row "e1" false source :invalid-input)
              (eval-row "e2" false source :invalid-input)
              (eval-row "e3" false source :invalid-input)]
        wedge (internal/wedge rows 3)]
    (is (= "schema/register!" (:my.plan/root-sym wedge)))
    (is (= :invalid-input (:my.plan/root-kind wedge)))
    (is (= 3 (:my.plan/fail-count wedge)))
    (is (= "e1" (:my.plan/episode wedge)))
    (is (nil? (internal/wedge rows 4)))))

(deftest wedge-resets-only-on-success-of-the-same-call
  (let [source "(schema/register! :my.rows (map [:string]))"
        failures [(eval-row "e1" false source)
                  (eval-row "e2" false source)]
        unrelated (conj failures (eval-row "u" true "(defn fixed [] 1)")
                        (eval-row "e3" false source))
        reset (conj failures (eval-row "ok" true source)
                    (eval-row "e3" false source))]
    (is (= 3 (:my.plan/fail-count (internal/wedge unrelated 3))))
    (is (nil? (internal/wedge reset 2)))))

(deftest escalation-marker-is-stable-and-addressable
  (let [marker (internal/consult-marker "step-a" "eval-a")]
    (is (= marker (internal/consult-marker "step-a" "eval-a")))
    (is (str/includes? marker ":my.plan/step \"step-a\""))
    (is (str/includes? marker ":my.plan/episode \"eval-a\""))))

(deftest transaction-reports-and-errors-map-to-plan-results
  (is (= {:my.plan/ok? true :my.plan/id "step"}
         (internal/write-result "done!" "step"
                                {:db-before database :db-after database
                                 :tx-data [] :tempids {}})))
  (let [failure (internal/write-result
                  "done!" "step"
                  {:seon.error/message "writer unavailable"})]
    (is (false? (:my.plan/ok? failure)))
    (is (str/includes? (:my.plan/error failure) "writer unavailable"))))
