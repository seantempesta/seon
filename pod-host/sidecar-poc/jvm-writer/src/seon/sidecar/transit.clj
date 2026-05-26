(ns seon.sidecar.transit
  "Transit-JSON codec used for value payloads on the sidecar wire.

   The wire control envelope is CBOR (see seon.sidecar.codec). Inside the
   envelope, every field that carries a CLOJURE VALUE (query results,
   tempids, tx-meta, datom v/a fields, query args, tx-data, selectors)
   is a Transit-JSON STRING.

   Why Transit-JSON for values:
   - First-class fidelity for keywords, symbols, sets, instants, ratios,
     BigInts, doubles vs ints — types that EDN-string-via-pr-str either
     mangles or requires a custom reader for.
   - Cognitect-blessed, both sides stable (transit-clj 1.x, transit-cljs
     0.8.x).
   - The Rust host never parses values — it forwards Transit-JSON strings
     between the JVM writer and the CLJS guest as opaque blobs.

   Float-vs-int caveat: JS Numbers don't distinguish 1 from 1.0. Schema-
   driven coercion happens JVM-side in handle-op 'transact' (see
   seon.sidecar.writer/coerce-tx-data-for-schema)."
  (:require [cognitect.transit :as t])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(set! *warn-on-reflection* true)

(defn write-str
  "Encode a Clojure value to a Transit-JSON string."
  [v]
  (let [out (ByteArrayOutputStream. 256)
        w (t/writer out :json)]
    (t/write w v)
    (.toString out "UTF-8")))

(defn read-str
  "Decode a Transit-JSON string to a Clojure value. Returns nil for nil
   or empty string (the wire convention for 'omitted')."
  [s]
  (when (and s (not (.isEmpty ^String s)))
    (let [in (ByteArrayInputStream. (.getBytes ^String s "UTF-8"))
          r (t/reader in :json)]
      (t/read r))))
