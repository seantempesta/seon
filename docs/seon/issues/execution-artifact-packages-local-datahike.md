---
type: issue
status: open
severity: friction
tags: [issue, architecture, database, agent]
---

# Remove local Datahike from the execution artifact

## Problem

Every per-agent Bun child currently packages the local Datahike implementation
even though the child performs database work through a remote authority
session. This avoidable dependency graph inflates cold-start, artifact size,
and retained memory exactly where modest-hardware density requires a thin
process.

## Evidence

The `:execution` Shadow build starts at `seon.execution/-main`.
`src/seon/execution.cljs` requires the monolithic `seon.db` namespace, whose
top-level dependencies include Datahike API and implementation namespaces.
The source-grounded audit
[[../../prds/database-authority-mesh/research/bun-child-modest-hardware-supervision-policy-2026-07-16]]
therefore identifies Datahike, persistent-sorted-set, superv.async, and
partial-cps as a density confounder in every child. Measurements of the current
artifact are a baseline, not evidence for the final active-child limit.

## Owner

The existing `seon.db` implementation and Shadow execution build dependency
boundary. Preserve the one public `seon.db` function/schema interface while
selecting only its remote session implementation in the execution artifact;
do not create a second database API.

## Acceptance

- Shadow reachability proves the execution artifact contains the remote
  authority client but no Datahike, persistent-sorted-set, superv.async, or
  partial-cps implementation code.
- Existing `seon.db` callers and ordinary protocol fixtures remain unchanged.
- Packaged cold-ready, artifact bytes, and per-child RSS improve or the retained
  measurements explain why the dependency split is not material.
- The 1/4/16/32 density matrix uses the remote-only artifact when selecting the
  shipped child cap.
