(ns seon.fn.publication-cache-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.fn :as functions]
            [seon.fn.analyzer :as analyzer]
            [seon.test-support :as support]))

(deftest analysis-reuse-matches-input-and-resolved-declarations
  (let [root (str "tmp/publication-cache/" (random-uuid))
        request {:seon.fn/root root :seon.fn/roots ["src"]
                 ::analyzer/cache-root (str root "/resolver")}
        write! (fn [path source]
                 (let [file (io/file root "src" path)]
                   (io/make-parents file)
                   (spit file source)))
        original "(ns pub.alpha) (defn f [x] x)"
        observed (atom [])
        analyze analyzer/analyze]
    (try
      (write! "alpha.clj" original)
      (write! "beta.clj" "(ns pub.beta (:require [pub.alpha :as a])) (defn g [] (a/f 1))")
      (write! "stranger.clj" "(ns pub.stranger) (defn h [] 0)")
      (let [first-publication (functions/build-manifest request)
            _ (write! "alpha.clj" "(ns pub.alpha) (defn f [x & xs] (+ x (count xs)))")
            changed (functions/build-manifest (assoc request :seon.fn/previous-manifest first-publication))
            _ (write! "alpha.clj" original)
            _ (write! "stranger.clj" "(ns pub.stranger) (defn h [] 1)")
            restored (with-redefs [analyzer/analyze
                                  (fn [request]
                                    (swap! observed into (map #(.getName (io/file %))
                                                             (keys (::analyzer/sources request))))
                                    (analyze request))]
                       (functions/build-manifest (assoc request :seon.fn/previous-manifest changed)))
            complete (functions/build-manifest
                      (assoc request ::analyzer/cache-root (str root "/complete-resolver")))]
        (is (= ["alpha.clj" "stranger.clj"] (sort @observed))
            "The unchanged caller reuses its earlier analysis after its resolved declaration returns.")
        (is (= (:seon.fn.manifest/artifacts complete) (:seon.fn.manifest/artifacts restored)))
        (is (= (:seon.fn.manifest/digest complete) (:seon.fn.manifest/digest restored)))
        (reset! observed [])
        (with-redefs [analyzer/analyze (fn [request]
                                      (swap! observed into (keys (::analyzer/sources request)))
                                      (analyze request))]
          (is (= restored (functions/build-manifest (assoc request :seon.fn/previous-manifest restored)))))
        (is (empty? @observed)))
      (finally (support/delete-recursively! root)))))
