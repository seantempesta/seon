(ns seon.datahike-fork-test
  "Seon-owned acceptance checks for behavior maintained in its Datahike fork."
  (:require
   [clojure.test :refer [deftest is]]
   [clojure.test.check :as tc]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.walk :as walk]
   [datahike.db :as db]
   [datahike.query :as query]
   [seon.db]
   [seon.schema]
   [seon.test-support]
   [seon.turn]))

(def ^:private planner-database
  (db/empty-db {}))

(def ^:private canonical-vars
  '[?first ?second ?third])

(def ^:private variable-name-generator
  (gen/vector-distinct
   (gen/elements
    '[?a ?b ?c ?agent ?block ?item ?root ?value ?x ?y ?z])
   {:num-elements 3}))

(defn- independent-function-clauses
  [variables]
  (mapv (fn [ordinal variable]
          [(list 'identity ordinal) variable])
        (range 1 4)
        variables))

(defn- create-plan
  [clauses]
  (#'query/create-plan-via-ir planner-database clauses #{} nil nil))

(deftest alpha-renaming-does-not-change-plan-selection
  (let [canonical-plan
        (create-plan (independent-function-clauses canonical-vars))
        check
        (tc/quick-check
         100
         (prop/for-all
          [renamed-vars variable-name-generator]
          (let [renamed-plan
                (create-plan (independent-function-clauses renamed-vars))
                renamed->canonical
                (zipmap renamed-vars canonical-vars)]
            (= canonical-plan
               (walk/postwalk-replace renamed->canonical renamed-plan))))
         :seed 1785366001)]
    (is (:result check) (pr-str check))))

(deftest schema-deletion-reads-earlier-declarations-in-one-transaction
 (seon.test-support/with-database
  (fn [connection]
   (let [key :turn-schema-cache/value
         schema-form [:int {:seon.db/index true}]
         form (pr-str schema-form)
         observed (atom nil)
         before (seon.db/db connection)
         _ (seon.schema/projection-from-database before (seon.db/carried-projection before))
         result (seon.db/transact! connection
                  {:tx-data
                   [[:db.fn/call #'seon.turn/row-tx {}
                     {:seon.schema/key key :seon.schema/form form}]
                    [:db.fn/call
                     (fn [during]
                      (reset! observed
                       {:seon.test/form
                        (seon.db/q '[:find ?form . :in $ ?key
                                     :where [?e :seon.schema/key ?key]
                                            [?e :seon.schema/form ?form]] during key)
                        :seon.test/projected-form
                        (get (:seon.schema.projection/forms
                              (seon.schema/projection-from-database
                               during (seon.db/carried-projection during))) key)
                        :seon.test/cache-identity (db/committed-cache-identity during)})
                      (#'seon.turn/row-tx during {}
                       {:seon.program/delete-identities [[:seon.schema/key key]]}))]]})
         after (seon.db/db connection)
         forms (:seon.schema.projection/forms
                (seon.schema/projection-from-database after (seon.db/carried-projection after)))]
    (is (some? (:db-after result)) (pr-str result))
    (is (= form (:seon.test/form @observed)))
    (is (= schema-form (:seon.test/projected-form @observed)))
    (is (nil? (:seon.test/cache-identity @observed)))
    (is (= {:seon.schema/key key}
           (seon.db/pull after [:seon.schema/key :seon.schema/form] [:seon.schema/key key])))
    (is (not (contains? (:schema after) key)))
    (is (map? forms))
    (is (not (contains? forms key)))))))
