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
            [seon.id :as id]
            [seon.program :as program]
            [seon.test.cache :as test.cache]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn])
  (:import [java.nio.file Files]))

(schema.edn/load! {})

(def current-branch
  "The one branch that names the latest complete source database value."
  :current-src)

(def ^:private source-attributes
  [:seon.source/digest :seon.source/test-input-digest :seon.source/built-at])

(defn- refuse!
  {:malli/schema [:=> [:cat :keyword :string :map] :nil]}
  [rule message data]
  (throw (ex-info message
                  (assoc data

                         ::refused rule
                         ::rule rule))))

(defn- require-committed!
  [result rule message data]
  (when (:seon.error/at result)
    (refuse! rule (str message " " (:seon.error/message result))
             (assoc data :seon.source/transaction-result result)))
  result)

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
                             (filter #(test.cache/source-file? (.getName ^java.io.File %)))
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

(defn capture-paths
  "Read named inputs once; carry the same Clojure text to the analyzer.
  Directory inputs contribute their recorded Git pin, never a recursive walk."
  {:malli/schema [:=> [:cat :string [:vector :string]]
                  [:tuple :seon.source/relative-file-digests [:map-of :string :string]]]}
  [directory paths]
  (let [pins (when (some #(.isDirectory (io/file directory %)) paths)
               (test.cache/gitlink-digests directory))]
    (reduce (fn [[digests sources] path]
              (let [file (io/file directory path)]
                (cond
                  (.isFile file)
                  (let [bytes (Files/readAllBytes (.toPath file))]
                    [(assoc digests path (schema/sha-256 [bytes]))
                     (if (some #(str/ends-with? path %) [".clj" ".cljc"])
                       (assoc sources (.getCanonicalPath file) (String. ^bytes bytes "UTF-8"))
                       sources)])
                  (get pins path) [(assoc digests path (get pins path)) sources]
                  :else [digests sources])))
            [{} {}] paths)))

(defn path-digests
  "Read digests of named inputs through the publication capture owner."
  {:malli/schema [:=> [:cat :string [:vector :string]] :seon.source/relative-file-digests]}
  [directory paths]
  (first (capture-paths directory paths)))

(defn discover-paths
  "Discover declared publication inputs from the checkout or export inventory."
  {:malli/schema [:=> [:cat :string :seon.source/roots] [:vector :string]]}
  [directory roots]
  (let [inputs (test.cache/input-roots directory)
        roots (set roots)]
    (into [] (comp (filter #(or (test.cache/input-path? inputs %)
                               (and (test.cache/input-path? roots %)
                                    (test.cache/source-file? %))))
                   (distinct))
          (test.cache/input-paths directory))))

(defn dependency-digests
  "The digests of `deps.edn` and every gitlink pin in `directory`, in the
  capture's own form: file bytes through `schema/sha-256`, a gitlink by its
  recorded pin (`capture-paths`)."
  {:malli/schema [:=> [:cat :string] :seon.source/relative-file-digests]}
  [directory]
  (let [manifest (io/file directory "deps.edn")]
    (cond-> (test.cache/gitlink-digests directory)
      (.isFile manifest)
      (assoc "deps.edn" (schema/sha-256 [(Files/readAllBytes (.toPath manifest))])))))

(defonce ^{:private true
           :doc "The dependency inputs this JVM loaded: read once when the namespace
  first loads at launch, and kept across reloads, because the classpath they
  chose is fixed for the life of the process."}
  launched-dependencies
  ;; A process launched outside a checkout (no Git index, no recorded pins)
  ;; loaded no checkout dependencies to compare.
  (let [directory (fs/source-directory)]
    (if (or (.exists (io/file directory ".git"))
            (.isFile (io/file directory "dependency-pins.txt")))
      (dependency-digests directory)
      {})))

(defn loaded-dependencies
  "The `deps.edn` and gitlink digests the running JVM launched with."
  {:malli/schema [:=> [:cat] :seon.source/relative-file-digests]}
  []
  launched-dependencies)

(defn classify-paths
  "Analyzer configuration invalidates every source. A changed `deps.edn` or
  gitlink refuses only when the running JVM loaded other bytes: `current`
  holds the files' digests and `loaded` the ones the JVM launched with
  (`loaded-dependencies`). A JVM started after the change loaded exactly those
  files, so its publication of them proceeds."
  {:malli/schema [:=> [:cat [:set :string] [:set :string]
                       :seon.source/relative-file-digests :seon.source/relative-file-digests]
                  [:enum :selected :all]]}
  [changed gitlinks current loaded]
  (let [dependencies (into #{}
                           (filter #(and (or (= "deps.edn" %) (gitlinks %))
                                         (not= (get current % ::absent) (get loaded % ::absent))))
                           changed)]
    (when (seq dependencies)
      ;; The JVM's classpath is fixed at launch, so a changed manifest or
      ;; vendored pin cannot load into this process. The store, its branches
      ;; and every database fact are unaffected: `bin/seon down` then
      ;; `bin/seon start` replaces the JVM on the same store, and that JVM's
      ;; publication of these files proceeds.
      (refuse! ::restart-needed
               (str "JVM RESTART NEEDED: loaded dependencies changed ("
                    (str/join ", " (sort dependencies))
                    "). Run `bin/seon down` then `bin/seon start` on the same"
                    " store; the store and its database survive, no reset.")
               {:seon.source/changed-paths (vec (sort dependencies))
                ::loaded (select-keys loaded dependencies)
                ::current (select-keys current dependencies)}))
    (if (some #(or (= ".clj-kondo" %) (str/starts-with? % ".clj-kondo/")) changed)
      :all
      :selected)))

(defn snapshot-test-input-digest
  "The publication snapshot's test-input digest, computed from content already held.

  The digest is `seon.test.cache/test-input-digest` of the checkout's gate
  inputs that carry no indexed declarations. It is a function of those
  inputs' paths and bytes, so:

  - when `:seon.source/changed-paths` names every changed path (a partial
    publication) and none of them is such an input, `::published`, the
    digest the prior publication stored, is still valid and is returned
    without reading anything (`::reused`);
  - otherwise Git's inventory names the inputs, every one whose digest
    `:seon.source/relative-file-digests` already holds (captured by this
    publication, or stored and unchanged) is reused, and only the rest are
    read (`::read`).

  `::roots` is the checkout's `seon.test.cache/input-roots`, held by the
  caller for the whole publication."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.fn/root :string]
               [::roots [:set :string]]
               [:seon.source/relative-file-digests :seon.source/relative-file-digests]
               [::published {:optional true} :seon.source/test-input-digest]
               [:seon.source/changed-paths {:optional true} :seon.source/changed-paths]]]
    [:map
     [:seon.source/test-input-digest :seon.source/test-input-digest]
     [::reused :boolean]
     [::hits :int]
     [::read [:vector :string]]]]}
  [{directory :seon.fn/root roots ::roots inputs :seon.source/relative-file-digests
    published ::published changed :seon.source/changed-paths}]
  (if (and published changed
           (not-any? #(test.cache/widening-path? roots %) changed))
    {:seon.source/test-input-digest published ::reused true ::hits 1 ::read []}
    (let [listed (filterv #(test.cache/widening-path? roots %)
                          (test.cache/input-paths directory))
          held (select-keys inputs listed)
          unread (filterv #(not (contains? held %)) listed)
          [captured _] (capture-paths directory unread)]
      {:seon.source/test-input-digest
       (test.cache/input-evidence-digest (merge captured held))
       ::reused false
       ::hits (count held)
       ::read unread})))

(defn stored-path-digests
  "The stored digest of each named input, read from `database`'s file rows.

  A raw Datahike query: the answer needs no schema projection, so a published
  commit whose stored contracts name a since-deleted predicate is still
  readable (resume compares file digests before publishing the files that
  replace those contracts)."
  {:malli/schema [:=> [:cat :seon.db/database-value [:vector :string]]
                  :seon.source/relative-file-digests]}
  [database paths]
  (into {}
        (d/q '[:find ?path ?digest
               :in $ [?path ...]
               :where [?file :seon.fn.file/relative-path ?path]
                      [?file :seon.fn.file/digest ?digest]]
             database paths)))

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

(defn commit-database
  "The exact published source commit as a raw database value, with no schema
  projection derived; `nil` when the commit is absent."
  {:malli/schema [:=> [:cat :seon.store/store :seon.source/commit-id]
                  [:or :nil :seon.db/database-value]]}
  [store commit-id]
  (d/commit-as-db (:seon.store/connection-object store) commit-id))

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
                              before after
                              (vec (d/datoms (d/since (d/history after) (db/basis-t before))
                                             :eavt)))]
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
    (when (or (:seon.db/invalid-read report) (:seon.schema/expected-value report))
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
  {:malli/schema [:=> [:cat :qualified-symbol :seon.source/digest] [:fn clojure.core/ifn?]]}
  [populate source-digest]
  (let [data {::refused ::populate-unresolvable
              ::rule ::populate-unresolvable
              :seon.source/populate populate
              :seon.source/digest source-digest}
        message (str "the population " populate " does not resolve")
        resolved (try
                   (requiring-resolve populate)
                   ;; A load failure of the population's namespace is the cause.
                   (catch Exception failure
                     (throw (ex-info message data failure))))]
    (or resolved (throw (ex-info message data)))))

(defn- retire-scratch!
  "Unlink one scratch branch. When cleanup follows `primary`, a cleanup failure
  is attached to it as suppressed; otherwise it propagates."
  {:malli/schema [:=> [:cat :seon.store/store :keyword [:or :seon.error/throwable :nil]] :nil]}
  [store scratch primary]
  (try
    (registry/retire-branch! {:seon.store/store store
                              :seon.store/branch scratch})
    nil
    (catch Exception failure
      (if primary
        (do (.addSuppressed ^Throwable primary failure) nil)
        (throw failure)))))

(defn publication-error
  "The typed stale-head refusal, from Datahike's `:stale-branch-head` data."
  {:malli/schema [:=> [:cat :qualified-symbol
                       [:map [:branch :keyword] [:expected-current-commit :uuid]
                        [:current-commit :uuid]]]
                  :seon.source/publication-error]}
  [operation data]
  {:seon.error/at (java.util.Date.)
   :seon.error/layer :seon.source/publication
   :seon.error/operation operation
   :seon.error/message "The source head changed before publication."
   :seon.error/expected (:expected-current-commit data)
   :seon.error/offending (:current-commit data)
   :seon.error/data data
   :seon.source/branch (:branch data)
   :seon.source/expected-commit-id (:expected-current-commit data)
   :seon.source/commit-id (:current-commit data)})

(defn- stale-publication-error
  "Translate Datahike's stale-head refusal once; unrelated failures propagate."
  {:malli/schema [:=> [:cat :qualified-symbol :seon.error/throwable] :seon.source/publication-error]}
  [operation failure]
  (if-let [data (some (fn [cause]
                        (let [data (ex-data cause)]
                          (when (= :stale-branch-head (:type data)) data)))
                      (take-while some? (iterate ex-cause failure)))]
    (publication-error operation data)
    (throw failure)))

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
    (let [recorded
          (try
      (let [connection (store/open-branch! held-store scratch)]
        (try
          (let [projection (schema/projection-from-database @connection)
                required (when (:seon.test.run/published-base-digest (:seon.test.run/provenance completion))
                           [:seon.test.run/published-base-digest :seon.test.run/overlay-input-digest
                            :seon.test.run/callers-at-head :seon.source/test-selection-request])
                missing (vec (remove #(get-in projection [:seon.schema.projection/forms %]) required))
                result (if (seq missing)
                         {:seon.error/at (java.util.Date.)
                           :seon.error/layer :seon.test/recording
                           :seon.error/operation 'seon.cluster.source/record-results!
                           :seon.error/message "The published recording authority predates snapshot result admission. The orchestrator must publish the converged schema before fast recording can be enabled."
                           :seon.error/expected required
                           :seon.error/offending missing
                           :seon.error/data {:seon.source/commit-id expected}
          :seon.test.run/branch current-branch}
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
                                   {:seon.error/at (java.util.Date.)
                                     :seon.error/layer :seon.test/admission
                                     :seon.error/operation 'seon.cluster.source/record-results!
                                     :seon.error/message "Matching snapshot work is already admitted and has no recorded terminal result."
                                     :seon.error/expected expected
                                     :seon.error/offending reserved
                                     :seon.error/data {:seon.test.run/provenance (:seon.test.run/provenance admission)}})))))))]
            (if (:seon.error/at result)
              (assoc result :seon.source/refused-test-run
                     (get-in completion [:seon.test.run/provenance :seon.test.run/id]))
              (do (d/force-branch! @connection current-branch #{expected}
                                   {:expected-current-commit expected})
                  result)))
          (finally (store/release-branch! connection))))
      (catch Throwable failure
        (retire-scratch! held-store scratch failure)
        (throw failure)))]
      (retire-scratch! held-store scratch nil)
      recorded)))

(defn record-results!
  "Admit and publish test completions against the acquired source head.
  Datahike refuses a concurrent head change at publication."
  {:malli/schema
   [:=> [:cat :seon.store/store :seon.source/test-recording-request]
    :seon.source/test-recording-result]}
  [held-store completion]
  (try
    (record-results-at-head! held-store completion)
    (catch Exception failure
      (stale-publication-error 'seon.cluster.source/record-results! failure))))

(defn- index-issues!
  [connection source-digest directory paths]
  (when (and (or (nil? paths) (seq paths))
             (get (:schema (db/db connection)) :seon.issue/id))
    (require-committed!
     ((requiring-resolve 'seon.issue/index!)
      (cond-> {:seon.db/connection connection
               :seon.issue/notes ((requiring-resolve 'seon.issue/notes) directory)}
        paths (assoc :seon.source/changed-paths paths)))
     :seon.issue/index-refused "Issue indexing was refused."
     {:seon.source/digest source-digest})))

(defn- publication-input-digest!
  "Require the snapshot inventory before beginning a publication transaction."
  {:malli/schema [:=> [:cat :string] :seon.source/digest]}
  [directory]
  (try
    (test.cache/test-input-digest directory (test.cache/input-digests directory))
    (catch Exception failure
      (let [refusal {:seon.error/at (java.util.Date.)
                      :seon.error/layer :seon.source/publication
                      :seon.error/operation 'seon.cluster.source/publish!
                      :seon.error/message "The publication input inventory is unavailable."
                      :seon.error/expected :snapshot-input-inventory
                      :seon.error/offending directory
                      :seon.error/data {:seon.source/inventory-failure (str (ex-message failure))}}]
        (throw (ex-info (:seon.error/message refusal) refusal failure))))))

(defn publish!
  "Reconcile and atomically publish on the current source database history."
  {:malli/schema [:=> [:cat :seon.source/publish-request]
                  :seon.source/publish-result]}
  [{:keys [:seon.store/store :seon.db/process]
    directory :seon.fn/root
    source-digest :seon.source/digest
    test-input-digest :seon.source/test-input-digest
    input-digests :seon.source/relative-file-digests
    changed-paths :seon.source/changed-paths
    requested-commit :seon.source/expected-commit-id
    populate :seon.source/populate
    populate-request :seon.source/populate-request
    progress! :seon.source/progress!
    :or {progress! (constantly nil)}}]
  (let [published (current store)
        moved? (and requested-commit published
                    (not= requested-commit (:seon.source/commit-id published)))
        note-paths (when (and published changed-paths)
                     (filterv (requiring-resolve 'seon.issue/note-path?) changed-paths))
        committed (when published
                    (d/commit-as-db (:seon.store/connection-object store)
                                    (:seon.source/commit-id published)))
        unchanged? (when committed
                     (try
                       (if input-digests
                         (= (select-keys input-digests changed-paths)
                            (stored-path-digests committed changed-paths))
                       (let [digest (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]]
                                          committed)]
                         (when (map? digest)
                           (refuse! ::publish-readback-failed
                                    "The published source digest could not be read." digest))
                         (= source-digest digest)))
                       (finally (d/release-materialized-db committed))))]
    (cond
      ;; The request was derived from `requested-commit`; another publisher
      ;; moved the head since. Refuse before scratch work, with Datahike's
      ;; own refusal shape; `force-branch!` remains the fence inside the permit.
      moved?
      (publication-error 'seon.cluster.source/publish!
                         {:branch current-branch
                          :expected-current-commit requested-commit
                          :current-commit (:seon.source/commit-id published)})
      (and unchanged? (not (seq note-paths)))
      (assoc published :seon.source/digest source-digest :seon.source/built? false)
      :else
      (let [projection (schema/declaration-projection)
        input-digest (or test-input-digest
                         (publication-input-digest! (or directory (fs/source-directory))))
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
            (let [population-result (when-not unchanged? (populate-fn
             (cond-> (merge populate-request
                            {:seon.db/connection connection
                             :seon.source/digest source-digest})
               expected-commit
               (assoc :seon.source/previous-database
                      previous-database))))
                  _ (when (or (nil? note-paths) (seq note-paths))
                      (progress! "publication issue indexing"))
                  issues (index-issues! connection source-digest (or directory (fs/source-directory)) note-paths)
                  _ (progress! "publication source identity")]
            (require-committed! population-result ::source-population-refused
                                "The source population was refused." {})
            (if (and unchanged? (nil? (:seon.db/transaction-report issues)))
              {:seon.source/built? false}
              (do
            ;; The source seal is the genesis boundary. Population must first
            ;; install canonical schema/program rows and boot/config process
            ;; facts; the digest and build instant are the final complete fact.
            (when-not unchanged? (require-committed!
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
             {:seon.source/digest source-digest}))
            (progress! "publication branch head")
            (let [installed
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
                            :seon.source/commit-id scratch-commit}))
                scratch-commit))]
            (cond-> {:seon.source/built? true
                     ;; This operation's own head, returned by the guarded
                     ;; update; never a reread of the mutable branch.
                     :seon.source/commit-id installed}
              (or (contains? population-result :seon.reconcile/adopt-identities)
                  (:seon.db/transaction-report issues))
              (assoc :seon.reconcile/adopt-identities
                     (into (or (:seon.reconcile/adopt-identities population-result) #{})
                           (when-let [report (:seon.db/transaction-report issues)]
                             (require-committed! (fn/report-identities report)
                                                 ::publish-readback-failed
                                                 "Issue report identities could not be read." {}))))
              (nil? expected-commit)
              (assoc :seon.program/unresolved-report (unresolved-report! (db/db connection)))))))))
            (finally
              (d/release connection))))]
        (do
          (registry/retire-branch! {:seon.store/store store
                                    :seon.store/branch scratch})
          (cond-> (merge outcome
                          {:seon.source/branch current-branch
                           :seon.source/digest source-digest})
            expected-commit (assoc :seon.source/expected-commit-id expected-commit))))
        (catch Throwable failure
          (retire-scratch! store scratch failure)
          (stale-publication-error 'seon.cluster.source/publish! failure)))))))

(defn- refused
  "A comparison refusal of this owner's merge operations."
  {:malli/schema [:=> [:cat :seon.program/missing-evidence :string :map] :seon.program/digest-map-refusal]}
  [missing message members]
  (assoc (program/digest-map-refusal missing message members) :seon.error/operation 'seon.cluster.source/prepare-merge!))

(defn- merge-base
  "The nearest commit both `branch` and `head` reach: alternate parent walks
  over stored commits, O(commits since the fork). More than `bound` visited
  commits or an unavailable one refuses by name."
  {:malli/schema [:=> [:cat :seon.store/store :seon.db/database-value :seon.db/database-value
                       :seon.program/max-datoms]
                  [:or :seon.db/database-value :seon.program/digest-map-refusal]]}
  [store branch head bound]
  (loop [queues (mapv #(conj clojure.lang.PersistentQueue/EMPTY (db/commit-id %)) [branch head])
         seen [#{} #{}] side 0 visited 0]
    (let [other (- 1 side) commit (peek (queues side))
          stored (when (and commit (not ((seen other) commit)) (<= visited bound))
                   (commit-database store commit))]
      (cond
        (and commit ((seen other) commit)) (commit-database store commit)
        (every? empty? queues) (refused :seon.program/history "The branches share no commit." {})
        (nil? commit) (recur queues seen other visited)
        (nil? stored) (refused :seon.program/read-bound "The merge base is unavailable within its commit bound."
                               {:seon.program/max-datoms bound :seon.error/offending commit})
        :else (recur (update (update queues side pop) side into
                             (try (get-in stored [:meta :datahike/parents])
                                  (finally (d/release-materialized-db stored))))
                     (update seen side conj commit) other (inc visited))))))

(defn- replacement
  "Exact replacement transaction data onto `onto` for `delta`'s rows in `from`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set]
                  [:or :seon.db/tx-data :seon.error/value]]}
  [from onto delta]
  (let [rows (fn/published-index-rows from (vec delta))]
    (if (vector? rows) (fn/reconcile-tx onto rows (vec delta)) rows)))

(defn prepare-merge!
  "Test `candidate`'s function and test replacements since its merge base with
  `source`'s head H on a fresh branch S of H through the save gate, with the
  issue's tests, and answer the proposal `accept-merge!` takes. A conflict
  answers the three-way value; an addition, deletion or empty delta refuses."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.store/store :seon.store/branch :seon.issue/id]
                  [:or [:map [:seon.source/candidate :seon.store/branch] [:seon.test.run/id :seon.test.run/id]
                        [:seon.test/passed? :boolean] [:seon.source/tally :string]
                        [:datahike/expected-basis-t :int] [:parents [:set :seon.source/commit-id]]]
                   :seon.program/three-way :seon.error/value]]}
  [source held-store candidate issue]
  (let [bound {:seon.program/max-datoms 10000}
        head (db/db (:seon.db/connection source))
        c-id (registry/branch-commit-id {:seon.store/store held-store :seon.store/branch candidate})
        branch (commit-database held-store c-id)
        base (merge-base held-store branch head 10000)
        ;; Only what the candidate changed can merge or conflict; H's
        ;; transaction numbers are another lineage's.
        changed (if (:seon.error/at base) base (program/changed-identities base branch bound))
        maps (when (set? changed)
               (mapv #(program/digest-map % (assoc bound :seon.program/identities changed)) [base branch head]))
        three (when (and maps (not-any? :seon.error/at maps)) (apply program/three-way maps))
        delta (:seon.program/changed-on-branch three)
        tx (when (seq delta) (replacement branch head delta))
        subject (db/pull branch [:db/id {:seon.issue/tests [:seon.test/sym]}] [:seon.issue/id issue])
        refuse #(refused :seon.program/scope %1 {:seon.error/offending %2})]
    (try
      (cond
        (not (set? changed)) changed
        (not three) (some #(when (:seon.error/at %) %) maps)
        (seq (:seon.program/conflict three)) three
        (some seq ((juxt :seon.program/added :seon.program/retracted) three))
        (refuse "Only replacements of existing functions and tests merge here." three)
        (empty? delta) (refuse "The candidate changes nothing the head lacks." three)
        (:seon.error/at tx) tx
        (not (:db/id subject)) (refuse "The issue is absent on the candidate." issue)
        :else
        (let [s (keyword (str "merge-" (id/id)))
              write! (fn [execution]
                       (let [written (db/transact! (:seon.db/connection execution) {:tx-data tx})]
                         (when (:seon.error/at written) (throw (ex-info "The merge delta was refused." written)))
                         (into delta (map #(vector :seon.test/sym (:seon.test/sym %))) (:seon.issue/tests subject))))
              gate ((requiring-resolve 'seon.cluster/candidate-gate!) source held-store s write! (constantly #{}))
              run (:seon.source/gate-run gate)]
          (if (:seon.error/at run) run
              (assoc (select-keys gate [:seon.test/passed? :seon.source/tally])
                     :seon.source/candidate s :seon.test.run/id (:seon.test.run/id run)
                     :datahike/expected-basis-t (db/basis-t head) :parents #{c-id}))))
      (finally (run! #(when-not (:seon.error/at %) (d/release-materialized-db %)) [base branch])))))

(defn accept-merge!
  "Merge exactly the program tested by `prepare-merge!`'s proposal into
  `source`: the named run on S complete, every member green, every replaced
  function reached by one, and S's program unwritten since. The writer
  refuses a moved head (expected basis-t) before any datom lands; the merge
  commit's parents add the candidate and S's head."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.store/store
                       [:map [:seon.source/candidate :seon.store/branch] [:seon.test.run/id :seon.test.run/id]
                        [:datahike/expected-basis-t :int] [:parents [:set :seon.source/commit-id]]]]
                  [:or :seon.db/transaction-report :seon.source/test-evidence-error :seon.error/value]]}
  [source held-store {s :seon.source/candidate run-id :seon.test.run/id basis :datahike/expected-basis-t
                      merged :parents}]
  (let [e-id (registry/branch-commit-id {:seon.store/store held-store :seon.store/branch s})
        tested (commit-database held-store e-id)
        head (db/db (:seon.db/connection source))
        refuse (fn [message offending]
                 {:seon.error/at (java.util.Date.) :seon.error/layer :seon.source/merge
                  :seon.error/operation 'seon.cluster.source/accept-merge! :seon.error/message message
                  :seon.error/offending offending :seon.source/refused-test-run run-id :seon.test.run/branch s})]
    (try
      (let [run (db/pull tested [:seon.test.run/branch :seon.test.run/tested-branch :seon.test.run/basis-t]
                         [:seon.test.run/id run-id])
            results ((requiring-resolve 'seon.test.runner/run-results) tested run-id)
            members (into {} (db/q '[:find ?m ?sym :in $ ?id :where [?r :seon.test.run/id ?id]
                                     (or [?r :seon.test.run/members ?m] [?r :seon.test.run/covered-by ?m])
                                     [?m :seon.test.member/symbol ?sym]] tested run-id))
            green-ids ((requiring-resolve 'seon.test/green-members) tested (vec (keys members)))
            green (set (vals (select-keys members green-ids)))
            ;; S forked from H at `basis`: comparable on S's own lineage.
            delta (program/changed-identities basis tested {:seon.program/max-datoms 10000})
            uncovered (when (set? delta)
                        (for [[a f] delta :when (= :seon.fn/sym a)
                              :when (not-any? green (fn/gate-sets {:seon.db/db tested :seon.fn/seeds #{f}}))] f))
            tx (when (seq delta) (replacement tested head delta))]
        (cond
          (not= s (or (:seon.test.run/tested-branch run) (:seon.test.run/branch run)))
          (refuse "The named run was not recorded on the tested branch." run)
          (:seon.error/at results) results
          (or (empty? members) (not-every? green-ids (keys members)))
          (refuse "Every member of the named run must be green." (vec (vals (apply dissoc members green-ids))))
          (not (false? ((requiring-resolve 'seon.test.runner/program-written-since?) tested (:seon.test.run/basis-t run))))
          (refuse "The tested program changed after the named run." (:seon.test.run/basis-t run))
          (not (set? delta)) delta
          (seq uncovered) (refuse "A replaced function reaches no green test of the named run." (vec uncovered))
          (:seon.error/at tx) tx
          (empty? tx) (refuse "The destination already holds the tested rows." (vec delta))
          :else (db/transact! (:seon.db/connection source)
                              {:tx-data tx :datahike/expected-basis-t basis :parents (conj merged e-id)})))
      (finally (d/release-materialized-db tested)))))
