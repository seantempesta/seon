(ns my.test
  "Run the tests declared in my namespace."
  (:require [seon.test]))

(defmacro run
  "Run my namespace's declared tests in my live SCI context and store their results."
  ([] (list 'my.test/run {}))
  ([request]
   (list 'mapv
         '(fn [test-symbol] (seon.test/run (resolve (symbol test-symbol))))
         (list 'seon.test/owned-symbols request))))
