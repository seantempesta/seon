---
type: issue
status: resolved
severity: friction
tags: [issue, datahike, skills, architecture, dependency]
---

# Derive or update current Datahike pin statements with the gitlink

## Problem

At filing time, the selected `reference-code/datahike` gitlink and checkout
were `10540578248e`, while the curated Datahike skill and current
library-grounding map still called `56f1c621...` current. This was a recurrence
of an archived literal-pin drift issue.

## Evidence

- `git ls-files -s reference-code/datahike` and
  `git -C reference-code/datahike rev-parse HEAD` both reported
  `10540578248e` on 2026-08-11.
- `.agents/skills/datahike/SKILL.md:35-40` named `56f1c62105b7` as current.
- `.agents/skills/datahike/references/fork-maintenance.md:31-38` repeated the
  old current pin.
- `docs/seon/architecture/library-grounding.md:14-24` repeated the old pin in
  an always-current architecture map.
- `docs/seon/issues/archive/datahike-skill-pin-drifted-after-cache-cleanup.md`
  records and resolves the previous occurrence, including the requirement to
  update current-pin authorities with every gitlink change.

## Owner

The Markdown structural validator, with current-pin statements in curated
skills and architecture maps.

## Acceptance

- Update the current pin and source boundaries in the skill, its maintenance
  reference, and the architecture grounding map in the same path-limited
  change.
- Make a gitlink advance mechanically identify these current-pin authorities
  in its gate so the same class cannot recur silently.
- Independently verify every touched skill claim against the selected checkout.

## Resolution

`seon.dev.markdown/validate-repository-pins` now derives every stage-zero,
mode-`160000` dependency path and SHA from the repository index with
`git ls-files --stage -z -- reference-code`. It separately derives the tracked
Markdown subjects under `docs/` and `.agents/skills/`; neither dependencies
nor files come from a maintained roster. Git failure, an unmerged index entry,
or an absent subject population is a structural error rather than a clean
result.

The citation boundary is deliberately mechanical. A current-pin claim is a
maximal 40-hex token within five lines of a `reference-code/<dep>` path, or on
a Markdown table row whose cell exactly names the gitlink basename. The
nearest path wins; table rows never borrow dependency names from neighboring
rows. Dated evidence uses an abbreviated commit identity instead of claiming
to be the selected pin. The validator tokenizes hexadecimal runs against a
literal ASCII hex alphabet and parses Git/table records with literal
delimiters; it introduces no new regular expression.

The first corrected whole-tree run found 607 stale full-pin claims across 303
files after the deterministic table boundary was applied. Dated evidence was
mechanically abbreviated, while current authorities were updated to the live
gitlinks. During the same work period Datahike advanced again, proving the
class in real time:

- `reference-code/datahike` selects `15d98da6` (historical);
- `reference-code/sci` selects `fcbd8862800e638dc0f8f5521111f999279cbcd2`;
  and
- `reference-code/malli` selects `3517a3cd9271b2083780ac7be1725493905bca2e`.

One synthetic regression proves a stale pin reports the file, dependency,
cited SHA, and current gitlink. A second recurring regression runs the
repository-wide gate. The class is closed by that gate rather than another
update checklist.
