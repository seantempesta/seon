(ns seon.agent.fs
  "Local-filesystem capability — your eyes and hands on the user's
   machine, gated by an explicit allowlist.

   ## Security model

   **Default-deny.** With no configuration every op returns
   `:seon.agent.fs/ok? false`. Access is granted via [[configure!]] (or
   the `SEON_FS_ROOT` env var); writes additionally require
   `:seon.agent.fs/read-only? false`. This is a SOFT boundary against
   LLM-emitted accidents, not a security boundary: a `..`-traversal or
   out-of-scope absolute path lands on a denial envelope rather than
   touching disk.

   ## House-rule API

   Map-in / map-out. Every fn takes one map with fully-namespaced keys,
   returns one map with a `:seon.agent.fs/ok?` discriminator, and never
   throws from its body — SEMANTIC failures (denied path, read-only,
   missing file) land as a `:seon.agent.fs/error` string. Only a
   SHAPE-invalid call trips the instrumentation validator, which the eval
   boundary surfaces as a structured `:seon/error` value — data, never a
   crash.

   ## Worked examples

     (seon.agent.fs/grants)    ;; the CONFIGURED roots + read-only flag —
                               ;; call this, never infer the grant from a listing
     (seon.agent.fs/read-file  {:seon.agent.fs/path \"/Users/me/work-folder/notes.md\"})
     (seon.agent.fs/write-file {:seon.agent.fs/path \"/Users/me/work-folder/out.txt\"
                                :seon.agent.fs/content \"hello\"})
     (seon.agent.fs/edit-file  {:seon.agent.fs/path \"/Users/me/work-folder/out.txt\"
                                :seon.agent.fs/old-string \"hello\"
                                :seon.agent.fs/new-string \"hi\"})
     (seon.agent.fs/list-dir   {:seon.agent.fs/path \"/Users/me/work-folder\"})
     (seon.agent.fs/walk-dir   {:seon.agent.fs/path \"/Users/me/work-folder\"
                                :seon.agent.fs/match-ext \".md\"})
     (seon.agent.fs/stat       {:seon.agent.fs/path \"/Users/me/work-folder/notes.md\"})"
  (:require
    ["node:fs" :as fs]
    [clojure.string :as str]
    [seon.agent.fs.internal :as int]
    [seon.platform :as platform]
    [seon.schema :as schema]))

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

;; The configured grant. `allowed-roots` empty = default-deny.
(schema/register! :seon.agent.fs/allowed-roots [:vector :string])
(schema/register! :seon.agent.fs/read-only?    :boolean)
;; True when the host set SEON_FS_LOCK — the grant is immutable for this
;; process; configure! is a no-op error. Read live from the env.
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

;; edit-file — in-place edits: line-range OR unique exact-match replace.
(schema/register! :seon.agent.fs/to-line           :int)
(schema/register! :seon.agent.fs/old-string        :string)
(schema/register! :seon.agent.fs/new-string        :string)
(schema/register! :seon.agent.fs/lines-replaced    :int)
(schema/register! :seon.agent.fs/lines-inserted    :int)
(schema/register! :seon.agent.fs/context           :string)
(schema/register! :seon.agent.fs/context-from-line :int)

(schema/register! :seon.agent.fs/edit-request
  [:map
   [:seon.agent.fs/path       :string]
   [:seon.agent.fs/from-line  {:optional true} :int]
   [:seon.agent.fs/to-line    {:optional true} :int]
   [:seon.agent.fs/content    {:optional true} :string]
   [:seon.agent.fs/old-string {:optional true} :string]
   [:seon.agent.fs/new-string {:optional true} :string]
   [:seon.agent.fs/encoding   {:optional true} :string]])

(schema/register! :seon.agent.fs/edit-response
  [:map
   [:seon.agent.fs/ok?               :boolean]
   [:seon.agent.fs/path              :string]
   [:seon.agent.fs/from-line         {:optional true} :int]
   [:seon.agent.fs/lines-replaced    {:optional true} :int]
   [:seon.agent.fs/lines-inserted    {:optional true} :int]
   [:seon.agent.fs/total-lines       {:optional true} :int]
   [:seon.agent.fs/context           {:optional true} :string]
   [:seon.agent.fs/context-from-line {:optional true} :int]
   [:seon.agent.fs/truncated?        {:optional true} :boolean]
   [:seon.agent.fs/error             {:optional true} :string]])

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

(schema/register! :seon.agent.fs/match-ext   :string)
(schema/register! :seon.agent.fs/skip-hidden :boolean)
(schema/register! :seon.agent.fs/max-results :int)
(schema/register! :seon.agent.fs/total-found :int)
(schema/register! :seon.agent.fs/truncated?  :boolean)

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

;; ============================================================
;; Grant — configure + inspect the allowlist.
;; ============================================================

(defn configure!
  "Replace the active fs grant, merging over current state.

   Pass only the keys you want to change.

     (configure! {:seon.agent.fs/allowed-roots [\"/Users/me/work\"]
                  :seon.agent.fs/read-only?    false})

   Returns the new config with `:seon.agent.fs/ok? true`. When the host
   set SEON_FS_LOCK the grant is IMMUTABLE: this is a legible no-op error
   (`:seon.agent.fs/ok? false`, `:seon.agent.fs/locked? true`) and the
   live grant is untouched — read it with [[grants]] and work within it
   (narrowing your own grant can lock you out of paths you still need)."
  {:malli/schema [:=> [:cat :map] :seon.agent.fs/configure-response]}
  [updates]
  (if (int/fs-locked?)
    {:seon.agent.fs/ok?     false
     :seon.agent.fs/locked? true
     :seon.agent.fs/error   (str "grants are locked by the host (SEON_FS_LOCK); "
                                 "read them with (seon.agent.fs/grants)")}
    (let [next (merge @int/!config
                      (select-keys updates
                                   [:seon.agent.fs/allowed-roots
                                    :seon.agent.fs/read-only?]))]
      (reset! int/!config next)
      (assoc next :seon.agent.fs/ok? true))))

(defn grants
  "What am I allowed to touch?

   Returns the CONFIGURED grant — the exact
   truth every fs op enforces:

     (seon.agent.fs/grants)
     ;; => {:seon.agent.fs/allowed-roots [\"/Users/me/work-folder\"]
     ;;     :seon.agent.fs/read-only?    false
     ;;     :seon.agent.fs/locked?       false}

   Call this BEFORE reasoning about your filesystem access. A directory
   listing or your CWD tells you what EXISTS, not what you may touch — the
   granted root is often an ANCESTOR of the directory you happen to be in.
   A path resolves iff its absolute `..`-resolved form lives under one of
   `allowed-roots`; empty = default-deny. Writes are additionally refused
   when `read-only?` is true. `:seon.agent.fs/locked? true` means the host
   locked the grant (SEON_FS_LOCK) and [[configure!]] is a no-op error."
  {:malli/schema [:=> [:cat] :seon.agent.fs/grants-response]}
  []
  {:seon.agent.fs/allowed-roots (int/allowed-roots)
   :seon.agent.fs/read-only?    (int/read-only?)
   :seon.agent.fs/locked?       (int/fs-locked?)})

;; ============================================================
;; Reads + writes — map-in / map-out, sync, never throw.
;; ============================================================

(defn read-file
  "Read a file (sync).

   Returns:
     {:seon.agent.fs/ok? true  :seon.agent.fs/path <p> :seon.agent.fs/content <s>}    ; ok
     {:seon.agent.fs/ok? false :seon.agent.fs/path <p> :seon.agent.fs/error   <s>}    ; fail

   Default encoding `utf-8`.

   ## Paged (section) reads — use them for long files

   Optional `:seon.agent.fs/from-line` (1-based) and/or
   `:seon.agent.fs/max-lines` return a line window plus honest totals so a
   partial read NEVER looks complete:

     (seon.agent.fs/read-file {:seon.agent.fs/path \"/Users/me/work/big.md\"
                               :seon.agent.fs/from-line 200
                               :seon.agent.fs/max-lines 50})
     ;; => {:seon.agent.fs/ok? true :seon.agent.fs/content \"...\"
     ;;     :seon.agent.fs/from-line 200 :seon.agent.fs/lines-returned 50
     ;;     :seon.agent.fs/total-lines 1841}

   `lines-returned` < `max-lines` means you ran off the end. Don't
   summarize a file from one page — page through it, or bind the full
   content and process it with code."
  {:malli/schema [:=> [:cat :seon.agent.fs/read-request] :seon.agent.fs/read-response]}
  [{:seon.agent.fs/keys [path encoding from-line max-lines] :or {encoding "utf-8"}}]
  (case (platform/host)
    :node (cond
            (int/out-of-scope? path) (int/scope-denied path)
            :else (try
                    (let [content (.readFileSync fs path encoding)
                          base    {:seon.agent.fs/ok?   true
                                   :seon.agent.fs/path  path}]
                      (if (or from-line max-lines)
                        (merge base (int/page-lines content from-line max-lines))
                        (assoc base :seon.agent.fs/content content)))
                    (catch :default e (int/->err path e))))
    :wasi (int/wasi-pending path "read-file")))

(defn write-file
  "Write `:seon.agent.fs/content` to `:seon.agent.fs/path` (sync).
   Overwrites. Returns:
     {:seon.agent.fs/ok? true  :seon.agent.fs/path <p>}                           ; ok
     {:seon.agent.fs/ok? false :seon.agent.fs/path <p> :seon.agent.fs/error <s>}  ; fail"
  {:malli/schema [:=> [:cat :seon.agent.fs/write-request] :seon.agent.fs/write-response]}
  [{:seon.agent.fs/keys [path content encoding] :or {encoding "utf-8"}}]
  (case (platform/host)
    :node (cond
            (int/read-only?)         (int/denied path "filesystem is read-only (:seon.agent.fs/read-only? true)")
            (int/out-of-scope? path) (int/scope-denied path)
            :else (try
                    (.writeFileSync fs path content encoding)
                    {:seon.agent.fs/ok?  true
                     :seon.agent.fs/path path}
                    (catch :default e (int/->err path e))))
    :wasi (int/wasi-pending path "write-file")))

(defn- apply-edit
  "Read `path`, run `edit-fn` (content → new-content facts or error
   envelope keys), write the result, return the shared edit envelope."
  [path encoding edit-fn]
  (try
    (let [content (.readFileSync fs path encoding)
          r       (edit-fn content)]
      (if (:seon.agent.fs/error r)
        (int/denied path (:seon.agent.fs/error r))
        (let [{:seon.agent.fs/keys [new-content new-lines from-line
                                    lines-replaced lines-inserted]} r
              to (if (pos? lines-inserted)
                   (dec (+ from-line lines-inserted))
                   from-line)]
          (.writeFileSync fs path new-content encoding)
          (merge {:seon.agent.fs/ok?            true
                  :seon.agent.fs/path           path
                  :seon.agent.fs/from-line      from-line
                  :seon.agent.fs/lines-replaced lines-replaced
                  :seon.agent.fs/lines-inserted lines-inserted
                  :seon.agent.fs/total-lines    (count new-lines)}
                 (int/edit-context-window new-lines from-line to)))))
    (catch :default e (int/->err path e))))

(defn edit-file
  "Edit a file in place: line-range or unique exact-match replace.

   Two modes, ONE result envelope — pass exactly one:

   Line range — replace 1-based INCLUSIVE lines [from-line to-line]
   with `:seon.agent.fs/content` (empty string = delete the range):

     (seon.agent.fs/edit-file {:seon.agent.fs/path \"/Users/me/work/f.clj\"
                               :seon.agent.fs/from-line 12
                               :seon.agent.fs/to-line   14
                               :seon.agent.fs/content   \"(def x 2)\"})

   Exact match — replace old-string with new-string; old-string must
   match EXACTLY ONCE (0 or >1 matches → a guiding error, no write —
   the safe editor primitive):

     (seon.agent.fs/edit-file {:seon.agent.fs/path \"/Users/me/work/f.clj\"
                               :seon.agent.fs/old-string \"(def x 1)\"
                               :seon.agent.fs/new-string \"(def x 2)\"})

   Success returns where the edit landed (`from-line`, `lines-replaced`
   → `lines-inserted`, new `total-lines`) plus a token-capped `context`
   window of the RESULT (starting at `context-from-line`) — verify from
   that, no full re-read needed. Same write gate as [[write-file]]:
   path must be under a granted root and `read-only?` false."
  {:malli/schema [:=> [:cat :seon.agent.fs/edit-request] :seon.agent.fs/edit-response]}
  [{:seon.agent.fs/keys [path from-line to-line content old-string new-string encoding]
    :or {encoding "utf-8"}}]
  (let [line-mode?  (or (some? from-line) (some? to-line) (some? content))
        match-mode? (or (some? old-string) (some? new-string))]
    (case (platform/host)
      :node (cond
              (int/read-only?)         (int/denied path "filesystem is read-only (:seon.agent.fs/read-only? true)")
              (int/out-of-scope? path) (int/scope-denied path)

              (and line-mode? match-mode?)
              (int/denied path (str "pass ONE mode: from-line/to-line/content "
                                    "(line range) OR old-string/new-string (exact match)"))

              (and line-mode? (not (and from-line to-line content)))
              (int/denied path (str "line-range mode needs all of :seon.agent.fs/from-line, "
                                    ":seon.agent.fs/to-line (1-based inclusive) and "
                                    ":seon.agent.fs/content"))

              (and match-mode? (not (and old-string new-string)))
              (int/denied path (str "exact-match mode needs both :seon.agent.fs/old-string "
                                    "and :seon.agent.fs/new-string"))

              line-mode?
              (apply-edit path encoding #(int/line-range-edit % from-line to-line content))

              match-mode?
              (apply-edit path encoding #(int/match-edit % old-string new-string))

              :else
              (int/denied path (str "no edit given — pass from-line/to-line/content "
                                    "(line range) or old-string/new-string (exact match)")))
      :wasi (int/wasi-pending path "edit-file"))))

(defn list-dir
  "List directory entries (filenames only, no recursion) — sync."
  {:malli/schema [:=> [:cat :seon.agent.fs/list-request] :seon.agent.fs/list-response]}
  [{:seon.agent.fs/keys [path]}]
  (case (platform/host)
    :node (cond
            (int/out-of-scope? path) (int/scope-denied path)
            :else (try
                    (let [arr (.readdirSync fs path)]
                      {:seon.agent.fs/ok?     true
                       :seon.agent.fs/path    path
                       :seon.agent.fs/entries (vec arr)})
                    (catch :default e (int/->err path e))))
    :wasi (int/wasi-pending path "list-dir")))

(defn stat
  "Stat a path (sync). Returns size, mtime, dir?/file? booleans."
  {:malli/schema [:=> [:cat :seon.agent.fs/stat-request] :seon.agent.fs/stat-response]}
  [{:seon.agent.fs/keys [path]}]
  (case (platform/host)
    :node (cond
            (int/out-of-scope? path) (int/scope-denied path)
            :else (try
                    (let [s (.statSync fs path)]
                      {:seon.agent.fs/ok?    true
                       :seon.agent.fs/path   path
                       :seon.agent.fs/size   (.-size s)
                       :seon.agent.fs/dir?   (.isDirectory s)
                       :seon.agent.fs/file?  (.isFile s)
                       :seon.agent.fs/mtime  (.-mtime s)})
                    (catch :default e (int/->err path e))))
    :wasi (int/wasi-pending path "stat")))

(defn file-exists?
  "True/false convenience — checks via stat, false on any error.

   Soft-fails to false. Named to avoid shadowing `cljs.core/exists?`."
  {:malli/schema [:=> [:cat :seon.agent.fs/stat-request] :boolean]}
  [req]
  (:seon.agent.fs/ok? (stat req)))

(defn home-dir
  "The user's home directory as a string.

   :node only; throws with a
   legible message on :wasi or when neither HOME nor USERPROFILE is set
   (absent = error, never nil)."
  {:malli/schema [:=> [:cat] :string]}
  []
  (case (platform/host)
    :node (or (.. js/process -env -HOME)
              (.. js/process -env -USERPROFILE)
              (throw (ex-info "home-dir: neither HOME nor USERPROFILE is set"
                              {:seon.agent.fs/op :home-dir})))
    :wasi (throw (ex-info "home-dir is :node only (no :wasi home concept yet)"
                          {:seon.agent.fs/op :home-dir}))))

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
     :seon.agent.fs/max-results — cap (default 5000); `:truncated? true` if hit

   Example:
     (seon.agent.fs/walk-dir {:seon.agent.fs/path \"/Users/you/src/your-project\"
                              :seon.agent.fs/match-ext \".md\"})"
  {:malli/schema [:=> [:cat :seon.agent.fs/walk-request] :seon.agent.fs/walk-response]}
  [{:seon.agent.fs/keys [path match-ext skip-hidden max-results]
    :or {skip-hidden true max-results 5000}}]
  (case (platform/host)
    :node (cond
            (int/out-of-scope? path) (int/scope-denied path)
            :else (try
                    (let [pred       (if match-ext
                                       #(str/ends-with? % match-ext)
                                       (constantly true))
                          !out       (atom [])
                          !truncated (atom false)]
                      (int/walk-dir-recursive! path pred skip-hidden max-results
                                               !out !truncated)
                      {:seon.agent.fs/ok?         true
                       :seon.agent.fs/path        path
                       :seon.agent.fs/entries     @!out
                       :seon.agent.fs/total-found (count @!out)
                       :seon.agent.fs/truncated?  @!truncated})
                    (catch :default e (int/->err path e))))
    :wasi (int/wasi-pending path "walk-dir")))
