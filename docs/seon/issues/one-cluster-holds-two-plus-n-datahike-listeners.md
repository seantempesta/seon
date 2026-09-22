---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, flow, datahike, listeners, writer-path]
---

# One cluster holds 2 + N Datahike listeners, and one fixed key is silently replaced

**Evidence ([flow usage audit](../../../docs/research/agent-platform/flow-usage-audit-2026-09-23.md) D3, probe P1 on `default` pid 70720).**
`(keys @(:listeners (meta (seon.cluster.boot/connection "default"))))` →
`[:seon.call-preparation/rows :seon.agent/route #uuid "59397c0e-…"]` with one agent:
the call-preparation listener (`src/seon/call_preparation.clj:578`), the router
(`src/seon/cluster/wake.clj:505`) and one random-uuid key per agent from the schedule
proc (`src/seon/schedule.clj:799`). Every listener runs on the writer's delivery path.
The call-preparation listener derives `snapshot` inside the callback
(`call_preparation.clj:582`) and uses one fixed key, so each `watch!` from a fork
(`sci/eval.clj` `watch!` callers) replaces the previous context's listener, and
nothing ever unwatches. The flow skill's "one listener per cluster" claim is false.

**Wanted.** One listener per cluster that only offers a payload-free wake; the router
proc derives schedule interest and delivers (audit rows 1, 2, 4). Regression: a cluster
with N armed agents holds exactly one listener key.

**Owner.** `cluster/wake.clj`, `schedule.clj`, `call_preparation.clj`. Audit §5 step 6.
