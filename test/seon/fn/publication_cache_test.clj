(ns seon.fn.publication-cache-test
  (:require [clj-kondo.core :as kondo]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.fn.analyzer :as analyzer]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Canonical fixture builds the complete program once (measured about two minutes); the regression uses real cold/partial clj-kondo and complete-artifact parity."
           :seon.test/long-ms 600000}
  lint-files-follow-changes-and-direct-callers
  (support/with-database
   (fn [connection]
     (let [root (str "tmp/publication-cache/" (random-uuid))
           request {:seon.fn/root root :seon.fn/roots ["src"]}
           write! (fn [path source]
                    (let [file (io/file root "src" path)]
                      (io/make-parents file)
                      (spit file source)))
           original "(ns pub.alpha) (defn f \"Original.\" {:malli/schema [:=> [:cat :int] :int]} [x] x)"
           observed (atom [])
           run! kondo/run!]
       (try
         (write! "alpha.clj" original)
         (write! "beta.clj" "(ns pub.beta (:require [pub.alpha :as a])) (defn g {:malli/schema [:=> [:cat] :int]} [] (a/f 1))")
         (write! "gamma.clj" "(ns pub.gamma (:require [pub.beta :as b])) (defn h {:malli/schema [:=> [:cat] :int]} [] (b/g))")
         (write! "stranger.clj" "(ns pub.stranger) (defn h {:malli/schema [:=> [:cat] :int]} [] 0)")
         (with-redefs [kondo/run!
                       (fn [options]
                         (is (true? (:cache options)))
                         (swap! observed into
                                (map #(.getName (io/file %)) (:lint options)))
                         (run! options))]
           (let [before (functions/build-manifest request)]
             (is (= ["alpha.clj" "beta.clj" "gamma.clj" "stranger.clj"] (sort @observed))
                 "Cold analysis lints every input exactly once.")
             (support/transacted!
              connection
              (functions/reconcile-tx (db/db connection)
                (vec (mapcat :seon.fn.file/rows (:seon.fn.manifest/artifacts before))) []))
             (reset! observed [])
             (write! "alpha.clj" "(ns pub.alpha) (defn f \"Changed.\" {:malli/schema [:=> [:cat :int] :int]} [x] x)")
             (let [after (functions/build-manifest
                          (assoc request :seon.fn/previous-manifest before
                                         :seon.source/previous-database (db/db connection)))]
               (is (= ["alpha.clj" "beta.clj"] (sort @observed))
                   "The caller's caller and unrelated file are not lint inputs.")
               (reset! observed [])
               (is (= after (functions/build-manifest
                              (assoc request :seon.fn/previous-manifest after))))
               (is (empty? @observed) "No source change performs zero lint.")
               (let [complete (functions/build-manifest
                               (assoc request ::analyzer/cache-root (str root "/complete-resolver")))]
                 (is (= (:seon.fn.manifest/artifacts complete)
                        (:seon.fn.manifest/artifacts after)))))))
         (finally (support/delete-recursively! root)))))))
