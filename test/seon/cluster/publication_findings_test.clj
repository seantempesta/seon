(ns seon.cluster.publication-findings-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.fn :as functions]
            [seon.fn.analyzer :as analyzer]
            [seon.test-support :as support]))

(deftest publication-reports-finding-count-and-delta-without-a-roster
  (let [root (str "tmp/publication-findings/" (random-uuid))
        file (io/file root "src/pub/sample.clj")
        request {:seon.fn/root root :seon.fn/roots ["src"]
                 ::analyzer/cache-root (str root "/resolver")}
        output (atom [])]
    (try
      (io/make-parents file)
      (spit file "(ns pub.sample) (defn chosen [unused] 1)")
      (let [before (functions/build-manifest request)
            findings (filter :seon.lint/id (mapcat :seon.fn.file/rows (:seon.fn.manifest/artifacts before)))
            _ (spit file "(ns pub.sample) (defn chosen [used] used)")
            after (functions/build-manifest (assoc request :seon.fn/previous-manifest before))]
        (is (= 1 (count findings)))
        (is (= :unused-binding (:seon.lint/type (first findings))))
        (is (= [:seon.fn/sym 'pub.sample/chosen] (:seon.lint/fn (first findings))))
        (with-bindings {#'cluster/*source-progress!* #(swap! output conj %)}
          (#'cluster/report-analysis-warnings! before after)
          (#'cluster/report-analysis-warnings! after after)
          (#'cluster/report-analysis-warnings! nil before))
        (is (= ["findings: 0; added=0; resolved=1"
                "findings: 0; added=0; resolved=0"
                "findings: 1; delta unavailable: no corresponding previous manifest"]
               @output)))
      (finally (support/delete-recursively! root)))))
