(ns seon.blob-threshold-test
  "Settlement preserves shown text independently of the blob threshold."
  (:require [clojure.test :refer [deftest is]]
            [seon.blob :as blob]
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
