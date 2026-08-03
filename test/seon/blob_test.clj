(ns seon.blob-test
  "Content-addressed blob behavior on Seon's real file store."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [konserve.core :as k]
            [seon.blob :as blob]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-support :as support])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            FileInputStream InputStream RandomAccessFile SequenceInputStream]
           [java.security MessageDigest]
           [java.util Arrays HexFormat]))

(def ^:private binary-threshold 4096)

(defn- independent-digest
  [^bytes octets]
  (.formatHex (HexFormat/of)
              (.digest (doto (MessageDigest/getInstance "SHA-256")
                         (.update octets)))))

(defn- with-file-blob-store
  [root f]
  (let [root-file (io/file root)]
    (when (.exists root-file)
      (support/delete-recursively! root-file))
    (let [opened (store/open-store! {:seon.store/dir (str root "/store")})]
      (try
        (db/transact!
         (:seon.store/connection opened)
         [{:db/ident :seon.config.eval.result/blob-threshold
           :db/valueType :db.type/long
           :db/cardinality :db.cardinality/one}])
        (registry/branch! {:seon.store/store opened
                           :seon.cluster.registry/from :db
                           :seon.store/branch :blob-binary-test})
        (let [connection (store/open-branch! opened :blob-binary-test)]
          (try
            (db/transact!
             connection
             [{:seon.config.eval.result/blob-threshold binary-threshold}])
            (f connection root)
            (finally
              (d/release connection))))
        (finally
          (store/release-store! opened)
          (support/delete-recursively! root-file))))))

(defn- reconstruct
  [connection digest chunk-size]
  (with-open [output (ByteArrayOutputStream.)]
    (loop [offset 0]
      (let [octets (blob/read-chunk connection digest offset chunk-size)]
        (if (zero? (alength ^bytes octets))
          (.toByteArray output)
          (do
            (.write output ^bytes octets)
            (recur (+ offset (alength ^bytes octets)))))))))

(defn- boundary-payload
  [size]
  (byte-array
   (map #(unchecked-byte
         (nth [0 195 40 255 128 127 1 254] (mod % 8)))
        (range size))))

(deftest generated-tier-local-binary-values-satisfy-the-registry
  (let [input-streams (gen/sample blob/input-stream-generator 20)
        octet-arrays (gen/sample blob/octet-array-generator 20)]
    (is (every? #(schema/valid-candidate-value?
                  :seon.blob/input-stream %)
                input-streams))
    (is (every? #(schema/valid-candidate-value?
                  :seon.blob/octet-array %)
                octet-arrays))))

(deftest binary-content-round-trips-on-both-sides-of-the-inline-threshold
  (with-file-blob-store
    "tmp/blob-binary-boundary-test"
    (fn [connection _root]
      (doseq [size [(dec binary-threshold) (inc binary-threshold)]]
        (testing (str size " bytes")
          (let [payload (boundary-payload size)
                expected-digest (independent-digest payload)
                result (blob/put-binary!
                        connection (ByteArrayInputStream. payload))
                reconstructed (reconstruct connection expected-digest 257)]
            (is (= expected-digest (:seon.blob/digest result)))
            (is (= size (:seon.blob/size result)))
            (is (Arrays/equals
                 (Arrays/copyOf payload (min size binary-threshold))
                 (:seon.blob/inline-prefix result)))
            (is (Arrays/equals payload reconstructed))
            (is (= expected-digest (independent-digest reconstructed)))))))))

(def ^:private binary-payload-generator
  (gen/bind
   (gen/one-of [(gen/choose 0 binary-threshold)
                (gen/choose (inc binary-threshold)
                            (* 2 binary-threshold))])
   (fn [size]
     (gen/vector (gen/choose 0 255) size))))

(deftest generated-binary-values-round-trip-through-staging-and-chunks
  (let [check
        (tc/quick-check
         20
         (prop/for-all
          [octet-values binary-payload-generator]
          (with-file-blob-store
            "tmp/blob-binary-property-test"
            (fn [connection _root]
              (let [payload (byte-array (map unchecked-byte octet-values))
                    expected-digest (independent-digest payload)
                    result (blob/put-binary!
                            connection (ByteArrayInputStream. payload))
                    reconstructed (reconstruct connection expected-digest 113)]
                (and (= expected-digest (:seon.blob/digest result))
                     (= (alength payload) (:seon.blob/size result))
                     (Arrays/equals payload reconstructed))))))
         :seed 1785772983)]
    (is (:result check) (pr-str check))))

(deftest interrupted-staging-leaves-the-store-unchanged
  (with-file-blob-store
    "tmp/blob-binary-crash-test"
    (fn [connection root]
      (let [payload (boundary-payload (inc binary-threshold))
            expected-digest (independent-digest payload)
            failure (proxy [InputStream] []
                      (read
                        ([] (throw (ex-info "simulated crash" {})))
                        ([_buffer] (throw (ex-info "simulated crash" {})))
                        ([_buffer _offset _length]
                         (throw (ex-info "simulated crash" {})))))
            input (SequenceInputStream.
                   (ByteArrayInputStream. payload) failure)
            thrown (try
                     (blob/put-binary! connection input)
                     nil
                     (catch clojure.lang.ExceptionInfo error
                       error))]
        (is (= "simulated crash" (ex-message thrown)))
        (is (false? (k/exists? (:store @connection)
                              expected-digest
                              {:sync? true})))
        (let [staging-files (seq (.listFiles (io/file root "blob-staging")))]
          (is (= 1 (count staging-files)))
          (is (> (.length ^java.io.File (first staging-files))
                 binary-threshold)))))))

(deftest large-binary-write-and-small-chunk-have-bounded-allocation
  (with-file-blob-store
    "tmp/blob-binary-allocation-test"
    (fn [connection root]
      (let [payload-file (io/file root "large-input.bin")
            payload-size (* 32 1024 1024)
            allocation-bean ^com.sun.management.ThreadMXBean
            (java.lang.management.ManagementFactory/getThreadMXBean)
            thread-id (.getId (Thread/currentThread))]
        (with-open [payload (RandomAccessFile. payload-file "rw")]
          (.setLength payload payload-size))
        (.setThreadAllocatedMemoryEnabled allocation-bean true)
        (let [write-before (.getThreadAllocatedBytes allocation-bean thread-id)
              result
              (with-redefs-fn
                {(ns-resolve 'seon.blob 'binary-threshold)
                 (constantly binary-threshold)
                 (ns-resolve 'seon.blob 'verify-stored!)
                 (constantly nil)}
                #(with-open [input (FileInputStream. payload-file)]
                   (blob/put-binary! connection input)))
              write-allocation (- (.getThreadAllocatedBytes allocation-bean thread-id)
                                  write-before)
              read-before (.getThreadAllocatedBytes allocation-bean thread-id)
              range-bytes (blob/read-chunk
                           connection (:seon.blob/digest result) 1048579 4096)
              read-allocation (- (.getThreadAllocatedBytes allocation-bean thread-id)
                                 read-before)]
          (is (= payload-size (:seon.blob/size result)))
          (is (= binary-threshold
                 (alength ^bytes (:seon.blob/inline-prefix result))))
          (is (< write-allocation (* 8 1024 1024))
              (str "32 MiB staged write allocated " write-allocation " bytes"))
          (is (= 4096 (alength ^bytes range-bytes)))
          (is (< read-allocation (* 1024 1024))
              (str "4 KiB chunk read allocated " read-allocation " bytes")))))))

(deftest utf8-content-round-trips-by-its-digest
  (let [root (str "tmp/blob-test/" (random-uuid))
        opened (store/open-store! {:seon.store/dir (str root "/store")})]
    (try
      (registry/branch! {:seon.store/store opened
                         :seon.cluster.registry/from :db
                         :seon.store/branch :blob-test})
      (let [connection (store/open-branch! opened :blob-test)]
        (try
          (let [content "naïve λ result \n {:rows [1 2 3]}"
                digest (blob/put! connection content)]
            (is (= (schema/sha-256 [(.getBytes content "UTF-8")]) digest))
            (is (= content (blob/get connection digest)))
            (is (= digest (blob/put! connection content))
                "the content address is stable and an existing blob is not rewritten"))
          (finally
            (d/release connection))))
      (finally
        (store/release-store! opened)
        (support/delete-recursively! (io/file root))))))

(deftest utf8-content-round-trips-through-the-memory-backend
  (support/with-database
    {::support/fresh-store? true}
    (fn [connection]
      (let [content "memory-backed naïve λ result"
            digest (blob/put! connection content)]
        (is (= content (blob/get connection digest)))
        (is (= digest (blob/put! connection content)))))))
