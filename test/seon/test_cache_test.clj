(ns seon.test-cache-test
  "Compatibility compares the inputs the existing cache owner digests."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [seon.test.cache :as cache]
            [seon.test.selection :as selection]))

(deftest resolved-classpath-preserves-order-and-rebases-only-checkout-roots
  (let [basis {:seon.test/classpath-root "/original"
               :seon.test/classpath-roots ["/cache/classes" "src" "." "/deps/library.jar"]}]
    (is (= (str/join java.io.File/pathSeparator
                    ["/cache/classes" "/worker/src" "/worker/." "/deps/library.jar"])
           (cache/classpath basis "/worker")))))

(deftest retained-base-compatibility-is-per-input
  (let [digests (selection/input-digests ".")
        inputs [digests "cache-owner" "dependency-closure"]
        program-path (str (first selection/graph-roots) "/compatibility-probe.clj")
        changed (assoc-in inputs [0 program-path] "new-bytes")
        ;; The widening paths are derived from the same declared boundary the
        ;; gate selects with, never a list maintained here.
        widening (into [] (comp (filter selection/widening-path?) (take 3))
                       (keys digests))]
    (is (= [] (#'cache/compatible-changes inputs inputs)))
    (is (= [program-path] (#'cache/compatible-changes inputs changed)))
    (is (= [program-path] (#'cache/compatible-changes changed inputs)))
    (testing "Legacy and incomplete evidence never establish compatibility."
      (doseq [missing [nil [] [{}] [{} nil nil]]]
        (is (nil? (#'cache/compatible-changes missing inputs)))
        (is (nil? (#'cache/compatible-changes inputs missing)))))
    (testing "Every declared input outside the graph must match."
      (is (seq widening))
      (doseq [path widening
              candidate [(assoc-in inputs [0 path] "different")
                         (update inputs 0 dissoc path)]]
        (is (nil? (#'cache/compatible-changes inputs candidate)))
        (is (nil? (#'cache/compatible-changes candidate inputs)))))
    (testing "Cache-owner and dependency inputs have the same authority."
      (doseq [index [1 2]]
        (is (nil? (#'cache/compatible-changes inputs (assoc inputs index "changed"))))))
    (testing "A graph-root prefix is a path boundary."
      (is (nil? (#'cache/compatible-changes
                 inputs (assoc-in inputs [0 (str (first selection/graph-roots)
                                                 "-other/file.clj")]
                                  "new-bytes")))))))
