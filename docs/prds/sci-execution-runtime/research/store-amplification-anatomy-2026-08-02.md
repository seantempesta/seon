---
type: research
status: active
tags: [database, datahike, performance, evidence]
---

# Store write-amplification anatomy

## Scope and dependency ledger

This report explains the file-store bytes before changing the write path. It
uses the maintained Datahike revision `256b714d97a0e8f952b01a47c693eff2976ccee7`,
Konserve revision `737697d9205e5e8f0bc08a666e4c97dad55e9dbe`, and
persistent-sorted-set `0.4.137` from the root dependency pin. The first-party
creation owner is `seon.cluster.store/datahike-configuration`; the current
tree already creates fresh stores with fused roots and a 256-entry diff buffer
and reopens existing stores by adopting their stored creation settings
(`src/seon/cluster/store.clj:156-185,308-328`). The current Konserve file
backend is multi-key-capable, so Datahike sends it one ordered collection of
writes (`reference-code/datahike/src/datahike/writing.cljc:497-528`).

The exact source boundaries read for the byte model are:

- Datahike turns a database value into six possible primary-index roots,
  schema metadata, and one stored database map in
  `reference-code/datahike/src/datahike/writing.cljc:48-180`;
- one commit orders pending index nodes, optional schema metadata, the
  immutable commit record, and the mutable branch head in
  `reference-code/datahike/src/datahike/writing.cljc:477-552`;
- cardinality-one writes update current and temporal indexes in
  `reference-code/datahike/src/datahike/db/transaction.cljc:538-582`;
- persistent-sorted-set queues every changed node as a new content-addressed
  value and uses a 512-entry default branching factor in
  `reference-code/datahike/src/datahike/index/persistent_set.cljc:409-485`;
- Konserve serializes the complete value supplied for each key and, on a
  single-key file write, forces the file and directory in
  `reference-code/konserve/src/konserve/impl/defaults.cljc:57-123`; and
- the maintained ordered file batch stages, forces, renames, and
  directory-forces each member in order in
  `reference-code/konserve/src/konserve/filestore.clj:90-153`.

## What one commit writes

For the persistent-set backend, changing a datom creates new immutable nodes
on every affected index path. The current indexes are EAVT and AEVT for every
datom, plus AVET only for an indexed/unique value. With history enabled,
cardinality-one upserts also update the corresponding temporal indexes. The
unchanged parts remain addressed by their old content keys; changed nodes are
queued as new `[address node]` pairs
(`persistent_set.cljc:409-444`).

`db->stored` flushes all three current roots and, when history is enabled, all
three temporal roots. Root fusion removes each root node from the separate
pending-node writes and embeds it in the stored database map instead
(`writing.cljc:73-84,142-180`). Fusion therefore saves objects and force calls;
it does not make the root bytes disappear. For a shallow index whose root is
also its leaf, the complete datom set is now inside every stored database map.

The commit then writes, in causal order:

1. every changed non-fused index node, children before parents;
2. one schema-metadata object only when that content-addressed schema map is
   absent from the process write cache (`writing.cljc:58-70`);
3. one immutable stored-database map under the new commit ID when the commit
   graph is enabled; and
4. the same stored-database map under the mutable branch key, last.

The branch roster is not rewritten on an ordinary transaction. It changes
only when branch lifecycle changes. On a steady-state commit the mutable
branch-head file is replaced, so its contribution to *net store growth* is the
difference between the new and old snapshot sizes. The immutable commit file
is additive and retains the complete snapshot bytes forever until useful GC
is allowed to remove it.

The object rig makes those categories concrete for one 4 KiB, non-indexed,
cardinality-one payload transaction after schema setup:

| Creation shape | Index-node objects | Immutable commit | Mutable head | Net directory growth |
|---|---:|---:|---:|---:|
| fused, diff buffer 256, history on | 0 | 19,201 B | 19,171 B | 35,761 B |
| unfused, diff buffer 0, history on | 4 leaves / 18,190 B | 1,694 B | 1,664 B | 19,884 B |
| fused, diff buffer 256, history off | 0 | 10,145 B | 10,115 B | 18,407 B |

The logical object sizes do not sum to net directory growth because the
mutable head replaces its smaller predecessor. Fusion is therefore a clear
object/force-count win but not an unconditional byte win: in a shallow tree it
copies all four history-on root leaves into both the immutable commit and the
mutable head. In this cell that makes the two fused database maps larger than
four separate content-addressed leaves plus two small database maps. Once the
tree splits, only the root node—not every descendant payload—is fused, so this
shallow result must not be extrapolated to a deep store.

Schema metadata is content-addressed by its UUID and guarded by the writer's
process write cache; an ordinary schema-unchanged transaction writes no new
schema-meta object (`writing.cljc:55-70`). Initial creation also writes the
initial roots, commit record, mutable head, and branch roster
(`writing.cljc:650-711`). The 16 KiB retained inventory after the 40 measured
transactions contains two schema-meta objects totaling 1,228 B and one
87-byte branch roster; these are small creation/schema-change costs, not the
per-transaction driver.

The ordered Konserve multi-key operation does not currently collapse those
writes into one file or one force. It serializes every value separately, then
the file backend stages, file-forces, atomically renames, and directory-forces
each member (`defaults.cljc:415-473,637-672`;
`filestore.clj:90-153`). Its benefit is one ordered protocol operation with a
durable-prefix crash contract. Any fsync-count improvement must be measured
separately; multi-key capability alone does not prove one.

## Why the quoted 86x appeared

The retained measurement behind the issue did **not** transact one 4 KiB or
64 KiB payload once. It ran 40 sequential transactions, and every transaction
added another entity carrying a same-sized string
(`tmp/store-amplification/amplify.clj`, `payload-4k` and `payload-64k`). The
reported 350.19 KiB and 5,510.19 KiB figures are total growth divided by 40.

That store is shallow: fewer than the 512 entries that would split a root.
The payload attribute is not indexed. Each cardinality-one upsert therefore
places the string in four fused roots: current EAVT and AEVT plus temporal EAVT
and AEVT (`transaction.cljc:538-582`). At transaction `i`, the immutable
commit record contains all `i` strings in all four roots. Across `N` commits,
those immutable records contain `4P * sum(1..N)` payload bytes. The mutable
branch file finishes with `4PN` payload bytes; its previous sizes were
replacements, not additive growth.

Ignoring small Fressian and database-map framing, total payload-attributable
growth is therefore:

```text
G(N, P, I=4) = I * P * (N(N+1)/2 + N)
             = I * P * N(N+3)/2

average per transaction = G / N
                        = I * P * (N+3)/2
```

For the retained rig, `I=4` and `N=40`, so the predicted payload slope is
`4 * 43 / 2 = 86` stored bytes per input byte. This is exactly the slope
between the measured 4 KiB and 64 KiB cells. The remaining roughly 6.19 KiB
per transaction is fixed framing and the non-payload datoms.

This corrects two descriptions in the issue:

- payload **size** is linear in this range; 4 KiB to 64 KiB changes the output
  by exactly the model's constant 86x slope; and
- the superlinear growth is over sequential transaction count: retaining a
  complete immutable snapshot after each addition makes cumulative growth
  quadratic while the fused roots remain shallow.

Once an index exceeds one leaf, payload bytes move into changed child nodes
and only separator keys plus addresses remain in the fused root. The multiplier
then depends on tree depth, affected indexes, whether the value is a separator,
history, and commit retention. Applying the shallow-store 86x multiplier to a
large eval store without inspecting its objects is invalid.

## Prediction made before measurement

The two existing cells gave a first, deliberately simple framing estimate:

```text
fixed = 350.192 KiB - 86 * 4 KiB = 6.192 KiB/transaction
```

For the unmeasured 16 KiB payload cell with the same `N=40` configuration, the
model predicts:

```text
6.192 KiB + 86 * 16 KiB = 1,382.192 KiB/transaction
```

The committed
`research/scripts/store-write-object-anatomy-2026-08-02.clj` rig improves the
prediction by measuring a zero-payload control and fitting the small Fressian
expansion from the 4 KiB training cell. It prints the prediction before it
performs the first 16 KiB transaction. The held-out result was:

| Quantity | Bytes |
|---|---:|
| predicted 40-transaction growth | 56,648,465 |
| measured 40-transaction growth | 56,618,147 |
| prediction error | -30,318 (0.05355%) |

The actual average is 1,415,454 B (1,382.28 KiB) per transaction. The model
therefore predicts a previously unmeasured payload size to within 0.054% and
graduates from a story to a useful explanation.

The retained object inventory independently verifies the coefficient. The
mutable head contains 160 payload occurrences (`4 * 40`), while the immutable
commit records contain 3,280 occurrences (`4 * sum(1..40)`); together they are
the predicted 3,440 copies. The extra two pre-baseline commit records contain
no payload. Three tiny index leaves total 525 B, the two schema-meta objects
total 1,228 B, and the branch roster is 87 B.

## Eval-sample decomposition

The retained read-only
`research/scripts/eval-sample-store-decomposition-2026-08-02.clj` rig joins the
archived 198 sample identities to both preserved operator roots, follows each
sample and grading branch's reachable commit graph, decodes every Konserve
object, and partitions the serialized database maps into record envelopes and
fused roots. Its script SHA-256 is
`516b635904547fa25102391ede9e21f2d591609ce793060c420d21db33125210`; the
Inspect archive SHA-256 is
`3b279877ddbad8f9ec66bc547f60998055be45a02341fbb54b13f9103546b158`.

The first correction is categorical: the issue's 42.444 MB median and
58.12 MB mean were differences between timestamps in one shared store with up
to five overlapping peers. They are not attributable per-sample costs. The
selected 198 branches reconstruct to 1,938,931,814 B, or 9,792,584.92 B
(9.793 MB) per sample, across 13,204 immutable commits—66.6869 transactions
per sample.

| Reconstructed selected-198 category | Bytes | Fraction |
|---|---:|---:|
| record envelopes | 12,992,082 | 0.6701% |
| current fused roots | 647,541,512 | 33.3968% |
| temporal fused roots | 794,520,131 | 40.9772% |
| current-only persistent-set leaves | 362,301,629 | 18.6856% |
| temporal-only persistent-set leaves | 121,576,460 | 6.2703% |
| **total** | **1,938,931,814** | **100%** |

The immutable commit records are 1,421,031,700 B. Within them, the record
envelope is 12,794,676 B—exactly 969 B per transaction on average—current
fused roots are 634,116,808 B, and temporal fused roots are 774,120,216 B.
The selected 198 mutable branch heads add 34,022,025 B: 197,406 B of envelope,
13,424,704 B of current fused roots, and 20,399,915 B of temporal fused roots.
Persistent-set leaves add 483,878,089 B. There are no stored branch nodes in
these roots; the roots are fused into the database records.

History's physical fraction is directly separable: temporal fused roots plus
temporal-only leaves total 916,096,591 B, or 47.2475%. Omitting those objects
and fields gives a history-off counterfactual of 1,022,835,223 B total, or
5,165,834.46 B (5.166 MB) per sample. This counterfactual preserves the same
transaction and current-index workload; it does not assume transaction
batching or GC.

The blob fraction is exactly zero. The preserved stores contain neither a
Konserve binary object nor a SHA-256 string key of the form
`seon.blob/put-bytes!` writes. The archive's transcript strings total only
2,155,179 raw UTF-8 bytes, but string datom values recur across retained fused
snapshots and leaves: decoded representations contain 1,124,682,462 raw UTF-8
bytes. That latter figure includes every string-valued datom and is not a
schema-attributed physical-byte count, so the honest inline-payload result is:
inline recurrence is dominant and directly observed, while exact transcript-
versus-other-string attribution remains bounded uncertainty. Lowering the blob
threshold cannot explain or fix this run because no blob object was written;
the high-leverage levers are history, commit count/snapshot retention, and a
ruled GC cutoff.

The two physical roots report 2,041,745,524 B of growth and 203 completions,
five more episodes than the selected archive mapping. The selected
1,938,931,814 B plus 61,319,611 B reconstructed for those five extras leaves
41,494,099 B (2.03%) for baseline/shared/schema/orphan boundary objects. Thus
the selected reachable-set categories sum exactly, while the selection's
relationship to whole-directory growth carries a stated 2.03% reconciliation
residual.

## Change ledger

The retained option matrix is
`research/scripts/store-options-before-after-2026-08-02.clj`. On one fresh
3,623-current-datom private store, with two warmups and seven measured
single-row replacements, it found:

| Shape | Forced blobs / commit | Median | Final objects | Final bytes |
|---|---:|---:|---:|---:|
| unfused, no diff, sequential, history on | 14 | 73.967 ms | 234 | 1,043,078 |
| unfused, no diff, ordered batch, history on | 14 | 116.963 ms | 234 | 1,043,033 |
| fused only, ordered batch, history on | 10 | 83.812 ms | 174 | 1,037,857 |
| fused + diff 256, ordered batch, history on | **2** | **18.113 ms** | 99 | 709,478 |
| fused + diff 256, ordered batch, history off | **2** | **17.026 ms** | 60 | 362,844 |
| current path + writer wait 5 ms, serial | 2 | 23.608 ms | — | — |

This settles the three maintained-fork store options:

- fused roots and the 256-entry diff buffer are already created by Seon and
  produce the proven 14 → 2 force and 73.967 → 18.113 ms win;
- ordered multi-key writes are already selected, but on the filestore they
  preserve causal durable-prefix order and still force each object; by
  themselves they were slower in this run; and
- disabling the commit graph is inadmissible because Seon creates branches
  from exact published commit IDs. It would remove the immutable records whose
  IDs that mechanism requires.

The writer's batching dial has a real but workload-dependent effect. Three
bursts of 24 callers with wait 0 produced 72 logical transactions in six
physical commits, a 36.789 ms median burst, and 652 transactions/s. Wait 5
produced three physical commits, a 26.953 ms median burst, and 890
transactions/s, while worsening the serial median by 5.495 ms. A literal
workstation-tuned wait is not adopted: writer configuration is captured at
connection acquisition, while Seon's runtime configuration facts require that
connection to read them. The acquisition/reconnect mechanism must be designed
before this can be a database-backed dial.

History-off reduced the controlled store from 709,478 to 362,844 bytes
(48.9%) and 99 to 60 objects, with no force-count reduction. Ruling #40's
economy is therefore proven. It is not yet safe as a per-cluster toggle:
`seon.reconcile/plan` makes an unguarded `d/history` read, and Datahike branch
creation copies the ancestor database value and changes only the branch name.
The safe near-term mechanism is a private history-off operator root plus an
honest non-temporal reconciliation fallback, both followed by a boot proof;
those owners are outside this wave's protected boundary.

Cutoff GC is similarly proven but not yet safe as policy. In an isolated
80-replacement store, plain `gc-storage` swept 0 of 90 objects and kept all
7,847,585 bytes. A cutoff at measurement time swept 86 objects and left four
objects / 372,335 bytes, reclaiming 7,475,250 bytes (95.3%), but it also
removed a sampled old commit record. Production needs a ruled commit-ID and
database-value retention window. Datahike's background collector cannot be
called directly because Seon's GC owner extends reachability with
schema-discovered blob keys.

Finally, the cache probe found two distinct 1,000-entry LRU allocations. The
outer Konserve wrapper stayed at zero entries through three direct reads. The
inner persistent-set node cache populated five entries on the first query and
performed no additional backing reads on the second. Removing the unused
outer allocation is a semantics-preserving fork cleanup with a direct
before/after measure; it does not change store bytes.

## Applied fixes and replay budget

Two safe fixes landed after the anatomy gate:

- `db4efb4fd` makes the settled ordinary-store policy explicit by emitting
  `:keep-history? true`. Before and after both normalize to history-on, so the
  measured byte change is exactly zero; the win is removal of an inherited
  policy decision.
- maintained Datahike commit `0e8601d7`, pinned by Seon commit `ccde63a4c`,
  removes the unused outer Konserve LRU. Before: two caches, outer 0 → 0
  entries, inner serving the second query without another backing read. After:
  outer absent, inner present, same query result. This removes one 1,000-entry-
  capacity allocation per connection and changes no file-store bytes.

Consequently, an identical 198-sample replay after the changes in this wave is
budgeted at the same **1,938,931,814 B selected reconstruction** and roughly
the same **2,041,745,524 B whole-root growth** (subject to the measured 2.03%
selection-boundary residual and ordinary run variance). Claiming a storage
reduction from these two fixes would be false.

The proven but not yet landed history-off mechanism would instead budget the
same selected workload at **1,022,835,223 B**, a 916,096,591 B / 47.25%
reduction. The cutoff-GC cell proves 95.3% reclamation for its isolated
80-replacement workload, not for this eval store; without a retention contract
there is no honest post-GC 198-sample prediction. Writer waiting improves burst
coalescing but does not by itself change a strictly serial episode's retained
snapshot count. Episode transaction batching remains the other high-leverage
storage win, but its complete run/receipt transaction boundary is protected in
this wave and no eval-scale saving is claimed without measuring that integrated
change.
