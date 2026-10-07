(ns seon.cluster.source
  "Publishes the latest complete source database value on one branch.

  Each changed digest is populated on a process-owned scratch branch. The
  first publication branches that complete head onto `:current-src`; later
  publications use Datahike's expected-head guard to advance the same branch.
  Failed and stale builds leave the previously published head untouched.
  Scratch branches are retired after publication and no connection to
  `:current-src` is opened or retained."
  (:require [seon.error.refusal]
            [clojure.java.io :as io]
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
            [hasch.core :as hasch]
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
  (let [commit-id (registry/branch-commit-id
                   {:seon.store/store store
                    :seon.store/branch current-branch})]
    ;; No source branch yet is "before publication"; the registry names it.
    (when-not (:seon.cluster.registry/branch-missing commit-id)
      {:seon.source/branch current-branch
       :seon.source/commit-id commit-id})))

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
              (when (:seon.db/invalid-read identities)
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
    (when (db/read-refusal? report)
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
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.source/publication operation
   {:seon.error/message "The source head changed before publication."
   :seon.error/expected (:expected-current-commit data)
   :seon.error/offending (:current-commit data)
   :seon.error/data data
   :seon.source/branch (:branch data)
   :seon.source/expected-commit-id (:expected-current-commit data)
   :seon.source/commit-id (:current-commit data)}))

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
                         (seon.error.refusal/diagnostic (java.util.Date.) :seon.test/recording 'seon.cluster.source/record-results!
                          {:seon.error/message "The published recording authority predates snapshot result admission. The orchestrator must publish the converged schema before fast recording can be enabled."
                           :seon.error/expected required
                           :seon.error/offending missing
                           :seon.error/data {:seon.source/commit-id expected}
          :seon.test.run/branch current-branch})
                         (db/call-with-custody {:seon.db/connection connection} ; the scratch's own write
                        (fn [] (schema/call-with-projection
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
                                     :seon.error/data {:seon.test.run/provenance (:seon.test.run/provenance admission)}})))))))))]
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

(defn- index-tasks!
  "Reconcile task declarations against the previous publication's basis, then
  refuse dangling annotations before the head advances. Existing files are read
  once; a removed file whose namespace still holds tasks supplies an empty declaration."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.source/digest :string [:maybe :seon.db/database-value]]
                  [:maybe :seon.task/report]]}
  [connection source-digest directory previous]
  (when (get (:schema (db/db connection)) :seon.task/id)
    (let [task-files (requiring-resolve 'seon.task/task-files)
          stored (require-committed! (db/q '[:find [?ns ...] :where [_ :seon.task/ns ?ns]] (db/db connection))
                                     :seon.task/findings-refused "Stored task namespaces could not be read." {})
          present (task-files directory nil)
          removed (remove #(contains? present %) (map #(str "resources/seon/tasks/" % ".edn") stored))
          files (merge present (task-files directory removed))
          basis (require-committed! ((requiring-resolve 'seon.task/task-basis) (or previous (db/db connection)) files)
                                    :seon.task/findings-refused "The task basis could not be read." {})
          report (require-committed!
                  ((requiring-resolve 'seon.task/index-tasks!)
                   {:seon.db/connection connection :seon.task/files files :seon.task/basis basis})
                  :seon.task/index-refused "Task indexing was refused." {:seon.source/digest source-digest})
          findings (require-committed! ((requiring-resolve 'seon.task/task-findings) (db/db connection))
                                       :seon.task/findings-refused "Task findings could not be read." {})]
      (when-let [[handle holders] (first (:seon.task/dangling findings))]
        (fn/task-refused! 'seon.cluster.source/publish! :dangling-id (first (sort holders)) handle
                          {:seon.task/dangling (:seon.task/dangling findings) :seon.source/digest source-digest}))
      report)))

(defn- publication-input-digest!
  "Require the snapshot inventory before beginning a publication transaction."
  {:malli/schema [:=> [:cat :string] :seon.source/digest]}
  [directory]
  (try
    (test.cache/test-input-digest directory (test.cache/input-digests directory))
    (catch Exception failure
      (let [refusal (seon.error.refusal/diagnostic (java.util.Date.) :seon.source/publication 'seon.cluster.source/publish!
                     {:seon.error/message "The publication input inventory is unavailable."
                      :seon.error/expected :snapshot-input-inventory
                      :seon.error/offending directory
                      :seon.error/data {:seon.source/inventory-failure (str (ex-message failure))}})]
        (throw (ex-info (:seon.error/message refusal) refusal failure))))))

(defn publish!
  "Reconcile and atomically publish on the current source database history.

  `:seon.source/branch` names the branch the publication installs; absent, it
  is `current-src`. Any other branch is created from the scratch commit, so a
  rehearsal (`seon.cluster/rehearse-source!`) runs this whole path and leaves
  `current-src` where it was."
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
    ;; The writer of publication's own scratch writes (schema and seal), an
    ;; explicit value so a caller supplies another without redefining a Var.
    transact! :seon.db/writer
    target :seon.source/branch
    :or {progress! (constantly nil)
         transact! db/transact!
         target current-branch}}]
  (let [published (current store)
        moved? (and requested-commit published
                    (not= requested-commit (:seon.source/commit-id published)))
        committed (when published
                    (d/commit-as-db (:seon.store/connection-object store)
                                    (:seon.source/commit-id published)))
        unchanged? (when committed
                     (try
                       (cond
                         input-digests
                         (= (select-keys input-digests changed-paths)
                            (stored-path-digests committed changed-paths))
                         :else
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
      unchanged?
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
            (db/call-with-custody {:seon.db/connection connection} ; the scratch's own work
            (fn [] (let [previous-database
                  (when expected-commit
                    (or (:seon.source/previous-database populate-request)
                        (d/db connection)))]
            ;; Reconcile on the published history. The scratch isolates refused
            ;; work; no test evidence is copied into a new history.
            (let [missing (filterv
                            (fn [entity]
                              (let [definition (dissoc entity :db/ident)]
                                (not= definition
                                      (select-keys (get (:schema @connection) (:db/ident entity))
                                                   (keys definition)))))
                            (schema.datahike/malli->datahike-schema-in projection source-attributes))]
              (when (seq missing)
                (require-committed! (transact! connection {:tx-data missing})
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
                  tasks (index-tasks! connection source-digest (or directory (fs/source-directory)) previous-database)
                  _ (progress! "publication source identity")]
            (require-committed! population-result ::source-population-refused
                                "The source population was refused." {})
            (if (and unchanged? (not (:seon.task/changed? tasks)))
              {:seon.source/built? false}
              (do
            ;; The source seal is the genesis boundary. Population must first
            ;; install canonical schema/program entities and boot/config process
            ;; facts; the digest and build instant are the final complete fact.
            (when-not unchanged? (require-committed!
             (transact!
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
            (if (and expected-commit (= target current-branch))
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
                                       :seon.store/branch target})]
                (when-not created?
                  (refuse! ::stale-publication
                           (str "another publisher created " (name target) " first")
                           {:seon.source/branch target
                            :seon.source/commit-id scratch-commit}))
                scratch-commit))]
            (cond-> {:seon.source/built? true
                     ;; This operation's own head, returned by the guarded
                     ;; update; never a reread of the mutable branch.
                     :seon.source/commit-id installed}
              (contains? population-result :seon.reconcile/adopt-identities)
              (assoc :seon.reconcile/adopt-identities
                     (:seon.reconcile/adopt-identities population-result))
              (nil? expected-commit)
              (assoc :seon.program/unresolved-report (unresolved-report! (db/db connection)))))))))))
            (finally
              (d/release connection))))]
        (do
          (registry/retire-branch! {:seon.store/store store
                                    :seon.store/branch scratch})
          (cond-> (merge outcome
                          {:seon.source/branch target
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
  "Exact replacement transaction data onto `onto` for `delta`'s entities in `from`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set]
                  [:or :seon.db/tx-data :seon.error/value]]}
  [from onto delta]
  (let [entities (fn/published-index-entities from (vec delta))]
    (if (vector? entities) (fn/reconcile-tx onto entities (vec delta)) entities)))

(defn- unselected-reach
  "The selected declarations that reach a change of `delta` outside `selection`
  on `branch`, through the reverse closure test selection walks
  (`seon.fn/reverse-closure`) seeded with those unselected changes."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set :seon.program/identity-set]
                  [:or :seon.program/identity-set :seon.db/invalid-read-error]]}
  [branch delta selection]
  (let [seeds (into #{} (keep (fn [[attribute value :as identity]]
                                (when (and (#{:seon.fn/sym :seon.test/sym} attribute) (not (selection identity)))
                                  value)))
                    delta)
        closure (if (seq seeds) (fn/reverse-closure {:seon.db/db branch :seon.fn/seeds seeds}) {})]
    (if (:seon.db/invalid-read closure)
      closure
      (let [reached (into (set (:seon.fn/affected closure)) (:seon.fn/tests closure))]
        (into #{} (filter (fn [[_ value]] (reached value))) selection)))))

(defn- held-digests
  "The definition digest `database` holds for each of `identities`,
  `:seon.program/absent` where it declares none: what a merge or write-back
  compares, so writes to other rows never stale it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set] :seon.source/expected-digests]}
  [database identities]
  (into {} (map (fn [[attribute value :as identity]]
                  [identity (or (db/q '[:find ?digest . :in $ ?a ?v
                                        :where [?e ?a ?v] [?e :seon.program/definition-digest ?digest]]
                                      database attribute value)
                                :seon.program/absent)]))
        identities))

(defn- moved-digests
  "The members of `expected` whose digest `held` disagrees with."
  {:malli/schema [:=> [:cat :seon.source/expected-digests :seon.source/expected-digests] [:vector :seon.program/declaration-identity]]}
  [expected held]
  (vec (sort-by str (filter #(not= (expected %) (held %)) (keys expected)))))

(defn- declarations-held-tx
  "Transaction data that refuses, inside the writer, unless every declaration
  of `expected` still holds its expected digest: the merge's basis is the rows
  it replaces, never the head's t. The refusal names each moved row with both
  digests."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.source/expected-digests] [:= []]]}
  [database expected]
  (let [held (held-digests database (set (keys expected)))
        moved (moved-digests expected held)]
    (when (seq moved)
      (throw (ex-info "A merged declaration changed on the destination after the merge was prepared."
                      (seon.error.refusal/diagnostic
                       (java.util.Date.) :seon.source/merge 'seon.cluster.source/accept-merge!
                       {:seon.error/message "Merged declarations changed on the destination after the merge was prepared; prepare it again."
                        :seon.error/member :seon.source/expected-digests
                        :seon.error/expected (select-keys expected moved)
                        :seon.error/offending (select-keys held moved)}))))
    []))

;; Write-back's proof, below with its helpers; prepare runs it before the gate.
(declare proven-files)

(defn release!
  "Release materialized database values (the result of `compare-branch`, `commit-database`); refusals and nils are skipped."
  {:malli/schema [:=> [:cat [:sequential [:maybe [:or :seon.db/database-value :seon.program/digest-map-refusal :seon.error/value]]]] :nil]}
  [values]
  (run! #(when (and % (not (:seon.error/at %))) (d/release-materialized-db %)) values)
  nil)

(defn compare-branch
  "Compare `candidate`'s committed program with `head` (a database value) on
  their merge base: the candidate's changed identities, the base, candidate and
  head digest maps and their `seon.program/three-way` classification. The one
  comparison merge and `my.program/diff` share. The base and branch values are
  materialized: the caller releases them. Refusals sit in `:c-id`, `:changed`
  or `:maps`."
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch :seon.db/database-value] :map]}
  [held-store candidate head]
  (let [c-id (registry/branch-commit-id {:seon.store/store held-store :seon.store/branch candidate})]
    (if (:seon.error/at c-id)
      {:c-id c-id}
      (let [bound {:seon.program/max-datoms 10000}
            branch (commit-database held-store c-id)
            base (merge-base held-store branch head 10000)
            ;; Only what the candidate changed can merge or conflict; H's
            ;; transaction numbers are another lineage's.
            changed (if (:seon.error/at base) base (program/changed-identities base branch bound))
            maps (when (set? changed)
                   (mapv #(program/digest-map % (assoc bound :seon.program/identities changed)) [base branch head]))]
        {:c-id c-id :base base :branch branch :head head :changed changed :maps maps
         :three (when (and maps (not-any? :seon.error/at maps)) (apply program/three-way maps))}))))

(defn prepare-merge!
  "Test `candidate`'s function and test changes since its merge base with
  `source`'s head H on a fresh branch S of H through the save gate, with the
  `proofs` (the test symbols that prove the change: a merge task's proofs),
  and answer the proposal `accept-merge!` takes: the whole run's verdict and
  tally, and its red members by name, shown and not gated; only the proofs
  gate acceptance (AGENTS.md, merge). A change is a
  replacement, an addition (absent at the base) or a retraction (absent on the
  candidate), as `seon.program/three-way` classifies it; one reconcile
  transaction applies all three. A conflict answers the three-way value; an
  empty delta refuses.

  A `selection` of declaration identities merges only those changes: a
  selected identity the candidate did not change, or a selected declaration
  reaching an unselected change (a new helper it calls), refuses by name.
  A selected declaration carries its namespace's row (requires, aliases) when
  the candidate changed it. Without one the whole delta merges, namespace rows
  included.

  With the checkout `root`, the files write-back would produce are proven first
  (`proven-files` over the merged value, `seon.db/with`): a declaration naming
  a namespace its file does not require refuses before any test runs."
  {:malli/schema [:function
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.store/branch :seon.task/proofs]
                   [:or :seon.source/proposal :seon.program/three-way :seon.error/value]]
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.store/branch :seon.task/proofs
                        [:maybe :seon.program/identity-set]]
                   [:or :seon.source/proposal :seon.program/three-way :seon.error/value]]
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.store/branch :seon.task/proofs
                        [:maybe :seon.program/identity-set] [:maybe :seon.fn/root]]
                   [:or :seon.source/proposal :seon.program/three-way :seon.source/write-back-error :seon.error/value]]]}
  ([source held-store candidate proofs] (prepare-merge! source held-store candidate proofs nil nil))
  ([source held-store candidate proofs selection] (prepare-merge! source held-store candidate proofs selection nil))
  ([source held-store candidate proofs selection root]
  (let [{:keys [c-id base branch head changed maps three]}
        (compare-branch held-store candidate (db/db (:seon.db/connection source)))]
    (if (:seon.error/at c-id)
      c-id
      (let [
            whole (when three (into (:seon.program/changed-on-branch three)
                                    (concat (:seon.program/added three) (:seon.program/retracted three))))
            unchanged (when (and whole selection) (vec (remove whole selection)))
            reach (when (and whole selection (empty? unchanged)) (unselected-reach branch whole selection))
            ;; A selected declaration carries its namespace entity when the candidate changed it (its requires).
            delta (if (and whole selection)
                    (into selection (filter whole) (for [[_ v] selection :when (qualified-symbol? v)] [:seon.ns/name (symbol (namespace v))]))
                    whole)
            tx (when (and (seq delta) (empty? unchanged) (empty? reach))
                 (replacement branch head delta))
            ;; The file the merge would write back, proven before any test runs.
            proof (when (and root (vector? tx))
                    (let [merged (db/with head {:tx-data tx})]
                      (if (:seon.error/at merged) merged (proven-files head merged delta root))))
            refuse #(refused :seon.program/scope %1 {:seon.error/offending %2})]
        (try
          (cond
            (not (set? changed)) changed
            (not three) (some #(when (:seon.error/at %) %) maps)
            (seq (:seon.program/conflict three)) three
            (seq unchanged) (refuse "A selected declaration is not a change on the candidate." unchanged)
            (:seon.db/invalid-read reach) reach
            (seq reach) (refuse "A selected declaration reaches an unselected change; select that change too."
                                {:seon.program/identities reach :seon.program/unselected (set (remove selection whole))})
            (empty? delta) (refuse "The candidate changes nothing the head lacks." three)
            (:seon.error/at tx) tx
            (:seon.error/at proof) proof
            (empty? proofs) (refuse "A merge names the tests that prove it; none were given." proofs)
            :else
            (let [s (keyword (str "merge-" (id/id)))
                  write! (fn [execution]
                           (let [written (db/transact! (:seon.db/connection execution) {:tx-data tx})
                                 ;; The writer re-derives a merged namespace's declarations: their tests run too.
                                 touched (when-not (:seon.error/at written) (fn/report-identities written))]
                             (run! #(when (:seon.error/at %) (throw (ex-info "The merge delta was refused." %))) [written touched])
                             (into (into delta touched) (map #(vector :seon.test/sym %)) proofs)))
                  gate ((requiring-resolve 'seon.cluster/candidate-gate!) source held-store s write!)
                  run (:seon.source/gate-run gate)
                  ;; Missing coverage refuses by name: every proof is a member.
                  answered (set (map :seon.test/sym (:seon.test/results run)))
                  unselected (vec (sort (remove answered proofs)))
                  red (vec (sort (keep #(when-not (and (pos? (:seon.test/pass-count %)) (zero? (:seon.test/fail-count %))
                                                       (zero? (:seon.test/error-count %)))
                                          (:seon.test/sym %))
                                       (:seon.test/results run))))]
              (cond
                (:seon.error/at run) run
                (seq unselected) (refuse "The gate answered none of these proofs." unselected)
                :else (cond-> (assoc (select-keys gate [:seon.test/passed? :seon.source/tally])
                                     :seon.source/candidate s :seon.test.run/id (:seon.test.run/id run)
                                     :seon.task/proofs proofs
                                     :seon.source/expected-digests (held-digests head delta) :parents #{c-id})
                        (seq red) (assoc :seon.source/red red)))))
          (finally (release! [base branch]))))))))

(defn accept-merge!
  "Merge exactly the program tested by `prepare-merge!`'s proposal into
  `source`: the named run on S complete, every one of its `:seon.task/proofs`
  green (the other reaching members' verdicts are shown by prepare, not
  gated), every added or replaced function reached by a green member, and S's
  program unwritten since. The
  writer refuses, before any datom lands, a merged declaration whose digest on
  the head moved from the proposal's `:seon.source/expected-digests`; writes to
  other rows never stale it. The merge commit's parents add the candidate and
  S's head. A task handle the merged program names with no task admitted on the
  head refuses, by handle and holder. Answers the accepted merge `write-back!`
  takes: its identities, the commit before it, its merge commit and the run.

  Example:
  (accept-merge! source store proposal)"
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.store/store
                       [:map [:seon.source/candidate :seon.store/branch] [:seon.test.run/id :seon.test.run/id]
                        [:seon.task/proofs :seon.task/proofs]
                        [:seon.source/expected-digests :seon.source/expected-digests]
                        [:parents [:set :seon.source/commit-id]]]]
                  [:or :seon.source/accepted :seon.source/test-evidence-error :seon.error/value]]}
  [source held-store {s :seon.source/candidate run-id :seon.test.run/id expected :seon.source/expected-digests
                      proofs :seon.task/proofs merged :parents}]
  (let [e-id (registry/branch-commit-id {:seon.store/store held-store :seon.store/branch s})]
    (if (:seon.error/at e-id)
      e-id
      (let [tested (commit-database held-store e-id)
            head (db/db (:seon.db/connection source))
            delta (set (keys expected))
            refuse (fn [message offending]
                     (seon.error.refusal/diagnostic (java.util.Date.) :seon.source/merge 'seon.cluster.source/accept-merge!
                      {:seon.error/message message
                      :seon.error/offending offending :seon.source/refused-test-run run-id :seon.test.run/branch s}))]
        (try
          (let [run (db/pull tested [:seon.test.run/branch :seon.test.run/tested-branch :seon.test.run/basis-t]
                             [:seon.test.run/id run-id])
                results ((requiring-resolve 'seon.test.runner/run-results) tested run-id)
                members (into {} (db/q '[:find ?m ?sym :in $ ?id :where [?r :seon.test.run/id ?id]
                                         (or [?r :seon.test.run/members ?m] [?r :seon.test.run/covered-by ?m])
                                         [?m :seon.test.member/symbol ?sym]] tested run-id))
                green-ids ((requiring-resolve 'seon.test/green-members) tested (vec (keys members)))
                green (set (vals (select-keys members green-ids)))
                unproven (vec (sort (remove green proofs)))
                ;; A retracted function has nothing to cover: its surviving
                ;; callers are the writer's refs and the run's reaching tests.
                ;; What an agent merges back is specified (owner 2026-09-27): the
                ;; REPL admits an uncontracted sketch with a warning; merge is
                ;; where the contract becomes required. Macros and host-bound
                ;; declarations carry no arity contract (sci/eval.clj:1005-1011).
                uncontracted (for [[a f] delta :when (= :seon.fn/sym a)
                                   :let [entity (db/pull tested [:db/id :seon.fn/spec :seon.fn/macro?
                                                                 :seon.fn/host-bound?] [a f])]
                                   :when (and (:db/id entity) (not (:seon.fn/spec entity))
                                              (not (:seon.fn/macro? entity))
                                              (not (:seon.fn/host-bound? entity)))] f)
                uncovered (for [[a f] delta :when (= :seon.fn/sym a)
                                :when (:db/id (db/pull tested [:db/id] [a f]))
                                :when (not-any? green (fn/gate-sets {:seon.db/db tested :seon.fn/seeds #{f}}))] f)
                tx (when (seq delta) (replacement tested head delta))
                dangling ((requiring-resolve 'seon.task/candidate-dangling) tested head delta)]
            (cond
              (not= s (or (:seon.test.run/tested-branch run) (:seon.test.run/branch run)))
              (refuse "The named run was not recorded on the tested branch." run)
              (:seon.error/at results) results
              (or (empty? proofs) (seq unproven))
              (refuse "Every proof of the named run must be green." unproven)
              (not (false? ((requiring-resolve 'seon.test.runner/program-written-since?) tested (:seon.test.run/basis-t run))))
              (refuse "The tested program changed after the named run." (:seon.test.run/basis-t run))
              (seq uncontracted)
              (refuse (str "Every function a merge carries declares a complete :malli/schema; these do not: "
                           (vec uncontracted) ". Declare their data shapes as registered schemas, "
                           "contract them on the candidate branch, run their tests and propose again.")
                      (vec uncontracted))
              (seq uncovered) (refuse "A replaced function reaches no green test of the named run." (vec uncovered))
              (:seon.error/at tx) tx
              (empty? tx) (refuse "The destination already holds the tested entities." (vec delta))
              (:seon.error/at dangling) dangling
              (seq dangling) (refuse "The merged program names tasks the destination has not admitted." dangling)
              :else
              (let [report (db/transact! (:seon.db/connection source)
                                         {:tx-data (into [[:db.fn/call declarations-held-tx expected]] tx)
                                          :parents (conj merged e-id)})]
                (if (:db-after report)
                  {:seon.program/identities delta :seon.test.run/id run-id
                   ;; The head this commit merged into is its parent other than
                   ;; the merged ones: Datahike's own lineage. A report's
                   ;; `:db-before` is the writer's uncommitted value and can name
                   ;; an older commit (datahike writer.cljc:210).
                   :seon.source/base-commit-id
                   (first (remove (conj merged e-id) (d/parent-commit-ids (:db-after report))))
                   :seon.source/commit-id (db/commit-id (:db-after report))}
                  report))))
          (finally (d/release-materialized-db tested)))))))

;;; ---------------------------------------------------------------------------
;;; Write-back: an accepted merge's replacements, back into their one file
;;; ---------------------------------------------------------------------------

(defn- write-back-refusal
  "`write-back!`'s declared refusal: its reason, message and evidence."
  {:malli/schema [:=> [:cat :seon.source/write-back-refused :string :map] :seon.source/write-back-error]}
  [reason message members]
  (seon.error.refusal/diagnostic (java.util.Date.) :seon.source/write-back 'seon.cluster.source/write-back!
   (merge {:seon.error/message message :seon.source/write-back-refused reason} members)))

(defn- sha-256
  "The file digest publication records (`:seon.fn.file/digest`)."
  {:malli/schema [:=> [:cat :seon.source/file-bytes] :string]}
  [file]
  (hasch/hash->str (hasch/sha256 file)))

(defn- spliced
  "`file` with each `[span source]` replacement's UTF-8 bytes in its byte span."
  {:malli/schema [:=> [:cat :seon.source/file-bytes [:vector [:tuple [:tuple :int :int] :string]]]
                  :seon.source/file-bytes]}
  [file replacements]
  (let [out (java.io.ByteArrayOutputStream.)
        end (reduce (fn [from [[start stop] source]]
                      (.write out ^bytes file (int from) (int (- start from)))
                      (.writeBytes out (.getBytes ^String source "UTF-8"))
                      stop)
                    0 (sort-by ffirst replacements))]
    (.write out ^bytes file (int end) (int (- (alength ^bytes file) end)))
    (.toByteArray out)))

(defn- declaration-digests
  "Definition digest by identity of each declaration in `entities`."
  {:malli/schema [:=> [:cat [:sequential [:maybe :map]]] [:map-of :seon.program/identity :string]]}
  [entities]
  (into {} (keep #(when-let [definition (:seon.program/definition-digest %)] [(program/row-identity %) definition])) entities))

(defn- file-digests
  "The declarations `database` records in the file at `path`, by identity."
  {:malli/schema [:=> [:cat :seon.db/database-value :string] [:map-of :seon.program/identity :string]]}
  [database path]
  (declaration-digests
   (db/q '[:find [(pull ?e [:seon.fn/sym :seon.test/sym :seon.ns/name :seon.program/definition-digest]) ...]
           :in $ ?path :where [?file :seon.fn.file/relative-path ?path] [?e :seon.fn/file ?file]]
         database path)))

(defn- install!
  "Install proven `candidate` bytes at `file`: a sibling write, then an atomic rename."
  {:malli/schema [:=> [:cat :string :seon.source/file-bytes] :nil]}
  [path candidate]
  (let [file (io/file path)
        staged (java.io.File/createTempFile ".write-back-" ".clj" (.getParentFile file))]
    (try
      (Files/write (.toPath staged) ^bytes candidate (make-array java.nio.file.OpenOption 0))
      (Files/move (.toPath staged) (.toPath file)
                  (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                        java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
      nil
      (finally (Files/deleteIfExists (.toPath staged))))))

(defn- namespace-path
  "The one file `database` records declaring namespace `namespace-name`, or nil."
  {:malli/schema [:=> [:cat :seon.db/database-value :symbol] [:maybe :map]]}
  [database namespace-name]
  (let [files (db/q '[:find [(pull ?file [:seon.fn.file/relative-path :seon.fn.file/relative-root
                                          :seon.fn.file/digest]) ...]
                      :in $ ?name :where [?ns :seon.ns/name ?name] [?ns :seon.fn/file ?file]]
                    database namespace-name)]
    (when (= 1 (count files)) (first files))))

(defn- proven-file
  "The candidate bytes that write `identities` as `after` holds them over
  their one file under `root` as `before` recorded it, proven by the ordinary
  analyzer to declare exactly `after`'s rows of that file; else the refusal."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set :seon.fn/root]
                  [:or :seon.source/proof :seon.source/write-back-error]]}
  [before after identities root]
  (let [identities (vec (sort-by str identities))
        pull (fn [database identity]
               (let [entity (db/pull database [:db/id :seon.fn/sym :seon.test/sym :seon.ns/name :seon.fn/form-span :seon.fn/source
                                            :seon.test/source :seon.ns/source :seon.program/definition-digest
                                            {:seon.fn/file [:seon.fn.file/relative-path :seon.fn.file/relative-root
                                                            :seon.fn.file/digest]}]
                                  identity)]
                 (when (:db/id entity) entity)))
        changes (for [identity identities] [identity (pull before identity) (pull after identity)])
        source-of #(or (:seon.fn/source %) (:seon.test/source %) (:seon.ns/source %))
        file-of (fn [[[_ declared] was _]]
                  (cond was (:seon.fn/file was)
                        (qualified-symbol? declared) (namespace-path before (symbol (namespace declared)))))
        files (into #{} (map file-of) changes)
        {path :seon.fn.file/relative-path base-digest :seon.fn.file/digest
         relative-root :seon.fn.file/relative-root} (first files)
        file (io/file root (str path))
        base-bytes (when (and (= 1 (count files)) path (.isFile file)) (Files/readAllBytes (.toPath file)))
        before-entities (keep second changes)
        base-text (some-> ^bytes base-bytes (String. "UTF-8"))
        ;; A namespace entity records its exact `ns` form, not a span: that form's one occurrence in the file.
        span-of (fn [{span :seon.fn/form-span source :seon.ns/source}]
                  (or span (when-let [at (and source base-text (str/index-of base-text source))]
                             (when (= at (str/last-index-of base-text source))
                               (let [start (alength (.getBytes (subs base-text 0 at) "UTF-8"))]
                                 [start (+ start (alength (.getBytes ^String source "UTF-8")))])))))
        span-text (fn [[start stop]] (String. (java.util.Arrays/copyOfRange ^bytes base-bytes (int start) (int stop)) "UTF-8"))
        ;; A retraction takes the blank lines that separated it from the form before.
        from-blank (fn [start] (loop [begin start n 0]
                                 (if (and (< n 2) (pos? begin) (= 10 (aget ^bytes base-bytes (dec begin))))
                                   (recur (dec begin) (inc n)) begin)))
        edits (fn [] (vec (for [[_ was now] changes]
                            (cond
                              (and was now) [(span-of was) (source-of now)]
                              was (let [[start stop] (span-of was)] [[(from-blank start) stop] ""])
                              :else (let [end (alength ^bytes base-bytes)]
                                      [[end end] (str (when-not (= 10 (aget ^bytes base-bytes (dec end))) "\n")
                                                      "\n" (source-of now) "\n")])))))
        refuse (fn [reason message evidence]
                 (write-back-refusal reason message (cond-> evidence path (assoc :seon.source/path path))))]
    (cond
      (empty? identities) (refuse :no-replacement "The accepted merge changed no declaration." {})
      (or (not= 1 (count files)) (nil? path) (some (fn [[_ _ now]] (and now (nil? (source-of now)))) changes))
      (refuse :one-file "Each change needs its source and one file."
              {:seon.error/offending identities})
      (nil? base-bytes) (refuse :file-absent "The changed declarations' file is absent under the root." {})
      (not= base-digest (sha-256 base-bytes))
      (refuse :file-changed "The file changed after its declarations were analyzed." {:seon.error/offending base-digest})
      (let [spans (map span-of before-entities)]
        (or (some nil? spans) (not= (mapv span-text spans) (mapv source-of before-entities))))
      (refuse :span-mismatch "A before-state has no span, or its span no longer holds its declaration's source." {})
      :else
      (let [candidate (spliced base-bytes (edits))
            ;; The analyzer refuses blocking findings by throwing (`seon.fn/assert-clean-analysis!`):
            ;; a declaration valid at the REPL may name a namespace its file does not require.
            analyzed (try (fn/analyze-entities {:seon.fn/root (str root) :seon.fn/roots [relative-root]
                                            :seon.fn/changed-paths #{path}
                                            :seon.fn.analyzer/sources {(.getCanonicalPath file)
                                                                       (String. ^bytes candidate "UTF-8")}})
                          (catch clojure.lang.ExceptionInfo failure
                            (if (:seon.fn/index-refused (ex-data failure)) (ex-data failure) (throw failure))))
            reindexed (when (sequential? analyzed) (declaration-digests analyzed))
            ;; Every declaration the file holds after the write, as `after` holds it.
            expected (into {} (remove (comp #{:seon.program/absent} val))
                           (held-digests after (into (set (keys (file-digests before path))) identities)))
            differing (vec (sort-by str (filter #(not= (get expected %) (get reindexed %))
                                                (into (set (keys expected)) (keys reindexed)))))]
        (cond
          (not reindexed)
          (refuse :not-analyzable "The candidate file does not analyze cleanly; its namespace form may lack a require the accepted declarations use."
                  {:seon.error/offending (:seon.fn/findings analyzed)})
          (seq differing)
          (refuse :not-equivalent "The reindexed candidate does not declare exactly the accepted program."
                  {:seon.error/offending differing :seon.error/expected (select-keys expected differing)})
          :else {:seon.source/path path :seon.source/base-digest base-digest
                 :seon.source/candidate-bytes candidate :seon.source/proof-rows (vec changes)})))))

(defn- change-path
  "The relative path of the one file `identity`'s change is written into: the
  declaration's own file as `before` recorded it, else (an addition) its
  namespace's file; nil when neither is recorded."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/declaration-identity] [:maybe :seon.source/path]]}
  [before [_ declared :as identity]]
  (or (get-in (db/pull before [{:seon.fn/file [:seon.fn.file/relative-path]}] identity)
              [:seon.fn/file :seon.fn.file/relative-path])
      (when (qualified-symbol? declared)
        (:seon.fn.file/relative-path (namespace-path before (symbol (namespace declared)))))))

(defn- proven-files
  "`proven-file` for each file the changes of `identities` are written into,
  by path: a function and the test proving it are two files of one merge.
  The first refusal answers; a change naming no file refuses `:one-file`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set :seon.fn/root]
                  [:or [:vector :seon.source/proof] :seon.source/write-back-error]]}
  [before after identities root]
  (let [groups (group-by #(change-path before %) identities)]
    (cond
      (empty? identities) (write-back-refusal :no-replacement "The accepted merge changed no declaration." {})
      (contains? groups nil)
      (write-back-refusal :one-file "Each change needs its source and one file."
                          {:seon.error/offending (vec (sort-by str (get groups nil)))})
      :else (reduce (fn [proofs [_ group]]
                      (let [proof (proven-file before after (set group) root)]
                        (if (:seon.error/at proof) (reduced proof) (conj proofs proof))))
                    [] (sort-by key groups)))))

(defn- write-back-commits
  "`write-back!` between the accepted merge's `before` and `after` commits."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.source/accepted :seon.db/database-value
                       :seon.db/database-value :seon.fn/root]
                  [:or :seon.source/written :seon.source/write-back-error]]}
  [connection accepted before after root]
  (let [identities (:seon.program/identities accepted)
        merged (held-digests after identities)
        moved (moved-digests merged (held-digests (db/db connection) identities))
        proven (when (empty? moved) (proven-files before after identities root))
        disk-digest #(sha-256 (Files/readAllBytes (.toPath (io/file root (str (:seon.source/path %))))))
        changed (when (vector? proven) (first (remove #(= (:seon.source/base-digest %) (disk-digest %)) proven)))
        kind (fn [entities pick] (vec (keep (fn [[identity was now]] (when (pick was now) identity)) entities)))]
    (cond
      (seq moved)
      (write-back-refusal :row-changed "Declarations changed on the branch after the merge was accepted; merge that change before writing back."
                          {:seon.error/member :seon.source/accepted
                           :seon.error/expected (select-keys merged moved)
                           :seon.error/offending (select-keys (held-digests (db/db connection) identities) moved)})
      (:seon.error/at proven) proven
      changed
      (write-back-refusal :file-changed "The file changed while the candidate was proven."
                          {:seon.source/path (:seon.source/path changed) :seon.error/offending (:seon.source/base-digest changed)})
      :else
      (reduce
       (fn [written {path :seon.source/path base-digest :seon.source/base-digest candidate :seon.source/candidate-bytes
                     entities :seon.source/proof-rows}]
         (try
           (install! (.getPath (io/file root (str path))) candidate)
           (let [added (kind entities #(and (not %1) %2))
                 retracted (kind entities #(and %1 (not %2)))]
             (-> written
                 (update :seon.source/changed-paths conj path)
                 (update :seon.source/files conj
                         (cond-> {:seon.source/path path :seon.source/base-digest base-digest
                                  :seon.source/candidate-digest (sha-256 candidate)
                                  :seon.source/replaced (kind entities #(and %1 %2))}
                           (seq added) (assoc :seon.source/added added)
                           (seq retracted) (assoc :seon.source/retracted retracted)))))
           (catch Exception failure
             ;; Files installed before this one stay installed and are named.
             (reduced (write-back-refusal :install-failed "The proven candidate could not be installed."
                                          {:seon.source/path path :seon.source/changed-paths (:seon.source/changed-paths written)
                                           :seon.error/throwable failure})))))
       {:seon.source/changed-paths [] :seon.source/files []}
       proven))))

(defn write-back!
  "Write the function and test changes of `accepted` (`accept-merge!`'s
  accepted merge on `connection`'s branch) into their source files under
  `root`, each change into its one file: a replaced declaration's recorded byte span takes its accepted
  source, a retracted one's span (and the blank lines before it) goes, and an
  added one is appended to its namespace's file. A namespace row is its `ns`
  form: its span takes the merged form.

  Proven before installation. Refuses by reason unless each written
  declaration still holds, on the branch head, the digest the merge commit gave
  it (other writes never stale it); every change names one file (a replaced or
  retracted declaration by its state before the merge, an added one by its
  namespace's file) and every before-state a span holding its source; each
  file's bytes are the ones its declarations were analyzed from; and the
  ordinary analyzer (`seon.fn/analyze-rows` over the candidate text) reindexes
  the candidate to exactly the digests the merge commit holds for the file's
  declarations. Every file's disk bytes are checked again, then each
  candidate is installed by an atomic rename, answering one entry per file. Publishing the changed path stays with the
  publication owner.

  Example:
  (write-back! {:seon.db/connection connection :seon.source/accepted accepted :seon.fn/root \"/checkout\"})"
  {:malli/schema [:=> [:cat :seon.source/write-back-request]
                  [:or :seon.source/written :seon.source/write-back-error]]}
  [{connection :seon.db/connection accepted :seon.source/accepted root :seon.fn/root}]
  (let [[before after] (map #(d/commit-as-db connection (% accepted))
                            [:seon.source/base-commit-id :seon.source/commit-id])]
    (try
      (if (some nil? [before after])
        (write-back-refusal :commit-absent "The accepted merge's commits are unavailable in this store."
                            {:seon.error/offending (select-keys accepted [:seon.source/base-commit-id :seon.source/commit-id])})
        (write-back-commits connection accepted before after root))
      (finally (run! #(some-> % d/release-materialized-db) [before after])))))
