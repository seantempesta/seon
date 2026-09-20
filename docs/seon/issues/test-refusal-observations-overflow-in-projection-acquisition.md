---
type: issue
status: open
severity: blocker
created: 2026-09-19
tags: [testing, instrumentation, error, schema]
---

# Test refusal observations overflow in projection acquisition

A1's fast snapshot at `487d7e4eb` plus owned selector/error-facet changes
completed 41 tests / 294 assertions / 1 failure / 16 errors
(`tmp/a1-fast-6.log`). The canonical admission regressions reach the expected
writer refusal carrying `:seon.error/at`, `/layer`, `/operation`, and
`:seon.test/admission-refusal`, then fail with `StackOverflowError`.

The repeating stack includes `seon.instrument/arm-var!` at lines 883–890,
`supplied-projection` at lines 606–612, and Malli's map predicate validation.
The worker-claim refusal regressions show the same stack. This records the
observed boundary; the recursion's root cause has not been isolated.

Separately, the snapshot's database diagnostic producer at
`src/seon/db.clj:174` passes no `:seon.error/at` to `seon.error/diagnostic`.
The armed constructor refuses it during `recording-distinguishes-run-replay-from-a-new-event`.
The database owner was being edited concurrently, and A1 excluded that edit
from its snapshot rather than changing it.

Contract propagation must also account for test selection/admission/execution
facets at the database transaction boundary. Its explicit
`:seon.db/error-result` union is outside A1's owned schema paths. Verify the
converged producer and return contracts with the three A1 namespaces; a
passing result must observe the original typed refusal, not a wrapper failure.

The ninth snapshot includes `dc5dbbfb4`'s database changes. Admission refusal
handling still reaches the same stack overflow. Preserving the provenance
exception's data additionally identifies the selector boundary precisely:
`seon.fn.schema-shape/normalized-form` refuses the compiled form for the held
`seon.error/config-expectation-present?` predicate. Its `:gen/gen` property is
an instantiated `clojure.test.check.generators.Generator`, not canonical EDN
(`tmp/a1-fast-9.log`, first selector failure). This prevents the complete
population selector from obtaining its program digest; the diagnostic path
hides the original contract problem. No held error, instrumentation, or
schema-shape owner was changed by A1.

The ninth run finishes with 41 tests / 306 assertions / 7 failures / 15 errors.
Its invalid-read probe now returns a base observation without the expected
read facet; propagation through `seon.blob/with-publication!` reports an
undeclared error facet. The immutable-run refusal is produced with its test
facet, but the enclosing write/publication boundary does not preserve the
test's expected result. These are separate observed propagation boundaries,
not evidence that restoring the retired kind key would be correct.
The check regression also stops at `seon.sci.eval/acquisition-refusal`
(`src/seon/sci/eval.clj:1580`), whose diagnostic invocation lacks the required
base observation. Its second-check zero-execution assertion is not reached.

## 2026-09-20 — results-reuse baseline and candidate

The results-reuse lane's unchanged baseline at `c76a161a3` reproduces the
noncanonical `seon.error/config-expectation-present?` compiled schema:
8 tests, 56 assertions, 2 failures, 1 error. Its four-namespace candidate
at `721b110b8` plus owned overlays records 45 tests, 354 assertions,
8 failures, 16 errors, including that provenance boundary, refused
propagation of admission/execution facets through `seon.error.refusal/refusal`,
and the same missing base observation in SCI acquisition. One additional
error was the new test's own missing database request key, subsequently
corrected; the whole tally is not attributed to this issue.
Raw logs and the exact snapshot commands are in
[the lane note](../../prds/steward-platform/research/results-reuse-everywhere-2026-09-20.md).

The host/reader follow-up at HEAD `c79167f9e89782143772c9579ae55a20b2bf20c6`
plus its owned overlay completed **10 tests / 64 assertions / 4 failures / 2
errors** (`tmp/results-reuse-everywhere/host-callers-fast.log`, 62,619 bytes,
SHA-256 `26849939cde139e8af0cea990ec110bbb13506abf1ee2eb05ca0ec5bafecad74`).
The SCI owned-test reuse and expiry regressions passed. The broader admission
suite still exposed these boundaries:

- `test/seon/test_test.clj:31`: evaluation supplied no program row before
  `seon.program/declaration-row`; its nil input refusal hides the earlier
  evaluation result, so this log does not establish that earlier cause.
- `:277`, `:284–288`: `seon.blob/with-publication!` replaces expected read and
  immutable-run refusals with undeclared error facets
  `:seon.db.write/validation-refusal` and `:seon.test.run/immutable-error`.
- `recording-preserves-admission-and-refuses-a-deleted-definition`:
  `seon.db/transact-call` rejects its refusal as a return missing `:db-before`.

The outer run `e1d46131b83d` then failed durable recording at the published
authority: `with-publication!` reported undeclared
`#{:seon.db.write/validation-refusal :seon.test/execution-error}`. Its printed
execution tally is not a recorded tally. The lane did not edit the blob,
database, error, schema, or publication owners to bypass these refusals.
