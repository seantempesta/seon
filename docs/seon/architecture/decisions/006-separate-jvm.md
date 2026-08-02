---
type: decision
status: superseded
date: 2026-02-14
tags: [decision, architecture, archive, agent]
---

# ADR-006: Separate JVM processes for agent isolation

This decision is superseded by
[[architecture/decisions/012-process-root-cluster-topology]]. Seon does not run
one application process, database, REPL, or mutable registry per agent.

The replacement follows the 2026-07-27 branch-per-cluster ruling and the
2026-07-28 agents-are-flows ruling. Agents are independent Flow graphs inside
their cluster; several sovereign cluster branches may run in one JVM over the
process-root store and shared executors. Run custody is the presence of
`:seon.cluster.run/process`; there is no claim epoch.
