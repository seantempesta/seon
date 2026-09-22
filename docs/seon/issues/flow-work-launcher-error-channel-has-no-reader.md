---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [issue, flow, errors, swallowed-error]
---

# The work launcher graph's error channel has no reader

**Evidence ([flow usage audit](../../../docs/research/agent-platform/flow-usage-audit-2026-09-23.md) D1, commit 120353209).** A throwing
background submission becomes a `::flow/error` output of `work-launcher-step`
(`src/seon/flow.clj:569-578`). Flow routes that port to the graph's own
`error-chan`, which is `(async/sliding-buffer 100)`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:101-102`,
`:161`; core.async `dc35f3e`). `start-work-launcher!` (`flow.clj:656`) returns the
started channels, but no join is made (`src/seon/cluster/boot.clj:73-77`;
`rg ':error-chan'` finds only the cluster graph and the agent graphs). Probe P4: the
launcher `error-chan` count 0, no reader. The submitter's `complete!` still sees the
throwable, but the fault route never does, and the 101st fault evicts the first.

**Wanted.** The launcher's error channel joins the cluster fault channel through the
existing `seon.flow/join-error-fanout!`, like every agent graph. Regression: a
throwing submission reaches the fault channel.

**Owner.** Boot/cluster wiring (`boot.clj` or `cluster/arm-agents!`). Audit §5 step 1.
