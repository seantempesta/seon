---
type: issue
status: resolved
severity: friction
tags: [issue, database, flow]
---

# Reject temporal KNN before embedding

## Problem

An exact earlier transaction could not use Datahike's containing-commit
secondary index, but the provider-to-KNN continuation computed the query
embedding before coordinate validation rejected the request. The response was
correct after an avoidable remote provider call had consumed latency and
capacity.

## Resolution

Resolved by `6424165b`.

The existing `pinned-database` resolver now has a primary-only validation mode.
Historical validation calls Datahike's `commit-as-db` with secondary-index
restoration disabled, verifies database identity, commit ancestry, branch
scope, and exact transaction, then releases the materialized primary database
before the embedding provider runs. The KNN continuation resolves the same
coordinate normally after embedding, so a supported historical commit still
restores its committed Proximum index.

The resolver reports whether it owns a materialized database value. Both
validation and KNN release only owned historical values; the attached live
head and its native secondary index remain open.

## Proof

`bin/test-writer seon.db.writer-integration-test seon.db.executor-test` passes
45 tests and 914 assertions. The integration proof covers:

- current-head KNN followed by a direct native Proximum query on the same live
  connection;
- full historical KNN with a restored committed secondary index;
- exact earlier-transaction and missing coordinates with no extra provider or
  KNN calls; and
- sibling and force-discarded coordinates with zero provider and KNN calls.

The native-index test uses Proximum directly for current and historical reads.
The sibling/force proof uses plain Datahike because memory-backed Proximum has
no mmap directory and therefore intentionally rejects branch/force operations;
that storage constraint is independent of coordinate rejection.
