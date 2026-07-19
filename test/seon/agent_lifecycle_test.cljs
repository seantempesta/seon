(ns seon.agent-lifecycle-test
  "Focused proof for lifecycle operations over the database authority facade."
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.agent.lifecycle :as lifecycle]
   [seon.agent.message :as message]
   [seon.agent.run :as run]
   [seon.db :as db]
   [seon.db.id :as db.id]
   [seon.db.protocol :as protocol]
   [seon.runtime.admission :as admission]))

(def database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(def database-after
  (assoc database :t 43
         :datahike/commit-id
         #uuid "cccccccc-cccc-4ccc-8ccc-cccccccccccc"))

(def native-report
  {:db-before database
   :db-after database-after
   :tx-data []
   :tempids {}
   :tx-meta {}})

(def current-run
  {:seon.agent.run/id "run-a"
   :seon.agent.run/status :open
   :seon.agent.run/started-at (js/Date. 1000)})

(defn- finish!
  [promise done restorations]
  (-> promise
      (.catch
       (fn [error]
         (is false (str "unexpected rejection: " error))))
      (.finally
       (fn []
         (doseq [[restore value] restorations]
           (restore value))
         (done)))))

(deftest missing-agent-scope-is-a-direct-error
  (async done
    (let [current-agent-id db/current-agent-id]
      (set! db/current-agent-id (constantly nil))
      (finish!
       (-> (lifecycle/wait "park")
           (.then
            (fn [result]
              (is (string? (:seon.error/message result)))
              (is (not (contains? result :seon.db/ok?)))
              (is (not (contains? result :seon.db/error))))))
       done
       [[#(set! db/current-agent-id %) current-agent-id]]))))

(deftest wait-uses-one-database-value-and-native-report
  (async done
    (let [current-agent-id db/current-agent-id
          db! db/db
          current run/current-run
          transact! db/transact!
          requests (atom [])]
      (set! db/current-agent-id (constantly "agent-a"))
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! run/current-run
            (fn [request]
              (swap! requests conj [:current request])
              (js/Promise.resolve current-run)))
      (set! db/transact!
            (fn [& call-args]
              (swap! requests conj [:transact (first call-args)])
              (js/Promise.resolve native-report)))
      (finish!
       (-> (lifecycle/wait "park")
           (.then
            (fn [result]
              (is (= :idle result))
              (let [[[_ current-request] [_ transaction-request]] @requests
                    transaction-data (::db/tx-data transaction-request)]
                (is (identical? database (::db/db current-request)))
                (is (identical? database (::db/db transaction-request)))
                (is (= :db.fn/cas (ffirst transaction-data)))
                (is (= :db/retract (first (last transaction-data))))))))
       done
       [[#(set! db/current-agent-id %) current-agent-id]
        [#(set! db/db %) db!]
        [#(set! run/current-run %) current]
        [#(set! db/transact! %) transact!]]))))

(deftest pause-and-resume-pass-the-acquired-database-to-run-owner
  (async done
    (let [current-agent-id db/current-agent-id
          db! db/db
          pull db/pull
          current run/current-run
          pause! run/pause!
          resume! run/resume!
          available? admission/available?
          calls (atom [])]
      (set! db/current-agent-id (constantly "root"))
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/pull
            (fn
              ([request]
               (swap! calls conj [:target request])
               (js/Promise.resolve {:seon.agent/id "agent-a"}))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! run/current-run
            (fn [request]
              (swap! calls conj [:current request])
              (js/Promise.resolve current-run)))
      (set! run/pause!
            (fn [request]
              (swap! calls conj [:pause request])
              (js/Promise.resolve native-report)))
      (set! run/resume!
            (fn [request]
              (swap! calls conj [:resume request])
              (js/Promise.resolve native-report)))
      (set! admission/available? (constantly true))
      (finish!
       (-> (lifecycle/pause {:seon.agent/id "agent-a"})
           (.then
            (fn [paused]
              (is (= :paused paused))
              (lifecycle/resume {:seon.agent/id "agent-a"})))
           (.then
            (fn [resumed]
              (is (= :running resumed))
              (is (every?
                   #(identical? database (::db/db (second %)))
                   @calls)))))
       done
       [[#(set! db/current-agent-id %) current-agent-id]
        [#(set! db/db %) db!]
        [#(set! db/pull %) pull]
        [#(set! run/current-run %) current]
        [#(set! run/pause! %) pause!]
        [#(set! run/resume! %) resume!]
        [#(set! admission/available? %) available?]]))))

(deftest complete-commits-result-message-close-and-pointer-retract-once
  (async done
    (let [current-agent-id db/current-agent-id
          db! db/db
          current run/current-run
          pull db/pull
          query db/query
          message-transaction message/message-transaction-for
          allocate! db.id/allocate!
          message! message/message!
          allocation (atom nil)
          attempts (atom 0)
          standalone-message-calls (atom 0)]
      (set! db/current-agent-id (constantly "agent-a"))
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! run/current-run
            (fn [request]
              (is (identical? database (::db/db request)))
              (js/Promise.resolve current-run)))
      (set! db/pull
            (fn
              ([request]
               (if (= [:seon.agent/id "agent-a"] (::db/ref request))
                 (js/Promise.resolve
                  {:db/id 10
                   :seon.agent/id "agent-a"
                   :seon.agent/parent {:db/id 11 :seon.agent/id "root"}
                   :seon.agent.testrun/_agent []})
                 (js/Promise.resolve nil)))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! db/query
            (fn
              ([_] (js/Promise.resolve []))
              ([_ & _] (js/Promise.reject (js/Error. "unexpected query arity")))))
      (set! message/message-transaction-for
            (fn [db-value request]
              (is (identical? database db-value))
              (is (= [[:seon.agent/id "root"]]
                     (:seon.agent.message/to request)))
              (js/Promise.resolve
               {:seon.agent.message/allocations
                [{::db.id/key :seon.agent.message/id
                  ::db.id/identity-attr :seon.agent.message/id}]
                :seon.agent.message/hops 1
                :seon.agent.message/transaction-builder
                (fn [ids]
                  {:seon.db/tx-data
                   [{:seon.agent.message/id
                     (get ids :seon.agent.message/id)
                     :seon.agent.message/content "done"}]})})))
      (set! db.id/allocate!
            (fn [request]
              (reset! allocation request)
              (let [attempt (swap! attempts inc)
                    built
                    ((::db.id/transaction-builder request)
                     {:seon.agent.message/id "message-a"})]
                (if (< attempt 3)
                  (js/Promise.resolve
                   {:seon.error/message "The database changed before commit."
                    :seon.error/data
                    {::protocol/error-kind
                     protocol/stale-database-value-error}})
                  (js/Promise.resolve
                   (assoc native-report
                          :tx-data (::db/tx-data built)
                          ::db.id/ids
                          {:seon.agent.message/id "message-a"}))))))
      (set! message/message!
            (fn [_]
              (swap! standalone-message-calls inc)
              (js/Promise.resolve {:seon.error/message "forbidden"})))
      (finish!
       (-> (lifecycle/complete "done" 99)
           (.then
            (fn [result]
              (is (= :idle result))
              (is (= 3 @attempts)
                  "completion reacquires through multiple unrelated writes")
              (is (zero? @standalone-message-calls))
              (let [built
                    ((::db.id/transaction-builder @allocation)
                     {:seon.agent.message/id "message-a"})
                    tx (::db/tx-data built)]
                (is (identical? database (::db/db @allocation)))
                (is (identical? database (::db/expected-db built)))
                (is (= :db.fn/cas (ffirst tx)))
                (is (= "done" (:seon.agent.run/result (second tx))))
                (is (= 99 (:seon.agent.run/result-ref (second tx))))
                (is (= "message-a" (:seon.agent.message/id (nth tx 2))))
                (is (= :closed (:seon.agent.run/status (nth tx 3))))
                (is (= :db/retract (first (last tx))))))))
       done
       [[#(set! db/current-agent-id %) current-agent-id]
        [#(set! db/db %) db!]
        [#(set! run/current-run %) current]
        [#(set! db/pull %) pull]
        [#(set! db/query %) query]
        [#(set! message/message-transaction-for %) message-transaction]
        [#(set! db.id/allocate! %) allocate!]
        [#(set! message/message! %) message!]]))))

(deftest red-latest-test-refuses-completion-before-any-write
  (async done
    (let [current-agent-id db/current-agent-id
          db! db/db
          current run/current-run
          pull db/pull
          query db/query
          transact! db/transact!
          allocate! db.id/allocate!
          writes (atom 0)]
      (set! db/current-agent-id (constantly "agent-a"))
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! run/current-run (fn [_] (js/Promise.resolve current-run)))
      (set! db/pull
            (fn
              ([request]
               (if (= [:seon.agent/id "agent-a"] (::db/ref request))
                 (js/Promise.resolve
                  {:db/id 10
                   :seon.agent/id "agent-a"
                   :seon.agent/parent {:db/id 11 :seon.agent/id "root"}
                   :seon.agent.testrun/_agent
                   [{:db/id 30
                     :seon.agent.testrun/passed 3
                     :seon.agent.testrun/failed 1
                     :seon.agent.testrun/errors 0}]})
                 (js/Promise.resolve nil)))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! db/query
            (fn
              ([_] (js/Promise.resolve []))
              ([_ & _] (js/Promise.reject (js/Error. "unexpected query arity")))))
      (set! db/transact!
            (fn [& _]
              (swap! writes inc)
              (js/Promise.resolve native-report)))
      (set! db.id/allocate!
            (fn [_]
              (swap! writes inc)
              (js/Promise.resolve native-report)))
      (finish!
       (-> (lifecycle/complete "done")
           (.then
            (fn [result]
              (is (re-find #"latest test run is RED"
                           (:seon.error/message result)))
              (is (zero? @writes)))))
       done
       [[#(set! db/current-agent-id %) current-agent-id]
        [#(set! db/db %) db!]
        [#(set! run/current-run %) current]
        [#(set! db/pull %) pull]
        [#(set! db/query %) query]
        [#(set! db/transact! %) transact!]
        [#(set! db.id/allocate! %) allocate!]]))))

(deftest terminate-closes-the-observed-run-in-the-termination-transaction
  (async done
    (let [current-agent-id db/current-agent-id
          db! db/db
          pull db/pull
          current run/current-run
          transact! db/transact!
          transaction (atom nil)]
      (set! db/current-agent-id (constantly "root"))
      (set! db/db (fn ([] (js/Promise.resolve database))
                    ([_] (js/Promise.resolve database))))
      (set! db/pull
            (fn
              ([_] (js/Promise.resolve {:seon.agent/id "agent-a"}))
              ([_ _] (js/Promise.reject (js/Error. "unexpected pull arity")))
              ([_ _ _] (js/Promise.reject (js/Error. "unexpected pull arity")))))
      (set! run/current-run
            (fn [request]
              (is (identical? database (::db/db request)))
              (js/Promise.resolve current-run)))
      (set! db/transact!
            (fn [& call-args]
              (reset! transaction (first call-args))
              (js/Promise.resolve native-report)))
      (finish!
       (-> (lifecycle/terminate "agent-a")
           (.then
            (fn [result]
              (is (= :terminated result))
              (let [tx (::db/tx-data @transaction)]
                (is (identical? database (::db/db @transaction)))
                (is (identical? database (::db/expected-db @transaction)))
                (is (= :seon.agent/terminated-at (nth (first tx) 2)))
                (is (= [:db.fn/retractAttribute
                        [:seon.agent/id "agent-a"]
                        :seon.agent/namespace]
                       (second tx)))
                (is (= :seon.agent/run (nth (nth tx 2) 2)))
                (is (= :closed (:seon.agent.run/status (nth tx 3))))
                (is (= :db/retract (first (last tx))))))))
       done
       [[#(set! db/current-agent-id %) current-agent-id]
        [#(set! db/db %) db!]
        [#(set! db/pull %) pull]
        [#(set! run/current-run %) current]
        [#(set! db/transact! %) transact!]]))))
