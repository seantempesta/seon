# Global ids: Phase 1, measured (2026-10-23)

These are the seven measurements that `notes/cross-branch-attack-2026-10-23.md` (§4) asks for before the store-wide counter, verbatim merges and the union-as-history db U are built. Each number is **measured** unless it says otherwise, and each says how. Everything ran in worktree `next-global-ids` under `nice -n 15`, on stores of its own under `target/m/`: never `next/runs/store`.

The scripts are in `notes/global-ids/bench/`. `run.sh FILE` runs a script on this checkout's Jolt with the test classpath. `mk.clj` and `mkh.clj` make the stores.
- **Store `calc`:** `seon e2e calc` with the scripted provider. 9 attempts, 4 merged, history kept. Main is 2,961 datoms, or 3,026 with history.
- **Store `big`:** `seon.db/open!` with seed 50,000, giving main `:db` 114,847 datoms. It has branches `b0`–`b9`, each 5 commits of 2 new forms (~50 datoms), and branch `:hist`, where every form's card-one `:form/order` is replaced 3 times: 114,907 current datoms, 169,387 with history.

## 0. Verdict

- **The design still meets its three bounds:** query O(log N + k), upkeep O(Δ) per commit, a branch view in O(1). The counter (O(1)) and the merge (O(Δ)) meet theirs too.
- **The constants are 3–10× the estimates,** which matters at scale (§2, §7).
- **Five findings change the plan:**
  1. Two Jolt defects make it fail outright today: `datoms-diff` and planned `d/history` queries (§A).
  2. The node cache is too small (§3).
  3. Today's merge is O(program), not O(Δ) (§5).
  4. Verbatim merges need one rule for an identity two branches both mint (§7).

| bound | estimate (note) | measured | meets? |
|---|---|---|---|
| query over U, per index access | O(log N + k), `d/history` within 2× of current | eavt seek: 34 µs over history vs 7 µs current; returns 2.2× the datoms (11 vs 5). A literal 3-clause point query: 191 vs 162 µs (1.2×) | **yes**, O(log N + k); k counts superseded versions |
| U upkeep per commit | Δ = 100 in ~0.3 ms | Δ = 10: 0.30 ms; Δ = 100: 2.7 ms; Δ = 1,000: 28 ms (27 µs per datom over 3 indexes, linear) | **yes** asymptotically; the constant is 9× the estimate |
| opening a branch view | O(1), 0.7–0.9 ms | `seon.db/head` 0.9–1.3 ms on `calc` | **yes** |
| one id | O(1), tens of ns | mmapped fetch-add: 28–34 ns alone, 200–240 ns with 10 processes contending | **yes** |
| a merge | O(changed datoms) | verbatim: 9.3 ms. Today's re-transact: **6,677 ms**, at the same Δ, on a 115k main | verbatim **yes**; today **no** (§5) |

## 1. The size of an agent's changes

How: `bench/m1.clj` on `calc`. For each attempt branch it walks the branch's own commits (`datahike.versioning/commit-graph-record`, `:datahike/branch` = the branch) back to its fork commit. Δ is the branch's history datoms that the fork commit's history lacks.

| branch | own commits | Δ asserted | Δ retracted | value bytes (pr-str) | `datoms-diff` (fork, head), current eavt |
|---|---|---|---|---|---|
| task-calc-add (1 turn) | 4 | 71 | 0 | 3,813 | 1.32 ms (8 only-fork, 70 only-head) |
| task-calc-twice (1 turn) | 4 | 71 | 0 | 3,895 | 1.31 ms |
| task-calc-sum-sq (1 turn) | 4 | 72 | 0 | 4,078 | 1.23 ms |
| task-calc-square (2 turns) | 6 | 127 | 0 | 6,259 | 2.36 ms |
| task-calc-neg (cancelled), agent-10 | 0 | 0 | 0 | 0 | 0.03–0.06 ms |

- **Measured (scripted provider):** about 55 datoms and 2.4 KB of values per turn, plus about 15 datoms per attempt. The Δ retracted column is 0, because Datahike's datom `=` compares e, a and v only, so a replaced value shows up as `only-fork` in the diff instead.
- **Estimated, for real model runs:** the same record shapes with larger values. The corpus of the standing store's 85 real attempts (`notes/corpus-2026-10-22.md`) has a median of 1–2 turns and a maximum of 27, so Δ is about 70–130 datoms for a typical attempt and about 1,500 for the longest.
  - The note assumed 2,000 per live agent, so the typical attempt is about 15× smaller.
  - Real-model runs were not made: paid runs need the owner's go.
- **The lease spends ids, not datoms.** Each attempt's eids jump by a lease block (100,006 to 300,014 per branch). Calc's 9 attempts took main's `max-eid` to 900,594, which is the 5,368-agent ceiling at work.

## 2. Per-node read cost on Jolt

How: `bench/m2.clj` on `big`, a full `:eavt` scan of a branch head in a fresh process (3,704 leaf and branch nodes), with `CachedStorage`'s `:reads` stat counting store reads.

- **Cold:** 65–75 µs per node read: LMDB read, zstd decode, jfasl read, restoring values out of line. The note estimated 20–50 µs.
- **Seeks:** 200 random cold eavt seeks cost 105 µs each, reading 1.6 nodes each, so 65 µs per node read. Warm, they cost 8.6 µs each.
- **Full scan:** a scan of 115k datoms through a cache that holds them takes 26 ms, against 250–280 ms cold.

## 3. Does the node cache share nodes across branch dbs?

How: `bench/m2.clj share`, scanning `b0`, then `b1` through the same connection, then `b2` through its own `d/connect`.

- **Yes, by address, across branch dbs and across connections to one store** (`datahike.index.interface/node-cache-key`, reserved per physical store in `datahike.connector`).
  - After `b0` was scanned, scanning `b1` read 8 nodes from the store (its own unshared paths) and took 27 ms.
  - `:db` read 6 nodes.
  - `b2`, on its own connection, took 33 ms with no reads.
- **But the default cache holds 1,000 nodes** (`datahike.config/*default-store-cache-size*`), and Seon sets no size. A scan of one 115k-datom index (3,704 nodes) thrashes it. A second scan of the same branch read all 3,704 nodes again: warm cost 270 ms, the same as cold.
  - So multi-source queries and U, whose working set is several thousand nodes, run at cold cost on the default cache.
  - **Design change:** a config key for the cache (`:store-cache-bytes`, Datahike's byte budget) sized to the working set, inside `:seon.config.machine/memory-mb`.

## 4. The cost of `d/history` queries at realistic size

How: `bench/m4.clj` on `big`'s `:hist` (115k current, 169k with history), warm (cache 200,000 nodes), 200 runs after 20 warm-up runs, on plain and `d/history` dbs. Both are planner-eligible (`datahike.query/planner-eligible-db?` → true).

| query | db | `(d/history db)` |
|---|---|---|
| literal point: `[?v :var/name app.m33/f7] [?v :var/form ?f] [?f :form/src ?src]` | 162 µs | 191 µs |
| the same with `?n` bound through `:in`, plus `:form/order` | **8,062 µs** | 291 µs |
| one namespace's 20 forms and orders (`:in ?n`) | **7,398 µs** | 817 µs (80 rows) |
| a 5-clause join over one namespace | 994 µs | 4,340 µs (80 rows) |
| 1,000 eavt seeks (one entity's datoms) | 7.0 µs each (5 datoms) | 34.3 µs each (11 datoms) |
| 1,000 avet seeks (`:form/id`) | 27.1 µs | 17.7 µs |

- **History costs 1.2–4.4× current per query.** Per index access it stays O(log N + k), and k includes superseded versions. That is within the note's 2× target for point queries, and above it for joins over entities with many versions.
- **Not U's cost: an existing planner defect.** On a plain db, a query with a scalar bound through `:in` runs 8 ms where the literal form runs 162 µs. That is 50× slower, and the history form does not suffer it. It deserves a task of its own.

## 5. A verbatim merge against today's re-transact

How: `bench/m5.clj` on `big`. Branch `b0` (or `b1`), 10 new forms in 5 commits, merges into a fresh branch of `:db`. Main has not moved since the fork, so the base is the fork commit.

| | today: `seon.merge` (as `seon.writer/octopus-merge`) | verbatim: `datoms-diff(base, head)` as Datoms into `d/merge-db` |
|---|---|---|
| open base and head | 2.7 ms | 1.8 ms |
| decide what merges | **6,584 ms**: `pm/program` of base, head and main (115k datoms each) | 2.0 ms (the diff) |
| tx-data | 66 ms: `three-way` + `apply-tx` → 11 entity maps | 50 Datoms |
| commit | 23.5 ms (42 datoms written) | 5.5 ms (51 datoms written) |
| **total** | **6,677 ms** | **9.3 ms** |
| new forms' eids on main | **differ from the branch's** | the branch's |

- **Today's merge is O(program).** It reads three whole programs per merge, which only matters because the calc store's main is 3k datoms. At 115k it is 6.7 s. Verbatim is O(Δ).
  - **Design change:** the three-way's *what merges* must also come from the deltas (`datoms-diff(base, head)` and `datoms-diff(base, main)`, by entity), not from `pm/program` of three whole dbs. Otherwise the merge keeps the O(program) cost.
- **The tx-id collision (attack §1.5), shown.**
  - Main's `max-tx` before the merge was 536,871,822, so the merge took tx 536,871,823. The branch's own first tx was also 536,871,823.
  - The carried branch datoms of that tx therefore became the merge's own.
  - The one extra datom written was the merge's `:db/txInstant` on that same tx entity, a second instant on one tx (card-one), so the carried `:db/txInstant` was replaced.
  - The store-wide counter removes this: the merge's tx is newer than every carried tx.
- **Datahike already takes the branch's tx on a carried datom.** The 50 carried datoms were written with their own tx ids (`datahike.db.transaction`, :835).

## 6. The counter's cost per allocation across processes

How: `bench/counter.c` (`seon_counter_open`: open, `ftruncate` to 4,096 B and `mmap MAP_SHARED`; `seon_counter_add`: `__atomic_fetch_add`) is built as a dylib and called through `jolt.ffi` (`bench/m6.clj`, `bench/m6b.clj`). The LMDB variant reads, increments and puts a counter key in one write transaction, on an env of its own with `MDB_NOSYNC`. The contended runs start 10 processes, which wait on a file before starting.

| | alone | 10 processes at once |
|---|---|---|
| mmapped word, atomic fetch-add | 28–34 ns per id | 197–240 ns per id (3M ids each; 30M in all; max id 29,999,999: none lost, none repeated) |
| LMDB counter key, one write txn per id | 9.6 µs per id | 494–508 µs per id (20,000 ids, all distinct): the writer lock serializes them |

- **The mmapped word is about 300× (alone) to 2,000× (contended) cheaper.** It is the counter to build.
- **Not measured:** what an OS crash leaves of the page. It is not needed, because the design reseeds at root's boot as `max(counter, max-eid/max-tx over every head) + 1` (attack §2.1). An id handed out but not committed is a gap, never a reuse.

## 7. What breaks in Seon when merged forms keep their branch eid

How: a read-only audit by a subagent (`notes/global-ids/eid-audit.md`, 197 lines, with file:line for every finding). Its main points:

- **The merge's own code assumes new eids on main.**
  - `seon.merge/def-ref`, `set-form-tx`, `set-def-tx` and `apply-tx` (merge.clj:166–260, "entity ids never matter") upsert by identity with tempids.
  - Under verbatim merges `apply-tx` needs the source db, and the octopus merge needs a source per key (writer.clj:96–100).
  - `program/divergence`'s docstring and docs/storage.md "Compiled code" describe the old rule. `pdb/lease-use` becomes wrong.
- **A bug today that verbatim merges fix.** Root merges with `:data-attrs :all` (root.clj:834), and `set-datum-tx` copies ref values such as `:task/refs` unchanged. A ref to a var that `apply-tx` re-minted on main therefore points at an eid main does not have.
- **Two branches minting the same unique identity** (the same new `:ns/name`, `:form/id`, `:var/name`, method, impl or schema key):
  - A verbatim merge of the second branch is refused by `validate-datom-upsert` (`:transact/unique`, transaction.cljc:574–581), and the whole octopus merge with it.
  - The writer answers with no `:results` (writer.clj:219), and reconcile.clj:660 then ends no attempt, so they stay `:running`. That is a hang.
  - It is common: two tasks that each add forms to the same new namespace both mint its `:ns/name` entity.
  - **This is the one rule verbatim merges need, and it changes the design** (choices below).
- **t comparisons across branches are safe.** `merged-t`/`basis-t` (reconcile.clj:289), `:seon.merge/main-t`, the fork server's t and `misaligned` all compare main's own t. `max-tx` stays a per-branch high-water mark.
- **Card-one.** `set-def-tx` relies on card-one upsert and retracts what new content lacks (merge.clj:216–217), and `set-form-tx` never retracts many-valued attributes. A verbatim merge carries the branch's retractions as datoms instead, so the carried retraction must be applied before the carried assertion (attack §2.2).
- **Tests that turn red:** every two-argument `apply-tx` call (merge_test:94, :124, :193; program_test:190; source_test's `merged` :91 and `merge-loaded` :378, with every deftest using them).

**Choices for the same-identity case, simplest first:**
1. **Keep today's upsert for exactly this case.** When the target already holds the identity under another eid, the source's datoms for that entity are re-pointed to the target's eid, and its refs remapped. It is a rule, and it reintroduces two eids for one form across branches. The union then holds both, which is true as history: two authors minted it.
2. **Content-derived eids for unique-identity entities:** the eid is a hash of `[attribute value]`, so both branches mint the same eid and the datoms coincide. That is zero rules, but Datahike's eid space is below `tx0` = 2^29, and hashing into it collides by the birthday bound at about 23k entities. It needs 64-bit eids, a much larger change to Datahike's datom layout. Not recommended now.
3. **Mint unique-identity entities on main only:** an agent asks root for the eid of a new namespace or form identity. That is a round trip per new identity and a second mechanism. Not recommended.

Recommendation: option 1, stated in `seon.merge` as the one merge rule for identity, with a proof. Root should rule on it before step 2 is built.

## A. Two Jolt defects found on the way (both block the design; patched locally only to measure, uncommitted)

1. **`datoms-diff` throws whenever two trees partly share.**
   - `datahike.index.persistent-set/diff`'s `unshared` calls `java.util.HashSet#retainAll`. Jolt's HashSet host methods (`reference-code/jolt/host/chez/java/host-static-classes.ss:1062–1084`) have no `retainAll`, `addAll`, `removeAll` or `containsAll`.
   - It was never hit, because nothing in Seon calls `datoms-diff` yet.
   - The cause is Jolt's: the HashSet shim needs the bulk Collection methods (a `jolt/hashset-bulk` branch). For the measurement only, `unshared` was rewritten over Clojure sets in this worktree's Datahike checkout.
2. **Every planned query over `d/history` throws** with `IObj$reify cannot be cast to IFn`.
   - `datahike.query.execute/history-versions!` opens `ForwardCursor`s with a `java.util.Comparator` (`fast-cmp-ea`, execute.cljc:1854–1857). The Jolt overlay's `ForwardCursor` calls its comparator as a fn (`reference-code/persistent-sorted-set/src/org/replikativ/persistent_sorted_set.jolt`, `seekGE`).
   - The fix belongs in the overlay's `forwardCursor [this cmp]`. For the measurement, it wraps a non-fn comparator in `(fn [a b] (.compare cmp a b))`.

## B. Not measured

- Real-model attempt sizes: they need the owner's go for paid runs. §1 estimates them from the corpus.
- Schema conflicts across kept branches (one ident, two value types, attack §4.5): the standing store's kept branches were not read. Opening it would take its LMDB writer lock, and the only kept copy (`runs/store-full-20261021`) predates per-agent branches.
- Leaf sharing after a branch merges main twice (attack §4.6). This waits for verbatim merge-main to exist; under today's re-transacting merge-main the diff is doubled by construction (attack §1.10).
- U's total size on a realistic store: estimated as main's history plus Σ Δ. At 300 attempts of about 100 datoms that is main plus about 30k datoms. That is small next to the 115k-datom main measured here.

## 8. Root's writer ops by kind, before and after (2026-10-23, after the delta merge)

How: `bench/split.clj` at the REPL, niced, with the machine at load ~30.
- **Main's program:** Seon's own `src` and `test` imported through `seon.source/import-tx`: 106 files, 2,128 live forms, 67,975 datoms, on LMDB.
- **The branch:** one turn, `seon.fixtures/generation` redefined as the agent records it, plus 40 turn facts. The branch differs from its base by 69 datoms.
- **Before** is jolt/land14's code: three `seon.merge/program` reads per decision. **After** is jolt/global-ids (delta-three-way, merge-tx).

| writer op | before | after | grows with |
|---|---|---|---|
| `seon.merge/program`, one db | 488 ms | (not called) | the program |
| `:branch-diff`: three-way + unmerged | 1,466 + 488 ≈ 1,950 ms | 7.9 + 0.09 ≈ 8 ms | Δ (and log N per seek) |
| `:merge` decision: three-way + merge-refusal's unmerged | ≈ 1,950 ms | ≈ 8 ms | Δ |
| `:merge` tx-data | `apply-tx`: ~66 ms (MEASURED §5, 115k) | `merge-tx` (verbatim): 1.7 ms | Δ |
| `:merge` commit (`d/merge-db`) | (the same Datahike commit) | 8.2 ms for the 29 carried datoms | Δ + log N paths |
| `:tx`, 55 datoms on main | | 3.8 ms | Δ + log N paths |
| `:tx`, 1 datom on main | | 1.25 ms | log N paths |
| sync (LMDB env sync, nothing pending) | | ~0 ms (one fsync when commits are pending: 5-10 ms, storage.md) | pending commits |
| `:spawn` (`d/branch!`) | | 1.0 ms | O(1) |

- A merge of one turn now costs about **18 ms** in the writer: 8 ms deciding, 1.7 ms building the tx-data, 8.2 ms committing.
- Before, it cost about **2 s**, and `:branch-diff` cost another 2 s.
- No writer op left reads the whole program. The one O(changes) step left is `program/load-changes!` in `main-commit`: root loads the forms that changed.
- The turn's 40 platform facts stayed on the branch. 29 datoms merged: the new form, the var's repointing, and the transactions' facts.

**The turn's contract gaps** (`seon.turn` added-gaps, `seon.merge/delta-changes`), on the same program and branch (`bench/gaps.clj`):
- Before: 1,830–2,770 ms, the changes of two whole programs.
- After: 6.2 ms in all:

| part | cost |
|---|---|
| `datoms-diff` (69 datoms) | 2.7 ms |
| `touched-keys`, the diff included | 3.7 ms |
| `program-of`, both sides | 1.35 ms |

- `entity-keys` used to read about six attributes of each touched entity through `d/entity`, and build `d/schema` twice (0.85 ms each). It now reads each entity's datoms with one `:eavt` seek, and cardinality from the db's rschema: 12.7 → 6.2 ms.
- The floor is the diff, which grows with the change, never with the program.
