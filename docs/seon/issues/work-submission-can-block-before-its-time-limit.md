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
