---
type: issue
status: open
severity: high
created: 2026-09-26
tags: [issue, sci, repl, dogfood]
---

# `letfn` and `defrecord` with an inline protocol implementation fail in the agent REPL, while plain SCI runs them

Found by the standing dogfood lane (dogfood-repl-opus), working only through MCP.

## Observed (default archive 04f3a159b, branch `dogfood-repl-opus-15`, 2026-09-26)

Reproduced in three separate runs:

| Form | Agent REPL (MCP SCI, branch) | Plain SCI, same JVM (`(sci.core/eval-string …)` in MCP JVM mode) | `clojure.main` |
|---|---|---|---|
| `(letfn [(f [] :ok)] (f))` | `… got nil nil. Fix: No implementation of method: :getName of protocol: #'sci.impl.types/HasName found for class: nil` | `:done` for the recursive variant | `:ok` |
| `(defprotocol Labeled (tag-of [x]))` then `(defrecord Card [title] Labeled (tag-of [_] title))` | `… Fix: class sci.impl.vars.SciUnbound cannot be cast to class clojure.lang.Associative …`, then `(->Card "x")` gives `Attempting to call unbound fn: #'user/->Card`. The same happens when the protocol comes from an earlier run | `"Book: SICP"` | works |
| `(defrecord Pt [x y])` then `(->Pt 1 2)` | works, but prints `#object[sci.lang.Type]` and `#object[sci.impl.records.SciRecord]` | not applicable | `user.Pt` and `#user.Pt{:x 1, :y 2}` |
| `(extend-protocol Labeled String (tag-of [s] …))` | works, but prints `#object[clojure.lang.MultiFn]` | not applicable | `nil` |
| `(let [g (fn g [n] …)] (g 3))`, `deftype`, `defmulti`/`defmethod`, transducers, `format`, `re-seq`, metadata | work | | |
| `(Math/abs -3)` | `Unable to resolve symbol: Math/abs` | the same (SCI needs `:classes`) | `3` |

## Which model is wrong

Plain SCI, from the fork `reference-code/sci` loaded in the same JVM, evaluates both forms. So the failure
is in how Seon's evaluation drives SCI, not in SCI itself. Both messages name **unbound SCI state during
analysis**: `HasName/getName` on `nil`, a namespace, and a `SciUnbound` var deref'd as a map. SCI's
analyzer reads its dynamic state, for example `@utils/current-ns` (`analyzer.cljc:842-847`). One
hypothesis, **not verified**: Seon analyzes or expands forms in a binding scope that lacks SCI's
namespace bindings (a pre-pass separate from evaluation), so macros that analyze nested definitions
(`letfn*`, `defrecord`'s inline method bodies) see nil. The owner should confirm this at the REPL by
comparing `sci.core/eval-string*` on a fork of the agent's own context with the entrance's
evaluation.

Separately, records and types print as `#object`. This is the floor's exact-class allowlist again
([issue](the-floor-prints-ordinary-seqs-as-objects-and-finite-lazy-results-as-pending.md)): a record is
a map. `java.lang.Math` is absent from the context's `:classes`.

## Expected

`letfn` and `defrecord`/`deftype` with inline protocol methods behave as in `clojure.main`. Records print
as tagged maps.

## Regression

Parity rows for `letfn`, `defrecord` with an inline protocol, and record printing, run through the agent
entrance.
