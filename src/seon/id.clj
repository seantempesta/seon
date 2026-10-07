(ns seon.id
  "One identity entry: `id` hashes the printed data, or creates a fresh
  event identity when called without data. Stable identities retain the
  supplied data's shape; callers choose their own identity parts."
  (:require [clojure.string :as str])
  (:import [java.io BufferedWriter OutputStream OutputStreamWriter StringWriter Writer]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn- hex
  "Lowercase hexadecimal of `bs` (babashka has no java.util.HexFormat)."
  {:malli/schema [:=> [:cat bytes?] :string]}
  [^bytes bs]
  (let [digits "0123456789abcdef"
        sb (StringBuilder. (* 2 (alength bs)))]
    (doseq [b bs]
      (let [v (bit-and (int b) 0xff)]
        (.append sb (.charAt digits (bit-shift-right v 4)))
        (.append sb (.charAt digits (bit-and v 0xf)))))
    (str sb)))

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
    (hex (.digest digester))))

(defn writer?
  "True for a `java.io.Writer`."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [value]
  (instance? Writer value))

(defn- print-canonical!
  "Print `data` onto `writer` under fixed print bindings, so a caller's
  `*print-length*`, `*print-meta*` or other print binding cannot change the
  text. `*print-namespace-maps*` is true because every stored identity was
  printed under clojure.main's REPL bindings, which set it.

  ITERATIVE: each open vector, seq, set, map or map entry is a frame on an
  explicit stack, so depth costs heap, never JVM stack. `pr` recursed once
  per level and threw `StackOverflowError` near 2,000 levels, which lost a
  10,000-deep evaluation's settlement (`seon.turn/a-deep-value-loses-the-run`).
  The text is `pr`'s, byte for byte: a value takes a frame only when
  `print-method` would dispatch it to one of Clojure's four standard
  collection methods, whose output the frame reproduces
  (core_print.clj:48-68,174-176,225-269,338-340); any other value is printed
  by its own `print-method`. The frame helpers are local: this loop is the
  hot path of every identity."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Canonical printing accepts arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}] [:fn seon.id/writer?]] :nil]}
  [data ^Writer writer]
  (binding [*out* writer *print-length* nil *print-level* nil *print-meta* false *print-dup* false
            *print-readably* true *print-namespace-maps* true]
    (let [vector-method (get-method print-method clojure.lang.IPersistentVector)
          seq-method (get-method print-method clojure.lang.ISeq)
          set-method (get-method print-method clojure.lang.IPersistentSet)
          map-method (get-method print-method clojure.lang.IPersistentMap)
          ;; `clojure.core/lift-ns` (core_print.clj:247-261)
          lift-ns (fn [m]
                    (loop [space nil
                           [[k v :as entry] & entries] (seq m)
                           kvs []]
                      (if entry
                        (when (and (qualified-ident? k) (or (nil? space) (= space (namespace k))))
                          (recur (namespace k) entries
                                 (conj kvs [(if (symbol? k) (symbol nil (name k)) (keyword nil (name k))) v])))
                        [space kvs])))
          ;; a frame is [sep close items started? entries?], advanced in place
          frame (fn [x]
                  (condp identical? (get-method print-method
                                                (let [t (get (meta x) :type)] (if (keyword? t) t (class x))))
                    vector-method (do (.write writer "[") (object-array [" " "]" (seq x) false false]))
                    seq-method (do (.write writer "(") (object-array [" " ")" (seq x) false false]))
                    set-method (do (.write writer "#{") (object-array [" " "}" (seq x) false false]))
                    map-method (let [[space kvs] (lift-ns x)]
                                 (.write writer (if space (str "#:" space "{") "{"))
                                 (object-array [", " "}" (seq (if space kvs x)) false true]))
                    nil))
          stack (java.util.ArrayDeque.)]
      (loop [x data entry? false visit? true]
        (if visit?
          (if-some [open (if entry? (object-array [" " "" (seq x) false false]) (frame x))]
            (do (.push stack open) (recur nil false false))
            (do (print-method x writer) (recur nil false false)))
          (when-some [^objects top (.peek stack)]
            (if-some [items (aget top 2)]
              (do (when (aget top 3) (.write writer ^String (aget top 0)))
                  (aset top 2 (next items))
                  (aset top 3 true)
                  (recur (first items) (aget top 4) true))
              (do (.write writer ^String (aget top 1))
                  (.pop stack)
                  (recur nil false false))))))))
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
    (hex (.digest digester))))

(defn id
  "SHA-256 of the canonical EDN text of data ([[printed]]), truncated to n
  characters (default 12). With no arguments, mint a fresh event identity.
  Data is deliberately polymorphic: scalars and collections are hashed
  exactly as printed, so equal maps printed in different entry order differ.

  ## Costs

  - Streams the print into the digest: memory is constant in the value's
    size, time is linear in its printed size. Callers hash small values: on
    default 2026-10-07, 917,027 calls averaged 21 us (max 23 ms); the largest
    input found, the schema projection's 3,389 forms, prints 641,443
    characters in 6.6 ms. A large derived value is hashed by composing its
    parts' digests, never by printing it (see
    `seon.test.runner/reach-component-digests`)."
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
