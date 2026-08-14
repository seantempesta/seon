---
type: issue
status: resolved
severity: friction
tags: [issue, operator, test, wave/operator-child-lifecycle]
---

# Bound operator subprocess reads and waits

## Problem

Several foreign subprocess paths read all output and wait without a backstop.
A child that keeps stdout open or never exits can wedge the operator; two
direct dev tests repeat the same unbounded wait and can wedge the suite.

## Evidence

- `src/seon/cluster/export.clj:118-125` drains merged clone-command output and
  then calls un-timed `.waitFor`; a clone child that stays alive wedges export.
- `script/seon/fresh_operator.clj:746-756` uses unbounded `slurp` then
  `.waitFor` for offline roster discovery.
- `script/seon/fresh_operator.clj:1454-1470` launches the detached cluster path
  without a bounded child handoff.
- `script/seon/fresh_operator.clj:1846-1853` uses unbounded `slurp` and wait for
  initialization.
- `script/seon/fresh_operator.clj:2046-2063,2419-2439` also waits without a
  backstop for browser/tail helpers.
- `test/seon/dev/changed_test_test.clj:5-13` and
  `test/seon/dev/edit_feedback_test.clj:11-21` are the two direct test waits
  without explicit time bounds; the other subprocess tests use backstops.

## Owner

One foreign child-process runner, shared by export and the operator, that owns concurrent output draining,
process completion, exact-process termination, and a loud foreign-process
deadline.

## Acceptance

Every spawned foreign process has one observable completion path and one loud
deadline backstop. Output cannot deadlock the wait, timeout terminates only the
captured process identity, and the two tests fail boundedly when their child
wedges.

## Resolution — 2026-08-14

`seon.operator.state/run-process!` is the one finite operator-subprocess seam.
Its required monotonic deadline covers process exit and both output-capture
futures. A firing throws typed
`:seon.operator.subprocess/deadline-exceeded` data naming the argv, phase,
deadline, and root process identity instead of returning partial output or
silently waiting.

Timeout cleanup derives the exact `(pid, start-instant)` identities visible at
launch and again immediately before termination, unions those observations,
and force-waits only that finite set under its cleanup backstop. The reported
`:seon.operator.subprocess/reaped?` is true only when every captured member is
gone; it is not merely the root process's exit status.

The finite production members now enter that seam: changed-test host analysis
and gate execution, clj-kondo classpath/lint analysis, Markdown Git probes,
cluster-export cloning, and fresh-operator dependency-cache, offline-roster,
detached-launch handoff, source initialization, browser-open, managed-root
cleanup, and log-tail calls. The direct changed-test, dependency-cache, and
edit-feedback test subprocess owners use the same contract. No Clojure
`start-child-jvm!` ProcessBuilder survives. The detached Python wrapper is a
finite seam child: it launches the managed JVM, publishes that JVM's pid only
after the adoption protocol, and exits. Readiness sockets and exact process
records then own the managed JVM's ongoing lifecycle.

The recurring class regression coordinates a child through filesystem events,
spawns a descendant only after launch observation, and proves that a descendant
which ignores TERM is still reaped by the typed deadline. Independent cases
prove that an undeclared deadline refuses before launch and that the same
deadline covers a capture future after child exit. Focused gates passed:

- `seon.dev.changed-test-test`: 5 tests, 12 assertions;
- `seon.dev.dependency-cache-test` plus `seon.dev.edit-feedback-test`: 8 tests,
  51 assertions.
