(ns seon.reconcile-retain-test
  "Config reconciliation never retracts a config row another cluster references."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Three config applies on one fixture branch: 57.0 s measured at 15ffb4936 on a scratch cluster, each apply's one reconcile transaction ~16.5 s (routed to the writer-cost owner; 313 ms per apply at bfe3445f8)."
           :seon.test/long-ms 90000}
  a-config-another-cluster-references-survives-a-later-apply
  ;; Every config row is first written by the one config process, so a
  ;; process-scoped reconciliation managed all of them: applying B retracted
  ;; A's row and the retraction refused on A's required :seon.cluster/config.
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "retain-a")
     (support/seed-cluster! connection "retain-b")
     (support/seed-cluster! connection "retain-a" {:seon.config.flow.compute/queue-depth 17})
     (let [database (db/db connection)
           configs (set (db/q '[:find [?name ...] :where [_ :seon.config/cluster ?name]] database))
           owned (set (db/q '[:find [?name ...]
                              :where [?c :seon.cluster/name ?name]
                                     [?c :seon.cluster/config ?config]
                                     [?config :seon.config/cluster ?name]]
                            database))]
       (is (every? configs ["retain-a" "retain-b"]) (pr-str configs))
       (is (every? owned ["retain-a" "retain-b"])
           "each cluster still references its own config row")))))
