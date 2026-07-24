(ns seon.dev.config-manifest
  "Pure identity functions for an admitted resolved config manifest."
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn digest
  "Return the SHA-256 identity of canonical resolved-manifest bytes."
  {:malli/schema [:=> [:cat :string] [:re #"[0-9a-f]{64}"]]}
  [text]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes text StandardCharsets/UTF_8))
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))
