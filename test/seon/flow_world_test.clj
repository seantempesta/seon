(ns seon.flow-world-test
  "A Flow proc step and an io task run in the world their environment names
  (health plan §0): Flow conveys no bindings, so `seon.flow` derives it."
  (:require [clojure.test :refer [deftest is]]
            [seon.batch]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal :as refusal]
            [seon.flow :as flow]
            [seon.id]
            [seon.test-support :as test-support]))

(defn- world
  "The environment a proc's args carry for one fixture cluster."
  [connection cluster-name]
  (test-support/seed-cluster! connection cluster-name {})
  (cluster/seed-root-agent! connection cluster-name cluster/boot-process-identity)
  (env/environment {:seon.boot/cluster-name cluster-name
                    :seon.db/connection connection
                    :seon.sci.admit/caps (config/result-caps (config/effective (db/db connection) cluster-name))}))

(deftest a-proc-step-runs-in-its-environments-world
  (test-support/with-database
   (fn [connection]
     (let [environment (world connection "flow-world")
           observed (#'flow/in-world environment {:seon.turn/id "t1" :seon.batch/ordinal 0}
                                     (fn [] seon.batch/*world*))]
       (is (= "flow-world" (:seon.boot/cluster-name observed)))
       (is (identical? connection (:seon.db/connection observed)))
       (is (db/database-value? (:seon.db/db observed)))
       (is (= "t1" (:seon.turn/id observed)) "the submitting evaluation's identities ride as data")
       (is (= {:seon.cluster.eval/id (seon.id/evaluation "t1" 0) :seon.turn/id "t1"}
              (select-keys (refusal/world-reference observed) [:seon.cluster.eval/id :seon.turn/id])))
       (is (= ::outer (binding [seon.batch/*world* ::outer]
                        (#'flow/in-world environment nil (fn [] seon.batch/*world*))))
           "an evaluation's own world is never replaced")))))
