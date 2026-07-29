---
type: issue
status: open
severity: cleanup
tags: [issue, docs, tooling, agent]
---

# Retire the pod-era loadable-skills component description

## Problem

`docs/seon/components/loadable-skills.md` now names the one linked skill tree
correctly, but most of the component still describes deleted pod-era
implementation: pod boot, `config/system.edn`, `SEON_SKILLS_DIR`, and the
effectful `my.skills` load/unload path.

The architecture target intentionally retains a future database-backed
`my.skills` contract, so mechanically rewriting the component from current
source would mix target design with deleted implementation history.

## Evidence

- The component's current-model sections describe pod boot and CLJS source
  owners that were deleted in the Group 4 cut.
- `docs/seon/issues/archive/pod-cut-group-4-leaves-surviving-toolkit-requires.md`
  records deletion of the pod filesystem scan/render and effectful
  `my.skills` load/unload mechanism.
- Current `src/`, `test/`, and `resources/` contain no fresh `my.skills`
  implementation, while `docs/seon/architecture/context.md` and
  `toolkit.md` describe the intended future contract.

## Owner

The documentation cleanup boundary: delete the stale component/dashboard
entry or replace it with a pointer to the architecture target, without
claiming the target is built.

## Acceptance

No always-current component page describes the deleted pod importer as current.
The architecture target remains the single intended-system description, and
implementation state stays in the active PRD.
