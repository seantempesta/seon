(ns seon.flow.loop-test
  "Standing fake-agent proofs for the generate-code loop coordination."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.flow :as sut]
            [seon.test-support :as test-support]))

;; PROTOTYPE ONLY. The production plan-lineage schema belongs to runtime step
;; 4. These raw Datahike attributes exist only in a throwaway test database.
(def ^:private prototype-schema
  [{:db/ident ::plan-id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::plan-fix-runs
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident ::plan-config
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::plan-admission
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::plan-choice-point
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::plan-consumers
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident ::fix-run-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::fix-run-plan
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::fix-run-namespace
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::attempt-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::attempt-run
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::attempt-ordinal
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident ::attempt-outcome
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::attempt-fault-namespace
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::success-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::success-plan
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::success-namespace
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::escalation-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::escalation-plan
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::wake-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::wake-plan
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::wake-target
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::wake-source
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::wake-reason
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident ::config-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::max-turns
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident ::max-failures
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident ::planner-observation-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident ::planner-observation-plan
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident ::planner-observation-basis
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident ::planner-observed-success
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}])

(defn- with-prototype-database
  [body]
  (let [configuration
        {:store {:backend :memory :id (random-uuid)}
         :schema-flexibility :write
         :keep-history? true}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (db/transact! connection prototype-schema)
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- create-lineage!
  [connection plan-id owners max-turns max-failures]
  (let [config-id (str plan-id "/budget")
        fix-run-ids
        (mapv (fn [owner] (str plan-id "/" (name owner))) owners)]
    (db/transact!
     connection
     (into
      [{::config-id config-id
        ::max-turns (long max-turns)
        ::max-failures (long max-failures)}
       {::plan-id plan-id
        ::plan-config [::config-id config-id]
        ::plan-admission ::pending}]
      (map
       (fn [owner fix-run-id]
         {::fix-run-id fix-run-id
          ::fix-run-plan [::plan-id plan-id]
          ::fix-run-namespace owner})
       owners
       fix-run-ids)))
    (db/transact!
     connection
     [{::plan-id plan-id
       ::plan-fix-runs (mapv #(vector ::fix-run-id %) fix-run-ids)}])))

(defn- plan-eid
  [database plan-id]
  (:db/id (db/pull database [:db/id] [::plan-id plan-id])))

(defn- fix-run-id
  [plan-id owner]
  (str plan-id "/" (name owner)))

(defn- successful-owners
  [database plan-id]
  (db/q
   '[:find [?owner ...]
     :in $ ?plan
     :where
     [?success :seon.flow.loop-test/success-plan ?plan]
     [?success :seon.flow.loop-test/success-namespace ?owner]]
   database
   (plan-eid database plan-id)))

(defn- owner-count
  [database plan-id]
  (db/q
   '[:find (count ?run) .
     :in $ ?plan
     :where
     [?run :seon.flow.loop-test/fix-run-plan ?plan]]
   database
   (plan-eid database plan-id)))

(defn- escalated?
  [database plan-id]
  (boolean
   (db/q
    '[:find ?escalation .
      :in $ ?plan
      :where
      [?escalation :seon.flow.loop-test/escalation-plan ?plan]]
    database
    (plan-eid database plan-id))))

(defn- admitted?
  [database plan-id]
  (= ::accepted
     (db/q
      '[:find ?verdict .
        :in $ ?plan
        :where
        [?plan :seon.flow.loop-test/plan-admission ?verdict]]
      database
      (plan-eid database plan-id))))

(defn- derived-status
  [database plan-id]
  (sut/lineage-status
   {::sut/owner-count (owner-count database plan-id)
    ::sut/successful-owners (set (successful-owners database plan-id))
    ::sut/escalated? (escalated? database plan-id)
    ::sut/admitted? (admitted? database plan-id)}))

(defn- attempt-count
  [database plan-id]
  (or
   (db/q
    '[:find (count ?attempt) .
      :in $ ?plan
      :where
      [?run :seon.flow.loop-test/fix-run-plan ?plan]
      [?attempt :seon.flow.loop-test/attempt-run ?run]]
    database
    (plan-eid database plan-id))
   0))

(defn- failure-count
  [database plan-id]
  (or
   (db/q
    '[:find (count ?attempt) .
      :in $ ?plan ?success
      :where
      [?run :seon.flow.loop-test/fix-run-plan ?plan]
      [?attempt :seon.flow.loop-test/attempt-run ?run]
      [?attempt :seon.flow.loop-test/attempt-outcome ?outcome]
      [(not= ?outcome ?success)]]
    database
    (plan-eid database plan-id)
    ::sut/fix-succeeds)
   0))

(defn- budget
  [database plan-id]
  (db/q
   '[:find [?turns ?failures]
     :in $ ?plan
     :where
     [?plan :seon.flow.loop-test/plan-config ?config]
     [?config :seon.flow.loop-test/max-turns ?turns]
     [?config :seon.flow.loop-test/max-failures ?failures]]
   database
   (plan-eid database plan-id)))

(defn- commit-attempt!
  [connection plan-id owner ordinal outcome]
  (db/transact!
   connection
   (cond->
    [{::attempt-id (str plan-id "/" (name owner) "/" ordinal)
      ::attempt-run [::fix-run-id (fix-run-id plan-id owner)]
      ::attempt-ordinal (long ordinal)
      ::attempt-outcome outcome
      ::attempt-fault-namespace owner}]
     (= outcome ::sut/fix-succeeds)
     (conj
      {::success-id (str plan-id "/" (name owner) "/success")
       ::success-plan [::plan-id plan-id]
       ::success-namespace owner}))))

(defn- commit-wake!
  [connection plan-id target source reason ordinal]
  (db/transact!
   connection
   [{::wake-id (str plan-id "/" (name target) "/" (name reason) "/" ordinal)
     ::wake-plan [::plan-id plan-id]
     ::wake-target target
     ::wake-source source
     ::wake-reason reason}]))

(defn- maybe-escalate!
  [connection plan-id stuck-owner]
  (let [database @connection
        [max-turns max-failures] (budget database plan-id)
        exhausted?
        (sut/escalate-lineage?
         {::sut/turn-count (attempt-count database plan-id)
          ::sut/failure-count (failure-count database plan-id)
          ::sut/max-turns max-turns
          ::sut/max-failures max-failures})]
    (when (and exhausted? (not (escalated? database plan-id)))
      (db/transact!
       connection
       [{::escalation-id (str plan-id "/escalation")
         ::escalation-plan [::plan-id plan-id]}
        {::wake-id (str plan-id "/planner/escalation")
         ::wake-plan [::plan-id plan-id]
         ::wake-target ::planner
         ::wake-source stuck-owner
         ::wake-reason ::escalation}]))
    exhausted?))

(defn- next-actions
  [database plan-id stuck-owner next-attempt]
  (if (escalated? database plan-id)
    [{::action ::wake-planner}]
    [{::action ::continue-owner
      ::owner stuck-owner
      ::attempt next-attempt}]))

(defn- self-wakes
  [database plan-id]
  (db/q
   '[:find ?target ?source
     :in $ ?plan
     :where
     [?wake :seon.flow.loop-test/wake-plan ?plan]
     [?wake :seon.flow.loop-test/wake-target ?target]
     [?wake :seon.flow.loop-test/wake-source ?source]
     [(= ?target ?source)]]
   database
   (plan-eid database plan-id)))

(defn- derived-fault-wakes
  [database plan-id]
  (let [plan (plan-eid database plan-id)
        open-owners
        (set/difference
         (set
          (db/q
           '[:find [?owner ...]
             :in $ ?plan
             :where
             [?run :seon.flow.loop-test/fix-run-plan ?plan]
             [?run :seon.flow.loop-test/fix-run-namespace ?owner]]
           database plan))
         (set (successful-owners database plan-id)))
        fault-routes
        (db/q
         '[:find ?source ?target
           :in $ ?plan ?success
           :where
           [?run :seon.flow.loop-test/fix-run-plan ?plan]
           [?run :seon.flow.loop-test/fix-run-namespace ?source]
           [?attempt :seon.flow.loop-test/attempt-run ?run]
           [?attempt :seon.flow.loop-test/attempt-outcome ?outcome]
           [(not= ?outcome ?success)]
           [?attempt
            :seon.flow.loop-test/attempt-fault-namespace ?target]]
         database plan ::sut/fix-succeeds)]
    (into
     #{}
     (keep
      (fn [[source target]]
        (when-not (and (= source target)
                       (contains? open-owners source))
          target)))
     fault-routes)))

(defn- planner-step!
  [connection admission-fn message]
  (let [plan-id (::plan-id message)
        verdict (admission-fn message)]
    (if (::accepted? verdict)
      (let [database @connection
            since-basis (::since-basis message)
            observed
            (if since-basis
              (set
               (db/q
                '[:find [?owner ...]
                  :in $ ?plan
                  :where
                  [?success :seon.flow.loop-test/success-plan ?plan]
                  [?success :seon.flow.loop-test/success-namespace ?owner]]
                (db/since database since-basis)
                (plan-eid database plan-id)))
              #{})]
        (db/transact!
         connection
         (cond->
          [{::plan-id plan-id
            ::plan-admission ::accepted}]
           since-basis
           (conj
            {::planner-observation-id
             (str plan-id "/planner-observation/"
                  (::planner-attempt message))
             ::planner-observation-plan [::plan-id plan-id]
             ::planner-observation-basis (long (:max-tx database))
             ::planner-observed-success observed})))
        {::accepted? true
         ::observed-success observed})
      (let [consumers (set (::consumers verdict))]
        (db/transact!
         connection
         [{::plan-id plan-id
           ::plan-admission ::rejected
           ::plan-choice-point ::accrete-or-adapt
           ::plan-consumers consumers}])
        {::accepted? false
         ::consumers consumers
         ::choice-point ::accrete-or-adapt}))))

(defn- create-loop-flow
  [connection owners admission-fn]
  (flow/create-flow
   {:procs
    (into
     {:planner
      {:proc
       (sut/planner-proc
        {::sut/plan-step-fn
         #(planner-step! connection admission-fn %)})}}
     (map
      (fn [owner]
        [owner
         {:proc
          (sut/namespace-owner-proc
           {::sut/fix-step-fn
            (fn [{::keys [plan-id attempt outcome]}]
              (commit-attempt!
               connection plan-id owner attempt outcome)
              outcome)})}])
      owners))
    :conns []}))

(defn- inject-and-report!
  [graph report-channel coordinate message]
  @(flow/inject graph coordinate [message])
  (test-support/await-event! report-channel coordinate))

(def ^:private property-seeds
  [7 17 41 73 101 211 307 401 509 997])

(defn- seeded-terminal
  [seed max-turns max-failures]
  (loop [turn-count 0
         failures 0
         successful-set #{}
         owner-ordinal 0]
    (let [status
          (sut/lineage-status
           {::sut/owner-count 3
            ::sut/successful-owners successful-set
            ::sut/escalated?
            (sut/escalate-lineage?
             {::sut/turn-count turn-count
              ::sut/failure-count failures
              ::sut/max-turns max-turns
              ::sut/max-failures max-failures})
            ::sut/admitted? true})]
      (if (contains? #{::sut/done ::sut/escalated} status)
        {::status status
         ::turn-count turn-count}
        (let [outcome
              (sut/seeded-outcome
               {::sut/seed seed
                ::sut/owner-ordinal owner-ordinal
                ::sut/attempt turn-count})
              success? (= outcome ::sut/fix-succeeds)]
          (recur
           (inc turn-count)
           (if success? failures (inc failures))
           (if success?
             (conj successful-set
                   (keyword (str "owner-" owner-ordinal)))
             successful-set)
           (mod (inc owner-ordinal) 3)))))))

(deftest seeded-lineages-terminate-within-fact-budgets
  (with-prototype-database
    (fn [connection]
      (let [plan-id (random-uuid)
            _ (create-lineage! connection plan-id
                               [:owner-0 :owner-1 :owner-2] 8 4)
            [max-turns max-failures] (budget @connection plan-id)
            check
            (tc/quick-check
             60
             (prop/for-all
              [seed (gen/elements property-seeds)]
              (let [{::keys [status turn-count]}
                    (seeded-terminal seed max-turns max-failures)]
                (and
                 (contains? #{::sut/done ::sut/escalated} status)
                 (<= turn-count max-turns))))
             :seed 20260726)]
        (test-support/assert-check!
         check
         "Seeded terminal property failed.")
        (is (= ::sut/iterating (derived-status @connection plan-id)))
        (db/transact!
         connection
         (into
          [{::plan-id plan-id
            ::plan-admission ::accepted}]
          (map
           (fn [owner]
             {::success-id (str plan-id "/" (name owner) "/success")
              ::success-plan [::plan-id plan-id]
              ::success-namespace owner})
           [:owner-0 :owner-1 :owner-2])))
        (is (= ::sut/done (derived-status @connection plan-id)))
        (is (= #{::sut/fix-succeeds ::sut/fix-fails
                 ::sut/fix-breaks-other-namespace ::sut/no-response}
               (into
                #{}
                (for [seed property-seeds
                      attempt (range 8)]
                  (sut/seeded-outcome
                   {::sut/seed seed
                    ::sut/owner-ordinal (mod attempt 3)
                    ::sut/attempt attempt})))))))))

(deftest seeded-namespace-owner-procs-return-reproducible-outcomes
  (let [seed 20260726
        owners [:owner-0 :owner-1 :owner-2]
        graph
        (flow/create-flow
         {:procs
          (into
           {}
           (map-indexed
            (fn [ordinal owner]
              [owner
               {:proc
                (sut/namespace-owner-proc
                 {::sut/fix-step-fn
                  (fn [{::keys [attempt]}]
                    (sut/seeded-outcome
                     {::sut/seed seed
                      ::sut/owner-ordinal ordinal
                      ::sut/attempt attempt}))})}])
            owners))
          :conns []})
        {:keys [report-chan]} (flow/start graph)]
    (try
      (flow/resume graph)
      (doseq [[ordinal owner] (map-indexed vector owners)
              attempt (range 3)]
        (let [report
              (inject-and-report!
               graph report-chan
               [owner ::sut/owner-step]
               {::attempt attempt})]
          (is (= (sut/seeded-outcome
                  {::sut/seed seed
                   ::sut/owner-ordinal ordinal
                   ::sut/attempt attempt})
                 (::sut/outcome report)))))
      (finally
        (flow/stop graph)))))

(deftest asymmetric-owner-escalation-replans-at-current-basis
  (with-prototype-database
    (fn [connection]
      (let [plan-id (random-uuid)
            owners [:owner-a :owner-b]
            _ (create-lineage! connection plan-id owners 10 2)
            graph
            (create-loop-flow
             connection owners
             (fn [_] {::accepted? true}))
            {:keys [report-chan]} (flow/start graph)]
        (try
          (flow/resume graph)
          (let [first-plan
                (inject-and-report!
                 graph report-chan
                 [:planner ::sut/planner-wake]
                 {::plan-id plan-id
                  ::planner-attempt 1})]
            (is (true? (get-in first-plan
                               [::sut/result ::accepted?]))))
          (doseq [owner owners]
            (commit-wake!
             connection plan-id owner ::planner ::initial-plan 0))
          (is (empty? (self-wakes @connection plan-id)))

          (let [before-a-success (:max-tx @connection)]
            (inject-and-report!
             graph report-chan
             [:owner-a ::sut/owner-step]
             {::plan-id plan-id
              ::attempt 1
              ::outcome ::sut/fix-succeeds})
            (doseq [ordinal [1 2]]
              (inject-and-report!
               graph report-chan
               [:owner-b ::sut/owner-step]
               {::plan-id plan-id
                ::attempt ordinal
                ::outcome ::sut/fix-fails})
              (maybe-escalate! connection plan-id :owner-b))

            (testing "escalation damps every later stuck-owner wake"
              (is (= ::sut/escalated
                     (derived-status @connection plan-id)))
              (is (= [{::action ::wake-planner}]
                     (next-actions @connection plan-id :owner-b 3)))
              (is (= 2
                     (db/q
                      '[:find (count ?attempt) .
                        :where
                        [?run :seon.flow.loop-test/fix-run-namespace
                         :owner-b]
                        [?attempt :seon.flow.loop-test/attempt-run ?run]]
                      @connection)))
              (is (empty? (self-wakes @connection plan-id)))
              (is (empty? (derived-fault-wakes
                           @connection plan-id)))
              (is (= 1
                     (db/q
                      '[:find (count ?wake) .
                        :in $ ?plan ?owner
                        :where
                        [?wake :seon.flow.loop-test/wake-plan ?plan]
                        [?wake :seon.flow.loop-test/wake-target ?owner]]
                      @connection
                      (plan-eid @connection plan-id)
                      :owner-b))
                  "only the initial planner wake reaches stuck owner B"))

            (let [second-plan
                  (inject-and-report!
                   graph report-chan
                   [:planner ::sut/planner-wake]
                   {::plan-id plan-id
                    ::planner-attempt 2
                    ::since-basis before-a-success})
                  since-successes
                  (set
                   (db/q
                    '[:find [?owner ...]
                      :in $ ?plan
                      :where
                      [?success :seon.flow.loop-test/success-plan ?plan]
                      [?success
                       :seon.flow.loop-test/success-namespace ?owner]]
                    (db/since @connection before-a-success)
                    (plan-eid @connection plan-id)))]
              (is (= #{:owner-a} since-successes))
              (is (= #{:owner-a}
                     (get-in second-plan
                             [::sut/result ::observed-success])))
              (is (= #{:owner-a}
                     (set
                      (db/q
                       '[:find [?owner ...]
                         :in $ ?plan
                         :where
                         [?observation
                          :seon.flow.loop-test/planner-observation-plan
                          ?plan]
                         [?observation
                          :seon.flow.loop-test/planner-observed-success
                          ?owner]]
                       @connection
                       (plan-eid @connection plan-id)))))))
          (finally
            (flow/stop graph)))))))

(deftest admission-rejection-is-a-value-and-records-the-choice-point
  (with-prototype-database
    (fn [connection]
      (let [plan-id (random-uuid)
            consumers #{:consumer.alpha :consumer.beta}
            _ (create-lineage! connection plan-id [:owner-a] 4 2)
            graph
            (create-loop-flow
             connection
             [:owner-a]
             (fn [_]
               {::accepted? false
                ::consumers consumers}))
            {:keys [report-chan error-chan]} (flow/start graph)]
        (try
          (flow/resume graph)
          (let [report
                (inject-and-report!
                 graph report-chan
                 [:planner ::sut/planner-wake]
                 {::plan-id plan-id
                  ::planner-attempt 1})
                result (::sut/result report)
                plan
                (db/pull
                 @connection
                 [::plan-admission ::plan-choice-point ::plan-consumers]
                 [::plan-id plan-id])]
            (is (false? (::accepted? result)))
            (is (= consumers (::consumers result)))
            (is (= ::accrete-or-adapt (::choice-point result)))
            (is (= ::rejected (::plan-admission plan)))
            (is (= ::accrete-or-adapt (::plan-choice-point plan)))
            (is (= consumers (set (::plan-consumers plan))))
            (is (nil? (async/poll! error-chan))
                "a fake admission rejection is an ordinary planner value"))
          (finally
            (flow/stop graph)))))))
