---
type: issue
status: resolved
severity: blocker
tags: [issue, test, blob, database, wave/agent-context]
---

# Platform blob reachability fails at 3d13aa0f7

Resolved by the owning transaction lane in 24953d294. It corrected the partial
schema setup and asserted successful writes. The cookbook lane's subsequent
platform gates independently passed 83 tests / 490 assertions, including both
named regressions. The retained failing root is now disposable evidence.

## Problem

The platform gate loses the blobs expected by both current and historical
reachability regressions. This reproduces without the context-cookbook changes.

## Evidence

2026-09-09, `SEON_TEST_WORKERS=3`, HEAD `3d13aa0f7`:

- `bin/test --paths` containing the cookbook's six source/test paths, with
  `--platform`: 83 tests / 490 assertions, two failures. Both reproduced in
  isolated confirmation workers. Run root: `tmp/test-runs/run.QL7w63`.
- `bin/test-fast --paths AGENTS.md -- seon.cluster.registry-test`: the snapshot
  reported no differences from HEAD. 12 tests / 62 assertions, the same two
  failures. This excludes every cookbook source/test edit.
- `blob-lifetime-follows-schema-derived-history-reachability`,
  `test/seon/cluster/registry_test.clj:140`: expected the saved historical
  content, observed `nil` from `blob/get` after collection.
- `non-temporal-collection-marks-current-blob-references`, line 164:
  expected current content, observed `nil` after collection.
- The cookbook's earlier platform gate at `eec1ca7c3` passed. Intervening
  commits include transaction validation `26ec13420`; that chronology is
  not a bisect or a causal attribution.

The fixture's `with-source-store` hand-declares schema and does not check every
transaction result. Whether setup now refuses is a hypothesis to probe.

## Owner

The existing registry fixture / transaction admission boundary. `src/seon/db.clj`
is still concurrently edited by transact-feedback; this lane did not edit it or
operate that session.

## Acceptance

Use the canonical fixture/projection, assert successful setup before collection,
and retain the positive blob-lifetime checks. Both named regressions and the
platform gate must pass. Do not weaken the reachability expectations.
