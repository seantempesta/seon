(ns seon.blob
  "Content-addressed result blobs in Seon's already-open Konserve store."
  (:refer-clojure :exclude [get])
  (:require [konserve.core :as k]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [java.nio.charset StandardCharsets]))

(schema.edn/load! {})

(defn- konserve-store
  [connection]
  (:store @connection))

(defn- utf8-bytes
  ^bytes [content]
  (.getBytes ^String content StandardCharsets/UTF_8))

(def ^:private byte-array-class
  (class (byte-array 0)))

(defn- read-octets
  ^bytes [binary]
  (if (instance? byte-array-class binary)
    binary
    (with-open [input ^java.io.InputStream binary]
      (.readAllBytes input))))

(defn put!
  "Store UTF-8 content once and return its SHA-256 digest."
  {:malli/schema
   [:=> [:cat :seon.store/branch-connection :seon.blob/content]
    :seon.blob/digest]}
  [connection content]
  (let [octets (utf8-bytes content)
        digest (schema/sha-256 [octets])
        store (konserve-store connection)]
    (when-not (k/exists? store digest {:sync? true})
      (k/bassoc store digest octets {:sync? true}))
    digest))

(defn get
  "Read and verify UTF-8 content by SHA-256 digest."
  {:malli/schema
   [:=> [:cat :seon.store/branch-connection :seon.blob/digest]
    [:maybe :seon.blob/content]]}
  [connection digest]
  (when-let [octets
             (k/bget
              (konserve-store connection)
              digest
              (fn [{:keys [input-stream]}]
                (read-octets input-stream))
              {:sync? true})]
    (let [actual (schema/sha-256 [octets])]
      (when-not (= digest actual)
        (throw
         (ex-info
          "Blob content does not match its digest."
          {:seon.error/kind :core-bug
           :seon.blob/digest digest
           :seon.blob/actual-digest actual})))
      (String. ^bytes octets StandardCharsets/UTF_8))))
