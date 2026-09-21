(ns seon.id
  "One identity entry: `id` hashes the pr-str of data, or creates a fresh
  event identity when called without data. Stable identities retain the
  supplied data's shape; callers choose their own identity parts."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]))

(def default-length
  "Default number of lowercase hexadecimal characters in an identity."
  12)

(def evaluation-length
  "Hex characters an evaluation id keeps: 48 bits, unique across every
  cluster one JVM will ever hold, short enough to read in a prompt."
  default-length)

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

(defn id
  "SHA-256 of (pr-str data), truncated to n characters (default 12).
  With no arguments, mint a fresh event identity. Data is deliberately
  polymorphic: scalars and collections are hashed exactly as printed."
  {:malli/schema
   [:function [:=> [:cat] [:string {:min 12, :max 12}]] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes Clojure's printed representation of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 12, :max 12}]] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes Clojure's printed representation of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}] [:int {:min 1, :max 64}]] [:string {:min 1, :max 64}]]]}
  ([] (id (random-uuid)))
  ([data] (id data default-length))
  ([data n]
   (subs (sha-256 [(.getBytes ^String (pr-str data) "UTF-8")]) 0 n)))

(defn digest
  "The stable id of a thing that IS its `parts`: `length` hex characters of
  the SHA-256 over their ordered `pr-str`."
  {:malli/schema [:=> [:cat [:int {:min 1, :max 64}] [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes Clojure's printed representation of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:string {:min 1, :max 64}]]}
  [length parts]
  (id (vec parts) length))

(defn symbol-in
  "The readable symbol `ns/<letter><id>` for an id — a Clojure symbol may
  not begin with a digit, so one letter leads. `(symbol-in \"result\" \\e id)`."
  {:malli/schema [:=> [:cat :string :seon.id/character [:string {:min 1}]]
                  :qualified-symbol]}
  [ns letter id]
  (symbol ns (str letter id)))

(defn evaluation
  "The id of one evaluation: ordinal `ordinal` of turn `turn-id` on the
  branch `branch-id`. The two-argument arity takes a turn identity that
  already includes its branch. Its handle is `(symbol-in \"result\" \\e id)`."
  {:malli/schema
   [:function
    [:=> [:cat :string [:int {:min 0}]] [:string {:min 12 :max 12}]]
    [:=> [:cat :string :string [:int {:min 0}]] [:string {:min 12 :max 12}]]]}
  ([turn-id ordinal]
   (digest evaluation-length [turn-id ordinal]))
  ([branch-id turn-id ordinal]
   (digest evaluation-length [branch-id turn-id ordinal])))

(defn valid?
  "Whether `id` is `length` lowercase hex characters."
  {:malli/schema [:=> [:cat [:int {:min 1}] :string] :boolean]}
  [length id]
  (and (= length (count id))
       (every? #(str/index-of "0123456789abcdef" %) id)))
