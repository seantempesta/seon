---
type: issue
status: open
severity: friction
tags: [issue, tooling]
---

# Operator stop crashes instead of falling back to SIGTERM

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

## Acceptance

Any stop-path failure (IO or eval) falls back to SIGTERM loudly,
naming the failure and the shared-JVM blast radius as the existing
fallback already does; one regression with a stub prepl returning an
eval exception. Second, smaller: `status` (or the stop error) surfaces
seon JVMs that advertise nothing, so orphans are visible.
