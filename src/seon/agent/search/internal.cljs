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
    [seon.ai.tokens :as tokens]))

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

(defn parse-match-line
  "One rg --json line → a {::path ::line-number ::line-text} map, or nil for
   non-match events (begin/end/summary), unparsable fragments (the cut-off
   last line when the output cap hits), and non-UTF8 paths/lines (rg emits
   `bytes` instead of `text` for those — we skip them). The line-text is
   already preview-capped."
  [line]
  (when-not (str/blank? line)
    (try
      (let [o (js/JSON.parse line)]
        (when (= "match" (.-type o))
          (let [d         (.-data o)
                path-text (some-> d .-path .-text)
                line-text (some-> d .-lines .-text)]
            (when (and path-text line-text)
              {::search/path        path-text
               ::search/line-number (.-line_number d)
               ::search/line-text   (preview-line line-text)}))))
      (catch :default _ nil))))

(defn- group-by-file
  "matches → vector of file rows, each {::path ::count ::line-number
   ::line-text}, where line-number/line-text sample the FIRST hit in the
   file. Ranked by descending match count (the densest files first), so the
   `cap` that follows keeps the most relevant rows."
  [matches]
  (->> matches
       (group-by ::search/path)
       (mapv (fn [[path ms]]
               (let [{ln ::search/line-number lt ::search/line-text} (first ms)]
                 {::search/path        path
                  ::search/count       (count ms)
                  ::search/line-number ln
                  ::search/line-text   lt})))
       (sort-by (comp - ::search/count))
       vec))

(defn success-from
  "Parse rg --json stdout into the ok?-true envelope.

   DEFAULT (concise) — group by file, rank by hit count, return the top
   `cap` file rows under ::by-file with the honest ::match-count /
   ::file-count and, when rows were clipped, a narrowing ::hint.

   `full?` true — return the flat ::matches list (capped at `cap` matches)
   for the rare case the agent wants every line, not the per-file roll-up."
  [stdout cap full?]
  (let [matches (into [] (keep parse-match-line) (str/split-lines stdout))
        total   (count matches)]
    (if full?
      (let [shown (subvec matches 0 (min cap total))]
        {::search/ok?         true
         ::search/match-count total
         ::search/returned    (count shown)
         ::search/matches     shown
         ::search/truncated?  (> total (count shown))})
      (let [rows       (group-by-file matches)
            file-count (count rows)
            shown      (subvec rows 0 (min cap file-count))
            clipped    (> file-count (count shown))]
        (cond-> {::search/ok?         true
                 ::search/match-count total
                 ::search/file-count  file-count
                 ::search/returned    (count shown)
                 ::search/by-file     shown
                 ::search/truncated?  clipped}
          clipped
          (assoc ::search/hint
                 (str total " matches in " file-count " files — showing the "
                      (count shown) " densest. Narrow :seon.agent.search/pattern, "
                      "add a :seon.agent.search/glob, or pass :seon.agent.search/paths "
                      "to drill; :seon.agent.search/full? true returns every line.")))))))
