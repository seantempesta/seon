(ns seon.dev.markdown
  "Pure markdown analysis namespace for development hook integration.

   Parses, validates, and auto-fixes markdown files. Replaces the external
   markdownlint-cli2 npm dependency with a Seon-native implementation.

   All rules operate on parsed document structures — no database dependency.
   Code blocks are tracked to avoid false positives inside fenced regions.

   Public API:
     (parse {::content \"# Title\\n...\"})
     (validate {::content \"...\" ::rules #{:has-frontmatter}})
     (validate-file {::file-path \"docs/foo.md\" ::vault-root \"docs\"})
     (format-violations {::violations [...] ::max-length 800})
     (fix {::content \"...\"})"
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Contracts (ordinary Vars keep this Babashka-loadable)
;;; ---------------------------------------------------------------------------

(def ^:private heading-schema
  [:map
   [::level [:int {:min 1 :max 6}]]
   [::text :string]
   [::line [:int {:min 1}]]])

(def ^:private link-schema
  [:map
   [::type [:enum :wikilink :markdown :bare-url]]
   [::target [:string {:min 1}]]
   [::text {:optional true} :string]
   [::line [:int {:min 1}]]])

(def ^:private section-schema
  [:map
   [::heading #'heading-schema]
   [::content :string]
   [::line-start [:int {:min 1}]]
   [::line-end [:int {:min 1}]]])

(def ^:private document-schema
  [:map
   [::content :string]
   [::frontmatter {:optional true} [:map-of :keyword :string]]
   [::headings [:vector #'heading-schema]]
   [::links [:vector #'link-schema]]
   [::sections [:vector #'section-schema]]])

(def ^:private violation-schema
  [:map
   [::rule :keyword]
   [::severity [:enum :error :warning :info]]
   [::line {:optional true} [:int {:min 1}]]
   [::message :string]
   [::fix {:optional true} :string]])

(def ^:private violations-schema [:vector #'violation-schema])

(def ^:private parse-request-schema [:map [::content :string]])

(def ^:private validate-request-schema
  [:map
   [::content :string]
   [::rules {:optional true} [:set :keyword]]
   [::vault-root {:optional true} [:string {:min 1}]]
   [::file-path {:optional true} [:string {:min 1}]]])

(def ^:private validate-response-schema
  [:map
   [::valid? :boolean]
   [::violations #'violations-schema]
   [::document #'document-schema]])

(def ^:private validate-file-request-schema
  [:map
   [::file-path [:string {:min 1}]]
   [::vault-root {:optional true} [:string {:min 1}]]])

(def ^:private format-violations-request-schema
  [:map
   [::violations #'violations-schema]
   [::max-length {:optional true} [:int {:min 1}]]])

(def ^:private format-violations-response-schema
  [:map [::formatted :string]])

(def ^:private fix-request-schema [:map [::content :string]])

(def ^:private fix-response-schema
  [:map [::content :string] [::fixed-count [:int {:min 0}]]])

;;; ---------------------------------------------------------------------------
;;; Parsing Helpers
;;; ---------------------------------------------------------------------------

(defn- parse-frontmatter
  "Extract YAML frontmatter from markdown content.
   Returns [frontmatter-map remaining-content] or [nil content]."
  [content]
  (if (str/starts-with? content "---")
    (let [lines (str/split-lines content)
          ;; Find closing --- (skip first line which is opening ---)
          close-idx (loop [i 1]
                      (when (< i (count lines))
                        (if (= (str/trim (nth lines i)) "---")
                          i
                          (recur (inc i)))))]
      (if close-idx
        (let [fm-lines (subvec (vec lines) 1 close-idx)
              fm (into {}
                       (keep (fn [line]
                               (let [trimmed (str/trim line)]
                                 (when-not (str/blank? trimmed)
                                   (let [colon-idx (str/index-of trimmed ":")]
                                     (when (and colon-idx (pos? colon-idx))
                                       (let [k (str/trim (subs trimmed 0 colon-idx))
                                             v (str/trim (subs trimmed (inc colon-idx)))]
                                         [(keyword k) v])))))))
                       fm-lines)
              remaining (str/join "\n" (subvec (vec lines) (inc close-idx)))]
          [fm remaining (+ close-idx 2)]) ; +2 because lines are 0-indexed but we want 1-based line after frontmatter
        [nil content 1]))
    [nil content 1]))

(defn- in-code-block?
  "Build a set of line numbers that are inside fenced code blocks.
   Uses 1-based line numbers."
  [lines]
  (loop [i 0
         inside false
         code-lines #{}]
    (if (>= i (count lines))
      code-lines
      (let [line (nth lines i)
            trimmed (str/trim line)
            is-fence (or (str/starts-with? trimmed "```")
                         (str/starts-with? trimmed "~~~"))]
        (if is-fence
          (if inside
            ;; Closing fence — mark this line as code too
            (recur (inc i) false (conj code-lines (inc i)))
            ;; Opening fence — mark this line as code
            (recur (inc i) true (conj code-lines (inc i))))
          (recur (inc i) inside (if inside (conj code-lines (inc i)) code-lines)))))))

(defn- extract-headings
  "Extract ATX headings from lines. Returns vector of ::heading maps.
   Skips headings inside code blocks."
  [lines code-lines]
  (into []
        (keep-indexed
         (fn [idx line]
           (let [line-num (inc idx)]
             (when-not (contains? code-lines line-num)
               (when-let [m (re-matches #"^(#{1,6})\s+(.+?)(?:\s+#+)?$" line)]
                 {::level (count (nth m 1))
                  ::text (str/trim (nth m 2))
                  ::line line-num})))))
        lines))

(defn- extract-links
  "Extract all link types from lines. Returns vector of ::link maps.
   Skips links inside code blocks."
  [lines code-lines]
  (let [results (atom [])]
    (doseq [[idx line] (map-indexed vector lines)]
      (let [line-num (inc idx)]
        (when-not (contains? code-lines line-num)
          ;; Strip inline code spans to avoid false positives across all link types
          (let [stripped (str/replace line #"`[^`]+`" (fn [m] (apply str (repeat (count m) " "))))]
            ;; Wikilinks: [[target]] or [[target|text]]
            (doseq [m (re-seq #"\[\[([^\]|]+)(?:\|([^\]]+))?\]\]" stripped)]
              (swap! results conj
                     (cond-> {::type :wikilink
                              ::target (nth m 1)
                              ::line line-num}
                       (nth m 2) (assoc ::text (nth m 2)))))
            ;; Markdown links: [text](url) — but not images ![text](url)
            (doseq [m (re-seq #"(?<!!)\[([^\]]*)\]\(([^)]+)\)" stripped)]
              (swap! results conj
                     {::type :markdown
                      ::target (nth m 2)
                      ::text (nth m 1)
                      ::line line-num}))
            ;; Bare URLs
            (let [matcher
                  (re-matcher
                   #"(?<!\(|\"|\[)https?://[^\s\)>\]\"']+"
                   stripped)]
              (loop []
                (when (.find matcher)
                  (let [target (.group matcher)
                        before-url (subs stripped 0 (.start matcher))]
                    (when-not (or (re-find #"\]\($" before-url)
                                  (str/ends-with? before-url "<"))
                      (swap! results conj
                             {::type :bare-url
                              ::target target
                              ::line line-num})))
                  (recur))))))))
    @results))

(defn- extract-sections
  "Extract sections (content between headings). Returns vector of ::section maps."
  [lines headings]
  (if (empty? headings)
    []
    (let [total-lines (count lines)]
      (into []
            (map-indexed
             (fn [idx heading]
               (let [start-line (::line heading)
                     end-line (if (< (inc idx) (count headings))
                                (dec (::line (nth headings (inc idx))))
                                total-lines)
                     section-lines (subvec (vec lines)
                                           (dec start-line)
                                           (min end-line total-lines))
                     section-content (str/join "\n" section-lines)]
                 {::heading heading
                  ::content section-content
                  ::line-start start-line
                  ::line-end end-line})))
            headings))))

;;; ---------------------------------------------------------------------------
;;; Rule Implementations
;;; ---------------------------------------------------------------------------

(defn- rule-heading-style
  "ATX headings only (# not underline === or ---)."
  [lines code-lines fm-end-line]
  (into []
        (keep-indexed
         (fn [idx line]
           (let [line-num (inc idx)]
             (when-not (or (contains? code-lines line-num)
                           (< line-num fm-end-line))
               (when (and (pos? idx)
                          (or (re-matches #"^={3,}\s*$" line)
                              (re-matches #"^-{3,}\s*$" line))
                          ;; Check previous line has text (setext heading)
                          (let [prev (nth lines (dec idx))]
                            (and (not (str/blank? prev))
                                 (not (contains? code-lines idx))
                                 (not (str/blank? (str/trim prev))))))
                 {::rule :heading-style
                  ::severity :warning
                  ::line line-num
                  ::message "Use ATX-style headings (# heading) instead of setext (underline)"
                  ::fix "Replace with # heading"})))))
        lines))

(defn- rule-heading-increment
  "No jumping heading levels (h1 -> h3)."
  [headings]
  (loop [prev-level 0
         remaining headings
         violations []]
    (if (empty? remaining)
      violations
      (let [h (first remaining)
            level (::level h)]
        (if (and (pos? prev-level) (> level (inc prev-level)))
          (recur level (rest remaining)
                 (conj violations
                       {::rule :heading-increment
                        ::severity :warning
                        ::line (::line h)
                        ::message (format "Heading level jumped from h%d to h%d" prev-level level)
                        ::fix (format "Use h%d instead" (inc prev-level))}))
          (recur level (rest remaining) violations))))))

(defn- rule-single-h1
  "Only one # per document."
  [headings]
  (let [h1s (filter #(= 1 (::level %)) headings)]
    (when (> (count h1s) 1)
      (mapv (fn [h]
              {::rule :single-h1
               ::severity :warning
               ::line (::line h)
               ::message "Multiple h1 headings found; use only one per document"})
            (rest h1s)))))

(defn- rule-trailing-whitespace
  "No trailing spaces except exactly 2 spaces for line break."
  [lines code-lines]
  (into []
        (keep-indexed
         (fn [idx line]
           (let [line-num (inc idx)]
             (when-not (contains? code-lines line-num)
               (let [trimmed (str/trimr line)]
                 (when (not= line trimmed)
                   ;; Allow exactly 2 trailing spaces (markdown line break)
                   (let [trailing (subs line (count trimmed))]
                     (when (not= trailing "  ")
                       {::rule :trailing-whitespace
                        ::severity :warning
                        ::line line-num
                        ::message "Trailing whitespace"
                        ::fix "Remove trailing spaces"}))))))))
        lines))

(defn- rule-no-multiple-blanks
  "Max 1 consecutive blank line."
  [lines code-lines]
  (loop [i 0
         prev-blank false
         violations []]
    (if (>= i (count lines))
      violations
      (let [line-num (inc i)
            blank? (str/blank? (nth lines i))
            in-code (contains? code-lines line-num)]
        (if (and blank? prev-blank (not in-code))
          (recur (inc i) true
                 (conj violations
                       {::rule :no-multiple-blanks
                        ::severity :warning
                        ::line line-num
                        ::message "Multiple consecutive blank lines"
                        ::fix "Remove extra blank lines"}))
          (recur (inc i) (and blank? (not in-code)) violations))))))

(defn- rule-blanks-around-headings
  "Blank line before and after headings."
  [lines code-lines headings fm-end-line]
  (into []
        (mapcat
         (fn [h]
           (let [line-num (::line h)
                 idx (dec line-num)]
             (when-not (contains? code-lines line-num)
               (let [violations (transient [])]
                 ;; Check line before (if not first line and not frontmatter end)
                 (when (and (pos? idx)
                            (not (str/blank? (nth lines (dec idx))))
                            (>= line-num fm-end-line) ; skip if heading is right after frontmatter
                            (not (= "---" (str/trim (nth lines (dec idx))))))
                   (conj! violations
                          {::rule :blanks-around-headings
                           ::severity :warning
                           ::line line-num
                           ::message "Missing blank line before heading"
                           ::fix "Add blank line before heading"}))
                 ;; Check line after
                 (when (and (< (inc idx) (count lines))
                            (not (str/blank? (nth lines (inc idx)))))
                   (conj! violations
                          {::rule :blanks-around-headings
                           ::severity :warning
                           ::line line-num
                           ::message "Missing blank line after heading"
                           ::fix "Add blank line after heading"}))
                 (persistent! violations))))))
        headings))

(defn- rule-blanks-around-fences
  "Blank line before and after ``` code blocks."
  [lines code-lines fm-end-line]
  (loop [idx 0, inside? false, violations []]
    (if (= idx (count lines))
      violations
      (let [line (nth lines idx)
            line-num (inc idx)
            trimmed (str/trim line)
            fence? (and (contains? code-lines line-num)
                        (or (str/starts-with? trimmed "```")
                            (str/starts-with? trimmed "~~~")))
            relevant? (and fence? (>= line-num fm-end-line))
            missing-before? (and relevant? (not inside?) (pos? idx)
                                 (not (str/blank? (nth lines (dec idx)))))
            missing-after? (and relevant? inside? (< (inc idx) (count lines))
                                (not (str/blank? (nth lines (inc idx)))))
            violation
            (cond
              missing-before?
              {::rule :blanks-around-fences
               ::severity :warning
               ::line line-num
               ::message "Missing blank line before code fence"
               ::fix "Add blank line before code fence"}

              missing-after?
              {::rule :blanks-around-fences
               ::severity :warning
               ::line line-num
               ::message "Missing blank line after code fence"
               ::fix "Add blank line after code fence"})]
        (recur (inc idx)
               (if fence? (not inside?) inside?)
               (cond-> violations violation (conj violation)))))))

(defn- rule-trailing-newline
  "File ends with exactly one newline."
  [content]
  (cond
    (str/blank? content) nil
    (not (str/ends-with? content "\n"))
    [{::rule :trailing-newline
      ::severity :warning
      ::line (count (str/split-lines content))
      ::message "File does not end with a newline"
      ::fix "Add trailing newline"}]
    (str/ends-with? content "\n\n")
    [{::rule :trailing-newline
      ::severity :warning
      ::line (count (str/split-lines content))
      ::message "File ends with multiple newlines"
      ::fix "Remove extra trailing newlines"}]))

(defn- rule-fenced-code-style
  "Backtick ``` not tilde ~~~."
  [lines code-lines]
  (into []
        (keep-indexed
         (fn [idx line]
           (let [line-num (inc idx)
                 trimmed (str/trim line)]
             (when (and (contains? code-lines line-num)
                        (str/starts-with? trimmed "~~~"))
               {::rule :fenced-code-style
                ::severity :warning
                ::line line-num
                ::message "Use backtick fences (```) instead of tilde (~~~)"
                ::fix "Replace ~~~ with ```"}))))
        lines))

(defn- rule-list-style
  "Dash - for unordered lists, not * or +."
  [lines code-lines]
  (into []
        (keep-indexed
         (fn [idx line]
           (let [line-num (inc idx)]
             (when-not (contains? code-lines line-num)
               (when (re-matches #"^(\s*)[\*\+]\s+.*" line)
                 {::rule :list-style
                  ::severity :warning
                  ::line line-num
                  ::message "Use dash (-) for unordered lists, not * or +"
                  ::fix "Replace list marker with -"})))))
        lines))

(defn- rule-has-frontmatter
  "YAML frontmatter required."
  [frontmatter]
  (when-not frontmatter
    [{::rule :has-frontmatter
      ::severity :error
      ::line 1
      ::message "Missing YAML frontmatter (---)"
      ::fix "Add frontmatter with type and status fields"}]))

(defn- rule-required-fields
  "type and status fields present in frontmatter."
  [frontmatter]
  (when frontmatter
    (let [violations (transient [])]
      (when-not (:type frontmatter)
        (conj! violations
               {::rule :required-fields
                ::severity :error
                ::line 1
                ::message "Frontmatter missing required field: type"
                ::fix "Add 'type: <value>' to frontmatter"}))
      (when-not (:status frontmatter)
        (conj! violations
               {::rule :required-fields
                ::severity :error
                ::line 1
                ::message "Frontmatter missing required field: status"
                ::fix "Add 'status: <value>' to frontmatter"}))
      (persistent! violations))))

(defn- parse-tag-list
  "Parse a frontmatter tags value like '[database, schema]' into keywords."
  [tags-str]
  (when (and tags-str (not (str/blank? tags-str)))
    (let [cleaned (-> tags-str
                      (str/replace #"^\[" "")
                      (str/replace #"\]$" ""))]
      (mapv (comp keyword str/trim) (str/split cleaned #",")))))

(def ^:private vault-vocabulary
  "Corpus-derived frontmatter vocabulary for one vault root.

   The vocabulary IS live usage: a tag or type value belongs once at least
   two documents in the vault carry it, so a singleton is either a typo or
   vocabulary nothing else has adopted. Computed, never a literal name set.
   Memoized per vault root; a fresh process re-derives it from the files."
  (memoize
   (fn [vault-root]
     (let [documents
           (into []
                 (comp (filter (fn [^File file] (.isFile file)))
                       (filter (fn [^File file]
                                 (str/ends-with? (.getName file) ".md")))
                       (keep (fn [file] (first (parse-frontmatter (slurp file))))))
                 (file-seq (io/file vault-root)))
           adopted (fn [values]
                     (into #{}
                           (keep (fn [[value uses]] (when (<= 2 uses) value)))
                           (frequencies values)))]
       {::tag-vocabulary
        (adopted (mapcat (fn [fm] (distinct (parse-tag-list (:tags fm))))
                         documents))
        ::type-vocabulary
        (adopted (keep (comp keyword :type) documents))}))))

(defn- rule-valid-tags
  "Every tag must belong to the vault's corpus-derived tag vocabulary."
  [frontmatter vault-root]
  (when-let [tags-str (and vault-root (:tags frontmatter))]
    (let [vocabulary (::tag-vocabulary (vault-vocabulary vault-root))]
      (into []
            (keep (fn [tag]
                    (when-not (contains? vocabulary tag)
                      {::rule :valid-tags
                       ::severity :warning
                       ::line 1
                       ::message (format "Tag not in vault vocabulary: %s (no second document uses it)"
                                         (name tag))
                       ::fix (format "Fix the typo, or adopt '%s' in a second document"
                                     (name tag))})))
            (parse-tag-list tags-str)))))

(defn- rule-valid-type
  "type must belong to the vault's corpus-derived type vocabulary."
  [frontmatter vault-root]
  (when-let [type-val (and vault-root (:type frontmatter))]
    (let [vocabulary (::type-vocabulary (vault-vocabulary vault-root))
          type-kw (keyword type-val)]
      (when-not (contains? vocabulary type-kw)
        [{::rule :valid-type
          ::severity :warning
          ::line 1
          ::message (format "Type not in vault vocabulary: %s (no second document uses it)"
                            type-val)
          ::fix (format "Fix the typo, or adopt '%s' in a second document"
                        type-val)}]))))

(defn- find-in-vault
  "Search vault for a file matching target (Obsidian-style shortest path).
   Tries: vault-root/target.md, then recursive search for any file named target.md."
  [vault-root target]
  (let [file-target (str/replace target #"\.md$" "")
        direct-candidates [(io/file vault-root (str file-target ".md"))
                           (io/file vault-root file-target)]
        direct-hit (some #(when (.exists %) %) direct-candidates)]
    (if direct-hit
      true
      ;; Vault-wide search: find any file matching the basename
      (let [basename (last (str/split file-target #"/"))
            target-name (str basename ".md")]
        (boolean
         (some #(and (.isFile %)
                     (= (.getName %) target-name))
               (file-seq (io/file vault-root))))))))

(defn- rule-wikilink-target-exists
  "Wikilink targets must resolve to existing files.
   Uses Obsidian-style resolution: direct path first, then vault-wide basename search."
  [links vault-root]
  (when vault-root
    (into []
          (keep (fn [link]
                  (when (= :wikilink (::type link))
                    (let [target (::target link)
                          file-target (first (str/split target #"#"))
                          exists? (find-in-vault vault-root file-target)]
                      (when-not exists?
                        {::rule :wikilink-target-exists
                         ::severity :warning
                         ::line (::line link)
                         ::message (format "Wikilink target not found: [[%s]]" target)
                         ::fix (format "Check that '%s.md' exists under %s" file-target vault-root)})))))
          links)))

(defn- rule-no-bare-urls
  "URLs should be in markdown link syntax."
  [links]
  (into []
        (keep (fn [link]
                (when (= :bare-url (::type link))
                  {::rule :no-bare-urls
                   ::severity :info
                   ::line (::line link)
                   ::message (format "Bare URL: %s" (::target link))
                   ::fix "Wrap in markdown link: [text](url)"})))
        links))

;;; ---------------------------------------------------------------------------
;;; All Rules
;;; ---------------------------------------------------------------------------

(def ^:private all-rules
  "Set of all available rule keywords."
  #{:heading-style :heading-increment :single-h1
    :trailing-whitespace :no-multiple-blanks
    :blanks-around-headings :blanks-around-fences
    :trailing-newline :fenced-code-style :list-style
    :has-frontmatter :required-fields :valid-tags :valid-type
    :wikilink-target-exists :no-bare-urls})

(defn- run-rules
  "Run selected rules against parsed data. Returns vector of violations."
  [{:keys [content lines code-lines headings links frontmatter vault-root rules fm-end-line]}]
  (let [active (or rules all-rules)
        run? (fn [r] (contains? active r))
        fm-end (or fm-end-line 1)]
    (into []
          cat
          [(when (run? :heading-style) (rule-heading-style lines code-lines fm-end))
           (when (run? :heading-increment) (rule-heading-increment headings))
           (when (run? :single-h1) (rule-single-h1 headings))
           (when (run? :trailing-whitespace) (rule-trailing-whitespace lines code-lines))
           (when (run? :no-multiple-blanks) (rule-no-multiple-blanks lines code-lines))
           (when (run? :blanks-around-headings) (rule-blanks-around-headings lines code-lines headings fm-end))
           (when (run? :blanks-around-fences) (rule-blanks-around-fences lines code-lines fm-end))
           (when (run? :trailing-newline) (rule-trailing-newline content))
           (when (run? :fenced-code-style) (rule-fenced-code-style lines code-lines))
           (when (run? :list-style) (rule-list-style lines code-lines))
           (when (run? :has-frontmatter) (rule-has-frontmatter frontmatter))
           (when (run? :required-fields) (rule-required-fields frontmatter))
           (when (run? :valid-tags) (rule-valid-tags frontmatter vault-root))
           (when (run? :valid-type) (rule-valid-type frontmatter vault-root))
           (when (run? :wikilink-target-exists) (rule-wikilink-target-exists links vault-root))
           (when (run? :no-bare-urls) (rule-no-bare-urls links))])))

;;; ---------------------------------------------------------------------------
;;; Auto-Fix
;;; ---------------------------------------------------------------------------

(defn- fix-trailing-whitespace
  "Strip trailing whitespace from lines (preserving exactly 2-space line breaks)."
  [content]
  (let [had-trailing-newline (str/ends-with? content "\n")
        lines (str/split-lines content)
        joined (str/join "\n"
                         (mapv (fn [line]
                                 (let [trimmed (str/trimr line)
                                       trailing (subs line (count trimmed))]
                                   (if (= trailing "  ")
                                     line ; Preserve intentional 2-space line break
                                     trimmed)))
                               lines))]
    (if had-trailing-newline
      (str joined "\n")
      joined)))

(defn- fix-multiple-blanks
  "Collapse consecutive blank lines to single blank line."
  [content]
  (str/replace content #"\n{3,}" "\n\n"))

(defn- fix-trailing-newline
  "Ensure file ends with exactly one newline."
  [content]
  (if (str/blank? content)
    content
    (str (str/trimr content) "\n")))

(defn- fix-blanks-around-headings
  "Insert blank lines around headings that don't have them."
  [content]
  (let [had-trailing-newline (str/ends-with? content "\n")
        lines (str/split-lines content)
        code-lines (in-code-block? lines)
        result (atom [])]
    (doseq [[idx line] (map-indexed vector lines)]
      (let [line-num (inc idx)
            is-heading (and (not (contains? code-lines line-num))
                            (re-matches #"^#{1,6}\s+.*" line))
            prev-line (when (pos? idx) (nth lines (dec idx)))]
        ;; Add blank line before heading if needed
        (when (and is-heading
                   prev-line
                   (not (str/blank? prev-line))
                   (not (= "---" (str/trim prev-line))))
          (swap! result conj ""))
        (swap! result conj line)
        ;; Add blank line after heading if needed
        (when (and is-heading
                   (< (inc idx) (count lines))
                   (not (str/blank? (nth lines (inc idx)))))
          (swap! result conj ""))))
    (let [joined (str/join "\n" @result)]
      (if had-trailing-newline
        (str joined "\n")
        joined))))

(defn- fix-blanks-around-fences
  "Insert blank lines around code fences that don't have them."
  [content]
  (let [had-trailing-newline (str/ends-with? content "\n")
        lines (str/split-lines content)
        result
        (loop [idx 0, inside? false, output []]
          (if (= idx (count lines))
            output
            (let [line (nth lines idx)
                  trimmed (str/trim line)
                  fence? (or (str/starts-with? trimmed "```")
                             (str/starts-with? trimmed "~~~"))
                  opening? (and fence? (not inside?))
                  closing? (and fence? inside?)
                  missing-before? (and opening? (seq output)
                                       (not (str/blank? (peek output))))
                  missing-after? (and closing? (< (inc idx) (count lines))
                                      (not (str/blank? (nth lines (inc idx)))))
                  output (cond-> output missing-before? (conj ""))
                  output (cond-> (conj output line) missing-after? (conj ""))]
              (recur (inc idx)
                     (if fence? (not inside?) inside?)
                     output))))
        joined (str/join "\n" result)]
      (if had-trailing-newline
        (str joined "\n")
        joined)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn parse
  "Parse markdown string into structured document.

   Request keys:
     ::content - Raw markdown string

   Response keys:
     ::content     - Original content
     ::frontmatter - Optional YAML frontmatter map
     ::headings    - Vector of heading maps
     ::links       - Vector of link maps
     ::sections    - Vector of section maps

   Example:
     (parse {::content \"---\\ntype: component\\n---\\n# Title\\n\\nBody\"})
     ;; => {::content \"...\" ::frontmatter {:type \"component\"} ::headings [...] ...}"
  {:malli/schema [:=> [:cat #'parse-request-schema] #'document-schema]}
  [{::keys [content]}]
  (let [[frontmatter _remaining _fm-end-line] (parse-frontmatter content)
        lines (str/split-lines content)
        code-lines (in-code-block? lines)
        headings (extract-headings lines code-lines)
        links (extract-links lines code-lines)
        sections (extract-sections lines headings)]
    (cond-> {::content content
             ::headings headings
             ::links links
             ::sections sections}
      frontmatter (assoc ::frontmatter frontmatter))))

(defn validate
  "Run rules against markdown content, collect violations.

   Request keys:
     ::content    - Raw markdown string
     ::rules      - Optional set of rule keywords to check (default: all)
     ::vault-root - Optional vault root for wikilink resolution

   Response keys:
     ::valid?     - true if no violations found
     ::violations - Vector of violation maps
     ::document   - Parsed document structure

   Example:
     (validate {::content \"# Test\\n\"})
     ;; => {::valid? false ::violations [...] ::document {...}}"
  {:malli/schema
   [:=> [:cat #'validate-request-schema] #'validate-response-schema]}
  [{::keys [content rules vault-root file-path]}]
  (let [[_fm _remaining fm-end-line] (parse-frontmatter content)
        document (parse {::content content})
        lines (str/split-lines content)
        code-lines (in-code-block? lines)
        violations (run-rules {:content content
                               :lines lines
                               :code-lines code-lines
                               :headings (::headings document)
                               :links (::links document)
                               :frontmatter (::frontmatter document)
                               :vault-root vault-root
                               :rules rules
                               :fm-end-line (or fm-end-line 1)
                               :file-path file-path})]
    {::valid? (empty? violations)
     ::violations violations
     ::document document}))

(defn validate-file
  "Read file and validate its markdown content.

   Request keys:
     ::file-path  - Path to markdown file
     ::vault-root - Optional vault root for wikilink resolution

   Response keys:
     Same as validate

   Example:
     (validate-file {::file-path \"docs/seon/components/database.md\"
                     ::vault-root \"docs\"})"
  {:malli/schema
   [:=> [:cat #'validate-file-request-schema] #'validate-response-schema]}
  [{::keys [file-path vault-root]}]
  (let [f (io/file file-path)]
    (if (.exists f)
      (let [content (slurp f)]
        (validate (cond-> {::content content ::file-path file-path}
                    vault-root (assoc ::vault-root vault-root))))
      {::valid? false
       ::violations [{::rule :file-not-found
                      ::severity :error
                      ::message (str "File not found: " file-path)}]
       ::document {::content ""
                   ::headings []
                   ::links []
                   ::sections []}})))

(defn format-violations
  "Format violations for human-readable hook feedback.

   Request keys:
     ::violations  - Vector of violation maps
     ::max-length  - Optional max output length (default: 1000)

   Response keys:
     ::formatted - Formatted string

   Example:
     (format-violations {::violations [...] ::max-length 500})"
  {:malli/schema
   [:=>
    [:cat #'format-violations-request-schema]
    #'format-violations-response-schema]}
  [{::keys [violations max-length]}]
  (let [max-len (or max-length 1000)
        by-severity (group-by ::severity violations)
        errors (:error by-severity)
        warnings (:warning by-severity)
        infos (:info by-severity)
        parts (cond-> []
                (seq errors)
                (conj (str "ERRORS (" (count errors) "):\n"
                           (str/join "\n" (map (fn [v]
                                                 (str "  L" (or (::line v) "?") ": " (::message v)))
                                               errors))))
                (seq warnings)
                (conj (str "WARNINGS (" (count warnings) "):\n"
                           (str/join "\n" (map (fn [v]
                                                 (str "  L" (or (::line v) "?") ": " (::message v)))
                                               warnings))))
                (seq infos)
                (conj (str "INFO (" (count infos) "):\n"
                           (str/join "\n" (map (fn [v]
                                                 (str "  L" (or (::line v) "?") ": " (::message v)))
                                               infos)))))
        full-text (str "Markdown lint: " (count violations) " issue(s)\n"
                       (str/join "\n" parts))]
    {::formatted (let [suffix "\n... (truncated)"]
                   (if (> (count full-text) max-len)
                     (str (subs full-text 0 (max 0 (- max-len (count suffix)))) suffix)
                     full-text))}))

(defn fix
  "Auto-fix safe formatting issues in markdown content.

   Applies fixes in order:
   1. Strip trailing whitespace
   2. Collapse multiple blank lines
   3. Insert blank lines around headings
   4. Insert blank lines around code fences
   5. Ensure trailing newline

   Request keys:
     ::content - Raw markdown string

   Response keys:
     ::content     - Fixed content
     ::fixed-count - Number of fixes applied

   Example:
     (fix {::content \"# No blank after\\ntext\"})
     ;; => {::content \"# No blank after\\n\\ntext\\n\" ::fixed-count 2}"
  {:malli/schema [:=> [:cat #'fix-request-schema] #'fix-response-schema]}
  [{::keys [content]}]
  (let [fixes (atom 0)
        apply-fix (fn [c fix-fn]
                    (let [result (fix-fn c)]
                      (when (not= c result)
                        (swap! fixes inc))
                      result))
        result (-> content
                   (apply-fix fix-trailing-whitespace)
                   (apply-fix fix-multiple-blanks)
                   (apply-fix fix-blanks-around-headings)
                   (apply-fix fix-blanks-around-fences)
                   (apply-fix fix-trailing-newline))]
    {::content result
     ::fixed-count @fixes}))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  ;; Parse a document
  (parse {::content "---\ntype: component\nstatus: active\ntags: [database, schema]\n---\n# Title\n\nSome [[link]] and [text](url)."})

  ;; Validate content
  (validate {::content "# Test\n\nHello world"})

  ;; Validate a real file
  (validate-file {::file-path "docs/seon/components/database.md"
                  ::vault-root "docs"})

  ;; Format violations
  (format-violations {::violations [{::rule :has-frontmatter
                                     ::severity :error
                                     ::line 1
                                     ::message "Missing frontmatter"}]})

  ;; Auto-fix
  (fix {::content "# Title\ntext  \n\n\nmore"})

  nil)
