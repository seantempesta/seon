---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [issue, flow, agent, lock-for-a-race]
---

# `arm!` and `disarm!` hold one monitor across seconds of work

**Evidence ([flow usage audit](../../../docs/research/agent-platform/flow-usage-audit-2026-09-23.md) D6, source).** `(locking routing …)` in
`src/seon/cluster/agent.clj` `arm!` (`:917`) wraps `acquire-context!`: a branch open,
the projection carry and possibly `fork-cluster-ctx`. In `disarm!` (`:1070`) it wraps
`await-turn-completion!`, which includes a model call (`disarm-agents!` docstring,
`cluster.clj`). While any agent disarms, every other arm blocks, including the armer
proc's. The lock exists only because direct source installers race the armer proc
(`agent.clj:908-911`). `(locking contexts …)` at `:784` serializes acquisition the
same way. Related symptom:
[warm-restart-hangs-in-agent-arm-waiting-on-an-atom-monitor](warm-restart-hangs-in-agent-arm-waiting-on-an-atom-monitor.md).

**Wanted.** Arm and disarm are messages to the `:seon.agent/armer` proc, which is the
serializer; direct installers inject and await a reply under the backstop; both monitors
are deleted (audit rows 16, 17). Regression: a disarm awaiting a long turn does not
block another agent's arm.

**Owner.** `src/seon/cluster/agent.clj` and its direct installers. Audit §5 step 8.
