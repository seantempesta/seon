---
type: issue
status: open
severity: cleanup
tags: [issue, architecture, render, sci]
---

# Finish the protected-walk integration with the admitted render floor

## Problem

W3 deleted the independent value sampler and made `seon.sci.admit` the one
nested-data admission walk for both floor projections. Two deliberately
bounded edges remain to integrate:

- a drilled collection selects its offset window before admitting that finite
  page; and
- `seon.render.walk/projection` chooses and associates a declaration before it
  calls the router, erasing whether the floor branch won.

## Evidence

`src/seon/render/value.cljc:133-182` admits once and both twins present that
ordinary value. `src/seon/render/block.clj:903-939` only delegates to those
twins; its former recursive panel walk is deleted. The drill's raw read is
bounded to offset + one configured collection page before admission
(`src/seon/render/value.cljc:71-139`), and realization failures become the
same admission marker vocabulary rather than escaping.

The remaining provenance defect is exact:
`src/seon/render/walk.clj:382-390` associates `chosen` under the render kind
before `seon.render/resolve-unit` sees the unit. An inherited floor therefore
looks explicit. Comparing symbols is invalid because a producer may explicitly
choose the same floor symbol. W3's evidence and live proof are recorded in
`docs/prds/sci-execution-runtime/research/w3-floor-debug-notes-2026-07-31.md`.

## Owner

W1 owns `src/seon/render/walk.clj`; integrate branch provenance there without
weakening admission safety or navigation.

## Acceptance

The walk hands an unresolved unit to the router, or carries an explicit
source-derived branch fact, so downstream W4 filtering can read
`:seon.render/would-fall-to-floor?` without comparing projection symbols. The
offset window remains bounded and every dropped or failed value remains loud.
