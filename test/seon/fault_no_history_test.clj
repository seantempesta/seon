(ns seon.fault-no-history-test
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as support]))

(deftest fault-occurrence-churn-drops-superseded-history
  (let [count-declaration
        (schema.datahike/malli->datahike-attr-in
         (schema/handed-projection) :seon.error.occurrence/count)]
    (support/with-database
      {::support/extra-schema [count-declaration]}
      (fn [connection]
        (let [agent-id "fault-no-history-agent"
              first-at #inst "2026-09-23T00:00:00Z"
              later-at #inst "2026-09-23T00:00:01Z"]
          (support/transacted!
           connection
           [{:seon.agent/id agent-id
             :seon.error.occurrence/count 1
             :seon.error.occurrence/first-at first-at}])
          (support/transacted!
           connection
           [{:seon.agent/id agent-id
             :seon.error.occurrence/count 2
             :seon.error.occurrence/first-at later-at}])
          (let [current-count-datoms
                (db/q '[:find ?count
                        :in $ ?agent-id
                        :where
                        [?agent :seon.agent/id ?agent-id]
                        [?agent :seon.error.occurrence/count ?count]]
                      (db/db connection) agent-id)
                count-history
                (db/q '[:find ?count ?added
                        :in $ ?agent-id
                        :where
                        [?agent :seon.agent/id ?agent-id]
                        [?agent :seon.error.occurrence/count ?count _ ?added]]
                      (db/history (db/db connection)) agent-id)
                sibling-history
                (db/q '[:find ?at ?added
                        :in $ ?agent-id
                        :where
                        [?agent :seon.agent/id ?agent-id]
                        [?agent :seon.error.occurrence/first-at ?at _ ?added]]
                      (db/history (db/db connection)) agent-id)]
            (is (= #{[2]} current-count-datoms)
                "one current count datom remains after replacement")
            (is (= #{[2 true]} count-history)
                "the superseded count assertion and retraction are not retained")
            (is (contains? sibling-history [first-at true])
                "a sibling without noHistory retains its original assertion")
            (is (contains? sibling-history [first-at false])
                "a sibling without noHistory retains its retraction")))))))
