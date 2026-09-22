(ns seon.cluster-test
  "Cluster reconciliation invariants that own no cluster process."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.db :as db]
            [seon.error :as error]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

(def ^:private ordered-marker ::ordered)

(deftest boot-recovery-refuses-an-unreadable-open-turn-query
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            refusal (db/q '[:find ?entity .
                            :where [?entity :seon.audit/poison _]]
                          database)
            before (db/basis-t database)
            recovered (#'cluster/recover-runs! connection)
            result (with-redefs [db/q (fn [& _] refusal)]
                     (#'cluster/recover-runs! connection))]
        (is (= {:seon.boot/recovered-runs 0
                :seon.boot/recovery-operations 0}
               recovered)
            "a fresh canonical database positively completes recovery")
        (is (and (map? refusal)
                 (contains? refusal :seon.error/at)
                 (contains? refusal :seon.error/layer)
                 (contains? refusal :seon.error/operation)) (pr-str refusal))
        (is (= refusal result)
            "boot recovery returns the failed read instead of recovering zero turns")
        (is (= before (db/basis-t (db/db connection)))
            "an unreadable recovery decision commits nothing")))))

(deftest process-identity-set-building-refuses-an-unreadable-query
  (test-support/with-database
    (fn [connection]
      (let [database (db/db connection)
            refusal (db/q '[:find ?entity .
                            :where [?entity :seon.audit/poison _]]
                          database)
            missing-process-rows
            (#'cluster/missing-process-rows refusal)]
        (is (and (map? refusal)
                 (contains? refusal :seon.error/at)
                 (contains? refusal :seon.error/layer)
                 (contains? refusal :seon.error/operation)) (pr-str refusal))
        (is (= refusal missing-process-rows)
            "process identities are not rebuilt from error-map entries")))))

(deftest ^{:seon.test/long "Compare the complete canonical schema population under both namespace-map printing modes; the supplied projection removes the former 19.57 s reconstruction. Full schema comparison remains proportional to the population."
           :seon.test/long-ms 30000}
  schema-row-convergence-uses-the-stores-own-semantics
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
        (doseq [namespace-maps? [false true]]
          (binding [*print-namespace-maps* namespace-maps?]
            (let [delta (changes database (schema/handed-projection))]
              (is (= [] delta)
                  (str "canonical schema bytes converge with namespace-map printing "
                       namespace-maps?)))))))))

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
                            {:seon.error/declared-schema :seon.flow/exception-error
                             :seon.error/source
                             {::seon.flow/pid :seon.cluster-test/probe
                              ::seon.flow/op :step
                              ::seon.flow/ex (ex-info "probe core fault" {})}})
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
            request {:seon.error/declared-schema :seon.agent/error
                     :seon.error/source
                     {:seon.error/at #inst "2026-09-20T00:00:00Z"
                      :seon.error/layer :seon.agent/lifecycle
                      :seon.error/operation 'seon.agent/by-id
                      :seon.agent/error-agent-id "cluster-test-observed"
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
            data refusal]
        (is ((schema/projection-validator (schema/handed-projection) :seon.instrument/contract-error) refusal)
            "the contract refuses before the recorder is reached")
        (is (= :input (:seon.instrument/check refusal)))
        (is (= 'seon.error/commit-tx (:seon.error/operation data))
            "and names the function that was called")
        (is (some #{[:seon.config.error/max-evidence-bytes]}
                  (:seon.instrument/problem-paths data))
            "EVERY Malli problem path is reported, so the key a caller has
             to supply is named rather than one of N problems")
        (is (str/includes? (:seon.error/message refusal)
                           ":seon.config.error/max-evidence-bytes")
            "and the message itself names it")
        (is (string? (:seon.instrument/caller
                      data))
            "with a caller frame that is not malli's own wrapper")
        (is (empty? (committed-fault-ids @connection))
            "and the refused call committed nothing")))))

(deftest a-dropped-storage-property-refuses-reopening-the-branch-in-place
  ;; The canonical population is the installed declaration set, so the
  ;; converged case asserts what production reopening does: no declaration
  ;; change at all. Dropping a property the branch still carries is the class
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
                   #(changes database (schema/handed-projection) "converged-fixture")))
            "a converged branch reopens with no declaration change")
        (let [stale (assoc-in database [:schema attribute :db/unique]
                              :db.unique/identity)
              refusal (test-support/refusal-data
                       #(schema/call-with-forms
                         forms
                         (fn [] (changes stale (schema/handed-projection) "stale-fixture"))))]
          (is (true? (:seon.boot/refused refusal))
              "a property the current declaration no longer carries refuses")
          (is (= attribute (:seon.boot/attribute (:seon.boot/offense refusal))))
          (is (= :db.unique/identity
                 (:db/unique (:seon.boot/installed (:seon.boot/offense refusal))))
              "and the refusal carries the installed property as evidence")
          (is (str/includes? (:seon.error/message refusal)
                             "bin/seon init stale-fixture --force")
              "naming the refork that resolves it"))))))

(def ^:private indexable-marker ::indexable)
(def ^:private indexable-row-id ::indexable-row-id)

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
     [{:db/ident indexable-row-id
       :db/valueType :db.type/string
       :db/cardinality :db.cardinality/one
       :db/unique :db.unique/identity}
      {:db/ident indexable-marker
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
                            #(changes older (schema/handed-projection) "older-fixture"))]
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
                                       {indexable-row-id (str "row-" index)
                                        indexable-marker (str "marker-" index)})
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
                       (fn [] (changes stale (schema/handed-projection) "stale-fixture"))))
            offense (:seon.boot/offense refusal)]
        (is (= :db.type/string declared)
            "the bridge derives the subject attribute as a string")
        (is (true? (:seon.boot/refused refusal))
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

(defn- analysis-source-change-failure
  "The refusal `seon.fn`'s span read raises when the file grew under it.

  Captured once from the real file, sliced against a span the analyzer could
  only have produced from the LARGER text: exactly the publication failure in
  `docs/seon/issues/source-analysis-throws-when-a-file-changes-between-snapshot-and-span-read.md`."
  []
  (let [file (java.io.File/createTempFile "cluster-span-change" ".clj")]
    (try
      (spit file "(ns probe.span)\n(defn a [] 1)\n")
      (let [contexts ((ns-resolve 'seon.fn 'source-contexts) [file])
            path (.getCanonicalPath file)]
        (spit file "(ns probe.span)\n(defn a [] 1)\n(defn b [] 2)\n")
        (try
          ((ns-resolve 'seon.fn 'exact-source)
           contexts
           {:seon.fn.analyzer/filename path
            :seon.fn.analyzer/row 3 :seon.fn.analyzer/col 1
            :seon.fn.analyzer/end-row 3 :seon.fn.analyzer/end-col 13})
          nil
          (catch clojure.lang.ExceptionInfo failure failure)))
      (finally (.delete file)))))

(deftest a-source-change-during-analysis-takes-the-one-publication-retry
  ;; The analysis-time refusal and the adoption-time digest compare are the
  ;; same event at two seams; both take the single retry, and neither is a
  ;; rebuild reason (`docs/prds/steward-platform/research/
  ;; adoption-retry-on-analysis-refusal-2026-09-17.md`).
  (let [retrying (ns-resolve 'seon.cluster 'retrying-source-change)
        phase-of (ns-resolve 'seon.cluster 'source-change-phase)
        progress (ns-resolve 'seon.cluster '*source-progress!*)
        analysis-failure (analysis-source-change-failure)
        adoption-failure (ex-info "Source changed during development adoption."
                                  {
                                   :seon.boot/offense
                                   {:seon.source/digest-before "c0"}})
        reported (atom [])
        attempts (atom 0)
        converging (fn [failure]
                     (fn []
                       (if (= 1 (swap! attempts inc))
                         (throw failure)
                         {:seon.source/commit-id "converged"})))]
    (is (some? analysis-failure)
        "the captured span read genuinely refuses instead of returning source")
    (is (true? (:seon.fn/index-refused (ex-data analysis-failure))))
    (is (vector? (:seon.fn/analysis-span (ex-data analysis-failure))))
    (is (= [:analysis :adoption nil]
           [(phase-of analysis-failure)
            (phase-of adoption-failure)
            (phase-of (ex-info "unrelated" {}))])
        "one predicate names the phase for both seams and refuses to widen")
    (with-bindings {progress (fn [phase] (swap! reported conj phase))}
      (is (= {:seon.source/commit-id "converged"}
             (retrying (converging analysis-failure)))
          "an analysis refusal retries and converges on the stable second read")
      (is (= 2 @attempts) "exactly once, never twice")
      (is (= ["source changed during analysis; retrying publication once"]
             @reported)
          "and the operator is told which phase changed")
      (reset! attempts 0)
      (is (= {:seon.source/commit-id "converged"}
             (retrying (converging adoption-failure)))
          "the adoption compare keeps its own single retry")
      (let [surviving (test-support/refusal-data
                       #(retrying (fn [] (throw analysis-failure))))]
        (is (true? (:seon.boot/refused surviving))
            "a second change refuses rather than rebuilding")
        (is (= :analysis (get-in surviving [:seon.boot/offense :seon.source/change-phase]))
            "naming the phase that changed under the retry")
        (is (= (:seon.fn/analysis-span (ex-data analysis-failure))
               (get-in surviving [:seon.boot/offense :seon.fn/analysis-span]))
            "retaining the captured span")))))

(deftest initialization-readiness-surfaces-a-refused-read-instead-of-absence
  ;; The readiness probe answers three states, not two. Before this, a
  ;; `seon.db/pull` REFUSAL on a lookup ref was read as "the entity is not
  ;; there yet": every row stayed waiting, nothing was ever ready, and the
  ;; boot refused with "Initialization lookup refs do not resolve." about
  ;; targets that were in the database all along (2026-09-18, republish).
  (test-support/with-database
    (fn [connection]
      (let [;; A unique lookup attribute carrying a value its installed type
            ;; rejects: the read refuses instead of answering absence.
            row {:seon.ai.model/id "seon.cluster-test/unreadable-lookup"
                 :seon.ai.model/provider [:seon.ai.model/provider-id 42]}
            before (db/basis-t (db/db connection))
            refusal (test-support/refusal-data
                     #(#'cluster/transact-initialization! connection [row]))
            offense (:seon.boot/offense refusal)]
        (is (true? (:seon.boot/refused refusal))
            (pr-str refusal))
        (is (not (str/includes? (str (:seon.error/message refusal))
                                "lookup refs do not resolve"))
            (str "the refusal names the refused read, not absence: "
                 (:seon.error/message refusal)))
        (is (= :seon.ai.model/provider-id
               (:seon.activation/lookup-attribute offense))
            (pr-str offense))
        (is (and (map? (:seon.boot/result offense))
                 (contains? (:seon.boot/result offense) :seon.error/at)
                 (contains? (:seon.boot/result offense) :seon.error/layer)
                 (contains? (:seon.boot/result offense) :seon.error/operation))
            "the read's own refusal is carried verbatim")
        (is (= before (db/basis-t (db/db connection)))
            "a refused readiness read commits nothing")))))

(deftest initialization-orders-provider-rows-before-the-models-naming-them
  (test-support/with-database
    (fn [connection]
      (let [provider {:seon.ai.model/provider-id "seon.cluster-test/provider"
                      :seon.config.ai/endpoint "https://example.invalid/v1/chat/completions"
                      :seon.config.ai/api-key-variable "SEON_CLUSTER_TEST_KEY"
                      :seon.ai.model/openai-chat-completions true
                      :seon.ai.model/output-token-wire-key "max_tokens"}
            model {:seon.ai.model/id "seon.cluster-test/model"
                   :seon.ai.model/provider
                   [:seon.ai.model/provider-id "seon.cluster-test/provider"]
                   :seon.ai.model/context-window-tokens 1000
                   :seon.ai.model/input-modalities #{:text}}]
        ;; The model row is offered FIRST: readiness, not input order, decides.
        (#'cluster/transact-initialization! connection [model provider])
        (let [database (db/db connection)
              pulled (db/pull database
                              [{:seon.ai.model/provider [:seon.ai.model/provider-id]}]
                              [:seon.ai.model/id "seon.cluster-test/model"])]
          (is (= "seon.cluster-test/provider"
                 (get-in pulled [:seon.ai.model/provider :seon.ai.model/provider-id]))
              (pr-str pulled)))))))
