---
type: issue
status: open
severity: blocker
tags: [issue, flow, concurrency, agent]
---

# Bound work submission before Flow injection can block

## Problem

`seon.flow/submit!!` starts its declared time limit only after Flow accepts the
submission. With every compute slot wedged and the fixed input buffer full, the
initial injection blocks indefinitely, wedging later agent-turn procs outside
their configured limit.

## Evidence

- `src/seon/flow.clj:479-499` performs an unbounded `Future.get` on the
  injection before the timed result dereference.
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:190-197`
  implements injection as blocking `>!!` on the target channel.
- `test/seon/flow_test.clj:271-335` leaves compute capacity available; the
  pre-start tests at `:387-446` submit into buffers that still accept the item.
- A no-cluster probe with concurrency 1 and queue depth 1 wedged the active
  task, filled the buffer, and submitted a third task with a 20 ms limit. The
  third call was still blocked after 120 ms and completed only after release,
  reporting `:seon.flow/submission-wait-ms 128`.

## Owner

The bounded work-launcher submission seam in `seon.flow`.

## Acceptance

- The one declared limit covers admission, pre-start waiting, and execution.
- Saturating compute plus the input buffer returns a bounded result without
  leaking an injection future or wedging the caller.
- The regression saturates all capacity; a test that leaves one slot or buffer
  entry free does not prove this class.

## Implementation evidence — 2026-08-02, pending orchestrator review

Dependency ledger:

- core.async `dc35f3e0d7bc2eef502e77982f48641f025c8051`
  (`1.10.874-alpha3`), with blocking injection at
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:190-197`;
- Flow's ordinary proc read-set and control priority at
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-323`;
  and
- the first-party admission owner and recurring proof at
  `src/seon/flow.clj:187-551` and `test/seon/flow_test.clj:451-518`.

The audit falsifier was reproduced unchanged before implementation: compute
concurrency 1, queue depth 1, one latched active task, one buffered task, then
a third submission with a 20 ms limit. The third submission remained blocked
after 120 ms and completed only after release. It reported a 130 ms submission
wait; the enclosing call measured 131 ms.

Three designs were evaluated:

1. **Chosen — refuse at bounded capacity.** The one submission buffer owns an
   admission count of `concurrency + queue-depth`. A full admission completes
   immediately with a flat `:seon.flow/submission-capacity` error value. This
   gives up caller-parking backpressure: callers receive an explicit value and
   must adapt or retry.
2. **Rejected — let the time limit cover a blocking inject.** This preserves
   caller-parking semantics, but spends a clock on channel capacity that the
   process already observes and keeps one caller parked until the clock fires.
3. **Rejected — retain every submission behind a nonblocking launcher.** This
   requires an unbounded hidden queue, silent loss, or a second admission
   mechanism. It gives up bounded memory or reliable delivery.

`submit!!` no longer dereferences Flow's injection future. The refusing input
is nonblocking, while one timer begins before the injection request and covers
accepted injection scheduling, queued/pre-start waiting, and execution. Its
docstring states those boundaries and the exact meaning of
`:seon.flow/submission-wait-ms` (`src/seon/flow.clj:501-551`). A late injection
after the limit observes the existing cancelled status and cannot execute.

The regression first failed on the previous implementation: the saturated
third submission produced no capacity error and the prior buffered item was
drained before it could return. After the repair, the same saturated shape
returns `:seon.flow/submission-capacity`, preserves the already-buffered item,
then executes both admitted tasks after release without sleeps or elapsed-time
assertions. The one-time post-fix probe reported submission wait 0 ms and the
third future was already settled at the 120 ms checkpoint.

Focused gate: `bin/test seon.flow-test` ran 21 tests containing 126 assertions
with zero failures and zero errors. This note remains open for orchestrator
review as requested.
