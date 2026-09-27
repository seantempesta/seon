(ns seon.cluster.status-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.status :as status]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest accounting-joins-runtime-turns-and-preserves-unknown-cost
  (support/with-database
   (fn [connection]
     ;; The turn's opening and closing are real transactions with chosen
     ;; instants (a bare {:db/txInstant} entity is not a transaction and the
     ;; writer refuses it as unowned). Opened now, after every fixture commit.
     (let [opened (java.util.Date.)
           now (java.util.Date. (+ (inst-ms opened) 20))]
       (let [result (db/transact! connection
                  [{:db/id "datomic.tx" :db/txInstant opened}
                   {:seon.agent/id "metrics"
                    :seon.agent/runtime
                    {:seon.runtime/agent [:seon.agent/id "metrics"]
                     :seon.runtime/turns
                     [{:seon.turn/id "metrics-turn"
                       :seon.turn/agent [:seon.agent/id "metrics"]
                       :seon.turn/opened-tx "datomic.tx"
                       :seon.turn/attempts
                       [{:seon.ai.attempt/id "metrics-attempt"
                         :seon.ai/endpoint "https://example.invalid/v1/chat/completions"
                         :seon.ai/model "fixture"
                         :seon.ai.attempt/settings-edn "{}"
                         :seon.ai.attempt/ordinal 0 :seon.ai.attempt/at now
                         :seon.ai.usage/total-tokens 17
                         :seon.ai.attempt/usage-edn "{\"total_tokens\" 17}"}]}]}}])]
         (is (some? (:db-after result)) (pr-str result)))
       (let [result (db/transact! connection
                  [{:db/id "datomic.tx" :db/txInstant now}
                   [:db/add [:seon.turn/id "metrics-turn"] :seon.turn/closed-tx "datomic.tx"]
                   {:seon.cluster.eval/id "metrics-eval"
                    :seon.cluster.eval/run [:seon.turn/id "metrics-turn"]
                    :seon.cluster.eval/ordinal 0 :seon.cluster.eval/at now
                    :seon.eval/shown "λ" :seon.eval/duration-ms 7}])]
         (is (some? (:db-after result)) (pr-str result)))
       (let [entities (status/agents {:seon.db/db @connection :seon.db/connection connection})
             entity (first (filter #(= "metrics" (:seon.agent/id %)) entities))]
         (is (vector? entities) (pr-str entities))
         (is (= 20 (:seon.cluster.status/last-turn-ms entity)))
         (is (= 1 (:seon.cluster.status/evaluations entity)))
         (is (= 7 (:seon.cluster.status/evaluation-ms entity)))
         (is (= 17 (:seon.cluster.status/provider-tokens entity)))
         (is (= 2 (:seon.cluster.status/storage-bytes entity)))
         (is (string? (get-in entity [:seon.cluster.status/provider-cost-usd :seon.cluster.status/unavailable-observation]))))
       (let [staged (blob/stage! connection "abc")
             digest (:seon.blob/digest staged)
             report (blob/with-publication!
                     connection [staged]
                     #(db/transact! connection
                       [[:db/add [:seon.turn/id "metrics-turn"] :seon.turn/reply-blob digest]
                        [:db/add [:seon.ai.attempt/id "metrics-attempt"] :seon.ai.attempt/reasoning-blob digest]
                        [:db/add [:seon.ai.attempt/id "metrics-attempt"] :seon.ai.attempt/usage-edn
                         "{\"total_tokens\" 17, \"cost\" 0.125}"]
                        {:seon.cluster.eval/id "old-metrics-eval"
                         :seon.cluster.eval/run [:seon.turn/id "metrics-turn"]
                         :seon.cluster.eval/ordinal 1 :seon.cluster.eval/at (java.util.Date. 0)
                         :seon.eval/shown "x" :seon.eval/duration-ms 99}]))
             entities (status/agents {:seon.db/db @connection :seon.db/connection connection})
             entity (first (filter #(= "metrics" (:seon.agent/id %)) entities))]
         (is (some? (:db-after report)) (pr-str report))
         (is (= 1 (:seon.cluster.status/evaluations entity)) "prior-session evaluation is excluded")
         (is (= 7 (:seon.cluster.status/evaluation-ms entity)))
         (is (= 0.125 (:seon.cluster.status/provider-cost-usd entity)))
         (is (= 6 (:seon.cluster.status/storage-bytes entity))
             "all retained shown bytes plus one copy of the shared blob"))))))

(deftest root-concern-is-selected-by-schema-and-jvm-counts-include-virtual-threads
  (support/with-database
   (fn [connection]
     (let [projection (schema/projection-from-database @connection)
           matching #(set (map :seon.schema/key (schema/matching-shapes-in projection %)))
           ready (java.util.concurrent.CountDownLatch. 1)
           release (java.util.concurrent.CountDownLatch. 1)
           finished (java.util.concurrent.CountDownLatch. 1)
           _thread (Thread/startVirtualThread
                    (fn []
                      (try
                        (.countDown ready)
                        (support/await-event! release :status-thread-release)
                        (finally (.countDown finished)))))]
       (try
         (is (support/await-event! ready :status-thread-ready))
         (is (contains? (matching {:seon.agent/id "root"}) :seon.cluster.status/root))
         (is (not (contains? (matching {:seon.agent/id "other"}) :seon.cluster.status/root)))
         (let [counts (#'status/thread-counts)]
           (is (pos-int? (:seon.cluster.status/platform-threads counts)) (pr-str counts))
           (is (pos-int? (:seon.cluster.status/virtual-threads counts)) (pr-str counts)))
         (finally
           (.countDown release)
           (support/await-event! finished :status-thread-finished)))))))

(deftest routine-status-declares-unmeasured-store-size
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "status-observation")
     (let [observation (status/snapshot {:seon.db/db @connection
                                         :seon.db/connection connection})]
       (is (= "status-observation" (:seon.cluster/name observation)) (pr-str observation))
       (is (string? (get-in observation [:seon.cluster.status/store-bytes :seon.cluster.status/unavailable-observation])))
       (is (pos-int? (:seon.cluster.status/platform-threads observation)))))))
