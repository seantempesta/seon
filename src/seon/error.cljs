(ns seon.error
  "Uniform error→map conversion for the safe-by-default boundary
   (spec-02 §2.5). Anywhere a seon surface catches an exception it
   should return `(error/->map e)` so agents inspect a stable shape.

   The result map carries:
     :seon.error/message   string — best-effort human-readable summary
     :seon.error/ex-data   map    — ex-data, if any (preserves user info)
     :seon.error/stack     string — .-stack, truncated to ~4kb
     :seon.error/cause     map    — recursive ->map of (ex-cause e)
     :seon.error/raw       any    — the original error instance, opaque
     :seon.error/truncated true   — set when cause-chain hits depth 5")

(defn ->message
  "Best-effort human-readable message for any error-ish value."
  [e]
  (or (when (some? e) (.-message e)) (str e)))

(defn ->map
  "Convert a CLJS error to an agent-inspectable map. Recursion on
   :cause is bounded to depth 5 to defend against cycles."
  ([e] (->map e 0))
  ([e depth]
   (when (some? e)
     (let [base   {:seon.error/message (->message e)
                   :seon.error/raw     e}
           data   (when (instance? cljs.core/ExceptionInfo e) (ex-data e))
           stack  (some-> (.-stack e) (subs 0 (min 4096 (count (.-stack e)))))
           cause  (when (< depth 5) (some-> (ex-cause e) (->map (inc depth))))
           trunc? (and (>= depth 5) (some? (ex-cause e)))]
       (cond-> base
         data   (assoc :seon.error/ex-data data)
         stack  (assoc :seon.error/stack stack)
         cause  (assoc :seon.error/cause cause)
         trunc? (assoc :seon.error/truncated true))))))
