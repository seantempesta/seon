(ns seon.server.codec
  "CBOR codec + length-framed I/O.

   Wire format: 4-byte big-endian length + CBOR payload.

   We use jackson-cbor for a no-native-deps CBOR encoder/decoder. Jackson's
   ObjectMapper round-trips strings/ints/floats/booleans/maps/arrays cleanly;
   Clojure-specific types (keywords, sets, ratios) round-trip via the simple
   strategy described in PROTOCOL.md (keywords as strings, recover on read)."
  (:require [clojure.java.io :as io])
  (:import [com.fasterxml.jackson.databind ObjectMapper]
           [com.fasterxml.jackson.dataformat.cbor CBORFactory]
           [java.io DataInputStream DataOutputStream InputStream OutputStream
                    ByteArrayOutputStream]
           [java.util LinkedHashMap]))

(set! *warn-on-reflection* true)

(defonce ^ObjectMapper mapper
  (ObjectMapper. (CBORFactory.)))

(defn- ->java
  "Convert Clojure data to the Java types Jackson's CBORFactory accepts.
   - keywords -> :ns/name string (caller-side encoding choice)
   - sets     -> sorted lists
   - everything else: maps/vectors/lists become LinkedHashMap/ArrayList through
     Jackson's bean introspection."
  [x]
  (cond
    (nil? x)     nil
    (keyword? x) (str (when-let [n (namespace x)] (str n "/")) (name x))
    (symbol? x)  (str x)
    (map? x)     (let [m (LinkedHashMap.)]
                   (doseq [[k v] x] (.put m (->java k) (->java v)))
                   m)
    (set? x)     (mapv ->java x)
    (sequential? x) (mapv ->java x)
    (or (string? x) (boolean? x) (integer? x) (float? x) (double? x)) x
    (instance? java.util.Date x) x
    :else (str x)))

(defn- java->clj
  "Convert Jackson's parsed objects back to Clojure data. Map keys come back
   as strings; callers that want keyword keys pull them out by string."
  [x]
  (cond
    (nil? x)                  nil
    (instance? java.util.Map x)
      (into {} (for [[k v] x] [(java->clj k) (java->clj v)]))
    (instance? java.util.List x)
      (mapv java->clj x)
    :else x))

(defn encode ^bytes [x]
  (.writeValueAsBytes mapper (->java x)))

(defn decode [^bytes b]
  (java->clj (.readValue mapper b Object)))

;; ---------- Length-framed I/O ----------

(defn write-frame! [^OutputStream out x]
  (let [^bytes payload (encode x)
        len (alength payload)
        dout (DataOutputStream. out)]
    (.writeInt dout len)
    (.write dout payload 0 len)
    (.flush dout)))

(defn read-frame
  "Read one length-framed CBOR message. Returns nil on EOF."
  [^InputStream in]
  (let [din (DataInputStream. in)
        len (try (.readInt din) (catch java.io.EOFException _ nil))]
    (when len
      (when (or (neg? len) (> len (* 16 1024 1024)))
        (throw (ex-info "Frame length out of bounds" {:len len})))
      (let [buf (byte-array len)]
        (.readFully din buf 0 len)
        (decode buf)))))
