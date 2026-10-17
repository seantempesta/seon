(ns seon.publication-cost-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cache :as cache]
            [seon.cluster :as cluster]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fs :as fs]
            [seon.fn.analyzer :as analyzer]
            [seon.profile :as profile]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Clones a publication fixture and publishes eight large namespaces three times, including first-use costs."
           :seon.test/long-ms 60000}
  publication-cost-follows-changed-inputs
  (let [root (str "tmp/publication-cost-" (random-uuid))
        directory (str root "/checkout")
        parent (support/execution-handle db/*conn*)
        paths ["src/seon/cluster.clj" "src/seon/fn.clj" "src/seon/db.clj"
               "src/seon/turn.clj" "src/seon/instrument.clj" "src/seon/context.clj"
               "src/seon/ref.clj" "src/seon/schema.clj"]]
    (try
      (doseq [path ["src" "test" "script" "resources" ".clj-kondo"]]
        (#'support/clone-directory! (io/file (fs/source-directory) path)
                                   (io/file directory path)))
      (doseq [path ["deps.edn" "dependency-pins.txt" "test-input-paths.txt"]]
        (io/copy (io/file (fs/source-directory) path) (io/file directory path)))
      (#'support/clone-directory! (io/file analyzer/cache-directory)
                                 (io/file root "resolver-cache"))
      (doseq [path paths]
        (let [file (io/file directory path)]
          (.setWritable file true)
          (spit file (str "; Publication cost probe.\n" (slurp file)))))
      (let [measurements
            (mapv (fn [_]
                    (let [held (store/open-overlay! (:seon.store/store parent))]
                      (try
                        (profile/measure
                         #(#'cluster/full-source-refresh!
                            root held
                            (assoc (#'cluster/publication-roots)
                                   :seon.fn/root directory
                                   :seon.source/changed-paths paths
                                   :seon.source/branch :publication-cost
                                   :seon.fn.analyzer/cache-root (str root "/resolver-cache"))))
                        (finally (store/release-store! held)))))
                  (range 3))]
        (doseq [measured measurements]
          (is (true? (:seon.source/built? (:seon.profile/value measured)))))
        (is (< (reduce + (map :seon.profile/ms measurements)) 12000))
        (doseq [measured (rest measurements)]
          (is (< (:seon.profile/alloc-bytes measured) 3800000000)))
        (is (not ((schema/projection-validator (schema/handed-projection) :seon.cache/discipline-warning)
                  (get-in (cache/status) [:seon.cache/kinds :seon.program/source-reading])))))
      (finally (support/delete-recursively! root)))))
