(ns seon.dev.feedback
  "REPL-side feedback utilities for the unified hook.

   Provides REPL introspection and generative testing capabilities:
   - Query Malli function schemas registered in a namespace
   - Extract schema references for context building
   - Run generative tests on schema-annotated functions
   - Store function/error/edit entities in XTDB (Phase 2)

   Entity types stored in XTDB:
   - :function - Function definitions with schemas
   - :error - Failure events for pattern detection
   - :edit-event - Edit history with results"
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.db.node :as node]
            [xtdb.api :as xt])
  (:import [java.security MessageDigest]
           [java.time Instant]
           [java.util Base64 UUID]))

;;; ---------------------------------------------------------------------------
;;; Schema Introspection
;;; ---------------------------------------------------------------------------

(defn namespace-schemas
  "Get all function schemas registered for a namespace.

   Returns a map of {fn-sym {:schema schema :ns ns :name name}} for all
   functions registered via `m/=>` in the given namespace.

   Returns nil if no schemas are registered for the namespace.

   Example:
     (namespace-schemas 'seon.trading.core)
     => {process-order {:schema [:=> [:cat :int] :string], :ns seon.trading.core, :name process-order}}"
  [ns-sym]
  ;; m/function-schemas stores schemas under namespace symbol directly
  ;; (not under :clj key as some docs suggest)
  (get (m/function-schemas) ns-sym))

(defn extract-schema-refs
  "Extract referenced schema keywords from a schema.

   Walks the schema form and collects all namespaced keywords that represent
   custom schema references (not built-in Malli types).

   Returns a set of keywords.

   Examples:
     (extract-schema-refs [:=> [:cat :user/id] :order/result])
     => #{:user/id :order/result}

     (extract-schema-refs [:map [:id :uuid] [:name :string]])
     => #{}  ; :uuid and :string are built-in types"
  [schema]
  (let [refs (atom #{})
        ;; Built-in types we should skip - these aren't registry refs
        builtin-types (set (keys (m/type-schemas)))
        ;; Get the raw form if it's a parsed schema, otherwise use as-is
        form (if (m/schema? schema)
               (m/form schema)
               schema)]
    (walk/postwalk
     (fn [x]
       ;; Namespaced keyword that's not a built-in type = registry ref
       (when (and (keyword? x)
                  (namespace x)
                  (not (contains? builtin-types x)))
         (swap! refs conj x))
       x)
     form)
    @refs))

;;; ---------------------------------------------------------------------------
;;; Generative Testing
;;; ---------------------------------------------------------------------------

(defn check-function
  "Run generative tests on a single function.

   Uses Malli's `mg/check` to generate random inputs based on the function's
   schema and verify the function produces valid outputs.

   Arguments:
     ns-sym  - Namespace symbol (e.g., 'seon.trading.core)
     fn-sym  - Function symbol (e.g., 'process-order)
     opts    - Optional map with:
               :num-tests - Number of tests to run (default: 10)

   Returns:
     nil         - If all tests pass
     {:fn fn-sym
      :error result} - If any test fails, with the shrunk counter-example

   Returns nil if the function has no registered schema."
  [ns-sym fn-sym & [{:keys [num-tests] :or {num-tests 10}}]]
  (when-let [schema-data (get (namespace-schemas ns-sym) fn-sym)]
    (when-let [var (ns-resolve ns-sym fn-sym)]
      (let [result (try
                     (mg/check (:schema schema-data)
                               @var
                               {:num-tests num-tests})
                     (catch Exception e
                       {:error {:type :check-exception
                                :message (ex-message e)}}))]
        ;; mg/check returns nil on success, or a map with :shrunk on failure
        (when result
          {:fn fn-sym
           :error result})))))

(defn check-namespace
  "Check all schema-annotated functions in a namespace.

   Runs generative tests on every function in the namespace that has a
   Malli function schema registered via `m/=>`.

   Arguments:
     ns-sym - Namespace symbol
     opts   - Optional map passed to check-function:
              :num-tests - Number of tests per function (default: 10)

   Returns:
     Vector of failure maps, each with {:fn fn-sym :error result}.
     Empty vector if all tests pass.

   Example:
     (check-namespace 'seon.trading.core {:num-tests 5})
     => []  ; all pass

     (check-namespace 'seon.broken-ns)
     => [{:fn some-fn :error {:shrunk {:smallest [...]}}}]"
  [ns-sym & [opts]]
  (let [schemas (namespace-schemas ns-sym)]
    (if (nil? schemas)
      []
      (->> (for [[fn-sym _] schemas]
             (check-function ns-sym fn-sym opts))
           (remove nil?)
           (into [])))))

;;; ---------------------------------------------------------------------------
;;; Utility Functions
;;; ---------------------------------------------------------------------------

(defn schema-fns
  "Get the set of all function symbols with schemas in a namespace.

   Example:
     (schema-fns 'seon.trading.core)
     => #{process-order validate-input create-signal}"
  [ns-sym]
  (set (keys (namespace-schemas ns-sym))))

(defn function-schema
  "Get the Malli schema for a specific function.

   Returns the schema in Malli form, or nil if no schema registered.

   Example:
     (function-schema 'seon.trading.core 'process-order)
     => [:=> [:cat :int] :string]"
  [ns-sym fn-sym]
  (when-let [schema-data (get (namespace-schemas ns-sym) fn-sym)]
    (m/form (:schema schema-data))))

(defn function-info
  "Get comprehensive info about a schema-annotated function.

   Returns a map with:
     :fn         - Function symbol
     :ns         - Namespace symbol
     :schema     - Malli schema in form notation
     :schema-refs - Set of referenced schemas
     :var-meta   - Selected var metadata (file, line)

   Returns nil if the function has no registered schema."
  [ns-sym fn-sym]
  (when-let [schema-data (get (namespace-schemas ns-sym) fn-sym)]
    (let [var (ns-resolve ns-sym fn-sym)
          schema (:schema schema-data)]
      {:fn fn-sym
       :ns ns-sym
       :schema (m/form schema)
       :schema-refs (extract-schema-refs schema)
       :var-meta (when var
                   (select-keys (meta var) [:file :line]))})))

;;; ---------------------------------------------------------------------------
;;; File Hashing
;;; ---------------------------------------------------------------------------

(defn file-hash
  "SHA-256 hash of file contents for change detection.

   Returns a Base64-encoded string of the hash, or nil if file doesn't exist.

   Example:
     (file-hash \"src/seon/foo.clj\")
     => \"a1b2c3...\" ; base64 encoded SHA-256"
  [file-path]
  (let [f (io/file file-path)]
    (when (.exists f)
      (let [content (slurp f)
            md (MessageDigest/getInstance "SHA-256")
            bytes (.digest md (.getBytes content "UTF-8"))]
        (.encodeToString (Base64/getEncoder) bytes)))))

;;; ---------------------------------------------------------------------------
;;; XTDB Storage - Functions
;;; ---------------------------------------------------------------------------

(defn fn-id
  "Convert namespace and function symbols to a keyword ID.

   Example:
     (fn-id 'seon.foo 'bar) => :seon.foo/bar"
  [ns-sym fn-sym]
  (keyword (str ns-sym) (str fn-sym)))

(defn record-function!
  "Store or update a function entity in XTDB.

   Tracks the function's schema, file location, and source hash for
   change detection. Preserves the original first-seen date on updates.

   Note: Symbols are converted to keywords for XTDB storage since XTDB v2
   doesn't support Clojure symbols directly.

   Args:
     node   - XTDB node
     ns-sym - Namespace symbol (e.g., 'seon.trading.core)
     fn-sym - Function symbol (e.g., 'process-order)

   Returns:
     Transaction result, or nil if function has no schema"
  [node ns-sym fn-sym]
  (when-let [info (function-info ns-sym fn-sym)]
    (let [id (fn-id ns-sym fn-sym)
          file-path (:file (:var-meta info))
          ;; Check for existing entity to preserve first-seen
          existing (first (node/xtql-query
                           node
                           (xt/template (from :function [{:xt/id ~id} fn/first-seen]))))
          ;; Convert symbols to keywords for XTDB storage
          ns-kw (keyword (str ns-sym))
          fn-kw (keyword (str fn-sym))
          entity {:xt/id id
                  :entity/type :function
                  :fn/namespace ns-kw
                  :fn/name fn-kw
                  :fn/schema (:schema info)
                  :fn/schema-refs (:schema-refs info)
                  :fn/file file-path
                  :fn/source-hash (when file-path (file-hash file-path))
                  :fn/first-seen (or (:fn/first-seen existing) (Instant/now))}]
      (node/execute-tx! node [[:put-docs :function entity]]))))

(defn record-error!
  "Store an error event in XTDB for pattern detection.

   Args:
     node       - XTDB node
     error-type - Keyword: :gen-test-fail, :syntax, :unit-test, :runtime
     fn-sym     - Fully qualified function keyword (e.g., :seon.foo/bar)
     data       - Error details map (message, shrunk input, stack trace, etc.)

   Returns:
     Transaction result"
  [node error-type fn-sym data]
  (let [entity {:xt/id (UUID/randomUUID)
                :entity/type :error
                :error/timestamp (Instant/now)
                :error/type error-type
                :error/function fn-sym
                :error/data data}]
    (node/execute-tx! node [[:put-docs :error entity]])))

(defn record-edit-event!
  "Store an edit event with result status.

   Args:
     node              - XTDB node
     file-path         - Path to edited file
     functions-changed - Set of function keywords that changed
     result            - Keyword: :success, :syntax-error, :test-fail, :gen-fail

   Returns:
     Transaction result"
  [node file-path functions-changed result]
  (let [entity {:xt/id (UUID/randomUUID)
                :entity/type :edit-event
                :edit/file file-path
                :edit/timestamp (Instant/now)
                :edit/functions-changed (set functions-changed)
                :edit/result result}]
    (node/execute-tx! node [[:put-docs :edit-event entity]])))

;;; ---------------------------------------------------------------------------
;;; XTDB Queries - Functions
;;; ---------------------------------------------------------------------------

(defn stored-functions
  "Get all stored functions for a namespace from XTDB.

   Returns a map of {fn-sym {:id id :schema schema :schema-refs refs ...}}
   for all functions stored for the given namespace. The fn-sym keys are
   symbols (converted from stored keywords).

   Args:
     node   - XTDB node
     ns-sym - Namespace symbol

   Returns:
     Map of function symbol to entity data, or empty map if none found"
  [node ns-sym]
  (let [ns-kw (keyword (str ns-sym))
        results (node/xtql-query
                 node
                 (xt/template
                  (from :function [{:fn/namespace ~ns-kw}
                                   xt/id fn/name fn/schema fn/schema-refs
                                   fn/file fn/source-hash fn/first-seen])))]
    (into {}
          (map (fn [row]
                 ;; Convert keyword back to symbol for API consistency
                 [(symbol (name (:fn/name row)))
                  {:id (:xt/id row)
                   :schema (:fn/schema row)
                   :schema-refs (:fn/schema-refs row)
                   :file (:fn/file row)
                   :source-hash (:fn/source-hash row)
                   :first-seen (:fn/first-seen row)}])
               results))))

(defn stored-file-hash
  "Get the stored hash for a file, or nil if not tracked.

   Looks up any function stored with this file path and returns its hash.
   Returns nil if no functions from this file are stored.

   Args:
     node      - XTDB node
     file-path - Path to file

   Returns:
     Base64-encoded hash string, or nil"
  [node file-path]
  (let [result (first (node/xtql-query
                       node
                       (xt/template
                        (-> (from :function [{:fn/file ~file-path} fn/source-hash])
                            (limit 1)))))]
    (:fn/source-hash result)))

(defn file-changed?
  "Check if file contents differ from stored hash.

   Returns true if:
   - File has no stored hash (new file)
   - File's current hash differs from stored hash

   Returns false if file hash matches stored hash.

   Args:
     node      - XTDB node
     file-path - Path to file

   Returns:
     Boolean"
  [node file-path]
  (let [stored (stored-file-hash node file-path)
        current (file-hash file-path)]
    (or (nil? stored)
        (not= stored current))))

(defn new-functions
  "Find functions that exist now but aren't stored in XTDB.

   Compares current namespace schemas against stored functions to detect
   newly created functions (for triggering AI review).

   Args:
     node   - XTDB node
     ns-sym - Namespace symbol

   Returns:
     Set of function symbols that are new (not yet stored)"
  [node ns-sym]
  (let [current (schema-fns ns-sym)
        stored (set (keys (stored-functions node ns-sym)))]
    (clojure.set/difference current stored)))

(defn function-errors
  "Get recent errors for a function.

   Args:
     node   - XTDB node
     fn-id  - Function keyword (e.g., :seon.foo/bar)
     limit  - Max errors to return (default 10)

   Returns:
     Vector of error entities, newest first"
  ([node fn-id]
   (function-errors node fn-id 10))
  ([node fn-id limit]
   (node/xtql-query
    node
    (xt/template
     (-> (from :error [{:error/function ~fn-id}
                       xt/id error/timestamp error/type error/function error/data])
         (order-by {:val error/timestamp :dir :desc})
         (limit ~limit))))))

;;; ---------------------------------------------------------------------------
;;; Pending Edit Tracking (for debounced code review)
;;; ---------------------------------------------------------------------------

(def ^:const review-debounce-seconds
  "Seconds to wait after last edit before triggering review."
  30)

(defn record-pending-edit!
  "Record an edit that's pending review.

   Stores the edit with timestamp for debounce calculation.
   Multiple edits accumulate until review is triggered.

   Args:
     node      - XTDB node
     file-path - Path to edited file
     ns-sym    - Namespace symbol
     code-diff - Summary of changes (optional)

   Returns:
     Transaction result"
  [node file-path ns-sym & [{:keys [code-diff new-functions]}]]
  (let [entity {:xt/id (UUID/randomUUID)
                :entity/type :pending-edit
                :edit/file file-path
                :edit/namespace (keyword (str ns-sym))
                :edit/timestamp (Instant/now)
                :edit/code-diff code-diff
                :edit/new-functions (set new-functions)}]
    (node/execute-tx! node [[:put-docs :pending-edit entity]])))

(defn pending-edits
  "Get all pending edits awaiting review.

   Returns:
     Vector of pending edit entities, oldest first"
  [node]
  (node/xtql-query
   node
   '(-> (from :pending-edit [xt/id edit/file edit/namespace
                             edit/timestamp edit/code-diff edit/new-functions])
        (order-by {:val edit/timestamp :dir :asc}))))

(defn oldest-pending-edit-age
  "Get seconds since oldest pending edit, or nil if none pending.

   Used for debounce: trigger review when this exceeds threshold."
  [node]
  (when-let [oldest (first (pending-edits node))]
    (let [ts (:edit/timestamp oldest)
          ;; Handle both Instant and ZonedDateTime from XTDB
          then (if (instance? Instant ts)
                 (.toEpochMilli ts)
                 (.toEpochMilli (.toInstant ts)))
          now (.toEpochMilli (Instant/now))]
      (/ (- now then) 1000.0))))

(defn should-trigger-review?
  "Check if enough time has passed to trigger a code review.

   Returns true if:
   - There are pending edits AND
   - The oldest edit is older than review-debounce-seconds"
  [node]
  (when-let [age (oldest-pending-edit-age node)]
    (> age review-debounce-seconds)))

(defn clear-pending-edits!
  "Clear all pending edits after review completes.

   Returns:
     Transaction result"
  [node]
  (let [pending (pending-edits node)
        ids (map :xt/id pending)]
    (when (seq ids)
      (node/execute-tx! node (mapv (fn [id] [:delete-docs :pending-edit id]) ids)))))

(defn pending-edits-summary
  "Get a summary of pending edits for review context.

   Returns map with:
     :files       - Set of affected file paths
     :namespaces  - Set of affected namespaces
     :new-fns     - Set of new function names
     :edit-count  - Number of edits
     :oldest-age  - Seconds since oldest edit"
  [node]
  (let [edits (pending-edits node)]
    {:files (set (map :edit/file edits))
     :namespaces (set (map :edit/namespace edits))
     :new-fns (reduce into #{} (map :edit/new-functions edits))
     :edit-count (count edits)
     :oldest-age (oldest-pending-edit-age node)}))
