(ns seon.fn.publication-test
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [clojure.test :refer [deftest is testing]]
            [seon.fn :as functions]
            [seon.db :as db]
            [seon.fn.analyzer :as analyzer]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as support]))

(defn- write-source!
  {:malli/schema [:=> [:cat :string :string :string] :nil]}
  [root path source]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file source))
  nil)

(deftest incremental-artifacts-equal-complete-analysis-through-declaration-edges
  (doseq [[label callee expected]
          [[:body-only
            "(ns pub.alpha) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1)) (defn unused [] 0)"
            #{"alpha.clj"}]
           [:arity
            "(ns pub.alpha) (defn f {:malli/schema [:=> [:cat :int [:* :int]] :int]} [x & xs] (+ x (count xs))) (defn unused [] 0)"
            #{"alpha.clj" "beta.clj"}]
           [:deletion
            "(ns pub.alpha) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] x)"
            #{"alpha.clj" "beta.clj"}]
           [:namespace-deprecation
            "(ns ^{:deprecated \"now\"} pub.alpha) (defn f {:malli/schema [:=> [:cat :int] :int]} [x] x) (defn unused [] 0)"
            #{"alpha.clj" "beta.clj"}]
           [:private-deprecation
            "(ns pub.alpha) (defn- ^{:deprecated \"now\"} f {:malli/schema [:=> [:cat :int] :int]} [x] x) (defn unused [] 0)"
            #{"alpha.clj" "beta.clj"}]]]
    (testing (name label)
      (let [root (str "tmp/publication-analysis/" (random-uuid))
            private? (= :private-deprecation label)
            request {:seon.fn/root root :seon.fn/roots ["src"]
                     ::analyzer/cache-root (str root "/resolver")}
            observed (atom [])
            analyze analyzer/analyze]
        (try
          (write-source! root "src/alpha.clj"
                         (str "(ns pub.alpha) (" (if private? "defn-" "defn")
                              " f {:malli/schema [:=> [:cat :int] :int]} [x] x) (defn unused [] 0)"))
          (write-source! root "src/beta.clj"
                         (str "(ns pub.beta (:require [pub.alpha :as a])) "
                              "(defn g {:malli/schema [:=> [:cat] :int]} [] ("
                              (if private? "#'a/f" "a/f") " 1))"))
          (write-source! root "src/stranger.clj" "(ns pub.stranger) (defn h [] 0)")
          (let [before (functions/build-manifest request)]
            (write-source! root "src/alpha.clj" callee)
            (let [incremental
                  (with-redefs [analyzer/analyze
                                (fn [request]
                                  (swap! observed into (map #(.getName (io/file %))
                                                           (keys (::analyzer/sources request))))
                                  (analyze request))]
                    (functions/build-manifest (assoc request :seon.fn/previous-manifest before)))
                  complete (functions/build-manifest
                            (assoc request ::analyzer/cache-root (str root "/complete-resolver")))]
              (is (= expected (set @observed)) (pr-str @observed))
              (is (= (count expected) (count @observed)) "Each selected input is analyzed once.")
              (is (= (:seon.fn.manifest/digest complete) (:seon.fn.manifest/digest incremental)))
              (is (= (:seon.fn.manifest/artifacts complete) (:seon.fn.manifest/artifacts incremental))
                  "Compare facts and findings too: equal source digests alone cannot establish parity.")))
          (finally (support/delete-recursively! root)))))))


(deftest schema-declarations-use-the-published-contract-edges
  (support/with-database
   (fn [connection]
  (let [root (str "tmp/publication-analysis/" (random-uuid))
        forms (assoc (schema.edn/packaged-forms) :pub/value :int)
        request {:seon.fn/root root :seon.fn/roots ["src"]
                 :seon.schema.projection/forms forms
                 ::analyzer/cache-root (str root "/resolver")}
        observed (atom [])
        analyze analyzer/analyze]
    (try
      (write-source! root "src/alpha.clj"
                     "(ns pub.alpha) (defn f \"A documented function.\" {:malli/schema [:=> [:cat :pub/value] :pub/value]} [x] x)")
      (write-source! root "src/beta.clj"
                     "(ns pub.beta (:require [pub.alpha :as a])) (defn g [] (a/f 1))")
      (write-source! root "src/stranger.clj" "(ns pub.stranger) (defn h [] 0)")
      (let [before (functions/build-manifest request)
            ; Use the actual publication contract-row producer, including its
            ; canonical schema population, to obtain the stored edge grammar.
            rows (into (#'functions/published-index-rows (db/db connection))
                       (#'functions/desired-rows
                  (assoc request :seon.fn/manifest before
                         :seon.fn/previous-manifest (assoc before :seon.fn.manifest/artifacts [])
                         :seon.fn/changed-paths #{"src/alpha.clj" "src/beta.clj" "src/stranger.clj"}
                         :seon.source/previous-database (db/db connection))
                  (constantly nil)))
            next-request (assoc request :seon.schema.projection/forms (assoc forms :pub/value :number))
            incremental (with-redefs [analyzer/analyze
                                      (fn [request]
                                        (swap! observed into (map #(.getName (io/file %))
                                                                 (keys (::analyzer/sources request))))
                                        (analyze request))]
                          (functions/build-manifest
                           (assoc next-request :seon.fn/previous-manifest before
                                  :seon.fn/published-rows rows)))
            complete (functions/build-manifest
                      (assoc next-request ::analyzer/cache-root (str root "/complete-resolver")))]
        (is (= #{"alpha.clj" "beta.clj"} (set @observed)))
        (is (= 2 (count @observed)))
        (is (= (:seon.fn.manifest/artifacts complete) (:seon.fn.manifest/artifacts incremental)))
        (is (= (:seon.fn.manifest/declaration-digests complete)
               (:seon.fn.manifest/declaration-digests incremental)))
        (let [shape-forms
              (update forms :seon.fn/fn
                      #(walk/postwalk
                        (fn [form]
                          (if (and (vector? form) (= :map (first form)))
                            (into [:map] (remove (fn [entry]
                                                  (and (vector? entry)
                                                       (= :seon.fn/doc (first entry))))) (rest form))
                            form)) %))
              shape-request (assoc request :seon.schema.projection/forms shape-forms)
              _ (reset! observed [])
              shaped (with-redefs [analyzer/analyze
                                   (fn [request]
                                     (swap! observed into (keys (::analyzer/sources request)))
                                     (analyze request))]
                       (functions/build-manifest
                        (assoc shape-request :seon.fn/previous-manifest before :seon.fn/published-rows rows)))
              fresh (functions/build-manifest
                     (assoc shape-request ::analyzer/cache-root (str root "/shape-resolver")))]
          (is (not= forms shape-forms) "The fixture must actually change the wrapped entity map.")
          (is (some :seon.fn/doc (:seon.fn.file/rows (functions/artifact-by-path before "src/alpha.clj"))))
          (is (not-any? :seon.fn/doc (:seon.fn.file/rows (functions/artifact-by-path shaped "src/alpha.clj"))))
          (is (= 3 (count @observed)) "Stored row-schema edges invalidate every affected declaration file.")
          (is (= (:seon.fn.manifest/artifacts fresh) (:seon.fn.manifest/artifacts shaped)))))
      (finally (support/delete-recursively! root)))))))


(deftest incremental-indexing-reconciles-only-selected-file-rows
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
             (let [after (functions/build-manifest (assoc request :seon.fn/previous-manifest before))
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
