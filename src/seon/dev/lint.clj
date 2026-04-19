(ns seon.dev.lint
  "Shared Clojure validation module for syntax and static analysis.

   Provides unified validation functions used by:
   - seon.dev.hook (PreToolUse validation)
   - seon.dev.clojure-replace (pre-write validation)
   - seon.dev.repair (delimiter detection)

   Main functions:
   - syntax-error? - Fast delimiter/syntax check using edamame
   - lint-source - clj-kondo static analysis
   - validate-clojure - Combined validation with structured response
   - format-findings - Human-readable error formatting

   Example usage:
     (require '[seon.dev.lint :as lint])

     ;; Quick syntax check
     (lint/syntax-error? {::lint/content \"(defn foo [x] (+ x 1\"})
     ;; => true

     ;; Full validation
     (lint/validate-clojure
       {::lint/content \"(defn foo [x] (undefined-fn x))\"
        ::lint/file-path \"src/foo.clj\"})
     ;; => {::lint/valid? false
     ;;     ::lint/errors [{:type :unresolved-symbol ...}]
     ;;     ::lint/error-count 1}"
  (:require [clj-kondo.core :as clj-kondo]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [seon.dev.suggestions :as suggestions]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

(schema/register! ::content
                  [:string {:description "Clojure source code content"}])

(schema/register! ::file-path
                  [:string {:description "File path for error context (optional)"}])

(schema/register! ::valid?
                  [:boolean {:description "Whether the code is valid"}])

(schema/register! ::error-count
                  [:int {:min 0 :description "Number of errors found"}])

(schema/register! ::warning-count
                  [:int {:min 0 :description "Number of warnings found"}])

(schema/register! ::message
                  [:maybe {:description "Error or syntax message"} :string])

;; Finding schema - uses non-namespaced keys to match clj-kondo output format.
;; This is intentional: clj-kondo returns findings with these exact keys,
;; and we preserve the format for compatibility with existing tooling.
(schema/register! ::finding
                  [:map
                   [:type :keyword]
                   [:message :string]
                   [:row :int]
                   [:col :int]
                   [:level {:optional true} :keyword]
                   [:filename {:optional true} :string]])

(schema/register! ::findings
                  [:vector ::finding])

(schema/register! ::errors
                  [:vector ::finding])

(schema/register! ::warnings
                  [:vector ::finding])

;; Request schemas
(schema/register! ::syntax-error-request
                  [:map
                   [::content [:maybe ::content]]])

(schema/register! ::lint-request
                  [:map
                   [::content ::content]
                   [::file-path {:optional true} [:maybe ::file-path]]])

(schema/register! ::validate-request
                  [:map
                   [::content [:maybe ::content]]
                   [::file-path {:optional true} [:maybe ::file-path]]])

;; Response schemas - named to match function names per CONVENTIONS.md
(schema/register! ::lint-source-response
                  [:map
                   [::valid? ::valid?]
                   [::findings ::findings]
                   [::error-count ::error-count]
                   [::warning-count ::warning-count]])

(schema/register! ::validate-clojure-response
                  [:map
                   [::valid? ::valid?]
                   [::errors ::errors]
                   [::warnings ::warnings]
                   [::error-count ::error-count]
                   [::warning-count ::warning-count]
                   [::syntax-error? {:optional true} :boolean]
                   [::syntax-message {:optional true} :string]])

(schema/register! ::format-findings-request
                  [:map
                   [::findings [:sequential ::finding]]
                   [::max-length {:optional true} [:int {:min 1}]]])

;; Response schema for syntax-check
(schema/register! ::syntax-check-response
                  [:map
                   [::valid? :boolean]
                   [::message {:optional true} ::message]])

(schema/register! ::formatted
                  [:string {:description "Human-readable formatted findings"}])

(schema/register! ::format-findings-response
                  [:map
                   [::formatted ::formatted]])

;; validate-for-write schemas
(schema/register! ::full-lint?
                  [:boolean {:description "Run full clj-kondo lint (true) or syntax-only (false)"}])

(schema/register! ::error-msg
                  [:maybe {:description "Formatted error message, nil if valid"} :string])

(schema/register! ::validate-for-write-request
                  [:map
                   [::content [:maybe ::content]]
                   [::file-path {:optional true} [:maybe ::file-path]]
                   [::full-lint? {:optional true :default true} ::full-lint?]])

(schema/register! ::validate-for-write-response
                  [:map
                   [::valid? ::valid?]
                   [::error-msg {:optional true} ::error-msg]
                   [::findings {:optional true} ::findings]])

;;; ---------------------------------------------------------------------------
;;; clj-kondo Configuration
;;; ---------------------------------------------------------------------------

(def ^:private lint-config
  "clj-kondo configuration for pre-write validation.
   Focus on errors that indicate broken code, not style issues."
  {:linters {:unresolved-symbol {:level :error}
             :unresolved-namespace {:level :error}
             :unresolved-var {:level :error}
             :invalid-arity {:level :error}
             :private-call {:level :error}       ; Catch private var access
             :deprecated-var {:level :warning}   ; Warn about deprecated
             :type-mismatch {:level :warning}
             ;; Style linters off - not errors
             :missing-else-branch {:level :off}
             :redundant-do {:level :off}
             :unused-binding {:level :off}}
   :output {:progress false}})

;;; ---------------------------------------------------------------------------
;;; edamame Configuration
;;; ---------------------------------------------------------------------------

(def ^:private edamame-opts
  "edamame parse options for syntax checking.
   Configured to accept Seon's full Clojure dialect."
  {:all true
   :read-cond :allow
   :features #{:clj :cljs}
   ;; Accept any namespace alias (e.g., ::codebase/foo)
   :auto-resolve (fn [_] 'user)
   ;; Accept any reader tag (e.g., #env, #profile, #ig/ref)
   :readers (fn [_tag] identity)})

;;; ---------------------------------------------------------------------------
;;; Syntax Checking (Fast - edamame)
;;; ---------------------------------------------------------------------------

(defn syntax-error?
  "Check if content has syntax errors (unbalanced delimiters, etc.).

   Uses edamame for fast parsing. Returns true if the content has
   delimiter-related parse errors that would prevent compilation.

   This is a FAST check (~1ms) suitable for PreToolUse validation.

   Request keys:
     ::content - Clojure source code string

   Returns:
     Boolean - true if content has syntax errors, false otherwise

   Example:
     (syntax-error? {::content \"(defn foo [x] (+ x 1\"})
     ;; => true (missing closing paren)

     (syntax-error? {::content \"(defn foo [x] (+ x 1))\"})
     ;; => false (valid syntax)"
  {:malli/schema [:=> [:cat ::syntax-error-request] :boolean]}
  [{::keys [content]}]
  (if (or (nil? content) (str/blank? content))
    false
    (try
      (edamame/parse-string-all content edamame-opts)
      false
      (catch clojure.lang.ExceptionInfo ex
        (let [data (ex-data ex)]
          ;; Return true for delimiter errors
          (boolean
           (and (= :edamame/error (:type data))
                (or (contains? data :edamame/opened-delimiter)
                    ;; Also catch "unmatched delimiter" errors
                    (when-let [msg (.getMessage ex)]
                      (re-find #"(?i)unmatched|unclosed|expected.*\]|\)|\}" msg))))))))))

(defn- get-syntax-error-message
  "Extract syntax error message from content.
   Returns nil if no syntax error."
  [content]
  (when (and content (not (str/blank? content)))
    (try
      (edamame/parse-string-all content edamame-opts)
      nil
      (catch clojure.lang.ExceptionInfo ex
        (.getMessage ex)))))

(defn syntax-check
  "Check syntax and return detailed result.

   Like syntax-error? but returns the error message for better feedback.
   This is a FAST check (~1ms) suitable for PreToolUse validation.

   Request keys:
     ::content - Clojure source code string

   Returns:
     {::valid? true/false, ::message \"error details\" or nil}

   Example:
     (syntax-check {::content \"(defn foo [x] (+ x 1\"})
     ;; => {::valid? false ::message \"EOF while reading...\"}"
  {:malli/schema [:=> [:cat ::syntax-error-request] ::syntax-check-response]}
  [{::keys [content]}]
  (if (or (nil? content) (str/blank? content))
    {::valid? true ::message nil}
    (if-let [msg (get-syntax-error-message content)]
      {::valid? false ::message msg}
      {::valid? true ::message nil})))

;;; ---------------------------------------------------------------------------
;;; Static Analysis (clj-kondo)
;;; ---------------------------------------------------------------------------

(defn lint-source
  "Run clj-kondo static analysis on source string.

   This is PURE STATIC ANALYSIS - no code execution, no side effects.
   Safe to run on any code. Takes ~50-100ms for typical files.

   Request keys:
     ::content   - Clojure source code string
     ::file-path - Optional file path for error context

   Response keys:
     ::valid?        - true if no errors (warnings allowed)
     ::findings      - Vector of all findings (errors + warnings)
     ::error-count   - Number of errors
     ::warning-count - Number of warnings

   Example:
     (lint-source {::content \"(defn foo [x] (undefined-fn x))\"
                   ::file-path \"src/foo.clj\"})
     ;; => {::valid? false
     ;;     ::findings [{:type :unresolved-symbol ...}]
     ;;     ::error-count 1
     ;;     ::warning-count 0}"
  {:malli/schema [:=> [:cat ::lint-request] ::lint-source-response]}
  [{::keys [content file-path]}]
  (let [file-path (or file-path "<stdin>")]
    (try
      (let [result (with-in-str content
                     (clj-kondo/run! {:lint ["-"]
                                      :filename file-path
                                      :config lint-config
                                      :cache false}))
            findings (:findings result)
            summary (:summary result)
            errors (or (:error summary) 0)
            warnings (or (:warning summary) 0)]
        {::valid? (zero? errors)
         ::findings (vec findings)
         ::error-count errors
         ::warning-count warnings})
      (catch Exception e
        {::valid? false
         ::findings [{:type :lint-error
                      :message (str "clj-kondo failed: " (.getMessage e))
                      :row 1
                      :col 1
                      :level :error}]
         ::error-count 1
         ::warning-count 0}))))

;;; ---------------------------------------------------------------------------
;;; Combined Validation
;;; ---------------------------------------------------------------------------

(defn validate-clojure
  "Validate Clojure source code with both syntax and static analysis.

   Performs two-stage validation:
   1. Fast syntax check (edamame) - catches delimiter errors
   2. Static analysis (clj-kondo) - catches undefined symbols, arity errors

   If syntax check fails, returns immediately without running clj-kondo.

   Request keys:
     ::content   - Clojure source code string
     ::file-path - Optional file path for error context

   Response keys:
     ::valid?         - true if no errors (warnings allowed)
     ::errors         - Vector of error findings
     ::warnings       - Vector of warning findings
     ::error-count    - Number of errors
     ::warning-count  - Number of warnings
     ::syntax-error?  - true if syntax error detected (optional)
     ::syntax-message - Syntax error message (optional)

   Example:
     (validate-clojure
       {::content \"(defn foo [x] (+ x 1))\"
        ::file-path \"src/foo.clj\"})
     ;; => {::valid? true ::errors [] ::warnings [] ...}"
  {:malli/schema [:=> [:cat ::validate-request] ::validate-clojure-response]}
  [{::keys [content file-path] :as request}]
  (cond
    ;; Empty/nil content is valid (nothing to validate)
    (or (nil? content) (str/blank? content))
    {::valid? true
     ::errors []
     ::warnings []
     ::error-count 0
     ::warning-count 0}

    ;; Check syntax first (fast path for obvious errors)
    (syntax-error? {::content content})
    (let [msg (get-syntax-error-message content)]
      {::valid? false
       ::errors [{:type :syntax-error
                  :message (or msg "Invalid Clojure syntax")
                  :row 1
                  :col 1}]
       ::warnings []
       ::error-count 1
       ::warning-count 0
       ::syntax-error? true
       ::syntax-message msg})

    ;; Syntax OK - run full static analysis
    :else
    (let [lint-result (lint-source request)
          findings (::findings lint-result)
          errors (filterv #(= :error (:level %)) findings)
          warnings (filterv #(= :warning (:level %)) findings)]
      {::valid? (::valid? lint-result)
       ::errors errors
       ::warnings warnings
       ::error-count (::error-count lint-result)
       ::warning-count (::warning-count lint-result)})))

;;; ---------------------------------------------------------------------------
;;; Formatting
;;; ---------------------------------------------------------------------------

(defn format-findings
  "Format lint findings for human-readable output.

   Request keys:
     ::findings   - Vector of findings to format
     ::max-length - Optional max length for truncation

   Returns:
     ::formatted - Human-readable string

   Example:
     (format-findings
       {::findings [{:type :unresolved-symbol :message \"...\" :row 5 :col 3}]})
     ;; => \"  Line 5, Col 3 [error]: ...\""
  {:malli/schema [:=> [:cat ::format-findings-request] ::format-findings-response]}
  [{::keys [findings max-length]}]
  (let [formatted (str/join "\n"
                            (map (fn [{:keys [type message row col level]}]
                                   (format "  Line %d, Col %d [%s]: %s"
                                           row col (name (or level type)) message))
                                 findings))]
    {::formatted (if (and max-length (> (count formatted) max-length))
                   (str (subs formatted 0 (- max-length 3)) "...")
                   formatted)}))

;;; ---------------------------------------------------------------------------
;;; PreToolUse Error Formatting
;;; ---------------------------------------------------------------------------

(schema/register! ::prefix
                  [:string {:description "Error message prefix (e.g., 'Edit would create invalid Clojure')"}])

(schema/register! ::result
                  [:map {:description "Validation result from validate-clojure"}])

(schema/register! ::pretooluse-error-request
                  [:map
                   [::prefix ::prefix]
                   [::result ::result]])

(schema/register! ::format-pretooluse-error-response
                  [:string {:description "Formatted error message for PreToolUse blocking"}])

(defn format-pretooluse-error
  "Format a validation error for PreToolUse blocking with detailed guidance.

   Provides actionable feedback including:
   - Syntax error details (for delimiter errors)
   - clj-kondo findings with line/column (for semantic errors)
   - Guidance on how to fix common issues

   Request keys:
     ::prefix - Error prefix (e.g., 'Edit would create invalid Clojure')
     ::result - Validation result from validate-clojure

   Returns:
     String - Human-readable error message for Claude Code

   Example:
     (format-pretooluse-error
       {::prefix \"Edit would create invalid Clojure\"
        ::result {::valid? false ::errors [...] ::syntax-error? true}})
     ;; => \"Edit would create invalid Clojure.\\n\\nSYNTAX ERROR: ...\""
  {:malli/schema [:=> [:cat ::pretooluse-error-request] ::format-pretooluse-error-response]}
  [{::keys [prefix result]}]
  (let [syntax-error? (::syntax-error? result)
        errors (::errors result)]
    (if syntax-error?
      ;; Syntax error - detailed delimiter guidance
      (str prefix ".\n\n"
           "SYNTAX ERROR: " (or (::syntax-message result)
                                (-> errors first :message)
                                "Unbalanced delimiters") "\n\n"
           "Common causes:\n"
           "- Missing closing paren/bracket/brace\n"
           "- Extra closing delimiter\n"
           "- Unclosed string literal\n\n"
           "Fix: Check delimiter balance in your new_string. "
           "If the file was already broken, make ONE edit that fixes ALL syntax issues.")
      ;; clj-kondo errors - show findings with suggestions
      (let [;; Enrich with "Did you mean?" suggestions
            enriched (::suggestions/findings
                      (suggestions/enrich-findings {::suggestions/findings errors}))
            error-lines (->> enriched
                             (take 5)  ; Limit to first 5 errors
                             (map (fn [{:keys [message row col end-row end-col]
                                        ::suggestions/keys [suggestion]}]
                                    (let [location (if (and end-row end-col
                                                            (or (not= row end-row) (not= col end-col)))
                                                     (format "%d:%d-%d:%d" row col end-row end-col)
                                                     (format "Line %d, Col %d" row col))
                                          hint (when suggestion
                                                 (format " (did you mean '%s'?)" suggestion))]
                                      (format "  %s: %s%s" location message (or hint "")))))
                             (str/join "\n"))
            more-count (- (count errors) 5)]
        (str prefix ".\n\n"
             "LINT ERRORS:\n"
             error-lines
             (when (pos? more-count)
               (format "\n  ... and %d more error(s)" more-count))
             "\n\nFix: Ensure all symbols are defined and function calls have correct arity.")))))

;;; ---------------------------------------------------------------------------
;;; Unified Pre-Write Validation
;;; ---------------------------------------------------------------------------

(defn validate-for-write
  "Unified pre-write validation for all Clojure editing tools.

   Performs validation and formats errors with 'did you mean?' suggestions.
   Used by both hook.clj (PreToolUse) and clojure_replace.clj (MCP).

   This is the SINGLE SOURCE OF TRUTH for pre-write validation. Both paths
   call this function to ensure consistent error messages and suggestions.

   Request keys:
     ::content    - Clojure source code to validate
     ::file-path  - File path for error context (optional)
     ::full-lint? - true for full clj-kondo, false for syntax-only (default: true)

   Response keys:
     ::valid?    - true if validation passes
     ::error-msg - Formatted error message with suggestions (only if invalid)
     ::findings  - Raw findings for structured access (only if invalid)

   Example:
     ;; Full validation (default)
     (validate-for-write {::content \"(defn foo [x] (mpa identity x))\"
                          ::file-path \"src/seon/foo.clj\"})
     ;; => {::valid? false
     ;;     ::error-msg \"Invalid Clojure...did you mean 'map'?...\"
     ;;     ::findings [...]}

     ;; Syntax-only (for non-project files)
     (validate-for-write {::content \"(defn foo [x\"
                          ::full-lint? false})
     ;; => {::valid? false ::error-msg \"Invalid Clojure...SYNTAX ERROR...\"}"
  {:malli/schema [:=> [:cat ::validate-for-write-request] ::validate-for-write-response]}
  [{::keys [content file-path full-lint?] :or {full-lint? true}}]
  (if full-lint?
    ;; Full validation: syntax + clj-kondo with suggestions
    (let [result (validate-clojure {::content content ::file-path file-path})]
      (if (::valid? result)
        {::valid? true}
        {::valid? false
         ::error-msg (format-pretooluse-error
                      {::prefix "Invalid Clojure"
                       ::result result})
         ::findings (::errors result)}))
    ;; Syntax-only: fast check for non-project files
    (let [check (syntax-check {::content content})]
      (if (::valid? check)
        {::valid? true}
        {::valid? false
         ::error-msg (format-pretooluse-error
                      {::prefix "Invalid Clojure"
                       ::result {::syntax-error? true
                                 ::syntax-message (::message check)
                                 ::errors []}})
         ::findings []}))))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  ;; Check syntax errors
  (syntax-error? {::content "(defn foo [x] (+ x 1"})  ;; => true
  (syntax-error? {::content "(defn foo [x] (+ x 1))"}) ;; => false

  ;; Run clj-kondo
  (lint-source {::content "(defn foo [x] (undefined-fn x))"
                ::file-path "test.clj"})

  ;; Combined validation
  (validate-clojure {::content "(defn foo [x] (+ x 1))"})
  (validate-clojure {::content "(defn foo [x] (undefined-fn x))"})

  ;; Format findings
  (format-findings {::findings [{:type :unresolved-symbol
                                  :message "Unresolved symbol: undefined-fn"
                                  :row 1
                                  :col 16
                                  :level :error}]})

  nil)
