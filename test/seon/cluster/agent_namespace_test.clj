(ns seon.cluster.agent-namespace-test
  "Namespace assignment is a non-unique ref; stewardship is the namespace's
  own `:seon.ns/steward` fact: creation, sharing, reassignment, oversight."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.cluster.agent :as agent]
            [seon.problems :as problems]
            [seon.test-support :as test-support]))

(defn- found [connection]
  (problems/problems @connection
                     {}))

(deftest creation-assigns-a-namespace-and-stewards-it
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (test-support/transacted!
                   connection
                   (agent/creation-tx
                    {:seon.agent/id "alice"
                     :seon.cluster/name "test"
                     :seon.ns/name 'my.agents.alice}))
      (is (= "alice" (agent/steward-of @connection 'my.agents.alice)))
      (is (nil? (agent/steward-of @connection 'my.agents.nobody)))
      (is (= 'my.agents.alice
             (db/q '[:find ?name .
                    :where
                    [?agent :seon.agent/id "alice"]
                    [?agent :seon.agent/namespace ?namespace]
                    [?namespace :seon.ns/name ?name]]
                  @connection))))))

(deftest reassignment-is-an-ordinary-cardinality-one-transaction
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (test-support/transacted!
                   connection
                   (agent/creation-tx
                    {:seon.agent/id "alice"
                     :seon.cluster/name "test"
                     :seon.ns/name 'my.agents.alice}))
      (test-support/transacted! connection
                              [{:seon.ns/name 'my.agents.reassigned}
                               {:seon.agent/id "alice"
                                :seon.agent/namespace
                                [:seon.ns/name 'my.agents.reassigned]}])
      (is (= 'my.agents.reassigned
             (db/q '[:find ?name .
                    :where
                    [?agent :seon.agent/id "alice"]
                    [?agent :seon.agent/namespace ?namespace]
                    [?namespace :seon.ns/name ?name]]
                  @connection))
          "assignment moved")
      (is (= "alice" (agent/steward-of @connection 'my.agents.alice))
          "stewardship is the namespace's fact and does not follow the
          agent's assignment")
      (is (nil? (agent/steward-of @connection 'my.agents.reassigned))
          "a namespace created by an ordinary transaction has no steward"))))

(deftest one-namespace-may-be-assigned-to-two-agents
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (test-support/transacted!
                   connection
                   (agent/creation-tx
                    {:seon.agent/id "alice"
                     :seon.cluster/name "test"
                     :seon.ns/name 'my.agents.shared}))
      (let [result
            (db/transact!
             connection
             (agent/creation-tx
              {:seon.agent/id "bob"
               :seon.cluster/name "test"
               :seon.ns/name 'my.agents.shared}))]
        (is (nil? (:seon.error/kind result))
            "a second agent on one namespace is admitted")
        (is (= #{"alice" "bob"}
               (set (db/q '[:find [?agent-id ...]
                           :in $ ?namespace-name
                           :where
                           [?namespace :seon.ns/name ?namespace-name]
                           [?agent :seon.agent/namespace ?namespace]
                           [?agent :seon.agent/id ?agent-id]]
                         @connection 'my.agents.shared)))
            "both agents are assigned the one namespace")
        (is (= "alice" (agent/steward-of @connection 'my.agents.shared))
            "the first creator remains the steward; the second does not
            displace it")))))

;;; OWNERSHIP IS THE STEWARD FACT, AND ONLY THAT. The problem line used to
;;; invert `:seon.agent/namespace`, which is assignment: it is not
;;; unique, so `example.assigned` below — a namespace an agent merely works
;;; in, that nobody stewards — answered "owned" and the problem the report
;;; exists to raise went silent. That is the absence-reads-as-health class.
(deftest source-bearing-namespaces-without-a-steward-derive-one-problem-line
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (test-support/transacted! connection
                              [{:seon.ns/name 'example.unowned
                                :seon.ns/source "(ns example.unowned)"
                                :seon.schema.admission/source :agent}
                               {:seon.ns/name 'example.assigned
                                :seon.ns/source "(ns example.assigned)"
                                :seon.schema.admission/source :agent}
                               {:seon.ns/name 'example.owned
                                :seon.ns/source "(ns example.owned)"
                                :seon.schema.admission/source :agent}
                               {:seon.agent/id "owner"

                                :seon.agent/namespace
                                [:seon.ns/name 'example.owned]}
                               {:seon.agent/id "worker"

                                :seon.agent/namespace
                                [:seon.ns/name 'example.assigned]}])
      (test-support/transacted! connection
                              [[:db/add [:seon.ns/name 'example.owned] :seon.ns/steward
                                [:seon.agent/id "owner"]]])
      (let [value (found connection)
            log-line (problems/log-report value)]
        (is (= [{:seon.ns/name 'example.assigned}
                {:seon.ns/name 'example.unowned}]
               (:seon.problems/unowned-namespaces value))
            "an assigned namespace nobody stewards is still unstewarded")
        (is (= "owner" (agent/steward-of @connection 'example.owned)))
        (is (nil? (agent/steward-of @connection 'example.assigned)))
        (is (str/includes? log-line
                           "unstewarded-namespace namespace=example.unowned"))
        (is (str/includes? log-line
                           "unstewarded-namespace namespace=example.assigned"))
        (is (not (str/includes? log-line "example.owned")))))))
