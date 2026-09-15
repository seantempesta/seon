(ns my.examples.nested-fixture
  "A nested namespace admitted by the canonical program population.")

(defn echo
  "Return the supplied text.

  Example:
  (my.examples.nested-fixture/echo (str 'nested))"
  {:malli/schema [:=> [:cat :string] :string]}
  [text]
  text)
