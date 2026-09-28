(ns seon.fn.analyzer-test
  (:require [clj-kondo.core :as kondo]
            [clj-kondo.impl.cache :as kondo.cache]
            [clj-kondo.impl.core :as kondo.core]
            [clojure.java.io :as io]
            [cognitect.transit :as transit]
            [clojure.test :refer [deftest is testing]]
            [seon.fn.analyzer :as analyzer]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(defn- fixture-directory
  [fixture-name]
  (let [directory (io/file "tmp" "analyzer-test" fixture-name)]
    (.mkdirs directory)
    directory))

(defn- write-fixture!
  [directory filename source]
  (let [file (io/file directory filename)]
    (spit file source)
    (.getCanonicalPath file)))

(deftest publication-findings-depend-on-declarations-not-inferred-return-types
  (let [directory (fixture-directory (str (random-uuid)))
        callee (write-fixture! directory "callee.clj" "")
        caller (write-fixture! directory "caller.clj" "")
        caller-text (str "(ns caller (:require [callee :as c]))\n"
                         "(defn g [] [(inc (c/f)) (str (c/f)) (c/f 1)])\n")
        analyses
        (try
          (mapv (fn [body]
                  (analyzer/analyze
                   {::analyzer/sources
                    {callee (str "(ns callee)\n"
                                 "(defn f {:malli/schema [:=> [:cat] [:or :int :string]]} [] "
                                 body ")\n")
                     caller caller-text}}))
                ["1" "\"text\""])
          (finally (test-support/delete-recursively! directory)))
        caller-findings (mapv #(filterv (fn [finding]
                                         (= caller (::analyzer/filename finding)))
                                       (::analyzer/findings %)) analyses)]
    (is (= (first caller-findings) (second caller-findings)))
    (is (= [:invalid-arity] (mapv ::analyzer/type (first caller-findings)))
        "declaration arity admission remains active")))

(deftest canonical-analysis-rejects-obsolete-cache-authorities
  (let [directory (fixture-directory (str (random-uuid)))
        cache-root (io/file directory "cache")
        sentinel-directory (io/file directory "sentinel")
        sentinel (io/file sentinel-directory "cache-authority.transit.json")
        options {:cache-dir (.getPath cache-root) :config-dir ".clj-kondo"
                 :repro true :lang :clj}
        source (write-fixture! directory "cache_authority.cljc"
                               "(ns cache-authority) (defn f [x y] [x y])")
        consumer (write-fixture! directory "cache_consumer.clj"
                                 (str "(ns cache-consumer (:require [cache-authority :as a]"
                                      " [clojure.java.io :as io]))\n"
                                      "(a/f 1 2) (io/make-parents \"tmp/x/y\")"))
        errors #(filter (fn [finding] (= :error (::analyzer/level finding)))
                        (::analyzer/findings %))]
    (try
      (with-redefs-fn
        {(ns-resolve 'seon.fn.analyzer 'cache-directory) (.getPath cache-root)}
        (fn []
          (doseq [poison-source ["(ns cache-authority) (defn f [x] x)"
                                 "(ns clojure.java.io) (defn incomplete [] nil)"]]
            (with-in-str poison-source
              (kondo/run! (assoc options :lint ["-"]))))
          (.mkdirs sentinel-directory)
          (spit sentinel (slurp (io/file cache-root "v1" "clj"
                                        "cache-authority.transit.json")))
          (java.nio.file.Files/createSymbolicLink
           (.toPath (io/file cache-root "v1" "symlinked-cache"))
           (.toPath (.getAbsoluteFile sentinel-directory))
           (make-array java.nio.file.attribute.FileAttribute 0))
          (is (seq (:findings (kondo/run! (assoc options :lint [source consumer]))))
              "the real dependency must observe the poisoned cache")
          (is (empty? (errors (analyzer/analyze
                              {::analyzer/paths [source consumer]}))))
          (is (.exists sentinel) "cache cleanup must not follow a symlink")
          (let [old-source (write-fixture! directory "cache_authority.clj"
                                            "(ns cache-authority) (defn f [x] x)")]
            (kondo/run! (assoc options :lint [old-source]))
            (is (empty? (errors (analyzer/analyze
                                {::analyzer/paths [source consumer]}))))
            (spit consumer "(ns cache-consumer (:require [cache-authority :as a])) (a/f 1)")
            (is (= [:invalid-arity]
                   (mapv ::analyzer/type
                         (errors (analyzer/analyze
                                  {::analyzer/paths [source consumer]}))))))))
      (finally
        (test-support/delete-recursively! directory)))))

(deftest complete-roots-and-individual-files-have-parity
  (let [directory (fixture-directory "parity")
        alpha
        (write-fixture!
         directory
         "alpha.clj"
         (str "(ns analyzer.fixture.alpha\n"
              "  (:require [clojure.string :as str]))\n"
              "(defn ^:private trim-value\n"
              "  \"Trim one value.\"\n"
              "  {:malli/schema [:=> [:cat :string] :string]}\n"
              "  [value]\n"
              "  (str/trim value))\n"))
        beta
        (write-fixture!
         directory
         "beta.clj"
         (str "(ns analyzer.fixture.beta\n"
              "  (:require [analyzer.fixture.alpha :as alpha]))\n"
              "(defn use-alpha [value]\n"
              "  (alpha/trim-value value))\n"))
        complete (analyzer/analyze {::analyzer/paths [(.getPath directory)]})
        incremental (analyzer/analyze {::analyzer/paths [alpha]})]
    (testing "one-file analysis is the exact file projection of a full pass"
      (doseq [analysis-key [::analyzer/namespace-definitions
                            ::analyzer/namespace-usages
                            ::analyzer/var-definitions
                            ::analyzer/var-usages]]
        (is (= (filterv #(= alpha (::analyzer/filename %))
                        (get complete analysis-key))
               (get incremental analysis-key)))))
    (testing "definitions retain docs, arglists, privacy, metadata, and positions"
      (let [definition
            (first
             (filter #(= 'trim-value (::analyzer/name %))
                     (::analyzer/var-definitions incremental)))]
        (is (= 'analyzer.fixture.alpha (::analyzer/ns definition)))
        (is (= ["[value]"] (::analyzer/arglist-strs definition)))
        (is (true? (::analyzer/private definition)))
        (is (= "Trim one value." (::analyzer/doc definition)))
        (is (= [:=> [:cat :string] :string]
               (get-in definition [::analyzer/meta :malli/schema])))
        (is (= 3 (::analyzer/row definition)))
        (is (= 7 (::analyzer/end-row definition)))
        (is (every? integer?
                    (map definition
                         [::analyzer/col ::analyzer/end-col
                          ::analyzer/name-row ::analyzer/name-col
                          ::analyzer/name-end-row ::analyzer/name-end-col])))))
    (testing "the complete pass includes the other file and its call edge"
      (is (some #(and (= beta (::analyzer/filename %))
                      (= 'analyzer.fixture.alpha (::analyzer/to %))
                      (= 'trim-value (::analyzer/name %)))
                (::analyzer/var-usages complete))))))

(deftest standard-declaration-macros-produce-definitions
  (let [directory (fixture-directory "macro")
        file
        (write-fixture!
         directory
         "macro.clj"
         (str "(ns analyzer.fixture.macro\n"
              "  (:require [clojure.test :refer [deftest is]]))\n"
              "(deftest indexed-test\n"
              "  (is (= 2 (+ 1 1))))\n"))
        analysis (analyzer/analyze {::analyzer/paths [file]})
        definition
        (first
         (filter #(= 'indexed-test (::analyzer/name %))
                 (::analyzer/var-definitions analysis)))]
    (is (= 'clojure.test/deftest (::analyzer/defined-by definition)))
    (is (true? (::analyzer/test definition)))
    (is (= ["[]"] (::analyzer/arglist-strs definition)))
    (is (= 3 (::analyzer/row definition)))
    (is (some #(and (= 'indexed-test (::analyzer/from-var %))
                    (= 'clojure.core (::analyzer/to %))
                    (= '+ (::analyzer/name %)))
              (::analyzer/var-usages analysis)))))

(deftest malformed-files-retain-valid-analysis-and-normalized-findings
  (let [directory (fixture-directory "malformed")
        valid (write-fixture! directory "valid.clj"
                              "(ns valid)\n(defn kept [x] x)\n")
        broken (write-fixture! directory "broken.clj"
                               "(ns broken)\n(defn unfinished [")
        analysis (analyzer/analyze {::analyzer/paths [valid broken]})]
    (is (some #(and (= valid (::analyzer/filename %))
                    (= 'kept (::analyzer/name %)))
              (::analyzer/var-definitions analysis)))
    (is (some #(and (= broken (::analyzer/filename %))
                    (= :error (::analyzer/level %))
                    (string? (::analyzer/message %))
                    (keyword? (::analyzer/type %)))
              (::analyzer/findings analysis)))))

(deftest jvm-projection-excludes-cljs-and-keeps-generated-constructors
  (let [directory (fixture-directory "jvm")
        cljc (write-fixture!
              directory "shared.cljc"
              (str "(ns shared)\n"
                   "#?(:clj (defn jvm-only [] :clj)\n"
                   "   :cljs (defn browser-only [] :cljs))\n"
                   "(defrecord Pair [left right])\n"
                   "(deftype Cell [value])\n"))
        definitions (::analyzer/var-definitions
                     (analyzer/analyze {::analyzer/paths [cljc]}))
        names (into #{} (map ::analyzer/name) definitions)]
    (is (contains? names 'jvm-only))
    (is (not (contains? names 'browser-only)))
    (is (every? #(not= :cljs (::analyzer/lang %)) definitions))
    (is (every? seq
                (map ::analyzer/arglist-strs
                     (filter #(contains? #{'->Pair 'map->Pair '->Cell}
                                         (::analyzer/name %))
                             definitions))))))

(deftest a-source-changed-during-analysis-is-the-declared-refusal
  ;; A source whose modification time stays after every rebuilt cache entry is
  ;; the concurrent edit: rebuilding cannot make its entry answer for its bytes.
  (let [directory (fixture-directory (str (random-uuid)))
        cache-root (.getPath (io/file directory "cache"))
        callee (write-fixture! directory "race_callee.clj" "(ns race-callee) (defn f [x] x)")
        caller (write-fixture! directory "race_caller.clj"
                               "(ns race-caller (:require [race-callee :as c])) (c/f 1)")]
    (try
      (analyzer/analyze {::analyzer/paths [callee] ::analyzer/cache-root cache-root})
      (.setLastModified (io/file callee) (+ (System/currentTimeMillis) 3600000))
      (let [refusal (try (analyzer/analyze {::analyzer/paths [caller]
                                            ::analyzer/cache-root cache-root})
                         nil
                         (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
        (is ((schema/projection-validator (schema/handed-projection) :seon.fn/index-refused-error)
             refusal))
        (is (= :analysis (:seon.cluster.source/phase refusal)))
        (is (= :refused (:seon.cluster.boot/disposition refusal))
            "the operator request returns it as refused, never a core fault")
        (is (= [callee] (:seon.source/changed-paths refusal))))
      (finally
        (test-support/delete-recursively! directory)))))

(deftest namespace-clauses-are-every-require-clj-kondo-attributes-to-the-namespace
  (let [directory (fixture-directory (str (random-uuid)))
        file (write-fixture! directory "clauses.clj" "")
        analysis
        (try
          (analyzer/analyze
           {::analyzer/sources
            {file (str "(ns analyzer.fixture.clauses\n"
                       "  (:require [clojure.string :as str]))\n"
                       "(require '[clojure.set :as set :refer [union]])\n"
                       "(defn f [m] [(str/trim \"\") (set/union) (union) (:rename m)])\n")}})
          (finally (test-support/delete-recursively! directory)))
        [definition] (::analyzer/namespace-definitions analysis)]
    (is (= #{['str 'clojure.string] ['set 'clojure.set]}
           (into #{} (map (juxt ::analyzer/alias ::analyzer/to)) (::analyzer/clause-usages definition)))
        "a top-level require is a clause of the namespace that made it")
    (is (= ['union] (mapv ::analyzer/name (::analyzer/clause-refers definition))))
    (is (= [] (::analyzer/clause-renames definition))
        "a :rename keyword read outside the ns form is not a clause")))

(deftest dependency-analysis-is-missing-exactly-for-unmarked-jars
  ;; Boot fills the dependency analysis when a jar lacks kondo's skip mark
  ;; (`ensure-dependency-analysis!`): an empty cache names every jar, a mark
  ;; covers every coordinate sharing its jar name, and directories carry none.
  (let [directory (fixture-directory (str (random-uuid)))
        cache (.getPath (io/file directory "cache"))
        entries ["/m2/com/lambdaisland/glogi/1.3.169/glogi-1.3.169.jar"
                 "/m2/lambdaisland/glogi/1.3.169/glogi-1.3.169.jar"
                 "/m2/cheshire/cheshire/6.0.0/cheshire-6.0.0.jar"
                 "/checkout/reference-code/sci/src"]
        unanalyzed #(#'analyzer/unanalyzed-jars cache entries)]
    (try
      (is (= (pop entries) (unanalyzed)) "an empty cache lacks every jar")
      (let [mark (io/file cache "v1" "skip" "glogi-1.3.169.jar.4468ce4e")]
        (io/make-parents mark)
        (spit mark (second entries)))
      (is (= ["/m2/cheshire/cheshire/6.0.0/cheshire-6.0.0.jar"] (unanalyzed))
          "one mark names both coordinates sharing its jar name, whatever path it holds")
      (finally (test-support/delete-recursively! directory)))))

(deftest a-first-party-entry-whose-source-is-gone-is-rebuilt-from-the-classpath
  ;; The shared cache outlives the checkout that wrote it: an entry naming
  ;; another archive's deleted mirror, built from other bytes than the
  ;; namespace's current file, is stale (source absent). Deleting it without a
  ;; rebuild left the namespace unanalyzed, so boot refused every call into it
  ;; (start --head b605b2144, 2026-09-26). Staleness is decided by content: an
  ;; entry whose recorded digest matches the current bytes answers wherever
  ;; its path went (a-cache-entry-answers-by-content-not-by-path).
  (let [directory (fixture-directory (str (random-uuid)))
        cache-root (.getPath (io/file directory "cache"))
        caller (write-fixture! directory "gone_caller.clj"
                               "(ns gone-caller (:require [seon.id :as id])) (id/valid? 1)")
        entry (io/file cache-root "v1" "clj" "seon.id.transit.json")
        analyze #(analyzer/analyze {::analyzer/paths [caller] ::analyzer/cache-root cache-root})]
    (try
      (analyze)
      (is (.isFile entry) "the first-party pass wrote seon.id's entry")
      (let [value (with-open [input (io/input-stream entry)]
                    (transit/read (transit/reader input :json)))]
        (with-open [output (io/output-stream entry)]
          (transit/write (transit/writer output :json)
                         (assoc value :filename (.getPath (io/file directory "gone" "id.clj")))))
        ;; built from other bytes: its recorded content digest is not the current file's
        (spit (str (.getPath entry) ".sha256") (apply str (repeat 64 "0"))))
      (let [analysis (analyze)
            stale (::analyzer/stale (::analyzer/cache analysis))]
        (is (= [::analyzer/source-absent] (mapv ::analyzer/reason stale)))
        (is (every? ::analyzer/source stale) "the stale entry names the classpath file to rebuild from")
        (is (not (contains? (::analyzer/unanalyzed-namespaces analysis) 'seon.id))))
      (finally
        (test-support/delete-recursively! directory)))))

(deftest a-directory-dependency-entry-older-than-its-source-is-rebuilt-not-deleted
  ;; A submodule bump makes a `:local/root` directory's files newer than their
  ;; shared cache entries. The analysis deleted such an entry without a
  ;; rebuild (only checkout sources rebuilt), and a directory carries no skip
  ;; mark to trigger the boot fill, so boot refused every call into the
  ;; namespace (start --head 5bed888d0, 2026-09-26: sci.impl.namespaces,
  ;; sci.impl.utils). Both the stale and the missing entry rebuild from the
  ;; file the classpath loads. Runs over the checkout's own cache, which it
  ;; leaves holding the namespace's current analysis.
  (let [caller (.getCanonicalPath (io/file "src/seon/effect.clj"))
        root (.getCanonicalPath ^java.io.File
              (kondo.core/resolve-cache-dir ".clj-kondo" true ".clj-kondo/.cache"))
        entry (kondo.cache/cache-file root :cljc 'sci.impl.utils)
        original (when (.isFile entry) (java.nio.file.Files/readAllBytes (.toPath entry)))
        analyze #(analyzer/analyze {::analyzer/paths [caller]})]
    (try
      (analyze)
      (is (.isFile entry) "the caller's analysis holds sci.impl.utils")
      (.setLastModified entry 0)
      (let [analysis (analyze)
            stale (::analyzer/stale (::analyzer/cache analysis))]
        (is (= [[::analyzer/modified 'sci.impl.utils]]
               (mapv (juxt ::analyzer/reason ::analyzer/namespace) stale)))
        (is (every? ::analyzer/source stale) "the stale entry names the directory file to rebuild from")
        (is (.isFile entry) "rebuilt, not deleted")
        (is (not (contains? (::analyzer/unanalyzed-namespaces analysis) 'sci.impl.utils))))
      (.delete entry)
      (let [analysis (analyze)]
        (is (.isFile entry) "a missing directory entry is analyzed from its classpath file")
        (is (not (contains? (::analyzer/unanalyzed-namespaces analysis) 'sci.impl.utils))))
      (finally
        (when (and original (not (.isFile entry)))
          (java.nio.file.Files/write (.toPath entry) ^bytes original
                                     (make-array java.nio.file.OpenOption 0)))))))

(deftest a-cache-entry-answers-by-content-not-by-path
  ;; CLASS: a cache entry invalidated by where its source lived. Each commit
  ;; runs from its own archive (data/source/<sha>), and the old one is pruned:
  ;; the first analysis after a move rebuilt every entry it used from the
  ;; classpath (466 entries named a pruned archive; 41 rebuilt, 5.9 s against
  ;; 2.8 s for an 18-file analysis, disposable root 2026-09-27).
  (let [directory (fixture-directory (str (random-uuid)))
        source (write-fixture! directory "probe_moved.clj" "(ns probe-moved)\n(defn f [x] x)\n")]
    (try
      (let [cache-root (::analyzer/cache-root
                        (#'analyzer/invoke-kondo {:lint [source]
                                                  :cache-dir (.getCanonicalPath (io/file directory "cache"))}))
            entry (kondo.cache/cache-file cache-root :clj 'probe-moved)
            ;; the archive the entry was written from is gone
            _ (spit entry (.replace ^String (slurp entry) source "/pruned/archive/probe_moved.clj"))
            result {:analysis {:namespace-definitions [{:name 'probe-moved :filename source}]
                               :namespace-usages [{:to 'probe-moved}]}}
            stale #(::analyzer/stale (#'analyzer/stale-cache-entries cache-root false result))]
        (is (empty? (stale)) "the same bytes answer, wherever they were read from")
        (spit source "(ns probe-moved)\n(defn f [x y] x)\n")
        (is (= [::analyzer/source-absent] (mapv ::analyzer/reason (stale)))
            "other bytes rebuild the entry"))
      (finally (test-support/delete-recursively! directory)))))
