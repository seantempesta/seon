---
type: research
status: complete
tags: [database, datahike, performance, evidence]
---

# Eval store per-attribute byte census

## Answer

Agents are storing two expensive classes of facts:

1. The program/schema population inherited from the fork parent is physically
   repeated inside immutable commit snapshots. It accounts for
   **1,128,741,258 B / 58.215% / 5.701 MB per sample**. The largest attribute
   is non-semantic `:seon.schema/created-at` at 187,360,394 B. Program text and
   documentation follow: `:seon.test/source` is 173,810,064 B,
   `:seon.fn/source` is 150,492,183 B, `:seon.fn/doc` is 63,635,367 B, and
   `:seon.ns/doc` is 58,347,635 B.
2. The sample itself adds **797,198,474 B / 41.115% / 4.026 MB per sample**.
   Its largest payloads are evaluation results (106,980,416 B), receipt problem
   identities (57,768,507 B), exact rendered prompts (53,797,232 B), frozen-form
   identities (52,016,670 B), form source (31,495,484 B), and captured output
   (26,660,686 B).

The remaining 12,992,082 B / 0.670% is record-envelope structure. The result
**refutes** the expectation that a fork makes the program graph free in the
per-sample physical delta. Content-addressed child nodes are shared, but the
fused roots embedded in each retained database record contain inherited datoms
again. The validated snapshot model predicts exactly this.

The reduction follow-up is [[store-census-reductions-2026-08-02]]. It lands
the seven measured no-history declarations, explains why unfusing roots does
not remove changed-node retention, proves the production result splitter at
4,096 against 65,536, and records the coordinated owner boundary for deleting
`:seon.schema/created-at`.

**Correction after physical deletion measurement:** the 187,360,394 B row is
an exact codec-weighted allocation, not a causal removable-byte estimate. The
landed deletion's paired 198-head / 13,204-commit exact-topology cell measured
9,661,654 B removed. Shared fused-node framing made the allocation excellent
for ranking churn but 19.4× too large as a deletion projection. The complete
counterfactual and instrument are in the reduction follow-up.

## Scope and dependency ledger

This census applies, rather than re-derives, the model validated in
[[store-amplification-anatomy-2026-08-02]]:
`4 * P * (N(N+1)/2 + N)` for a shallow retained-snapshot run. The selected 198
branches reconstruct to the already-validated **1,938,931,814 B / 9.793 MB per
sample** over 13,204 commits. The byte pools here reconcile to that exact
total.

The maintained dependencies are Datahike
`256b714d97a0e8f952b01a47c693eff2976ccee7`, Konserve
`737697d9205e5e8f0bc08a666e4c97dad55e9dbe`, and
persistent-sorted-set `0.4.137`. The record writer is
`reference-code/datahike/src/datahike/writing.cljc:48-180,477-552`; the index
codec is
`reference-code/datahike/src/datahike/index/persistent_set.cljc:526-566`; the
node codec is
`reference-code/persistent-sorted-set/src-clojure/org/replikativ/persistent_sorted_set/fressian.cljc`.

The read-only instrument is
`docs/prds/sci-execution-runtime/research/scripts/store-attribute-census-2026-08-02.clj`,
SHA-256
`524ed77890cf6facd8c5a8cbe67b3024ade48999c9d6e82f50df64ff9799ff13`.
It loads the previously validated decoder without changing it; that decoder
retains SHA-256
`516b635904547fa25102391ede9e21f2d591609ce793060c420d21db33125210`.

## Read-only copy and size evidence

The `.eval` archive is
`evals/runs/2026-08-02-gpqa-seon-full-198/2026-08-02T13-10-48-00-00_gpqa-diamond_EMVtW2ePz78tqpCwZEo5sk.eval`.
Its recorded summaries name the two preserved physical roots under
`tmp/inspect-ai-head-c6d79f817/tmp/inspect-ai/`. They were cloned with
copy-on-write `cp -cR` before decoding. No Datahike connection was opened and
no transaction was performed.

| Root | Source before | Copy before | Copy after clone | Source after all probes | Copy before final census | Copy after final census |
|---|---:|---:|---:|---:|---:|---:|
| `333214a0f358` | 1,606,071,118 B | absent | 1,606,071,118 B | 1,606,071,118 B | 1,606,071,118 B | 1,606,071,118 B |
| `d0cd8fbc1fa3` | 485,844,715 B | absent | 485,844,715 B | 485,844,715 B | 485,844,715 B | 485,844,715 B |

The commands sum regular-file logical sizes with `stat`; directory inode sizes
are excluded consistently. The copies live only under
`tmp/store-census-20260802/`. The live `default` cluster and default operator
root were not touched.

## Delta method

For each archived sample the script resolves the sample and grading branch
heads, walks their commit parents, intersects all reachable chains to identify
the common fork base, and subtracts every base commit and base index address.
A datom whose transaction is later than the base maximum transaction is
sample-generated; an earlier datom is inherited from the fork parent.

One concrete sample proves both endpoints:

- Inspect sample `rec06pnAkLOr2t2mp`, Seon sample
  `inspect-5c022394c4594117`;
- sample branch `:inspect-sample-1e063f81-37f`, whose mutable head was removed
  after grading;
- grading branch `:inspect-grade-inspect-5c022394c4594117-fc8963cc`;
- fork-parent commit
  `6a6f4113-d0a3-5f8f-b8df-b21dd7a1c325`, maximum transaction 536870921; and
- archived ending commit and surviving grading head
  `6a6f412d-05db-5123-8974-ef919273bb26`, maximum transaction 536870985.

Every physical record/index-node byte pool is exact. Fressian framing and node
headers are shared by multiple datoms, so they have no literal attribute byte
address. The script assigns each pool by the same codec's serialized-datom
weights and uses deterministic largest-remainder allocation; record envelopes
remain a separate structural row. Thus every attributed row is an exact-total
allocation, not a claim that a node header belongs intrinsically to one
attribute. The complete 208-row ranking is emitted as `:eval/census`.

## Ranked physical-byte census

Current/history and live/snapshot are orthogonal splits. “Current” means bytes
in current index families; “history” means temporal index families. “Live” is
reachable from a surviving ending head; “snapshot” is retained only through
immutable commits or obsolete nodes.

| Rank | Attribute | Total B | Current B | History B | Live B | Snapshot B | Origin |
|---:|---|---:|---:|---:|---:|---:|---|
| 1 | `:seon.schema/created-at` | 187,360,394 | 91,780,015 | 95,580,379 | 3,391,448 | 183,968,946 | inherited |
| 2 | `:seon.test/source` | 173,810,064 | 106,750,364 | 67,059,700 | 23,291,670 | 150,518,394 | inherited |
| 3 | `:seon.fn/source` | 150,492,183 | 59,677,848 | 90,814,335 | 7,442,365 | 143,049,818 | mixed |
| 4 | `:seon.cluster.eval/result-edn` | 106,980,416 | 53,315,880 | 53,664,536 | 10,890,263 | 96,090,153 | generated |
| 5 | `:seon.fn/doc` | 63,635,367 | 10,219,134 | 53,416,233 | 1,646,112 | 61,989,255 | mixed |
| 6 | `:seon.ns/doc` | 58,347,635 | 984,503 | 57,363,132 | 24,298,565 | 34,049,070 | inherited |
| 7 | `:seon.fn/calls` | 57,983,622 | 57,983,622 | 0 | 1,204,197 | 56,779,425 | inherited |
| 8 | `:seon.problems/id` | 57,768,507 | 28,509,245 | 29,259,262 | 2,136,561 | 55,631,946 | generated |
| 9 | `:seon.context.capture/prompt` | 53,797,232 | 23,001,711 | 30,795,521 | 9,833,818 | 43,963,414 | generated |
| 10 | `:seon.cluster.run.form/id` | 52,016,670 | 23,749,280 | 28,267,390 | 1,773,058 | 50,243,612 | generated |
| 11 | `:seon.fn.ast/type` | 36,751,946 | 20,260,923 | 16,491,023 | 1,095,474 | 35,656,472 | mixed |
| 12 | `:seon.test/sym` | 36,212,576 | 21,963,212 | 14,249,364 | 2,851,604 | 33,360,972 | inherited |
| 13 | `:seon.cluster.run.form/run` | 36,043,765 | 16,228,007 | 19,815,758 | 1,210,261 | 34,833,504 | generated |
| 14 | `:seon.cluster.run.form/ns` | 35,557,745 | 16,008,751 | 19,548,994 | 1,194,081 | 34,363,664 | generated |
| 15 | `:seon.fn/sym` | 34,383,433 | 18,856,426 | 15,527,007 | 1,418,499 | 32,964,934 | mixed |
| 16 | `:seon.cluster.eval/id` | 32,854,310 | 14,233,993 | 18,620,317 | 1,800,879 | 31,053,431 | generated |
| 17 | `:seon.cluster.run.form/source` | 31,495,484 | 18,460,191 | 13,035,293 | 2,416,764 | 29,078,720 | generated |
| 18 | `:seon.cluster.eval/output` | 26,660,686 | 16,355,745 | 10,304,941 | 1,815,090 | 24,845,596 | generated |
| 19 | `:db/txInstant` | 26,589,887 | 26,589,887 | 0 | 1,176,212 | 25,413,675 | mixed |
| 20 | `:seon.fn.ast.entry/value` | 26,540,196 | 14,681,952 | 11,858,244 | 2,510,684 | 24,029,512 | mixed |

These 20 attributes account for 1,285,282,118 B / 66.29%. The machine-readable
output ranks all 208 attribute/origin rows rather than hiding the tail.

The sample-generated ranking answers what one episode adds logically:

| Rank | Sample-generated attribute | Total B | History B | Snapshot B |
|---:|---|---:|---:|---:|
| 1 | `:seon.cluster.eval/result-edn` | 106,980,416 | 53,664,536 | 96,090,153 |
| 2 | `:seon.problems/id` | 57,768,507 | 29,259,262 | 55,631,946 |
| 3 | `:seon.context.capture/prompt` | 53,797,232 | 30,795,521 | 43,963,414 |
| 4 | `:seon.cluster.run.form/id` | 52,016,670 | 28,267,390 | 50,243,612 |
| 5 | `:seon.cluster.run.form/run` | 36,043,765 | 19,815,758 | 34,833,504 |
| 6 | `:seon.cluster.run.form/ns` | 35,557,745 | 19,548,994 | 34,363,664 |
| 7 | `:seon.cluster.eval/id` | 32,854,310 | 18,620,317 | 31,053,431 |
| 8 | `:seon.cluster.run.form/source` | 31,495,484 | 13,035,293 | 29,078,720 |
| 9 | `:seon.cluster.eval/output` | 26,660,686 | 10,304,941 | 24,845,596 |
| 10 | `:seon.cluster.eval/run` | 23,918,602 | 13,562,168 | 22,619,674 |
| 11 | `:seon.cluster.eval/ns` | 23,124,335 | 13,216,529 | 21,835,903 |
| 12 | `:db/txInstant` | 21,947,039 | 0 | 20,901,095 |
| 13 | `:seon.cluster.run/forms` | 16,155,432 | 0 | 15,560,686 |
| 14 | `:seon.cluster.run.form/ordinal` | 13,563,391 | 5,367,621 | 12,689,290 |
| 15 | `:seon.cluster.eval/result-size` | 13,203,055 | 6,323,818 | 12,170,812 |

The identity/ref rows are not large strings. They are expensive because every
receipt/form datom recurs through later snapshots. In particular,
`:seon.problems/id` is not a removable duplicate: the archived resolution in
[[archive/form-and-receipt-identities-collide-for-problem-assignment]] gives it
a distinct identity and routing job.

## History and snapshot multiplication

| Split | Bytes | Fraction | Per sample |
|---|---:|---:|---:|
| Current index families | 1,009,843,141 | 52.082% | 5.100 MB |
| Temporal history families | 916,096,591 | 47.247% | 4.627 MB |
| Live ending state | 145,061,905 | 7.482% | 0.733 MB |
| Retained snapshots/obsolete nodes | 1,793,869,909 | 92.518% | 9.060 MB |

History is driven first by `:seon.schema/created-at` (95,580,379 B),
`:seon.fn/source` (90,814,335 B), `:seon.test/source` (67,059,700 B),
`:seon.ns/doc` (57,363,132 B), `:seon.cluster.eval/result-edn`
(53,664,536 B), `:seon.fn/doc` (53,416,233 B),
`:seon.context.capture/prompt` (30,795,521 B), `:seon.problems/id`
(29,259,262 B), and `:seon.cluster.run.form/id` (28,267,390 B). Those nine
attributes account for 506,220,888 B / 55.26% of all temporal bytes.

Snapshot multiplication, not ending-state size, is the dominant why. For
example, only 10,890,263 B of result bytes and 9,833,818 B of prompt bytes are
live; 96,090,153 B and 43,963,414 B respectively are retained by snapshots.
Even `:seon.schema/created-at` has only 3,391,448 live bytes and 183,968,946
snapshot bytes.

## Why the blob census is zero

The archived run did **not** use the tree's current 4,096-character threshold.
All 198 archived attempt settings record
`:seon.config.eval.result/blob-threshold 65536`, all 198 record
`:seon.config.ai/thinking :disabled`, and zero attempts carry reasoning. The
4,096 change landed later in commit `063b09f327a8f4aaf5895dd7129d106fef56857f`
at 2026-08-02T10:46:58-04:00.

| Attribute | Datoms | UTF-8 B | Max chars | Over 4,096 | Over 65,536 | Path |
|---|---:|---:|---:|---:|---:|---|
| `:seon.cluster.eval/result-edn` | 5,368 | 3,840,864 | 13,256 | 444 | 0 | splitter |
| `:seon.context.capture/prompt` | 198 | 3,443,098 | 22,363 | 198 | 0 | bypass |
| `:seon.cluster.eval/output` | 1,584 | 658,944 | 1,367 | 0 | 0 | inline |
| `:seon.cluster.run.form/source` | 5,368 | 643,228 | 14,094 | 9 | 0 | inline |
| `:seon.cluster.message/content` | 198 | 166,838 | 5,842 | 1 | 0 | inline |

`seon.cluster.loop/settlement-result` consults the threshold, writes the full
result through `seon.blob/put!`, and leaves a bounded result window plus digest
and size (`src/seon/cluster/loop.clj:469-522`;
`src/seon/render/value.clj:246-257`). `attempt-finish!` applies the same split
to reasoning (`src/seon/cluster/loop.clj:915-963`). No eligible result crossed
65,536 and reasoning was disabled, so these paths correctly wrote zero blobs.

Prompts are different. `seon.context/capture-tx` puts exact rendered text
straight into `:seon.context.capture/prompt` (`src/seon/context.clj:119-144`).
It never consults the threshold or blob owner. The schema comment still says
the blob archive does not exist (`resources/seon/schema.edn:888-894`). Thus
every prompt was under the archived threshold **and**, more importantly,
prompts could not split at any threshold. Raw provider reply text is not stored
as `:seon.ai/text`; parsed reply form source is stored. Reasoning is absent,
not small.

## Ranked recommendations

### 1. Delete `:seon.schema/created-at`

Measured saving target: **187,360,394 B / 946,265 B per sample**, including
95,580,379 temporal bytes and 183,968,946 snapshot bytes.
`seon.schema/canonical-schema-rows` writes it (`src/seon/schema.clj:2180-2202`)
and `seon.cluster/schema-row-changes` preserves it
(`src/seon/cluster.clj:258-262,314-332`). The timestamp defines neither schema
identity nor content; transaction metadata owns provenance. Delete the
attribute, argument, preservation code, and display readers. `:db/noHistory`
would save its temporal share as an interim measure, but would retain a stored
non-semantic clock and 91,780,015 B of current allocation.

### 2. Put exact context captures through the blob owner

Measured saving target before blob/digest overhead: **53,797,232 B / 271,703 B
per sample**, including 30,795,521 history bytes. Keep the exact capture as
evidence, but store its content-addressed digest and size on the capture. Debug
and forensic readers should resolve the blob. Repair `seon.context`; do not add
a second splitter.

### 3. Mark measured append-once payload attributes `:db/noHistory` — applied

These values are current facts whose old values have no independent meaning.
Datahike keeps their current datoms while omitting temporal datoms
(`reference-code/datahike/src/datahike/db/transaction.cljc:439-466,538-560`).

| Rank | Attribute | Measured history B | Per sample | Reason |
|---:|---|---:|---:|---|
| 1 | `:seon.cluster.eval/result-edn` | 53,664,536 | 271,033 B | terminal receipt payload |
| 2 | `:seon.context.capture/prompt` | 30,795,521 | 155,533 B | idempotent capture; superseded by recommendation 2 |
| 3 | `:seon.cluster.run.form/source` | 13,035,293 | 65,835 B | frozen form source |
| 4 | `:seon.cluster.eval/output` | 10,304,941 | 52,045 B | terminal captured output |
| 5 | `:seon.cluster.message/content` | 2,020,059 | 10,202 B | immutable message content |
| 6 | `:seon.ai.attempt/settings-edn` | 1,750,500 | 8,841 B | immutable attempt settings |
| 7 | `:seon.ai.attempt/usage-edn` | 360,367 | 1,820 B | immutable terminal usage |

Together these account for **111,931,217 B / 565,309 B per sample / 5.773%**.
This is measured temporal allocation, not a promise that an in-place toggle
reclaims old immutable commits; acceptance needs a fresh eval root plus the
existing cutoff-GC plan. Identity/ref attributes are excluded because
historical topology and routing depend on them.

Commit `041540fb8` applies all seven declarations. The selected-198 exact
census counterfactual is now 1,827,000,597 B / 9.227 MB per sample. A
controlled physical replay and focused 81-test gate are recorded in
[[store-census-reductions-2026-08-02]].

### 4. Keep 4,096 after the production splitter sweep

The validated model has no 4,096-character knee. If payload `P` were replaced
only by a 64-character digest across the run's average 66.69 commits, the
shallow model's average repetition coefficient is about 139.37 and the
storage-only break-even is approximately
`139.37 * 64 / (139.37 - 1) = 64.46` characters. That is only a mathematical
lower bound: the real path also persists a bounded result window and size,
writes and reads a blob, and crosses deeper index nodes.

The committed follow-up now sweeps the production result splitter at the
measured 67-commit/sample shape. With the census's observed split proportion,
4,096 grew 1,277,558 B and split six results; 65,536 grew 1,572,238 B and split
none. The 4,096 cell saved 294,680 B / 18.743%. Thresholds 64 through 4,096
split the same six values and differed by only 401 B, so lowering further would
widen blob use without measured benefit. Keep **4,096**, replacing its
disproved 86× provenance with this result. Prompt capture must still be fixed
independently because no threshold reaches it.

### 5. Reduce commits and retain fewer snapshots

The largest saving remains the storage owner: 1,793,869,909 B / 92.518% is
snapshot-retained, and 1,128,741,258 B is inherited data. Batching receipt
transitions, the ruled private history-off eval root, and cutoff GC attack the
quadratic multiplier for every attribute.

## Reproduction

After making copy-on-write clones at the paths above:

```bash
SEON_DECOMPOSITION_LIBRARY=1 \
SEON_CENSUS_OUTPUT=tmp/store-census-20260802/census.edn \
clojure -M:dev \
  docs/prds/sci-execution-runtime/research/scripts/store-attribute-census-2026-08-02.clj
```

The final run printed 198 samples, 1,938,931,814 physical bytes,
9,792,584.919 B per sample, 208 attribute/origin rows, and 48 string
attributes. It also emitted: 198/198 thresholds were 65,536, 198/198 thinking
settings were disabled, and 0/198 attempts carried reasoning.
