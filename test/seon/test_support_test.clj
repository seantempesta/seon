(ns seon.test-support-test
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.fn :as seon.fn]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support]))

(deftest a-canonical-database-is-the-production-source-population
  (test-support/with-database
    (fn [connection]
      (let [database @connection
            packaged-forms (schema.edn/packaged-forms)
            installed
            (into #{} (filter keyword?) (keys (:schema database)))
            expected-schema-keys
            (into
             (into #{}
                   (map :seon.schema/key)
                   (schema/canonical-schema-rows packaged-forms))
             (keep :seon.schema/key)
             (seon.fn/rows {:seon.fn/roots seon.fn/source-roots}))
            actual-schema-keys
            (d/q
             '[:find [?key ...]
               :where
               [_ :seon.schema/key ?key]]
             database)]
        (is (every? installed
                    (schema/canonical-database-attributes packaged-forms)))
        (is (not (contains? installed :seon.schema/created-at)))
        (is (every? #(not (contains? % :seon.schema/created-at))
                    (schema/canonical-schema-rows packaged-forms)))
        (is (= expected-schema-keys (set actual-schema-keys)))
        (is (= #{cluster/boot-process-identity
                 config/managing-process-identity}
               (set
                (d/q
                 '[:find [?process-id ...]
                   :where
                   [?process :seon.db.process/id ?process-id]]
                 database))))
        (let [before (:max-tx @connection)]
          ((ns-resolve 'seon.cluster 'accrete-schema-population!)
           connection nil)
          (is (= before (:max-tx @connection))
              "clock-free schema reconciliation is idempotent"))))))

(deftest config-reconciliation-cannot-retract-the-schema-population
  (test-support/with-database
    (fn [connection]
      (let [before
            (d/q
             '[:find [?key ...]
               :where
               [_ :seon.schema/key ?key]]
             @connection)
            result
            (config/apply!
             {:seon.config/connection connection
              :seon.config/manifest (config/defaults)
              :seon.boot/cluster-name "fixture-proof"})
            after
            (d/q
             '[:find [?key ...]
               :where
               [_ :seon.schema/key ?key]]
             @connection)]
        (is (= 1 (:seon.reconcile/operations result)))
        (is (= (set before) (set after)))))))

(deftest explicit-synthetic-schema-rows-extend-only-that-database
  (let [options
        {::test-support/extra-schema
         (test-support/file-store-probe-schema ::marker)}]
    (is (= [#{"installed"} [false #{}]]
           [(test-support/with-database
              options
              (fn [connection]
                (d/transact connection [{::marker "installed"}])
                (test-support/file-store-markers connection ::marker)))
            (test-support/with-database
              (fn [connection]
                [(contains? (:schema @connection) ::marker)
                 (test-support/file-store-markers connection ::marker)]))])
        "a released lease is rebranched from the immutable base; neither its
         child-only schema state nor its rows can leak into the next test")))

(deftest shared-support-observes-events-refusals-and-cleanup
  (let [events (async/chan 1)
        path (str "tmp/test-support/" (random-uuid))
        file (java.io.File. path "nested/value.edn")]
    (.mkdirs (.getParentFile file))
    (spit file "{}")
    (async/>!! events ::published)
    (is (= ::published
           (test-support/await-event! events ::published)))
    (is (= {::rule ::refused}
           (test-support/refusal-data
            #(throw (ex-info "refused" {::rule ::refused})))))
    (test-support/delete-recursively! path)
    (is (not (.exists (java.io.File. path))))))

(deftest shared-property-reporting-is-a-clojure-test-assertion
  (test-support/assert-check!
   (tc/quick-check
    10
    (prop/for-all [value gen/int]
      (= value value))
    :seed 20260728)))

(deftest recursive-cleanup-never-follows-a-symlink-out-of-tmp
  ;; The 2026-07-29 data-loss incident: a scratch root under tmp/ linked the
  ;; source tree for its classpath, and cleanup walked the link and deleted 55
  ;; tracked paths. A sandbox check on the ROOT is worthless if the walk can
  ;; leave the sandbox, so the sentinel below must survive its own link's
  ;; deletion.
  (let [scratch (java.nio.file.Files/createTempDirectory
                 (.toPath (io/file "tmp"))
                 "cleanup-symlink-proof"
                 (make-array java.nio.file.attribute.FileAttribute 0))
        outside (java.nio.file.Files/createTempDirectory
                 (.toPath (io/file "tmp"))
                 "cleanup-sentinel"
                 (make-array java.nio.file.attribute.FileAttribute 0))
        sentinel (.resolve outside "must-survive.txt")
        link (.resolve scratch "linked-elsewhere")
        nofollow (into-array java.nio.file.LinkOption
                             [java.nio.file.LinkOption/NOFOLLOW_LINKS])]
    (try
      (spit (.toFile sentinel) "do not delete me")
      (java.nio.file.Files/createSymbolicLink
       link outside (make-array java.nio.file.attribute.FileAttribute 0))
      (test-support/delete-recursively! (str scratch))
      (is (not (java.nio.file.Files/exists (.toPath (.toFile scratch)) nofollow))
          "the scratch directory itself is gone")
      (is (java.nio.file.Files/exists sentinel nofollow)
          "a file reached only through a symlink SURVIVES cleanup")
      (is (java.nio.file.Files/exists outside nofollow)
          "the link's target directory survives; only the link was removed")
      (finally
        (test-support/delete-recursively! (str outside))))))
