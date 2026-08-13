---
type: issue
status: open
severity: friction
tags: [issue, operator, class/n5, wave/operator-status-face]
---

# Status prints unexplained invalid-claim lines

## Problem

`bin/seon status` prints one line per stale operator claim record —
currently EIGHT repetitions of
`record unreadable …/claims/roots/<uuid>.edn: The external claim is
invalid.` — naming neither WHAT was invalid (schema mismatch? dead root?
orphaned by a deleted isolated root?) nor what to do about it. The claims
belong to isolated test roots whose directories no longer exist, so the
honest rendering is one line: N orphaned claims for absent roots, with the
reclamation verb to run. A diagnostic that repeats an unexplained sentence
eight times is the N5 evidence-collapse class at the operator face.

## Evidence

2026-08-13: both the orchestrator's status checks and the comprehension
test deployment saw the eight identical lines
(`data/operator/claims/roots/*.edn` — the roots were tmp test-run roots
since deleted).

## Owner

`seon.operator/existence` claim reading and the status face: classify the
invalid-claim cause (absent root vs malformed record), aggregate identical
causes, and name the cleanup action. Orphaned claims for absent roots
should also be reclaimable by the existing cleanup owner rather than
printed forever.

## Acceptance

- Status renders orphaned/invalid claims as one aggregated line naming the
  cause and the action; identical causes never repeat per-file lines.
- Claims for absent roots are reclaimed by the cleanup owner; a fresh
  status after reclamation prints none.
