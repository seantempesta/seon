(ns seon.eval.receipt-test
  "Pure contract tests for the parent-owned eval receipt mechanics."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.eval.internal :as receipt]))

(def ^:private start
  {:seon.agent.turn/id "TRNreceipt0001"
   :seon.eval/id "EVLreceipt0001"
   :seon.eval/at (js/Date. 1000)
   :seon.eval/source "(+ 1 2)"
   :seon.eval/narration "check arithmetic"
   :seon.eval/ns :my.agent.receipt
   :seon.eval/agent [:seon.agent/id "AGTreceipt0001"]})

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

(deftest receipt-schemas-are-closed-and-terminal-states-are-bounded
  (is (m/validate ::receipt/start-request start))
  (is (not (m/validate ::receipt/start-request
                       (assoc start :seon.db/user
                              [:seon.agent/id "forged"]))))
  (is (m/validate ::receipt/terminal-request
                  {:seon.eval/id "EVLreceipt0001"
                   :seon.eval/status :interrupted}))
  (is (not (m/validate ::receipt/terminal-request
                       {:seon.eval/id "EVLreceipt0001"
                        :seon.eval/status :running}))))

(deftest start-data-is-one-running-component-with-no-terminal-projection
  (let [tx-data (receipt/start-tx-data start)
        eval-row (-> tx-data first :seon.agent.turn/evals first)]
    (is (= 1 (count tx-data)))
    (is (= "TRNreceipt0001" (:seon.agent.turn/id (first tx-data))))
    (is (= :running (:seon.eval/status eval-row)))
    (is (= [:seon.agent/id "AGTreceipt0001"]
           (:seon.eval/agent eval-row)))
    (is (not (contains? eval-row :seon.eval/ok?)))))

(deftest terminal-data-leads-with-running-cas-and-derives-ok
  (testing "success"
    (is (= [[:db.fn/cas [:seon.eval/id "EVLreceipt0001"]
             :seon.eval/status :running :running]
            {:seon.eval/id "EVLreceipt0001"
             :seon.eval/status :done
             :seon.eval/ok? true}]
           (receipt/terminal-tx-data
             {:seon.eval/id "EVLreceipt0001"
              :seon.eval/status :done}))))
  (testing "interruption"
    (is (= false
           (:seon.eval/ok?
             (second
               (receipt/terminal-tx-data
                 {:seon.eval/id "EVLreceipt0001"
                  :seon.eval/status :interrupted})))))))

(deftest receipt-state-derives-historical-terminal-rows
  (is (= :running (receipt/receipt-state {:seon.eval/status :running})))
  (is (= :done (receipt/receipt-state {:seon.eval/ok? true})))
  (is (= :error (receipt/receipt-state {:seon.eval/ok? false})))
  (is (= :absent (receipt/receipt-state {}))))

(deftest one-terminal-transition-commits-and-late-transitions-are-refused
  (async done
    (-> (with-conn
          (fn ^:async exercise [conn]
            (let [seed
                  (await
                    (db/transact!
                      {:seon.db/tx-data
                       [{:seon.agent/id "AGTreceipt0001"}
                        {:seon.agent.turn/id "TRNreceipt0001"
                         :seon.agent.turn/at (js/Date. 500)
                         :seon.agent.turn/status :running}]}))]
              (is (true? (:seon.db/ok? seed))))
            (let [started
                  (await
                    (db/transact!
                      {:seon.db/tx-data (receipt/start-tx-data start)}))
                  turn
                  (db/pull
                    {:seon.db/db @conn
                     :seon.db/pull-pattern
                     '[:seon.agent.turn/id
                       {:seon.agent.turn/evals [*]}]
                     :seon.db/ref
                     [:seon.agent.turn/id "TRNreceipt0001"]})
                  eval-row (-> turn :seon.agent.turn/evals first)]
              (is (true? (:seon.db/ok? started)))
              (is (= "EVLreceipt0001" (:seon.eval/id eval-row)))
              (is (= :running (:seon.eval/status eval-row)))
              (is (not (contains? eval-row :seon.eval/ok?))))
            (let [terminal
                  (await
                    (db/transact!
                      {:seon.db/tx-data
                       (receipt/terminal-tx-data
                         {:seon.eval/id "EVLreceipt0001"
                          :seon.eval/status :done})}))
                  database @conn
                  eval-row
                  (db/entity
                    {:seon.db/db database
                     :seon.db/ref [:seon.eval/id "EVLreceipt0001"]})
                  terminal-t (:seon.db/tx terminal)]
              (is (true? (:seon.db/ok? terminal)))
              (is (= :done (:seon.eval/status eval-row)))
              (is (true? (:seon.eval/ok? eval-row)))
              (is (= terminal-t
                     (db/query
                       {:seon.db/db (db/history database)
                        :seon.db/query
                        '[:find ?tx .
                          :in $ ?eval-id
                          :where
                          [?eval :seon.eval/id ?eval-id _ true]
                          [?eval :seon.eval/status :done ?tx true]]
                        :seon.db/args ["EVLreceipt0001"]}))))
            (let [before (db/basis-t @conn)
                  duplicate
                  (await
                    (db/transact!
                      {:seon.db/tx-data
                       (receipt/terminal-tx-data
                         {:seon.eval/id "EVLreceipt0001"
                          :seon.eval/status :done})}))
                  late
                  (await
                    (db/transact!
                      {:seon.db/tx-data
                       (receipt/terminal-tx-data
                         {:seon.eval/id "EVLreceipt0001"
                          :seon.eval/status :interrupted})}))]
              (is (false? (:seon.db/ok? duplicate)))
              (is (false? (:seon.db/ok? late)))
              (is (= before (db/basis-t @conn)))
              (is (= :done
                     (:seon.eval/status
                       (db/entity
                         {:seon.db/ref
                          [:seon.eval/id "EVLreceipt0001"]})))))))
        (.then (fn [_] (done)))
        (.catch
          (fn [error]
            (is false (str "threw — " error))
            (done))))))

(deftest interrupted-receipt-makes-late-recorder-settle-without-fallback
  (async done
    (-> (with-conn
          (fn ^:async exercise [_conn]
            (let [seeded
                  (await
                    (db/transact!
                      {:seon.db/tx-data
                       [{:seon.agent/id "AGTreceipt0001"}
                        {:seon.agent.turn/id "TRNreceipt0001"
                         :seon.agent.turn/at (js/Date.)
                         :seon.agent.turn/status :running}]}))
                  _ (is (true? (:seon.db/ok? seeded)))
                  started
                  (await
                    (db/with-agent
                      "AGTreceipt0001"
                      #(seval/start-eval!
                         {:seon.agent.turn/id-of-turn "TRNreceipt0001"
                          ::seval/at (js/Date.)
                          ::seval/narration "late completion"
                          ::seval/source "42"
                          ::seval/starting-ns 'my.agent.receipt})))
                  eval-id (:seon.eval/id started)
                  interrupted
                  (await
                    (db/transact!
                      {:seon.db/tx-data
                       (receipt/terminal-tx-data
                         {:seon.eval/id eval-id
                          :seon.eval/status :interrupted})}))
                  _ (is (true? (:seon.db/ok? interrupted)))
                  late
                  (await
                    (seval/record-eval!
                      {:seon.agent.turn/id-of-turn "TRNreceipt0001"
                       ::seval/eval-id eval-id
                       ::seval/at (js/Date.)
                       ::seval/duration-ms 1
                       ::seval/narration "late completion"
                       ::seval/source "42"
                       ::seval/ending-ns 'my.agent.receipt
                       ::seval/result {::seval/ok? true ::seval/value 42}
                       ;; A nonempty tee would enter transcript fallback on an
                       ;; ordinary write failure. A settled competing terminal
                       ;; must short-circuit before that retry path.
                       ::seval/tee [{:seon.agent/id "AGTreceipt0001"
                                     :seon.eval/home-requires []}]}))
                  row (db/entity
                        {:seon.db/ref [:seon.eval/id eval-id]})]
              (is (false? (:seon.db/ok? late)))
              (is (true? (::seval/settled? late)))
              (is (= :interrupted (:seon.eval/status late)))
              (is (= :interrupted (:seon.eval/status row)))
              (is (not (contains? row :seon.eval/result-edn))))))
        (.then (fn [_] (done)))
        (.catch
          (fn [error]
            (is false (str "threw — " error))
            (done))))))
