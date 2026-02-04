(ns seon.dev.clojure-replace
  "Comment-aware s-expression match/replace editing using rewrite-clj.

   Finds expressions by structural matching (whitespace-insensitive)
   and replaces them. Comments can be explicitly matched if you want
   to change them.

   Behavior:
   | Match Pattern         | Replace Pattern       | Result                              |
   |-----------------------|-----------------------|-------------------------------------|
   | (+ x 10)              | (* x 2)               | Code changes, comments preserved    |
   | ;; old\\n(+ x 10)      | ;; new\\n(* x 2)       | Both comment AND code replaced      |
   | ;; old\\n(+ x 10)      | (* x 2)               | Comment removed, code replaced      |
   | (+ x 10)              | ;; new\\n(+ x 10)      | Comment added, code unchanged       |
   | ;; wrong\\n(+ x 10)    | ...                   | FAILS - comment doesn't match       |

   Main function:
   - replace-sexp! - Find and replace s-expressions in a file

   Example usage:
     (require '[seon.dev.clojure-replace :as repl])

     ;; Simple replacement (preserves adjacent comments)
     (repl/replace-sexp!
       {::repl/file-path \"src/seon/foo.clj\"
        ::repl/match \"(if (nil? x) default x)\"
        ::repl/replace \"(or x default)\"})

     ;; Replace code AND comment
     (repl/replace-sexp!
       {::repl/file-path \"src/seon/foo.clj\"
        ::repl/match \";; Handle nil case\\n(if (nil? x) default x)\"
        ::repl/replace \";; Use or for nil coalescing\\n(or x default)\"})"
  (:require [rewrite-clj.zip :as z]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [cljfmt.core :as cljfmt]
            [clojure.string :as str]
            [seon.dev.lint :as lint]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

(schema/register! ::file-path
                  [:string {:min 1
                            :description "Path to Clojure file to edit"}])

(schema/register! ::match
                  [:string {:min 1
                            :description "S-expression to find (whitespace-insensitive). Include comments to match/change them."}])

(schema/register! ::replace
                  [:string {:min 1
                            :description "S-expression to replace with. Include comments to add/change them."}])

(schema/register! ::line-start
                  [:maybe [:int {:min 1
                                 :description "Only match within this line range (start)"}]])

(schema/register! ::line-end
                  [:maybe [:int {:min 1
                                 :description "Only match within this line range (end)"}]])

(schema/register! ::replace-all?
                  [:boolean {:description "Replace all occurrences (required if multiple matches)"}])

(schema/register! ::dry-run?
                  [:boolean {:description "Preview changes without writing file"}])

(schema/register! ::success
                  [:boolean {:description "Whether the operation succeeded"}])

(schema/register! ::message
                  [:string {:description "Success message"}])

(schema/register! ::error
                  [:string {:description "Error message if operation failed"}])

(schema/register! ::diff
                  [:string {:description "Unified diff showing the changes"}])

(schema/register! ::replacements
                  [:int {:min 0
                         :description "Number of replacements made"}])

(schema/register! ::line
                  [:int {:min 1
                         :description "Line number of match"}])

(schema/register! ::column
                  [:int {:min 0
                         :description "Column number of match"}])

(schema/register! ::context
                  [:string {:description "The matched expression as a string"}])

(schema/register! ::match-location
                  [:map
                   [::line ::line]
                   [::column ::column]
                   [::context ::context]])

(schema/register! ::matches
                  [:vector ::match-location])

(schema/register! ::lint?
                  [:boolean {:description "Run static analysis before writing (default: true)"}])

(schema/register! ::replace-sexp-request
                  [:map
                   [::file-path ::file-path]
                   [::match ::match]
                   [::replace ::replace]
                   [::line-start {:optional true} ::line-start]
                   [::line-end {:optional true} ::line-end]
                   [::replace-all? {:optional true :default false} ::replace-all?]
                   [::dry-run? {:optional true :default false} ::dry-run?]
                   [::lint? {:optional true :default true} ::lint?]])

(schema/register! ::lint-finding
                  [:map
                   [:type :keyword]
                   [:message :string]
                   [:row :int]
                   [:col :int]
                   [:level {:optional true} :keyword]
                   [:filename {:optional true} :string]])

(schema/register! ::lint-findings
                  [:vector ::lint-finding])

(schema/register! ::replace-sexp-response
                  [:map
                   [::success ::success]
                   [::message {:optional true} ::message]
                   [::error {:optional true} ::error]
                   [::diff {:optional true} ::diff]
                   [::replacements {:optional true} ::replacements]
                   [::matches {:optional true} ::matches]
                   [::lint-findings {:optional true} ::lint-findings]])

;;; ---------------------------------------------------------------------------
;;; Diff Generation
;;; ---------------------------------------------------------------------------

(def ^:const max-diff-lines
  "Maximum lines in diff output to avoid filling context window."
  50)

(defn- line-diff
  "Generate a simple unified diff between two strings.
   Truncates output to max-diff-lines to avoid filling context window."
  [old-content new-content file-path]
  (let [old-lines (str/split-lines old-content)
        new-lines (str/split-lines new-content)]
    (if (= old-lines new-lines)
      ""
      (let [first-diff (loop [i 0]
                         (cond
                           (>= i (max (count old-lines) (count new-lines))) nil
                           (not= (get old-lines i) (get new-lines i)) i
                           :else (recur (inc i))))
            context 3
            start (max 0 (- first-diff context))
            end (loop [i (max (count old-lines) (count new-lines))]
                  (if (<= i first-diff)
                    first-diff
                    (if (not= (get old-lines (dec i)) (get new-lines (dec i)))
                      i
                      (recur (dec i)))))
            end-with-context (min (max (count old-lines) (count new-lines))
                                  (+ end context))
            total-diff-lines (- end-with-context start)
            truncated? (> total-diff-lines max-diff-lines)
            effective-end (if truncated?
                            (+ start max-diff-lines)
                            end-with-context)
            lines-truncated (when truncated?
                              (- total-diff-lines max-diff-lines))]
        (str "--- a/" file-path "\n"
             "+++ b/" file-path "\n"
             "@@ -" (inc start) "," (- end-with-context start) " +"
             (inc start) "," (- end-with-context start) " @@\n"
             (str/join "\n"
                       (for [i (range start effective-end)]
                         (let [old-line (get old-lines i)
                               new-line (get new-lines i)]
                           (cond
                             (= old-line new-line)
                             (str " " (or old-line new-line ""))

                             (nil? old-line)
                             (str "+" new-line)

                             (nil? new-line)
                             (str "-" old-line)

                             :else
                             (str "-" old-line "\n+" new-line)))))
             (when truncated?
               (str "\n... (" lines-truncated " more lines truncated)")))))))

;;; ---------------------------------------------------------------------------
;;; Comment-Aware Pattern Parsing
;;; ---------------------------------------------------------------------------

(defn- significant-nodes
  "Get children that are meaningful (not pure whitespace, but keep comments)."
  [nodes]
  (->> nodes
       (remove #(#{:whitespace :newline} (n/tag %)))))

(defn- parse-pattern
  "Parse a pattern string and extract:
   - :before-comment - comment text before the code (or nil)
   - :code-sexpr - the main code form as sexpr
   - :code-string - the main code form as string (for error messages)
   - :code-node - the main code form as node (for replacement)
   - :after-comment - inline comment after the code (or nil)

   Returns nil if pattern doesn't contain exactly one code form."
  [pattern-str]
  (try
    (let [nodes (n/children (p/parse-string-all pattern-str))
          sig-nodes (vec (significant-nodes nodes))
          comments (filter #(= :comment (n/tag %)) sig-nodes)
          code-nodes (filter #(not= :comment (n/tag %)) sig-nodes)]

      (when (= 1 (count code-nodes))
        (let [code-node (first code-nodes)
              code-idx (.indexOf sig-nodes code-node)
              before-nodes (take code-idx sig-nodes)
              after-nodes (drop (inc code-idx) sig-nodes)
              before-comments (filter #(= :comment (n/tag %)) before-nodes)
              after-comments (filter #(= :comment (n/tag %)) after-nodes)]
          {:before-comment (when (seq before-comments)
                             (n/string (first before-comments)))
           :code-sexpr (n/sexpr code-node)
           :code-string (n/string code-node)
           :code-node code-node
           :after-comment (when (seq after-comments)
                            (n/string (first after-comments)))})))
    (catch Exception _
      nil)))

;;; ---------------------------------------------------------------------------
;;; Comment Navigation
;;; ---------------------------------------------------------------------------

(defn- find-preceding-comment
  "Navigate left from loc, skip whitespace, return comment loc if found.
   Returns nil if no comment precedes or if there's non-whitespace in between."
  [loc]
  (loop [l (z/left* loc)]
    (when l
      (let [tag (n/tag (z/node l))]
        (cond
          (= tag :comment) l
          (#{:whitespace :newline} tag) (recur (z/left* l))
          :else nil)))))

(defn- find-following-comment
  "Navigate right from loc, skip whitespace (not newlines), return comment if found.
   Returns nil if no inline comment follows."
  [loc]
  (loop [r (z/right* loc)]
    (when r
      (let [tag (n/tag (z/node r))]
        (cond
          (= tag :comment) r
          (= tag :whitespace) (recur (z/right* r))
          ;; newline means no inline comment
          :else nil)))))

(defn- comment-matches?
  "Check if comment node matches pattern (trimmed comparison)."
  [comment-loc pattern]
  (when (and comment-loc pattern)
    (let [actual (str/trim (z/string comment-loc))
          expected (str/trim pattern)]
      (= actual expected))))

;;; ---------------------------------------------------------------------------
;;; Matching Logic
;;; ---------------------------------------------------------------------------

(defn- sexpr-match?
  "Check if zipper location's s-expr equals target s-expr.
   Returns false for non-sexpr-able nodes (comments, whitespace, etc.)."
  [zloc target-sexpr]
  (try
    (= (z/sexpr zloc) target-sexpr)
    (catch Exception _
      ;; Some nodes aren't sexpr-able (comments, whitespace, etc.)
      false)))

(defn- in-line-range?
  "Check if zipper position is within line range (if specified)."
  [zloc line-start line-end]
  (if (or line-start line-end)
    (let [[line _col] (z/position zloc)]
      (and (or (nil? line-start) (>= line line-start))
           (or (nil? line-end) (<= line line-end))))
    true))

(defn- match-with-comments
  "Check if a code location matches the pattern including comment constraints.
   Returns match info map or nil if no match.

   Match info includes:
   - ::line, ::column, ::context (for reporting)
   - ::zloc (code zipper location)
   - ::before-comment-loc (preceding comment location, if matched)
   - ::after-comment-loc (following comment location, if matched)"
  [code-loc pattern line-start line-end]
  (let [{:keys [code-sexpr before-comment after-comment]} pattern]
    (when (and (sexpr-match? code-loc code-sexpr)
               (in-line-range? code-loc line-start line-end))
      ;; Code matches - now check comment constraints
      (let [before-loc (find-preceding-comment code-loc)
            after-loc (find-following-comment code-loc)
            ;; If pattern has before-comment, source must have matching one
            before-ok? (if before-comment
                         (comment-matches? before-loc before-comment)
                         true)
            ;; If pattern has after-comment, source must have matching one
            after-ok? (if after-comment
                        (comment-matches? after-loc after-comment)
                        true)]
        (when (and before-ok? after-ok?)
          (let [[line col] (z/position code-loc)
                context (z/string code-loc)]
            {::line line
             ::column col
             ::context context
             ::zloc code-loc
             ;; Only include comment locs if they were matched in pattern
             ::before-comment-loc (when before-comment before-loc)
             ::after-comment-loc (when after-comment after-loc)}))))))

(defn- find-all-matches
  "Walk zipper, collect all matching locations with comment context.
   Returns vector of match info maps."
  [zloc pattern line-start line-end]
  (loop [loc zloc
         matches []]
    (if (z/end? loc)
      matches
      (let [match (match-with-comments loc pattern line-start line-end)
            matches' (if match (conj matches match) matches)]
        (recur (z/next loc) matches')))))

;;; ---------------------------------------------------------------------------
;;; Replacement Logic
;;; ---------------------------------------------------------------------------

(defn- truncate-context
  "Truncate context string for display."
  [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 max-len) "...")
    s))

(defn- find-similar-forms
  "Find forms with the same head symbol as target, for error context.
   Returns up to 3 similar forms with their locations.
   Helps users understand why their match didn't work."
  [zloc target-sexpr]
  (when (and (seq? target-sexpr) (symbol? (first target-sexpr)))
    (let [head-sym (first target-sexpr)]
      (->> (loop [loc zloc, found []]
             (if (z/end? loc)
               found
               (let [found' (try
                              (let [sexpr (z/sexpr loc)]
                                (if (and (seq? sexpr)
                                         (= (first sexpr) head-sym)
                                         (not= sexpr target-sexpr))
                                  (let [[line _col] (z/position loc)]
                                    (conj found {:line line
                                                 :form (z/string loc)}))
                                  found))
                              (catch Exception _ found))]
                 (recur (z/next loc) found'))))
           (take 3)
           vec))))

(defn- build-replacement-string
  "Build the replacement string including comments from pattern.
   Takes the replace pattern and constructs the full replacement text."
  [replace-pattern]
  (let [{:keys [before-comment code-string after-comment]} replace-pattern]
    (str (when before-comment (str before-comment "\n"))
         code-string
         (when after-comment (str " " after-comment)))))

(defn- apply-single-replacement
  "Apply a single replacement at the matched location.
   Handles removing/adding comments as needed.
   Returns the modified source string."
  [source match match-pattern replace-pattern]
  (let [{::keys [zloc before-comment-loc after-comment-loc]} match
        {:keys [before-comment after-comment]} match-pattern
        replace-str (build-replacement-string replace-pattern)]
    ;; Strategy: Build a text-based replacement since we need to handle
    ;; comments which are outside the sexpr node
    (let [;; Determine the range to replace
          start-loc (or before-comment-loc zloc)
          end-loc (or after-comment-loc zloc)

          ;; Get positions - we need string indices
          ;; Note: rewrite-clj positions are 1-based [line, col]
          source-lines (str/split-lines source)

          ;; Find start position (beginning of first node to remove)
          [start-line start-col] (z/position start-loc)
          start-idx (+ (reduce + 0 (map #(inc (count %)) (take (dec start-line) source-lines)))
                       (dec start-col))

          ;; Find end position (end of last node to remove)
          end-node-str (z/string end-loc)
          [end-line end-col] (z/position end-loc)
          end-idx (+ (reduce + 0 (map #(inc (count %)) (take (dec end-line) source-lines)))
                     (dec end-col)
                     (count end-node-str))]

      ;; Simple string replacement
      (str (subs source 0 start-idx)
           replace-str
           (subs source end-idx)))))

(defn- replace-matches
  "Replace all matches in the source.
   Processes from end to start to preserve positions."
  [source matches match-pattern replace-pattern]
  (let [;; Sort matches by position (descending) to replace from end first
        sorted-matches (sort-by (fn [m] [(- (::line m)) (- (::column m))]) matches)]
    (reduce
     (fn [current-source match]
       ;; Re-parse for each replacement since positions shift
       (let [zloc (z/of-string current-source {:track-position? true})
             target-line (::line match)
             target-col (::column match)
             ;; Find the node at this position
             found-match (loop [loc zloc]
                           (if (z/end? loc)
                             nil
                             (let [[line col] (z/position loc)]
                               (if (and (= line target-line)
                                        (= col target-col))
                                 ;; Re-build match info at current position
                                 (match-with-comments loc match-pattern nil nil)
                                 (recur (z/next loc))))))]
         (if found-match
           (apply-single-replacement current-source found-match match-pattern replace-pattern)
           current-source)))
     source
     sorted-matches)))

(defn- format-source
  "Format source code using cljfmt.
   Returns formatted string, or original if formatting fails."
  [source]
  (try
    (cljfmt/reformat-string source)
    (catch Exception _
      ;; If cljfmt fails, return original
      source)))

;;; ---------------------------------------------------------------------------
;;; Error Messages for Comment Mismatches
;;; ---------------------------------------------------------------------------

(defn- find-potential-matches-with-wrong-comments
  "Find code matches that have different comments than expected.
   Helps diagnose comment mismatch errors."
  [zloc pattern line-start line-end]
  (let [{:keys [code-sexpr before-comment after-comment]} pattern]
    (loop [loc zloc
           found []]
      (if (z/end? loc)
        found
        (let [found' (if (and (sexpr-match? loc code-sexpr)
                              (in-line-range? loc line-start line-end))
                       (let [before-loc (find-preceding-comment loc)
                             after-loc (find-following-comment loc)
                             [line _col] (z/position loc)
                             actual-before (when before-loc (z/string before-loc))
                             actual-after (when after-loc (z/string after-loc))]
                         (conj found {:line line
                                      :expected-before before-comment
                                      :actual-before actual-before
                                      :expected-after after-comment
                                      :actual-after actual-after}))
                       found)]
          (recur (z/next loc) found'))))))

(defn- format-comment-mismatch-error
  "Format a helpful error message for comment mismatch."
  [potential-matches match-pattern]
  (let [{:keys [before-comment after-comment]} match-pattern]
    (str "Code found but comments don't match:\n\n"
         (str/join "\n\n"
                   (map (fn [{:keys [line expected-before actual-before expected-after actual-after]}]
                          (str "Line " line ":\n"
                               (when expected-before
                                 (str "  Expected before: " (pr-str expected-before) "\n"
                                      "  Actual before:   " (pr-str (or actual-before "(none)")) "\n"))
                               (when expected-after
                                 (str "  Expected after:  " (pr-str expected-after) "\n"
                                      "  Actual after:    " (pr-str (or actual-after "(none)"))))))
                        potential-matches)))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn replace-sexp!
  "Find and replace s-expressions in a file with comment awareness.

   Parses both match and replace as Clojure expressions with optional comments.
   Matching is whitespace-insensitive for code, but comments must match exactly
   (after trimming) if specified in the pattern.

   Comment Behavior:
   - If match pattern includes a comment, source must have that exact comment
   - If match pattern has NO comment, existing comments are PRESERVED
   - If replace pattern includes a comment, it's added/replaced
   - If replace pattern has NO comment but match did, comment is REMOVED

   IMPORTANT: If multiple matches are found and replace-all? is false,
   the operation is REJECTED and all match locations are returned.

   Request keys:
     ::file-path     - Path to Clojure file to edit
     ::match         - S-expression to find (whitespace-insensitive)
     ::replace       - S-expression to replace with
     ::line-start    - Optional. Only match within this line range (start)
     ::line-end      - Optional. Only match within this line range (end)
     ::replace-all?  - Optional. Replace all occurrences (default: false)
     ::dry-run?      - Optional. Preview changes without writing (default: false)

   Response keys:
     ::success       - Boolean indicating if the operation succeeded
     ::message       - Success message (if succeeded)
     ::error         - Error message (if failed)
     ::diff          - Unified diff showing changes (if succeeded)
     ::replacements  - Number of replacements made (if succeeded)
     ::matches       - Match locations (if multiple matches found without replace-all?)

   Example:
     ;; Simple replacement (preserves existing comments)
     (replace-sexp! {::file-path \"src/foo.clj\"
                     ::match \"(+ 1 2)\"
                     ::replace \"(+ 1 3)\"})

     ;; Replace code AND its comment
     (replace-sexp! {::file-path \"src/foo.clj\"
                     ::match \";; old comment\\n(+ 1 2)\"
                     ::replace \";; new comment\\n(+ 1 3)\"})"
  {:malli/schema [:=> [:cat ::replace-sexp-request] ::replace-sexp-response]}
  [{::keys [file-path match replace line-start line-end replace-all? dry-run? lint?]}]
  (let [replace-all? (or replace-all? false)
        dry-run? (or dry-run? false)
        lint? (if (nil? lint?) true lint?)  ; default true
        match-pattern (parse-pattern match)
        replace-pattern (parse-pattern replace)]
    (cond
      ;; Validate match expression
      (nil? match-pattern)
      {::success false
       ::error (str "Invalid match pattern: must contain exactly one code form.\n"
                    "Pattern: " (pr-str match))}

      ;; Validate replace expression
      (nil? replace-pattern)
      {::success false
       ::error (str "Invalid replace pattern: must contain exactly one code form.\n"
                    "Pattern: " (pr-str replace))}

      :else
      (try
        (let [source (slurp file-path)
              zloc (z/of-string source {:track-position? true})
              matches (find-all-matches zloc match-pattern line-start line-end)]
          (cond
            ;; No matches found - check for comment mismatches
            (empty? matches)
            (let [{:keys [code-sexpr before-comment after-comment]} match-pattern
                  ;; Check if code matches exist but with wrong comments
                  potential (when (or before-comment after-comment)
                              (find-potential-matches-with-wrong-comments
                               zloc match-pattern line-start line-end))]
              (if (seq potential)
                {::success false
                 ::error (format-comment-mismatch-error potential match-pattern)}
                ;; No code match at all - show similar forms
                (let [similar (find-similar-forms zloc code-sexpr)
                      hint (when (seq similar)
                             (str "\n\nSimilar forms with same head symbol:\n"
                                  (str/join "\n"
                                            (map #(str "  Line " (:line %) ": " (:form %))
                                                 similar))))]
                  {::success false
                   ::error (str "Match not found: " match hint)})))

            ;; Multiple matches without replace_all -> REJECT with locations
            (and (> (count matches) 1) (not replace-all?))
            {::success false
             ::error (format "Found %d matches - be more specific or use replace_all"
                             (count matches))
             ::matches (mapv (fn [m]
                               {::line (::line m)
                                ::column (::column m)
                                ::context (truncate-context (::context m) 60)})
                             matches)}

            ;; Single match or replace_all -> proceed
            :else
            (let [new-source-raw (replace-matches source matches match-pattern replace-pattern)
                  ;; Generate diff BEFORE cljfmt so we only show semantic changes
                  diff (line-diff source new-source-raw file-path)
                  new-source (format-source new-source-raw)
                  ;; Static analysis BEFORE writing - uses unified validation with suggestions
                  lint-result (when lint? (lint/validate-for-write
                                           {::lint/content new-source
                                            ::lint/file-path file-path
                                            ::lint/full-lint? true}))]
              (if (and lint-result (not (::lint/valid? lint-result)))
                ;; Lint errors - don't write, return structured findings with suggestions
                {::success false
                 ::error (str (::lint/error-msg lint-result)
                              "\n\nFile NOT modified. Fix the issues and try again.")
                 ::lint-findings (::lint/findings lint-result)
                 ::diff diff}
                ;; Lint passed (or disabled) - safe to write
                (do
                  (when-not dry-run?
                    (spit file-path new-source))
                  {::success true
                   ::message (if dry-run?
                               (format "DRY RUN: Would replace %d occurrence(s) in %s"
                                       (count matches) file-path)
                               (format "Replaced %d occurrence(s) in %s"
                                       (count matches) file-path))
                   ::replacements (count matches)
                   ::diff diff})))))
        (catch java.io.FileNotFoundException _
          {::success false
           ::error (str "File not found: " file-path)})
        (catch Exception e
          {::success false
           ::error (str "Error: " (.getMessage e))})))))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; REPL exploration

  ;; Parse a pattern with comment
  (parse-pattern ";; before comment\n(+ 1 2)")
  ;; => {:before-comment ";; before comment", :code-sexpr (+ 1 2), ...}

  ;; Parse pattern without comment
  (parse-pattern "(+ 1 2)")
  ;; => {:before-comment nil, :code-sexpr (+ 1 2), ...}

  ;; Parse with inline comment
  (parse-pattern "(+ 1 2) ; inline")
  ;; => {:after-comment "; inline", ...}

  ;; Find preceding comment
  (let [zloc (z/of-string ";; comment\n(+ 1 2)" {:track-position? true})
        code-loc (z/find-value zloc z/next '+)]
    (when-let [parent (z/up code-loc)]
      (find-preceding-comment parent)))

  nil)
