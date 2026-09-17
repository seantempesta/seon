---
type: research
status: open
created: 2026-09-18
tags: [seon.db, contracts, errors, pull]
---

# Database contracts and read seams

## Verification boundary

The Codex lane had no Seon MCP runtime tools. The shared-tree plain fast run
was also unavailable because the concurrent test-selection work could not load
`seon.adoption-diagnostic-test` (`No such namespace: edn` at
`seon/dev/clj_kondo.clj:33`). Iteration therefore used a detached HEAD
worktree at `tmp/db-contracts-wt`, with the repository `reference-code/`
linked into it. The orchestrator still owes the cold gate and platform proof.

## Declared unions

`seon.db/error-result` is one explicit union of the canonical facet population.
It includes the legacy `:seon.error/value`, the base entity schema, and every
canonical facet. This is exact for these pass-through seams: a database read can
successfully return a stored error entity of any facet, and transaction
functions can refuse with any classified facet.

| Function | Success arms | Error arms |
|---|---|---|
| `seon.db/q` | `:seon.schema/value` | `:seon.db/error-result` (including `:seon.db.read/error`) |
| `seon.db/pull` | `:nil`, pulled map | `:seon.db/error-result` (including `:seon.db.read/error`) |
| `seon.db/pull-many` | aligned vector of nil or pulled maps | `:seon.db/error-result` (including `:seon.db.read/error`) |
| `seon.db/transact-call` | `:seon.db/transaction-report` | `:seon.db/error-result` (including `:seon.db.write/error`) |
| `seon.db/transaction-result` | `:seon.db/transaction-result` | `:seon.db/error-result` |
| `seon.db/transact!` | transaction result or report, by arity | `:seon.db/error-result` (including `:seon.db.write/error`) |

The private read/write helpers that construct only legacy diagnostics continue
to declare `:seon.error/value`; the pass-through functions above declare the
larger union they can actually return.

## Pulled forms and refused callers

Pending Item 2.

## B4 read seams

Pending Item 3.

## Provenance-derived write bound

Pending Item 3.

## Fast evidence

- Item 1, clean HEAD worktree: `seon.db-test` + `seon.instrument-test` — 91
  tests, 633 assertions, 0 failures, 0 errors. The three wrapper findings are
  gone.
