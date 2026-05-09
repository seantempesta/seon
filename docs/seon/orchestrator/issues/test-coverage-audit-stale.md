---
type: issue
status: open
tags: [issue, component]
---
# Test Coverage Audit is Stale

## Problem

`docs/prds/test-coverage-audit/findings.md` references ml-options era code that no longer exists (`dsl/primitives.clj`, `dsl/executor.clj`, `db/transactions.clj`, `agent/analysis.clj`). The P0/P1/P2 findings are all for deleted files. The codebase was restructured into Seon with 70 test files across different namespaces.

The PRD status says "complete" (the audit was done) but its findings are obsolete.

## Action Needed

1. Mark the PRD as `superseded` in `orchestrator/prds.md`
2. Run a fresh test coverage audit against current Seon namespaces
3. Identify which `src/seon/**/*.clj` files lack corresponding test files
4. Identify critical untested functions (especially `health/` and other domain code)

## File Refs

- `docs/prds/test-coverage-audit/findings.md` — stale findings
- `docs/seon/orchestrator/prds.md` — needs status update
- `docs/seon/components/testing.md` — coverage map needs refresh

## Severity

friction

## Milestone

[[vision/m3-convention-uniformity]]
