(ns seon.cluster.mcp-test
  "The runtime observation (`runtime_status`, `bin/seon status`) and the value
  artifact marker."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.config :as config]
            [seon.env :as env]
            [seon.fault :as fault]
            [seon.operator.runtime :refer [running-instances]]
            [seon.problems :as problems]
            [seon.render.value :as render.value]
            [seon.schema :as schema]
            [seon.sci.admit :as admit]
            [seon.test-support :as support]))

(deftest missing-artifacts-have-a-real-marker-under-armed-admission
  (let [missing {:seon.sci.admit/reason :over-bound :seon.sci.admit/bytes 123}
        artifact (render.value/artifact missing)]
    (is (= missing (render.value/artifact-value artifact)))
    (is (some? (:seon.sci.admit/print-node artifact)))
    (let [refusal (support/refusal-data
                   #(admit/admit-value
                     {:seon.sci.admit/value missing
                      :seon.sci.admit/caps {}
                      :seon.sci.admit/interrupt-fn (fn [])}))]
      (is ((schema/projection-validator (schema/handed-projection)
                                        :seon.instrument/contract-error) refusal))
      (is (= 'seon.sci.admit/admit-value
             (:seon.instrument/fn refusal))
          "bounded admission still requires its declared limits"))))

(defn- listening-instance
  "A declared `:seon.boot/instance` for a fixture cluster, built by boot's own
  constructor over this JVM's live listener and bootstrap config (renamed), with
  the fixture's connection as its database layer."
  [connection cluster-name]
  (let [live (some #(when (:seon.boot/prepl-server %) %) (vals @running-instances))]
    (assert live "a fixture instance stands beside this JVM's live instance")
    (assoc (boot/listening-instance (assoc (:seon.boot/config live) :seon.boot/cluster-name cluster-name)
                                    (:seon.boot/prepl-server live))
           :seon.boot/cluster-connection connection)))

(defn- running-instance
  "The fixture cluster's instance: boot's listening instance over the fixture
  connection, holding a real SCI context forked onto that connection through
  the production fork (an instance's context is an SCI context, never a map)."
  [connection cluster-name]
  (assoc (listening-instance connection cluster-name)
         :seon.sci.eval/ctx (support/fork-cluster-ctx connection cluster-name)))
