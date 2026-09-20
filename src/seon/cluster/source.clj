(ns seon.cluster.source
  "Publishes the latest complete source database value on one branch.

  Each changed digest is populated on a process-owned scratch branch. The
  first publication branches that complete head onto `:current-src`; later
  publications use Datahike's expected-head guard to advance the same branch.
  Failed and stale builds leave the previously published head untouched.
  Scratch branches are retired after publication and no connection to
  `:current-src` is opened or retained."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.cluster.process :as cluster.process]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.error :as error]
            [seon.fn :as fn]
            [seon.fs :as fs]
            [seon.program :as program]
            [seon.test.cache :as test.cache]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [malli.core :as m]
            [malli.registry :as mr])
  (:import [java.nio.file Files]))

(schema.edn/load! {})

(def current-branch
  "The one branch that names the latest complete source database value."
  :current-src)

(def ^:private source-attributes
  [:seon.source/digest :seon.source/test-input-digest :seon.source/built-at])

(defn- refuse!
  [rule message data]
  (throw (ex-info message
                  (assoc data
                         :seon.error/kind ::refused
                         ::refused rule
                         ::rule rule))))

(defn- require-committed!
  [result rule message data]
  (when (:seon.error/at result)
    (refuse! rule (str message " " (:seon.error/message result))
             (assoc data :seon.source/transaction-result result)))
  result)

(defn- source-file?
  [filename]
  (or (str/ends-with? filename ".clj")
      (str/ends-with? filename ".cljc")
      (str/ends-with? filename ".edn")))

(defn snapshot
  "The source-tree digest and exact per-file digests of the declared roots."
  {:malli/schema [:=> [:cat :seon.source/digest-request]
                  :seon.source/snapshot]}
  [{roots :seon.source/roots directory :seon.fn/root}]
  (let [directory (or directory (fs/source-directory))
        declared (->> roots
                      (map #(io/file (fs/absolute-path directory %)))
                      distinct
                      (sort-by #(.getPath ^java.io.File %)))]
    (doseq [^java.io.File root declared]
      (when-not (or (.isDirectory root) (.isFile root))
        (refuse! ::root-absent
                 (str "the declared source root " (.getPath root)
                      " is neither a directory nor a file")
                 {::root (.getPath root)
                  :seon.source/roots roots})))
    (let [entries
          (for [^java.io.File root declared
                :let [directory? (.isDirectory root)
                      prefix (inc (count (.getPath (if directory?
                                                    root
                                                    (.getParentFile root)))))]
                entry (if directory?
                        (->> (file-seq root)
                             (filter #(.isFile ^java.io.File %))
                             (filter #(source-file? (.getName ^java.io.File %)))
                             (sort-by #(subs (.getPath ^java.io.File %) prefix)))
                        ;; an explicitly declared file root is one digest
                        ;; entry regardless of extension
                        [root])
                :let [file-digest
                      (schema/sha-256
                       [(Files/readAllBytes (.toPath ^java.io.File entry))])]]
            {:path (fs/relative-path directory (.getCanonicalPath ^java.io.File entry))
             :relative-path (subs (.getPath ^java.io.File entry) prefix)
             :digest file-digest})]
      {:seon.source/digest
       (schema/sha-256
        (map (fn [{:keys [path digest]}]
               (.getBytes (str path "\u0000" digest "\n") "UTF-8"))
             entries))
       :seon.source/relative-file-digests
       (into (sorted-map) (map (juxt :path :digest)) entries)})))

(defn digest
  "Return the source-tree digest of the declared roots."
  {:malli/schema [:=> [:cat :seon.source/digest-request]
                  :seon.source/digest]}
  [request]
  (:seon.source/digest (snapshot request)))

(defn current
  "The published source branch and commit ID, or nil before publication."
  {:malli/schema [:=> [:cat :seon.store/store]
                  [:maybe :seon.source/current]]}
  [store]
  (when-let [commit-id
             (registry/branch-commit-id
              {:seon.store/store store
               :seon.store/branch current-branch})]
    {:seon.source/branch current-branch
     :seon.source/commit-id commit-id}))

(defn database
  "Read the exact published source commit without opening its branch."
  {:malli/schema
   [:=> [:cat :seon.store/store :seon.source/commit-id]
    :seon.db/database-value]}
  [store commit-id]
  (if-let [database
           (d/commit-as-db (:seon.store/connection-object store) commit-id)]
    (vary-meta database assoc :seon.schema/projection
               (schema/projection-from-database database))
    (refuse! ::source-absent "the adopted source commit is unavailable"
             {:seon.source/commit-id commit-id})))

(defn changed-identities
  "Publication report identities, or history since the cluster's adopted commit."
  {:malli/schema
   [:=> [:cat :seon.store/store :seon.source/published :seon.source/commit-id]
    :seon.reconcile/adopt-identities]}
  [store published prior-commit]
  (if (and (= prior-commit (:seon.source/expected-commit-id published))
           (contains? published :seon.reconcile/adopt-identities))
    (:seon.reconcile/adopt-identities published)
    (let [before (d/commit-as-db (:seon.store/connection-object store) prior-commit)]
      (when-not before
        (refuse! ::source-absent "The adopted source commit is unavailable."
                 {:seon.source/commit-id prior-commit}))
      (try
        (let [after (d/commit-as-db (:seon.store/connection-object store)
                                  (:seon.source/commit-id published))]
          (when-not after
            (refuse! ::source-absent "The published source commit is unavailable." published))
          (try
            (let [identities (fn/report-identities
                              {:db-before before :db-after after
                               :tx-data (vec (d/datoms (d/since (d/history after) (db/basis-t before))
                                                      :eavt))
                               :tempids {} :tx-meta {}})]
              (when (:seon.error/at identities)
                (refuse! ::publish-readback-failed "Publication changes could not be read." identities))
              identities)
            (finally (d/release-materialized-db after))))
        (finally (d/release-materialized-db before))))))

(defn- unresolved-report!
  {:malli/schema
   [:=> [:cat :seon.db/database-value]
    :seon.program/unresolved-report]}
  [database]
  (let [report (fn/unresolved-callers database)]
    (when (:seon.error/kind report)
      (refuse! ::publish-readback-failed
               "the published source unresolved-callers read was refused"
               {:seon.source/read 'seon.fn/unresolved-callers
                :seon.source/read-result report}))
    report))

(defn deleted-identities
  "Identities with historical definitions and no current definition."
  {:malli/schema
   [:=> [:cat :seon.db/database-value] :seon.fn.file/identities]}
  [database]
  (let [history (db/history database)]
    (into []
          (mapcat
           (fn [attribute]
             (let [source-attribute
                   (:seon.program/source-attribute (program/shape attribute))]
               (map (fn [value] [attribute value])
                    (db/q '[:find [?value ...]
                            :in $ $history ?identity-attribute ?source-attribute
                            :where
                            [$history ?entity ?identity-attribute ?value]
                            [$history ?entity ?source-attribute]
                            (not-join [?identity-attribute ?value]
                              [_ ?identity-attribute ?value])]
                          database history attribute source-attribute)))))
          program/identity-attributes)))

(defn- scratch-branch
  []
  (let [{:seon.boot/keys [pid start-instant]}
        (cluster.process/current-identity)]
    (keyword (str "building-source-" pid "-"
                  (inst-ms start-instant) "-" (random-uuid)))))

(defn- resolve-population
  [populate source-digest]
  (or (try
        (requiring-resolve populate)
        (catch Throwable _ nil))
      (refuse! ::populate-unresolvable
               (str "the population " populate " does not resolve")
               {:seon.source/populate populate
                :seon.source/digest source-digest})))

(defn- retire-scratch!
  [store scratch]
  (try
    (registry/retire-branch! {:seon.store/store store
                              :seon.store/branch scratch})
    (catch Throwable _ nil)))

(defn- record-results-at-head!
  {:malli/schema [:=> [:cat :seon.store/store :seon.source/test-recording-request]
                  :seon.source/test-recording-result]}
  [held-store completion]
  (let [expected (:seon.source/commit-id (current held-store))
        scratch (scratch-branch)]
    (when-not expected
      (refuse! ::source-absent "Test recording requires a published current-src." {}))
    (registry/branch! {:seon.store/store held-store
                       :seon.cluster.registry/from expected
                       :seon.store/branch scratch})
    (try
      (let [connection (store/open-branch! held-store scratch)]
        (try
          (let [projection (schema/projection-from-database @connection)
                required (when (:seon.test.run/published-base-digest (:seon.test.run/provenance completion))
                           [:seon.test.run/published-base-digest :seon.test.run/overlay-input-digest
                            :seon.test.run/callers-at-head :seon.source/test-selection-request])
                missing (vec (remove #(get-in projection [:seon.schema.projection/forms %]) required))
                result (if (seq missing)
                         (error/diagnostic
                          {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/recording
                           :seon.error/operation 'seon.cluster.source/record-results!
                           :seon.error/message "The published recording authority predates snapshot result admission. The orchestrator must publish the converged schema before fast recording can be enabled."
                           :seon.error/diagnostic-layer :test
                           :seon.error/diagnostic-operation ::record-results!
                           :seon.error/diagnostic-member current-branch
                           :seon.error/diagnostic-expected required
                           :seon.error/diagnostic-offending missing
                           :seon.error/diagnostic-cause :recording-schema-unavailable
                           :seon.error/diagnostic-evidence {:seon.source/commit-id expected}})
                         (schema/call-with-projection
                        projection
                        #(if (contains? completion :seon.test.runner/results)
                           ((requiring-resolve 'seon.test.runner/commit-results!)
                            connection (assoc completion :seon.test.run/branch current-branch))
                           (let [admission ((requiring-resolve 'seon.test/selection-admission)
                                            (assoc completion :seon.db/db (db/db connection)))
                                 report (when-not (:seon.error/at admission)
                                          (db/transact! connection
                                                        [[:db.fn/call (requiring-resolve 'seon.test/admit-run)
                                                          admission]]))]
                             (cond
                               (:seon.error/at admission) admission
                               (:seon.error/at report) report
                               :else
                               (let [reserved (db/q '[:find [?symbol ...] :in $ ?id
                                                      :where [?run :seon.test.run/id ?id]
                                                             [?run :seon.test.run/members ?member]
                                                             [?member :seon.test.member/symbol ?symbol]]
                                                    (:db-after report)
                                                    (get-in admission [:seon.test.run/provenance :seon.test.run/id]))
                                     expected (set (map :seon.test.member/symbol
                                                        (:seon.test.run/members admission)))]
                                 (if (= expected (set reserved)) admission
                                   (error/diagnostic
                                    {:seon.error/at (java.util.Date.)
                                     :seon.error/layer :seon.test/admission
                                     :seon.error/operation 'seon.cluster.source/record-results!
                                     :seon.error/message "Matching snapshot work is already admitted and has no recorded terminal result."
                                     :seon.error/diagnostic-layer :test
                                     :seon.error/diagnostic-operation ::record-results!
                                     :seon.error/diagnostic-member (get-in admission [:seon.test.run/provenance :seon.test.run/id])
                                     :seon.error/diagnostic-expected expected
                                     :seon.error/diagnostic-offending reserved
                                     :seon.error/diagnostic-cause :seon.test/claim-conflict
                                     :seon.error/diagnostic-evidence {:seon.test.run/provenance (:seon.test.run/provenance admission)}}))))))))]
            (if (:seon.error/at result)
              (assoc result :seon.source/refused-test-run
                     (get-in completion [:seon.test.run/provenance :seon.test.run/id]))
              (do (d/force-branch! @connection current-branch #{expected}
                                   {:expected-current-commit expected})
                  result)))
          (finally (store/release-branch! connection))))
      (finally (retire-scratch! held-store scratch)))))

(defn record-results!
  "Admit snapshot requests and publish completions through one source authority.
  Rebase a stale attempt on the latest head until the declared test allowance
  expires. Contention never changes the tested fingerprint; each attempt
  retires its scratch branch. Expiry reports the bound and last conflict."
  {:malli/schema
   [:=> [:cat :seon.store/store :seon.source/test-recording-request]
    :seon.source/test-recording-result]}
  [held-store completion]
    (let [published (or (current held-store)
                      (refuse! ::source-absent "Test recording requires a published current-src." {}))
        head (database held-store (:seon.source/commit-id published))
        allowance (or (:seon.test/remaining-ms completion)
                      (:seon.config/default
                        (m/properties
                          (mr/schema (:seon.schema.projection/registry
                                      (schema/projection-from-database head))
                                     :seon.test/check-time-limit-ms))))
        deadline (+ (System/nanoTime) (* 1000000 allowance))]
    (loop [attempt 1]
      (let [outcome (try
                      {::recorded (record-results-at-head! held-store completion)}
                      (catch clojure.lang.ExceptionInfo failure
                        (if (= :stale-branch-head (:type (ex-data failure)))
                          {::conflict (ex-data failure)}
                          (throw failure))))]
        (if-let [conflict (::conflict outcome)]
          (if (< (System/nanoTime) deadline)
            (recur (inc attempt))
            (assoc (error/diagnostic
              {:seon.error/at (java.util.Date.)
               :seon.error/layer :seon.test/recording
               :seon.error/operation 'seon.cluster.source/record-results!
               :seon.error/message "Test evidence publication exhausted its declared allowance while the source head changed."
               :seon.error/diagnostic-layer :test
               :seon.error/diagnostic-operation ::record-results!
               :seon.error/diagnostic-member (:seon.test.run/id (:seon.test.run/provenance completion))
               :seon.error/diagnostic-expected :committed-results
               :seon.error/diagnostic-offending conflict
               :seon.error/diagnostic-cause :seon.await/backstop-fired
               :seon.error/diagnostic-evidence
               {:seon.await/config-attribute (if (:seon.test/remaining-ms completion)
                                               :seon.test/remaining-ms :seon.test/check-time-limit-ms)
                :seon.await/config-value allowance :seon.test.runner/attempts attempt}})
                   :seon.source/refused-test-run
                   (get-in completion [:seon.test.run/provenance :seon.test.run/id])))
          (::recorded outcome))))))

(defn- index-issues!
  [connection source-digest directory]
  (when (get (:schema (db/db connection)) :seon.issue/id)
    (require-committed!
     ((requiring-resolve 'seon.issue/index!)
      {:seon.db/connection connection
       :seon.issue/notes ((requiring-resolve 'seon.issue/notes) directory)})
     :seon.issue/index-refused "Issue indexing was refused."
     {:seon.source/digest source-digest})))

(defn- publication-input-digest!
  "Require the snapshot inventory before beginning a publication transaction."
  {:malli/schema [:=> [:cat :string] :seon.source/digest]}
  [directory]
  (try
    (test.cache/test-input-digest directory (test.cache/input-digests directory))
    (catch Exception failure
      (let [refusal (error/diagnostic
                     {:seon.error/kind :seon.test/input-evidence-unavailable
                      :seon.error/message "The publication input inventory is unavailable."
                      :seon.error/diagnostic-layer :source-publication
                      :seon.error/diagnostic-operation 'seon.cluster.source/publish!
                      :seon.error/diagnostic-member directory
                      :seon.error/diagnostic-expected :snapshot-input-inventory
                      :seon.error/diagnostic-offending directory
                      :seon.error/diagnostic-cause :seon.test/input-evidence-unavailable
                      :seon.error/diagnostic-evidence {:seon.source/inventory-failure (str (ex-message failure))}})]
        (throw (ex-info (:seon.error/message refusal) refusal failure))))))

(defn publish!
  "Reconcile and atomically publish on the current source database history."
  {:malli/schema [:=> [:cat :seon.source/publish-request]
                  :seon.source/published]}
  [{:keys [:seon.store/store :seon.db/process]
    directory :seon.fn/root
    source-digest :seon.source/digest
    requested-commit :seon.source/expected-commit-id
    populate :seon.source/populate
    populate-request :seon.source/populate-request
    progress! :seon.source/progress!
    :or {progress! (constantly nil)}}]
  (let [published (current store)
        committed (when published
                    (d/commit-as-db (:seon.store/connection-object store)
                                    (:seon.source/commit-id published)))
        unchanged? (when committed
                     (try
                       (let [digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                                          committed)]
                         (when (map? digest)
                           (refuse! ::publish-readback-failed
                                    "The published source digest could not be read." digest))
                         (= source-digest digest))
                       (finally (d/release-materialized-db committed))))]
    (if unchanged?
      (assoc published :seon.source/digest source-digest :seon.source/built? false)
      (let [projection (schema/declaration-projection)
        input-digest (publication-input-digest! (or directory (fs/source-directory)))
          populate-fn (resolve-population populate source-digest)
          expected-commit (or requested-commit (:seon.source/commit-id published))
          scratch (scratch-branch)]
      (registry/branch! {:seon.store/store store
                         :seon.cluster.registry/from (or expected-commit :db)
                         :seon.store/branch scratch})
      (try
        (let [outcome
              (let [connection (store/open-branch! store scratch)]
          (try
            (let [previous-database
                  (when expected-commit
                    (or (:seon.source/previous-database populate-request)
                        (db/carry-derived-projection (d/db connection))))]
            ;; Reconcile on the published history. The scratch isolates refused
            ;; work; no test evidence is copied into a new history.
            (let [missing (filterv
                            (fn [row]
                              (let [definition (dissoc row :db/ident)]
                                (not= definition
                                      (select-keys (get (:schema @connection) (:db/ident row))
                                                   (keys definition)))))
                            (schema.datahike/malli->datahike-schema-in projection source-attributes))]
              (when (seq missing)
                (require-committed! (db/transact! connection {:tx-data missing})
                                   ::scratch-schema-refused
                                   "the source scratch schema transaction was refused"
                                   {:seon.source/digest source-digest})))
            (let [population-result (populate-fn
             (cond-> (merge populate-request
                            {:seon.db/connection connection
                             :seon.source/digest source-digest})
               expected-commit
               (assoc :seon.source/previous-database
                      previous-database)))
                  _ (progress! "publication issue indexing")
                  issues (index-issues! connection source-digest (or directory (fs/source-directory)))
                  _ (progress! "publication source identity")]
            (require-committed! population-result ::source-population-refused
                                "The source population was refused." {})
            ;; The source seal is the genesis boundary. Population must first
            ;; install canonical schema/program rows and boot/config process
            ;; facts; the digest and build instant are the final complete fact.
            (require-committed!
             (db/transact!
              connection
              (cond-> {:tx-data
               [(let [prior-id (db/q '[:find ?entity . :where [?entity :seon.source/digest]]
                                      (db/db connection))]
                  (cond-> {:seon.source/digest source-digest
                           :seon.source/built-at (java.util.Date.)
                           :seon.source/test-input-digest input-digest}
                    prior-id (assoc :db/id prior-id)))]}
                process (assoc :tx-meta {:seon.db/process process})))
             ::source-seal-refused
             "the source seal transaction was refused"
             {:seon.source/digest source-digest})
            (when expected-commit
              (when-let [refusal (db/deletion-error previous-database (db/db connection))]
                (refuse! ::source-deletion-refused
                         (:seon.error/message refusal)
                         (:seon.error/data refusal))))
            (progress! "publication branch head")
            (if expected-commit
              ;; The scratch commit is deliberately NOT a parent. Published
              ;; history follows the prior `current-src` commit, keeping the
              ;; scratch outside descendant-retirement safety.
              (d/force-branch! @connection current-branch #{expected-commit}
                               {:expected-current-commit expected-commit})
              ;; Equal heads are not strict descendants, so initial scratch
              ;; retirement remains safe under the existing registry rule.
              (let [scratch-commit (db/commit-id @connection)
                    {:seon.cluster/keys [created?]}
                    (registry/branch! {:seon.store/store store
                                       :seon.cluster.registry/from scratch
                                       :seon.store/branch current-branch})]
                (when-not created?
                  (refuse! ::stale-publication
                           "another publisher created current-src first"
                           {:seon.source/branch current-branch
                            :seon.source/commit-id scratch-commit}))))
            (cond-> {}
              (and (map? population-result)
                   (contains? population-result :seon.reconcile/adopt-identities))
              (assoc :seon.reconcile/adopt-identities
                     (into (:seon.reconcile/adopt-identities population-result)
                           (when-let [report (:seon.db/transaction-report issues)]
                             (require-committed! (fn/report-identities report)
                                                 ::publish-readback-failed
                                                 "Issue report identities could not be read." {}))))
              (nil? expected-commit)
              (assoc :seon.program/unresolved-report (unresolved-report! (db/db connection))))))
            (finally
              (d/release connection))))]
        (let [commit-id
              (registry/branch-commit-id
               {:seon.store/store store
                :seon.store/branch current-branch})]
          (when-not (uuid? commit-id)
            (refuse! ::publish-readback-failed
                     "the published source branch has no commit ID"
                     {:seon.source/branch current-branch}))
          (registry/retire-branch! {:seon.store/store store
                                    :seon.store/branch scratch})
          (cond-> (merge outcome
                          {:seon.source/branch current-branch
                           :seon.source/commit-id commit-id
                           :seon.source/digest source-digest
                           :seon.source/built? true})
            expected-commit (assoc :seon.source/expected-commit-id expected-commit))))
        (catch Throwable failure
          (retire-scratch! store scratch)
          (throw failure)))))))

(defn- assert-scalar-rows!
  [db rows]
  (let [attributes (into #{} (mapcat keys) rows)
        unsafe
        (into []
              (filter (fn [attribute]
                        (let [definition (get (:schema db) attribute)]
                          (or (nil? definition)
                              (not= :db.cardinality/one
                                    (:db/cardinality definition))
                              (:db/isComponent definition)))))
              attributes)]
    (when (seq unsafe)
      (refuse! ::unsafe-incremental-rows
               "incremental publication accepts scalar attributes only"
               {:seon.source/unsafe-attributes (vec (sort unsafe))}))))

(defn populate-upserts!
  "Admit scalar rows at the population seam of the common publisher."
  {:malli/schema [:=> [:cat [:map [:seon.db/connection :seon.db/connection]
                                  [:seon.source/upsert-request :seon.source/upsert-request]]] :nil]}
  [{connection :seon.db/connection previous :seon.source/previous-database
    request :seon.source/upsert-request}]
  (let [{rows :seon.source/upsert-rows manifest :seon.fn/manifest
         populate :seon.source/populate process :seon.db/process
         digest :seon.source/digest} request]
    (assert-scalar-rows! @connection rows)
    (when populate
      ((resolve-population populate digest)
       (assoc (:seon.source/populate-request request) :seon.db/connection connection
              :seon.source/previous-database previous)))
    (when (and manifest (nil? populate))
      (fn/index! {:seon.db/connection connection :seon.fn/manifest manifest
                  :seon.source/previous-database previous} (constantly nil)))
    (when (seq rows)
      (require-committed!
        (db/transact! connection (cond-> {:tx-data rows}
                                  process (assoc :tx-meta {:seon.db/process process})))
        ::incremental-source-refused "the incremental source transaction was refused"
        {:seon.source/digest digest})))
  nil)

(defn upsert!
  "Publish admitted scalar rows through the common publication owner."
  {:malli/schema [:=> [:cat [:and :seon.source/upsert-request
                            [:map [:seon.fn/manifest {:optional true} :seon.fn.manifest/manifest]]]]
                  :seon.source/published]}
  [request]
  (publish! (assoc request :seon.source/populate `populate-upserts!
                    :seon.source/populate-request
                    (cond-> {:seon.source/upsert-request request}
                      (:seon.fn/manifest request)
                      (assoc :seon.fn/manifest (:seon.fn/manifest request))))))
