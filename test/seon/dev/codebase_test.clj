(ns seon.dev.codebase-test
  "Tests for the codebase namespace - file introspection utilities."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [seon.dev.codebase :as cb]))

;;; ---------------------------------------------------------------------------
;;; clojure-file? Tests
;;; ---------------------------------------------------------------------------

(deftest clojure-file?-test
  (testing "Recognizes Clojure source files"
    (is (true? (cb/clojure-file? "src/seon/core.clj")))
    (is (true? (cb/clojure-file? "/absolute/path/to/file.clj"))))

  (testing "Recognizes ClojureScript files"
    (is (true? (cb/clojure-file? "src/app/core.cljs"))))

  (testing "Recognizes common Clojure files"
    (is (true? (cb/clojure-file? "src/app/core.cljc"))))

  (testing "Recognizes Babashka files"
    (is (true? (cb/clojure-file? "bin/script.bb"))))

  (testing "Recognizes EDN files"
    (is (true? (cb/clojure-file? "deps.edn")))
    (is (true? (cb/clojure-file? "config.edn"))))

  (testing "Case insensitive"
    (is (true? (cb/clojure-file? "FILE.CLJ")))
    (is (true? (cb/clojure-file? "File.Clj"))))

  (testing "Rejects non-Clojure files"
    (is (false? (cb/clojure-file? "package.json")))
    (is (false? (cb/clojure-file? "README.md")))
    (is (false? (cb/clojure-file? "style.css")))
    (is (false? (cb/clojure-file? "script.js")))
    (is (false? (cb/clojure-file? "image.png"))))

  (testing "Handles nil input"
    (is (false? (cb/clojure-file? nil))))

  (testing "Handles empty string"
    (is (false? (cb/clojure-file? "")))))

;;; ---------------------------------------------------------------------------
;;; file->namespace Tests
;;; ---------------------------------------------------------------------------

(deftest file->namespace-test
  (testing "Parses namespace from real project files"
    ;; Test with actual files in the project
    (is (= 'seon.core (cb/file->namespace "src/seon/core.clj")))
    (is (= 'seon.schema (cb/file->namespace "src/seon/schema.clj")))
    (is (= 'seon.dev.codebase (cb/file->namespace "src/seon/dev/codebase.clj"))))

  (testing "Returns nil for non-existent files"
    (is (nil? (cb/file->namespace "nonexistent/path/file.clj"))))

  (testing "Returns nil for nil input"
    (is (nil? (cb/file->namespace nil))))

  (testing "Returns nil for files without ns form"
    ;; Create a temp file without ns
    (let [temp-file (java.io.File/createTempFile "no-ns" ".clj")]
      (try
        (spit temp-file "(defn foo [] 42)")
        (is (nil? (cb/file->namespace (.getAbsolutePath temp-file))))
        (finally
          (.delete temp-file))))))

;;; ---------------------------------------------------------------------------
;;; file->test-namespace Tests
;;; ---------------------------------------------------------------------------

(deftest file->test-namespace-test
  (testing "Derives test namespace from source file"
    (is (= 'seon.core-test (cb/file->test-namespace "src/seon/core.clj")))
    (is (= 'seon.schema-test (cb/file->test-namespace "src/seon/schema.clj"))))

  (testing "Test files return unchanged namespace"
    ;; Test namespace already ends with -test
    (is (= 'seon.dev.context-test (cb/file->test-namespace "test/seon/dev/context_test.clj"))))

  (testing "Returns nil for files without namespace"
    (is (nil? (cb/file->test-namespace "nonexistent.clj"))))

  (testing "Returns nil for nil input"
    (is (nil? (cb/file->test-namespace nil)))))

;;; ---------------------------------------------------------------------------
;;; read-source Tests
;;; ---------------------------------------------------------------------------

(deftest read-source-test
  (testing "Reads existing file successfully"
    (let [result (cb/read-source "src/seon/core.clj")]
      (is (true? (::cb/success result)))
      (is (string? (::cb/content result)))
      (is (clojure.string/includes? (::cb/content result) "(ns seon.core"))))

  (testing "Returns error for non-existent file"
    (let [result (cb/read-source "nonexistent/file.clj")]
      (is (false? (::cb/success result)))
      (is (string? (::cb/error result)))
      (is (clojure.string/includes? (::cb/error result) "does not exist"))))

  (testing "Returns error for directory path"
    (let [result (cb/read-source "src/seon")]
      (is (false? (::cb/success result)))
      (is (string? (::cb/error result)))))

  (testing "Handles files with special content"
    ;; Create a temp file with Unicode content
    (let [temp-file (java.io.File/createTempFile "unicode-test" ".clj")
          content "(ns test.unicode \"Unicode: \u03BB lambda\")\n"]
      (try
        (spit temp-file content)
        (let [result (cb/read-source (.getAbsolutePath temp-file))]
          (is (true? (::cb/success result)))
          (is (= content (::cb/content result))))
        (finally
          (.delete temp-file))))))

;;; ---------------------------------------------------------------------------
;;; namespace->file Tests
;;; ---------------------------------------------------------------------------

(deftest namespace->file-test
  (testing "Converts simple namespace"
    (is (= "src/seon/core.clj" (cb/namespace->file 'seon.core))))

  (testing "Converts nested namespace"
    (is (= "src/seon/dev/codebase.clj" (cb/namespace->file 'seon.dev.codebase))))

  (testing "Converts hyphenated namespace to underscores"
    (is (= "src/seon/foo_bar.clj" (cb/namespace->file 'seon.foo-bar)))
    (is (= "src/seon/foo_bar/baz_qux.clj" (cb/namespace->file 'seon.foo-bar.baz-qux))))

  (testing "Uses custom source directory"
    (is (= "other-src/seon/core.clj" (cb/namespace->file 'seon.core "other-src")))
    (is (= "test/seon/core_test.clj" (cb/namespace->file 'seon.core-test "test")))))

;;; ---------------------------------------------------------------------------
;;; test-file-exists? Tests
;;; ---------------------------------------------------------------------------

(deftest test-file-exists?-test
  (testing "Returns true for existing test files"
    ;; These tests exist in the project
    (is (true? (cb/test-file-exists? 'seon.dev.codebase-test)))
    (is (true? (cb/test-file-exists? 'seon.dev.context-test))))

  (testing "Returns false for non-existent test files"
    (is (false? (cb/test-file-exists? 'seon.nonexistent-namespace-test)))
    (is (false? (cb/test-file-exists? 'foo.bar.baz-test)))))

;;; ---------------------------------------------------------------------------
;;; Round-trip Tests
;;; ---------------------------------------------------------------------------

(deftest round-trip-test
  (testing "file->namespace and namespace->file are consistent"
    ;; For actual project files, verify round-trip works
    (let [original-file "src/seon/core.clj"
          ns-sym (cb/file->namespace original-file)
          derived-file (cb/namespace->file ns-sym)]
      (is (= ns-sym 'seon.core))
      (is (= derived-file original-file))))

  (testing "Round-trip for nested namespaces"
    (let [original-file "src/seon/dev/codebase.clj"
          ns-sym (cb/file->namespace original-file)
          derived-file (cb/namespace->file ns-sym)]
      (is (= ns-sym 'seon.dev.codebase))
      (is (= derived-file original-file)))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases
;;; ---------------------------------------------------------------------------

(deftest edge-cases-test
  (testing "Empty path handling"
    (is (false? (cb/clojure-file? "")))
    (is (nil? (cb/file->namespace ""))))

  (testing "Path with spaces"
    (is (true? (cb/clojure-file? "/path with spaces/file.clj"))))

  (testing "Path with special characters"
    (is (true? (cb/clojure-file? "/path-to/file_name.clj")))
    (is (true? (cb/clojure-file? "/path.to/file.clj"))))

  (testing "Multiple extensions (last wins)"
    ;; .clj.bak should not be recognized
    (is (false? (cb/clojure-file? "file.clj.bak")))
    ;; .tar.clj should be recognized (unusual but valid)
    (is (true? (cb/clojure-file? "file.tar.clj")))))

;;; ---------------------------------------------------------------------------
;;; Integration with actual project
;;; ---------------------------------------------------------------------------

(deftest integration-test
  (testing "Can introspect actual project namespaces"
    (let [source-files (->> (file-seq (io/file "src/seon"))
                            (filter #(.isFile %))
                            (filter #(cb/clojure-file? (.getName %)))
                            (take 5))]
      (doseq [f source-files]
        (let [path (.getPath f)
              ns-sym (cb/file->namespace path)]
          (when ns-sym
            (testing (str "File: " path)
              (is (symbol? ns-sym))
              (is (clojure.string/starts-with? (str ns-sym) "seon"))))))))

  (testing "read-source returns valid Clojure for project files"
    (let [result (cb/read-source "src/seon/dev/codebase.clj")]
      (is (::cb/success result))
      (is (clojure.string/includes? (::cb/content result) "ns seon.dev.codebase")))))
