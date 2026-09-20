(ns seon.fn.publication-toolchain-test
  "Declared test inputs include the analyzer configuration."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [seon.fn.analyzer :as analyzer]
            [seon.test-support :as support]
            [seon.test.cache :as cache]))

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
