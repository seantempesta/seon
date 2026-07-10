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
    [seon.agent.fs.match :as match]
    [seon.code :as code]
    [seon.platform :as platform]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas
;; ============================================================

(schema/register! :seon.agent.fs/path     :string)
(schema/register! :seon.agent.fs/encoding :string)
;; Content is a string OR a `#code` heredoc value (`:seon.code/value` =
;; `[:or :string :seon.code/block]`) — one meaning, referenced not
;; inlined. Every write/insert boundary extracts the verbatim text with
;; `seon.code/text`, so a plain string is unchanged and a foreign-code
;; block flows straight from the transcript to disk with no escaping.
(schema/register! :seon.agent.fs/content  :seon.code/value)
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

;; The file's SHA-256 content address — echoed back to replace! as an
;; optimistic fence against editing a stale copy.
(schema/register! :seon.agent.fs/file-sha       :string)

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
   [:seon.agent.fs/file-sha       {:optional true} :seon.agent.fs/file-sha]
   [:seon.agent.fs/error          {:optional true} :string]])

(schema/register! :seon.agent.fs/write-request
  [:map
   [:seon.agent.fs/path     :string]
   [:seon.agent.fs/content  :seon.agent.fs/content]
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
   [:seon.agent.fs/content    {:optional true} :seon.agent.fs/content]
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
(schema/register! :seon.agent.fs/glob        :string) ; e.g. "*.py" or "src/**/*.cljs"
(schema/register! :seon.agent.fs/skip-hidden :boolean)
(schema/register! :seon.agent.fs/max-results :int)
;; total-found = how many matches were collected BEFORE the walk stopped at
;; max-results — a true grand total ONLY when :truncated? is false (the walk
;; halts at the cap; there is no unbounded second counting pass).
(schema/register! :seon.agent.fs/total-found :int)
(schema/register! :seon.agent.fs/truncated?  :boolean)
(schema/register! :seon.agent.fs/hint        :string)
;; result order: :name (per-dir alphabetical, the default) or :mtime
;; (newest-first — the "what changed recently" walk).
(schema/register! :seon.agent.fs/sort        [:enum :name :mtime])

(schema/register! :seon.agent.fs/walk-request
  [:map
   [:seon.agent.fs/path        :string]
   [:seon.agent.fs/match-ext   {:optional true} :string]
   [:seon.agent.fs/glob        {:optional true} :seon.agent.fs/glob]
   [:seon.agent.fs/skip-hidden {:optional true} :boolean]
   [:seon.agent.fs/sort        {:optional true} :seon.agent.fs/sort]
   [:seon.agent.fs/max-results {:optional true} :int]])

(schema/register! :seon.agent.fs/walk-response
  [:map
   [:seon.agent.fs/ok?         :boolean]
   [:seon.agent.fs/path        :string]
   [:seon.agent.fs/entries     {:optional true} [:vector :string]]
   [:seon.agent.fs/total-found {:optional true} :int]
   [:seon.agent.fs/truncated?  {:optional true} :boolean]
   [:seon.agent.fs/hint        {:optional true} :seon.agent.fs/hint]
   [:seon.agent.fs/error       {:optional true} :string]])

;; ── view — a line-numbered, bounded, sha-stamped read (the edit surface).
(schema/register! :seon.agent.fs/view-request
  [:map
   [:seon.agent.fs/path      :string]
   [:seon.agent.fs/from-line {:optional true} :int]
   [:seon.agent.fs/max-lines {:optional true} :int]
   [:seon.agent.fs/encoding  {:optional true} :string]])

(schema/register! :seon.agent.fs/view-response
  [:map
   [:seon.agent.fs/ok?            :boolean]
   [:seon.agent.fs/path           :string]
   [:seon.agent.fs/content        {:optional true} :string]
   [:seon.agent.fs/from-line      {:optional true} :int]
   [:seon.agent.fs/lines-returned {:optional true} :int]
   [:seon.agent.fs/total-lines    {:optional true} :int]
   [:seon.agent.fs/file-sha       {:optional true} :seon.agent.fs/file-sha]
   [:seon.agent.fs/error          {:optional true} :string]])

;; ── anchored edits — replace! / insert!. Deterministic-only mutation:
;; the pure cascade in seon.agent.fs.match FINDS candidates; only an
;; unambiguous hit MUTATES. Response keys reference the match shapes so
;; the range/normalization vocabulary is defined once.
(schema/register! :seon.agent.fs/range-after     :seon.agent.fs.match/range)
(schema/register! :seon.agent.fs/lines-added     :int)
(schema/register! :seon.agent.fs/lines-removed   :int)
(schema/register! :seon.agent.fs/excerpt         :string)
(schema/register! :seon.agent.fs/normalizations  :seon.agent.fs.match/normalizations)
(schema/register! :seon.agent.fs/after-line      :int)
(schema/register! :seon.agent.fs/before-line     :int)

;; ::all? — replace every occurrence without counting them; mutually
;; exclusive with ::expected-count (the same rule the pure cascade enforces).
(schema/register! :seon.agent.fs/all? :seon.agent.fs.match/all?)

(schema/register! :seon.agent.fs/replace-request
  [:and
   [:map
    [:seon.agent.fs/path           :string]
    [:seon.agent.fs/find           :seon.code/value]
    [:seon.agent.fs/replace        :seon.code/value]
    [:seon.agent.fs/expected-count {:optional true} :seon.agent.fs.match/expected-count]
    [:seon.agent.fs/all?           {:optional true} :seon.agent.fs/all?]
    [:seon.agent.fs/near           {:optional true} :seon.agent.fs.match/near]
    [:seon.agent.fs/file-sha       {:optional true} :seon.agent.fs/file-sha]
    [:seon.agent.fs/encoding       {:optional true} :string]]
   [:fn {:error/message ":seon.agent.fs/all? and :seon.agent.fs/expected-count are mutually exclusive"}
    (fn [m] (not (and (contains? m :seon.agent.fs/expected-count)
                      (contains? m :seon.agent.fs/all?))))]])

(schema/register! :seon.agent.fs/insert-request
  [:map
   [:seon.agent.fs/path        :string]
   [:seon.agent.fs/content     :seon.code/value]
   [:seon.agent.fs/after-line  {:optional true} :seon.agent.fs/after-line]
   [:seon.agent.fs/before-line {:optional true} :seon.agent.fs/before-line]
   [:seon.agent.fs/encoding    {:optional true} :string]])

;; ONE anchored-edit envelope — replace! and insert! share it (no
;; parallel shape). ok? true carries where the edit landed + a
;; line-numbered excerpt; ok? false carries the guiding :seon.error/*
;; (and on a sha mismatch, the file's ACTUAL :seon.agent.fs/file-sha).
(schema/register! :seon.agent.fs/anchored-response
  [:or
   [:map
    [:seon.agent.fs/ok?            [:= true]]
    [:seon.agent.fs/path           :string]
    [:seon.agent.fs/file-sha       :seon.agent.fs/file-sha]
    [:seon.agent.fs/range-after    :seon.agent.fs/range-after]
    [:seon.agent.fs/lines-added    :seon.agent.fs/lines-added]
    [:seon.agent.fs/lines-removed  :seon.agent.fs/lines-removed]
    [:seon.agent.fs/normalizations {:optional true} :seon.agent.fs/normalizations]
    [:seon.agent.fs/excerpt        :seon.agent.fs/excerpt]]
   [:map
    [:seon.agent.fs/ok?      [:= false]]
    [:seon.agent.fs/path     :string]
    [:seon.agent.fs/file-sha {:optional true} :seon.agent.fs/file-sha]
    [:seon.error/message     :string]
    [:seon.error/data        {:optional true} :map]]])

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
     ; ⟹ «map: ::allowed-roots [\"/Users/me/work-folder\"], ::read-only? false, ::locked? false»

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
     ; ⟹ «map: ::ok? true, ::content \"...\", ::from-line 200, ::lines-returned 50, ::total-lines 1841»

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
                          base    {:seon.agent.fs/ok?       true
                                   :seon.agent.fs/path      path
                                   :seon.agent.fs/file-sha  (int/file-sha content)}]
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
                    (.writeFileSync fs path (code/text content) encoding)
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
              (apply-edit path encoding #(int/line-range-edit % from-line to-line (code/text content)))

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
      :seon.agent.fs/total-found <int>   ; found BEFORE the walk stopped at
                                         ; the cap — a true grand total only
                                         ; when :truncated? is false
      :seon.agent.fs/truncated? <bool>}
     {:seon.agent.fs/ok? false :seon.agent.fs/path <p> :seon.agent.fs/error <s>}

   Opts (all optional):
     :seon.agent.fs/match-ext   — e.g. \".md\" — only files ending in this
     :seon.agent.fs/glob        — a shell glob (`*.py`, `src/**/*.cljs`)
                                  matched against the root-relative path; a
                                  slash-free glob also matches the basename,
                                  so `*.py` finds .py files at any depth.
                                  Combined with match-ext by AND.
     :seon.agent.fs/skip-hidden — skip files starting with \".\" (default true)
     :seon.agent.fs/sort        — :name (default) or :mtime (newest-first)
     :seon.agent.fs/max-results — cap (default 5000); `:truncated? true` +
                                  a :seon.agent.fs/hint when hit (raise the
                                  cap or narrow the glob). On truncation the
                                  walk STOPS at the cap, so :total-found then
                                  equals the cap, NOT the true grand total.

   Example:
     (seon.agent.fs/walk-dir {:seon.agent.fs/path \"/Users/you/src/your-project\"
                              :seon.agent.fs/glob \"*.cljs\"
                              :seon.agent.fs/sort :mtime})"
  {:malli/schema [:=> [:cat :seon.agent.fs/walk-request] :seon.agent.fs/walk-response]}
  [{:seon.agent.fs/keys [path match-ext glob skip-hidden sort max-results]
    :or {skip-hidden true max-results 5000}}]
  (case (platform/host)
    :node (cond
            (int/out-of-scope? path) (int/scope-denied path)
            :else (try
                    (let [pred       (int/walk-pred path match-ext glob)
                          !out       (atom [])
                          !truncated (atom false)]
                      (int/walk-dir-recursive! path pred skip-hidden max-results
                                               !out !truncated)
                      (let [found   (count @!out)
                            entries (if (= :mtime sort)
                                      (int/sort-by-mtime @!out)
                                      @!out)]
                        (cond-> {:seon.agent.fs/ok?         true
                                 :seon.agent.fs/path        path
                                 :seon.agent.fs/entries     entries
                                 :seon.agent.fs/total-found found
                                 :seon.agent.fs/truncated?  @!truncated}
                          @!truncated
                          (assoc :seon.agent.fs/hint
                                 (str "hit the " max-results "-result cap — the walk STOPPED "
                                      "here, so :seon.agent.fs/total-found is the count found "
                                      "before the cap, NOT a true grand total (more files "
                                      "match). Raise :seon.agent.fs/max-results, or narrow "
                                      "with :seon.agent.fs/glob / :seon.agent.fs/match-ext / a "
                                      "deeper :seon.agent.fs/path.")))))
                    (catch :default e (int/->err path e))))
    :wasi (int/wasi-pending path "walk-dir")))

;; ============================================================
;; Line-numbered view — the read surface an anchored edit is aimed with.
;; ============================================================

(def default-view-lines
  "Default [[view]] page size — a line-numbered read renders straight
   into context, so an unbounded view is never the default."
  100)

(defn view
  "A line-numbered, bounded window of a file, with its content SHA.

   The read surface you aim an edit with: `:seon.agent.fs/content` carries
   1-based line numbers (right-aligned, `N<tab>line`) so you can pick an
   exact `:seon.agent.fs/near` window, and `:seon.agent.fs/file-sha` is the
   token you echo to [[replace!]] to fence against a stale edit. Defaults
   to the first `default-view-lines` lines; page the rest with a 1-based
   `:seon.agent.fs/from-line` + `:seon.agent.fs/max-lines`. `total-lines`
   is the WHOLE file, so a partial page never looks complete —
   `lines-returned` < `max-lines` means you ran off the end. STRIP the
   `N<tab>` prefix before copying text into a find/replace payload."
  {:malli/schema [:=> [:cat :seon.agent.fs/view-request] :seon.agent.fs/view-response]}
  [{:seon.agent.fs/keys [path from-line max-lines encoding]}]
  (let [encoding  (or encoding "utf-8")
        max-lines (or max-lines default-view-lines)]
    (case (platform/host)
      :node (cond
              (int/out-of-scope? path) (int/scope-denied path)
              :else (try
                      (let [content (.readFileSync fs path encoding)
                            lines   (match/content-lines content)
                            total   (count lines)
                            from    (max 1 (or from-line 1))
                            start   (min (dec from) total)
                            end     (min total (+ start (max 0 max-lines)))
                            window  (subvec lines start end)]
                        ;; Key ORDER is load-bearing: the transcript render
                        ;; clips long values front-to-back, so the tiny
                        ;; load-bearing keys (::file-sha — the replace!
                        ;; fence token) must render BEFORE the big ::content
                        ;; string, never after it (a clipped view hid the
                        ;; sha and the agent invented one — observer drive
                        ;; evidence 2026-07-10). ≤8 keys = array map,
                        ;; insertion order preserved.
                        {:seon.agent.fs/ok?            true
                         :seon.agent.fs/path           path
                         :seon.agent.fs/file-sha       (int/file-sha content)
                         :seon.agent.fs/from-line      from
                         :seon.agent.fs/lines-returned (- end start)
                         :seon.agent.fs/total-lines    total
                         :seon.agent.fs/content        (match/number-lines window from)})
                      (catch :default e (int/->err path e))))
      :wasi (int/wasi-pending path "view"))))

;; ============================================================
;; Anchored edits — replace! / insert!. The mutation rule: the pure
;; cascade FINDS candidates; only an unambiguous, deterministic hit
;; WRITES. Every failure is a value on the shared :seon.error/* shape.
;; ============================================================

(defn- anchored-msg
  "An anchored-edit ok?-false envelope on the shared :seon.error/* shape."
  ([path msg] (anchored-msg path msg nil))
  ([path msg data]
   (cond-> {:seon.agent.fs/ok?  false
            :seon.agent.fs/path path
            :seon.error/message msg}
     (seq data) (assoc :seon.error/data data))))

(defn- ->anchored-fail
  "Re-shape an fs `:seon.agent.fs/error` envelope (from the shared gating
   helpers) into the anchored-edit `:seon.error/message` contract."
  [{:seon.agent.fs/keys [path error]}]
  (anchored-msg path error))

(defn- stale-file
  "The optimistic-fence failure: the on-disk `actual` sha ≠ the `expected`
   the caller passed. Carries the ACTUAL sha so the caller can re-aim."
  [path actual expected]
  (assoc (anchored-msg path
                       (str "file changed since your read — the on-disk SHA is "
                            actual ", you passed " expected ". Re-view "
                            "(seon.agent.fs/view {:seon.agent.fs/path …}) for the "
                            "current content + :seon.agent.fs/file-sha, then retry.")
                       {:seon.agent.fs/file-sha actual})
         :seon.agent.fs/file-sha actual))

(defn- cascade-fail
  "Turn a match `:fail` decision into the anchored failure envelope —
   the guiding message plus the reason + line-numbered candidates."
  [path decision]
  (anchored-msg path
                (:seon.agent.fs.match/message decision)
                {:seon.agent.fs.match/reason     (:seon.agent.fs.match/reason decision)
                 :seon.agent.fs.match/candidates (:seon.agent.fs.match/candidates decision)}))

(defn- edit-success
  "The anchored success envelope for a splice that produced `new-content`,
   from a match `:apply` `decision`. `range-after` + `excerpt` are the
   1-based landing spot and a ±3-line line-numbered view of the result."
  [path new-content decision]
  (let [lines (match/content-lines new-content)
        range (:seon.agent.fs.match/range-after decision)]
    (cond-> {:seon.agent.fs/ok?           true
             :seon.agent.fs/path          path
             :seon.agent.fs/file-sha      (int/file-sha new-content)
             :seon.agent.fs/range-after   range
             :seon.agent.fs/lines-added   (:seon.agent.fs.match/lines-added decision)
             :seon.agent.fs/lines-removed (:seon.agent.fs.match/lines-removed decision)
             :seon.agent.fs/excerpt       (match/preview lines range)}
      (seq (:seon.agent.fs.match/normalizations decision))
      (assoc :seon.agent.fs/normalizations (:seon.agent.fs.match/normalizations decision)))))

(defn replace!
  "Replace EXACT `:seon.agent.fs/find` text in a file — deterministic only.

   The safe anchored editor. The pure cascade (seon.agent.fs.match) tries,
   first hit wins: exact text occurring exactly `:seon.agent.fs/expected-count`
   times (default 1) → apply; the same inside the `:seon.agent.fs/near`
   `[from-line to-line]` window → apply; conservative line-ending /
   trailing-whitespace normalization (NEVER indentation) → apply. Anything
   else FAILS with line-numbered candidates and never guesses — an
   ambiguous find returns every occurrence, a not-found returns
   normalization near-misses. `:seon.agent.fs/find` and
   `:seon.agent.fs/replace` accept a plain string or a `#code` heredoc
   value. Pass the `:seon.agent.fs/file-sha` from your [[view]] to fence
   against a stale edit (mismatch → an ok?-false with the actual sha).

   Worked example — a `#code` heredoc carries raw foreign source (quotes,
   backslashes, regexes) with ZERO escaping:

     (replace! {:seon.agent.fs/path \"app.py\"
                :seon.agent.fs/find #code/python <<PY
     def f(x):
         return x
     PY
                :seon.agent.fs/replace #code/python <<PY
     def f(x):
         return x + 1
     PY
                })

   Success carries the new `:seon.agent.fs/file-sha`, `range-after`, the
   lines added/removed, and a line-numbered `excerpt` of the result — no
   re-read needed. Same write gate as [[write-file]] (granted root,
   `read-only?` false). Never throws — every failure is a value."
  {:malli/schema [:=> [:cat :seon.agent.fs/replace-request] :seon.agent.fs/anchored-response]}
  [{:seon.agent.fs/keys [path find replace expected-count all? near file-sha encoding]}]
  (let [encoding  (or encoding "utf-8")
        find-text (code/text find)
        repl-text (code/text replace)]
    (case (platform/host)
      :node (cond
              (int/read-only?)         (->anchored-fail (int/denied path "filesystem is read-only (:seon.agent.fs/read-only? true)"))
              (int/out-of-scope? path) (->anchored-fail (int/scope-denied path))
              (= "" find-text)         (anchored-msg path (str ":seon.agent.fs/find must be non-empty — "
                                                              "copy the EXACT anchor text from (seon.agent.fs/view …)."))
              :else
              (try
                (let [content (.readFileSync fs path encoding)
                      actual  (int/file-sha content)]
                  (if (and file-sha (not= file-sha actual))
                    (stale-file path actual file-sha)
                    (let [decision (match/decide
                                     (cond-> {:seon.agent.fs.match/content content
                                              :seon.agent.fs.match/find    find-text
                                              :seon.agent.fs.match/replace repl-text}
                                       expected-count (assoc :seon.agent.fs.match/expected-count expected-count)
                                       all?           (assoc :seon.agent.fs.match/all? all?)
                                       near           (assoc :seon.agent.fs.match/near near)))]
                      (if (= :apply (:seon.agent.fs.match/action decision))
                        (let [new-content (:seon.agent.fs.match/new-content decision)]
                          (.writeFileSync fs path new-content encoding)
                          (edit-success path new-content decision))
                        (cascade-fail path decision)))))
                (catch :default e (->anchored-fail (int/->err path e)))))
      :wasi (->anchored-fail (int/wasi-pending path "replace!")))))

(defn insert!
  "Insert `:seon.agent.fs/content` at a line boundary — exactly one anchor.

   Pass EXACTLY ONE of `:seon.agent.fs/after-line` / `:seon.agent.fs/before-line`
   (1-based). `after-line 0` prepends; `before-line (inc total)` appends;
   an out-of-range anchor FAILS with the file's real `:seon.agent.fs/total-lines`.
   `:seon.agent.fs/content` accepts a plain string or a `#code` heredoc
   value. Success carries the new `:seon.agent.fs/file-sha`, `range-after`
   (the inserted span), `lines-added`, and a line-numbered `excerpt`. Same
   write gate as [[write-file]]; never throws — failures are values."
  {:malli/schema [:=> [:cat :seon.agent.fs/insert-request] :seon.agent.fs/anchored-response]}
  [{:seon.agent.fs/keys [path content after-line before-line encoding]}]
  (let [encoding    (or encoding "utf-8")
        ins-text    (code/text content)
        has-after?  (some? after-line)
        has-before? (some? before-line)]
    (case (platform/host)
      :node (cond
              (int/read-only?)         (->anchored-fail (int/denied path "filesystem is read-only (:seon.agent.fs/read-only? true)"))
              (int/out-of-scope? path) (->anchored-fail (int/scope-denied path))
              (= has-after? has-before?)
              (anchored-msg path (str "pass EXACTLY ONE of :seon.agent.fs/after-line or "
                                      ":seon.agent.fs/before-line (a 1-based line number); got "
                                      (if has-after? "both" "neither") "."))
              :else
              (try
                (let [content-str (.readFileSync fs path encoding)
                      lines       (match/content-lines content-str)
                      total       (count lines)
                      trailing?   (str/ends-with? content-str "\n")
                      idx         (if has-after? after-line (dec before-line))]
                  (if (or (< idx 0) (> idx total))
                    (anchored-msg path
                                  (str (if has-after? ":seon.agent.fs/after-line " ":seon.agent.fs/before-line ")
                                       (if has-after? after-line before-line)
                                       " is out of range — the file has " total
                                       " lines (after-line 0.." total ", before-line 1.." (inc total) ").")
                                  {:seon.agent.fs/total-lines total})
                    (let [ins-lines   (match/content-lines ins-text)
                          new-lines   (-> (subvec lines 0 idx)
                                          (into ins-lines)
                                          (into (subvec lines idx)))
                          new-content (cond-> (str/join "\n" new-lines)
                                        (and trailing? (seq new-lines)) (str "\n"))
                          range       [(inc idx) (+ idx (count ins-lines))]]
                      (.writeFileSync fs path new-content encoding)
                      {:seon.agent.fs/ok?           true
                       :seon.agent.fs/path          path
                       :seon.agent.fs/file-sha      (int/file-sha new-content)
                       :seon.agent.fs/range-after   range
                       :seon.agent.fs/lines-added   (count ins-lines)
                       :seon.agent.fs/lines-removed 0
                       :seon.agent.fs/excerpt       (match/preview new-lines range)})))
                (catch :default e (->anchored-fail (int/->err path e)))))
      :wasi (->anchored-fail (int/wasi-pending path "insert!")))))
