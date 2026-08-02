---
type: issue
status: resolved
severity: blocker
tags: [issue, skills, datahike, dependency]
---

# Re-ground the Datahike fork skill at the selected revision

## Problem

The Datahike skill says its fork-maintenance mechanics are grounded in the
“current fork” at `19f5cdd9` and Seon pin `4dc963e2e`. The selected submodule is
now `256b714d`, hundreds of commits later, after major query, schema, GC,
branching, async, migration, and ordered-write changes.

The planner/cache symbols spot-checked in this audit still exist at their
cited lines, so the problem is not that every recipe is already false. The
danger is stronger: the skill marks an old provenance checkpoint as current,
inviting a fork maintainer to trust that no intervening semantics need review.

## Evidence

- `.agents/skills/datahike/SKILL.md:21-40` identifies `19f5cdd9` and
  `4dc963e2e` as the current grounding. The same old grounding appears in
  `references/fork-maintenance.md:1-15`.
- `git submodule status reference-code/datahike` and
  `git -C reference-code/datahike rev-parse HEAD` both resolve to
  `256b714d97a0e8f952b01a47c693eff2976ccee7`.
- `git -C reference-code/datahike log 19f5cdd9..HEAD` includes current Seon
  dependencies such as ordered Konserve filestore batches (`256b714d`), schema
  removal refusal (`b73550bf`), branch-roster serialization (`357ffc87`), and
  extensive query planner/executor repairs.
- The old planner entry points and cache evidence functions remain present in
  current `reference-code/datahike/src/datahike/query.cljc`; this calibrates
  the finding as stale authority/provenance, not a claim that the entire skill
  is unusable.

The reader chain is direct. Root `AGENTS.md` and `docs/TRANSFER_PROMPT.md:119`
route every Datahike fork repair through this skill and its maintenance
reference. The same bytes serve Codex, Claude, and runtime/import readers.

The open `datahike-fork-is-28-commits-behind-upstream` issue tracks upstream
delta, not the maintained skill falsely naming an old selected pin.

## Owner

The `datahike` skill and both references, verified against selected Datahike
`256b714d` and the current Seon acceptance call sites/tests.

## Acceptance

- The dependency ledger names `256b714d` (and the root submodule pointer that
  selects it) as current.
- Every planner, cache, writer, branch, schema, and test command the skill
  teaches is rechecked against that revision; stale recipes are corrected or
  removed rather than grandfathered from `19f5cdd9`.
- Historical repairs remain labeled as precedents, never current provenance.
- An independent fork-maintenance probe follows the skill successfully from a
  clean checkout.

## Resolution

Resolved by `431f424bb`. The skill and both references now identify the root
gitlink-selected Datahike revision
`256b714d97a0e8f952b01a47c693eff2976ccee7`; earlier planner and branch repairs
are historical precedents only. The dependency ledger was rechecked for the
planner, result/plan caches, ordered writer, store create/reopen behavior,
branch identity/roster, schema removal, and both projects' test launchers.

Proof at the selected checkout: the Seon fork gate passed 1 test/1 assertion,
the Datahike planner focus passed 96 tests/396 assertions, and the Seon store
gate passed 13 tests/45 assertions. Direct probes exercised the private
planner, uncacheable result evidence, writer argument shapes, branch roster,
and current-data schema-removal refusal. The skill package passes validation.
The law's clean-checkout independent verification remains deliberately
unclaimed and is requested after this repair wave.
