(ns seon.cluster.run-test
  "Sealed acceptance for the nucleus run model (N2).

  Orchestrator-authored (2026-07-26 s3). The implementation lane makes
  these green by implementing seon.cluster.run ONLY — schemas and tests
  are byte-sealed; friction is reported, never resolved by weakening.
  Everything runs against in-memory Datahike in-process: the testbed's
  instant-feedback loop, no operator machinery."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.cluster.run :as run]
            [seon.db :as db]
            [seon.schema]))

;;; ---------------------------------------------------------------------------
;;; In-memory database fixture — the same idiom as the wake tests
;;; ---------------------------------------------------------------------------

(def ^:private model-attributes
  [:seon.cluster.agent/id
   :seon.cluster.agent/run
   :seon.cluster.run/id
   :seon.cluster.run/agent
   :seon.cluster.run/opened-at
   :seon.cluster.run/closed-at
   :seon.cluster.run/process
   :seon.cluster.run/claim-epoch
   :seon.cluster.run/lease-until
   :seon.cluster.run/plan-digest
   :seon.cluster.run/forms
   :seon.cluster.run.form/id
   :seon.cluster.run.form/run
   :seon.cluster.run.form/ordinal
   :seon.cluster.run.form/source
   :seon.cluster.eval/id
   :seon.cluster.eval/run
   :seon.cluster.eval/ordinal
   :seon.cluster.eval/claim-epoch
   :seon.cluster.eval/at
   :seon.cluster.eval/status
   :seon.cluster.eval/result-edn
   :seon.cluster.eval/error])

(defn- with-model-database [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection (db/malli->datahike-schema model-attributes))
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(def ^:private t0 #inst "2026-07-26T12:00:00.000-00:00")
(def ^:private t1 #inst "2026-07-26T12:05:00.000-00:00")
(def ^:private t2 #inst "2026-07-26T12:30:00.000-00:00")

(defn- open-run!
  [connection run-id]
  (d/transact connection [{:seon.cluster.agent/id "runner"}])
  (d/transact connection (run/open-tx {::run/id run-id
                                       ::run/agent [:seon.cluster.agent/id "runner"]
                                       ::run/opened-at t0
                                       :seon.cluster.agent/id "runner"})))

(defn- claim!
  "Attempt one claim; truthy result on commit, nil when the CAS lost."
  [connection run-id process observed-epoch lease-until]
  (try
    (d/transact connection
                (run/claim-tx {::run/id run-id
                               ::run/process process
                               ::run/observed-epoch observed-epoch
                               ::run/lease-until lease-until}))
    (catch Exception _ nil)))

(defn- run-entity [connection run-id]
  (d/pull (d/db connection) '[*] [:seon.cluster.run/id run-id]))

;;; ---------------------------------------------------------------------------
;;; Derivations — state is computed from primitives, never stored
;;; ---------------------------------------------------------------------------

(deftest state-is-derived-from-primitives
  (testing "open is the absence of closed-at"
    (is (true? (run/open? {})))
    (is (false? (run/open? {::run/closed-at t2}))))
  (testing "claimed means a holder under a live lease"
    (is (true? (run/claimed? {::run/process "p1" ::run/lease-until t2} t1)))
    (is (false? (run/claimed? {::run/process "p1" ::run/lease-until t0} t1)))
    (is (false? (run/claimed? {} t1))))
  (testing "expired means open with a lapsed holder"
    (is (true? (run/expired? {::run/process "p1" ::run/lease-until t0} t1)))
    (is (false? (run/expired? {::run/process "p1" ::run/lease-until t2} t1)))
    (is (false? (run/expired? {::run/closed-at t2
                               ::run/process "p1"
                               ::run/lease-until t0} t1)))))

(deftest interrupted-warning-is-one-derived-value
  (let [forms [{:seon.cluster.run.form/ordinal 0}
               {:seon.cluster.run.form/ordinal 1}
               {:seon.cluster.run.form/ordinal 2}]]
    (testing "clean receipts derive no warning at all"
      (is (nil? (run/interrupted-warning
                 forms
                 [{:seon.cluster.eval/ordinal 0
                   :seon.cluster.eval/status :done}]))))
    (testing "an interrupted receipt derives exactly one warning naming
              the first interrupted ordinal and the missing tail"
      (let [warning (run/interrupted-warning
                     forms
                     [{:seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/status :done}
                      {:seon.cluster.eval/ordinal 1
                       :seon.cluster.eval/status :interrupted}])]
        (is (= 1 (:seon.cluster.eval/ordinal warning)))
        (is (= 2 (:seon.cluster.run/missing-results warning)))))))

;;; ---------------------------------------------------------------------------
;;; Custody — generated interleavings, fixed seeds
;;; ---------------------------------------------------------------------------

(deftest exactly-one-claim-wins
  (with-model-database
    (fn [connection]
      (let [check
            (tc/quick-check
             30
             (prop/for-all [n (gen/choose 2 6)
                            round gen/nat]
               (let [run-id (str "claim-race-" round "-" (random-uuid))
                     _ (open-run! connection run-id)
                     winners (->> (range n)
                                  (mapv #(claim! connection run-id
                                                 (str "process-" %)
                                                 nil t2))
                                  (filterv some?))
                     entity (run-entity connection run-id)]
                 (and (= 1 (count winners))
                      (= 1 (:seon.cluster.run/claim-epoch entity))
                      (string? (:seon.cluster.run/process entity)))))
             :seed 20260726)]
        (is (true? (:result check))
            (str "claim exclusivity failed: " (pr-str check)))))))

(deftest epochs-fence-monotonically
  (with-model-database
    (fn [connection]
      (let [run-id (str "epoch-" (random-uuid))]
        (open-run! connection run-id)
        (is (some? (claim! connection run-id "p1" nil t2)))
        (testing "a live foreign claim is not stealable"
          (is (nil? (claim! connection run-id "p2" 1 t2))))
        (d/transact connection
                    (run/release-tx {::run/id run-id
                                     ::run/process "p1"
                                     ::run/claim-epoch 1}))
        (testing "reacquisition increments the epoch"
          (is (some? (claim! connection run-id "p2" 1 t2)))
          (is (= 2 (:seon.cluster.run/claim-epoch
                    (run-entity connection run-id)))))
        (testing "the displaced holder's fenced write fails"
          (is (thrown? Exception
                       (d/transact
                        connection
                        (run/heartbeat-tx {::run/id run-id
                                           ::run/process "p1"
                                           ::run/claim-epoch 1
                                           ::run/lease-until t2})))))))))

(deftest plan-freeze-is-mutually-exclusive
  (with-model-database
    (fn [connection]
      (let [run-id (str "plan-" (random-uuid))]
        (open-run! connection run-id)
        (claim! connection run-id "p1" nil t2)
        (is (some? (d/transact connection
                               (run/plan-tx {::run/id run-id
                                             ::run/process "p1"
                                             ::run/claim-epoch 1
                                             ::run/plan-digest "digest-a"
                                             ::run/sources ["(+ 1 1)"
                                                            "(+ 2 2)"]}))))
        (testing "a second reply cannot splice a competing plan"
          (is (thrown? Exception
                       (d/transact
                        connection
                        (run/plan-tx {::run/id run-id
                                      ::run/process "p1"
                                      ::run/claim-epoch 1
                                      ::run/plan-digest "digest-b"
                                      ::run/sources ["(+ 3 3)"]})))))
        (testing "the committed plan holds its explicit order"
          (is (= ["(+ 1 1)" "(+ 2 2)"]
                 (->> (d/q '[:find ?ordinal ?source
                             :in $ ?run-id
                             :where
                             [?run :seon.cluster.run/id ?run-id]
                             [?form :seon.cluster.run.form/run ?run]
                             [?form :seon.cluster.run.form/ordinal ?ordinal]
                             [?form :seon.cluster.run.form/source ?source]]
                           (d/db connection) run-id)
                      (sort-by first)
                      (mapv second)))))))))

;;; ---------------------------------------------------------------------------
;;; Crash recovery — mark interrupted, release the dead, NEVER re-execute
;;; ---------------------------------------------------------------------------

(def ^:private receipt-status-gen
  (gen/elements [:running :done :error]))

(deftest recovery-marks-interrupted-and-releases-the-dead
  (with-model-database
    (fn [connection]
      (let [check
            (tc/quick-check
             30
             (prop/for-all [statuses (gen/vector receipt-status-gen 1 5)
                            dead? gen/boolean
                            round gen/nat]
               (let [run-id (str "recover-" round "-" (random-uuid))
                     holder (if dead? "dead-process" "live-process")
                     _ (open-run! connection run-id)
                     _ (claim! connection run-id holder nil t2)
                     _ (d/transact
                        connection
                        (vec (map-indexed
                              (fn [ordinal status]
                                {:seon.cluster.eval/id
                                 (pr-str [run-id ordinal 1])
                                 :seon.cluster.eval/run
                                 [:seon.cluster.run/id run-id]
                                 :seon.cluster.eval/ordinal ordinal
                                 :seon.cluster.eval/claim-epoch 1
                                 :seon.cluster.eval/at t1
                                 :seon.cluster.eval/status status})
                              statuses)))
                     recovery
                     (run/recover-tx
                      {::run/run (run-entity connection run-id)
                       ::run/receipts
                       (mapv #(d/pull (d/db connection) '[*] %)
                             (d/q '[:find [?receipt ...]
                                    :in $ ?run-id
                                    :where
                                    [?run :seon.cluster.run/id ?run-id]
                                    [?receipt :seon.cluster.eval/run ?run]]
                                  (d/db connection) run-id))
                       ::run/live-processes #{"live-process"}})
                     _ (when (seq recovery)
                         (d/transact connection recovery))
                     after (d/db connection)
                     statuses-after
                     (set (d/q '[:find [?status ...]
                                 :in $ ?run-id
                                 :where
                                 [?run :seon.cluster.run/id ?run-id]
                                 [?receipt :seon.cluster.eval/run ?run]
                                 [?receipt :seon.cluster.eval/status ?status]]
                               after run-id))
                     entity (run-entity connection run-id)]
                 (and
                  ;; no receipt stays :running, none is re-opened
                  (not (contains? statuses-after :running))
                  ;; terminal receipts are untouched — nothing re-executes
                  (= (count statuses)
                     (count (d/q '[:find [?receipt ...]
                                   :in $ ?run-id
                                   :where
                                   [?run :seon.cluster.run/id ?run-id]
                                   [?receipt :seon.cluster.eval/run ?run]]
                                 after run-id)))
                  ;; dead holders are released; live holders keep custody
                  (if dead?
                    (nil? (:seon.cluster.run/process entity))
                    (= "live-process" (:seon.cluster.run/process entity))))))
             :seed 20260726)]
        (is (true? (:result check))
            (str "recovery property failed: " (pr-str check)))))))

;;; ---------------------------------------------------------------------------
;;; Schema admissibility — the model refuses what it must
;;; ---------------------------------------------------------------------------

(deftest run-schema-admits-and-refuses
  (is (seon.schema/valid-candidate-value?
       :seon.cluster.run/run
       {::run/id "r1"
        ::run/agent [:seon.cluster.agent/id "runner"]
        ::run/opened-at t0}))
  (is (not (seon.schema/valid-candidate-value?
            :seon.cluster.run/run
            {::run/agent [:seon.cluster.agent/id "runner"]
             ::run/opened-at t0}))
      "identity is required")
  (is (not (seon.schema/valid-candidate-value?
            :seon.cluster.run/run
            {::run/id ""
             ::run/agent [:seon.cluster.agent/id "runner"]
             ::run/opened-at t0}))
      "a blank identity is refused"))
