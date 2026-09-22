(ns seon.boot-process-attribute-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.instruction :as instruction]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as support]))

(deftest canonical-population-retains-transaction-process-provenance
  (support/with-database
   (fn [connection]
     (let [projection (schema/handed-projection)
           report (support/transacted!
                   connection
                   {:tx-data (instruction/seed-rows)
                    :tx-meta {:seon.db/process
                              [:seon.db.process/id cluster/boot-process-identity]}})
           database (:db-after report)]
       (is (some (fn [[entity attribute _ tx added?]]
                   (and (= attribute :seon.db/process) (= entity tx) added?))
                 (:tx-data report))
           "the boot instruction population commits provenance on its transaction")
       (is (some #{:seon.db/process}
                 (schema.datahike/database-attributes-in projection))
           "a new database installs the independently declared provenance ref")
       (is (= :db.type/ref
              (:db/valueType
               (db/pull database [:db/valueType] [:db/ident :seon.db/process]))))
       (is (pos-int?
            (db/q '[:find ?tx .
                    :in $ ?process-id
                    :where
                    [?process :seon.db.process/id ?process-id]
                    [?schema :seon.schema/key :seon.db/process ?tx]
                    [?tx :seon.db/process ?process]]
                  database cluster/boot-process-identity))
           "canonical population committed its schema row with boot provenance")))))
