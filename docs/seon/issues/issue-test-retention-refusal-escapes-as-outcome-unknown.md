---
type: issue
status: fixed-unverified
severity: defect
created: 2026-09-25
tags: [database-write, error, retention]
---

# Issue-test retention refusal escapes as an unknown transaction outcome

## Problem

A worker that retracts a started issue's test, or changes its authority, must get
the writer's declared refusal back. Instead `seon.db/transact!` threw
`:seon.db/transaction-outcome-unknown` under `:seon.config/on-core-error :panic`.

## Cause

- `seon.db/retention-check` (src/seon/db.clj, before 2b1027b5d at :4342) *threw*
  an `ex-info` whose data carried `:seon.error/message` and `:seon.error/data` but no
  `:seon.error/at`, `:seon.error/layer` or `:seon.error/operation`.
- It runs inside the final-report validator (`write-report-validator` →
  `write-report-error` → `retention-report-check`). Datahike's `validate-report`
  (reference-code/datahike/src/datahike/db/transaction.cljc:1224-1234) rejects on
  any *returned* value and carries it as `:datahike/validation-refusal`. A throw
  escapes that path.
- `seon.error.refusal/refusal` classifies data only when it has at, layer and
  operation (src/seon/error/refusal.clj:142-144), so it answered the bare map.
  `transact-call`'s catch has no `:datahike/validation-refusal`, no `:seon.error/at`
  and no Datahike `:error` for it. It therefore took the `:else` branch: outcome
  unknown, then a throw under `:panic`.
- The writer's other report-validator refusals return values: the arity and
  deletion refusals return directly, and the owned-value refusal's internal
  throw is caught and returned (`write-owned-values-error`). Retention was the
  only throw left in that path. The class audit covered every `throw` in
  db.clj:3000–4460.

## Evidence

- Recorded failure blob `1aa08310…` of
  `seon.error-write-timing-test/retention-validates-expanded-writes-and-retained-target-identities`.
  The previous lane reported it in
  docs/prds/agent-platform/landing/lane-fix-test-shapes-2026-09-25.md.
- Source chain: the file:line citations above. No live JVM answered during the
  fix (`bin/seon status`: "No live exact-root JVM"), so the premise was not
  reproduced at the REPL.

## Fix

Commit 2b1027b5d. `retention-check` now returns the first broken retention as a
`diagnostic` (db.clj's writer refusal constructor), with
`:seon.db/transaction-refused`, layer `:seon.db/database-write`, operation
`seon.db/transact!` and `:seon.error/member` naming the retained attribute. The
message keeps "Started issue tests …". `retention-report-check` returns it, and
both contracts are now `[:or :nil :seon.error/base]`. The regression also asserts
that the refusal is `:seon.db/transaction-refused` and not
`:seon.db/transaction-outcome-unknown`.

## Verification

Pending root: `bin/test-check default --test seon.error-write-timing-test/retention-validates-expanded-writes-and-retained-target-identities`.
