# Content-derived entity ids: what they cost (2026-10-23)

A read-only study for the owner's question: *how expensive are content-derived eids, given that they fit immutability better?* The design under study: an entity with a unique identity (`:ns/name`, `:form/id`, `:var/name`, `:method/key`, `:impl/key`, `:schema/key`, `:schema.file/path`, the list at `src/seon/source.clj:184`) gets its eid from a hash of `[attribute value]`, so two branches that mint the same identity mint the same eid and their datoms coincide. Turns, evals, attempts and the rest keep the store-wide counter. This is option 2 of `next-global-ids/notes/global-ids/MEASURED.md` §7, set against option 1 (remap on merge).

**Measured** means run today: Chez arithmetic and fasl sizes on `/opt/homebrew/bin/chez`, which uses the same integer representation as Jolt's Chez, and a B+-tree model in Python (scripts in this session's scratchpad, `sim/bt.py` and `sim/aevt.py`). **Estimated** means reasoned from code or from other numbers. No Seon or Datahike store was opened.

Paths below are relative to `reference-code/datahike/` (the fork at `fd0c065`) unless they start with `src/seon` or `reference-code/`.

## 0. Verdict: moderate, and much cheaper than MEASURED §7 says

- **It is not a datom-layout change.** Eids are already 64-bit on every path we run (§1). The partition is three constants, and all ~290 uses of them are symbolic. "64-bit eids" means one more partition and one place that mints, not a new layout.
- **Lines (estimated):** Datahike about 75 lines of source and 80 of tests. Seon about 10 lines plus docs. Jolt, persistent-sorted-set and konserve: 0. Option 1 is about 40–60 lines of Seon plus proofs.
- **No migration.** With the layout in §2, every eid and tx in an existing store stays valid.
- **The cost is performance, and only on commits that create identities** (§3, modelled):
  - EAVT nodes dirtied per commit: 1.3× with 2 new identity entities, 2.8× with 10.
  - AEVT nodes dirtied: 1.7× with 2, 5.1× with 10.
  - A hashed eid takes about 10 bytes in fasl against 3–5 today, and it does not compress.
  - Reading one namespace's forms touches about 3× more leaves.
  - Commits that only update existing entities, or that add only counter entities (turns, evals), are unchanged.
- **Collision safety is fine below 2^60 without bignums:** about 4×10⁻⁷ at 1M identities and 4×10⁻³ at 100M. One check at mint turns a collision into a recorded error (§4).
- **Recommendation (§6):** content eids, built in the same Datahike branch as the global counter, since both edit `next-eid` and the partition. Option 1 only if the owner wants verbatim merges before that Datahike branch exists, and then as a stopgap.

## 1. Where the eid/tx partition lives, and what assumes it

**The constants.** `src/datahike/constants.cljc:3-6`:

```clojure
(def ^:const e0 0x00000000)
(def ^:const tx0 0x20000000)   ; 2^29
(def ^:const emax 0x7FFFFFFF)  ; 2^31-1
(def ^:const txmax 0x7FFFFFFF)
```

Also `ue0`/`utx0` (`constants.cljc`, last lines) and the system-schema tx entity `{:db/id tx0}`.

**Who uses them.** 295 matching lines in `src` (measured, by grep). Nearly all use them as symbolic slice bounds: `(datom e0 a v tx0)` … `(datom emax a v txmax)`.
- Files: `db.cljc` (18 uses), `db/search.cljc` (9), `query/execute.cljc` (60), `query/estimate.cljc` (27), `db/utils.cljc:262-263`, `index/utils.cljc:8-11`, `index_page.cljc:81,93` and `query.cljc:3822`.
- None of them does arithmetic on the bounds. A grep for `(- emax`, `(- txmax` and `(- tx0` finds nothing.
- So raising `emax` and `txmax` changes no logic. Every eid- and tx-range scan just covers more of the number line.

**The places that read meaning into a range** (these are what a new partition must touch):

| site | what it assumes |
|---|---|
| `db/transaction.cljc:82-102` `next-eid` | counter eids stay below `tx0`; raises `:entity-id/range` at `tx0` |
| `db/transaction.cljc:118-122` `advance-max-eid` | only `eid < tx0` advances `:max-eid` (so an explicit high eid does not move the counter) |
| `db.cljc:921-929` `init-max-eid` | `max-eid` = the last eavt datom in `[e0, tx0)` |
| `db/transaction.cljc:853` | `e >= tx0` means "a prior tx entity" (valid-time check) |
| `db/transaction.cljc:991-997` `entity-id` | an explicit `e > current-tx` is refused, so a hashed eid above the tx range would be refused today |
| `api/impl.cljc:178`, `query.cljc:2714` | a time-point `>= tx0` is a tx id rather than a date |
| `datom.cljc:22-23` | the sign of `tx` is the added flag (`(- tx)` for a retraction) |
| `writing.cljc:844-862` `roster-max-eid` | the store counter is seeded from `max-eid` over every head, which stays correct only while `max-eid` is counter-only |
| `src/seon/db.clj:323` `lease-use` | `(- max-eid lease)`, the same assumption |

**Datom fields and comparators.**
- The Datom type is `[^long e a v ^long tx _hash]` (`datom.cljc:16`).
- The comparators use `Long/compare` on e and tx (`datom.cljc:190-245`), and on Jolt plain compares of exact integers.
- `numeric-entid?` is `(and (number? x) (pos? x))` (`db/utils.cljc:113`).
- **No int32 packing anywhere.**
  - Datom hashing folds `(hash e)` into an int (`datom.cljc:57,99-102`). That is a hash, not storage.
  - persistent-sorted-set's Jolt overlay (`reference-code/persistent-sorted-set/src/org/replikativ/persistent_sorted_set.jolt`) packs nothing. Its only `int-array` (`:921`) holds path indices.
- cljs is the one exception: numbers there are doubles, exact only to 2^53, so cljs Datahike could not hold these eids. We never run cljs.

**On disk.**
- The JVM Fressian handler writes `e` and `tx` with `.writeInt` (`index/persistent_set.cljc:749,752`), which in Fressian is a 64-bit varint.
- On Jolt, nodes go through konserve as EDN (`#pss/leaf`) or, in Seon, the FaslSerializer (`src/seon/db.clj:84`). Both hold exact integers of any size.
- Node addresses are content hashes of the canonicalized datoms, tx included (`index/persistent_set.cljc:346-375`).

**Jolt's integers are Chez exact integers, measured:**
- `(fixnum-width)` = 61, so `most-positive-fixnum` = 2^60−1. Above that a long is a heap bignum. Jolt wraps the unchecked ops to 64 bits (`reference-code/jolt/host/chez/natives-num.ss:50-53`, `seq.ss:1307`).
- Comparing two numbers costs 3.0 ns as fixnums (near 2^40) and 6.1 ns as bignums (near 2^61): 2× slower, and each bignum is also a heap allocation.
- **Any layout must therefore keep every eid and tx below 2^60.** MEASURED's "64-bit eids" read as "tx0 at 2^62" would make every tx id a bignum.

## 2. "64-bit eids" is a new partition and one mint hook, not a layout change

**Layout B (recommended).** It keeps what exists and puts content eids on top:

| range | holds | change |
|---|---|---|
| `[1, 2^29)` | counter eids (today's, and the global counter's) | none |
| `[2^29, 2^40)` | tx ids (`tx0` stays; `txmax` 2^31−1 → 2^40−1) | one constant |
| `[2^40, 2^60)` | content eids; `emax` → 2^60−1 (`most-positive-fixnum`) | one new constant `ce0`, `emax` raised |

**Why tx stays low:**
- Raising `tx0` would put 9 fasl bytes instead of 5 on every datom.
- It would also turn every existing tx in a store into an entity.
- Under layout B, `advance-max-eid` (`< eid tx0`) and `init-max-eid` (`[e0, tx0)`) already exclude content eids, so `:max-eid`, `roster-max-eid` and the lease stay counter-only with no edit.
- No existing value changes meaning, so there is no migration.

**What changes in Datahike (estimated):**

| what | lines |
|---|---|
| `constants.cljc`: `txmax`, `emax`, add `ce0` | 3 |
| `entity-id` (`transaction.cljc:991`): also accept `ce0 <= e <= emax` | 3 |
| `:853`: the tx-entity test becomes `tx0 <= e <= txmax` | 1 |
| `content-eid`: `ce0 + (hasch over [a v], 60 bits, mod (2^60 − 2^40))`, with `hasch` already a Datahike dependency (`deps.edn:3`, `writing.cljc`) | ~10 |
| a pre-pass over tx-data that assigns each tempid whose entity carries a content-identity attribute its content eid, before `entity-map->op-vec` (`:1019-1020`) or the op-vec path (`:1389`, `:1395`) can hand it a counter eid | ~30 |
| the identity invariant and collision check (§4) | ~20 |
| which attributes: a schema flag or config key, read where `:db.unique/identity` already is | ~10 |
| tests (red → green: same identity on two branches gives the same eid and a clean verbatim merge; collision is an error; a rename is refused; retract then re-mint) | ~80 |

The pre-pass is needed because a tempid named as a ref value before its entity map is allocated from the counter at `:1395`. Seon writes such tempids (`src/seon/program.clj:391-399`: `ns-tid`, `var-tid`).

**What changes in Seon (estimated):**
- About 10 lines: the attribute list at `src/seon/source.clj:184` becomes the config key or schema flag.
- `seon.merge`'s "entity ids never matter" docstrings (merge.clj:166–260, per the eid audit).
- docs/storage.md.
- **Minting needs no Seon edit.** Seon mints every identity through a string tempid plus the identity attribute in an entity map (`program.clj:398-399`, `source.clj:123,154`), which is exactly the path the Datahike hook covers.

**Jolt, persistent-sorted-set, konserve: 0 lines.** Everything stays a fixnum, and nothing packs widths.

**Where it meets the global counter.**
- Attack note §2.1 proposes one counter for eids and tx ids alike. In one number range, that already conflicts with the `tx0` partition, which needs eids and txs in separate ranges.
- Both changes edit `next-eid` and the partition, so they belong in one Datahike branch with one layout decision. Two counters (eid and tx), or one counter mapped into the two ranges, is a choice that branch has to make anyway.

## 3. Performance: locality, with numbers

**Why random eids cost more.**
- EAVT sorts by e first, so a sequential eid appends at the tail and a hashed one lands in a random leaf.
- AEVT sorts by attribute, then e. Sequential eids append at the tail of each attribute's run, so all of a commit's new entities share about k tail leaves (k = attributes per entity). Hashed eids touch up to k leaves per entity.
- AVET (a, v, e) is ordered by value and unaffected.
- In a copy-on-write tree that keeps history, every dirtied leaf plus its path to the root is a new immutable node, kept forever. So write amplification here is also storage growth.

**The model (measured).**
- A copy-on-write B+-tree at Seon's `:seon.config.store/branching-factor` 64 (`resources/seon/config/default.edn:101`), splitting at half, built to N datoms. Then 200 commits, each adding m new entities of 25 datoms. The model counts distinct nodes dirtied per commit.
- The sequential tree's fill (29–32 datoms per leaf) matches the real store: MEASURED §2 found 115k datoms in 3,704 nodes, 31 per node.

| index, N | new identity entities per commit | sequential: nodes/commit | hashed: nodes/commit | ratio |
|---|---|---|---|---|
| EAVT, 115k | 2 | 5.6 | 7.1 | 1.3× |
| EAVT, 115k | 10 | 12.1 | 28.3 | 2.3× |
| EAVT, 1M | 2 | 5.6 | 8.1 | 1.4× |
| EAVT, 1M | 10 | 12.1 | 34.5 | 2.8× |
| AEVT, 115k (25 attributes) | 2 | 54.6 | 92.2 | 1.7× |
| AEVT, 115k (25 attributes) | 10 | 61.9 | 315.9 | 5.1× |

- **Extra nodes per merge that adds forms.**
  - Two new forms cost about 40 more nodes than sequential, ten new forms about 270.
  - **Estimated** at 2–3 KB and roughly 50–100 µs to print, compress and put per node (the write mirror of MEASURED §2's 65–75 µs cold read): +2–4 ms and about 100 KB for 2 forms; +15–25 ms and about 600 KB for 10.
  - Against MEASURED's 9.3 ms verbatim merge, that is real but bounded.
  - A merge's updates to *existing* forms scatter today too (existing eids), so the extra share of a realistic merge is smaller than this table.
- **One identity entity per commit costs about the same either way.** A sequential append dirties k tail leaves in AEVT and a hashed insert dirties k random ones. The amplification grows with the number of new identities in one commit.
- **The tree is smaller with hashed eids.** Random inserts fill leaves to 38–41 datoms against 29–32 for appends, so there are 23% fewer leaves (3,005 vs 3,906 at 115k). That partly offsets the write cost in space.
- **Bytes per eid (measured, Chez fasl, uncompressed):**
  - A counter eid near 2^17 takes 3 B, a tx near 2^29 takes 5 B, and a hashed eid in `[2^40, 2^60)` takes about 10 B.
  - With fasl compression on, a run of sequential ints compresses to about 4 B; hashed eids stay at 11 B.
  - Every occurrence pays about 7 B more: the e of each of the entity's datoms in each index, and the v of every ref to it.
- **Read locality (estimated).**
  - A namespace of 30 forms × 10 datoms is about 10 contiguous leaves today and about 30 when hashed (one per form): 3× the node reads.
  - At 65–75 µs per cold read that is about 1.4 ms more for one namespace cold, and nothing once warm.
  - It does raise the working set against the 1,000-node default cache (MEASURED §3), which needs sizing anyway.
- **The hash itself (estimated, not measured on Jolt):** a `hasch` digest of `[a v]` costs tens of µs, once per new identity. That is negligible next to the merge.

**How others do it** (from their published designs as known; not re-checked on the web today):

| system | how it handles identity and locality |
|---|---|
| Datomic | Eids are counter-based 64-bit longs with partition bits on top. It hashes only to choose a partition (implicit partitions), and does so to *group* related entities for locality. Identity merges by upsert on one transactor, so it never has two writers minting the same identity. |
| XTDB | Hashes the user's id into its index key (SHA-1, 20 B, in v1; a hash-based 16 B IID in v2) and accepts the scatter. Its LSM store (RocksDB, then its own trie) absorbs random writes in sequential runs. |
| git, IPFS | Content addresses are 160–256 bits, with no collision check: the bits are the safety. Locality comes from packfiles ordered by path and type, and from the DAG shape, never from the keys. |

The lesson from all three:
- Content addressing is normal, but at 128 bits or more, and on stores that absorb random writes.
- A copy-on-write B+-tree that keeps every version, like ours, is where random keys cost most.
- Datomic's own answer to "related entities should be near each other" was to hash into *partitions*, not into ids. A namespace-prefixed hash (high bits from the namespace, low bits from the identity) would keep a namespace's forms together. But it splits the collision space per namespace (about 3.6×10⁻³ for 500 namespaces of 2,000 entities on 38 low bits), so it is not recommended unless locality turns out to matter.

## 4. Collision safety at 60 bits

The birthday bound is p ≈ n² / (2·space).

| hash space | n = 23k | n = 1M | n = 100M |
|---|---|---|---|
| below 2^29 (today's eid range) | ~0.4 (MEASURED §7) | 1 | 1 |
| `[2^40, 2^60)` ≈ 2^60 (layout B, fixnums) | 2×10⁻¹⁰ | 4.3×10⁻⁷ | 4.3×10⁻³ |
| 2^62 (bignums, not recommended) | 6×10⁻¹¹ | 1.1×10⁻⁷ | 1.1×10⁻³ |
| 2^64 | 1.4×10⁻¹¹ | 2.7×10⁻⁸ | 2.7×10⁻⁴ |

**How many identities Seon will mint (estimated).**
- `:form/id` changes whenever a form's text changes (`src/seon/program.clj:294-298`), so every edit mints a new identity.
- Main holds about 5k forms (115k datoms / ~25 each). At hundreds of attempts a week, a lifetime of 10⁶ identities is generous, which gives about 4×10⁻⁷.

**The one check: identities are immutable.**
- When an identity datom `[e a v]` is asserted on a content eid, look up `[e a]` in eavt. That is one seek: 7 µs warm, 34 µs over history (MEASURED §4).
- If e holds a different value of a, the transaction is refused with a declared error, say `:entity-id/collision`, naming both values. It is recorded, never merged silently.
- This one check covers three cases:
  1. **A collision at mint:** a new identity hashes onto an existing entity.
  2. **A collision carried by a verbatim merge.** Without the check, a card-one identity datom would silently replace the other value: the dangerous case.
  3. **A rename** (§5).
- **A collision cannot be recovered by probing.** Something like `hash(a, v, 1)` is not deterministic across branches, because branches see different occupants. A collided identity therefore stays an error that the owner resolves, at about 4×10⁻⁷ over the lifetime.

**Side finding.** `:form/id` is already a content address, built on Clojure's 32-bit `hash` of the namespace plus the text, plus the length (`program.clj:298`).
- Two different forms in one namespace, of the same length, with the same 32-bit hash, would silently be one form today.
- That is very unlikely per (namespace, length) bucket, but the same check, or a wider hash, would close it.
- `hash` is also not specified to agree across hosts.

## 5. Retraction, re-creation and renames

**Retraction: yes, and better than option 1.**
- A branch's retraction `[h a v tx false]` names the same eid on main, so a verbatim merge applies it with no remap.
- Under option 1, every carried retraction of a remapped entity has to be remapped too. It is one more case the rule must cover, and a missed one is a retraction that silently does nothing.

**Re-creation after a retraction: the same eid.**
- A retracted namespace that is created again gets its old eid back, so history shows one entity with two lives.
- In Datomic, upsert after a full retraction mints a new eid. Here the identity *is* the name, like a path in git.
- For code that is the right reading: "namespace `foo`" is one thing over time. As-of queries are unaffected.
- `retractEntity` also retracts incoming refs, so no current ref resurrects onto the new life.

**A changed identity value (a rename) must be a new entity, and the check refuses an in-place change.**
- In Seon's domain this is already true:
  - Renaming a namespace renames every `:var/name` in it (`a.b/f` → `c.d/f`).
  - Every `:form/id` starts with the namespace (`program.clj:298`).
  - So a rename is a new namespace with new forms and vars, as in Clojure, where a renamed namespace is a different namespace.
- The referrers must be re-pointed, which a rename re-transacts anyway.
- The one rule this adds: identity values are immutable. It is a true property of these seven attributes, enforced by the §4 check, and not a merge rule.
- **To verify before building:** that no Seon path updates an identity attribute in place. A grep of `merge.clj`, `program.clj` and `source.clj` found only creation through tempids (`source.clj:123,154`; `program.clj:398-399`), but this was not exhaustive.

## 6. Recommendation, set against option 1

| | option 1: remap on merge | content eids (layout B) |
|---|---|---|
| lines | Seon ~40–60 + proofs; Datahike 0 | Datahike ~75 + ~80 tests; Seon ~10; Jolt/pss/konserve 0 |
| rules | one merge rule: re-point the entity's datoms and its refs, and its retractions; on merge-main as well | none in the merge; one invariant (identities are immutable) in the transactor |
| union U / history | two eids for one form across branches (true as history, noisy as a current union) | one eid; datoms coincide; and the node addresses of identical subtrees can coincide too (content-addressed nodes, `persistent_set.cljc:346`) |
| `datoms-diff` after merge-main | remapped datoms differ, so the doubled delta returns for those entities (attack §1.10) | identical datoms, so no doubling |
| write cost | none extra | 1.3–2.8× EAVT and 1.7–5.1× AEVT nodes on commits that add 2–10 identities; +7 B per hashed-eid occurrence |
| read locality | today's | about 3× the leaves for a namespace's forms when cold |
| migration | none | none (layout B keeps every existing value valid) |
| failure | a missed ref or retraction remap is silent | a collision (about 4×10⁻⁷ at 1M) is a recorded error |

**Build content eids.**
1. Build them in the same `jolt/<topic>` Datahike branch as the global counter of attack §2.1. Both rewrite `next-eid` and the partition (`transaction.cljc:82-122`, `constants.cljc:3-6`), and doing it twice is the waste.
2. Take layout B: `tx0` unchanged, `txmax` at 2^40−1, content eids in `[2^40, 2^60)`, everything a fixnum.
3. The mint is the tempid pre-pass in the transactor, so Seon's minting code is untouched.
4. The one check is identity immutability (§4).
5. Its cost proof is `nodes dirtied per commit` against the number of new identities, red when it is steeper than the table in §3. Its measurement is to re-run MEASURED §5's verbatim merge with 2 and 10 new forms.

**Option 1 is the stopgap** only if verbatim merges must merge before that Datahike branch. It is cheaper today, but it is a rule with silent failure modes (refs, retractions, merge-main), and content eids delete it later.

**Not measured:**
- The real Datahike commit cost per extra node on Jolt (estimated above at 50–100 µs and 2–3 KB).
- The `hasch` cost on Jolt.
- The real attribute count per Seon form entity (the model used 25; fewer attributes shrink the AEVT ratios).
- Whether the persistent set's diff buffer (`:diff-buf-size`; branch-slot buffering, `persistent_set.cljc:266-275`) absorbs some of the scatter. Seon runs with whatever the store records. Measuring it means building a store, which this read-only study did not do.
