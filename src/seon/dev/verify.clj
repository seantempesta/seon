(ns seon.dev.verify
  "Test orchestration for the development hook.

   Provides test running and result formatting:
   - run-unit-tests - Run unit tests for specific namespaces
   - run-gen-tests - Run generative tests on schema-annotated functions
   - format-results - Format test results for display

   This namespace is designed to be called from the running server via nREPL.
   It wraps clojure.test and Malli generative testing with proper result handling.

   Example usage:
     (require '[seon.dev.verify :as v])

     ;; Run unit tests for a namespace
     (v/run-unit-tests 'seon.core-test)
     ;; => {::success true ::test-count 5 ::pass-count 5 ...}

     ;; Run generative tests
     (v/run-gen-tests 'seon.core)
     ;; => {::success true ::failures []}

     ;; Format results for display
     (v/format-results {::success false ::fail-count 2})"
  (:require [clojure.string :as str]
            [clojure.test :as test]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.instrument :as mi]
            [seon.schema :as schema]))

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
                   [::timeout {:optional true} :boolean]])

(schema/register! ::gen-test-failure
                  [:map
                   [::fn-symbol ::namespace-symbol]
                   [::shrunk {:optional true} :any]
                   [::error {:optional true} :any]])

(schema/register! ::gen-test-result
                  [:map
                   [::success :boolean]
                   [::failures [:vector ::gen-test-failure]]
                   [::error {:optional true} :string]
                   [::timeout {:optional true} :boolean]])

(schema/register! ::num-tests
                  [:int {:min 1
                         :description "Number of generative tests to run per function"}])

(schema/register! ::gen-test-options
                  [:map
                   [::num-tests {:optional true} ::num-tests]])

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
  "Try to require a test namespace, returning error message on failure."
  [test-ns]
  (try
    (require test-ns :reload)
    nil
    (catch Exception e
      (str "Failed to load test namespace: " (.getMessage e)))))

(defn run-unit-tests
  "Run unit tests for a namespace.

   Reloads the test namespace and runs its tests, capturing results.
   Designed for use from nREPL in the development hook.

   Request keys:
     test-ns - Test namespace symbol (e.g., seon.core-test)

   Response keys:
     ::success     - true if all tests passed
     ::test-count  - Total number of tests run
     ::pass-count  - Number of passing tests
     ::fail-count  - Number of failing tests
     ::error-count - Number of test errors
     ::output      - Captured test output (failures/errors only)
     ::error       - Error message if test loading failed

   Example:
     (run-unit-tests 'seon.core-test)
     ;; => {::success true ::test-count 5 ::pass-count 5 ::fail-count 0 ::error-count 0}"
  {:malli/schema [:=> [:cat ::namespace-symbol] ::unit-test-result]}
  [test-ns]
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
     source-ns - Source namespace symbol (e.g., seon.core)

   Response keys:
     Same as run-unit-tests, plus:
     ::test-ns - The derived test namespace

   Example:
     (run-unit-tests-for-source 'seon.core)
     ;; Runs seon.core-test"
  {:malli/schema [:=> [:cat ::namespace-symbol] ::unit-test-result]}
  [source-ns]
  (let [ns-str (str source-ns)
        test-ns (if (str/ends-with? ns-str "-test")
                  source-ns
                  (symbol (str ns-str "-test")))]
    (assoc (run-unit-tests test-ns)
           ::test-ns test-ns)))

;;; ---------------------------------------------------------------------------
;;; Generative Test Running
;;; ---------------------------------------------------------------------------

(defn- get-function-schemas
  "Get all functions with Malli schemas in a namespace.

   Returns a map of {fn-sym {:schema schema :ns ns :name name}}."
  [ns-sym]
  (get (m/function-schemas) ns-sym))

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
     ns-sym - Namespace symbol (e.g., seon.core)
     opts   - Optional map with:
              ::num-tests - Tests per function (default: 10)

   Response keys:
     ::success  - true if all tests passed
     ::failures - Vector of failure maps, each with:
                  ::fn-symbol - Function that failed
                  ::shrunk    - Shrunk counterexample (if available)
                  ::error     - Error details (if check failed)

   Example:
     (run-gen-tests 'seon.core)
     ;; => {::success true ::failures []}

     (run-gen-tests 'seon.core {::num-tests 100})
     ;; => {::success false ::failures [{::fn-symbol process ...}]}"
  {:malli/schema [:=> [:cat ::namespace-symbol [:? ::gen-test-options]] ::gen-test-result]}
  ([ns-sym]
   (run-gen-tests ns-sym {}))
  ([ns-sym opts]
   (let [num-tests (or (::num-tests opts) 10)]
     (try
       ;; Reload namespace first
       (require ns-sym :reload)
       ;; Collect function schemas from metadata - required for m/function-schemas to work
       (mi/collect! {:ns ns-sym})
       (let [schemas (get-function-schemas ns-sym)
             failures (if (nil? schemas)
                        []
                        (->> (for [[fn-sym _] schemas]
                               (check-function ns-sym fn-sym num-tests))
                             (remove nil?)
                             (into [])))]
         {::success (empty? failures)
          ::failures failures})
       (catch Exception e
         {::success false
          ::failures []
          ::error (str "Failed to check namespace: " (.getMessage e))})))))

;;; ---------------------------------------------------------------------------
;;; Result Formatting
;;; ---------------------------------------------------------------------------

(defn format-unit-result
  "Format unit test result for human display.

   Returns a single-line summary string suitable for hook feedback.

   Example:
     (format-unit-result {::success true ::test-count 5 ::pass-count 5 ...})
     ;; => \"5 tests passed (seon.core-test)\""
  {:malli/schema [:=> [:cat ::unit-test-result [:? ::namespace-symbol]] :string]}
  ([result]
   (format-unit-result result nil))
  ([result test-ns]
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
            (::test-count result 0) " tests" ns-suffix)))))

(defn format-gen-result
  "Format generative test result for human display.

   Returns a summary string, optionally with failure details.

   Example:
     (format-gen-result {::success true ::failures []})
     ;; => \"Generative tests passed\"

     (format-gen-result {::success false ::failures [{::fn-symbol 'foo}]})
     ;; => \"Generative tests failed: foo\""
  {:malli/schema [:=> [:cat ::gen-test-result [:? ::namespace-symbol]] :string]}
  ([result]
   (format-gen-result result nil))
  ([result ns-sym]
   (let [ns-suffix (when ns-sym (str " (" ns-sym ")"))]
     (cond
       (::timeout result)
       (str "Generative tests timed out" ns-suffix)

       (::error result)
       (str "Generative test error: " (::error result) ns-suffix)

       (::success result)
       (str "Generative tests passed" ns-suffix)

       :else
       (let [failed-fns (str/join ", " (map #(str (::fn-symbol %)) (::failures result)))]
         (str "Generative tests failed: "
              (if (str/blank? failed-fns) "schema errors" failed-fns)
              ns-suffix))))))

(defn format-results
  "Format any test result (unit or gen) for display.

   Detects the result type and calls the appropriate formatter.
   Used by the hook to generate feedback messages.

   Example:
     (format-results unit-result 'seon.core-test)
     (format-results gen-result 'seon.core)"
  ([result]
   (format-results result nil))
  ([result ns-sym]
   (cond
     ;; Unit test result (has ::test-count or ::fail-count)
     (or (contains? result ::test-count)
         (contains? result ::fail-count)
         (contains? result ::pass-count))
     (format-unit-result result ns-sym)

     ;; Gen test result (has ::failures)
     (contains? result ::failures)
     (format-gen-result result ns-sym)

     ;; Unknown - just stringify
     :else
     (str result))))

;;; ---------------------------------------------------------------------------
;;; Combined Testing (convenience)
;;; ---------------------------------------------------------------------------

(defn check-namespace
  "Run both unit and generative tests for a namespace.

   Runs unit tests first (if test file exists), then generative tests.
   Returns a combined result.

   Request keys:
     source-ns - Source namespace symbol (e.g., seon.core)
     opts      - Optional map with:
                 ::num-tests     - Gen tests per function (default: 10)
                 ::skip-unit     - Skip unit tests (default: false)
                 ::skip-gen      - Skip generative tests (default: false)

   Response keys:
     ::success    - true if all tests passed
     ::unit       - Unit test result (or nil if skipped)
     ::gen        - Generative test result (or nil if skipped)
     ::messages   - Vector of formatted result messages"
  ([source-ns]
   (check-namespace source-ns {}))
  ([source-ns opts]
   (let [skip-unit (::skip-unit opts false)
         skip-gen (::skip-gen opts false)

         ;; Run unit tests
         unit-result (when-not skip-unit
                       (run-unit-tests-for-source source-ns))

         ;; Run gen tests
         gen-result (when-not skip-gen
                      (run-gen-tests source-ns opts))

         ;; Overall success
         success (and (or skip-unit (::success unit-result))
                      (or skip-gen (::success gen-result)))

         ;; Format messages
         messages (cond-> []
                    unit-result (conj (format-unit-result unit-result (::test-ns unit-result)))
                    gen-result (conj (format-gen-result gen-result source-ns)))]

     {::success success
      ::unit unit-result
      ::gen gen-result
      ::messages messages})))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration
  ;; Run these after (require '[seon.dev.verify :as v])

  ;; Run unit tests
  (run-unit-tests 'seon.dev.context-test)

  ;; Run for source namespace (derives test ns)
  (run-unit-tests-for-source 'seon.dev.context)

  ;; Run generative tests
  (run-gen-tests 'seon.dev.context)
  (run-gen-tests 'seon.dev.context {::num-tests 20})

  ;; Format results
  (format-unit-result {::success true ::test-count 5 ::pass-count 5})
  (format-gen-result {::success false ::failures [{::fn-symbol 'foo}]})

  ;; Run both
  (check-namespace 'seon.dev.context)

  nil)
