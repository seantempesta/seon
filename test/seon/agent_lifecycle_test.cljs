(ns seon.agent-lifecycle-test
  "Focused proof for lifecycle operations over the database authority facade."
  (:require
   [cljs.test :refer [async deftest is]]
   [seon.agent.lifecycle :as lifecycle]
   [seon.agent.run :as run]
   [seon.db :as db]
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
   :seon.agent.run/claim-epoch 7
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
                (is (= [:db.fn/cas [:seon.agent.run/id "run-a"]
                        :seon.agent.run/claim-epoch 7 7]
                       (second transaction-data)))
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

(deftest complete-returns-a-terminal-value-without-database-or-leaves
  (let [first-value (lifecycle/complete "done")
        second-value (lifecycle/complete "done" 99)]
    (is (= {:seon.agent.lifecycle/terminal :completed
            :seon.agent.lifecycle/result "done"}
           first-value))
    (is (= {:seon.agent.lifecycle/terminal :completed
            :seon.agent.lifecycle/result "done"
            :seon.agent.lifecycle/result-ref 99}
           second-value))
    (is (lifecycle/terminal-value? first-value))
    (is (lifecycle/terminal-value? second-value))
    (is (not (lifecycle/terminal-value? :idle)))))

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
                (is (not (contains? @transaction ::db/expected-db))
                    "the transaction request carries its one database value")
                (is (= :seon.agent/terminated-at (nth (first tx) 2)))
                (is (= [:db.fn/retractAttribute
                        [:seon.agent/id "agent-a"]
                        :seon.agent/namespace]
                       (second tx)))
                (is (= :seon.agent/run (nth (nth tx 2) 2)))
                (is (= [:db.fn/cas [:seon.agent.run/id "run-a"]
                        :seon.agent.run/claim-epoch 7 7]
                       (nth tx 3)))
                (is (= :closed (:seon.agent.run/status (nth tx 4))))
                (is (= :db/retract (first (last tx))))))))
       done
       [[#(set! db/current-agent-id %) current-agent-id]
        [#(set! db/db %) db!]
        [#(set! db/pull %) pull]
        [#(set! run/current-run %) current]
        [#(set! db/transact! %) transact!]]))))
