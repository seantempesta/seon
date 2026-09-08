(ns seon.id
  "THE ONE IDENTITY DERIVATION. Every stable identifier in Seon that is not
  a Datahike entity id comes from `digest`: the lowercase SHA-256 hex of the
  ordered `pr-str` of its identity parts, truncated to `length` hex
  characters. Same parts, same id, on every JVM and after every refork of
  the same data (owner ruling, 2026-09-08: ids are stable digests; no
  random generator, no per-family scheme, no second truncation anywhere).

  `random-uuid` remains for a genuinely fresh EVENT with no identity of its
  own (a run opened, a tab opened). A thing that IS its parts — an
  evaluation of ordinal N in turn T on branch B, a rendered value at a
  path — takes `digest` of those parts, never a fresh random."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]))

(def evaluation-length
  "Hex characters an evaluation id keeps: 48 bits, unique across every
  cluster one JVM will ever hold, short enough to read in a prompt."
  12)

(defn sha-256
  "Lowercase SHA-256 hex digest of ordered byte arrays."
  {:malli/schema [:=> [:cat [:sequential [:fn clojure.core/bytes?]]]
                  [:string {:min 64 :max 64}]]}
  [byte-arrays]
  (let [digester (MessageDigest/getInstance "SHA-256")]
    (doseq [bytes byte-arrays]
      (.update digester ^bytes bytes))
    (apply str
           (map #(format "%02x" (bit-and 0xff %))
                (.digest digester)))))

(defn digest
  "The stable id of a thing that IS its `parts`: `length` hex characters of
  the SHA-256 over their ordered `pr-str`."
  {:malli/schema [:=> [:cat [:int {:min 1 :max 64}] [:sequential :any]]
                  [:string {:min 1 :max 64}]]}
  [length parts]
  (subs (sha-256 [(.getBytes ^String (pr-str (vec parts)) "UTF-8")])
        0 length))

(defn symbol-in
  "The readable symbol `ns/<letter><id>` for an id — a Clojure symbol may
  not begin with a digit, so one letter leads. `(symbol-in \"result\" \\e id)`."
  {:malli/schema [:=> [:cat :string char? [:string {:min 1}]] :qualified-symbol]}
  [ns letter id]
  (symbol ns (str letter id)))

(defn evaluation
  "The id of one evaluation: ordinal `ordinal` of turn `turn-id` on the
  branch `branch-id`. Its handle is `(symbol-in \"result\" \\e id)`."
  {:malli/schema [:=> [:cat :string :string [:int {:min 0}]]
                  [:string {:min 12 :max 12}]]}
  [branch-id turn-id ordinal]
  (digest evaluation-length [branch-id turn-id ordinal]))

(defn valid?
  "Whether `id` is `length` lowercase hex characters."
  {:malli/schema [:=> [:cat [:int {:min 1}] :string] :boolean]}
  [length id]
  (and (= length (count id))
       (every? #(str/index-of "0123456789abcdef" %) id)))
