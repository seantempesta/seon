(ns seon.cluster.agent-identity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(def ^:private agent-id "identity-root")
(def ^:private namespace-name 'my.agents.identity-root)
(def ^:private cluster-name "identity-cluster")

(defn- with-agent
  [body]
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       (into [{:seon.cluster/name cluster-name}]
             (agent/creation-tx
              {:seon.cluster.agent/id agent-id
               :seon.ns/name namespace-name
               :seon.cluster/name cluster-name})))
      (body connection))))

(deftest identity-renders-from-current-database-facts
  (with-agent
    (fn [connection]
      (let [unit {:seon.db/db @connection
                  :seon.cluster.agent/id agent-id}
            source (agent/render-identity-ai unit)
            html (agent/render-identity-html unit)]
        (is (= '(seon.cluster.agent/whoami) (read-string source)))
        (is (not (str/includes? source agent-id))
            "discovering identity cannot require already knowing it")
        (is (= "Agent     identity-root\nNamespace my.agents.identity-root\nCluster   identity-cluster"
               (agent/whoami @connection agent-id)))
        (is (str/includes? (pr-str html) agent-id))
        (is (str/includes? (pr-str html) (str namespace-name)))
        (is (str/includes? (pr-str html) cluster-name))
        (db/transact! connection
                      [[:db/add [:seon.cluster/name cluster-name]
                        :seon.cluster/name "renamed-cluster"]])
        (is (str/includes? (agent/whoami @connection agent-id)
                           "Cluster   renamed-cluster")
            "the result follows the supplied database, not a captured identity")))))

(deftest identity-rendering-keeps-partial-data-and-read-errors-visible
  (testing "an id remains visible while optional connections are absent"
    (let [unit {:seon.cluster.agent/id agent-id
                :seon.render/value {:seon.cluster.agent/id agent-id}}]
      (is (= '(seon.cluster.agent/whoami)
             (read-string (agent/render-identity-ai unit))))
      (is (str/includes? (pr-str (agent/render-identity-html unit)) agent-id))))
  (testing "a database refusal remains a typed rendered refusal"
    (let [database-error
          (db/pull {:selector [:seon.cluster.agent/id]
                    :eid [:seon.cluster.agent/id agent-id]})
          unit {:seon.db/db database-error
                :seon.cluster.agent/id agent-id}]
      (is (:seon.error/kind database-error))
      (is (= database-error (agent/render-identity-html unit))))))
