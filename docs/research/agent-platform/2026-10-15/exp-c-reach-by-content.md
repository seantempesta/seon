# exp-c: reach prior by content, not by branch (Hunt 3, causes 3A and 3B)

Lane exp-c, 2026-10-15. Read-only JVM-mode probes on default (no var changed, no write, no cache eviction);
numbers are the replies' `:seon.cost` (alloc-mb is process allocation for the whole form, setup included, about 25 MB).
Program: 29 seed tests (the first 29 sorted `:seon.test/sym`), reach graph 5,190 nodes + 5,450 schemas, 10,590 components.
Chain: `:chain-f1f6dc8e6652` (basis 536873134; main 536873004).

## 1. The code

- `seon.test.runner/reach-owner` (runner.clj:868): `(assoc (dissoc identity :datahike.value/commit-id) :seon.store/branch <branch>)`.
  `db/committed-value-identity` supplies connection-id `[store-id branch]` and the connection **generation**.
  Measured: main and the chain differ in the branch keyword AND in the generation uuid, so dropping `:seon.store/branch` alone would not make them share.
- Uses of the owner key: `::reach-attribute-head [owner attribute shape]` (`reach-attribute`, `retire-replaced-revision!`: remembers the
  basis and revision of the last attribute map, to update it from `reach-changed-entities`), and `::reach-head owner` (`reach-entries`:
  `{::basis ::revisions}` of the last index this owner built).
- `seon.test.runner/reach-entries` (runner.clj:1242): `current` = `(cache/present ::reach-index-by-revision revisions)`, keyed by
  `reach-revision-key` = `db/content-revisions` of the attributes it reads (`:seon.fn/sym :seon.test/sym :seon.program/definition-digest
  :seon.fn/doc :seon.schema/key :seon.schema/form` + `reach-fact-attributes`). Equal programs share one index on every branch: this half is
  already by content. When `current` lacks a requested test, `prior = (or current previous)` where `previous` is found ONLY through
  `(cache/present ::reach-head owner)`; with no prior, `known = {}` and `reached-graph` + `reach-index` (Tarjan SCC + SHA-256 of every
  component) rebuild the whole closure. With a prior, `changed-reach` takes the changed entities via `reach-changed-entities` (which asks
  the database `entities-changed-after` the prior's `::basis`) and their reverse closure over `::reverse`; everything else is `known`.
- `seon.test.runner/reach-closure` (runner.clj:1478): one walk of `reach-graph` (`reach-names`, schema-key closure). It reads the same cached
  graph `reach-entries` built (`::reach-index-by-revision` hit in `reach-graph`); it recomputes only the name/schema closure, not digests.
- `seon.cluster.source/merge-seeds` (source.clj:885) calls `current-results` (-> `reach-entries`); `merge-list` (source.clj:861) calls
  `reach-closure`; `candidate-gate!` (cluster.clj) makes a fresh `:merge-<id>` branch whose `seon.test/run` calls `reach-digests` there.

## 2. Measurements (default, JVM mode)

| probe | ms | alloc-mb |
|---|---:|---:|
| `reach-entries` main, 29 tests, 1st call (index present, test set advance) | 47.7 | 72.8 (incl. setup) |
| `reach-entries` main, 2nd call | 0.17 | small |
| `reach-entries` on chain right after (its own owner has a head) | 6.1, then 0.09 | 29.5 (whole probe) |
| COLD main: `reach-declarations` + `reached-graph {} #{}` + `reach-index` (what a new owner pays) | 128 (3 + 32 + 91) | **470.7** |
| COLD chain: same | 145 (3.6 + 39 + 97) | **476.8** |
| `reach-closure` (3B), warm graph, per call (20 calls: 647.8 MB) | 11-18 | **~32** |

So a cold rebuild is ~130-145 ms and ~470 MB for only 29 tests, independent of how few definitions differ from main. The index step
(Tarjan + SHA-256) is two thirds of the time. It is paid once per new owner: every new chain-attempt branch (first test step), every
`:merge-<id>` scratch branch, and per generation (main and chain generations differ, so a restart or reattach also resets the owner).

3B: `reach-closure` is NOT a rebuild. It is a ~32 MB / ~12 ms walk of the already-built graph (about 3 to 4 percent of the 0.9 GB);
the duplicate with `merge-seeds` is the graph walk, not the digest work. Not worth a change before 3A.

## 3. Content identity and validity

The prior should be any held index of the same program lineage, found by content. `::reach-index-by-revision revisions` already is the
content-keyed store of indexes; what is missing is a *nearby* entry when the exact `revisions` is absent. What invalidates reach is exactly
the attributes in `reach-revision-key`: a name's node depends on its `:seon.program/definition-digest`, its facts (`reach-fact-attributes`,
`:seon.fn/doc`) and, for schemas, `:seon.schema/form`; a component digest is a hash over node contents and edges (names), never over
entity ids.

Problem: `reach-changed-entities` is branch-bound (it needs the prior's `::basis` as a t on this database and returns entity ids; after a
fork, t and eids are branch-local). Remedy: diff by NAME instead of by entity. Between the prior's `::declarations` (rows name->eid,
digests eid->digest, facts, schema-ids, schema-forms) and the new database's `reach-declarations`, a name is changed when
`[digest facts...]` differ; a schema key is changed when its form differs. Reverse closure over the prior's `::reverse` as today.

Which prior: replace the `::reach-head` key `owner` by the connection's store (process scope), so "the most recently built index of this
store" is the prior, whichever branch built it; the basis check `(< ::basis (basis-t database))` goes away because nothing now depends
on t. (Both main and branch indexes are within `seon.cache`'s byte budget as today; no new cache kind.) `::reach-attribute-head` should
move the same way (key `[store attribute shape]`, diff by name when the basis is not on this database) or be dropped in favour of
`::reach-attribute` lookups by revision, which are already content-keyed.

## 4. Prototype (scratch form, no var redefined)

Prior = main's cached index (`::reach-index-by-revision` at main's revisions), new = the chain's database, changed by name as above,
`reached-graph` with `known`/`unknown` from the prior, then `reach-index`. Correctness check: the 29 digests equal the cold chain digests
(`proto-eq-truth true`).

| step | ms |
|---|---:|
| `reach-declarations` chain (revision-keyed, warm) | 3.1 |
| name diff (naive full scan of ~10k names, ~5k schemas) | 37.0 |
| reverse closure | 2.2 |
| `reached-graph` with known | 18.5 |
| `reach-index` | 33.9 |
| total | 94.6 |

Alone: 119 ms / **246.9 MB** vs cold 134-145 ms / ~470 MB: about 2x less allocation and ~35 percent less time, **on a branch that is not
a one-change branch**: the chain differs from main by 13 declarations plus 228 schemas (e.g. `:my.root/open-assignment` wrappers changed
`:merge` -> `:and`, error schemas), whose reverse closure is 2,780 of 5,190 nodes (53 percent), so 4,531 nodes were re-walked and 8,041
components stayed known. Cost follows the delta's reverse closure, as intended; for a branch forked from main with one function edited
the closure is that function's callers, and the stated floor is the name diff (reducible: diff only attributes whose revision differs,
and compare digests rather than facts first) + a graph walk of the closure + `reach-index` of the closure.

Not measured: a true one-change branch. I tried `datahike.api/with` on the chain head to fabricate one; `reach-declarations` on that
uncommitted value allocated 1.9 GB (`content-revisions` of a non-committed value; also the digest I wrote failed the contract), so I
stopped per the 1.5 GB rule. File separately: `reach-revision-key` on an uncommitted `with` value costs ~1.9 GB.

## 5. The change for Opus (runner.clj, after omb-p2 releases the merge files)

1. `reach-entries`: when `current` lacks the requested tests and the store's `::reach-head` (keyed by store identity, not branch or
   generation) holds a `::revisions` entry present in `::reach-index-by-revision`, use that index as `prior`; compute `changed` by name
   diff of `::declarations` (not `reach-changed-entities`), same reverse closure, same `known`/`unknown`. Delete the
   `(< ::basis ...)` guard, `reach-owner`'s branch and generation components for `::reach-head`, and `retire-replaced-revision!`'s owner
   key (attribute maps are already content-keyed by `::reach-attribute [attribute shape revision]`; with by-name diffing the
   `::reach-attribute-head` incremental update can derive from the held map of any revision, or be deleted).
2. Contract: `reach-owner` disappears or returns `:seon.store/store` only; `reach-entries` contract unchanged.
3. Red proof (`seon.profile/measure`, per clojure-testing, same probe parent vs change, on a branch forked from a warmed main):
   fork a branch from the test harness's captured commit, change one function through the canonical writer, call
   `seon.test.runner/reach-digests` for N seeds, assert (a) digests equal the cold ones (compute cold on a second fresh store value),
   and (b) allocation of that first call <= 25 percent of a cold full rebuild measured in the same run (today it is ~100 percent:
   470 MB cold vs same on every new branch). A count of `reached-graph` nodes visited (<= reverse closure of the edit + seeds) supports it.
   A second assertion: `candidate-gate!`'s `:merge-<id>` first `reach-digests` allocates < 10 percent of cold.

## 6. Expected gain

- `prepare-merge!` (Argon: 205 MB in `merge-seeds` + 131 MB on `:merge-<id>` = 336 MB of ~900 MB): with a store-scoped prior both reach
  builds cost the delta. On this chain (53 percent closure) the measured prototype saves about half (~225 MB per build, ~450 MB of cold
  470 MB x2 in my accounting); on a true one-change branch, close to all of it. Cause 3B (~32 MB) is separate and small.
- A new attempt's first automatic test step: today one cold rebuild (~470 MB / 140 ms for 29 tests, growing with the reached closure;
  the 7.1 GB and 2,376-test OOM cases are consistent with the cold cost scaling to the whole program and being paid again per branch and
  generation, but I did not reproduce those). With the change: the delta's reverse closure only. The 7.1 GB case needs its own measurement
  once the change lands.

## 7. Separate finding: reach on an uncommitted `d/with` value (~1.9 GB)

Reading the code during lane reach-content (2026-10-15; not fixed there). The probable cause is not `content-revisions` itself:
`seon.db/content-revisions` returns nil for a value with no committed identity (`seon.db/committed-context`), so
`seon.test.runner/reach-revision-key` is nil. Then every `seon.test.runner/reach-attribute` takes the `derive` branch with no
`::reach-attribute` cache and no incremental head, a full `:aevt` scan of each of the 11 reach attributes. There is no prior either
(no store identity), and `reached-graph` + `reach-index` run cold. Measured per full attribute scan on default (68ceef4ad, JVM mode,
read-only, thread allocation): `:seon.fn/calls` 391 MB / 128 ms (10,309 entities), `:seon.fn/keywords` 179 MB / 59 ms,
`:seon.fn/doc` 14 MB, others at most 10 MB. The total is ~600 MB per declarations build. The heavy part is `seon.db/datoms`, which
realizes every datom as a map and validates each one through its output contract (several KB per datom of the `:many` attributes).
Owners: `seon.db/datoms` for the per-datom cost, and `seon.test.runner/reach-attribute` for the full scan with no revision. The same
full scan is what a new branch still pays for each changed `:many` attribute after 6ce9dd8ad: `::reach-attribute-head` is still
keyed by `reach-owner`, because its incremental update reads `seon.db/entities-changed-after` a basis t that is valid only on its own
branch. Proposed slice: derive the changed attribute's map from the store head's held map, with the changed entities taken from
`datahike.versioning/merge-base` of the two commits (each side's `entities-changed-after` the base's t). Then `reach-owner` and
`retire-replaced-revision!`'s owner key can go.

## 8. Attribute maps by content (lane reach-maps, 2026-10-15)

The map of an attribute at revision R derives from the store's most recent held map at revision P (any branch):
P names a commit whose datoms of the attribute that map holds, so the entities to read again are those of
`datahike.api/datoms-diff` (fork `771a0c4b`) between commit P and the database, limited to the attribute's AEVT
range. No t is compared. `::reach-attribute-head` is keyed by store; `reach-owner`, `reach-changed-entities` and
`retire-replaced-revision!` are deleted (the LRU bounds superseded maps). Patch: `reach-maps.patch` (runner,
a `seon.db/datoms-diff` wrapper for the seon.db owner, red proof
`seon.test.reach-test/a-new-branchs-first-attribute-map-costs-what-it-changed`). It lands after root bumps the fork
to `771a0c4b`; it was not run: HEAD's pinned fork lacks the function, so `bin/lane commit` cannot check it.

Measured on default (JVM mode, read-only, thread allocation). The first two rows come from the JVM before root's
1a149891 restart, with warm node caches; the rest come after it:
| probe | ms | MB |
|---|---:|---:|
| full map `:seon.fn/calls` (old JVM, warm) | 26-31 | 58-69 |
| full map `:seon.fn/keywords` (old JVM, warm) | 10-13 | 22 |
| full map `:seon.fn/calls` (new JVM) | 288-322 | 300-309 |
| full map `:seon.fn/keywords` (new JVM) | 105 | 113 |
| diff, consecutive main commits (238 datoms differ), first / warm | 28 / 2.8 | 38 / 9 |
| diff, main vs `:chain-e439df9798c6` (95% of the range's leaves unshared) | 100-130 | 250 |
| re-read of 415 changed entities onto the held map | 2-90 | 1-108 |

Exact (equal to a full read, and to the plain `[e a v tx]` difference) on every pair tried. The gain holds for
a branch that is a few commits from the held map, as a chain's first step is. A long-diverged branch costs as
much as a full read. Open: in that case the walk allocates about 90 MB beyond its node reads (instrumented:
children 4 MB, node opens 57 MB cold, leaves 1 MB, merge under 1 MB); not yet found.
