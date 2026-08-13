(ns seon.schema-test.probe-ns
  "A test-only predicate namespace whose load state belongs to one test.")

(defn probe-predicate?
  "True for the dedicated schema-loading probe value."
  [value]
  (= ::probe value))
