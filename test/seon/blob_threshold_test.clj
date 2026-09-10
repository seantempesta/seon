(ns seon.blob-threshold-test
  "Settlement preserves shown text independently of the blob threshold."
  (:require [clojure.test :refer [deftest is]]
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
