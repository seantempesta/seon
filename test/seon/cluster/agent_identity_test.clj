(ns seon.cluster.agent-identity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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

(deftest identity-renders-from-current-database-facts
  (with-agent
    (fn [connection]
      (let [unit {:seon.db/db @connection
                  :seon.agent/id agent-id}
            html (agent/render-identity-html unit)]
        (is (= "Agent     identity-root\nNamespace my.agents.identity-root\nCluster   identity-cluster"
               (agent/whoami {:seon.db/db @connection :seon.agent/id agent-id})))
        (is (str/includes? (pr-str html) agent-id))
        (is (str/includes? (pr-str html) (str namespace-name)))
        (is (str/includes? (pr-str html) "Steward"))
        (test-support/transacted! connection
                                  [[:db/add [:seon.cluster/name cluster-name]
                                    :seon.cluster/name "renamed-cluster"]])
        (is (str/includes? (agent/whoami {:seon.db/db @connection :seon.agent/id agent-id})
                           "Cluster   renamed-cluster")
            "the result follows the supplied database, not a captured identity")))))

(deftest identity-rendering-keeps-partial-data-and-read-errors-visible
  (testing "an id remains visible while optional connections are absent"
    (let [unit {:seon.agent/id agent-id
                :seon.render/value {:seon.agent/id agent-id}}]
      (is (str/includes? (pr-str (agent/render-identity-html unit)) agent-id))))
  (testing "a database refusal remains a typed rendered refusal"
    (let [database-error
          (db/pull {:selector [:seon.agent/id]
                    :eid [:seon.agent/id agent-id]})
          unit {:seon.db/db database-error
                :seon.agent/id agent-id}]
      (is (:seon.error/kind database-error))
      (is (= database-error (agent/render-identity-html unit))))))

(deftest identity-map-and-omitted-arguments-use-the-same-function
  (with-agent
    (fn [connection]
      (test-support/transacted! connection
                                (filterv :seon.call-preparation/key
                                         (:seon.config/initialization
                                          (config/compile-manifest {}))))
      (test-support/transacted! connection [{:seon.agent/id "supplied"}])
      (let [database @connection
            indexed (db/pull database
                             '[:seon.fn/sym
                               {:seon.fn/arities
                                [:seon.fn.arity/order
                                 :seon.fn.arity/argument-count
                                 :seon.fn.arity/input
                                 :seon.fn.arity/output]}]
                             [:seon.fn/sym "seon.cluster.agent/whoami"])
            arities (sort-by :seon.fn.arity/order (:seon.fn/arities indexed))
            expected (agent/whoami {:seon.db/db database :seon.agent/id agent-id})
            acquired (sci.eval/cluster-ctx database connection)
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
              (sci.eval/evaluate
               {:seon.sci.eval/ctx live
                :seon.agent/id agent-id
                :seon.sci.admit/caps
                (config/result-caps (test-support/effective-config))
                :seon.sci.eval/time-limit-ms 5000
                :seon.config/on-core-error :panic
                :seon.cluster.eval/source source
                :seon.cluster.eval/ns [:seon.ns/name namespace-name]}))]
        (is (= [1] (mapv :seon.fn.arity/argument-count arities))
            "the argumentless SCI call uses the one declared request-map arity")
        (is (every? :seon.fn.arity/output arities))
        (is (some? live))
        (let [result (evaluate (str "(do\n"
                                   (agent/render-identity-ai
                                    {:seon.agent/id agent-id})
                                   "\n)"))
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
        (let [dials (:seon.sci.admit/value (evaluate (str "(do\n" (seon.agent/render-settings-ai {}) "\n)")))]
          (is (map? dials))
          (is (= 1234 (:seon.config.eval/time-limit-ms dials)))
          (is (not (contains? dials :my.agent/turns-left))))
        (is (= [] (:seon.sci.admit/value (evaluate "(my.test/run)"))))
        (is (= {:my.turn/disposition :wait :my.turn/note "Session complete."}
               (:seon.sci.admit/value (evaluate "(my.agent/done)"))))
        (is (= expected (:seon.sci.admit/value (evaluate "(seon.cluster.agent/whoami)"))))
        (is (= "Agent     supplied\nCluster   identity-cluster"
               (:seon.sci.admit/value
                (evaluate "(seon.cluster.agent/whoami {:seon.agent/id \"supplied\"})")))
            "the supplied map wins over current-agent defaults")
        (is (= "Agent     supplied\nCluster   identity-cluster"
               (agent/whoami {:seon.db/db database :seon.agent/id "supplied"})))))))
