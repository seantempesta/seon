(ns seon.boot-source-cost-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.fn :as functions]
            [seon.profile :as profile]
            [seon.test-support :as support]))

(deftest publication-analysis-retains-its-first-file-results
  (let [root (str "tmp/boot-source-cost-" (random-uuid))
        request {:seon.fn/root root :seon.fn/roots ["src"]
                 :seon.fn.analyzer/cache-root (str root "/resolver")}
        sources {"src/alpha.clj" "(ns boot.cost.alpha) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)"
                 "src/beta.clj" "(ns boot.cost.beta (:require [boot.cost.alpha :as a])) (defn g {:malli/schema [:=> [:cat] :int]} [] (a/f 1))"}]
    (try
      (doseq [[path source] sources]
        (let [file (io/file root path)]
          (io/make-parents file)
          (spit file source)))
      (let [held (:seon.fn.manifest/artifacts
                  (functions/build-manifest (assoc request :seon.fn/changed-paths #{"src/alpha.clj"})))
            together (functions/analyze-entities (assoc request :seon.fn/changed-paths (set (keys sources))))]
        (doseq [selection [(set (keys sources)) #{"src/beta.clj"} nil]]
          (let [measured (profile/measure
                          #(functions/analyze-entities
                            (cond-> (assoc request :seon.fn.manifest/artifacts held)
                              selection (assoc :seon.fn/changed-paths selection))))]
            (is (= together (:seon.profile/value measured)))
            (is (= 1 (profile/calls-of measured 'seon.fn.analyzer/parsed-facts))))))
      (finally (support/delete-recursively! root)))))

