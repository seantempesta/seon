---
type: issue
status: open
severity: friction
tags: [issue, tooling, mcp, render]
---

# Give MCP results the value system instead of a second truncation

## Problem

Owner direction, 2026-08-02: "I don't want to build parallel tools. I
want one really great set of tools we keep improving." MCP currently
solves the oversized-result problem with its own crude mechanism while
Seon already owns a principled one for the identical problem.

What MCP does today: a raw string chop —
`(str (subs value 0 limit) "\n… output truncated by MCP bridge")`
(`script/seon/dev/mcp.clj:86`), plus `:seon.dev.mcp/truncated?` flags
and per-field truncation (`:56,107,161-167,618`). The bytes past the
limit are simply gone: unrecoverable, unaddressable, and carrying no
identity.

What Seon already owns for the same problem, as one chain:

- `seon.sci.admit` — bounded projection with explicit `::print/elided`
  markers, depth/length/width caps, and `::capped?` evidence
  (`src/seon/sci/admit.clj:98-143,361-432`). Elision is a recorded
  fact, not a lost suffix.
- `seon.print` — the one closed print grammar; text and hiccup derive
  from one stored fact (ruling #26).
- `seon.render.value/node-id` — "Stable element id for one root
  selector and `get-in` path" (`src/seon/render/value.clj:19-33`),
  with `path-url` building `?path=…&offset=…` drill links (`:36-45`).
  Paged navigation by `get-in` path is the "drill" already in the
  vocabulary table.
  CORRECTION (2026-08-02, orchestrator's own overclaim, caught by the
  MCP PRD lane and re-verified): `node-id` hashes the ADDRESS
  `[agent-id root-address path]`, NOT the value, so it is a stable
  element identity for morph targeting and is NOT a retrievable
  content address. Retrieval comes from the blob tier below, whose
  digest IS the content. A design that returns only a node id hands
  back something nothing can look up; the two are complementary and
  an oversized result needs both.
- `seon.blob` — content-addressed `put!`/`get` with digest and size
  (`src/seon/blob.clj:19,32`), already the overflow tier for oversized
  eval results and reasoning under ruling #25's threshold split.
- `/data` is the existing paged navigation route
  (`src/seon/render/route.clj:22`).

So an oversized value already has: a bounded projection, honest elision
markers, a stable identity, a drill path, and a blob tier. MCP uses
none of it and loses the data instead.

## Acceptance

- MCP evaluation results project through the same admit/print chain the
  agent-facing path uses, with the same elision markers rather than a
  string chop.
- An oversized result yields a stable value reference (the existing
  `node-id` shape) plus enough of the value to be useful, and the
  remainder is RETRIEVABLE — by a follow-up drill call on a `get-in`
  path, and/or from the blob tier — never discarded.
- One threshold authority: the existing
  `:seon.config.eval.result/blob-threshold` family, not a second
  MCP-only token budget with its own constants.
- The status/inventory surface proposed in
  `docs/prds/mcp-surface/README.md` uses the same bounding, so there is
  one answer to "this response is too big" across every tool.
- No mechanism is duplicated: if the chain needs a non-cluster caller
  (MCP has no agent id and may target a degraded JVM), that is fixed in
  the owning namespace, never forked into the bridge.

Blocks the output-bounds row of `docs/prds/mcp-surface/README.md`,
which currently proposes keeping the MCP-only budget.
