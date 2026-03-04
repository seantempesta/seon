(ns seon.dev.review
  "AI code review for the development hook.

   Extracts Gemini review logic from bin/seon-hook into clean, testable Clojure:
   - build-context - Build context for AI review from edits
   - call-gemini - Call Gemini for code review
   - format-output - Format review output for display

   This namespace uses the existing seon.ai.gemini client for API calls.
   Reviews are advisory (never blocking) and rate-limited by seon.dev.context.

   Example usage:
     (require '[seon.dev.review :as review])

     ;; Build context from edit summary
     (review/build-context {::review/files #{\"/path/to/file.clj\"}
                            ::review/test-results {...}})
     ;; => {:prompt \"...\" :code \"...\" :conventions \"...\"}

     ;; Call Gemini for review
     (review/call-gemini {::review/context {...}})
     ;; => \"Review text...\"

     ;; Format for display
     (review/format-output {::review/text \"Review...\"
                            ::review/max-length 500})"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [seon.ai.gemini :as gemini]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

(schema/register! ::file-path
                  [:string {:min 1
                            :description "Absolute path to a file"}])

(schema/register! ::namespace
                  [:keyword {:description "Namespace keyword (e.g., :seon.foo)"}])

(schema/register! ::files
                  [:set ::file-path])

(schema/register! ::namespaces
                  [:set ::namespace])

(schema/register! ::new-functions
                  [:set :symbol])

(schema/register! ::test-summary
                  [:string {:description "Summary of test results for context"}])

(schema/register! ::conventions
                  [:string {:description "Project conventions text"}])

(schema/register! ::code
                  [:string {:description "Code to review"}])

(schema/register! ::prompt
                  [:string {:min 1
                            :description "Prompt for the review"}])

;; Review context - what we pass to Gemini
(schema/register! ::review-context
                  [:map
                   [::prompt ::prompt]
                   [::code ::code]
                   [::conventions {:optional true} [:maybe ::conventions]]
                   [::test-summary {:optional true} [:maybe ::test-summary]]
                   [::new-functions {:optional true} [:maybe ::new-functions]]])

;; Build context request
(schema/register! ::build-context-request
                  [:map
                   [::files ::files]
                   [::test-results {:optional true} [:maybe :map]]
                   [::new-functions {:optional true} [:maybe ::new-functions]]
                   [::max-code-length {:optional true} [:int {:min 1}]]
                   [::max-conventions-length {:optional true} [:int {:min 1}]]])

;; Call gemini request
(schema/register! ::call-gemini-request
                  [:map
                   [::context ::review-context]
                   [::timeout {:optional true} [:maybe [:int {:min 1}]]]
                   [::api-key {:optional true} [:maybe :string]]])

;; Review result with full Gemini context for training data
(schema/register! ::review-result
                  [:map
                   [::success :boolean]
                   [::text {:optional true} [:maybe :string]]
                   [::error {:optional true} [:maybe :string]]
                   ;; Gemini interaction data for training
                   [::gemini-system-instruction {:optional true} [:maybe :string]]
                   [::gemini-user-prompt {:optional true} [:maybe :string]]
                   [::gemini-code {:optional true} [:maybe :string]]
                   [::gemini-tokens {:optional true}
                    [:maybe
                     [:map
                      [:prompt {:optional true} [:maybe :int]]
                      [:response {:optional true} [:maybe :int]]
                      [:cached {:optional true} [:maybe :int]]]]]])

;; Format output request
(schema/register! ::format-output-request
                  [:map
                   [::text :string]
                   [::max-length {:optional true} [:int {:min 1}]]])

;; review-edits request (convenience function)
(schema/register! ::review-edits-request
                  [:map
                   [::files ::files]
                   [::test-results {:optional true} [:maybe :map]]
                   [::new-functions {:optional true} [:maybe ::new-functions]]
                   [::max-output-length {:optional true} [:maybe [:int {:min 1}]]]
                   [::timeout {:optional true} [:maybe [:int {:min 1}]]]
                   [::api-key {:optional true} [:maybe :string]]])

;; review-edits response (includes full data for observability)
(schema/register! ::review-edits-response
                  [:map
                   [::formatted-text :string]
                   [::prompt :string]
                   [::response {:optional true} [:maybe :string]]
                   [::success :boolean]
                   [::error {:optional true} [:maybe :string]]
                   [::gemini-system-instruction {:optional true} [:maybe :string]]
                   [::gemini-user-prompt {:optional true} [:maybe :string]]
                   [::gemini-code {:optional true} [:maybe :string]]
                   [::gemini-tokens {:optional true}
                    [:maybe
                     [:map
                      [:prompt {:optional true} [:maybe :int]]
                      [:response {:optional true} [:maybe :int]]
                      [:cached {:optional true} [:maybe :int]]]]]])

;;; ---------------------------------------------------------------------------
;;; Configuration Defaults
;;; ---------------------------------------------------------------------------

(def ^:const default-max-code-length
  "Maximum characters of code to send to Gemini."
  12000)

(def ^:const default-max-conventions-length
  "Maximum characters of CONVENTIONS.md to include."
  4000)

(def ^:const default-max-output-length
  "Maximum characters for formatted output."
  500)

(def ^:const default-timeout-ms
  "Default timeout for Gemini API calls."
  60000)

(def ^:const conventions-path
  "Path to project conventions file."
  "CONVENTIONS.md")

;;; ---------------------------------------------------------------------------
;;; Private Helpers
;;; ---------------------------------------------------------------------------

(defn- read-file-safe
  "Read a file safely, returning content or error placeholder."
  [file-path]
  (let [f (io/file file-path)]
    (if (and (.exists f) (.canRead f))
      (try
        (slurp f)
        (catch Exception e
          (str "[Error reading file: " (.getMessage e) "]")))
      "[File not found]")))

(defn- truncate
  "Truncate string to max-len chars, adding marker if truncated."
  [s max-len]
  (if (and s (> (count s) max-len))
    (str (subs s 0 max-len) "\n[truncated]")
    s))

(defn- source->test-path
  "Convert source file path to test file path.
   Works with both absolute and relative paths:
   - src/seon/foo/bar.clj -> test/seon/foo/bar_test.clj
   - /abs/path/src/seon/foo.clj -> /abs/path/test/seon/foo_test.clj"
  [source-path]
  (when source-path
    (-> source-path
        ;; Replace /src/ or ^src/ with /test/ or test/
        (str/replace #"(^|/)src/" "$1test/")
        (str/replace #"\.clj$" "_test.clj"))))

(defn- load-conventions
  "Load conventions from file, truncating if needed."
  [max-length]
  (let [f (io/file conventions-path)]
    (when (.exists f)
      (-> (slurp f)
          (truncate max-length)))))

(defn- format-test-results
  "Format test results for inclusion in review context."
  [test-results]
  (if (nil? test-results)
    "Tests: not run"
    (if (get test-results :seon.dev.verify/success
             (get test-results ::success))
      (let [test-count (or (get test-results :seon.dev.verify/test-count)
                           (get test-results ::test-count)
                           0)]
        (format "Tests: %d passed" test-count))
      (let [fail-count (or (get test-results :seon.dev.verify/fail-count)
                           (get test-results ::fail-count)
                           0)
            error-count (or (get test-results :seon.dev.verify/error-count)
                            (get test-results ::error-count)
                            0)
            test-count (or (get test-results :seon.dev.verify/test-count)
                           (get test-results ::test-count)
                           0)]
        (format "Tests: %d failed, %d errors out of %d tests"
                fail-count error-count test-count)))))

(defn- collect-source-files
  "Collect source and test file contents for review."
  [files max-length]
  (let [source-files (vec files)
        test-files (vec (keep source->test-path source-files))
        all-files (into source-files test-files)]
    (when (seq all-files)
      (let [file-contents (->> all-files
                               (map (fn [f]
                                      (str ";;; " f "\n" (read-file-safe f))))
                               (str/join "\n\n"))]
        (truncate file-contents max-length)))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn build-context
  "Build context for AI review from edit summary.

   Takes information about what files were edited and any test results,
   and builds a structured context map for the Gemini review.

   Request keys:
     ::files               - Set of file paths that were edited
     ::test-results        - Optional test results map (from verify.clj)
     ::new-functions       - Optional set of new function symbols
     ::max-code-length     - Optional max code chars (default: 12000)
     ::max-conventions-length - Optional max conventions chars (default: 4000)

   Response keys:
     ::prompt              - The prompt for the review
     ::code                - Code content to review
     ::conventions         - Project conventions (for system instruction)
     ::test-summary        - Formatted test results
     ::new-functions       - Set of new function symbols

   Example:
     (build-context {::files #{\"/path/to/file.clj\"}
                     ::test-results {::success true ::test-count 5}})
     ;; => {::prompt \"Review these Clojure code changes...\"
     ;;     ::code \"(ns seon.foo ...)\"
     ;;     ::conventions \"# Seon Code Conventions...\"}"
  {:malli/schema [:=> [:cat ::build-context-request] ::review-context]}
  [{::keys [files test-results new-functions max-code-length max-conventions-length]}]
  (let [max-code (or max-code-length default-max-code-length)
        max-conv (or max-conventions-length default-max-conventions-length)
        conventions (load-conventions max-conv)
        code (collect-source-files files max-code)
        test-summary (format-test-results test-results)
        new-fns-str (when (seq new-functions)
                      (str/join ", " (map str new-functions)))]
    {::prompt "Review these Clojure code changes against project conventions."
     ::code (or code "")
     ::conventions conventions
     ::test-summary (str test-summary
                         (when new-fns-str
                           (str "\n\nNew functions: " new-fns-str)))
     ::new-functions new-functions}))

(defn call-gemini
  "Call Gemini for code review.

   Uses the existing seon.ai.gemini client to perform the review.
   Returns review result with full Gemini context for training data.

   Request keys:
     ::context  - Review context map from build-context
     ::timeout  - Optional timeout in ms (default: 60000)
     ::api-key  - Optional explicit API key

   Response keys:
     ::success                  - true if review completed
     ::text                     - Review text on success
     ::error                    - Error message on failure
     ::gemini-system-instruction - System instruction sent to Gemini
     ::gemini-user-prompt       - User prompt sent to Gemini
     ::gemini-code              - Code that was reviewed
     ::gemini-tokens            - Token counts {:prompt N :response N :cached N}

   Example:
     (call-gemini {::context {::prompt \"Review...\"
                              ::code \"(defn foo ...)\"}})"
  {:malli/schema [:=> [:cat ::call-gemini-request] ::review-result]}
  [{::keys [context timeout api-key]}]
  (let [{::keys [prompt code conventions test-summary]} context
        timeout-ms (or timeout default-timeout-ms)
        full-context (str "=== TEST RESULTS ===\n" test-summary)]
    (try
      (let [result (gemini/review-code
                    {::gemini/prompt prompt
                     ::gemini/code code
                     ::gemini/conventions conventions
                     ::gemini/context full-context
                     ::gemini/timeout timeout-ms
                     ::gemini/api-key api-key})]
        ;; gemini/review-code now returns a structured map
        (if (::gemini/success result)
          {::success true
           ::text (::gemini/text result)
           ::gemini-system-instruction (::gemini/system-instruction result)
           ::gemini-user-prompt (::gemini/user-prompt result)
           ::gemini-code (::gemini/code result)
           ::gemini-tokens (::gemini/tokens result)}
          {::success false
           ::error (::gemini/error result)
           ::gemini-system-instruction (::gemini/system-instruction result)
           ::gemini-user-prompt (::gemini/user-prompt result)
           ::gemini-code (::gemini/code result)
           ::gemini-tokens (::gemini/tokens result)}))
      (catch Exception e
        (log/error e "Gemini review failed")
        {::success false
         ::error (str "Review exception: " (.getMessage e))}))))

(defn format-output
  "Format review output for display.

   Truncates long reviews and adds prefix for hook feedback.

   Request keys:
     ::text       - Review text to format
     ::max-length - Optional max chars (default: 500)

   Returns:
     Formatted string ready for display

   Example:
     (format-output {::text \"This code looks good...\"
                     ::max-length 200})
     ;; => \"Gemini: This code looks good...\""
  {:malli/schema [:=> [:cat ::format-output-request] :string]}
  [{::keys [text max-length]}]
  (let [max-len (or max-length default-max-output-length)]
    (str "Gemini: " (truncate text max-len))))

(defn review-edits
  "Build context, call Gemini, return full review data.

   Combines build-context and call-gemini into a single call.
   Returns full data for observability (prompt, response, formatted text).

   Request keys:
     ::files               - Set of file paths that were edited
     ::test-results        - Optional test results map
     ::new-functions       - Optional set of new function symbols
     ::max-output-length   - Optional max output chars (default: 500)
     ::timeout             - Optional timeout in ms (default: 60000)
     ::api-key             - Optional explicit API key

   Returns map with:
     ::formatted-text            - The formatted output string
     ::prompt                    - The full prompt sent to Gemini (legacy, for compatibility)
     ::response                  - The raw response from Gemini
     ::success                   - Whether the review succeeded
     ::error                     - Error message if failed
     ::gemini-system-instruction - System instruction sent to Gemini
     ::gemini-user-prompt        - User prompt sent to Gemini
     ::gemini-code               - Code that was reviewed
     ::gemini-tokens             - Token counts {:prompt N :response N :cached N}

   Example:
     (review-edits {::files #{\"/path/to/file.clj\"}})
     ;; => {::formatted-text \"Gemini: ...\" ::prompt \"...\" ::response \"...\"}"
  {:malli/schema [:=> [:cat ::review-edits-request] ::review-edits-response]}
  [{::keys [files test-results new-functions max-output-length timeout api-key]}]
  (let [context (build-context {::files files
                                ::test-results test-results
                                ::new-functions new-functions})
        prompt (::prompt context)
        code (::code context)
        result (call-gemini {::context context
                             ::timeout timeout
                             ::api-key api-key})]
    (if (::success result)
      {::formatted-text (format-output {::text (::text result)
                                        ::max-length max-output-length})
       ::prompt (str prompt "\n\n" code)
       ::response (::text result)
       ::success true
       ;; Full Gemini context for training data
       ::gemini-system-instruction (::gemini-system-instruction result)
       ::gemini-user-prompt (::gemini-user-prompt result)
       ::gemini-code (::gemini-code result)
       ::gemini-tokens (::gemini-tokens result)}
      {::formatted-text (str "Gemini review failed: " (::error result))
       ::prompt (str prompt "\n\n" code)
       ::response nil
       ::success false
       ::error (::error result)
       ;; Still include Gemini context even on failure
       ::gemini-system-instruction (::gemini-system-instruction result)
       ::gemini-user-prompt (::gemini-user-prompt result)
       ::gemini-code (::gemini-code result)
       ::gemini-tokens (::gemini-tokens result)})))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  (require '[seon.dev.review :as review])

  ;; Build context for a file
  (review/build-context {::files #{"/Users/sean/src/seon/src/seon/core.clj"}})

  ;; Build with test results
  (review/build-context {::files #{"/Users/sean/src/seon/src/seon/core.clj"}
                         ::test-results {:seon.dev.verify/success true
                                         :seon.dev.verify/test-count 5}
                         ::new-functions #{'foo 'bar}})

  ;; Call Gemini (requires API key)
  (let [ctx (review/build-context {::files #{"/Users/sean/src/seon/src/seon/core.clj"}})]
    (review/call-gemini {::context ctx}))

  ;; Format output
  (review/format-output {::text "This code is well-structured and follows conventions."})
  (review/format-output {::text (apply str (repeat 1000 "x"))
                         ::max-length 100})

  ;; Full review cycle (convenience function)
  (review/review-edits {::files #{"/Users/sean/src/seon/src/seon/core.clj"}})

  nil)
