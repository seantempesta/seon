(ns seon.transact-feedback-test
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]
            [seon.sci.eval :as evaluation]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(deftest raw-write-maps-select-only-their-asserted-required-identity
  (test-support/with-database
   (fn [connection]
     (test-support/seed-cluster! connection "feedback")
     (test-support/transacted! connection
                              (agent/creation-tx {:seon.agent/id "feedback"
                                                  :seon.cluster/name "feedback"
                                                  :seon.ns/name 'feedback.agent}))
     (test-support/transacted! connection
                              (turn/open-tx {:seon.turn/id "reverse-turn"
                                             :seon.turn/agent [:seon.agent/id "feedback"]
                                             :seon.turn/opened-tx "datomic.tx"}))
     (let [accepted (db/transact! connection
                                 [{:seon.problems/id "x"}
                                  [:db/add "reverse-turn" :seon.turn/id "reverse-turn"]
                                  {:seon.problems/id "reverse-attempt"
                                   :seon.turn/_attempts [:seon.turn/id "reverse-turn"]}])]
       (is (some? (:db-after accepted)) (pr-str accepted))
       (is (= "x" (:seon.problems/id
                    (db/pull @connection [:seon.problems/id] [:seon.problems/id "x"]))))
       (is (= "reverse-attempt"
              (db/q '[:find ?id . :where
                      [?turn :seon.turn/id "reverse-turn"]
                      [?turn :seon.turn/attempts ?attempt]
                      [?attempt :seon.problems/id ?id]] @connection))))
     (let [accepted (db/transact! connection
                                 [{:db/id [:seon.problems/id "x"]
                                   :my.plan.item/title "attribute-only"}])]
       (is (some? (:db-after accepted)) (pr-str accepted))
       (is (= "attribute-only"
              (:my.plan.item/title (db/pull @connection [:my.plan.item/title]
                                           [:seon.problems/id "x"])))))
     (let [before (:t @connection)
           refused (db/transact! connection [{:seon.cluster.eval/id "incomplete"}])
           wrong (db/transact! connection [{:my.plan.item/title 42}])
           nested (db/transact! connection
                                [{:seon.problems/id "nested"
                                  :seon.turn/_attempts [{:seon.turn/id 42}]}])]
       (is (= :seon.db/invalid-write (:seon.error/kind refused)))
       (is (= [:seon.cluster.eval/run] (vec (rest (:seon.db/path refused)))))
       (is (int? (first (:seon.db/path refused))))
       (is (str/includes? (:seon.error/message refused) ":seon.cluster.eval/run"))
       (is (= :seon.db/invalid-write (:seon.error/kind wrong)))
       (is (= [0 :my.plan.item/title] (:seon.db/path wrong)))
       (is (= [0 :seon.turn/_attempts 0 :seon.turn/id] (:seon.db/path nested)))
       (is (= before (:t @connection)))))))

(defn- refused
  [connection transaction attribute offending path]
  (let [before (:t @connection)
        result (db/transact! connection transaction)]
    (is (= :seon.db/invalid-write (:seon.error/kind result)) (pr-str result))
    (is (= attribute (:seon.db/attribute result)))
    (is (= offending (:seon.db/offending result)))
    (if (= :seon.error/unknown offending)
      (do (is (int? (first (:seon.db/path result))))
          (is (= (rest path) (rest (:seon.db/path result)))))
      (is (= path (:seon.db/path result))))
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
         (is (str/starts-with? (db/render-rejection-ai result) "seon.db/transact! refused transaction data at"))
         (is (str/includes? (db/render-rejection-ai result) "got an integer 42. Fix:"))))
     (testing "the supplied projection owns constraints on the same attribute"
       (let [forms (:seon.schema.projection/forms (schema/handed-projection))
             projection (schema/declaration-projection
                         (assoc forms :my.plan.item/title [:string {:min 8}]))]
         (db/carry-connection-projection-state!
          connection (evaluation/projection-state @connection projection))
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

(deftest unknown-key-suggestion-comes-from-present-attributes
  (test-support/with-database
   (fn [connection]
     (let [unknown (keyword "my.plan" "item/id")
           result (refused connection [{unknown "feedback/typo"
                                       :my.plan.item/title "A real step"}]
                           unknown "feedback/typo" [0 unknown])
           shown (db/render-rejection-ai result)]
       (is (= [:my.plan.item/id] (:seon.db/registered-candidates result)))
       (is (str/includes? shown "Fix: Use :my.plan.item/id;") shown)))))

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
              :my.plan.item/title "Next"
              :my.plan.item/needs [:my.plan.item/id "feedback/step"]}
             {:my.plan.item/id "feedback/single" :my.plan.item/title "Single"
              :my.plan.item/needs "feedback/step"}
             [:db/add "feedback/step" :my.plan.item/needs "datomic.tx"]])]
       (is (nil? (:seon.error/kind result)) (pr-str result))
       (is (= #{"feedback/step" "feedback/next" "feedback/single"}
              (set (db/q '[:find [?id ...] :where [_ :my.plan.item/id ?id]] @connection))))
       (is (some? (db/q '[:find ?instant . :where
                          [?step :my.plan.item/id "feedback/step"]
                          [?step :my.plan.item/needs ?tx]
                          [?tx :db/txInstant ?instant]] @connection)))))))

(deftest non-temporal-stores-admit-writes-while-a-retention-rule-is-in-force
  ;; Retention snapshotting asked Datahike for `history` unconditionally,
  ;; and a `:keep-history? false` store answers "history is only allowed on
  ;; temporal indexed databases" — inside the writer, after admission, so
  ;; the refusal arrived as a failed transaction rather than a value. One
  ;; declared `:seon.db/append-only-after` rule was therefore enough to
  ;; break EVERY write to EVERY non-temporal store: the two platform stores
  ;; (seon.cluster.store-test, seon.cluster.registry-test) silently lost
  ;; their schema installation and then reported the attributes they had
  ;; just declared as uninstalled.
  (test-support/with-database
   (fn [_]
     (let [configuration {:store {:backend :memory :id (random-uuid)}
                          :keep-history? false
                          :schema-flexibility :write}]
       (d/create-database configuration)
       (let [connection (d/connect configuration)]
         (try
           (let [installed
                 (db/transact! connection
                               [{:db/ident :seon.problems/id
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one
                                 :db/unique :db.unique/identity}])
                 written (db/transact! connection
                                       [{:seon.problems/id "non-temporal"}])]
             (is (some? (:db-after installed)) (pr-str installed))
             (is (some? (:db-after written)) (pr-str written))
             (is (= "non-temporal"
                    (db/q '[:find ?id . :where [_ :seon.problems/id ?id]]
                          @connection))))
           (finally
             (d/release connection)
             (d/delete-database configuration))))))))
