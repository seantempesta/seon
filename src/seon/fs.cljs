(ns seon.fs
  "Local-filesystem capability — the agent's eyes + hands on the
   user's machine. Two backends in V0.5:

     :node — `node:fs/promises` directly. Full filesystem access; no
             sandbox. This is the Lane A dev path AND the V1+
             production path now that Sean's pulled back from
             multi-user JVM-server isolation (2026-05-19 — V1 will
             be 100% CLJS in Tauri).

     :wasi — stubbed; lands in Lane B's B-5 when wasi:filesystem/preopens
             gets wired (spec-05 §9.2). Until then, agents running under
             wasm-rquickjs see `:seon.fs/ok? false` with a wasi-pending
             error message.

   ## House-rule API

   Map-in / map-out per seon's CLAUDE.md data rules. Every fn:
     • takes one map argument with fully-namespaced keys
     • returns one map with `:seon.fs/ok?` discriminator
     • never throws — errors land as `:seon.fs/error` strings

   The schema keeps the caller's hands clean: `(if (:seon.fs/ok? r)
   (:seon.fs/content r) (handle-error (:seon.fs/error r)))`.

   ## Worked examples

     (seon.fs/read-file  {:seon.fs/path \"/Users/sean/.zshrc\"})
     (seon.fs/write-file {:seon.fs/path \"/tmp/note.txt\"
                          :seon.fs/content \"hello\"})
     (seon.fs/list-dir   {:seon.fs/path \"/Users/sean\"})
     (seon.fs/stat       {:seon.fs/path \"/Users/sean/.zshrc\"})

   ## V0.5 + V1+ posture (per Sean's 2026-05-19 direction)

   Plain Node = no sandbox. The agent CAN read `~/Documents/whatever`,
   write to `~/seon-dev-share/`, etc. The trust model is 'the agent
   is part of the user's process'; capability prompts come later (V0.6+
   when Tauri's native dialog ships with the wasmtime sandbox)."
  (:require
    ["node:fs" :as fs]
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
;;   (let [r (walk-dir {...})] (if (:seon.fs/ok? r) ...))
;;
;; `r` would bind to a Promise (not the resolved map), and the
;; (:seon.fs/ok? r) check returns nil → wrong branch. The fix is
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

(schema/register! :seon.fs/path     :string)
(schema/register! :seon.fs/encoding :string)
(schema/register! :seon.fs/content  :string)
(schema/register! :seon.fs/ok?      :boolean)
(schema/register! :seon.fs/error    :string)
(schema/register! :seon.fs/entries  [:vector :string])
(schema/register! :seon.fs/size     :int)
(schema/register! :seon.fs/dir?     :boolean)
(schema/register! :seon.fs/file?    :boolean)
(schema/register! :seon.fs/mtime    :any) ; js/Date — :inst varies across CLJS reader registries

(schema/register! :seon.fs/read-request
  [:map
   [:seon.fs/path     :string]
   [:seon.fs/encoding {:optional true} :string]])

(schema/register! :seon.fs/read-response
  [:map
   [:seon.fs/ok?     :boolean]
   [:seon.fs/path    :string]
   [:seon.fs/content {:optional true} :string]
   [:seon.fs/error   {:optional true} :string]])

(schema/register! :seon.fs/write-request
  [:map
   [:seon.fs/path     :string]
   [:seon.fs/content  :string]
   [:seon.fs/encoding {:optional true} :string]])

(schema/register! :seon.fs/write-response
  [:map
   [:seon.fs/ok?     :boolean]
   [:seon.fs/path    :string]
   [:seon.fs/error   {:optional true} :string]])

(schema/register! :seon.fs/list-request
  [:map
   [:seon.fs/path :string]])

(schema/register! :seon.fs/list-response
  [:map
   [:seon.fs/ok?     :boolean]
   [:seon.fs/path    :string]
   [:seon.fs/entries {:optional true} [:vector :string]]
   [:seon.fs/error   {:optional true} :string]])

(schema/register! :seon.fs/stat-request
  [:map
   [:seon.fs/path :string]])

(schema/register! :seon.fs/stat-response
  [:map
   [:seon.fs/ok?    :boolean]
   [:seon.fs/path   :string]
   [:seon.fs/size   {:optional true} :int]
   [:seon.fs/dir?   {:optional true} :boolean]
   [:seon.fs/file?  {:optional true} :boolean]
   [:seon.fs/mtime  {:optional true} :any]
   [:seon.fs/error  {:optional true} :string]])

;; ============================================================
;; Internal — error envelope helper
;; ============================================================

(defn- ->err
  "Build a uniform error response for `path` from caught exception `e`."
  [path e]
  {:seon.fs/ok?   false
   :seon.fs/path  path
   :seon.fs/error (or (some-> e .-message) (str e))})

(defn- wasi-pending
  "Stub response for the :wasi branch until Lane B B-5 wires
   wasi:filesystem/preopens."
  [path op]
  {:seon.fs/ok?   false
   :seon.fs/path  path
   :seon.fs/error (str ":wasi backend not implemented — " op
                       " requires wasi:filesystem/preopens (spec-05 §9.2). "
                       "Run under :node for V0.5.")})

;; ============================================================
;; Public API — map-in / Promise-out
;; ============================================================

(defn read-file
  "Read a file (sync). Returns:
     {:seon.fs/ok? true  :seon.fs/path <p> :seon.fs/content <s>}    ; ok
     {:seon.fs/ok? false :seon.fs/path <p> :seon.fs/error   <s>}    ; fail

   Default encoding `utf-8`."
  {:malli/schema [:=> [:cat :seon.fs/read-request] :seon.fs/read-response]}
  [{:seon.fs/keys [path encoding] :or {encoding "utf-8"}}]
  (case (platform/host)
    :node (try
            (let [content (.readFileSync fs path encoding)]
              {:seon.fs/ok?     true
               :seon.fs/path    path
               :seon.fs/content content})
            (catch :default e (->err path e)))
    :wasi (wasi-pending path "read-file")))

(defn write-file
  "Write `:seon.fs/content` to `:seon.fs/path` (sync). Overwrites.
   Returns:
     {:seon.fs/ok? true :seon.fs/path <p>}                          ; ok
     {:seon.fs/ok? false :seon.fs/path <p> :seon.fs/error <s>}       ; fail"
  {:malli/schema [:=> [:cat :seon.fs/write-request] :seon.fs/write-response]}
  [{:seon.fs/keys [path content encoding] :or {encoding "utf-8"}}]
  (case (platform/host)
    :node (try
            (.writeFileSync fs path content encoding)
            {:seon.fs/ok?  true
             :seon.fs/path path}
            (catch :default e (->err path e)))
    :wasi (wasi-pending path "write-file")))

(defn list-dir
  "List directory entries (filenames only, no recursion) — sync."
  {:malli/schema [:=> [:cat :seon.fs/list-request] :seon.fs/list-response]}
  [{:seon.fs/keys [path]}]
  (case (platform/host)
    :node (try
            (let [arr (.readdirSync fs path)]
              {:seon.fs/ok?     true
               :seon.fs/path    path
               :seon.fs/entries (vec arr)})
            (catch :default e (->err path e)))
    :wasi (wasi-pending path "list-dir")))

(defn stat
  "Stat a path (sync). Returns size, mtime, dir?/file? booleans."
  {:malli/schema [:=> [:cat :seon.fs/stat-request] :seon.fs/stat-response]}
  [{:seon.fs/keys [path]}]
  (case (platform/host)
    :node (try
            (let [s (.statSync fs path)]
              {:seon.fs/ok?    true
               :seon.fs/path   path
               :seon.fs/size   (.-size s)
               :seon.fs/dir?   (.isDirectory s)
               :seon.fs/file?  (.isFile s)
               :seon.fs/mtime  (.-mtime s)})
            (catch :default e (->err path e)))
    :wasi (wasi-pending path "stat")))

;; ============================================================
;; Conveniences for the agent prompt — short helpers that match the
;; worked-example shapes in seon.render.default/what-you-can-do.
;; ============================================================

(defn exists?
  "True/false convenience — checks via stat. Soft-fails to false on
   any error."
  {:malli/schema [:=> [:cat :seon.fs/stat-request] :boolean]}
  [req]
  (:seon.fs/ok? (stat req)))

(defn home-dir
  "Convenience — returns the user's home directory. :node only."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (case (platform/host)
    :node (or (.. js/process -env -HOME)
              (.. js/process -env -USERPROFILE))
    :wasi nil))

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

(schema/register! :seon.fs/match-ext   :string)
(schema/register! :seon.fs/skip-hidden :boolean)
(schema/register! :seon.fs/max-results :int)

(schema/register! :seon.fs/walk-request
  [:map
   [:seon.fs/path        :string]
   [:seon.fs/match-ext   {:optional true} :string]
   [:seon.fs/skip-hidden {:optional true} :boolean]
   [:seon.fs/max-results {:optional true} :int]])

(schema/register! :seon.fs/walk-response
  [:map
   [:seon.fs/ok?         :boolean]
   [:seon.fs/path        :string]
   [:seon.fs/entries     {:optional true} [:vector :string]]
   [:seon.fs/total-found {:optional true} :int]
   [:seon.fs/truncated?  {:optional true} :boolean]
   [:seon.fs/error       {:optional true} :string]])

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
  "Recursively walk `:seon.fs/path` (sync), return matching files.
   Returns:
     {:seon.fs/ok? true :seon.fs/path <p>
      :seon.fs/entries [<absolute-path>...]
      :seon.fs/total-found <int>
      :seon.fs/truncated? <bool>}
     {:seon.fs/ok? false :seon.fs/path <p> :seon.fs/error <s>}

   Opts (all optional):
     :seon.fs/match-ext   — e.g. \".md\" — only files ending in this
     :seon.fs/skip-hidden — skip files starting with \".\" (default true)
     :seon.fs/max-results — cap (default 500); `:truncated? true` if hit

   Example:
     (seon.fs/walk-dir {:seon.fs/path \"/Users/you/src/your-project\"
                        :seon.fs/match-ext \".md\"})"
  {:malli/schema [:=> [:cat :seon.fs/walk-request] :seon.fs/walk-response]}
  [{:seon.fs/keys [path match-ext skip-hidden max-results]
    :or {skip-hidden true max-results 5000}}]
  (case (platform/host)
    :node (try
            (let [pred       (if match-ext
                               #(str/ends-with? % match-ext)
                               (constantly true))
                  !out       (atom [])
                  !truncated (atom false)]
              (walk-dir-recursive! path pred skip-hidden max-results
                                   !out !truncated)
              {:seon.fs/ok?         true
               :seon.fs/path        path
               :seon.fs/entries     @!out
               :seon.fs/total-found (count @!out)
               :seon.fs/truncated?  @!truncated})
            (catch :default e (->err path e)))
    :wasi (wasi-pending path "walk-dir")))
