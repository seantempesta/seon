(ns seon.id
  "One identity entry: `id` hashes the printed data, or creates a fresh
  event identity when called without data. Stable identities retain the
  supplied data's shape; callers choose their own identity parts."
  (:require [clojure.string :as str])
  (:import [java.io BufferedWriter OutputStream OutputStreamWriter StringWriter Writer]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util HexFormat]))

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
    (.formatHex (HexFormat/of) (.digest digester))))

(defn writer?
  "True for a `java.io.Writer`."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? Writer value))

(defn- print-canonical!
  "Print `data` onto `writer` under fixed print bindings, so a caller's
  `*print-length*`, `*print-meta*` or other print binding cannot change the
  text. `*print-namespace-maps*` is true because every stored identity was
  printed under clojure.main's REPL bindings, which set it."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Canonical printing accepts arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}] [:fn seon.id/writer?]] :nil]}
  [data ^Writer writer]
  (binding [*out* writer *print-length* nil *print-level* nil *print-meta* false *print-dup* false
            *print-readably* true *print-namespace-maps* true]
    (pr data))
  nil)

(defn printed
  "The canonical EDN text of `data`: the one fixed-binding print that
  identities hash and that storage keeps as text.

  ## Costs

  - Linear in the printed size, and it holds the whole text: a caller that
    only needs an identity uses [[id]], which streams the same characters
    into the digest without building the string."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes Clojure's printed representation of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}]] :string]}
  [data]
  (let [writer (StringWriter.)]
    (print-canonical! data writer)
    (str writer)))

(defn- printed-digest
  "SHA-256 hex of the UTF-8 bytes of `(printed data)`, printed straight into
  the digest: no string or byte array of the whole text is built."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes Clojure's printed representation of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 64 :max 64}]]}
  [data]
  (let [digester (MessageDigest/getInstance "SHA-256")
        ;; The encoder, not a per-write getBytes, so a surrogate pair split
        ;; across two writes encodes exactly as String.getBytes would.
        ;; A proxy, not java.security.DigestOutputStream: the operator loads
        ;; this namespace in babashka, which has no DigestOutputStream class.
        sink (proxy [OutputStream] []
               (write
                 ([b] (if (bytes? b)
                        (.update digester ^bytes b)
                        (.update digester (unchecked-byte b))))
                 ([^bytes bs off len] (.update digester bs (int off) (int len)))))
        writer (BufferedWriter. (OutputStreamWriter. ^OutputStream sink StandardCharsets/UTF_8) 8192)]
    (print-canonical! data writer)
    (.flush writer)
    (.formatHex (HexFormat/of) (.digest digester))))

(defn id
  "SHA-256 of the canonical EDN text of data ([[printed]]), truncated to n
  characters (default 12). With no arguments, mint a fresh event identity.
  Data is deliberately polymorphic: scalars and collections are hashed
  exactly as printed, so equal maps printed in different entry order differ.

  ## Costs

  - Streams the print into the digest: memory is constant in the value's
    size, time is linear in its printed size. The 2,387 reach-digest inputs
    of default's tests (about 600 MB printed) take 8.7 s either way;
    streaming cut allocation from 17.4 GB to 13.4 GB (2026-09-28)."
  {:malli/schema
   [:function [:=> [:cat] [:string {:min 12, :max 12}]] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes Clojure's printed representation of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 12, :max 12}]] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes Clojure's printed representation of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}] [:int {:min 1, :max 64}]] [:string {:min 1, :max 64}]]]}
  ([] (id (random-uuid)))
  ([data] (id data default-length))
  ([data n]
   (subs (printed-digest data) 0 n)))

(defn digest
  "The stable id of a thing that IS its `parts`: `length` hex characters of
  the SHA-256 over their ordered, fixed-binding `pr-str`."
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
