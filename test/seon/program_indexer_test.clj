(ns seon.program-indexer-test
  "Properties over the source-current program graph emitted by the JVM indexer."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.program.edge :as edge]))

(defn- emitted-function-rows []
  (let [manifest-path (System/getenv "SEON_WRITER_ARTIFACT_MANIFEST")
        manifest (edn/read-string (slurp manifest-path))
        checkout (System/getProperty "user.dir")
        row-path (io/file checkout
                          (:seon.dev.artifact/program-row-path manifest))]
    (:seon.dev.artifact/program-rows
     (edn/read-string (slurp row-path)))))

(defn- pure-call-graph? [root bundles]
  (loop [pending (list root)
         seen #{}]
    (if-let [function-symbol (first pending)]
      (if (contains? seen function-symbol)
        (recur (next pending) seen)
        (if-let [bundle (get bundles function-symbol)]
          (let [terminal-effects
                (into {} (map (juxt ::edge/terminal-symbol ::edge/effect))
                      (::edge/terminals bundle))
                calls (::edge/calls bundle)
                indexed-calls (filter bundles calls)
                terminal-calls (remove bundles calls)]
            (if (or (seq (::edge/uncertainties bundle))
                    (some #(not= :pure (get terminal-effects %))
                          terminal-calls))
              false
              (recur (concat indexed-calls (next pending))
                     (conj seen function-symbol))))
          false))
      true)))

(deftest indexer-emitted-capability-edge-is-never-pure
  (let [bundles (into {}
                      (map (juxt ::edge/function-symbol identity))
                      (edge/reconstruct-bundles
                       (emitted-function-rows)))
        root "seon.db/bind-leaf"
        bundle (get bundles root)
        transact-terminal
        (some #(when (= "seon.db/transact!"
                        (::edge/terminal-symbol %))
                 %)
              (::edge/terminals bundle))]
    (is (= :idempotent (::edge/effect transact-terminal))
        "the emitted graph retains the guarded transaction capability edge")
    (is (false? (pure-call-graph? root bundles))
        "a graph that reaches that capability is never classified pure")))
