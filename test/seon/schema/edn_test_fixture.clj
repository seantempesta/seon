(ns seon.schema.edn-test-fixture
  "A predicate OWNER namespace that no test requires.

  Exists solely as the falsifier target for the gate's computed
  predicate-owner resolution: `seon.schema.edn-test` names
  `late-instant?` in a candidate `[:fn]` WITHOUT requiring this
  namespace, and admission must `requiring-resolve` it — loading this
  file, which defines the predicate's Var. The filename deliberately does not end in `_test`, so
  the runner never loads it first."
  (:import [java.util Date]))

(defn late-instant?
  "True for a `java.util.Date` — trivially, the point is WHO loads me."
  [value]
  (instance? Date value))
