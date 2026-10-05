(ns seon.error.resolution-test
  "When a fault resolves by its producer (`seon.error/resolution`): a green stored run of its
  task's test after the change, or a deleted producer."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.error :as error]
            [seon.error.attribution-test :refer [context signature-row world]]
            [seon.fault :as fault]
            [seon.task :as task]
            [seon.test-support :as test-support]))

(defn- fault-in
  "Records a fault `producer` produced as of another digest; answers its signature and its task's handle."
  [connection environment producer]
  (let [receipt (fault/fault! environment
                              {:seon.error/at (java.util.Date.) :seon.error/layer ::boundary
                               :seon.error/operation producer :seon.error/message "refused"
                               :seon.error/producer producer
                               :seon.error/producer-digest (apply str (repeat 64 "0"))
                               :seon.task/refused :unproven :seon.task/holder (symbol (namespace producer))}
                              (assoc context :seon.error/declared-schema :seon.task/refused-error))
        signature (:seon.error/signature receipt)]
    [signature (-> (signature-row connection signature) :seon.task/_faults first :seon.task/id)]))

(defn- define-in!
  "Writes `producer` as a stored declaration, and the test `tested` naming `handle` with one stored run of
  `failures` failures, in that order."
  [connection producer tested handle failures]
  (let [namespace-name (symbol (namespace producer))
        source #(str "(" % ")")]
    (test-support/transacted! connection [(test-support/namespace-entity (db/db connection) namespace-name)
                                          (test-support/program-fn-entity (db/db connection) producer
                                                                          (source (str "defn " (name producer) " [] 1")))])
    (when tested
      (test-support/transacted! connection [(test-support/program-entity
                                             (db/db connection) [:seon.test/sym tested]
                                             (str "(deftest ^{:seon/tasks #{\"" handle "\"}} " (name tested) " (is true))"))])
      (test-support/transacted!
       connection
       [{:seon.test.run/id (str (random-uuid)) :seon.test.run/at (java.util.Date.)
         :seon.test.run/branch (get-in (db/schema-database (db/db connection)) [:config :branch])
         :seon.test.run/members [{:seon.test.member/symbol tested :seon.test.member/reasons #{:named}
                                  :seon.test.member/completed-tx "datomic.tx" :seon.test.member/terminated-tx "datomic.tx"
                                  :seon.test.member/pass-count 1 :seon.test.member/fail-count failures
                                  :seon.test.member/error-count 0}]}]))))

(deftest a-fault-resolves-by-a-green-stored-run-or-a-deleted-producer
  (test-support/with-database
   (fn [connection]
     (let [environment (world connection "attribution-resolution")
           open? #(nil? (error/resolution (db/db connection) %))
           resolved-by #(:seon.error/resolved-by (error/resolution (db/db connection) %))
           scenario (fn [producer failures]
                      (let [[signature handle] (fault-in connection environment producer)]
                        (define-in! connection producer (symbol (namespace producer) "proves") handle failures)
                        [signature handle]))
           [green] (scenario 'attribution.green/target 0)
           [red] (scenario 'attribution.red/target 1)
           [deleted handle] (fault-in connection environment 'attribution.deleted/target)]
       (is (string? handle) "the producer's task answers the fault")
       (is (= :seon.task/proof (resolved-by green))
           "a changed producer whose task's test has a green stored run resolves")
       (is (open? red) "a changed producer whose task's test is red stays open")
       (is (= :seon.program/definition-digest (resolved-by deleted))
           "a producer the program no longer defines is not open")
       (is (nil? (task/resolution (db/db connection) (-> (signature-row connection red) :seon.task/_faults first :seon.task/id)))
           "so its task stays open")))))
