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
    [seon.agent.search :as-alias search]))

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

(def max-line-chars
  "Per-match line-text cap — keeps a minified-JS hit from flooding the
   agent's context."
  500)

(def default-max-results 100)

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
   ::search/matches     []
   ::search/match-count 0
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

(defn parse-match-line
  "One rg --json line → a ::search/match map, or nil for non-match events
   (begin/end/summary), unparsable fragments (the cut-off last line when the
   output cap hits), and non-UTF8 paths/lines (rg emits `bytes` instead of
   `text` for those — we skip them)."
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
               ::search/line-text   (let [t (str/trim-newline line-text)]
                                       (if (> (count t) max-line-chars)
                                         (subs t 0 max-line-chars)
                                         t))}))))
      (catch :default _ nil))))

(defn success-from
  "Parse rg --json stdout into the ok?-true envelope, clipping at `cap`
   matches. Parses cap+1 rows so ::search/truncated? can report that the
   clip actually dropped something."
  [stdout cap]
  (let [rows    (into []
                      (comp (keep parse-match-line) (take (inc cap)))
                      (str/split-lines stdout))
        clipped (> (count rows) cap)
        ms      (if clipped (subvec rows 0 cap) rows)]
    {::search/ok?         true
     ::search/matches     ms
     ::search/match-count (count ms)
     ::search/truncated?  clipped}))
