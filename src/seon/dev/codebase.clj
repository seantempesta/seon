(ns seon.dev.codebase
  "Codebase introspection utilities for the development hook.

   Provides file-to-namespace mapping and source reading capabilities:
   - clojure-file? - Check if a file is a Clojure file
   - file->namespace - Parse namespace from a Clojure source file
   - file->test-namespace - Derive test namespace from source path
   - read-source - Read file contents safely
   - namespace->file - Convert namespace to likely source file path
   - test-file-exists? - Check if test file exists

   The namespace parsing is robust: it reads the actual ns declaration from
   the file rather than guessing from file paths (which breaks when the project
   path contains 'src', e.g., /Users/sean/src/seon/src/seon/schema.clj).

   All public functions use map-based APIs with namespaced keys (per CONVENTIONS.md).

   Example usage:
     (require '[seon.dev.codebase :as codebase])

     ;; Check if file is Clojure
     (codebase/clojure-file? {::codebase/file-path \"src/seon/core.clj\"})
     ;; => true

     ;; Get namespace from file
     (codebase/file->namespace {::codebase/file-path \"/path/to/src/seon/core.clj\"})
     ;; => seon.core

     ;; Read source safely
     (codebase/read-source {::codebase/file-path \"/path/to/file.clj\"})
     ;; => {::codebase/success true ::codebase/content \"(ns ...)\"}

     ;; Reverse mapping
     (codebase/namespace->file {::codebase/namespace 'seon.core})
     ;; => \"src/seon/core.clj\""
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

;; Primitive types
(schema/register! ::file-path
                  [:string {:min 1
                            :description "Path to a file (absolute or relative)"}])

(schema/register! ::namespace
                  [:fn {:description "A namespace symbol (e.g., seon.core)"
                        :error/message "must be a symbol"}
                   symbol?])

(schema/register! ::source-content
                  [:string {:description "Source code content of a file"}])

(schema/register! ::source-dir
                  [:string {:min 1
                            :description "Base source directory (e.g., 'src', 'test')"}])

;; Request schemas
(schema/register! ::clojure-file-request
                  [:map
                   [::file-path {:optional true} [:maybe :string]]])

(schema/register! ::file->namespace-request
                  [:map
                   [::file-path {:optional true} [:maybe :string]]])

(schema/register! ::file->test-namespace-request
                  [:map
                   [::file-path {:optional true} [:maybe ::file-path]]])

(schema/register! ::read-source-request
                  [:map
                   [::file-path ::file-path]])

(schema/register! ::namespace->file-request
                  [:map
                   [::namespace ::namespace]
                   [::source-dir {:optional true} ::source-dir]])

(schema/register! ::test-file-exists-request
                  [:map
                   [::namespace ::namespace]])

;; Response schemas
(schema/register! ::read-source-response
                  [:map
                   [::success :boolean]
                   [::content {:optional true} ::source-content]
                   [::error {:optional true} :string]])

;;; ---------------------------------------------------------------------------
;;; File Classification
;;; ---------------------------------------------------------------------------

(def ^:private clojure-extensions
  "Set of file extensions considered Clojure files."
  #{".clj" ".cljs" ".cljc" ".bb" ".edn"})

(defn clojure-file?
  "Check if a file path represents a Clojure file.

   Checks for common Clojure file extensions:
   - .clj (Clojure)
   - .cljs (ClojureScript)
   - .cljc (Clojure common)
   - .bb (Babashka)
   - .edn (EDN data)

   Request keys:
     ::file-path - Path to check (string, optional/nullable)

   Returns:
     Boolean - true if file has a Clojure extension, false otherwise

   Example:
     (clojure-file? {::file-path \"src/seon/core.clj\"})
     ;; => true
     (clojure-file? {::file-path \"package.json\"})
     ;; => false
     (clojure-file? {::file-path nil})
     ;; => false"
  {:malli/schema [:=> [:cat ::clojure-file-request] :boolean]}
  [{::keys [file-path]}]
  (if (nil? file-path)
    false
    (let [lower-path (str/lower-case file-path)]
      (boolean (some #(str/ends-with? lower-path %) clojure-extensions)))))

;;; ---------------------------------------------------------------------------
;;; Namespace Parsing
;;; ---------------------------------------------------------------------------

(defn- read-ns-form
  "Parse the ns form from a Clojure file.

   Reads the file and looks for the ns declaration in the first few forms.
   This is more robust than guessing from file paths.

   Returns the namespace symbol, or nil if not found."
  [file-path]
  (when-let [f (and file-path (io/file file-path))]
    (when (.exists f)
      (try
        (with-open [rdr (io/reader f)]
          (let [pbr (java.io.PushbackReader. rdr)]
            ;; Read forms until we find ns (skip comments, reader macros, etc.)
            (loop [limit 10] ; Safety limit - ns should be in first few forms
              (when (pos? limit)
                (let [form (read {:eof ::eof :read-cond :allow} pbr)]
                  (cond
                    (= form ::eof) nil
                    (and (list? form)
                         (= (first form) 'ns))
                    (second form)
                    :else (recur (dec limit))))))))
        (catch Exception _
          ;; Silently return nil on parse errors - caller handles missing ns
          nil)))))

(defn file->namespace
  "Get namespace symbol for a Clojure source file.

   Parses the ns form directly from the file rather than guessing from paths.
   This handles edge cases like projects located in paths containing 'src'.

   Request keys:
     ::file-path - Path to the Clojure file (optional/nullable)

   Returns:
     Namespace symbol (e.g., seon.core), or nil if:
     - File doesn't exist
     - File has no ns form
     - Parse error occurs

   Example:
     (file->namespace {::file-path \"src/seon/core.clj\"})
     ;; => seon.core

     (file->namespace {::file-path \"/path/to/file-without-ns.clj\"})
     ;; => nil"
  {:malli/schema [:=> [:cat ::file->namespace-request] [:maybe ::namespace]]}
  [{::keys [file-path]}]
  (read-ns-form file-path))

(defn file->test-namespace
  "Derive the test namespace from a source file.

   Parses the namespace from the source file, then appends '-test' suffix
   if not already present.

   Request keys:
     ::file-path - Path to the source file (optional/nullable)

   Returns:
     Test namespace symbol, or nil if source has no namespace

   Example:
     (file->test-namespace {::file-path \"src/seon/core.clj\"})
     ;; => seon.core-test

     (file->test-namespace {::file-path \"test/seon/core_test.clj\"})
     ;; => seon.core-test (unchanged)"
  {:malli/schema [:=> [:cat ::file->test-namespace-request] [:maybe ::namespace]]}
  [{::keys [file-path]}]
  (when-let [source-ns (file->namespace {::file-path file-path})]
    (let [ns-str (str source-ns)]
      (symbol
       (if (str/ends-with? ns-str "-test")
         ns-str
         (str ns-str "-test"))))))

;;; ---------------------------------------------------------------------------
;;; Source Reading
;;; ---------------------------------------------------------------------------

(defn read-source
  "Read the contents of a source file safely.

   Handles common error cases gracefully, returning a result map
   that indicates success/failure.

   Request keys:
     ::file-path - Path to the file to read

   Response keys:
     ::success - true if file read successfully
     ::content - File contents (on success)
     ::error   - Error message (on failure)

   Example:
     (read-source {::file-path \"src/seon/core.clj\"})
     ;; => {::success true ::content \"(ns seon.core ...)\"}

     (read-source {::file-path \"nonexistent.clj\"})
     ;; => {::success false ::error \"File does not exist\"}"
  {:malli/schema [:=> [:cat ::read-source-request] ::read-source-response]}
  [{::keys [file-path]}]
  (let [f (io/file file-path)]
    (cond
      (not (.exists f))
      {::success false
       ::error "File does not exist"}

      (not (.isFile f))
      {::success false
       ::error "Path is not a regular file"}

      (not (.canRead f))
      {::success false
       ::error "File is not readable"}

      :else
      (try
        {::success true
         ::content (slurp f)}
        (catch Exception e
          {::success false
           ::error (str "Error reading file: " (.getMessage e))})))))

;;; ---------------------------------------------------------------------------
;;; Reverse Mapping (Namespace -> File)
;;; ---------------------------------------------------------------------------

(defn namespace->file
  "Convert a namespace symbol to its likely source file path.

   Generates the conventional file path for a namespace:
   - Dots become directory separators
   - Hyphens become underscores
   - .clj extension is assumed

   Note: This is a heuristic - the file may not exist or may be in
   a different location. Use file->namespace for authoritative mapping.

   Request keys:
     ::namespace  - Namespace symbol (e.g., seon.core)
     ::source-dir - Optional base directory (default: \"src\")

   Returns:
     String file path

   Example:
     (namespace->file {::namespace 'seon.core})
     ;; => \"src/seon/core.clj\"

     (namespace->file {::namespace 'seon.foo-bar})
     ;; => \"src/seon/foo_bar.clj\"

     (namespace->file {::namespace 'seon.core ::source-dir \"other-src\"})
     ;; => \"other-src/seon/core.clj\""
  {:malli/schema [:=> [:cat ::namespace->file-request] ::file-path]}
  [{::keys [namespace source-dir]}]
  (let [source-dir (or source-dir "src")
        ns-str (str namespace)
        path (-> ns-str
                 (str/replace "." "/")
                 (str/replace "-" "_"))]
    (str source-dir "/" path ".clj")))

(defn test-file-exists?
  "Check if the test file for a namespace exists.

   Request keys:
     ::namespace - Test namespace symbol (e.g., seon.core-test)

   Returns:
     Boolean - true if test file exists

   Example:
     (test-file-exists? {::namespace 'seon.core-test})
     ;; => true (if test/seon/core_test.clj exists)"
  {:malli/schema [:=> [:cat ::test-file-exists-request] :boolean]}
  [{::keys [namespace]}]
  (let [path (namespace->file {::namespace namespace ::source-dir "test"})]
    (.exists (io/file path))))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  ;; Check file types
  (clojure-file? {::file-path "src/seon/core.clj"})       ;; => true
  (clojure-file? {::file-path "package.json"})             ;; => false
  (clojure-file? {::file-path "script.bb"})                ;; => true
  (clojure-file? {::file-path nil})                        ;; => false

  ;; Parse namespace from files
  (file->namespace {::file-path "src/seon/core.clj"})
  (file->namespace {::file-path "src/seon/dev/codebase.clj"})  ;; => seon.dev.codebase
  (file->namespace {::file-path "nonexistent.clj"})            ;; => nil

  ;; Get test namespace
  (file->test-namespace {::file-path "src/seon/core.clj"})     ;; => seon.core-test

  ;; Read source
  (read-source {::file-path "src/seon/core.clj"})
  (read-source {::file-path "nonexistent.clj"})

  ;; Reverse mapping
  (namespace->file {::namespace 'seon.core})               ;; => "src/seon/core.clj"
  (namespace->file {::namespace 'seon.foo-bar})            ;; => "src/seon/foo_bar.clj"
  (namespace->file {::namespace 'seon.core ::source-dir "other-src"})   ;; => "other-src/seon/core.clj"

  ;; Check test file exists
  (test-file-exists? {::namespace 'seon.core-test})

  nil)
