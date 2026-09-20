(ns seon.cluster.publication-host-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.id :as id]
            [seon.operator.runtime :as runtime]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Boot and publish the same tree in the existing test JVM."
           :seon.test/long-ms 600000}
  freshly-booted-host-publishes-its-own-tree
  (let [root (str "tmp/publication-host-" (id/id))
        name (str "publication-" (id/id))]
    (.mkdirs (io/file root))
    (try
      (support/preserving-instrumentation-state
        (fn []
          (cluster/refresh-source! root)
          (let [instance (cluster/start! {:seon.boot/root root :seon.boot/cluster-name name})]
            (try
              (is (uuid? (:seon.source/commit-id (cluster/refresh-source! root))))
              (finally (cluster/stop! (get @runtime/running-instances name instance)))))))
      (finally
        (when-let [instance (get @runtime/running-instances name)]
          (cluster/stop! instance))
        (support/delete-recursively! root)))))
