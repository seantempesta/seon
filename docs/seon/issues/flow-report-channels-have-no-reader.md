---
type: issue
status: open
severity: minor
created: 2026-09-23
tags: [issue, flow, dead-plumbing]
---

# Flow report channels have no reader

**Evidence ([flow usage audit](../../../docs/research/agent-platform/flow-usage-audit-2026-09-23.md) D9, probe P4 on `default`).** Agent graphs'
`report-chan` is never read: P4 found 25 queued. The cluster fan-out's
`::application-report-channel` (`src/seon/flow.clj` `start-error-fanout!`) has no
consumer (`rg` finds none). Harmless (sliding, observational), but ping and oversight's
`report` are the only live observation.

**Wanted.** Either one consumer (the monitor view) or delete the unread tap with the
fan-out (audit §3, option B). No regression needed for a deletion.

**Owner.** `src/seon/flow.clj` fan-out, with audit §5 step 3.
