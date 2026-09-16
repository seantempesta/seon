(ns cloned-base-path-identity-probe-2026-09-17
  (:require [clojure.java.io :as io]
            [seon.cluster :as cluster]
            [seon.cluster.export :as export]
            [seon.fn.analyzer :as analyzer]
            [seon.fs :as fs]
            [seon.operator.runtime :as runtime]
            [seon.test-support :as test-support]))

(defn measure!
  "Observe a real exported publication through its first relocated refresh."
  [cluster-name operator-root]
  (let [instance (get @runtime/running-instances cluster-name)
        root (str (io/file operator-root "clone"))
        checkout (str (io/file root "checkout"))
        directory (.getCanonicalPath (io/file checkout))
        roots (assoc (#'cluster/publication-roots) :seon.fn/root directory)
        calls (atom [])
        analyze analyzer/analyze
        source-path "src/seon/ai/tokens.cljc"
        path (io/file checkout source-path)]
    (export/export! {:seon.store/store (:seon.store/store instance)
                     :seon.export/parent-dir (str (io/file root "data"))})
    (io/make-parents (cluster/source-artifact-file root))
    (io/copy (io/file operator-root "data/clusters/build/current-src.edn")
             (io/file (cluster/source-artifact-file root)))
    (doseq [relative (:seon.source/roots roots)]
      (let [source (io/file (fs/source-directory) relative)
            target (io/file checkout relative)]
        (io/make-parents target)
        (if (.isDirectory source)
          (#'test-support/clone-directory! source target)
          (io/copy source target))))
    (with-redefs-fn
      {#'cluster/publication-roots (constantly roots)
       #'analyzer/analyze
       (fn [request]
         (swap! calls into (keys (:seon.fn.analyzer/sources request)))
         (analyze request))}
      (fn []
        (let [unchanged (cluster/refresh-source! root [])
              initial @calls]
          (spit path (str (slurp path) "\n; Relocated checkout edit.\n"))
          (let [changed (cluster/refresh-source! root [(.getCanonicalPath path)])]
            {:probe/unchanged-built? (:seon.source/built? unchanged)
             :probe/unchanged-analysis-paths initial
             :probe/edited-analysis-paths (mapv #(fs/relative-path directory %) @calls)
             :probe/changed-built? (:seon.source/built? changed)
             :probe/unchanged-commit (:seon.source/commit-id unchanged)
             :probe/changed-commit (:seon.source/commit-id changed)}))))))
