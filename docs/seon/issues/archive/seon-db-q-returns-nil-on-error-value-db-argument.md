---
type: issue
status: resolved
severity: blocker
tags: [issue, database, error]
---

# `seon.db/q` silently returns nil when its db argument is an error value

## Evidence

Curriculum research probe, 2026-08-03
([bootstrap-curriculum-2026-08-03.md](../../../prds/sci-execution-runtime/research/bootstrap-curriculum-2026-08-03.md)):
a `seon.db/q` call whose db argument was itself a flat `:seon.error` value
(from a failed upstream read) returned `nil` with no explanation — the
probe's entire first census came back as nils before the cause was found.

## Why this was wrong

Errors-as-values only works when a consumer handed an error value preserves
the failure. Silent nil destroyed the upstream signal and quietly poisoned
every downstream conclusion. Ruling #41 places `seon.db` as the one database
namespace with error-value semantics, so error propagation is part of every
read contract.

## Resolution

Resolved in this commit. Every public read checks an explicit database or
connection argument before dependency work and returns an upstream error
value unchanged. Query reads also check the database at the parsed Datalog
source-argument position before argument normalization.

`seon.db-test/every-public-read-preserves-an-upstream-database-error` covers
18 entry forms across connection and database identity, read-evidence,
`db`, both explicit `q` layouts, `pull`, `pull-many`, `entity`, `datoms`,
commit identity, and temporal views. It asserts `identical?`, proving that no
replacement error can erase upstream provenance. The focused namespace gate
passed 21 tests and 133 assertions in an isolated test operator root.
