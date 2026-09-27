(ns seon.db.schema-retirement-test
  "A schema retirement adopts incrementally on a branch of a live store with entities."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(deftest retiring-an-identity-attribute-with-entities-is-admitted
  ;; The C4 retirement of `:seon.schema.shape.child/id` refused: the writer's
  ;; deletion check resolved each removed identity as a lookup ref in the value
  ;; after, where the retired attribute is no longer unique.
  (test-support/with-database
   (fn [connection]
     (let [attribute :seon.db.schema-retirement-test/id
           projection (schema/handed-projection)
           installed (:db-after (d/with @connection
                                        {:tx-data [{:db/ident attribute
                                                    :db/valueType :db.type/string
                                                    :db/cardinality :db.cardinality/one
                                                    :db/unique :db.unique/identity}]}))
           before (:db-after (d/with installed {:tx-data [{attribute "retired-row"}]}))
           report (d/with before {:tx-data (cluster/attribute-change-tx before [attribute] {})})]
       (is (seq (d/datoms before :avet attribute))
           "the branch holds an entity under the retiring identity")
       (is (not (contains? (:schema (:db-after report)) attribute))
           "the retirement removes the attribute with its data")
       (is (nil? (schema/call-with-projection
                  projection #(#'db/write-report-error projection report)))
           "the writer's final check admits the retirement")))))
