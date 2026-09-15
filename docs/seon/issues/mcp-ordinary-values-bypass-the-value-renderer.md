---
type: issue
status: open
severity: friction
tags: [issue, mcp, render, wave/dev-mcp]
---

# Ordinary MCP values bypass the value renderer

## Problem

`seon.cluster/mcp-project` derives an MCP render profile, then decodes the
complete admitted print node unchanged. Ordinary nested results therefore
escape AI presentation bounds.

## Evidence

The armed pre-WIP snapshot `38c49a1db` emits 551,392 bytes against an
8,192-byte expectation in
`seon.cluster.mcp-test/nested-bulk-is-bounded-by-the-shared-value-window`.
Commit `9248692d6` (September 8) removed the terminal-local fit, leaving
`projected-node print-node` in `src/seon/cluster.clj:384`.
The deletion obeys the one-clipping-authority rule, but the caller still
needs to use that authority. See
[the investigation](../../prds/context-generation/research/bisect-today-reds-2026-09-15.md).

## Owner

`seon.cluster/mcp-project`, handing ordinary values to the existing value
renderer. This assignment explicitly protects `src/seon/cluster.clj`;
no production edit was made at that boundary.

## Acceptance

The ordinary MCP branch uses the value renderer with the explicit MCP
profile, retaining retrievable complete evidence where appropriate.
Nested bulk is bounded and exposes a usable requery. Do not restore a
second `print/fit` call at the MCP terminal. Refresh the SCI test fixture
to exercise actual shown-text evaluation output.
