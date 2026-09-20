(ns seon.dev.publication-launch-test
  (:require [clojure.test :refer [deftest is]]
            [seon.fresh-operator :as operator]))

(deftest launch-loads-the-cluster-without-an-inlined-input-inventory
  (let [text (#'operator/launch-form "tmp/publication-launch" "publication" {} 12345)
        forms (tree-seq coll? seq (read-string text))]
    ;; Clojure's generated method must fit the JVM's 64 KiB ceiling.
    (is (< (alength (.getBytes text java.nio.charset.StandardCharsets/UTF_8)) 16384))
    (is (some #(and (seq? %) (= 'clojure.core/require (first %))
                    (= '(quote seon.cluster) (second %))) forms))))
