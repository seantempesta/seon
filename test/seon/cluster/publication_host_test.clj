(ns seon.cluster.publication-host-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.db :as db]
            [seon.fs :as fs]
            [seon.id :as id]
            [seon.operator.runtime :as runtime]
            [seon.schema :as schema]
            [seon.test.cache :as cache]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Boot and publish the same tree with the recorded instance projection in one JVM."
           :seon.test/long-ms 600000}
  freshly-booted-host-publishes-its-own-tree
  (let [root (str "tmp/publication-host-" (id/id))
        name (str "publication-" (id/id))]
    (.mkdirs (io/file root))
    (try
      (support/preserving-instrumentation-state
        (fn []
          (cluster/refresh-source! root)
          (let [before (cache/input-digests (fs/source-directory))
                instance (cluster/start! {:seon.boot/root root :seon.boot/cluster-name name})]
            (try
              ;; The caller carries the test runner's projection; the recorded
              ;; generation must instead carry the booted instance's world.
              (is (nil? (cluster/record-loaded-producers! instance before)))
              (let [recorded (get @runtime/running-instances name)
                    generation (db/pull (:seon.source/loaded-database recorded)
                                 [:seon.source/loaded-producer-digest
                                  {:seon.source/loaded-producers
                                   [:seon.source/producer-namespace :seon.source/producer-path
                                    :seon.source/producer-digest]}]
                                 [:seon.source/loaded-host (:seon.source/loaded-host recorded)])]
                (is (identical? (:seon.schema/projection
                                 @(get-in instance [:seon.sci.eval/ctx :seon.sci.eval/projection-state]))
                                (db/carried-projection (:seon.source/loaded-database recorded))))
                (is (string? (:seon.source/loaded-producer-digest generation)) (pr-str generation)))
              (is (uuid? (:seon.source/commit-id (cluster/refresh-source! root))))
              (finally (cluster/stop! (get @runtime/running-instances name instance)))))))
      (finally
        (when-let [instance (get @runtime/running-instances name)]
          (cluster/stop! instance))
        (support/delete-recursively! root)))))
