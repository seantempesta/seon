---
type: issue
status: open
severity: cleanup
tags: [issue, architecture, schema]
---

# Block attribute vocabulary splits across architecture docs

## Problem

The architecture layer teaches two incompatible vocabularies for the same
block mechanism. `architecture.md` (thesis + glossary) uses
`:seon.agent.ctx/block`, `:seon.agent.ctx/name`, `:seon.agent.ctx/priority`,
the agent ref `:seon.agent/ctx`, and the mutators
`seon.agent.ctx/install!`/`remove!`. `ui.md` uses `:seon.block/block`,
`:seon.block/name`, `:seon.block/priority`, the agent ref
`:seon.cluster.agent/blocks`, and the pure `seon.render.block/install-tx`.
A reader cannot tell which set is the target; an implementer could register
either.

## Evidence

- `docs/seon/architecture/architecture.md` — thesis paragraph and glossary
  entries for **block**, **seed-copy**, and **`install!` / `remove!`**.
- `docs/seon/architecture/ui.md` §"The block and its two renders",
  §"Seed-copy", §"Installing and removing — the one override". That last
  section also drifts internally: it defines the pure
  `seon.render.block/install-tx` and then refers to imperative
  `install!`/`remove!` mutators two paragraphs later.
- Flagged by the edit-hook review 2026-07-28
  (`tmp/reviews/20260728T173927.309Z.md`); pre-existing drift, not introduced
  by the 2026-07-28 flow/transport revision.

## Acceptance

One canonical attribute namespace, agent ref, and mutator surface for blocks,
stated identically in `architecture.md` and `ui.md`, matching whatever the
fresh `src/` owner registers (or the ruled target if not yet built). The
losing vocabulary is deleted from the docs in the same change.

## Owner

Architecture docs + the fresh block/render owner. Needs the owner (or the
top-level orchestrator) to pick the canonical set; not decidable inside a
documentation lane.
