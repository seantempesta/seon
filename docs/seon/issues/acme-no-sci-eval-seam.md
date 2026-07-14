---
type: issue
status: open
severity: friction
tags: [issue, agent]
---

# acme cluster has no programmatic SCI eval seam

## Problem

There is no current-operator-owned way to eval a CLJS form in the **acme**
cluster's pod SCI runtime programmatically. The unified MCP can address any
cluster-qualified runtime advertised through its Shadow server, but preserved
ACME runs an unwatched legacy `out-acme` bundle from another worktree and is
not owned or advertised by the current operator. Its writer REPL is CLJ, not
the CLJS SCI cage.

## Impact

Surfaced by the eval-tier masked-divergent measurement
([[parser-as-generation-oracle-2026-06-28]]): it needed to eval ~250 forms in
the SCI cage, but had to run them on the DEFAULT pod's `:client` runtime instead
of acme. That was SAFE only because every form was a pure expression (no db
writes / defs / agent state, verified by construction) — but it means any
acme-side live-eval measurement, or a probe that must run in acme's isolated
store context, currently has no seam. The only way to exercise acme's SCI
runtime today is to drive a full live AGENT on it (the agent loop evals), which
is heavyweight and indirect.

## Acceptance criteria

Make the migrated ACME runtime discoverable through the existing unified MCP
runtime-selection mechanism with a cluster-qualified agent id. Do not add an
HTTP `/eval`, second socket REPL, or ACME-only eval implementation. The seam
must resolve to ACME's own database and fail on ambiguous/bare ids. Then the
eval-tier measurement can run in the isolated cluster as intended.

## Workarounds (today)

- Pure expressions on the default pod's `:client` runtime (no side effects) —
  what the measurement did.
- Drive a live agent on acme (its turn loop evals) — heavyweight, indirect.
