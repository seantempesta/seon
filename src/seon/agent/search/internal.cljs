(ns seon.agent.search.internal
  "Plumbing behind `seon.agent.search/grep` — the hard caps, the envelope
   helpers, the npm boundary (lazy `js/require` + `execFile`), the
   seon.agent.fs allowlist gate, and the rg `--json` parser.

   This namespace is INTERNAL: it is never rendered into agent context (the
   `*.internal` ns name IS the filter). Agents call the public face in
   `seon.agent.search`; nothing here is part of the taught surface.
   Everything is a plain `defn` — the ns boundary is the privacy boundary,
   so `seon.agent.search` (and tests) can call across without `#'`
   gymnastics.

   All map keys stay in the `:seon.agent.search/*` namespace (via
   `:as-alias`): the keyword namespace tracks the OWNING DATA namespace
   (`seon.agent.search`), not the file the code lives in."
  (:require
    ["node:child_process" :as cp]
    [clojure.string :as str]
    [seon.agent.fs :as fs]
    [seon.agent.search :as-alias search]
    [seon.ai.tokens :as tokens]
    [seon.db :as db]))

;; ============================================================
;; Hard caps.
;; ============================================================

(def timeout-ms
  "Kill the rg process after this long (SIGTERM via execFile :timeout)."
  10000)

(def max-output-bytes
  "execFile :maxBuffer — rg stdout beyond this is dropped; the partial
   output IS still parsed and returned with ::search/truncated? true."
  (* 8 1024 1024))

(def max-preview-tokens
  "Per-match line-text cap, in TOKENS (seon.ai.tokens/estimate). A long
   matched line (minified JS, a giant data literal) is trimmed to this and
   marked with an ellipsis — the preview only has to let the agent confirm
   the hit is relevant, not show the whole line."
  32)

(def default-max-results
  "DEFAULT cap on FILE ROWS returned by a grouped grep (`:by-file`). Low on
   purpose: a broad pattern hits many files, and the 12 densest count-ranked
   file rows are enough to orient + drill (a row is ~50 tok — mostly the
   namespaced keys + abs path). The honest TOTAL (`:match-count` /
   `:file-count`) is always reported; a `:hint` fires when rows are clipped."
  12)

;; ============================================================
;; Envelope helpers.
;; ============================================================

(defn fail
  "ok?-false envelope. `raw` (optional) preserves the npm-side detail."
  ([msg] (fail msg nil))
  ([msg raw]
   (cond-> {::search/ok?   false
            ::search/error msg}
     (and raw (not (str/blank? raw)))
     (assoc ::search/raw-error (str/trim raw)))))

(defn ok-empty []
  {::search/ok?         true
   ::search/match-count 0
   ::search/file-count  0
   ::search/returned    0
   ::search/by-file     []
   ::search/truncated?  false})

;; ============================================================
;; npm boundary — lazy require + execFile wrapper.
;; ============================================================

(defn rg-path
  "Absolute path of the bundled rg binary, or nil if the package (or its
   platform binary) is missing. Lazy so a broken install is an envelope at
   call time, not a crash at ns load."
  []
  (try
    (let [p (.-rgPath (js/require "@vscode/ripgrep"))]
      (when (and (string? p) (.existsSync (js/require "node:fs") p))
        p))
    (catch :default _ nil)))

(defn exec-rg
  "Run rg with `args` (vector of argv strings — never a shell string).
   ALWAYS resolves, to a JS object {err stdout stderr} (err nil on exit 0).
   Timeout + output cap enforced by execFile options."
  [bin args]
  (js/Promise.
    (fn [resolve _]
      (.execFile cp bin (into-array args)
                 #js {:timeout     timeout-ms
                      :maxBuffer   max-output-bytes
                      :windowsHide true}
                 (fn [err stdout stderr]
                   (resolve #js {:err err :stdout stdout :stderr stderr}))))))

;; ============================================================
;; Allowlist gate — delegate to seon.agent.fs, never reimplement.
;; ============================================================

(defn default-roots
  "The seon.agent.fs allowed roots — the default search scope (\"search
   everything the agent may read\")."
  []
  (vec (:seon.agent.fs/allowed-roots (fs/grants))))

(defn gate-path
  "nil when `path` is readable per seon.agent.fs; otherwise the ok?-false
   envelope. Delegates normalization + allowlist + existence to
   seon.agent.fs/stat (the same gate read-file uses), so search and read
   always agree on what is reachable."
  [path]
  (let [{ok?   :seon.agent.fs/ok?
         error :seon.agent.fs/error} (fs/stat {:seon.agent.fs/path path})]
    (when-not ok?
      (fail (str "search path " (pr-str path) " is not searchable — "
                 "path outside the allowed roots — ask your human to "
                 "grant access via (seon.agent.fs/configure! "
                 "{:seon.agent.fs/allowed-roots [...]}). seon.agent.fs said: " error)))))

;; ============================================================
;; rg --json parsing.
;; ============================================================

(defn preview-line
  "Trim a matched line to `max-preview-tokens` (TOKENS, not chars), marking
   a cut with an ellipsis. Keeps one long minified-JS hit from blowing the
   eval row."
  [s]
  (let [t (str/trim (str/trim-newline s))]
    (if (> (tokens/estimate t) max-preview-tokens)
      (str (subs t 0 (tokens/estimate-chars max-preview-tokens)) "…")
      t)))

(defn parse-event-line
  "One rg --json line → a {::path ::line-number ::line-text (::context?)}
   map, or nil for structural events (begin/end/summary), unparsable
   fragments (the cut-off last line when the output cap hits), and non-UTF8
   paths/lines (rg emits `bytes` instead of `text` for those — we skip
   them). BOTH `match` and `context` events are parsed (context events only
   appear under `-C`/`::context-lines`); a context line carries
   `::context? true` so callers can count real matches honestly while still
   showing surrounding lines. The line-text is already preview-capped."
  [line]
  (when-not (str/blank? line)
    (try
      (let [o    (js/JSON.parse line)
            type (.-type o)]
        (when (or (= "match" type) (= "context" type))
          (let [d         (.-data o)
                path-text (some-> d .-path .-text)
                line-text (some-> d .-lines .-text)]
            (when (and path-text line-text)
              (cond-> {::search/path        path-text
                       ::search/line-number (.-line_number d)
                       ::search/line-text   (preview-line line-text)}
                (= "context" type) (assoc ::search/context? true))))))
      (catch :default _ nil))))

;; ============================================================
;; Generic grouped envelope — the SHARED concise formatter.
;;
;; Both grep (file content) and grep-graph (program graph) produce the
;; SAME shape: a flat list of hits, GROUPED by a container (file path /
;; namespace), each group a row with a hit-count + a sample preview,
;; capped + ranked by density, with honest totals + a narrowing hint
;; when clipped. This fn IS that shape; the two functions only differ in the
;; container key, the per-group row projection, the response field names,
;; and the hint text — all passed in. Do NOT fork a second formatter.
;; ============================================================

(defn grouped-envelope
  "Build the ok?-true concise/full envelope from a flat `matches` vector.

   - `group-key` — extracts a hit's CONTAINER (e.g. ::search/path for
     files, ::search/ns for the graph). Hits sharing a container roll up
     to one row.
   - `row-fn`    — (container, hits-in-that-container) → a group ROW map.
     The row MUST carry ::search/count (used to rank densest-first); the
     rest of the row shape is the caller's (a sample line / member).
   - `fields`    — {:rows <vector key, e.g. ::search/by-file>
                    :group-count <int key, e.g. ::search/file-count>
                    :hint <(total group-count shown) → narrowing string>}.

   DEFAULT (full? false): rank rows by ::count desc, keep the top `cap`,
   report honest ::match-count (all hits) + the group-count + a ::hint
   when rows were clipped.

   full? true: skip grouping, return the flat ::matches list capped at
   `cap` — the drill escape hatch (every hit, not the roll-up)."
  [matches group-key row-fn {:keys [rows group-count hint]} cap full?]
  (let [total (count matches)]
    (if full?
      (let [shown (subvec (vec matches) 0 (min cap total))]
        {::search/ok?         true
         ::search/match-count total
         ::search/returned    (count shown)
         ::search/matches     shown
         ::search/truncated?  (> total (count shown))})
      (let [grouped (->> matches
                         (group-by group-key)
                         (mapv (fn [[k ms]] (row-fn k ms)))
                         (sort-by (comp - ::search/count))
                         vec)
            gcount  (count grouped)
            shown   (subvec grouped 0 (min cap gcount))
            clipped (> gcount (count shown))]
        (cond-> {::search/ok?      true
                 ::search/match-count total
                 group-count       gcount
                 ::search/returned (count shown)
                 rows              shown
                 ::search/truncated? clipped}
          clipped (assoc ::search/hint (hint total gcount (count shown))))))))

;; ============================================================
;; File target — the rg projection of the generic envelope.
;; ============================================================

(defn- file-row
  "One file group → {::path ::count ::line-number ::line-text}, sampling the
   FIRST hit in the file for the line-number + preview line."
  [path ms]
  (let [{ln ::search/line-number lt ::search/line-text} (first ms)]
    {::search/path        path
     ::search/count       (count ms)
     ::search/line-number ln
     ::search/line-text   lt}))

(defn- file-hint [total file-count shown]
  (str total " matches in " file-count " files — showing the " shown
       " densest. Narrow :seon.agent.search/pattern, add a "
       ":seon.agent.search/glob, or pass :seon.agent.search/paths to drill; "
       ":seon.agent.search/full? true returns every line."))

;; ── ::context-lines — rg -C N surrounds each hit with N lines. A context
;; line rides the same flat shape (::context? true) so real matches still
;; count honestly. Two projections: the by-file row's sample line-text
;; widens to a numbered block around the first hit; :full? returns the flat
;; match+context stream, line-numbered.

(defn- numbered
  "Render a seq of parsed lines as `N<tab>text`, one per line, in
   line-number order — the shared read-view format, so a widened preview
   copies back cleanly (strip the N<tab> prefix)."
  [lines]
  (->> (sort-by ::search/line-number lines)
       (map (fn [{n ::search/line-number t ::search/line-text}]
              (str n "\t" t)))
       (str/join "\n")))

(defn- widened-file-row
  "A by-file row whose ::line-text is a numbered window (the first hit ±
   `context-lines`, drawn from that file's match+context lines in `all`).
   ::count stays the honest per-file MATCH count."
  [all context-lines path ms]
  (let [{ln ::search/line-number} (first ms)
        lo    (- ln context-lines)
        hi    (+ ln context-lines)
        block (filter (fn [m] (and (= path (::search/path m))
                                   (<= lo (::search/line-number m) hi)))
                      all)]
    {::search/path        path
     ::search/count       (count ms)
     ::search/line-number ln
     ::search/line-text   (numbered block)}))

(defn- flat-context-envelope
  "The :full? projection when ::context-lines is set: the flat match+context
   stream (each line numbered via ::line-number), capped at `cap` LINES.
   ::match-count counts only real matches (context is not a match); nothing
   is discarded silently — ::truncated? flags a clipped stream."
  [all matches cap]
  (let [shown (subvec all 0 (min cap (count all)))]
    {::search/ok?         true
     ::search/match-count (count matches)
     ::search/returned    (count shown)
     ::search/matches     shown
     ::search/truncated?  (> (count all) (count shown))}))

;; ============================================================
;; Lane-correctness — the active CLJS pod lane is canonical; a paused
;; `.clj` lane-sibling (foo.clj alongside foo.cljs) is NOISE. File grep
;; runs rg over the whole repo (.clj + .cljs), so a naive pattern can
;; surface dead JVM source and HIDE the active pod source (the
;; `(defn ^:async transact!` in db.cljs fails a `"defn transact!"` regex
;; while the paused `(defn transact!` in db.clj matches). We drop a `.clj`
;; match when its co-located `<base>.cljs` exists — UNLESS the request
;; explicitly targeted the `.clj`. Standalone `.clj` / `.cljc` /
;; reference-code paths are never touched (no sibling → nothing suppressed).
;; ============================================================

(defn cljs-sibling-exists?
  "True when `clj-path` (a path ending `.clj`) has a co-located
   `<base>.cljs` on disk. The rg search root IS the real filesystem, so a
   targeted existsSync is the source of truth (the `.cljs` need not be in
   the match set — that's exactly the trap: its `^:async` defn didn't match
   the regex)."
  [clj-path]
  (try
    ;; a `.clj` path's `.cljs` sibling is the same path + "s".
    (.existsSync (js/require "node:fs") (str clj-path "s"))
    (catch :default _ false)))

(defn explicit-clj?
  "True when the REQUEST explicitly targeted this `.clj` — so auto-suppression
   must NOT apply. Explicit means the caller named `.clj` directly:
   a `:seon.agent.search/glob` ending exactly `.clj` (e.g. `*.clj`,
   restricting to clj-only — NOT `db.clj*`, which also matches `.cljs`), or a
   `:seon.agent.search/paths` entry that names a `.clj` file this match
   sits under."
  [clj-path paths glob]
  (or (boolean (and glob (str/ends-with? glob ".clj")))
      (boolean (some (fn [p] (and (str/ends-with? p ".clj")
                                  (str/ends-with? clj-path p)))
                     paths))))

(defn suppress-paused-clj-siblings
  "Remove file matches for a paused `.clj` lane-sibling whose active
   `<base>.cljs` exists on disk, unless the request explicitly targeted the
   `.clj`. Co-located pairs only; standalone `.clj`/`.cljc`/reference-code
   untouched. `paths`/`glob` are the RAW request values (nil when omitted).
   existsSync is cached per call (a dense file has many match lines)."
  [matches paths glob]
  (let [cache (atom {})
        sibling? (fn [p]
                   (if (contains? @cache p)
                     (@cache p)
                     (let [v (cljs-sibling-exists? p)]
                       (swap! cache assoc p v)
                       v)))]
    (into []
          (remove (fn [m]
                    (let [p (::search/path m)]
                      (and (str/ends-with? p ".clj")
                           (not (explicit-clj? p paths glob))
                           (sibling? p)))))
          matches)))

(defn success-from
  "Parse rg --json stdout into the ok?-true envelope via [[grouped-envelope]]
   (grouped by FILE). Paused `.clj` lane-siblings are suppressed before
   grouping (the active CLJS pod lane is canonical); `paths`/`glob` are the
   raw request values so an explicitly-targeted `.clj` still reaches the
   caller. `context-lines` (0 = none) widens each by-file sample line into a
   numbered window and, under `full?`, returns the flat match+context
   stream; real matches are always counted honestly (context lines are not
   matches)."
  [stdout paths glob cap full? context-lines]
  (let [ctx? (pos? (or context-lines 0))
        all  (-> (into [] (keep parse-event-line) (str/split-lines stdout))
                 (suppress-paused-clj-siblings paths glob))
        matches (into [] (remove ::search/context?) all)]
    (cond
      (and full? ctx?)  (flat-context-envelope all matches cap)
      :else
      (grouped-envelope matches ::search/path
                        (if ctx?
                          (fn [path ms] (widened-file-row all context-lines path ms))
                          file-row)
                        {:rows ::search/by-file :group-count ::search/file-count
                         :hint file-hint}
                        cap full?))))

;; ============================================================
;; Graph target — text search over the PROGRAM GRAPH (the literal
;; counterpart to SEON_EMBED semantic recall). Same envelope shape as the
;; file grep, but the corpus is :seon.fn / :seon.schema / :seon.ns rows in
;; seon.db — code that may live in NO source file (agent-authored + seeded
;; code-as-data), which rg can't reach. Grouped BY NAMESPACE.
;; ============================================================

(def default-graph-targets
  "Graph targets searched when :seon.agent.search/targets is omitted — the
   CODE corpus (fns, schemas, namespaces). :seon.eval (the eval LOG) is
   opt-in: high-volume and not the core target."
  [:seon.fn :seon.schema :seon.ns])

(defn compile-pattern
  "Compile `pattern` to a STATELESS (no global flag — `.test` is reused per
   line) js/RegExp under ::search/re, or an ok?-false envelope when the
   regex is invalid. `ci?` adds the case-insensitive flag."
  [pattern ci?]
  (try
    {::search/re (js/RegExp. pattern (if ci? "i" ""))}
    (catch :default e
      (fail (str "invalid :seon.agent.search/pattern — it is a REGEX (JS "
                 "syntax): escape ( ) [ ] { } . with \\\\. Detail: "
                 (or (some-> e .-message) (str e)))))))

(defn first-matching-line
  "First line of `src` matching `re`, preview-capped; falls back to the first
   non-blank line, then \"\". The graph analog of rg's matched line — keeps
   the per-member sample to a single trimmed line, not a whole defn."
  [re src]
  (let [lines (str/split-lines (str src))]
    (preview-line
      (or (some (fn [l] (when (.test re l) l)) lines)
          (first (remove str/blank? lines))
          ""))))

(defn- hit
  "One matching graph entity → a flat hit map (the graph analog of a file
   match line): which target matched, the owning namespace (container), the
   member identifier, and a preview of the matching source line."
  [target ns-str member src re]
  {::search/target    target
   ::search/ns        ns-str
   ::search/member    member
   ::search/line-text (first-matching-line re src)})

(defn- fn-hits [re]
  (->> (db/query '[:find ?sym ?src ?doc
                   :where [?e :seon.fn/sym ?sym] [?e :seon.fn/source ?src]
                          [(get-else $ ?e :seon.fn/doc "") ?doc]])
       (keep (fn [[sym src doc]]
               (when (.test re (str sym "\n" doc "\n" src))
                 (hit :seon.fn (or (namespace (symbol sym)) sym) sym src re))))))

(defn- schema-hits [re]
  (->> (db/query '[:find ?k ?src
                   :where [?e :seon.schema/key ?k] [?e :seon.schema/source ?src]])
       (keep (fn [[k src]]
               (when (.test re (str k "\n" src))
                 (hit :seon.schema (or (namespace k) (name k)) (str k) src re))))))

(defn- ns-hits [re]
  (->> (db/query '[:find ?nm ?src
                   :where [?e :seon.ns/name ?nm] [?e :seon.ns/source ?src]])
       (keep (fn [[nm src]]
               (when (.test re (str (name nm) "\n" src))
                 (hit :seon.ns (name nm) (str nm) src re))))))

(defn- eval-hits [re]
  (->> (db/query '[:find ?id ?src ?ns
                   :where [?e :seon.eval/id ?id] [?e :seon.eval/source ?src]
                          [(get-else $ ?e :seon.eval/ns :seon.eval/unknown) ?ns]])
       (keep (fn [[id src ns]]
               (when (.test re (str src))
                 (hit :seon.eval (name ns) (str id) src re))))))

(defn graph-hits
  "Flat hits across the requested graph `targets`, in TARGET ORDER (fns
   first) so a namespace group samples a concrete fn for its preview. `re`
   is the compiled stateless pattern."
  [targets re]
  (vec (mapcat (fn [t] (case t
                         :seon.fn     (fn-hits re)
                         :seon.schema (schema-hits re)
                         :seon.ns     (ns-hits re)
                         :seon.eval   (eval-hits re)
                         nil))
               targets)))

(defn- ns-row
  "One namespace group → {::ns ::count ::member ::target ::line-text}, sampling
   the FIRST hit (a fn, by target order) for member/target/preview."
  [ns-str ms]
  (let [{m ::search/member t ::search/target lt ::search/line-text} (first ms)]
    {::search/ns        ns-str
     ::search/count     (count ms)
     ::search/member    m
     ::search/target    t
     ::search/line-text lt}))

(defn- graph-hint [total ns-count shown]
  (str total " graph matches in " ns-count " namespaces — showing the " shown
       " densest. Narrow :seon.agent.search/pattern, restrict "
       ":seon.agent.search/targets, or pass :seon.agent.search/full? true for "
       "every member."))

(defn graph-search
  "Backend for seon.agent.search/grep-graph: text-search the program graph,
   grouped BY NAMESPACE, via the SAME [[grouped-envelope]] as the file grep.
   Errors as values (invalid regex / unexpected → ok?-false envelope)."
  [pattern targets cap full? ci?]
  (try
    (let [compiled (compile-pattern pattern ci?)]
      (if-let [re (::search/re compiled)]
        (grouped-envelope (graph-hits targets re) ::search/ns ns-row
                          {:rows ::search/by-ns :group-count ::search/ns-count
                           :hint graph-hint}
                          cap full?)
        compiled))
    (catch :default e
      (fail (str "unexpected error in seon.agent.search/grep-graph: "
                 (or (some-> e .-message) (str e)))))))
