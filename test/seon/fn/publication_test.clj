(ns seon.fn.publication-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.fn :as functions]
            [seon.db :as db]
            [seon.fn.analyzer :as analyzer]
            [seon.test-support :as support]))

(defn- write-source!
  {:malli/schema [:=> [:cat :string :string :string] :nil]}
  [root path source]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file source))
  nil)

(deftest ^{:seon.test/long "Two real indexed publications and datom comparisons, including first canonical fixture acquisition; measured 51.3 seconds in the isolated fast snapshot."
           :seon.test/long-ms 60000}
  incremental-indexing-reconciles-only-selected-file-rows
  (support/with-database
   (fn [connection]
     (let [root (str "tmp/publication-index/" (random-uuid))
           request {:seon.fn/root root :seon.fn/roots ["src"]
                    ::analyzer/cache-root (str root "/resolver")}
           observed (atom [])
           reports (atom [])
           transact db/transact!
           add-contracts @#'functions/add-contract-facts]
       (try
         (write-source! root "src/alpha.clj"
                        "(ns pub.alpha) (defn g {:malli/schema [:=> [:cat] :int]} [] 0) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)")
         (write-source! root "src/stranger.clj"
                        "(ns pub.stranger) (defn h {:malli/schema [:=> [:cat] :int]} [] 0)")
         (let [before (functions/build-manifest request)
               empty-manifest (assoc before :seon.fn.manifest/artifacts [])
               installed (functions/index!
                          {:seon.db/connection connection
                           :seon.fn/manifest before
                           :seon.fn/previous-manifest empty-manifest
                           :seon.fn/changed-paths #{"src/alpha.clj" "src/stranger.clj"}
                           :seon.source/previous-database (db/db connection)})]
           (is (nil? (:seon.error/kind installed)) (pr-str installed))
           (is (every? #(some? (:db/id (db/pull (db/db connection) '[:db/id] [:seon.fn/sym %])))
                       '[pub.alpha/f pub.alpha/g pub.stranger/h]))
           (let [prior (db/db connection)
                 stranger (db/pull prior '[*] [:seon.fn/sym 'pub.stranger/h])]
             (write-source! root "src/alpha.clj"
                            "(ns pub.alpha) (defn g {:malli/schema [:=> [:cat] :int]} [] 0) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
             (let [after (functions/build-manifest (assoc request :seon.fn/previous-manifest before :seon.source/previous-database prior))
                   result (with-redefs-fn
                            {#'db/transact!
                             (fn [conn request]
                               (let [report (transact conn request)]
                                 (swap! reports conj report)
                                 report))
                             #'functions/add-contract-facts
                             (fn [rows progress projection]
                               (swap! observed into rows)
                               (add-contracts rows progress projection))}
                            #(functions/index!
                              {:seon.db/connection connection :seon.fn/manifest after
                               :seon.fn/previous-manifest before :seon.fn/changed-paths #{"src/alpha.clj"}
                               :seon.source/previous-database prior}))]
               (is (nil? (:seon.error/kind result)) (pr-str result))
               (is (= #{'pub.alpha/f} (into #{} (keep :seon.fn/sym) @observed)))
               (let [changed-entities (into #{} (map :e) (mapcat :tx-data @reports))
                     unchanged (db/pull prior '[*] [:seon.fn/sym 'pub.alpha/g])
                     arity-ids (into #{} (map :db/id) (:seon.fn/arities
                                                     (db/pull prior '[*] [:seon.fn/sym 'pub.alpha/f])))]
                 (let [identities (db/identity-attributes prior)
                       roots (into #{}
                                   (filter #(seq (db/pull (db/db connection) identities %)))
                                   changed-entities)
                       function (db/pull prior '[:db/id {:seon.fn/file [:db/id]}]
                                         [:seon.fn/sym 'pub.alpha/f])]
                   (is (= #{(:db/id function) (get-in function [:seon.fn/file :db/id])}
                          roots)))
                 (is (not (contains? changed-entities (:db/id stranger))))
                 (is (not (contains? changed-entities (:db/id unchanged))))
                 (is (seq arity-ids))
                 (is (not-any? changed-entities arity-ids))
                 (is (contains? changed-entities
                                (:db/id (db/pull prior '[:db/id] [:seon.fn/sym 'pub.alpha/f])))))
               (is (= stranger (db/pull (db/db connection) '[*] [:seon.fn/sym 'pub.stranger/h])))
               (is (= "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"
                      (:seon.fn/source (db/pull (db/db connection) '[:seon.fn/source]
                                              [:seon.fn/sym 'pub.alpha/f])))))))
         (finally (support/delete-recursively! root)))))))
