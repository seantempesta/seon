(ns seon.fn.analyzer-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.fn.analyzer :as analyzer]))

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

(deftest ordered-forms-use-existing-context-and-original-row-numbers
  (let [analysis
        (analyzer/analyze-forms
         {::analyzer/namespace-name 'my.agents.alpha
          ::analyzer/namespace-row
          {:seon.ns/name 'my.agents.alpha
           :seon.ns/aliases
           #{{:seon.ns.alias/local 'str
              :seon.ns.alias/target-ns 'clojure.string}}
           :seon.ns/refers
           #{{:seon.ns.refer/local 'read
              :seon.ns.refer/target-ns 'clojure.edn
              :seon.ns.refer/target-name 'read-string}}}
          ::analyzer/available-functions
          [{:seon.fn/sym "my.run/complete"
            :seon.fn/private? false
            :seon.fn/arglists "([message])"}
           {:seon.fn/sym "other/private-helper"
            :seon.fn/private? true
            :seon.fn/arglists "([value])"}]
          ::analyzer/sources
          ["(defn broken [value]\n  (missing value))"
           "(str/join [\"kept\"])"
           "(read \"{:also :kept}\")"
           "(my.run/complete \"done\")"
           "(other/private-helper 1)"
           "(my.run/complete)"
           "(broken)"]})]
    (testing "the synthetic namespace prelude resolves persisted aliases"
      (is (empty? (::analyzer/findings (nth analysis 1))))
      (is (empty? (::analyzer/findings (nth analysis 2)))
          "a persisted renamed refer is reconstructed with :rename")
      (is (empty? (::analyzer/findings (nth analysis 3)))))
    (testing "program rows preserve cross-namespace privacy checks"
      (is (= :private-call
             (::analyzer/type
              (first (::analyzer/findings (nth analysis 4)))))))
    (testing "program rows preserve cross-namespace arities"
      (is (= :invalid-arity
             (::analyzer/type
              (first (::analyzer/findings (nth analysis 5)))))))
    (testing "prelude rows are removed from exact per-form locations"
      (is (= [{::analyzer/row 2
               ::analyzer/level :error
               ::analyzer/type :unresolved-symbol}]
             (mapv #(select-keys % [::analyzer/row
                                    ::analyzer/level
                                    ::analyzer/type])
                   (::analyzer/findings (first analysis))))))
    (testing "a later dependent form receives its own reported error"
      (is (= :invalid-arity
             (::analyzer/type
              (first (::analyzer/findings (nth analysis 6)))))))))

(deftest reply-analysis-does-not-contaminate-build-analysis
  (let [cache-root (io/file "tmp" "analyzer-test" (str (random-uuid)) "cache")
        source-directory (fixture-directory (str (random-uuid)))
        source-file
        (write-fixture!
         source-directory
         "cache_clean.clj"
         (str "(ns cache-clean)\n"
              "(defn update-value []\n"
              "  (let [value (volatile! 0)]\n"
              "    (vswap! value inc)\n"
              "    (vswap! value + 2)\n"
              "    @value))\n"))
        cache-directory-var
        (ns-resolve 'seon.fn.analyzer 'cache-directory)]
    (with-redefs-fn
      {cache-directory-var (.getPath cache-root)}
      (fn []
        (let [before (analyzer/analyze {::analyzer/paths [source-file]})
              reply
              (analyzer/analyze-forms
               {::analyzer/namespace-name 'my.agents.cache-test
                ::analyzer/available-functions
                [{:seon.fn/sym "clojure.core/volatile!"
                  :seon.fn/arglists "([x])"}
                 {:seon.fn/sym "clojure.core/vswap!"
                  :seon.fn/arglists "([_ _ vol f & args])"}
                 {:seon.fn/sym "runtime.example/one"
                  :seon.fn/arglists "([x])"}]
                ::analyzer/sources
                ["(let [value (clojure.core/volatile! 0)]\n   (clojure.core/vswap! value inc)\n   @value)"
                 "(runtime.example/one)"]})
              after (analyzer/analyze {::analyzer/paths [source-file]})
              builtin-defect?
              #(contains? #{:invalid-arity :type-mismatch}
                          (::analyzer/type %))]
          (testing "build analysis begins with intact builtin definitions"
            (is (empty? (filter builtin-defect? (::analyzer/findings before)))))
          (testing "reply lint retains synthesized program findings"
            (is (= :invalid-arity
                   (::analyzer/type
                    (first (::analyzer/findings (second reply)))))))
          (testing "reply synthesis leaves builtin build analysis intact"
            (is (empty? (filter builtin-defect?
                                (::analyzer/findings after))))))))))
