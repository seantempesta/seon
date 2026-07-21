(ns seon.content-hash
  "Own exact UTF-8 SHA-256 content identities across Seon runtimes."
  (:require
   [seon.schema :as schema]
   #?(:cljs ["node:crypto" :as crypto]))
  #?(:clj
     (:import
      [java.nio.charset StandardCharsets]
      [java.security MessageDigest]
      [java.util HexFormat])))

(schema/register! ::content :string)
(schema/register! ::digest [:re "^[0-9a-f]{64}$"])

(defn sha-256
  "Lowercase SHA-256 identity of exact UTF-8 string bytes."
  {:malli/schema [:=> [:cat ::content] ::digest]}
  [content]
  #?(:clj
     (let [digest (MessageDigest/getInstance "SHA-256")]
       (.formatHex (HexFormat/of)
                   (.digest digest (.getBytes ^String content
                                              StandardCharsets/UTF_8))))
     :cljs
     (-> (.createHash crypto "sha256")
         (.update content "utf8")
         (.digest "hex"))))
