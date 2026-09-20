(ns ^{:seon.ns/context-relevant? true} my.fs
  "Read, write, inspect, and find files with bounded results."
  (:refer-clojure :exclude [read])
  (:require [seon.fs :as fs]
            [clojure.test.check.generators :as gen]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))
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
  (schema/register-core-predicate! 'seon.fs/content? fs/content?))

(defonce ^:private _write-precondition-predicate
  (schema/register-core-predicate!
   'seon.fs/write-precondition? fs/write-precondition?))

;; The predicates must exist before seon.effect loads the complete schema
;; population, whose :my.fs/content declaration resolves these Vars.
(require '[seon.effect :as effect])

(schema.edn/load! {})

(defn read
  "Read a bounded window of one file.

  Returns :my.fs/text (or :my.fs/bytes), :my.fs/window-digest,
  :my.fs/file-bytes, :my.fs/bytes-read and :my.fs/eof?. The whole-file
  :my.fs/digest appears only for a complete read. Page with
  :my.fs/byte-offset and :my.fs/max-bytes.

  Example:
  (my.fs/read {:my.fs/path \"AGENTS.md\" :my.fs/max-bytes 1024})"
  {:malli/schema
   [:=> [:cat :my.fs/read-request]
    [:or :my.fs/read-result :my.fs/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/read}
  [request]
  (effect/request! #'read request))

(defn write!
  "Write one file only if its content precondition holds.

  Supply :my.fs/path, :my.fs/content containing one text/bytes/blob source,
  and :my.fs/precondition containing expected absence or the prior digest.
  Returns :my.fs/path, :my.fs/after-digest, :my.fs/bytes-written and
  :my.fs/changed?.

  Example:
  (my.fs/write! {:my.fs/path \"example.txt\"
                 :my.fs/content {:my.fs/text \"Verified.\"}
                 :my.fs/precondition {:my.fs/expected-absence? true}})"
  {:malli/schema
   [:=> [:cat :my.fs/write-request]
    [:or :my.fs/write-result :my.fs/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/write}
  [request]
  (effect/request! #'write! request))

(defn glob
  "Find paths beneath one root without following symbolic links.

  Returns :my.fs/paths, :my.fs/examined, :my.fs/returned and :my.fs/complete?.
  Supply :my.fs/max-depth and :my.fs/max-results to bound the search.

  Example:
  (my.fs/glob {:my.fs/root \".\" :my.fs/pattern \"*.md\"
               :my.fs/max-depth 1 :my.fs/max-results 10})"
  {:malli/schema
   [:=> [:cat :my.fs/glob-request]
    [:or :my.fs/glob-result :my.fs/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/glob}
  [request]
  (effect/request! #'glob request))

(defn stat
  "Inspect one path without following symbolic links.

  Returns :my.fs/path, :my.fs/regular-file?, :my.fs/directory? and
  :my.fs/symbolic-link?, with :my.fs/byte-size and :my.fs/modified-at
  when available.

  Example:
  (my.fs/stat {:my.fs/path \"AGENTS.md\"})"
  {:malli/schema
   [:=> [:cat :my.fs/stat-request]
    [:or :my.fs/stat-result :my.fs/error]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/stat}
  [request]
  (effect/request! #'stat request))
