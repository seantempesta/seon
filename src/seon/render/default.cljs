(ns seon.render.default
  "The renderers a fresh agent uses when no slot override is set.

   A-2 ships only the pretty-print floors (`pretty-ai`, `pretty-html`)
   — the universal fallbacks that fire when a slot's symbol doesn't
   resolve, when the slot is nil / unqualified, or when html-dispatch
   sees a non-vector / non-symbol value. They keep the contract that
   render mechanism never crashes: missing → pretty-print.

   A-4 will add the rich-default renderers (`ctx`, `view`,
   `repl-state-header`, `what-you-can-do`, `recent-conversation`,
   `recent-evals`, `recent-errors`, `schema-reference`) on top of the
   helpers ported in A-3 (`seon.ui.html/->string`).

   Per spec-05 §15 these renderers all follow seon's map-in / map-out
   convention with `:malli/schema` metadata on every public fn.")

;; ============================================================
;; Pretty-print floors — universal fallbacks for both surfaces.
;;
;; pretty-ai emits the input value as edn so the agent sees the raw
;; shape it's looking at. pretty-html wraps an edn dump in a monospace
;; <pre> container so the user at least sees the data structure.
;;
;; Both are pure of the database (they read only the input map) so
;; the floor remains usable even when nothing else has been wired up
;; — fresh boot, broken slot, missing entity.
;; ============================================================

(defn pretty-ai
  "Universal AI-side fallback. Emits the input map as edn."
  {:malli/schema [:=> [:cat :map] :seon.render/ai-response]}
  [input]
  {:seon.render/text (pr-str input)})

(defn pretty-html
  "Universal HTML-side fallback. Wraps an edn dump in a monospace
   container so the user at least sees the data structure."
  {:malli/schema [:=> [:cat :map] :seon.render/html-response]}
  [input]
  {:seon.render/hiccup
   [:pre {:class "p-3 text-xs font-mono bg-base-900 text-text-200 overflow-auto"}
    (pr-str input)]})
