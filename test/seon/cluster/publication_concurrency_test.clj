(ns seon.cluster.publication-concurrency-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster.source :as source]
            [seon.cluster.source-test :as source-test]
            [seon.test-support :as support])
  (:import [java.util.concurrent CountDownLatch]))

(deftest ^{:seon.test/long "Clone the canonical published store and publish two concurrent requests through the real branch writer."
           :seon.test/long-ms 15000}
  concurrent-publications-return-one-committed-head
  (#'source-test/with-store
   (fn [opened]
     (let [start (CountDownLatch. 1)
           ready (CountDownLatch. 2)
           requests (mapv (fn [_]
                            (future
                              (.countDown ready)
                              (support/await-event! start "concurrent source publication")
                              (#'source-test/publish opened (apply str (repeat 64 "a")))))
                          (range 2))]
       (try
         (support/await-event! ready "both source requests entered")
         (.countDown start)
         (let [replies (mapv #(support/await-event! % "source publication completed") requests)
               head (:seon.source/commit-id (source/current opened))]
           (is (= [head head] (mapv :seon.source/commit-id replies)))
           (is (= #{true false} (set (map :seon.source/built? replies))))
           (is (empty? (#'source-test/scratch-branches opened))))
         (finally
           (.countDown start)
           (doseq [request requests]
             (when-not (realized? request) (future-cancel request)))))))))
