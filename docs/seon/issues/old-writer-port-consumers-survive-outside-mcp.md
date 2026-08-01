---
type: issue
status: open
severity: friction
tags: [issue, tooling]
---

# Pod-era writer-port consumers survive outside the MCP server

The mcp-fix-wave lane (2026-08-01) deleted MCP's old writer-port
fallback (`727e436b9`) and, chasing readers repo-wide, found the same
pod-era port-file consumption alive in three places outside its
MCP-only ownership:

- `bin/seon-server-call`
- `bin/acme`
- `src-inspect-ai`

Each still reads the deleted operator's port files, so each carries the
same stale-endpoint hazard MCP just shed: a leftover file from a dead
JVM can direct traffic at nothing (or at the wrong process). The
transitive closure of the pod-era port mechanism is not yet deleted.

Acceptance: all three consumers discover endpoints from fresh cluster
advertisements only (or are themselves deleted as pod-era rot where
dead); a stale port-file fixture influences nothing; the ACME wrapper
issue (`acme-wrapper-speaks-deleted-operator-command-language.md`)
likely shares the fix wave.
