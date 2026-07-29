---
type: issue
status: resolved
severity: friction
tags: [issue, tooling, operator, repl]
---

# Delete the duplicate REPL launcher

## Problem

`bin/repl` directly launched `seon.cluster/start!` on the source classpath while
the fresh operator already owned cluster start, advertisement, status, and
reconciliation through `bin/seon start`. The development MCP taught the
duplicate command as the remedy for a missing cluster.

## Evidence

The repository runbook names `bin/seon start <name>` as the way to obtain a
live cluster. The deleted script bypassed that public operator path, creating a
second startup truth and omitting the operator's lifecycle behavior.

## Owner

`bin/seon` is the development operator; `script/seon/dev/mcp.clj` reports its
repair commands.

## Acceptance

There is no standalone `bin/repl`, and a missing-cluster MCP error teaches
`bin/seon start <name>`.

## Resolution

Commit `9e79b77e9` deleted `bin/repl` and repointed the MCP repair message to
the fresh operator.

## Proof

A direct missing-endpoint probe returned
`Start the cluster with: bin/seon start scratch.` Active non-protected tooling
and guidance have no remaining `bin/repl` caller.
