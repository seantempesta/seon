(ns seon.transact-feedback-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(defn- refused
  [connection transaction attribute offending path]
  (let [before (:t @connection)
        result (db/transact! connection transaction)]
    (is (= :seon.db/invalid-write (:seon.error/kind result)) (pr-str result))
    (is (= attribute (:seon.db/attribute result)))
    (is (= offending (:seon.db/offending result)))
    (is (= path (:seon.db/path result)))
    (is (= before (:t @connection)) "a refusal commits nothing")
    (is (= :database-write (get-in result [:seon.error/data :seon.error/diagnostic-layer])))
    result))

(deftest bad-value-type
  (test-support/with-database
   (fn [connection]
     (doseq [[transaction path]
             [[[{ :my.plan.item/id "feedback/type" :my.plan.item/title 42}]
               [0 :my.plan.item/title]]
              [[[:db/add "feedback/type" :my.plan.item/title 42]] [0 3]]]]
       (let [result (refused connection transaction :my.plan.item/title 42 path)]
         (is (= (get (:seon.schema.projection/forms (schema/handed-projection))
                     :my.plan.item/title)
                (:seon.schema/form result)))
         (is (str/starts-with? (db/render-rejection-ai result) "Expected: [:string"))
         (is (str/includes? (db/render-rejection-ai result) "\nGot: 42"))))
     (testing "the supplied projection owns constraints on the same attribute"
       (let [forms (:seon.schema.projection/forms (schema/handed-projection))
             projection (schema/declaration-projection
                         (assoc forms :my.plan.item/title [:string {:min 8}]))]
         (schema/call-with-projection
          projection
          #(refused connection [[:db/add "feedback/projected" :my.plan.item/title "short"]]
                    :my.plan.item/title "short" [0 3])))))))

(deftest unknown-attribute-with-candidates
  (test-support/with-database
   (fn [connection]
     (doseq [[transaction path]
             [[[{ :my.plan.item/typo "x"}] [0 :my.plan.item/typo]]
              [[[:db/add "feedback/unknown" :my.plan.item/typo "x"]] [0 3]]]]
       (let [result (refused connection transaction :my.plan.item/typo "x" path)]
         (is (some #{:my.plan.item/title} (:seon.db/registered-candidates result))))))))

(deftest missing-required-identity-member
  (test-support/with-database
   (fn [connection]
     (let [result (refused connection [{:my.plan.item/id "feedback/missing"}]
                           :my.plan.item/title :seon.error/unknown [0 :my.plan.item/title])]
       (is (= :map (first (:seon.db/entity-form result))))
       (is (some #(= [:my.plan.item/title :my.plan.item/title] %)
                 (:seon.db/entity-form result)))))))

(deftest nested-component-and-lookup-violations
  (test-support/with-database
   (fn [connection]
     (testing "a nested component reports its own attribute and complete path"
       (refused connection
                [{:seon.agent/id "feedback/nested"
                  :seon.agent/plan {:my.plan/steps
                                    [{:my.plan.item/id "feedback/child"
                                      :my.plan.item/title false}]}}]
                :my.plan.item/title false
                [0 :seon.agent/plan :my.plan/steps 0 :my.plan.item/title]))
     (testing "a lookup ref validates the identity value, including its nesting"
       (refused connection
                [[:db/add "feedback/ref" :my.plan.item/agent [:seon.agent/id 42]]]
                :seon.agent/id 42 [0 3 1])))))

(deftest tempids-and-current-transaction-are-admitted
  (test-support/with-database
   (fn [connection]
     (let [result
           (db/transact!
            connection
            [{:db/id -1 :seon.agent/id "feedback/tempid"
              :my.plan.item/position 4}
             {:db/id "feedback/step" :my.plan.item/id "feedback/step"
              :my.plan.item/title "Valid" :my.plan.item/agent -1
              :my.plan.item/needs #{"feedback/next"}}
             {:db/id "feedback/next" :my.plan.item/id "feedback/next"
              :my.plan.item/title "Next"}
             [:db/add "feedback/step" :my.plan.item/needs "datomic.tx"]])]
       (is (nil? (:seon.error/kind result)) (pr-str result))
       (is (= #{"feedback/step" "feedback/next"}
              (set (db/q '[:find [?id ...] :where [_ :my.plan.item/id ?id]] @connection))))
       (is (some? (db/q '[:find ?instant . :where
                          [?step :my.plan.item/id "feedback/step"]
                          [?step :my.plan.item/needs ?tx]
                          [?tx :db/txInstant ?instant]] @connection)))))))

(deftest datahike-refusals-retain-their-classification
  (test-support/with-database
   (fn [connection]
     (let [accepted
           (db/transact! connection
                         [{:seon.ns/name 'my.agents.feedback-target}
                          [:db/add "feedback/owner" :seon.cluster.eval/id "feedback/owner"]
                          [:db/add "feedback/owner" :seon.cluster.eval/refreshes
                           [:seon.ns/name 'my.agents.feedback-target]]])
           rejected
           (db/transact! connection
                         [[:db/add "feedback/contender" :seon.cluster.eval/id "feedback/contender"]
                          [:db/add "feedback/contender" :seon.cluster.eval/refreshes
                           [:seon.ns/name 'my.agents.feedback-target]]])]
       (is (some? (:db-after accepted)) (pr-str accepted))
       (is (= :seon.db/rejected (:seon.error/kind rejected)))
       (is (= :transact/unique (get-in rejected [:seon.error/data :error])))
       (is (= [:seon.cluster.eval/id "feedback/owner"]
              (get-in rejected [:seon.error/data :seon.db/conflict-owner])))))))
