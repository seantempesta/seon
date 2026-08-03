(ns seon.cluster.agent-namespace-test
  "Namespace assignment is a ref: creation, reassignment, and oversight."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster.agent :as agent]
            [seon.problems :as problems]
            [seon.test-support :as test-support]))

(defn- found [connection]
  (problems/problems @connection
                     {:seon.cluster.run/live-processes #{}}))

(deftest creation-assigns-one-queryable-namespace-owner
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (d/transact
       connection
       (agent/creation-tx
        {:seon.cluster.agent/id "alice"
         :seon.cluster/name "test"
         :seon.ns/name 'my.agents.alice}))
      (is (= "alice" (agent/owner-of @connection 'my.agents.alice)))
      (is (nil? (agent/owner-of @connection 'my.agents.nobody)))
      (is (= 'my.agents.alice
             (d/q '[:find ?name .
                    :where
                    [?agent :seon.cluster.agent/id "alice"]
                    [?agent :seon.cluster.agent/namespace ?namespace]
                    [?namespace :seon.ns/name ?name]]
                  @connection))))))

(deftest reassignment-is-an-ordinary-cardinality-one-transaction
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (d/transact
       connection
       (agent/creation-tx
        {:seon.cluster.agent/id "alice"
         :seon.cluster/name "test"
         :seon.ns/name 'my.agents.alice}))
      (d/transact connection
                  [{:seon.ns/name 'my.agents.reassigned}
                   {:seon.cluster.agent/id "alice"
                    :seon.cluster.agent/namespace
                    [:seon.ns/name 'my.agents.reassigned]}])
      (is (nil? (agent/owner-of @connection 'my.agents.alice)))
      (is (= "alice"
             (agent/owner-of @connection 'my.agents.reassigned))))))

(deftest one-namespace-cannot-be-assigned-to-two-agents
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (d/transact
       connection
       (agent/creation-tx
        {:seon.cluster.agent/id "alice"
         :seon.cluster/name "test"
         :seon.ns/name 'my.agents.shared}))
      (let [refusal
            (test-support/refusal-data
             #(d/transact
               connection
               [{:seon.cluster.agent/id "bob"
                 :seon.cluster.agent/namespace
                 [:seon.ns/name 'my.agents.shared]}]))]
        (is (= :transact/unique (:error refusal)))
        (is (= "alice" (agent/owner-of @connection 'my.agents.shared)))))))

(deftest source-bearing-namespaces-without-owners-derive-one-problem-line
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (d/transact connection
                  [{:seon.ns/name 'example.unowned
                    :seon.ns/source "(ns example.unowned)"
                    :seon.schema.admission/source :agent}
                   {:seon.ns/name 'example.owned
                    :seon.ns/source "(ns example.owned)"
                    :seon.schema.admission/source :agent}
                   {:seon.cluster.agent/id "owner"
                    :seon.cluster.agent/cluster [:seon.cluster/name "test"]
                    :seon.cluster.agent/namespace
                    [:seon.ns/name 'example.owned]}])
      (let [value (found connection)
            log-line (problems/log-report value)]
        (is (= [{:seon.ns/name 'example.unowned}]
               (:seon.problems/unowned-namespaces value)))
        (is (str/includes? log-line
                           "unowned-namespace namespace=example.unowned"))
        (is (not (str/includes? log-line "example.owned")))))))
