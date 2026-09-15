---
type: issue
status: resolved
severity: friction
tags: [issue, render, agent, wave/verification-audit]
---

# Captured-prompt fallback is a second history assembler

## Problem

After the canonical walk produces a prompt, `captured-history` pulls every entry again, calls `entity-emission` and `text`, inserts its own double-newline separators, and replaces three acquired-history fields when those bytes match a capture. At the audited commit it intentionally omits the database supplied by the walk, suppressing the derived changed-read annotation. It does fail a remaining mismatch; this is not a silent acceptance of unequal capture bytes. The defect is two assemblies of the same history with different inputs and a compatibility retry. The working tree already changes this call's database argument; that unfinished edit was not audited as a completed repair.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `src/seon/render.clj:1422–1443`
- `src/seon/render/web.clj:2500–2510`
- `src/seon/render/walk.clj:875–906`
- `src/seon/repl.clj:301–349`

## Owner and deletion

Keep capture equality as verification, but make the one history owner produce faithful saved bytes and segments. Remove the repull/reformat/rejoin retry and the separate separator rule.

Estimated change: 20–35 lines deleted/merged. Audit classes: 1, 4. No production edits for this finding were made by the audit lane.

## Acceptance

Late supplement: `1f18b99fc` supplies the database to the fallback emission.
That removes the differing-input omission, but retains the second pull,
format, separator computation, and replacement of acquired history fields.
The recommended deletion still applies; do not report the missing argument
as a current defect after that commit.

At immutable named-turn bases, current history, HTML text, and capture comparisons consume the same acquired entries. Include saved generated rereads, old saved text, and newly annotated evaluations. Missing evidence remains a typed unavailable result, never a successful empty comparison.

See [the audit](../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.

## Resolution

The resolving commit deletes the repull/reformat/rejoin retry and the session's
separate saved-row pull. Both consume history's acquired rows and bytes; capture
equality only verifies. A canonical regression requires a typed mismatch when
canonical bytes change even though repulling saved entries could repair them.
Fast 10/162 and isolated 15/236 assertions pass. The landing note records the
commit and the inspected default session (55 emissions, 44,187 history bytes).
