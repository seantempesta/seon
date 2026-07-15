(ns seon.eval.receipt-test
  "Pure contract tests for the parent-owned eval receipt mechanics."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [datahike.api :as d]
    [malli.core :as m]
    [seon.agent]
    [seon.agent.home :as home]
    [seon.client :as client]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.eval :as seval]
    [seon.eval.internal :as receipt]
    [seon.repl :as repl]
    [seon.repl.internal :as repl.internal]))

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

(defn- with-allocation-stub
  [body]
  (let [original db.id/allocate!]
    (try
      (-> (js/Promise.resolve (body original))
          (.finally (fn [] (set! db.id/allocate! original))))
      (catch :default error
        (set! db.id/allocate! original)
        (js/Promise.reject error)))))

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

(deftest failed-start-allocation-never-executes-and-next-form-gets-a-receipt
  (async done
    (-> (with-conn
          (fn ^:async exercise [conn]
            (let [fixture
                  (await
                    (db.id/allocate!
                      {::db.id/allocations
                       [{::db.id/key ::fixture-agent
                         ::db.id/identity-attr :seon.agent/id}
                        {::db.id/key ::fixture-turn
                         ::db.id/identity-attr :seon.agent.turn/id}]
                       ::db.id/transaction-builder
                       (fn [ids]
                         {:seon.db/tx-data
                          [{:seon.agent/id (::fixture-agent ids)}
                           {:seon.agent.turn/id (::fixture-turn ids)
                            :seon.agent.turn/at (js/Date.)
                            :seon.agent.turn/status :running}]})
                       :seon.db/conn conn}))
                  agent-id (get-in fixture [::db.id/ids ::fixture-agent])
                  turn-id (get-in fixture [::db.id/ids ::fixture-turn])
                  compile-state (await (repl/ensure-bootstrap!))
                  agent-ns (home/home-ns agent-id)
                  _ (await (seval/setup-agent-ns!
                             compile-state agent-ns agent-id))
                  _ (set! (.-receiptStartFailed js/globalThis) false)
                  _ (set! (.-receiptSecondRan js/globalThis) false)
                  failure-count (atom 0)
                  source
                  (str "(set! (.-receiptStartFailed js/globalThis) true)\n"
                       "(set! (.-receiptSecondRan js/globalThis) true)")
                  batch
                  (await
                    (with-allocation-stub
                      (fn [original]
                        (let [stub
                              (fn [request]
                                (if (and (zero? @failure-count)
                                         (= :seon.eval/eval-allocation
                                            (get-in request
                                                    [::db.id/allocations 0
                                                     ::db.id/key])))
                                  (do
                                    (swap! failure-count inc)
                                    (js/Promise.resolve
                                      {:seon.db/ok? false
                                       :seon.db/error
                                       {:seon.error/kind :core-bug
                                        :seon.error/message
                                        "injected receipt allocation failure"}}))
                                  (original request)))]
                          (set! db.id/allocate! stub)
                          (db/with-agent
                            agent-id
                            #(seval/eval-batch!
                               compile-state
                               (repl.internal/parse-forms source)
                               agent-ns agent-id turn-id nil))))))
                  eval-ids
                  (db/query
                    {:seon.db/db @conn
                     :seon.db/query
                     '[:find [?id ...] :where [_ :seon.eval/id ?id]]})
                  eval-row (db/entity
                             {:seon.db/ref
                              [:seon.eval/id (first (:seon.eval/ids batch))]})]
              (is (= 1 @failure-count)
                  "the first receipt allocation failed exactly once")
              (is (false? (.-receiptStartFailed js/globalThis))
                  "the failed form's observable side effect never ran")
              (is (true? (.-receiptSecondRan js/globalThis))
                  "the independent later form executed normally")
              (is (= 1 (:seon.eval/n-fail batch)))
              (is (= 1 (:seon.eval/n-ok batch)))
              (is (= 1 (count (:seon.eval/ids batch))))
              (is (= (:seon.eval/ids batch) eval-ids)
                  "no eval or result identity exists for the failed start")
              (is (= :done (:seon.eval/status eval-row)))
              (is (= "(set! (.-receiptSecondRan js/globalThis) true)"
                     (:seon.eval/source eval-row))))))
        (.then (fn [_] (done)))
        (.catch
          (fn [error]
            (is false (str "threw — " error))
            (done))))))
