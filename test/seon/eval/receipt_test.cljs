(ns seon.eval.receipt-test
  "Focused contracts for eval receipts at the database authority boundary."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [malli.core :as m]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
    [seon.eval :as seval]
    [seon.eval.internal :as receipt]
    [seon.runtime.admission :as admission]))

(def configuration (config/resolve-config-singleton {}))

(def database
  {:db-name "default"
   :t 42
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"})

(def database-after
  (assoc database
         :t 43
         :datahike/commit-id
         #uuid "cccccccc-cccc-4ccc-8ccc-cccccccccccc"))

(def transaction-report
  {:db-before database
   :db-after database-after
   :tx-data []
   :tempids {}})

(def start
  {:seon.agent.turn/id "TRNreceipt0001"
   :seon.eval/id "EVLreceipt0001"
   :seon.eval/at (js/Date. 1000)
   :seon.eval/source "(+ 1 2)"
   :seon.eval/narration "check arithmetic"
   :seon.eval/ns :my.agent.receipt
   :seon.eval/agent [:seon.agent/id "AGTreceipt0001"]})

(def record-request
  {:seon.agent.turn/id-of-turn "TRNreceipt0001"
   ::seval/eval-id "EVLreceipt0001"
   ::seval/at (js/Date. 1100)
   ::seval/duration-ms 2
   ::seval/narration "check arithmetic"
   ::seval/source "(+ 1 2)"
   ::seval/ending-ns 'my.agent.receipt
   ::seval/result {::seval/ok? true ::seval/value 3}
   ::seval/tee []
   ::db/db database
   ::db/expected-db database})

(defn- call-record-eval!
  "Return the asynchronous eval-recording operation without CPS awaiting it."
  [request]
  (seval/record-eval! request))

(defn- call-retry-eval-record!
  "Return the asynchronous stale-publication retry without CPS awaiting it."
  [frozen acquire! compile-tee record!]
  ((deref #'seval/retry-eval-record!)
   frozen acquire! compile-tee record!))

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

(deftest start-eval-returns-the-native-allocation-report
  (async done
    (let [original db.id/allocate!
          observed (atom nil)]
      (set! db.id/allocate!
            (fn [request]
              (reset! observed request)
              (js/Promise.resolve
               (assoc transaction-report
                      ::db.id/ids
                      {:seon.eval/eval-allocation "EVLreceipt0001"}))))
      (-> (seval/start-eval!
           {:seon.agent.turn/id-of-turn "TRNreceipt0001"
            ::seval/at (js/Date. 1000)
            ::seval/narration "check arithmetic"
            ::seval/source "(+ 1 2)"
            ::seval/starting-ns 'my.agent.receipt
            ::db/db database})
          (.then
           (fn [result]
             (is (= database (::db/db @observed)))
             (is (= database (:db-before result)))
             (is (= database-after (:db-after result)))
             (is (= "EVLreceipt0001" (:seon.eval/id result)))
             (is (not (contains? result :seon.db/ok?)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db.id/allocate! original)
             (done)))))))

(deftest record-eval-returns-the-native-transaction-report
  (async done
    (let [original db/transact!
          observed (atom nil)]
      (set! db/transact!
            (fn [& [request]]
              (reset! observed request)
              (js/Promise.resolve transaction-report)))
      (-> (seval/record-eval! record-request)
          (.then
           (fn [result]
             (is (= database (::db/db @observed)))
             (is (= database (::db/expected-db @observed)))
             (is (= database-after (:db-after result)))
             (is (= "EVLreceipt0001" (:seon.eval/id result)))
             (is (not (contains? result ::seval/tee-recorded?)))
             (is (= 3 (::seval/retained-value result)))
             (is (not (contains? result :seon.db/ok?)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/transact! original)
             (done)))))))

(deftest failed-program-publication-does-not-commit-a-transcript
  (async done
    (let [original-transact db/transact!
          original-pull db/pull
          calls (atom [])
          tee-row {:seon.fn/sym "my.agent.receipt/example"
                   :seon.fn/source "(defn example [] 3)"}
          transaction-error
          {:seon.error/message "program row rejected"
           :seon.error/data {}}]
      (set! db/transact!
            (fn [& [request]]
              (swap! calls conj [:transact request])
              (js/Promise.resolve transaction-error)))
      (set! db/pull
            (fn
              ([request]
               (swap! calls conj [:pull request])
               (js/Promise.resolve {:seon.eval/status :running}))
              ([_selector _ref]
               (js/Promise.reject
                (js/Error. "unexpected positional pull")))
              ([_database _selector _ref]
               (js/Promise.reject
                (js/Error. "unexpected database positional pull")))))
      (-> (js/Promise.resolve nil)
          (.then
           (fn [_]
             (call-record-eval!
              (assoc record-request ::seval/tee [tee-row]))))
          (.then
           (fn [result]
             (let [transactions (filter #(= :transact (first %)) @calls)
                   tx-data (get-in (first transactions)
                                   [1 :seon.db/tx-data])]
               (is (= transaction-error result))
               (is (= [:transact :pull] (mapv first @calls)))
               (is (= 1 (count transactions))
                   "a rejected program row cannot trigger a transcript write")
               (is (= :db.fn/cas (ffirst tx-data)))
               (is (= "EVLreceipt0001" (:seon.eval/id (second tx-data))))
               (is (= tee-row (nth tx-data 2))
                   "receipt outcome and program row share one transaction")
               (is (not (contains? result ::seval/retained-value)))
               (is (not (contains? result ::seval/tee-recorded?))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/transact! original-transact)
             (set! db/pull original-pull)
             (done)))))))

(deftest stale-publication-rebuilds-from-the-frozen-result
  (async done
    (let [value (js-obj)
          calls (atom [])
          attempt (atom 0)
          frozen {::seval/tee-entities
                  [{:seon.fn/sym "my.agent.receipt/example"}]
                  ::seval/source "(defn example [] value)"
                  ::seval/ending-ns 'my.agent.receipt
                  ::seval/result {::seval/ok? true ::seval/value value}
                  ::seval/eval-id "EVLreceipt0001"
                  :seon.agent.turn/id-of-turn "TRNreceipt0001"}
          acquire!
          (fn [_request]
            (let [n (swap! attempt inc)]
              (swap! calls conj [:acquire n])
              (js/Promise.resolve {::db/db (assoc database :t (+ 41 n))})))
          compile-tee
          (fn [frozen-value acquired]
            (swap! calls conj [:compile (::db/db acquired)])
            {::seval/tee [{:seon.fn/sym "my.agent.receipt/example"}]
             ::seval/result (::seval/result frozen-value)
             ::seval/pending? false
             ::seval/changed-schemas #{}})
          record!
          (fn [request]
            (swap! calls conj [:record request])
            (js/Promise.resolve
             (if (= 1 (count (filter #(= :record (first %)) @calls)))
               {:seon.error/message "database advanced"
                :seon.error/data
                {::protocol/error-kind protocol/stale-database-value-error}}
               transaction-report)))]
      (-> (call-retry-eval-record!
           frozen acquire! compile-tee record!)
          (.then
           (fn [{::seval/keys [recorded]}]
             (let [requests (mapv second
                                  (filter #(= :record (first %)) @calls))]
               (is (= transaction-report recorded))
               (is (= 2 (count (filter #(= :acquire (first %)) @calls))))
               (is (= 2 (count requests)))
               (is (every? #(identical? value
                                        (get-in % [::seval/result
                                                   ::seval/value]))
                           requests)
                   "both publications reuse the already-executed value")
               (is (not= (::db/expected-db (first requests))
                         (::db/expected-db (second requests)))
                   "the retry reacquires and recompiles at the new database"))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn [] (done)))))))

(deftest failed-terminal-status-read-remains-a-direct-database-error
  (async done
    (let [original-transact db/transact!
          original-pull db/pull
          calls (atom [])
          transaction-error
          {:seon.error/message "receipt CAS lost"
           :seon.error/data {}}
          read-error
          {:seon.error/message "authority status read failed"
           :seon.error/kind :core-bug}]
      (set! db/transact!
            (fn [& [request]]
              (swap! calls conj [:transact request])
              (js/Promise.resolve transaction-error)))
      (set! db/pull
            (fn
              ([request]
               (swap! calls conj [:pull request])
               (js/Promise.resolve read-error))
              ([_selector _ref]
               (js/Promise.reject
                (js/Error. "unexpected positional pull")))))
      (-> (seval/record-eval! record-request)
          (.then
           (fn [result]
             (is (= "authority status read failed"
                    (:seon.error/message result)))
             (is (= "EVLreceipt0001" (:seon.eval/id result)))
             (is (= [:transact :pull] (mapv first @calls)))
             (is (not (contains? result :seon.db/error)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/transact! original-transact)
             (set! db/pull original-pull)
             (done)))))))

(deftest run-fence-uses-the-invocation-database
  (async done
    (let [original-transact db/transact!
          original-available admission/available?
          requests (atom [])]
      (set! admission/available? (constantly true))
      (set! db/transact!
            (fn [& [request]]
              (swap! requests conj request)
              (js/Promise.resolve {:seon.error/message "run superseded"})))
      (-> (seval/eval-batch!
           nil [] 'my.agent.receipt "AGTreceipt0001"
           "TRNreceipt0001" "RUNreceipt0001"
           {:seon.config/configuration configuration
            ::seval/authored-sources {} ::db/db database})
          (.then
           (fn [result]
             (is (true? (:seon.eval/fenced? result)))
             (is (= database (::db/db (first @requests))))
             (is (= 1 (count @requests)))))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/transact! original-transact)
             (set! admission/available? original-available)
             (done)))))))

(deftest forms-do-not-reuse-the-database-value-consumed-by-the-run-fence
  (async done
    (let [original-transact db/transact!
          original-record seval/record-eval!
          original-available admission/available?
          recorded-request (atom nil)]
      (set! admission/available? (constantly true))
      (set! db/transact!
            (fn [& [_request]]
              (js/Promise.resolve transaction-report)))
      (set! seval/record-eval!
            (fn [request]
              (reset! recorded-request request)
              (js/Promise.resolve
               (assoc transaction-report :seon.eval/id "EVLreceipt0001"))))
      (-> (seval/eval-batch!
           nil
           [{:seon.repl/kind :comment
             :seon.repl/narration "thinking"}]
           'my.agent.receipt "AGTreceipt0001"
           "TRNreceipt0001" "RUNreceipt0001"
           {:seon.config/configuration configuration
            ::seval/authored-sources {} ::db/db database})
          (.then
           (fn [result]
             (is (= ["EVLreceipt0001"] (:seon.eval/ids result)))
             (is (not (contains? @recorded-request ::db/db))
                 "each form acquires the current cached database after earlier writes")))
          (.catch (fn [error] (is false (str error))))
          (.finally
           (fn []
             (set! db/transact! original-transact)
             (set! seval/record-eval! original-record)
             (set! admission/available? original-available)
             (done)))))))
