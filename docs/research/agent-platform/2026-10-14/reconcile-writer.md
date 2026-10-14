---
type: research
status: deferred (root, plan §0 175906558: after the automatic live chain, S5)
---
# Reconciliation in the writer: one projection read per entity attribute

Lane reconcile-writer, 2026-10-14. Evidence it starts from: `gemini/perf-transact.md` Finding 1 and
`gemini/handoff.md` §4 (f21bdcbca).

## Reproduced (JVM mode, read-only, default)

Probe: reconcile `src/seon/fn.clj`'s own published population (169 entities: 1 file, 1 namespace,
167 functions) against the database that already holds it, so the right answer is no transaction data.

```clojure
(let [db (seon.db/db (seon.cluster.boot/connection "default"))
      ids (#'seon.fn/file-identities db ["src/seon/fn.clj"] :seon.fn/file)
      fids (into [[:seon.fn.file/relative-path "src/seon/fn.clj"]] ids)
      ents (seon.fn/published-index-entities db fids)
      shapes (seon.program/shapes-in (seon.db/carried-projection db))
      spec (:db-after (datahike.api/with db []))]   ; uncommitted, as the writer's :db.fn/call argument is
  (seon.profile/measure #(#'seon.fn/reconcile-tx-in shapes spec ents fids false)))
```

Parent, re-measured on the JVM root restarted mid-lane (loaded commit 6acf36ae…), warm:

| value reconciled | ms | alloc MB | armed calls | `seon.db/carried-projection` calls | tx ops |
|---|---|---|---|---|---|
| speculative (`d/with` []) | 673 | 75 | 52,685 | 1,768 | 0 |
| committed | 691 | 80 | 63,293 | 1,768 | 0 |
| `published-index-entities` (adoption's read) | 340 | 56 | 37,195 | 884 | — |

On the JVM before the restart the committed case took 2,637 ms: `seon.db/alias!` 1,847 ms self over
1,768 calls, `seon.cache/present` 644 ms.

**What it is proportional to:** entities × cardinality-many attributes × one projection read.
`seon.fn/normalized-index-entity` asks `seon.db/stored-collection` for every many attribute of the
current and the desired entity, and each of those calls `seon.db/carried-projection` again on the
same database value (1,764 of the 1,768 calls). **What it must do:** read the projection once, then
compare 169 entities, about 30-50 ms going by the time spent outside the projection reads.

Gemini's counts reproduce in shape: 1,768 reads for 169 entities is the 2.54M `stored-collection` →
`carried-projection` total spread over its 315 reconciliations.

## Why a projection hit costs 0.3-1 ms (seon.db, held by lane proj-content-key / projections)

- **Committed hit:** `seon.db/carried-projection` resolves the commit alias, then calls `alias!` on the
  connection's revision key on *every* hit. `alias!` compares the two canonical keys with `=`. They are
  equal but not identical, and the third member of each is the whole declaration content
  (`[::declaration-content builder <content>]`), so that `=` cost **1.04 ms** (measured directly). Patch:
  `reconcile-writer-committed-projection-hit.patch` (it resolves the revision key first, so a hit names
  nothing again).
- **Speculative hit:** the revision key misses for any `d/with` value, even an empty one, and every read
  goes through `speculative-projection`, about 340 µs: each `seon.cache/present` under a
  content-holding key whose instance is not the stored one walks the content in `equals`
  (8,840 presents, 556 ms).
- Root cause of both: **the canonical projection cache key is the declaration content itself, not a
  digest of it.** Keying it by a structural digest would make every alias, present and lookup O(1).
  That is the projections lane's subject (`declaration-content-key`); its working-tree rewrite to a
  `frequencies` map is still a deep value used as a key.

## Design decision (fn.clj)

Normalize against the projection `seon.fn/reconcile-tx-in` already holds. A private
`seon.fn/stored-collection-in` takes `[projection attribute values]` and decides vector versus set with
`seon.schema.datahike/vector-attribute-in?`, as `seon.db/stored-collection` does. `normalized-index-entity`
takes the projection, and `published-index-entities` reads its projection once. Projection reads per
reconciliation drop from 2 + 10.5 × entities to a constant. The writer still normalizes and compares
every desired entity, writes one transaction and skips no check on a changed entity.
Patch: `reconcile-writer-one-projection.patch` (against 175906558's fn.clj; it does not touch
the boot-dup-analysis hunks at 2964-3019).

Follow-up for the db.clj owner: `seon.db/stored-collection` should take the projection, not the
database. Its 6 callers in `src` (`seon.reconcile`, `seon.turn`, `seon.cluster`, `seon.render.value`,
`seon.fn`) each pay one projection read per many attribute. After that conversion,
`stored-collection-in` is deleted.

**Rejected: skip entities whose definition digest is unchanged.** A function row carries
`:seon.fn/form-span`, `:seon.fn/file`, `:seon.fn/host-bound?`, `:seon.fn/call-arities` and analysis
digests beside `:seon.program/definition-digest`. Moving a function changes its span and leaves its
digest alone, so a digest skip would leave stale facts. It is sound only once the digest is proven to
cover every owned attribute. With the projection read once, the per-entity comparison costs about
0.2-0.3 ms, and that is what the work must do.

## Red proof (not committed)

`reconcile-writer-red-proof.patch` adds task `seon.fn/reconciliation-normalizes-against-one-projection`
and `seon.fn.reconcile-cost-test/an-unchanged-population-reconciles-to-nothing-at-a-cost-independent-of-its-size`.
That deftest indexes a file of 2 functions and one of 12, reconciles each unchanged population, and
asserts no transaction data and equal `seon.db/carried-projection` counts (`seon.profile/calls-of`).

Red on HEAD + test: `bin/seon check` run **fcb3c55b8d9d** (candidate 7e10e98a4), pass 2 / fail 1:
`(not (= 13 53))`. Both populations wrote nothing, and the projection reads grew with size.

Not done: green run, and the parent-versus-change `seon.profile/measure` on the probe above (it needs
the change loaded: a branch SCI run interprets it, or a restart after commit).
Expected: speculative 673 ms → ~40 ms, published read 340 ms → ~20 ms, projection reads 1,768 → 2.
