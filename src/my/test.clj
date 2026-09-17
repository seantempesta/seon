(ns ^{:seon.ns/context-relevant? true} my.test
  "Run the tests declared in my namespace."
  (:require [seon.test]
            [seon.env :as env]))

(defn check
  "Check the tests reaching my change and return results and next-tier commands.

  My connection is supplied by call preparation. Failure data names the test,
  its message, and the changed functions it reaches. Larger gates run only
  after green; :seon.test/next-tier is :none on red.

  Example:
  (my.test/check {:seon.test/changed [\"my.note/add!\"]
                  :seon.test/paths [\"src/my/note.clj\"]})"
  {:malli/schema [:=> [:cat :my.test/check-request]
                  [:or :seon.test/check-result :seon.error/value]]}
  [request]
  (let [environment (env/require-environment
                     (get-in request [:my.program/context :seon.sci.eval/ctx])
                     :my.test/check)]
    (if (:seon.error/kind environment)
      environment
      (seon.test/check
       (cond-> request
         (and (not (find request :seon.boot/cluster-name))
              (:seon.boot/cluster-name environment))
         (assoc :seon.boot/cluster-name (:seon.boot/cluster-name environment)))))))

(defmacro run
  "Run my namespace's declared tests and store their results.

  Takes an optional request map; my database and agent identity are supplied.
  Returns a vector of :seon.test/sym, :seon.test/pass-count,
  :seon.test/fail-count and :seon.test/error-count maps. [] means no tests
  are declared; it is not evidence that any test passed.

  My tests run as MY cluster's work: `seon.test/run-owned` receives my
  connection from call preparation and hands it to the test body, so a
  `seon.db` call my test elides inside reaches my cluster exactly as the rest
  of my evaluation does. Unchanged green requests return the recorded result
  with :seon.test/unchanged and :seon.test/recorded-basis-t. An explicit
  :seon.test/run-basis-t requests that basis; use the current database basis
  to deliberately rerun an unchanged program.

  Example:
  (my.test/run)"
  ([] (list 'my.test/run {}))
  ([request]
   `(let [request# ~request
          symbols# (seon.test/owned-symbols request#)]
      (if (:seon.error/kind symbols#)
        symbols#
        (mapv (fn [test-symbol#]
                (seon.test/run-owned
                 (assoc request# :seon.test/var (resolve (symbol test-symbol#)))))
              symbols#)))))
