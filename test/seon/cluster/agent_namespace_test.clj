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
                              [(test-support/namespace-entity @connection 'my.agents.reassigned)
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
        (is (some? (:db-after result))
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

;;; A failed read is not "nobody is assigned": `ensure-namespace-owner!` creates
;;; an owner agent on an empty answer, so an unreadable database must refuse.
(deftest assignment-reads-refuse-an-unreadable-database-and-answer-none-only-when-none
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection "test")
      (test-support/transacted! connection
                                (agent/creation-tx
                                 {:seon.agent/id "alice"
                                  :seon.cluster/name "test"
                                  :seon.ns/name 'my.agents.alice}))
      (is (= ["alice"] (agent/assigned-to @connection 'my.agents.alice)))
      (is (= [] (agent/assigned-to @connection 'my.agents.nobody)))
      ;; The invalid-read shape `seon.db/db` gives an unusable source (db.clj `db`).
      (let [unreadable (assoc @connection :seon.db/invalid-read true
                              :seon.db/refused-read-operation 'seon.db/db)
            refusal (try (agent/assigned-to unreadable 'my.agents.alice)
                         (catch clojure.lang.ExceptionInfo error (ex-data error)))]
        (is (true? (:seon.db/invalid-read refusal)))
        (is (string? (:seon.error/message refusal)))
        (testing "steward-of: nil only when no steward, a refusal when unreadable"
          (is (= "alice" (agent/steward-of @connection 'my.agents.alice)))
          (is (nil? (agent/steward-of @connection 'my.agents.nobody)))
          (is (true? (:seon.db/invalid-read
                      (try (agent/steward-of unreadable 'my.agents.alice)
                           (catch clojure.lang.ExceptionInfo error (ex-data error)))))))))))

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
                              (into [(test-support/namespace-entity @connection 'example.unowned)
                                     (test-support/namespace-entity @connection 'example.assigned)
                                     (test-support/namespace-entity @connection 'example.owned)]
                                    (test-support/agents-tx @connection ["owner" "worker"])))
      ;; An agent that works in a namespace it did not create stewards nothing.
      (test-support/transacted! connection
                              [{:seon.agent/id "owner"
                                :seon.agent/namespace [:seon.ns/name 'example.owned]}
                               {:seon.agent/id "worker"
                                :seon.agent/namespace [:seon.ns/name 'example.assigned]}])
      (test-support/transacted! connection
                              [[:db/add [:seon.ns/name 'example.owned] :seon.ns/steward
                                [:seon.agent/id "owner"]]])
      (let [value (found connection)
            html (pr-str (problems/html-report value))
            ;; The test world also carries default's agent namespace entities
            ;; without their agents (S2c); judge the fixture's own names.
            own #{'example.unowned 'example.assigned 'example.owned}]
        (is (= [{:seon.ns/name 'example.assigned}
                {:seon.ns/name 'example.unowned}]
               (filterv (comp own :seon.ns/name) (:seon.problems/unowned-namespaces value)))
            "an assigned namespace nobody stewards is still unstewarded")
        (is (= "owner" (agent/steward-of @connection 'example.owned)))
        (is (nil? (agent/steward-of @connection 'example.assigned)))
        (is (str/includes? html "example.unowned"))
        (is (str/includes? html "example.assigned"))
        (is (not (str/includes? html "example.owned")))))))
