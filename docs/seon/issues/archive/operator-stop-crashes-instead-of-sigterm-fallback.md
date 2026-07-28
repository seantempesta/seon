---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, archive]
---

# Operator stop crashes instead of falling back to SIGTERM

## Problem

Live-proven 2026-07-28 night resetting the 500'ing default cluster.
`bin/seon-fresh stop` catches only `java.io.IOException` around its
prepl stop; a remote EVAL failure (here: a stale instrumented-schema
JVM refusing `stop!`'s input — the pre-`808c299d4` snapshot vs the
live instance's F1 `routing` key) crashes the command instead of
engaging the SIGTERM fallback. This is the exact catch-bypass the
2026-07-28 morning review flagged; the `-original` de-hack wave fixed
the other findings but left this one.

Also observed: a forgotten morning-era JVM (PID 84702) squatted all
day, invisible — `status` shows only advertised clusters, never
orphan seon JVMs.

## Owner

`script/seon/fresh_operator.clj` owns both the prepl-to-SIGTERM stop
fallback and the advertisement-derived status projection.

## Acceptance

Any stop-path failure (IO or eval) falls back to SIGTERM loudly,
naming the failure and the shared-JVM blast radius as the existing
fallback already does; one regression with a stub prepl returning an
eval exception. Second, smaller: `status` (or the stop error) surfaces
seon JVMs that advertise nothing, so orphans are visible.

## Resolution

Resolved by `588cf6ab6`. The prepl stop catch now covers every thrown
stop-path failure, including the `ExceptionInfo` produced by an
exceptional prepl `:ret`, and routes it through the existing loud
`sigterm!` output. `status` walks live `ProcessHandle`s, recognizes the
exact detached `java ... clojure.main -e <launch-form>` tail, subtracts
the live advertisement PID set, and prints the remaining orphan PIDs.
No orphan state is stored.

Proof:

- `seon.dev.fresh-operator-test/eval-failure-falls-back-to-sigterm`
  uses a plain `ServerSocket` to return an exceptional prepl `:ret`.
  The shipped Babashka command printed the named failure and
  shared-JVM blast radius, returned success, and stopped the disposable
  advertised child with SIGTERM: 1 test, 7 assertions, green.
- Full `bin/test`: 397 tests, 1,571 assertions, 0 failures, 0 errors.
- Live `bin/seon-fresh status` printed `orphan seon JVMs: none` from
  the derived process walk.
- A live scratch-cluster drive hit the stale instrumented-schema
  failure on both add and stop. Stop printed
  `The cluster rejected the prepl operation.`, named shared clusters
  `default, operator-stop-proof-728`, selected `path=SIGTERM`, and the
  shared PID exited. This proved the repaired eval-failure fallback
  end to end. It also stopped `default`, contrary to this lane's
  isolation instruction; both advertisements were left stale and the
  lane did not restart or reset `default`.
