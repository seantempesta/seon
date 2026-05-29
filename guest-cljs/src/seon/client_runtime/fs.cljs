(ns seon.client-runtime.fs
  "Node-style filesystem access for guests, routed through `node:fs` (which
   wasm-rquickjs's built-in `fs` polyfill maps onto wasi:filesystem preopens
   configured by the Rust host).

   Two default mounts every guest gets (see `client-runtime/host/src/main.rs`
   `build_mounts_for_session`):

   - `/seon-src`  — host `~/src/seon`, READ-ONLY. Browse / read project
                    sources to learn about Seon.
   - `/scratch`   — host `data/sessions/<name>/scratch`, READ-WRITE. The
                    agent's own working area; survives session restart.

   Writes to a read-only mount throw an error whose `:errno` is `EROFS`
   (or `EACCES` depending on wasm-rquickjs's polyfill mapping); callers can
   pattern-match via `(:errno (ex-data e))`.

   All paths are POSIX-style absolute paths inside the guest namespace
   (`/seon-src/CLAUDE.md`, not `~/src/seon/CLAUDE.md`)."
  (:refer-clojure :exclude [read exists?])
  (:require [clojure.string :as str]))

(defn- fs
  "Lazy resolve of node:fs. The guest's `js/require` is wasm-rquickjs's
   built-in fs polyfill (see guest-cljs/build (generated) src/builtin/fs.rs).
   Defonce'd so repeated calls don't pay the resolve cost."
  []
  (js/require "node:fs"))

(defn- ex->error [op path e]
  (let [msg   (or (some-> e .-message) (str e))
        errno (or (some-> e .-code) "EUNKNOWN")]
    (ex-info (str "fs/" op " " path " failed: " msg)
             {:seon.fs/op    op
              :seon.fs/path  path
              :errno         errno
              :seon.fs/error msg})))

(defn read-file
  "Read a UTF-8 file. Returns string."
  [path]
  (try
    (.readFileSync (fs) path "utf-8")
    (catch :default e
      (throw (ex->error "read-file" path e)))))

(defn read-binary
  "Read a file as binary. Returns a Uint8Array (Node Buffer is a Uint8Array)."
  [path]
  (try
    (.readFileSync (fs) path)
    (catch :default e
      (throw (ex->error "read-binary" path e)))))

(defn exists?
  "True if `path` exists. Never throws."
  [path]
  (try
    (.existsSync (fs) path)
    (catch :default _ false)))

(defn stat
  "Stat-like map: {:size :file? :dir? :symlink? :mtime-ms :mode}. Returns nil
   on ENOENT, throws on other errors."
  [path]
  (try
    (let [s (.statSync (fs) path)]
      {:size      (.-size s)
       :file?     (.isFile s)
       :dir?      (.isDirectory s)
       :symlink?  (.isSymbolicLink s)
       :mtime-ms  (.-mtimeMs s)
       :mode      (.-mode s)})
    (catch :default e
      (if (= (some-> e .-code) "ENOENT")
        nil
        (throw (ex->error "stat" path e))))))

(defn ls
  "List directory entries. Returns vector of
   `{:name string :type :file|:dir|:symlink|:other :size long}`.
   Stat'ing each entry to populate :type + :size — for very large
   directories prefer `(.readdirSync (fs) path)` directly."
  [path]
  (try
    (let [names (.readdirSync (fs) path)]
      (mapv (fn [n]
              (let [full (if (str/ends-with? path "/")
                           (str path n)
                           (str path "/" n))
                    s    (stat full)]
                {:name n
                 :type (cond
                         (nil? s)        :unknown
                         (:dir? s)       :dir
                         (:symlink? s)   :symlink
                         (:file? s)      :file
                         :else           :other)
                 :size (:size s 0)}))
            names))
    (catch :default e
      (throw (ex->error "ls" path e)))))

(defn list-tree
  "Recursive listing under `path`. Returns a vec of file paths (strings)
   for every regular file in the subtree (depth-first, not lazy because
   wasm-rquickjs doesn't love lazy seqs across JS boundaries).

   `:max-depth` caps recursion depth (default 6).
   `:include-hidden?` defaults false."
  ([path] (list-tree path {}))
  ([path {:keys [max-depth include-hidden?]
          :or   {max-depth 6 include-hidden? false}}]
   (let [out (volatile! [])]
     (letfn [(walk! [p depth]
               (when (<= depth max-depth)
                 (doseq [{:keys [name type]} (ls p)]
                   (when (or include-hidden? (not (str/starts-with? name ".")))
                     (let [full (if (str/ends-with? p "/")
                                  (str p name)
                                  (str p "/" name))]
                       (case type
                         :file (vswap! out conj full)
                         :dir  (walk! full (inc depth))
                         nil))))))]
       (walk! path 0)
       @out))))

(defn write-file!
  "Write UTF-8 string to `path`. Will throw with `:errno` set on a
   read-only mount."
  [path content]
  (try
    (.writeFileSync (fs) path content "utf-8")
    :ok
    (catch :default e
      (throw (ex->error "write-file!" path e)))))

(defn append-file!
  "Append UTF-8 string to `path`."
  [path content]
  (try
    (.appendFileSync (fs) path content "utf-8")
    :ok
    (catch :default e
      (throw (ex->error "append-file!" path e)))))

(defn mkdir!
  "Create directory `path`. Pass `:recursive? true` for `mkdir -p` behavior."
  ([path] (mkdir! path {}))
  ([path {:keys [recursive?] :or {recursive? true}}]
   (try
     (.mkdirSync (fs) path #js {:recursive recursive?})
     :ok
     (catch :default e
       (throw (ex->error "mkdir!" path e))))))

(defn unlink!
  "Delete file `path`. Throws on RO mounts."
  [path]
  (try
    (.unlinkSync (fs) path)
    :ok
    (catch :default e
      (throw (ex->error "unlink!" path e)))))
