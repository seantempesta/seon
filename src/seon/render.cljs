(ns seon.render
  "Render protocol — two surfaces (`:seon.render/ai` and
   `:seon.render/html`) dispatched from per-agent entity slots.

   Each slot holds **either a fully-qualified symbol** (resolved + called
   when rendered) **or a literal value** (used as-is, where allowed).
   The dispatchers fall through to `seon.render.default/pretty-*` when
   the slot is unresolvable — render mechanism never crashes; missing →
   pretty-print.

   See spec-05 §15 for the full design (slot types, input shapes, the
   per-agent vs system-fn input distinction, the agent's quickest 'say
   hi' literal-hiccup path).

   ## Late-bound compile-state

   `resolve-symbol` looks symbols up in a defonce'd CLJS analyzer
   compile-state. To avoid a circular dependency with `seon.client`
   (which owns the compile-state defonce), this namespace keeps a
   `!compile-state-ref` atom that `seon.client` resets at boot. Until
   then, every resolve returns nil and dispatch falls through to
   pretty-print. That fallback IS the contract: a fresh process with
   no boot-side wiring should still render something usable."
  (:require
    [clojure.string :as str]
    [goog.object :as gobj]
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

;; :seon.render/html accepts a symbol (fn taking the input map → hiccup)
;; or a literal hiccup value. Static views don't impair the agent the
;; way a static prompt would.
(schema/register! :seon.render/html [:or [:fn symbol?] :any])

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
;; Late-bound compile-state. `seon.client` resets this atom to its
;; own `!compile-state` ref at boot. Stays nil in fresh processes /
;; tests that don't boot the full client; resolve-symbol degrades
;; gracefully to nil, and dispatch falls through to pretty-print.
;; ============================================================

(defonce !compile-state-ref
  (atom nil))

(defn use-compile-state!
  "Wire `compile-state-atom` as the source for `resolve-symbol`.
   `compile-state-atom` is the atom returned by `seon.eval/init-bootstrap!`
   (typically `seon.client/!compile-state`). Idempotent."
  [compile-state-atom]
  (reset! !compile-state-ref compile-state-atom))

;; ============================================================
;; Symbol resolution
;;
;; Two paths, tried in order:
;;
;;   1. Bootstrap compile-state — for AGENT-DEFINED fns. After
;;      `seon.eval/eval-batch!` runs `(defn alice/my-view ...)`,
;;      the var-meta lands in `(@!compile-state :cljs.analyzer/
;;      namespaces ... :defs ...)`. A-8 wires this path; today's
;;      stub returns nil.
;;
;;   2. globalThis walker — for SYSTEM fns from the :client bundle
;;      (seon.render.default/view, seon.example/setup!, anything
;;      compiled into out/client/main.js). Shadow-cljs in :node-script
;;      target emits namespace objects at goog-global paths matching
;;      the source namespace (with per-segment munge). We walk the
;;      path with `goog.object/get` and `cljs.core/munge`.
;;
;; Both paths return nil if the symbol can't be found. Callers fall
;; through to pretty-print (the dispatch contract from spec-05 §15.5).
;; ============================================================

(defn- ^:no-doc resolve-via-global-this
  "Walk `js/globalThis` segment-by-segment, munging each segment to
   match the JS names shadow-cljs emits. Returns the resolved value
   (typically a fn) or nil if any step misses.

   Munge handles JS reserved words (`default` → `default$`) and
   character substitutions (`-` → `_`, `?` → `_QMARK_`, etc.) so this
   works equally well for system fns like `seon.render.default/view`
   AND for agent-defined fns like `seon.agent.seon/my-view`."
  [sym]
  (let [ns-parts (str/split (namespace sym) #"\.")
        ns-obj   (reduce (fn [obj seg]
                           (when obj (gobj/get obj (munge seg))))
                         js/globalThis
                         ns-parts)]
    (when ns-obj
      (gobj/get ns-obj (munge (name sym))))))

(defn- ^:no-doc resolve-via-compile-state
  "Look up `sym` in the bootstrap-CLJS analyzer cache. A-8 will wire
   `seon.eval/lookup-value` to walk var-meta → callable. Today
   returns nil for every symbol; the globalThis path catches
   everything we need for V0.5 system fns."
  [_sym]
  ;; Reserved for agent-defined fns. Until A-8, this is a stub.
  nil)

(defn resolve-symbol
  "Resolve a fully-qualified symbol to its runtime value. Returns nil
   if unresolvable — callers decide their own fallback (ai-dispatch /
   html-dispatch pick the pretty-print floor in seon.render.default).

   Tries the bootstrap-CLJS compile-state first (for agent-defined
   fns) then falls back to globalThis (for system fns shipped in the
   :client bundle). Never throws on bad input — unqualified symbols,
   nil, keywords, strings all return nil."
  {:malli/schema [:=> [:cat :any] [:maybe :any]]}
  [sym]
  (when (qualified-symbol? sym)
    (or (resolve-via-compile-state sym)
        (resolve-via-global-this sym))))

;; ============================================================
;; Dispatchers — one per surface. Both fall through to pretty-print
;; on miss; html-dispatch additionally accepts literal hiccup.
;; ============================================================

(defn ai-dispatch
  "Materialize an :seon.render/ai slot. Slot is a qualified symbol;
   if it doesn't resolve (or is nil / unqualified), fall through to
   seon.render.default/pretty-ai so the agent always gets some ctx."
  {:malli/schema [:=> [:cat :any :map] :seon.render/ai-response]}
  [sym input-map]
  (let [f (or (resolve-symbol sym) default/pretty-ai)]
    (f input-map)))

(defn html-dispatch
  "Materialize an :seon.render/html slot. Slot is either a symbol
   (resolved + called), a literal hiccup vector (used as-is), or
   anything else (pretty-printed)."
  {:malli/schema [:=> [:cat :any :map] :seon.render/html-response]}
  [slot input-map]
  (cond
    (qualified-symbol? slot)
    ((or (resolve-symbol slot) default/pretty-html) input-map)

    (vector? slot)
    {:seon.render/hiccup slot}

    :else
    (default/pretty-html input-map)))
