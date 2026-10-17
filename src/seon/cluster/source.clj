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
            [seon.cache]
            [seon.db :as db]
            [seon.error :as error]
            [seon.fn :as fn]
            [seon.fn.analyzer :as analyzer]
            [seon.fs :as fs]
            [seon.id :as id]
            [seon.program :as program]
            [seon.ref]
            [seon.test.cache :as test.cache]
            [hasch.core :as hasch]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]
            [rewrite-clj.zip :as z]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.task :as task])
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

(defn- changed-datoms
  "The entity and attribute of every datom held by exactly one of `before` and `after`, two commits of one store:
  their EAVT index diff (`seon.db/datoms-diff`), so the work follows what changed, never main's whole history."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value]
                  [:vector [:map [:e :int] [:a :keyword]]]]}
  [before after]
  (into [] (comp cat (map (fn [[e a]] {:e e :a a}))) (db/datoms-diff before after :eavt [])))

(defn changed-identities
  "Publication report identities, or what changed since the cluster's adopted commit."
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
                              (changed-datoms before after))]
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

(def ^:private merge-base-refusals
  "The `datahike.api/merge-base` refusals that answer as data: they say this pair of commits has no one merge base."
  #{:merge-base-criss-cross :commit-not-found :branch-does-not-exist :commit-graph-disabled})

(defn merge-base
  "The merge base of the commits `a` and `b` of `database`'s store, git's `merge-base`: their best common ancestor
  in Datahike's commit graph (`datahike.api/merge-base`), a commit id. A pure function of two immutable ids, so it
  is cached by them (`seon.cache`) and never invalidated. Two commits with no common ancestor, a criss-cross with
  several best ones, or an unknown commit refuse by name."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.source/commit-id :seon.source/commit-id]
                  [:or :seon.source/commit-id :seon.program/digest-map-refusal]]}
  [database a b]
  (let [schema-db (db/schema-database database)
        ;; every merge base shares the commits' graph records: a commit is immutable, so its record is cached by
        ;; its id and a collection's merge bases of every work branch read main's history once
        record (fn [cid] (seon.cache/lookup ::commit-graph-record cid (constantly 128)
                                            #(d/commit-graph-record schema-db cid)))
        found (try (seon.cache/lookup ::merge-base (vec (sort [a b])) (constantly 64)
                                      #(d/merge-base schema-db a b record))
                   (catch clojure.lang.ExceptionInfo failure
                     ;; a deliberate translation: Datahike's named refusal, answered as this reader's
                     (if (merge-base-refusals (:type (ex-data failure)))
                       (assoc (refused :seon.program/history (ex-message failure)
                                       {:seon.error/offending (:type (ex-data failure)) :seon.error/expected [a b]})
                              :seon.error/operation 'seon.cluster.source/merge-base)
                       (throw failure))))]
    (or found
        (assoc (refused :seon.program/history "The two commits share no ancestor, so they have no merge base."
                        {:seon.error/expected [a b]})
               :seon.error/operation 'seon.cluster.source/merge-base))))

(defn merge-base-database
  "The database at the merge base (`merge-base`) of the values `a` and `b`, opened by `seon.db/commit-database`; the
  caller releases it. A refused merge base is answered."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value]
                  [:or :seon.db/database-value :seon.program/digest-map-refusal]]}
  [a b]
  (let [base (merge-base a (db/commit-id a) (db/commit-id b))]
    (if (:seon.error/at base) base (db/commit-database a base))))

(defn branch-merge-base
  "The merge base of the branch value `database` with its target's head (its fork entity's `:seon.branch/target`,
  `seon.cluster.registry/fork-of`): `git merge-base HEAD main`, a commit id; nil on a value with no fork entity
  (main itself) or whose target the store no longer holds; a refused merge base is answered."
  {:malli/schema [:=> [:cat :seon.db/database-value]
                  [:or :nil :seon.source/commit-id :seon.program/digest-map-refusal]]}
  [database]
  (let [target (:seon.branch/target (registry/fork-of database (get-in database [:config :branch])))
        head (when target (registry/head-commit-id database target))]
    (when head (merge-base database (db/commit-id database) head))))

(defn- replacement
  "Exact replacement transaction data onto `onto` for `delta`'s entities in `from`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set]
                  [:or :seon.db/tx-data :seon.error/value]]}
  [from onto delta]
  (let [entities (fn/published-index-entities from (vec delta))]
    (if (vector? entities) (fn/reconcile-tx onto entities (vec delta)) entities)))

(defn- held-digests
  "The definition digest `database` holds for each of `identities` (a task's
  revision, `seon.task/task-revision`, for `[:seon.task/sym ns/name]`),
  `:seon.program/absent` where it declares none: what a merge or write-back
  compares, so writes to other rows never stale it."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set] :seon.source/expected-digests]}
  [database identities]
  (into {} (map (fn [[attribute value :as identity]]
                  [identity (or (if (= :seon.task/sym attribute)
                                  (task/task-revision database (task/task-handle (symbol (namespace value)) (name value)))
                                  (db/q '[:find ?digest . :in $ ?a ?v
                                          :where [?e ?a ?v] [?e :seon.program/definition-digest ?digest]]
                                        database attribute value))
                                :seon.program/absent)]))
        identities))

(defn- moved-digests
  "The members of `expected` whose digest `held` disagrees with."
  {:malli/schema [:=> [:cat :seon.source/expected-digests :seon.source/expected-digests] [:vector :seon.program/declaration-identity]]}
  [expected held]
  (vec (sort-by str (filter #(not= (expected %) (held %)) (keys expected)))))

(declare proven-files lint-errors root-refusal identity-namespace new-path done-refusals)

(defn release!
  "Release materialized database values (the result of `compare-branch`, `commit-database`); refusals and nils are skipped."
  {:malli/schema [:=> [:cat [:sequential [:maybe [:or :seon.db/database-value :seon.program/digest-map-refusal :seon.error/value]]]] :nil]}
  [values]
  (run! #(when (and % (not (:seon.error/at %))) (d/release-materialized-db %)) values)
  nil)

(def merge-bound
  "The bound on the changed entities one merge comparison reads (`seon.program/changed-identities`)."
  {:seon.program/max-datoms 10000})


(declare agent-owned)

(defn- contribution
  "The nearest-test contribution of one changed declaration."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity]
                  [:or [:map [:seon.fn/tests :seon.fn/tests] [:seon.fn/walked [:set :qualified-symbol]]]
                   :seon.error/value]]}
  [database identity]
  (let [selected ((requiring-resolve 'seon.test/reaching-selection)
                  {:seon.db/db database :seon.test/changed [identity]})]
    (if (:seon.error/at selected) selected
      (update selected :seon.fn/tests
              #(into [] (remove (fn [test] (:seon.test/long (db/pull database [:seon.test/long] [:seon.test/sym test])))) %)))))

(defn- remove-contribution
  "Remove one declaration's contribution, retaining tests shared with other declarations."
  {:malli/schema [:=> [:cat :map :seon.program/identity] :map]}
  [held identity]
  (let [{tests :seon.fn/tests walked :seon.fn/walked} (get-in held [::contributions identity])]
    (-> (reduce (fn [state test]
                  (let [n (dec (get-in state [::counts test]))]
                    (if (pos? n) (assoc-in state [::counts test] n)
                      (-> state (update ::counts dissoc test) (update ::reached disj test))))) held tests)
        (update ::walkers #(reduce (fn [m target]
                                    (let [remaining (disj (get m target) identity)]
                                      (if (seq remaining) (assoc m target remaining) (dissoc m target))))
                                  % walked))
        (update ::contributions dissoc identity))))

(defn- add-contribution
  "Add one declaration's tests and reverse dependency entries to the held fold."
  {:malli/schema [:=> [:cat :map :seon.program/identity
                       [:map [:seon.fn/tests :seon.fn/tests] [:seon.fn/walked [:set :qualified-symbol]]]] :map]}
  [held identity selected]
  (-> (reduce (fn [state test]
                (-> state (update-in [::counts test] (fnil inc 0)) (update ::reached conj test)))
              held (:seon.fn/tests selected))
      (update ::walkers #(reduce (fn [m target] (update m target (fnil conj #{}) identity))
                                % (:seon.fn/walked selected)))
      (assoc-in [::contributions identity] selected)))

(defn- fold-members
  "Update the contribution fold for changed identities and changed incoming call edges."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set :map [:set :qualified-symbol]]
                  [:or :map :seon.error/value]]}
  [database changed held targets]
  (let [previous (set (keys (::contributions held)))
        affected (into #{} (mapcat #(get-in held [::walkers %])) targets)
        removed (into affected (remove changed) previous)
        added (into (into #{} (filter changed) affected) (remove previous) changed)
        remaining (reduce remove-contribution held removed)]
    (reduce (fn [state identity]
              (let [selected (contribution database identity)]
                (if (:seon.error/at selected) (reduced selected)
                  (add-contribution state identity selected))))
            remaining added)))

(defn- member-contributions
  "The held union of per-identity selections; call-edge changes replace only their dependent contributions."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set :seon.schema/value]
                  [:or :map :seon.error/value]]}
  [database changed revisions]
  (let [edges [:seon.fn/calls :seon.test/subject]
        key [revisions changed]
        head (when (map? revisions) (apply dissoc revisions edges))
        empty-fold {::contributions {} ::walkers {} ::counts {} ::reached #{}}
        derive (fn []
                 (let [prior-key (when head (seon.cache/present ::member-head head))
                       prior (when prior-key (seon.cache/present ::member-contributions prior-key))
                       update-fold (fn [before]
                                     (let [targets (when before
                                                     (into #{} (comp (mapcat #(apply concat (db/datoms-diff before database :aevt [%])))
                                                                      (map #(nth % 2))) edges))]
                                       (assoc (fold-members database changed (if before prior empty-fold) (or targets #{}))
                                              ::commit (db/commit-id database))))]
                   (if prior (db/read-at database (::commit prior) update-fold) (update-fold nil))))]
    (if (seq revisions)
      (let [held (seon.cache/lookup ::member-contributions key
                                   #(+ 256 (* 96 (count revisions)) (* 160 (count changed)) (* 192 (count (::counts %)))
                                       (* 160 (+ (count (::contributions %))
                                                 (transduce (map count) + 0 (vals (::walkers %)))
                                                 (transduce (map (comp count :seon.fn/tests val)) + 0 (::contributions %)))))
                                   derive #(not (:seon.error/at %)))]
        (when (and head (not (:seon.error/at held)))
          (seon.cache/evict! ::member-head head)
          (seon.cache/lookup ::member-head head (constantly (+ 256 (* 96 (count head)))) (constantly key)))
        held)
      (derive))))

(defn merge-members
  "The member list of a merge of `changed` (declaration identities) on `database`, with `proofs`: the one list the
  attempt's test step runs after each eval request (`seon.turn`), the done rule judges (`done-refusals`) and the merge
  gate runs (`seon.cluster/reaching-run`). `:seon.source/members` holds the proofs, every test `changed` names that
  `database` defines, and every other test reaching a changed function, test, namespace or schema as the gate's named
  selection walks it (`seon.test/reaching`: a namespace seeds its members and the tests reading it as a file, a
  schema the functions whose contracts name it) but those declared long; `:seon.source/reaching` holds each changed
  declaration's own reaching tests, the fold's contribution of it. `:seon.source/deferred` holds the members
  that run only on a platform host (`seon.test/host-exclusions` of an isolated run): the merge's platform run answers
  them. Held by selection's content revisions (`seon.test/selection-revisions`), changed identities and proofs.
  Call-edge changes fold only the contributions whose walks read those targets; result and evaluation writes reuse the list. Reads only."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set [:set :seon.test/sym]]
                  [:or :seon.source/member-list :seon.error/value]]}
  [database changed proofs]
  (let [revisions ((requiring-resolve 'seon.test/selection-revisions) database)
        derive (fn []
                (let [held? #(seq (d/datoms database :avet :seon.test/sym %))
                      seeds (into [] (filter (fn [[attribute]] (#{:seon.fn/sym :seon.test/sym :seon.ns/name :seon.schema/key} attribute)))
                                  (sort-by str changed))
                      folded (member-contributions database (set seeds) revisions)
                      reached (::reached folded)
                      named (into (set proofs) (keep (fn [[a v]] (when (= :seon.test/sym a) v))) changed)
                      members (when (set? reached)
                                (into reached (filter held?) named))
                      isolated (when members
                                 ((requiring-resolve 'seon.test/host-exclusions) database nil true (vec (sort members))))]
                  (cond (:seon.error/at folded) folded
                        (:seon.error/at isolated) isolated
                        :else {:seon.source/members members
                               :seon.source/deferred (into #{} (map :seon.test/sym)
                                                           (concat (:seon.test/destructive-excluded isolated) (:seon.test/deferred isolated)))
                               :seon.source/reaching (into {} (map (fn [[identity {tests :seon.fn/tests}]] [identity (set tests)]))
                                                           (::contributions folded))})))]
    (if (seq revisions)
      (seon.cache/lookup ::members [revisions changed proofs]
                         #(+ (* 96 (count revisions)) (* 160 (+ (count changed) (count proofs)
                                                               (count (:seon.source/members %)))))
                         derive #(not (:seon.error/at %)))
      (derive))))

(defn merge-changes
  "Of `changed`, the identities branch `database` changed, those a merge onto `head` carries: all but an agent's
  own namespace and its declarations (`agent-owned`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set] :seon.program/identity-set]}
  [database head changed]
  (into #{} (remove (agent-owned database head changed)) changed))

(defn- agent-owned
  "The identities of `changed` that belong to an agent the destination `head` lacks: a namespace born on `branch`
  (the destination holds none of its name) stewarded by such an agent (`:seon.ns/steward`), and each declaration
  in it. An agent's own namespace
  is its compartment, not the branch's program: a chain's agents live only on its branch (TASK-CHAIN §2.2),
  so their namespaces never merge. Interim until `seon.cluster.agent/agent-creation-writes-no-program-namespace`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set]
                  :seon.program/identity-set]}
  [branch head changed]
  (let [stewarded (db/q '[:find ?n ?id :where [?ns :seon.ns/steward ?g] [?ns :seon.ns/name ?n] [?g :seon.agent/id ?id]]
                        branch)
        _ (when (map? stewarded) (throw (ex-info (:seon.error/message stewarded "The branch's namespace stewards could not be read.") stewarded)))
        ;; born on the branch for its agent: a namespace the destination already holds is never an agent's own
        owned (into #{} (keep (fn [[n agent-id]]
                                (when-not (or (:db/id (db/pull head [:db/id] [:seon.agent/id agent-id]))
                                              (:db/id (db/pull head [:db/id] [:seon.ns/name n])))
                                  n)))
                    stewarded)]
    (into #{} (filter (fn [[attribute value]]
                        (owned (if (= :seon.ns/name attribute)
                                 value
                                 (when (qualified-symbol? value) (symbol (namespace value)))))))
          changed)))

(defn unmerged-tx
  "Transaction data recording each of `identities`, conflicts whose three digests `digests` holds
  (`:seon.program/conflict-digests`), as an unmerged entry (`:seon.unmerged/entry`) on the branch the merge writes:
  `ours-key` and `theirs-key` name which digest of the comparison the target holds and which the source holds,
  `base-commits` the merge base commit of each (else `base-commit`) and `theirs-commit` the source commit, where
  each other version is read."
  {:malli/schema [:=> [:cat :seon.program/identity-set :seon.program/conflict-digests :qualified-keyword :qualified-keyword
                       [:map-of :seon.program/declaration-identity :seon.source/commit-id] [:maybe :seon.source/commit-id]
                       :seon.source/commit-id]
                  :seon.store/transaction-data]}
  [identities digests ours-key theirs-key base-commits base-commit theirs-commit]
  (mapv (fn [[attribute value :as identity]]
          (let [digest (fn [k] (let [d (get-in digests [identity k])] (when (string? d) d)))
                base (get base-commits identity base-commit)]
            (cond-> {:seon.unmerged/id (id/id identity) :seon.unmerged/attribute attribute :seon.unmerged/name value
                     :seon.unmerged/theirs-commit theirs-commit}
              (digest :seon.program/base-digest) (assoc :seon.unmerged/base-digest (digest :seon.program/base-digest))
              (digest ours-key) (assoc :seon.unmerged/ours-digest (digest ours-key))
              (digest theirs-key) (assoc :seon.unmerged/theirs-digest (digest theirs-key))
              base (assoc :seon.unmerged/base-commit base))))
        (sort-by str identities)))

(defn- entry-identity
  "The declaration identity an unmerged entry names."
  {:malli/schema [:=> [:cat :seon.unmerged/shown] :seon.program/declaration-identity]}
  [entry]
  [(:seon.unmerged/attribute entry) (:seon.unmerged/name entry)])

(defn unmerged
  "The unresolved unmerged entries branch `database` holds (`:seon.unmerged/entry`, git's unmerged index entries): each
  whose declaration the branch still holds at its `:seon.unmerged/ours-digest` (absent when ours was absent). Any
  write of the declaration resolves its entry; retracting the entry keeps ours. What the done rule
  (`done-refusals`) and root's merge (`compare-branch`) refuse while any is left."
  {:malli/schema [:=> [:cat :seon.db/database-value] :seon.unmerged/entries]}
  [database]
  (let [entries (into [] (map (fn [datom] (dissoc (db/pull database '[*] (:e datom)) :db/id :seon.unmerged/id)))
                      (when (get-in database [:schema :seon.unmerged/id]) (d/datoms database :aevt :seon.unmerged/id)))
        held (held-digests database (into #{} (map entry-identity) entries))]
    (filterv #(= (held (entry-identity %)) (:seon.unmerged/ours-digest % :seon.program/absent)) entries)))

(defn- with-unmerged
  "`three` (`seon.program/three-way` of the merge base, the candidate and the head, whose digest maps are
  `branch-map` and `head-map`) with each of the candidate's unresolved unmerged `entries` a conflict: a conflict
  stays unmerged on its branch until someone resolves it, whatever the merge base has become since."
  {:malli/schema [:=> [:cat :seon.program/three-way :seon.program/digest-map :seon.program/digest-map :seon.unmerged/entries]
                  :seon.program/three-way]}
  [three branch-map head-map entries]
  (reduce (fn [three entry]
            (let [identity (entry-identity entry)]
              (-> (reduce #(update %1 %2 disj identity) three
                          [:seon.program/unchanged :seon.program/changed-on-branch :seon.program/changed-on-head
                           :seon.program/added :seon.program/retracted])
                  (update :seon.program/conflict conj identity)
                  (assoc-in [:seon.program/conflict-digests identity]
                            {:seon.program/base-digest (:seon.unmerged/base-digest entry :seon.program/absent)
                             :seon.program/branch-digest (get branch-map identity :seon.program/absent)
                             :seon.program/head-digest (get head-map identity :seon.program/absent)}))))
          three entries))

(defn- compare-commit
  "`compare-branch` of the candidate's commit `c-id`, already read from the store's branches."
  {:malli/schema [:=> [:cat :seon.store/store :seon.source/commit-id :seon.db/database-value] :map]}
  [held-store c-id head]
  (let [bound merge-bound
        branch (commit-database held-store c-id)
        base (merge-base-database branch head)
        open (unmerged branch)
        ;; Only what the candidate changed since its merge base can merge or conflict: git's diff of the base
        ;; and the candidate, walked on the candidate's own lineage, and what it holds unmerged.
        changed (if (:seon.error/at base) base (program/own-changed-identities base branch bound))
        ;; an agent's own namespace is its compartment, never the branch's program (`agent-owned`)
        changed (if (set? changed)
                  (into (into #{} (remove (agent-owned branch head changed)) changed) (map entry-identity) open)
                  changed)
        maps (when (set? changed)
               (mapv #(program/digest-map % (assoc bound :seon.program/identities changed)) [base branch head]))]
    {:c-id c-id :base base :branch branch :head head :changed changed :maps maps :unmerged open
     :three (when (and maps (not-any? :seon.error/at maps))
              (with-unmerged (apply program/three-way maps) (second maps) (nth maps 2) open))}))

(defn compare-branch
  "Compare `candidate`'s committed program with `head` (a database value of the
  branch `target`) on their merge base (`merge-base-database`, Datahike's commit graph): the candidate's own changed
  identities (`seon.program/own-changed-identities`), the base, candidate and
  head digest maps and their `seon.program/three-way` classification. The one
  comparison merge and `my.program/diff` share. The candidate's head comes from one read of the store's branches,
  the caller's `branches` when it holds them. The base and branch values are
  materialized: the caller releases them. Refusals sit in `:c-id`, `:changed`
  or `:maps`."
  {:malli/schema [:function
                  [:=> [:cat :seon.store/store :seon.store/branch :seon.db/database-value :seon.store/branch] :map]
                  [:=> [:cat :seon.store/store :seon.cluster.registry/branches :seon.store/branch :seon.db/database-value
                        :seon.store/branch] :map]]}
  ([held-store candidate head target]
   (compare-branch held-store (registry/branches held-store) candidate head target))
  ([held-store branches candidate head _target]
   (let [c-id (registry/branch-commit-id {:seon.store/store held-store :seon.store/branch candidate
                                          :seon.cluster.registry/branches branches})]
     (if (:seon.error/at c-id) {:c-id c-id} (compare-commit held-store c-id head)))))

(def ^:private unmerged-sets
  "The `seon.program/three-way` sets a branch holds that main lacks."
  [:seon.program/added :seon.program/changed-on-branch :seon.program/retracted :seon.program/conflict])

(defn unmerged-count
  "How many declarations branch commit `c-id` changed that main `database` lacks (`compare-branch` against main's
  head); a refused comparison is thrown. A function of the two commits, so it is derived once per pair."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.store/store :seon.source/commit-id]
                  :seon.flow.actions/unmerged]}
  [database store c-id]
  (seon.cache/lookup ::unmerged [c-id (db/commit-id database)] (constantly 256)
    #(let [{three :three :as compared} (compare-commit store c-id database)]
       (try (if three
              (reduce + (map (fn [k] (count (get three k))) unmerged-sets))
              (let [refusal (some (fn [v] (when (:seon.error/at v) v))
                                  (concat [(:base compared) (:changed compared)] (:maps compared)))]
                (throw (ex-info (:seon.error/message refusal "Could not compare the branch with main.") refusal))))
            (finally (release! [(:base compared) (:branch compared)]))))))


(defn lint-refusal
  "The `:seon.source/lint-error` refusing `findings` (`lint-errors`), the bar a lane commit holds: clj-kondo errors in
  a file a merge would publish, each by file, position and type. The merge gate (`prepare-merge!`) and the done rule
  (`done-refusals`, through `publication`) refuse with it."
  {:malli/schema [:=> [:cat :seon.source/lint-findings] :seon.source/lint-error]}
  [findings]
  (seon.error.refusal/error :seon.source/lint-error
   {:seon.error/layer :seon.source/merge :seon.error/operation 'seon.cluster.source/lint-refusal
    :seon.error/message (str "clj-kondo reports an error in a file this merge would publish, which a lane commit refuses: "
                             (str/join "; " (map (fn [{:seon.source/keys [path] :seon.lint/keys [row col type message]}]
                                                   (str path ":" row ":" col " " type " " message))
                                                 findings))
                             ". Fix it on the branch.")
    :seon.source/path (:seon.source/path (first findings)) :seon.source/lint-findings findings}))

(defn- file-refusal
  "Why the files a merge writes, `proofs` (`proven-files`) under the checkout `root`, cannot publish: their clj-kondo
  errors (`lint-refusal`); nil when they publish clean."
  {:malli/schema [:=> [:cat [:vector :seon.source/proof] :seon.fn/root] [:or :nil :seon.source/lint-error]]}
  [proofs root]
  (let [lint (lint-errors root proofs)] (when (seq lint) (lint-refusal lint))))

(defn publication
  "What a merge of `branch`, whose own changes since its merge base are `changed`, onto `main` would publish, judged
  from what the branch alone decides: `:seon.source/merge-identities`, its whole change but an agent's own namespace
  (`merge-changes`), and under a checkout `root` `:seon.source/lint-refusal` when the files it writes over `main`
  (`replacement`, `proven-files`) hold a clj-kondo error (`lint-errors`, `lint-refusal`). Files that do not prove
  leave nothing to lint: whether they write is the merge's, judged against the checkout as root's merge sees it.
  The done rule's input (`done-refusals`). Cached by main's commit and the digests `branch` holds for `changed`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set [:maybe :seon.fn/root]]
                  [:or :seon.source/publication :seon.error/value]]}
  [main branch changed root]
  (seon.cache/lookup ::publication [(db/commit-id main) (held-digests branch changed) root] (constantly 1024)
    (fn []
     (let [delta (merge-changes branch main changed)
           tx (when (and (seq delta) (string? root) (nil? (root-refusal root))) (replacement branch main delta))
           merged (when (vector? tx) (db/with main {:tx-data tx}))
           proof (when (and merged (not (:seon.error/at merged))) (proven-files main merged delta root))
           lint (when (vector? proof) (lint-errors root proof))]
       (or (some #(when (:seon.error/at %) %) [tx merged])
           (cond-> {:seon.source/merge-identities delta} (seq lint) (assoc :seon.source/lint-refusal (lint-refusal lint))))))))

(defn- judged-merge!
  "The merge branch S of `source`'s head `head` holding `tx` (the candidate commit `c-id`'s whole change `delta`, root's
  facts squash and the unmerged entries of its conflicts) in one write, judged: the tests reaching what S changed,
  with task `task-sym`'s proofs (`seon.task/assigned-obligations` on S; a Prove step's product beside them), run on S
  (`seon.cluster/candidate-gate!`, each answered from its record at an unchanged reach digest; its isolated members
  over `files`), and S judged as the task's step is (`seon.context/judged-input` against `head`, `done-refusals`).
  The prepared merge `accept-merge!` takes; `refused`, a conflict or a file refusal, answered with S kept instead. A
  run that could not start retires S and is answered."
  {:malli/schema [:=> [:cat :seon.agent/context-source :seon.store/store :seon.db/database-value :seon.source/commit-id
                       :seon.task/sym :seon.store/transaction-data :seon.program/identity-set
                       [:maybe [:or :seon.program/three-way :seon.error/value]]
                       [:maybe [:vector :seon.source/proof]]]
                  [:or :seon.source/prepared :seon.program/three-way :seon.error/value]]}
  [source held-store head c-id task-sym tx delta refused files]
  (let [s (keyword (str "merge-" (id/id)))
        write! (fn [execution]
                 (let [connection (:seon.db/connection execution)
                       written (db/transact! connection {:tx-data tx})
                       owed (when (:db-after written) (task/assigned-obligations (db/db connection) task-sym))
                       failed (some #(when (:seon.error/at %) %) [written owed])]
                   (when failed (throw (ex-info (:seon.error/message failed) failed)))
                   ;; the writer re-derives a merged namespace's declarations: their tests run too
                   (into (into delta (fn/report-identities written))
                         (map #(vector :seon.test/sym %))
                         (into (set (:seon.task/proofs owed)) (when (= :prove (:seon.task/step owed)) (:seon.task/product owed))))))
        gate ((requiring-resolve 'seon.cluster/candidate-gate!) source held-store s write! (when-not refused files))
        failed (some #(when (:seon.error/at %) %) [(:seon.source/gate-run gate) (:seon.source/platform-run gate)])]
    (if failed
      (do (registry/retire-branch! {:seon.store/store held-store :seon.store/branch s}) failed)
      (let [judged-db (db/branch-database head s)
            observed (try ((requiring-resolve 'seon.context/judged-input) head judged-db task-sym nil)
                          (finally (release! [judged-db])))
            judged (:seon.source/judged observed)
            refusals (cond (:seon.error/at observed) [observed]
                           judged (done-refusals judged-db judged)
                           :else [(seon.error.refusal/error :seon.source/test-evidence-error
                                   {:seon.error/layer :seon.source/merge :seon.error/operation 'seon.cluster.source/prepare-merge!
                                    :seon.error/message "The merge branch recorded no run to judge."
                                    :seon.error/offending s})])
            red (into [] (comp (mapcat :seon.source/red-tests) (distinct)) refusals)
            platform-run (some (fn [{run :seon.test/run}] (if (vector? run) (second run) (:seon.test.run/id run)))
                               (:seon.test/results (:seon.source/platform-run gate)))]
        (if refused
          (cond-> (assoc refused :seon.source/candidate s) (seq red) (assoc :seon.source/red-tests red))
          (cond-> {:seon.source/candidate s :seon.source/judged-commit (db/commit-id judged-db)
                   :seon.test/passed? (:seon.test/passed? gate) :seon.source/tally (:seon.source/tally gate)
                   :seon.program/identities delta :seon.source/refusals refusals
                   :seon.db/basis-t (:max-tx head) :parents #{c-id}}
            (:seon.test.run/id judged) (assoc :seon.test.run/id (:seon.test.run/id judged))
            platform-run (assoc :seon.source/platform-run platform-run)
            (seq red) (assoc :seon.source/red-tests red)))))))

(defn prepare-merge!
  "Root's one merge of `candidate` onto `source`'s head H, prepared and judged: the candidate compared with H on
  their merge base (`compare-branch`), and a fresh merge branch S of H holding, in ONE write, the candidate's whole
  change since the merge base Δ (`seon.program/three-way`'s changes on the branch, additions and retractions; an
  agent's own namespace aside, `agent-owned`), the `:merging` transaction data (root's squash of the source's facts,
  `seon.merge/merge-tx`) and, for each conflict, an unmerged entry (`unmerged-tx`) beside main's version, which S
  keeps (git's ours, the target); the tests reaching what S changed run there and S is judged as task `task-sym`'s
  step is judged (`judged-merge!`). Answers the prepared merge `accept-merge!` takes: S, its judged commit, Δ, the
  refusals (empty only for acceptance) with their red tests, and H's basis. A conflict, or with the `:root` option
  (the checkout) a file write-back would produce that does not prove or holds a clj-kondo error (`proven-files`,
  `file-refusal`, proven over the merged value before any test runs), answers its refusal with S kept
  (`:seon.source/candidate`, MERGE-STEP)."
  {:malli/schema [:function
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.store/branch :seon.task/sym]
                   [:or :seon.source/prepared :seon.program/three-way :seon.error/value]]
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.store/branch :seon.task/sym
                        [:map [:root {:optional true} :seon.fn/root]
                         [:merging {:optional true} :seon.store/transaction-data]]]
                   [:or :seon.source/prepared :seon.program/three-way :seon.source/write-back-error :seon.error/value]]]}
  ([source held-store candidate task-sym] (prepare-merge! source held-store candidate task-sym {}))
  ([source held-store candidate task-sym {:keys [root merging]}]
   (let [head (db/db (:seon.db/connection source))
         {:keys [c-id base branch changed maps three] open :unmerged}
         (compare-branch held-store candidate head (get-in head [:config :branch]))]
     (if (:seon.error/at c-id)
       c-id
       (try
         (let [delta (when three (into (:seon.program/changed-on-branch three)
                                       (concat (:seon.program/added three) (:seon.program/retracted three))))
               conflicts (:seon.program/conflict three)
               ;; main wins every conflict: S holds main's version, its unmerged entry the base's and the source's
               ;; (MERGE-STEP 6)
               recorded (when (seq conflicts)
                          (unmerged-tx conflicts (:seon.program/conflict-digests three)
                                       :seon.program/head-digest :seon.program/branch-digest
                                       (into {} (map (juxt entry-identity :seon.unmerged/base-commit))
                                             (filter :seon.unmerged/base-commit open))
                                       (db/commit-id base) c-id))
               tx (when delta (if (seq delta) (replacement branch head delta) []))
               root-error (when (and root (seq tx)) (root-refusal root))
               ;; the files write-back would produce over the merged value: the bar a lane commit holds (bin/lane)
               proof (when (and root (nil? root-error) (vector? tx) (seq tx))
                       (let [merged (db/with head {:tx-data tx})]
                         (if (:seon.error/at merged) merged (proven-files head merged delta root))))
               published (if (vector? proof) (file-refusal proof root) proof)]
           (cond
             root-error root-error
             (not (set? changed)) changed
             (not three) (some #(when (:seon.error/at %) %) maps)
             (not (vector? tx)) tx
             :else (judged-merge! source held-store head c-id task-sym (-> (vec merging) (into tx) (into recorded)) delta
                                  (cond (seq conflicts) three published published)
                                  (when (vector? proof) proof))))
         (finally (release! [base branch])))))))

(defn- red-test-verdicts
  "Each of `not-green` (the judged tests with no green result) with the run that recorded its result and its
  verdict: `:unknown` with no executed failure, else `:red`. A test with no recorded result names `run-id`, the
  judged run."
  {:malli/schema [:=> [:cat :seon.test.run/id [:vector [:or :seon.test/result :seon.test/result-unknown]]
                       [:sequential :seon.test/sym]]
                  [:vector :seon.source/red-test]]}
  [run-id results not-green]
  (let [by-symbol (into {} (map (juxt #(or (:seon.test/sym %) (:seon.test/error-test-symbol %)) identity)) results)
        red? (requiring-resolve 'seon.test.runner/red-result?)]
    (into [] (map (fn [test-symbol]
                    (let [{run :seon.test/run :as result} (by-symbol test-symbol)
                          id (or (if (vector? run) (second run) (:seon.test.run/id run)) run-id)]
                      {:seon.test/sym test-symbol :seon.test.run/id id
                       :seon.source/verdict (if (and result (red? result)) :red :unknown)})))
          (sort (distinct not-green)))))

(defn- red-test-refusal
  "The `:seon.source/red-test-error` of a candidate whose judged results left `red-tests` not green, with the run
  and branch `evidence` names (its layer, operation and message, when it names them, over the merge's). A red the
  merge base holds red is not judged here: neither judge counts it (`red-at-merge-base`)."
  {:malli/schema [:=> [:cat [:map [:seon.test.run/branch :seon.store/branch] [:seon.source/refused-test-run :seon.test.run/id]]
                       :seon.source/red-tests]
                  :seon.source/red-test-error]}
  [evidence red-tests]
  (seon.error.refusal/error
   :seon.source/red-test-error
   (merge {:seon.error/layer :seon.source/merge :seon.error/operation 'seon.cluster.source/accept-merge!
           :seon.error/message "A test of the named run is not green; a merge commits only when every test is."}
          evidence
          {:seon.source/red-tests red-tests})))

(defn- green-result?
  "Whether `result` is an executed recorded result with a passing assertion and no failure or error."
  {:malli/schema [:=> [:cat [:or :seon.test/result :seon.test/result-unknown]] :boolean]}
  [result]
  (boolean (and (not (:seon.error/at result)) (pos? (:seon.test/pass-count result 0))
                (zero? (:seon.test/fail-count result 0)) (zero? (:seon.test/error-count result 0)))))

(def ^:private incomplete-findings
  "The `seon.fn/contract-findings` a merge refuses: no contract, or a slot accepting anything that records no
  polymorphic-boundary justification."
  #{:seon.fn.contract.finding/missing-spec :seon.fn.contract.finding/any :seon.fn.contract.finding/some})

(defn contract-gaps
  "What keeps the functions among `identities` that `database` holds from a complete `:malli/schema`, private ones
  included, judged by the one function-contract rule (`seon.fn/contract-findings`): each missing contract and each
  unjustified `:any` or `:some` slot, with its position. Macros, host-bound declarations and value definitions
  (`seon.fn/value-definition?`) carry no arity contract. Throws a refused read."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set] [:vector :seon.fn.contract/finding-row]]}
  [database identities]
  (let [judged (into #{} (keep (fn [[a f]]
                                 (when (= :seon.fn/sym a)
                                   (let [entity (db/pull database [:db/id :seon.fn/macro? :seon.fn/host-bound? :seon.fn/arglists] [a f])]
                                     (when (and (:db/id entity) (not (:seon.fn/macro? entity))
                                                (not (:seon.fn/host-bound? entity))
                                                (not (fn/value-definition? (:seon.fn/arglists entity))))
                                       f)))))
                     identities)
        findings (if (seq judged) (fn/contract-findings database {:seon.fn.contract/selected judged}) [])]
    (when-not (vector? findings) (throw (ex-info (str (:seon.error/message findings)) findings)))
    (filterv (comp incomplete-findings :seon.fn.contract/finding) findings)))

(defn gap-symbols
  "The functions `gaps` (`contract-gaps`) names, once each, ordered by symbol."
  {:malli/schema [:=> [:cat [:vector :seon.fn.contract/finding-row]] [:vector :seon.fn/sym]]}
  [gaps]
  (into [] (comp (map :seon.fn/sym) (distinct)) (sort-by (comp str :seon.fn/sym) gaps)))

(defn uncontracted
  "The functions among `identities` that `database` holds with no complete `:malli/schema` (`contract-gaps`), by
  symbol: what a merge refuses (`accept-merge!`) and what keeps an implement step from done
  (`seon.context/step-status`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set] [:vector :seon.fn/sym]]}
  [database identities]
  (gap-symbols (contract-gaps database identities)))

(defn- uncontracted-refusal
  "The refusal of the functions `gaps` (`contract-gaps`) names, with the run, branch, layer and operation
  `evidence` names."
  {:malli/schema [:=> [:cat [:map [:seon.test.run/branch :seon.store/branch] [:seon.source/refused-test-run :seon.test.run/id]
                             [:seon.error/layer :qualified-keyword] [:seon.error/operation :qualified-symbol]]
                       :seon.source/contract-gaps]
                  :seon.source/uncontracted-error]}
  [evidence gaps]
  (let [uncontracted (gap-symbols gaps)]
    (seon.error.refusal/error :seon.source/uncontracted-error
     (assoc evidence
            :seon.error/message (str "Every function a merge carries declares a complete :malli/schema, private ones included, "
                                     "with no unjustified :any or :some slot: :seon.source/uncontracted names those that do not and "
                                     ":seon.source/contract-gaps each gap's position. Declare their data shapes as registered "
                                     "schemas or named predicates, contract them on the candidate branch, run their tests and "
                                     "request the merge again.")
            :seon.error/offending uncontracted :seon.source/uncontracted uncontracted :seon.source/contract-gaps gaps))))

(def ^:private uncovered-message
  "What a refusal of a function no green test reaches says (`uncovered-functions`)."
  "A replaced function reaches no green test of the named run.")

(defn- uncovered-functions
  "The functions among `identities` that `database` holds which no test of `covering` reaches, each with the tests that
  do reach it (its seed's contribution to the member fold, `member-contributions`): what a merge refuses as
  uncovered, and what would cover it once green. The fold leaves out the tests declared long. A refused fold answers
  that refusal, never an uncovered function."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set [:set :seon.test/sym]]
                  [:or [:map-of :seon.fn/sym [:vector :seon.test/sym]] :seon.error/value]]}
  [database identities covering]
  (let [seeds (into #{} (filter (fn [[a]] (#{:seon.fn/sym :seon.test/sym :seon.ns/name :seon.schema/key} a))) identities)
        folded (member-contributions database seeds ((requiring-resolve 'seon.test/selection-revisions) database))]
    (if (:seon.error/at folded) folded
      (reduce (fn [uncovered [a f :as identity]]
                (let [tests (get-in folded [::contributions identity :seon.fn/tests])]
                  (if (and (= :seon.fn/sym a) (:db/id (db/pull database [:db/id] identity)) (not-any? covering tests))
                    (assoc uncovered f (vec (sort tests)))
                    uncovered)))
              {} identities))))

(defn- uncovered-refusal
  "The `:seon.source/test-evidence-error` refusing `uncovered` (`uncovered-functions`) with `evidence`'s members: the
  functions as `:seon.error/offending`, and each red test of `results` reaching one as `:seon.source/red-tests`
  (`red-test-verdicts`), the test whose green would cover it."
  {:malli/schema [:=> [:cat :map [:map-of :seon.fn/sym [:vector :seon.test/sym]] [:vector :map]]
                  :seon.source/test-evidence-error]}
  [evidence uncovered results]
  (let [red? (requiring-resolve 'seon.test.runner/red-result?)
        red (into #{} (comp (filter red?) (map #(or (:seon.test/sym %) (:seon.test/error-test-symbol %)))) results)
        reaching (vec (sort (filter red (into #{} (mapcat val) uncovered))))]
    (seon.error.refusal/error :seon.source/test-evidence-error
     (cond-> (assoc evidence :seon.error/message uncovered-message :seon.error/offending (vec (sort (keys uncovered))))
       (seq reaching) (assoc :seon.source/red-tests
                             (red-test-verdicts (:seon.source/refused-test-run evidence) results reaching))))))

(defn versions
  "`entry`'s base, ours and theirs, each a reference read where it lives (`:seon.ref/at`: the merge base commit,
  branch `database`'s head, the source commit) and resolved at its code (`seon.ref/references`)."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.unmerged/shown] [:map-of :keyword :map]]}
  [database {n :seon.unmerged/name base :seon.unmerged/base-commit theirs :seon.unmerged/theirs-commit}]
  (let [read (fn [at] (first (seon.ref/references database {:seon.ref/text ""
                                                              :seon.ref/names #{{:seon.ref/name n :seon.ref/at at :seon.ref/level :code}}})))]
    (into {} (keep (fn [[k at]] (when-let [found (when at (read at))] [k found])))
          [[:base base] [:ours (get-in database [:config :branch])] [:theirs theirs]])))

(defn unmerged-refusal
  "The `:seon.source/conflict-error` refusing the unresolved unmerged `entries` (`unmerged`) of branch `database`, as
  the done rule refuses them, with `members` (the judged run's evidence), each entry's three versions as resolved
  references (`versions`) and how to resolve them."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.unmerged/entries :map] :seon.source/conflict-error]}
  [database entries members]
  (seon.error.refusal/error :seon.source/conflict-error
   (assoc members
          :seon.error/message (str "Both sides of a merge changed " (str/join ", " (map :seon.unmerged/name entries))
                                   " since their merge base; this branch holds ours (on a merge branch, main's). Resolve"
                                   " each: keep ours with my.program/resolve!, or define theirs or a combination.")
          :seon.program/conflict (into #{} (map :seon.unmerged/name) entries)
          :seon.unmerged/entries (mapv #(assoc % :seon.unmerged/versions (versions database %)) entries))))

(defn red-at-merge-base
  "Of the red results among `results` other than `exempt`'s, the tests red at `branch`'s merge base `base-commit`,
  as recorded at their reach there, on the base or on `branch` (`seon.test/held-red`, the question
  `seon.test/fork-point-red` answers for the merge, which the attempt's test step runs when no result is held): the
  done rule's `:seon.source/red-on-target`. A red with no result at the base is the change's. Reads only."
  {:malli/schema [:=> [:cat :seon.db/database-value [:maybe :seon.source/commit-id] :seon.source/results [:set :seon.test/sym]]
                  [:or [:set :seon.test/sym] :seon.error/value]]}
  [branch base-commit results exempt]
  (let [red? (requiring-resolve 'seon.test.runner/red-result?)
        reds (into [] (comp (filter red?) (map :seon.test/sym) (remove exempt)) results)
        base (when (and (seq reds) (uuid? base-commit)) (db/commit-database branch base-commit))]
    (if-not base
      #{}
      (try (let [held ((requiring-resolve 'seon.test/held-red) base branch reds)]
             (if (:seon.error/at held) held (into #{} (keep (fn [[k red]] (when red k))) held)))
           (finally (release! [base]))))))

(defn done-refusals
  "Every refusal the done rule gives a task over the recorded results in `judged`, the same for every task: each
  of its `:seon.task/proofs` and other `:seon.source/members` (the next task's proofs aside) without an executed
  current result (absent, or a result that never started: unknown evidence, never red or green, as the merge judges
  it; a task with neither proofs nor next proofs never finishes: a Prove step's work may be its product alone), each
  proof whose executed result is not green; each other member red here that is not red at the merge base (`:seon.source/red-on-target`, `seon.test/fork-point-red`'s
  question, as the merge asks it: a red with no result there is the change's), and each red test defined or changed
  on the branch (in `:seon.program/identities`, `:seon.source/stray-red-error`), unless it is one of the
  `:seon.source/next-proofs` (none on the last task); and every function of the identities `branch` holds with a
  contract gap (`contract-gaps`); and what the branch alone decides of its merge (`publication`): its
  `:seon.source/lint-refusal` (a file its merge writes holds a clj-kondo error) and, on the last task (no next
  proofs), each function of its `:seon.source/merge-identities` no green test reaches (`uncovered-functions`), as
  the merge refuses them, and every unresolved unmerged entry the branch holds (`unmerged-refusal`), as git refuses a
  commit while a path is unmerged. Mid-chain a function no test reaches is not refused: the next task's proofs cover
  it. Empty only for done. Runs no test; reads `branch` for contracts and coverage only.

  ## Example

  ```clojure
  (seon.cluster.source/done-refusals branch judged)
  ```"
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.source/judged] :seon.source/refusals]}
  [branch {members :seon.source/members results :seon.source/results proofs :seon.task/proofs
           next-proofs :seon.source/next-proofs on-base :seon.source/red-on-target delta :seon.program/identities
           lint :seon.source/lint-refusal merging :seon.source/merge-identities deferred :seon.source/deferred
           run-id :seon.test.run/id s :seon.test.run/branch}]
  (let [evidence {:seon.test.run/branch s :seon.source/refused-test-run run-id
                  :seon.error/layer :seon.source/done :seon.error/operation 'seon.cluster.source/done-refusals}
        red? (requiring-resolve 'seon.test.runner/red-result?)
        by-sym (into {} (map (juxt #(or (:seon.test/sym %) (:seon.test/error-test-symbol %)) identity)) results)
        on-base (set on-base)
        executed (fn [test-symbol] (let [result (by-sym test-symbol)] (and result (not (:seon.error/at result)))))
        ;; every member needs a current executed result: none is unknown, never green
        ;; a deferred member runs only on the merge's platform host, which answers it there (`merge-members`)
        unknown (vec (sort (remove executed (into (set proofs) (remove (into (set next-proofs) deferred)) members))))
        red-proofs (filterv #(and (executed %) (not (green-result? (by-sym %)))) proofs)
        others (into #{} (filter #(some-> (by-sym %) red?)) (remove (into (set proofs) next-proofs) members))
        stray (filterv #(delta [:seon.test/sym %]) others)
        regressed (filterv #(not (or (on-base %) (delta [:seon.test/sym %]))) others)
        verdicts (fn [tests] (red-test-verdicts run-id results tests))
        gaps (contract-gaps branch delta)
        green (into #{} (filter #(some-> (by-sym %) green-result?)) members)
        uncovered (when (and merging (empty? next-proofs)) (uncovered-functions branch merging green))
        open (unmerged branch)]
    (cond-> []
      (seq open) (conj (unmerged-refusal branch open evidence))
      (or (empty? (into (set proofs) next-proofs)) (seq unknown))
      (conj (seon.error.refusal/error :seon.source/test-evidence-error
             (assoc evidence :seon.error/message (str "Every proof and every other member needs a current executed result; a test with none"
                                                        " is unknown, not green, and a task with neither proofs nor next proofs never finishes.")
                    :seon.error/offending unknown)))
      (seq (into red-proofs regressed))
      (conj (red-test-refusal (assoc evidence :seon.error/message "A proof, or a test not red at the merge base, is not green here.")
                              (verdicts (into red-proofs regressed))))
      (seq stray)
      (conj (seon.error.refusal/error :seon.source/stray-red-error
             (assoc evidence :seon.error/message "A test defined or changed on the branch is red and is not one of the next task's proofs."
                    :seon.source/red-tests (verdicts stray))))
      (seq gaps) (conj (uncontracted-refusal evidence gaps))
      lint (conj lint)
      (:seon.error/at uncovered) (conj uncovered)
      (and (seq uncovered) (not (:seon.error/at uncovered))) (conj (uncovered-refusal evidence uncovered results)))))

(defn accept-merge!
  "Merge the prepared merge `prepared` (`prepare-merge!`) into `source` when its judgment refused nothing, else
  answer its first refusal: ONE transaction on the head, the candidate's whole change Δ replaced from the merge
  branch's judged commit, after the `merging` transaction data and metadata (root's merge of the assignment's facts,
  `seon.merge/merge!`), whose refs those declarations may name; its parents add the candidate's commit and that judged
  commit, so Datahike's commit graph makes the value merged the next comparison's merge base (`merge-base`). It
  carries Datahike's basis fence (`:datahike/expected-basis-t`, the head the judgment read): when main moved since,
  the writer refuses with nothing written (`:seon.db/transaction-refused-error`) and the next pass prepares again.
  Answers the accepted merge `write-back!` takes: its identities, the commit before it, its merge commit and the run.

  ## Example

  ```clojure
  (accept-merge! source store prepared {:tx-data [] :tx-meta {}})
  ```"
  {:malli/schema [:function
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.source/prepared]
                   [:or :seon.source/accepted :seon.error/value]]
                  [:=> [:cat :seon.agent/context-source :seon.store/store :seon.source/prepared :seon.source/merging]
                   [:or :seon.source/accepted :seon.error/value]]]}
  ([source held-store prepared] (accept-merge! source held-store prepared {:tx-data []}))
  ([source held-store {e-id :seon.source/judged-commit run-id :seon.test.run/id delta :seon.program/identities
                       refusals :seon.source/refusals basis :seon.db/basis-t merged :parents}
    {merging :tx-data meta :tx-meta}]
   (if (seq refusals)
     (first refusals)
     (let [connection (:seon.db/connection source)
           tested (commit-database held-store e-id)]
       (try
         (let [tx (if (seq delta) (replacement tested (db/db connection) delta) [])
               report (when (vector? tx)
                        (db/transact! connection (cond-> {:tx-data (into (vec merging) tx) :parents (conj merged e-id)
                                                          :datahike/expected-basis-t basis}
                                                   meta (assoc :tx-meta meta))))]
           (cond
             (not (vector? tx)) tx
             (:db-after report)
             (cond-> {:seon.program/identities delta
                      ;; the head this commit merged into is its parent other than the merged ones: Datahike's own
                      ;; lineage, never the report's uncommitted `:db-before`
                      :seon.source/base-commit-id (first (remove (conj merged e-id) (d/parent-commit-ids (:db-after report))))
                      :seon.source/commit-id (db/commit-id (:db-after report))}
               run-id (assoc :seon.test.run/id run-id))
             :else report))
         (finally (release! [tested])))))))

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

(defn stage!
  "Write proven `candidate` bytes beside the file at `path`: the staged file's path, which a rename installs."
  {:malli/schema [:=> [:cat :string :seon.source/file-bytes] :string]}
  [path candidate]
  (let [file (io/file path)
        _ (io/make-parents file)
        staged (java.io.File/createTempFile ".write-back-" ".clj" (.getParentFile file))]
    (try
      (Files/write (.toPath staged) ^bytes candidate (make-array java.nio.file.OpenOption 0))
      (.getPath staged)
      (catch Exception failure
        ;; a partial write is no stage: its file goes, the failure stays the caller's
        (Files/deleteIfExists (.toPath staged))
        (throw failure)))))

(defn- install-staged!
  "Rename each staged file onto its path, atomically per file."
  {:malli/schema [:=> [:cat [:sequential [:tuple :string :string]]] :nil]}
  [staged-paths]
  (doseq [[staged path] staged-paths]
    (Files/move (.toPath (io/file staged)) (.toPath (io/file path))
                (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                                                      java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))

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

(defn- committed-bytes
  "The bytes of `path` in the checkout `root`'s git HEAD, the committed tree a merge is judged against; nil when
  HEAD holds no such file. A lane's uncommitted edit of the working tree is never read here: publication waits for
  it (`waiting-paths`). Throws when `root` is no git checkout."
  {:malli/schema [:=> [:cat :seon.fn/root :seon.source/path] [:or :nil :seon.source/file-bytes]]}
  [root path]
  (let [run (fn [& args]
              (let [process (.start (doto (ProcessBuilder. ^java.util.List (into ["git" "-C" root] args))
                                      (.redirectErrorStream false)))
                    out (.readAllBytes (.getInputStream process))
                    err (String. (.readAllBytes (.getErrorStream process)) "UTF-8")]
                (when-not (zero? (.waitFor process))
                  (throw (ex-info (str "git " (first args) " failed in the checkout: " err)
                                  {:seon.error/message (str "git " (first args) " failed in the checkout.")
                                   :seon.fn/root root :seon.source/path path})))
                out))]
    (when (pos? (alength ^bytes (run "ls-tree" "HEAD" "--" path)))
      (run "show" (str "HEAD:" path)))))

(defn- definition-order
  "`changes` with each addition (a row with no before-state) placed after the additions it calls
  (`:seon.fn/calls`), as Clojure reads a file: defined before use; otherwise in their given order."
  {:malli/schema [:=> [:cat :seon.source/proof-rows] :seon.source/proof-rows]}
  [changes]
  (let [added (filterv (fn [[_ was now]] (and now (not was))) changes)
        by-name (into {} (map (fn [[[_ declared] :as change]] [declared change])) added)
        visit (fn visit [[order seen] [_ _ now :as change]]
                (if (seen change)
                  [order seen]
                  (let [[order seen] (reduce visit [order (conj seen change)] (keep by-name (:seon.fn/calls now)))]
                    [(conj order change) seen])))]
    (into (first (reduce visit [[] #{}] added)) (remove (set added)) changes)))

(defn- proven-file
  "The candidate bytes that write the `named` declarations as `after` holds them over
  their one file under `root` as git HEAD holds it (`committed-bytes`), proven by the ordinary analyzer to
  declare the file's other declarations unchanged and exactly `after`'s rows of
  them (a new file's namespace among them); else the refusal. A file changed since `before` recorded it
  (a sibling change landed) is read again: each written declaration must still
  hold its `before` digest there, else the write-back is a `:conflict`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set :seon.fn/root]
                  [:or :seon.source/proof :seon.source/write-back-error]]}
  [before after named root]
  (let [pull (fn [database identity]
               (let [entity (db/pull database [:db/id :seon.fn/sym :seon.test/sym :seon.ns/name :seon.fn/form-span :seon.fn/source
                                            :seon.test/source :seon.ns/source :seon.program/definition-digest :seon.fn/calls
                                            {:seon.fn/file [:seon.fn.file/relative-path :seon.fn.file/relative-root
                                                            :seon.fn.file/digest]}]
                                  identity)]
                 (when (:db/id entity) entity)))
        record (fn [identities] (vec (for [identity (sort-by str identities)] [identity (pull before identity) (pull after identity)])))
        source-of #(or (:seon.fn/source %) (:seon.test/source %) (:seon.ns/source %))
        file-of (fn [[[_ declared] was _]]
                  (cond was (:seon.fn/file was)
                        (qualified-symbol? declared) (namespace-path before (symbol (namespace declared)))))
        files (into #{} (map file-of) (record named))
        ;; A namespace main never published has no file: it starts as empty bytes, then its `ns` form,
        ;; so its namespace is written with the declarations named.
        fresh? (and (= #{nil} files) (every? (comp nil? second) (record named)))
        namespace-identity [:seon.ns/name (identity-namespace (first (sort-by str named)))]
        identities (vec (sort-by str (cond-> (set named) fresh? (conj namespace-identity))))
        recorded (record identities)
        ns-source (when fresh? (:seon.ns/source (pull after namespace-identity)))
        {path :seon.fn.file/relative-path recorded-digest :seon.fn.file/digest
         relative-root :seon.fn.file/relative-root} (if fresh? {:seon.fn.file/relative-path (new-path identities)} (first files))
        relative-root (if fresh? (first (str/split path #"/")) relative-root)
        base-bytes (cond fresh? (some-> ns-source (str "\n") (.getBytes "UTF-8"))
                         (and (= 1 (count files)) path) (committed-bytes root (str path)))
        base-digest (if fresh? (sha-256 (byte-array 0)) (some-> base-bytes sha-256))
        base-text (some-> ^bytes base-bytes (String. "UTF-8"))
        ;; The file's declarations now: as `before` recorded them, or read again when the file changed since.
        ours (when (and base-bytes (not fresh?) (not= recorded-digest base-digest)) (file-rows before root relative-root path base-text))
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
        ;; Additions first, each after the additions it calls: one inserted at the anchor precedes the
        ;; replacement starting there.
        edits (fn [] (vec (for [[_ was now] (sort-by (fn [[_ was]] (if was 1 0)) (definition-order changes))
                                :when (not (and fresh? (:seon.ns/name now)))]
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
      (or (not= 1 (count files)) (nil? path) (and fresh? (nil? ns-source))
          (some (fn [[_ _ now]] (and now (nil? (source-of now)))) changes))
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
            ;; Every declaration the file holds after the write: its others as they are now, the written ones as
            ;; `after` holds them. An unchanged file's others are `after`'s: a written namespace row re-derives the
            ;; declarations whose resolver context it changes (`seon.program/definition-digest`).
            expected (into {} (remove (comp #{:seon.program/absent} val))
                           (merge (if ours (update-vals ours :seon.program/definition-digest) (file-digests after path))
                                  (held-digests after (set identities))))
            actual (when-not (:seon.fn/index-refused reindexed) (update-vals reindexed :seon.program/definition-digest))
            differing (vec (sort-by str (filter #(not= (get expected %) (get actual %))
                                                (into (set (keys expected)) (keys actual)))))]
        (cond
          (not actual)
          (refuse :not-analyzable
                  (str "The candidate file does not analyze cleanly; its namespace form may lack a require the accepted declarations use: "
                       (str/join "; " (map (fn [{::analyzer/keys [row col type message]}] (str path ":" row ":" col " " type " " message))
                                           (:seon.fn/findings reindexed)))
                       ". Require the namespace, or unmap the declaration (my.program/ns-unmap!), and request the merge again.")
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
        base-bytes (committed-bytes root path)
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

(defn- filed-declaration
  "The declaration a task file holds for the task `sym` as `database` holds it: its declaration
  (`seon.task/task-declaration`), nil when absent or runtime data (`:seon.task/created-by`, a chain's among them),
  which no task file declares."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.task/sym] [:or :nil :seon.task/declaration :seon.db/invalid-read-error]]}
  [database sym]
  (let [handle (task/task-handle (symbol (namespace sym)) (name sym))]
    (when-not (:seon.task/created-by (db/pull database [:seon.task/created-by] [:seon.task/id handle]))
      (task/task-declaration database handle))))

(defn- task-path
  "The task file the task `sym` is declared in: its namespace's file under `resources/seon/tasks/`."
  {:malli/schema [:=> [:cat :seon.task/sym] :seon.source/path]}
  [sym]
  (str "resources/seon/tasks/" (namespace sym) ".edn"))

(defn- task-entry-node
  "`declaration` as a task file entry in the layout of `old`, the entry it
  replaces or the neighbour it follows (`relaid`): members in `old`'s order,
  then in `order`'s (`seon.task/declaration-members`), and namespaced as `old` is."
  {:malli/schema [:=> [:cat [:fn rewrite-clj.node/node?] :seon.task/declaration [:vector :qualified-keyword]] [:fn rewrite-clj.node/node?]]}
  [old declaration order]
  (let [[qualifier inner] (if (= :namespaced-map (node/tag old)) (node/children old) [nil old])
        prefix (some-> qualifier node/string (subs 1))
        members (dissoc declaration :seon.task/ns)
        order (distinct (concat (keep #(when (keyword? %) %) (map node/sexpr (remove node/whitespace-or-comment? (node/children inner))))
                                order))
        value (node/coerce (apply array-map (mapcat (fn [k] (when (contains? members k)
                                                              [(if (= prefix (namespace k)) (keyword (name k)) k) (members k)]))
                                                    order)))
        laid (relaid inner value)]
    (if qualifier (node/replace-children old [qualifier laid]) laid)))

(defn- task-file-text
  "`text`, one task file's vector of entries, with each `[name declaration]` of
  `edits` applied by rewrite-clj: the entry named `name` replaced
  (`task-entry-node`), removed with the whitespace before it when
  `declaration` is nil, or appended after the last entry, separated as the
  last two are, its members in `projection`'s declaration order. Every other node keeps its text."
  {:malli/schema [:=> [:cat :seon.schema/projection :string [:sequential [:tuple :seon.task/name [:maybe :seon.task/declaration]]]] :string]}
  [projection text edits]
  (let [outer (z/find (z/of-string text) z/right #(= :vector (z/tag %)))
        order (mapv first (task/declaration-members projection))
        edit (fn [children [task-name declaration]]
               (let [significant (vec (keep-indexed (fn [i n] (when-not (node/whitespace-or-comment? n) i)) children))
                     at (some #(when (= task-name (:seon.task/name (node/sexpr (nth children %)))) %) significant)
                     before (last (filter #(< % (or at 0)) significant))
                     splice (fn [from to nodes] (into (into (subvec children 0 from) nodes) (subvec children to)))]
                 (cond
                   (and at declaration) (assoc children at (task-entry-node (nth children at) declaration order))
                   (and at before) (splice (inc before) (inc at) [])
                   at (splice at (or (some #(when (> % at) %) significant) (count children)) [])
                   (nil? declaration) children
                   (empty? significant) (splice 0 0 [(task-entry-node (node/coerce {}) declaration order)])
                   :else (let [end (peek significant)
                               gap (if (< 1 (count significant))
                                     (subvec children (inc (peek (pop significant))) end)
                                     [(node/newlines 1) (node/spaces 1)])]
                           (splice (inc end) (inc end) (conj gap (task-entry-node (nth children end) declaration order)))))))
        children (reduce edit (vec (node/children (z/node outer))) edits)]
    (z/root-string (z/replace outer (node/replace-children (z/node outer) children)))))

(defn- proven-task-file
  "The candidate bytes of the task file `identities` (tasks `[:seon.task/sym
  ns/name]` of one namespace) are declared in, under `root`, with each entry as
  `after` declares it: replaced, removed or appended by `task-file-text`. A file
  entry declaring neither `before`'s nor `after`'s value is a `:conflict`.
  A namespace with no task file yet (its tasks lived only in the database)
  starts from an empty vector, as `proven-file` starts a fresh namespace's file.
  Proven by reading the candidate back through ingestion's reader
  (`seon.task/file-declarations`): each written entry declares `after`'s value
  or is absent with it, and every other entry reads as it did."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set :seon.fn/root]
                  [:or :seon.source/proof :seon.source/write-back-error]]}
  [before after identities root]
  (let [path (task-path (second (first identities)))
        held (committed-bytes root path)
        fresh? (nil? held)
        base-bytes (or held (.getBytes "[]" "UTF-8"))
        base-text (String. ^bytes base-bytes "UTF-8")
        projection (db/carried-projection after)
        rows (vec (for [[_ sym :as identity] (sort-by str identities)]
                    [identity (filed-declaration before sym) (filed-declaration after sym)]))
        ;; the file's declarations by symbol, as ingestion reads them; nil when it refuses the text
        read-file #(try (into {} (map (juxt :seon.task/sym (partial task/declaration projection)))
                              (task/file-declarations projection path %))
                        (catch clojure.lang.ExceptionInfo failure
                          (if (:seon.task/refused (ex-data failure)) nil (throw failure))))
        digest-of #(if % (id/digest 64 [%]) :seon.program/absent)
        ours (read-file base-text)
        refuse (fn [reason message evidence] (write-back-refusal reason message (assoc evidence :seon.source/path path)))]
    (cond
      (nil? ours) (refuse :not-analyzable "The task file as it is now does not read as task declarations." {})
      :else
      (let [conflicting (into {} (keep (fn [[[_ sym :as identity] was now]]
                                         (let [file-held (get ours sym)]
                                           (when-not (or (= was file-held) (= now file-held))
                                             [identity {:seon.program/base-digest (digest-of was)
                                                        :seon.program/branch-digest (digest-of now)
                                                        :seon.program/head-digest (digest-of file-held)}]))))
                              rows)
            written (map (comp second first) rows)
            candidate (when (empty? conflicting)
                        (task-file-text projection base-text (mapv (fn [[[_ sym] _ now]] [(name sym) now]) rows)))
            read (some-> candidate read-file)
            differing (when read
                        (into (vec (keep (fn [[[_ sym :as identity] _ now]] (when-not (= now (get read sym)) identity)) rows))
                              (when (not= (apply dissoc ours written) (apply dissoc read written)) [:seon.source/other-entries])))]
        (cond
          (seq conflicting)
          (refuse :conflict "The file changed these tasks since your branch's base, and your branch changed them too."
                  {:seon.program/conflict-digests conflicting})
          (nil? read) (refuse :not-analyzable "The candidate task file does not read as task declarations." {})
          (seq differing)
          (refuse :not-equivalent "The candidate task file does not declare exactly the merged tasks."
                  {:seon.error/offending differing})
          :else {:seon.source/path path :seon.source/base-digest (sha-256 (if fresh? (byte-array 0) base-bytes))
                 :seon.source/candidate-bytes (.getBytes ^String candidate "UTF-8") :seon.source/proof-rows rows})))))

(defn- identity-namespace
  "The namespace symbol `identity` belongs to: a namespace's own name, else its symbol's namespace."
  {:malli/schema [:=> [:cat :seon.program/declaration-identity] [:maybe :symbol]]}
  [[attribute declared]]
  (cond (= :seon.ns/name attribute) declared
        (qualified-symbol? declared) (symbol (namespace declared))))

(defn- new-path
  "The relative path publication creates for the namespace `identities` (one namespace's changes) declare
  anew: `<root>/<munged name>.clj`, `test` for a namespace holding only deftests or named `*-test`, else `src`."
  {:malli/schema [:=> [:cat [:sequential :seon.program/declaration-identity]] :seon.source/path]}
  [identities]
  (let [namespace-name (identity-namespace (first identities))
        tests-only? (every? (fn [[attribute]] (#{:seon.test/sym :seon.ns/name} attribute)) identities)
        root (if (or tests-only? (str/ends-with? (str namespace-name) "-test")) "test" "src")]
    (str root "/" (-> (str namespace-name) (str/replace "-" "_") (str/replace "." "/")) ".clj")))

(defn- change-path
  "The relative path of the one file `identity`'s change is written into: a
  task's file (`task-path`), a schema's resource (`schema-path`), the declaration's own file as `before`
  recorded it, else (an addition) its namespace's file; for a namespace `before` records no file for, the
  file publication creates (`new-path` over the merge's `identities` in that namespace); nil when the
  identity names no namespace."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.program/identity-set :seon.program/declaration-identity] [:maybe :seon.source/path]]}
  [before identities [attribute declared :as identity]]
  (case attribute
    :seon.task/sym (task-path declared)
    :seon.schema/key (schema-path declared)
    (or (get-in (db/pull before [{:seon.fn/file [:seon.fn.file/relative-path]}] identity)
                [:seon.fn/file :seon.fn.file/relative-path])
        (when-let [namespace-name (identity-namespace identity)]
          (or (:seon.fn.file/relative-path (namespace-path before namespace-name))
              (new-path (filter #(= namespace-name (identity-namespace %)) identities)))))))

(defn- proven-files
  "`proven-file` (`proven-schema-file` for schemas, `proven-task-file` for
  tasks) for each file the changes
  of `identities` are written into, by path: a function, its schema and the
  test proving it are three files of one merge. The first refusal answers; a
  change naming no file refuses `:one-file`."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.db/database-value :seon.program/identity-set :seon.fn/root]
                  [:or [:vector :seon.source/proof] :seon.source/write-back-error]]}
  [before after identities root]
  (let [groups (group-by #(change-path before identities %) identities)]
    (cond
      (empty? identities) (write-back-refusal :no-replacement "The accepted merge changed no declaration." {})
      (contains? groups nil)
      (write-back-refusal :one-file "Each change needs its source and one file."
                          {:seon.error/offending (vec (sort-by str (get groups nil)))})
      :else (reduce (fn [proofs [_ group]]
                      (let [prove (case (ffirst group) :seon.task/sym proven-task-file :seon.schema/key proven-schema-file proven-file)
                            proof (prove before after (set group) root)]
                        (if (:seon.error/at proof) (reduced proof) (conj proofs proof))))
                    [] (sort-by key groups)))))

(defn lint-errors
  "The clj-kondo errors, under the checkout's `.clj-kondo` configuration (`seon.fn.analyzer/analyze`, one run),
  in the Clojure files of `proofs` (`proven-files`) as their candidate bytes would publish them under `root`:
  the findings a lane commit refuses (`bin/lane`), by file and position."
  {:malli/schema [:=> [:cat :seon.fn/root [:vector :seon.source/proof]] [:vector :seon.source/lint-finding]]}
  [root proofs]
  (let [clojure (filter #(some (partial str/ends-with? (:seon.source/path %)) [".clj" ".cljc"]) proofs)
        canonical #(.getCanonicalPath (io/file root ^String (:seon.source/path %)))
        paths (into {} (map (juxt canonical :seon.source/path)) clojure)
        texts (into {} (map (juxt canonical #(String. ^bytes (:seon.source/candidate-bytes %) "UTF-8"))) clojure)]
    (if (empty? texts)
      []
      (into [] (keep (fn [{level ::analyzer/level filename ::analyzer/filename :as finding}]
                       (when (and (= :error level) (paths filename))
                         {:seon.source/path (paths filename)
                          :seon.lint/row (long (::analyzer/row finding)) :seon.lint/col (long (::analyzer/col finding))
                          :seon.lint/type (::analyzer/type finding) :seon.lint/message (::analyzer/message finding)})))
            (sort-by (juxt ::analyzer/filename ::analyzer/row ::analyzer/col)
                     (::analyzer/findings (analyzer/analyze {::analyzer/sources texts})))))))

(defn- write-back-commits
  "`write-back!` between the accepted merge's `before` and `after` commits."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.source/accepted :seon.db/database-value
                       :seon.db/database-value :seon.fn/root :seon.source/stage]
                  [:or :seon.source/written :seon.source/write-back-error]]}
  [connection accepted before after root stage]
  (let [identities (:seon.program/identities accepted)
        merged (held-digests after identities)
        moved (moved-digests merged (held-digests (db/db connection) identities))
        proven (when (empty? moved) (proven-files before after identities root))
        disk-digest #(let [file (io/file root (str (:seon.source/path %)))]
                       (sha-256 (if (.isFile file) (Files/readAllBytes (.toPath file)) (byte-array 0))))
        ;; the disk holds HEAD's bytes, or this merge's own candidate from a write-back a crash left uncommitted
        changed (when (vector? proven)
                  (first (remove #(#{(:seon.source/base-digest %) (sha-256 (:seon.source/candidate-bytes %))} (disk-digest %))
                                 proven)))
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
      ;; All or nothing: every candidate is staged beside its file before any is renamed onto it.
      (let [staging (reduce (fn [staging {path :seon.source/path candidate :seon.source/candidate-bytes}]
                              (let [file (.getPath (io/file root (str path)))]
                                (try (update staging :staged conj [(stage file candidate) file])
                                     (catch Exception failure (reduced (assoc staging :failed path :failure failure))))))
                            {:staged []} proven)]
        (if-let [failed (:failed staging)]
          (do (run! (fn [[staged]] (Files/deleteIfExists (.toPath (io/file staged)))) (:staged staging))
              (write-back-refusal :install-failed "A proven candidate could not be staged; no file was installed."
                                  {:seon.source/path failed :seon.source/changed-paths []
                                   :seon.error/throwable (:failure staging)}))
          (do (install-staged! (:staged staging))
              {:seon.source/changed-paths (mapv :seon.source/path proven)
               :seon.source/files
               (mapv (fn [{path :seon.source/path base-digest :seon.source/base-digest candidate :seon.source/candidate-bytes
                           entities :seon.source/proof-rows}]
                       (let [added (kind entities #(and (not %1) %2))
                             retracted (kind entities #(and %1 (not %2)))]
                         (cond-> {:seon.source/path path :seon.source/base-digest base-digest
                                  :seon.source/candidate-digest (sha-256 candidate)
                                  :seon.source/replaced (kind entities #(and %1 %2))}
                           (seq added) (assoc :seon.source/added added)
                           (seq retracted) (assoc :seon.source/retracted retracted))))
                     proven)}))))))

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
  definition digest the merge changed and the filed tasks whose filed declaration it changed
  (`[:seon.task/sym ns/name]`; a runtime task, `:seon.task/created-by`, is never a file's, so a filed task a chain
  made runtime reads as removed), the main commit it was transacted onto and
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
        (let [datoms (changed-datoms before after)
              declared (fn/report-identities before after datoms (vec program/identity-attributes))
              ;; a task is a declaration too: each entity whose task facts moved, by its symbol, when either side
              ;; holds it filed; a runtime task (`:seon.task/created-by`, a chain's among them) is no file's, so a
              ;; filed task a chain made runtime is filed before and absent after: its entry is deleted
              filed-sym (fn [database e] (when-not (seq (d/datoms database :eavt e :seon.task/created-by))
                                           (:v (first (d/datoms database :eavt e :seon.task/sym)))))
              sym-of (fn [e] (some #(filed-sym % e) [after before]))
              tasks (into #{} (comp (filter #(= "seon.task" (namespace (:a %)))) (map :e) (distinct) (keep sym-of)
                                    (map #(vector :seon.task/sym %)))
                          datoms)
              filed-digests (fn [database identities]
                              (into (held-digests database identities)
                                    (keep (fn [[attribute sym :as identity]]
                                            (when (and (= :seon.task/sym attribute) (nil? (filed-declaration database sym)))
                                              [identity :seon.program/absent])))
                                    identities))]
          (when (:seon.db/invalid-read declared) (throw (ex-info (:seon.error/message declared) declared)))
          (let [touched (into (set declared) tasks)
                [was now] (map #(filed-digests % touched) [before after])]
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
    (try (vec (sort-by str (into #{} (keep #(change-path database identities %)) identities)))
         (finally (d/release-materialized-db database)))))

(defn write-back!
  "Write the function and test changes of `accepted` (`accept-merge!`'s
  accepted merge on `connection`'s branch) into their source files under
  `root`, each change into its one file: a replaced declaration's recorded byte span takes its accepted
  source, a retracted one's span (and the blank lines before it) goes, and an
  added one is appended to its namespace's file. A namespace row is its `ns`
  form: its span takes the merged form. A task's change is its entry in its
  task file (`proven-task-file`), a schema's its entry in its resource.

  Proven before installation. Refuses by reason unless each written
  declaration still holds, on the branch head, the digest the merge commit gave
  it (other writes never stale it); each file is read at the checkout's git HEAD (`committed-bytes`), never a
  lane's uncommitted working copy, which `seon.merge/publish!` waits for; every change names one file (a replaced or
  retracted declaration by its state before the merge, an added one by its
  namespace's file) and every before-state a span holding its source; a file
  changed since its declarations were analyzed is read again and each written
  declaration must still hold its base digest there (else `:conflict`, with
  `:seon.program/conflict-digests`); and the ordinary analyzer reindexes the
  candidate to the file's other declarations unchanged and the merge commit's
  digests for the written ones. Every file's disk bytes are checked again, then every
  candidate is staged beside its file (`:seon.source/stage`, default `seon.cluster.source/stage!`) and only then
  renamed onto it, answering one entry per file; a failed stage removes the staged files and installs none
  (`:install-failed`). Publishing the changed path stays with the publication owner.

  `:seon.fn/root` is the checkout's absolute path (`seon.fs/checkout-root?`);
  a relative path or one inside a `data/source` archive is refused as
  `:root-refused` and nothing is written.

  ## Example

  ```clojure
  (write-back! {:seon.db/connection connection :seon.source/accepted accepted :seon.fn/root \"/Users/me/src/project\"})
  ```"
  {:malli/schema [:=> [:cat :seon.source/write-back-request]
                  [:or :seon.source/written :seon.source/write-back-error]]}
  [{connection :seon.db/connection accepted :seon.source/accepted root :seon.fn/root
    stage :seon.source/stage :or {stage stage!}}]
  (let [[before after] (map #(d/commit-as-db connection (% accepted))
                            [:seon.source/base-commit-id :seon.source/commit-id])]
    (try
      (if-let [refused (root-refusal root)]
        refused
      (if (some nil? [before after])
        (write-back-refusal :commit-absent "The accepted merge's commits are unavailable in this store."
                            {:seon.error/offending (select-keys accepted [:seon.source/base-commit-id :seon.source/commit-id])})
        (write-back-commits connection accepted before after root stage)))
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
  "The merge's commit message: what merged and the evidence it merged on, in `bin/lane`'s trailer form, naming
  its agent and each model it called (`:seon.source/models`)."
  {:malli/schema [:=> [:cat :seon.source/acceptance [:vector :seon.source/path]] :string]}
  [{task-sym :seon.task/sym assignment :seon.task/assignment agent-id :seon.agent/id run-id :seon.test.run/id
    commit-id :seon.source/commit-id models :seon.source/models}
   paths]
  (str "Merge " (or task-sym (str "assignment " assignment)) (when agent-id (str " from agent " agent-id))
       ": merged on main as " commit-id (when run-id (str " by gate run " run-id)) "\n\n"
       "Written: " (str/join " " paths) "\n\n"
       "Assignment: " assignment "\n"
       "Merge-commit: " commit-id "\n"
       (when run-id (str "Branch-evidence-runs: " run-id "\n"))
       (when agent-id (str "Agent: " agent-id "\n"))
       (apply str (map #(str "Model: " % "\n") models))))

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

  ## Example

  ```clojure
  (commit! \"/Users/me/src/project\" written acceptance)
  ```"
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
