(ns seon.dev.docstring
  "WARN-ONLY doc-lint for public-fn docstring FIRST LINES.

   The compact-namespace-card render shows ONLY a fn's docstring line 1
   (`(first (str/split-lines doc))`). For those cards to read as clean,
   complete sentences, line 1 must be a self-contained thought within the
   width the codebase already uses. This linter ENFORCES that seam so the
   convention is self-sustaining — new fns comply, agents see consistent
   cards and imitate. It is the enforcement half of the compact-cards spec
   (`docs/prds/agent-fsm/compact-namespace-cards-spec.md`).

   Sibling to `seon.dev.markdown`: same shape (pure analysis, namespaced
   findings, `format-findings` for hook feedback), but over Clojure source
   instead of markdown. Parsing is purely syntactic via rewrite-clj — no
   eval, no classpath, tolerant of `.cljs` `#js`/reader-conditionals.

   The rule (line 1 of a PUBLIC `defn`'s docstring):
     (a) present,
     (b) <= 78 chars (72 ideal — the fill-column already in use),
     (c) ends in terminal punctuation (`.`/`?`/`!`).

   PLUS a body rule (any docstring line): a docstring must carry NO reserved
   RESULT-GRAMMAR glyph literal (result-open/close, status-open/close, prompt —
   the five `seon.agent.ctx/reserved-glyphs`). Those glyphs are RUNTIME-ONLY
   (the transcript emits them from the constants); a docstring renders into
   agent context, so a literal example there both models the fabrication shape
   can imitate runtime chrome AND drifts if the glyph ever changes (static text
   can't reference the constant). Static agent-facing text
   shows the CALL and describes the return in PROSE. Computed via
   [[wrong-echo-re]] (a structural regex over the glyph set, never a file
   list). The value-VOCABULARY glyphs (`⟨N tok⟩`/`‹…›`/`«…»`) are NOT reserved
   and NOT flagged.

   WARN-ONLY, by design NEVER blocks: ~560 existing docstrings are
   non-compliant; a blocking lint would wedge the shared multi-agent tree.
   It REPORTS and lets the edit through. It does NOT auto-fix — the cleanup
   sweep is a separate human/agent job.

   Only PUBLIC `defn` fns are checked: `defn-`, `^:private`, `*.internal`
   and `*-test` namespaces are skipped; non-`defn` top-level forms are
   skipped; multi-arity and no-docstring fns are handled.

   Public API:
     (check-source {::source \"(ns ...)\\n(defn ...)\"})
     (check-file {::file-path \"src/seon/foo.clj\"})
     (format-findings {::findings [...] ::max-length 800})
     (scan {::file-paths [\"src/seon/foo.clj\" ...]})"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]))

;;; ---------------------------------------------------------------------------
;;; Config
;;; ---------------------------------------------------------------------------

(def ^:private hard-cap
  "Hard cap on docstring line 1 length. 72 is the ideal fill-column; 78 is
   where the good exemplars top out — WARN only beyond it."
  78)

(def ^:private terminal-punctuation
  "A complete sentence ends in one of these."
  #{\. \? \!})

(defn- excluded-namespace?
  "True for test and internal namespaces omitted from docstring linting."
  [namespace-name]
  (let [namespace-name (str namespace-name)]
    (or (str/ends-with? namespace-name "-test")
        (str/ends-with? namespace-name ".internal")
        (str/includes? namespace-name ".internal."))))

(def ^:private wrong-echo-re
  "A RESERVED result-grammar glyph literal in a docstring — result-open `⟹`
   (U+27F9), result-close `⟸` (U+27F8), status-open/close `⋘`/`⋙`
   (U+22D8/U+22D9), or prompt `❯` (U+276F). These are RUNTIME-ONLY glyphs
   (`seon.agent.ctx/reserved-glyphs`) the transcript emits from its constants;
   a docstring renders into agent context, so a literal one both imitates
   runtime chrome AND drifts if the glyph changes (static text can't reference
   the constant). A char class
   over exactly the five reserves — the value-VOCABULARY glyphs
   (`⟨⟩`/`‹›`/`«»`) are NOT reserved and NOT matched. Any occurrence on a line
   is flagged (this is the anti-fabrication inversion of the old
   require-a-`; ⟹`-echo rule)."
  #"[\x{27F9}\x{27F8}\x{22D8}\x{22D9}\x{276F}]")

(def ^:private comment-result-re
  "A COMMENT-SHAPED RESULT echo in a docstring line — `;; =>`, `; =>`,
   or a comment followed by the result glyph. Ruling 45
   (context-generation ledger): results print BARE; a result rendered
   as a comment teaches agents to fabricate results as comments —
   observed destroying agents in both implementations. The 2026-08-17
   sweep removed 300+ instances; this guard keeps them from
   reseeding. Prose comments describing the return (`; returns 1`)
   carry no arrow and stay clean."
  #"^\s*;{1,3}\s*(=>|\x{27F9})")

;;; ---------------------------------------------------------------------------
;;; Contracts (ordinary Vars keep this Babashka-loadable)
;;; ---------------------------------------------------------------------------

(def ^:private rule-schema
  [:enum
   :missing-docstring
   :blank-first-line
   :first-line-too-long
   :no-terminal-punctuation
   :reserved-glyph-literal])

(def ^:private finding-schema
  [:map
   [::fn-name :string]
   [::rule #'rule-schema]
   [::line [:int {:min 1}]]
   [::message :string]
   [::first-line {:optional true} :string]
   [::file-path {:optional true} [:string {:min 1}]]])

(def ^:private findings-schema [:vector #'finding-schema])

(def ^:private check-source-request-schema
  [:map [::source :string] [::ns-name {:optional true} :string]])

(def ^:private check-response-schema
  [:map
   [::clean? :boolean]
   [::skipped? :boolean]
   [::findings #'findings-schema]])

(def ^:private check-file-request-schema
  [:map [::file-path [:string {:min 1}]]])

(def ^:private format-findings-request-schema
  [:map
   [::findings #'findings-schema]
   [::max-length {:optional true} [:int {:min 1}]]])

(def ^:private format-findings-response-schema [:map [::formatted :string]])

(def ^:private scan-request-schema
  [:map [::file-paths [:vector [:string {:min 1}]]]])

(def ^:private scan-response-schema
  [:map
   [::file-count [:int {:min 0}]]
   [::fn-count [:int {:min 0}]]
   [::finding-count [:int {:min 0}]]
   [::by-rule [:map-of #'rule-schema :int]]
   [::findings #'findings-schema]])

;;; ---------------------------------------------------------------------------
;;; Source analysis (rewrite-clj — purely syntactic, no eval)
;;; ---------------------------------------------------------------------------

(defn- safe-sexpr
  "n/sexpr guarded — returns nil on any tagged-literal / unreadable node."
  [node]
  (try (n/sexpr node) (catch Exception _ nil)))

(defn- extract-ns-name
  "First `(ns X ...)` form's name as a string, or nil."
  [top-nodes]
  (some (fn [node]
          (when (= :list (n/tag node))
            (let [kids (remove n/whitespace? (n/children node))
                  head (first kids)]
              (when (and head (= :token (n/tag head))
                         (= 'ns (safe-sexpr head)))
                (let [nm (safe-sexpr (second kids))]
                  (when (symbol? nm) (str nm)))))))
        top-nodes))

(defn- public-defn
  "If `node` is a public `defn` form, return {:fn-name :doc :line}; else nil.
   Skips `defn-` and `^:private`/`{:private true}` names. Docstring is the
   string literal immediately after the name (canonical defn arg order), so a
   fn with an attr-map or arglist first reads as no-docstring."
  [node]
  (when (= :list (n/tag node))
    (let [kids (remove n/whitespace? (n/children node))
          head (first kids)]
      (when (and head (= :token (n/tag head))
                 (= 'defn (safe-sexpr head)))
        (let [name-node (second kids)
              name-sexpr (safe-sexpr name-node)]
          (when (and (symbol? name-sexpr)
                     (not (:private (meta name-sexpr))))
            (let [after-name (nth (vec kids) 2 nil)
                  doc (when after-name
                        (let [v (safe-sexpr after-name)]
                          (when (string? v) v)))]
              {:fn-name (str name-sexpr)
               :doc doc
               :line (or (:row (meta node)) 1)})))))))

(defn- doc-first-line
  "Line 1 of a docstring, trailing whitespace stripped (nil in → nil)."
  [doc]
  (when doc
    (str/trimr (first (str/split-lines doc)))))

(defn- check-one
  "Return a finding map for a public defn, or nil when line 1 is compliant."
  [{:keys [fn-name doc line]}]
  (let [base {::fn-name fn-name ::line line}]
    (cond
      (nil? doc)
      (assoc base ::rule :missing-docstring
             ::message (str fn-name ": public fn has no docstring"))

      :else
      (let [line1 (doc-first-line doc)]
        (cond
          (str/blank? line1)
          (assoc base ::rule :blank-first-line
                 ::message (str fn-name ": docstring first line is blank"))

          (> (count line1) hard-cap)
          (assoc base ::rule :first-line-too-long
                 ::first-line line1
                 ::message (str fn-name ": docstring line 1 is " (count line1)
                                " chars (cap " hard-cap ")"))

          (not (contains? terminal-punctuation (last line1)))
          (assoc base ::rule :no-terminal-punctuation
                 ::first-line line1
                 ::message (str fn-name ": docstring line 1 lacks terminal"
                                " punctuation (. ? !)"))

          :else nil)))))

(defn- check-echoes
  "Findings for a public defn's docstring BODY lines carrying a RESERVED
   result-grammar glyph literal (`⟹`/`⟸`/`⋘`/`⋙`/`❯` — [[wrong-echo-re]]).
   Those glyphs are runtime-only; a docstring must show the CALL and describe
   the return in PROSE, never render a literal result line. One finding per
   offending line; empty when the docstring is clean or nil."
  [{:keys [fn-name doc line]}]
  (when doc
    (into []
          (keep (fn [ln]
                  (cond
                    (re-find comment-result-re ln)
                    {::fn-name fn-name
                     ::rule :comment-shaped-result
                     ::line line
                     ::first-line (str/trim ln)
                     ::message (str fn-name ": docstring carries a"
                                    " comment-shaped result echo — results"
                                    " print bare (ruling 45); show the call"
                                    " and describe the return in prose")}

                    (re-find wrong-echo-re ln)
                    {::fn-name fn-name
                     ::rule :reserved-glyph-literal
                     ::line line
                     ::first-line (str/trim ln)
                     ::message (str fn-name ": docstring carries a reserved"
                                    " runtime result-grammar glyph — describe"
                                    " the return in prose; the runtime alone"
                                    " emits the glyph from its constant")})))
          (str/split-lines doc))))

(defn- check-fn
  "All findings for one public defn: the line-1 finding (if any) plus every
   reserved-glyph-literal in the body."
  [defn-map]
  (concat (some-> (check-one defn-map) vector)
          (check-echoes defn-map)))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn check-source
  "Lint a Clojure/CLJS source string's public-fn docstring first lines.

   Request keys:
     ::source  - Raw source string
     ::ns-name - Optional ns name; parsed from the source when omitted

   Response keys:
     ::clean?   - true when no findings (also true when skipped)
     ::skipped? - true when the ns is `*-test`/`*.internal` (not linted)
     ::findings - Vector of finding maps (empty when clean or skipped)

   Purely syntactic (rewrite-clj) — never evals, tolerant of `#js` and
   reader conditionals. WARN-ONLY: it reports, never blocks."
  {:malli/schema
   [:=> [:cat #'check-source-request-schema] #'check-response-schema]}
  [{::keys [source ns-name]}]
  (let [top-nodes (try (n/children (p/parse-string-all source))
                       (catch Exception _ nil))
        nm (or ns-name (some-> top-nodes extract-ns-name))]
    (if (or (nil? top-nodes)
            (some-> nm excluded-namespace?))
      {::clean? true
       ::skipped? (boolean (some-> nm excluded-namespace?))
       ::findings []}
      (let [findings (into []
                           (comp (keep public-defn)
                                 (mapcat check-fn))
                           top-nodes)]
        {::clean? (empty? findings)
         ::skipped? false
         ::findings findings}))))

(defn check-file
  "Read a `.clj`/`.cljs`/`.cljc` file and lint its docstring first lines.

   Request keys:
     ::file-path - Path to the source file

   Response keys: same as `check-source`. A missing file returns clean +
   skipped (nothing to lint) rather than throwing."
  {:malli/schema
   [:=> [:cat #'check-file-request-schema] #'check-response-schema]}
  [{::keys [file-path]}]
  (let [f (io/file file-path)]
    (if (.exists f)
      (check-source {::source (slurp f)})
      {::clean? true ::skipped? true ::findings []})))

(defn format-findings
  "Format findings into human-readable hook feedback (WARN header).

   Request keys:
     ::findings   - Vector of finding maps
     ::max-length - Optional max output length (default 1000)

   Response keys:
     ::formatted - The formatted string (truncated to max-length)."
  {:malli/schema
   [:=>
    [:cat #'format-findings-request-schema]
    #'format-findings-response-schema]}
  [{::keys [findings max-length]}]
  (let [max-len (or max-length 1000)
        lines (map (fn [v]
                     (str "  L" (::line v) " " (::message v)))
                   findings)
        full (str "Docstring lint (WARN, non-blocking): "
                  (count findings) " public-fn docstring issue(s)\n"
                  (str/join "\n" lines))]
    {::formatted (let [suffix "\n... (truncated)"]
                   (if (> (count full) max-len)
                     (str (subs full 0 (max 0 (- max-len (count suffix)))) suffix)
                     full))}))

(defn scan
  "Lint many source files and aggregate — for audits and corpus checks.

   Request keys:
     ::file-paths - Vector of source-file paths

   Response keys:
     ::file-count   - Files actually linted (test/internal/missing skipped)
     ::fn-count     - Public defn heads inspected
     ::finding-count- Total findings
     ::by-rule      - Findings grouped/counted by rule
     ::findings     - All findings, each carrying the source path in
                      `::file-path`."
  {:malli/schema [:=> [:cat #'scan-request-schema] #'scan-response-schema]}
  [{::keys [file-paths]}]
  (reduce
   (fn [acc path]
     (let [f (io/file path)]
       (if-not (.exists f)
         acc
         (let [source (slurp f)
               top-nodes (try (n/children (p/parse-string-all source))
                              (catch Exception _ nil))
               nm (some-> top-nodes extract-ns-name)]
           (if (or (nil? top-nodes)
                   (some-> nm excluded-namespace?))
             acc
             (let [defns (into [] (keep public-defn) top-nodes)
                   findings (into []
                                  (comp (mapcat check-fn)
                                        (map #(assoc % ::file-path path)))
                                  defns)]
               (-> acc
                   (update ::file-count inc)
                   (update ::fn-count + (count defns))
                   (update ::finding-count + (count findings))
                   (update ::by-rule
                           #(reduce (fn [m fnd] (update m (::rule fnd) (fnil inc 0)))
                                    % findings))
                   (update ::findings into findings))))))))
   {::file-count 0 ::fn-count 0 ::finding-count 0 ::by-rule {} ::findings []}
   file-paths))

;;; ---------------------------------------------------------------------------
;;; Development Helpers (REPL)
;;; ---------------------------------------------------------------------------

(comment
  (check-source {::source "(ns foo)\n(defn bar \"Ok.\" [x] x)"})
  (check-source {::source "(ns foo)\n(defn bar [x] x)"})
  (check-file {::file-path "src/seon/dev/markdown.clj"})

  ;; Corpus audit
  (require '[clojure.java.io :as io])
  (let [paths (->> (file-seq (io/file "src"))
                   (filter #(.isFile %))
                   (map str)
                   (filter #(re-find #"\.clj[cs]?$" %)))]
    (dissoc (scan {::file-paths (vec paths)}) ::findings))

  nil)
