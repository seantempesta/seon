---
type: issue
status: resolved
severity: blocker
created: 2026-09-17
tags: [operator, adoption, bounded-execution, class/absence-as-health]
---

# Development adoption's hold bound measures duration instead of progress

## Problem

`data/operator/operations/init-lifecycle-94902.log` and
`init-lifecycle-97908.log` refuse their own holder after 180027 and 180047 ms.
The lifecycle lock measures total hold time even though publication and
adoption expose real phase progress. The CLI takes the 180000 ms publication
limit from `.claude/seon-hook.edn`; its request does not carry the operator's
30000 ms event-silence configuration. The JVM operator's internal fallback is
separately 900000 ms at the inspected HEAD; neither is phase liveness.

The supplied comparison `init-init-58066.log` took 170704 ms but FAILED during
`development reload seon.operator`: `No such var:
state/cleanup-root-under-lock!`. Its elapsed time is not successful convergence.

## Repair and verification

The lock observes a request-carried progress atom, subscribes before work
starts, renews its monotonic silence window on phase events, and persists the
phase/deadline in the holder record. Completion releases kernel custody;
silence names the phase and leaves custody with unfinished work. The CLI
passes the declared silence configuration and publication progress explicitly.
Progress output also records the completed phase and its elapsed milliseconds.

The first HEAD-plus-owned-paths fast iteration passed 1 test / 7 assertions.
Literal-duration scratch and live adoption evidence are being collected in
[the landing note](../../prds/steward-platform/research/adoption-margin-2026-09-17.md).

## Additional observed boundary

A JSON thread dump of default pid 94566 at 05:16Z showed an earlier publication
inside source analysis and three later prepl calls blocked in
`refresh-source!`'s monitor. Later snapshots showed this queue growing after
CLI timeouts. A closed CLI does not cancel its submitted JVM evaluation.
No foreign session was stopped or resumed. A transport timeout and a remote
operation's terminal event are different boundaries; the unfinished remote
work must not be mistaken for completed adoption.

## Resolution — 2026-09-17

The literal progressing holder completed 540838 ms; the stalled SCI phase
refused after 30075 ms under the declared 30000 ms window. Default pid 94566
converged without restart/refork, and an explicit `bin/seon init --dev default`
returned successfully in 37354 ms (190874 ms including lock acquisition).
MCP then read equal adopted/published commit IDs,
`6aab814a-c7b5-5d2d-950b-1ea7970b16d6`, and the unchanged 30000 ms effective
configuration. Phase measurements, hot-reload scope and remaining unrelated
verification failures are in the linked landing note. Cold/platform proof
remains the orchestrator's responsibility.
