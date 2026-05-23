(ns seon.render
  "Render surfaces — two today (`:seon.render/ai` and
   `:seon.render/html`), `:seon.render/canvas` planned (v2/v3).

   Each agent entity carries one slot per surface — a fully-qualified
   symbol naming the fn to call. `*-render` resolves the symbol via
   `seon.eval/lookup-value` and calls it; if the slot is nil,
   unqualified, or points at an unresolvable symbol, falls through to
   `seon.render.default/pretty-*` so the surface never crashes.

   See [[../prds/agent-runtime/v1.md]] §5 for the AI surface (six-section
   composer producing `:seon.turn/prompt-text`) and
   [[../prds/agent-runtime/v2.md]] 'Per-section HTML composer' for the
   HTML mirror (section fns grow `:seon.render/hiccup` in their
   return map alongside `:seon.render/text`).

   ## Naming note

   `ai-render` / `html-render` are thin: resolve symbol → call fn →
   fall back to pretty-print. They are NOT multimethod dispatch.
   V2's per-entity Malli-specificity dispatch is the real
   data-shape-driven pick-the-renderer — it lives in `seon.eval`
   alongside the program-graph queries it needs.

   ## Late-bound symbol lookup

   `seon.eval/lookup-value` walks `js/globalThis` with `cljs.core/munge`
   per segment — works for substrate fns (shadow-cljs precompiled
   bundle) AND agent-defined fns (written by `cljs.js/eval-str` at the
   same munged paths). Single path, no boot-time wire-up needed."
  (:require
    [seon.eval :as eval]
    [seon.render.default :as default]
    [seon.schema :as schema]))

;; ============================================================
;; Schemas — every shape this surface reads or writes (spec-05 §15.1).
;; ============================================================

;; Datahike db snapshot — opaque to validation; the renderer body uses it.
(schema/register! :seon.db/db :any)

;; :seon.render/ai is FUNCTION-ONLY (literal strings would deprive the
;; agent of dynamic ctx — current convo, recent evals, schema ref, …).
;; Force-function ensures the default ctx fn is always the baseline.
(schema/register! :seon.render/ai [:fn symbol?])

;; :seon.render/html — symbol-only at entity storage (V0.5 limitation;
;; see seon.client/agent-bootstrap-schema for the datahike side). The
;; in-process dispatch path still accepts literal hiccup at call sites
;; (e.g. when a render fn returns a vector that wraps another).
(schema/register! :seon.render/html [:fn symbol?])

;; Hiccup data shape — recursive vector starting with keyword, optional
;; attrs map, children. Defined via Malli local registry so the
;; recursive ref resolves.
(schema/register! :seon.render/hiccup
  [:schema {:registry {::elem [:or :string :int :nil ::node]
                       ::node [:cat :keyword
                                    [:? :map]
                                    [:* [:ref ::elem]]]}}
   ::elem])

;; Renderer return shapes — map-in / map-out per seon house rule.
(schema/register! :seon.render/ai-response
  [:map [:seon.render/text :string]])

(schema/register! :seon.render/html-response
  [:map [:seon.render/hiccup :any]])

;; System renderer input — for `seon.render.default/*` and other
;; non-agent-namespaced fns. Doesn't know which agent ahead of time;
;; carries `:seon.agent/id` and pulls the entity itself.
(schema/register! :seon.render/system-input
  [:map
   [:seon.db/db    :any]
   [:seon.agent/id :string]])

;; ============================================================
;; Renderers — one per surface. Both fall through to pretty-print
;; on miss; html-render additionally accepts literal hiccup.
;; ============================================================

(defn ai-render
  "Materialize an :seon.render/ai slot. Slot is a qualified symbol;
   if it doesn't resolve (or is nil / unqualified), fall through to
   seon.render.default/pretty-ai so the agent always gets some ctx."
  {:malli/schema [:=> [:cat :any :map] :seon.render/ai-response]}
  [sym input-map]
  (let [f (or (eval/lookup-value sym) default/pretty-ai)]
    (f input-map)))

(defn html-render
  "Materialize an :seon.render/html slot. Slot is either a symbol
   (resolved + called), a literal hiccup vector (used as-is), or
   anything else (pretty-printed)."
  {:malli/schema [:=> [:cat :any :map] :seon.render/html-response]}
  [slot input-map]
  (cond
    (qualified-symbol? slot)
    ((or (eval/lookup-value slot) default/pretty-html) input-map)

    (vector? slot)
    {:seon.render/hiccup slot}

    :else
    (default/pretty-html input-map)))
