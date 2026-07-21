---
type: orchestrator
status: active
tags: [orchestrator, prd, database, flow]
---

# Database lifecycle recovery — working context

This successor PRD owns database-native reconstruction and lifecycle semantics.
Read [[roadmap]], the architecture database/runtime targets, and the closest
source `AGENTS.md` files before research or code.

Every plan begins with exact Datahike, Konserve, Kabel/transport, Malli, and
Seon database-protocol dependencies: selected versions/SHAs,
`reference-code/` paths, maintained source behavior, and idiomatic call sites.
Probe each critical assumption through the live CLJ/CLJS REPL before editing.

Strengthen `seon.db`, its writer/backend/registry/replica owners, and the one
protocol in place. Do not add a second registry, manifest authority, replay
engine, lifecycle state machine, physical-copy history mechanism, or transport-
specific semantic branch. Runtime projections reconstruct from committed facts.

Research goes in `research/`; current state, gaps, order, and graduation proof
stay in [[roadmap]]. Implementation begins only after the current
runtime-reliability branch graduates.
