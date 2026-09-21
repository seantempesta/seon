(ns seon.cluster.publication-adoption-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.db :as db]
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
  freshly-forked-host-already-has-its-published-source
  (let [root (str "tmp/publication-host-" (id/id))
        name (str "publication-" (id/id))]
    (.mkdirs (io/file root))
    (try
      (support/preserving-instrumentation-state
        (fn []
          (cluster/refresh-source! root)
          (let [instance (boot/start! {:seon.boot/root root :seon.boot/cluster-name name})
                store (:seon.store/store instance)
                published (source/current store)
                operations (atom [])
                phases (atom [])
                thread (Thread/currentThread)
                observe (fn [operation callable]
                          (fn [& args]
                            (when (identical? thread (Thread/currentThread))
                              (swap! operations conj operation))
                            (apply callable args)))]
            (try
              (is (= (:seon.source/commit-id published)
                     (:seon.source/commit-id
                      (db/pull (db/db (:seon.boot/cluster-connection instance))
                               [:seon.source/commit-id] [:seon.cluster/name name]))))
              (is (false? (:seon.cluster/created?
                           (registry/ensure-cluster!
                            {:seon.store/store store :seon.boot/cluster-name name
                             :seon.source/commit-id (:seon.source/commit-id published)}))))
              (with-redefs-fn
                {#'db/transact! (observe :transact db/transact!)
                 #'cluster/load-development-definitions!
                 (observe :reload @#'cluster/load-development-definitions!)}
                #(with-bindings {#'cluster/*source-progress!* (fn [phase] (swap! phases conj phase))}
                   (is (= (:seon.source/commit-id published)
                          (:seon.source/commit-id (cluster/refresh-source! root [] name))))))
              (is (empty? @operations) (pr-str @operations))
              (is (some #{"development cluster converged"} @phases))
              (finally (boot/stop! (get @runtime/running-instances name instance)))))))
      (finally
        (when-let [instance (get @runtime/running-instances name)]
          (boot/stop! instance))
        (support/delete-recursively! root)))))
