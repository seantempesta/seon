(ns seon.blob.retention-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [konserve.impl.defaults :as defaults]
            [konserve.protocols :as protocols]
            [seon.blob :as blob]
            [seon.blob.retention :as retention]
            [seon.db :as db]
            [seon.test-support :as support])
  (:import [java.util Date]))

(defn- dated-blob!
  [connection content millis]
  (let [digest (blob/put! connection content)
        store (:store @connection)]
    ; Set the real Konserve write metadata explicitly; no clock sleeps.
    (protocols/-bassoc store digest
                      (fn [_] {:key digest :type :binary :last-write (Date. millis)})
                      (.getBytes ^String content "UTF-8") {:sync? true})
    digest))

(defn- physical-bytes
  [connection digest]
  (.length (io/file (get-in @connection [:store :backing :base])
                    (defaults/key->store-key digest))))

(deftest oldest-unreferenced-blobs-go-first-and-current-references-survive
  (let [root (io/file "tmp" (str "blob-retention-" (random-uuid)))]
    (try
      (support/with-published-file-database
       root :blob-retention-test
       (fn [connection]
         (let [referenced (dated-blob! connection "referenced" 1000)
               oldest (dated-blob! connection "oldest" 2000)
               newest (dated-blob! connection "newest" 3000)
               tx (db/transact! connection
                                [{:seon.cluster.eval/result-blob referenced}])
               _ (is (not (:seon.error/kind tx)) (pr-str tx))
               _ (d/branch! connection :blob-retention-test :blob-retention-sibling)
               eid (db/q (db/db connection)
                         '[:find ?e . :in $ ?digest
                           :where [?e :seon.cluster.eval/result-blob ?digest]]
                         referenced)
               retraction (db/transact! connection
                                       [[:db/retract eid :seon.cluster.eval/result-blob referenced]])
               _ (is (not (:seon.error/kind retraction)) (pr-str retraction))
               before (+ (physical-bytes connection referenced)
                         (physical-bytes connection oldest)
                         (physical-bytes connection newest))
               budget (- before (physical-bytes connection oldest))
               request {:seon.db/connection connection
                        :seon.config.blob/max-bytes budget}
               result (retention/reclaim! request)]
           (is (= 1 (:seon.blob.retention/deleted-count result)))
           (is (= budget (:seon.blob.retention/bytes-after result)))
           (is (nil? (blob/get connection oldest)))
           (is (= "newest" (blob/get connection newest)))
           (is (= "referenced" (blob/get connection referenced)))
           (is (= 0 (:seon.blob.retention/deleted-count
                     (retention/reclaim! request))))
           (let [result (retention/reclaim!
                         (assoc request :seon.config.blob/max-bytes 1))]
             (is (= 1 (:seon.blob.retention/deleted-count result)))
             (is (pos? (:seon.blob.retention/excess-bytes result)))
             (is (= "referenced" (blob/get connection referenced)))
             (is (nil? (blob/get connection newest))))
           (d/delete-branch! connection :blob-retention-sibling)
           (let [result (retention/reclaim!
                         (assoc request :seon.config.blob/max-bytes 1))]
             (is (= 1 (:seon.blob.retention/deleted-count result)))
             (is (= 0 (:seon.blob.retention/bytes-after result)))
             (is (nil? (blob/get connection referenced))
                 "A historical reference alone does not retain bytes.")))))
      (finally
        (support/delete-recursively! root)))))
