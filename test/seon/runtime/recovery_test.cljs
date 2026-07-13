(ns seon.runtime.recovery-test
  "Behavioral coverage for one-transaction unexpected-exit recovery."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [malli.core :as m]
    [seon.agent :as agent]
    [seon.agent.run :as run]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.process :as db.process]
    [seon.runtime.recovery :as recovery]))

(def ^:private agent-a "recovra-260713")
(def ^:private agent-b "recovrb-260713")
(def ^:private agent-c "recovrc-260713")
(def ^:private agent-d "recovrd-260713")

(defn- with-conn
  [body]
  (-> (client/open-agent-conn!)
      (.then
        (fn [conn]
          (let [previous db/*conn*]
            (set! db/*conn* conn)
            (-> (js/Promise.resolve (body conn))
                (.finally (fn [] (set! db/*conn* previous)))))))))

(defn- open-run!
  [agent-id]
  (run/open-run! {:seon.agent/id agent-id
                  :seon.agent.run/trigger :message}))

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
                     :seon.agent.turn/status :running}
                    {:seon.agent.turn/id turn-b
                     :seon.agent.turn/at (js/Date.)
                     :seon.agent.turn/run [:seon.agent.run/id run-b]
                     :seon.agent.turn/status :done}
                    {:seon.agent.turn/id turn-c
                     :seon.agent.turn/at (js/Date.)
                     :seon.agent.turn/run [:seon.agent.run/id run-c]
                     :seon.agent.turn/status :running}
                    {:seon.agent.turn/id turn-d
                     :seon.agent.turn/at (js/Date.)
                     :seon.agent.turn/run [:seon.agent.run/id run-d]
                     :seon.agent.turn/status :running}
                    ;; A closed run with a stale current pointer is repaired,
                    ;; but not re-closed as a crash.
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
                  (is (int? transaction))
                  (is (every?
                        #{transaction}
                        [(pointer-retraction-transaction database agent-a run-a)
                         (pointer-retraction-transaction database agent-b run-b)
                         (pointer-retraction-transaction database agent-d run-d)
                         (run-close-transaction database run-a)
                         (run-close-transaction database run-b)
                         (turn-interruption-transaction database turn-a)
                         (turn-interruption-transaction database turn-d)]))
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
                  (is (= messages-before (message-count database))))
                (testing "an immediate second pass writes no duplicate anchor"
                  (let [second-result (await (recovery/recover! {}))
                        anchors (db/query
                                  {:seon.db/db @conn
                                   :seon.db/query
                                   '[:find [?id ...]
                                     :where
                                     [_ :seon.runtime.recovery/id ?id]]})]
                    (is (false? (::recovery/repaired? second-result)))
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
