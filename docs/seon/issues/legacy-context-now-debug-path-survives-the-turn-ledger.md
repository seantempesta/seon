---
type: issue
status: open
severity: cleanup
tags: [issue, render, web, docs, wave/verification-audit]
---

# The old Context-now renderer survives beside the ledger and still has its own prompt joins

## Problem

The ordinary debug route now renders the ledger, but subject/legacy branches still call `debug-prompt` and `debug-ai-html`, and the old feed still fills their morph targets. Both `debug-comparison-html` and `debug-ai-html` join saved evaluation renderings independently. The old path is reachable, so it must not be called dead code or deleted blindly. `page_review_test` still requires `Context now`, while the skill claims that view is always primary. The new ledger did not replace these owners or update that skill claim.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `src/seon/render/web.clj:727–755, 792–847, 1887–1904, 3200–3237`
- `test/seon/render/page_review_test.clj:62–90`
- `.agents/skills/datastar-web-ui/SKILL.md:66–69`

## Owner and deletion

Keep entity inspection as a concern, but route its history/context display through the same acquired session component. Delete the superseded Context-now assembly, matching feed targets/CSS/tests, and stale skill assertion together.

Estimated change: 100–180 lines net deletion after route consolidation. Audit classes: 1, 2, 4, 5. No production edits for this finding were made by the audit lane.

## Acceptance

Ordinary, subject, raw-prompt, and Datastar debug routes retain entity inspection and actions but share one history assembly. Route-level tests cover the replacement; the skill describes the observed UI. Confirm selectors are no longer emitted before removing their CSS.

See [the audit](../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.
