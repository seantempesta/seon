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
            [clojure.string :as str]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::level
                  [:int {:min 1 :max 6
                         :description "Heading level (1-6)"}])

(schema/register! ::text
                  [:string {:description "Text content"}])

(schema/register! ::line
                  [:int {:min 1
                         :description "Line number (1-based)"}])

(schema/register! ::content
                  [:string {:description "Raw markdown content"}])

(schema/register! ::heading
                  [:map
                   [::level ::level]
                   [::text ::text]
                   [::line ::line]])

(schema/register! ::link-type
                  [:enum :wikilink :markdown :bare-url])

(schema/register! ::target
                  [:string {:min 1 :description "Link target (URL or file)"}])

(schema/register! ::link
                  [:map
                   [::type ::link-type]
                   [::target ::target]
                   [::text {:optional true} ::text]
                   [::line ::line]])

(schema/register! ::line-start
                  [:int {:min 1 :description "Start line of section"}])

(schema/register! ::line-end
                  [:int {:min 1 :description "End line of section"}])

(schema/register! ::section
                  [:map
                   [::heading ::heading]
                   [::content ::content]
                   [::line-start ::line-start]
                   [::line-end ::line-end]])

(schema/register! ::tag
                  [:enum
                   :component :concept :issue :architecture :vision :reference
                   :database :schema :flow :web :agent :trading :health
                   :prd :decision :research
                   :dashboard :index
                   :capability :milestone :orchestrator :archive])

(schema/register! ::frontmatter
                  [:map-of :keyword :string])

(schema/register! ::headings
                  [:vector ::heading])

(schema/register! ::links
                  [:vector ::link])

(schema/register! ::sections
                  [:vector ::section])

(schema/register! ::document
                  [:map
                   [::content ::content]
                   [::frontmatter {:optional true} ::frontmatter]
                   [::headings ::headings]
                   [::links ::links]
                   [::sections ::sections]])

;; Violation schemas
(schema/register! ::rule
                  [:keyword {:description "Rule identifier"}])

(schema/register! ::severity
                  [:enum :error :warning :info])

(schema/register! ::message
                  [:string {:description "Human-readable violation message"}])

(schema/register! ::fix
                  [:string {:description "Suggested fix"}])

(schema/register! ::violation
                  [:map
                   [::rule ::rule]
                   [::severity ::severity]
                   [::line {:optional true} ::line]
                   [::message ::message]
                   [::fix {:optional true} ::fix]])

(schema/register! ::valid?
                  :boolean)

(schema/register! ::violations
                  [:vector ::violation])

;; Request/response schemas
(schema/register! ::parse-request
                  [:map
                   [::content ::content]])

(schema/register! ::parse-response
                  ::document)

(schema/register! ::rules
                  [:set :keyword])

(schema/register! ::vault-root
                  [:string {:min 1 :description "Root directory for vault (e.g. 'docs')"}])

(schema/register! ::file-path
                  [:string {:min 1 :description "Path to markdown file"}])

(schema/register! ::validate-request
                  [:map
                   [::content ::content]
                   [::rules {:optional true} ::rules]
                   [::vault-root {:optional true} ::vault-root]
                   [::file-path {:optional true} ::file-path]])

(schema/register! ::validate-response
                  [:map
                   [::valid? ::valid?]
                   [::violations ::violations]
                   [::document ::document]])

(schema/register! ::validate-file-request
                  [:map
                   [::file-path ::file-path]
                   [::vault-root {:optional true} ::vault-root]])

(schema/register! ::max-length
                  [:int {:min 1 :description "Maximum output length in characters"}])

(schema/register! ::format-violations-request
                  [:map
                   [::violations ::violations]
                   [::max-length {:optional true} ::max-length]])

(schema/register! ::formatted
                  [:string {:description "Formatted violation text"}])

(schema/register! ::format-violations-response
                  [:map
                   [::formatted ::formatted]])

(schema/register! ::fix-request
                  [:map
                   [::content ::content]])

(schema/register! ::fixed-count
                  [:int {:min 0 :description "Number of fixes applied"}])

(schema/register! ::fix-response
                  [:map
                   [::content ::content]
                   [::fixed-count ::fixed-count]])

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
            (doseq [m (re-seq #"(?<!\(|\"|\[)https?://[^\s\)>\]\"']+" stripped)]
              ;; Only if not already inside a markdown link or angle brackets
              (let [before-url (subs stripped 0 (or (str/index-of stripped m) 0))]
                (when-not (or (re-find #"\]\($" before-url)
                              (str/ends-with? before-url "<"))
                  (swap! results conj
                         {::type :bare-url
                          ::target m
                          ::line line-num}))))))))
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
  (into []
        (keep-indexed
         (fn [idx line]
           (let [line-num (inc idx)
                 trimmed (str/trim line)]
             ;; Only check fence markers (which are in code-lines set)
             (when (and (contains? code-lines line-num)
                        (>= line-num fm-end-line) ; skip fences inside frontmatter region
                        (or (str/starts-with? trimmed "```")
                            (str/starts-with? trimmed "~~~")))
               ;; Check if previous line is not blank (and exists)
               (when (and (pos? idx)
                          (not (str/blank? (nth lines (dec idx))))
                          (not (contains? code-lines idx))) ; prev line not in code
                 {::rule :blanks-around-fences
                  ::severity :warning
                  ::line line-num
                  ::message "Missing blank line before code fence"
                  ::fix "Add blank line before code fence"})))))
        lines))

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

(def ^:private valid-tags
  "Set of valid tag values from the ::tag enum."
  #{:component :concept :issue :architecture :vision :reference
    :database :schema :flow :web :agent :trading :health
    :prd :decision :research
    :dashboard :index
    :capability :milestone :orchestrator :archive
    ;; Core / pod domain (Phase 3 WASM containment work).
    :pod :wasm :cljs :mcp})

(defn- parse-tag-list
  "Parse a frontmatter tags value like '[database, schema]' into keywords."
  [tags-str]
  (when (and tags-str (not (str/blank? tags-str)))
    (let [cleaned (-> tags-str
                      (str/replace #"^\[" "")
                      (str/replace #"\]$" ""))]
      (mapv (comp keyword str/trim) (str/split cleaned #",")))))

(defn- rule-valid-tags
  "Tags (if present) must be from ::tag enum."
  [frontmatter]
  (when-let [tags-str (:tags frontmatter)]
    (let [tags (parse-tag-list tags-str)]
      (into []
            (keep (fn [tag]
                    (when-not (contains? valid-tags tag)
                      {::rule :valid-tags
                       ::severity :warning
                       ::line 1
                       ::message (format "Invalid tag: %s (valid: %s)"
                                         (name tag)
                                         (str/join ", " (sort (map name valid-tags))))
                       ::fix (format "Use a valid tag instead of '%s'" (name tag))})))
            tags))))

(defn- rule-valid-type
  "type field must be one of the known tag values."
  [frontmatter]
  (when-let [type-val (:type frontmatter)]
    (let [type-kw (keyword type-val)]
      (when-not (contains? valid-tags type-kw)
        [{::rule :valid-type
          ::severity :warning
          ::line 1
          ::message (format "Invalid type: %s" type-val)
          ::fix (format "Use one of: %s" (str/join ", " (sort (map name valid-tags))))}]))))

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
;;; Skill ↔ system-text floor-duplication (warn-only)
;;;
;;; system-text (ctx.cljs) is the SINGLE always-on home for a universal
;;; load-bearing rule; a `seon-skills/*/SKILL.md` may only DEEPEN a floor rule
;;; behind a pointer, never restate it verbatim. This is a duplication
;;; DETECTOR — a contiguous ≥N-word run shared with the live system-text prose
;;; — not a hand-maintained name list. It reuses the existing markdown linter
;;; (no second linter), self-gates on the skill path, and is warn-only.
;;; ---------------------------------------------------------------------------

(def ^:private floor-source-file
  "The live always-on floor — read as text (a CLJS file, not eval'd here)."
  "src/seon/agent/ctx.cljs")

(def ^:private floor-ngram-size
  "A contiguous verbatim run this long counts as reproduction, not overlap."
  6)

(def ^:private floor-pointer-phrase
  "A skill section that carries this phrase is DEEPENING a floor rule (points
   at it), so its restatement is intentional and NOT flagged."
  "always in your context")

(defn- word-tokens
  "Lowercased alphanumeric word tokens of `s` (all punctuation dropped)."
  [s]
  (->> (str/split (str/lower-case (or s "")) #"[^a-z0-9]+")
       (remove str/blank?)
       vec))

(defn- floor-prose
  "The `;`-comment prose of ctx.cljs's `system-text` def between the
   `── system ──` / `── end system ──` markers, `\\n` line-joins flattened to
   spaces. \"\" when the source file is absent."
  []
  (let [f (io/file floor-source-file)]
    (if-not (.exists f)
      ""
      (let [src   (slurp f)
            start (str/index-of src "── system ──")
            end   (str/index-of src "── end system ──")]
        (if (and start end (< start end))
          (str/replace (subs src start end) "\\n" " ")
          "")))))

(defn- floor-ngram-set
  "Set of contiguous `floor-ngram-size`-word runs in the floor prose — the
   lookup index a skill line's runs are tested against."
  []
  (into #{} (map vec) (partition floor-ngram-size 1 (word-tokens (floor-prose)))))

(defn- skill-file?
  "True when `file-path` is a rendered-corpus `seon-skills/<name>/SKILL.md`."
  [file-path]
  (boolean (and file-path (re-find #"seon-skills/[^/]+/SKILL\.md$" file-path))))

(defn- line-reproduces-floor?
  "True when `line` shares a contiguous `floor-ngram-size`-word run with the
   floor (`grams`)."
  [grams line]
  (boolean (some grams (map vec (partition floor-ngram-size 1 (word-tokens line))))))

(defn- rule-skill-floor-duplication
  "Warn when a seon-skills SKILL.md line reproduces a ≥N-word verbatim run
   from the always-on floor WITHOUT a pointer prefix. A pointer (the phrase
   `always in your context`) declared in a section (h1/h2 scope, inherited by
   its h3+ subsections) marks the whole section as deepening the floor, so its
   restatement is intentional and not flagged. Self-gates on the skill path;
   returns [] for every other file (and reads the floor only for skill files)."
  [lines code-lines file-path]
  (if-not (skill-file? file-path)
    []
    (let [grams (floor-ngram-set)]
      (loop [i 0, pointer? false, out []]
        (if (>= i (count lines))
          out
          (let [line          (nth lines i)
                line-num      (inc i)
                hlevel        (when-let [m (re-matches #"^(#{1,6})\s+.*" line)]
                                (count (second m)))
                pointer-here? (str/includes? (str/lower-case line) floor-pointer-phrase)
                ;; A pointer resets at each top-level (h1/h2) section and is
                ;; inherited by that section's h3+ subsections.
                pointer?'     (if (and hlevel (<= hlevel 2))
                                pointer-here?
                                (or pointer? pointer-here?))
                dup?          (and (not pointer?')
                                   (not (contains? code-lines line-num))
                                   (line-reproduces-floor? grams line))]
            (recur (inc i) pointer?'
                   (if dup?
                     (conj out
                           {::rule :skill-floor-duplication
                            ::severity :warning
                            ::line line-num
                            ::message (str "Reproduces a system-text floor sentence (≥"
                                           floor-ngram-size " words verbatim) without a '"
                                           floor-pointer-phrase "' pointer — point at the floor, don't restate it")
                            ::fix (str "Prefix the section with the '" floor-pointer-phrase
                                       "' pointer, or trim the restatement to a reference")})
                     out))))))))

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
    :wikilink-target-exists :no-bare-urls
    :skill-floor-duplication})

(defn- run-rules
  "Run selected rules against parsed data. Returns vector of violations."
  [{:keys [content lines code-lines headings links frontmatter vault-root rules fm-end-line file-path]}]
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
           (when (run? :valid-tags) (rule-valid-tags frontmatter))
           (when (run? :valid-type) (rule-valid-type frontmatter))
           (when (run? :wikilink-target-exists) (rule-wikilink-target-exists links vault-root))
           (when (run? :no-bare-urls) (rule-no-bare-urls links))
           (when (run? :skill-floor-duplication) (rule-skill-floor-duplication lines code-lines file-path))])))

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
        result (atom [])]
    (doseq [[idx line] (map-indexed vector lines)]
      (let [trimmed (str/trim line)
            is-fence (or (str/starts-with? trimmed "```")
                         (str/starts-with? trimmed "~~~"))
            prev-line (when (pos? idx) (nth lines (dec idx)))]
        ;; Add blank line before fence if needed
        (when (and is-fence
                   prev-line
                   (not (str/blank? prev-line)))
          (swap! result conj ""))
        (swap! result conj line)))
    (let [joined (str/join "\n" @result)]
      (if had-trailing-newline
        (str joined "\n")
        joined))))

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
  {:malli/schema [:=> [:cat ::parse-request] ::parse-response]}
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
  {:malli/schema [:=> [:cat ::validate-request] ::validate-response]}
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
  {:malli/schema [:=> [:cat ::validate-file-request] ::validate-response]}
  [{::keys [file-path vault-root]}]
  (let [f (io/file file-path)]
    (if (.exists f)
      (let [content (slurp f)]
        (validate (cond-> {::content content ::file-path file-path}
                    vault-root (assoc ::vault-root vault-root))))
      (do
        (log/warn "Markdown file not found:" file-path)
        {::valid? false
         ::violations [{::rule :file-not-found
                        ::severity :error
                        ::message (str "File not found: " file-path)}]
         ::document {::content ""
                     ::headings []
                     ::links []
                     ::sections []}}))))

(defn format-violations
  "Format violations for human-readable hook feedback.

   Request keys:
     ::violations  - Vector of violation maps
     ::max-length  - Optional max output length (default: 1000)

   Response keys:
     ::formatted - Formatted string

   Example:
     (format-violations {::violations [...] ::max-length 500})"
  {:malli/schema [:=> [:cat ::format-violations-request] ::format-violations-response]}
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
  {:malli/schema [:=> [:cat ::fix-request] ::fix-response]}
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
