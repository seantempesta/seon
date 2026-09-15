---
type: issue
status: resolved
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
[the investigation](../../../prds/context-generation/research/bisect-today-reds-2026-09-15.md).

## Owner

`seon.cluster/mcp-project`, handing ordinary values to the existing value
renderer. The initial assignment protected `src/seon/cluster.clj`; the owner
released that boundary after `5ffc491ae`, explicitly authorizing this repair.

Routing through the renderer also exposed semantic decoding that reduced
elisions to a keyword or boolean, discarding the coordinates and requery.
The decoder now preserves that data. The supplied executable requery reads
the stored artifact through the selected cluster's explicit connection;
it no longer treats a blob digest as a database entity lookup ref.

## Acceptance

The ordinary MCP branch uses the value renderer with the explicit MCP
profile, retaining retrievable complete evidence where appropriate.
Nested bulk is bounded and exposes a usable requery. Do not restore a
second `print/fit` call at the MCP terminal. Refresh the SCI test fixture
to exercise actual shown-text evaluation output.

## Verification, September 15

The four-namespace HEAD-plus-owned-paths gate passed **103 tests / 248
assertions, 0 failures / 0 errors**, exit 0, at 20:10:49Z. The snapshot
basis was `6dc70f30a`; the exact command and content digest are in the landing
note. This includes the armed missing-marker check, executable artifact
requery, nested-map elision coordinates, and root/nested SCI record rendering.
