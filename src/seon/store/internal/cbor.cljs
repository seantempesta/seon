(ns seon.store.internal.cbor
  "Minimal CBOR codec for the seon wire-server control envelope, in CLJS for
   the Node transport (`seon.store.internal.wire-node`).

   The wire-server (`seon.server.codec`, Jackson CBORFactory) frames each
   message as: 4-byte big-endian length + CBOR payload. The control envelope is
   a CBOR MAP with STRING keys; values are strings, ints, booleans, and arrays
   (of strings, for `args`/`eids`). Every Clojure VALUE rides as a Transit-JSON
   STRING inside the envelope (see `seon.client-runtime.transit`).

   Jackson emits INDEFINITE-length maps (0xBF .. 0xFF) and definite-length
   strings/ints/arrays. This decoder handles BOTH definite- and
   indefinite-length maps/arrays/strings; the encoder mirrors Jackson
   (indefinite map, definite strings/ints/arrays) so encoded frames are
   byte-identical to the server's — verified against `seon.server.codec/encode`.

   Operates on Node `Buffer`s. No external deps; the envelope subset is tiny."
  (:require [clojure.string :as str]))

(def ^js Buffer (js/require "buffer"))
(def ^js B (.-Buffer Buffer))

;; ---------- encode ----------

(defn- enc-uint
  "Encode an unsigned int under major type `major` (already shifted: 0x00,
   0x20, 0x60, 0x80). Returns a Buffer."
  [major n]
  (cond
    (< n 24)        (.from B #js [(bit-or major n)])
    (< n 0x100)     (.from B #js [(bit-or major 24) n])
    (< n 0x10000)   (.from B #js [(bit-or major 25)
                                  (bit-and (bit-shift-right n 8) 0xff)
                                  (bit-and n 0xff)])
    (< n 0x100000000)
    (.from B #js [(bit-or major 26)
                  (bit-and (unsigned-bit-shift-right n 24) 0xff)
                  (bit-and (unsigned-bit-shift-right n 16) 0xff)
                  (bit-and (unsigned-bit-shift-right n 8) 0xff)
                  (bit-and n 0xff)])
    :else
    (let [buf (.alloc B 9)]
      (aset buf 0 (bit-or major 27))
      (.writeBigUInt64BE buf (js/BigInt n) 1)
      buf)))

(declare encode)

(defn- enc-array [xs]
  (let [parts (array)]
    (.push parts (enc-uint 0x80 (count xs)))   ; major 4: definite array
    (doseq [el xs] (.push parts (encode el)))
    (.concat B parts)))

(defn- enc-map
  "Indefinite-length map (mirror Jackson): 0xBF .. 0xFF. `m` is a JS object or
   a Clojure map of string keys."
  [m]
  (let [parts (array)
        pairs (if (map? m) (seq m) (map (fn [k] [k (aget m k)]) (js-keys m)))]
    (.push parts (.from B #js [0xbf]))
    (doseq [[k v] pairs]
      (.push parts (encode (name k)))
      (.push parts (encode v)))
    (.push parts (.from B #js [0xff]))
    (.concat B parts)))

(defn encode
  "Encode a value to a CBOR Buffer. Supports nil, bool, non-negative int,
   string, JS array / Clojure sequential, and JS object / Clojure map (string
   keys). This is exactly the envelope subset the wire protocol uses."
  ^js [x]
  (cond
    (nil? x)        (.from B #js [0xf6])                      ; null
    (boolean? x)    (.from B #js [(if x 0xf5 0xf4)])
    (and (number? x) (integer? x) (>= x 0)) (enc-uint 0x00 x)
    (and (number? x) (integer? x))          (enc-uint 0x20 (- (- x) 1))  ; neg int
    (string? x)     (let [bytes (.from B x "utf-8")]
                      (.concat B #js [(enc-uint 0x60 (.-length bytes)) bytes]))
    (array? x)      (enc-array x)
    (sequential? x) (enc-array x)
    (or (map? x) (object? x)) (enc-map x)
    :else (throw (ex-info (str "cbor encode: unsupported type " (type x)) {:x x}))))

;; ---------- decode ----------

(defn decode
  "Decode a CBOR Buffer into a CLJS value. Maps decode to CLJS maps with STRING
   keys; arrays to vectors. Handles both definite- and indefinite-length
   maps/arrays/strings."
  [^js buf]
  (let [pos (atom 0)]
    (letfn [(read-byte []
              (let [b (aget buf @pos)] (swap! pos inc) b))
            (read-uint-arg [ai]
              (cond
                (< ai 24) ai
                (= ai 24) (read-byte)
                (= ai 25) (let [v (.readUInt16BE buf @pos)] (swap! pos + 2) v)
                (= ai 26) (let [v (.readUInt32BE buf @pos)] (swap! pos + 4) v)
                (= ai 27) (let [v (.readBigUInt64BE buf @pos)]
                            (swap! pos + 8)
                            (if (<= v (js/BigInt js/Number.MAX_SAFE_INTEGER))
                              (js/Number v) v))
                :else (throw (ex-info (str "cbor: bad additional-info " ai) {}))))
            (read-item []
              (let [ib    (read-byte)
                    major (bit-shift-right ib 5)
                    ai    (bit-and ib 0x1f)]
                (case major
                  0 (read-uint-arg ai)                          ; unsigned int
                  1 (let [v (read-uint-arg ai)] (- (- v) 1))    ; negative int
                  2 (let [len (read-uint-arg ai)                ; byte string
                          b (.subarray buf @pos (+ @pos len))]
                      (swap! pos + len) b)
                  3 (if (= ai 31)                               ; text string
                      (loop [s ""]
                        (if (= (aget buf @pos) 0xff)
                          (do (swap! pos inc) s)
                          (recur (str s (read-item)))))
                      (let [len (read-uint-arg ai)
                            s (.toString buf "utf-8" @pos (+ @pos len))]
                        (swap! pos + len) s))
                  4 (if (= ai 31)                               ; array
                      (loop [arr (transient [])]
                        (if (= (aget buf @pos) 0xff)
                          (do (swap! pos inc) (persistent! arr))
                          (recur (conj! arr (read-item)))))
                      (let [len (read-uint-arg ai)]
                        (loop [i 0 arr (transient [])]
                          (if (< i len)
                            (recur (inc i) (conj! arr (read-item)))
                            (persistent! arr)))))
                  5 (if (= ai 31)                               ; map (indefinite)
                      (loop [m (transient {})]
                        (if (= (aget buf @pos) 0xff)
                          (do (swap! pos inc) (persistent! m))
                          (let [k (read-item) v (read-item)]
                            (recur (assoc! m k v)))))
                      (let [len (read-uint-arg ai)]
                        (loop [i 0 m (transient {})]
                          (if (< i len)
                            (let [k (read-item) v (read-item)]
                              (recur (inc i) (assoc! m k v)))
                            (persistent! m)))))
                  7 (cond                                       ; simple/float
                      (= ai 20) false
                      (= ai 21) true
                      (= ai 22) nil
                      (= ai 26) (let [v (.readFloatBE buf @pos)] (swap! pos + 4) v)
                      (= ai 27) (let [v (.readDoubleBE buf @pos)] (swap! pos + 8) v)
                      :else (throw (ex-info (str "cbor: unsupported simple/float ai " ai) {})))
                  (throw (ex-info (str "cbor: unsupported major type " major) {})))))]
      (read-item))))

;; ---------- length-framed I/O helpers ----------

(defn frame
  "Prepend the 4-byte big-endian length header to a CBOR payload Buffer."
  ^js [^js payload]
  (let [header (.alloc B 4)]
    (.writeUInt32BE header (.-length payload) 0)
    (.concat B #js [header payload])))
