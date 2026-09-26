(ns seon.cluster.fault-message-test
  (:require [clojure.core.async.flow :as-alias async.flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fault :as fault]
            [seon.test-support :as support]))

(deftest a-refused-recording-panics-naming-the-source-fault
  (support/with-database
   (fn [connection]
     ;; The absent cluster has no recording dials. The real preparation
     ;; contract refuses before a durable fact exists; no writer is mocked.
     ;; The panic names the original fault and keeps the recording failure.
     (doseq [fault [(ex-info "original throwable fault" {})
                    {:seon.error/at #inst "2026-09-26T00:00:00Z"
                     :seon.error/layer ::source :seon.error/operation `source
                     :seon.error/message "original map fault"}]
             :let [message (if (instance? Throwable fault) (ex-message fault) (:seon.error/message fault))
                   panic (try (fault/fault! (env/environment {:seon.boot/cluster-name "absent-fault-cluster"
                                                              :seon.db/connection connection
                                                              :seon.sci.admit/caps (config/result-caps config/defaults)})
                                            fault
                                            {:seon.error/layer ::boundary :seon.error/operation `boundary
                                             :seon.db.process/id "fault-message-test"})
                              nil
                              (catch clojure.lang.ExceptionInfo thrown thrown))
                   receipt (:seon.fault/recorded (ex-data panic))]]
       (is (some? panic) "a refused recording panics")
       (is (false? (:seon.fault/committed? receipt)))
       (is (str/includes? (ex-message panic) message) "the panic names the source fault")))))

(defn- world
  "The environment a boundary already holds, for one fixture cluster."
  [connection cluster-name manifest]
  ;; a member's world holds no agent it did not create; root is the shipped
  ;; escalation owner, so the boundary's cluster and root are seeded as boot does
  (support/seed-cluster! connection cluster-name manifest)
  (cluster/seed-root-agent! connection cluster-name cluster/boot-process-identity)
  (env/environment {:seon.boot/cluster-name cluster-name
                    :seon.db/connection connection
                    :seon.sci.admit/caps (config/result-caps
                                          (config/effective (db/db connection) cluster-name))}))

(defn- context
  [process]
  {:seon.error/layer ::boundary
   :seon.error/operation `boundary
   :seon.db.process/id process})

(defn- occurrence
  [connection receipt]
  (db/pull (db/db connection)
           '[:seon.error.occurrence/count {:seon.error.occurrence/related [:seon.error.occurrence/id]}]
           [:seon.error.occurrence/id (:seon.error.occurrence/id receipt)]))

(deftest the-receipt-names-the-writers-selected-recipients-and-references-persist
  ;; Lost-error seams P2. The receipt's `:seon.fault/delivered-to` is read from
  ;; the transaction's own report: the notification the writer selected for the
  ;; current opening. A recurrence in that opening keeps it; a reopened
  ;; signature whose only message belongs to the old opening is undelivered.
  ;; An obligation is one occurrence across processes, and a declared
  ;; reference is stored by reference.
  (support/with-database
   (fn [connection]
     (let [cluster "fault-delivery"
           environment (world connection cluster {:seon.config.error/escalate-to "root"})
           failure #(ex-info "a delivered core fault" {::fault :delivery})
           first-receipt (fault/fault! environment (failure) (context "delivery-a"))
           recurrence (fault/fault! environment (failure)
                                    (assoc (context "delivery-b")
                                           :seon.error/related-occurrence
                                           (:seon.error.occurrence/id first-receipt)))
           signature (:seon.error/signature first-receipt)]
       (is (true? (:seon.fault/committed? first-receipt)) (pr-str first-receipt))
       (is (= #{"root"} (:seon.fault/delivered-to first-receipt)) "the first opening wakes root")
       (is (= signature (:seon.error/signature recurrence)))
       (is (= #{"root"} (:seon.fault/delivered-to recurrence))
           "a recurrence in the open opening is delivered by that opening's message")
       (is (= (:seon.error.occurrence/id first-receipt)
              (get-in (occurrence connection recurrence)
                      [:seon.error.occurrence/related :seon.error.occurrence/id]))
           "the related occurrence is stored as a reference")
       ;; resolve, then recur with no escalation owner: the old message is not delivery
       (support/transacted! connection [[:db/add [:seon.error/signature signature]
                                         :seon.error/resolved-tx "datomic.tx"]])
       (let [reopened (fault/fault! (world connection cluster {:seon.config.error/escalate-to :seon.config/absent})
                                    (failure) (context "delivery-c"))]
         (is (true? (:seon.fault/committed? reopened)))
         (is (= #{} (:seon.fault/delivered-to reopened))
             "a reopened signature with only the old opening's message is committed but undeliverable"))
       (let [environment (world connection cluster {:seon.config.error/escalate-to "root"})
             wake {:seon.error/obligation "fault-delivery-wake-1"}
             first-attempt (fault/fault! environment (failure) (merge (context "delivery-d") wake))
             second-attempt (fault/fault! environment (failure) (merge (context "delivery-e") wake))
             other-wake (fault/fault! environment (failure)
                                      (merge (context "delivery-f") {:seon.error/obligation "fault-delivery-wake-2"}))]
         (is (= (:seon.error.occurrence/id first-attempt) (:seon.error.occurrence/id second-attempt))
             "one obligation is one occurrence across processes")
         (is (= 1 (:seon.error.occurrence/count (occurrence connection second-attempt)))
             "a second derivation of the same obligation is not recounted")
         (is (not= (:seon.error.occurrence/id first-attempt) (:seon.error.occurrence/id other-wake))
             "a different wake is its own obligation"))))))

(deftest panic-requests-the-owners-stop-before-it-records
  (support/with-database
   (fn [connection]
     (let [order (atom [])
           environment (world connection "fault-stop-request" {:seon.config.error/escalate-to "root"})
           panic (try (fault/fault! environment (ex-info "a panicking fault" {})
                                    (assoc (context "stop-request")
                                           :seon.fault/stop-request!
                                           #(swap! order conj [:stop-request
                                                               (count (db/q '[:find [?e ...] :in $ ?p
                                                                              :where [?o :seon.error.occurrence/process ?pe]
                                                                                     [?pe :seon.db.process/id ?p]
                                                                                     [?e :seon.error/occurrences ?o]]
                                                                            (db/db connection) "stop-request"))])))
                      nil
                      (catch clojure.lang.ExceptionInfo thrown thrown))
           receipt (:seon.fault/recorded (ex-data panic))]
       (is (some? panic) "panic mode throws")
       (is (= [[:stop-request 0]] @order) "the stop request ran once, before any occurrence existed")
       (is (true? (:seon.fault/committed? receipt)) "it still records")
       (is (= #{"root"} (:seon.fault/delivered-to receipt)))))))
