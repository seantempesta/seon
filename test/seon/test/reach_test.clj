(ns seon.test.reach-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.program :as program]
            [seon.test.runner :as runner]
            [seon.test-support :as support]))

(deftest program-digest-uses-the-databases-program-shapes
  (support/with-database
   (fn [connection]
     (let [before (runner/program-digest (db/db connection))]
       (support/transacted!
        connection
        [[:db/add [:seon.fn/sym 'seon.id/id] :seon.fn/doc "Changed fixture documentation."]
         [:db/add [:seon.fn/sym 'seon.id/digest] :seon.fn/doc "Another changed declaration."]])
       (let [after (with-redefs [program/shapes
                                #(throw (ex-info "Program digest read authored resources." {}))]
                     (runner/program-digest (db/db connection)))]
         (is (string? before))
         (is (string? after) (pr-str after))
         (is (not= before after)))))))

(deftest indexed-reach-rows-retain-the-pulled-facts
  (support/with-database
   (fn [connection]
     (let [current (db/db connection)
           database (db/as-of current (db/basis-t current))
           index (#'runner/reach-refresh database nil)
           identities [[:seon.fn/sym 'seon.id/id]
                       [:seon.test/sym 'seon.id-test/data-shape-and-explicit-length-determine-identity]
                       [:seon.schema/key :seon.fn/calls]]
           rows (db/pull-many database
                 '[:db/id :seon.fn/sym :seon.fn/source :seon.fn/spec :seon.fn/keywords
                   (limit :seon.fn/calls nil) (limit :seon.fn/references nil)
                   :seon.test/sym :seon.test/source :seon.test/subject
                   :seon.schema/key :seon.schema/form] identities)]
       (is (= (count identities) (count (filter :db/id rows))))
       (doseq [row rows :when (:db/id row)]
         (let [normalized (reduce-kv
                           (fn [result attribute value]
                             (assoc result attribute
                                    (if (= :db.cardinality/many
                                           (get-in (db/schema-database database)
                                                   [:schema attribute :db/cardinality]))
                                      (set value) value)))
                           {} row)]
           (is (= (#'runner/reach-row normalized)
                  (get-in index [::runner/reach-rows (:db/id row)])))))))))
