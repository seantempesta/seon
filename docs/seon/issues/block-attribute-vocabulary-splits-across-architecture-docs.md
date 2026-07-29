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

## Ruling (owner, 2026-07-28 evening)

The canonical set is decided: **block attributes take the owning code
namespace** — `:seon.render.block/name`, `:seon.render.block/priority`,
`:seon.render.block/band`, entity slot `:seon.render.block/block` —
per the existing colocation rule (attributes live with the code namespace
that owns them, here `seon.render.block`). The agent link keeps its name
(`:seon.cluster.agent/blocks` — its owner is the agent entity), and the
`:seon.context/*` capture/contribution attributes are unchanged
(`seon.context` is their code owner). `:seon.agent.ctx/*` and bare
`:seon.block/*` are both losing vocabularies.

Source and tests were renamed in the context-blocks wave (2026-07-28):
`resources/seon/schema/block.edn`, `resources/seon/schema/context.edn`,
`src/seon/render/block.clj`, `src/seon/render/web.clj`,
`src/seon/render/root.clj`, and the block/web/problems/stream/boot tests.
Residual noted for the boot owner: `:seon.block/count`
(`resources/seon/schema/boot.edn`, `src/seon/cluster.clj`) is boot's readiness
count, not a block entity attribute — colocation names it under boot's own
namespace when that owner next touches it.

## Remaining

The architecture-doc rewrite (`architecture.md` + `ui.md` adopting
`:seon.render.block/*`, deleting `:seon.agent.ctx/*` and the imperative
`install!`/`remove!` drift) stays with the architecture-doc owner; the
source rename above is the vocabulary it must match.

## Owner

Architecture docs (rewrite pending). The fresh block/render owner's half is
done — source registers `:seon.render.block/*` only.
