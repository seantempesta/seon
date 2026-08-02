(ns seon.reconcile-test
  "Sealed acceptance draft for provenance-scoped exact reconciliation."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.reconcile :as reconcile]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(schema.edn/load! {})

(def ^:private managing-process
  "seon.db.process/config")

(def ^:private unmanaged-process
  "seon.db.process/unmanaged")

(def ^:private digest
  (str/join (repeat 64 "a")))

(defn- database-id
  [trial]
  (UUID/nameUUIDFromBytes
   (.getBytes (pr-str trial) StandardCharsets/UTF_8)))

(defn- with-model-database
  ([body]
   (test-support/with-database
     (fn [connection]
       (d/transact connection
                   [{:seon.db.process/id unmanaged-process}])
       (body connection))))
  ([id body]
   (test-support/with-database
     {::test-support/database-id id}
     (fn [connection]
       (d/transact connection
                   [{:seon.db.process/id unmanaged-process}])
       (body connection)))))

(defn- transaction-meta
  [process]
  {:seon.db/process [:seon.db.process/id process]})

(defn- transact-as!
  [connection process tx-data]
  (d/transact connection
              {:tx-data tx-data
               :tx-meta (transaction-meta process)}))

(defn- request
  [desired]
  {::reconcile/desired desired
   ::reconcile/process managing-process})

(defn- config-row
  [cluster queue-depth]
  {:seon.config/cluster cluster
   :seon.config/applied-manifest-digest digest
   :seon.config.flow.compute/queue-depth queue-depth
   :seon.config.flow.compute/concurrency 18
   :seon.config/on-core-error :panic})

(deftest fixture-provenance-is-real-transaction-metadata
  (with-model-database
    (fn [connection]
      (transact-as! connection
                    managing-process
                    [(config-row "provenance-proof" 10)])
      (is (= managing-process
             (d/q
              '[:find ?process-id .
                :where
                [?entity :seon.config/cluster "provenance-proof"]
                [?entity :seon.config.flow.compute/queue-depth _ ?tx]
                [?tx :seon.db/process ?process]
                [?process :seon.db.process/id ?process-id]]
              @connection))))))

(deftest first-apply-writes-and-reapply-is-zero-writes
  (with-model-database
    (fn [connection]
      (let [desired [(config-row "alpha" 10)]
            before (:max-tx @connection)
            first-result (reconcile/reconcile! connection (request desired))
            after-first (:max-tx @connection)
            second-result (reconcile/reconcile! connection (request desired))
            after-second (:max-tx @connection)]
        (is (= {::reconcile/converged? false
                ::reconcile/operations 1}
               first-result))
        (is (< before after-first) "the first apply commits")
        (is (= {::reconcile/converged? true
                ::reconcile/operations 0}
               second-result))
        (is (= after-first after-second)
            "converged means no transaction entity and unchanged :max-tx")))))

(deftest a-hand-edit-is-repaired-by-the-next-apply
  (with-model-database
    (fn [connection]
      (let [desired [(config-row "drifted" 10)]]
        (reconcile/reconcile! connection (request desired))
        (transact-as! connection
                      unmanaged-process
                      [{:seon.config/cluster "drifted"
                        :seon.config.flow.compute/queue-depth 99}])
        (is (= 99
               (:seon.config.flow.compute/queue-depth
                (d/pull @connection
                        '[*]
                        [:seon.config/cluster "drifted"]))))
        (reconcile/reconcile! connection (request desired))
        (is (= 10
               (:seon.config.flow.compute/queue-depth
                (d/pull @connection
                        '[*]
                        [:seon.config/cluster "drifted"]))))))))

(deftest a-managed-identity-absent-from-desired-is-retracted
  (with-model-database
    (fn [connection]
      (reconcile/reconcile!
       connection
       (request [(config-row "keep" 10)
                 (config-row "remove" 11)]))
      (reconcile/reconcile!
       connection
       (request [(config-row "keep" 10)]))
      (is (some? (d/pull @connection
                         '[:seon.config/cluster]
                         [:seon.config/cluster "keep"])))
      (is (nil? (d/pull @connection
                        '[:seon.config/cluster]
                        [:seon.config/cluster "remove"]))))))

(deftest malformed-and-conflicting-identities-refuse
  (with-model-database
    (fn [connection]
      (testing "no registered identity attribute"
        (is (= ::reconcile/no-identity
               (::reconcile/rule
                (test-support/refusal-data
                 #(reconcile/plan
                   @connection
                   (request [{:seon.config/on-core-error :panic}])))))))
      (testing "two registered identity attributes"
        (is (= ::reconcile/two-identities
               (::reconcile/rule
                (test-support/refusal-data
                 #(reconcile/plan
                   @connection
                   (request
                    [{:seon.config/cluster "two"
                      :seon.cluster.run/id "also-two"}])))))))
      (testing "the same upsert handle appears twice"
        (is (= ::reconcile/duplicate-identity
               (::reconcile/rule
                (test-support/refusal-data
                 #(reconcile/plan
                   @connection
                   (request [(config-row "duplicate" 10)
                             (config-row "duplicate" 11)])))))))
      (testing "an unmanaged entity already owns the desired identity"
        (transact-as! connection
                      unmanaged-process
                      [(config-row "outside" 77)])
        (is (= ::reconcile/identity-outside-scope
               (::reconcile/rule
                (test-support/refusal-data
                 #(reconcile/plan
                   @connection
                   (request [(config-row "outside" 10)]))))))))))

(def ^:private desired-population-generator
  (gen/fmap
   (fn [entries]
     (mapv (fn [[ordinal queue-depth mode]]
             (assoc (config-row (str "generated-" ordinal) queue-depth)
                    :seon.config/on-core-error mode))
           entries))
   ;; distinct BY IDENTITY ordinal — gen/vector-distinct has no :key
   ;; option; vector-distinct-by is the supported API
   (gen/vector-distinct-by
    first
    (gen/tuple (gen/choose 0 40)
               (gen/choose 1 100)
               (gen/elements [:record :panic]))
    {:min-elements 0
     :max-elements 12})))

(deftest apply-then-reapply-converges-over-generated-populations
  (let [check
        (tc/quick-check
         60
         (prop/for-all [desired desired-population-generator]
           (with-model-database
             (database-id desired)
             (fn [connection]
               (reconcile/reconcile! connection (request desired))
               (let [before (:max-tx @connection)
                     result (reconcile/reconcile! connection (request desired))
                     after (:max-tx @connection)]
                 (and (= {::reconcile/converged? true
                          ::reconcile/operations 0}
                         result)
                      (= before after))))))
         :seed 20260727)]
    (test-support/assert-check!
     check
     "Whole-domain reconciliation idempotence failed.")))
