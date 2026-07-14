(ns acme.helpers
  "Acme helper namespace.

   `format-count` is UNSPECCED ON PURPOSE: it is the downstream helper a
   renderer calls through an alias (`h/format-count`), and the exact member
   SCI's `expose-ns` cannot enumerate today — it only reads the
   `:seon.fn` index, which holds SPECCED fns only. That gap is why a renderer
   requiring this ns falls off the SCI-bounded path onto the unbounded
   compiled path (BUG A — the downstream's live `Unable to resolve symbol`
   when a renderer calls an unspecced helper in a required ns). `greet` is
   specced, so this ns still owns a full-source `:seon.ns` row and shows
   in context.")

(defn format-count
  "Render a count with its noun pluralized.

   This helper is deliberately unspecced; SCI must still resolve it when the
   dashboard calls through the namespace alias."
  [n noun]
  (str n " " noun (when (not= 1 n) "s")))

(defn greet
  "A specced helper so acme.helpers owns at least one indexed fn."
  {:malli/schema [:=> [:cat :string] :string]}
  [who]
  (str "Acme greets " who))
