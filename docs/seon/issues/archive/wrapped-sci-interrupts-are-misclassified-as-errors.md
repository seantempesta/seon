---
type: issue
status: resolved
severity: blocker
tags: [issue, sci, eval, runtime]
---

# Recognize wrapped sci interrupts through their cause chain

## Problem

`seon.sci.eval/interrupted?` inspects only the top-level throwable's
`ex-data`. Sci wraps an interrupt raised inside an interpreted function to
attach source location and callstack data, leaving its private interrupt
marker on the cause. Seon therefore records the common wrapped time-limit
failure as `:seon.eval/outcome :error` instead of `:time`, omits
`:seon.cluster.eval/interrupted-at`, and lets downstream admission treat the
interrupt as an ordinary failure.

## Evidence

`src/seon/sci/eval.clj:213-221` checks only for a top-level
`:sci.impl/interrupt` key. Sci's marker owner is
`reference-code/sci/src/sci/impl/utils.cljc:47-56`, and its
`rethrow-with-location-of-node` wrapper is at
`reference-code/sci/src/sci/impl/utils.cljc:121-151`. The observed wrapped
chain and reproducing probes are recorded in
`docs/prds/sci-execution-runtime/research/sci-interrupt-ground-truth-2026-07-31.md`
§6: the outer throwable has location/callstack data and the cause satisfies
`sci.impl.utils/interrupt-ex?`.

## Owner

`seon.sci.eval/interrupted?` is the one system owner of interrupt
classification; `seon.sci.admit` consumes that answer.

## Acceptance

- `interrupted?` recognizes a real sci interrupt both directly and through a
  wrapper by walking the throwable cause chain with sci's own
  `sci.impl.utils/interrupt-ex?` predicate.
- A time limit raised inside a previously defined interpreted function returns
  the flat `:seon.sci.eval/time-limit` value, records
  `:seon.eval/outcome :time`, and carries
  `:seon.cluster.eval/interrupted-at`.
- Ordinary wrapped exceptions remain ordinary errors.

## Resolution

Resolved by `7ed006f18`. `seon.sci.eval/interrupted?` now walks the throwable
cause chain and delegates marker identity to
`sci.impl.utils/interrupt-ex?`. A wrapper with the real marker on its cause is
classified as an interrupt; a forged raw key and ordinary wrapped exceptions
remain ordinary errors.

The recurring wrapped-interrupt test and the cross-eval time-limit regression
both pass in `seon.sci.eval-test` (14 tests / 53 assertions). The complete SCI
owner selection passes 34 tests / 233 assertions, and the live cross-eval
falsifier records `:seon.eval/outcome :time` plus
`:seon.cluster.eval/interrupted-at` at approximately 505 ms.
