(ns ^{:seon.ns/context-relevant? true} my.test
  "Run the tests declared in my namespace."
  (:require [seon.test]))

(defmacro run
  "Run my namespace's declared tests and store their results.

  Takes an optional request map; my database and agent identity are supplied.
  Returns a vector of :seon.test/sym, :seon.test/pass-count,
  :seon.test/fail-count and :seon.test/error-count maps. [] means no tests
  are declared; it is not evidence that any test passed.

  Example:
  (my.test/run)"
  ([] (list 'my.test/run {}))
  ([request]
   `(let [symbols# (seon.test/owned-symbols ~request)]
      (if (:seon.error/kind symbols#)
        symbols#
        (mapv (fn [test-symbol#]
                (seon.test/run (resolve (symbol test-symbol#))))
              symbols#)))))
