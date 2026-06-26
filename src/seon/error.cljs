(ns seon.error
  "Uniform error→map conversion for the safe-by-default boundary
   (spec-02 §2.5). Anywhere a seon surface catches an exception it
   should return `(error/->map e)` so agents inspect a stable shape.

   The result map carries:
     :seon.error/message   string — best-effort human-readable summary
     :seon.error/ex-data   map    — ex-data of THIS level (per-layer)
     :seon.error/data      map    — ex-data merged across the entire
                                    cause chain, deepest-wins. Read this
                                    from renderers / agent code; it
                                    flattens cljs.js's wrap layers
                                    (`Could not eval …` → `ERROR` →
                                    original) into one map.
     :seon.error/stack     string — .-stack, truncated to ~4kb
     :seon.error/cause     map    — recursive ->map of (ex-cause e)
     :seon.error/raw       any    — the original error instance, opaque
     :seon.error/truncated true   — set when cause-chain hits depth 5

   See docs/prds/agent-runtime/research/eval-error-envelope-2026-05-22.md
   for the cljs.js wrap analysis that motivates :seon.error/data.")

(defn ->message
  "Best-effort human-readable message for any error-ish value."
  {:malli/schema [:=> [:cat :any] :string]}
  [e]
  (or (when (some? e) (.-message e)) (str e)))

(defn- ex-data-chain
  "Walk e and its ex-cause chain (bounded depth 5), collecting each
   level's ex-data. Returns a seq ordered deepest-first, so a
   subsequent (apply merge ...) gives deepest-wins semantics — the
   ORIGINAL throw's ex-data takes precedence over wrap-layer
   ex-data added by cljs.js etc."
  [e]
  (loop [e e depth 0 acc ()]
    (if (or (nil? e) (>= depth 5))
      acc
      (let [data (when (instance? cljs.core/ExceptionInfo e) (ex-data e))
            acc' (if (seq data) (cons data acc) acc)]
        (recur (ex-cause e) (inc depth) acc')))))

(defn ->map
  "Convert a CLJS error to an agent-inspectable map. Recursion on
   :cause is bounded to depth 5 to defend against cycles.

   The top-level `:seon.error/data` flattens the entire cause chain's
   ex-data into one map (deepest wins). Renderers + agent code should
   read THIS, not walk the per-level `:seon.error/ex-data` keys, so
   useful info like `:seon.eval/warning-type` surfaces regardless of
   how many layers cljs.js's `wrap-error` added on top."
  {:malli/schema [:function
                  [:=> [:cat :any] [:maybe :map]]
                  [:=> [:cat :any :int] [:maybe :map]]]}
  ([e] (->map e 0))
  ([e depth]
   (when (some? e)
     (let [base   {:seon.error/message (->message e)
                   :seon.error/raw     e}
           data   (when (instance? cljs.core/ExceptionInfo e) (ex-data e))
           stack  (some-> (.-stack e) (subs 0 (min 4096 (count (.-stack e)))))
           cause  (when (< depth 5) (some-> (ex-cause e) (->map (inc depth))))
           trunc? (and (>= depth 5) (some? (ex-cause e)))
           ;; :seon.error/data only emitted at the top of the chain —
           ;; flattens cljs.js's wraps so renderers read one key.
           merged (when (zero? depth)
                    (apply merge {} (ex-data-chain e)))]
       (cond-> base
         data            (assoc :seon.error/ex-data data)
         (seq merged)    (assoc :seon.error/data merged)
         stack           (assoc :seon.error/stack stack)
         cause           (assoc :seon.error/cause cause)
         trunc?          (assoc :seon.error/truncated true))))))
