---
type: issue
status: open
severity: blocker
tags: [issue, test, operator, database, class/n9, wave/test-fixture, wave/publication-velocity]
---

# A cloned published base names a checkout that is gone, so every file reads as changed

## Problem

`seon.test-support/populate-published-root!` (`test/seon/test_support.clj:101`)
clones the runner's immutable published base into a test root. The cloned
source artifact `build/current-src.edn` keys `:seon.source/file-digests` — and
`:seon.fn.manifest/roots` — by the ABSOLUTE canonical paths of the base
checkout. The test then runs from a worker checkout at a different path.

Measured in the retained root `tmp/test-runs/run.j9rx0e`
(`workers/pool-2/tmp/boot-test/5aa0cc50-…/build/current-src.edn`), 2026-09-16:

```
file-digests count:   2048
base-prefixed keys:   2048   (…/target/test-published-bases/1e52e…/checkout/…)
worker-prefixed keys:    0   (…/run.j9rx0e/workers/pool-2/…)
```

`seon.cluster/changed-source-paths` (`src/seon/cluster.clj:1948`) unions the
cached key set with the current snapshot's key set, so all ~2 048 base paths
read as `:deleted` and all ~2 048 worker paths as `:added`. Nothing in the
program has changed. This is the project's recurring class in its exact
shape: a path that is merely spelled differently is read as a file that was
deleted, and no check reports the relocation.

The consequences, in `incremental-source-refresh!` (`src/seon/cluster.clj:1957`):

1. all 337 `.clj`/`.cljc` files are fully analysed by `seon.fn/build-artifact`;
2. `structural` is then non-empty, so that analysis is discarded;
3. `full-source-refresh!` analyses the whole tree AGAIN and publishes it.

## Evidence

`seon.cluster.boot-test/incremental-source-refresh-publishes-without-touching-existing-clusters`
asserts `(is (false? (:seon.source/built? refreshed)))`
(`test/seon/cluster/boot_test.clj:950`) for a reported file that did not
change. It has failed on that assertion, plus "the existing cluster remains on
its independent commit", in gate batches 68, 71, 74 and 79, and in batch 88 it
expired the 270 s worker exchange bound on `pool-2` mid-publication (657 store
segments written in the minute the bound fired). Its own
`:seon.test/long` reason — "186.733 s pool: complete incremental publication
dominates" (`boot_test.clj:928`) — is a record of this defect.

The expectation is correct and must not be relaxed, and the bound must not be
widened with `:seon.test/long-ms`: per AGENTS.md 2.3 the firing bound is the
bug report.

## Root fix

File identity in a publication artifact must survive relocation of its
checkout: key `:seon.source/file-digests` (and the manifest's roots and
`:seon.fn.file/path` rows) relative to the artifact's own root, resolved
against that root when read.

Until that lands, `incremental-source-refresh!` must DECIDE the relocation
instead of reading it as 4 096 changes: a cached artifact whose
`:seon.fn.manifest/roots` differ from the publication's `:seon.fn/roots` — the
value `19874b71b` already threads to that seam — is stale by construction and
belongs in the existing "complete publication: missing or stale artifact"
branch (`src/seon/cluster.clj:1968`).

## Not caused by 19874b71b

`19874b71b` touches no path identity and adds no per-row work; the failure
predates it (batches 68–79). Full triage, with the measured per-call costs
that grew the operation past the bound, is in
[docs/prds/context-generation/research/incremental-refresh-exchange-bound-2026-09-16.md](../../prds/context-generation/research/incremental-refresh-exchange-bound-2026-09-16.md).
