(ns seon.dev.verify
  "Test orchestration for the development hook.

   Provides test running and result formatting:
   - run-unit-tests - Run unit tests for specific namespaces
   - run-gen-tests - Run generative tests on schema-annotated functions
   - format-unit-result - Format unit test results for display
   - format-gen-result - Format generative test results for display

   This namespace is designed to be called from the running server via nREPL.
   It wraps clojure.test and Malli generative testing with proper result handling.

   Example usage:
     (require '[seon.dev.verify :as v])

     ;; Run unit tests for a namespace
     (v/run-unit-tests 'seon.core-test)
     ;; => {::success true ::test-count 5 ::pass-count 5 ...}

     ;; Run generative tests
     (v/run-gen-tests 'seon.core)
     ;; => {::success true ::failures []}"
  (:require [clojure.string :as str]
            [clojure.test :as test]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.instrument :as mi]
            [malli.registry :as mr]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

(schema/register! ::namespace-symbol
                  [:fn {:description "A namespace symbol (e.g., seon.core)"
                        :error/message "must be a symbol"}
                   symbol?])

(schema/register! ::test-count
                  [:int {:min 0
                         :description "Number of tests run"}])

(schema/register! ::pass-count
                  [:int {:min 0
                         :description "Number of tests passed"}])

(schema/register! ::fail-count
                  [:int {:min 0
                         :description "Number of tests failed"}])

(schema/register! ::error-count
                  [:int {:min 0
                         :description "Number of test errors"}])

(schema/register! ::unit-test-result
                  [:map
                   [::success :boolean]
                   [::test-count {:optional true} ::test-count]
                   [::pass-count {:optional true} ::pass-count]
                   [::fail-count {:optional true} ::fail-count]
                   [::error-count {:optional true} ::error-count]
                   [::output {:optional true} :string]
                   [::error {:optional true} :string]
                   [::timeout {:optional true} :boolean]
                   [::test-ns {:optional true} ::namespace-symbol]])

(schema/register! ::gen-test-failure
                  [:map
                   [::fn-symbol ::namespace-symbol]
                   [::shrunk {:optional true} :any]
                   [::error {:optional true} :any]])

(schema/register! ::gen-test-result
                  [:map
                   [::success :boolean]
                   [::failures [:vector ::gen-test-failure]]
                   [::skipped {:optional true} [:vector ::namespace-symbol]]
                   [::error {:optional true} :string]
                   [::timeout {:optional true} :boolean]])

(schema/register! ::num-tests
                  [:int {:min 1
                         :description "Number of generative tests to run per function"}])

(schema/register! ::gen-test-options
                  [:map
                   [::num-tests {:optional true} ::num-tests]])

;;; Request/Response Schemas for Public API

(schema/register! ::run-unit-tests-request
                  [:map
                   [::test-ns ::namespace-symbol]])

(schema/register! ::run-unit-tests-for-source-request
                  [:map
                   [::source-ns ::namespace-symbol]])

(schema/register! ::run-gen-tests-request
                  [:map
                   [::namespace ::namespace-symbol]
                   [::num-tests {:optional true} ::num-tests]])

(schema/register! ::format-unit-result-request
                  [:map
                   [::result ::unit-test-result]
                   [::test-ns {:optional true} ::namespace-symbol]])

(schema/register! ::format-gen-result-request
                  [:map
                   [::result ::gen-test-result]
                   [::namespace {:optional true} ::namespace-symbol]])

;;; ---------------------------------------------------------------------------
;;; Unit Test Running
;;; ---------------------------------------------------------------------------

(defn- capture-test-output
  "Run tests and capture output. Returns [output summary-map]."
  [test-fn]
  (let [output (java.io.StringWriter.)
        summary (atom nil)]
    (binding [test/*test-out* output
              test/report (fn [m]
                           ;; Capture the summary when we see it
                            (when (= :summary (:type m))
                              (reset! summary m))
                           ;; Also write to output for full results
                            (test/with-test-out
                              (test/inc-report-counter (:type m))
                              (when (#{:fail :error} (:type m))
                                (println (test/testing-vars-str m))
                                (when-let [msg (:message m)]
                                  (println msg))
                                (println "expected:" (pr-str (:expected m)))
                                (println "  actual:" (pr-str (:actual m))))))]
      (test-fn))
    [(str output) @summary]))

(defn- require-test-ns
  "Try to require a test namespace, returning error message on failure.

   Uses remove-ns before requiring to avoid alias conflicts that occur when
   reloading a namespace that already has aliases defined."
  [test-ns]
  (try
    ;; Remove namespace first to avoid alias conflicts on reload
    (when (find-ns test-ns)
      (remove-ns test-ns))
    (require test-ns :reload)
    nil
    (catch Exception e
      (str "Failed to load test namespace: " (.getMessage e)))))

(defn run-unit-tests
  "Run unit tests for a namespace.

   Reloads the test namespace and runs its tests, capturing results.
   Designed for use from nREPL in the development hook.

   Request keys:
     ::test-ns - Test namespace symbol (e.g., seon.core-test)

   Response keys:
     ::success     - true if all tests passed
     ::test-count  - Total number of tests run
     ::pass-count  - Number of passing tests
     ::fail-count  - Number of failing tests
     ::error-count - Number of test errors
     ::output      - Captured test output (failures/errors only)
     ::error       - Error message if test loading failed

   Example:
     (run-unit-tests {::test-ns 'seon.core-test})
     ;; => {::success true ::test-count 5 ::pass-count 5 ::fail-count 0 ::error-count 0}"
  {:malli/schema [:=> [:cat ::run-unit-tests-request] ::unit-test-result]}
  [{::keys [test-ns]}]
  (if-let [load-error (require-test-ns test-ns)]
    ;; Failed to load namespace
    {::success false
     ::error load-error}
    ;; Run tests
    (try
      (let [[output summary] (capture-test-output #(test/run-tests test-ns))]
        (if summary
          {::success (and (zero? (:fail summary 0))
                          (zero? (:error summary 0)))
           ::test-count (:test summary 0)
           ::pass-count (:pass summary 0)
           ::fail-count (:fail summary 0)
           ::error-count (:error summary 0)
           ::output output}
          ;; No summary found - something went wrong
          {::success false
           ::error "Could not parse test results"
           ::output output}))
      (catch Exception e
        {::success false
         ::error (str "Test execution error: " (.getMessage e))}))))

(defn run-unit-tests-for-source
  "Run unit tests for a source namespace by deriving the test namespace.

   Appends '-test' to the source namespace if not already present.

   Request keys:
     ::source-ns - Source namespace symbol (e.g., seon.core)

   Response keys:
     Same as run-unit-tests, plus:
     ::test-ns - The derived test namespace

   Example:
     (run-unit-tests-for-source {::source-ns 'seon.core})
     ;; Runs seon.core-test"
  {:malli/schema [:=> [:cat ::run-unit-tests-for-source-request] ::unit-test-result]}
  [{::keys [source-ns]}]
  (let [ns-str (str source-ns)
        test-ns (if (str/ends-with? ns-str "-test")
                  source-ns
                  (symbol (str ns-str "-test")))]
    (assoc (run-unit-tests {::test-ns test-ns})
           ::test-ns test-ns)))

;;; ---------------------------------------------------------------------------
;;; Generative Test Running
;;; ---------------------------------------------------------------------------

(defn- ensure-registry-sync!
  "Ensure Malli's default registry is in sync with our mutable schema atom.

   After namespace reloads, the Malli registry can get out of sync with
   our seon.schema/*schemas atom. This refreshes the registry binding
   to ensure all registered schemas are visible to Malli.

   This is a workaround for a timing issue where defonce in seon.schema
   may have created the registry before schemas were registered."
  []
  (mr/set-default-registry!
   (mr/composite-registry
    (m/default-schemas)
    (mr/mutable-registry @#'schema/*schemas))))

(defn- get-function-schemas
  "Get all functions with Malli schemas in a namespace.

   Returns a map of {fn-sym {:schema schema :ns ns :name name}}."
  [ns-sym]
  (get (m/function-schemas) ns-sym))

(defn- skip-gen-check?
  "True when `fn-sym` must NOT be generatively invoked.

   Generative checks call the real function with generated arguments
   against the LIVE system — for side-effecting functions that means
   real mutations (e.g. `registry/ensure-db!` creating LMDB stores
   under `data/sessions/` for generated keywords). Two general signals
   mark a function as side-effecting, no name-specific skip-list:

   - the Clojure `!`-suffix convention on the fn name
   - explicit `:seon.dev/no-gen true` var metadata (for side-effecting
     fns whose name lacks the `!`)

   A pure fn that happens to end in `!` can force checking back on with
   `:seon.dev/gen-check true` var metadata."
  [ns-sym fn-sym]
  (let [var-meta (some-> (ns-resolve ns-sym fn-sym) meta)]
    (cond
      (:seon.dev/gen-check var-meta) false
      (:seon.dev/no-gen var-meta) true
      (str/ends-with? (name fn-sym) "!") true
      :else false)))

(defn- check-function
  "Run generative tests on a single function.

   Returns nil on success or if the function can't be tested (no generator).
   Returns a failure map only for real schema violations."
  [ns-sym fn-sym num-tests]
  (when-let [schema-data (get (get-function-schemas ns-sym) fn-sym)]
    (when-let [var (ns-resolve ns-sym fn-sym)]
      (let [result (try
                     (mg/check (:schema schema-data)
                               @var
                               {:num-tests num-tests})
                     (catch Exception e
                       (let [error-type (:type (ex-data e))]
                         (if (= error-type :malli.generator/no-generator)
                           ;; No generator available - skip this function, not a failure
                           nil
                           ;; Real error - report it
                           {:error {:type :check-exception
                                    :message (ex-message e)}}))))]
        ;; mg/check returns nil on success, or a map with :shrunk on failure
        (when result
          {::fn-symbol fn-sym
           ::shrunk (:shrunk result)
           ::error (:error result)})))))

(defn run-gen-tests
  "Run generative tests on all schema-annotated functions in a namespace.

   Uses Malli's mg/check to generate random inputs based on function schemas
   and verify the functions produce valid outputs.

   Request keys:
     ::namespace - Namespace symbol (e.g., seon.core)
     ::num-tests - Optional. Tests per function (default: 10)

   Side-effecting functions are NEVER generatively invoked (they would
   mutate the live system with generated arguments). See `skip-gen-check?`
   for the mechanism (`!` suffix / `:seon.dev/no-gen`); skipped fns are
   logged and returned under ::skipped so coverage loss is visible.

   Response keys:
     ::success  - true if all tests passed
     ::failures - Vector of failure maps, each with:
                  ::fn-symbol - Function that failed
                  ::shrunk    - Shrunk counterexample (if available)
                  ::error     - Error details (if check failed)
     ::skipped  - Vector of fn symbols excluded as side-effecting

   Example:
     (run-gen-tests {::namespace 'seon.core})
     ;; => {::success true ::failures []}

     (run-gen-tests {::namespace 'seon.core ::num-tests 100})
     ;; => {::success false ::failures [{::fn-symbol process ...}]}"
  {:malli/schema [:=> [:cat ::run-gen-tests-request] ::gen-test-result]}
  [{::keys [namespace num-tests]}]
  (let [num-tests (or num-tests 10)]
    (try
      ;; Reload namespace first
      (require namespace :reload)
      ;; Ensure registry is in sync after reload (fixes defonce timing issues)
      (ensure-registry-sync!)
      ;; Collect function schemas from metadata - required for m/function-schemas to work
      ;; Use clj-collect! function directly since mi/collect! is a macro that doesn't
      ;; work well with dynamic namespace symbols at runtime
      (mi/clj-collect! {:ns namespace})
      (let [schemas (get-function-schemas namespace)
            fn-syms (sort (keys schemas))
            {skipped true checked false} (group-by #(skip-gen-check? namespace %)
                                                   fn-syms)
            skipped (vec skipped)
            _ (when (seq skipped)
                (log/info "Generative checks skipped for side-effecting fns"
                          {::namespace namespace ::skipped skipped}))
            failures (->> checked
                          (map #(check-function namespace % num-tests))
                          (remove nil?)
                          (into []))]
        {::success (empty? failures)
         ::failures failures
         ::skipped skipped})
      (catch Exception e
        {::success false
         ::failures []
         ::error (str "Failed to check namespace: " (.getMessage e))}))))

;;; ---------------------------------------------------------------------------
;;; Result Formatting
;;; ---------------------------------------------------------------------------

(defn format-unit-result
  "Format unit test result for human display.

   Returns a single-line summary string suitable for hook feedback.

   Request keys:
     ::result  - Unit test result map
     ::test-ns - Optional test namespace symbol for context

   Returns:
     Formatted string

   Example:
     (format-unit-result {::result {::success true ::test-count 5 ::pass-count 5}})
     ;; => \"5 tests passed\"

     (format-unit-result {::result {::success true ::test-count 5} ::test-ns 'seon.core-test})
     ;; => \"5 tests passed (seon.core-test)\""
  {:malli/schema [:=> [:cat ::format-unit-result-request] :string]}
  [{::keys [result test-ns]}]
  (let [ns-suffix (when test-ns (str " (" test-ns ")"))]
    (cond
      (::timeout result)
      (str "Unit tests timed out" ns-suffix)

      (::error result)
      (str "Unit test error: " (::error result) ns-suffix)

      (::success result)
      (str (::test-count result) " tests passed" ns-suffix)

      :else
      (str (::fail-count result 0) " failures, "
           (::error-count result 0) " errors out of "
           (::test-count result 0) " tests" ns-suffix))))

(defn format-gen-result
  "Format generative test result for human display.

   Returns a summary string, optionally with failure details.

   Request keys:
     ::result    - Generative test result map
     ::namespace - Optional namespace symbol for context

   Returns:
     Formatted string

   Example:
     (format-gen-result {::result {::success true ::failures []}})
     ;; => \"Generative tests passed\"

     (format-gen-result {::result {::success false ::failures [{::fn-symbol 'foo}]} ::namespace 'seon.core})
     ;; => \"Generative tests failed: foo (seon.core)\""
  {:malli/schema [:=> [:cat ::format-gen-result-request] :string]}
  [{::keys [result namespace]}]
  (let [ns-suffix (when namespace (str " (" namespace ")"))
        skipped (::skipped result)
        skip-suffix (when (seq skipped)
                      (str "; skipped " (count skipped)
                           " side-effecting: " (str/join ", " skipped)))]
    (cond
      (::timeout result)
      (str "Generative tests timed out" ns-suffix)

      (::error result)
      (str "Generative test error: " (::error result) ns-suffix)

      (::success result)
      (str "Generative tests passed" ns-suffix skip-suffix)

      :else
      (let [failed-fns (str/join ", " (map #(str (::fn-symbol %)) (::failures result)))]
        (str "Generative tests failed: "
             (if (str/blank? failed-fns) "schema errors" failed-fns)
             ns-suffix)))))


;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration
  ;; Run these after (require '[seon.dev.verify :as v])

  ;; Run unit tests
  (run-unit-tests {::test-ns 'seon.dev.context-test})

  ;; Run for source namespace (derives test ns)
  (run-unit-tests-for-source {::source-ns 'seon.dev.context})

  ;; Run generative tests
  (run-gen-tests {::namespace 'seon.dev.context})
  (run-gen-tests {::namespace 'seon.dev.context ::num-tests 20})

  ;; Format results
  (format-unit-result {::result {::success true ::test-count 5 ::pass-count 5}})
  (format-gen-result {::result {::success false ::failures [{::fn-symbol 'foo}]}})

  nil)
