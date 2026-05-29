(ns seon.client-runtime.transit
  "Transit-JSON codec for sidecar wire value payloads.

   Mirrors seon.sidecar.transit on the JVM side. Encodes Clojure values as
   Transit-JSON strings — keywords, sets, instants, BigInts, ratios all
   preserved. The Rust host treats these strings as opaque blobs; only the
   CLJS guest and the JVM writer interpret them."
  (:require [cognitect.transit :as t]))

(defonce ^:private !writer (atom nil))
(defonce ^:private !reader (atom nil))

(defn- writer []
  (or @!writer
      (let [w (t/writer :json)]
        (reset! !writer w)
        w)))

(defn- reader []
  (or @!reader
      (let [r (t/reader :json)]
        (reset! !reader r)
        r)))

(defn write-str
  "Encode a Clojure value to a Transit-JSON string."
  [v]
  (t/write (writer) v))

(defn read-str
  "Decode a Transit-JSON string. nil/empty → nil."
  [s]
  (when (and s (not= "" s))
    (t/read (reader) s)))
