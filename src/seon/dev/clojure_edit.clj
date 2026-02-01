(ns seon.dev.clojure-edit
  "S-expression match/replace editing using rewrite-clj.

   Finds expressions by structural matching (whitespace-insensitive)
   and replaces them. Always produces valid output.

   Main function:
   - edit-sexp! - Find and replace s-expressions in a file

   Example usage:
     (require '[seon.dev.clojure-edit :as edit])

     ;; Replace (if (nil? x) default x) with (or x default)
     (edit/edit-sexp!
       {::edit/file-path \"src/seon/foo.clj\"
        ::edit/match \"(if (nil? x) default x)\"
        ::edit/replace \"(or x default)\"})
     ;; => {::edit/success true
     ;;     ::edit/message \"Replaced 1 occurrence(s)\"
     ;;     ::edit/replacements 1
     ;;     ::edit/diff \"...\"}

     ;; With line range to disambiguate
     (edit/edit-sexp!
       {::edit/file-path \"src/seon/foo.clj\"
        ::edit/match \"(+ 1 2)\"
        ::edit/replace \"(+ 1 3)\"
        ::edit/line-start 10
        ::edit/line-end 15})

     ;; Replace all occurrences
     (edit/edit-sexp!
       {::edit/file-path \"src/seon/foo.clj\"
        ::edit/match \"(+ 1 2)\"
        ::edit/replace \"(+ 1 3)\"
        ::edit/replace-all? true})"
  (:require [rewrite-clj.zip :as z]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [clojure.string :as str]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration (per CONVENTIONS.md)
;;; ---------------------------------------------------------------------------

(schema/register! ::file-path
                  [:string {:min 1
                            :description "Path to Clojure file to edit"}])

(schema/register! ::match
                  [:string {:min 1
                            :description "S-expression to find (whitespace-insensitive)"}])

(schema/register! ::replace
                  [:string {:min 1
                            :description "S-expression to replace with"}])

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

(schema/register! ::edit-request
                  [:map
                   [::file-path ::file-path]
                   [::match ::match]
                   [::replace ::replace]
                   [::line-start {:optional true} ::line-start]
                   [::line-end {:optional true} ::line-end]
                   [::replace-all? {:optional true :default false} ::replace-all?]
                   [::dry-run? {:optional true :default false} ::dry-run?]])

(schema/register! ::edit-response
                  [:map
                   [::success ::success]
                   [::message {:optional true} ::message]
                   [::error {:optional true} ::error]
                   [::diff {:optional true} ::diff]
                   [::replacements {:optional true} ::replacements]
                   [::matches {:optional true} ::matches]])

;;; ---------------------------------------------------------------------------
;;; Diff Generation
;;; ---------------------------------------------------------------------------

(defn- line-diff
  "Generate a simple unified diff between two strings."
  [old-content new-content file-path]
  (let [old-lines (str/split-lines old-content)
        new-lines (str/split-lines new-content)]
    (if (= old-lines new-lines)
      ""
      (let [;; Find the first and last differing lines
            first-diff (loop [i 0]
                         (cond
                           (>= i (max (count old-lines) (count new-lines))) nil
                           (not= (get old-lines i) (get new-lines i)) i
                           :else (recur (inc i))))
            ;; Context around the diff (3 lines before/after)
            context 3
            start (max 0 (- first-diff context))
            ;; Find end of diff
            end (loop [i (max (count old-lines) (count new-lines))]
                  (if (<= i first-diff)
                    first-diff
                    (if (not= (get old-lines (dec i)) (get new-lines (dec i)))
                      i
                      (recur (dec i)))))
            end-with-context (min (max (count old-lines) (count new-lines))
                                  (+ end context))]
        (str "--- a/" file-path "\n"
             "+++ b/" file-path "\n"
             "@@ -" (inc start) "," (- end-with-context start) " +"
             (inc start) "," (- end-with-context start) " @@\n"
             (str/join "\n"
                       (for [i (range start end-with-context)]
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
                             (str "-" old-line "\n+" new-line))))))))))

;;; ---------------------------------------------------------------------------
;;; S-Expression Parsing
;;; ---------------------------------------------------------------------------

(defn- parse-expr
  "Parse string as s-expression.
   Returns {:success true :sexpr ... :node ...} or {:success false :error ...}"
  [s]
  (try
    (let [node (p/parse-string s)]
      {:success true
       :sexpr (n/sexpr node)
       :node node})
    (catch Exception e
      {:success false
       :error (.getMessage e)})))

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

(defn- find-all-matches
  "Walk zipper, collect all matching locations with context.
   Returns vector of maps with ::line, ::column, ::context, and ::zloc."
  [zloc match-sexpr line-start line-end]
  (loop [loc zloc
         matches []]
    (if (z/end? loc)
      matches
      (let [matches' (if (and (sexpr-match? loc match-sexpr)
                              (in-line-range? loc line-start line-end))
                       (let [[line col] (z/position loc)
                             context (z/string loc)]
                         (conj matches {::line line
                                        ::column col
                                        ::context context
                                        ::zloc loc}))
                       matches)]
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

(defn- replace-matches
  "Replace all matches in the source.
   Uses position-based replacement from end to start to preserve positions."
  [source matches replace-node]
  (let [;; Sort matches by position (descending) to replace from end first
        sorted-matches (sort-by (fn [m] [(- (::line m)) (- (::column m))]) matches)]
    (reduce
     (fn [current-source match]
       (let [zloc (z/of-string current-source {:track-position? true})
             target-line (::line match)
             target-col (::column match)
             ;; Find the node at this position
             found-loc (loop [loc zloc]
                         (if (z/end? loc)
                           nil
                           (let [[line col] (z/position loc)]
                             (if (and (= line target-line)
                                      (= col target-col))
                               loc
                               (recur (z/next loc))))))]
         (if found-loc
           (-> found-loc
               (z/replace replace-node)
               z/root-string)
           current-source)))
     source
     sorted-matches)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn edit-sexp!
  "Find and replace s-expressions in a file.

   Parses both match and replace as Clojure expressions, finds all occurrences
   of match in the file (whitespace-insensitive), and replaces them.

   IMPORTANT: If multiple matches are found and replace-all? is false,
   the operation is REJECTED and all match locations are returned.
   The caller must then either:
   1. Be more specific (expand match to include surrounding context)
   2. Use line-start/line-end to target a specific occurrence
   3. Use replace-all? true if all occurrences should change

   Request keys:
     ::file-path     - Path to Clojure file to edit
     ::match         - S-expression to find (whitespace-insensitive)
     ::replace       - S-expression to replace with
     ::line-start    - Optional. Only match within this line range (start)
     ::line-end      - Optional. Only match within this line range (end)
     ::replace-all?  - Optional. Replace all occurrences (default: false)

   Response keys:
     ::success       - Boolean indicating if the operation succeeded
     ::message       - Success message (if succeeded)
     ::error         - Error message (if failed)
     ::diff          - Unified diff showing changes (if succeeded)
     ::replacements  - Number of replacements made (if succeeded)
     ::matches       - Match locations (if multiple matches found without replace-all?)

   Example:
     ;; Simple replacement
     (edit-sexp! {::file-path \"src/foo.clj\"
                  ::match \"(+ 1 2)\"
                  ::replace \"(+ 1 3)\"})

     ;; With line range to disambiguate
     (edit-sexp! {::file-path \"src/foo.clj\"
                  ::match \"(+ 1 2)\"
                  ::replace \"(+ 1 3)\"
                  ::line-start 10
                  ::line-end 15})

     ;; Replace all occurrences
     (edit-sexp! {::file-path \"src/foo.clj\"
                  ::match \"old-fn\"
                  ::replace \"new-fn\"
                  ::replace-all? true})"
  {:malli/schema [:=> [:cat ::edit-request] ::edit-response]}
  [{::keys [file-path match replace line-start line-end replace-all?]}]
  (let [replace-all? (or replace-all? false)
        match-parsed (parse-expr match)
        replace-parsed (parse-expr replace)]
    (cond
      ;; Validate match expression
      (not (:success match-parsed))
      {::success false
       ::error (str "Invalid match expression: " (:error match-parsed))}

      ;; Validate replace expression
      (not (:success replace-parsed))
      {::success false
       ::error (str "Invalid replace expression: " (:error replace-parsed))}

      :else
      (try
        (let [source (slurp file-path)
              zloc (z/of-string source {:track-position? true})
              match-sexpr (:sexpr match-parsed)
              replace-node (:node replace-parsed)
              matches (find-all-matches zloc match-sexpr line-start line-end)]
          (cond
            ;; No matches found
            (empty? matches)
            {::success false
             ::error (str "Match not found: " match)}

            ;; Multiple matches without replace_all → REJECT with locations
            (and (> (count matches) 1) (not replace-all?))
            {::success false
             ::error (format "Found %d matches - be more specific or use replace_all"
                             (count matches))
             ::matches (mapv (fn [m]
                               {::line (::line m)
                                ::column (::column m)
                                ::context (truncate-context (::context m) 60)})
                             matches)}

            ;; Single match or replace_all → proceed
            :else
            (let [new-source (replace-matches source matches replace-node)
                  diff (line-diff source new-source file-path)]
              (spit file-path new-source)
              {::success true
               ::message (format "Replaced %d occurrence(s) in %s"
                                 (count matches) file-path)
               ::replacements (count matches)
               ::diff diff})))
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

  ;; Parse an expression
  (parse-expr "(+ 1 2)")
  ;; => {:success true, :sexpr (+ 1 2), :node ...}

  (parse-expr "(+ 1 2")
  ;; => {:success false, :error "..."}

  ;; Test sexpr matching
  (let [zloc (z/of-string "(defn foo [] (+ 1 2))" {:track-position? true})]
    (sexpr-match? (z/down (z/down zloc)) 'defn))
  ;; => true

  ;; Find matches in code
  (let [code "(defn foo [] (+ 1 2) (+ 1 2))"
        zloc (z/of-string code {:track-position? true})]
    (find-all-matches zloc '(+ 1 2) nil nil))
  ;; => [{::line 1, ::column 14, ::context "(+ 1 2)", ...}
  ;;     {::line 1, ::column 22, ::context "(+ 1 2)", ...}]

  nil)
