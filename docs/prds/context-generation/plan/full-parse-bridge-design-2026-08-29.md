---
type: prd
status: draft
tags: [prd, program-graph, schema, database]
---

# The full-parse bridge — ruling 50's schema, links, and query conversions

*Design for the owner's markup before the implementing lane launches.
Sequenced AFTER the atomic identity pass (rulings 47/48), so every
family below is born compliant: identities are symbols, keys are
namespaced, dependency-vocabulary values verbatim (49 as amended).
Field names are clj-kondo's own under our namespaces (naming law
rank 2); verify each against the vendored analyzer output before
declaring — no field is stored that kondo does not emit.*

## 1. The families

**Usages become component children of their definer.** A usage has no
natural global identity (M4), so it is reached through its owner and
replaced wholesale when the owner re-indexes — no hand lifecycle:

```clojure
;; on the fn/test row (and the run-form row for settled forms):
:seon.fn/usages [:set #:seon.db{:component true} :seon.db/ref]

#:seon.fn.usage{:to        :seon.db/ref     ; the TARGET row — guaranteed
                                            ; resolvable by ruling 47's
                                            ; population invariant
                :arity     [:int {:min 0}]  ; optional — present on calls
                :row :int  :col :int  :end-row :int  :end-col :int
                :name-row :int :name-col :int      ; optional — absent on
                :name-end-row :int :name-end-col :int ; non-invocation sites
                :defmethod :boolean                 ; optional, when true
                :dispatch-val-str [:string]}        ; optional, defmethods
;; VERIFIED against reference-code/clj-kondo/analysis/README.md:119-143.
;; Kondo also copies the TARGET's properties onto each usage (:private,
;; :macro, :fixed-arities, …) — the bridge DROPS those copies: they are
;; the target row's own attributes, and storing them per usage is a
;; mirror that drifts (the one principled exception to store-it-all).
;; :alias and :refer are NOT var-usage fields (they live on
;; namespace-usages and keywords) — earlier draft error, corrected.
```

Same construction, four more owners:

```clojure
:seon.ns/usages       ; component children of the ns row (kondo
                      ; :namespace-usages — alias/refer live HERE)
#:seon.ns.usage{:to :seon.db/ref, :alias [:symbol],
                :row :int, :col :int}

:seon.fn/keyword-sites ; component children (definer-owned), superseding
                       ; the bare :seon.fn/keywords set as authority
#:seon.keyword.site{:keyword :qualified-keyword ; synthesized ns+name,
                                                ; the queryable handle
                    :alias [:symbol]            ; optional
                    :auto-resolved :boolean     ; optional, when true
                    :keys-destructuring :boolean ; optional, when true
                    :reg [:symbol]              ; optional, hook-registered
                    :row :int, :col :int}

:seon.ns/protocol-impls ; kondo :protocol-impls fields verbatim
#:seon.protocol.impl{:protocol :seon.db/ref ; resolved from
                                            ; protocol-ns/protocol-name
                     :method [:symbol], :defined-by [:symbol],
                     :row :int, :col :int}

:seon.fn/symbol-sites  ; kondo :symbols — symbols in QUOTED forms and
                       ; EDN, with :to resolved through aliases. This
                       ; is the mention-tracing feed for quoted
                       ; teaching forms and agent-quoted data — the
                       ; render plan's fixpoint reads it directly.
#:seon.symbol.site{:symbol [:symbol], :to [:symbol] ; optional
                   :row :int, :col :int}
```

**Var-definition fields kondo emits and we currently drop, accreted
onto the existing fn row** (all optional, absent = not asserted):
`:seon.fn/macro?` (landing now in S2-addendum), `:seon.fn/defined-by`
(symbol — `clojure.core/defn` vs `defmacro` vs `defrecord`, the "how"
fact), `:seon.fn/fixed-arities` (set of int), `:seon.fn/varargs-min-arity`
(int), `:seon.fn/row`/`:seon.fn/end-row` (the span — M6's shape data
rides arities + spans, no AST interrogation needed at selection time).

## 2. What gets DELETED as stored state (derive-or-die)

- **`:seon.fn/calls` stored sets die.** The calls-shaped set becomes
  one query over usage children; every consumer converts (below). One
  authority, no mirror.
- **`:seon.fn/keywords` stored sets die** the same way once keyword
  sites land (the set is `(distinct (map :seon.keyword.site/keyword …))`).
- The name-only-row minting in `desired-rows` SHRINKS: targets are
  discovered from usages exactly as before, but the ruling-47
  invariant (ctx-derived population) supplies most rows; minting
  covers only external/library names.

## 3. Convoluted code → straightforward queries

| Today (hand derivation) | After (one query over links) |
|---|---|
| `call-targets-by-caller` reduce over raw analysis, string set math (`fn.clj:249+`) | `[?caller :seon.fn/usages ?u] [?u :seon.fn.usage/to ?target]` |
| `form-calls` spelling-syntactic filter over var-usages (`fn.clj:528+`) | the settled form's own usage children, `:seon.fn.usage/arity` present = a call |
| bare-gate reachability from `:seon.fn/calls` edges | transitive closure over `usage/to` refs — same Datalog rule, richer base |
| `tests-reaching` 3-hop join through calls sets | closure over usage refs from `:seon.test` rows — arity-exact |
| producers-of-key + composition join (`output-refs ∩ input-refs`) | unchanged inputs, but suggestion ranking gains real usage counts/locations |
| mention-tracing `form-symbols` spelling match (M10) | resolved `usage/to` refs at the recorded site — alias-proof by construction |
| error-adjacency "which code touches this keyword" via `:seon.fn/keywords` sets | keyword SITES with locations — adjacency lands on the line, not the function |
| `renderer unavailable`-era "who calls help" unanswerable | `[?u :seon.fn.usage/to ?help-row]` — one clause |

## 4. Regressions (one per class, wanted behavior)

- **P-BRIDGE-COMPLETE**: for one fixture file, every kondo analysis
  entry of the mapped buckets has exactly one stored child; a bucket
  the bridge cannot map lands as a typed-unknown fact, never silently
  dropped (absence-as-signal).
- **P-CALLS-DERIVED**: the derived calls set over usages ≡ the
  pre-deletion stored set on the same fixture (computed once at
  conversion, then the stored set is gone).
- **P-REINDEX-REPLACES**: re-indexing a changed file replaces its
  owners' component children wholesale; stale usages are
  unconstructable (component retraction proves it).
- **Init timing gate**: usage volume is the big family — time
  `bin/seon init` before/after; >2× degradation is stop-and-report
  (the A1 lane's own rule).

## 5. Open to the owner

1. RULED YES (owner, 2026-08-29, ruling 51): settled run forms carry
   usage children — every agent form links the graph; cross-agent
   call analytics and the self-improvement derivation depend on it.
2. Locals/local-usages: kondo can emit them; recommended OFF (noise,
   volume) until a consumer exists — but state it so the omission is a
   decision, not a drop.
3. `:seon.fn/calls` deletion timing: same lane as the bridge
   (recommended — one mechanism, no transition window) vs a
   deprecation window with the drift regression.
