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
    (refuse! rule message
             (assoc data :seon.source/transaction-result result)))
  result)

(defn- source-file?
  [filename]
  (or (str/ends-with? filename ".clj")
      (str/ends-with? filename ".cljc")
      (str/ends-with? filename ".edn")
      (str/ends-with? filename ".md")))

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
  (or (d/commit-as-db (:seon.store/connection-object store) commit-id)
      (refuse! ::source-absent "the adopted source commit is unavailable"
               {:seon.source/commit-id commit-id})))

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
                            [?entity ?identity-attribute ?value]
                            (not [?entity ?source-attribute])
                            [$history ?entity ?source-attribute]]
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

(defn- identity-tempid [[identity-attribute value]]
  (str "evidence-identity:" identity-attribute ":" value))

(defn- identity-namespace-tempid [namespace-name]
  (str "evidence-identity-ns:" namespace-name))

(defn- mintable-identity
  "The minimal row that asserts this identity, or nil when none is honest.

  ONE rule over every attribute `seon.program/identity-attributes` declares:
  take the row schema's required keys, drop the identity itself and the
  admission source, and mint only what remains DERIVABLE FROM THE IDENTITY.
  Nothing left means the identity alone is a complete row
  (`:seon.ns/ns`, `:seon.test/test`); a single required `:seon.db/ref` beside a
  namespace-qualified identity is that identity's namespace (`:seon.fn/fn`).
  Anything else — `:seon.fn.file/file` wants the digest of the file the indexer
  walked, `:seon.schema/schema` its form, `:seon.lint/finding` its site — cannot
  be minted without inventing a fact, so those callers keep the typed unknown."
  [forms [identity-attribute value :as program-identity]]
  (let [row-schema (:seon.program/row-schema
                    (schema.form/attr-form-properties (get forms identity-attribute)))
        entries (schema.form/map-entries (get forms row-schema))
        declared (into #{} (map first) entries)
        required (into [] (comp (remove #(:optional (second %))) (map first)) entries)
        companions (remove #{identity-attribute :seon.schema.admission/source} required)
        namespace-name (when (string? value)
                         (some-> (namespace (symbol value)) symbol))
        companion (first companions)
        companion-ref? (and companion (= 1 (count companions))
                            (= :seon.db/ref (get forms companion))
                            namespace-name)
        row (cond-> {:db/id (identity-tempid program-identity) identity-attribute value}
              (declared :seon.schema.admission/source)
              (assoc :seon.schema.admission/source :core)
              companion-ref?
              (assoc companion (identity-namespace-tempid namespace-name)))]
    (when (or (empty? companions) companion-ref?)
      (cond-> [row]
        companion-ref? (conj {:db/id (identity-namespace-tempid namespace-name)
                              :seon.ns/name namespace-name})))))

(defn absent-program-identities
  "The program identities this database value has no row for.

  Ruling 47 — PROGRAM IDENTITY ROWS NEVER RETRACT — makes a ref to a deleted
  declaration stable forever, because deletion retracts definition facts and
  leaves the identity behind as a tombstone. A database built FRESH from later
  source has nothing to tombstone, so evidence that crosses a publication (a
  recorded `:seon.test/reach` member, a failure's test or site) can name an
  identity that value never minted. The decision is made HERE, on the writer's
  own database, never on a caller's pre-read of the database the evidence
  came from."
  {:malli/schema [:=> [:cat :seon.db/database-value
                       [:sequential :seon.program/identity]]
                  [:set :seon.program/identity]]}
  [database-value identities]
  (into #{}
        (comp (distinct)
              (remove #(some? (db/pull database-value [:db/id] %))))
        identities))

(defn identity-tombstone-rows
  "Mint each absent identity as a tombstone: the identity and nothing else.

  This restores the population invariant at the writer rather than rejecting
  the whole transaction on the first absent lookup ref. A minted row carries
  no source, span, or contract — it asserts only that the name once existed,
  exactly as an ordinary deletion leaves it. An identity no honest minimal row
  can assert is skipped; its holder reports the typed unknown instead."
  {:malli/schema [:=> [:cat :map [:set :seon.program/identity]] [:vector :map]]}
  [forms absent]
  (into [] (comp (mapcat #(mintable-identity forms %)) (distinct))
        (sort-by pr-str absent)))

(defn identity-ref
  "The portable ref for this identity, or nil when none exists.

  A tempid is used for an absent identity because the minted row lands in the
  SAME transaction; a lookup ref would be resolved against the value before it.
  Nil means the identity is absent and cannot be minted honestly.

  `forms` is the packaged schema population, acquired ONCE by the operation
  and handed in: reading the schema resources from disk per call put a full
  EDN parse of every schema file inside the publication transaction for every
  evidence ref (2026-09-16, publication exceeded its 180 s bound)."
  {:malli/schema [:=> [:cat :map [:set :seon.program/identity] :seon.program/identity]
                  [:maybe [:or :seon.program/identity :string]]]}
  [forms absent program-identity]
  (cond
    (not (contains? absent program-identity)) program-identity
    (seq (mintable-identity forms program-identity))
    (identity-tempid program-identity)))

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
                     (conj {:seon.test/reach [:seon.fn/sym]})
                     (get (:schema previous) :seon.test/reach-unknown)
                     (conj :seon.test/reach-unknown)
                     (get (:schema previous) :seon.test/failures)
                     (conj {:seon.test/failures
                            ['* {:seon.test.failure/file [:seon.fn.file/relative-path]}
                             {:seon.test.failure/first-run [:seon.test.run/id]}
                             {:seon.test.failure/last-run [:seon.test.run/id]}]}))]
      (into (mapv #(dissoc (db/pull previous '[*] %) :db/id) runs)
            (map (fn [test]
                   (let [row (dissoc (db/pull previous selector test) :db/id)]
                     (cond-> (assoc row :seon.test/run
                            [:seon.test.run/id
                             (get-in row [:seon.test/run :seon.test.run/id])])
                       (:seon.test/reach row)
                       (update :seon.test/reach
                               #(mapv (fn [f] [:seon.fn/sym (:seon.fn/sym f)]) %))
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
            results))))

(defn- evidence-identities
  "Every program identity the carried evidence names, as a ref or as a row."
  [evidence]
  (into []
        (mapcat (fn [row]
                  (into (into [] (keep program/row-identity) [row])
                        (concat (:seon.test/reach row)
                                (keep :seon.test.failure/file (:seon.test/failures row))
                                (keep :seon.test.failure/test (:seon.test/failures row))))))
        evidence))

(defn- preserved-evidence-tx
  "Rewrite carried evidence against the database it is written into.

  Evidence outlives declarations: a `:seon.test/reach` member, a failure's
  own test, and a failure site can all name a declaration this rebuilt source
  no longer has. Every such identity is minted as a tombstone and referred to
  by its tempid, so refs to identities stay stable by construction. An
  identity no honest minimal row can assert — a file, whose entity requires
  the digest of the file the indexer walked — keeps its line and reports its
  path as the typed `:seon.test.failure/reported-file` instead of a dangling
  ref. Either way the rest of the evidence commits."
  [database-value evidence]
  (let [absent (absent-program-identities database-value (evidence-identities evidence))
        forms (schema.edn/packaged-forms)
        ref-of (partial identity-ref forms absent)
        reported-path?
        (some? (get (:schema database-value) :seon.test.failure/reported-file))
        portable-failure
        (fn [failure]
          (let [site (:seon.test.failure/file failure)
                owner (:seon.test.failure/test failure)
                path (second site)]
            (cond-> failure
              owner (assoc :seon.test.failure/test (ref-of owner))
              (and site (nil? (ref-of site)))
              (-> (dissoc :seon.test.failure/file)
                  (cond-> reported-path?
                    (assoc :seon.test.failure/reported-file path)))
              (and site (ref-of site))
              (assoc :seon.test.failure/file (ref-of site)))))]
    (into (identity-tombstone-rows forms absent)
          (map (fn [row]
                 (cond-> row
                   (program/row-identity row)
                   (assoc :db/id (identity-tempid (program/row-identity row)))
                   (:seon.test/reach row)
                   (update :seon.test/reach #(into [] (keep ref-of) %))
                   (:seon.test/failures row)
                   (update :seon.test/failures #(mapv portable-failure %)))))
          evidence)))

(defn- record-results-at-head!
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
                result (schema/call-with-projection
                        projection
                        #((requiring-resolve 'seon.test.runner/commit-results!)
                          connection (assoc completion :seon.test.run/branch current-branch)))]
            (if (:seon.error/kind result)
              result
              (do (d/force-branch! @connection current-branch #{expected}
                                   {:expected-current-commit expected})
                  result)))
          (finally (store/release-branch! connection))))
      (finally (retire-scratch! held-store scratch)))))

(defn record-results!
  "Publish test evidence through the result writer on a private source branch.
  Rebase a stale attempt on the latest head until the declared test allowance
  expires. Contention never changes the tested fingerprint; each attempt
  retires its scratch branch. Expiry reports the bound and last conflict."
  {:malli/schema
   [:=> [:cat :seon.store/store :seon.test.run/completion]
    [:or :seon.test/results :seon.error/value]]}
  [held-store completion]
  (if (empty? (:seon.test.runner/results completion))
    []
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
            (error/diagnostic
              {:seon.error/kind ::recording-expired
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
                :seon.await/config-value allowance :seon.test.runner/attempts attempt}}))
          (::recorded outcome)))))))

(defn- index-issues!
  [connection source-digest]
  (when (get (:schema (db/db connection)) :seon.issue/id)
    (require-committed!
     ((requiring-resolve 'seon.issue/index!)
      {:seon.db/connection connection
       :seon.issue/notes ((requiring-resolve 'seon.issue/notes) ".")})
     :seon.issue/index-refused "Issue indexing was refused."
     {:seon.source/digest source-digest})))

(defn publish!
  "Build and atomically publish one complete source database value."
  {:malli/schema [:=> [:cat :seon.source/publish-request]
                  :seon.source/published]}
  [{:keys [:seon.store/store]
    source-digest :seon.source/digest
    populate :seon.source/populate
    activation :seon.source/activation
    populate-request :seon.source/populate-request}]
  (let [populate-fn (resolve-population populate source-digest)
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
            (index-issues! connection source-digest)
            (when expected-commit
              (let [evidence (result-preservation-tx
                              (database store expected-commit))]
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
            ;; The source seal is the genesis boundary. Population must first
            ;; install canonical schema/program rows and boot/config process
            ;; facts; the digest and build instant are the final complete fact.
            (require-committed!
             (db/transact!
              connection
              {:tx-data
               (activation-seal-tx
                connection source-digest #{populate activation} activation-fn)})
             ::source-seal-refused
             "the source seal transaction was refused"
             {:seon.source/digest source-digest})
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
           :seon.source/built? true})
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
    manifest :seon.fn/manifest
    populate :seon.source/populate
    populate-request :seon.source/populate-request
    rows :seon.source/upsert-rows
    expected-commit :seon.source/expected-commit-id
    source-digest :seon.source/digest
    activation :seon.source/activation}]
  (let [activation-fn (resolve-activation activation source-digest)
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
              (index-issues! connection source-digest)
              (let [seal (activation-seal-tx
                          connection source-digest #{activation} activation-fn)]
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
         :seon.source/built? true})
      (catch Throwable failure
        (retire-scratch! store scratch)
        (throw failure)))))
