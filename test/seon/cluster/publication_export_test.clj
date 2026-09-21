(ns seon.cluster.publication-export-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fs :as fs]
            [seon.fn :as functions]
            [seon.id :as id]
            [seon.schema]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Reconcile a canonical published base with checkout declarations and reopen its export without a caller projection."
           :seon.test/long-ms 600000}
  export-identifies-the-committed-program-without-a-live-cluster
  (let [root (str "tmp/publication-export-" (id/id))
        destination (str root "/export")]
    (.mkdirs (io/file root))
    (try
      (support/populate-published-root!
       root {:seon.test/fixture-observation
             "An explicit checkout export must refresh an existing publication, not treat it as an empty edit."})
      (with-bindings {(ns-resolve 'seon.schema '*projection*) nil
                      (ns-resolve 'seon.schema '*projection-state*) nil}
        (let [exported (cluster/publication-base! root (fs/source-directory) destination)]
          (is (= destination exported))))
      (let [provenance (edn/read-string (slurp (io/file destination "provenance.edn")))
            artifact (edn/read-string (slurp (cluster/source-artifact-file destination)))
            opened (store/open-store! {:seon.store/dir (str destination "/data/store")})]
        (try
          (let [published (source/current opened)
                database (source/database opened (:seon.source/commit-id published))]
            (try
              (is (= (:seon.source/commit-id published) (:seon.source/commit-id artifact)))
              (is (= (:seon.source/digest artifact)
                     (:seon.test.run/program-digest provenance)
                     (db/q database '[:find ?digest . :where [_ :seon.source/digest ?digest]])))
              (is (= (db/basis-t database) (:seon.test.run/basis-t provenance)))
              (is (= (:malli/schema (meta #'functions/published-index-rows))
                     (edn/read-string
                      (:seon.fn/spec
                       (db/pull database [:seon.fn/spec]
                                [:seon.fn/sym 'seon.fn/published-index-rows]))))
                  "the exported contract comes from this checkout, not the previous base")
              (finally (d/release-materialized-db database))))
          (finally (store/release-store! opened))))
      (finally (support/delete-recursively! root)))))
