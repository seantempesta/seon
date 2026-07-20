(ns seon.runtime.recovery-test
  "Crash recovery fixtures over one immutable database value."
  (:require
    [cljs.test :refer [async deftest is testing]]
    [malli.core :as m]
    [my.blob :as blob]
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
        original-query db/query
        original-execute-many db/execute-many
        original-allocate db.id/allocate!]
    (set! db/db
          (fn
            ([] (js/Promise.resolve database))
            ([_] (js/Promise.reject
                  (js/Error. "unexpected named database selection")))))
    (set! db/execute-many (fn [_] (js/Promise.resolve acquired)))
    (set! db/query (fn [_] (js/Promise.resolve [])))
    (set! db.id/allocate! allocate)
    (-> (js/Promise.resolve (body))
        (.finally
         (fn []
           (set! db/db original-db)
           (set! db/query original-query)
           (set! db/execute-many original-execute-many)
           (set! db.id/allocate! original-allocate))))))

(deftest recovery-schemas-compile-and-bound-the-optional-detail
  (is (m/validate :seon.runtime.recovery/detail "pod exited unexpectedly"))
  (is (not (m/validate :seon.runtime.recovery/detail
                       (apply str (repeat 2049 "x")))))
  (is (m/validate ::recovery/recover-request {}))
  (is (m/validate ::recovery/recover-request
                  {:seon.runtime.recovery/detail "signal 9"}))
  (is (m/validate ::recovery/recover-request
                  {:seon.agent/id "alpha"
                   :seon.agent.run/id "n12345678901"
                   :seon.runtime.recovery/detail "execution child deadline"}))
  (is (not (m/validate ::recovery/recover-request
                       {:seon.agent/id "alpha"}))))

(deftest execution-child-recovery-is-scoped-to-one-agent-run
  (async done
    (let [!execute-request (atom nil)
          !built (atom nil)
          original-put blob/put!
          allocate
          (fn [request]
            (let [built ((::db.id/transaction-builder request)
                         {:seon.runtime.recovery/id "r12345678901"})]
              (reset! !built built)
              (js/Promise.resolve
               {:db-before database
                :db-after database-after
                :tx-data (::db/tx-data built)
                :tempids {}
                :tx-meta {}
                ::db.id/ids {:seon.runtime.recovery/id "r12345678901"}})))]
      (set! blob/put!
            (fn [_]
              (js/Promise.resolve
               {:my.blob/ok? true
                :my.blob/hash (apply str (repeat 64 "a"))})))
      (-> (with-authority-stubs
           (acquisition #{["alpha" "n12345678901" :open]}
                        #{["n12345678901" "turn-a"]}
                        #{["n12345678901" "turn-a" "eval-a"
                           "(js/process.exit 17)"]})
           allocate
           (fn []
             (let [original db/execute-many]
               (set! db/execute-many
                     (fn [request]
                       (reset! !execute-request request)
                       (original request)))
               (recovery/recover!
                {:seon.agent/id "alpha"
                 :seon.agent.run/id "n12345678901"
                 :seon.runtime.recovery/detail
                 "execution child deadline"
                 :seon.runtime.recovery/evidence
                 {:seon.execution.host/pid 812
                  :seon.execution.host/artifact-digest
                  (apply str (repeat 64 "d"))
                  :seon.execution.host/elapsed-ms 10001
                  :seon.execution.host/stdout-tail "before timeout"
                  :seon.execution.host/resource-usage
                  {:seon.subprocess/rss-bytes 4096
                   :seon.subprocess/max-rss-bytes 8192
                   :seon.subprocess/cpu-time
                   {:seon.subprocess/user 700
                    :seon.subprocess/system 300
                    :seon.subprocess/total 1000}}}}))))
          (.then
           (fn [result]
             (let [members (::db/members @!execute-request)
                   tx (::db/tx-data @!built)
                   anchor (last tx)]
               (is (= 4 (count members)))
               (is (= [["alpha" "n12345678901"]
                       ["alpha" "n12345678901"]
                       ["alpha" "n12345678901"]
                       []]
                      (mapv ::protocol/arguments members)))
               (is (= ["alpha"] (::recovery/agent-ids result)))
               (is (= ["n12345678901"] (::recovery/run-ids result)))
               (is (true? (::recovery/automatic-run? result)))
               (is (= 812 (:seon.runtime.recovery/pid anchor)))
               (is (= 4096 (:seon.runtime.recovery/rss-bytes anchor)))
               (is (= 1000
                      (:seon.runtime.recovery/cpu-total-microseconds anchor)))
               (is (= [:seon.eval/id "eval-a"]
                      (:seon.runtime.recovery/eval anchor)))
               (is (= (apply str (repeat 64 "d"))
                      (:seon.runtime.recovery/execution-digest anchor)))
               (is (= [:my.blob/hash (apply str (repeat 64 "a"))]
                      (:seon.runtime.recovery/diagnostic-blob anchor)))
               (is (some #{(db/cas-assert
                            [:seon.agent/id "alpha"]
                            :seon.agent/run
                            [:seon.agent.run/id "n12345678901"])} tx)))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally (fn []
                      (set! blob/put! original-put)
                      (done)))))))

(deftest automatic-run-policy-breaks-the-same-crash-until-new-contact
  (async done
    (let [policy (deref #'recovery/automatic-run-after-recovery?)
          original-query db/query
          prior? (atom false)
          contacted? (atom false)
          digest (apply str (repeat 64 "d"))]
      (set! db/query
            (fn [request]
              (js/Promise.resolve
               (if (= 3 (count (:seon.db/args request)))
                 (when @prior? 70)
                 (when @contacted? 900)))))
      (-> (policy database "alpha" "(js/process.exit 17)" digest)
          (.then (fn [automatic?]
                   (is (true? automatic?))
                   (reset! prior? true)
                   (policy database "alpha" "(js/process.exit 17)" digest)))
          (.then (fn [automatic?]
                   (is (false? automatic?))
                   (reset! contacted? true)
                   (policy database "alpha" "(js/process.exit 17)" digest)))
          (.then (fn [automatic?]
                   (is (true? automatic?))))
          (.catch (fn [error] (is false (str "policy threw — " error))))
          (.finally (fn []
                      (set! db/query original-query)
                      (done)))))))

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

(deftest later-run-presence-is-awaited-not-a-truthy-promise
  ;; Regression: the sync form was `(boolean (db/query ...))` over a Promise,
  ;; which is ALWAYS true — every notice vanished. Absence must return false.
  (async done
    (let [later-run? (deref #'recovery/later-run?)
          original-query db/query]
      (set! db/query (fn [_] (js/Promise.resolve nil)))
      (-> (later-run? database "alpha" 70)
          (.then (fn [absent]
                   (is (false? absent)
                       "no later run must derive false, not a truthy Promise")
                   (set! db/query (fn [_] (js/Promise.resolve 9001)))
                   (later-run? database "alpha" 70)))
          (.then (fn [present]
                   (is (true? present))
                   (set! db/query
                         (fn [_]
                           (js/Promise.resolve
                            {:seon.error/message "database unavailable"
                             :seon.error/kind :core-bug})))
                   (later-run? database "alpha" 70)))
          (.then (fn [failed]
                   (is (= "database unavailable"
                          (:seon.error/message failed)))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally (fn []
                      (set! db/query original-query)
                      (done)))))))

(defn- notice-query-stub
  "Dispatch a recovery notice query on its distinguishing where clause."
  [{later-run-result :later-run}]
  (fn [{query :seon.db/query}]
    (js/Promise.resolve
     (let [text (pr-str query)]
       (cond
         (re-find #":db/txInstant" text)
         #{["r12345678901" 70 #inst "2026-07-20T00:00:00.000-00:00"]}
         (re-find #":seon.agent.run/agent" text) later-run-result
         (re-find #":seon.agent.turn/status" text)
         #{["run-a" "turn-a"] ["run-x" "turn-x"]}
         (re-find #":seon.agent/run" text)
         #{["alpha" "run-a"]}
         :else (throw (ex-info "unexpected query" {:query query})))))))

(deftest pending-notices-derive-and-clear-on-a-later-run
  (async done
    (let [original-query db/query
          original-entity db/entity]
      ;; Multi-arity like the real fn so the compiled arity-1 dispatch resolves.
      (set! db/entity
            (fn stub-entity
              ([_]
               (js/Promise.resolve
                {:seon.runtime.recovery/id "r12345678901"
                 :seon.runtime.recovery/reason :unexpected-exit
                 :seon.runtime.recovery/detail "pod exited"}))
              ([_ _] (stub-entity nil))))
      (set! db/query (notice-query-stub {:later-run nil}))
      (-> (recovery/pending-notices {:seon.db/db database})
          (.then
           (fn [notices]
             (is (= 1 (count notices)))
             (let [notice (first notices)]
               (is (= "r12345678901" (:seon.runtime.recovery/id notice)))
               (is (= :unexpected-exit
                      (:seon.runtime.recovery/reason notice)))
               (is (= "pod exited" (:seon.runtime.recovery/detail notice)))
               (is (= 70 (::recovery/transaction notice)))
               (is (= ["alpha"] (::recovery/agents notice)))
               (is (= ["run-a"] (::recovery/runs notice)))
               (is (= ["turn-a"] (::recovery/turns notice))
                   "turns outside the pending run set are excluded"))
             ;; A later run for the affected agent clears the notice.
             (set! db/query (notice-query-stub {:later-run 9001}))
             (recovery/pending-notices {:seon.db/db database})))
          (.then (fn [notices]
                   (is (= [] notices))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally (fn []
                      (set! db/query original-query)
                      (set! db/entity original-entity)
                      (done)))))))

(deftest pending-notices-return-a-query-failure-as-data
  (async done
    (let [original-query db/query
          failure {:seon.error/message "history read failed"
                   :seon.error/kind :core-bug}]
      (set! db/query (fn [_] (js/Promise.resolve failure)))
      (-> (recovery/pending-notices {:seon.db/db database})
          (.then (fn [result]
                   (is (= failure result))))
          (.catch (fn [error] (is false (str "threw — " error))))
          (.finally (fn []
                      (set! db/query original-query)
                      (done)))))))

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
