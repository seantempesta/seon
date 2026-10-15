# Merge cost on the S5 chain (:chain-f1f6dc8e6652): measured, per operation

Lane merge-cost, 2026-10-15, read-only on default (JVM mode, HEAD cd7714c97's JVM). Chain: 26 changed identities
(8 `seon.error` functions, 1 test, 14 `my.attempt-*` namespaces, `seon.error`, `user`, `:tmp/probe`); the merge
list carries 9 of them. All numbers are `seon.profile/measure` (thread allocation) around the call; "cold" is the
first call in this JVM, "warm" the repeat. I could not re-create a cold start without evicting `seon.cache`
(a write), so cold numbers are from the first call only and the 4.6-4.8 GB reindex was not reproduced cold.

## The common cause: `seon.cache` is full, and cheap-to-keep, costly-to-recompute entries are evicted

`(seon.cache/status)` on default: 511.8 of 512 MB weighed. Held bytes by kind: `reach-attribute` 131 MB (25%),
`seon.db/projection` 110 MB, `reach-index-by-revision` 94 MB (18 whole-program indexes of about 5,600 nodes each;
45 misses, 0 hits, 20 evictions: nothing reads them through a counting lookup), `declaration-projection` 39 MB.
Meanwhile `seon.test.runner/reach-node`: 35,597 hits, 11,892 misses, 7,462 evictions at 7 MB total;
`seon.program/source-resolver-names`: 10,398 misses, 6,184 evictions at 3 MB; `seon.db/behavior-digest` weighs
256 bytes an entry. A miss of those is 0.6 MB of allocation each (below), so the weigher says "cheap to keep" while
the recompute is 2,000 times its weight. Caffeine evicts them for the 100 MB maps.

## 1. `seon.cluster.source/merge-seeds` (and the first `reach-closure` / `merge-list`)

| | ms | alloc |
|---|---|---|
| merge-seeds, cold | 3,408 | 5,297 MB, 340,149 armed calls |
| merge-seeds, warm | 512 | 562 MB |
| `seon.test.runner/reach-closure` over the seed, warm | 16 | 14 MB (4,745 names, 5,095 schema keys) |
| `seon.cluster.source/merge-list`, warm | 14 | 17 MB |

So the 2.1 s / 6.4 GB for seeds + closure and the 6.9 GB for the merge list were not those functions: they were
the one cold build of the reach graph (`seon.test.runner/reached-graph`), paid by whichever call came first.
Warm, closure and list are linear in the reached graph, as `reach-closure` documents.

Cold breakdown (self ms of 3,408): `seon.db/declaration-value` 890 (2,467 calls), `seon.program/without-docstring`
655 (2,356), `seon.program/source-resolver-names` 529 (2,356), `seon.db/behavior-digest` 319 (2,356); about 2.4 s, so
roughly 70% of the time and, taking allocation proportional to time, about 3.7 GB. The 2,356 is the reached
names that carry a docstring whose `::reach-node` content was evicted: `reached-graph`'s `node-of` calls
`seon.db/behavior-digest` for each, which on a miss reads the declaration, rewrite-clj-parses its source twice
(`without-docstring`, then `source-resolver-names`) and hashes. Micro-measured on 40 declarations (avg 665 bytes of
source), warm JIT: `declaration-value` 130 KB, `without-docstring` 214 KB, `behavior-digest` body 233 KB, plus
`source-resolver-names`; about 0.6 MB per miss, so 2,356 misses are at least 1.4 GB warm and the rest of the
measured 5.3 GB is cold JIT and the other cold work (`with-declarations` 1,658 calls, `structural-digest` 7,463).
What it should cost: the chain changed 8 functions; the reach nodes of the other 2,348 are unchanged by content,
so the cost is 8 misses, about 5 MB. Whole-program work: it is proportional to the closure of the test (4,745
names), not the delta, because the content cache that should make it so has lost its entries.

Owner of the excess: `seon.db/behavior-digest` (parse per miss; weight constant 256) with
`seon.cache` weighing (`seon.cache/lookup` weigher), not the reach walk.

Smallest fix, in order of how little it adds:
1. Stop computing `behavior-digest` from source at read time. The writer already computes the definition digest
   per declaration (`seon.program/definition-digest`, stored `:seon.program/definition-digest`); compute the
   behavior digest in the same place and store it as an attribute beside it. `node-of` then reads a datom. This
   deletes `seon.db/behavior-digest`'s cache kind, the two parses per miss, and the eviction exposure. (A schema
   attribute: needs owner approval and a publication.)
2. Without a schema change: remove the dead weight that crowds them out. `reach-index-by-revision` holds 18
   whole-program indexes with zero counted hits; `seon.cache/present` reads are not counted, so measure its real
   reads first (a `present` hit counter), then bound it to the revisions actually read (the current head's).

## 2. Reindexing `src/seon/error.clj` (`proven-files` / `proven-file`)

Reproduced with the delta the merge builds (`replacement`, `seon.db/with head`, `proven-files head merged delta root`):

| stage | ms | alloc |
|---|---|---|
| `replacement` (39 tx datoms) | 6 | 8 MB |
| `seon.db/with head` | 370 | 477 MB (self 221 ms in `seon.db/with` for a 39-datom tx) |
| `proven-files`, first | 836 | 475 MB |
| `proven-files`, second | 473 | 429 MB |

First-over-second delta (about 400 ms, 45 MB here): `seon.schema/contract-references` 4,314 calls and 8,402
`structural-schema` / `compilable-form` calls, i.e. `canonical-reference-graph` over all 8,400 schema forms
(`seon.schema/declaration-projection` is cached by `[forms predicates]`, weighed "about 340 ms / 410 MB per
build"): a projection rebuilt for the merged value because its forms differ from any cached one. This is
proportional to the schema population, for a delta that changed 8 function contracts. The merge-callees 4.6-4.8 GB
is this build plus cold per-kind misses; I measured 0.5-0.9 GB here with the schema kinds warm, so the remainder
is cold cache (see the common cause) and is not reproduced.

Warm floor 429 MB / 473 ms: `seon.db/vector-query-call` 41 calls, 144 ms; `seon.fn.analyzer/invoke-kondo` 105-325 ms
(the ordinary analyzer on one file, which is the proof); `committed-bytes` forks `git`. The 41 queries are
what to read next (which attribute scans). The proof result in this probe was a refusal ("The reindexed candidate
does not declare exactly the accepted program"): default's JVM predates cd7714c97, so the comparison here is of
cost only.

Owners: `seon.schema/canonical-reference-graph` (whole forms, called from `seon.schema/declaration-projection`)
and `seon.db/with` (221 ms self).
Smallest fix: derive the merged value's declaration projection from the head's by the changed forms only (the
projection already carries `:seon.schema.projection/schema-dependencies`, reused when `forms` are equal: extend that
reuse to "equal except these keys", recomputing the reference graph for the changed keys and their dependents,
which `dependent-schema-keys` already names). Not measured as a prototype.

## 3. `merge-base` + `own-changed-identities`

| call | ms | alloc |
|---|---|---|
| `seon.cluster.source/merge-base` cold | 377 | 415 MB |
| same, cached (keyed by two commit ids) | 0.2 | 0.006 MB |
| `merge-base-database` (opens the base commit) | 353 | 417 MB |
| `commit-database` repeated | 4 | 1.3 MB |
| `seon.program/own-changed-identities`, first | 354 | 430 MB |
| same, second | 48 | 77 MB |

Together 1.26 GB / about 1.1 s cold, 0.1 GB warm, matching merge-callees' 1.4 GB / 1 s. These are all read costs of
konserve nodes (commit graph walk, base root and its index nodes, the diff of digests for 26 identities over the
chain's own history since `:seon.branch/base-t`: 14 attempts of recordings). What they should cost: proportional
to the commits since the fork and the changed datoms, each read once; the second call shows a warm floor of 77 MB.
Commit a17790feb (konserve-read) reports merge-base 424 to 83 MB; default still shows 415 MB, so that fork bump is
not in this JVM. Expect this row to fall with root's batched restart; remeasure then. No other owner found.

## What lanes datoms-maps and reach-maps remove

Both act on `seon.test.runner/reach-attribute` derivation (`reach-declarations` mapping every datom of an attribute
into a map). In the cold `merge-seeds` run `reach-declarations`/`reach-attribute` do not appear in the top 20 by
self time and `reach-fixed-point` is 34 ms, so on this chain they would remove under 5% of the 5.3 GB (the
reach-attribute kind cost 5.7 s of load over the JVM's life across 212 misses, about 27 ms each, but not on this
chain's path). They help the reach of an uncommitted value (their stated case) and free 131 MB of cache by making the
maps incremental, which indirectly relieves the eviction above. They do not touch the dominant cost here
(behavior-digest misses, 70%), the schema projection rebuild or the commit reads (section 3).

## Ranked

1. behavior-digest per reached declaration (about 3.7 of 5.3 GB cold): store it at the writer, or stop evicting it.
2. `seon.cache` crowding: 18 whole-program `reach-index-by-revision` entries with 0 counted hits, 131 MB of
   `reach-attribute`; the common cause of all cold repeats.
3. Declaration projection rebuilt whole for the merged value (about 0.4 s, 45+ MB warm-visible, up to 0.4 GB cold).
4. `seon.db/with` 370 ms / 477 MB for a 39-datom transaction (221 ms self): unexamined, next to measure.
5. Commit reads (1.26 GB cold): konserve-read fork bump pending.
