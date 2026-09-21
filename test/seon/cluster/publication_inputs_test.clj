(ns seon.cluster.publication-inputs-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.fn.analyzer :as analyzer]
            [seon.fs :as fs]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest changed-paths-hash-only-the-named-files-and-use-recorded-pins
  (let [root (str "tmp/publication-inputs/" (random-uuid))
        file (io/file root "src/input.clj")
        pin "reference-code/example"
        captured (atom [])
        sha-256 schema/sha-256]
    (try
      (io/make-parents file)
      (spit file "(ns input)")
      (.mkdirs (io/file root pin))
      (spit (io/file root "deps.edn") "This unrequested input must not be hashed.")
      (spit (io/file root "dependency-pins.txt")
            (str "160000 " (apply str (repeat 40 "a")) " 0\t" pin "\n"))
      (let [digests (with-redefs [schema/sha-256
                                 (fn [parts]
                                   (swap! captured into
                                          (map #(String. ^bytes % "UTF-8") parts))
                                   (sha-256 parts))]
                      (source/path-digests root ["src/input.clj" pin "removed.clj"]))]
        (is (= #{"src/input.clj" pin} (set (keys digests))))
        (is (= ["(ns input)"] @captured))
        (is (every? #(= 64 (count %)) (vals digests))))
      (finally (support/delete-recursively! root)))))

(deftest publication-inputs-are-file-facts-but-not-analysis-inputs
  (support/with-database
   (fn [connection]
     (let [root (str "tmp/publication-inputs/" (random-uuid))
           path "resources/publication-input.edn"
           digest (apply str (repeat 64 "b"))
           manifest {:seon.fn.manifest/root root
                     :seon.fn.manifest/relative-roots []
                     :seon.fn.manifest/artifacts []
                     :seon.fn.manifest/identities []
                     :seon.fn.manifest/digest (apply str (repeat 64 "c"))}
           before (db/db connection)
           result (functions/index!
                   {:seon.db/connection connection
                    :seon.schema/projection (db/carried-projection before)
                    :seon.source/previous-database before
                    :seon.fn/previous-manifest manifest
                    :seon.fn/manifest manifest
                    :seon.fn/changed-paths #{path}
                    :seon.source/relative-file-digests {path digest}})]
       (is (not (:seon.error/at result)) (pr-str result))
       (is (= {path digest} (source/stored-path-digests (db/db connection) [path "missing"])))
       (is (empty? (db/q '[:find [?declaration ...] :in $ ?path
                          :where [?file :seon.fn.file/relative-path ?path]
                                 [?declaration :seon.fn/file ?file]]
                        (db/db connection) path)))
       (let [lint-files (atom [])
             analyze analyzer/analyze]
         (with-redefs [analyzer/analyze
                       (fn [request]
                         (swap! lint-files conj (:seon.fn.analyzer/paths request))
                         (analyze request))]
           (functions/build-manifest
            {:seon.fn/root root :seon.fn/roots []
             :seon.fn/previous-manifest manifest
             :seon.source/previous-database (db/db connection)
             :seon.source/relative-file-digests {path digest}
             :seon.source/changed-paths [path]}))
         (is (empty? @lint-files)))))))

(deftest schema-resource-paths-are-inputs-without-a-derived-merged-entry
  (let [inputs (:seon.source/relative-file-digests (cluster/source-snapshot))]
    (is (get inputs "resources/seon/schemas/seon.ns.edn"))
    (is (not (contains? inputs "resources/seon/schemas"))))
  (is (not-any? #{"resources/seon/bootstrap.edn"} cluster/source-roots)))

(deftest ^{:seon.test/long "Canonical fixture plus a private physical publication store copy measured 6.10 s; the real branch head is the assertion subject."
           :seon.test/long-ms 10000}
  an-empty-change-request-hashes-no-files-and-keeps-the-head
  (support/with-database
   (fn [_]
     (let [root (str "tmp/publication-inputs/" (random-uuid))]
       (try
         (support/populate-published-operator-root!
          root {:seon.test/fixture-observation
                "Verify the real published branch head without submitting a transaction."})
         (let [opened (store/open-store! {:seon.store/dir (str root "/data/store")})]
           (try
             (let [before (source/current opened)
                   calls (atom [])
                   path-digests source/path-digests
                   result (with-redefs [source/path-digests
                                        (fn [directory paths]
                                          (swap! calls conj paths)
                                          (path-digests directory paths))]
                            (#'cluster/full-source-refresh!
                             (str root "/data/clusters") opened
                             {:seon.fn/root (fs/source-directory)
                              :seon.fn/roots functions/source-roots
                              :seon.source/roots cluster/source-roots
                              :seon.source/changed-paths []}))]
               (is (false? (:seon.source/built? result)))
               (is (= [[]] @calls))
               (is (= before (source/current opened))))
             (finally (store/release-store! opened))))
         (finally (support/delete-recursively! root)))))))


(deftest file-row-selection-follows-declaration-and-finding-refs
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           path "src/my/note.clj"
           declarations (functions/file-rows database [path] :seon.fn/file)]
       (is (= #{'my.note/add! 'my.note/forget! 'my.note/notes}
              (into #{} (keep :seon.fn/sym) declarations)))
       (is (every? #(= [:seon.fn.file/relative-path path] (:seon.fn/file %))
                   declarations))
       (is (empty? (functions/file-rows database ["resources/seon/schemas/seon.fn.edn"]
                                     :seon.fn/file))
           "a publication input row alone does not declare an analysis input")
       (is (empty? (functions/file-rows database [] :seon.lint/file)))
       (is (empty? (functions/file-rows database ["missing-file.clj"] :seon.fn/file)))))))
