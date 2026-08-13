(ns seon.db-transact-shape-test
  "Regression for the one native `seon.db/transact!` return shape."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support]))

(def ^:private caps
  (config/result-caps (config/defaults)))

(defn transaction-result-shape
  "The observable native shape of one committed empty transaction."
  {:malli/schema
   [:=> [:cat :seon.db/connection]
    [:map
     [::keys [:set :keyword]]
     [::db-before? :boolean]
     [::db-after? :boolean]]]}
  [connection]
  (let [result (db/transact! connection [])]
    {::keys (set (keys result))
     ::db-before? (db/database-value? (:db-before result))
     ::db-after? (db/database-value? (:db-after result))}))

(defn- agent-evaluation
  [connection]
  (let [base-ctx (test-support/fork-cluster-ctx connection)
        environment
        (env/refuse-incomplete-environment!
         (env/environment
          {:seon.boot/cluster-name "db-transact-shape-test"
           :seon.db/connection connection
           :seon.schema/projection (:seon.schema/projection base-ctx)}))
        ctx (env/carry-state base-ctx (env/environment-state environment))]
    (sci.eval/evaluate
     {:seon.cluster.run.form/source
      "(seon.db-transact-shape-test/transaction-result-shape)"
      :seon.sci.eval/ctx ctx
      :seon.sci.admit/caps caps
      :seon.sci.eval/time-limit-ms 2000
      :seon.config/on-core-error :panic})))

(deftest transact-return-shape-is-independent-of-dynamic-custody
  (test-support/with-database
   (fn [connection]
     (let [system-result (transaction-result-shape connection)
           evaluation (agent-evaluation connection)
           agent-result (:seon.sci.admit/value evaluation)
           native-keys #{:db-before :db-after :tx-data :tempids :tx-meta}]
       (testing "the contracted function sees Datahike's native report"
         (is (= {::keys native-keys
                 ::db-before? true
                 ::db-after? true}
                system-result)))
       (testing "the same function agrees inside a guarded agent evaluation"
         (is (nil? (:seon.cluster.eval/error evaluation)))
         (is (= system-result agent-result)))))))
