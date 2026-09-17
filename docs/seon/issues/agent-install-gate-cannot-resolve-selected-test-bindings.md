---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, sci, install-gate, test-bindings]
---

# An agent install gate cannot resolve its selected test bindings

On regenerated default PID 33583, ordinary source turn `d1d37229ada1`
redefined `seon.id/valid?` with its existing complete contract. Auto-check
passed 25/25. The gate selected four tests and refused the installation:

- `seon.id-test/an-evaluation-id-is-stable-short-and-a-symbol`: unable to
  resolve `id/evaluation`.
- `seon.id-test/data-shape-and-explicit-length-determine-identity`: unable
  to resolve `id/id`.
- `seon.error-test/error-identity-and-occurrences-are-owned-by-the-writer`:
  unable to resolve `test-support/with-database`.
- `seon.schedule-test/returned-and-thrown-handler-errors-use-the-existing-root-wake`:
  unable to resolve `testing`.

The original core row remained unchanged. These are measured missing
bindings, not an attribution to another lane or a claim that the four tests
would otherwise pass. The S3 probe continued with a different first-party
identity whose graph-derived gate set was empty; its normal auto-check and
acceptance path remained active.

Exact source and context are in
[the S3 landing note](../../prds/steward-platform/research/acquisition-by-provenance-s3-2026-09-16.md).
