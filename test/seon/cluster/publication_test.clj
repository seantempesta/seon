(ns seon.cluster.publication-test
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fn :as fn]
            [seon.fs :as fs]
            [seon.id :as id]
            [seon.operator.runtime :as runtime]
            [seon.test.cache :as cache]
            [seon.test-support :as support]))

(deftest malformed-manifest-is-not-an-incremental-basis
  (is (false? (#'cluster/valid-source-manifest?
               #:seon.fn.manifest{:artifacts {0 #:seon.fn.file{:rows {1 #:seon.ns{:name nil}}}}}))))

(deftest live-producer-admission-compares-recorded-facts-before-analysis
  (support/with-database
    (fn [connection]
      (let [root (str "tmp/publication-admission-" (id/id))
            opened (store/open-store! {:seon.store/dir (str root "/store")})
            manifest @support/source-manifest
            inputs (cache/input-digests (fs/source-directory))
            paths (fn/producer-paths manifest)
            digest (fn/toolchain-digest manifest inputs
                     (cache/toolchain-dependencies (fs/source-directory) #{".clj-kondo"}))
            host (id/id)
            [namespace path] (first paths)]
        (try
          (support/transacted! connection
            [{:seon.source/loaded-host host
              :seon.source/loaded-producer-digest digest
              :seon.source/loaded-producers
              (mapv (fn [[name file]]
                      {:seon.source/producer-namespace name
                       :seon.source/producer-path file
                       :seon.source/producer-digest (get inputs file)}) paths)}])
          (let [instance {:seon.store/store opened
                          :seon.source/loaded-host host
                          :seon.source/loaded-database (db/db connection)
                          :seon.source/loaded-state {:seon.instrument/roots {}
                                                     :seon.instrument/function-schemas {}}
                          :seon.boot/config {:seon.boot/root (str root "/data/clusters")
                                             :seon.boot/cluster-name "publication"}}
                check (fn [] (#'cluster/require-loaded-producers! opened manifest))]
            (with-redefs [runtime/running-instances (atom {"publication" instance})]
              (is (nil? (check)))
              (with-redefs [cache/input-digests (fn [_] (assoc inputs path (apply str (repeat 64 "0"))))]
                (let [refusal (try (check) nil
                                  (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
                  (is (= #{namespace} (:seon.source/producer-mismatch refusal)))
                  (is (= digest (:seon.source/loaded-producer-digest refusal)))
                  (is (not= digest (:seon.source/requested-producer-digest refusal)))
                  (is (string? (:seon.source/host-transition refusal)))))))
          (finally
            (store/release-store! opened)
            (support/delete-recursively! root)))))))
