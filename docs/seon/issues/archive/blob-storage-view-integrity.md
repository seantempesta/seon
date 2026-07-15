---
type: issue
status: resolved
severity: friction
tags: [issue, archive, database]
---

# Blob storage lacks branch views and byte-integrity checks

## Problem

`my.blob` historically used one writable directory for both reads and writes.
A same-database forensic branch could therefore either lose access to source
prompt/reply bytes or share the source's writable lifetime. Reads also trusted
the hash-shaped pathname without hashing the bytes, and writes published
directly to the final path, so corruption or a process-ending partial write
could be returned as successful content.

## Evidence

The pre-fix `src/my/blob.cljs` stored one `!dir`, wrote the final pathname with
`writeFileSync`, and returned `readFileSync` content without recomputing its
SHA-256. The maintained Konserve source at
`reference-code/konserve/src/konserve/node_filestore.cljs` publishes its own
files by rename, while Datahike branches share one Konserve store rather than
copying it. The database lifecycle audit records why blob bytes need a separate
writable overlay plus read-only source bases.

The implemented correction replaces the directory with a validated
storage view, writes a unique `.new` file, fsyncs and renames it, searches the
writable overlay before ordered bases, and refuses bytes whose digest differs
from the requested name. Focused `my.blob-test` proof passes 10 tests and 65
assertions, including base fallback without copying, loud overlay corruption,
directory uniqueness, and no retained `.new` file. The combined consumer gate
passes 43 tests/245 assertions with zero warnings or failures. Live default-pod
proof read exact source bytes through an empty overlay without copying them and
returned a false integrity envelope with the actual digest for a corrupt
overlay.

## Owner

The one `my.blob` content-addressed archive and the database lifecycle launch
descriptor that will supply its storage view. Branch creation, overlay release,
promotion materialization, and garbage collection remain lifecycle work; they
must reuse this archive rather than add a fork-specific blob API.

## Acceptance

- The storage-view implementation and focused regression tests are committed.
- Preserve the public `put!`, `get`, `text`, `concat!`, and `stat` contracts.
- A normal cluster uses one writable directory and no read-only bases.
- A branch can read source bytes while every new byte lands only in its overlay.
- Corrupt bytes return a guiding error value and never fall through to a later
  base under the same hash.
- Publication exposes either no final pathname or complete hash-verified bytes.
- The fixing commit is recorded in this note's history.
