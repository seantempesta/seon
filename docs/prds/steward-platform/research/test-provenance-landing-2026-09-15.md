---
type: research
status: active
date: 2026-09-15
tags: [test, provenance, steward-platform]
---

# Test provenance — slice 1

Item 1 is implemented. Destination preservation and the success predicate
are the following two commits; their final verification will be recorded here.

## Authority and dependency ledger

Read AGENTS.md, the stewards draft (including §10–11), the steward-platform
README and working edge, and data-audit-a's subject-bound success and test
destination evidence. The owner's explicit lane assignment authorizes this
slice despite the program README labelling the wider ideas unapproved.

- Datahike's writer supplies its current database to `:db.fn/call`:
  `reference-code/datahike/src/datahike/db/transaction.cljc:1152`.
  `seon.test.runner/record-tx` remains the sole result transaction builder.
- `force-branch!` checks the expected head and warns that live connections
  become stale: `reference-code/datahike/src/datahike/versioning.cljc:323`.
  `seon.cluster.source/record-results!` uses a private branch, commits through
  `commit-results!`, advances current-src with the existing guard, and releases
  its connection. The generated JVM request invokes this same path.
- `seon.program/canonical-row` owns program attributes; result attributes are
  outside that ownership. The fingerprint extends the source snapshot identity
  only for changed canonical program facts, comparing the current database with
  its sealed basis. Datahike history/since finds the affected rows.

## Storage and preservation rule

Every recorded result links to one immutable run row. Its digest, basis, and
branch describe the tested program, never the recording destination. The
coordinator captures the prepared base's provenance before running tests and
retains its Git SHA. Agent-local runs capture their database program before
execution; Git SHA is absent when no Git identity was supplied.

Every canonical gate selection records, including explicit namespaces and
platform-only runs. The fast iteration path does not record: it has no isolated
published base and is explicitly not durable gate evidence.

Full publication carries run rows and latest results from the expected
current-src commit, not its older :db population base. All original fingerprints
remain unchanged, including for changed or removed definitions. Consequently,
unchanged definitions retain their last evidence; changed programs cannot use
that evidence to certify their new digest. Incremental publication already
forks the expected current-src commit and preserves non-program attributes.

## Initial observations

Default answered MCP JVM and runtime-status probes. At basis 536877865 it had
one recorded test result and no installed :seon.test/run attribute. No lifecycle
operation was performed on default.

The first fast run exposed a pulled-ref output-contract mismatch and excessive
work in the first fingerprint implementation. The ref contract now accepts
Datahike's pulled reference map. Fingerprinting compares only rows touched since
the source seal. A nested snapshot regression copied a hand-selected subset of
runner files; its fixture now copies the tested source/resource roots together.

## Verification

- Item 1 fast snapshot: 2 tests, 14 assertions, zero failures/errors.
- Item 1 isolated selected-path gate: 2 tests, 16 assertions, zero
  failures/errors, exit 0. Snapshot HEAD `a3264ffc473becd00961e847cf5d4cb6e3da880b`;
  preparation 47 seconds, coordinator/tests 43 seconds. Successful root removed.
- Direct MCP fingerprint capture on default at basis 536878827 returned run
  `1d9c5f4f090e`, branch `:cluster-default`, digest
  `005f1b4d968bb7f8521f2af6d2a0046bfa3db3af2ae0de23699cb232a65b7f20`.
  Capture took 4723.310375 ms under concurrent gates; this read did not record a
  test result. The earlier unscoped MCP call exceeded its 20-second bound.
- During commit separation I temporarily removed `verified?` while the source
  test still referenced it. That blocked static analysis and adoption. The
  source test now checks stored provenance directly and has no dependency on
  the later predicate. Manual `bin/seon init --dev default` exited 0 and printed
  `development cluster converged`, publishing commit
  `6aa97ee2-809b-563c-906e-a7e446c3b3b9`, digest
  `8d09e147ea1095f90ccb0b5a8e43b0f8125077a24b22032e24a9ee29a29c1755`.
  No reset or refork was needed. Intermediate source must remain analyzable.
- The older full fast snapshot finished with 64 tests / 639 assertions,
  6 failures, 0 errors. All six were the nested concurrent-runner fixture
  passing directories to `--paths`, which accepts files only. The fixture now
  tests its inherited snapshot directly with an explicit disposable evidence
  cluster. The provenance, full-rebuild, and loop checks in that run passed.

Final combined and platform gates remain pending.
