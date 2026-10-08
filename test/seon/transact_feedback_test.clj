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
                                 [{:seon.source/digest "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                                  [:db/add "reverse-turn" :seon.turn/id "reverse-turn"]
                                  {:seon.ai.attempt/id "reverse-attempt"
                                   :seon.ai.attempt/ordinal 0
                                   :seon.ai.attempt/at #inst "2026-01-01"
                                   :seon.ai/endpoint "reverse-endpoint"
                                   :seon.ai/model "reverse-model"
                                   :seon.ai.attempt/settings-edn "{}"
                                   :seon.turn/_attempts [:seon.turn/id "reverse-turn"]}])]
       (is (some? (:db-after accepted)))
       (is (= "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" (:seon.source/digest
                    (db/pull @connection [:seon.source/digest] [:seon.source/digest "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]))))
       (is (= "reverse-attempt"
              (db/q '[:find ?id . :where
                      [?turn :seon.turn/id "reverse-turn"]
                      [?turn :seon.turn/attempts ?attempt]
                      [?attempt :seon.ai.attempt/id ?id]] @connection))))
     (let [accepted (db/transact! connection
                                 [{:db/id [:seon.source/digest "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
                                   :seon.task/intent "attribute-only"}])]
       (is (some? (:db-after accepted)))
       (is (= "attribute-only"
              (:seon.task/intent (db/pull @connection [:seon.task/intent]
                                           [:seon.source/digest "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"])))))
     (let [before (:t @connection)
           refused (db/transact! connection [{:seon.cluster.eval/id "incomplete"}])
           wrong (db/transact! connection [{:seon.task/intent 42}])
           nested (db/transact! connection
                                [{:seon.source/digest "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                                  :seon.turn/_attempts [{:seon.turn/id 42}]}])]
       (is (string? (:seon.db.write.attempt/request-id refused)))
       (is (= [:seon.cluster.eval/run] (vec (rest (:seon.db/path refused)))))
       (is (int? (first (:seon.db/path refused))))
       (is (str/includes? (:seon.error/message refused) ":seon.cluster.eval/run"))
       (is (string? (:seon.db.write.attempt/request-id wrong)))
       (is (= [0 :seon.task/intent] (:seon.db/path wrong)))
       (is (= [0 :seon.turn/_attempts 0 :seon.turn/id] (:seon.db/path nested)))
       (is (= before (:t @connection)))))))

(defn- refused
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/transaction :qualified-keyword :seon.schema/value [:sequential :seon.schema/value]] :seon.db/error-result]}
  [connection transaction attribute offending path]
  (let [before (:t @connection)
        result (db/transact! connection transaction)]
    (is (string? (:seon.db.write.attempt/request-id result)))
    (is (= attribute (:seon.db/attribute result)))
    (is (= offending (:seon.db/offending result)))
    ;; a missing key's offending value is the entity missing it
    (if (map? offending)
      (do (is (int? (first (:seon.db/path result))))
          (is (= (rest path) (rest (:seon.db/path result)))))
      (is (= path (:seon.db/path result))))
    (is (= before (:t @connection)) "a refusal commits nothing")
    (is (= :seon.db/database-write (:seon.error/layer result)))
    result))

(deftest bad-value-type
  (test-support/with-database
   (fn [connection]
     (doseq [[transaction path]
             [[[(assoc (test-support/task-row "feedbacktype" "x") :seon.task/intent 42)]
               [0 :seon.task/intent]]
              [[[:db/add "feedback/type" :seon.task/intent 42]] [0 3]]]]
       (let [result (refused connection transaction :seon.task/intent 42 path)]
         (is (= (get (:seon.schema.projection/forms (schema/handed-projection))
                     :seon.task/intent)
                (:seon.schema/form result)))
         (is (= (:seon.error/message result) (:seon.error/message (db/render-rejection-ai result))))
         (is (= [42] (mapv :seon.error/offending (:seon.error/problems (db/render-rejection-ai result)))))))
     (testing "the supplied projection owns constraints on the same attribute"
       (let [forms (:seon.schema.projection/forms (schema/handed-projection))
             projection (schema/declaration-projection
                         (assoc forms :seon.task/intent [:string {:min 8}]))]
         (db/carry-connection-projection-state!
          connection (evaluation/projection-state @connection projection))
         (schema/call-with-projection
          projection
          #(refused connection [[:db/add "feedback/projected" :seon.task/intent "short"]]
                    :seon.task/intent "short" [0 3])))))))

(deftest unknown-attribute-with-candidates
  (test-support/with-database
   (fn [connection]
     (doseq [[transaction path]
             [[[{ :seon.task/typo "x"}] [0 :seon.task/typo]]
              [[[:db/add "feedback/unknown" :seon.task/typo "x"]] [0 3]]]]
       (let [result (refused connection transaction :seon.task/typo "x" path)]
         (is (some #{:seon.task/intent} (:seon.db/registered-candidates result))))))))

(deftest missing-required-identity-member
  (test-support/with-database
   (fn [connection]
     (let [result (refused connection [{:seon.task/id "feedbackmiss"}]
                           :seon.task/ns {:seon.task/id "feedbackmiss"}
                           [0 :seon.task/ns])]
       (is (= :map (first (:seon.db/entity-form result))))
       (is (some #(= [:seon.task/ns :seon.task/ns] %)
                 (:seon.db/entity-form result)))))))

(deftest unknown-key-suggestion-comes-from-present-attributes
  (test-support/with-database
   (fn [connection]
     (let [unknown (keyword "seon" "task/id")
           result (refused connection [(assoc (dissoc (test-support/task-row "feedbacktypo" "A real task") :seon.task/id)
                                          unknown "feedbacktypo")]
                           unknown "feedbacktypo" [0 unknown])
           shown (db/render-rejection-ai result)]
       (is (= [:seon.task/id] (:seon.db/registered-candidates result)))
       (is (= [unknown] (mapv :seon.error/offending (:seon.error/problems shown))))))))

(deftest nested-component-and-lookup-violations
  (test-support/with-database
   (fn [connection]
     (testing "a nested component reports its own attribute and complete path"
       (refused connection
                [(assoc (test-support/task-row "feedbacknest" "Nested")
                        :seon.task/steps [{:seon.step/position 0 :seon.step/text false :seon.step/status :open}])]
                :seon.step/text false
                [0 :seon.task/steps 0 :seon.step/text]))
     (testing "a lookup ref validates the identity value, including its nesting"
       (refused connection
                [[:db/add "feedback/ref" :seon.task/created-by [:seon.agent/id 42]]]
                :seon.agent/id 42 [0 3 1])))))

(deftest tempids-and-current-transaction-are-admitted
  (test-support/with-database
   (fn [connection]
     (let [result
           (db/transact!
            connection
            [{:db/id -1 :seon.agent/id "feedback/tempid"}
             (assoc (test-support/task-row "feedbackstep" "Valid")
                    :db/id "feedback/step" :seon.task/created-by -1
                    :seon.task/faults #{"feedback/next"})
             (assoc (test-support/task-row "feedbacknext" "Next")
                    :db/id "feedback/next" :seon.task/faults [[:seon.task/id "feedbackstep"]])
             (assoc (test-support/task-row "feedbacksing" "Single")
                    :seon.task/faults "feedback/step")
             [:db/add "feedback/step" :seon.task/faults "datomic.tx"]])]
       (is (some? (:db-after result)))
       (is (every? (set (db/q '[:find [?id ...] :where [_ :seon.task/id ?id]] @connection))
                   ["feedbackstep" "feedbacknext" "feedbacksing"]))
       (is (some? (db/q '[:find ?instant . :where
                          [?step :seon.task/id "feedbackstep"]
                          [?step :seon.task/faults ?tx]
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
                               [{:db/ident :seon.source/digest
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one
                                 :db/unique :db.unique/identity}])
                 written (db/transact! connection
                                       [{:seon.source/digest "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}])]
             (is (some? (:db-after installed)))
             (is (some? (:db-after written)))
             (is (= "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                    (db/q '[:find ?id . :where [_ :seon.source/digest ?id]]
                          @connection))))
           (finally
             (d/release connection)
             (d/delete-database configuration))))))))

(deftest ^{:seon/tasks #{'seon.db/uninstalled-attribute-candidates-are-alphabetical}}
  uninstalled-attribute-candidates-are-the-nearest-names
  (let [declarations (#'seon.db/installed-attribute-declarations (seon.db/db))]
    (is (= :seon.agent/settings (first (#'seon.db/registered-attribute-candidates declarations :seon.agent/setings))))
    (is (= :seon.agent/branch (first (#'seon.db/registered-attribute-candidates declarations :seon.agnet/branch))))))
