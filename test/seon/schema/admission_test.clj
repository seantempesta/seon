(ns seon.schema.admission-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as test-support]))

(def ^:private boot-process-identity "seon.db.process/boot")
(def ^:private runtime-core-process-identity "seon.db.process/core")
(def ^:private agent-process-identity "seon.db.process/agent")
(def ^:private repl-process-identity "seon.db.process/repl")
(def ^:private seal-digest (apply str (repeat 64 "a")))
(def ^:private second-seal-digest (apply str (repeat 64 "b")))
(def ^:private source-attributes
  [:seon.source/digest :seon.source/built-at])

(defn- with-temporal-database [body]
  (let [configuration
        {:store {:backend :memory :id (random-uuid)}
         :schema-flexibility :write
         :keep-history? true}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact
       connection
       (schema.datahike/malli->datahike-schema
        (into (schema/canonical-database-attributes)
              source-attributes)))
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- transaction-id [report]
  (:max-tx (:db-after report)))

(defn- seed-process! [connection process-id]
  (transaction-id
   (d/transact connection [{:seon.db.process/id process-id}])))

(defn- seal! [connection digest]
  (transaction-id
   (d/transact
    connection
    [{:seon.source/digest digest
      :seon.source/built-at (java.util.Date.)}])))

(defn- transact-row!
  ([connection row-key]
   (transaction-id
    (d/transact connection [{:seon.schema/key row-key}])))
  ([connection row-key process]
   (transaction-id
    (d/transact
     connection
     {:tx-data [{:seon.schema/key row-key}]
      :tx-meta {:seon.db/process process}}))))

(defn- admission [connection tx]
  (schema/admission-from-asserting-transaction @connection tx))

(defn- source [connection tx]
  (:seon.schema.admission/source (admission connection tx)))

(defn- with-published-source [body]
  (let [root (str "tmp/schema-admission-test/" (random-uuid))
        dir (str root "/store")]
    (.mkdirs (io/file root))
    (let [opened (store/open-store! {:seon.store/dir dir})]
      (try
        (let [published
              (source/publish!
               {:seon.store/store opened
                :seon.source/digest seal-digest
                :seon.source/populate 'seon.cluster/populate-source!})
              connection
              (store/open-branch!
               opened
               (:seon.source/branch published))]
          (try
            (body connection)
            (finally
              (d/release connection))))
        (finally
          (store/release-store! opened)
          (test-support/delete-recursively! root))))))

(deftest genesis-process-identities-are-core-by-history
  (with-temporal-database
    (fn [connection]
      (let [new-producer (str "test.generated/" (random-uuid))
            producers
            [[:boot boot-process-identity]
             [:config config/managing-process-identity]
             [:runtime-core runtime-core-process-identity]
             [:new-producer new-producer]]]
        (doseq [[_ process-id] producers]
          (seed-process! connection process-id))
        (seal! connection seal-digest)
        (doseq [[label process-id] producers]
          (testing (name label)
            (let [tx
                  (transact-row!
                   connection
                   (keyword "test.schema.admission" (name label))
                   [:seon.db.process/id process-id])
                  decision (admission connection tx)]
              (is (= :core (:seon.schema.admission/source decision)))
              (is (= process-id
                     (:seon.schema.admission/process-id decision)))))))
        (testing "projection rows carry transaction ids and the same db value"
          (let [tx
                (transact-row!
                 connection
                 :test.schema.admission/projection-row
                 [:seon.db.process/id boot-process-identity])
                projection
                (schema/projection-from-rows
                 {:seon.schema/database-value @connection
                  :seon.schema/schema-rows
                  [[:test.schema.admission/value ":string" tx]]
                  :seon.schema/function-contract-rows []})]
            (is (= :core
                   (get-in
                    projection
                    [:seon.schema.projection/schema-admissions
                     :test.schema.admission/value
                     :seon.schema.admission/source])))))))
  (is (nil? (ns-resolve 'seon.schema 'core-process-identities))
      "the classifier has no literal process roster"))

(deftest post-genesis-and-trusted-looking-processes-fail-closed
  (with-temporal-database
    (fn [connection]
      (seal! connection seal-digest)
      (doseq [[label process-id]
              [[:agent agent-process-identity]
               [:repl repl-process-identity]
               [:trusted-looking boot-process-identity]]]
        (seed-process! connection process-id)
        (testing (name label)
          (let [tx
                (transact-row!
                 connection
                 (keyword "test.schema.admission" (name label))
                 [:seon.db.process/id process-id])]
            (is (= :agent (source connection tx)))
            (is (= process-id
                   (:seon.schema.admission/process-id
                    (admission connection tx))))))))))

(deftest advancing-the-published-source-digest-preserves-its-genesis-seal
  (with-temporal-database
    (fn [connection]
      (seed-process! connection boot-process-identity)
      (let [seal-tx (seal! connection seal-digest)
            source-entity
            (d/q '[:find ?source .
                   :where [?source :seon.source/digest _]]
                 @connection)]
        (d/transact
         connection
         [[:db.fn/retractAttribute source-entity :seon.source/digest]
          {:db/id source-entity :seon.source/digest second-seal-digest}])
        (let [tx
              (transact-row!
               connection
               :test.schema.admission/after-explicit-prime
               [:seon.db.process/id boot-process-identity])]
          (is (= :core (source connection tx)))
          (is (= seal-tx
                 (d/q '[:find (min ?tx) .
                        :in $ ?source
                        :where
                        [?source :seon.source/digest _ ?tx true]]
                      (d/history @connection)
                      source-entity))))))))

(deftest missing-and-malformed-genesis-provenance-fails-closed
  (testing "a transaction without process provenance"
    (with-temporal-database
      (fn [connection]
        (seal! connection seal-digest)
        (is (= :agent
               (source connection
                       (transact-row! connection
                                      :test.schema.admission/no-process)))))))
  (testing "a process ref whose entity has no process identity"
    (with-temporal-database
      (fn [connection]
        (seal! connection seal-digest)
        (d/transact connection
                    [{:seon.schema/key
                      :test.schema.admission/process-without-identity}])
        (is (= :agent
               (source
                connection
                (transact-row!
                 connection
                 :test.schema.admission/dangling-process
                 [:seon.schema/key
                  :test.schema.admission/process-without-identity])))))))
  (testing "a database with no source seal"
    (with-temporal-database
      (fn [connection]
        (seed-process! connection config/managing-process-identity)
        (is (= :agent
               (source
                connection
                (transact-row!
                 connection
                 :test.schema.admission/no-seal
                 [:seon.db.process/id
                  config/managing-process-identity])))))))
  (testing "a database with multiple source seals"
    (with-temporal-database
      (fn [connection]
        (seed-process! connection config/managing-process-identity)
        (seal! connection seal-digest)
        (seal! connection second-seal-digest)
        (is (= :agent
               (source
                connection
                (transact-row!
                 connection
                 :test.schema.admission/multiple-seals
                 [:seon.db.process/id
                  config/managing-process-identity])))))))
  (testing "a process identity first asserted in the seal transaction"
    (with-temporal-database
      (fn [connection]
        (d/transact
         connection
         [{:seon.db.process/id runtime-core-process-identity}
          {:seon.source/digest seal-digest
           :seon.source/built-at (java.util.Date.)}])
        (is (= :agent
               (source
                connection
                (transact-row!
                 connection
                 :test.schema.admission/at-seal
                 [:seon.db.process/id
                  runtime-core-process-identity]))))))))

(deftest production-source-order-makes-config-core
  (with-published-source
    (fn [connection]
      (testing "canonical schema rows carry genesis config provenance"
        (let [schema-tx
              (d/q '[:find ?tx .
                     :where
                     [_ :seon.schema/key :seon.config/manifest ?tx]]
                   @connection)]
          (is (= :core (source connection schema-tx)))))
      (testing "the actual config reconcile transaction is core"
        (let [before (:max-tx @connection)
              result
              (config/apply!
               {:seon.config/connection connection
                :seon.config/manifest (config/defaults)
                :seon.boot/cluster-name "schema-admission"})
              config-tx (:max-tx @connection)]
          (is (false? (:seon.reconcile/converged? result)))
          (is (< before config-tx))
          (is (= :core (source connection config-tx))))))))

(deftest config-is-agent-authored-in-a-bare-pre-genesis-database
  (with-temporal-database
    (fn [connection]
      (seed-process! connection config/managing-process-identity)
      (let [before (:max-tx @connection)
            result
            (config/apply!
             {:seon.config/connection connection
              :seon.config/manifest (config/defaults)
              :seon.boot/cluster-name "bare-schema-admission"})
            config-tx (:max-tx @connection)]
        (is (false? (:seon.reconcile/converged? result)))
        (is (< before config-tx))
        (is (= :agent (source connection config-tx)))))))
