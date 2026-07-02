(ns seon.server.transit
  "Transit-JSON string codec helpers.

   The pod↔wire-server wire is uniform Transit-JSON: `seon.server.codec`
   encodes the WHOLE frame (envelope + native values) in one Transit pass,
   so the wire no longer wraps individual values in Transit STRINGS. These
   two helpers remain for the few sites that still need a standalone
   Transit-JSON string of a Clojure value (e.g. persisting a query as a
   source string is unrelated — that uses pr-str).

   Why Transit-JSON:
   - First-class fidelity for keywords, symbols, sets, instants, ratios,
     BigInts, doubles vs ints — types that EDN-string-via-pr-str either
     mangles or requires a custom reader for.
   - Cognitect-blessed, both sides stable (transit-clj 1.x, transit-cljs
     0.8.x), and the Cognitect sibling of datahike's Fressian persistence.

   Float-vs-int caveat: JS Numbers don't distinguish 1 from 1.0. Schema-
   driven coercion happens JVM-side in handle-op 'transact' (see
   seon.server.wire/coerce-tx-data-for-schema)."
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
