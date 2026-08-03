---
type: research
status: complete
tags: [database, datahike, performance, evidence]
---

# Store census reductions

## Answer

One measured reduction landed: seven payload attributes now use
`:db/noHistory`. The census assigns them **111,931,217 B / 5.773% / 565,309 B
per sample** of the selected 198-sample run. On the same selected workload,
the exact census counterfactual is therefore **1,827,000,597 B / 9.227 MB per
sample**, down from 1,938,931,814 B / 9.793 MB per sample. That number is an
exact subtraction from the validated physical-byte allocation, not a fresh
198-sample replay.

The largest attributed row, `:seon.schema/created-at`, is now deleted by
`e4bffb0d1`. The deletion was correct, but the **187,360,394 B / 946,265 B per
sample attribution was not a causal saving**. A paired production-population
cell with the archived 158/40 root split, 198 heads, and exactly 13,204
post-base commits measured **9,661,654 B / 48,796 B per sample** removed. The
codec-weighted census had assigned shared fused-node framing to the clock; the
paired physical deletion is the stronger counterfactual and is only 5.157% of
that allocation.

Fork-parent bytes are not removable by changing the existing fork call.
`branch!` copies one stored database record, but every later transaction
retains a new immutable index version. Root fusion decides whether the changed
root node is embedded in the database record or stored under its content
address; persistent-sorted-set still copies the changed leaf/path at node
granularity. In the committed controlled cell, disabling fusion made the fork
itself 249,008 B smaller but made the following 20-commit run 19,200 B larger.
No creation-option change or maintained Datahike patch landed.

The 4,096-character blob threshold remains. A committed production-splitter
cell with 67 commits and the census's observed split proportion grew
1,277,558 B at 4,096 versus 1,572,238 B at 65,536: **294,680 B / 18.743% less**.
A threshold of 64 saved only another 401 B in this cell because the observed
small/large populations have a gap, while it would send every unmeasured
65–4,096-character value through blob I/O. Thus the validated model's
64.46-character digest-only lower bound is not an operational threshold;
4,096 is the highest measured cell that captured the same six oversized
results without widening blob use. The value stays, but the disproved 86×
provenance in `config/default.edn` must be replaced by this evidence at its
owner.

## Dependency ledger

The reduction instrument ran against Datahike
`0e8601d7f2f68c01070e13a95483bc82be04cabc`, Konserve
`737697d9205e5e8f0bc08a666e4c97dad55e9dbe`, and persistent-sorted-set
`e1a17bbe767c7801e67407c81f64efabfd2f1601`.

The source boundaries are:

- exact-commit branching copies the selected stored database record and changes
  only its branch in
  `reference-code/datahike/src/datahike/versioning.cljc:237-289`;
- commits build and write the immutable commit record plus mutable head in
  `reference-code/datahike/src/datahike/writing.cljc:48-180,477-552`;
- root fusion embeds root nodes at `writing.cljc:142-180`;
- persistent-sorted-set queues each changed node under its content address in
  `reference-code/datahike/src/datahike/index/persistent_set.cljc:409-425`;
- cardinality-one no-history writes omit temporal index operations at
  `reference-code/datahike/src/datahike/db/transaction.cljc:439-466,538-560`;
  and
- the production result splitter is
  `src/seon/cluster/loop.clj:469-522`, with its bounded window at
  `src/seon/render/value.clj:246-257` and blob write at
  `src/seon/blob.clj:11-30`.

The committed instrument is
`research/scripts/store-census-reductions-2026-08-02.clj`, SHA-256
`94682d33c1983e803366810ce9125200108fec8ebc4a5217ad87c498c8af89eb`.
Its final machine-readable output is
`tmp/store-census-reductions-production-2026-08-02.edn`, SHA-256
`0087483c9a71d72e77b1ae84376dcfab4d428b3c68c7477a653d9b81716dc970`.

The clock-deletion instrument is
`research/scripts/schema-created-at-saving-2026-08-02.clj`, SHA-256
`b3578d1daf34b3e5c0529d594d2bdb9f8648d03b6d187c6eeefdacf437f837b7`.
Its retained evidence root is
`tmp/schema-created-at-saving-2acf0c0b-5592-4867-b18c-e0b35d20a29a`.

## Isolation and physical size evidence

Every cell created a new file store beneath
`tmp/store-census-reductions-ebd58050-cae4-43a8-a2e2-04f5b7ddb218`.
No source archive, eval root, default operator root, or live cluster was opened.
Logical bytes are sums of regular-file lengths; directory inode sizes are
excluded. The script does not delete its evidence roots.

### Exact-commit fork and root fusion

Both cells used history on, the commit graph on, and diff buffer 256. The
parent contains the same 200 inherited payload rows; the child performs the
same 20 transactions.

| Operation | Fused bytes | Unfused bytes |
|---|---:|---:|
| Created | 4,470 | 4,428 |
| Schema installed | 8,986 | 9,247 |
| Parent populated | 507,642 | 261,283 |
| Exact commit forked | 758,336 | 262,969 |
| Child committed | 5,844,456 | 5,368,289 |
| Fork growth | 250,694 | 1,686 |
| Child-run growth | 5,086,120 | 5,105,320 |

The final fused record contains 2,036 inherited datoms in its six root nodes;
the unfused record contains none because it carries root descriptors. The
unfused store nevertheless grew **19,200 B / 0.377% more** over the child run:
changed persistent-set nodes retained the inherited datoms as standalone
content-addressed objects. This falsifies “disable fusion to remove inherited
retention” for the measured workload. Fusion's immediate fork duplication is
real, but the 13,204 retained immutable commits—not 198 mutable branch heads—
are the eval-scale multiplier.

`:commit-graph? false` would remove those immutable records but is inadmissible:
Seon forks from exact published commit IDs and Datahike explicitly refuses a
commit-ID fork without the graph. `branch!` has no layout option beyond
`:sync?`. History-off remains excluded by ruling #40 and its boot/branch
blocker.

### Schema-clock and no-history matrix

Each cell installs the same schema-row population, exact-commit-forks it, and
runs the same 20 sample commits. The baseline retains schema clocks and history
for all seven payloads.

| Cell | Final bytes | Change vs baseline | Fraction |
|---|---:|---:|---:|
| Baseline | 15,914,212 | 0 | 0% |
| No schema clock | 10,896,008 | -5,018,204 | -31.533% |
| Seven payloads no-history | 7,804,533 | -8,109,679 | -50.959% |
| Both | 6,482,826 | -9,431,386 | -59.264% |

This controlled matrix proves the mechanisms, not the eval-scale magnitudes.
The eval-scale amounts come from the exact-total census:

| Attribute | Measured history B | Per sample | Landed |
|---|---:|---:|---|
| `:seon.cluster.eval/result-edn` | 53,664,536 | 271,033 B | yes |
| `:seon.context.capture/prompt` | 30,795,521 | 155,533 B | yes |
| `:seon.cluster.run.form/source` | 13,035,293 | 65,835 B | yes |
| `:seon.cluster.eval/output` | 10,304,941 | 52,045 B | yes |
| `:seon.cluster.message/content` | 2,020,059 | 10,202 B | yes |
| `:seon.ai.attempt/settings-edn` | 1,750,500 | 8,841 B | yes |
| `:seon.ai.attempt/usage-edn` | 360,367 | 1,820 B | yes |
| **Total** | **111,931,217** | **565,309 B** | **yes** |

These attributes are immutable payload observations. Their current values
remain ordinary database facts; only the duplicate temporal index entries are
omitted. Identity and ref attributes stay historical because topology and
routing depend on them.

Commit `041540fb8` applies the seven declarations in
`resources/seon/schema.edn`. A JVM probe derived exactly seven
`:db/noHistory true` declarations. The focused gate passed **81 tests / 402
assertions / 0 failures / 0 errors** across schema derivation, schema EDN,
blob settlement, context capture, the loop, and full turn behavior.

## Created-at reader census and measured deletion

Fresh source has eight literal occurrences: four in `src/seon/schema.clj`,
three in `src/seon/cluster.clj`, and one in `resources/seon/schema.edn`. There
are no literal fresh-test occurrences and no Datalog query reads the attribute.
The only database read is the boot reconciliation pull that preserves the
original value.

- `src/seon/schema.clj:716-721,2180-2202` declares and writes it.
- `src/seon/cluster.clj:258-262,314-332,379-385` pulls, preserves, and writes
  it during boot reconciliation.
- `src/seon/fn.clj:687-723` supplies epoch time during static source indexing.
- Runtime schema projection reads key, form, and the form datom's transaction,
  not the clock (`src/seon/schema.clj:1824-1860`).
- Provenance already derives from transaction facts
  (`src/seon/schema.clj:567-593,1678-1700`).
- Namespace rendering selects key and form only
  (`src/seon/render/ns.clj:36-81,348-351`).

The verdict was delete, with no replacement clock. Commit `e4bffb0d1` removed
the leaf declaration and timestamp arguments/field in `seon.schema`, removed
reconciliation's pull/preservation/`now` argument, removed the epoch argument
in `seon.fn`, and removed the optional EDN entity field. The reconciliation
comparison is now the direct semantic desired/current comparison; a focused
regression proves a converged second pass leaves `:max-tx` unchanged.

The committed paired cell uses complete production source populations. Its
baseline reconstructs the old declaration, old entity-map member, 691
canonical rows, and 691 epoch-zero clocks locally inside the script. Its
treatment uses the landed production population: 690 rows, no installed clock
attribute, and no clock datoms. Both sides then create the archived two-root
shape, 198 heads, and 13,204 commits with byte-identical deterministic marker
transactions.

| Root | Samples | Commits | Baseline B | Treatment B | Saving B |
|---|---:|---:|---:|---:|---:|
| `333214a0f358` | 158 | 10,542 | 1,284,399,105 | 1,276,655,102 | 7,744,003 |
| `d0cd8fbc1fa3` | 40 | 2,662 | 339,134,164 | 337,216,513 | 1,917,651 |
| **Total** | **198** | **13,204** | **1,623,533,269** | **1,613,871,615** | **9,661,654** |

That is **48,796 B per sample / 0.595% of this paired baseline**. The
post-base transactions reproduce the exact topology with deterministic marker
rows; they are not an identical replay of the model's logical transaction
stream. The earlier attribution therefore remains useful as a churn-ranking
signal, but it is falsified as a removable-byte estimate. Applying the paired
physical delta to the already landed no-history counterfactual corrects the
combined result from the theoretical 1,639,640,203 B / 8.281 MB per sample to
**1,817,338,943 B / 9.178 MB per sample**.

## Blob threshold

The archived run stored zero blob bytes because its 198/198 attempts used
65,536, reasoning was disabled, and no eligible result exceeded 65,536. The
current 4,096 value landed later; the census finds 444 of 5,368 result strings
above it, so the tier is not inert under current settings.

The committed replay calls the production `settlement-result` on 67 ordered
results: 61 measured-common 59-character values plus six values spanning
4,100–13,256 characters. `:seon.cluster.eval/result-edn` uses the newly landed
no-history declaration in every cell.

| Threshold | Blobs | Before results | After results | Growth |
|---:|---:|---:|---:|---:|
| 64 | 6 | 16,378 B | 1,293,535 B | 1,277,157 B |
| 256 | 6 | 16,382 B | 1,293,606 B | 1,277,224 B |
| 1,024 | 6 | 16,388 B | 1,293,678 B | 1,277,290 B |
| 4,096 | 6 | 16,396 B | 1,293,954 B | 1,277,558 B |
| 65,536 | 0 | 16,398 B | 1,588,636 B | 1,572,238 B |

The lower cells differ almost entirely because the configured integer itself
recurs; they split the same observed values. This replay proves 4,096 beats the
conservative 65,536 rollback for storage in the measured result path. It does
not calibrate thinking-enabled reasoning or session-image read latency, so it
does not justify lowering below the highest same-split boundary.

## 198-sample budget and proof labels

- **Proven landed counterfactual:** 1,827,000,597 selected bytes / 9.227 MB per
  sample after the seven no-history declarations. Proven means exact census
  allocation plus a physical before/after mechanism cell; the identical 198
  external-model run was not repeated.
- **Measured threshold decision:** retain 4,096. The production result splitter
  saved 294,680 B / 18.743% versus 65,536 in the controlled 67-commit cell.
  This changes the current default's justification, not the archived run's
  zero-blob result.
- **Measured clock deletion:** 9,661,654 B / 48,796 B per sample in the paired
  exact-topology physical cell. Combined with the landed no-history
  counterfactual: 1,817,338,943 B / 9.178 MB per sample. The old
  1,639,640,203 B / 8.281 MB projection is refuted.
- **Not claimed:** any saving from fusion, a Datahike fork change, history-off,
  GC, transaction batching, or routing prompts through the blob owner.

The whole-root budget cannot be made exact from the selected counterfactual:
the original reconstruction carries a measured 2.03% selection-boundary
residual. An identical fresh 198-sample replay is the final integrated proof.
