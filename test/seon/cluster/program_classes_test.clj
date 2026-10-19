(ns seon.cluster.program-classes-test
  "A launch loads our program from source (`seon.operator/launch-form`,
  `seon.operator/start-classpath`), so `require :reload` re-evaluates a
  namespace and a namespace removed with `remove-ns` comes back on
  `require :reload`. A namespace loaded from a compiled `__init` class does
  neither: `clojure.lang.RT/load` prefers a class newer than its source, and
  that class's static initializer has already run."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(deftest ^{:seon.test/fixture-observation "Reloads and removes the JVM namespace seon.web.extract, which every cluster in the process shares; a branch isolates no loaded Var."
           :seon.test/platform "Moving part: the launch's classpath decides whether a reload reads source; it must observe a JVM the operator launched, in the platform tier's isolated JVM."}
  a-launched-namespace-reloads-from-its-source
  ;; `seon.web.extract` is a leaf the boot loads, with no contracted Var to re-arm.
  (let [namespace-name 'seon.web.extract
        html #(deref (find-var 'seon.web.extract/html))]
    (require namespace-name)
    (is (nil? (io/resource "seon/web/extract__init.class"))
        "the launch put no compiled class of our program on the classpath")
    (let [before (html)]
      (require namespace-name :reload)
      (is (not (identical? before (html))) "the reload re-evaluated the namespace"))
    (remove-ns namespace-name)
    (require namespace-name :reload)
    (is (some? (find-ns namespace-name)) "a removed namespace comes back on reload")))
