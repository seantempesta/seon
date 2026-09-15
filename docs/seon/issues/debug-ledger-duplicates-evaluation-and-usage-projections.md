---
type: issue
status: open
severity: cleanup
tags: [issue, render, database, wave/verification-audit]
---

# Ledger duplicates the evaluation query and several usage/outcome calculations

## Problem

`ledger-evaluations` duplicates `seon.eval/of-agent`'s membership query with a hand-copied field selector, omitting author/read-evidence/t even as other displays require provenance. It also returns an empty map for an absent agent, unlike the owner diagnostic. The performance reason for a narrow pull is valid; placing a second census in the UI is not necessary. Usage decoding appears in the card, strip, headers, and `attempt-usage`; the latter derives miss=prompt-hit but the strip displays unavailable for that same observation. Result/error/out classification and history byte separator arithmetic are also recalculated by separate helpers.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `src/seon/render/transcript.clj:1464–1486, 1535–1542, 1570–1576, 1594, 1694–1702, 1992–2019, 2068`
- `src/seon/eval.clj:9–55`

## Owner and deletion

Accrete a narrow projection at the evaluation query owner, keeping one membership/order/absence contract. Derive one per-render summary for card, strip, and panel; use acquired history segments for exact byte accounting. Do not add a persisted summary cache.

Estimated change: 50–100 lines merged. Audit classes: 1, 3, 5. No production edits for this finding were made by the audit lane.

## Acceptance

Absent-agent diagnostics agree across callers. The same usage row has the same availability/totals everywhere. Interrupted/output-only/error evaluations have consistent summaries, and strip byte totals equal the acquired current history including generated annotations.

See [the audit](../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.
