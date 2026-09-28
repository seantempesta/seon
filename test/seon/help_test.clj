(ns seon.help-test
  (:require [clojure.test :refer [deftest is]]
            [seon.bootstrap :as bootstrap]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.test-support :as support]))

;; The opening no longer evaluates (help) first: the agent's identity unit
;; offers it as data (db908b0c6), so help is one read away rather than the
;; opening's first stored evaluation.

(defn- with-help-agent
  "Run `body` with the fixture connection holding a seeded cluster and agent help."
  {:malli/schema [:=> [:cat [:fn clojure.core/ifn?]] :seon.schema/value]}
  [body]
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "help")
     (support/transacted! connection
                          (agent/creation-tx {:seon.agent/id "help"
                                              :seon.ns/name 'my.agents.help
                                              :seon.cluster/name "help"}))
     (body connection))))

(deftest an-agent-identity-offers-help-as-its-operation
  (with-help-agent
   (fn [connection]
     (is (= ['(help)]
            (:seon.render/operations
             (agent/render-identity-ai {:seon.db/db @connection :seon.agent/id "help"})))
         "a non-root agent's identity offers exactly (help)"))))

(deftest help-evidence-tracks-its-code-version
  (with-help-agent
   (fn [connection]
     (let [captured (atom [])
           database @connection
           _ (binding [db/*read-evidence-sink* captured]
               (bootstrap/help-value database "help"))
           evidence (db/read-evidence @captured)
           source (:seon.fn/source (db/pull database [:seon.fn/source]
                                            [:seon.fn/sym 'seon.bootstrap/help-value]))]
       (is (seq evidence) "help records what it read")
       (support/transacted! connection (support/agent-tx @connection "unrelated"))
       (is (true? (db/read-evidence-current? @connection evidence))
           "an unrelated write leaves help current")
       (support/transacted! connection
                            (support/replacement-tx @connection
                                                    [:seon.fn/sym 'seon.bootstrap/help-value]
                                                    (str source "\n;; revised")
                                                    {}))
       (is (false? (db/read-evidence-current? @connection evidence))
           "a new definition of help makes its evidence stale")))))
