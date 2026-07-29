(ns seon.render
  "THE ONE PROJECTION ROUTER. A map declares what it can become; this
  resolves and applies it.

  THE WHOLE MECHANISM, in one sentence: a UNIT is any map carrying, per
  OUTPUT KIND, the fully qualified symbol of the function that projects
  the unit into that kind, and `render` resolves the symbol and applies
  it to the unit. That is the entire contract — there is no
  registration table, no dispatch map, no per-kind namespace and no
  protocol.

  WHY THIS EXISTS AS ITS OWN TINY NAMESPACE. The render contract
  (`docs/seon/architecture/ui.md`, \"The block and its two renders\")
  already had exactly this shape for exactly two kinds: `:seon.render/ai`
  → prompt text, `:seon.render/html` → a surface, selected by key
  presence with no stored discriminator, the symbol \"late-resolved each
  render\". The owner's direction is to admit that this was never about
  the UI: an error fact wants an `ai` projection (steering prose) and a
  `log` projection (a line), a failover notice wants `ai`, a metric
  wants something else. So the two-render rule becomes the special case
  of one open kind set, and the render contract keeps its two keys
  unchanged. Each new kind names its consumer; nothing here changes.

  RESOLUTION IS LATE AND VAR-BACKED, and that is load-bearing rather
  than incidental. `requiring-resolve` returns the VAR, and this
  namespace INVOKES the var rather than a fn it dereferenced earlier:
  re-evaluating the projection's `defn` against the running system
  changes the next render with no re-registration, which is the same
  hot-reload property `:seon.ancestor/populate`,
  `:seon.cluster.loop/evaluate` and the schema gate's predicate-owner
  rule already rely on. A cached fn value would silently serve the old
  projection after a reload — the failure would look like a stale UI, so
  it is stated as a prohibition: NOTHING here memoizes a resolution.

  IT IS TOTAL, because it is on the error path. `seon.error`'s notices
  route through this router, so a router that threw would turn recording
  an error into a second error — the quarry's recursion fence
  (`src-old/seon/error.cljc:738-745`) restated for this owner. Every
  failure is therefore a flat `:seon.error/value`, and there is no
  `:seon.config/on-core-error` key: this namespace never panics, in any
  mode. A projection that throws is reported as a value naming its
  class, with the unit's declared symbol, so the broken projection is
  named rather than the caller.

  THE KIND SET IS COMPUTED, never listed. `kinds` derives what a unit
  can become from the unit itself — every key in the `seon.render`
  namespace whose value is a qualified symbol — so adding a kind to a
  producer makes it discoverable everywhere with no edit here. This is
  the no-hand-maintained-lists rule applied to the one place a registry
  would have been the obvious design.

  LITERALS ARE DECLARATIONS. An AI render may be a verbatim string and
  an HTML render may be a hiccup vector rather than a symbol.
  `declaration?` admits those two narrow runtime shapes, and `render`
  returns a non-symbol declaration as its own output.

  Crash walk: pure resolution plus one call. Nothing here opens,
  commits, or holds anything, so a kill during a render loses a value
  that was never durable. Whether the PROJECTION is pure is the
  projection's own contract; the ones this repository ships are."
  (:require [seon.schema.edn :as schema.edn]))

;;; ---------------------------------------------------------------------------
;;; Schemas — src/seon/schema/render.edn
;;; ---------------------------------------------------------------------------

(schema.edn/load! {})

;;; ---------------------------------------------------------------------------
;;; Contract
;;; ---------------------------------------------------------------------------

(defn declaration?
  "True when `value` is a projection declaration rather than data.

  THREE SHAPES, and the narrowness is the mechanism rather than a
  restriction: a qualified SYMBOL to resolve and apply, a STRING that is
  its own output, or a VECTOR that is its own output. Anything else on a
  `seon.render`-namespaced key is presentation data —
  `:seon.render/priority 3` is the standing example, and admitting
  numbers would silently turn it into a kind."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [value]
  (or (qualified-symbol? value)
      (string? value)
      (vector? value)))

(defn kinds
  "The output kinds `unit` declares.
  Every key in the `seon.render` namespace whose value is a
  `declaration?`. COMPUTED from the unit, so a producer that adds a kind
  is discoverable without an edit here and without a registry. The
  router's own request keys (`:seon.render/unit`, `:seon.render/kind`)
  can never be mistaken for declarations — a map and a keyword are
  neither symbol, string nor vector — which is why the rule needs no
  exclusion list.
  Returns the empty set for a map that declares nothing; a unit with no
  projections is an ordinary value, not an error."
  {:malli/schema [:=> [:cat :seon.render/unit] [:set :seon.render/kind]]}
  [unit]
  (into #{}
        (keep (fn [[key value]]
                (when (and (qualified-keyword? key)
                           (= "seon.render" (namespace key))
                           (declaration? value))
                  key)))
        unit))

(defn render
  "Project `:seon.render/unit` into `:seon.render/kind`.
  Resolves the unit's declared symbol with `requiring-resolve` — loading
  the owning namespace if needed — and INVOKES THE VAR with the unit,
  so a re-evaluated projection takes effect immediately. On success:
  `{:seon.render/kind <kind> :seon.render/output <the projection>}`.

  Flat `:seon.error` values, never throws — this router runs on the
  error path and may not fault into it:
  - `::kind-not-declared` — the unit declares no such kind, naming the
    kinds it does declare so the caller can see what it has;
  - `::unresolvable` — the declared symbol does not resolve, naming the
    symbol. This is the same failure `:seon.ancestor/populate` refuses
    on, and it is a bug in the producer, not in the caller;
  - `::projection-failed` — the projection itself threw, naming the
    symbol and the throwable's class. The projection is named because
    the projection is what is broken."
  {:malli/schema [:=> [:cat :seon.render/request]
                  [:or :seon.render/rendered :seon.error/value]]}
  [{:seon.render/keys [unit kind]}]
  (let [declaration (get unit kind)]
    (cond
      (not (declaration? declaration))
      {:seon.error/kind ::kind-not-declared
       :seon.error/message (str "This unit declares no " kind " projection.")
       :seon.error/data {:seon.render/kind kind
                         :seon.render/kinds (kinds unit)}}

      ;; A LITERAL IS ITS OWN OUTPUT. No resolution, nothing to invoke,
      ;; and therefore nothing that can throw — a fixed string or a
      ;; fixed hiccup vector is the answer, and a block that just says a
      ;; fixed thing should not have to define a function to say it.
      (not (qualified-symbol? declaration))
      {:seon.render/kind kind
       :seon.render/output declaration}

      :else
      ;; the VAR, never a fn value taken once: re-evaluating the
      ;; projection's defn must change the next render
      (if-let [projection (try
                            (requiring-resolve declaration)
                            (catch Throwable _ nil))]
        (try
          {:seon.render/kind kind
           :seon.render/output (projection unit)}
          (catch Throwable failure
            {:seon.error/kind ::projection-failed
             :seon.error/message (str "The " declaration " projection threw: "
                                      (or (ex-message failure)
                                          (.getName (class failure))))
             :seon.error/data {:seon.render/kind kind
                               :seon.render/projection declaration
                               :seon.error/class (.getName (class failure))}}))
        {:seon.error/kind ::unresolvable
         :seon.error/message (str "The projection " declaration
                                  " does not resolve.")
         :seon.error/data {:seon.render/kind kind
                           :seon.render/projection declaration}}))))
