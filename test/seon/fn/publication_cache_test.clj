(ns seon.fn.publication-cache-test
  (:require [clj-kondo.core :as kondo]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.fn :as functions]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Canonical fixtures plus real cold/partial clj-kondo and declaration/finding transactions, including arity/privacy refusals."
           :seon.test/long-ms 600000}
  lint-direct-callers-after-committed-declaration-changes
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
               (let [result (functions/index!
                (assoc request :seon.db/connection connection
                               :seon.schema/projection (db/carried-projection (db/db connection))
                               :seon.source/previous-database (db/db connection)
                               :seon.fn/previous-manifest before
                               :seon.fn/manifest after
                               :seon.fn/changed-paths #{"src/alpha.clj"}))]
                 (is (not (:seon.error/at result)) (pr-str result)))
               (is (= ["alpha.clj" "beta.clj"] @observed)
                   "Declaration changes conservatively lint direct callers, not their callers.")
               (reset! observed [])
               (is (= after (functions/build-manifest
                              (assoc request :seon.fn/previous-manifest after))))
               (is (empty? @observed) "No source change performs zero lint.")
               (write! "alpha.clj" "(ns pub.alpha) (defn f \"Changed.\" {:malli/schema [:=> [:cat :number] :number]} [x] x)")
               (let [changed (functions/build-manifest
                              (assoc request :seon.fn/previous-manifest after
                                             :seon.source/previous-database (db/db connection)))
                     result (functions/index!
                             (assoc request :seon.db/connection connection
                                            :seon.schema/projection (db/carried-projection (db/db connection))
                                            :seon.source/previous-database (db/db connection)
                                            :seon.fn/previous-manifest after
                                            :seon.fn/manifest changed
                                            :seon.fn/changed-paths #{"src/alpha.clj"}))]
                 (is (not (:seon.error/at result)) (pr-str result))
                 (is (= ["alpha.clj" "beta.clj"] @observed)
                     "Only the direct caller is linted after the declaration transaction.")
                 (is (= #{"src/beta.clj"}
                        (functions/caller-files (:seon.db/transaction-report result))))))))
         (finally (support/delete-recursively! root))))))
  (doseq [[expected source]
          [[:invalid-arity "(ns pub.alpha) (defn f [x y] (+ x y))"]
           [:private-call "(ns pub.alpha) (defn- f [x] x)"]]]
    (support/with-database
     (fn [connection]
       (let [root (str "tmp/publication-cache/" (random-uuid))
             request {:seon.fn/root root :seon.fn/roots ["src"]}
             alpha (io/file root "src/alpha.clj")
             beta (io/file root "src/beta.clj")]
         (try
           (io/make-parents alpha)
           (spit alpha "(ns pub.alpha) (defn f [x] x)")
           (spit beta "(ns pub.beta (:require [pub.alpha :as a])) (defn g [] (a/f 1))")
           (let [before (functions/build-manifest request)]
             (support/transacted!
              connection
              (functions/reconcile-tx
               (db/db connection)
               (vec (mapcat :seon.fn.file/rows (:seon.fn.manifest/artifacts before))) []))
             (spit alpha source)
             (let [database (db/db connection)
                   changed (functions/build-manifest
                            (assoc request :seon.fn/previous-manifest before
                                           :seon.source/previous-database database))
                   refusal (support/refusal-data
                            #(functions/index!
                              (assoc request :seon.db/connection connection
                                             :seon.schema/projection (db/carried-projection database)
                                             :seon.source/previous-database database
                                             :seon.fn/previous-manifest before
                                             :seon.fn/manifest changed
                                             :seon.fn/changed-paths #{"src/alpha.clj"})))]
               (is (true? (:seon.fn/index-refused refusal)) (pr-str refusal))
               (is (some #(and (= expected (:seon.fn.analyzer/type %))
                               (= "beta.clj" (.getName (io/file (:seon.fn.analyzer/filename %)))))
                         (:seon.fn/findings refusal))
                   (str "Absent specs must not hide caller " expected ": " (pr-str refusal)))))
           (finally (support/delete-recursively! root))))))))
