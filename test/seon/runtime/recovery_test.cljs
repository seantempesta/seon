(ns seon.runtime.recovery-test
  "Behavioral coverage for one-transaction unexpected-exit recovery."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.agent :as agent]
    [seon.agent.home :as home]
    [seon.agent.run :as run]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.process :as db.process]
    [seon.eval :as seval]
    [seon.repl :as repl]
    [seon.repl.internal :as repl.internal]
    [seon.runtime.recovery :as recovery]))

(def ^:private agent-a "recovra-260713")
(def ^:private agent-b "recovrb-260713")
(def ^:private agent-c "recovrc-260713")
(def ^:private agent-d "recovrd-260713")
(def ^:private eval-a "EVLrecoverA001")
(def ^:private eval-b "EVLrecoverB001")
(def ^:private eval-c "EVLrecoverC001")
(def ^:private eval-d "EVLrecoverD001")

(defn- with-conn
  [body]
  (-> (client/open-agent-conn!)
      (.then
        (fn [conn]
          (-> (d/transact!
                conn
                {:tx-data
                 (db/malli->datahike-schema [:seon.eval/status])})
              (.then
                (fn [_]
                  (let [previous db/*conn*]
                    (set! db/*conn* conn)
                    (-> (js/Promise.resolve (body conn))
                        (.finally
                          (fn [] (set! db/*conn* previous))))))))))))

(defn- eval-row
  [eval-id status]
  (cond->
    {:seon.eval/id eval-id
     :seon.eval/at (js/Date.)
     :seon.eval/source "(+ 1 2)"
     :seon.eval/narration "recovery fixture"
     :seon.eval/ns :my.agent.recovery
     :seon.eval/status status}
    (not= :running status)
    (assoc :seon.eval/ok? (= :done status))))

(defn- open-run!
  [agent-id]
  (run/open-run! {:seon.agent/id agent-id
                  :seon.agent.run/trigger :message}))

(defn- wait-for-running-eval
  [turn-id attempts]
  (let [eval-row (-> (db/pull
                       {:seon.db/pull-pattern
                        '[{:seon.agent.turn/evals [*]}]
                        :seon.db/ref [:seon.agent.turn/id turn-id]})
                     :seon.agent.turn/evals
                     first)]
    (cond
      (= :running (:seon.eval/status eval-row))
      (js/Promise.resolve eval-row)

      (pos? attempts)
      (js/Promise.
        (fn [resolve _reject]
          (js/setTimeout
            #(resolve (wait-for-running-eval turn-id (dec attempts)))
            10)))

      :else
      (js/Promise.reject
        (js/Error. (str "running eval receipt did not appear for " turn-id))))))

(defn- message-count
  [database]
  (or (db/query
        {:seon.db/db database
         :seon.db/query
         '[:find (count ?message) .
           :where [?message :seon.agent.message/id _]]})
      0))

(defn- recovery-transaction
  [database recovery-id]
  (db/query
    {:seon.db/db (db/history database)
     :seon.db/query
     '[:find ?transaction .
       :in $ ?recovery-id
       :where
       [?anchor :seon.runtime.recovery/id ?recovery-id ?transaction true]]
     :seon.db/args [recovery-id]}))

(defn- pointer-retraction-transaction
  [database agent-id run-id]
  (db/query
    {:seon.db/db (db/history database)
     :seon.db/query
     '[:find ?transaction .
       :in $ ?agent-id ?run-id
       :where
       [?agent :seon.agent/id ?agent-id _ true]
       [?run :seon.agent.run/id ?run-id _ true]
       [?agent :seon.agent/run ?run ?transaction false]]
     :seon.db/args [agent-id run-id]}))

(defn- run-close-transaction
  [database run-id]
  (db/query
    {:seon.db/db (db/history database)
     :seon.db/query
     '[:find ?transaction .
       :in $ ?run-id
       :where
       [?run :seon.agent.run/id ?run-id _ true]
       [?run :seon.agent.run/status :closed ?transaction true]]
     :seon.db/args [run-id]}))

(defn- turn-interruption-transaction
  [database turn-id]
  (db/query
    {:seon.db/db (db/history database)
     :seon.db/query
     '[:find ?transaction .
       :in $ ?turn-id
       :where
       [?turn :seon.agent.turn/id ?turn-id _ true]
       [?turn :seon.agent.turn/status :interrupted ?transaction true]]
     :seon.db/args [turn-id]}))

(defn- eval-interruption-transaction
  [database eval-id]
  (db/query
    {:seon.db/db (db/history database)
     :seon.db/query
     '[:find ?transaction .
       :in $ ?eval-id
       :where
       [?eval :seon.eval/id ?eval-id _ true]
       [?eval :seon.eval/status :interrupted ?transaction true]]
     :seon.db/args [eval-id]}))

(deftest recovery-schemas-compile-and-bound-the-optional-detail
  (is (m/validate :seon.runtime.recovery/detail "pod exited unexpectedly"))
  (is (not (m/validate :seon.runtime.recovery/detail
                       (apply str (repeat 2049 "x")))))
  (is (m/validate ::recovery/recover-request {}))
  (is (m/validate ::recovery/recover-request
                  {:seon.runtime.recovery/detail "signal 9"})))

(deftest recovery-commits-one-fenced-repair-and-derives-root-notices
  (async done
    (-> (with-conn
          (fn ^:async exercise [conn]
            (let [seed-result
                  (await
                    (db/transact!
                      {:seon.db/tx-data
                       (mapv (fn [id]
                               {:seon.agent/id id
                                :seon.eval/home-requires []})
                             [agent-a agent-b agent-c agent-d])}))]
              (is (true? (:seon.db/ok? seed-result)) "fixture agents transact")
              (when-not (:seon.db/ok? seed-result)
                (throw
                  (ex-info "fixture agent transaction failed"
                           {::fixture-result seed-result}))))
            (let [run-a (:seon.agent.run/id (await (open-run! agent-a)))
                  run-b (:seon.agent.run/id (await (open-run! agent-b)))
                  run-c (:seon.agent.run/id (await (open-run! agent-c)))
                  run-d (:seon.agent.run/id (await (open-run! agent-d)))
                  turn-a "TRNrecovera001"
                  turn-b "TRNrecoverb001"
                  turn-c "TRNrecoverc001"
                  turn-d "TRNrecoverd001"]
              (let [setup-result
                    (await
                      (db/transact!
                        {:seon.db/tx-data
                         [{:seon.agent.turn/id turn-a
                           :seon.agent.turn/at (js/Date.)
                           :seon.agent.turn/run [:seon.agent.run/id run-a]
                           :seon.agent.turn/status :running
                           :seon.agent.turn/evals
                           [(eval-row eval-a :running)]}
                          {:seon.agent.turn/id turn-b
                           :seon.agent.turn/at (js/Date.)
                           :seon.agent.turn/run [:seon.agent.run/id run-b]
                           :seon.agent.turn/status :done
                           :seon.agent.turn/evals [(eval-row eval-b :done)]}
                          {:seon.agent.turn/id turn-c
                           :seon.agent.turn/at (js/Date.)
                           :seon.agent.turn/run [:seon.agent.run/id run-c]
                           :seon.agent.turn/status :running
                           :seon.agent.turn/evals
                           [(eval-row eval-c :running)]}
                          {:seon.agent.turn/id turn-d
                           :seon.agent.turn/at (js/Date.)
                           :seon.agent.turn/run [:seon.agent.run/id run-d]
                           :seon.agent.turn/status :running
                           :seon.agent.turn/evals
                           [(eval-row eval-d :running)]}
                          ;; A closed run with a stale current pointer is
                          ;; repaired, but not re-closed as a crash.
                          {:seon.agent.run/id run-d
                           :seon.agent.run/status :closed
                           :seon.agent.run/closed-reason :completed
                           :seon.agent.run/closed-at (js/Date.)}
                          ;; Terminated ownership is deliberately untouched.
                          {:seon.agent/id agent-c
                           :seon.agent/terminated-at (js/Date.)}]}))]
                (is (true? (:seon.db/ok? setup-result))
                    "fixture run state transacts")
                (when-not (:seon.db/ok? setup-result)
                  (throw
                    (ex-info "fixture run-state transaction failed"
                             {::fixture-result setup-result}))))
              (let [messages-before (message-count @conn)
                    result
                    (await
                      (db/with-tx-context
                        {:seon.db/user [:seon.agent/id "root"]
                         :seon.db/process
                         (db.process/lookup-ref :seon.db.process/boot)}
                        (fn []
                          (recovery/recover!
                            {:seon.runtime.recovery/detail "cold restart"}))))
                    recovery-id (:seon.runtime.recovery/id result)
                    database @conn
                    transaction (recovery-transaction database recovery-id)]
                (testing "all repairs and the anchor are one root/boot transaction"
                  (is (true? (::recovery/repaired? result)))
                  (is (= [agent-a agent-b agent-d]
                         (::recovery/agent-ids result)))
                  (is (= #{run-a run-b run-d} (set (::recovery/run-ids result))))
                  (is (= [turn-a turn-d] (::recovery/turn-ids result)))
                  (is (= [eval-a eval-d] (::recovery/eval-ids result)))
                  (is (int? transaction))
                  (is (every?
                        #{transaction}
                        [(pointer-retraction-transaction database agent-a run-a)
                         (pointer-retraction-transaction database agent-b run-b)
                         (pointer-retraction-transaction database agent-d run-d)
                         (run-close-transaction database run-a)
                         (run-close-transaction database run-b)
                         (turn-interruption-transaction database turn-a)
                         (turn-interruption-transaction database turn-d)
                         (eval-interruption-transaction database eval-a)
                         (eval-interruption-transaction database eval-d)]))
                  (let [tx-entity (db/entity
                                    {:seon.db/db database
                                     :seon.db/ref transaction})]
                    (is (= "root" (get-in tx-entity
                                           [:seon.db/user :seon.agent/id])))
                    (is (= :seon.db.process/boot
                           (get-in tx-entity
                                   [:seon.db/process :seon.db.process/id])))))
                (testing "affected live agents are idle without fabricated messages"
                  (doseq [id [agent-a agent-b agent-d]]
                    (is (= :idle
                           (:seon.agent/state
                             (agent/derive-status {:seon.agent/id id})))))
                  (is (= :terminated
                         (:seon.agent/state
                           (agent/derive-status {:seon.agent/id agent-c}))))
                  (is (= run-c
                         (:seon.agent.run/id
                           (run/current-run {:seon.agent/id agent-c}))))
                  (is (= :running
                         (:seon.agent.turn/status
                             (db/entity
                             {:seon.db/ref [:seon.agent.turn/id turn-c]}))))
                  (is (= :running
                         (:seon.eval/status
                           (db/entity
                             {:seon.db/ref [:seon.eval/id eval-c]}))))
                  (is (= :done
                         (:seon.eval/status
                           (db/entity
                             {:seon.db/ref [:seon.eval/id eval-b]}))))
                  (doseq [eval-id [eval-a eval-d]]
                    (let [eval-entity
                          (db/entity
                            {:seon.db/ref [:seon.eval/id eval-id]})]
                      (is (= :interrupted (:seon.eval/status eval-entity)))
                      (is (false? (:seon.eval/ok? eval-entity)))))
                  (is (empty?
                        (db/query
                          {:seon.db/db database
                           :seon.db/query
                           '[:find ?eval-id
                             :where
                             [?run :seon.agent.run/status :closed]
                             [?turn :seon.agent.turn/run ?run]
                             [?turn :seon.agent.turn/status :interrupted]
                             [?turn :seon.agent.turn/evals ?eval]
                             [?eval :seon.eval/id ?eval-id]
                             [?eval :seon.eval/status :running]]})))
                  (is (= messages-before (message-count database))))
                (testing "an immediate second pass writes no duplicate anchor"
                  (let [before-second (db/basis-t @conn)
                        second-result (await (recovery/recover! {}))
                        anchors (db/query
                                  {:seon.db/db @conn
                                   :seon.db/query
                                   '[:find [?id ...]
                                     :where
                                     [_ :seon.runtime.recovery/id ?id]]})]
                    (is (false? (::recovery/repaired? second-result)))
                    (is (= [] (::recovery/eval-ids second-result)))
                    (is (= before-second (db/basis-t @conn)))
                    (is (= [recovery-id] anchors))))
                (testing "the root notice is derived and shrinks after later runs"
                  (let [notices (recovery/pending-notices {:seon.db/db database})]
                    (is (= 1 (count notices)))
                    (is (= #{agent-a agent-b agent-d}
                           (set (::recovery/agents (first notices)))))
                    (is (= #{run-a run-b run-d}
                           (set (::recovery/runs (first notices)))))
                    (is (= [turn-a turn-d]
                           (::recovery/turns (first notices)))))
                  (await (open-run! agent-a))
                  (is (= #{agent-b agent-d}
                         (set (::recovery/agents
                                (first
                                  (recovery/pending-notices
                                    {:seon.db/db @conn}))))))
                  (await (open-run! agent-b))
                  (await (open-run! agent-d))
                  (is (= [] (recovery/pending-notices {:seon.db/db @conn}))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "threw — " error))
                  (done))))))

(deftest real-eval-receipt-is-interrupted-before-late-completion
  (async done
    (-> (with-conn
          (fn ^:async exercise [conn]
            (let [agent-envelope
                  (await
                    (db.id/allocate!
                      {::db.id/allocations
                       [{::db.id/key ::fixture-agent
                         ::db.id/identity-attr :seon.agent/id}]
                       ::db.id/transaction-builder
                       (fn [{agent-id ::fixture-agent}]
                         {:seon.db/tx-data
                          [{:seon.agent/id agent-id
                            :seon.eval/home-requires []}]})
                       :seon.db/conn conn}))
                  _ (is (true? (:seon.db/ok? agent-envelope)))
                  agent-id (get-in agent-envelope
                                   [::db.id/ids ::fixture-agent])
                  run-id (:seon.agent.run/id (await (open-run! agent-id)))
                  turn-envelope
                  (await
                    (db.id/allocate!
                      {::db.id/allocations
                       [{::db.id/key ::fixture-turn
                         ::db.id/identity-attr :seon.agent.turn/id}]
                       ::db.id/transaction-builder
                       (fn [{turn-id ::fixture-turn}]
                         {:seon.db/tx-data
                          [{:seon.agent.turn/id turn-id
                            :seon.agent.turn/at (js/Date.)
                            :seon.agent.turn/run [:seon.agent.run/id run-id]
                            :seon.agent.turn/status :running}]})
                       :seon.db/conn conn}))
                  _ (is (true? (:seon.db/ok? turn-envelope)))
                  turn-id (get-in turn-envelope [::db.id/ids ::fixture-turn])
                  compile-state (await (repl/ensure-bootstrap!))
                  agent-ns (home/home-ns agent-id)
                  _ (await (seval/setup-agent-ns!
                             compile-state agent-ns agent-id))
                  ;; The form settles later, but its running receipt must be
                  ;; visible before the Promise does. Recovery wins the one
                  ;; terminal CAS while this eval-batch! invocation is alive.
                  batch-promise
                  (db/with-agent
                    agent-id
                    #(seval/eval-batch!
                       compile-state
                       (repl.internal/parse-forms
                         (str "(seon.eval/budget 1000 "
                              "(js/Promise. (fn [resolve _] "
                              "(js/setTimeout #(resolve 42) 250))))"))
                       agent-ns agent-id turn-id run-id))
                  running (await (wait-for-running-eval turn-id 100))
                  eval-id (:seon.eval/id running)
                  recovered (await
                              (recovery/recover!
                                {:seon.runtime.recovery/detail
                                 "focused late-completion falsifier"}))
                  batch (await batch-promise)
                  final-row (db/entity
                              {:seon.db/ref [:seon.eval/id eval-id]})
                  eval-ids (db/query
                             {:seon.db/db @conn
                              :seon.db/query
                              '[:find [?id ...]
                                :where [_ :seon.eval/id ?id]]})]
              (is (= [eval-id] (::recovery/eval-ids recovered)))
              (is (= :interrupted (:seon.eval/status final-row)))
              (is (false? (:seon.eval/ok? final-row)))
              (is (not (contains? final-row :seon.eval/result-edn)))
              (is (= [eval-id] eval-ids)
                  "late completion neither overwrites nor allocates another row")
              (is (empty? (:seon.eval/ids batch))
                  "the losing terminal CAS is returned as a database error"))))
        (.then (fn [_] (done)))
        (.catch
          (fn [error]
            (is false (str "threw — " error))
            (done))))))
