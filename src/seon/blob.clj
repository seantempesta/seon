(ns seon.blob
  "Content-addressed result blobs in Seon's already-open Konserve store."
  (:refer-clojure :exclude [get])
  (:require [clojure.java.io :as io]
            [clojure.test.check.generators :as gen]
            [konserve.core :as k]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [java.io ByteArrayInputStream File InputStream OutputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.security DigestOutputStream MessageDigest]
           [java.util Arrays HexFormat]))

(defn- konserve-store
  [connection]
  (:store @connection))

(defn input-stream?
  "True when value is a JVM input stream."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [value]
  (instance? InputStream value))

(defonce ^:private _input-stream-predicate
  (schema/register-core-predicate!
   'seon.blob/input-stream? input-stream?))

(def input-stream-generator
  (gen/fmap (fn [octets]
              (ByteArrayInputStream. ^bytes octets))
            gen/bytes))

(def octet-array-generator gen/bytes)

(schema.edn/load! {})

(defn- binary-threshold
  [connection]
  (let [threshold
        (db/q
         (db/db connection)
         '[:find ?threshold .
           :where
           [_ :seon.config.eval.result/blob-threshold ?threshold]])]
    (when-not (and (integer? threshold) (pos? threshold))
      (throw
       (ex-info
        "Blob threshold is not a positive integer."
        {:seon.error/kind :core-bug
         :seon.config.eval.result/blob-threshold threshold})))
    (Math/toIntExact (long threshold))))

(defn- stage-file!
  ^File [store]
  (let [store-base (some-> store :backing :base io/file .getAbsoluteFile)
        process-root (some-> store-base .getParentFile)]
    (when-not process-root
      (throw
       (ex-info
        "The file-backed blob store has no process root."
        {:seon.error/kind :core-bug
         :seon.blob/store-base store-base})))
    (let [directory (io/file process-root "blob-staging")]
      (Files/createDirectories (.toPath directory)
                               (make-array java.nio.file.attribute.FileAttribute 0))
      (File/createTempFile "blob-" ".stage" directory))))

(defn- digest-string
  [^MessageDigest digest]
  (.formatHex (HexFormat/of) (.digest digest)))

(defn- stored-digest-and-size
  [store content-digest buffer-size]
  (let [digester (MessageDigest/getInstance "SHA-256")]
    (loop [offset 0]
      (when-let [octets (k/bget-range store content-digest
                                      offset buffer-size {:sync? true})]
        (let [read-count (alength ^bytes octets)
              next-offset (+ offset read-count)]
          (.update digester octets)
          (if (< read-count buffer-size)
            {:seon.blob/digest (digest-string digester)
             :seon.blob/size next-offset}
            (recur next-offset)))))))

(defn- verify-stored!
  [store content-digest expected-size buffer-size]
  (let [actual (stored-digest-and-size store content-digest buffer-size)]
    (when-not (= {:seon.blob/digest content-digest
                  :seon.blob/size expected-size}
                 actual)
      (throw
       (ex-info
        "Stored blob does not match its digest and size."
        {:seon.error/kind :core-bug
         :seon.blob/digest content-digest
         :seon.blob/size expected-size
         :seon.blob/actual actual}))))
  nil)

(defn- publish-binary!
  [store threshold ^bytes prefix prefix-size total-size staged-file
   ^MessageDigest digester]
  (let [content-digest (digest-string digester)
        inline-prefix (Arrays/copyOf prefix prefix-size)
        source (or staged-file inline-prefix)]
    (when-not (k/exists? store content-digest {:sync? true})
      (k/bassoc store content-digest source {:sync? true}))
    (verify-stored! store content-digest total-size threshold)
    (when staged-file
      (Files/delete (.toPath ^File staged-file)))
    {:seon.blob/digest content-digest
     :seon.blob/size total-size
     :seon.blob/inline-prefix inline-prefix}))

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

(defn digest
  "Return the content-addressed SHA-256 digest of UTF-8 content."
  {:malli/schema [:=> [:cat :seon.blob/content] :seon.blob/digest]}
  [content]
  (schema/sha-256 [(utf8-bytes content)]))

(defn put!
  "Store UTF-8 content once and return its SHA-256 digest."
  {:malli/schema
   [:=> [:cat :seon.store/branch-connection :seon.blob/content]
    :seon.blob/digest]}
  [connection content]
  (let [octets (utf8-bytes content)
        content-digest (digest content)
        store (konserve-store connection)]
    (when-not (k/exists? store content-digest {:sync? true})
      (k/bassoc store content-digest octets {:sync? true}))
    content-digest))

(defn put-binary!
  "Stream binary content into the content-addressed blob store.

  Retains only the configured inline prefix. Content beyond that prefix is
  written to one staging file under the process root before publication."
  {:malli/schema
   [:=>
    [:cat :seon.store/branch-connection :seon.blob/input-stream]
    :seon.blob/write-result]}
  [connection ^InputStream input]
  (let [store (konserve-store connection)
        threshold (binary-threshold connection)
        prefix (byte-array threshold)
        buffer (byte-array threshold)
        digester (MessageDigest/getInstance "SHA-256")]
    (loop [prefix-size 0
           total-size 0]
      (let [read-count
            (.read input buffer)]
        (cond
          (neg? read-count)
          (publish-binary!
           store threshold prefix prefix-size total-size nil digester)

          (zero? read-count)
          (throw
           (ex-info
            "Blob input stream made no progress."
            {:seon.error/kind :core-bug
             :seon.blob/size total-size}))

          :else
          (let [prefix-count (min read-count (- threshold prefix-size))
                next-prefix-size (+ prefix-size prefix-count)
                next-total-size (+ total-size read-count)]
            (.update digester buffer 0 read-count)
            (System/arraycopy buffer 0 prefix prefix-size prefix-count)
            (if (= prefix-count read-count)
              (recur next-prefix-size next-total-size)
              (let [file (stage-file! store)
                    stage-output ^OutputStream (io/output-stream file)]
                (try
                  (.write stage-output prefix 0 next-prefix-size)
                  (.write stage-output buffer
                          prefix-count (- read-count prefix-count))
                  (let [digest-output
                        (DigestOutputStream. stage-output digester)
                        remaining-size (.transferTo input digest-output)]
                    (.close digest-output)
                    (publish-binary!
                     store threshold prefix next-prefix-size
                     (+ next-total-size remaining-size) file digester))
                  (catch Throwable error
                    (.close stage-output)
                    ;; Publication may fail after the staging file exists
                    ;; (for example, a bounded producer refuses its next
                    ;; chunk). Delete only that explicit path; `Files/delete`
                    ;; removes a swapped link entry rather than following it.
                    (Files/deleteIfExists (.toPath ^File file))
                    (throw error)))))))))))

(defn read-chunk
  "Read at most length exact binary bytes beginning at offset."
  {:malli/schema
   [:=>
    [:cat :seon.store/branch-connection :seon.blob/digest
     :seon.blob/offset :seon.blob/length]
    [:maybe :seon.blob/octet-array]]}
  [connection content-digest offset length]
  (k/bget-range (konserve-store connection)
                content-digest offset length {:sync? true}))

(defn get
  "Read and verify UTF-8 content by SHA-256 digest."
  {:malli/schema
   [:=> [:cat :seon.store/branch-connection :seon.blob/digest]
    [:maybe :seon.blob/content]]}
  [connection content-digest]
  (when-let [octets
             (k/bget
              (konserve-store connection)
              content-digest
              (fn [{:keys [input-stream]}]
                (read-octets input-stream))
              {:sync? true})]
    (let [actual (schema/sha-256 [octets])]
      (when-not (= content-digest actual)
        (throw
         (ex-info
          "Blob content does not match its digest."
          {:seon.error/kind :core-bug
           :seon.blob/digest content-digest
           :seon.blob/actual-digest actual})))
      (String. ^bytes octets StandardCharsets/UTF_8))))
