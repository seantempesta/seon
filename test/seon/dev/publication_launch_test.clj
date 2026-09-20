(ns seon.dev.publication-launch-test
  (:require [clojure.test :refer [deftest is]]
            [seon.fresh-operator :as operator]
            [seon.test.cache :as cache]))

(deftest launch-captures-inputs-in-child-before-loading-producers
  (let [text (with-redefs [cache/input-digests
                          (fn [_] (throw (ex-info "Parent must not inline inputs" {})))]
               (#'operator/launch-form "tmp/publication-launch" "publication" {} 12345))
        form (read-string text)
        forms (vec (tree-seq coll? seq form))
        input-call (first (filter #(and (seq? %) (seq? (first %))
                                       (= 'clojure.core/requiring-resolve (ffirst %))) forms))
        load-call (first (filter #(and (seq? %) (= 'clojure.core/require (first %))
                                      (= '(quote seon.cluster) (second %))) forms))]
    ;; Keep the actual source form far below a JVM method's 64 KiB code
    ;; ceiling. A producer/input inventory literal made this grow with the tree.
    (is (< (alength (.getBytes text java.nio.charset.StandardCharsets/UTF_8)) 16384))
    (is (= '(quote seon.test.cache/input-digests) (second (first input-call))))
    (is (some? load-call))
    (is (< (.indexOf forms input-call) (.indexOf forms load-call)))))
