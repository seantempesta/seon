---
type: issue
status: resolved
severity: friction
tags: [issue, render, agent, wave/verification-audit]
---

# Ledger treats every no-attempt turn as generated and guesses completion from source spelling

## Problem

`turn-kind` distinguishes `:generate`, virtual `:call`, and a call without a provider attempt. `ledger-turn-body` ignores that derivation and uses only `(seq (:seon.turn/attempts row))`: every other body becomes `WE GENERATED (system turn …)`, and the provider's preceding generated group includes every no-attempt row. A virtual authored reply is consequently labelled system-generated. `turn-story` separately recognizes successful source beginning with the exact symbol `my.agent/done`; it already receives the stored `:seon.turn/disposition` in the turn selector.

## Evidence

Audit-1, 2026-09-15; committed snapshot `0c70a1cb4b14a391935d47762580abe02c235cc4`. Line numbers below refer to that snapshot, not concurrent working-tree edits.

- `src/seon/render/transcript.clj:1193–1210, 1578–1607, 2025–2039`
- `src/seon/render/transcript.clj:1164–1174`

## Owner and deletion

Use one provenance derivation for headers, bodies, grouping, and strip. Derive completion from accepted disposition/session facts and delete source-name recognition.

Estimated change: 25–60 lines merged. Audit classes: 1, 3. No production edits for this finding were made by the audit lane.

## Acceptance

Resolved in the A02 commit containing this note: ledger rows carry the one
derived turn kind through headers, bodies, grouping and strip. Virtual replies
retain their exact authored bytes and results; accepted closed dispositions
determine completion. Fast: 10 tests / 146 assertions; isolated: 10 / 150,
zero failures/errors. Default adopted the change; both routes at 1440 and 700
were captured and inspected. See the consolidate-debug landing note for hash.

Use the real canonical virtual-turn and provider paths. A virtual reply remains authored input in the body and is never included as generated context. Aliased or nested disposition-producing forms agree with directly spelled calls; a shown value alone cannot assert completion.

See [the audit](../../prds/context-generation/research/audit-1-2026-09-15.md) for scope, change counts, and verification limits.
