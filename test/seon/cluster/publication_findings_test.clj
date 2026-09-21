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
            findings (filterv :seon.lint/id (mapcat :seon.fn.file/rows (:seon.fn.manifest/artifacts before)))
            _ (spit file "(ns pub.sample) (defn chosen [used] used)")
            after (functions/build-manifest request)
            remaining (filterv :seon.lint/id (mapcat :seon.fn.file/rows (:seon.fn.manifest/artifacts after)))]
        (is (= 1 (count findings)))
        (is (= :unused-binding (:seon.lint/type (first findings))))
        (is (= [:seon.fn/sym 'pub.sample/chosen] (:seon.lint/fn (first findings))))
        (with-bindings {#'cluster/*source-progress!* #(swap! output conj %)}
          (#'cluster/report-analysis-warnings! findings remaining)
          (#'cluster/report-analysis-warnings! remaining remaining)
          (#'cluster/report-analysis-warnings! nil findings))
        (is (= ["findings in analyzed files: 0; added=0; resolved=1"
                "findings in analyzed files: 0; added=0; resolved=0"
                "findings in analyzed files: 1; delta unavailable: no corresponding previous manifest"]
               @output)))
      (finally (support/delete-recursively! root)))))
