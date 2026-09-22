(ns seon.adoption-margin-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster.process :as state]
            [seon.cluster :as cluster]
            [seon.test-support :as support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest closed-publication-observer-refuses-at-the-next-phase
  (let [writer (java.io.PrintWriter. (java.io.StringWriter.))]
    (.close writer)
    (let [failure (binding [*out* writer
                            cluster/*source-progress!* println]
                    (try
                      (#'cluster/report-source-progress! "schema declarations")
                      nil
                      (catch clojure.lang.ExceptionInfo error (ex-data error))))]
      (is (= "schema declarations" (:seon.source/progress failure))))))
