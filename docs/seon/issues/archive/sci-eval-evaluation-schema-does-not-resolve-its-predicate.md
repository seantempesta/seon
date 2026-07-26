---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, database]
---

# Resolve `seon.sci.eval`'s value predicate at schema admission

## Resolution

Resolved by the commit that archives this note.

`::value` is now one admitted named `[:fn]` predicate schema whose callable is
supplied through the existing core-predicate cache. The predicate preserves the
genuinely polymorphic successful SCI value surface while requiring every
error-shaped value to carry the evaluator's complete flat error contract.
`::record` now names all five diagnostics that `evaluate` returns instead of
accepting an arbitrary map. There is no second schema registration.

The pre-edit JVM REPL probe established why the successful arm cannot honestly
be narrowed before the separate deep-realization boundary lands: current SCI
evaluation returns nil, lazy sequences, SCI functions, atoms, promises, and
even Throwable objects as ordinary successful values. The related
[[../lazy-authored-values-escape-the-armed-interrupt-boundary]] issue therefore
remains open.

## Proof

Before the fix, `tmp/plan-evidence/test-writer-2026-07-26.log` reported:

- `FAIL composes-the-established-frozen-prompt-projections` at lines 50 and 52;
- `FAIL no-stored-attribute-promises-an-order-the-database-cannot-keep`; and
- `ERROR every-registered-wire-shape-is-total-and-round-trips`.

After the fix,
`tmp/plan-evidence/test-writer-2026-07-26-suite-green.log` reports 545 tests,
3,849 assertions, three failures, and zero errors.
`every-registered-wire-shape-is-total-and-round-trips` passes. Both
`composes-the-established-frozen-prompt-projections` assertions still fail, so
the issue's “three of four” hypothesis is disproved rather than silently
accepted. The remaining third failure is the known ordered-collection design
issue and is outside this fix.

The focused post-edit JVM REPL probe also proves that `::value` resolves,
accepts `45`, rejects an incomplete error-shaped map, and that `::evaluation`
accepts the exact five-key diagnostic record.

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

## Hypothesis worth testing first: this may be THREE of the four failures

`composes-the-established-frozen-prompt-projections` fails twice
(`prompt_test.clj:50`, `:52`) because `schema/valid-candidate-value?` returns
false for `:seon.agent.prompt/render-request` and `:seon.agent.prompt/rendered-prompt`
on values that look structurally fine. If one invalid registration prevents the
schema population from admitting, every `valid-candidate-value?` call against
that population would fail regardless of its input — which would make this one
defect the cause of 3 of the run's 4 failures.

**`[UNVERIFIED]`.** Test it before fixing anything else: fix this registration,
re-run `bin/test-writer`, and see whether the prompt failures disappear. If they
do, the run is one defect away from green and the prompt test is not broken at
all. If they do not, the prompt failures are independent and need their own
diagnosis. Either answer is cheap and changes what to do next.

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

Related: [[../lazy-authored-values-escape-the-armed-interrupt-boundary]],
[[../../../prds/sci-execution-runtime/plan/state.md]] §8.
