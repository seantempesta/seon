---
type: issue
status: open
severity: friction
tags: [issue, blob, database, test, class/n13, wave/blob-storage]
---

# Blob get assumes the file-store callback shape

## Problem

`seon.blob/get` assumes that Konserve skips its `bget` callback when a binary
key is absent. The file store has that behavior, but the in-memory store calls
the callback with `{:input-stream nil}`. A missing memory-store key therefore
reaches `read-octets`, which tries to close nil and throws a
`NullPointerException` instead of reporting that the blob is unavailable.

The original issue diagnosis said the in-memory store supplied a raw byte
array as the callback argument. That is false at the maintained Konserve pin:
it supplies the same callback map as the file store, with the stored byte array
under `:input-stream` when the key is present.

## Evidence

- `src/seon/blob.clj:314-335` — `blob/get` destructures the callback map and
  passes its possibly nil `:input-stream` to `read-octets`.
- `reference-code/konserve/src/konserve/memory.cljc:77-82` at maintained pin
  `07377c27c8288b7484f0aa7b82e8158b415985be` — `MemoryStore/-bget` always
  invokes the callback; `(second (get @state key))` is nil for an absent key.
- 2026-08-06 live JVM probe — a missing key produced
  `{:callback-map? true, :input-stream-nil? true}`; after `bassoc`, a present
  key produced
  `{:callback-map? true, :input-stream-nil? false,
  :input-stream-byte-array? true}`.
- `test/seon/blob_test.clj:218-224` — present UTF-8 content already round-trips
  through the in-memory backend.
- The 2026-08-06 bare-gate NPE was a caller-ordering falsifier, not a production
  publication failure: `test/seon/blob_threshold_test.clj` called the private
  stage-only `settlement-result` transform and read its digest without passing
  `:seon.blob/staged-writes` through `blob/with-publication!`. The production
  loop performs that publication before the root transaction at
  `src/seon/cluster/loop.clj:1638-1668`.

## Acceptance criteria

- Missing content has the same explicit result on both maintained Konserve
  backends and never reaches `read-octets` as nil.
- The result is represented at the owning boundary as the declared flat
  blob-unavailable error rather than as a backend exception.
- Present UTF-8 content continues to round-trip through both the in-memory
  fixture and the file-backed store without adding a second blob reader.

## Owner

Blob storage owner (`seon.blob`).
