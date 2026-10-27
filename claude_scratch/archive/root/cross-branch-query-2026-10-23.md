# Querying across branches: one db of all branches (2026-10-23)

The question (owner): branches give isolation, but a query over the system should be easy to run globally. The example is a message: root writes it on main, the agent writes its reply on its own branch, and one query joins the two. The owner's preferred shape is one all-branches db whose facts carry their branch, built from each branch's delta since it split from main. An agent's view would then be `(d/filter all …)`, and permissions would be filters too. He asked that it be optimized at the Datahike and storage level.

Scope: read-only research. Nothing was booted and nothing was built. **Every number here is an estimate unless it is marked *measured*.** Measured figures come from earlier notes and docs, which are cited.

## 0. Summary

- **Recommended, step 1:** a union db, `datahike.union`. It is main plus one small in-memory delta index. That index holds each live branch's datoms since its merge base, read with the structural diff Datahike already has. It needs no format change.
- **Step 2:** store-unique tx ids, leased like entity ids. Then the branch tag is the tx's lease block: one arithmetic step per datom, with no new datoms and no lookup.
- **Step 3:** views compile to a choice of source trees, not to per-datom predicates.
- **An agent's own view needs no filter.** Its branch head already is "main at its fork point, plus its own datoms", physically. A filter on the union is the right tool for other views: a permission, a role, root's global view, one agent reading a sibling's reply.

## 1. How branches work today

**What a branch is physically.**
- A branch is one konserve key, holding the branch's head record. With `:commit-graph?`, that record is a compact head: a pointer to a commit.
- The commit holds the roots of the persistent-sorted-set index trees (`eavt`, `aevt`, `avet`, plus the temporal indexes, because history is kept).
- Nodes are content-addressed, one node per key (docs/storage.md:3), so a branch shares every untouched subtree with its parent.
- Making a branch from a branch with a compact head writes that same pointer under the new name and reads nothing else (`datahike.versioning/branch!`, reference-code/datahike/src/datahike/versioning.cljc:224-301; the "pointer?" path is at :266-273). The roster records the branch name for GC.
- *Measured* (seon.db comment, src/seon/db.clj:152-156): about 1 ms per branch. A fresh seeded store costs about 200 ms.

**Getting a db value for a branch.**
- `d/branch-as-db store b` (versioning.cljc:698-709) reads the head and runs `stored->db` (writing.cljc:327). That builds a db whose index roots are bound to storage. Children stay lazy, so nodes load on first touch.
- `d/commit-as-db` does the same for one commit. *Measured* at 0.7–0.9 ms (docs/storage.md:93; notes/store-native-2026-10-22.md:102).
- Reading main **whole** costs 0.3–0.4 s (*measured*, docs/storage.md:93). This is why nothing should open a branch by reading it whole.

**Seon's wrappers.**
- `seon.db/head` (src/seon/db.clj:343-346) is `branch-as-db`.
- `seon.db/branch-base` (db.clj:348-354) is `commit-as-db` at `d/merge-base` (versioning.cljc:583).
- `seon.db/branch?` is an O(1) key read. `d/branches` folds the whole roster, O(branches) (db.clj:187-191).
- Root reads branches only through the writer: `:branch-db`, `:branch-diff` and `:branch-info` (src/seon/writer.clj:186-201), plus `branch-merge` (writer.clj:22-30).

**Ids. This matters for tagging.**
- **Entity ids are unique across the store.** Every branch mints from one counter (`datahike.writing/eid-allocator`, writing.cljc:851-863), and agents mint from a leased block of 100,000 ids (`:seon.config.store/eid-lease`; `seon.db/lease-block!`, db.clj:335-341; docs/storage.md:30). So one entity id never means two things on two branches.
- **Transaction ids are per branch.** A tx id is `(inc max-tx)` of the branch's own db (reference-code/datahike/src/datahike/db/transaction.cljc:56, :395, :1346). The same tx id names different transactions on main and on an agent's branch; docs/storage.md:40 says "a transaction's id is its own branch's".
- So the tx is **not** a branch tag today. In a naive union, tx-meta `:branch` on two transaction entities with one id would collide.

**What already exists that a union needs.**
- `datahike.api/datoms-diff` (specification.cljc:120-128; impl `datahike.db/datoms-diff`, reference-code/datahike/src/datahike/db.cljc:262-270) runs over `datahike.index.persistent-set/diff` (persistent_set.cljc:277). It answers `[only-a only-b]` for two db values of one store, and skips every stored subtree that both share. So its work follows the delta, not the index.
- Nothing in Seon calls it yet. A grep of src/ finds no `datoms-diff`.

**The fork's `d/filter`.**
- It exists (`datahike.core/filter`, reference-code/datahike/src/datahike/core.cljc:114-122; `FilteredDB`, db.cljc:478-549) and has tests (test/datahike/test/filter_test.cljc).
- It works like Datomic's. The index slice runs on the unfiltered db, and the predicate is applied as a transducer after the slice (`-search-context` → `context-with-xform-after … (filter pred)`, db.cljc:539-541). Nested filters compose with `and` (core.cljc:117-121).
- So it is **O(1) per datom visited, plus the predicate's own cost. It never narrows a slice**: it visits every datom the index range holds and drops the ones that fail.
- **The query planner does not accept a FilteredDB.** `planner-eligible-db?` admits only `DB`, `AsOfDB`, `SinceDB` and `HistoricalDB` (reference-code/datahike/src/datahike/query.cljc:4309-4317). A filtered db falls back to the older relation engine.

**How many branches.**
- Each attempt is a new agent on a new branch (src/seon/reconcile.clj:617, "every attempt is a new agent"). Branches are kept as evidence.
- The standing store had **297 branches and 159,152 keys** (*measured* 2026-10-22, docs/storage.md:87). Live agents are capped at 1,024 (`:seon.config.root/max-agents`).
- A global query therefore wants **live (unmerged) branches** cheaply, and all branches ever only when asked: "every turn of every attempt" is a history question.
- Enumerating branches: `d/branches` costs O(branches) small reads. Root already holds every live agent's branch and t in its view (src/seon/render/proc.clj:428-432), so it needs no store read.

## 2. What Seon does today instead of a global query (what one would delete)

**Messages are not stored at all.**
- Root delivers `{:op :message}` into the agent's in-memory `:inbox` (src/seon/agent.clj:492-503). The agent takes it with `(messages)` (agent.clj:66-70), and sends with `:op :send` (agent.clj:71-74), relayed by `agent_proc` (src/seon/agent_proc.clj:170).
- A message survives only inside a turn's `:observed` basis (src/seon/turn.clj:126-140).
- So the conversation in the owner's example cannot be queried today from any branch.

**Copies from a branch to main at an attempt's end.**
- `judge-tx` copies the turn loop's end record (outcome, turns, model calls, tokens, error, waste) off the branch, with `pull-end` (src/seon/reconcile.clj:469-476, :538-575). It writes a copy onto main's attempt.
- `seon.task/carry-tx` (src/seon/task.clj:173-194) carries `:seon.task/plan` and `:seon.task/instructions` onto main. This one is a merge, not a read: later agents fork from main and need the value there. A global query does not delete it.

**Root reading branches one at a time.**
- The writer's `:branch-db` and `:branch-info` (writer.clj:193-201) and `seon.root/branch-db` (src/seon/root.clj:943-947).
- The dashboard's zoom reads, one branch db per watched agent (src/seon/render/proc.clj:425-439).
- The reconciler's `:dead-read` and `:read` (reconcile.clj:597-600, :641-645).
- Checks and e2e (src/seon/check.clj:911, :1066; src/seon/e2e.clj:264).

**Merges and double counting.**
- Platform records never merge. Turns, evaluations, attempts and ends stay on the branch where they were written (`seon.merge/carried-platform-attrs` and `platform-record?`, src/seon/merge.clj:33-42).
- An octopus merge applies only the program keys that changed, as new tx-data on main (writer.clj:85-120).
- So after a merge, a union holds:
  - platform records **once**, on the agent's branch: correct, and the reason a union is worth having;
  - merged program datoms **twice**: the same `[e a v]` (entity ids are store-unique, db.clj:356-360), under the branch's tx and again under main's merge tx.
- Rule: **main wins on `[e a v]`.** A branch contributes only datoms whose `[e a v]` main does not hold now. The merge's tx-meta names the branches it took from, so provenance survives. The membership check is one AVET/EAVT seek per delta datom (O(log n)), done once when the delta is built.

## 3. The design space, ranked (simplest viable first; ★ = recommended)

Assumptions behind the estimates:
- main is about 100k datoms;
- a live agent's delta averages about 2k datoms (about 20 turns of about 100 datoms);
- a cold node read plus decode is about 20–50 µs;
- a planned query is about 95 µs at 100 tasks (*measured*, docs/storage.md, "Entity ids").

| | option | Datahike lines | Seon lines | query at 10 branches | query at 100 branches | merges | reactive |
|---|---|---|---|---|---|---|---|
| a | multi-source `q`: a helper binds `$main $b1 … $bn` | 0 | ~40 | warm ~1 ms; cold +8 ms to open | warm ~10 ms; cold +80 ms | double unless the query excludes merged branches | re-run on any head move |
| **b ★** | **union db: main + one delta index of every live branch since its merge base** | **v1 ~0 (Seon-side); v2 ~250** | **~120** | **~0.1–0.2 ms; build ~3 ms/branch cold, once** | **~0.1–0.3 ms; Σ delta 200k datoms in memory** | main wins on `[e a v]` | **apply only the moved branch's diff** |
| c | global persisted index: every commit also writes its datoms, branch-tagged, into one shared tree | ~150 | ~150 | like b | like b, with no build at boot | the same rule, at write | free, it is the write |
| d | Datomic-style `d/filter` over a union, with no planner | 0 | ~30 | scans every branch's datoms in range | 100× the datoms visited, on the old engine | — | — |

**(a) Multi-source `q`.**
- It works today. Datahike binds each `$`-symbol from `:in` as its own source (query.cljc:882-883, :2225, :2380-2382) and runs multi-source through the planner (`multi-source?`, query.cljc:4486-4497).
- Datalog cannot bind a *collection* of sources, though. The helper has to generate the `:in` and an `or-join` per branch, and the query grows with n.
- Each branch db is a full db, so every clause runs n times over shared structure. Main is read n times, unless the node cache is keyed by address; I did not check the Jolt node cache, and `node_cache.clj` is the JVM's Caffeine cache.
- Simple, and a good first proof of the message join. It scales badly in the thing that grows.

**(b) ★ The union db.** It is one db value built from shared structure. The delta for branch b is `datoms-diff(branch-base b, head b)`, against the **merge base**, not main's head, because main has moved since. Its `only-b` side holds what b asserted, and its `only-base` side what b retracted.
- **v1 (Seon only, about 120 lines, no Datahike change).**
  - Build the delta index as a plain Datahike in-memory db (`d/db-with` of the deltas' datoms), keyed by `[e a v]`, with the branch as a datom on a per-branch *source* entity. That works because v1's datoms carry synthetic tx ids made by the builder, so they never collide.
  - Query with two sources, `$main` and `$delta`, plus a rule `(fact ?e ?a ?v ?br)` that `or`s them.
  - This deletes the per-branch reads in §2, and the message join becomes one query.
- **v2 (Datahike, about 250 lines).** A `UnionDB` type that implements `ISearch`, `IIndexAccess` and `-datoms` as a two-way merge of two sorted slices (main's and the delta tree's), with main-wins dedup on `[e a v]`. `planner-eligible-db?` admits it, and the planner plans on main's schema and statistics. Then `$` is simply `all`, and every existing query, pull and entity call works on it.

**(c) A persisted global index.** It is the same index as (b), but written at commit time, so a cold root does not rebuild it.
- Only one process can own one persistent tree. Agents commit in their own processes, so root would have to apply every agent commit to the tree, which is (b)'s update step persisted.
- The cost is one extra root-to-leaf path per index per commit (≤32 KB, docs/storage.md:11) and a second writer of record.
- Worth it only when a measured boot-time rebuild hurts. At 100 live branches that rebuild is about 0.3 s cold (estimated).

**(d) What the field does.**
- **Datomic** has no persistent branches. `d/with` is a speculative branch, and several db values can be sources in one query (`:in $a $b`). `as-of`, `since` and `history` are built-in filters, and `d/filter` takes a per-datom predicate that is "applied to an unfiltered database value" ([Datomic filters](https://docs.datomic.com/reference/filters.html), [pro filters](https://docs.datomic.com/pro/time/filters.html), [Database javadoc](https://docs.datomic.com/pro/javadoc/datomic/Database.html)).
  - The community's experience: filtering to a partition is no win, because the filter still visits every datom the query touches ([clojurians 2015-07-08](https://clojurians-log.clojureverse.org/datomic/2015-07-08)).
  - An `as-of` re-implemented as a plain filter gets wrong answers ([Janus, "forking the past"](https://blog.danieljanus.pl/datomic-forking-the-past/)), which is the retraction problem in §4.
- **Dolt** keeps a prolly-tree root per branch. `AS OF 'branch'` names a branch in SQL, and one query may join tables at several revisions (`t AS OF 'main' JOIN t AS OF 'feature'`). `dolt_diff`, `dolt_history_*` and `DOLT_DIFF()` read the delta by structural diff of shared trees ([querying history](https://docs.dolthub.com/sql-reference/version-control/querying-history), [system tables](https://docs.dolthub.com/sql-reference/version-control/dolt-system-tables), [diff magic 2](https://dolthub.com/blog/2022-04-11-dolt-diff-magic-part-2)). In effect this is option (a), with (b)'s diff for change views.
- **TerminusDB** (from memory, not re-checked today): each branch is a stack of immutable delta layers over its parent. WOQL `using("branch/…")` scopes part of one query to another branch, so one query reads several branches. The delta-layer stack is (b) made physical (https://terminusdb.com/docs/).
- **Irmin** and **Noms** (from memory): a Merkle tree per branch, diffs by hash that skip shared subtrees (Noms' prolly trees are the model Dolt uses), and no cross-branch query language (https://irmin.org, https://github.com/attic-labs/noms).
- **XTDB** (from memory): bitemporal history on one timeline and no branches. Speculative transactions stand in for a branch (https://docs.xtdb.com/).
- The lesson: **every system that has branches reads across them by structural diff of shared trees** (Dolt, TerminusDB, Noms), and none offers a filtered global view that does as-of correctly as a plain predicate. Our `datoms-diff` is already that diff.

## 4. The owner's question: is an agent's view just `(d/filter all …)`?

**It can be expressed, but as a plain predicate it is wrong, and it is never needed for the agent itself.**
- The agent's view is "main **as of** its fork point (or its last merge base), plus its own datoms, minus what it retracted".
- A predicate over *current* datoms cannot express "as of": a datom main retracted after the fork is gone from main's current index, and the agent should still see it. That is the same counterexample as Janus's.
- With history kept (`:seon.config.store/keep-history?`), the view is `as-of(main, base-t) ⊕ delta_b`. And that value **is the agent's branch head**, which exists physically at zero cost.
- So: the agent reads its own branch db, as it does now. The union is for everyone else.

**Where filters on the union are right: permissions and roles.**
- "Agent x may read main and the branches of tasks under goal g" and "the dashboard shows these agents" are branch-set predicates over a current union. These are the elegant part the owner sees.
- Examples:
  - a reader that may not see secrets: `[:not (= a :seon.secret/…)]`;
  - an agent reading a sibling's reply, which needs one branch beyond its own.

**What a filter costs.**
- With a FilteredDB as it is today: one predicate call per datom *visited* (about 10–50 ns on Chez for a set lookup, estimated), and every datom in the slice is visited.
- Over a union of 100 branches, a clause on `:seon.turn/reply` visits every branch's turns, about 100× what one agent's turns need.
- It also loses the planner (query.cljc:4309-4317).
- So the predicate is O(1) per datom, but **the number of datoms visited grows with the branches the view excludes**. That is the thing to optimize.

**Where the branch tag lives.**
- **Not in the datom.** A datom is `[e a v tx added?]`, with nowhere to put a branch.
- **In the tx, once tx ids are store-unique** (optimization 2 below). Today they are per branch (transaction.cljc:56, :1346), so the tag would be ambiguous.
- In v1, before that change, the union's builder knows each delta datom's source and records it on the delta side.

## 5. Optimizations, ranked by win against lines

| # | optimization | win | Datahike | Seon | notes |
|---|---|---|---|---|---|
| 1 | **Read only each branch's delta**: `datoms-diff(base, head)`, which skips shared subtrees (db.cljc:262; persistent_set.cljc:277). **Already exists.** | building a union costs Σ delta nodes, not n × main: about 100 nodes per 2k-datom delta (~3 ms cold) vs ~0.35 s to read main whole (*measured*), **~100×** per branch | 0 | ~30 | the base is `seon.db/branch-base` (db.clj:349) |
| 2 | **React to a head move with only the new diff**: on an agent's commit notice (root already sees each agent's t change, render/proc.clj:428-432), `datoms-diff(prev-head, new-head)` and apply it to the delta index | an update costs one commit's datoms (~100) and its paths: ~µs to 1 ms, vs rebuilding a branch's delta | 0 | ~40 | no poll: it runs on the notice; a merge notice drops the merged `[e a v]`s (main wins) |
| 3 | **The branch tag as a tx lease block**: store-unique tx ids minted from the store counter in blocks (like `eid-lease`), so `branch-of(tx)` = block index `(quot (- tx tx0) lease)` → a bitset per view | the tag is free (no extra datoms, no map lookup), ~5–10 ns per datom; and tx-meta `:branch` becomes unambiguous across the union | ~60–100 (`next-tx` at transaction.cljc:56/:395/:1346; a tx ceiling like `eid-ceiling`) | ~20 (`lease-block!` hands out a tx block too) | tx ids stay increasing within each branch (an agent's later block is higher), but **not a global time order** across branches: `as-of` by t is only meaningful per branch; by instant (`:db/txInstant`) everywhere |
| 4 | **Compile a view to sources, not predicates**: a view is `{:main as-of-t, :branches #{…}}`; `UnionDB` merges only the chosen delta trees (one tree per branch, or one tree per branch *set* the bitset selects) | a view excluding 99 branches visits 0 of their datoms (vs all of them with FilteredDB); and it stays on the planner | ~250 (UnionDB) + ~10 (`planner-eligible-db?`) | ~20 | the "range skip" the owner asked for: tx is the *last* index component, so tx bounds cannot narrow an `eavt/aevt/avet` slice; the branch-leading index already exists, and it is the branch trees themselves |
| 5 | **Content-keyed cache of the merged view** per head set `(main-cid, {b cid})` | only for re-reading a *past* head set (a turn's basis, a review); with #2 the current view is just held | 0 | ~20 | allowed by §5 "content-keyed reuse" only once measured expensive; build later |
| 6 | **Persist the union index** (option c) | cold boot needs no rebuild (~0.3 s at 100 live branches, estimated) | ~150 | ~50 | a second record of every datom; only if boot measures it |

**First version (v1, about 150 lines, Seon only):** #1 + #2.
- A `seon.union` value held in root: `{:main db :delta db :branches {b {:base-t :head-cid}}}`.
- Built from `datoms-diff` and updated per commit notice.
- Queried as two sources, `$main` and `$delta`, through a rule.

**The path to the fast one:**
1. #3, store-unique tx ids, on `jolt/tx-lease`. It is a foundation, so it merges as its own task.
2. #4, `UnionDB` in Datahike on `jolt/union-db`: views as source selection, planner-eligible.
3. #5 and #6 only when a measurement asks for them.

## 6. The example, at REPL size

Messages become facts:
- root's message on main: `{:seon.message/id uuid :seon.message/from "root" :seon.message/to "agent-1" :seon.message/body "…"}`;
- the agent's reply on its branch: `{:seon.message/id uuid2 :seon.message/reply-to uuid :seon.message/body "…"}`.

`reply-to` is the uuid (`:db.unique/identity` on `:seon.message/id`), not a ref. A message sent after the fork is not on the agent's branch, and a value joins across sources where a ref to a missing entity would not.

```clojure
;; v1: union as two sources, built from structural diffs (no Datahike change)
(require '[datahike.api :as d] '[seon.db :as pdb])

(defn delta [conn main b]
  (let [base (pdb/branch-base conn main b) head (pdb/head conn b)
        [gone added] (d/datoms-diff base head :eavt [])]      ; skips shared subtrees
    {:branch b :added added :retracted gone}))

(defn union [conn main bs]
  (let [m @conn
        ds (map #(delta conn main %) bs)
        ;; main wins on [e a v]: a merged form is main's, counted once
        fresh (for [{:keys [branch added]} ds, dt added
                    :when (empty? (d/datoms m :eavt (:e dt) (:a dt) (:v dt)))]
                [branch dt])]
    {:main m
     :delta (d/db-with (d/empty-db (d/schema m))       ; in memory; v2 makes this a UnionDB
                       (for [[b dt] fresh] [:db/add (:e dt) (:a dt) (:v dt)]))
     :branch-of (into {} (for [[b dt] fresh] [[(:e dt) (:a dt)] b]))}))

;; the conversation: root's message on main, the agent's reply on its branch, one query
(def rules '[[(fact ?e ?a ?v ?src) [$main ?e ?a ?v] [(ground :main) ?src]]
             [(fact ?e ?a ?v ?src) [$delta ?e ?a ?v] [(ground :branch) ?src]]])

(let [{:keys [main delta]} (union conn :db [:agent-1])]
  (d/q '[:find ?body ?reply
         :in $main $delta %
         :where (fact ?m :seon.message/to "agent-1" _)
                (fact ?m :seon.message/id ?id _)
                (fact ?m :seon.message/body ?body _)
                (fact ?r :seon.message/reply-to ?id _)
                (fact ?r :seon.message/body ?reply _)]
       main delta rules))
;; => #{["main moved, merge main" "merged; 3 forms changed"]}

;; v2 (with #3 + #4): one db; the tag is the tx's lease block
(def all (d/union conn {:main :db :branches :live}))   ; UnionDB, planner-eligible
(d/q '[:find ?body ?reply ?br
       :where [?m :seon.message/to "agent-1"] [?m :seon.message/id ?id]
              [?m :seon.message/body ?body]
              [?r :seon.message/reply-to ?id ?tx] [?r :seon.message/body ?reply]
              [(datahike.union/branch-of ?tx) ?br]]
     all)

;; agent-1's own view of the same data: NOT a filter; it is its branch head
(pdb/head conn :agent-1)            ; = as-of(main, base-t) + its delta, physically
;; a permission/role view IS a filter: v1 a predicate, v2 a source selection
(d/filter all (fn [_ dt] (#{:db :agent-1 :agent-2} (datahike.union/branch-of (:tx dt)))))  ; v1-style, O(datoms visited)
(d/union conn {:main :db :branches #{:agent-1 :agent-2}})                               ; v2: others never read
```

Mind the gap: the agent's branch holds root's message only if it was sent before the fork. A query run *in the agent* that joins its reply to a later message needs main too: the agent reads `(d/union … {:branches #{self}})` with main at its head, or its inbox as now. That is a policy choice for root: whether an agent may read main live.

## 7. Risks

- **Store-unique tx ids** (#3) touch the deepest code in Datahike's transactor.
  - `max-tx` stays the per-branch high-water mark, so `as-of`, `since` and the reconciler's `basis-t` and `merged-t` comparisons (reconcile.clj:282-290) keep their per-branch meaning.
  - Comparing a t across branches becomes meaningless, and it is nearly meaningless today too.
  - The tx space (`tx0` 0x20000000 to `txmax` 0x7FFFFFFF, constants.cljc:4-6) holds about 1.6 billion. A 100k block per agent gives about 16k agents before it runs out; at 297 kept branches that is decades away, but the block size must be config.
- **Main wins on `[e a v]`** hides a branch's datom that main *also* asserts independently (two branches converge). That is correct for "what is true globally". The per-branch provenance stays readable on the branch.
- **The memory bound:** the delta index holds Σ live deltas, about 200k datoms at 100 agents (~40–80 MB, estimated), inside `:seon.config.machine/memory-mb`. Merged and ended branches leave the live union; "all branches ever" is a history query over kept branches, built on demand.
- **FilteredDB off the planner:** if v1 ships with `d/filter` views, they run on the old engine. Keep v1 views as source selection, the two `$` sources, and use filters only for permissions until v2.
- **Unmeasured:** delta sizes per agent, node read/decode cost on Jolt and LMDB, and whether Jolt's node cache dedups shared nodes across db values. A worktree session should measure all three at n ≤ 100 before v2.

## 8. Proposed tasks

1. `seon.union/a-conversation-is-one-query`. v1: messages as facts on main and branch, the union of #1 + #2, and the query above. It deletes the inbox's unrecorded path, `pull-end`'s copy, and the render proc's per-agent `:branch-db` reads.
   - Proofs: a deftest for the reply join; a merge counted once; a cost proof that the union's update is O(commit datoms).
2. `datahike.writing/tx-ids-are-leased` (`jolt/tx-lease`): the store counter mints tx ids in blocks.
3. `datahike.union/union-db` (`jolt/union-db`): `UnionDB`, planner-eligible, with views as source sets.
