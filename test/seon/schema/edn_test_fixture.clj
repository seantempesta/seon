(ns seon.schema.edn-test-fixture
  "A predicate OWNER namespace that no test requires.

  Exists solely as the falsifier target for the gate's computed
  predicate-owner resolution: `seon.schema.edn-test` names
  `late-instant?` in a candidate `[:fn]` WITHOUT requiring this
  namespace, and admission must `requiring-resolve` it — loading this
  file, whose load-time registration below makes the predicate
  registered. The filename deliberately does not end in `_test`, so
  the runner never loads it first."
  (:require [seon.schema :as schema])
  (:import [java.util Date]))

(defn late-instant?
  "True for a `java.util.Date` — trivially, the point is WHO loads me."
  [value]
  (instance? Date value))

(schema/register-core-predicate! 'seon.schema.edn-test-fixture/late-instant?
                                 late-instant?)
