---
type: issue
status: resolved
severity: friction
tags: [issue, database, runtime, concurrency]
---

# `cluster/stop!` races an in-flight transact and dumps a stack trace on a pool thread

## Problem

Stopping a cluster while a transaction is still in flight releases the
connection out from under it. The writer's dispatch then finds `nil` where its
`PWriter` was and throws on a `core.async` pool thread, where nothing catches
it and nothing records it:

```text
Exception in thread "async-mixed-57"
java.lang.IllegalArgumentException:
  No implementation of method: :-dispatch! of protocol:
  #'datahike.writer/PWriter found for class: nil
  at datahike.writer$dispatch_BANG_ (writer.cljc:312)
  at datahike.writer$transact_BANG_$fn__…/state_machine (writer.cljc:367)
```

Sometimes it lands as a core fault instead, which is worse because the fault
carries nothing:

```text
seon.error commit-fault! failed: Connection has been released.
SEON CORE FAULT (dev panic): nil
```

## Evidence

Reproduced repeatedly on 2026-07-28 in ordinary boot/stop loops
(`tmp/block_divergence_loop.clj`, twelve boots — several of the twelve threw)
and in single-boot probes that transact and then stop. It is timing-dependent:
the same script does not throw every run.

The immediate producer is the web view — `stop!` tears the view down while a
render's commit is still on its way to the writer.

## Impact

Two honest failures wear the wrong clothes. A stack trace on `async-mixed-N`
is invisible to the operator, to the fault facts, and to any test; and a core
fault whose payload is `nil` names no owner, so "who should fix this" stops
being a query — the one property the fault committer exists to provide.

It also makes every boot-loop probe noisy, which is how it got ignored: the
trace scrolls past between real results.

## Owner and acceptance

`seon.cluster/stop!` and the release path in `seon.cluster.store`. Stop is
already instance-addressed; it now needs to be ORDERED — quiesce the producers
(view, then loop) and let their in-flight commits settle before the connection
is released, rather than releasing and letting the writer discover it.

- Boot and stop a cluster with a transaction deliberately in flight, in a loop,
  with no exception reaching any pool thread's default handler.
- If a commit genuinely cannot complete because the cluster is going down, it
  becomes a fault fact with provenance, never a `nil` panic.

Related: `cluster-stop-release-failure-becomes-unaddressable.md`.

## Resolution

Resolved by `852ef9759`.

Orderly shutdown now has an explicit completion dependency for each database
producer it stops. The run-loop proc publishes once from its
`::flow/stop` transition, which Flow can only invoke after the active pass
returns; `disarm-loop!` awaits that event before releasing the branch
connection. A seconds-long model call or transaction therefore extends
orderly stop honestly. A process kill still does not wait and remains owned by
the crash model.

The first green teardown exposed the same missing join in the fault-committer
proc. Its source error channel also yields one terminal `nil` when closed,
which had been misread as a core fault. The existing proc now treats that
value as lifecycle, publishes completion from its own stop transition, and
`stop-error-fanout!` awaits an active durable fault commit before returning.
No Flow SPI change, sleep, or production timeout was required.

## Verification

- Before the fix, `orderly-stop-awaits-the-active-loop-pass` failed because
  `stop!` returned while the pass was held, then reproduced both
  `PWriter … for class: nil` and the nil core-fault path.
- Focused gate after the fix:
  `38 tests, 185 assertions, 0 failures, 0 errors`.
- Full gate after the fix:
  `388 tests, 1509 assertions, 0 failures, 0 errors`.
- The focused and full teardown output contains no released-connection,
  `PWriter`, or terminal-nil core fault. The only development panic in the
  focused gate is the fault suite's named injected fault.
