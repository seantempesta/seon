(ns seon.fn.publication-toolchain-test
  "Historical indexer replay: publication facts depend on the executing indexer."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.fn :as functions]
            [seon.fn.analyzer :as analyzer]
            [seon.test-support :as support]
            [seon.test.cache :as cache]))

(deftest indexer-body-revisions-invalidate-every-analysis-artifact
  (let [root (str "tmp/publication-toolchain/" (random-uuid))
        source (slurp (io/resource "seon/fn.clj"))
        start (.indexOf source "(defn- call-target\n")
        stop (.indexOf source "(defn- references-by-caller\n" start)
        ; Exact call-target implementation before af800d1a0, using this
        ; snapshot's unchanged usage-symbol owner. No analyzer is simulated.
        prior "(defn- call-target\n  [usage]\n  (when (contains? usage ::analyzer/arity)\n    (usage-symbol usage)))\n\n"
        historical (str (subs source 0 start) prior (subs source stop))
        request {:seon.fn/root root :seon.fn/roots ["src"]
                 ::analyzer/cache-root (str root "/resolver")}
        write! (fn [path text]
                 (let [file (io/file root path)]
                   (io/make-parents file)
                   (spit file text)))
        old-call-target (fn [usage]
                          (when (contains? usage ::analyzer/arity)
                            (#'functions/usage-symbol usage)))
        consumer #(functions/artifact-by-path % "src/beta.clj")]
    (try
      (write! "src/compiler.clj" historical)
      (write! "src/alpha.clj" "(ns pub.alpha) (defn f [x] x)")
      (write! "src/beta.clj" "(ns pub.beta (:require [pub.alpha :as a])) (defn g [] (#'a/f 1))")
      (let [before (with-redefs-fn {#'functions/call-target old-call-target}
                     #(functions/build-manifest request))
            _ (write! "src/compiler.clj" source)
            observed (atom [])
            analyze analyzer/analyze
            incremental (with-redefs [analyzer/analyze
                                      (fn [request]
                                        (swap! observed into (keys (::analyzer/sources request)))
                                        (analyze request))]
                          (functions/build-manifest (assoc request :seon.fn/previous-manifest before)))
            complete (functions/build-manifest
                      (assoc request ::analyzer/cache-root (str root "/complete-resolver")))
            calls (fn [manifest]
                    (into #{} (mapcat :seon.fn/calls) (:seon.fn.file/rows (consumer manifest))))
            selected (functions/publication-inputs before complete #{"src/compiler.clj"} [])]
        (is (= #{"src/compiler.clj"} selected))
        (is (= 3 (count @observed)) "A producer body change reanalyzes every input exactly once.")
        (is (not= (:seon.source/toolchain-digest before) (:seon.source/toolchain-digest incremental)))
        (is (= (:seon.source/toolchain-digest complete) (:seon.source/toolchain-digest incremental)))
        (is (= (:seon.fn.manifest/digest complete) (:seon.fn.manifest/digest incremental)))
        (is (= (:seon.fn.manifest/artifacts complete) (:seon.fn.manifest/artifacts incremental)))
        (is (contains? (calls complete) 'pub.alpha/f))
        (println "PUBLICATION TOOLCHAIN EVIDENCE"
                 (pr-str {:seon.source/changed-paths selected
                          :seon.fn/incremental-calls (calls incremental)
                          :seon.fn/complete-calls (calls complete)
                          :seon.fn/source-digests-equal?
                          (= (:seon.fn.manifest/digest incremental)
                             (:seon.fn.manifest/digest complete))})))
      (finally (support/delete-recursively! root)))))


(deftest analyzer-configuration-is-a-toolchain-input
  (let [root (str "tmp/publication-toolchain/" (random-uuid))
        paths ["deps.edn" ".clj-kondo/config.edn" ".clj-kondo/hooks.clj" "src/app.clj"]
        write! (fn [path text]
                 (let [file (io/file root path)]
                   (io/make-parents file)
                   (spit file text)))]
    (try
      (write! "test-input-paths.txt" (apply str (map #(str % (char 0)) paths)))
      (write! "dependency-pins.txt" "")
      (write! "deps.edn" "{:deps {}}")
      (write! ".clj-kondo/config.edn" "{}")
      (write! ".clj-kondo/hooks.clj" "(ns hooks) (defn analyze [x] x)")
      (write! "src/app.clj" "(ns app)")
      (let [before (cache/toolchain-dependencies root #{analyzer/config-directory})]
        (is (= #{"deps.edn" ".clj-kondo/config.edn" ".clj-kondo/hooks.clj"} (set (keys before))))
        (write! "src/app.clj" "(ns app) (def value 1)")
        (is (= before (cache/toolchain-dependencies root #{analyzer/config-directory})))
        (write! ".clj-kondo/hooks.clj" "(ns hooks) (defn analyze [x] (assoc x :changed true))")
        (is (not= before (cache/toolchain-dependencies root #{analyzer/config-directory})))
        (is (cache/input-path? (cache/input-roots root) ".clj-kondo/hooks.clj")))
      (finally (support/delete-recursively! root)))))
