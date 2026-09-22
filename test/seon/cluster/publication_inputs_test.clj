(ns seon.cluster.publication-inputs-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.fn.analyzer :as analyzer]
            [seon.fs :as fs]
            [seon.schema :as schema]
            [seon.test.cache :as test.cache]
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
            (str "160000 " (apply str (repeat 64 "a")) " 0\t" pin "\n"))
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
  (let [inputs (set (source/discover-paths (fs/source-directory) cluster/source-roots))]
    (is (contains? inputs "resources/seon/schemas/seon.ns.edn"))
    (is (not (contains? inputs "resources/seon/schemas"))))
  (is (not-any? #{"resources/seon/bootstrap.edn"} cluster/source-roots)))

(deftest ^{:seon.test/long "Canonical fixture plus a private physical publication store copy measured 6.10 s; the real branch head is the assertion subject."
           :seon.test/long-ms 10000}
  an-unchanged-explicit-path-is-captured-once-and-keeps-the-head
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
                   capture-paths source/capture-paths
                   result (with-redefs [source/capture-paths
                                        (fn [directory paths]
                                          (swap! calls conj paths)
                                          (capture-paths directory paths))]
                            (#'cluster/full-source-refresh!
                             (str root "/data/clusters") opened
                             {:seon.fn/root (fs/source-directory)
                              :seon.fn/roots functions/source-roots
                              :seon.source/roots cluster/source-roots
                              :seon.source/changed-paths ["src/my/note.clj"]}))]
               (is (false? (:seon.source/built? result)))
               (is (= ["src/my/note.clj"] (vec (mapcat identity @calls))))
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

(deftest publication-classifies-configuration-and-loaded-dependencies
  (is (= :selected (source/classify-paths #{"src/my/note.clj"} #{"reference-code/sci"})))
  (is (= :all (source/classify-paths #{".clj-kondo/config.edn"} #{})))
  (doseq [path ["deps.edn" "reference-code/sci"]]
    (let [refusal (try (source/classify-paths #{path} #{"reference-code/sci"})
                       (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
      (is (= :seon.cluster.source/restart-needed (:seon.cluster.source/rule refusal)))
      (is (= [path] (:seon.source/changed-paths refusal))))))

(defn- test-input-checkout!
  "A checkout whose inventory and pins are recorded, so no Git process runs."
  [root]
  (doseq [[path text] {"deps.edn" "{:paths [\"src\" \"script\"]}"
                       "src/leaf.clj" "(ns leaf)"
                       "script/tool.clj" "(ns tool)"
                       "test/fixtures/sample.txt" "fixture bytes"}]
    (io/make-parents (io/file root path))
    (spit (io/file root path) text))
  (.mkdirs (io/file root "reference-code/example"))
  (spit (io/file root "dependency-pins.txt")
        (str "160000 " (apply str (repeat 64 "a")) " 0\treference-code/example\n"))
  (spit (io/file root "test-input-paths.txt")
        (str/join (char 0) ["deps.edn" "src/leaf.clj" "script/tool.clj"
                            "test/fixtures/sample.txt" "reference-code/example"])))

(deftest the-test-input-digest-reads-only-inputs-it-does-not-already-hold
  (let [root (.getCanonicalPath (io/file "tmp/publication-inputs" (str (random-uuid))))]
    (try
      (test-input-checkout! root)
      (let [roots (test.cache/input-roots root)
            whole (test.cache/test-input-digest root (test.cache/input-digests root))
            held (source/path-digests root ["deps.edn" "src/leaf.clj" "script/tool.clj"
                                            "reference-code/example"])
            derived (source/snapshot-test-input-digest
                     {:seon.fn/root root ::source/roots roots
                      :seon.source/relative-file-digests held})]
        (is (= whole (:seon.source/test-input-digest derived))
            "the derived digest equals the whole-checkout digest")
        (is (= ["test/fixtures/sample.txt"] (::source/read derived))
            "only the input the capture did not hold is read")
        (is (= 3 (::source/hits derived)))
        (is (false? (::source/reused derived))))
      (finally (support/delete-recursively! root)))))

(deftest a-partial-publication-reuses-the-published-test-input-digest-until-an-input-changes
  (let [root (.getCanonicalPath (io/file "tmp/publication-inputs" (str (random-uuid))))
        published (apply str (repeat 64 "d"))]
    (try
      (test-input-checkout! root)
      (let [request {:seon.fn/root root ::source/roots (test.cache/input-roots root)
                     ::source/published published
                     :seon.source/relative-file-digests
                     (source/path-digests root ["src/leaf.clj"])}
            leaf (source/snapshot-test-input-digest
                  (assoc request :seon.source/changed-paths ["src/leaf.clj"]))
            tool (source/snapshot-test-input-digest
                  (assoc request :seon.source/changed-paths ["script/tool.clj"]))]
        (is (= {:seon.source/test-input-digest published ::source/reused true
                ::source/hits 1 ::source/read []}
               leaf)
            "a leaf change keeps the published test-input digest, reading nothing")
        (is (false? (::source/reused tool)))
        (is (= (test.cache/test-input-digest root (test.cache/input-digests root))
               (:seon.source/test-input-digest tool))
            "a changed test input recomputes from content"))
      (finally (support/delete-recursively! root)))))

(deftest captured-source-is-the-analysis-and-digest-authority
  (let [root (io/file "tmp" (str "publication-capture-" (random-uuid)))
        file (io/file root "src/captured.clj")
        directory (.getCanonicalPath root)
        path "src/captured.clj"]
    (io/make-parents file)
    (try
      (spit file "(ns publication.captured)\n(defn value [] 1)\n")
      (let [[digests captured] (source/capture-paths directory [path])]
        (spit file "(ns publication.captured)\n(defn value [] 2)\n")
        (let [rows (functions/analyze-rows
                    {:seon.fn/root directory :seon.fn/roots ["src"]
                     :seon.fn/changed-paths #{path} :seon.fn.analyzer/sources captured})
              function (first (filter :seon.fn/sym rows))
              file-row (first (filter :seon.fn.file/relative-path rows))]
          (is (= "(defn value [] 1)" (:seon.fn/source function)))
          (is (= (get digests path) (:seon.fn.file/digest file-row)))
          (is (not= digests (source/path-digests directory [path])))))
      (finally (support/delete-recursively! root)))))

(deftest a-caller-is-never-checked-against-a-cache-entry-older-than-its-callee
  ;; docs/seon/issues/publication-analysis-reads-a-stale-kondo-cache-entry-for-an-unindexed-caller-target.md
  (let [root (.getCanonicalFile (io/file "tmp/publication-inputs" (str (random-uuid))))
        callee (io/file root "src/kondo_stale/callee.clj")
        caller (io/file root "src/kondo_stale/caller.clj")
        cache-root (.getPath (io/file root "cache"))
        arity-findings (fn [analysis]
                         (filterv #(= :invalid-arity (::analyzer/type %))
                                  (::analyzer/findings analysis)))
        analyze (fn [files]
                  (analyzer/analyze {::analyzer/paths (mapv #(.getPath ^java.io.File %) files)
                                     ::analyzer/cache-root cache-root}))]
    (try
      (io/make-parents callee)
      (spit callee "(ns kondo-stale.callee)\n(defn f [x] x)\n")
      (spit caller (str "(ns kondo-stale.caller (:require [kondo-stale.callee :as callee]))\n"
                        "(defn g [] (callee/f 1 2))\n"))
      (is (= 1 (count (arity-findings (analyze [callee caller]))))
          "the old one-argument callee refuses the two-argument call")
      ;; The edit is later than the entry clj-kondo wrote for the old bytes;
      ;; age the entry so the file clock's resolution cannot tie them.
      (let [entry (io/file cache-root "v1" "clj" "kondo-stale.callee.transit.json")]
        (is (.isFile entry))
        (.setLastModified entry (- (System/currentTimeMillis) 2000)))
      (spit callee "(ns kondo-stale.callee)\n(defn f [x y] [x y])\n")
      (let [caller-only (analyze [caller])
            cache (::analyzer/cache caller-only)]
        (is (empty? (arity-findings caller-only))
            "the caller is checked against the callee's current arity")
        (is (= [['kondo-stale.callee ::analyzer/modified]]
               (mapv (juxt ::analyzer/namespace ::analyzer/reason) (::analyzer/stale cache))))
        (is (= [(.getCanonicalPath callee)] (::analyzer/rebuilt cache))))
      (let [again (analyze [caller])]
        (is (empty? (arity-findings again)))
        (is (empty? (::analyzer/stale (::analyzer/cache again)))
            "the rebuilt entry is reused")
        (is (pos? (::analyzer/examined (::analyzer/cache again)))))
      (finally (support/delete-recursively! (.getPath root))))))
