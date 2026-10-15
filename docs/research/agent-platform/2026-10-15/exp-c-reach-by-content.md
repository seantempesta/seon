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
