(ns seon.cluster.status-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.status :as status]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.env :as env]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest accounting-joins-agent-turns-and-preserves-unknown-cost
  (support/with-database
   (fn [connection]
     ;; The turn's opening and closing are real transactions with chosen
     ;; instants (a bare {:db/txInstant} entity is not a transaction and the
     ;; writer refuses it as unowned). Opened now, after every fixture commit.
     (support/transacted! connection (support/agent-tx @connection "metrics"))
     (let [opened (java.util.Date.)
           now (java.util.Date. (+ (inst-ms opened) 20))]
       (let [result (db/transact! connection
                  [{:db/id "datomic.tx" :db/txInstant opened}
                   {:seon.turn/id "metrics-turn"
                       :seon.turn/agent [:seon.agent/id "metrics"]
                       :seon.turn/opened-tx "datomic.tx"
                       :seon.turn/attempts
                       [{:seon.ai.attempt/id "metrics-attempt"
                         :seon.ai/endpoint "https://example.invalid/v1/chat/completions"
                         :seon.ai/model "fixture"
                         :seon.ai.attempt/settings-edn "{}"
                         :seon.ai.attempt/ordinal 0 :seon.ai.attempt/at now
                         :seon.ai.usage/total-tokens 17
                         :seon.ai.attempt/usage-edn "{\"total_tokens\" 17}"}]}])]
         (is (some? (:db-after result))))
       (let [result (db/transact! connection
                  [{:db/id "datomic.tx" :db/txInstant now}
                   [:db/add [:seon.turn/id "metrics-turn"] :seon.turn/closed-tx "datomic.tx"]
                   {:seon.cluster.eval/id "metrics-eval"
                    :seon.cluster.eval/run [:seon.turn/id "metrics-turn"]
                    :seon.cluster.eval/ordinal 0 :seon.cluster.eval/at now
                    :seon.eval/shown "λ" :seon.eval/duration-ms 7}])]
         (is (some? (:db-after result))))
       (let [entities (status/agents {:seon.db/db @connection :seon.db/connection connection})
             entity (first (filter #(= "metrics" (:seon.agent/id %)) entities))]
         (is (vector? entities))
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
         (is (some? (:db-after report)))
         (is (= 1 (:seon.cluster.status/evaluations entity)) "prior-session evaluation is excluded")
         (is (= 7 (:seon.cluster.status/evaluation-ms entity)))
         (is (= 0.125 (:seon.cluster.status/provider-cost-usd entity)))
         (is (= 6 (:seon.cluster.status/storage-bytes entity))
             "all retained shown bytes plus one copy of the shared blob"))))))

(deftest root-concern-is-selected-by-schema-and-virtual-threads-are-unknown
  (support/with-database
   (fn [connection]
     (let [projection (schema/projection-from-database @connection)
           matching #(set (map :seon.schema/key (schema/matching-shapes-in projection %)))
           counts (#'status/thread-counts)]
       (is (contains? (matching {:seon.agent/id "root"}) :seon.cluster.status/root))
       (is (not (contains? (matching {:seon.agent/id "other"}) :seon.cluster.status/root)))
       (is (pos-int? (:seon.cluster.status/platform-threads counts)))
       (is (:seon.cluster.status/unavailable-observation (:seon.cluster.status/virtual-threads counts))
           "virtual threads are a typed unknown, never a count read from a dump file")))))

(deftest routine-status-declares-unmeasured-store-size
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "status-observation")
     ;; The snapshot observes the caller's cluster (f667d9ef4): the fixture
     ;; branch also holds its parent's cluster entity, so the name is carried
     ;; by an environment, not inferred from the branch's cluster entities.
     (let [environment (env/environment
                        {:seon.boot/cluster-name "status-observation"
                         :seon.db/connection connection
                         :seon.schema/projection (schema/projection-from-database @connection)})
           observation (status/snapshot (env/carry {:seon.db/db @connection
                                                    :seon.db/connection connection}
                                                   environment))]
       (is (= "status-observation" (:seon.cluster/name observation)))
       (is (string? (get-in observation [:seon.cluster.status/store-bytes :seon.cluster.status/unavailable-observation])))
       (is (pos-int? (:seon.cluster.status/platform-threads observation)))))))
