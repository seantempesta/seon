(ns seon.cluster.source
  "Publishes the latest complete source database value on one branch.

  Each changed digest is populated on a process-owned scratch branch. The
  first publication branches that complete head onto `:current-src`; later
  publications use Datahike's expected-head guard to advance the same branch.
  Failed and stale builds leave the previously published head untouched.
  Scratch branches are retired after publication and no connection to
  `:current-src` is opened or retained."
  (:require [seon.error.refusal]
            [babashka.process :as process]
            [clojure.edn :as edn]
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
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]
            [rewrite-clj.zip :as z]
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
                         (db/with-branch {:seon.db/connection connection} ; the scratch's own write
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
            (db/with-branch {:seon.db/connection connection} ; the scratch's own work
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

(defn branch-base-t
  "The base-t attribute toward `head` (the target's database) of `candidate`'s
  own fork entity in `branch` (`seon.cluster.registry/fork-of`): the one a
  merge recorded on the target under that fork's id, else the fork's own. A
  branch with no fork entity, or one whose target is not `target` (the branch
  `head` is read from), refuses by name."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.store/branch :seon.store/branch]
                  [:or :seon.db/basis-t :seon.program/digest-map-refusal]]}
  [branch head target candidate]
  (let [fork (registry/fork-of branch candidate)]
    (cond
      (nil? fork)
      (refused :seon.program/history "The branch has no fork entity, so it has no merge base."
               {:seon.store/branch candidate})
      (not= target (:seon.branch/target fork))
      (refused :seon.program/history "The branch's fork entity names another target, so it has no merge base with this one."
               {:seon.store/branch candidate :seon.error/expected (:seon.branch/target fork) :seon.error/offending target})
      :else (or (registry/advanced-base-t head (:seon.branch/fork-id fork)) (:seon.branch/base-t fork)))))

(defn merge-base
  "The immutable commit held by candidate's retained base ref, preserving
  values whose attributes omit history. The caller releases the database."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.store/branch :seon.store/branch]
                  [:or :seon.db/database-value :seon.program/digest-map-refusal]]}
  [branch head target candidate]
  (let [t (branch-base-t branch head target candidate)]
    (if (:seon.error/at t)
      t
      (if-let [commit (registry/base-commit-id branch candidate)]
        (d/commit-as-db branch commit)
        (refused :seon.program/history "The branch has no retained base ref."
                 {:seon.store/branch candidate})))))

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
(declare proven-files root-refusal)

(defn release!
  "Release materialized database values (the result of `compare-branch`, `commit-database`); refusals and nils are skipped."
  {:malli/schema [:=> [:cat [:sequential [:maybe [:or :seon.db/database-value :seon.program/digest-map-refusal :seon.error/value]]]] :nil]}
  [values]
  (run! #(when (and % (not (:seon.error/at %))) (d/release-materialized-db %)) values)
  nil)

(defn compare-branch
  "Compare `candidate`'s committed program with `head` (a database value of the
  branch `target`) on their merge base: the candidate's changed identities, the base, candidate and
  head digest maps and their `seon.program/three-way` classification. The one
  comparison merge and `my.program/diff` share. The base and branch values are
  materialized: the caller releases them. Refusals sit in `:c-id`, `:changed`
  or `:maps`."
  {:malli/schema [:=> [:cat :seon.store/store :seon.store/branch :seon.db/database-value :seon.store/branch] :map]}
  [held-store candidate head target]
  (let [c-id (registry/branch-commit-id {:seon.store/store held-store :seon.store/branch candidate})]
    (if (:seon.error/at c-id)
      {:c-id c-id}
      (let [bound {:seon.program/max-datoms 10000}
            branch (commit-database held-store c-id)
            base (merge-base branch head target candidate)
            ;; Only what the candidate changed since its base can merge or
            ;; conflict, read on the candidate's own lineage.
            changed (if (:seon.error/at base) base
                        (program/changed-identities (branch-base-t branch head target candidate) branch bound))
            maps (when (set? changed)
                   (mapv #(program/digest-map % (assoc bound :seon.program/identities changed)) [base branch head]))]
        {:c-id c-id :base base :branch branch :head head :changed changed :maps maps
         :three (when (and maps (not-any? :seon.error/at maps)) (apply program/three-way maps))}))))

(defn prepare-merge!
  "Test `candidate`'s function and test changes since its merge base with
  `source`'s head H on a fresh branch S of H through the save gate, with the
  `proofs` (the test symbols that prove the change: a merge task's proofs),
  and answer the prepared merge `accept-merge!` takes: the whole run's verdict and
  tally, and its red members by name, which refuse acceptance (AGENTS.md,
  merge). A change is a
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
                   [:or :seon.source/prepared :seon.program/three-way :seon.error/value]]
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.store/branch :seon.task/proofs
                        [:maybe :seon.program/identity-set]]
                   [:or :seon.source/prepared :seon.program/three-way :seon.error/value]]
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.store/branch :seon.task/proofs
                        [:maybe :seon.program/identity-set] [:maybe :seon.fn/root]]
                   [:or :seon.source/prepared :seon.program/three-way :seon.source/write-back-error :seon.error/value]]]}
  ([source held-store candidate proofs] (prepare-merge! source held-store candidate proofs nil nil))
  ([source held-store candidate proofs selection] (prepare-merge! source held-store candidate proofs selection nil))
  ([source held-store candidate proofs selection root]
  (let [{:keys [c-id base branch head changed maps three]}
        (let [head (db/db (:seon.db/connection source))]
          (compare-branch held-store candidate head (get-in head [:config :branch])))]
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
            root-error (when root (root-refusal root))
            proof (when (and root (nil? root-error) (vector? tx))
                    (let [merged (db/with head {:tx-data tx})]
                      (if (:seon.error/at merged) merged (proven-files head merged delta root))))
            refuse #(refused :seon.program/scope %1 {:seon.error/offending %2})]
        (try
          (cond
            root-error root-error
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
                                     :seon.source/expected-digests (held-digests head delta) :parents #{c-id}
                                     ;; The merge advances the branch's base to the value it merged.
                                     :seon.source/advanced-base (assoc (registry/fork-of branch candidate)
                                                                       :seon.branch/base-t (db/basis-t branch)))
                        (seq red) (assoc :seon.source/red red)))))
          (finally (release! [base branch]))))))))

(defn- red-test-verdicts
  "Each of `not-green` (test symbols of run `run-id` with no green member) with
  the run that recorded its result and its verdict: `:unknown` with no
  executed failure, `:red` when it is of `changed` (red on the tested branch
  and not red on the target under the same evaluator, `seon.test/change-reds`),
  else `:red-on-target`."
  {:malli/schema [:=> [:cat :seon.test.run/id :seon.test/results [:sequential :seon.test/sym] [:set :seon.test/sym]]
                  [:vector :seon.source/red-test]]}
  [run-id results not-green changed]
  (let [by-symbol (into {} (map (juxt :seon.test/sym identity)) results)
        red? (requiring-resolve 'seon.test.runner/red-result?)]
    (into [] (map (fn [test-symbol]
                    (let [{run :seon.test/run :as result} (by-symbol test-symbol)]
                      {:seon.test/sym test-symbol
                       :seon.test.run/id (or (if (vector? run) (second run) (:seon.test.run/id run)) run-id)
                       :seon.source/verdict (cond (not (and result (red? result))) :unknown
                                                  (changed test-symbol) :red
                                                  :else :red-on-target)})))
          (sort (distinct not-green)))))

(defn- red-test-refusal
  "The refusal of a merge whose run `run-id` on `s` left `red-tests` not
  green: `:seon.source/red-on-target-error` when each is already red on the
  target, for root to decide, else `:seon.source/red-test-error`."
  {:malli/schema [:=> [:cat :seon.test.run/id :seon.store/branch :seon.source/red-tests]
                  [:or :seon.source/red-test-error :seon.source/red-on-target-error]]}
  [run-id s red-tests]
  (let [on-target? (every? #(= :red-on-target (:seon.source/verdict %)) red-tests)]
    (seon.error.refusal/error
     (if on-target? :seon.source/red-on-target-error :seon.source/red-test-error)
     {:seon.error/layer :seon.source/merge :seon.error/operation 'seon.cluster.source/accept-merge!
      :seon.error/message (if on-target?
                            "Every test the merge leaves red is already red on the target; root decides."
                            "A test of the named run is not green; a merge commits only when every test is.")
      :seon.source/red-tests red-tests :seon.source/refused-test-run run-id :seon.test.run/branch s})))

(defn accept-merge!
  "Merge exactly the program tested by `prepare-merge!`'s prepared merge into
  `source`: the named run on S complete, each of its `:seon.task/proofs` a
  member, every member green (`red-test-refusal`), every added or replaced
  function reached by a green member, and S's program unwritten since. The
  writer refuses, before any datom lands, a merged declaration whose digest on
  the head moved from the prepared merge's `:seon.source/expected-digests`; writes to
  other rows never stale it. The merge commit's parents add the candidate and
  S's head, and it writes the merged branch's fork entity with its advanced
  base-t, so the next comparison's merge base is the value merged. A task handle the merged program names with no task admitted on the
  head refuses, by handle and holder. Answers the accepted merge `write-back!`
  takes: its identities, the commit before it, its merge commit and the run.
  The `merging` transaction data and metadata (root's merge of the assignment's
  facts, `seon.merge/merge!`) ride in the same transaction.

  Example:
  (accept-merge! source store prepared {:tx-data [] :tx-meta {}})"
  {:malli/schema [:function
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.source/prepared]
                   [:or :seon.source/accepted :seon.source/test-evidence-error :seon.error/value]]
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.source/prepared :seon.source/merging]
                   [:or :seon.source/accepted :seon.source/test-evidence-error :seon.error/value]]]}
  ([source held-store prepared] (accept-merge! source held-store prepared {:tx-data []}))
  ([source held-store {s :seon.source/candidate run-id :seon.test.run/id expected :seon.source/expected-digests
                      proofs :seon.task/proofs merged :parents advanced :seon.source/advanced-base}
   {merging :tx-data meta :tx-meta}]
  (let [e-id (registry/branch-commit-id {:seon.store/store held-store :seon.store/branch s})]
    (if (:seon.error/at e-id)
      e-id
      (let [tested (commit-database held-store e-id)
            head (db/db (:seon.db/connection source))
            delta (set (keys expected))
            refuse (fn [message offending]
                     (seon.error.refusal/error :seon.source/test-evidence-error
                      {:seon.error/layer :seon.source/merge :seon.error/operation 'seon.cluster.source/accept-merge!
                       :seon.error/message message
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
                unproven (vec (sort (remove (set (vals members)) proofs)))
                not-green (vec (remove green (vals members)))
                ;; one predicate: a red is the change's unless main is red under the same evaluator
                changed (when (and (vector? results) (seq not-green))
                          ((requiring-resolve 'seon.test/change-reds) tested source not-green))
                red-tests (when (and (vector? results) (not (:seon.error/at changed)))
                            (red-test-verdicts run-id results not-green (set changed)))
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
              (refuse "Every proof must be a member of the named run." unproven)
              (not (false? ((requiring-resolve 'seon.test.runner/program-written-since?) tested (:seon.test.run/basis-t run))))
              (refuse "The tested program changed after the named run." (:seon.test.run/basis-t run))
              (:seon.error/at changed) changed
              (seq red-tests) (red-test-refusal run-id s red-tests)
              (seq uncontracted)
              (refuse (str "Every function a merge carries declares a complete :malli/schema; these do not: "
                           (vec uncontracted) ". Declare their data shapes as registered schemas, "
                           "contract them on the candidate branch, run their tests and request the merge again.")
                      (vec uncontracted))
              (seq uncovered) (refuse "A replaced function reaches no green test of the named run." (vec uncovered))
              (:seon.error/at tx) tx
              (empty? tx) (refuse "The destination already holds the tested entities." (vec delta))
              (:seon.error/at dangling) dangling
              (seq dangling) (refuse "The merged program names tasks the destination has not admitted." dangling)
              :else
              (let [report (db/transact! (:seon.db/connection source)
                                         (cond-> {:tx-data (-> [[:db.fn/call declarations-held-tx expected] advanced]
                                                                  (into tx) (into merging))
                                                  :parents (conj merged e-id)}
                                           meta (assoc :tx-meta meta)))]
                (if (:db-after report)
                  (do
                    (let [merged-db (commit-database held-store (first merged))]
                      (try (registry/advance-base! merged-db (:seon.branch/name advanced))
                           (finally (d/release-materialized-db merged-db))))
                  {:seon.program/identities delta :seon.test.run/id run-id
                   ;; The head this commit merged into is its parent other than
                   ;; the merged ones: Datahike's own lineage. A report's
                   ;; `:db-before` is the writer's uncommitted value and can name
                   ;; an older commit (datahike writer.cljc:210).
                   :seon.source/base-commit-id
                   (first (remove (conj merged e-id) (d/parent-commit-ids (:db-after report))))
                   :seon.source/commit-id (db/commit-id (:db-after report))})
                  report))))
          (finally (d/release-materialized-db tested))))))))

;;; ---------------------------------------------------------------------------
;;; Write-back: an accepted merge's replacements, back into their one file
;;; ---------------------------------------------------------------------------

(defn- write-back-refusal
  "`write-back!`'s declared refusal: its reason, message and evidence."
  {:malli/schema [:=> [:cat :seon.source/write-back-refused :string :map] :seon.source/write-back-error]}
  [reason message members]
  (seon.error.refusal/error :seon.source/write-back-error
   (merge {:seon.error/layer :seon.source/write-back :seon.error/operation 'seon.cluster.source/write-back!
           :seon.error/message message :seon.source/write-back-refused reason}
          members)))

(defn- root-refusal
  "A `:root-refused` write-back error naming `root` unless it is an absolute
  existing directory outside any `data/source` archive (`seon.fs/checkout-root?`):
  a relative root resolves against the JVM's working directory, which on a Seon
  JVM is its committed archive."
  {:malli/schema [:=> [:cat :seon.fn/root] [:or :nil :seon.source/write-back-error]]}
  [root]
  (when-not (fs/checkout-root? root)
    (write-back-refusal :root-refused "A write-back root is the checkout's absolute path to an existing directory outside data/source; nothing was written."
                        {:seon.error/offending root})))

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

(defn- file-rows
  "The declarations `text`, the file at `path` under `root`, holds by identity,
  as the ordinary analyzer reads it against `database`'s program; else the
  analyzer's refusal (`:seon.fn/index-refused`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.fn/root :string :seon.source/path :string]
                  [:or [:map-of :seon.program/declaration-identity :map] [:map [:seon.fn/index-refused :boolean]]]]}
  [database root relative-root path text]
  ;; The analyzer refuses blocking findings by throwing (`seon.fn/assert-clean-analysis!`):
  ;; a declaration valid at the REPL may name a namespace its file does not require.
  ;; `database` names the first-party functions the file calls: without it a call into
  ;; another first-party namespace is unclassifiable (`seon.fn/host-bound-callers`).
  (let [analyzed (try (fn/analyze-entities {:seon.fn/root (str root) :seon.fn/roots [relative-root]
                                            :seon.fn/changed-paths #{path}
                                            :seon.source/previous-database database
                                            :seon.fn.analyzer/sources {(.getCanonicalPath (io/file root (str path))) text}})
                      (catch clojure.lang.ExceptionInfo failure
                        (if (:seon.fn/index-refused (ex-data failure)) (ex-data failure) (throw failure))))]
    (if (sequential? analyzed)
      (into {} (keep #(when (:seon.program/definition-digest %) [(program/row-identity %) %])) analyzed)
      analyzed)))

(defn- conflicts
  "The conflict digests of each of `identities` that `ours` (the file's
  declarations now, by identity) holds differently from both the merge base
  `before` and the branch `after`: changed on both sides."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value [:map-of :seon.program/declaration-identity :map]
                       :seon.program/identity-set]
                  :seon.program/conflict-digests]}
  [before after ours identities]
  (let [[base theirs] (map #(held-digests % identities) [before after])]
    (into {} (keep (fn [identity]
                     (let [head (get-in ours [identity :seon.program/definition-digest] :seon.program/absent)]
                       (when-not (#{(base identity) (theirs identity)} head)
                         [identity {:seon.program/base-digest (base identity) :seon.program/head-digest head
                                    :seon.program/branch-digest (theirs identity)}]))))
          identities)))

(defn- proven-file
  "The candidate bytes that write `identities` as `after` holds them over
  their one file under `root` as it is now, proven by the ordinary analyzer to
  declare the file's other declarations unchanged and exactly `after`'s rows of
  `identities`; else the refusal. A file changed since `before` recorded it
  (a sibling change landed) is read again: each written declaration must still
  hold its `before` digest there, else the write-back is a `:conflict`."
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
        recorded (for [identity identities] [identity (pull before identity) (pull after identity)])
        source-of #(or (:seon.fn/source %) (:seon.test/source %) (:seon.ns/source %))
        file-of (fn [[[_ declared] was _]]
                  (cond was (:seon.fn/file was)
                        (qualified-symbol? declared) (namespace-path before (symbol (namespace declared)))))
        files (into #{} (map file-of) recorded)
        {path :seon.fn.file/relative-path recorded-digest :seon.fn.file/digest
         relative-root :seon.fn.file/relative-root} (first files)
        file (io/file root (str path))
        base-bytes (when (and (= 1 (count files)) path (.isFile file)) (Files/readAllBytes (.toPath file)))
        base-digest (some-> base-bytes sha-256)
        base-text (some-> ^bytes base-bytes (String. "UTF-8"))
        ;; The file's declarations now: as `before` recorded them, or read again when the file changed since.
        ours (when (and base-bytes (not= recorded-digest base-digest)) (file-rows before root relative-root path base-text))
        conflicting (when (and ours (not (:seon.fn/index-refused ours))) (conflicts before after ours (set identities)))
        changes (if ours (mapv (fn [[identity _ now]] [identity (get ours identity) now]) recorded) recorded)
        before-entities (keep second changes)
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
        ;; An addition goes before the earliest function or test this write
        ;; replaces, so a replaced caller naming it follows it; else at the end.
        anchor (some->> changes
                        (keep (fn [[_ was now]] (when (and was now (not (:seon.ns/name was))) (first (span-of was)))))
                        seq (apply min))
        ;; Additions first: one inserted at the anchor precedes the replacement starting there.
        edits (fn [] (vec (for [[_ was now] (sort-by (fn [[_ was]] (if was 1 0)) changes)]
                            (cond
                              (and was now) [(span-of was) (source-of now)]
                              was (let [[start stop] (span-of was)] [[(from-blank start) stop] ""])
                              anchor [[anchor anchor] (str (source-of now) "\n\n")]
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
      (:seon.fn/index-refused ours)
      (refuse :not-analyzable "The file as it is now does not analyze cleanly." {:seon.error/offending (:seon.fn/findings ours)})
      (seq conflicting)
      (refuse :conflict "The file changed these declarations since your branch's base, and your branch changed them too."
              {:seon.program/conflict-digests conflicting})
      (let [spans (map span-of before-entities)]
        (or (some nil? spans) (not= (mapv span-text spans) (mapv source-of before-entities))))
      (refuse :span-mismatch "A before-state has no span, or its span no longer holds its declaration's source." {})
      :else
      (let [candidate (spliced base-bytes (edits))
            reindexed (file-rows after root relative-root path (String. ^bytes candidate "UTF-8"))
            ;; Every declaration the file holds after the write: its others as they are now, the written ones as `after` holds them.
            expected (into {} (remove (comp #{:seon.program/absent} val))
                           (merge (if ours (update-vals ours :seon.program/definition-digest) (file-digests before path))
                                  (held-digests after (set identities))))
            actual (when-not (:seon.fn/index-refused reindexed) (update-vals reindexed :seon.program/definition-digest))
            differing (vec (sort-by str (filter #(not= (get expected %) (get actual %))
                                                (into (set (keys expected)) (keys actual)))))]
        (cond
          (not actual)
          (refuse :not-analyzable "The candidate file does not analyze cleanly; its namespace form may lack a require the accepted declarations use."
                  {:seon.error/offending (:seon.fn/findings reindexed)})
          (seq differing)
          (refuse :not-equivalent "The reindexed candidate does not declare exactly the accepted program."
                  {:seon.error/offending differing :seon.error/expected (select-keys expected differing)})
          :else {:seon.source/path path :seon.source/base-digest base-digest
                 :seon.source/candidate-bytes candidate :seon.source/proof-rows (vec changes)})))))

(defn- schema-path
  "The schema resource `k` is declared in: its namespace's file under the
  checkout's `resources/` (`seon.schema.edn/default-resource`, whose loader
  refuses a key placed in another namespace's file)."
  {:malli/schema [:=> [:cat :qualified-keyword] :seon.source/path]}
  [k]
  (str "resources/" schema.edn/default-resource "/" (namespace k) ".edn"))

(defn- same-value?
  "Whether the nodes `a` and `b` read as the same value (a node that does not read is never the same)."
  {:malli/schema [:=> [:cat [:fn rewrite-clj.node/node?] [:fn rewrite-clj.node/node?]] :boolean]}
  [a b]
  (try (= (node/sexpr a) (node/sexpr b)) (catch Exception _ false)))

(defn- relaid
  "`value` (a parsed value node) in the layout of `old`, the value it replaces:
  a part reading as before keeps its node, and a collection of the same kind
  keeps its whitespace and comments between items, recursively, an added item
  taking the last separator; any other part is `value`'s. Reads as `value`."
  {:malli/schema [:=> [:cat [:fn rewrite-clj.node/node?] [:fn rewrite-clj.node/node?]] [:fn rewrite-clj.node/node?]]}
  [old value]
  (cond
    (same-value? old value) old
    (and (= (node/tag old) (node/tag value)) (#{:vector :list :map :set} (node/tag value)))
    (let [children (vec (node/children old))
          at (vec (keep-indexed (fn [i n] (when-not (node/whitespace-or-comment? n) i)) children))
          items (vec (remove node/whitespace-or-comment? (node/children value)))
          gaps (mapv (fn [[a b]] (subvec children (inc a) b)) (partition 2 1 at))
          gap #(get gaps % (or (peek gaps) [(node/spaces 1)]))]
      (if (empty? at)
        value
        (node/replace-children
         old
         (-> (subvec children 0 (first at))
             (into (mapcat (fn [i item]
                             (cons (if (< i (count at)) (relaid (children (at i)) item) item)
                                   (when (< i (dec (count items))) (gap i))))
                           (range) items))
             (into (subvec children (inc (peek at))))))))
    :else value))

(defn- schema-entry-edit
  "`children` (a schema resource map node's children) with `k`'s entry set to
  `form`: its value replaced in the old value's layout (`relaid`), its entry and the whitespace before it removed
  when `form` is nil, or, for a new `k`, an entry inserted before the first
  greater key when the keys are sorted, else appended, separated as the
  file's first two entries are. `prefix` is the map's namespace qualifier."
  {:malli/schema [:=> [:cat [:vector :any] [:maybe :string] :qualified-keyword [:maybe :string]] [:vector :any]]}
  [children prefix k form]
  (let [significant (vec (keep-indexed (fn [i n] (when-not (node/whitespace-or-comment? n) i)) children))
        keys-at (vec (take-nth 2 significant))
        key-of #(node/sexpr (nth children %))
        at (some #(when (= k (key-of %)) %) keys-at)
        after-key #(some (fn [i] (when (> i %) i)) significant)
        value-node #(parser/parse-string form)
        entry #(vector (node/keyword-node (if (= prefix (namespace k)) (keyword (name k)) k)) (node/spaces 1) (value-node))
        separator (if (< 1 (count keys-at))
                    (vec (remove node/comment? (subvec children (inc (after-key (first keys-at))) (second keys-at))))
                    [(node/spaces 1)])
        splice (fn [from to nodes] (into (into (subvec children 0 from) nodes) (subvec children to)))]
    (cond
      (and at form) (assoc children (after-key at) (relaid (nth children (after-key at)) (value-node)))
      at (let [before (last (filter #(< % at) significant))
               value (after-key at)]
           (if before
             (splice (inc before) (inc value) [])
             (splice at (or (after-key value) (count children)) [])))
      (nil? form) children
      (empty? keys-at) (splice 0 0 (entry))
      :else (let [ks (map key-of keys-at)
                  greater (when (= ks (sort ks)) (some #(when (pos? (compare (key-of %) k)) %) keys-at))]
              (if greater
                (splice greater greater (into (entry) separator))
                (let [end (inc (last significant))] (splice end end (into separator (entry)))))))))

(defn- schema-file-text
  "`text`, one schema resource's EDN map (plain or namespaced), with each
  `[k form]` of `edits` applied by rewrite-clj (`schema-entry-edit`); every
  other node keeps its text."
  {:malli/schema [:=> [:cat :string [:sequential [:tuple :qualified-keyword [:maybe :string]]]] :string]}
  [text edits]
  (let [outer (z/find (z/of-string text) z/right #(#{:map :namespaced-map} (z/tag %)))
        [m prefix] (if (= :namespaced-map (z/tag outer))
                     [(z/find (z/down outer) z/right #(= :map (z/tag %))) (:prefix (z/node (z/down outer)))]
                     [outer nil])
        children (reduce (fn [children [k form]] (schema-entry-edit children prefix k form))
                         (vec (node/children (z/node m))) edits)]
    (z/root-string (z/replace m (node/replace-children (z/node m) children)))))

(defn- schema-entry-digest
  "The definition digest publication gives `form` declared as `k` under
  `projection` (`seon.schema/canonical-schema-entities`)."
  {:malli/schema [:=> [:cat :seon.schema/projection :qualified-keyword :any] :seon.program/definition-digest]}
  [projection k form]
  (:seon.program/definition-digest (first (schema/canonical-schema-entities projection {k form}))))

(defn- proven-schema-file
  "The candidate bytes of the schema resource `identities` (schema keys of one
  namespace) are declared in, under `root`, with each entry as `after` holds
  it: replaced, removed or added by `schema-file-text`. A file entry that
  holds neither `before`'s nor `after`'s form is a `:conflict`. Proven by
  reading the candidate back: each written entry's publication digest
  (`schema-entry-digest` under `after`'s projection) equals `after`'s, a
  removed one is absent, and every other entry reads as it did."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set :seon.fn/root]
                  [:or :seon.source/proof :seon.source/write-back-error]]}
  [before after identities root]
  (let [path (schema-path (second (first identities)))
        file (io/file root path)
        base-bytes (when (.isFile file) (Files/readAllBytes (.toPath file)))
        base-text (some-> ^bytes base-bytes (String. "UTF-8"))
        pull (fn [database [_ k]]
               (let [entity (db/pull database [:db/id :seon.schema/key :seon.schema/form :seon.program/definition-digest]
                                     [:seon.schema/key k])]
                 (when (:db/id entity) (dissoc entity :db/id))))
        rows (vec (for [identity (sort-by str identities)] [identity (pull before identity) (pull after identity)]))
        form-of #(if % (edn/read-string (:seon.schema/form %)) ::absent)
        digest-of #(or (:seon.program/definition-digest %) :seon.program/absent)
        read-map #(try (let [value (edn/read-string %)] (when (map? value) value)) (catch Exception _ nil))
        ours (some-> base-text read-map)
        projection (db/carried-projection after)
        refuse (fn [reason message evidence] (write-back-refusal reason message (assoc evidence :seon.source/path path)))]
    (cond
      (nil? base-bytes) (refuse :file-absent "The changed schemas' resource is absent under the root." {})
      (nil? ours) (refuse :not-analyzable "The schema resource as it is now does not read as one EDN map." {})
      :else
      (let [conflicting (into {} (keep (fn [[[_ k :as identity] was now]]
                                         (let [held (get ours k ::absent)]
                                           (when-not (#{(form-of was) (form-of now)} held)
                                             [identity {:seon.program/base-digest (digest-of was)
                                                        :seon.program/branch-digest (digest-of now)
                                                        :seon.program/head-digest
                                                        (if (= ::absent held) :seon.program/absent
                                                            (schema-entry-digest (db/carried-projection before) k held))}]))))
                              rows)
            written (map (comp second first) rows)
            candidate (when (empty? conflicting)
                        (schema-file-text base-text (mapv (fn [[[_ k] _ now]] [k (:seon.schema/form now)]) rows)))
            read (some-> candidate read-map)
            differing (when read
                        (into (vec (keep (fn [[[_ k :as identity] _ now]]
                                           (when-not (if now
                                                       (and (contains? read k)
                                                            (= (digest-of now) (schema-entry-digest projection k (get read k))))
                                                       (not (contains? read k)))
                                             identity))
                                         rows))
                              (when (not= (apply dissoc ours written) (apply dissoc read written)) [:seon.source/other-entries])))]
        (cond
          (seq conflicting)
          (refuse :conflict "The resource changed these schemas since your branch's base, and your branch changed them too."
                  {:seon.program/conflict-digests conflicting})
          (nil? read) (refuse :not-analyzable "The candidate schema resource does not read as one EDN map." {})
          (seq differing)
          (refuse :not-equivalent "The candidate schema resource does not declare exactly the accepted schemas."
                  {:seon.error/offending differing})
          :else {:seon.source/path path :seon.source/base-digest (sha-256 base-bytes)
                 :seon.source/candidate-bytes (.getBytes ^String candidate "UTF-8") :seon.source/proof-rows rows})))))

(defn- change-path
  "The relative path of the one file `identity`'s change is written into: a
  schema's resource (`schema-path`), the declaration's own file as `before`
  recorded it, else (an addition) its namespace's file; nil when neither is
  recorded."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/declaration-identity] [:maybe :seon.source/path]]}
  [before [attribute declared :as identity]]
  (if (= :seon.schema/key attribute)
    (schema-path declared)
    (or (get-in (db/pull before [{:seon.fn/file [:seon.fn.file/relative-path]}] identity)
                [:seon.fn/file :seon.fn.file/relative-path])
        (when (qualified-symbol? declared)
          (:seon.fn.file/relative-path (namespace-path before (symbol (namespace declared))))))))

(defn- proven-files
  "`proven-file` (`proven-schema-file` for schemas) for each file the changes
  of `identities` are written into, by path: a function, its schema and the
  test proving it are three files of one merge. The first refusal answers; a
  change naming no file refuses `:one-file`."
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
                      (let [prove (if (= :seon.schema/key (ffirst group)) proven-schema-file proven-file)
                            proof (prove before after (set group) root)]
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

(defn- main-parent
  "The parent of commit `after` on its own branch, or the `:commit-absent` refusal.
  It is the main commit a merge was transacted onto, beside the merged commits its
  other parents name (Datahike's lineage, `datahike.api/parent-commit-ids`)."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.db/database-value]
                  [:or :seon.source/commit-id :seon.source/write-back-error]]}
  [connection after]
  (let [branch (get-in after [:config :branch])]
    (or (some (fn [parent]
                (let [database (d/commit-as-db connection parent)]
                  (try (when (= branch (get-in database [:config :branch])) parent)
                       (finally (some-> database d/release-materialized-db)))))
              (d/parent-commit-ids after))
        (write-back-refusal :commit-absent "The merge commit names no parent on its own branch."
                            {:seon.error/offending (db/commit-id after)}))))

(defn merge-accepted
  "The accepted merge main's merge `commit` made, as `write-back!` takes it.
  Derived from the commits on `connection`'s store alone: the declarations whose
  definition digest the merge changed, the main commit it was transacted onto and
  the merge commit; else the `:commit-absent` refusal."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.source/commit-id]
                  [:or :seon.source/accepted :seon.source/write-back-error]]}
  [connection commit]
  (let [after (d/commit-as-db connection commit)
        parent (if after
                 (main-parent connection after)
                 (write-back-refusal :commit-absent "The merge commit is unavailable in this store."
                                     {:seon.error/offending commit}))
        before (when (uuid? parent) (d/commit-as-db connection parent))]
    (try
      (if-not before
        parent
        (let [touched (fn/report-identities before after
                                            (vec (d/datoms (d/since (d/history after) (db/basis-t before)) :eavt))
                                            (vec program/identity-attributes))]
          (when (:seon.db/invalid-read touched) (throw (ex-info (:seon.error/message touched) touched)))
          (let [[was now] (map #(held-digests % (set touched)) [before after])]
            {:seon.program/identities (into #{} (filter #(not= (was %) (now %))) touched)
             :seon.source/base-commit-id parent :seon.source/commit-id commit})))
      (finally (run! #(some-> % d/release-materialized-db) [before after])))))

(defn merge-paths
  "The sorted checkout-relative paths `write-back!` writes `accepted` into.
  Each change's one file as the main commit before the merge records it
  (`change-path`); a change naming no file names none here (`write-back!` refuses it)."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.source/accepted] [:vector :seon.source/path]]}
  [connection {identities :seon.program/identities before :seon.source/base-commit-id}]
  (let [database (d/commit-as-db connection before)]
    (try (vec (sort-by str (into #{} (keep #(change-path database %)) identities)))
         (finally (d/release-materialized-db database)))))

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
  namespace's file) and every before-state a span holding its source; a file
  changed since its declarations were analyzed is read again and each written
  declaration must still hold its base digest there (else `:conflict`, with
  `:seon.program/conflict-digests`); and the ordinary analyzer reindexes the
  candidate to the file's other declarations unchanged and the merge commit's
  digests for the written ones. Every file's disk bytes are checked again, then each
  candidate is installed by an atomic rename, answering one entry per file. Publishing the changed path stays with the
  publication owner.

  `:seon.fn/root` is the checkout's absolute path (`seon.fs/checkout-root?`);
  a relative path or one inside a `data/source` archive is refused as
  `:root-refused` and nothing is written.

  Example:
  (write-back! {:seon.db/connection connection :seon.source/accepted accepted :seon.fn/root \"/Users/me/src/project\"})"
  {:malli/schema [:=> [:cat :seon.source/write-back-request]
                  [:or :seon.source/written :seon.source/write-back-error]]}
  [{connection :seon.db/connection accepted :seon.source/accepted root :seon.fn/root}]
  (let [[before after] (map #(d/commit-as-db connection (% accepted))
                            [:seon.source/base-commit-id :seon.source/commit-id])]
    (try
      (if-let [refused (root-refusal root)]
        refused
      (if (some nil? [before after])
        (write-back-refusal :commit-absent "The accepted merge's commits are unavailable in this store."
                            {:seon.error/offending (select-keys accepted [:seon.source/base-commit-id :seon.source/commit-id])})
        (write-back-commits connection accepted before after root)))
      (finally (run! #(some-> % d/release-materialized-db) [before after])))))

;;; ---------------------------------------------------------------------------
;;; Landing: one green merge request onto main, its files and one git commit
;;; ---------------------------------------------------------------------------

(defn- git
  "One git command `args` in the checkout `root`: its exit and trimmed output."
  {:malli/schema [:=> [:cat :seon.fn/root [:sequential :string]] [:map [:exit :int] [:out :string] [:err :string]]]}
  [root args]
  (let [{:keys [exit out err]} (apply process/shell {:dir root :out :string :err :string :continue true} "git" args)]
    {:exit exit :out (str/trim out) :err (str/trim err)}))

(defn- commit-refusal
  "`commit!`'s refusal: its message and evidence."
  {:malli/schema [:=> [:cat [:vector :seon.source/path] :string :map] :seon.git/commit-error]}
  [paths message members]
  (seon.error.refusal/error :seon.git/commit-error
   (merge {:seon.error/layer :seon.source/write-back :seon.error/operation 'seon.cluster.source/commit!
           :seon.error/message message :seon.git/paths paths}
          members)))

(defn- commit-message
  "The merge's commit message: what merged and the evidence it merged on, in `bin/lane`'s trailer form."
  {:malli/schema [:=> [:cat :seon.source/acceptance [:vector :seon.source/path]] :string]}
  [{task-sym :seon.task/sym assignment :seon.task/assignment agent-id :seon.agent/id run-id :seon.test.run/id
    commit-id :seon.source/commit-id}
   paths]
  (str "Merge " (or task-sym (str "assignment " assignment)) (when agent-id (str " from agent " agent-id))
       ": merged on main as " commit-id (when run-id (str " by gate run " run-id)) "\n\n"
       "Written: " (str/join " " paths) "\n\n"
       "Assignment: " assignment "\n"
       "Merge-commit: " commit-id "\n"
       (when run-id (str "Branch-evidence-runs: " run-id "\n"))
       "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\n"))

(defn- call-with-commit-lock
  "`(f)` while this process holds `.git/lane-commit.lock` of the checkout `root`,
  the lock `bin/lane commit` takes with perl's `flock` around its staged-index
  check and commit, so a landing and a lane commit never interleave in the shared
  index; else the refusal naming why the lock was not taken. A perl child holds
  the lock until its stdin closes, which this process does when `f` returns (and
  the OS does if this process dies)."
  {:malli/schema [:=> [:cat :seon.fn/root [:vector :seon.source/path] [:fn clojure.core/ifn?]]
                  [:or :map :seon.git/commit-error]]}
  [root paths f]
  (let [git-dir (git root ["rev-parse" "--absolute-git-dir"])]
    (if-not (zero? (:exit git-dir))
      (commit-refusal paths "The checkout's git directory could not be read." {:seon.error/offending (:err git-dir)})
      (let [holder (process/process {:dir root :err :string}
                                    "perl" "-MFcntl=:flock" "-e"
                                    (str "open(my $lock, '>>', shift) or die \"lane-commit.lock: $!\\n\"; "
                                         "flock($lock, LOCK_EX) or die \"lane-commit.lock: $!\\n\"; "
                                         "$| = 1; print \"locked\\n\"; <STDIN>;")
                                    (str (:out git-dir) "/lane-commit.lock"))]
        (try
          (if (= "locked" (.readLine (io/reader (:out holder))))
            (f)
            (commit-refusal paths "The lane commit lock could not be taken; nothing was committed."
                            {:seon.error/offending (str/trim (:err @holder))}))
          (finally
            (.close ^java.io.OutputStream (:in holder))
            (deref holder)))))))

(defn- committed-merge
  "The commit of the checkout `root` whose message names the merge commit `commit-id`
  (`commit-message`'s `Merge-commit:` trailer), or the refusal saying none does."
  {:malli/schema [:=> [:cat :seon.fn/root [:vector :seon.source/path] :seon.source/commit-id]
                  [:or [:map [:seon.git/commit :seon.git/commit]] :seon.git/commit-error]]}
  [root paths commit-id]
  (let [found (git root ["log" "-1" "--format=%H" "--fixed-strings" (str "--grep=Merge-commit: " commit-id)])]
    (if (and (zero? (:exit found)) (seq (:out found)))
      {:seon.git/commit (:out found)}
      (commit-refusal paths "The written paths equal HEAD and no commit names this merge; nothing was committed."
                      {:seon.error/offending (:err found)}))))

(defn- commit-locked
  "`commit!` under the lane commit lock: the staged-index check, the commit of
  exactly `paths` and its verification. Untracked files among `paths` are added alone
  first and unstaged again if git refuses the commit, as `bin/lane commit` does. Paths
  already equal to HEAD were committed by this merge before: its commit answers."
  {:malli/schema [:=> [:cat :seon.fn/root [:vector :seon.source/path] :seon.source/acceptance]
                  [:or [:map [:seon.git/commit :seon.git/commit]] :seon.git/commit-error]]}
  [root paths acceptance]
  (let [staged (git root ["diff" "--cached" "--name-only"])
        other (when (zero? (:exit staged)) (vec (remove (set paths) (remove str/blank? (str/split-lines (:out staged))))))
        untracked (when (seq paths) (git root (into ["ls-files" "--others" "--exclude-standard" "--"] paths)))
        added (vec (remove str/blank? (str/split-lines (str (:out untracked)))))
        unchanged? (and (seq paths) (empty? added) (zero? (:exit (git root (into ["diff" "--quiet" "HEAD" "--"] paths)))))
        before (git root ["rev-parse" "HEAD"])]
    (cond
      (not (zero? (:exit staged))) (commit-refusal paths "The checkout's git index could not be read." {:seon.error/offending (:err staged)})
      (seq other) (commit-refusal paths "Other paths are staged in the shared index; a merge commits only its own." {:seon.error/offending other})
      (empty? paths) (commit-refusal paths "The merge wrote no path to commit." {:seon.error/offending paths})
      unchanged? (committed-merge root paths (:seon.source/commit-id acceptance))
      :else
      (let [_ (when (seq added) (git root (into ["add" "--"] added)))
            made (git root (into ["commit" "--only" "--cleanup=whitespace" "-m" (commit-message acceptance paths) "--"] paths))
            _ (when (and (seq added) (not (zero? (:exit made)))) (git root (into ["reset" "-q" "--"] added)))
            after (git root ["rev-parse" "HEAD"])
            carried (git root ["diff-tree" "--no-commit-id" "--name-only" "-r" "--no-renames" (:out before) (:out after)])]
        (cond
          (not (zero? (:exit made))) (commit-refusal paths "git commit failed; nothing was committed." {:seon.error/offending (:err made)})
          (not= (set paths) (set (str/split-lines (:out carried))))
          (commit-refusal paths "The commit git made is not exactly the written paths; history is not rewritten."
                          {:seon.error/offending (str/split-lines (:out carried)) :seon.error/expected paths})
          :else {:seon.git/commit (:out after)})))))

(defn commit!
  "Commit exactly the paths `written` installed under the checkout `root` as one git commit
  (`git commit --only`), its message derived from the `acceptance`, and answer its sha as
  `:seon.git/commit`. Refuses, committing nothing, when anything else is staged in the
  index: the rule `bin/lane commit` enforces for Claude lanes, which this replaces for
  landings. The check and the commit hold `.git/lane-commit.lock` as `bin/lane commit`
  does, so neither interleaves with a concurrent lane commit. A commit git made that is not exactly the written paths on the previous head
  is answered as a refusal naming what it carries; history is never rewritten.

  Example:
  (commit! \"/Users/me/src/project\" written acceptance)"
  {:malli/schema [:=> [:cat :seon.fn/root :seon.source/written :seon.source/acceptance]
                  [:or [:map [:seon.git/commit :seon.git/commit]] :seon.git/commit-error]]}
  [root written acceptance]
  (let [paths (vec (:seon.source/changed-paths written))]
    (call-with-commit-lock root paths #(commit-locked root paths acceptance))))

;;; The lane ledger `bin/lane` keeps: tmp/orchestrator/lanes/<lane>.paths, one checkout-relative path
;;; (or dir/ prefix) per line.

(defn- ledger-file
  "The path of lane `lane`'s claims file in the checkout `root`'s lane ledger."
  {:malli/schema [:=> [:cat :seon.fn/root :string] :string]}
  [root lane]
  (str (io/file root "tmp" "orchestrator" "lanes" (str lane ".paths"))))

(defn- covers?
  "Whether the ledger entry `entry` covers `path`: equal, or a dir/ prefix of it (`bin/lane`'s `covers`)."
  {:malli/schema [:=> [:cat :string :seon.source/path] :boolean]}
  [entry path]
  (or (= entry path) (and (str/ends-with? entry "/") (str/starts-with? path entry))))

(defn waiting-paths
  "The `paths` of the checkout `root` lane `lane` may not write now, sorted: each one another lane's
  claim in `bin/lane`'s ledger covers, or with uncommitted changes (`git status`) no claim of `lane`
  covers. A path a lane holds or edits waits; nothing is locked."
  {:malli/schema [:=> [:cat :seon.fn/root :string [:vector :seon.source/path]] [:vector :seon.source/path]]}
  [root lane paths]
  (let [claims (for [file (or (.listFiles (.getParentFile (io/file (ledger-file root lane)))) [])
                     :let [name (.getName ^java.io.File file)]
                     :when (str/ends-with? name ".paths")
                     entry (remove str/blank? (str/split-lines (slurp file)))]
                 [(subs name 0 (- (count name) (count ".paths"))) entry])
        holders (fn [path] (into #{} (keep (fn [[holder entry]] (when (covers? entry path) holder))) claims))
        dirty? (fn [path] (seq (:out (git root ["status" "--porcelain" "--" path]))))]
    (vec (sort (filter (fn [path] (let [held (holders path)]
                                    (or (seq (disj held lane)) (and (not (held lane)) (dirty? path)))))
                       paths)))))

(defn claim-paths!
  "Record lane `lane`'s claim of `paths` in the checkout `root`'s `bin/lane` ledger; nil."
  {:malli/schema [:=> [:cat :seon.fn/root :string [:vector :seon.source/path]] :nil]}
  [root lane paths]
  (let [file (io/file (ledger-file root lane))]
    (io/make-parents file)
    (spit file (str (str/join "\n" paths) "\n"))
    nil))

(defn release-claim!
  "Remove lane `lane`'s claims from the checkout `root`'s `bin/lane` ledger; nil."
  {:malli/schema [:=> [:cat :seon.fn/root :string] :nil]}
  [root lane]
  (Files/deleteIfExists (.toPath (io/file (ledger-file root lane))))
  nil)

(defn land!
  "Root's merge of the assignment the merge request `merge-request` answers (`seon.merge/merge!`), into the
  checkout `root`: the one merge, called here until `seon.turn/land-end!` goes (hybrid-done-and-merge M8)."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.task/merge-request-view :seon.fn/root]
                  [:or :seon.merge/merged :seon.source/conflict-error :seon.error/value]]}
  [execution merge-request root]
  ((requiring-resolve 'seon.merge/merge!)
   execution
   (db/q '[:find ?k . :in $ ?r :where [?m :seon.task/merge-request ?r] [?m :seon.task/of-assignment ?a] [?a :seon.task/assignment ?k]]
         (db/db (:seon.db/connection execution)) (:seon.task/merge-request merge-request))
   root))
