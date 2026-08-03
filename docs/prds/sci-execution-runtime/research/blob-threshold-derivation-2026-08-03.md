---
type: research
status: complete
tags: [database, datahike, performance, evidence]
---

# Blob-threshold full-model derivation

## Corrected decision

The shipped `:seon.config.eval.result/blob-threshold` default is **4,096
serialized characters**, restored as a measured latency and object-count
floor. Crossing the floor is now only eligibility: `settlement-result` also
compares the complete stored shapes and writes a blob only when the window,
digest/size envelope, and blob payload are smaller than retaining the full
result inline.

The earlier 343 decision is rejected. It modeled the full transaction for a
scalar string, but generalized that scalar crossover to every result shape and
ignored the fixed synchronous blob-write cost in the eval settle path. Its
test then copied two measured numbers into a table instead of asserting the
production derivation. Commit `2bef865bf` is therefore corrected by this work.

## Question 1: full stored shapes

The first curve did call the production `settlement-result` and transact
`:seon.cluster.eval/result-edn`, `/result-blob`, and `/result-size`, so its blob
cells included the inline window plus digest/size envelope and binary payload.
The defect was the input: `exact-result-edn` supplied a scalar string. A scalar
window collapses to a small truncated-string node, making that one shape look
like a universal size crossover.

The follow-up uses actual `seon.sci.admit/admit` output and the production
eight-item `result-window-edn`. Fresh fused-root, diff-buffered, history-on file
stores measured:

| Admitted shape | Full `R` | Window `W` | Inline growth | Forced blob growth | Decision |
|---|---:|---:|---:|---:|:---|
| scalar string | 258 B | 72 B | 5,822 B | 6,079 B | inline by 257 B |
| scalar string | 358 B | 72 B | 6,220 B | 6,177 B | blob by 43 B |
| one-item vector | 4,107 B | 4,107 B | 21,240 B | 26,090 B | inline by 4,850 B |
| wide vector | 4,408 B | 850 B | 22,438 B | 13,353 B | blob by 9,085 B |

There is no honest global byte crossover: the 4,107-character one-item vector
keeps its entire item in the window, so blob placement stores the full value
twice and is always larger. A similarly sized wide vector has a much smaller
window and benefits materially.

### Derived comparison

`:seon.cluster.eval/result-edn` is `:db/noHistory`. In the validated shallow
one-commit model, its payload occurs in EAVT and AEVT in both the immutable
commit and mutable branch head. Full inline cost therefore contributes `4R`.
Blob placement contributes the inline window on those four paths plus the full
binary payload once: `4W + R`.

Both scalar calibration cells independently leave **743 bytes** after removing
those payload terms:

```text
R=258, W=72: (6,079 - 5,822) + 3R - 4W = 743
R=358, W=72: (6,177 - 6,220) + 3R - 4W = 743
```

That remainder contains the blob framing and digest/size receipt envelope. The
production decision is consequently:

```text
blob when threshold-eligible AND 743 + 4W + R < 4R
```

The model predicts the two adversarial shapes exactly or within four bytes:
4,850 B extra for the one-item vector and 9,081 B saved for the wide vector,
versus measured 4,850 B and 9,085 B. The implementation uses UTF-8 byte counts,
not character counts, for this physical comparison.

## Question 2: settle wall time

`blob/put!` performs `exists?`, then synchronous Konserve `bassoc` inside
`settlement-result` (`src/seon/blob.clj:29-40`;
`src/seon/cluster/loop.clj:527-548`). Konserve serializes and writes the binary
object, synchronizes it and the directory, and atomically renames it
(`reference-code/konserve/src/konserve/impl/defaults.cljc:57-125,580-611`).
This work is before the terminal Datahike transaction and directly lengthens
eval settlement.

The requested N=50 probe used unique admitted scalar results of 475–477
characters on fresh production-format file stores. Two complete runs measured:

| Threshold | Blob writes | Total settle time | Median | p95 |
|---:|---:|---:|---:|---:|
| 343 | 50 | 428.70–462.45 ms | 8.905–9.019 ms | 9.709–10.016 ms |
| 4,096 | 0 | 1.11–1.69 ms | 0.0087–0.0160 ms | 0.036–0.061 ms |

At this size, 343 makes settlement roughly 274–387 times slower in aggregate.
That hot-loop regression disqualifies the byte-only scalar crossover. The
4,096 floor keeps this measured small-result class inline; an operator may
still override it, while the stored-shape comparison prevents an override from
forcing a physically larger result receipt.

## Question 3: the 198-sample object count

The preserved archive has 5,368 current result receipts across 198 grading
branches. The object-count script walks their real EAVT roots without opening
or mutating a Datahike store, then counts distinct threshold-eligible content
per physical operator root because `seon.blob` is content addressed within one
store.

| Threshold | Eligible receipts | Distinct blob objects, threshold-only |
|---:|---:|---:|
| 343 | 1,007 | 557 |
| 4,096 | 444 | 291 |

Thus accepting 343 under the old unconditional splitter would mint **557 blob
objects**, 266 more than 4,096. Since GC currently runs without an effective
cutoff, these are retained objects rather than harmless temporary files. At
the measured scalar median, 557 unique synchronous writes also imply roughly
5.0 seconds of settle work if every eligible value had the scalar's small
window; this is an estimate, not a replay.

Applying the corrected stored-shape comparison to every archived result selects
**zero** blobs at either threshold: all 557 threshold-eligible distinct values
have windows whose complete blob-side shape is not smaller. This explains why
the scalar synthetic curve was a poor workload proxy and means the corrected
mechanism would mint no result blob objects for that exact archived run.

## Dependency ledger and reproduction

Measurements use Datahike `0e8601d7f2f68c01070e13a95483bc82be04cabc`,
Konserve `737697d9205e5e8f0bc08a666e4c97dad55e9dbe`, fused roots, a 256-entry
diff buffer, history enabled, and the self writer. The governing source is:

- Datahike stored database and ordered writer shapes:
  `reference-code/datahike/src/datahike/writing.cljc:48-180,477-552`;
- Konserve binary write/read path:
  `reference-code/konserve/src/konserve/impl/defaults.cljc:57-125,580-611`;
- Seon result window: `src/seon/render/value.clj:220-257`;
- result settlement: `src/seon/cluster/loop.clj:497-548`; and
- validated linear-payload/quadratic-commit model:
  `research/store-amplification-anatomy-2026-08-02.md:110-155`.

Reproduction scripts:

- `research/scripts/blob-threshold-full-shape-2026-08-03.clj` creates only
  UUID-named file stores under `tmp/`, measures forced inline/blob shapes plus
  N=50 latency, and deletes every store; and
- `research/scripts/blob-threshold-object-count-2026-08-03.clj` reads the
  preserved archive and stores without opening a connection or writing data.

## Regression

`test/seon/blob_threshold_test.clj` asserts the mechanism against real admitted
shapes:

- a measured ~500-character result remains inline under the shipped floor;
- an eligible one-item vector whose window equals the full result remains
  inline; and
- an eligible wide vector with a much smaller window uses a blob and restores
  the exact full value.

The test derives behavior from the production comparison; it does not copy a
343/344 measurement table or assert that a byte crossover is universal. The
focused gate covering blob threshold, existing blob settlement, cluster loop,
and config application ran 25 tests / 116 assertions with zero failures or
errors. Both reproduction scripts completed; the full-shape script deleted all
of its private stores.
