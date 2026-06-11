(ns seon.agent.fs
  "Local-filesystem capability — the agent's eyes + hands on the
   user's machine, gated by an explicit allowlist.

   Two backends:

     :node — `node:fs` directly, sandboxed by [[!config]].
     :wasi — stubbed until wasi:filesystem/preopens lands.

   ## Security model

   **Default-deny.** With no configuration, every op returns
   `:seon.agent.fs/ok? false`. Callers must explicitly grant access via
   [[configure!]] (or the legacy `SEON_FS_ROOT` env var) before
   `read-file` / `list-dir` / `walk-dir` / `stat` will resolve a
   path. Writes additionally require `:seon.agent.fs/read-only? false`.

   This is a SOFT boundary against LLM-emitted accidents, not a
   security boundary against malicious code. The agent's `cljs.js`
   eval can `(js/require \"node:fs\")` directly and bypass us
   entirely — closing that gap requires process isolation (Phase 2
   worker-thread eval) or WASM containment (Phase 3 wasmtime+WIT).
   We harden what we can in the meantime; LLM hallucinations
   calling `seon.agent.fs` with `..`-traversal paths or out-of-scope
   absolute paths land on `denied` rather than `unlinkSync`.

   ## House-rule API

   Map-in / map-out per CLAUDE.md data rules. Every fn:
     • takes one map argument with fully-namespaced keys
     • returns one map with `:seon.agent.fs/ok?` discriminator
     • never throws — errors land as `:seon.agent.fs/error` strings

   ## Configuration

     ;; In a consumer overlay's boot fn — explicit allowlist.
     (seon.agent.fs/configure!
       {:seon.agent.fs/allowed-roots [\"/Users/me/work-folder\"]
        :seon.agent.fs/read-only?    false})

     ;; Or via env vars at process start (back-compat shim — the
     ;; ns-load reads SEON_FS_ROOT / SEON_FS_READ_ONLY into the
     ;; same atom).

     ;; SEON_FS_LOCK=1 (host env) makes the env-bootstrapped grant
     ;; IMMUTABLE: [[configure!]] becomes a legible no-op error and
     ;; [[grants]] reports :seon.agent.fs/locked? true. Observed live
     ;; 2026-06-11: an agent configure!'d itself to a NARROWER grant
     ;; and locked itself out for the session — hosts that bootstrap
     ;; the grant via env should set the lock too.

   ## Worked examples

     (seon.agent.fs/grants)    ;; the CONFIGURED roots + read-only flag —
                               ;; call this, never infer the grant from a listing
     (seon.agent.fs/read-file  {:seon.agent.fs/path \"/Users/me/work-folder/notes.md\"})
     (seon.agent.fs/write-file {:seon.agent.fs/path \"/Users/me/work-folder/out.txt\"
                          :seon.agent.fs/content \"hello\"})
     (seon.agent.fs/list-dir   {:seon.agent.fs/path \"/Users/me/work-folder\"})
     (seon.agent.fs/stat       {:seon.agent.fs/path \"/Users/me/work-folder/notes.md\"})"
  (:require
    ["node:fs" :as fs]
    ["node:path" :as np]
    [clojure.string :as str]
    [seon.platform :as platform]
    [seon.schema :as schema]))

;; ============================================================
;; Why sync, not async (Promise-returning)
;;
;; The agent evals forms via cljs.js bootstrap; the only auto-await
;; (in seon.eval/maybe-await-value) fires on the FORM's outer-most
;; value — not inside let-bindings. So when the agent writes the natural
;;
;;   (let [r (walk-dir {...})] (if (:seon.agent.fs/ok? r) ...))
;;
;; `r` would bind to a Promise (not the resolved map), and the
;; (:seon.agent.fs/ok? r) check returns nil → wrong branch. The fix is
;; either teaching agents about .then chains (painful) or making fs
;; calls synchronous so the agent's let-binding gets the real value.
;;
;; For local-file ops the perf cost of sync is irrelevant (V0.5 pod
;; isn't I/O bound; the event loop blocks for ms, not seconds). WASI
;; (Lane B target) also supports synchronous fd reads, so this stays
;; valid post-convergence.
;; ============================================================

;; ============================================================
;; Schemas
;; ============================================================

(schema/register! :seon.agent.fs/path     :string)
(schema/register! :seon.agent.fs/encoding :string)
(schema/register! :seon.agent.fs/content  :string)
(schema/register! :seon.agent.fs/ok?      :boolean)
(schema/register! :seon.agent.fs/error    :string)
(schema/register! :seon.agent.fs/entries  [:vector :string])
(schema/register! :seon.agent.fs/size     :int)
(schema/register! :seon.agent.fs/dir?     :boolean)
(schema/register! :seon.agent.fs/file?    :boolean)
(schema/register! :seon.agent.fs/mtime    :any) ; js/Date — :inst varies across CLJS reader registries

;; The configured grant — what [[grants]] reads back. `allowed-roots`
;; empty = default-deny (nothing readable until [[configure!]] /
;; SEON_FS_ROOT grants roots).
(schema/register! :seon.agent.fs/allowed-roots [:vector :string])
(schema/register! :seon.agent.fs/read-only?    :boolean)
;; True when the host set SEON_FS_LOCK — the grant is immutable for
;; this process; configure! is a no-op error. Read live from the env
;; (the host owns it; the agent can't flip it from inside).
(schema/register! :seon.agent.fs/locked?       :boolean)

(schema/register! :seon.agent.fs/grants-response
  [:map
   [:seon.agent.fs/allowed-roots :seon.agent.fs/allowed-roots]
   [:seon.agent.fs/read-only?    :seon.agent.fs/read-only?]
   [:seon.agent.fs/locked?       :seon.agent.fs/locked?]])

(schema/register! :seon.agent.fs/configure-response
  [:map
   [:seon.agent.fs/ok?           :boolean]
   [:seon.agent.fs/allowed-roots {:optional true} :seon.agent.fs/allowed-roots]
   [:seon.agent.fs/read-only?    {:optional true} :seon.agent.fs/read-only?]
   [:seon.agent.fs/locked?       {:optional true} :seon.agent.fs/locked?]
   [:seon.agent.fs/error         {:optional true} :string]])

;; read-file paging — line-based section reads (1-based from-line).
(schema/register! :seon.agent.fs/from-line      :int)
(schema/register! :seon.agent.fs/max-lines      :int)
(schema/register! :seon.agent.fs/lines-returned :int)
(schema/register! :seon.agent.fs/total-lines    :int)

(schema/register! :seon.agent.fs/read-request
  [:map
   [:seon.agent.fs/path      :string]
   [:seon.agent.fs/encoding  {:optional true} :string]
   [:seon.agent.fs/from-line {:optional true} :int]
   [:seon.agent.fs/max-lines {:optional true} :int]])

(schema/register! :seon.agent.fs/read-response
  [:map
   [:seon.agent.fs/ok?            :boolean]
   [:seon.agent.fs/path           :string]
   [:seon.agent.fs/content        {:optional true} :string]
   [:seon.agent.fs/from-line      {:optional true} :int]
   [:seon.agent.fs/lines-returned {:optional true} :int]
   [:seon.agent.fs/total-lines    {:optional true} :int]
   [:seon.agent.fs/error          {:optional true} :string]])

(schema/register! :seon.agent.fs/write-request
  [:map
   [:seon.agent.fs/path     :string]
   [:seon.agent.fs/content  :string]
   [:seon.agent.fs/encoding {:optional true} :string]])

(schema/register! :seon.agent.fs/write-response
  [:map
   [:seon.agent.fs/ok?     :boolean]
   [:seon.agent.fs/path    :string]
   [:seon.agent.fs/error   {:optional true} :string]])

(schema/register! :seon.agent.fs/list-request
  [:map
   [:seon.agent.fs/path :string]])

(schema/register! :seon.agent.fs/list-response
  [:map
   [:seon.agent.fs/ok?     :boolean]
   [:seon.agent.fs/path    :string]
   [:seon.agent.fs/entries {:optional true} [:vector :string]]
   [:seon.agent.fs/error   {:optional true} :string]])

(schema/register! :seon.agent.fs/stat-request
  [:map
   [:seon.agent.fs/path :string]])

(schema/register! :seon.agent.fs/stat-response
  [:map
   [:seon.agent.fs/ok?    :boolean]
   [:seon.agent.fs/path   :string]
   [:seon.agent.fs/size   {:optional true} :int]
   [:seon.agent.fs/dir?   {:optional true} :boolean]
   [:seon.agent.fs/file?  {:optional true} :boolean]
   [:seon.agent.fs/mtime  {:optional true} :any]
   [:seon.agent.fs/error  {:optional true} :string]])

;; ============================================================
;; Internal — error envelope helper
;; ============================================================

(defn- ->err
  "Build a uniform error response for `path` from caught exception `e`."
  [path e]
  {:seon.agent.fs/ok?   false
   :seon.agent.fs/path  path
   :seon.agent.fs/error (or (some-> e .-message) (str e))})

;; ============================================================
;; Capability config — default-deny allowlist.
;;
;; `!config` holds:
;;   :seon.agent.fs/allowed-roots — vector of absolute paths. A path is
;;       in-scope iff its resolved absolute form lives under one
;;       of these roots. Empty = nothing allowed.
;;   :seon.agent.fs/read-only?    — when true, write-file refuses.
;;
;; Bootstrap reads SEON_FS_ROOT / SEON_FS_READ_ONLY at ns load for
;; back-compat (singleton root → singleton allowlist). Consumers
;; can replace via `configure!`.
;; ============================================================

(defn- env-bootstrap []
  (let [host (platform/host)]
    {:seon.agent.fs/allowed-roots
     (case host
       ;; SEON_FS_ROOT supports MULTIPLE roots split on the platform
       ;; path-list delimiter (":" on POSIX, ";" on Windows) — same
       ;; convention as $PATH. One root stays a one-element vector.
       :node (when-let [r (some-> js/process .-env .-SEON_FS_ROOT)]
               (->> (str/split r (re-pattern (str "\\" np/delimiter)))
                    (remove str/blank?)
                    vec))
       :wasi nil)
     :seon.agent.fs/read-only?
     (case host
       :node (= "1" (some-> js/process .-env .-SEON_FS_READ_ONLY))
       :wasi true)}))

;; Active fs capability config. Read by every op; replace via
;; [[configure!]]. Defaults to env-var bootstrap so existing
;; SEON_FS_ROOT / SEON_FS_READ_ONLY callers keep working.
(defonce !config (atom (or (env-bootstrap) {})))

(defn- fs-locked?
  "True when the HOST locked the grant via SEON_FS_LOCK. Read live
   from the env on every call (not cached at ns load) — the host owns
   the knob; nothing inside the pod can flip it. Any non-blank value
   other than \"0\" locks."
  []
  (case (platform/host)
    :node (let [v (some-> js/process .-env .-SEON_FS_LOCK)]
            (boolean (and v (not (str/blank? v)) (not= "0" v))))
    :wasi false))

(defn configure!
  "Replace the active fs capability config. Merges over current state;
   pass nil for a key to leave it unchanged.

     (configure! {:seon.agent.fs/allowed-roots [\"/Users/me/work\"]
                  :seon.agent.fs/read-only?    false})

   Returns the new config map with `:seon.agent.fs/ok? true`.

   When the host set SEON_FS_LOCK, the env-bootstrapped grant is
   IMMUTABLE and this is a legible no-op error (`:seon.agent.fs/ok?
   false`, `:seon.agent.fs/locked? true`) — the live grant is
   untouched. Observed 2026-06-11: an agent NARROWED its own grant and
   locked itself out for the session; the lock exists so that can't
   recur. Read the active grant with [[grants]]."
  {:malli/schema [:=> [:cat :map] :seon.agent.fs/configure-response]}
  [updates]
  (if (fs-locked?)
    {:seon.agent.fs/ok?     false
     :seon.agent.fs/locked? true
     :seon.agent.fs/error   (str "grants are locked by the host (SEON_FS_LOCK); "
                                 "read them with (seon.agent.fs/grants)")}
    (let [next (merge @!config
                      (select-keys updates
                                   [:seon.agent.fs/allowed-roots
                                    :seon.agent.fs/read-only?]))]
      (reset! !config next)
      (assoc next :seon.agent.fs/ok? true))))

(defn- read-only? []
  (boolean (:seon.agent.fs/read-only? @!config)))

(defn- allowed-roots []
  (vec (:seon.agent.fs/allowed-roots @!config)))

(defn grants
  "What am I allowed to touch? Returns the CONFIGURED grant — the
   exact truth every fs op here enforces:

     (seon.agent.fs/grants)
     ;; => {:seon.agent.fs/allowed-roots [\"/Users/me/work-folder\"]
     ;;     :seon.agent.fs/read-only?    false
     ;;     :seon.agent.fs/locked?       false}

   `:seon.agent.fs/locked?` true means the HOST locked the grant
   (SEON_FS_LOCK env): it is immutable for this process and
   [[configure!]] is a no-op error — work within the listed roots.

   Call this BEFORE reasoning about your filesystem access. A
   directory listing or your CWD tells you what EXISTS, not what you
   may touch — the granted root is often an ANCESTOR of the directory
   you happen to be looking at (observed live 2026-06-11: an agent
   inferred its grant from a CWD listing and wrongly treated the CWD
   as the boundary when the real grant was a parent directory).
   Reading: a path resolves iff its absolute `..`-resolved form lives
   under one of `allowed-roots`; empty = default-deny, nothing
   resolves until [[configure!]] (or SEON_FS_ROOT) grants roots.
   Writing: additionally refused whenever `read-only?` is true.

   There is deliberately NO always-on prose section repeating this in
   your context: the grant is one call away, and `seon.warn/check-fs-denied`
   surfaces a DERIVED warning pointing here whenever your recent evals
   show allowlist denials — it appears only while the problem exists
   and self-heals once your calls stay in scope (reactive-context
   doctrine: derived surfaces over stored notices)."
  {:malli/schema [:=> [:cat] :seon.agent.fs/grants-response]}
  []
  {:seon.agent.fs/allowed-roots (allowed-roots)
   :seon.agent.fs/read-only?    (read-only?)
   :seon.agent.fs/locked?       (fs-locked?)})

(defn- denied [path reason]
  {:seon.agent.fs/ok?   false
   :seon.agent.fs/path  path
   :seon.agent.fs/error reason})

(defn- resolve-abs
  "Normalize `path` to an absolute, `..`-resolved string. Returns nil
   on the WASI host (paths there are pre-opened and don't normalize
   through node:path)."
  [path]
  (case (platform/host)
    :node (try (.resolve np path) (catch :default _ nil))
    :wasi nil))

(defn- under-root?
  "True iff `abs-path` is `root` itself or a descendant. Uses path
   separator boundary to avoid the classic /foo/bar vs /foobar
   false-positive."
  [abs-path root]
  (let [r (try (.resolve np root) (catch :default _ root))]
    (or (= abs-path r)
        (str/starts-with? (str abs-path)
                          (if (str/ends-with? r np/sep)
                            r
                            (str r np/sep))))))

(defn- out-of-scope?
  "True iff `path` is denied by the current allowlist. Always true
   when allowlist is empty (default-deny)."
  [path]
  (case (platform/host)
    :node (let [roots (allowed-roots)]
            (or (empty? roots)
                (let [abs (resolve-abs path)]
                  (or (nil? abs)
                      (not (some #(under-root? abs %) roots))))))
    ;; :wasi defers to wasi-pending elsewhere; treat as out-of-scope
    ;; so callers hit the wasi-pending branch.
    :wasi true))

(defn- scope-denied [path]
  (let [roots (allowed-roots)]
    (denied path
            (if (empty? roots)
              "seon.agent.fs has no allowed-roots configured (default-deny). Call (seon.agent.fs/configure! {:seon.agent.fs/allowed-roots [...]}) or set SEON_FS_ROOT."
              (str "path outside allowed-roots " (pr-str roots))))))

(defn- wasi-pending
  "Stub response for the :wasi branch until Lane B B-5 wires
   wasi:filesystem/preopens."
  [path op]
  {:seon.agent.fs/ok?   false
   :seon.agent.fs/path  path
   :seon.agent.fs/error (str ":wasi backend not implemented — " op
                       " requires wasi:filesystem/preopens (spec-05 §9.2). "
                       "Run under :node for V0.5.")})

;; ============================================================
;; Public API — map-in / Promise-out
;; ============================================================

(defn- page-lines
  "Slice `content` to the requested 1-based line window. Returns the
   honest-totals keys: what range came back of how many total lines —
   never let a partial read look complete."
  [content from-line max-lines]
  (let [lines (str/split content #"\n" -1)
        ;; a trailing newline yields a final \"\" pseudo-line — drop it
        ;; so total-lines matches what an editor shows
        lines (if (and (seq lines) (= "" (peek lines))) (pop lines) lines)
        total (count lines)
        from  (max 1 (or from-line 1))
        start (min (dec from) total)
        end   (if max-lines
                (min total (+ start (max 0 max-lines)))
                total)]
    {:seon.agent.fs/content        (str/join "\n" (subvec lines start end))
     :seon.agent.fs/from-line      from
     :seon.agent.fs/lines-returned (- end start)
     :seon.agent.fs/total-lines    total}))

(defn read-file
  "Read a file (sync). Returns:
     {:seon.agent.fs/ok? true  :seon.agent.fs/path <p> :seon.agent.fs/content <s>}    ; ok
     {:seon.agent.fs/ok? false :seon.agent.fs/path <p> :seon.agent.fs/error   <s>}    ; fail

   Default encoding `utf-8`.

   ## Paged (section) reads — first-class, use them for long files

   Optional `:seon.agent.fs/from-line` (1-based) and/or
   `:seon.agent.fs/max-lines` return a line window instead of the
   whole file, plus honest totals so a partial read NEVER looks
   complete:

     (seon.agent.fs/read-file {:seon.agent.fs/path \"/Users/me/work/big.md\"
                               :seon.agent.fs/from-line 200
                               :seon.agent.fs/max-lines 50})
     ;; => {:seon.agent.fs/ok? true :seon.agent.fs/content \"...\"
     ;;     :seon.agent.fs/from-line 200 :seon.agent.fs/lines-returned 50
     ;;     :seon.agent.fs/total-lines 1841}

   `lines-returned` < `max-lines` (or 0) means you ran off the end of
   the file — `total-lines` is always the whole file's line count.
   Don't summarize a file from one page: page through it (or bind the
   full content and process with code)."
  {:malli/schema [:=> [:cat :seon.agent.fs/read-request] :seon.agent.fs/read-response]}
  [{:seon.agent.fs/keys [path encoding from-line max-lines] :or {encoding "utf-8"}}]
  (case (platform/host)
    :node (cond
            (out-of-scope? path) (scope-denied path)
            :else (try
                    (let [content (.readFileSync fs path encoding)
                          base    {:seon.agent.fs/ok?   true
                                   :seon.agent.fs/path  path}]
                      (if (or from-line max-lines)
                        (merge base (page-lines content from-line max-lines))
                        (assoc base :seon.agent.fs/content content)))
                    (catch :default e (->err path e))))
    :wasi (wasi-pending path "read-file")))

(defn write-file
  "Write `:seon.agent.fs/content` to `:seon.agent.fs/path` (sync). Overwrites.
   Returns:
     {:seon.agent.fs/ok? true :seon.agent.fs/path <p>}                          ; ok
     {:seon.agent.fs/ok? false :seon.agent.fs/path <p> :seon.agent.fs/error <s>}       ; fail"
  {:malli/schema [:=> [:cat :seon.agent.fs/write-request] :seon.agent.fs/write-response]}
  [{:seon.agent.fs/keys [path content encoding] :or {encoding "utf-8"}}]
  (case (platform/host)
    :node (cond
            (read-only?)         (denied path "filesystem is read-only (:seon.agent.fs/read-only? true)")
            (out-of-scope? path) (scope-denied path)
            :else (try
                    (.writeFileSync fs path content encoding)
                    {:seon.agent.fs/ok?  true
                     :seon.agent.fs/path path}
                    (catch :default e (->err path e))))
    :wasi (wasi-pending path "write-file")))

(defn list-dir
  "List directory entries (filenames only, no recursion) — sync."
  {:malli/schema [:=> [:cat :seon.agent.fs/list-request] :seon.agent.fs/list-response]}
  [{:seon.agent.fs/keys [path]}]
  (case (platform/host)
    :node (cond
            (out-of-scope? path) (scope-denied path)
            :else (try
                    (let [arr (.readdirSync fs path)]
                      {:seon.agent.fs/ok?     true
                       :seon.agent.fs/path    path
                       :seon.agent.fs/entries (vec arr)})
                    (catch :default e (->err path e))))
    :wasi (wasi-pending path "list-dir")))

(defn stat
  "Stat a path (sync). Returns size, mtime, dir?/file? booleans."
  {:malli/schema [:=> [:cat :seon.agent.fs/stat-request] :seon.agent.fs/stat-response]}
  [{:seon.agent.fs/keys [path]}]
  (case (platform/host)
    :node (cond
            (out-of-scope? path) (scope-denied path)
            :else (try
                    (let [s (.statSync fs path)]
                      {:seon.agent.fs/ok?    true
                       :seon.agent.fs/path   path
                       :seon.agent.fs/size   (.-size s)
                       :seon.agent.fs/dir?   (.isDirectory s)
                       :seon.agent.fs/file?  (.isFile s)
                       :seon.agent.fs/mtime  (.-mtime s)})
                    (catch :default e (->err path e))))
    :wasi (wasi-pending path "stat")))

;; ============================================================
;; Conveniences for the agent prompt — short helpers that match the
;; worked-example shapes in seon.agent/capabilities-section.
;; ============================================================

(defn file-exists?
  "True/false convenience — checks via stat. Soft-fails to false on
   any error. Named to avoid shadowing `cljs.core/exists?`."
  {:malli/schema [:=> [:cat :seon.agent.fs/stat-request] :boolean]}
  [req]
  (:seon.agent.fs/ok? (stat req)))

(defn home-dir
  "Convenience — returns the user's home directory as a string. :node
   only; throws with a legible message on :wasi or when neither HOME
   nor USERPROFILE is set (absent = error, never nil — no-maybe rule)."
  {:malli/schema [:=> [:cat] :string]}
  []
  (case (platform/host)
    :node (or (.. js/process -env -HOME)
              (.. js/process -env -USERPROFILE)
              (throw (ex-info "home-dir: neither HOME nor USERPROFILE is set"
                              {:seon.agent.fs/op :home-dir})))
    :wasi (throw (ex-info "home-dir is :node only (no :wasi home concept yet)"
                          {:seon.agent.fs/op :home-dir}))))

;; ============================================================
;; Recursive walk — discover files under a tree, filter by extension,
;; cap total results so an agent can't accidentally enumerate the
;; whole filesystem.
;;
;; Implementation note: pure recursive scan via list-dir + stat. Slower
;; than `glob` for large trees but portable + no extra deps. For V0.5
;; the trees we walk (a consulting wiki, a project repo) are O(1000)
;; files — fast enough.
;; ============================================================

(schema/register! :seon.agent.fs/match-ext   :string)
(schema/register! :seon.agent.fs/skip-hidden :boolean)
(schema/register! :seon.agent.fs/max-results :int)

(schema/register! :seon.agent.fs/walk-request
  [:map
   [:seon.agent.fs/path        :string]
   [:seon.agent.fs/match-ext   {:optional true} :string]
   [:seon.agent.fs/skip-hidden {:optional true} :boolean]
   [:seon.agent.fs/max-results {:optional true} :int]])

(schema/register! :seon.agent.fs/walk-response
  [:map
   [:seon.agent.fs/ok?         :boolean]
   [:seon.agent.fs/path        :string]
   [:seon.agent.fs/entries     {:optional true} [:vector :string]]
   [:seon.agent.fs/total-found {:optional true} :int]
   [:seon.agent.fs/truncated?  {:optional true} :boolean]
   [:seon.agent.fs/error       {:optional true} :string]])

(defn- walk-dir-recursive!
  "Internal — depth-first recursive walk (sync). Mutates `!out`
   (vector of matching absolute paths) and `!truncated?` (boolean)."
  [dir pred skip-hidden cap !out !truncated?]
  (when-not @!truncated?
    (let [listing (try (.readdirSync fs dir) (catch :default _ nil))]
      (when listing
        (doseq [name (sort (vec listing))
                :when (not @!truncated?)]
          (when-not (and skip-hidden (str/starts-with? name "."))
            (let [full (str dir "/" name)
                  s    (try (.statSync fs full) (catch :default _ nil))]
              (when s
                (cond
                  (.isDirectory s)
                  (walk-dir-recursive! full pred skip-hidden cap !out !truncated?)

                  (and (.isFile s) (pred full))
                  (do (swap! !out conj full)
                      (when (>= (count @!out) cap)
                        (reset! !truncated? true))))))))))))

(defn walk-dir
  "Recursively walk `:seon.agent.fs/path` (sync), return matching files.
   Returns:
     {:seon.agent.fs/ok? true :seon.agent.fs/path <p>
      :seon.agent.fs/entries [<absolute-path>...]
      :seon.agent.fs/total-found <int>
      :seon.agent.fs/truncated? <bool>}
     {:seon.agent.fs/ok? false :seon.agent.fs/path <p> :seon.agent.fs/error <s>}

   Opts (all optional):
     :seon.agent.fs/match-ext   — e.g. \".md\" — only files ending in this
     :seon.agent.fs/skip-hidden — skip files starting with \".\" (default true)
     :seon.agent.fs/max-results — cap (default 500); `:truncated? true` if hit

   Example:
     (seon.agent.fs/walk-dir {:seon.agent.fs/path \"/Users/you/src/your-project\"
                        :seon.agent.fs/match-ext \".md\"})"
  {:malli/schema [:=> [:cat :seon.agent.fs/walk-request] :seon.agent.fs/walk-response]}
  [{:seon.agent.fs/keys [path match-ext skip-hidden max-results]
    :or {skip-hidden true max-results 5000}}]
  (case (platform/host)
    :node (cond
            (out-of-scope? path) (scope-denied path)
            :else (try
                    (let [pred       (if match-ext
                                       #(str/ends-with? % match-ext)
                                       (constantly true))
                          !out       (atom [])
                          !truncated (atom false)]
                      (walk-dir-recursive! path pred skip-hidden max-results
                                           !out !truncated)
                      {:seon.agent.fs/ok?         true
                       :seon.agent.fs/path        path
                       :seon.agent.fs/entries     @!out
                       :seon.agent.fs/total-found (count @!out)
                       :seon.agent.fs/truncated?  @!truncated})
                    (catch :default e (->err path e))))
    :wasi (wasi-pending path "walk-dir")))
