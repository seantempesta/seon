---
type: issue
status: open
severity: blocker
created: 2026-09-23
tags: [issue, flow, errors, policy]
---

# Durable faults cross dropping channels

**Evidence ([flow usage audit](../../../docs/research/agent-platform/flow-usage-audit-2026-09-23.md) D5; probe P3).** Every graph's errors reach
the committer through core.async's sliding-100 `error-chan`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:101-102`),
then a `mult`, then Seon's `counted-dropping-buffer` of 64 (`src/seon/flow.clj`
`start-error-fanout!`, `src/seon/cluster.clj` `:seon.flow/fault-buffer-capacity 64`).
Six out-of-flow sites `offer!` onto that channel and fall back to `println`
(`turn.clj` write-refusal and turn-backstop faults, `cluster/agent.clj` stop backstop,
`render/web.clj` render/feed/data faults). P3: committer `{committed 0 lost 0}`,
buffer capacity 65. AGENTS' error policy: a fault is committed at the owning boundary
and never dropped by an overload channel.

**Wanted.** A synchronous `seon.fault/fault!` at each boundary and one drain per graph
(audit §3.4, option 1), deleting the counted-dropping buffer and the committer graph.

**Owner.** `flow.clj` plus the six sites; after one-error-route step 0. Audit §5 steps 2-4.
