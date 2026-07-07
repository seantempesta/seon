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

   PLUS a body rule (any docstring line): a worked-example result echo must
   use the reserved runtime marker `; ⟹` (`seon.agent.ctx/result-marker`),
   NOT a stale `;=>`/`;; =>`/bare `=>` — that double-`;;=>` shape is the exact
   fabrication the pod's `neutralize-result-claims` strips, so teaching it in
   the rendered corpus primes the very lie the runtime rejects. Computed via
   [[wrong-echo-re]] (a structural regex, never a file list).

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
            [rewrite-clj.parser :as p]
            [seon.schema :as schema]))

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

(def ^:private wrong-echo-re
  "A docstring result-echo written on a STALE marker: a line whose first
   content is an arrow result-claim — `;=>`, `; =>`, `;;=>`, `;; =>`
   (or `⇒`), or a bare column-0 `=>` — followed by a value. The reserved
   RUNTIME result marker is `; ⟹` (`⟹`, U+27F9, `seon.agent.ctx/result-marker`),
   which the runtime alone writes; a worked example must echo results in that
   shape so the corpus never teaches the double-`;;=>` fabrication shape the
   pod's own `neutralize-result-claims` strips as unverified narration.

   `⟹` contains no `=>`/`⇒`, so a CORRECT `; ⟹` echo never matches. Anchored
   to the echo's own indented line start (`^`), so prose that merely MENTIONS
   the shape mid-sentence (`the `;; =>` shape`) or a `:malli/schema [:=> …]`
   vector is not flagged."
  #"^[ \t]*;*[ \t]*(?:=>|⇒)[ \t]")

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::source
                  [:string {:description "Raw Clojure/ClojureScript source"}])

(schema/register! ::file-path
                  [:string {:min 1 :description "Path to a .clj/.cljs/.cljc file"}])

(schema/register! ::ns-name
                  [:string {:description "Namespace name of the source (e.g. seon.foo)"}])

(schema/register! ::fn-name
                  [:string {:description "The public defn's name"}])

(schema/register! ::rule
                  [:enum
                   :missing-docstring
                   :blank-first-line
                   :first-line-too-long
                   :no-terminal-punctuation
                   :result-echo-wrong-marker])

(schema/register! ::line
                  [:int {:min 1 :description "1-based line of the defn form"}])

(schema/register! ::message
                  [:string {:description "Human-readable finding message"}])

(schema/register! ::first-line
                  [:string {:description "The offending docstring first line"}])

(schema/register! ::finding
                  [:map
                   [::fn-name ::fn-name]
                   [::rule ::rule]
                   [::line ::line]
                   [::message ::message]
                   [::first-line {:optional true} ::first-line]
                   ;; `scan` annotates each finding with its source path.
                   [::file-path {:optional true} ::file-path]])

(schema/register! ::findings
                  [:vector ::finding])

(schema/register! ::clean?
                  :boolean)

(schema/register! ::skipped?
                  :boolean)

;; Request / response schemas
(schema/register! ::check-source-request
                  [:map
                   [::source ::source]
                   [::ns-name {:optional true} ::ns-name]])

(schema/register! ::check-response
                  [:map
                   [::clean? ::clean?]
                   [::skipped? ::skipped?]
                   [::findings ::findings]])

(schema/register! ::check-file-request
                  [:map
                   [::file-path ::file-path]])

(schema/register! ::max-length
                  [:int {:min 1 :description "Max output length in characters"}])

(schema/register! ::format-findings-request
                  [:map
                   [::findings ::findings]
                   [::max-length {:optional true} ::max-length]])

(schema/register! ::formatted
                  [:string {:description "Formatted findings text"}])

(schema/register! ::format-findings-response
                  [:map
                   [::formatted ::formatted]])

(schema/register! ::file-paths
                  [:vector ::file-path])

(schema/register! ::scan-request
                  [:map
                   [::file-paths ::file-paths]])

(schema/register! ::file-count
                  [:int {:min 0 :description "Files scanned (test/internal skipped)"}])

(schema/register! ::fn-count
                  [:int {:min 0 :description "Public defn heads inspected"}])

(schema/register! ::finding-count
                  [:int {:min 0 :description "Total findings across all files"}])

(schema/register! ::by-rule
                  [:map-of ::rule :int])

(schema/register! ::scan-response
                  [:map
                   [::file-count ::file-count]
                   [::fn-count ::fn-count]
                   [::finding-count ::finding-count]
                   [::by-rule ::by-rule]
                   [::findings ::findings]])

;;; ---------------------------------------------------------------------------
;;; Namespace naming skips (STRUCTURAL, mirrors
;;; seon.agent.ctx.namespaces/{hidden-ns-name?,test-ns-name?} — reimplemented
;;; here because that ns is .cljs and unreachable from this JVM linter.)
;;; ---------------------------------------------------------------------------

(defn- hidden-ns?
  "A `*.internal` namespace (or a child of one) — never rendered, never linted."
  [ns-name]
  (boolean (and ns-name
                (or (str/ends-with? ns-name ".internal")
                    (str/includes? ns-name ".internal.")))))

(defn- test-ns?
  "A `*-test` namespace — its deftests are not public verbs, skip linting."
  [ns-name]
  (boolean (and ns-name (str/ends-with? ns-name "-test"))))

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
  "Findings for a public defn's docstring BODY lines that echo a result on a
   STALE marker (`;=>`/`;; =>`/bare `=>`) instead of the reserved `; ⟹`.
   One finding per offending line; empty when the docstring is clean or nil."
  [{:keys [fn-name doc line]}]
  (when doc
    (into []
          (comp (filter #(re-find wrong-echo-re %))
                (map (fn [ln]
                       {::fn-name fn-name
                        ::rule :result-echo-wrong-marker
                        ::line line
                        ::first-line (str/trim ln)
                        ::message (str fn-name ": docstring result echo uses a"
                                       " stale marker — echo results as"
                                       " `; ⟹ value` (the reserved runtime"
                                       " result marker), never `=>`/`;;=>`")})))
          (str/split-lines doc))))

(defn- check-fn
  "All findings for one public defn: the line-1 finding (if any) plus every
   stale-marker result-echo in the body."
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
  {:malli/schema [:=> [:cat ::check-source-request] ::check-response]}
  [{::keys [source ns-name]}]
  (let [top-nodes (try (n/children (p/parse-string-all source))
                       (catch Exception _ nil))
        nm (or ns-name (some-> top-nodes extract-ns-name))]
    (if (or (nil? top-nodes) (hidden-ns? nm) (test-ns? nm))
      {::clean? true ::skipped? (boolean (or (hidden-ns? nm) (test-ns? nm)))
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
  {:malli/schema [:=> [:cat ::check-file-request] ::check-response]}
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
  {:malli/schema [:=> [:cat ::format-findings-request] ::format-findings-response]}
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
  {:malli/schema [:=> [:cat ::scan-request] ::scan-response]}
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
           (if (or (nil? top-nodes) (hidden-ns? nm) (test-ns? nm))
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
