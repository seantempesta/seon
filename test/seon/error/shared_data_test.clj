(ns seon.error.shared-data-test
  "Error data that repeats one value is referenced once per distinct value."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.error.refusal :as error.refusal]))

(deftest a-value-repeated-under-every-problem-is-referenced-once
  ;; growth lane 2026-10-07: a contract refusal names the offending argument
  ;; under each of its hundreds of problems; referencing it per occurrence made
  ;; a 10,000-deep argument cost gigabytes. The repeated value is rebuilt once
  ;; (it holds a place), so every problem keeps the same rebuilt value.
  (let [deep (reduce (fn [v _] {:a v}) {:place (atom 1)} (range 10000))
        problems (mapv (fn [i] {:seon.error/path [i] :seon.error/offending deep}) (range 300))
        out (:seon.error/problems (error.refusal/references nil {:seon.error/problems problems}))
        bottom (loop [m (:seon.error/offending (first out))] (if (:a m) (recur (:a m)) (:place m)))]
    (is (every? #(identical? (:seon.error/offending (first out)) (:seon.error/offending %)) out))
    (is (str/starts-with? bottom "#object[clojure.lang.Atom"))))
