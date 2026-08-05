(ns seon.cluster.curate-test
  "Recurring proof for revision replay, acceptance, and atomic adopt."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.curate :as curate]
            [seon.cluster.registry :as registry]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval.drive]
            [seon.render.transcript :as transcript]))

(defn- await-run! [connection run-id]
  ((ns-resolve 'seon.eval.drive 'await-fact!)
   connection 120000 (str "closed run " run-id)
   (fn [database]
     (db/q '[:find ?closed .
             :in $ ?run-id
             :where
             [?run :seon.cluster.run/id ?run-id]
             [?run :seon.cluster.run/closed-at ?closed]]
           database run-id))))

(defn- seed-system-run! [instance run-id sources]
  (let [connection (:seon.boot/cluster-connection instance)
        process (cluster/process-identity (:seon.boot/advertisement instance))
        starting-ns
        (db/q '[:find ?name .
                :where
                [?agent :seon.cluster.agent/id "root"]
                [?agent :seon.cluster.agent/namespace ?namespace]
                [?namespace :seon.ns/name ?name]]
              @connection)]
    (db/transact!
     connection
     {:tx-data
      (run/system-run-tx
       @connection
       {:seon.cluster.agent/id "root"
        :seon.cluster.run/id run-id
        :seon.cluster.run/process process
        :seon.cluster.run/opened-at (java.util.Date.)
        :seon.cluster.run/starting-ns [:seon.ns/name starting-ns]
        :seon.cluster.run/plan-digest (str "digest:" run-id)
        :seon.cluster.run/sources sources})})
    ((ns-resolve 'seon.cluster.curate 'execute-revision!)
     connection (:seon.cluster.loop/cluster instance)
     "root" process run-id)
    (await-run! connection run-id)))

(defn- transcript-ai [instance]
  (let [connection (:seon.boot/cluster-connection instance)
        database @connection
        cluster-value (:seon.cluster.loop/cluster instance)
        settings (config/effective database
                                   (get-in instance [:seon.boot/config
                                                     :seon.boot/cluster-name]))]
    (transcript/render-ai
     {:seon.db/db database
      :seon.db/connection connection
      :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster-value)
      :seon.cluster.agent/id "root"
      :seon.sci.admit/caps (config/result-caps settings)
      :seon.sci.eval/time-limit-ms
      (:seon.config.eval/time-limit-ms settings)
      :seon.config/on-core-error (:seon.config/on-core-error settings)
      :seon.render.transcript/token-budget 1000000})))

(deftest proof-acceptance-and-atomic-adopt-curate-one-messy-span
  (let [suffix (subs (str (random-uuid)) 0 8)
        process-root (str "tmp/session-curation-tests/" suffix)
        cluster-name (str "session-curation-test-" suffix)
        _ (cluster/refresh-source! process-root)
        instance (cluster/start! {:seon.boot/cluster-name cluster-name
                                  :seon.boot/root process-root})]
    (try
      (let [connection (:seon.boot/cluster-connection instance)
            store-value (:seon.store/store instance)
            _ (await-run! connection "bootstrap:root")
            original-run (str "messy:" suffix)
            messy [{:seon.cluster.run.form/source "(+ 20 22)"}
                   {:seon.cluster.run.form/source "missing-symbol"}
                   {:seon.cluster.run.form/source "(my.run/complete \"42\")"}]
            revision [{:seon.cluster.run.form/source "(+ 20 22)"}
                      {:seon.cluster.run.form/source "(my.run/complete \"42\")"}]
            _ (seed-system-run! instance original-run messy)
            failed (curate/prove!
                    {:seon.boot/instance instance
                     :seon.cluster.curate/run-ids [original-run]
                     :seon.cluster.curate/revision
                     [{:seon.cluster.run.form/source
                       "(my.run/complete \"43\")"}]})]
        (testing "a failed proof names its predicate and leaves the original"
          (is (= :seon.cluster.curate/completed-result-equivalent
                 (:seon.cluster.curate/predicate failed)))
          (is (empty?
               (db/q '[:find [?new-id ...]
                       :in $ ?old-id
                       :where
                       [?old :seon.cluster.run/id ?old-id]
                       [?new :seon.cluster.run/supersedes ?old]
                       [?new :seon.cluster.run/id ?new-id]]
                     @connection original-run))))
        (let [proof (curate/prove!
                     {:seon.boot/instance instance
                      :seon.cluster.curate/run-ids [original-run]
                      :seon.cluster.curate/revision revision})]
          (testing "a clean equivalent revision returns proof receipts"
            (is (nil? (:seon.error/kind proof)))
            (is (= :completed
                   (get-in proof [:seon.cluster.curate/terminal
                                  :seon.eval.drive/outcome])))
            (is (= 2 (count (:seon.cluster.curate/receipts proof)))))
          (testing "a crash after proof and before adopt leaves history intact"
            (let [crash-proof (curate/prove!
                               {:seon.boot/instance instance
                                :seon.cluster.curate/run-ids [original-run]
                                :seon.cluster.curate/revision revision})]
              (registry/retire-branch!
               {:seon.store/store store-value
                :seon.store/branch
                (:seon.cluster.curate/proof-branch crash-proof)})
              (is (some? (db/pull @connection [:seon.cluster.run/id]
                                  [:seon.cluster.run/id original-run])))
              (is (empty?
                   (db/q '[:find [?new ...]
                           :in $ ?old-id
                           :where
                           [?old :seon.cluster.run/id ?old-id]
                           [?new :seon.cluster.run/supersedes ?old]]
                         @connection original-run)))))
          (let [adoption (curate/adopt!
                          {:seon.boot/instance instance
                           :seon.cluster.curate/proof proof})
                curated-run (:seon.cluster.run/id adoption)]
            (testing "adopt is one visible run whose ordinals join"
              (is (uuid? (:seon.cluster.curate/adopted-commit-id adoption)))
          (is (= [original-run]
                     (:seon.cluster.curate/run-ids adoption)))
              (is (= 1
                     (count
                      (db/q '[:find [?tx ...]
                              :in $ ?run-id
                              :where
                              [?run :seon.cluster.run/id ?run-id _ true]
                              [?receipt :seon.cluster.eval/run ?run ?tx true]]
                            (db/history @connection) curated-run)))
                  "every adopted receipt became visible in one transaction")
              (is (= #{[0 0] [1 1]}
                     (db/q '[:find ?form-ordinal ?receipt-ordinal
                             :in $ ?run-id
                             :where
                             [?run :seon.cluster.run/id ?run-id]
                             [?form :seon.cluster.run.form/run ?run]
                             [?form :seon.cluster.run.form/ordinal ?form-ordinal]
                             [?receipt :seon.cluster.eval/run ?run]
                             [?receipt :seon.cluster.eval/ordinal ?receipt-ordinal]
                             [(= ?form-ordinal ?receipt-ordinal)]]
                           @connection curated-run))))
            (is (not (contains? (registry/roster store-value)
                                (:seon.cluster.curate/proof-branch proof)))
                "adopt retires the proof branch")
            (testing "the transcript is curated while the original is queryable"
              (let [rendered (transcript-ai instance)]
                (is (str/includes? rendered "(my.run/complete \"42\")"))
                (is (not (str/includes? rendered "missing-symbol"))))
              (is (= original-run
                     (:seon.cluster.run/id
                      (db/pull @connection [:seon.cluster.run/id]
                               [:seon.cluster.run/id original-run]))))))))
      (finally
        (cluster/stop! instance)))))
