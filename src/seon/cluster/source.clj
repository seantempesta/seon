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
            [seon.schema.form :as schema.form])
  (:import [java.nio.file Files]))

(schema.edn/load! {})

(def current-branch
  "The one branch that names the latest complete source database value."
  :current-src)

(def ^:private activation-missing-sample-size 10)

(defn activation-refusal
  "Bound a missing activation set for an operator-facing refusal."
  {:malli/schema [:=> [:cat :seon.activation/missing]
                  :seon.activation/refusal]}
  [missing]
  (let [total (count missing)
        sample (vec (take activation-missing-sample-size missing))
        omitted (- total (count sample))
        elision
        (when (pos? omitted)
          {:seon.print/face :seon.print/elided
           :seon.print/omitted omitted
           :seon.print/elision-unit :children
           :seon.render.data/total total
           :seon.render.data/path [:seon.activation/missing]
           :seon.render.data/next-offset (count sample)
           :seon.render.profile/id :seon.render.profile/operator
           :seon.print/requery-refusal
           "Activation refusal facts are available only at the refused database value."})]
    (cond->
     {:seon.error/message
      (str "The source activation closure is missing " total
           (if (= 1 total) " fact: " " facts: ")
           (pr-str sample)
           (when (pos? omitted) (str " … " omitted " more.")))
      :seon.activation/missing-count total
      :seon.activation/missing sample}
      elision (assoc :seon.activation/missing-elision elision))))

(def ^:private source-attributes
  [:seon.source/digest
   :seon.source/test-input-digest
   :seon.source/built-at
   :seon.source/activation-closure
   :seon.activation/source-digest
   :seon.activation/schema-keys
   :seon.activation/required-attributes
   :seon.activation/config-defaults
   :seon.activation/config-required
   :seon.activation/executable-symbols
   :seon.activation/lookup-refs
   :seon.activation.lookup/id
   :seon.activation.lookup/attribute
   :seon.activation.lookup/value])

(defn- refuse!
  [rule message data]
  (throw (ex-info message
                  (assoc data
                         :seon.error/kind ::refused
                         ::refused rule
                         ::rule rule))))

(defn- require-committed!
  [result rule message data]
  (when (:seon.error/kind result)
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
    (db/carry-derived-projection database)
    (refuse! ::source-absent "the adopted source commit is unavailable"
             {:seon.source/commit-id commit-id})))

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

(defn- resolve-activation
  [activation source-digest]
  (or (try
        (requiring-resolve activation)
        (catch Throwable _ nil))
      (refuse! ::activation-unresolvable
               (str "the activation derivation " activation " does not resolve")
               {:seon.source/activation activation
                :seon.source/digest source-digest})))

(defn- activation-seal-tx
  [connection source-digest requested-symbols activation-fn]
  (let [database-value (db/db connection)
        prior-id (db/q '[:find ?entity . :where [?entity :seon.source/digest]] database-value)
        prior (when prior-id
                (db/pull database-value
                         '[* {:seon.source/activation-closure [*]}] prior-id))
        prior-closure (:seon.source/activation-closure prior)]
    (if (and (:db/id prior-closure)
             (= source-digest (:seon.source/digest prior)))
      []
      (let [{closure :seon.activation/closure
             lookup-rows :seon.activation/lookup-rows
             missing :seon.activation/missing}
            (activation-fn
             {:seon.db/connection connection
              :seon.source/digest source-digest
              :seon.activation/requested-symbols requested-symbols})
            requirement-count
            (+ (count (:seon.activation/schema-keys closure))
               (count (:seon.activation/required-attributes closure))
               (count (:seon.activation/config-defaults closure))
               (count (:seon.activation/config-required closure))
               (count (:seon.activation/executable-symbols closure))
               (count (:seon.activation/lookup-refs closure)))
            activation-tempid (or (:db/id prior-closure) (str "activation:" source-digest))
            lookup-tempids
            (into {}
                  (map (fn [{id :seon.activation.lookup/id}]
                         [id (str "activation-lookup:" id)]))
                  lookup-rows)
            closure
            (assoc closure
                   :db/id activation-tempid
                   :seon.activation/lookup-refs
                   (mapv (fn [[_ id]] (get lookup-tempids id))
                         (:seon.activation/lookup-refs closure)))
            lookup-rows
            (mapv (fn [{id :seon.activation.lookup/id :as row}]
                    (assoc row :db/id (get lookup-tempids id)))
                  lookup-rows)]
        (when (seq missing)
          (let [refusal (activation-refusal missing)]
            (refuse! ::activation-incomplete
                     (:seon.error/message refusal)
                     (assoc refusal :seon.source/digest source-digest))))
        (when-not (pos? requirement-count)
          (refuse! ::activation-empty
                   "the source activation closure is empty"
                   {:seon.source/digest source-digest}))
        (into
         (into
          (into []
                (mapcat (fn [[attribute desired]]
                          (when (set? desired)
                            (for [value (get prior-closure attribute)
                                  :when (not (contains? desired value))]
                              [:db/retract activation-tempid attribute value]))))
                closure)
          (map (fn [lookup] [:db/retractEntity (:db/id lookup)]))
          (:seon.activation/lookup-refs prior-closure))
         (concat
          [(cond-> {:seon.source/digest source-digest
                    :seon.source/built-at (java.util.Date.)
                    :seon.source/activation-closure activation-tempid}
             prior-id (assoc :db/id prior-id))
           closure]
          lookup-rows))))))

(defn- retire-scratch!
  [store scratch]
  (try
    (registry/retire-branch! {:seon.store/store store
                              :seon.store/branch scratch})
    (catch Throwable _ nil)))

(defn- identity-rows
  [database-value identities]
  (let [rows (db/pull-many database-value [:db/id] identities)]
    (when (:seon.error/kind rows)
      (throw (ex-info "Program identity lookup failed during publication." rows)))
    (zipmap identities rows)))

(defn- evidence-entity
  "Read complete stored evidence through datoms, including owned children.
  Peer refs retain their identity; lineage reconciliation owns their history."
  {:malli/schema [:=> [:cat :seon.db/database-value :int] :map]}
  [database entity-id]
  (let [datoms (db/q '[:find ?attribute ?value :in $ ?entity
                       :where [?entity ?attribute ?value]] database entity-id)]
    (when (:seon.error/at datoms)
      (throw (ex-info "Published test evidence could not be read." datoms)))
    (reduce
     (fn [row [attribute value]]
       (let [declaration (get (:schema database) attribute)
             value (if (= :db.type/ref (:db/valueType declaration))
                     (if (:db/isComponent declaration)
                       (evidence-entity database value)
                       {:db/id value})
                     value)]
         (if (= :db.cardinality/many (:db/cardinality declaration))
           (update row attribute (fnil conj []) value)
           (assoc row attribute value))))
     {:db/id entity-id} datoms)))

(defn- result-preservation-tx
  "Carry latest evidence from the published head, never the rebuild's base.
  Keep the original run fingerprint even when a definition changed. Evidence
  remains inspectable; it certifies only the program actually tested."
  [previous]
  (if-not (get (:schema previous) :seon.test/run)
    []
    (let [results (db/q '[:find [?test ...]
                          :where [?test :seon.test/run]] previous)
          runs (db/q '[:find [?run ...]
                       :where [?run :seon.test.run/id]] previous)
          selector (cond-> [:seon.test/sym :seon.test/pass-count
                    :seon.test/fail-count :seon.test/error-count
                    :seon.test/run-basis-t :seon.test/run-at
                    :seon.test/failing-assertions :seon.test/failure-message
                    {:seon.test/run [:seon.test.run/id]}]
                     (get (:schema previous) :seon.test/reach-digest)
                     (conj :seon.test/reach-digest)
                     (get (:schema previous) :seon.test/reach)
                     (conj '(limit :seon.test/reach nil))
                     (get (:schema previous) :seon.test/reach-unknown)
                     (conj :seon.test/reach-unknown)
                     (get (:schema previous) :seon.test/failures)
                     (conj {:seon.test/failures
                            ['* {:seon.test.failure/file [:seon.fn.file/relative-path]}
                             {:seon.test.failure/first-run [:seon.test.run/id]}
                             {:seon.test.failure/last-run [:seon.test.run/id]}]}))
          run-rows (mapv #(evidence-entity previous %) runs)
          test-rows (db/pull-many previous selector results)]
      (doseq [rows [run-rows test-rows]]
        (when (:seon.error/kind rows)
          (throw (ex-info "Published test evidence could not be read." rows))))
      (into (mapv #(dissoc % :db/id) run-rows)
            (map (fn [test]
                   (let [row (dissoc test :db/id)]
                     (cond-> (assoc row :seon.test/run
                            [:seon.test.run/id
                             (get-in row [:seon.test/run :seon.test.run/id])])
                       (:seon.test/reach row)
                       (update :seon.test/reach
                               set)
                       (:seon.test/failures row)
                       (update :seon.test/failures
                         (fn [failures]
                           (mapv
                             (fn [failure]
                               (cond-> (assoc (dissoc failure :db/id)
                                         :seon.test.failure/test [:seon.test/sym (:seon.test/sym row)]
                                         :seon.test.failure/first-run [:seon.test.run/id (get-in failure [:seon.test.failure/first-run :seon.test.run/id])]
                                         :seon.test.failure/last-run [:seon.test.run/id (get-in failure [:seon.test.failure/last-run :seon.test.run/id])])
                                 (:seon.test.failure/file failure)
                                 (assoc :seon.test.failure/file [:seon.fn.file/relative-path (get-in failure [:seon.test.failure/file :seon.fn.file/relative-path])])))
                             failures)))))))
            test-rows))))

(defn- preserved-evidence-tx
  "Carry values without manufacturing absent program definitions.
   A retracted test has no current result; its old result remains in history.
   A missing optional file keeps its exact path."
  [database-value evidence]
  (let [identities (vec (distinct
                         (mapcat (fn [row]
                                   (concat (when-let [identity (program/row-identity row)] [identity])
                                           (keep :seon.test.failure/file (:seon.test/failures row))))
                                 evidence)))
        rows (identity-rows database-value identities)
        present? #(some? (get rows %))
        surviving (remove (fn [row]
                            (when-let [test-name (:seon.test/sym row)]
                              (not (present? [:seon.test/sym test-name])))) evidence)]
    (mapv
     (fn [row]
       (cond-> row
         (program/row-identity row)
         (assoc :db/id (program/row-identity row))
         (:seon.test/reach row) (update :seon.test/reach set)
         (:seon.test/failures row)
         (update :seon.test/failures
                 (fn [failures]
                   (mapv (fn [failure]
                           (let [site (:seon.test.failure/file failure)]
                             (if (and site (not (present? site)))
                               (-> failure
                                   (dissoc :seon.test.failure/file)
                                   (assoc :seon.test.failure/reported-file (second site)))
                               failure))) failures)))))
     surviving)))

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
                        (schema.form/attr-form-properties
                          (get-in (schema/projection-from-database head)
                                  [:seon.schema.projection/forms :seon.test/check-time-limit-ms]))))
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
  "Build and atomically publish one complete source database value."
  {:malli/schema [:=> [:cat :seon.source/publish-request]
                  :seon.source/published]}
  [{:keys [:seon.store/store]
    directory :seon.fn/root
    source-digest :seon.source/digest
    populate :seon.source/populate
    activation :seon.source/activation
    populate-request :seon.source/populate-request
    progress! :seon.source/progress!
    :or {progress! (constantly nil)}}]
  (let [input-digest (publication-input-digest! (or directory (fs/source-directory)))
          populate-fn (resolve-population populate source-digest)
          activation-fn (resolve-activation activation source-digest)
          expected-commit (:seon.source/commit-id (current store))
          scratch (scratch-branch)]
      (registry/branch! {:seon.store/store store
                         :seon.cluster.registry/from :db
                         :seon.store/branch scratch})
      (try
        (let [connection (store/open-branch! store scratch)]
          (try
            ;; Give the scratch a unique committed head before invoking the
            ;; population. If population fails immediately, this prevents its
            ;; branch from remaining an alias of `:db`, whose commit is an
            ;; ordinary ancestor of the already-published source history.
            (require-committed!
             (db/transact!
              connection
              {:tx-data (schema.datahike/malli->datahike-schema
                         source-attributes)})
             ::scratch-schema-refused
             "the source scratch schema transaction was refused"
             {:seon.source/digest source-digest})
            (populate-fn
             (merge populate-request
                    {:seon.db/connection connection
                     :seon.source/digest source-digest}))
            (progress! "publication issue indexing")
            (index-issues! connection source-digest (or directory (fs/source-directory)))
            (progress! "publication test evidence")
            (when expected-commit
              (let [evidence (result-preservation-tx
                              (database store expected-commit))]
                (progress! "publication test evidence transaction")
                (when (seq evidence)
                  (require-committed!
                   (db/transact!
                    connection
                    [[:db.fn/call
                      (fn [database-value]
                        (preserved-evidence-tx database-value evidence))]])
                   ::source-seal-refused
                   "The rebuilt source could not preserve test evidence."
                   {:seon.source/digest source-digest}))))
            (progress! "publication activation seal")
            ;; The source seal is the genesis boundary. Population must first
            ;; install canonical schema/program rows and boot/config process
            ;; facts; the digest and build instant are the final complete fact.
            (require-committed!
             (db/transact!
              connection
              {:tx-data
               (conj (activation-seal-tx
                      connection source-digest #{populate activation} activation-fn)
                     {:seon.source/digest source-digest
                      :seon.source/test-input-digest input-digest})})
             ::source-seal-refused
             "the source seal transaction was refused"
             {:seon.source/digest source-digest})
            (when expected-commit
              (when-let [refusal (db/deletion-error (database store expected-commit)
                                                    @connection)]
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
            (finally
              (d/release connection))))
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
          {:seon.source/branch current-branch
           :seon.source/commit-id commit-id
           :seon.source/digest source-digest
           :seon.source/built? true
           :seon.program/unresolved-report
           (unresolved-report! (database store commit-id))})
        (catch Throwable failure
          (retire-scratch! store scratch)
          (throw failure)))))

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

(defn upsert!
  "Publish canonical safe upserts against one exact source commit."
  {:malli/schema [:=> [:cat [:and :seon.source/upsert-request
                            [:map [:seon.fn/manifest {:optional true}
                                   :seon.fn.manifest/manifest]]]]
                  :seon.source/published]}
  [{:keys [:seon.store/store :seon.db/process]
    directory :seon.fn/root
    manifest :seon.fn/manifest
    populate :seon.source/populate
    populate-request :seon.source/populate-request
    rows :seon.source/upsert-rows
    expected-commit :seon.source/expected-commit-id
    source-digest :seon.source/digest
    activation :seon.source/activation}]
  (let [input-digest (publication-input-digest! (or directory (fs/source-directory)))
        activation-fn (resolve-activation activation source-digest)
        scratch (scratch-branch)]
    (registry/branch! {:seon.store/store store
                       :seon.cluster.registry/from expected-commit
                       :seon.store/branch scratch})
    (try
      (let [connection (store/open-branch! store scratch)]
        (try
          (assert-scalar-rows! @connection rows)
          (let [basis-before (:max-tx @connection)]
          (when populate
            ((resolve-population populate source-digest)
             (assoc populate-request :seon.db/connection connection)))
          (when (and manifest (nil? populate))
            (fn/index! {:seon.db/connection connection
                        :seon.fn/manifest manifest
                        :seon.source/previous-database @connection}
                       (constantly nil)))
          (let [digest-entities
                (db/q '[:find [?entity ...]
                       :where [?entity :seon.source/digest]]
                     @connection)]
            (when-not (= 1 (count digest-entities))
              (refuse! ::invalid-source-seal
                       "incremental publication requires one source digest entity"
                       {:seon.source/expected-commit-id expected-commit
                        ::digest-entity-count (count digest-entities)}))
            (when (seq rows)
              (require-committed!
               (db/transact!
                connection
                (cond->
                 {:tx-data rows}
                  process (assoc :tx-meta {:seon.db/process process})))
               ::incremental-source-refused
               "the incremental source transaction was refused"
               {:seon.source/digest source-digest
                :seon.source/expected-commit-id expected-commit}))
              (index-issues! connection source-digest (or directory (fs/source-directory)))
              (let [seal (conj (activation-seal-tx
                                connection source-digest #{activation} activation-fn)
                               {:seon.source/digest source-digest
                                :seon.source/test-input-digest input-digest})]
                (when (seq seal)
                 (require-committed!
                  (db/transact!
                   connection
                   (cond->
                    {:tx-data seal}
                  process (assoc :tx-meta {:seon.db/process process})))
               ::incremental-activation-refused
               "the incremental source activation transaction was refused"
               {:seon.source/digest source-digest
                :seon.source/expected-commit-id expected-commit})))
            (when (not= basis-before (:max-tx @connection))
             (d/force-branch! @connection current-branch #{expected-commit}
                             {:expected-current-commit expected-commit}))))
            (finally
              (d/release connection))))
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
        {:seon.source/branch current-branch
         :seon.source/commit-id commit-id
         :seon.source/digest source-digest
         :seon.source/built? true
           :seon.program/unresolved-report
           (unresolved-report! (database store commit-id))})
      (catch Throwable failure
        (retire-scratch! store scratch)
        (throw failure)))))
