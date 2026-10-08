---
title: "Card EN-9: Every entrant keeps its values through a recording failure"
type: reference
status: active
created: 2026-10-08
---

# EN-9 Every entrant keeps its values through a recording failure

Ledger row EN-9 (S5-07f, group E). Lane: Sonnet. Cited at HEAD `e7f120672`.

## Change
Test-only, plus the sweep. Complete `seon.mcp-entrance-test/every-entrant-keeps-values-through-recording-failure` with
its fourth row: a test request whose record writer refuses (`commit-results!` refused: a recording connection whose
writer refuses `[:db.fn/call … record-tx …]`, injected as the world's writer value) keeps every member's value: the
request's outcome holds `:seon.test/recording-refusal` (`src/seon/test.clj:2501-2503`) and each result's shown text
(EN-4). D sweep: `rg -n 'settle-batch!|settle-evaluated!|settle-batch-refusal!|with-submission|test-agent-world|attempt-request-context' src test`
answers nothing.

## Commit
`bin/lane commit <lane> -m "EN-9: the four entrants keep their values through a refused recording" --test seon.mcp-entrance-test test/seon/mcp_entrance_test.clj`

## Dependencies
EN-6, EN-8b. Reset: none.
