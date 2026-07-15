---
type: issue
status: open
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

- Publication fsyncs the final pathname's containing directory after atomic
  rename before reporting success.
- Injected failure before and after rename leaves either no final pathname or
  complete hash-verifiable content; retry converges idempotently.
- Restore materialization verifies the final destination by digest after
  publication and cannot license force without directory-durable success.
- Existing `put!`, `get`, `text`, `concat!`, storage-view, and projection
  contracts remain unchanged.
