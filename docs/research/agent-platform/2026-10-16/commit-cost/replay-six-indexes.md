---
title: "Commit cost: durable six-index replay"
type: research
status: measured
created: 2026-10-16
---

# Durable six-index replay

**Recommendation: retain 128/32.** Neither larger buffer meets the measured durable-win
and no-read-regression requirements; 128/16 also loses. No production dial or format changes.

Lane: commit-cost-2. Follow-up to [the original attribution](report.md) and
[its probes](probes.edn). The experiment retains all six indexes and every replayed commit.
The tested control is branching-factor/diff-buf-size 128/32; candidates are 128/64 and
128/128, followed by a second 128/32 control in the same submission.

## Method and scope

[replay-six-indexes.clj](replay-six-indexes.clj) is the complete single-form MCP JVM probe;
[replay-six-indexes-results.edn](replay-six-indexes-results.edn) holds returned observations.
It reads 241 consecutive immutable main snapshots ending at
`6ad1678c-f986-5e7c-bf6d-6cffdda72745`, the same endpoint as the original twelve-commit probe.
There is one three-parent merge in this range. The path selects its unique parent whose
stored commit branch is `:cluster-default`; all 241 selected records were verified to name
that branch. `datahike.api/datoms-diff` supplies the exact removed/added datoms for each of the six indexes.
For temporal indexes, the selected temporal tree is installed at the corresponding current
index key of a disposable database value before calling the same diff function. No live
connection is transacted by the replay.

Each variant builds all six trees from the first snapshot and writes that base to its own
unique scratch Konserve directory outside the Seon store. Then each of the 240 transitions
removes and inserts the exact datoms and calls `datahike.writing/commit!` synchronously.
`datahike.index/-temporal-insert` preserves the full datom comparison, including transaction
and assertion/retraction, for both current and temporal trees. The source snapshot supplies
its schema and database metadata; the scratch store and its configuration replace the live
ones. This is an index-update-through-durable-commit measurement, not the entire Seon eval
request or transaction-validation path.

`seon.profile/measure` surrounds each transition's index mutation and durable commit. It
includes flush, codec, index files, schema metadata when changed, fused commit record,
commit-graph record, compact branch head, and the head's durability barrier. Base creation,
source-diff extraction, directory enumeration/stat, query verification, codec counterfactual,
and final full-index comparison are outside that interval. File bytes are logical file
lengths, not APFS allocated blocks or device traffic. Each overwritten branch-head file is
counted once per commit; new immutable files are counted by the exact filename difference.
The scratch-only measurement record is written after replay and excluded from its cost.

The installed Konserve configuration confirms `:sync-blob? true`, `:in-place? false`,
`:defer-immutable-sync? true`, and LZ4. `konserve.impl.defaults/sync-plan` selects deferred
sync only for new immutable objects; the mutable head provides the barrier. On this macOS
filesystem `konserve.filestore/BackingFilestore` implements the deferred data push with
`FileDescriptor.sync`, and the head awaits the store barriers. This uses the existing format
and durability protocol, without a new codec or migration path.

Fresh store handles and independent 64 MB decoded-node caches are created for every cold-read
trial. Each trial includes rereading the final head and fused record, a query of all
`:seon.fn/sym` entities, 32 deterministic entity pulls of id/symbol/doc, and an entity history
read. Five trials run per variant. "Cold" here means decoded-node cache cold: OS filesystem
cache, schema cache, and query compilation may be warm. No machine-wide cache purge is used.
Read answers are compared with the real final snapshot. Final index comparisons explicitly
compare all five components of every datom; Datahike's ordinary datom equality checks only
entity, attribute, and value and is insufficient for this proof.

Forty-commit windows show buffer aging. They contain different real workloads, so raw
late/early bytes alone do not isolate aging. Compare variants on the same window and consult
the six-index delta counts as well. The base is rebuilt with empty buffers, then aged by the
actual history; this is not a retuning or migration of the live tree shape.

## Complete main-history results

All 24 full-index comparisons (six indexes × four stores) passed, comparing all five datom
components. All 20 cold read sets matched the real final snapshot. The query returns 7,161
function entities. Each cold set incurs exactly 93 node misses, 248 hits and no evictions.

| Variant, in execution order | Durable bytes, 240 commits | Files written | Mutation + durable commit ms | Calling-thread allocation MB | Cold read median ms | Cold read allocation MB |
|---|---:|---:|---:|---:|---:|---:|
| 128/32 control A | 44,842,203 | 9,287 | 6,824.63 | 2,789.68 | 10.90 | 21.501 |
| 128/64 | 43,331,479 | 7,490 | 6,818.10 | 2,776.84 | 11.05 | 21.963 |
| 128/128 | 44,864,916 | 5,723 | 6,185.96 | 2,857.74 | 12.40 | 22.881 |
| 128/32 control B | 44,848,225 | 9,287 | 7,301.27 | 2,789.74 | 11.38 | 21.501 |

128/64 reduces bytes 3.37% and files 19.35%, but its time differs from control A by only
0.10%. The controls differ by 6.98% in time. This does not establish a durable end-to-end
improvement, and cold read allocation rises 2.15%.

128/128 reduces files 38.38% and time 9.36% against control A, but writes 0.05% MORE bytes
and allocates 2.44% more during commits. Its cold read median is 8.96–13.76% above the two
controls, with 6.42% more allocation. It fails the no-read-regression requirement even if
its write-time improvement is accepted. The two controls agree on file count exactly;
their bytes differ only 6,022 (0.013%), consistent with randomized addresses/compression.

All measured individual durable commits stayed below one second (maxima 690.87, 682.09,
464.06, and 840.60 ms respectively). The full submission took 193.688 s, which includes
four complete base builds, 960 commits, file inventories and full comparisons; it is not
an ordinary eval latency. Base construction took 16,966.02 / 15,197.18 / 14,302.98 /
15,555.33 ms, with 31,077 base files per variant and about 76.4 MB of base files. Exact
source-diff preparation took 5,803.59 ms and 7,383.14 MB calling-thread allocation. These
research scans are proportional to the complete base population and 240 history transitions;
the per-commit interval is reported separately. The complete request reported 236,360.5 MB
allocated cumulatively, not retained heap or peak memory. Each scratch node cache was bounded
to 64 MB; peak heap was not measured. JVM-window counters may include unrelated work.

### Smaller-buffer check

The aging results suggested testing less buffering. A second same-run comparison used the
identical 240 main transitions with variants `[[128 32] [128 16] [128 32]]`; this is the
only experiment parameter changed from the committed probe form.

| Variant | Durable bytes | Files | Durable transition ms | Calling-thread allocation MB | Cold read median ms |
|---|---:|---:|---:|---:|---:|
| 128/32 control C | 44,847,343 | 9,287 | 7,499.18 | 2,885.42 | 10.80 |
| 128/16 | 48,211,241 | 11,250 | 7,849.65 | 2,984.77 | 11.38 |
| 128/32 control D | 44,891,756 | 9,287 | 6,430.30 | 2,789.72 | 10.73 |

128/16 writes about 7.4–7.5% more bytes and 21.1% more files, and is slower than both
controls. Its cold-read allocation is lower (21.208 MB), but that is not a durable-write
improvement. All 18 full-index comparisons and 15 read sets passed. The submission took
141.216 s; its largest individual transition was 857.44 ms across all three stores. The
final 128/16 fused record encoded to 23,963 bytes; the codec counterfactual was 15,591 bytes
plus three additional objects. No further variant is justified by these results.

The two complete comparisons together contain 1,680 measured transitions, 42 full-index
comparisons, and 35 cold-read sets. All 1,680 individual measurement rows were recovered
from the durable scratch stores, with no printer elisions, and saved in the evidence file.
A separate artifact check verifies each 240-row count and reconciles its bytes/files to
the reported window totals. Row columns are bytes, files, milliseconds, and allocated bytes;
row position is the consecutive commit ordinal starting at one.

### Buffer aging

Bytes per commit, by matching 40-commit windows:

| Commits | 128/32 A | 128/64 | 128/128 | 128/32 B |
|---|---:|---:|---:|---:|
| 1–40 | 114,315 | 105,163 | 96,049 | 114,324 |
| 41–80 | 163,431 | 160,041 | 164,627 | 163,402 |
| 81–120 | 189,078 | 184,422 | 194,902 | 189,036 |
| 121–160 | 184,012 | 188,013 | 204,083 | 184,103 |
| 161–200 | 161,422 | 171,659 | 197,820 | 161,503 |
| 201–240 | 308,797 | 273,988 | 264,142 | 308,837 |

128/128 starts 15.98% below control A, but is 22.55% ABOVE it in commits 161–200. That
window has no temporal changes; the current EAVT delta count is 1,528, nearly the first
window's 1,537, and AVET is 452 in both. This is direct evidence that the initial empty-buffer
saving does not persist at the same small-change scale. The final window includes 7,464
current EAVT changes and 3,960 temporal EAVT changes: its high raw bytes are not attributable
to aging alone. Exact counts for all six indexes and all windows are in the evidence.

### Fused-root codec counterfactual

The final commit has three changed current roots and three unchanged temporal roots.
The existing codec produces these value sizes (file framing excluded):

| Variant | Fused record bytes | Record without roots + three changed roots | Extra objects |
|---|---:|---:|---:|
| 128/32 A | 27,013 | 18,172 | 3 |
| 128/64 | 35,044 | 21,093 | 3 |
| 128/128 | 56,765 | 31,252 | 3 |
| 128/32 B | 26,985 | 18,175 | 3 |

This is only a codec counterfactual. It does not prove a durable latency improvement,
block-allocation saving, or a safe existing-store conversion. No format change was made.
The increasing fused-record size is one measured cost of carrying larger root buffers.

## Runtime and foreign boundary

Initial default was faulted: 31 open faults, 372 errored receipts, 19 agent-error signatures,
no missing readiness layers. PID 98702, started 2026-10-15T23:39:29.526Z, ran archive
`8dd4334f89781395b98f8e6fcc8f67b1137be694`. Live resource URLs established Datahike
`88d05cf830d5d39eddf8e22be815889da7448bd6` and Konserve
`a2a24792ff410235ea4c211f8434785c6e3a6576`, with persistent-sorted-set 0.4.137 inherited
from that pinned Datahike dependency.

The first 240-commit submission lost its PREPL terminal reply while default was replaced.
Status subsequently showed PID 31614, start 2026-10-16T00:18:48.808Z, archive
`d198ac29bba481118d94bfe9ed8d1a593feb67ad`. This lane did not stop, restart, or reset default.
The interrupted run is excluded from the complete comparison. The resource URLs after
replacement still named the same loaded Datahike and Konserve revisions. Scratch files
survived; the complete comparison was resubmitted from the original immutable commit range.

Probe setup corrections: the first pull selector included nonexistent `:seon.fn/name`, then
removed it after inspecting the installed schema; scratch creation uses Konserve create
before connect; explicit datom component extraction replaces `vec` on Datom, which throws.
These attempts do not contribute timing results. A completed exploratory run followed
`first` over the unordered parent set at a merge: 120 records named main and 121 named a
merge branch. Its full-datom comparisons passed, but it is excluded from the requested
main-history comparison; the corrected range starts at `6ad0b84f-934f-54cb-bbfc-9ca11a114300`. The successful two-commit pilot was not
used to decide tuning. Its original equality checked only ordinary datom equality, corrected
for the longer proof.

Other lanes held source/schema/render/merge files and the Konserve checkout differed from
the loaded dependency. They were not edited, resumed, or messaged. The candidate commit
contains only this lane's research paths; the commit gate builds HEAD plus those paths.

## Creation settings and existing branches

`seon.cluster.store/datahike-configuration` declares creation settings;
`seon.cluster.store/open-configuration` omits index and fusion settings when reopening.
`datahike.index.persistent-set/add-konserve-handlers` supplies the creation parameters to
`datahike.index/init-index` and `datahike.index/empty-index`. Stored roots/nodes describe their
own buffer and branching settings. `datahike.versioning/branch!` reuses the selected stored
roots, and `seon.cluster.store/open-branch!` uses the existing connection's configuration.

Therefore a source-default change would affect newly created stores and trees constructed
under that new creation configuration. It would not rewrite or retune existing default,
even after restart. New ordinary chain branches forked from existing default would inherit
its 128/32 roots and configuration; merely being new branches would not give them the new
default. Existing branches' subsequent node rewrites continue under their trees' parameters.
A deliberate tree rebuild/migration is a separate operation and is not part of this lane.

## Decision and verification

Retain 128/32. Larger buffers substantially reduce file count but do not establish the
required combination of durable end-to-end gain and no cold-read regression. Smaller
buffering also does not win. The source creation default, existing default store, and new
chain branches of that store therefore remain unchanged. Kept histories were not discarded.
The next storage investigation should target the measured repeated payload/encoding at its
producer, with the same durable and cold-read proof; the codec counterfactual alone does not
justify a root-format change.

The broad namespace suite is not claimed green. The preceding lane documented the
pre-existing red `seon.cluster.store-test/datahike-logs-through-seon-bounded-and-expected-refusals-below-error`,
filed as `seon.cluster.store/datahike-error-log-lines-exceed-their-bound`; it is not rerun or
accepted by this research lane. The named narrow commit check is
`seon.cluster.store-test/configuration-is-the-one-shape`. No production or test definitions
changed (net source/test lines 0/0). Full `src script test` lint with `--cache false --parallel` completed in 6,010 ms:
**0 errors**, 1,798 warnings. The commit gate supplies its own candidate archive and test result.

After the completed measurements, default was replaced again: status showed PID 36051,
start 2026-10-16T00:30:27.920Z, archive `deeefca6e1816d7d30a1bab2a9fdf33ecb86c591`.
The first evidence-export requests found no live default during that replacement; once it
was available, all seven scratch measurement records were recovered. Neither replacement
was issued by this lane. Both successful comparisons finished within their original JVM
submission; no cross-restart timing samples are combined.

All eight owned scratch directories (pilot, setup attempts, interrupted/excluded runs, and
both complete comparisons) were removed after exporting the evidence. Konserve handles and
materialized source snapshots were released; no owned JVM or background worker remains.
Cleanup took 42.793 s over the accumulated research files. Scratch paths in the evidence
identify the experiment's provenance and no longer name live stores. No restart is requested.
