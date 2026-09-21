---
type: issue
status: resolved
severity: blocker
created: 2026-09-17
resolved: 2026-09-17
tags: [issue, test, database, write-admission, program-graph]
---

# The recorder creates a test row the write-grammar validator refuses

## Resolution

`record-tx` already runs as `:db.fn/call`. Its absent-row branch now supplies
`:seon.schema.admission/source` by querying namespace provenance at that
mid-transaction database, defaulting to `:agent` when none was declared,
following `seon.error/function-identity-call`. Existing test provenance is
not overwritten. A new canonical regression changes namespace provenance
before the transaction call, then retracts/recreates the test, proving both
branches against the real writer.

The three named regressions below completed without failures in the
`ec350ece0` plus selected-paths fast snapshot. The agent-callable regression
also needed its existing canonical namespace instead of an absent `user`
namespace before calling the analysis owner; fixture writes now use
`transacted!`. The 2,000-result completion and concurrent retraction tests
then completed green through the same recorder. This does not claim the
whole broader run passed: its remaining fixture failures and publication
errors are recorded in
[the landing note](../../prds/steward-platform/research/test-system-stage2-2026-09-17.md).

## Problem

`seon.test.runner/record-tx` builds each result's row by carrying the result
map through to the transaction (`src/seon/test/runner.clj:2389`), upserting on
`[:seon.test/sym …]`. It never supplies `:seon.schema.admission/source`.

The stored test entity has REQUIRED that key since 2026-08-12
(`resources/seon/schemas/seon.test.edn:83-89` — `:seon.test/sym` and
`:seon.schema.admission/source` are the only two non-optional keys). That was
harmless while admission validated submitted transaction data, because a
recorded result is an upsert onto a row the indexer already declared with its
source.

`35c5d2fa8` ("Validate every write grammar against the final transaction
entities", 2026-09-16T14:10) and `b1508dc8a` (14:42) made the validator rebuild
the MERGED entity from the resulting datoms. A row that does not already exist
is now rebuilt with `:seon.test/sym` and the result attributes and NO source,
and the whole completion is refused:

```
seon.db/transact! refused transaction data at [48299 :seon.schema.admission/source]:
expected the required key :seon.schema.admission/source with either :core or :agent,
got a map missing :seon.schema.admission/source.
Entity: #:seon.test{:sym "seon.test-runner-failure-fixture/failing-example"}.
```

`commit-results!` returns that `:seon.error/value` instead of the recorded
facts, so a caller counting results counts the error map's keys — which is why
the regression reads `(not (= 2000 9))`.

## Why this is a blocker, not a fixture defect

The recorder is SUPPOSED to be able to create a test row: recording is declared
total, and `record-tx` already runs as a transaction function precisely so the
writer re-decides presence rather than trusting a caller pre-read
(`8199364a2`, "Keep test result recording total"). The regression that proves
it — `result-recording-is-total-under-concurrent-test-retraction`
(`test/seon/test_runner_test.clj:867`) — retracts the row and asserts the next
recording recreates it. That path is now impossible: recreation has no source
to give.

This is the owner law from the other side. The validator correctly re-decides
at the authority; the recorder hands it an entity that cannot satisfy the
declared grammar. The missing fact is the recorder's: a recreated or
first-seen test row has an admission source (`:core` for a gate-run test,
`:agent` for an agent-authored one), and nothing writes it.

## Evidence — cold gate batch 115, HEAD `defd915cd`

Log `tmp/orchestrator/gate-results/batch-115.log`, retained root
`tmp/test-runs/run.Z4ufFh`. Three reds in `seon.test-runner-test`, one class:

| test | line | assertion |
|---|---|---|
| `gate-completions-travel-as-a-file-not-as-code` | 2361 | `(= 2000 (count (commit-results! …)))` → `9` |
| `result-recording-is-total-under-concurrent-test-retraction` | 900, 902, 904 | `(not (:seon.error/kind recorded))` → `:seon.db/invalid-write`; the row is not recreated; `#{"3dd0dc259d30"}` vs `#{}` |
| `the-agent-fork-callable-returns-the-committed-projection` | 1037, 1052 | `(map? row)` → `nil`; the same refusal for `user/agent-fork-example` |

Each is preceded in the log by
`:error datahike.writer … :datahike/write-rejected {:kind :transaction/validation-rejected}`.

## Not caused by the gate-preparation slice

`cde8b17fa` (2026-09-16T19:41) touched `bin/test`, `bin/_test-slot` and added
one launcher-fixture regression plus two `cp` lines inside fixture checkout
shell scripts. None of the three failing tests starts a process or loads either
script, and both validator commits are its ancestors, landing five hours
earlier. The key was already required at `cde8b17fa^`.

## Direction

The recorder supplies the source it already knows. A gate-run test is `:core`;
`seon.test/run-owned` for an agent's own test is `:agent`. The row-building
`cond->` in `record-tx` is the one seam that knows which, and it is the same
place the namespace row is minted through `namespace-tempid`
(`src/seon/test/runner.clj:2351`, `:2411`). Owner:
`src/seon/test/runner.clj`, held by the write-admission lane; not touched here.

Distinct from
[final-report validation runs unbounded on the writer thread](final-report-validation-runs-unbounded-on-the-writer-thread.md),
which is the same validator's COST. This one is its VERDICT: the validator is
right, and the entity handed to it is incomplete.
