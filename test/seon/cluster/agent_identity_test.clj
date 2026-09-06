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

(deftest identity-form-reproduces-the-rendered-agent
  (with-agent
    (fn [connection]
      (let [unit {:seon.db/db @connection
                  :seon.cluster.agent/id agent-id}
            entry (agent/identity-form unit)
            result (binding [db/*conn* connection]
                     (eval (:seon.repl/form entry)))]
        (is (= ";; Who am I?" (:seon.repl/comment entry)))
        (is (= {:seon.cluster.agent/id agent-id
                :seon.cluster.agent/namespace
                {:seon.ns/name namespace-name}
                :seon.cluster.agent/cluster
                {:seon.cluster/name cluster-name}}
               result))
        (is (= (agent/render-identity-text result)
               (str "Agent \"identity-root\"\n"
                    "Namespace my.agents.identity-root\n"
                    "Cluster \"identity-cluster\"")))))))

(deftest identity-ai-source-and-terminal-value-are-separated
  (let [unit {:seon.cluster.agent/id agent-id}
        queried {:seon.cluster.agent/id agent-id
                 :seon.cluster.agent/namespace
                 {:seon.ns/name namespace-name}
                 :seon.cluster.agent/cluster
                 {:seon.cluster/name cluster-name}}
        source (agent/render-identity-ai unit)]
    (is (= (str ";; Who am I?\n"
                (pr-str
                 (list 'seon.cluster.agent/render-identity-text
                       (:seon.repl/form (agent/identity-form unit)))))
           source)
        "the preview is an authored comment plus one executable query form")
    (is (= (str "Agent \"identity-root\"\n"
                "Namespace my.agents.identity-root\n"
                "Cluster \"identity-cluster\"")
           (agent/render-identity-text queried))
        "the terminal formatter returns only the queried identity value")
    (let [database-error
          {:seon.error/kind :seon.db/pull-failed
           :seon.error/message "identity query failed"}]
      (is (= database-error (agent/render-identity-text database-error))
          "the terminal formatter preserves a flat query error"))
    (is (not (str/includes? source "Namespace my.agents.identity-root"))
        "the source does not fabricate its eventual query result")))

(deftest identity-renders-for-agents-and-humans
  (with-agent
    (fn [connection]
      (let [unit {:seon.db/db @connection
                  :seon.cluster.agent/id agent-id}
            ai (agent/render-identity-ai unit)
            html (agent/render-identity-html unit)]
        (testing "AI identity is concise and explicit"
          (is (str/includes? ai (pr-str agent-id)))
          (is (str/includes? ai
                             "seon.cluster.agent/render-identity-text"))
          (is (str/includes? ai "seon.db/pull")))
        (testing "HTML identity is a labelled card"
          (is (str/includes? (pr-str html) agent-id))
          (is (str/includes? (pr-str html) (str namespace-name)))
          (is (str/includes? (pr-str html) cluster-name)))))))

(deftest identity-rendering-keeps-partial-data-and-read-errors-visible
  (testing "an id remains visible while optional connections are absent"
    (let [unit {:seon.cluster.agent/id agent-id
                :seon.render/value {:seon.cluster.agent/id agent-id}}]
      (is (str/includes? (agent/render-identity-ai unit) agent-id))
      (is (str/includes? (pr-str (agent/render-identity-html unit)) agent-id))))
  (testing "a database refusal remains a typed rendered refusal"
    (let [database-error
          (db/pull {:selector [:seon.cluster.agent/id]
                    :eid [:seon.cluster.agent/id agent-id]})
          unit {:seon.db/db database-error
                :seon.cluster.agent/id agent-id}]
      (is (:seon.error/kind database-error))
      (is (str/includes? (agent/render-identity-ai unit)
                         (pr-str agent-id)))
      (is (= database-error (agent/render-identity-html unit))))))
