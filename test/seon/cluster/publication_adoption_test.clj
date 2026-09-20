(ns seon.cluster.publication-adoption-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.dev.docstring :as docstring]
            [seon.fs :as fs]
            [seon.id :as id]
            [seon.instrument :as instrument]
            [seon.operator.runtime :as runtime]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Acquire the canonical fixture before checking loaded-definition restoration; 121.3 s with fixture construction in the slice 1 fast run."
           :seon.test/long-ms 600000}
  failed-loaded-definition-scope-restores-arming
  (support/with-database
   (fn [_]
     (let [before (instrument/instrumented)
           original @#'docstring/check-file
           failure (try
                     (support/preserving-instrumentation-state
                      (fn []
                        (load-file (str (io/file (fs/source-directory)
                                                "script/seon/dev/docstring.clj")))
                        (throw (ex-info "Failure after loaded definitions changed."
                                        {::injected true}))))
                     (catch Exception thrown thrown))]
       (is (::injected (ex-data failure)))
       (is (not (identical? original @#'docstring/check-file)))
       (is (= before (instrument/instrumented)))
       (is (not-any? var? (tree-seq coll? seq (:malli/schema (meta #'docstring/check-file)))))
       (is (:seon.dev.docstring/clean?
            (docstring/check-file {:seon.dev.docstring/file-path
                                   (str (io/file (fs/source-directory)
                                                "tmp/absent-docstring-fixture.clj"))})))))))

(deftest ^{:seon.test/long "Boot and adopt the same published tree in the existing test JVM."
           :seon.test/long-ms 600000}
  freshly-booted-host-adopts-its-own-tree
  (let [root (str "tmp/publication-host-" (id/id))
        name (str "publication-" (id/id))]
    (.mkdirs (io/file root))
    (try
      (support/preserving-instrumentation-state
        (fn []
          (cluster/refresh-source! root)
          (let [instance (cluster/start! {:seon.boot/root root :seon.boot/cluster-name name})]
            (try
              (is (uuid? (:seon.source/commit-id (cluster/refresh-source! root [] name))))
              (finally (cluster/stop! (get @runtime/running-instances name instance)))))))
      (finally
        (when-let [instance (get @runtime/running-instances name)]
          (cluster/stop! instance))
        (support/delete-recursively! root)))))
