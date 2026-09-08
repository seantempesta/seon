(ns seon.cluster-test
  "Cluster reconciliation invariants that own no cluster process."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.db :as db]
            [seon.error :as error]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private ordered-marker ::ordered)

(deftest schema-row-convergence-uses-the-stores-own-semantics
  (test-support/with-database
    {::test-support/extra-schema
     [{:db/ident ordered-marker
       :db/valueType :db.type/keyword
       :db/cardinality :db.cardinality/many}]}
    (fn [connection]
      (let [database @connection
            installed (:schema database)
            converged? (ns-resolve 'seon.cluster 'schema-row-converged?)
            changes (ns-resolve 'seon.cluster 'schema-row-changes)]
        (is (= :db.cardinality/many
               (get-in installed [ordered-marker :db/cardinality]))
            "the synthetic attribute is genuinely installed cardinality-many")
        (is (true?
             (converged? installed
                         {ordered-marker [:a :b :c]}
                         {ordered-marker [:c :a :b]}))
            "a cardinality-many value read back in another order is
             convergence: the store holds a set, not a sequence")
        (is (false?
             (converged? installed
                         {ordered-marker [:a :b :c]}
                         {ordered-marker [:a :b]}))
            "a genuinely different cardinality-many value is still a change")
        (is (= [] (changes database (schema/registered-schemas)))
            "a canonical database is already converged, so no branch open
             re-transacts a schema row it did not need to")))))

;;; ---------------------------------------------------------------------------
;;; The fault committer hands `seon.error` the dials its contracts declare
;;; ---------------------------------------------------------------------------

(def ^:private fault-cluster "fault-dial")

(defn- committed-fault-ids
  [database]
  (db/q '[:find [?id ...] :where [?error :seon.error/id ?id]] database))

;; THE CLASS: a committer that pre-reads a dial for one callee and hands the
;; other callee a request without it. `error/prepare` and `error/commit-tx`
;; both declare `:seon.config.error/max-evidence-bytes` required, and the
;; committer built ONE request, added the dial only to the copy it handed
;; `prepare`, and handed the original to `commit-tx`. Under the contracts
;; every cluster arms, that made EVERY core fault unrecordable: the operator
;; printed "A core fault could not be normalized" about its own refusal and
;; the fault's message, kind, provenance and evidence were lost.
;;
;; Both arms are asserted, because either one alone is green for the wrong
;; reason: with the dial present the fault must be a STORED FACT, and with it
;; absent the refusal must NAME THE KEY a caller has to supply.
(deftest a-core-fault-commits-with-the-dial-its-cluster-config-carries
  (test-support/with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name fault-cluster})
      (let [dials (config/effective @connection fault-cluster)
            caps (config/result-caps dials)
            commit-fault! (var-get (ns-resolve 'seon.cluster 'commit-fault!))
            [fact outcome] (commit-fault!
                            connection fault-cluster "cluster-test-process" caps
                            {::seon.flow/pid :seon.cluster-test/probe
                             ::seon.flow/op :step
                             ::seon.flow/ex (ex-info "probe core fault" {})})
            stored (committed-fault-ids @connection)]
        (is (int? (:seon.config.error/max-evidence-bytes dials))
            "the cluster's effective config genuinely carries the dial, so
             this asserts the committer rather than a missing fact")
        (is (= ::seon.flow/committed outcome)
            "the durable record was committed, not refused")
        (is (= [(:seon.error/id fact)] stored)
            "and the committed fault is one stored fact, queryable by id")
        (is (string? (:seon.error/message fact)))))))

(deftest a-commit-tx-request-without-the-evidence-bound-names-the-key
  (test-support/with-database
    (fn [connection]
      (let [dials (test-support/effective-config)
            request {:seon.error/source
                     {:seon.error/kind :seon.cluster-test/probe
                      :seon.error/message "probe core fault"}
                     :seon.error/id (str (random-uuid))
                     :seon.error/at (java.util.Date.)
                     :seon.error/process "cluster-test-process"
                     :seon.sci.admit/caps (config/result-caps dials)
                     :seon.config.error/recurrence-limit
                     (:seon.config.error/recurrence-limit dials)
                     :seon.config.error/max-evidence-bytes
                     (:seon.config.error/max-evidence-bytes dials)}
            refusal
            (test-support/refusal-data
             #(error/commit-tx
               @connection
               (dissoc request :seon.config.error/max-evidence-bytes)))
            data (:seon.error/data refusal)]
        (is (= :seon.instrument/contract-violated (:seon.error/kind refusal))
            "the contract refuses before the recorder is reached")
        (is (= 'seon.error/commit-tx (:seon.error/diagnostic-operation data))
            "and names the function that was called")
        (is (some #{[:seon.config.error/max-evidence-bytes]}
                  (:seon.instrument/problem-paths data))
            "EVERY Malli problem path is reported, so the key a caller has
             to supply is named rather than one of N problems")
        (is (str/includes? (:seon.error/message refusal)
                           ":seon.config.error/max-evidence-bytes")
            "and the message itself names it")
        (is (string? (:seon.instrument/caller
                      (:seon.error/diagnostic-evidence data)))
            "with a caller frame that is not malli's own wrapper")
        (is (empty? (committed-fault-ids @connection))
            "and the refused call committed nothing")))))
