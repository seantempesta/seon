(ns seon.edn
  "The EDN text codec: the text a value is printed as where text is wanted (a form's
  contract, a schema's source, a stable sort key) and the value read back. A stored value
  is plain data (`:db.type/data`, docs/storage.md#plain-values), not this text. It requires no Seon namespace, so seon.error, seon.config and
  seon.contract (which load first) use it like every other caller; its functions carry
  their schemas as metadata, armed at boot."
  (:require [clojure.edn :as edn]))

(defn encode
  "`v` as EDN text that reads back: no print limits, no metadata."
  {:malli/schema [:=> [:cat :any] :string]}
  [v] (binding [*print-length* nil *print-level* nil *print-meta* false] (pr-str v)))

(defn decode
  "The value `encode` printed."
  {:malli/schema [:=> [:cat :string] :any]}
  [s] (edn/read-string s))

(defn digest
  "The first 16 hex digits of the SHA-256 of s: a content identity (docs/source.md#a-result-is-a-function-of-its-digest). The one hash of a string; hasch is for store ids."
  {:malli/schema [:=> [:cat :string] [:string {:min 16 :max 16}]]}
  [s]
  ;; the first 8 bytes as one long in hex, left-padded: the same digits as each byte's two, with no per-byte format
  (let [b (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String s))
        h (Long/toHexString (.getLong (java.nio.ByteBuffer/wrap b)))]
    (str (subs "0000000000000000" (count h)) h)))

(defn id
  "A name made of hex text h (a digest, a hash), safe in every Clojure reader: the same characters, its first hex
  digit 0-f written as a letter a-p, so it reads as a symbol, a keyword, a namespace segment and a qualified name (a
  name that starts with a digit is no symbol to Clojure's reader, which 65% of raw digests do). One-to-one, so it
  names what the hex names. See docs/storage.md#ids."
  {:malli/schema [:=> [:cat [:re "^[0-9a-f]+$"]] [:re "^[a-p][0-9a-f]*$"]]}
  [h] (str (char (+ (int \a) (Character/digit (.charAt ^String h 0) 16))) (subs h 1)))
