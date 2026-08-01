---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, mcp, deletion]
---

# Delete MCP's old writer-port endpoint fallback

## Problem

The fresh operator publishes cluster-qualified advertisement files, but the MCP
bridge still treats a missing advertisement as permission to read the deleted
pod/writer port-file convention. A stale file can therefore turn absence of
the fresh readiness fact into a connection attempt against an unrelated or
obsolete listener.

## Evidence

- `script/seon/dev/mcp.clj:275-298` reconstructs the old
  `tmp/seon-writer-repl-port-<cluster>` endpoint.
- `script/seon/dev/mcp.clj:321-343` consults it exactly when the authoritative
  fresh advertisement is absent.
- Commit `98e6ab2a2` deleted the old pod operator and config manifests; the
  surviving `bin/seon` executes only `seon.fresh-operator`.
- `script/seon/fresh_operator.clj:1820-1828` names the current operator surface
  and has no writer process or port-file publication path.

## Owner

The source-independent MCP endpoint discovery in `script/seon/dev/mcp.clj`.

## Acceptance

- Missing, stale, invalid, and unreadable fresh advertisements fail with the
  guiding fresh-operator error; none falls back to pod-era files.
- A stale old writer-port file cannot affect endpoint selection.
- Current advertisement discovery and session replacement tests remain green.

Resolved by `727e436b9`. MCP endpoint discovery now accepts only fresh
advertisements. Missing advertisements ignore pod-era writer files and
return the fresh-operator refusal.
