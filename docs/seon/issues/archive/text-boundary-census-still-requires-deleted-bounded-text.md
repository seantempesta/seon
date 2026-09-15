---
type: issue
status: resolved
severity: friction
tags: [issue, render, test, wave/verification-audit]
---

# Text-boundary census and regression still require a deleted private function

## Problem

`8df86358b` deletes `seon.print/bounded-text`. The unchanged `text-boundary-report` still names it as its target and hard-codes `#{"seon.print/fit-text"}` as authorized callers. The graph regression still requires the target, exactly one caller, and a path from `fit` to that removed function. `one-private-text-bounder-serves-the-one-elision-boundary` also retains the old subject name despite now testing whole-value elision. This is verified source drift; this audit did not run the whole fn-test namespace or claim its current failure tally.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `src/seon/fn.clj:1072–1105`
- `test/seon/fn_test.clj:1327–1341`
- `test/seon/print_test.clj:548`
- `commit 8df86358b, src/seon/print.cljc`

## Owner and deletion

Delete the retired bounder-specific census and its stored/report contract fields if no live consumer needs them, or derive the current projection boundary from the existing graph declarations. Preserve the general bypass/sink coverage and absence-subject failure.

Estimated change: 30–50 lines retired/reworked. Audit classes: 2, 3, 5. No production edits for this finding were made by the audit lane.

## Acceptance

The canonical graph check proves the current whole-item projection mechanism, names live subjects, and fails if the declared boundary disappears. No removed function name or hand-authorized caller roster remains in the current check.

See [the audit](../../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.

## Resolution — 2026-09-15

Commit subject: `Retire audit misc mechanisms and derive settings and API checks` (this commit).

Deleted the removed-function census and its report schema fields. The general sink/projection census remains, with positive subjects and synthetic projected, bypass, unresolved, and codec paths. Canonical fixture rows now carry required admission provenance; the whole-value elision test uses current message identity.

See the [backstop-and-misc landing](../../../prds/context-generation/research/backstop-and-misc-landing-2026-09-15.md) for exact changes and verification boundaries.
