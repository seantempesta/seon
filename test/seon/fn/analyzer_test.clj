(ns seon.fn.analyzer-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.fn.analyzer :as analyzer]
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

(deftest scratch-analysis-does-not-write-the-shared-cache
  (let [directory (fixture-directory (str (random-uuid)))
        source (write-fixture! directory "scratch_analysis.clj"
                               "(ns scratch-analysis) (defn f [x] x)")
        cache-file (io/file analyzer/cache-directory "v1/clj/scratch-analysis.transit.json")]
    (try
      (is (not (.exists cache-file)))
      (let [analysis (analyzer/analyze {::analyzer/paths [source]})]
        (is (= ['f] (mapv ::analyzer/name (::analyzer/var-definitions analysis)))))
      (is (not (.exists cache-file)))
      (finally (test-support/delete-recursively! directory)))))

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

(deftest dependency-cache-identity-follows-its-inputs
  (doseq [n [1 10 100]]
    (let [entries (mapv #(str "/deps/library-" % ".jar") (range n))
          [cache-key calls] (test-support/armed-calls
                             '#{seon.id/id}
                             #(#'analyzer/dependency-cache-key entries "kondo-v1" "pins-a"))]
      (is (= 1 (get calls 'seon.id/id)))
      (is (= cache-key (#'analyzer/dependency-cache-key (vec (reverse entries)) "kondo-v1" "pins-a")))
      (is (not= cache-key (#'analyzer/dependency-cache-key entries "kondo-v1" "pins-b")))
      (is (not= cache-key (#'analyzer/dependency-cache-key entries "kondo-v2" "pins-a")))
      (is (not= cache-key (#'analyzer/dependency-cache-key (conj entries "/deps/new.jar") "kondo-v1" "pins-a"))))))

(deftest supplied-checkout-files-are-analyzed-once-with-their-current-content
  (let [directory (fixture-directory (str (random-uuid)))
        callee (write-fixture! directory "callee.clj" "(ns callee) (defn f [x] x)")
        caller (write-fixture! directory "caller.clj" "(ns caller (:require [callee :as c])) (c/f 1 2)")
        analyze #(analyzer/analyze {::analyzer/paths [callee caller]
                                    ::analyzer/cache-root (.getPath (io/file directory "cache"))})]
    (try
      (is (some #(= :invalid-arity (::analyzer/type %)) (::analyzer/findings (analyze))))
      (spit callee "(ns callee) (defn f [x y] [x y])")
      (let [[result calls] (test-support/armed-calls
                            '#{seon.fn.analyzer/invoke-kondo} analyze)]
        (is (not-any? #(= :invalid-arity (::analyzer/type %)) (::analyzer/findings result)))
        (is (= 1 (get calls 'seon.fn.analyzer/invoke-kondo))))
      (finally (test-support/delete-recursively! directory)))))
