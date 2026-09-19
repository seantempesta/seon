---
type: issue
status: open
severity: blocker
created: 2026-09-19
tags: [issue, agents, schema]
---

# Namespace responsibility and issue workers are singular

## Problem and evidence

The owner clarified on 2026-09-19 that several namespace agents may work on
the same namespace. `:seon.ns/steward` is a cardinality-one ref and
`seon.cluster.agent/steward-call` retains the first agent
(`src/seon/cluster/agent.clj:128`). Nonunique `:seon.agent/namespace` allows
shared evaluation namespaces but does not fix singular responsibility.

`seon.issue` also has a scalar agent ref and derives its worker identity
solely from the issue identity (`src/seon/issue.clj:885`). Starting work
therefore reuses that worker. The [audit](../../prds/steward-platform/research/schema-audit-b-supplement-2026-09-19.md)
distinguishes these mechanisms and includes installed-schema evidence.

## Acceptance

Namespace responsibility supports multiple agents independently from the
current evaluation namespace. Task participation/attempt identity does not
force exclusive namespace ownership or silently duplicate an obligation.
Prove two agents can cover one namespace and perform independent changes;
when collaborating on one task their evidence remains attributable.
Generalize the existing issue/plan owner for non-defect tasks, including
reply tasks, instead of adding a parallel scheduler. The
[design](../../prds/steward-platform/research/namespace-agents-design-2026-09-19.md)
records proposed names and the dependent gates. Schema and all consumers
must land together at the reset boundary.
