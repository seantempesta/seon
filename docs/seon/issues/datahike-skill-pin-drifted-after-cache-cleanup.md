---
type: issue
status: open
severity: friction
tags: [issue, skills, datahike, dependency]
---

# Update the Datahike skill after every selected fork commit

## Problem

The curated Datahike skill states that the root gitlink currently selects
`256b714d97a0e8f952b01a47c693eff2976ccee7`, but the selected and checked-out
fork is now `0e8601d7f2f68c01070e13a95483bc82be04cabc`. The later commit removed the
unused outer Konserve cache. A skill's current-pin statement silently became
historical evidence again immediately after its previous repair.

## Evidence

- `.agents/skills/datahike/SKILL.md:35-40` names `256b714d97a0` as current.
- `git submodule status reference-code/datahike` reports `0e8601d7f2f6`.
- Seon commit `ccde63a4c` pins maintained Datahike commit `0e8601d7`; the
  before/after cache evidence is recorded in
  `docs/prds/sci-execution-runtime/research/store-amplification-anatomy-2026-08-02.md`.
- The prior occurrence was closed in
  `docs/seon/issues/archive/datahike-skill-claims-an-obsolete-fork-pin-is-current.md`;
  its recurrence shows a literal current SHA in the skill has no update owner.

## Owner

The Datahike skill and fork-maintenance reference owner.

## Acceptance

- Update the skill and its fork-maintenance reference to the selected
  `0e8601d7` pin with current source/test boundaries.
- Add the skill/reference update to the same checklist or commit that changes
  the root Datahike gitlink, so “current” cannot drift independently again.
- Independently verify every touched skill claim against the selected fork and
  Seon acceptance tests.
