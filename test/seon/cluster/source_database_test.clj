(ns seon.cluster.source-database-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.test-support :as test-support]))

(deftest ^{:seon.test/fixture-observation
           "The subject is commit-as-db against a commit-bearing database, which the ordinary in-memory branch disables."}
  source-commit-database-carries-its-own-projection
  (test-support/with-database
    {:seon.test-support/fresh-store? true}
    (fn [connection]
      (let [root (str "tmp/source-database-test/" (random-uuid))]
        (.mkdirs (io/file root))
        (let [opened (store/open-store! {:seon.store/dir (str root "/store")})]
          (try
            (let [store-value (assoc opened :seon.store/connection-object connection)
                  commit-id (db/commit-id (db/db connection))
                  result (promise)
                  thread
                  (Thread.
                   (fn []
                     (try
                       (let [database (source/database store-value commit-id)]
                         (deliver result
                                  {:query (db/q '[:find (count ?entity) .
                                                 :where [?entity :seon.fn/sym]]
                                               database)
                                   :report (seon.fn/unresolved-callers database)
                                   :projection (db/carried-projection database)}))
                       (catch Throwable failure
                         (deliver result failure)))))]
              (.start thread)
              (.join thread)
              (let [observed @result]
                (when (instance? Throwable observed)
                  (throw observed))
                (is (int? (:query observed)))
                (is (map? (:projection observed)))
                (is (int? (get-in observed [:report :seon.db/basis-t])))))
            (finally
              (store/release-store! opened)
              (test-support/delete-recursively! root))))))))
