---
type: issue
status: open
tags: [issue, agent]
---
# Wire call graph context into Gemini review

## Problem

Gemini reviews currently see only the edited file's source. Adding callee sources would make reviews significantly more accurate — the reviewer could see what functions the edited code calls.

## Design (from archive)

`docs/archive/unified-dev-hook/phase-11-unified-analysis-pipeline.md` Phase 11e describes this:

- `analysis/callees-of` already exists in `src/seon/dev/analysis.clj`
- Wire it into `review/build-context` to include callee source in the review prompt
- Gemini sees not just what changed, but what the changed code depends on

## File Refs

- `src/seon/dev/analysis.clj` — `callees-of` function
- `src/seon/dev/review.clj` — review context builder

## Acceptance Criteria

- Gemini review prompt includes source of functions called by edited code
- Review quality measurably improves (fewer false positives about unknown functions)

## Severity

friction

## Milestone

[[vision/m5-observable-system]]
