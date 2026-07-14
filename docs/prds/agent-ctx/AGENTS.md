---
type: orchestrator
status: completed
tags: [orchestrator, prd, agent, web]
---

# Agent context — completed chunk index

## Current state

Completed 2026-07-12. This chunk proved and shipped database-derived context
blocks, shared agent/debug projections, the minimal default context, one
`my.canvas` interaction path, targeted reactive invalidation, and cold-resume
hardening. It is historical depth, not the current work ledger. Current runtime
work lives in `docs/prds/runtime-reliability/roadmap.md`.

## Settled results

- Context blocks are database facts rendered by functions over a database
  value; AI and HTML are formats of the same block, not separate systems.
- New agents receive the manifest-selected minimal block set. Skills remain
  importable but are disabled by default so dynamic context proves relevance.
- `my.canvas` is the sole agent-facing focal UI API. Agents write facts and
  render functions; the Datastar feed reacts to committed database changes.
- Agent pages, the separate debug view, and the right rail share one context
  projection. Missing formats are omitted.
- `ctx-sections`, world, inspector, tile, and live-tile are retired active
  concepts. Do not restore compatibility paths.

## Entry points

- `roadmap.md` — dated closeout and shipped evidence.
- `context-rebuild.md` — the minimal-context design that this chunk proved.
- `docs/seon/architecture/context.md` and `docs/seon/architecture/ui.md` —
  current target architecture; these win over historical details here.
- `docs/prds/runtime-reliability/roadmap.md` — current implementation status.
