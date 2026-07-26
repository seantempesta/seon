---
type: issue
status: open
severity: blocker
tags: [issue, runtime, database]
---

# Resolve `seon.sci.eval`'s value predicate at schema admission

## Problem

`:seon.sci.eval/evaluation` is not a valid registered schema. Admitting the
schema population throws: the registry cannot resolve
`seon.sci.eval/evaluation-value?`, the named predicate its `::value` member
points at.

This is in `seon.sci.eval` — the namespace at the centre of the surviving
runtime — so every evaluation's response shape is currently unvalidated, and the
totality property the wire-shape test exists to prove cannot be established for
it.

## Evidence

Surfaced the moment `bin/test-writer` was restored on 2026-07-26 (the gate had
been discovering 0 tests because the compiled artifact was stale). Retained log:
`tmp/plan-evidence/test-writer-2026-07-26.log`.

```
ERROR in (every-registered-wire-shape-is-total-and-round-trips) (internal.cljc:256)
clojure.lang.ExceptionInfo: schema/register! :seon.sci.eval/evaluation:
  [:map {:closed true}
   [:seon.sci.eval/value :seon.sci.eval/value]
   [:seon.sci.eval/record :map]]
  is not a valid Malli schema (:malli.core/invalid-schema).
  Missing schema reference seon.sci.eval/evaluation-value? from namespace seon.sci.eval.
{:seon.schema/missing-reference seon.sci.eval/evaluation-value?
 :seon.schema/missing-reference-namespace "seon.sci.eval"}
```

The registration sites are in `src/seon/sci/eval.clj`: `evaluation-value?` is
defined there, `::value` is registered as the quoted symbol
`'seon.sci.eval/evaluation-value?`, and `::evaluation` is a closed map
referencing `::value`. So the predicate exists in source but is not resolvable at
the point the population is admitted.

Note what the predicate currently is — `(fn [_] true)` — so even once it
resolves it asserts nothing. Whatever the fix, decide whether an
always-true predicate is the right contract for a value crossing out of an
eval, given that the same boundary is where deep realization and bounding must
happen ([[lazy-authored-values-escape-the-armed-interrupt-boundary]]).

## Owner

`seon.sci.eval` owns the shape. `seon.schema` owns admission and is the
authority on how a named predicate becomes resolvable — the error message names
the contract: *"If the form references another schema keyword, register that
keyword in the same admitted schema population."*

## Acceptance

- Admitting the schema population with `seon.sci.eval` loaded raises nothing,
  and `every-registered-wire-shape-is-total-and-round-trips` passes.
- The fix is a real contract, not a rename: either the value member is a genuine
  predicate over what an evaluation may return, or it is a registered schema
  keyword. An always-true predicate that merely resolves is not acceptance —
  under the repo rule, `:any` and its equivalents require a proven genuinely
  polymorphic boundary, and this boundary is the opposite of polymorphic: it is
  exactly where values must be proven ordinary and bounded.
- No second registration path is added to make the reference resolve.

Related: [[lazy-authored-values-escape-the-armed-interrupt-boundary]],
[[../../prds/sci-execution-runtime/plan/state.md]] §8.
