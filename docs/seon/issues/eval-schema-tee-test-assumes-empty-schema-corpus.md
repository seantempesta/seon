---
type: issue
status: open
severity: friction
tags: [issue, schema, flow]
---

# Make the schema tee test assert its owned row

## Problem

The focused `seon.eval.record-eval-tee-test` selector passes the intended
`:probe.dom/dur-secs` schema row and namespace link, but
`data-ns-schema-tee-lands-both-rows-and-upserts-the-ns` asserts that its query
over every `:seon.schema/key` row equals a singleton. When the fixture contains
the normal boot schema corpus, the expected probe row is present alongside
those existing rows and the test fails for unrelated data.

Observed on 2026-07-15: the selector ran 32 tests/145 assertions with this one
failure. Production schema tee behavior was not shown incorrect.

## Acceptance criteria

- The test scopes its query or assertion to `:probe.dom/dur-secs` and its exact
  `:probe.dom` namespace connection.
- A preexisting schema corpus neither fails the test nor hides an absent,
  duplicated, or wrongly linked probe row.
- The focused record-eval tee selector passes without weakening the transaction
  identity/link/tee assertions.
