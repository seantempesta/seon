---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Make blob publication directory-durable

## Problem

`my.blob/publish!` writes a unique temporary file, fsyncs its file descriptor,
and atomically renames it to the content-addressed final path. It does not fsync
the shard directory after rename. A process or machine failure can therefore
lose the directory entry after restore preparation reported the blob
materialized, allowing guarded Datahike force to publish a root whose projected
blob bytes are not durably present in the main archive.

Ordinary append-only blob use tolerated this gap as a repairable file/projection
split. Restore cannot: verified blob materialization is a destructive-transition
precondition.

## Evidence

The current source is `src/my/blob.cljs` `publish!`. The selected Konserve
filestore demonstrates the complete durability idiom: synchronize written
content, atomically move the published name, and synchronize the containing
directory/store. Exact grounding and the restore failure cuts are in
`docs/prds/database-lifecycle-recovery/research/restore-blob-and-cold-reconstruction-contract-2026-07-15.md`.

## Owner

The one private `my.blob` publication operation. Restore materialization must
reuse it; neither the operator nor a restore namespace may implement another
copier or filesystem publisher.

## Acceptance

- Publication durably creates every missing archive-directory entry, fsyncs the
  temporary file, atomically renames it, and fsyncs the final shard before
  reporting success.
- Injected failure before and after rename leaves either no final pathname or
  complete hash-verifiable content; retry converges idempotently.
- Any durability failure returns before the database projection transaction;
  restore materialization must consume this same operation before licensing
  force rather than interpreting pathname presence itself.
- Existing `put!`, `get`, `text`, `concat!`, storage-view, and projection
  contracts remain unchanged.

## Resolution

Resolved by `2827e991`. Publication now creates missing writable/archive
directories one level at a time and fsyncs each parent entry, then performs
temporary-file fsync, atomic rename, and shard-directory fsync. A retry after
post-rename failure re-verifies the writable content and fsyncs the existing
file plus directory chain without renaming it. Any failed fence prevents the
blob projection transaction.

The focused isolated CLJS gate compiled 510 files with zero warnings and passed
13 tests/91 assertions with zero failures or errors. It injects failures before
writable-root durability, before rename, and after rename; proves the only
remaining pathname is absent or hash-verifiable; and proves retries converge
without replacing an existing verified blob. Retained evidence is
`tmp/test-cljs-20260715-100822-31792.log` and its `.report.edn` projection.

Restore materialization itself remains the ordered later Slice 4 consumer in
the database-lifecycle-recovery roadmap; this resolution closes the publication
owner it must reuse and does not claim that consumer is already implemented.
