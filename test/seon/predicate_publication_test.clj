(ns seon.predicate-publication-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            [sci.core :as sci]
            [seon.cluster :as cluster]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.db :as db]
            [seon.fn :as function]
            [seon.schema :as schema]
            [seon.shell :as shell]
            [seon.test-support :as support]))

(deftest ^{:seon.test/fixture-observation "A new cluster must boot from a published predicate rename while preserving the old identity tombstone."
           :seon.test/long "Two complete publications and a real cold boot exercise publication across a predicate rename."
           :seon.test/long-ms 600000} a-published-predicate-rename-forks-and-boots-with-its-tombstone
  (support/preserving-instrumentation-state
   (fn []
     (let [root (str "tmp/predicate-publication/" (random-uuid))
           namespace-name (symbol (str "predicate.publication.p" (System/nanoTime)))
           old-symbol (symbol (str namespace-name) "stdin?")
           old-ref [:seon.fn/sym (str old-symbol)]
           instance (atom nil)]
       (.mkdirs (io/file root))
       (create-ns namespace-name)
       (intern namespace-name 'stdin? shell/stdin?)
       (schema/register-core-predicate! old-symbol shell/stdin?)
       (try
         (support/populate-published-root! root)
         (let [store-dir (:seon.boot/store-dir
                          (cluster/resolve-bootstrap {:seon.boot/root root}))]
           (with-open [opened (support/closeable
                               (store/open-store! {:seon.store/dir store-dir})
                               store/release-store!)]
             (let [published (source/current @opened)
                   database (source/database @opened (:seon.source/commit-id published))
                   old-function
                   (-> (db/pull database
                                [:seon.fn/source :seon.fn/spec :seon.fn/arglists
                                 :seon.fn/doc :seon.fn/private?]
                                [:seon.fn/sym "seon.shell/stdin?"])
                       (dissoc :db/id)
                       (assoc :seon.fn/sym (str old-symbol)
                              :seon.fn/ns [:seon.ns/name namespace-name]
                              :seon.schema.admission/source :core))
                   old-schema
                   (-> (db/pull database
                                [:seon.schema/key :seon.schema/form
                                 :seon.schema/generatable? :seon.schema.admission/source]
                                [:seon.schema/key :my.shell/stdin])
                       (dissoc :db/id)
                       (update :seon.schema/form
                               #(pr-str (walk/postwalk-replace
                                         {'seon.shell/stdin? old-symbol}
                                         (edn/read-string %)))))
                   first-publication
                   (source/upsert!
                    {:seon.store/store @opened
                     :seon.source/expected-commit-id (:seon.source/commit-id published)
                     :seon.source/digest (apply str (repeat 64 "a"))

                     :seon.source/upsert-rows
                     [{:seon.ns/name namespace-name
                       :seon.ns/source (pr-str (list 'ns namespace-name))
                       :seon.schema.admission/source :core}
                      old-function old-schema]})
                   before (source/database @opened (:seon.source/commit-id first-publication))
                   old-id (:db/id (db/pull before [:db/id] old-ref))]
               (is (int? old-id))
               (is (seq (:seon.fn/source (db/pull before [:seon.fn/source] old-ref))))
               (is (contains? (:seon.schema.projection/predicate-functions
                               (schema/projection-from-database before)) old-symbol))
               ;; The canonical manifest is the renamed source: its stdin
               ;; schema names seon.shell/stdin?, and the old definition is
               ;; absent. Reconciliation retains its identity on publication.
               (let [renamed
                     (source/upsert!
                      {:seon.store/store @opened
                       :seon.source/expected-commit-id (:seon.source/commit-id first-publication)
                       :seon.source/digest (apply str (repeat 64 "b"))

                       :seon.source/upsert-rows []
                       :seon.fn/manifest (function/build-manifest
                                          {:seon.fn/roots function/source-roots})})
                     after (source/database @opened (:seon.source/commit-id renamed))]
                 (remove-ns namespace-name)
                 (is (= {:db/id old-id :seon.fn/sym (str old-symbol)}
                        (db/pull after '[*] old-ref)))
                 (is (contains? (set (source/deleted-identities after)) old-ref))
                 (is (not (contains? (:seon.schema.projection/predicate-functions
                                      (schema/projection-from-database after)) old-symbol)))))))
         (reset! instance (cluster/start! {:seon.boot/root root
                                           :seon.boot/cluster-name "predicate-rename"}))
         (is (nat-int? (:seon.boot/ready-ms @instance)))
         (let [ctx (:seon.sci.eval/ctx @instance)]
           (is (nil? (sci/resolve ctx old-symbol)))
           (is (some? (sci/resolve ctx 'seon.shell/stdin?))))
         (finally
           (when @instance (cluster/stop! @instance))
           (when (find-ns namespace-name) (remove-ns namespace-name))
           (support/delete-recursively! root)))))))
