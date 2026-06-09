(ns seon.search
  "Content search over allowed files — ripgrep wrapped as a substrate
   capability. THE EXEMPLAR npm-package wrapper: copy this file's shape
   when wrapping the next package.

   ## The wrapper doctrine (template for future package wrappers)

   1. ONE package → ONE thin CLJS ns. The npm dep (`@vscode/ripgrep`)
      is installed into the repo's package.json (`npm install --save`)
      so the whole cluster shares it — one agent adds a capability,
      every agent has it.
   2. Map-in / map-out. Registered `::request`/`::response` schemas,
      full `:malli/schema` on every public fn, ALL keys namespaced.
      No `:any` except at the npm boundary value itself.
   3. ERRORS ARE VALUES. Every public fn RESOLVES to an envelope —
      `{:seon.search/ok? true …}` on success,
      `{:seon.search/ok? false :seon.search/error <guiding message>
        :seon.search/raw-error <npm-side detail>}` on failure.
      Never throws, never rejects (same contract as seon.db/transact!).
      Each failure mode of the wrapped tool gets its OWN guiding
      message; the raw npm/binary output is preserved at
      `:seon.search/raw-error` for debugging.
   4. CAPABILITY-GATED. File-touching wrappers honor the `seon.fs`
      allowlist by DELEGATING to seon.fs's public surface (here:
      `seon.fs/stat` per search root — same normalization, same
      default-deny). Never reimplement the allowlist, never bypass it.
   5. Lazy `js/require` of the package inside a try — a missing binary
      is an envelope, not a load-time crash.

   ## What this ns wraps

   `@vscode/ripgrep` ships a platform rg binary (no system dependency);
   `(.-rgPath (js/require \"@vscode/ripgrep\"))` is the absolute path.
   Spawned via `child_process.execFile` (argv vector, NEVER a shell
   string — no injection surface) with `--json` structured output,
   a hard output-byte cap and a timeout.

   gitignore semantics (probed live 2026-06-09): ignore rules apply
   relative to the SEARCH ROOT — searching the repo root skips
   node_modules/out/tmp via the repo's .gitignore, but a directory you
   were explicitly granted (even one ignored by some parent repo) is
   fully searchable when passed as a root.

   ## The search→read recipe (the core move)

     ;; 1. grep for the term (pattern is a REGEX)
     (await (seon.search/grep {:seon.search/pattern \"validate-entity-values!\"
                               :seon.search/glob    \"*.cljs\"}))
     ;; => {:seon.search/ok? true
     ;;     :seon.search/matches
     ;;     [{:seon.search/path \"/Users/me/src/seon/src/seon/db.cljs\"
     ;;       :seon.search/line-number 803
     ;;       :seon.search/line-text \"(defn- validate-entity-values!\"} …]
     ;;     :seon.search/match-count 4 :seon.search/truncated? false}

     ;; 2. read the hit precisely
     (seon.fs/read-file {:seon.fs/path \"/Users/me/src/seon/src/seon/db.cljs\"})
     ;; result paths are absolute and allowlisted, so they feed
     ;; seon.fs/read-file directly — search → read, no guessing."
  (:require
    ["node:child_process" :as cp]
    [clojure.string :as str]
    [seon.fs :as fs]
    [seon.schema :as schema]))

;; ============================================================
;; Hard caps — internal constants, documented here so agents and the
;; next wrapper author can see the limits without reading the body.
;; ============================================================

(def ^:private timeout-ms
  "Kill the rg process after this long (SIGTERM via execFile :timeout)."
  10000)

(def ^:private max-output-bytes
  "execFile :maxBuffer — rg stdout beyond this is dropped; the partial
   output IS still parsed and returned with :seon.search/truncated? true."
  (* 8 1024 1024))

(def ^:private max-line-chars
  "Per-match line-text cap — keeps a minified-JS hit from flooding the
   agent's context."
  500)

(def ^:private default-max-results 100)

;; ============================================================
;; Schemas — every key registered, request/response named.
;; ============================================================

(schema/register! :seon.search/pattern [:string {:min 1}])
(schema/register! :seon.search/paths [:vector :string])
(schema/register! :seon.search/glob :string)
(schema/register! :seon.search/max-results :int)
(schema/register! :seon.search/case-insensitive? :boolean)

(schema/register! :seon.search/ok? :boolean)
(schema/register! :seon.search/error :string)
(schema/register! :seon.search/raw-error :string)

(schema/register! :seon.search/path :string)
(schema/register! :seon.search/line-number :int)
(schema/register! :seon.search/line-text :string)

(schema/register! :seon.search/match
  [:map
   [:seon.search/path        :seon.search/path]
   [:seon.search/line-number :seon.search/line-number]
   [:seon.search/line-text   :seon.search/line-text]])

(schema/register! :seon.search/matches [:vector :seon.search/match])
(schema/register! :seon.search/match-count :int)
(schema/register! :seon.search/truncated? :boolean)

(schema/register! :seon.search/grep-request
  [:map
   [:seon.search/pattern           :seon.search/pattern]
   [:seon.search/paths             {:optional true} :seon.search/paths]
   [:seon.search/glob              {:optional true} :seon.search/glob]
   [:seon.search/max-results       {:optional true} :seon.search/max-results]
   [:seon.search/case-insensitive? {:optional true} :seon.search/case-insensitive?]])

(schema/register! :seon.search/grep-response
  [:map
   [:seon.search/ok?         :seon.search/ok?]
   [:seon.search/matches     {:optional true} :seon.search/matches]
   [:seon.search/match-count {:optional true} :seon.search/match-count]
   [:seon.search/truncated?  {:optional true} :seon.search/truncated?]
   [:seon.search/error       {:optional true} :seon.search/error]
   [:seon.search/raw-error   {:optional true} :seon.search/raw-error]])

;; ============================================================
;; Envelope helpers
;; ============================================================

(defn- fail
  "ok?-false envelope. `raw` (optional) preserves the npm-side detail."
  ([msg] (fail msg nil))
  ([msg raw]
   (cond-> {:seon.search/ok?   false
            :seon.search/error msg}
     (and raw (not (str/blank? raw)))
     (assoc :seon.search/raw-error (str/trim raw)))))

(defn- ok-empty []
  {:seon.search/ok?         true
   :seon.search/matches     []
   :seon.search/match-count 0
   :seon.search/truncated?  false})

;; ============================================================
;; npm boundary — lazy require + execFile wrapper.
;; ============================================================

(defn- rg-path
  "Absolute path of the bundled rg binary, or nil if the package (or
   its platform binary) is missing. Lazy so a broken install is an
   envelope at call time, not a crash at ns load."
  []
  (try
    (let [p (.-rgPath (js/require "@vscode/ripgrep"))]
      (when (and (string? p) (.existsSync (js/require "node:fs") p))
        p))
    (catch :default _ nil)))

(defn- exec-rg
  "Run rg with `args` (vector of argv strings — never a shell string).
   ALWAYS resolves, to a JS object {err stdout stderr} (err nil on
   exit 0). Timeout + output cap enforced by execFile options."
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
;; Allowlist gate — delegate to seon.fs, never reimplement.
;; ============================================================

(defn- default-roots
  "The seon.fs allowed roots — the default search scope (\"search
   everything the agent may read\")."
  []
  (vec (:seon.fs/allowed-roots @fs/!config)))

(defn- gate-path
  "nil when `path` is readable per seon.fs; otherwise the ok?-false
   envelope. Delegates normalization + allowlist + existence to
   seon.fs/stat (the same gate read-file uses), so search and read
   always agree on what is reachable."
  [path]
  (let [{ok?   :seon.fs/ok?
         error :seon.fs/error} (fs/stat {:seon.fs/path path})]
    (when-not ok?
      (fail (str "search path " (pr-str path) " is not searchable — "
                 "path outside the allowed roots — ask your human to "
                 "grant access via (seon.fs/configure! "
                 "{:seon.fs/allowed-roots [...]}). seon.fs said: " error)))))

;; ============================================================
;; rg --json parsing
;; ============================================================

(defn- parse-match-line
  "One rg --json line → a :seon.search/match map, or nil for non-match
   events (begin/end/summary), unparsable fragments (the cut-off last
   line when the output cap hits), and non-UTF8 paths/lines (rg emits
   `bytes` instead of `text` for those — we skip them)."
  [line]
  (when-not (str/blank? line)
    (try
      (let [o (js/JSON.parse line)]
        (when (= "match" (.-type o))
          (let [d         (.-data o)
                path-text (some-> d .-path .-text)
                line-text (some-> d .-lines .-text)]
            (when (and path-text line-text)
              {:seon.search/path        path-text
               :seon.search/line-number (.-line_number d)
               :seon.search/line-text   (let [t (str/trim-newline line-text)]
                                          (if (> (count t) max-line-chars)
                                            (subs t 0 max-line-chars)
                                            t))}))))
      (catch :default _ nil))))

(defn- success-from
  "Parse rg --json stdout into the ok?-true envelope, clipping at
   `cap` matches. Parses cap+1 rows so :seon.search/truncated? can
   report that the clip actually dropped something."
  [stdout cap]
  (let [rows    (into []
                      (comp (keep parse-match-line) (take (inc cap)))
                      (str/split-lines stdout))
        clipped (> (count rows) cap)
        ms      (if clipped (subvec rows 0 cap) rows)]
    {:seon.search/ok?         true
     :seon.search/matches     ms
     :seon.search/match-count (count ms)
     :seon.search/truncated?  clipped}))

;; ============================================================
;; Public API
;; ============================================================

(defn ^:async grep
  "Search file CONTENTS under the seon.fs allowed roots. `^:async` —
   returns a Promise that ALWAYS resolves to a :seon.search/grep-response
   envelope (never rejects; errors are values).

   Request keys:
     :seon.search/pattern           REQUIRED — a REGEX (rg syntax), not a
                                    literal: escape ( ) [ ] { } . with \\\\
     :seon.search/paths             optional — files/dirs to search;
                                    DEFAULT = the seon.fs allowed roots
     :seon.search/glob              optional — filename filter, e.g. \"*.cljs\"
     :seon.search/max-results       optional — clip (default 100);
                                    :seon.search/truncated? true when hit
     :seon.search/case-insensitive? optional

   No matches is SUCCESS: {:seon.search/ok? true :seon.search/matches []}.
   rg exit 1 (nothing found) is not an error.

   Worked example — search → read precisely:

     (await (seon.search/grep {:seon.search/pattern \"transact!\"
                               :seon.search/glob    \"*.md\"}))
     ;; pick a hit, then:
     (seon.fs/read-file {:seon.fs/path <:seon.search/path of the hit>})
     ;; jump to its :seon.search/line-number in the content.

   NOTE: ^:async means Malli validates the request; the response schema
   documents the RESOLVED value (the raw return is a js/Promise — same
   caveat as seon.db/transact!)."
  {:malli/schema [:=> [:cat :seon.search/grep-request]
                  :seon.search/grep-response]}
  [{:seon.search/keys [pattern paths glob max-results case-insensitive?]
    :or {max-results default-max-results}}]
  (try
    (let [roots (if (seq paths) (vec paths) (default-roots))
          bin   (rg-path)]
      (cond
        (or (nil? pattern) (str/blank? pattern))
        (fail (str ":seon.search/pattern is required and must be non-blank "
                   "— it is a regex over file contents."))

        (empty? roots)
        (fail (str "nothing is searchable: no :seon.search/paths given and "
                   "seon.fs has no allowed-roots configured (default-deny) "
                   "— ask your human to grant access via "
                   "(seon.fs/configure! {:seon.fs/allowed-roots [...]})."))

        (nil? bin)
        (fail (str "ripgrep binary not found — the @vscode/ripgrep npm "
                   "package is missing or its platform binary did not "
                   "install. Run `npm install` in the repo root."))

        :else
        (if-let [denied (some gate-path roots)]
          denied
          (let [args (-> ["--json" "--no-config"]
                         (cond-> case-insensitive? (conj "-i")
                                 glob              (conj "--glob" glob))
                         (conj "--regexp" pattern "--")
                         (into roots))
                ^js r  (await (exec-rg bin args))
                ^js err (.-err r)
                stdout (.-stdout r)
                stderr (.-stderr r)]
            (cond
              ;; Timeout — execFile killed the child.
              (and err (.-killed err))
              (fail (str "search timed out after " timeout-ms "ms — "
                         "narrow :seon.search/paths, add a "
                         ":seon.search/glob, or use a more specific "
                         "pattern.")
                    stderr)

              ;; Binary vanished between rg-path check and spawn.
              (and err (= "ENOENT" (.-code err)))
              (fail (str "ripgrep binary failed to spawn (" bin ") — "
                         "run `npm install` in the repo root.")
                    (.-message err))

              ;; Output cap — partial stdout is still parseable.
              (and err (= "ERR_CHILD_PROCESS_STDIO_MAXBUFFER" (.-code err)))
              (assoc (success-from stdout max-results)
                     :seon.search/truncated? true)

              ;; rg exit 1 = searched fine, found nothing. NOT an error.
              (and err (= 1 (.-code err)))
              (ok-empty)

              ;; rg exit 2 (or anything else) — bad regex is the common case.
              err
              (fail (str "ripgrep rejected the search — most often an "
                         "invalid regex in :seon.search/pattern (it is a "
                         "REGEX, not a literal: escape ( ) [ ] { } . with "
                         "\\\\). Detail: "
                         (or (first (str/split-lines (str stderr)))
                             (.-message err)))
                    (if (str/blank? (str stderr)) (.-message err) stderr))

              :else
              (success-from stdout max-results))))))
    (catch :default e
      (fail (str "unexpected error in seon.search/grep: "
                 (or (some-> e .-message) (str e)))))))
