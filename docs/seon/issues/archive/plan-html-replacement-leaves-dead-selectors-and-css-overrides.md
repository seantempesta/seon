---
type: issue
status: resolved
severity: cleanup
tags: [issue, render, web, wave/verification-audit]
---

# HTML replacement adds a CSS override layer and leaves old plan selectors

## Problem

`blocks.css` explicitly declares that it is more specific than historical shell rules, then overrides plan padding, borders, backgrounds, opacity, fonts, and state text already owned by `input.css`. Across the day it adds 108 lines and removes none. The replaced markup no longer emits `my-plan-id` or `my-plan-reference`, but their 9-line and 4-line rules survive at input.css:1144 and :1192; the `:not(.my-plan-id)` exclusion also survives. Source search found no current emitter for those two classes. Not every older rule is dead: structural/layout rules still participate in the cascade.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `resources/public/css/input.css:1125–1195`
- `resources/public/css/blocks.css:7–35`
- `commit 5d59aa991, src/seon/plan.clj`

## Owner and deletion

Move each live plan declaration to its single component stylesheet owner and delete redundant overrides and the two dead selectors. Rebuild output.css through bin/css rather than hand-editing it.

Estimated change: 13 dead-rule lines plus roughly 40–80 overlapping lines to consolidate. Audit classes: 2, 5. No production edits for this finding were made by the audit lane.

## Acceptance

Existing HTML-view tests and browser checks at desktop/narrow widths preserve the plan layout. Check compiled selectors and actual markup; do not delete base layout rules merely because a newer rule overrides one property.

See [the audit](../../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.

## Resolution — 2026-09-15

Commit subject: `Retire audit misc mechanisms and derive settings and API checks` (this commit).

Deleted the obsolete input.css plan section and consolidated every emitted plan selector in blocks.css. Rebuilt with bin/css. Chromium compared 64 canonical plan elements at both 1440 and 700 pixels: zero computed-style differences and no horizontal overflow. Generated output.css is excluded from the commit.

See the [backstop-and-misc landing](../../../prds/context-generation/research/backstop-and-misc-landing-2026-09-15.md) for exact changes and verification boundaries.
