(ns seon.blob-threshold-test
  "Settlement preserves shown text independently of the blob threshold."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.sci.eval :as sci.eval]
            [seon.turn :as turn]
            [seon.test-support :as support]))

(deftest settlement-preserves-every-shown-byte-without-a-result-blob
  (support/with-database
   (fn [connection]
     (doseq [length [420 160000]]
       (let [evaluation {:seon.eval/shown (apply str (repeat length \r))}
             [settled _ stages]
             (turn/settlement-projection
              (support/cluster-handle {:seon.db/connection connection}) evaluation)]
         (is (= evaluation settled))
         (is (empty? stages)))))))

(deftest text-at-threshold-is-inline-over-is-staged
  (support/with-database
   (fn [connection]
     (doseq [[label text staged?]
             [["at the threshold" (apply str (repeat 4096 \a)) false]
              ["one byte over" (apply str (repeat 4097 \a)) true]
              ["two-byte characters over in bytes" (apply str (repeat 2049 \u00e9)) true]]]
       (let [{fact :seon.blob/fact writes :seon.blob/staged-writes}
             (blob/inline-or-stage connection text :t/text :t/size :t/blob)]
         (is (= (blob/utf8-size text) (:t/size fact)) label)
         (is (= staged? (contains? fact :t/blob)) label)
         (is (= (not staged?) (contains? fact :t/text)) label)
         (is (= (if staged? 1 0) (count writes)) label)
         (when staged?
           (is (= (blob/digest text) (:t/blob fact)) label)))))))

(deftest ^{:seon.test/long "Two cluster configurations share one file fixture; its measured acquisition budget is declared by the fixture cost proof."
           :seon.test/long-ms 60000}
  blob-threshold-belongs-to-the-connections-cluster
  (let [root (io/file "tmp" "blob-cluster-threshold" (str (random-uuid)))]
    (try
      (support/with-published-file-database
       root :blob-cluster-threshold
       (fn [connection]
         (doseq [[cluster-name threshold] [["small-blobs" 8] ["large-blobs" 16]]]
           (support/seed-cluster! connection cluster-name
                                  {:seon.config.eval.result/blob-threshold threshold}))
         (doseq [[cluster-name threshold staged?] [["small-blobs" 8 true] ["large-blobs" 16 false]]]
           (let [database (db/db connection)]
             (db/carry-connection-projection-state!
              connection (sci.eval/projection-state database (db/carried-projection database) cluster-name)))
           (with-open [input (java.io.ByteArrayInputStream. (byte-array (range 12)))]
             (let [write (blob/stage-binary! connection input)]
               (is (= (min 12 threshold) (alength ^bytes (:seon.blob/inline-prefix write))))
               (is (= staged? (contains? write :seon.blob/staged-path)))))
           (let [{fact :seon.blob/fact} (blob/inline-or-stage connection "twelve-bytes" :t/text :t/size :t/blob)]
             (is (= staged? (contains? fact :t/blob)))))))
      (finally (support/delete-recursively! root)))))
