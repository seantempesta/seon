---
title: "konserve-read: a read without per-read buffers"
type: research
status: complete (lane konserve-read, 2026-10-15, throwaway JVM on a store clone)
created: 2026-10-15
---

# konserve-read: a read without per-read buffers

Lane `konserve-read` (Opus), 2026-10-15. Fork `reference-code/konserve`, branch `seon/tiered-range-reads`,
from `b4b896f` to **`647bd2a17a6e18dbd3e917c354b7ceda21aa3640`** (pushed to origin). Evidence from
[argon-konserve-read.md](argon-konserve-read.md) reproduced, fixed at the owner, and measured end to end. Probe forms:
[konserve-read-probes.clj](konserve-read-probes.clj). Nothing touched default; every number is from a throwaway JVM
on an APFS clone of `data/store`.

**Root's bump:** `org.replikativ/konserve {:git/sha "647bd2a17a6e18dbd3e917c354b7ceda21aa3640"}` in `deps.edn`
(and `reference-code/datahike/deps.edn` when the datahike fork next bumps). The on-disk format and every write path
are unchanged, so the bump needs no data conversion.

## Confirmed breakdown (before)

Warm `konserve.core/get`, ThreadMXBean bytes per read, clone of the live store:

| value | on disk | per `get` | per `get-meta` |
|---|---:|---:|---:|
| `[:datahike/commit-graph cid]` (61 B value) | 220 B | 189,652 | 190,360 |
| index leaf (mean of 8) | ~1.0 KB | 282,568 | ~186,000 |
| index branch node | 5.7 KB | 382,462 | 186,584 |
| commit record | 30.6 KB | 1,240,176 | 186,440 |
| `konserve.impl.defaults/key->store-key` | | 14,272 per call | |

Argon's four causes hold: two frame-sized buffers in `net.jpountz.lz4.LZ4FrameInputStream` (131 KB, per value and per
metadata read), the `BufferedInputStream` `clojure.data.fressian/read` adds (8 KB), the per-read handler `merge`
(3.8 KB), and hasch's seq of unsigned longs in `hasch.core/edn-hash` (~10 KB of the 14 KB key hash). The fixed
overhead is ~150 KB per read for every value, index nodes included.

## The changes and why each is correct

1. **`konserve.compressor/Lz4Compressor` `-deserialize`** decodes the value's LZ4 frames from its bytes (it is always a
   whole value in memory) into one array of exactly the content's length, in two passes over the frame: lengths, then
   content.
   - Uncompressed blocks are copied.
   - A compressed block's decompressed length is the sum of its sequences' literal and match lengths, read from the
     tokens (`block-length`). It is then decompressed by lz4-java's own `LZ4SafeDecompressor` into exactly that
     length, so a malformed block is still refused by lz4-java.
   - The frame descriptor is parsed by lz4-java's own `LZ4FrameOutputStream$FLG/fromByte` and `$BD/fromByte` (the
     same validation: version, reserved bits, block independence). Descriptor, block and content checksums and the
     content size are verified with lz4-java's XXHash32, as `LZ4FrameInputStream` does.
   - Frames in sequence and skippable frames are read as `LZ4FrameInputStream` reads them. A frame without an end mark
     (`Lz4Compressor` only flushes) ends at the end of the bytes; this is the shape of every stored value.
   - No shared mutable state: the decoders are lz4-java's thread-safe instances, held in a `delay`.
   - **`konserve.compressor/decompressing-stream`** (binary values) keeps `LZ4FrameInputStream`: a range read
     (`konserve.filestore/compressed-range`) must decompress only up to its end, so the stream is the right shape there.
2. **`konserve.serializers/FressianSerializer` `-deserialize`** builds `org.fressian.FressianReader` directly on the
   stream (what `fress/create-reader` does, minus `to-input-stream`'s 8 KiB `BufferedInputStream`), over one
   `ILookup` that asks the incognito, custom, then clojure handler maps. That is the handler
   `(merge clojure custom incognito)` would hold for each tag, without building the merged map. Handlers stay per call:
   `incognito-read-handlers` is still applied to the call's `read-handlers` atom (it derefs it at read time). CLJS is
   unchanged.
3. **`konserve.impl.defaults/key->store-key`** hands `hasch.benc/digest`'s bytes straight to `hasch.platform/uuid5`,
   skipping `edn-hash`'s seq of unsigned longs. This is the same uuid, proven against `hasch.core/uuid` over 516 keys
   (`konserve.store-key-test`). CLJS still calls `hasch.core/uuid`.

## Tests

Fork suite, `clojure -M:test` (cognitect test-runner): **100 tests, 2,626 assertions, 0 failures** (baseline `b4b896f`:
93 tests, 0 failures). The new tests are in `konserve.compressor-test` and `konserve.store-key-test`:

- **Old frames.** Frames captured from the code *before* the change (`test/konserve/lz4_frames/`, values in
  `konserve.lz4-fixture-values`) read back equal. They come from the 64 KiB writer and the 4 MiB default it replaced,
  and cover tiny (one uncompressed block), node (one compressed block), multi-block compressed and multi-block
  incompressible values, plus a closed binary frame read as a stream.
- **Round trips.** 40 seeds × sizes 1 B to 300 KB × compressible or not.
- **Agreement with `LZ4FrameInputStream`.** Byte-equal content over:
  - sizes 0 to 200 KB;
  - every flag set (block checksum, content checksum, content size);
  - both block sizes, closed and flushed frames;
  - frames in sequence with a skippable frame.

  A corrupted block is refused.
- **Binary values.** `bassoc`/`bget` of compressible and incompressible octets on an LZ4 store.
- **Concurrency.** 16 threads × 40 reads of the fixture values: all equal.
- **Allocation bound.** Bytes per read under 48 KB for a tiny value, and under its size + 48 KB for a 6 KB value.
  This is red on `b4b896f` (181 KB and 185 KB) and green now.

On `b4b896f` the agreement test also errors. That is expected: `LZ4FrameInputStream` raises "Stream ended prematurely"
when a flushed frame is read to its end, a read the old path never made, because Fressian stops early.

Datahike fork at HEAD `593a8276` (a worktree, not omb-gc's working tree), `clojure -M:test -m kaocha.runner clj-pss`
with konserve as `:local/root`:
- **969 tests, 5,626 assertions, 0 failures, 2 errors.**
- Both errors are in `datahike.test.background-gc-test`: "File store already exists at
  .../T/dh-bgc-branch". Another lane's datahike suite was running at the same time on the same fixed temp path.
- Rerun alone with its own `java.io.tmpdir`, that namespace is 9 tests, 45 assertions, 0 failures.

## Before / after

Per read, warm, the same clone (`konserve-read-probes.clj`, "per-read allocation"):

| value | before | after | |
|---|---:|---:|---:|
| commit-graph record (61 B) | 189,652 B | 36,583 B | -81% |
| index leaf (~1 KB on disk) | 282,568 B | 132,483 B | -53% |
| index branch node (5.7 KB) | 382,462 B | 243,963 B | -36% |
| `get-meta` (any key) | ~186 KB | ~34 KB | -82% |
| commit record (30.6 KB) | 1,240,176 B | 1,168,897 B | -6% |
| `key->store-key` | 14,272 B | 4,608 B | -68% |
| µs per tiny read | 116 | 78 | |

What a read still allocates:
- ~33 KB is konserve's own path: the two key hashes, the lock, the header, the env maps and the `async+sync` closures.
- About 100 KB per leaf is Datahike decoding the leaf's datoms: the read handlers in
  `datahike.index.persistent-set` (`dd/datom-from-reader` per datom). That is Datahike's code, outside this lane.

End to end, the same clone, one fresh JVM per konserve, Datahike HEAD. The clone's commit graph was backfilled once
before these runs. Thread MB, wall ms; storage reads are from `CachedStorage` `:stats`:

| probe | before | after |
|---|---|---|
| `datahike.api/merge-base` `:chain-f1f6dc8e6652` vs `:cluster-default`, warm (×3) | 90-103 ms, **424 MB** | 96-111 ms, **83 MB** |
| same, first call | 355 ms, 424 MB | 370 ms, 84 MB |
| cold `:eavt` scan, 709,148 datoms, 9,353 reads | 2,474 ms, **3,021 MB** | 2,152 ms, **1,659 MB** |
| `d/with` of 5,059 retractions on chain head `6acfdaa8`, cold (~16.7k reads) | 4,949 ms, **5,841 MB** | 4,254 ms, **3,318 MB** |
| same, warm runs 2-6 (2.5k-3.9k reads) | 503-832 ms, 1,195-1,612 MB | 599-938 ms, 840-1,070 MB |

- **merge-base.** It allocates 80% less, and its time is unchanged at ~100 ms, so its time is not allocation; the
  same holds for the warm `d/with`.
- **The `d/with` timings.** They ran while another lane's datahike suite was using the CPU, so its ms are noisy; the
  MB are not.
- **Before the backfill.** The clone's lineage lacked records, `merge-base` derived them from whole commit records,
  and it cost 1,653 → 1,406 MB warm.

## Verdict on root's hypothesis

**Partly confirmed: storage reads are most of the retraction cost, but konserve's fixed overhead is only about half of
each read, and the read count is Datahike's.**

- **Each storage read's cost inside the retraction `d/with`.** A linear fit of MB on reads over the warm runs gives
  ~316 KB per read and ~410 MB of other work before. After, it gives ~196 KB per read and ~310 MB. The cold run fits
  the same line: 16,816 × 316 KB + 410 MB ≈ 5.7 GB, measured 5.8 GB.
- **Storage reads are what the retraction costs.** They are 66% of a warm run's allocation and 93% of a cold run's.
- **konserve's overhead.** It removes ~120-150 KB per read, so the fix cuts the retraction `d/with` by 43% cold
  (5.8 → 3.3 GB) and ~30% warm.
- **What stays.** The number of reads stays: 5,059 retractions fault 2.5k-3.9k nodes warm and ~16.7k cold, so the
  Branch.remove multiplier argon-hunts-b names is real and not refuted. The ~100 KB of datom decoding per leaf stays
  too.
- **Argon's figure.** 1,784 MB / 12,656 reads (141 KB/read, measured on default) is lower per read than this fit.
  Argon's figure is thread MB on default, which undercounts konserve reads (one-merge-base.md §C), so the two do not
  contradict.
- **Remaining levers, in order.**
  1. Fewer node faults per retraction (`persistent-sorted-set` `Branch.remove` / subtree counts).
  2. Datahike's leaf decoding per read.
  3. konserve's remaining ~33 KB of per-read bookkeeping. The two key hashes per `get` are the largest single part.

## Verification limits

- Allocation is ThreadMXBean on the calling thread; reads are synchronous here, so it covers konserve's work.
- The exp-b `d/with` was rebuilt in plain Datahike terms: 5,059 `:db/retract` sampled evenly over the chain head's
  `:eavt`. That is the same order of retractions as Argon's 5,087, but not Seon's `reconcile-tx` output, because
  that needs Seon's code loaded against the clone.
- The datahike suite ran against the commit before the last edit, which only made defaults' `hasch.core` require
  CLJS-only. The konserve suite ran after that edit.
