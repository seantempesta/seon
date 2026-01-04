(ns seon.dev.repair
  "Delimiter repair for Clojure source code.

   Provides detection of unbalanced delimiters and automatic repair using
   parinferish. This enables the development hook to fix common LLM-generated
   errors like missing closing parentheses.

   All public functions use map-in pattern per CONVENTIONS.md.

   Main functions:
   - delimiter-error? - Check if code has unbalanced delimiters
   - repair - Attempt to fix delimiter errors using parinfer
   - repair-and-format - Repair and optionally format code

   Example usage:
     (require '[seon.dev.repair :as repair])

     ;; Check for delimiter errors
     (repair/delimiter-error? {::repair/content \"(defn foo [x] (+ x 1\"})
     ;; => true

     ;; Repair the code
     (repair/repair {::repair/content \"(defn foo [x] (+ x 1\"})
     ;; => {::repair/success true ::repair/repaired \"(defn foo [x] (+ x 1))\"}

     ;; Repair and format
     (repair/repair-and-format
       {::repair/content \"(defn foo [x](+ x 1\"
        ::repair/format? true})
     ;; => {::repair/success true
     ;;     ::repair/content \"(defn foo [x]\\n  (+ x 1))\"}"
  (:require [edamame.core :as edamame]
            [parinferish.core :as parinferish]
            [cljfmt.core :as cljfmt]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

(schema/register! ::content
                  [:string {:description "Clojure source code content"}])

(schema/register! ::format?
                  [:boolean {:description "Whether to format after repair"}])

(schema/register! ::success
                  [:boolean {:description "Whether the operation succeeded"}])

(schema/register! ::error
                  [:string {:description "Error message if operation failed"}])

;; Request/Response schemas for delimiter-error?
(schema/register! ::delimiter-error-request
                  [:map
                   [::content ::content]])

;; Request/Response schemas for repair
(schema/register! ::repair-request
                  [:map
                   [::content ::content]])

(schema/register! ::repair-response
                  [:map
                   [::repaired {:optional true} ::content]
                   [::success :boolean]])

;; Request/Response schemas for repair-and-format
(schema/register! ::repair-and-format-request
                  [:map
                   [::content ::content]
                   [::format? {:optional true} ::format?]])

(schema/register! ::repair-and-format-response
                  [:map
                   [::success ::success]
                   [::content {:optional true} ::content]
                   [::error {:optional true} ::error]])

;;; ---------------------------------------------------------------------------
;;; Private Implementation
;;; ---------------------------------------------------------------------------

(defn- has-delimiter-error?
  "Check if content has delimiter-related parse errors.

   Uses edamame to parse the content. Returns true if parsing fails due to
   unbalanced delimiters (indicated by :edamame/opened-delimiter in ex-data).

   This is more precise than catching all parse errors - we specifically
   detect delimiter issues that parinfer can fix."
  [content]
  (try
    (edamame/parse-string-all content {:all true
                                       :read-cond :allow
                                       :features #{:clj}})
    false
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        ;; Only return true for delimiter errors, not other parse errors
        (boolean
         (and (= :edamame/error (:type data))
              (or (contains? data :edamame/opened-delimiter)
                  ;; Also catch "unmatched delimiter" errors
                  (when-let [msg (.getMessage ex)]
                    (re-find #"(?i)unmatched|unclosed|expected.*\]|\)|\}" msg)))))))))

(defn- try-repair
  "Attempt to repair content using parinferish indent mode.

   Parinferish indent mode infers the correct delimiters from indentation.
   Returns the repaired content, or nil if repair failed."
  [content]
  (try
    (let [parsed (parinferish/parse content {:mode :indent})
          result (parinferish/flatten parsed)]
      result)
    (catch Exception _
      ;; If parinferish can't parse it, return nil
      nil)))

(defn- try-format
  "Attempt to format content using cljfmt.

   Returns the formatted content, or the original if formatting fails."
  [content]
  (try
    (cljfmt/reformat-string content)
    (catch Exception _
      ;; If formatting fails, return original
      content)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn delimiter-error?
  "Check if content has unbalanced delimiter errors.

   Uses edamame to parse the content and detect unbalanced parentheses,
   brackets, and braces.

   Request keys:
     ::content - Clojure source code string

   Returns:
     Boolean - true if content has delimiter errors, false otherwise

   Example:
     (delimiter-error? {::content \"(defn foo [x] (+ x 1\"})
     ;; => true (missing closing paren)

     (delimiter-error? {::content \"(defn foo [x] (+ x 1))\"})
     ;; => false (balanced)"
  {:malli/schema [:=> [:cat ::delimiter-error-request] :boolean]}
  [{::keys [content]}]
  (if (or (nil? content) (empty? content))
    false
    (has-delimiter-error? content)))

(defn repair
  "Attempt to repair delimiter errors in content using parinfer.

   Uses parinferish in indent mode to infer correct delimiters from
   indentation. This is effective for common LLM errors like missing
   closing parentheses.

   Request keys:
     ::content - Clojure source code with delimiter errors

   Response keys:
     ::success  - Whether repair was needed and succeeded
     ::repaired - The repaired content (only present if repair succeeded)

   Example:
     (repair {::content \"(defn foo [x]\\n  (+ x 1\"})
     ;; => {::success true ::repaired \"(defn foo [x]\\n  (+ x 1))\"}

     (repair {::content \"(defn foo [x] (+ x 1))\"})
     ;; => {::success false} (no errors to repair)"
  {:malli/schema [:=> [:cat ::repair-request] ::repair-response]}
  [{::keys [content]}]
  (if (or (nil? content) (empty? content) (not (delimiter-error? {::content content})))
    {::success false}
    (if-let [repaired (try-repair content)]
      ;; Only return if repair actually fixed the errors
      (if (and (not= repaired content)
               (not (delimiter-error? {::content repaired})))
        {::success true ::repaired repaired}
        {::success false})
      {::success false})))

(defn repair-and-format
  "Repair delimiter errors and optionally format the result.

   Combines repair and formatting in one operation. First attempts to
   repair any delimiter errors, then optionally formats the result.

   Request keys:
     ::content  - Clojure source code
     ::format?  - Optional. If true, format after repair (default: false)

   Response keys:
     ::success  - Boolean indicating if content is now valid
     ::content  - The (possibly repaired/formatted) content
     ::error    - Error message if repair failed and content is still invalid

   Example:
     (repair-and-format {::content \"(defn foo [x](+ x 1\"
                         ::format? true})
     ;; => {::success true
     ;;     ::content \"(defn foo [x]\\n  (+ x 1))\"}

     (repair-and-format {::content \"(defn foo [x] (+ x 1))\"})
     ;; => {::success true
     ;;     ::content \"(defn foo [x] (+ x 1))\"}"
  {:malli/schema [:=> [:cat ::repair-and-format-request] ::repair-and-format-response]}
  [{::keys [content format?]}]
  (let [format? (or format? false)
        needs-repair? (delimiter-error? {::content content})
        repair-result (when needs-repair? (repair {::content content}))
        repaired (if (and needs-repair? (::success repair-result))
                   (::repaired repair-result)
                   content)
        still-broken? (delimiter-error? {::content repaired})
        formatted (if (and format? (not still-broken?))
                    (try-format repaired)
                    repaired)]
    (if still-broken?
      {::success false
       ::content content
       ::error "Unable to repair delimiter errors"}
      {::success true
       ::content formatted})))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  ;; Check for delimiter errors
  (delimiter-error? {::content "(defn foo [x] (+ x 1"})  ;; => true (missing paren)
  (delimiter-error? {::content "(defn foo [x] (+ x 1))"}) ;; => false
  (delimiter-error? {::content ""})                       ;; => false
  (delimiter-error? {::content nil})                      ;; => false
  (delimiter-error? {::content "(let [x 1"})              ;; => true
  (delimiter-error? {::content "[1 2 3"})                 ;; => true
  (delimiter-error? {::content "{:a 1"})                  ;; => true

  ;; Repair code
  (repair {::content "(defn foo [x]\n  (+ x 1"})
  ;; => {::success true ::repaired "(defn foo [x]\n  (+ x 1))"}

  (repair {::content "(defn foo [x] (+ x 1))"})
  ;; => {::success false} (no errors to repair)

  ;; Repair and format
  (repair-and-format {::content "(defn foo [x](+ x 1"
                      ::format? true})
  ;; => {::success true, ::content "...formatted..."}

  (repair-and-format {::content "(defn foo [x] (+ x 1))"})
  ;; => {::success true, ::content "(defn foo [x] (+ x 1))"}

  nil)
