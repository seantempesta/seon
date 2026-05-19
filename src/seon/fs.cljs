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
    ["node:fs/promises" :as fsp]
    [clojure.string :as str]
    [seon.platform :as platform]
    [seon.schema :as schema]))

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

(defn ^:async read-file
  "Read a file. Returns a Promise resolving to:
     {:seon.fs/ok? true  :seon.fs/path <p> :seon.fs/content <s>}    ; ok
     {:seon.fs/ok? false :seon.fs/path <p> :seon.fs/error   <s>}    ; fail

   Default encoding `utf-8`."
  {:malli/schema [:=> [:cat :seon.fs/read-request] :any]}
  [{:seon.fs/keys [path encoding] :or {encoding "utf-8"}}]
  (case (platform/host)
    :node (try
            (let [content (await (.readFile fsp path encoding))]
              {:seon.fs/ok?     true
               :seon.fs/path    path
               :seon.fs/content content})
            (catch :default e (->err path e)))
    :wasi (wasi-pending path "read-file")))

(defn ^:async write-file
  "Write `:seon.fs/content` to `:seon.fs/path`. Overwrites. Returns a
   Promise resolving to:
     {:seon.fs/ok? true :seon.fs/path <p>}                          ; ok
     {:seon.fs/ok? false :seon.fs/path <p> :seon.fs/error <s>}       ; fail"
  {:malli/schema [:=> [:cat :seon.fs/write-request] :any]}
  [{:seon.fs/keys [path content encoding] :or {encoding "utf-8"}}]
  (case (platform/host)
    :node (try
            (await (.writeFile fsp path content encoding))
            {:seon.fs/ok?  true
             :seon.fs/path path}
            (catch :default e (->err path e)))
    :wasi (wasi-pending path "write-file")))

(defn ^:async list-dir
  "List directory entries (filenames only, no recursion). Returns a
   Promise resolving to:
     {:seon.fs/ok? true  :seon.fs/path <p> :seon.fs/entries [<s>...]}
     {:seon.fs/ok? false :seon.fs/path <p> :seon.fs/error   <s>}"
  {:malli/schema [:=> [:cat :seon.fs/list-request] :any]}
  [{:seon.fs/keys [path]}]
  (case (platform/host)
    :node (try
            (let [arr (await (.readdir fsp path))]
              {:seon.fs/ok?     true
               :seon.fs/path    path
               :seon.fs/entries (vec arr)})
            (catch :default e (->err path e)))
    :wasi (wasi-pending path "list-dir")))

(defn ^:async stat
  "Stat a path. Returns a Promise resolving to:
     {:seon.fs/ok? true :seon.fs/path <p>
      :seon.fs/size <int> :seon.fs/dir? <bool>
      :seon.fs/file? <bool> :seon.fs/mtime <js/Date>}
     {:seon.fs/ok? false :seon.fs/path <p> :seon.fs/error <s>}"
  {:malli/schema [:=> [:cat :seon.fs/stat-request] :any]}
  [{:seon.fs/keys [path]}]
  (case (platform/host)
    :node (try
            (let [s (await (.stat fsp path))]
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

(defn ^:async exists?
  "True/false convenience — checks via stat. Soft-fails to false on
   any error. Returns a Promise of boolean."
  {:malli/schema [:=> [:cat :seon.fs/stat-request] :any]}
  [{:seon.fs/keys [path] :as req}]
  (let [r (await (stat req))]
    (:seon.fs/ok? r)))

(defn home-dir
  "Convenience — returns the user's home directory. :node only."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
  []
  (case (platform/host)
    :node (or (.. js/process -env -HOME)
              (.. js/process -env -USERPROFILE))
    :wasi nil))
