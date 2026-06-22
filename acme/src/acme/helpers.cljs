(ns acme.helpers
  "Acme helper namespace.

   `format-count` is UNSPECCED ON PURPOSE: it is the downstream helper a
   tile calls through an alias (`h/format-count`), and the exact member
   SCI's `expose-ns` cannot enumerate today — it only reads the
   `:seon.fn` index, which holds SPECCED fns only. That gap is why a tile
   requiring this ns falls off the SCI-bounded path onto the unbounded
   compiled path (BUG A — aria's live `Unable to resolve symbol:
   insp/classified-rows`). `greet` is specced, so this ns still owns a
   full-source `:seon.ns` row and shows in context.")

(defn format-count
  "Unspecced helper — render a count with its noun, pluralized. The tile
   calls this; SCI must be able to resolve it for bounding to engage."
  [n noun]
  (str n " " noun (when (not= 1 n) "s")))

(defn greet
  "A specced helper so acme.helpers owns at least one indexed fn."
  {:malli/schema [:=> [:cat :string] :string]}
  [who]
  (str "Acme greets " who))
