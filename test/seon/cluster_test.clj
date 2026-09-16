(ns seon.cluster-test
  "Cluster reconciliation invariants that own no cluster process."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.db :as db]
            [seon.error :as error]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
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

(deftest a-dropped-storage-facet-refuses-reopening-the-branch-in-place
  ;; The canonical population is the installed declaration set, so the
  ;; converged case asserts what production reopening does: no declaration
  ;; change at all. Dropping a facet the branch still carries is the class
  ;; this regression kills — comparing only the current declaration's keys
  ;; read that absence as health and kept the stale uniqueness installed
  ;; (2026-09-16 blocker: every publication then refused with "multiple
  ;; entity identities" instead of naming the incompatible declaration).
  (test-support/with-database
    (fn [connection]
      (let [database @connection
            forms (schema.edn/packaged-forms)
            changes (ns-resolve 'seon.cluster 'declaration-changes)
            attribute :seon.test/reach-digest
            installed (get (:schema database) attribute)]
        (is (some? installed)
            "the canonical population installs the subject attribute")
        (is (not (contains? installed :db/unique))
            "which the bridge derives WITHOUT uniqueness")
        (is (= [] (schema/call-with-forms
                   forms
                   #(changes database forms "converged-fixture")))
            "a converged branch reopens with no declaration change")
        (let [stale (assoc-in database [:schema attribute :db/unique]
                              :db.unique/identity)
              refusal (test-support/refusal-data
                       #(schema/call-with-forms
                         forms
                         (fn [] (changes stale forms "stale-fixture"))))]
          (is (= :seon.boot/refused (:seon.error/kind refusal))
              "a facet the current declaration no longer carries refuses")
          (is (= attribute (:seon.boot/attribute (:seon.boot/offense refusal))))
          (is (= :db.unique/identity
                 (:db/unique (:seon.boot/installed (:seon.boot/offense refusal))))
              "and the refusal carries the installed facet as evidence")
          (is (str/includes? (:seon.error/message refusal)
                             "bin/seon init stale-fixture --force")
              "naming the refork that resolves it"))))))

(def ^:private indexable-marker ::indexable)

(deftest an-added-index-adopts-in-place-instead-of-forcing-a-refork
  ;; Adding `:db/index` to an already-installed attribute is accretion, and
  ;; the vendored fork applies it: the transactor atomically backfills AVET
  ;; before publishing the resulting database value
  ;; (`reference-code/datahike/src/datahike/schema.cljc:277`). Comparing the
  ;; declaration maps with `=` called that difference "incompatible" and
  ;; forced a destructive refork of every existing cluster (2026-09-16,
  ;; `docs/seon/issues/adoption-refuses-a-monotonic-index-addition-datahike-supports.md`).
  (test-support/with-database
    {::test-support/extra-schema
     [{:db/ident indexable-marker
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one}]}
    (fn [connection]
      (let [forms (schema.edn/packaged-forms)
            changes (ns-resolve 'seon.cluster 'declaration-changes)
            attribute :seon.issue/agent
            installed (get (:schema @connection) attribute)]
        (is (true? (:db/index installed))
            "the canonical population declares the subject attribute indexed")
        (let [older (update-in @connection [:schema attribute] dissoc :db/index)
              declarations (schema/call-with-forms
                            forms
                            #(changes older forms "older-fixture"))]
          (is (= [attribute] (mapv :db/ident declarations))
              "a branch forked before the index addition adopts exactly that
               declaration in place instead of refusing to reopen")
          (is (true? (:db/index (first declarations)))
              "and the declaration it transacts carries the index")))
      (let [avet-count (fn []
                         (count
                          (filter #(= indexable-marker (:a %))
                                  (seq (:avet @connection)))))
            written (db/transact!
                     connection
                     {:tx-data (mapv (fn [index]
                                       {indexable-marker (str "marker-" index)})
                                     (range 3))})]
        (is (some? (:db-after written))
            "the fixture writes its rows through the one write path")
        (is (zero? (avet-count))
            "an unindexed attribute holds no AVET datoms")
        (let [adopted (db/transact!
                       connection
                       {:tx-data [{:db/ident indexable-marker
                                   :db/valueType :db.type/string
                                   :db/cardinality :db.cardinality/one
                                   :db/index true}]})]
          (is (some? (:db-after adopted))
              "transacting the declaration adds the index in place")
          (is (true? (get-in (:schema @connection)
                             [indexable-marker :db/index]))
              "and the installed attribute is now indexed")
          (is (= 3 (avet-count))
              "with every pre-existing datom backfilled into AVET")
          (is (= 1 (count (db/q [:find '?e
                                 :where ['?e indexable-marker "marker-1"]]
                                @connection)))
              "so the AVET index answers a value-bound query afterwards"))))))

(deftest an-incompatible-declaration-refuses-naming-the-changed-property
  ;; "predates the incompatible schema change" named neither the property nor
  ;; its values, so a reader could not tell an accretive index addition from a
  ;; genuine value-type change.
  (test-support/with-database
    (fn [connection]
      (let [database @connection
            forms (schema.edn/packaged-forms)
            changes (ns-resolve 'seon.cluster 'declaration-changes)
            attribute :seon.test/reach-digest
            declared (:db/valueType (get (:schema database) attribute))
            stale (assoc-in database [:schema attribute :db/valueType]
                            :db.type/long)
            refusal (test-support/refusal-data
                     #(schema/call-with-forms
                       forms
                       (fn [] (changes stale forms "stale-fixture"))))
            offense (:seon.boot/offense refusal)]
        (is (= :db.type/string declared)
            "the bridge derives the subject attribute as a string")
        (is (= :seon.boot/refused (:seon.error/kind refusal))
            "a value-type change still refuses to reopen the branch")
        (is (= :db/valueType (:seon.boot/property offense))
            "naming the property Datahike will not apply")
        (is (= [:db.type/long declared]
               [(:seon.boot/installed-value offense)
                (:seon.boot/declared-value offense)])
            "and carrying both of its values as evidence")
        (is (str/includes? (:seon.error/message refusal)
                           ":db/valueType from :db.type/long to :db.type/string")
            "the message states the change rather than `predates`")
        (is (not (str/includes? (:seon.error/message refusal) "predates"))
            "so the reader is never told a whole-map inequality")))))
