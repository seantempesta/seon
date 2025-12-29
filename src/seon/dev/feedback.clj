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
            [seon.db.node :as node])
  (:import [java.security MessageDigest]
           [java.time Duration Instant]
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
          ;; Check for existing entity to preserve first-seen - use SQL with parameterized _id
          existing (first (node/sql-query
                           node
                           ["SELECT * FROM function WHERE _id = ?" id]))
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
        ;; Use SQL and filter in Clojure - XTQL has issues with namespaced columns
        all-fns (node/sql-query node "SELECT * FROM function")
        results (filter #(= ns-kw (:fn/namespace %)) all-fns)]
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
  ;; Use SQL and filter in Clojure - XTQL has issues with this version
  (let [all-fns (node/sql-query node "SELECT * FROM function")
        result (first (filter #(= file-path (:fn/file %)) all-fns))]
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
  ([node fn-id limit-n]
   ;; Use SQL with parameterized query - _id filtering works with keywords
   (let [all-errors (node/sql-query node "SELECT * FROM error ORDER BY error$timestamp DESC")
         matching (filter #(= fn-id (:error/function %)) all-errors)]
     (vec (take limit-n matching)))))

;;; ---------------------------------------------------------------------------
;;; Bounded Window Edit Tracking (new model)
;;; ---------------------------------------------------------------------------
;;
;; This model replaces the "pending edits" approach with:
;; - Edit events as immutable facts (never deleted)
;; - A singleton tracking last review time
;; - Bounded queries that only look at recent edits
;;
;; Safety: Even if reviews stop, we only process a bounded time window.

(defn- instant->epoch-millis
  "Convert Instant or ZonedDateTime to epoch milliseconds."
  [t]
  (if (instance? Instant t)
    (.toEpochMilli t)
    (.toEpochMilli (.toInstant t))))

(defn- seconds-since
  "Calculate seconds elapsed since the given instant."
  [then now]
  (/ (- (instant->epoch-millis now) (instant->epoch-millis then)) 1000.0))

(defn record-edit!
  "Record an edit event. Immutable fact, never deleted.

   Uses XTDB valid-time for timestamp (automatic via put-docs).

   Args:
     node - XTDB node
     file-path - Absolute path to edited file
     ns-sym - Namespace symbol (e.g., 'seon.foo)
     opts - Optional {:new-functions #{...}}

   Returns:
     Transaction result"
  [node file-path ns-sym & [opts]]
  (let [entity {:xt/id (UUID/randomUUID)
                :entity/type :edit-event
                :edit/file file-path
                :edit/namespace (keyword (str ns-sym))
                :edit/new-functions (set (:new-functions opts))}]
    (node/execute-tx! node [[:put-docs :edit-event entity]])))

(defn record-review-completed!
  "Update the review state singleton to now.

   Args:
     node - XTDB node

   Returns:
     Transaction result"
  [node]
  (let [entity {:xt/id :seon.dev/review-state
                :entity/type :review-state
                :review/last-completed (Instant/now)}]
    (node/execute-tx! node [[:put-docs :review-state entity]])))

(defn get-last-edit-time
  "Get timestamp of most recent edit, or nil if none.

   Queries the most recent edit event by valid-time."
  [node]
  (let [result (first (node/sql-query
                       node
                       "SELECT _valid_from FROM edit_event ORDER BY _valid_from DESC LIMIT 1"))]
    (:xt/valid-from result)))

(defn get-last-review-time
  "Get timestamp of last completed review, or nil if never reviewed."
  [node]
  ;; Use parameterized SQL query - _id filtering works with keywords
  (let [result (first (node/sql-query
                       node
                       ["SELECT * FROM review_state WHERE _id = ?" :seon.dev/review-state]))]
    (:review/last-completed result)))

(defn recent-unreviewed-edits
  "Get edits from the recent window that haven't been reviewed.

   Always bounded by:
   1. lookback-minutes - Only look at last N minutes of edits
   2. last-review-time - Only include edits after last review

   Even if no review has ever happened, we only get recent edits.

   Args:
     node - XTDB node
     opts - {:lookback-minutes 5}

   Returns:
     Vector of edit event maps, oldest first"
  [node & [{:keys [lookback-minutes] :or {lookback-minutes 5}}]]
  (let [now (Instant/now)
        window-start (.minus now (Duration/ofMinutes lookback-minutes))
        last-review (get-last-review-time node)
        ;; Use whichever is more recent: window start or last review
        cutoff (if (and last-review (.isAfter last-review window-start))
                 last-review
                 window-start)]
    ;; Use SQL with parameterized timestamp for the cutoff
    (node/sql-query
     node
     ["SELECT *, _valid_from FROM edit_event WHERE _valid_from > ? ORDER BY _valid_from"
      cutoff])))

(defn unreviewed-summary
  "Get summary of unreviewed edits for review context.

   Args:
     node - XTDB node
     opts - {:lookback-minutes 5}

   Returns:
     {:files #{...}
      :namespaces #{...}
      :new-fns #{...}
      :edit-count N}"
  [node & [opts]]
  (let [edits (recent-unreviewed-edits node opts)]
    {:files (set (map :edit/file edits))
     :namespaces (set (map :edit/namespace edits))
     :new-fns (reduce into #{} (map :edit/new-functions edits))
     :edit-count (count edits)}))

(defn should-trigger-review?
  "Determine if a review should trigger now.

   Returns true if ALL conditions met:
   1. There are unreviewed edits in the recent window
   2. User has stopped typing (debounce met)
   3. Enough time since last review (cooldown met)

   Args:
     node - XTDB node
     opts - {:debounce-seconds 5
             :cooldown-seconds 30
             :lookback-minutes 5}"
  [node & [{:keys [debounce-seconds cooldown-seconds lookback-minutes]
            :or {debounce-seconds 5 cooldown-seconds 30 lookback-minutes 5}}]]
  (let [now (Instant/now)
        last-edit-time (get-last-edit-time node)
        last-review-time (get-last-review-time node)
        recent-edits (recent-unreviewed-edits node {:lookback-minutes lookback-minutes})]

    (and
     ;; 1. Have something to review
     (seq recent-edits)

     ;; 2. User stopped typing (debounce)
     (or (nil? last-edit-time)
         (> (seconds-since last-edit-time now) debounce-seconds))

     ;; 3. Not reviewing too frequently (cooldown)
     (or (nil? last-review-time)
         (> (seconds-since last-review-time now) cooldown-seconds)))))

;;; ---------------------------------------------------------------------------
;;; File Stability for Cache-Optimized Ordering
;;; ---------------------------------------------------------------------------

(defn file-stability-data
  "Query edit history to compute stability metrics for each file.
   Returns map of {file-path {:edit-count N :first-seen Instant :last-edit Instant}}"
  [node]
  ;; Fetch all edits and aggregate in Clojure - SQL aggregation has issues
  (let [all-edits (node/sql-query node "SELECT * FROM edit_event")
        by-file (group-by :edit/file all-edits)
        ;; Compare timestamps using epoch millis
        ts-compare (fn [a b]
                     (let [a-ms (instant->epoch-millis a)
                           b-ms (instant->epoch-millis b)]
                       (compare a-ms b-ms)))]
    (into {}
          (map (fn [[file-path edits]]
                 (let [timestamps (keep :edit/timestamp edits)]
                   [file-path
                    {:edit-count (count edits)
                     :first-seen (when (seq timestamps) (first (sort ts-compare timestamps)))
                     :last-edit (when (seq timestamps) (last (sort ts-compare timestamps)))}]))
               by-file))))

(defn stability-score
  "Calculate stability score. Higher = more stable = should come first.
   Score = days-since-first-seen / edit-count"
  [edit-count first-seen]
  (let [now (Instant/now)
        first-seen-ms (if (instance? Instant first-seen)
                        (.toEpochMilli first-seen)
                        (.toEpochMilli (.toInstant first-seen)))
        age-ms (- (.toEpochMilli now) first-seen-ms)
        age-days (/ age-ms (* 1000.0 60 60 24))]
    (if (and (pos? edit-count) (pos? age-days))
      (/ age-days edit-count)
      Double/MAX_VALUE)))

(defn order-by-stability
  "Order files for cache-optimized Gemini requests.
   Stable files first, recently-edited files last."
  [stability-data file-paths pending-files]
  (let [pending-set (set pending-files)
        regular (remove pending-set file-paths)
        pending (filter pending-set file-paths)

        regular-sorted (sort-by (fn [f]
                                  (let [data (get stability-data f)
                                        score (if data
                                                (stability-score (:edit-count data)
                                                                 (:first-seen data))
                                                0.0)]
                                    [(- score) f]))
                                regular)

        pending-sorted (sort-by (fn [f]
                                  (let [last-edit (get-in stability-data [f :last-edit])]
                                    (if last-edit
                                      (if (instance? Instant last-edit)
                                        (.toEpochMilli last-edit)
                                        (.toEpochMilli (.toInstant last-edit)))
                                      Long/MAX_VALUE)))
                                pending)]
    (vec (concat regular-sorted pending-sorted))))
