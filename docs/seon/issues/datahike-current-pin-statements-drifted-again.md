---
type: issue
status: open
severity: friction
tags: [issue, datahike, skills, architecture, dependency]
---

# Derive or update current Datahike pin statements with the gitlink

## Problem

The selected Datahike gitlink and checkout are
`10540578248eaa686c1f88a7fe57644ee4c9f993`, while the curated Datahike skill and
current library-grounding map still call `56f1c621...` current. This is a
recurrence of an archived literal-pin drift issue.

## Evidence

- `git ls-files -s reference-code/datahike` and
  `git -C reference-code/datahike rev-parse HEAD` both report
  `10540578248eaa686c1f88a7fe57644ee4c9f993` on 2026-08-11.
- `.agents/skills/datahike/SKILL.md:35-40` names
  `56f1c62105b7087f0cac13162f9fd54b1690986e` as current.
- `.agents/skills/datahike/references/fork-maintenance.md:31-38` repeats the old
  current pin.
- `docs/seon/architecture/library-grounding.md:14-24` repeats the old pin in an
  always-current architecture map.
- `docs/seon/issues/archive/datahike-skill-pin-drifted-after-cache-cleanup.md`
  records and resolves the previous occurrence, including the requirement to
  update current-pin authorities with every gitlink change.

## Owner

The Datahike fork-update checklist, curated skill, and architecture grounding
map.

## Acceptance

- Update the current pin and source boundaries in the skill, its maintenance
  reference, and the architecture grounding map in the same path-limited
  change.
- Make a gitlink advance mechanically identify these current-pin authorities
  in its gate so the same class cannot recur silently.
- Independently verify every touched skill claim against the selected checkout.
