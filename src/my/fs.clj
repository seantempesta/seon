(ns my.fs
  "Read, write, inspect, and find files with bounded results."
  (:refer-clojure :exclude [read])
  (:require [clojure.test.check.generators :as gen]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(defn content?
  "Whether a value names exactly one file-content source.

  Takes a value and returns a boolean. This predicate validates text, bytes,
  or blob-digest content for `write`."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (= 1
          (count
           (filter #(contains? value %)
                   [:my.fs/text :my.fs/bytes :seon.blob/digest])))))

(defn write-precondition?
  "Whether a value names exactly one write precondition.

  Takes a value and returns a boolean. This predicate validates an expected
  absence or expected digest for `write`."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (and (map? value)
       (= 1
          (count
           (filter #(contains? value %)
                   [:my.fs/expected-absence? :my.fs/expected-digest])))))

(def ^:private digest-generator
  (gen/fmap #(apply str %)
            (gen/vector (gen/elements (seq "0123456789abcdef")) 64)))

(def content-generator
  (gen/one-of
   [(gen/fmap (fn [text] {:my.fs/text text}) gen/string)
    (gen/fmap (fn [octets] {:my.fs/bytes octets})
              (gen/vector (gen/choose 0 255)))
    (gen/fmap (fn [digest] {:seon.blob/digest digest})
              digest-generator)]))

(def write-precondition-generator
  (gen/one-of
   [(gen/return {:my.fs/expected-absence? true})
    (gen/fmap (fn [digest] {:my.fs/expected-digest digest})
              digest-generator)]))

(defonce ^:private _content-predicate
  (schema/register-core-predicate! 'my.fs/content? content?))

(defonce ^:private _write-precondition-predicate
  (schema/register-core-predicate!
   'my.fs/write-precondition? write-precondition?))

;; The predicates must exist before seon.effect loads the complete schema
;; population, whose :my.fs/content declaration resolves these Vars.
(require '[seon.effect :as effect])

(schema.edn/load! {})

(defn read
  "Read a bounded window of one file, whatever the file's size.

  Takes `:my.fs/path` plus optional `:my.fs/byte-offset`, `:my.fs/max-bytes`,
  and `:my.fs/encoding` (`:utf-8` or `:bytes`). Returns `:my.fs/text` or
  `:my.fs/bytes` with `:my.fs/window-digest`, `:my.fs/file-bytes`,
  `:my.fs/bytes-read`, and `:my.fs/eof?`, or a flat error. The whole-file
  `:my.fs/digest` is present only when the window WAS the whole file. The
  window is what is read, so the cost and the bound are the window: use it
  before editing, or to page through a file too large to take whole."
  {:malli/schema
   [:=> [:cat :my.fs/read-request]
    [:or :my.fs/read-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/read}
  [request]
  (effect/request! #'read request))

(defn write
  "Write one file only if its content precondition holds.

  Takes a path, one text/bytes/blob content source, and an expected absence or
  digest. Returns the write summary or a flat error. Use it for atomic,
  stale-safe file replacement."
  {:malli/schema
   [:=> [:cat :my.fs/write-request]
    [:or :my.fs/write-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/write}
  [request]
  (effect/request! #'write request))

(defn glob
  "Find paths beneath one root without following symbolic links.

  Takes a root, pattern, and optional depth/result bounds. Returns matching
  paths with examined/returned counts, or a flat error. Use it to discover
  files before reading them."
  {:malli/schema
   [:=> [:cat :my.fs/glob-request]
    [:or :my.fs/glob-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/glob}
  [request]
  (effect/request! #'glob request))

(defn stat
  "Inspect one path without following a symbolic link.

  Takes a path and returns its file, directory, link, size, and modification
  facts or a flat error. Use it to identify a path before another operation."
  {:malli/schema
   [:=> [:cat :my.fs/stat-request]
    [:or :my.fs/stat-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/stat}
  [request]
  (effect/request! #'stat request))
