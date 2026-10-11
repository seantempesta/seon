---
title: "Per-branch entity-id ranges: Opus assessment"
type: research
status: assessment, design only; recommends identity matching as a squash merge, no eid change
basis: reference-code/datahike b2ffa4af read by symbol; scratch in-memory Datahike probe in default JVM (store untouched); seon.cluster.source/merge-base
---

# Per-branch entity-id ranges — Opus assessment (2026-10-11)

Question (owner): why not make eid allocation unique across branches, so an entity has one eid on every branch and the
copy-back replays datoms unchanged? Assessed in `reference-code/datahike` at `b2ffa4af`, probed in a scratch in-memory
Datahike db in default's JVM (`eval_clj` jvm mode; default's store untouched). Independent of the Sol assessment.

**Answer.** Feasible, but it is a cross-cutting fork change with a silent-corruption failure mode, and it removes almost
nothing from Seon: identity matching stays required for the one case that actually conflicts. Recommend identity
matching, framed as a squash merge from the merge base (below), and no eid change.

## What the code does today

- **Eid space** (`datahike.constants`): `e0` 0, `tx0` 0x20000000, `emax` = `txmax` 0x7FFFFFFF. Entities live in
  `[ue0, tx0)` (2^29 ≈ 537 M), transaction entities in `[tx0, txmax]`; both share the `e` column. `Datom.e` and
  `Datom.tx` are `long` (`datahike.datom/Datom`; the sign of `tx` is the added flag).
- **Allocation**: `datahike.db.transaction/next-eid` = `(inc (:max-eid db))`; `current-tx` = `(inc max-tx)` of
  `:db-before`. `advance-max-eid` raises `max-eid` for ANY datom whose `e` is in `(max-eid, tx0)`, called from
  `with-datom` and the history path, i.e. explicit eids drag the allocator. `init-max-eid` (load, import) takes the last
  `e` below `tx0` in `:eavt`; `get-max-tx` the max tx.
- **Range bounds**: every full or prefix scan bounds `e` by `e0`/`emax` and `tx` by `tx0`/`txmax`:
  `datahike.db.utils/components->pattern` callers in `datahike.db` (`-slice`, `-seek-datoms`, `-rseek-datoms`,
  `-index-range`), `datahike.db.search` (generated lookup patterns, `lower-datom`/`upper-datom`), `datahike.query`
  (`:aevt` ground-value scans, time-point checks), `datahike.index-page`. Datahike has no partition concept.
- **Stored db record**: `max-eid` and `max-tx` are written into every commit/branch head (`datahike.writing` stored keys,
  `db->stored`, `commit-id` fallback `[hash max-tx max-eid meta]`), `datahike.transit`/`datahike.json`,
  `datahike.remote/RemoteDB`, `datahike.codegen.pod`. `datahike.migrate/update-max-tx` recomputes on import.
- **Branching**: `datahike.versioning/branch!` copies the stored head (`assoc-in [:config :branch]`), so `max-eid` and
  `max-tx` are copied; both lineages then count up from the same numbers. **`merge!`/`merge-writer!` computes no diff**:
  it is `transact!` of the caller's tx-data plus `:datahike/merge-parents` on the meta (a multi-parent commit).
- **Planner/other**: `datahike.query` caches plans by `(size-class (dbi/-max-eid db))`; `datahike.index.entity-set`
  casts eids to `int` (RoaringBitmap); `datahike.experimental.graph` keys by `max-eid`. Node caches, hashes (`hash e`)
  and fressian (varint longs) are width-agnostic.

## Probe (exact results)

Schema `:n` string unique-identity, `:r` ref; `branch! :db :b1`; one new entity on each side:

| step | result |
|---|---|
| `{:db/id "x" :n "main-1"}` on main, `{... "branch-1"}` on `:b1` | both eid **3**; both tx **536870914** (eids and tx ids collide) |
| main `[:db/add 0x10000001 :n "explicit-lo"]`, then a new entity | next eid **268435458**: one copied ranged eid moves main's allocator into that range |
| branch `[:db/add (7<<32 \| 1) :n "explicit-hi"]` | **accepted silently**; `max-eid` unchanged (treated like a tx id) |
| that entity via `d/entity` by eid, pull, `[?e :n ?v]` with `?e` bound | found |
| `(d/q [:find ?e ?v :where [?e :n ?v]])`, `:aevt` datoms, `:eavt` full scan | **missing** (beyond `emax`) |
| lookup ref `[:n "explicit-hi"]`, `?e` from a bound value | **nil / #{}** — the unique-identity index cannot see it, so the next upsert of that value makes a duplicate |

So eids are already 64-bit in memory and on disk, but every scan and the tx/entity split assume 31 bits. Using high
bits without changing the fork corrupts identity silently.

## The fork change (sketch)

1. **Partition id per branch**, minted at `branch!` from a monotonic counter in the store beside `:branches` (never
   reused, even after `delete-branch!`: merged entities keep their eids on main forever). Main is partition 0 = today's
   `[ue0, tx0)`, so **existing stores need no migration**; a stored head without `:partition` reads as 0.
2. **eid = p·2^32 + local** for p ≥ 1 (local < 2^32), leaving tx ids where they are. `emax` → `Long/MAX_VALUE`
   everywhere it bounds `e` (all `components->pattern` callers, `datahike.db.search` generated bounds, query, index
   page); `txmax` unchanged in the tx position.
3. **`max-eid` means "max local eid in my partition"**: `next-eid`, `advance-max-eid` (advance only for eids in the own
   partition; replace the `(< eid tx0)` tx test with an explicit "is a tx id" range test), `init-max-eid` (seek the
   own partition's last `e`), the planner's `size-class`, `entity-set` (Roaring64), `experimental.graph`.
4. **Stored record**: `:partition` added to the stored keys in `datahike.writing`, transit/json/remote/pod.
5. Tx ids still collide across lineages; that is fine (a copy is a new transaction on main), but tx-entity refs
   (provenance) never copy by number.

Size: ~15 call sites across 10 namespaces plus tests; a day of Opus work with Astra review. Index ordering: branch-born
entities sort after all of main's in `:eavt`/`:aevt` (good locality per branch, ~1 extra fressian byte per `e`);
`:avet` is unaffected. Bit split: 2^31 partitions × 2^32 entities each on the JVM (CLJS, if ever revived, caps at 2^53:
2^21 partitions). Partitions are consumed by every branch, test branches included, so the counter, not the width,
is what to watch — 2^31 is ample.

## What it would simplify in Seon — less than it seems

- **Entities that existed at the fork already have one eid on both sides today.** Changes to them (task intent,
  occurrence count, an attempt's end) already conflict-check and replay by `[e a]`. Ranges change nothing there.
- **Entities born on the branch** with no counterpart on main: ranges let their datoms and refs replay unchanged. But
  the P1 design already writes them as entity maps keyed by identity with **refs as lookup refs**, which Datahike's own
  upsert resolves in the same transaction: no remap table exists to delete.
- **Entities born on both sides with the same identity** (two agents hit the same error signature and each mint an
  occurrence; two refinements mint the same `ns/name` child task): under ranges they get two different eids, so a
  verbatim replay **violates `:db.unique/identity` on main** (or, past `emax` today, silently duplicates). They must be
  matched by identity anyway — and this is exactly the case conflict resolution exists for. Eid ranges never decide a
  conflict that identity cannot, and identity decides every one eid can.
- Also risked: divergence from upstream Datahike on its most basic invariant, a planner/scan bug class that fails as
  missing rows rather than errors, and every Seon caller that hard-codes eid assumptions.

## The copy-back as git's common case: a squash merge from the merge base

The owner's framing fits, and P1 is already most of it: base = the branch `as-of` its fork t (on the branch's own
lineage, since tx numbers collide across lineages — probe above), theirs = the branch head, ours = main now; three-way
per `[identity attribute]` filtered to `seon.program/cluster-attributes`; conflicts only where both sides changed the
same attribute of the same identity; applied to main as **one ordinary `seon.db/transact!`** with provenance in
`:tx-meta`. It must NOT be `datahike.versioning/merge!`: that records `:datahike/merge-parents`, which would keep the
deleted branch's commits reachable for `datahike.gc` — the squash's whole point is no parent link. Rename the operation
from "cherry-pick" to **squash merge** in the vocabulary (git: `merge --squash`; cherry-pick replays individual commits,
which this does not do).

**Reuse of merge-request machinery.** `seon.cluster.source/branch-base-t` and `merge-base` (fork entity via
`seon.cluster.registry/fork-of`, base-t advanced per merge by `registry/advanced-base-t`) are partition-independent and
should be called as-is. `seon.cluster.source/compare-branch` and `seon.program/three-way` classify the **program**
partition by definition digests (`program/changed-identities`, `program/digest-map`). The squash merge needs the same
three maps for the cluster partition: generalize `changed-identities`/`digest-map` over a projection (program
attributes or cluster attributes) so merge requests and the end copy are one comparison with a projection argument,
rather than a second three-way in `seon.cluster.pick`.

**With per-branch eid ranges**, the squash would replay branch-born datoms with eids unchanged and could key conflicts
by `[e a]`; without them, it keys by `[identity a]`. Both yield the same transaction for every non-colliding entity,
and only identity handles the colliding one.

## Recommendation

Do not change eid allocation. Keep identity-attribute matching (P0 already requires an identity on every
`:seon.cluster` entity), rename P1 to the squash merge, reuse `branch-base-t`/`merge-base`, and generalize the
program three-way over a projection instead of writing a second one. Two findings to file regardless:
`datahike` accepts an explicit eid ≥ `tx0`/above `emax` silently and then hides it from scans and identity lookups (the
transaction should refuse it), and an explicit eid below `tx0` advances `max-eid` — so no Seon path may ever transact a
copied numeric eid; P1's proof table should include that row (`seon.cluster.pick-test/…-never-transacts-a-numeric-eid`).
