(ns seon.dev.hook-entrance-fixture
  "The file the hook's REPL-entrance test edits.")

(defn unchanged
  "Returns its argument."
  {:malli/schema [:=> [:cat :int] :int]}
  [x]
  x)

(defn changed
  "Returns its argument plus one."
  {:malli/schema [:=> [:cat :int] :int]}
  [x]
  (inc x))
