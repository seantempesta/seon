(ns ^{:seon/babashka true} seon.id
  "One identity entry: `id` hashes the data's structure, or creates a fresh
  event identity when called without data. Stable identities retain the
  supplied data's shape; callers choose their own identity parts."
  (:require [clojure.string :as str])
  (:import [java.io StringWriter Writer]
           [java.math BigDecimal BigInteger]
           [java.nio ByteBuffer]
           [java.security MessageDigest]
           [java.util ArrayDeque ArrayList]))

(set! *warn-on-reflection* true)

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
  storage keeps as text.

  ## Costs

  - Linear in the printed size, and it holds the whole text: a caller that
    only needs an identity uses `seon.id/id`, which hashes structure."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Canonical printing accepts arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}]] :string]}
  [data]
  (let [writer (StringWriter.)]
    (print-canonical! data writer)
    (str writer)))

(defn- structural-digest
  "SHA-256 bytes of `data`'s structure: one tag byte per value, then its
  payload; a map's or set's members each digested apart and summed, so entry
  order never matters. Equal values (Clojure `=`) digest equal, except that a
  vector and a list differ. Metadata is ignored. A value with no encoding
  throws `:seon.id/unhashable-error`."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes the structure of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}]] [:fn clojure.core/bytes?]]}
  ^bytes [data]
  (let [;; one digester per unordered nesting depth, reused
        levels (ArrayList.)
        _ (.add levels (MessageDigest/getInstance "SHA-256"))
        depth (int-array 1)
        buf (ByteBuffer/allocate 8192)
        current (fn ^MessageDigest [] (.get levels (aget depth 0)))
        ;; every writer answers nil, so `visit!` answers only frames
        flush! (fn [] (when (pos? (.position buf)) (.flip buf) (.update ^MessageDigest (current) buf) (.clear buf)) nil)
        room! (fn [^long n] (when (< (.remaining buf) n) (flush!)))
        tag! (fn [^long t] (room! 1) (.put buf (unchecked-byte t)) nil)
        long! (fn [^long v] (room! 8) (.putLong buf v) nil)
        ;; a length or count: Java strings, arrays and collections count in ints
        length! (fn [^long n] (room! 4) (.putInt buf (int n)) nil)
        char! (fn [c] (room! 2) (.putChar buf (char c)) nil)
        bytes! (fn [^bytes bs] (length! (alength bs)) (flush!) (.update ^MessageDigest (current) bs))
        ;; ASCII text as one byte a character, other text as UTF-16 units under its own tag
        string! (fn [^String s]
                  (let [n (.length s)]
                    (if (loop [i 0] (or (== i n) (and (< (int (.charAt s i)) 128) (recur (inc i)))))
                      (do (tag! 8) (length! n) (dotimes [i n] (room! 1) (.put buf (unchecked-byte (int (.charAt s i))))))
                      (do (tag! 23) (length! n) (dotimes [i n] (char! (.charAt s i)))))))
        name! (fn [^long t x] (tag! t) (if-some [n (namespace x)] (do (tag! 1) (string! n)) (tag! 0)) (string! (name x)))
        ;; a frame: kind, the collection, its cursor (a seq, or an index into an indexed
        ;; collection), the unordered members' sum; built without an intermediate vector
        frame (fn [kind coll cursor acc] (doto (object-array 4) (aset 0 kind) (aset 1 coll) (aset 2 cursor) (aset 3 acc)))
        big! (fn [^BigInteger b] (if (< (.bitLength b) 64) (do (tag! 3) (long! (.longValue b))) (do (tag! 4) (bytes! (.toByteArray b)))))
        enter! (fn [] (flush!)
                 (let [d (inc (aget depth 0))]
                   (when (= d (.size levels)) (.add levels (MessageDigest/getInstance "SHA-256")))
                   (aset depth 0 d)))
        ;; leave a member's level, adding its digest into `acc` mod 2^256
        leave! (fn [^longs acc]
                 (flush!)
                 (let [member (ByteBuffer/wrap (.digest ^MessageDigest (current)))]
                   (aset depth 0 (dec (aget depth 0)))
                   (loop [i 3 carry 0]
                     (when (>= i 0)
                       (let [a (aget acc i) s1 (unchecked-add a (.getLong member (int (* i 8)))) s2 (unchecked-add s1 carry)]
                         (aset acc i s2)
                         (recur (dec i) (if (or (neg? (Long/compareUnsigned s1 a)) (and (== carry 1) (zero? s2))) 1 0)))))))
        ;; encode a scalar and answer nil, or answer the frame a collection pushes
        visit! (fn [x]
                 (cond
                   (keyword? x) (name! 9 x)
                   (string? x) (string! x)
                   (instance? Long x) (do (tag! 3) (long! x))
                   (nil? x) (tag! 0)
                   (symbol? x) (name! 10 x)
                   (boolean? x) (tag! (if x 2 1))
                   (vector? x) (do (tag! 17) (frame :vector x (long-array 1) nil))
                   (or (map? x) (set? x)) (frame :unordered x (seq x) (long-array 4))
                   (sequential? x) (do (tag! 18) (frame :ordered x (seq x) nil))
                   (or (instance? Integer x) (instance? Short x) (instance? Byte x)) (do (tag! 3) (long! (long x)))
                   (or (instance? Double x) (instance? Float x))
                   (let [d (double x)] (tag! 5) (long! (Double/doubleToLongBits (if (== d 0.0) 0.0 d))))
                   (instance? clojure.lang.BigInt x) (big! (.toBigInteger ^clojure.lang.BigInt x))
                   (instance? BigInteger x) (big! x)
                   (ratio? x) (do (tag! 6) (bytes! (.toByteArray (numerator x))) (bytes! (.toByteArray (denominator x))))
                   (decimal? x) (let [d (.stripTrailingZeros ^BigDecimal x)]
                                  (tag! 7) (long! (.scale d)) (bytes! (.toByteArray (.unscaledValue d))))
                   (char? x) (do (tag! 11) (char! x))
                   (uuid? x) (do (tag! 12) (long! (.getMostSignificantBits ^java.util.UUID x))
                                 (long! (.getLeastSignificantBits ^java.util.UUID x)))
                   (instance? java.util.Date x) (do (tag! 13) (long! (.getTime ^java.util.Date x)))
                   (instance? java.time.Instant x) (do (tag! 14) (long! (.getEpochSecond ^java.time.Instant x))
                                                       (long! (.getNano ^java.time.Instant x)))
                   (instance? java.util.regex.Pattern x) (do (tag! 15) (string! (.pattern ^java.util.regex.Pattern x))
                                                             (long! (.flags ^java.util.regex.Pattern x)))
                   (tagged-literal? x) (do (tag! 16) (name! 10 (:tag x)) (frame :bare [(:form x)] (long-array 1) nil))
                   :else (throw (ex-info "seon.id cannot hash a host object; hash its data instead."
                                         {:seon.error/type :seon.id/unhashable-error :seon.error/at (java.util.Date.)
                                          :seon.error/layer :seon.id/unhashable-error :seon.error/operation 'seon.id/id
                                          :seon.error/message "seon.id cannot hash a host object; hash its data instead."
                                          :seon.id/class (.getName (class x))}))))
        stack (ArrayDeque.)]
    (loop [x data visit? true]
      (if visit?
        (do (some->> (visit! x) (.push stack))
            (recur nil false))
        (when-some [^objects top (.peek stack)]
          (case (aget top 0)
            (:vector :bare)
            (let [^clojure.lang.Indexed v (aget top 1) ^longs i (aget top 2) at (aget i 0)]
              (if (< at (count v))
                (do (aset i 0 (inc at)) (recur (.nth v (int at)) true))
                (do (when (identical? :vector (aget top 0)) (tag! 19)) (.pop stack) (recur nil false))))
            :ordered
            (if-some [items (aget top 2)]
              (do (aset top 2 (next items)) (recur (first items) true))
              (do (tag! 19) (.pop stack) (recur nil false)))
            :unordered
            (if-some [items (aget top 2)]
              (let [item (first items) ^longs acc (aget top 3)]
                (aset top 2 (next items))
                (enter!)
                (.push stack (frame :leave nil nil acc))
                (if (map? (aget top 1))
                  (do (.push stack (frame :bare item (long-array 1) nil)) (recur nil false))
                  (recur item true)))
              (let [coll (aget top 1) ^longs acc (aget top 3)]
                (.pop stack)
                (cond (record? coll) (do (tag! 21) (string! (.getName (class coll))))
                      (map? coll) (tag! 20)
                      :else (tag! 22))
                (length! (count coll))
                (dotimes [i 4] (long! (aget acc i)))
                (recur nil false)))
            :leave (do (.pop stack) (leave! (aget top 3)) (recur nil false))))))
    (flush!)
    (.digest ^MessageDigest (current))))

(defn id
  "The first n (default 12) hex characters of the SHA-256 of `data`'s
  structure, or a fresh event identity without data. Equal values have equal
  ids whatever their map or set order; a vector and a list differ.

  ## Costs

  - Linear in the value's size; memory proportional to its depth. Callers
    hash small values; a large derived value is hashed by composing its parts'
    digests (`seon.test.runner/reach-component-digests`)."
  {:malli/schema
   [:function [:=> [:cat] [:string {:min 12, :max 12}]] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes the structure of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}]] [:string {:min 12, :max 12}]] [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes the structure of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}] [:int {:min 1, :max 64}]] [:string {:min 1, :max 64}]]]}
  ([] (id (random-uuid)))
  ([data] (id data default-length))
  ([data n]
   (subs (hex (structural-digest data)) 0 n)))

(defn digest
  "The stable id of a thing that IS its `parts`: `length` hex characters of
  the structural SHA-256 of their ordered vector (`id`)."
  {:malli/schema [:=> [:cat [:int {:min 1, :max 64}] [:sequential [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "Identity hashes the structure of arbitrary data, including nested heterogeneous values and nil.", :gen/elements [nil false 0 "" :k [] {}]}]]] [:string {:min 1, :max 64}]]}
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
