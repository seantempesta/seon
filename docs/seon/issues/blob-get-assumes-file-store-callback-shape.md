---
type: issue
status: open
severity: friction
tags: [issue, database, testing]
---

# Blob get assumes the file-store callback shape

## Problem

`seon.blob/get` destructures the `konserve.core/bget` callback argument as
`{:input-stream ...}`. The file store supplies that shape, but Konserve's
in-memory store supplies a raw byte array. A blob written through the ordinary
in-memory database fixture therefore cannot be read back: the callback tries
to close the byte array and throws `IllegalArgumentException`.

## Evidence

- `src/seon/blob.clj:38-45` — `bget` assumes an `:input-stream` callback value.
- `test/seon/test_support.clj:151-183` — ordinary database tests use the
  in-memory backend.
- 2026-08-01 reasoning-persistence probe — `blob/put!` succeeded, then
  `blob/get` failed with `No matching field found: close for class [B`.

## Acceptance criteria

- `seon.blob/get` decodes and verifies both maintained Konserve callback
  shapes without adding a second blob reader.
- One recurring test round-trips the same UTF-8 content through the in-memory
  fixture and the file-backed store.

## Owner

Blob storage owner (`seon.blob`).
