(ns seon.server.codec
  "Transit-JSON codec + length-framed I/O.

   Wire format: 4-byte big-endian length + Transit-JSON payload.

   The WHOLE wire frame (control envelope + values) is one Transit-JSON map:
   `:seon.store.wire/*` keyword keys and native Clojure values (keywords,
   instants, sets, ratios, BigInts, tx-data, results) all round-trip in a
   single encode/decode — no inner Transit strings, no keyword flattening.
   The CLJS pod's `seon.store.internal.wire-node` mirrors this exactly with
   `cognitect.transit` (transit-cljs). Length-prefix framing is shared,
   transport-agnostic (UDS now, TCP later)."
  (:require [cognitect.transit :as t])
  (:import [java.io DataInputStream DataOutputStream InputStream OutputStream
                    ByteArrayInputStream ByteArrayOutputStream]))

(set! *warn-on-reflection* true)

(defn encode ^bytes [x]
  (let [out (ByteArrayOutputStream. 1024)
        w   (t/writer out :json)]
    (t/write w x)
    (.toByteArray out)))

(defn decode [^bytes b]
  (let [in (ByteArrayInputStream. b)
        r  (t/reader in :json)]
    (t/read r)))

;; ---------- Length-framed I/O ----------

(defn write-frame! [^OutputStream out x]
  (let [^bytes payload (encode x)
        len (alength payload)
        dout (DataOutputStream. out)]
    (.writeInt dout len)
    (.write dout payload 0 len)
    (.flush dout)))

(defn read-frame
  "Read one length-framed Transit-JSON message. Returns nil on EOF."
  [^InputStream in]
  (let [din (DataInputStream. in)
        len (try (.readInt din) (catch java.io.EOFException _ nil))]
    (when len
      (when (or (neg? len) (> len (* 16 1024 1024)))
        (throw (ex-info "Frame length out of bounds" {:len len})))
      (let [buf (byte-array len)]
        (.readFully din buf 0 len)
        (decode buf)))))
