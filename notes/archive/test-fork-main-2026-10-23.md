# Why can't a test run cache and fork main (or any commit)?

Read-only research, 2026-10-23, main 356759dc. Nothing was built or run. Numbers marked M are measurements quoted from the cited source. E marks my estimates.

**Short answer.** Nothing fundamental stops it. Every hard rule of fork, LMDB and Chez that applies here is already handled in `src`, because Seon's own agents already do (a): they fork from a process that holds main's db, its compiled program and its reach graph. They load only the forms that differ (`load-diff!`, `load-changes!`), update the graph by what changed (`reach-update`, 20-45 ms M), and run tests in a fork of themselves. `bin/test` uses none of this for two reasons:
1. it was written as a stand-alone cold process, before `after-fork!` existed, and its docstring still says a fork cannot connect to a store;
2. a Claude worktree's change is files in a checkout, not a branch in a store, and nothing turns "this git tree" into "a branch holding its program".

That second piece, a program db per git tree that is branched from the nearest one we already have and brought up to date by `refresh-tx`, is what (b) needs, and what (a) needs for any commit other than main's head. Nobody has built it yet.

---

## 1. What `bin/test` does today, and where the time goes

Flow (`bin/test`):
- `runner --plan` is one cold Jolt process in source mode, `target/jolt/jolt -A:test run -m test-runner` (bin/test:24). It writes the shards.
- `bin/build seon` runs only when a selected key reaches the binary (bin/test:39-42).
- One more cold Jolt process runs per shard, all at once (bin/test:47).

The plan process (`test/test_runner.clj`):
- `program-db` (test_runner.clj:36-42) reads every `.clj` file under src and test (`fx/read-source`). It takes `ts/test-db`, an **in-memory** store seeded with 2,000 example datoms (test/seon/test_store.clj:9-15, 17-22), and calls `(d/db-with db (source/import-tx db files))`. That is a full analysis plus a full transact on every run. Nothing is kept.
- `tests-of` (test_runner.clj:44-58) runs `program/reach-graph` over that db, then `graph-index`.
- `plan-of` (test_runner.clj:165-186) runs `graph-index` again for the check steps (line 176), then `proofs/greens` against `../test-results`.

Cold costs, from `git show jolt/agent-view:resources/seon/tasks/seon.derive.edn` (pure-tests' figures, main 1343c920, M):

| phase | time | alloc |
|---|---|---|
| read src+test (113 files, 1.9 MB) | 9 ms | |
| `ts/test-db` (seed) | 566 ms | 675 MB |
| `source/import-tx` (626k contracted calls) | 2.2 s | 3.5 GB |
| `d/db-with` of 11,860 ops (75,683 datoms) | 5.4-6.3 s | 3.6 GB (about 48 KB a datom) |
| `reach-index`, run twice a plan (tests, then steps) | 650 ms each | 0.9 GB each |
| `fx/inputs` / greens | 29 ms / 2 ms | |

That is about 9.5-10.5 s on an unchanged tree, roughly 60% in `d/db-with`. The brief's 72% fits the higher db-with figure. The root cause of db-with's cost is Datahike's add path: ``jolt/datom-cost`` 16899617 measured 7.2k ticks and 11.1 KB per plain datom on the merged tree (M). The Store group is on it now (docs/plan.md, "In flight").

Other per-run costs, from `notes/root/test-time-2026-10-23.md` (M):
- Each Jolt process start was 11.3 s. Jolt ``jolt/aot-cache-hit`` 5edda163 has since fixed that ("a start 7.3 s to 0.6 s", docs/forks.md:61).
- A shard spends 2.9-9.1 s requiring its namespaces before its first test.
- A no-op `bin/build seon` costs 30-46 s when a selected key reaches the binary.

## 2. What forking already gives us

**How the fork server works** (`src/seon/fork_server.clj`, docs/processes.md:3-13):
- Root forks it at boot, while root has one active thread.
- It is single-threaded. It holds main's db value, its compiled program and main's reach graph (`:graph`).
- It applies each stored main commit (`d/commit-as-db` plus `program/load-changes!`), so every agent forked afterwards inherits that program compiled, copy-on-write.
- It forks an agent in about 0.8 ms (M, notes/root/fork-test-merge-design-2026-10-22.md §0). An agent costs about 5 MB of its own and 20-30 MB of system cost (M, same).
- An agent's tests run in a fork of the agent (`seon.agent/run-tests-forked`, agent.clj:484). The plan uses the base graph plus `reach-update` (`base-graph`, agent.clj:317-336: graph 640 ms and 816 MB built once, a one-form update 20-45 ms, M).
- The prove service already runs any branch's tests through this path (`seon.prove`; seon_prove MCP). For a branch no agent holds, it spawns a prove agent (prove.clj:82).

**What a forked child inherits, and what breaks**

| resource | after fork() | handled by | fundamental? |
|---|---|---|---|
| Heap, compiled code, loaded namespaces, db values (index nodes in the node cache) | copied copy-on-write. On macOS arm64, MAP_JIT code would be private, not COW, so it is moved out of MAP_JIT by a static collect (Chez patch 0007, chez/c/segment.c:755-762). The child re-protects JIT pages before returning to Scheme (patch 0006, pthread_atfork, chez/c/thread.c:25-42) | ours, done | no |
| LMDB env | "Use an MDB_env* in the process which opened it, not after fork()" (reference-code/lmdb/libraries/liblmdb/lmdb.h:102) | `seon.db/after-fork!` (db.clj:326-334) forgets the parent's env and the Datahike connections, then the child calls `d/connect` again (fork_server.clj:129, agent.clj:703) | the rule is fundamental, the fix exists |
| Datahike connections and writer go-blocks | parent's state, not valid in the child | `reset! datahike.connections/*connections*` (db.clj:334) | done |
| the ids page (mmap) | shared, which is intended (storage.md "Ids") | nothing needed | no |
| Threads, Jolt fiber carriers, timer, shutdown watcher | fork copies only the calling thread; the bookkeeping of the others is stale | `jolt-after-fork-child!` (reference-code/jolt/host/chez/java/fibers-async.ss:510-532), called by `proto-fork-alone` (src/seon/lib.clj:134-143) | done |
| Forking with more than one active thread | refused (-2) | `proto-fork-alone` | yes: physics, a fork only from a single-threaded process |
| Chez parallel-GC sweeper threads | a child of a process that ever started sweepers hangs in its first multi-threaded collection | rule: forking processes never start them (docs/failure-points.md:81-96). Root fixes them at 0 (`gc-sweepers!`) and never forks after boot. "The real fix is a Chez atfork handler", not built | yes: root can never be the forker. The fork server and agents can |
| Sockets and fds | inherited. Each agent gets its own socket over SCM_RIGHTS | done | no |
| konserve memory stores (the test store) | copied as a value. Connections are reset by after-fork! | `ts/test-branch!` makes fresh branches | no |

So the claim in test_runner.clj:10-11, "a fork of this runtime cannot connect to a store: Datahike's connection state does not survive fork", dates from d1f8d223 (2026-10-20). It is stale: `after-fork!` and `jolt-after-fork-child!` fixed it, and agents connect after fork every day.

**What compiled code cannot do:** it cannot be stored. Jolt units are 15 times their source, hold absolute paths and are valid only under one runtime (docs/storage.md "Compiled code"). So a process gets a program by inheriting it or by loading the forms it lacks. A fork therefore gives you the program of whatever its parent holds. Any other commit is that program plus `load-diff!` (program.clj:799). Getting a commit's program is `d/commit-as-db` (0.7-0.9 ms, M) plus `load-diff!` (1.6-5 ms a form, M).

## 3. Can the store hold the program db as a branch?

Yes. Datahike supports everything this needs:
- `d/branch!` from a commit id or a branch (reference-code/datahike/src/datahike/versioning.cljc:224-249) writes a head plus the roster, not a copy.
- `commit-as-db` and `branch-as-db` read any commit (versioning.cljc:668, 698).
- konserve nodes are content-addressed, and `datoms-diff` skips shared subtrees by address (storage.md "Merges read what changed").
- Program entities take **content ids**: `:form/id` is the hash of the namespace and text, and the entity id is the hash of the identity (storage.md "Content ids"). The same form in two commits is the same entity and the same datoms. Commits that share files share datoms, and on one store they also share index nodes wherever their trees branched from each other.

**What applying a diff costs** (`seon.source/refresh-tx`, source.clj:355-383; task `an-import-reads-only-changed-files`, M):
- A body edit in a 29-form leaf namespace: 33 ms (read and analysis 20, interface gate 2, diff and tx 9) plus 3 ms to transact. The full import was 151+25 ms on that probe's db.
- An edit in seon.edn, with 21 dependents: 2 ms.
- The cost stays flat as the program grows.
- Worst case: an interface change (a macro, protocol, ns form) in a widely required namespace re-reads every dependent. That approaches the 2.2 s analysis, but still transacts only the forms that differ.

Against the full import's 7.6-8.5 s, that is roughly a 100-250× saving for a typical change.

**What is not built:**
- Root's own boot import still calls `replace-tx` over the whole tree (root.clj:645). The task's "Left:" item is recording each file's digest beside `:ns/file` and calling `refresh-tx`.
- Nothing maps a git tree or commit to a store branch.
- bin/test's store is in-memory and per-process (test_store.clj:11), so nothing survives a run. In-memory nodes also have no addresses, so diffs there share nothing (storage.md "Merges read what changed").

**Blockers for one durable store shared by many processes:**
- **Branch roster race.** `d/branch!` holds a lock of its own process only. Two processes branching one store at once can drop a branch (storage.md:41; task `seon.db/two-processes-branch-one-store-at-once`). Not built yet, and it is a real blocker for one store shared by several worktrees.
- **Collection.** It runs only as the store's sole writer, and `sole-writer?` is false under `:scripted` (storage.md:129). A shared store is never collected, so a program-db store grows until the owner reclaims it.
- The agent-view task records that "one shared Datahike results store races its branch head across writers" was **rejected** (root and agent-view, 2026-10-23), and so was "reading main's store through main root's server" ("makes every bin/test depend on main's root").

## 4. What v1 did (~/src/seon, read-only)

v1 neither re-imported nor forked an OS process. `bin/test` sent a request to the **running JVM** over its prepl, and "no JVM starts" (~/src/seon/bin/test:1-27). The live JVM already held the loaded program.
- `seon.test/clean-request-handle` (~/src/seon/src/seon/test.clj:2318-2345) made a **checkpoint branch named by the digest of its inputs** (base commit, cluster, effective config, process). Every request with equal inputs shared one preparation. A request that found none prepared one and named the checkpoint from it ("the first name stands").
- Each request then took a private overlay branch off the checkpoint and wrote `program-overlay-tx` (test.clj:2001-2020): only the candidate's digest differences from the loaded program.
- SCI interpreted the changed definitions over the loaded program.

In this codebase's words, v1 did (a) and (b) together: a content-keyed program branch plus a diff, run in a warm process. Its cost was a JVM per host and SCI interpretation of changed code.

## 5. Earlier attempts in git

- `notes/root/fork-test-merge-design-2026-10-22.md` §2, steps 4-6, proposed exactly this:
  - run tests in forks of a warm holder, `k` at a time;
  - `bin/test --reach` becomes `seon prove [NS...]` on the built binary, "no 9.85 s jolt import";
  - "Import incrementally, by file digest".

  What became of it:
  - Step 6 became `refresh-tx` (b855b30f, c09db768; merged via 8bc65dfd). It was never wired to bin/test or to root's boot import.
  - Step 5 became the prove service (862d47e6, decd5e1c), for Seon agents only.
  - For a worktree, the note's blocker was §2 gap 5: the standing root must import test/. That is now done through the main tree's config/local.edn (`:seon.config.source/dirs ["src" "test" ...]`, root `../next-merges`).
- 40564b2c had the fork server replay the boot import with `d/with` (3.5 s). It now reads stored commits instead. That fixed main's replica, not test runs.
- The agent-view task `one-content-keyed-derivation` (on ``jolt/agent-view``, not on main) folds in pure-tests' dropped task "the-plan-reuses-each-files-analysis-by-its-digest". The owner's rulings stop it on "cold first" and "if it's fast enough we don't need caching". It waits on the owner (docs/plan.md "Waiting on the owner").
- No commit tried to fork bin/test's shards or to keep a program db across runs.

So it stopped for three reasons: rulings (cold path first; no shared results store; no dependency on main's root), the order of work (refresh-tx proven but never wired to a caller), and two unbuilt pieces (tree → branch, and test namespaces loading cleanly in the fork server).

---

## 6. The blockers

| # | blocker | evidence | kind |
|---|---|---|---|
| 1 | Fork only from a single-threaded process that never started GC sweepers; root can never fork | failure-points.md:81-96; lib.clj:134-143 | **fundamental** (Chez, OS). Already satisfied by the fork server and agents. A bin/test planner that forks must call `gc-sweepers! 0` first and have no active second thread |
| 2 | An LMDB env must not be used across fork | lmdb.h:102 | **fundamental**, already handled (`after-fork!`, db.clj:326) |
| 3 | Compiled code is not storable, so a fork has only its parent's program | storage.md "Compiled code" | **fundamental**. The way around it is `load-diff!` (exists) |
| 4 | Nothing maps a git tree or commit to a store branch holding its program | none exists; `program-db` re-imports (test_runner.clj:36-42) | not built |
| 5 | bin/test's test store is in-memory and per-process, so nothing persists, and in-memory nodes have no addresses (diffs share nothing) | test_store.clj:11; storage.md "Merges" | not built (an LMDB test store per checkout) |
| 6 | Several processes branching one store race the roster | storage.md:41, task `seon.db/two-processes-branch-one-store-at-once` | not built. Avoided by one store per checkout |
| 7 | Root's boot and watcher import is whole-tree `replace-tx` (4.5 s with test/, M) | root.clj:645; task `an-import-reads-only-changed-files` "Left" | not built (small) |
| 8 | Test namespaces in the fork server are loaded form by form from db, not built into the binary. Seen: load errors (`seon.source-test/diff-of`'s contract does not compile there). A restart is +5 s | tasks `test-namespaces-built-into-the-binary`, `every-test-contract-compiles-where-it-loads` | not built (defects) |
| 9 | Through root, results are keyed by shared inputs only when the binary was built from the tree (`built?`, root.clj:638). After a change loaded in place they are `:unshared`, so nothing is stored or reused across checkouts | root.clj:638-642; agent.clj:363-367 | design gap, not built |
| 10 | Tests that read files (`tree-readers`) read `:seon.config.source/root` from an agent working in a scratch dir, not the worktree's files | agent.clj (cwd), default.edn:259 | not built (config per run) |
| 11 | The agent's test fork is one child, not `:seon.config.proofs/jobs` shards | agent.clj:484-518 | not built |
| 12 | Owner rulings: no shared results store, no bin/test dependent on main's root, cold path first, no reuse of what is fast cold | seon.derive.edn on ``jolt/agent-view``; decisions.md | ruling, not code |

---

## 7. The options, simplest viable first

Per-run costs are E, built from the M phase costs above.
- Unchanged tree, today: about 10 s plan, plus shard boots of 0.6 s each and namespace requires of 2.9-9.1 s each.
- One-file change, today: the same.

### Option 0. Fix the cold path only (already in flight; the owner's first rule)
- What: ``jolt/datom-cost`` (Datahike's add path), import-tx's allocation, and one reach walk instead of two (test_runner.clj:176).
- Reuses: everything. Deletes: the second `graph-index` walk.
- Cost: unknown until datom-cost reports. If d/db-with halves and the second walk goes: about 10 → 6 s unchanged, and the same 6 s for a one-file change.
- Lines: fork commits plus about -3 in test_runner.
- It never reaches sub-second, because the import is still O(program) on every run.

### Option B. A program db per git tree, as a branch of one durable store per checkout, brought current by `refresh-tx` (the owner's (b), generalized to any commit)

**Make.**
- Key the branch by the commit's **tree id**: `git rev-parse C^{tree}`, which is git's own content address of the whole tree. Two commits with one tree share one branch. The working tree's id is `fx/tree-id`, already computed (`git add -A` into a temp index).
- On a miss, take the nearest kept tree: the fewest changed paths, from `git diff --name-only <kept> <want>` (a few ms each).
- Then `d/branch!` from that branch's head (head plus roster: ms), apply `refresh-tx` with those files, and transact.

**Per-file sharing.**
- Free across commits through content ids: the same form text is the same entity and datoms.
- Index nodes are shared wherever a branch was made from another.
- A separate per-blob analysis key is not sound by itself. A file's analysis depends on the interfaces of the namespaces it requires (macros, protocols). `refresh-tx` already follows them by its interface gate. A per-blob key would have to include those interface digests, so it is not worth building while `refresh-tx` costs 33 ms.

**Store.**
- The test store moves from `:memory` to LMDB at `target/` per checkout (one store, many branches, per checkout, as each worktree has its own stores). That avoids blocker 6.
- A fresh worktree seeds its store with a compacted copy of the main tree's (`konserve.jolt.lmdb-ffi/compact-copy!`, docs/forks.md:147) instead of a cold import.

**Reuses:** `refresh-tx`, `import-tx` (only for the very first tree), `d/branch!` and `branch-as-db`, `fx/tree-id`, `seon.db/open!`, the results directory.

**Deletes:** the per-run full import in `program-db` (test_runner.clj:36-42 becomes a lookup), and the in-memory seed per process for the planner.

**Cost (E):**
- Unchanged tree: 0.6 s boot + about 1 ms `branch-as-db` + one reach walk 0.65 s + 30 ms inputs ≈ **1.3-1.5 s** (versus about 10 s).
- A one-file body change on an unseen tree: + git diff about 10 ms + `refresh-tx` 33 ms + branch and commit about 5-20 ms ≈ **1.4-1.6 s**.
- A wide interface change: up to about 2-3 s of analysis, still far below today.
- The reach walk dominates. Storing the graph is not possible: it is an in-memory map, and the derive ruling says nothing is cached that is fast cold.

**Space (E, not measured):**
- One full program db: 75k datoms ×3-4 indexes, zstd, with `:form/src` values of 1 KB or more stored once ≈ 20-60 MB on disk.
- Each further tree costs the root-to-leaf paths of its changed datoms. Content eids scatter across `:eavt`, so a 20-form file is about 20-60 leaves per index ≈ 0.3-2 MB.
- So 1 GB of disk holds hundreds of trees. For comparison, the standing store is 224 MB with history (M, `du`). Memory is not the bound: a kept branch costs nothing until read, the node cache is 128 MB per process (`:seon.config.store/node-cache-bytes`, a thirty-second of `:seon.config.machine/memory-mb` 4096), and a run holds one db value.
- Collection: the per-checkout store has one writer at a time, so `collect!` can run. Deleting old tree branches is the owner's act (§9), so the store grows until reclaimed.

**Lines (E):** about +60 to +90 in test/ and bin (store open, the tree → branch lookup, nearest-by-diff) and about -10 in program-db. src grows only if root's boot import also switches to `refresh-tx` (blocker 7, about +20 / -5 in root.clj).

**Rulings it touches:** the derive task's "nothing cached that is fast cold". A program db per tree is a value in the store, but it is still reuse, so it needs root's gate (AGENTS §3) with the numbers above.

### Option A. Run through a warm fork server: the worktree's own root plus seon_prove (the owner's (a), and the end state)

**Make.**
- The worktree runs its own root (`bin/root start` in the worktree; it watches its checkout, docs/health.md "Every writer").
- Its watcher's source load becomes `refresh-tx` of the batch's paths (blocker 7).
- The fork server loads the commit in place (`load-changes!`).
- `bin/test` becomes a `:prove` request on the `tests` branch at main's t. The prove agent's `test-plan` does `reach-update` from the fork server's graph and runs `run-tests-forked`, sharded `k` ways (blocker 11).
- Any branch at any commit already in the store: fork from main, then `load-diff!` main → `commit-as-db C`.
- Any git commit not in the store: Option B's tree → branch step first, then the same.

**Reuses:** the fork server, `after-fork!`, `run-tests-forked`, the prove service and its joins ("a key whose run ended is answered at once"), `reach-update`, `load-diff!`, `refresh-tx`.

**Deletes:**
- test_runner's planner (`tests-of`, `plan-of`, `program-db`, `shards`: about 150 of its 259 lines) and bin/test's per-shard processes;
- the "a fork cannot connect" note;
- one of the two planners (the fork-test note's "two test paths").

**Cost (E):**
- Unchanged tree: the prove key is already answered, so **milliseconds**.
- A one-file body change: watcher batch → `refresh-tx` 33 ms + writer commit about 3 ms + fork server load about 2-5 ms a form + fork 0.8 ms + `reach-update` 20-45 ms ≈ **under 0.1 s before the tests**. Shard requires and boots disappear, because the program is inherited.

**Memory:**
- About 25 MB of system cost per forked agent or test child (M) plus pages the tests dirty (not measured). `k` = 4 shards is about 100-200 MB (E).
- A worktree root with test/ is 119-130 MB (M, task `test-namespaces-built-into-the-binary`).
- Five worktree roots ≈ 0.7-1 GB of the 4,096 MB budget (E).

**Lines (E):** about +80 to +120 in src (the prove request from a checkout, inputs for an unbuilt tree, the config root per run, `k` children, the boot import on `refresh-tx`), against about -150 in test_runner and -20 in bin/test. Roughly net negative.

**Before it works:**
- blockers 8 (test namespaces in the binary and clean contract loads) and 9 (results keyed when the binary is not built from the tree) must be fixed;
- it depends on a running root per worktree. The owner ruled that out against main's root, but a worktree's own root matches "Claude sessions use worktrees until Seon agents can do this work inside the system".

### Option C. The planner forks its own shards (independent of A and B)
- What: after `program-db` and the plan, call `gc-sweepers! 0`, require the selected namespaces once, then fork `k` children through `proto-fork-alone` plus `after-fork!`.
- Saves: per-shard boots (0.6 s each) and duplicate requires (2.9-9.1 s each, mostly CPU rather than wall).
- Does not touch the 10 s plan.
- Lines: about +30 / -10.
- Risk: the planner must have no active second thread at fork. Datahike's writer go-blocks are parked, which is allowed, but this is not verified in the source-mode runtime.

---

## 8. Recommendation

1. **Let Option 0 land first** (``jolt/datom-cost``, one reach walk). That is the owner's cold-first rule, and it is already in flight.
2. **Then Option B,** keyed by git's tree id, branched from the nearest kept tree, with `refresh-tx`, in an LMDB test store per checkout. It is the smallest change that removes the O(program) import, about 10 s → about 1.5 s. It answers "any branch, any commit" by construction: git's tree id is the content address, and content ids plus Datahike's branching share the work between commits that share files. It also avoids the rejected designs (no shared store across writers, no dependency on another root). Put it through root's gate with the numbers above.
3. **Option A is the end state,** where a test run costs milliseconds plus the tests. It reuses B's tree → branch step for commits not in the store. Do it once blockers 8 and 9 are fixed, as part of moving worktree sessions onto Seon agents.
4. **Option C** only if shard boots and requires still show up after B.

## 9. What I could not verify

- How large one program-db branch is on LMDB, and the bytes per one-file tree delta. Both are estimates; measure with one `d/branch!` plus `refresh-tx` on an LMDB copy.
- What `d/branch!` costs on LMDB (expected: ms, a head plus the roster).
- What `load-diff!` costs after its datoms rewrite. The only figure is "~1.4 s before" (program.clj:803-805).
- Whether a source-mode Jolt planner can fork safely after Datahike's writer has started (Option C). Neither its thread count nor its sweepers were checked.
- How many pages a test child dirties, so the copy-on-write memory per shard is unmeasured.
- Whether `refresh-tx`'s 33 ms holds on today's main. It was measured by pure-tests and not re-measured here.
- How far ``jolt/datom-cost`` will cut `d/db-with`. It has not reported a final number.
- Chez's own documentation says nothing about fork. The rules here come from our patches (chez/c/thread.c, segment.c) and docs/failure-points.md, not from CSUG.
