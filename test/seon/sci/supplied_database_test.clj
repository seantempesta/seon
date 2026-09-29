(ns seon.sci.supplied-database-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest own-record-reads-supply-the-same-database-and-agent-as-explicit-calls
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "supplied-database")
     (support/transacted!
      connection
      (agent/creation-tx {:seon.agent/id "supplied-database-agent"
                         :seon.ns/name 'my.agents.supplied-database-agent
                         :seon.cluster/name "supplied-database"}))
     (let [database (db/db connection)
           ctx (support/fork-cluster-ctx connection "supplied-database")
           functions '[seon.bootstrap/help-value
                       seon.agent/identity seon.agent/archived? seon.agent/open?
                       seon.agent/settings seon.agent/effective-settings
                       seon.turn/turns-left]
           source (pr-str
                   (mapv (fn [sym]
                           [(list sym)
                            (list sym '(seon.db/db) "supplied-database-agent")])
                         functions))
           result (support/evaluate
                   {:seon.sci.eval/ctx ctx :seon.db/db database
                    :seon.agent/id "supplied-database-agent"
                    :seon.cluster.eval/source source
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 5000})
           values (:seon.sci.admit/value result)]
       (is (nil? (:seon.cluster.eval/error result)))
       (is (vector? values))
       (is (= (count functions) (count values)))
       (doseq [[_sym [omitted explicit]] (map vector functions (when (vector? values) values))]
         (is (= explicit omitted))
         (is (not (and (map? omitted)
                       (or (:seon.db/invalid-read omitted) (:seon.schema/expected-value omitted))))))
       (is (seq (:seon.help/lines (ffirst values))))))))
