(ns seon.cluster.agent-identity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.agent :as my.agent]
            [seon.db :as db]
            [seon.config :as config]
            [seon.env :as env]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]))

(def ^:private agent-id "identity-root")
(def ^:private namespace-name 'my.agents.identity-root)
(def ^:private cluster-name "identity-cluster")

(defn- with-agent
  [body]
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection cluster-name)
      (test-support/transacted!
                   connection
                   (vec (agent/creation-tx
                         {:seon.agent/id agent-id
                          :seon.ns/name namespace-name
                          :seon.cluster/name cluster-name})))
      (body connection))))

(defn- described
  "The identity card's description list as data: each term's shown leaves."
  {:malli/schema [:=> [:cat :seon.schema/value] [:map-of :string [:vector :seon.schema/value]]]}
  [html]
  (into {} (for [[tag & children] (test-support/hiccup-elements html)
                 :when (= :div tag)
                 :let [[term description] (filter vector? children)]
                 :when (= :dt (first term))]
             [(second term) (vec (test-support/hiccup-leaves description))])))

(deftest identity-renders-from-current-database-facts
  (with-agent
    (fn [connection]
      (let [unit {:seon.db/db @connection
                  :seon.agent/id agent-id}
            html (agent/render-identity-html unit)]
        (is (= "Agent     identity-root\nNamespace my.agents.identity-root\nCluster   identity-cluster"
               (agent/whoami {:seon.db/db @connection :seon.agent/id agent-id})))
        (is (= [agent-id] (get (described html) "Agent")))
        (is (= [(str namespace-name)] (get (described html) "Namespace")))
        (is (contains? (described html) "Steward") "the card names the namespace's steward")
        (test-support/transacted! connection
                                  [[:db/add [:seon.agent/id agent-id]
                                    :seon.agent/cluster "renamed-cluster"]])
        (is (str/includes? (agent/whoami {:seon.db/db @connection :seon.agent/id agent-id})
                           "Cluster   renamed-cluster")
            "the result follows the supplied database, not a captured identity (an agent names its cluster, 648369489)")))))

(deftest the-root-agent-renders-as-one-node-without-the-fleet
  ;; Class regression: the agent pair renders its own node (identity, tasks,
  ;; status); the fleet table belonged to the fleet page and needed a connection.
  (test-support/with-database
    (fn [connection]
      (test-support/seed-cluster! connection cluster-name)
      (test-support/transacted! connection
                                (vec (agent/creation-tx {:seon.agent/id "root" :seon.ns/name 'my.agents.root
                                                         :seon.cluster/name cluster-name})))
      (let [html (agent/render-identity-html {:seon.db/db @connection :seon.agent/id "root"})
            card (described html)]
        (is (= ["None"] (get card "Tasks")))
        (is (re-matches #"(Idle|Turn open) · \d+ turns?" (str (first (get card "Status"))))
            "the node carries its own status")
        (is (not (contains? (test-support/hiccup-classes html) "seon-root-agents")) "no fleet table")))))

(deftest identity-map-and-omitted-arguments-use-the-same-function
  (with-agent
    (fn [connection]
      (test-support/transacted! connection
                                (filterv :seon.call-preparation/key
                                         (:seon.config/initialization
                                          (config/compile-manifest {:seon.boot/cluster-name cluster-name}))))
      (test-support/transacted! connection (test-support/agent-tx @connection "supplied"))
      (let [database @connection
            indexed (db/pull database
                             '[:seon.fn/sym
                               {:seon.fn/arities
                                [:seon.fn.arity/argument-count
                                 :seon.fn.arity/input
                                 :seon.fn.arity/output]}]
                             [:seon.fn/sym 'seon.cluster.agent/whoami])
            arities (:seon.fn/arities indexed)
            expected (agent/whoami {:seon.db/db database :seon.agent/id agent-id})
            ;; A built context holds no program until it acquires one: evaluate
            ;; stopped acquiring on demand in 04e329e21.
            acquired (let [built (sci.eval/cluster-ctx database connection)]
                       (sci.eval/acquire! {:seon.sci.eval/ctx built :seon.db/db database})
                       built)
            environment (env/refuse-incomplete-environment!
                         (env/environment
                          {:seon.boot/cluster-name cluster-name
                           :seon.db/connection connection
                           :seon.schema/projection (:seon.schema/projection acquired)}))
            context (env/carry-state acquired (env/environment-state environment))
            forked (sci.eval/fork-for-turn
                    {:seon.sci.eval/ctx context
                     :seon.db/db database
                     :seon.db/connection connection
                     :seon.agent/id agent-id})
            live (:seon.sci.eval/ctx forked)
            evaluate
            (fn [source]
              (test-support/evaluate
               {:seon.sci.eval/ctx live
                :seon.agent/id agent-id
                :seon.sci.admit/caps
                (config/result-caps (test-support/effective-config))
                :seon.sci.eval/time-limit-ms 5000
                :seon.cluster.eval/source source
                :seon.cluster.eval/ns [:seon.ns/name namespace-name]}))]
        (is (= [1] (mapv :seon.fn.arity/argument-count arities))
            "the argumentless SCI call uses the one declared request-map arity")
        (is (every? :seon.fn.arity/output arities))
        (is (some? live))
        (let [result (evaluate (str (:seon.repl/form (agent/identity-form {:seon.agent/id agent-id}))))
              value (:seon.sci.admit/value result)]
          ;; `identity-form` emits the raw identity pull (d6377ac39), so the
          ;; value carries the stored attributes, not the :my.agent projection.
          (is (= agent-id (:seon.agent/id value)))
          (is (= namespace-name (get-in value [:seon.agent/namespace :seon.ns/name])))
          (is (= agent-id (get-in value [:seon.agent/namespace :seon.ns/steward
                                         :seon.agent/id])))
          (is (empty? (:seon.cluster.eval/output result))))
        (is (= {:seon.config.eval/time-limit-ms 1234}
               (:seon.sci.admit/value
                (evaluate "(my.agent/settings! {:seon.config.eval/time-limit-ms 1234})"))))
        ;; Turn accounting left the opening reads with 0dca8534e: settings are
        ;; the overrides, and remaining turns are read on demand.
        (is (= {:seon.config.eval/time-limit-ms 1234}
               (:seon.sci.admit/value (evaluate "(my.agent/settings)"))))
        (let [dials (:seon.sci.admit/value (evaluate (str (seon.agent/render-settings-form {:seon.agent/id agent-id}))))]
          (is (map? dials))
          (is (= 1234 (:seon.config.eval/time-limit-ms dials)))
          (is (not (contains? dials :my.agent/turns-left))))
        (is (= [] (:seon.sci.admit/value (evaluate "(my.test/run)"))))
        (is (= {:my.turn/disposition :wait :my.turn/note "Session complete."}
               (:seon.sci.admit/value (evaluate "(my.agent/done)"))))
        (is (= expected (:seon.sci.admit/value (evaluate "(seon.cluster.agent/whoami)"))))
        (is (= "Agent     supplied\nNamespace my.agents.supplied\nCluster   identity-cluster"
               (:seon.sci.admit/value
                (evaluate "(seon.cluster.agent/whoami {:seon.agent/id \"supplied\"})")))
            "the supplied map wins over current-agent defaults")
        (is (= "Agent     supplied\nNamespace my.agents.supplied\nCluster   identity-cluster"
               (agent/whoami {:seon.db/db database :seon.agent/id "supplied"})))))))
