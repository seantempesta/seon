(ns seon.incremental-publication-test
  "Publication input ownership through the real source refresh boundary."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.fs :as fs]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(defn- copy-tree! [from to]
  (io/make-parents to)
  (if (.isDirectory (io/file from))
    (#'support/clone-directory! (io/file from) (io/file to))
    (io/copy (io/file from) (io/file to))))

(defn- spit-file! [file content]
  (io/make-parents file)
  (spit file content))

(defn- with-published [root f]
  (let [directory (:seon.boot/store-dir
                   (cluster/resolve-bootstrap {:seon.boot/root root}))]
    (with-open [held (support/closeable
                     (store/open-store! {:seon.store/dir directory})
                     store/release-store!)]
      (let [held-store @held
            current (source/current held-store)
            database (source/database held-store (:seon.source/commit-id current))]
        (try
          ;; THE PUBLISHED VALUE CARRIES NO PROJECTION, and `seon.db` refuses
          ;; a read without one. Without this the assertions below would
          ;; compare against a refusal value and pass on absence of signal.
          (schema/call-with-projection
           (schema/projection-from-database database)
           #(f current database))
          (finally (d/release-materialized-db database)))))))

(defn- published-value [root f]
  (with-published root (fn [_current database] (f database))))

(defn- published-digest [root]
  ;; THE PUBLISHED DIGEST IS A DATOM, not part of `source/current`, which
  ;; carries only the branch and its commit ID (`src/seon/cluster/source.clj:152`).
  ;; `current-publication` reads it with exactly this query
  ;; (`src/seon/cluster.clj:1944`).
  (with-published root
    (fn [_current database]
      (db/q '[:find ?digest . :where [_ :seon.source/digest ?digest]] database))))

(deftest ^{:seon.test/fixture-observation
           "Observes real source publication decisions and persisted program/schema/issue facts after filesystem edits."
           :seon.test/long "Publishes a small fixture checkout and five incremental edits through the real refresh boundary."}
  non-program-inputs-have-their-own-publication-owner
  (let [root (str "tmp/incremental-publication-" (random-uuid))
        checkout (io/file root "checkout")
        directory (.getCanonicalPath checkout)
        ;; `test` is deliberately absent: no test file owns a declaration the
        ;; schema population needs, and analyzing it doubles the fixture's
        ;; one complete publication for no additional decision.
        roots (assoc (#'cluster/publication-roots)
                     :seon.fn/root directory
                     :seon.fn/roots ["src"]
                     :seon.source/roots ["src" config/default-manifest-path])
        resource io/resource
        phases (atom [])
        progress-var (ns-resolve 'seon.cluster '*source-progress!*)]
    (try
      ;; THE CANONICAL POPULATION, WITHOUT `test`. The schema resources
      ;; declare render pairs and call-preparation suppliers that name
      ;; functions in `src`, so neither half can be trimmed; `test` owns no
      ;; declaration either half needs, and leaving it out halves the
      ;; fixture's one complete publication.
      (doseq [path ["src" "resources/seon/schemas" config/default-manifest-path]]
        (copy-tree! (io/file (fs/source-directory) path) (io/file checkout path)))
      (spit-file! (io/file checkout "src/sample/a.clj")
                  "(ns sample.a)\n(defn value [] 1)\n")
      ;; Issue notes are NOT part of the publication's source identity, so the
      ;; checkout carries none. The directory exists because issue indexing
      ;; reads it at every publication; the fixture note below is the only one.
      (.mkdirs (io/file checkout "docs/seon/issues"))
      (with-redefs-fn
        {#'cluster/publication-roots (constantly roots)
         #'io/resource
         (fn [name & args]
           (cond
             (or (= name "seon/schemas") (str/starts-with? name "seon/schemas/"))
             (.toURL (.toURI (io/file checkout "resources" name)))
             (= name config/default-manifest-path)
             (.toURL (.toURI (io/file checkout name)))
             :else (apply resource name args)))}
        (fn []
          (cluster/refresh-source! root)
          (doseq [[path content check owner]
                  [["resources/seon/schemas/seon.publication.fixture.edn"
                    "{:seon.publication.fixture/value :string}\n"
                    (fn [database]
                      (is (some? (db/pull database '[*]
                                         [:seon.schema/key :seon.publication.fixture/value]))))
                    :schema-resource]
                   [config/default-manifest-path
                    (pr-str
                     (update (edn/read-string (slurp (io/file checkout config/default-manifest-path)))
                             :seon.config/initialization
                             (fn [rows]
                               (mapv #(if (= "deepseek" (:seon.ai.model/provider-id %))
                                        (assoc % :seon.config.ai/endpoint "https://fixture.invalid/chat")
                                        %) rows))))
                    (fn [database]
                      (is (= "https://fixture.invalid/chat"
                             (:seon.config.ai/endpoint
                              (db/pull database [:seon.config.ai/endpoint]
                                       [:seon.ai.model/provider-id "deepseek"])))))
                    :config]
                   ["src/sample/a.clj"
                    "(ns sample.a)\n(defn value [] 2)\n"
                    (fn [database]
                      (is (str/includes?
                           (str (:seon.fn/source
                                 (db/pull database [:seon.fn/source]
                                          [:seon.fn/sym "sample.a/value"])))
                           "[] 2")))
                    :program]
                   ["publication-note.md" "# No program facts\n"
                    (fn [database]
                      (is (nil? (db/pull database [:db/id]
                                        [:seon.fn.file/relative-path "publication-note.md"]))))
                    :no-program-facts]
                   ["docs/seon/issues/publication-fixture.md"
                    "---\ntype: issue\nstatus: open\nseverity: cleanup\ntags: [issue, publication]\n---\n\n# Publication fixture\n\nAn issue note is indexed independently of program identity.\n"
                    (fn [database]
                      (is (= "Publication fixture"
                             (:seon.issue/title
                              (db/pull database [:seon.issue/title]
                                       [:seon.issue/id "publication-fixture"])))))
                    :no-program-facts]]]
            (testing path
              (let [file (io/file checkout path)
                    digest-before (published-digest root)]
                (io/make-parents file)
                (spit file content)
                (reset! phases [])
                (with-bindings {progress-var #(swap! phases conj %)}
                  (cluster/refresh-source! root [(.getCanonicalPath file)]))
                (is (some #(str/starts-with? % "incremental") @phases) (pr-str @phases))
                ;; THE DECISION NAMES THE OWNER that installs this kind of
                ;; input's facts; a publication that cannot name one is the
                ;; complete-rebuild defect this regression exists to kill.
                (is (some #(str/includes? % (str "owners=(" owner ")")) @phases)
                    (pr-str @phases))
                (is (not-any? #(str/starts-with? % "complete publication") @phases))
                (when (str/ends-with? path ".md")
                  (is (string? digest-before))
                  (is (= digest-before (published-digest root))))
                (published-value root check))))))
      (finally (support/delete-recursively! root)))))
