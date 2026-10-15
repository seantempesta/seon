# exp-b: retraction churn in `seon.fn/reconcile-tx-in` (refutes cause 2A(i); confirms 2A(ii))

Measured read-only on default (JVM mode, `d/with`, no transact) on Call 1 of argon-hunts-b Hunt 2: base `6acf6fed`,
head `6acfdaa8`, main `6ad0440f` of `:chain-f1f6dc8e6652`; 675 delta identities, 660 carried entities, tx of 5,599 ops
(24 `:db/retractEntity`, 775 `:db/retract`, 1,546 `:db.fn/retractAttribute`, 2,594 `:db/add`, 660 maps).
Sampling: the first 110 identities by `pr-str` (tx 983 ops, 117 entities), because the full `d/with` is 6.7 GB.

## The clause and callers

`seon.fn/reconcile-tx-in` decides per entity: `same-indexed-entity?` skips an unchanged entity; otherwise
`seon.program/exact-replacement-tx-in` -> `replacement-tx` (src/seon/program.cljc) retracts only
`(changed-attributes-in ...)`: attributes whose normalized value is `not=` between current and desired.
Single-valued ones get `[:db.fn/retractAttribute e a v]`; set attributes (`:seon.program/set-attributes`) get
`[:db/retract e a m]` for `current - desired` only and re-add only `desired - current`. The row re-added
is `select-keys ... changed-attributes`. So the diff Argon asks for already exists at the attribute and set-member level.

Callers of `reconcile-tx` / `reconcile-tx-in`: `seon.merge/merge-main!` (merge.clj:461), `seon.cluster.source/replacement`
(source.clj:787), `seon.test` overlay (test.clj:1949), `seon.fn/reconcile-call` (publication/write-back `:db.fn/call`,
fn.clj:4093; also `adoption-tx` via cluster.clj:2531), population caller-findings (fn.clj:4250). A `defn` eval request
does not go through it (it writes one declaration through the eval recording path); the hot, repeated paths are
publication/write-back and merge.

## Churn counts (sample of 110 identities)

- Exact `(e a v)` retracted and re-added on an existing entity: **0** of 1,067 retracted / 4,357 added datoms.
- A one-function tx (`my.program/ns-unmap!`, a real definition change): retracts exactly `arglists arities doc form-span
  source spec analyzed-source-digest definition-digest` plus 9 `:db/retract` set members; every one of those values differs
  from the new value. Across 60 sampled functions the single-valued retractions are digest/form-span/source/doc/spec, which
  change with any body edit by construction. `call-arities`/`calls`/`references` retract only removed members.
- Vector attributes: `seon.db/vector-diff` already keeps retained cells (a retained element keeps its cell; only
  unmatched cells are retracted).
- Only possible structural churn: component children (`:seon.fn/arities` and its arguments/bindings) are rebuilt when the
  whole arities value differs, with new eids. An upper bound by (attribute, value) overlap is 538 of 1,067 retracted datoms,
  but most of that overlap is coincidence across entities (same `binding/symbol`, same `position`). Grouped per child entity,
  at most 163 of 235 retracted child groups (313 non-ref datoms, <30%) have an identical re-added group somewhere in the
  tx, again an over-count. No measured case of an unchanged attribute being retracted.

Verdict on (i): the claim "emits retractions for attributes whose values did not change" is not what the code does or what the
data shows. A churn-free version of this tx would be the same tx, so there is no before/after to report: the red proof
would be green on HEAD.

## Costs (sample, warm cache, `d/with` of the prepared tx, thread alloc)

| step | datoms | ms | MB |
|---|---|---|---|
| whole prepared tx (972 ops) | 1,067 retracted, 4,357 added | 2,014 (first), ~1,300 later runs | 1,301 |
| retract ops only (450 ops) | 521 retracted | 466 / 395 (repeat) | 430 / 363 |
| remaining ops (adds, maps, vector cells fn) | 4,357 added, 546 retracted by `vectors-tx` | 346 | 328 |

Retracting costs ~0.7-0.8 MB and ~0.8 ms per retracted datom here; adding ~0.02 MB each (Argon: 0.35 MB vs 0.007 MB on the
full call). Storage reads were not measured (no stats seam used in this lane).

## Verdict on (ii)

The retractions that remain are genuine (definition changes, removed calls, replaced vector cells), and they dominate the
`d/with` cost: ~1,067 retractions explain most of the 2.0 s / 1.3 GB while 4,357 adds explain ~0.33 GB. So the
persistent-sorted-set `Branch.remove` behavior is the real multiplier and no change in `reconcile-tx-in` removes it.
The only reducer of retraction count is fewer genuinely retracted datoms:
- `:seon.program/analyzed-source-digest`, `form-span`, `source`, `doc`, `spec`, `definition-digest` are each retracted and
  re-added as a single-valued attribute. `:db/add` on a cardinality-one attribute makes Datahike retract the old datom itself, so
  `retractAttribute` is redundant there but yields the same datom retraction (no saving; component attributes still need
  the explicit cascade).
- Moved declarations that main changed and the branch did not (the merge case) are re-written whole because the branch
  value is replaced by main's. That is inherent to the transaction, not churn.

## Recommendation

Do not implement the "diff before emit" change; it is already implemented and measured to remove nothing. If the merge cost
must fall, the levers are (a) the library fix to `Branch.remove`/`tryComputeSubtreeCountFromChildren`, (b) merge-main!
producing the tx from main's committed delta so unmoved declarations are never touched, and (c) the lane omb-p2 merge-commit
work (a forward merge as a merge commit moves no declarations at all). A proof for (a) is a storage-read assertion on
`d/with` of N retractions (reads per retracted datom bounded), not wall-clock.
