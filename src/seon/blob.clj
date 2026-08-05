(ns seon.blob
  "Content-addressed result blobs in Seon's already-open Konserve store."
  (:refer-clojure :exclude [get])
  (:require [clojure.java.io :as io]
            [clojure.test.check.generators :as gen]
            [datahike.gc-guard :as gc-guard]
            [konserve.core :as k]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn])
  (:import [java.io ByteArrayInputStream File InputStream OutputStream
            RandomAccessFile]
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
    (k/bget
     store content-digest
     (fn [binary]
       (let [payload (if (map? binary) (:input-stream binary) binary)]
       (if (instance? (class (byte-array 0)) payload)
         (do
           (.update digester ^bytes payload)
           {:seon.blob/digest (digest-string digester)
            :seon.blob/size (alength ^bytes payload)})
         (with-open [input ^InputStream payload]
           (let [buffer (byte-array buffer-size)]
             (loop [total-size 0]
               (let [read-count (.read input buffer)]
                 (if (neg? read-count)
                   {:seon.blob/digest (digest-string digester)
                    :seon.blob/size total-size}
                   (do
                     (.update digester buffer 0 read-count)
                     (recur (+ total-size read-count)))))))))))
     {:sync? true})))

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

(defn- staged-write
  [^bytes prefix prefix-size total-size staged-file ^MessageDigest digester]
  (let [content-digest (digest-string digester)
        inline-prefix (Arrays/copyOf prefix prefix-size)]
    (cond-> {:seon.blob/digest content-digest
             :seon.blob/size total-size
             :seon.blob/inline-prefix inline-prefix}
      staged-file
      (assoc :seon.blob/staged-path (.getAbsolutePath ^File staged-file))

      (nil? staged-file)
      (assoc :seon.blob/staged-octets inline-prefix))))

(defn- store-id
  [connection]
  (get-in @connection [:config :store :id]))

(defn- staged-source
  [staged]
  (if-let [path (:seon.blob/staged-path staged)]
    (io/file path)
    (:seon.blob/staged-octets staged)))

(defn- publish-staged!
  [connection staged]
  (let [store (konserve-store connection)
        content-digest (:seon.blob/digest staged)
        expected-size (:seon.blob/size staged)
        verification-buffer-size
        (max 1 (alength ^bytes (:seon.blob/inline-prefix staged)))]
    ;; This existence check must remain inside the publication permit. An
    ;; issued sweep batch may already contain an orphan with the same digest.
    (when-not (k/exists? store content-digest {:sync? true})
      (k/bassoc store content-digest (staged-source staged) {:sync? true}))
    (verify-stored! store content-digest expected-size verification-buffer-size)
    (when-let [path (:seon.blob/staged-path staged)]
      (Files/deleteIfExists (.toPath (io/file path))))
    (dissoc staged :seon.blob/staged-path :seon.blob/staged-octets)))

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

(defn stage!
  "Stage UTF-8 content without publishing it into the blob store."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.blob/content]
    :seon.blob/staged-write]}
  [connection content]
  (let [octets (utf8-bytes content)
        digester (doto (MessageDigest/getInstance "SHA-256")
                   (.update octets))]
    (staged-write octets (alength octets) (alength octets) nil digester)))

(defn stage-binary!
  "Stage binary content without publishing it into the blob store.

  Retains only the configured inline prefix. Content beyond that prefix is
  written to one staging file under the process root."
  {:malli/schema
   [:=>
    [:cat :seon.db/connection :seon.blob/input-stream]
    :seon.blob/staged-write]}
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
          (staged-write prefix prefix-size total-size nil digester)

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
                    (staged-write
                     prefix next-prefix-size
                     (+ next-total-size remaining-size) file digester))
                  (catch Throwable error
                    (.close stage-output)
                    ;; The staging file is the observable artifact of an
                    ;; interrupted oversized write. It remains under the
                    ;; process root for inspection until explicit cleanup.
                    (throw error)))))))))))

(defn with-publication!
  "Publish staged blobs and commit their direct roots under one permit."
  {:malli/schema
   [:=>
    [:cat :seon.db/connection [:vector :seon.blob/staged-write]
     [:fn clojure.core/fn?]]
    :seon.schema/value]}
  [connection staged-writes commit-roots!]
  (if (seq staged-writes)
    (let [permit (gc-guard/acquire-reachability-permit!
                  (store-id connection) :blob)]
      (try
        (run! #(publish-staged! connection %) staged-writes)
        (commit-roots!)
        (finally
          (gc-guard/release-reachability-permit! permit))))
    (commit-roots!)))

(defn put!
  "Store UTF-8 content once and return its SHA-256 digest."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.blob/content]
    :seon.blob/digest]}
  [connection content]
  (let [staged (stage! connection content)]
    (with-publication! connection [staged]
      (fn [] (:seon.blob/digest staged)))))

(defn put-binary!
  "Stream binary content into the content-addressed blob store."
  {:malli/schema
   [:=>
    [:cat :seon.db/connection :seon.blob/input-stream]
    :seon.blob/write-result]}
  [connection input]
  (let [staged (stage-binary! connection input)]
    (with-publication! connection [staged]
      (fn []
        (dissoc staged :seon.blob/staged-path :seon.blob/staged-octets)))))

(defn read-staged-chunk
  "Read at most length staged bytes beginning at offset."
  {:malli/schema
   [:=>
    [:cat :seon.blob/staged-write :seon.blob/offset :seon.blob/length]
    :seon.blob/octet-array]}
  [staged offset length]
  (if-let [octets (:seon.blob/staged-octets staged)]
    (let [start (min (alength ^bytes octets) offset)
          end (min (alength ^bytes octets) (+ start length))]
      (Arrays/copyOfRange ^bytes octets start end))
    (with-open [file (RandomAccessFile. ^String (:seon.blob/staged-path staged)
                                        "r")]
      (.seek file (long offset))
      (let [buffer (byte-array length)
            read-count (.read file buffer)]
        (if (neg? read-count)
          (byte-array 0)
          (Arrays/copyOf buffer read-count))))))

(defn read-chunk
  "Read at most length exact binary bytes beginning at offset."
  {:malli/schema
   [:=>
    [:cat :seon.db/connection :seon.blob/digest
     :seon.blob/offset :seon.blob/length]
    [:maybe :seon.blob/octet-array]]}
  [connection content-digest offset length]
  (k/bget-range (konserve-store connection)
                content-digest offset length {:sync? true}))

(defn get
  "Read and verify UTF-8 content by SHA-256 digest."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.blob/digest]
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
