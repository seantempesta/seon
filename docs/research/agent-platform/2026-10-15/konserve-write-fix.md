---
title: "konserve-write: a write without per-write buffers"
type: research
status: complete (lane konserve-write, 2026-10-15)
created: 2026-10-15
---

# konserve-write: a write without per-write buffers

Fork `reference-code/konserve`, branch `seon/tiered-range-reads`, from `af35a974` to
**`a2a24792ff410235ea4c211f8434785c6e3a6576`** (pushed to origin).

**Root's bump:** `org.replikativ/konserve {:git/sha "a2a24792ff410235ea4c211f8434785c6e3a6576"}` in `deps.edn` (and
`reference-code/datahike/deps.edn` when the datahike fork next bumps). The on-disk format is unchanged: frames are
byte-identical to `LZ4FrameOutputStream`'s, so no data conversion and old and new readers both read them.

## Cause and change

`konserve.compressor/Lz4Compressor` `-serialize` built an `LZ4FrameOutputStream` per value; the stream allocates a block
buffer and a compressed-block buffer of the block size (2 x 64 KiB) however small the value.
`-serialize` now collects the serializer's bytes in one growable array (256 B start, doubling), then writes the frame
directly: the stream's descriptor (captured once from the stream itself), then each 64 KiB block compressed by lz4-java's
`fastCompressor` into a scratch sized by `maxCompressedLength` of the block, stored uncompressed (high bit) when it did
not shrink, and an end mark only when the serializer closed the stream (`compress-octets`' binary path), as the stream
does. Flush alone leaves the frame open, as before.

## Measured (ThreadMXBean bytes per `-serialize`, warm, minimum of 20)

| value | frame | before | after |
|---|---:|---:|---:|
| `{:a 1 :b "hello"}` | 30 B | 136,608 | 5,488 |
| `(vec (range 200))` | 356 B | 143,064 | 12,528 |
| `(vec (range 60000))` | 175,869 B | 2,810,264 | 3,093,312 |
| 300 KB random octets (Fressian) | 300,047 B | 1,417,880 | 2,036,624 |

Small values drop ~25x (a node-sized one ~11x). Large values allocate about 10% to 40% more: the buffering grows the
array by doubling and the frame is written after serialization, whereas the stream compressed as it went; this is
proportional to the value and is the price of writing the right-sized frame. The serializer alone allocates 4.7 KB,
11.4 KB and 2.57 MB for the three Fressian rows, so the compressor's own share is now ~0.8 KB, ~1.1 KB and ~0.5 MB.
Not measured: the end-to-end allocation of a 17-datom Datahike commit (the finding put LZ4 at ~4.6 MB of 10 MB; at
~0.13 MB per file written, expect that share to fall to roughly 0.2 MB).

## Proofs (`konserve.compressor-test`)

- `lz4-writer-is-byte-identical-to-the-stream`: 17 sizes (0 to 300,000, around the 64 KiB block edges) x random,
  compressible and mixed content x closed or flushed: the bytes equal `LZ4FrameOutputStream`'s.
- `lz4-writer-round-trips-through-every-reader`: `LZ4FrameInputStream`, `decompressing-stream`, `Lz4Compressor`
  `-deserialize` and a Fressian value round trip.
- `lz4-write-allocates-in-proportion-to-the-value`: 20 KB bound for the 30 B value, 30 KB for the node-sized one, 3.6 MB
  for the 60,000-element vector. The small bounds are red on `af35a974` (136 KB and 143 KB).
- Fork suite `TIMBRE_LEVEL=':warn' clojure -X:test`: **104 tests, 2,805 assertions, 0 failures** (was 101).
- Datahike fork `96000df8`, `clojure -M:test -m kaocha.runner clj-pss` with `org.replikativ/konserve` as
  `:local/root` at this commit: **973 tests, 6,471 assertions, 0 failures**.
