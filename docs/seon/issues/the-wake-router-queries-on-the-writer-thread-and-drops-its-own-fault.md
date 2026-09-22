---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, flow, datahike, writer-path, swallowed-error]
---

# The wake router queries on the writer thread and drops its own fault

**Evidence ([flow usage audit](../../../docs/research/agent-platform/flow-usage-audit-2026-09-23.md) D4, source).** On any `seon.wake`,
`seon.listen` or schema datom, `route!`'s listener rebuilds its matchers with
`(wake-matchers (:db-after report))` and `arming-attributes` inside the Datahike
callback (`src/seon/cluster/wake.clj:517-524`): database work on the one writer's
delivery path. Its catch `offer!`s a bare Throwable onto the counted-dropping fault
channel and ignores the result (`wake.clj:546-547`).

**Wanted.** The listener only offers a sliding-1 wake to a router proc that derives from
`(d/since …)` at its own basis (audit row 1); the listener's residual failure path is an
open owner decision (audit §4, option 2). Regression: the wake-routing suite unchanged,
and no query runs inside the listener.

**Owner.** `src/seon/cluster/wake.clj`. Audit §5 step 6.
