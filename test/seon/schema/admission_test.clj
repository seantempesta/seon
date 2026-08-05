(ns seon.schema.admission-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.cluster]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

(def ^:private seal-digest (apply str (repeat 64 "a")))

(defn- with-history-policy [keep-history? body]
  (let [configuration
        {:store {:backend :memory :id (random-uuid)}
         :schema-flexibility :write
         :keep-history? keep-history?}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (db/transact!
       connection
       (schema.datahike/malli->datahike-schema
        (schema/canonical-database-attributes)))
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- transaction-id [report]
  (:max-tx (:db-after report)))

(defn- transact-schema-row!
  ([connection schema-key]
   (transaction-id
    (db/transact! connection [{:seon.schema/key schema-key}])))
  ([connection schema-key admission-source]
   (transaction-id
    (db/transact!
     connection
     [{:seon.schema/key schema-key
       :seon.schema.admission/source admission-source}]))))

(defn- admission [connection transaction]
  (schema/admission-from-asserting-transaction @connection transaction))

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
                :seon.source/populate 'seon.cluster/populate-source!
                :seon.source/populate-request
                {:seon.fn/manifest @test-support/source-manifest}})
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

(deftest recorded-admission-is-representation-independent
  (doseq [keep-history? [true false]]
    (testing (if keep-history? "history on" "history off")
      (with-history-policy
        keep-history?
        (fn [connection]
          (let [core-tx
                (transact-schema-row!
                 connection :test.schema.admission/core :core)
                agent-tx
                (transact-schema-row!
                 connection :test.schema.admission/agent :agent)]
            (is (= :core
                   (:seon.schema.admission/source
                    (admission connection core-tx))))
            (is (= :agent
                   (:seon.schema.admission/source
                    (admission connection agent-tx))))
            (when-not keep-history?
              (is (= :seon.db/non-temporal-database
                     (:seon.error/kind (db/history @connection)))
                  "the fixture must genuinely be non-temporal"))))))))

(deftest missing-recorded-admission-source-fails-closed
  (with-history-policy
    false
    (fn [connection]
      (let [decision
            (admission
             connection
             (transact-schema-row!
              connection :test.schema.admission/missing-source))]
        (is (= :agent (:seon.schema.admission/source decision)))
        (is (string? (:seon.schema.admission/note decision)))))))

(deftest shipped-schemas-remain-core-in-a-non-temporal-database
  (with-history-policy
    false
    (fn [connection]
      (db/transact!
       connection
       (schema/canonical-schema-rows (schema.edn/packaged-forms)))
      (let [projection (schema/projection-from-database @connection)]
        (is (= :core
               (get-in
                projection
                [:seon.schema.projection/schema-admissions
                 :seon.ai/usage
                 :seon.schema.admission/source])))
        (is (schema/projection-validator projection :seon.ai/usage))))))

(deftest source-publication-records-core-on-every-row
  (with-published-source
    (fn [connection]
      (let [database @connection
            missing
            (db/q '[:find ?entity ?identity-attribute
                   :in $ [?identity-attribute ...]
                   :where
                   [?entity ?identity-attribute]
                   (not [?entity :seon.schema.admission/source])]
                 database
                 program/identity-attributes)
            sources
            (set
             (db/q '[:find [?source ...]
                    :where [_ :seon.schema.admission/source ?source]]
                  database))]
        (is (empty? missing))
        (is (= #{:core} sources))
        (is (= :core
               (:seon.schema.admission/source
                (schema/admission-from-asserting-transaction
                 database
                 (db/q '[:find ?tx .
                        :where
                        [_ :seon.schema/key :seon.config/manifest ?tx]]
                      database)))))))))
