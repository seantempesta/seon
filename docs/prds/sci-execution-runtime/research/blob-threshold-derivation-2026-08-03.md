---
type: research
status: complete
tags: [database, datahike, performance, evidence]
---

# Blob-threshold derivation

## Decision

The shipped `:seon.config.eval.result/blob-threshold` default is **343
serialized characters**, derived from the measured physical-store crossover.
The operator override remains. The production splitter stores a value inline
when its character count is at most the dial and uses `seon.blob` above it, so
343 selects the cheaper representation on both sides of the measured knee:

| Characters | Inline growth | Blob growth | Cheaper |
|---:|---:|---:|:---|
| 343 | 6,157 B | 6,159 B | Inline by 2 B |
| 344 | 6,163 B | 6,160 B | Blob by 3 B |

This is option (a), a config default derived from the validated store model,
not a tuned compromise. The dial is still useful because the storage saving
has a latency price: on this machine the blob path added roughly 7–21 ms to
settlement across the small cells, while warm `bget` plus EDN parsing remained
below 1 ms. An operator may raise the threshold when avoiding blob write/read
latency is worth greater retained growth.

Option (b), deleting the dial in favor of a per-value duration comparison, is
the wrong seam. By the time `store-session-values!`, `settlement-result`, or
`record-attempt!` invokes this choice, the system has already decided that the
value is durable; the choice here is only inline Datahike value versus Konserve
blob. The resume precedent compares a defining form's recomputation cost with
stored-value read-back earlier in the lifecycle
(`plan/README.md:1795-1810`). Result receipts and attempt reasoning have no
equivalent recomputation alternative, so applying that rule here would create
three consumer-specific persistence policies instead of strengthening the one
existing storage-placement mechanism.

## Dependency ledger

The measurement ran on 2026-08-03 against:

- Datahike `0e8601d7f2f68c01070e13a95483bc82be04cabc`;
- Konserve `737697d9205e5e8f0bc08a666e4c97dad55e9dbe`; and
- the production file-store configuration: fused roots, 256-entry diff buffer,
  history enabled, and the self writer
  (`src/seon/cluster/store.clj:150-164`).

The source boundaries establishing the comparison are:

- Datahike derives the stored database value and flushes its primary indexes
  in `reference-code/datahike/src/datahike/writing.cljc:48-180`;
- its writer orders changed index values, the immutable commit, and the mutable
  branch head in `reference-code/datahike/src/datahike/writing.cljc:477-552`;
- Konserve serializes and writes each supplied value in
  `reference-code/konserve/src/konserve/impl/defaults.cljc:57-125`;
- the binary blob path is `-bget` and `-bassoc` in
  `reference-code/konserve/src/konserve/impl/defaults.cljc:580-611`, exposed as
  `bget` and `bassoc` by
  `reference-code/konserve/src/konserve/core.cljc:633-672`; and
- Seon's production blob owner performs UTF-8 encoding, content addressing,
  binary write, read-back, and digest verification in `src/seon/blob.clj:15-63`.

The earlier anatomy's validated model remains the governing model: payload
growth is linear in payload size and quadratic in sequential retained commit
count while the fused roots remain shallow
(`research/store-amplification-anatomy-2026-08-02.md:110-155`). The quoted
86× was the `N=40` retained-snapshot slope, not a per-value multiplier. Ruling
#44 consequently named 4,096 as unjustified and required this revisit
(`plan/README.md:2058-2092`); the 2026-08-03 batch explicitly required a
derived comparison (`plan/README.md:1927-1931`).

## Owner and consumers

The owner is the config dial declared in `resources/seon/schema.edn:615-618`
and defaulted in `config/default.edn:34-42`. The shipped 4,096 value and its
disproven 86× provenance came from commit `063b09f327`; the earlier 65,536
default came from `be37aac87`.

There is one query owner and three write consumers:

- `result-blob-threshold` reads the database fact
  (`src/seon/cluster/loop.clj:469-473`);
- session values choose `:seon.code.def/value-edn` or `/blob`
  (`src/seon/cluster/loop.clj:475-495`);
- settled eval results choose full inline EDN or a bounded window plus blob
  (`src/seon/cluster/loop.clj:503-522`); and
- model-attempt reasoning chooses inline reasoning or reasoning blob
  (`src/seon/cluster/loop.clj:928-963`).

The actual blob readers are cold session restoration, which performs `bget`
plus `edn/read-string` (`src/seon/sci/eval.clj:1335-1351`), and settled
reasoning rendering (`src/seon/render/transcript.clj:471-498`). The eval
transcript renders the digest without eagerly reading the full result
(`src/seon/render/transcript.clj:439-448`); the planned `get_value` consumer
will read it on demand.

`:seon.cluster.eval/result-edn` is declared `:seon.db/no-history? true`
(`resources/seon/schema.edn:2480`). Session value EDN and attempt reasoning are
history-bearing. The result path is therefore the least-amplified of the three
consumers; deriving the shared default there is conservative because history
can only make an inline value recur in more retained indexes.

## Falsifier

The first direct cell falsified 4,096. The production condition is strictly
`>` (`src/seon/cluster/loop.clj:510`), so a 4,096-character value stays inline.
On a fresh production-format file store the two representations measured:

| Characters | Inline growth | Blob growth | Blob saving |
|---:|---:|---:|---:|
| 4,096 | 21,184 B | 9,925 B | 11,259 B / 53.15% |

This is the missing population in the earlier 67-commit replay. That replay
contained 61 values of 59 characters and six values of 4,100–13,256, so every
threshold from 64 through 4,096 made the same split
(`research/store-census-reductions-2026-08-02.md:213-237`). Its result proved
that 4,096 beat 65,536 for that observed distribution; the gap made it unable
to test whether 4,096 was the crossover.

## Measurement method

The committed reproduction is
`research/scripts/blob-threshold-derivation-2026-08-03.clj`. Every physical
cell creates one private UUID-named file store below `tmp/`, installs only the
production attributes required by `settlement-result`, transacts the config,
records directory bytes, invokes the production splitter, transacts the
result, reads a produced blob through `seon.blob/get`, and deletes the cell.
There is one payload transaction per store, so sequential commit count does not
confound the per-value crossover.

Inputs are ASCII serialized strings with exact character counts. This aligns
the dial's declared unit with UTF-8 bytes one-for-one. Non-ASCII content may
cross earlier because the dial counts characters while the blob stores UTF-8
bytes; 343 is consequently conservative for storage, not a promise that every
Unicode payload has the same exact knee.

The broad curve was:

| Characters | Inline growth | Blob growth | Blob saving |
|---:|---:|---:|---:|
| 64 | 5,042 B | 5,875 B | −833 B |
| 256 | 5,811 B | 6,072 B | −261 B |
| 1,024 | 8,884 B | 6,845 B | 2,039 B |
| 4,096 | 21,184 B | 9,925 B | 11,259 B |
| 8,192 | 37,568 B | 14,021 B | 23,547 B |
| 13,256 | 57,825 B | 19,090 B | 38,735 B |
| 65,536 | 266,945 B | 71,370 B | 195,575 B |

The boundary sweep was:

| Characters | Inline growth | Blob growth | Difference, inline − blob |
|---:|---:|---:|---:|
| 337 | 6,135 B | 6,155 B | −20 B |
| 338 | 6,139 B | 6,156 B | −17 B |
| 339 | 6,141 B | 6,155 B | −14 B |
| 340 | 6,145 B | 6,158 B | −13 B |
| 341 | 6,151 B | 6,157 B | −6 B |
| 342 | 6,155 B | 6,158 B | −3 B |
| 343 | 6,157 B | 6,159 B | −2 B |
| 344 | 6,163 B | 6,160 B | 3 B |
| 345 | 6,167 B | 6,161 B | 6 B |

The blob write path necessarily adds digest, existence check, binary file
write, synchronization, and rename costs. In the reproduced run, settlement
at the exact boundary took 1.74–1.91 ms inline and 8.65–9.77 ms through the
blob path. First blob reads were 0.19–0.25 ms there; the median of seven warm
`bget` plus EDN parses was 0.045–0.049 ms. These timings describe this APFS
machine and are evidence for the override trade-off, not a latency contract.

## Regression

`test/seon/blob_threshold_test.clj` makes drift loud with two small in-memory
tests:

- the file-measured 343/344 boundary derives 343, equals the shipped config,
  and drives the production splitter to inline 343 and blob 344; and
- one 4,096-character cell under the derived default has less raw retained
  payload than forced-inline storage, while `seon.blob/get` restores the exact
  serialized value.

The focused gate on 2026-08-03 ran 2 tests and 9 assertions with zero failures
or errors. The adjacent blob-settlement and config-application gate then ran 6
tests and 28 assertions with zero failures or errors. The reproduction script
also completed and deleted every private file-store cell.
