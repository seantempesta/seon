# Attacking the cross-branch union design (2026-10-23)

An adversarial review of `notes/cross-branch-query-2026-10-23.md` (the "union db": main plus per-branch delta indexes built with `datoms-diff`, with "main wins on `[e a v]`"). Read-only: nothing was booted, built or measured. **Every number is an estimate unless marked *measured*;** measured ones cite their source. Paths are under `next/`; Datahike is `reference-code/datahike/src/datahike/` (written `dh/` below).

The owner's ruling, received mid-review, is folded in: global addressing (eids and tx ids unique across the store) is a given; queries use plain `d/q`, `d/pull` and `d/entity` over a db value; and the costs must meet the lower bounds (query O(log n + k) per index access, union upkeep O(Δ) per commit, opening a branch view O(1)).

## 0. Verdict

- The recommended design needs **at least seven rules** that it doesn't state (§1). Two of them break it outright:
  - **Merged datoms are not the same datoms.** A merge re-transacts with main's tx id and, for program forms, main's entity id. So "main wins on `[e a v]`" deduplicates nothing for new forms.
  - **The union drops exactly the data it exists for.** It holds only live branches, but replies and turns never merge. Once an attempt ends, its reply leaves the union.
- **The cause is that the union is defined over *current* states.** "What is true now" across divergent branches has no answer without a merge; that is a fact of git, and no index can remove it. Each special case is a hidden merge policy.
- **The design with zero special cases** (§2) puts the union over *history* instead:
  - every datom committed on any branch, held once;
  - global addressing makes "once" a plain set union;
  - **verbatim merges** make a merged datom literally the same datom.
- It needs **no new query type and no planner change**: `d/history` of an ordinary DB value is already planner-eligible (`dh/query.cljc:4309-4317`).
- Of the bounds, it meets query O(log N + k), upkeep O(Δ log N) and view O(1). It misses one: an exact *current-state* union for `d/pull`/`d/entity` on entities that two branches both wrote. §3 says what would reach it and why I would not build it.

## 1. Each failure case of the recommended design

The marks are **works**, **needs a rule** or **breaks**.

### 1.1 A branch retracts a fact that main holds — **needs a rule; the sketch breaks**
- `datoms-diff` answers `[only-base only-b]` (`dh/db.cljc:262-270`). The note's v1 code binds `[gone added]` and never uses `gone` (note §6). So the union shows the fact as true while the agent's view says it is false.
- Any fix picks whose view the union is. "Main holds it, so show it" and "b retracted it, so hide it" are both merge policies.
- A Datahike AsOfDB over a merged history picks the second ("a retraction anywhere retracts globally"). That leaks a branch's unmerged proposal into the global view.

### 1.2 The same entity changed on two branches — **needs a rule**
- This is the task entity case: `:seon.task/plan` is improved on a branch (`seon.merge/carried-platform-attrs`, `src/seon/merge.clj:33-38`), while root writes the same task on main.
- The union has to pick one value or show both. "Main wins" hides the branch's change, which is the change the owner wants to see.

### 1.3 Cardinality-one values that conflict across branches — **breaks**
- The note's v1 builds the delta as `(d/db-with (d/empty-db (d/schema m)) [[:db/add e a v] …])`, under the real schema. When two branches hold different values for one card-one `[e a]`, the second `:db/add` upserts and retracts the first (`dh/db/transaction.cljc:827-843`, `upsert?`). One branch's value is lost silently, in arrival order.
- Unique identity is worse. Two branches that each minted an entity holding the same `:form/id` make `db-with` raise "Cannot add … because of unique constraint" (`dh/db/transaction.cljc:30-32`), so the union build throws. That happens whenever two agents write the same new form, which is a normal event: "an identical form on two branches is one key" (`src/seon/merge.clj:9-10`).
- `:branch-of` is keyed by `[e a]` (note §6), so it keeps one branch per attribute: the last one written.
- v2's `UnionDB` with "main wins on `[e a v]`" still holds two values for one card-one `[e a]`. `d/pull` and `d/entity` then return whichever comes first in index order. That is a hidden rule.

### 1.4 History and as-of over the union — **breaks**
- The v1 delta db has synthetic tx ids, so `?tx`, `:db/txInstant` and tx-meta (`:seon.tx/runtime`, docs/storage.md "History") are lost or wrong. `d/history`, `as-of` and `since` over it mean nothing.
- The v2 "tag = lease block" makes tx ids unique but **not ordered** across branches (note §5 #3 says so).
- Now combine that with any merge that keeps tx ids, which global addressing wants:
  - main asserts v1 at a tx in block 0;
  - a branch in block 7 replaces it with v2;
  - the change is merged verbatim;
  - main later replaces v2 at a block-0 tx that is *lower* than block 7's.
  
  Main's history then sorts the retraction of v2 before its assertion, and `AsOfDB` reconstructs the wrong state. So **leased tx blocks plus identity-preserving merges break history**, unless the allocator also follows a Lamport rule (next tx > every tx held). With blocks, that rule means asking root for a fresh block in the middle of a merge.

### 1.5 tx-id collisions across branches — **breaks today**
- A tx id is `(inc max-tx)` of its own branch (`dh/db/transaction.cljc:55-56`, `:1346`). Two agents forked at main's t = 105 each write tx 106. The tx entity 106 then carries two `:db/txInstant`s and two `:seon.tx/runtime`s.
- In any union keyed by datom, the two branches' identical retraction datoms `[e a v 106 false]` collapse into one event.
- The fork server already works around this: tx-meta travels outside the datoms "because a transaction's id is its own branch's" (docs/storage.md, "History"; `seon.db/report->tx-meta`).

### 1.6 Entity-id leases — **works for new entities, needs a rule for merges, and runs out**
- Leased eids are store-unique (`dh/db/transaction.cljc:82-102`, `seon.db/lease-block!`, `src/seon/db.clj:335-341`).
- **But the merge does not keep them for program entities.** `seon.merge/apply-tx` upserts forms and definitions by identity, using tempids ("entity ids never matter", `src/seon/merge.clj:239-246`; `def-ref` and `set-form-tx`, `:166-201`).
  - A form that is new to main is minted from main's counter, so main and the branch hold one form under two entity ids.
  - The note's claim that merged program datoms are "the same `[e a v]`" (note §2, "Merges and double counting") is false for every new form and definition.
- **Exhaustion, an existing hazard.** Entity ids lie below `tx0` = 0x20000000 (536,870,912; `dh/constants.cljc:3-4`). At `:seon.config.store/eid-lease` 100,000 per block, and one block per agent or clone (docs/storage.md, "Leases"), the store holds **5,368 agents ever**. The kept store had 297 branches (*measured*, docs/storage.md:87). Every attempt is a new agent (`src/seon/reconcile.clj:617`).
- A restarted root re-mints a block's unspent tail, and two roots on one store overlap (docs/storage.md, "Leases"). Both are guard-shaped rules that come from leasing.

### 1.7 A branch deleted, abandoned or merged mid-query — **works for a held value; upkeep needs a rule**
- A query over a held db value is safe: values are immutable.
- The upkeep step "a merge notice drops the merged `[e a v]`s" misses merged forms, whose entity id changed (§1.6).
- "Merged and ended branches leave the live union" (note §7) contradicts the reason for the union. Platform records never merge (`src/seon/merge.clj:33-42`), so dropping an ended branch deletes its reply from every global query. The owner's example stops working when the attempt ends.
- So the union must hold **every branch ever**, which grows with every attempt.

### 1.8 Boot rebuild and memory at 100 branches — **misses the bound**
- With all branches ever, the boot cost is Σ over every kept branch of `datoms-diff(base, head)`, which is O(all branch history). At ~3 ms per branch (the note's estimate), that is ~1 s at 300 branches and ~10 s at 3,000. It is paid on every boot.
- Memory is Σ of all deltas held in root, values included (turn texts, eval records). That is at least 40–80 MB at 100 live branches (the note's estimate), and it grows without bound once ended branches have to stay.

### 1.9 The in-memory index and the store after a crash — **needs a rule (unstated)**
- Upkeep is driven by "an agent's commit notice". Today root sees only an agent's `:t`, through its view (`src/seon/render/proc.clj:428-432`; agents report `:t` in `:stats`/`:hello`, `src/seon/agent.clj:497, :507`), not commit events with datoms.
- An agent that commits and dies before its notice leaves the index stale. Then something has to catch up: comparing each branch's applied commit with its head, at boot and at death. That is a second mechanism unless it is the only one.

### 1.10 What `datoms-diff` costs after a rebase or a merge of main — **breaks the O(Δ) claim, and the delta's meaning**
- `datoms-diff` compares a datom **whole, tx included** (`dh/db.cljc:262-266` docstring; `psi/diff`, `dh/index/persistent_set.cljc:277-330`, which decides by the set's comparator).
- `merge-main!` re-transacts main's changes on the branch under the branch's tx ids (the same `apply-tx` path). So after a merge of main, `diff(new-base, head)` holds main's changes since the fork on both sides: originals in `only-base`, copies in `only-b`.
- It also skips only subtrees at the **same address** (`unshared`, persistent_set.cljc:303-307). Two trees that reached the same content by different insert sequences share few addresses. The diff's work therefore follows everything both lines changed, not the branch's own Δ.

### 1.11 Is "main wins" a hidden rule that will bite? — **yes, three ways**
1. It hides a branch's correction of a main fact (§1.2).
2. It deduplicates nothing for program entities, whose ids differ (§1.6).
3. It makes the global view's meaning depend on merge timing: the same query gives different branch attributions before and after a merge, with no fact changed.

### 1.12 Reacting without polling — **needs new plumbing**
- Root's render proc re-reads a whole branch db whenever an agent's `:t` changes (`src/seon/render/proc.clj:428-432`). That is a re-read, not a delta, and the trigger comes from the view.
- Upkeep at O(Δ) needs each agent writer's tx-report sent to root, the way main's writer already sends one to the fork server (`seon.writer/main-commit`, `src/seon/writer.clj:10-20`).

### 1.13 Smaller claims in the note
- **Deleting `judge-tx`'s copy.** `judge-tx` is not just a copy (`src/seon/reconcile.clj:538-575`). It writes root's own judgment: proof results, conflicts, changed keys and failure. Only `pull-end` (`:469-476`) is a copy.
  - Deleting even that moves the reconciler's input from main to the union. The reconciler would then react to every agent commit.
  - That is a separate task with its own cost, not a free deletion.
- **Filters lose the planner.** `d/filter` views are off the planner (`dh/query.cljc:4309-4317`; `FilteredDB`). The note says so; the zero-rule design below needs no filter.

## 2. The design with zero special cases: one history of every branch

**The idea:**
- What is true across divergent branches is a merge question.
- What *happened* across them is a set union, and that has no conflicts.
- So the union is the history: every datom any branch committed, assertions and retractions both, held once.
- A branch's current state stays its head.

### 2.1 Global addressing: one counter for eids and tx ids
- **One cross-process counter per store**, for entity ids and tx ids alike.
  - Option A, recommended: a 64-bit word in a small mmapped file beside `data.mdb`, advanced by an atomic fetch-add. That needs a ~20-line C helper in `native/` (`__atomic_fetch_add`) or a Chez ftype CAS loop, reached through `jolt.ffi`.
  - Option B: a counter key in LMDB, incremented in its own write transaction.
- **Seeding.**
  - On open, `counter := max(counter, max over branch heads of max-eid and max-tx) + 1`. That is O(branches) small head reads, once at root's boot (~20 µs each, estimated).
  - So a counter page lost in an OS crash cannot re-issue an id that is in a durable commit.
- **Order.** A transaction takes its tx id when it starts, after it has read every datom it builds on. So any datom a merge carries has a lower tx id than the merge.
  - That is enough for history order on every branch (it fixes §1.4) without a Lamport rule.
  - Within a branch, tx ids still increase, so per-branch `as-of`, `since` and the reconciler's t comparisons keep their meaning (`src/seon/reconcile.clj:282-290` compares main's t with main's t).
- **Changes in `dh/db/transaction.cljc`.**
  - `current-tx` (`:55-56`) reads `(::tx report)`. That value is allocated once, when the initial report is made, from the counter; a group commit takes a contiguous range.
  - The exits at `:1346` and `:1422` set `:max-tx` to `(max max-tx tx)` instead of `inc`.
  - `next-eid` (`:82-102`) takes from the same counter.
  - **Deleted:** `eid-ceilings`, `set-eid-ceiling!`, `eid-ceiling` (`:58-80`) and the `:entity-id/lease-spent` path.
  - `next-tx-instant` (`:192-205`) reads the instant at the branch's own last tx; this needs one check.
- **Migration:** none. The standing store is test data and is erased (owner).
- **Deleted in Seon:**
  - `seon.db/lease-block!`, `lease!`, `lease-use`, `:seon.config.store/eid-lease`, its failure point, docs/storage.md "Leases" and the "one root per store" id hazard;
  - `report->tx-meta`'s side channel: tx-meta becomes ordinary datoms on a globally unique tx;
  - the 5,368-agent ceiling (§1.6).

### 2.2 Merges carry datoms verbatim: a merged datom is the same datom
- **What changes.** The three-way merge still decides *which* keys merge (`seon.merge/three-way`, unchanged). The tx-data becomes the branch's own datoms for those keys' entities:
  - the `only-b` side of `datoms-diff(base, head)`, restricted to those entities, as `Datom` objects with their e, a, v and tx;
  - the tx entities those datoms name, so `:db/txInstant`, `:seon.tx/runtime` and the author branch travel with them.
- **Datahike already takes this.** A `Datom` in tx-data is transacted with its own tx: `transact-add` uses the datom's tx (`dh/db/transaction.cljc:835`), and a retraction keeps it with `keep-tx-id` (`:860-863`, `:1402-1406`).
  - The one fix is ordering: apply a carried retraction before the carried assertion of the same `[e a]`. Otherwise card-one upsert writes an extra retraction under the merge's tx (`:827-843`). About 20 lines plus a red→green test.
- **The same path for `merge-main!`.** Main's datoms land on the branch unchanged, so `datoms-diff` after a merge of main no longer sees a doubled delta (fixes §1.10).
- **What stays a new datom.** Only a conflict resolution whose content is new is authored on main, and it is new: it gets a new datom.
- **What stays unresolved.** Two branches that each minted the same new form still hold two entities with one `:form/id`.
  - In the history that is true: two assertions by two authors.
  - It is a conflict only for a *current* union (§3).
  - Content-derived entity ids for unique-identity entities would remove it at the cause, but that is a bigger change. It waits until a measurement shows it matters.

### 2.3 U, the store's history: a plain DB value
- **What U is.** An ordinary `datahike.db/DB` whose current indexes hold every **assertion** ever committed on any branch, and whose temporal indexes hold every **retraction**, each datom once.
  - `(d/history U)` is a stock `HistoricalDB`. Its slices are already a sorted, distinct merge of current and temporal (`dbu/distinct-datoms` → `merge-datoms`, `dh/db/utils.cljc:236-243`; `dh/db/search.cljc:256-270`).
  - It is planner-eligible as it stands (`dh/query.cljc:4309-4317`).
- **Upkeep is plain set insertion**, with no transact semantics and nothing to resolve:
  - each committed datom goes into U's indexes;
  - an equal datom is the same datom, so inserting it is a no-op (the comparator includes tx);
  - shared ancestry and verbatim merges deduplicate by themselves.
  - New in Datahike: `datahike.union/insert` (~60 lines over `di/-insert`).
- **U's schema.** It is the union of the schema datoms, with cardinality-one and uniqueness lifted to *many* and *none*. This is the one rule left. It is not a merge policy but U's definition: U is a set of facts, and constraints belong to a branch.
  - Two branches declaring one ident with different `:db/valueType`s is a real conflict. It has to be measured (§5); if it never occurs, a law (one ident, one type, store-wide) states it.
- **The branch tag is a datom.** Every transaction carries `:seon.tx/branch` in its tx-meta (one inline datom, ~30 B). It travels with verbatim merges, so a datom's tx always names its author branch.
- **The owner's example, in plain `d/q`:**
  ```clojure
  (d/q '[:find ?body ?reply ?br
         :where [?m :seon.message/to "agent-1"] [?m :seon.message/id ?id] [?m :seon.message/body ?body]
                [?r :seon.message/reply-to ?id ?tx] [?r :seon.message/body ?reply] [?tx :seon.tx/branch ?br]]
       (d/history U))
  ```
- **Messages, turns and evaluations** are single-author and append-only. For them, `(d/as-of U t)`, a stock AsOfDB, is exact, and `d/pull`/`d/entity` work on it as on any db.
- **A branch's own view** is its head (`seon.db/head`), opened in O(1). A branch's history is `(d/history head)`.
- **"Current on these named branches"** is multi-source `d/q` over their heads (`:in $main $b`, `dh/query.cljc:4486-4497`). With global addressing it joins by eid and tx across sources and needs no rule.

### 2.4 Keeping U current: reactive, one catch-up mechanism
- **Per commit.** Each writer (root's, and each agent's in-process one) sends its tx-report's datoms to root, as main's writer does for the fork server (`src/seon/writer.clj:10-20`). Root's writer inserts them into U at its group commit and records U's applied commit per branch as data.
- **Catch-up.** One reconcile for every gap: U's applied commit for b ≠ b's head commit ⇒ insert the `only-b` side of `datoms-diff(applied, head)`. It runs on root's boot, on an agent's death (`:seon.agent/died`) and on a refused notice. There is no poll.
  - With verbatim merges, the diff follows only what b wrote since the applied commit: O(Δ) nodes.
- **Persistence.** U's index trees are flushed by the existing stored-db machinery under one store key, `:datahike/union`. One root-to-leaf path per index per group commit (≤32 KB, docs/storage.md), and lazily loaded nodes, so memory stays bounded.
  - The cost is a second copy of history in storage. A later step (§3) can delete the per-branch temporal indexes, if `as-of` on a branch goes through the commit graph instead.

### 2.5 As-of on a branch after verbatim merges
- A carried datom has its author's tx t_b, which is lower than main's merge tx t_m. So `(d/as-of main T)` for t_b < T < t_m shows a fact that main did not hold at T. Its meaning becomes "authored by T", as git's author date does.
- Zero-rule fix: `as-of` on a commit-graph store resolves T to the branch's newest commit whose own tx ≤ T (a first-parent walk), then `commit-as-db`. That is O(1) to open (0.7–0.9 ms *measured*, docs/storage.md:93) plus the walk. About 30 lines in `dh/versioning.cljc`.
- Seon's reconciler compares main's merge t with main's t, so it is unaffected.

### 2.6 Against the lower bounds

| operation | lower bound | this design | meets? |
|---|---|---|---|
| a query over the union | O(log N + k) per index access, N = U's datoms | U is one sorted set per index: one seek plus k; `d/history` merges two sorted slices | **yes**; for mutable attributes, k counts superseded versions, because the union is history |
| keeping the union current | O(Δ) per commit | Δ inserts × 3 indexes × log N in memory (~0.3 ms for Δ = 100), plus one path per index per group commit persisted | **yes** (log N is the index's own) |
| opening a branch view | O(1) | `branch-as-db`, 0.7–0.9 ms *measured* | **yes** |
| allocating an id | O(1) | one atomic fetch-add, tens of ns (estimate); an LMDB txn is ~10–50 µs (estimate) | **yes** |
| a merge | O(changed datoms) | a diff restricted to the merged keys' entities | **yes** |
| boot | O(branches) + Σ gaps | head-commit compare per branch, a diff only where one differs | **yes**, with U persisted; in memory it is O(N), which fails |
| a *current-state* union for pull/entity on entities written on two branches | O(log N + k), upkeep O(Δ) | **not provided.** AsOfDB over U says "a retraction anywhere retracts", which is a merge policy | **no**: see §3 |

## 3. Ranked: the simplest that meets the bounds first

1. **Global addressing, verbatim merges and an in-memory U.**
   - It meets the query, upkeep, view and id bounds, and it proves the message join.
   - It fails the boot and memory bounds as N grows. Build it first at n ≤ 100, and measure N on a realistic store.
2. **The same, with U persisted** (`:datahike/union`, §2.4). It meets every bound in §2.6 except the current-state union. **Recommended as the target.**
3. **Delete the per-branch temporal indexes** (history is U ∩ ancestry; `as-of` goes by commit, §2.5).
   - It recovers the storage that 2 doubles, and it deletes more code than it adds.
   - Build it only when a measurement of store size asks: `d/history` of one branch becomes a filter over U.
4. **An exact current-state union** (every fact some live branch holds now, for `d/pull`/`d/entity`).
   - Upkeep at O(Δ) needs a count of the live leaves holding each datom, keyed by node address. A branch is then O(1) (it shares the root), a commit O(changed leaves × fanout), and a deleted branch O(its unshared nodes).
   - **It is blocked by the set's buffered edits.** A branch node holds pending edits for its children (`slot-datoms`, `dh/index/persistent_set.cljc:266-275`), so a leaf's datoms are not what a head holds, and a node's effective content is not a function of its address alone.
   - The alternative, checking every branch when main retracts, costs O(branches × log n) per retracted datom, which fails the bound.
   - Even when built, it still has to choose among card-one conflicts. **Do not build it.** The current state across divergent branches is a merge, and the tool for it is the merge.

**The smallest dependency change that makes the union fall out with no rules** is the store-wide counter for tx ids (with eids on the same counter), plus `transact-add`'s carried-retraction ordering. Datahike already accepts datoms with their own tx (`dh/db/transaction.cljc:835`, `:1402-1406`), so verbatim merges are a Seon change, and U is plain set insertion into ordinary indexes.

### Estimated lines

| repo | change | added | deleted |
|---|---|---|---|
| Datahike | the counter in `next-eid` and `current-tx`, and `max-tx` as a high-water mark | ~40 | ~45 (eid ceilings) |
| Datahike | carried-retraction ordering | ~20 | 0 |
| Datahike | `datahike.union`: insert, the lifted schema, the persisted head | ~160 | 0 |
| Datahike | `as-of` by commit (step 3 only) | ~30 | 0 |
| Jolt / native | an atomic fetch-add on an mmapped word (C helper or Chez ftype), with its failure point | ~30 | 0 |
| Seon | delete leases (`seon.db` lease fns, config key, failure point, docs, the fork-server tx-meta side channel) | 0 | ~100 |
| Seon | verbatim merge in the writer and `merge-main!` | ~60 | ~10 |
| Seon | agent commit reports to root | ~20 | 0 |
| Seon | U's upkeep and catch-up in root's writer | ~50 | 0 |
| Seon | `:seon.tx/branch` tx-meta | ~5 | 0 |
| Seon | messages as facts (the feature itself) | ~40 | 0 |

Net: Datahike ~+205 (~+175 without step 3), Seon ~+65, native ~+30.

## 4. What must be measured before building (each at n ≤ 100)
1. **The id counter:** an mmap fetch-add against an LMDB counter txn, with up to 10 processes contending, per transaction and per group commit. Also: does LMDB with `MDB_NOSYNC` (`src/seon/db.clj:85`) keep a commit-ordered prefix after an OS crash, or is the reseed from heads (§2.1) required?
2. **N:** the distinct datoms across every branch of a realistic store, all history included, and the memory U needs held in root. This decides between steps 1 and 2.
3. **U's costs:** insert time per commit at Δ = 100, and a planned `d/q` over `(d/history U)` against the same query over main at the same N. The target is within 2×.
4. **Verbatim merge:** a red→green test that a merged card-one change produces exactly the branch's datoms (no extra retraction at the merge tx), and that `datoms-diff` after `merge-main!` is O(Δ).
5. **Schema conflicts:** count idents declared with two different `:db/valueType`s across the kept branches. If there are none, the lifted schema plus a store-wide law is enough.
6. **Leaf sharing:** whether `datoms-diff` work after a merge really follows Δ with verbatim carries. Persistent-set node addresses depend on insert order, so this needs measuring on a branch that merged main twice.
7. **The node cache:** whether Jolt's node cache shares nodes across db values. It decides the cost of multi-source `d/q` over named heads (§2.3).
