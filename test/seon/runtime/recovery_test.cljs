(ns seon.runtime.recovery-test
  "Crash recovery fixtures over one immutable database value."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [malli.core :as m]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
    [seon.runtime.recovery :as recovery]))

(def ^:private database
  {:db-name "default"
   :t 81
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "ba3a8f3a-554a-4395-92d6-b27fa148b3fd"})

(def ^:private database-after
  (assoc database
         :t 82
         :datahike/commit-id
         #uuid "bc3a8f3a-554a-4395-92d6-b27fa148b3fd"))

(defn- query-result
  [value]
  {::protocol/success? true
   :datahike.query/result value})

(defn- acquisition
  [targets turns evals]
  {::db/results
   [(query-result targets)
    (query-result turns)
    (query-result evals)
    (query-result :seon.db.id.generator/compact)]})

(defn- with-authority-stubs
  [acquired allocate body]
  (let [original-db db/db
        original-execute-many db/execute-many
        original-allocate db.id/allocate!]
    (set! db/db
          (fn
            ([] (js/Promise.resolve database))
            ([_] (js/Promise.reject
                  (js/Error. "unexpected named database selection")))))
    (set! db/execute-many (fn [_] (js/Promise.resolve acquired)))
    (set! db.id/allocate! allocate)
    (-> (js/Promise.resolve (body))
        (.finally
         (fn []
           (set! db/db original-db)
           (set! db/execute-many original-execute-many)
           (set! db.id/allocate! original-allocate))))))

(deftest recovery-schemas-compile-and-bound-the-optional-detail
  (is (m/validate :seon.runtime.recovery/detail "pod exited unexpectedly"))
  (is (not (m/validate :seon.runtime.recovery/detail
                       (apply str (repeat 2049 "x")))))
  (is (m/validate ::recovery/recover-request {}))
  (is (m/validate ::recovery/recover-request
                  {:seon.runtime.recovery/detail "signal 9"})))

(deftest incomplete-runs-turns-and-evals-compile-one-fenced-transaction
  (async done
    (let [targets #{["beta" "run-b" :closed]
                    ["alpha" "run-a" :open]}
          turns #{["run-b" "turn-b"] ["run-a" "turn-a"]}
          evals #{["run-a" "turn-a" "eval-a"]
                  ["run-b" "turn-b" "eval-b"]}
          !execute-request (atom nil)
          !allocation-request (atom nil)
          original-db db/db
          original-execute-many db/execute-many
          original-allocate db.id/allocate!
          allocate
          (fn [request]
            (reset! !allocation-request request)
            (let [built ((::db.id/transaction-builder request)
                         {:seon.runtime.recovery/id "r12345678901"})]
              (js/Promise.resolve
               {:db-before database
                :db-after database-after
                :tx-data (::db/tx-data built)
                :tempids {}
                :tx-meta {}
                ::db.id/ids
                {:seon.runtime.recovery/id "r12345678901"}
                ::db.id/eids {:seon.runtime.recovery/id 9001}
                ::built built})))]
      (set! db/db
            (fn
              ([] (js/Promise.resolve database))
              ([_] (js/Promise.reject
                    (js/Error. "unexpected named database selection")))))
      (set! db/execute-many
            (fn [request]
              (reset! !execute-request request)
              (js/Promise.resolve (acquisition targets turns evals))))
      (set! db.id/allocate! allocate)
      (-> (recovery/recover!
           {:seon.runtime.recovery/detail "cold restart"})
          (.then
           (fn [result]
             (testing "all recovery reads share one immutable database value"
               (is (= 4 (count (::db/members @!execute-request))))
               (is (identical? database (::db/db @!execute-request)))
               (is (every? #(= protocol/query-operation
                               (::protocol/operation %))
                           (::db/members @!execute-request))))
             (testing "ordinary policy data and a pure builder reach allocation"
               (is (identical? database (::db/db @!allocation-request)))
               (is (= {:seon.runtime.recovery/id
                       :seon.db.id.generator/compact}
                      (::db.id/generator-policies @!allocation-request)))
               (let [built ((::db.id/transaction-builder @!allocation-request)
                            {:seon.runtime.recovery/id "r12345678901"})
                     tx (::db/tx-data built)]
                 (is (identical? database (::db/expected-db built)))
                 (is (some #{(db/cas-assert
                              [:seon.agent/id "alpha"]
                              :seon.agent/run
                              [:seon.agent.run/id "run-a"])} tx))
                 (is (some #{[:db/retract [:seon.agent/id "beta"]
                             :seon.agent/run
                             [:seon.agent.run/id "run-b"]]} tx))
                 (is (some #(and (= "run-a" (:seon.agent.run/id %))
                                 (= :crashed
                                    (:seon.agent.run/closed-reason %)))
                           tx))
                 (is (not-any? #(= "run-b" (:seon.agent.run/id %))
                               (filter :seon.agent.run/closed-reason tx)))
                 (is (some #{"turn-a"}
                           (keep :seon.agent.turn/id tx)))
                 (is (some #{"eval-a"}
                           (keep :seon.eval/id tx)))
                 (is (= "cold restart"
                        (:seon.runtime.recovery/detail (last tx))))))
             (testing "response order is deterministic ordinary data"
               (is (true? (::recovery/repaired? result)))
               (is (= ["alpha" "beta"] (::recovery/agent-ids result)))
               (is (= ["run-a" "run-b"] (::recovery/run-ids result)))
               (is (= ["turn-a" "turn-b"] (::recovery/turn-ids result)))
               (is (= ["eval-a" "eval-b"] (::recovery/eval-ids result)))
               (is (= "r12345678901"
                      (:seon.runtime.recovery/id result))))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally
           (fn []
             (set! db/db original-db)
             (set! db/execute-many original-execute-many)
             (set! db.id/allocate! original-allocate)
             (done)))))))

(deftest converged-recovery-does-not-allocate-or-transact
  (async done
    (let [!allocations (atom 0)]
      (-> (with-authority-stubs
           (acquisition #{} #{} #{})
           (fn [_]
             (swap! !allocations inc)
             (js/Promise.resolve
              {:db-before database
               :db-after database-after
               :tx-data []
               :tempids {}
               :tx-meta {}}))
           #(recovery/recover! {}))
          (.then
           (fn [result]
             (is (false? (::recovery/repaired? result)))
             (is (= [] (::recovery/agent-ids result)))
             (is (zero? @!allocations))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally done)))))

(deftest concurrent-write-stale-fence-is-terminal-data
  (async done
    (let [!requests (atom [])
          stale
          {:seon.error/message "expected database value is no longer current"
           :seon.error/kind :user-input
           :seon.error/data {:seon.db/expected-db database}}]
      (-> (with-authority-stubs
           (acquisition #{["alpha" "run-a" :open]} #{} #{})
           (fn [request]
             (swap! !requests conj request)
             (is (identical? database
                             (::db/expected-db
                     ((::db.id/transaction-builder request)
                      {:seon.runtime.recovery/id "r12345678901"}))))
             (js/Promise.resolve stale))
           #(recovery/recover! {}))
          (.then
           (fn [result]
             (is (= stale result))
             (is (= 1 (count @!requests))
                 "a stale domain snapshot is not mislabeled as an ID collision")))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally done)))))

(deftest member-failure-is-an-error-value
  (async done
    (let [failed
          {::db/results
           [{::protocol/success? false
             ::protocol/error "query budget exhausted"}
            (query-result #{})
            (query-result #{})
            (query-result :seon.db.id.generator/compact)]}]
      (-> (with-authority-stubs
           failed
           (fn [_]
             (js/Promise.reject (js/Error. "allocation must not run")))
           #(recovery/recover! {}))
          (.then
           (fn [result]
             (is (= :core-bug (:seon.error/kind result)))
             (is (string? (:seon.error/message result)))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally done)))))

(deftest acquisition-error-remains-a-direct-error
  (async done
    (let [direct-error {:seon.error/message "database unavailable"
                        :seon.error/kind :core-bug}]
      (-> (with-authority-stubs
           direct-error
           (fn [_]
             (js/Promise.reject (js/Error. "allocation must not run")))
           #(recovery/recover! {}))
          (.then
           (fn [result]
             (is (= direct-error result))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally done)))))
