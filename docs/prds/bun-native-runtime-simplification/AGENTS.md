---
type: orchestrator
status: active
tags: [orchestrator, prd, flow, web, agent]
---

# Bun-native runtime simplification — working context

This PRD owns the measured replacement of Node compatibility machinery with a
smaller Bun-native host boundary. Read [[roadmap]], the two July 15 Bun audits,
the independent-distribution roadmap, and architecture runtime/UI before work.

The unit is removal-first. Preserve Seon's data contracts, public agent
functions, database protocol, render derivation, routes, and operator lifecycle;
replace only host mechanics whose native Bun implementation is measured and
better. One capability-shaped owner exists for subprocesses, HTTP/feeds, and
framed sockets. Do not expose Bun objects in agent-facing envelopes or create a
Node/Bun compatibility namespace.

Every cut begins with exact Bun source and a baseline over the current owner.
Delete superseded imports, adapters, tests, dependencies, and documentation in
the same cut. A smaller diff is not success unless behavior, cancellation,
bounded memory, shutdown, and live browser/database evidence remain green.

Research and retained measurements belong in `research/`; current state, order,
deletion ledger, and graduation evidence belong in [[roadmap]].
