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

(deftest ^{:seon.test/long "Two real indexed publications and final database comparisons; measured 5.7 seconds after canonical fixture acquisition."
           :seon.test/long-ms 60000}
  incremental-indexing-reconciles-only-selected-file-rows
  (support/with-database
   (fn [connection]
     (let [root (str "tmp/publication-index/" (random-uuid))
           request {:seon.fn/root root :seon.fn/roots ["src"]
                    ::analyzer/cache-root (str root "/resolver")}
           observed (atom [])
           add-contracts @#'functions/add-contract-facts]
       (try
         (write-source! root "src/alpha.clj"
                        "(ns pub.alpha) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)")
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
           (let [prior (db/db connection)
                 stranger (db/pull prior '[*] [:seon.fn/sym 'pub.stranger/h])]
             (write-source! root "src/alpha.clj"
                            "(ns pub.alpha) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))")
             (let [after (functions/build-manifest (assoc request :seon.fn/previous-manifest before :seon.source/previous-database prior))
                   result (with-redefs-fn
                            {#'functions/add-contract-facts
                             (fn [rows progress projection]
                               (swap! observed into rows)
                               (add-contracts rows progress projection))}
                            #(functions/index!
                              {:seon.db/connection connection :seon.fn/manifest after
                               :seon.fn/previous-manifest before :seon.fn/changed-paths #{"src/alpha.clj"}
                               :seon.source/previous-database prior}))]
               (is (nil? (:seon.error/kind result)) (pr-str result))
               (is (= #{'pub.alpha/f} (into #{} (keep :seon.fn/sym) @observed)))
               (is (= stranger (db/pull (db/db connection) '[*] [:seon.fn/sym 'pub.stranger/h])))
               (is (= "(defn f {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))"
                      (:seon.fn/source (db/pull (db/db connection) '[:seon.fn/source]
                                              [:seon.fn/sym 'pub.alpha/f])))))))
         (finally (support/delete-recursively! root)))))))
