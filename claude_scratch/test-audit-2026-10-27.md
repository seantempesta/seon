# Test pipeline audit, 2026-10-27

The selection's 30–42 s is a **full program import** (`test-runner/program-db` → `seon.source/import-tx`). It runs whenever a checkout's program store is new, and every schema change makes it new: `seon.test-store/kept-store!` names the store by `seon.db/schema-digest`, and 7 schema digests were on disk today. With a kept branch of the tree, the whole selection costs 2.5 s. Most of that is the reach graph, rebuilt from nothing on every plan, although the fork server and the agents already keep that graph up to date incrementally.

**Measured** in `.worktrees/test-audit`, under `nice -n 15`, at load 38–55, with one process at a time, through `seon.profile/measure!` (ticks, bytes, contracted calls; ms recorded only). Scripts: `stages.clj` and `cold.clj` in the shared side-session notes folder, `test-audit-scripts/` (§4). **n today:** T ≈ 977 tests (graph-index keys), F ≈ 2,700 live forms, about 660 schema entries, 856 tracked files plus submodules, R ≈ 359,000 result files (1.4 GB), C ≈ 1,000 candidate digests per plan, B = the kept tree branches per program store (2–3 in a fresh worktree; stores reach 186 MB of the 256 MB cap).

**A caveat on the evidence:** `bin/worktree` APFS-clones main's `target/`, `target/prove/*.log` included. The identical "selected in 30151 / 42301 ms" lines in many worktrees are copies of main's log, not runs of their own.

## 1. Every step, cost against estimate

| # | Step (path) | Reads / computes | Estimate | Measured today | Grows with |
|---|---|---|---|---|---|
| 1 | bin/prove → bin/test → `jolt -A:test run -m test-runner` | process start, require of runner + seon.* | O(program loaded) | ≈4.6 s wall (9.0 s total − 4.4 s of stages) | the program, every run |
| 2 | `refused-names`, `test-namespaces` | file-seq of test/ | O(test files) | <10 ms | — |
| 3 | `program-db`: `fx/tree-id` | `git read-tree HEAD; add -A` into an empty index: rehashes every file | O(tree bytes); with the repo's stat-cached index O(changed) | 0.33–0.73 s, 0.38 M ticks (0.22 s from a copy of the repo index, same id) | **the whole tree**, ×2 per plan, ×2 per shard |
| 4 | `program-db`: a kept branch of the tree | open LMDB, `d/branches` | O(1) | 0.38 s, 1.5 M ticks, 6 MB | — |
| 4b | … a new tree, refreshed from the closest branch | `git cat-file` + `git diff` **per kept branch**, then `refresh-tx` of the changed files | O(B × git spawn + changed files) | 1.0–1.5 s, 30–68 M ticks, 120–400 MB (B = 2–3) | B (store age) |
| 4c | … **no store at this schema digest: full import** | `tree-files` (git archive of all), `import-tx` of every form | O(F) analysis | **17.7–27.7 s, 2.6–3.9 G ticks, 8–24 GB allocated, 844 k calls** | **the whole program** |
| 5 | `fx/inputs` | tree-id again, `git ls-tree -r` of the tree, a `pin` git call per submodule, config | O(tree entries + submodules × git) | 1.05 s, 2.7 M ticks, 7.6 MB, 514 calls | the whole tree |
| 6 | `program/reach-graph` | every live form, schema entry, edge; SCCs; Merkle fold | O(F + E) | **1.65 s, 254 M ticks, 1.31 GB, 36 k calls** | **the whole program, every plan** |
| 7 | `program/graph-index` (tests) | per test: its components' digests, sorted, + config keys | O(Σ\|reach(t)\|) | 76 ms, 27 M ticks, 173 MB | T × reach |
| 8 | step index, `base-commit`, `step-digests` | 39 roots; `git merge-base`/`status` | O(steps) | ≈0.1 s | — |
| 9 | `proofs/verdicts` | 2 stat/read per candidate digest | O(C), **not O(R)** | 0.20 s, 3.3 M ticks, 21 MB (884 found) | C only |
| 10 | write shards, `binary`, `stall-bounds`, `tests.edn`; `measured-ms` | one file per test in target/prove/ms | O(T) | small | — |
| 11 | `bin/build seon` when any selected key reaches the binary | build from this tree | should be O(1) on an unchanged tree | "~30–45 s no-op" (bin/test comment; not measured here) | the whole program |
| 12 | 4 shards: start + require + `proofs/run-tests!` + `store-verdicts!` | the tests, then one file per result into a 359k-entry dir | O(tests run) | test time; tree-id ×2 per shard | — |
| 13 | `bin/check --steps`, `--store-steps` | each step boots target/seon on a clone of the seeded store | O(steps run) | per step | — |
| 14 | `--merge`: then `bin/check contracts` and `bin/check smoke` **again, unconditionally** | the same two modes the digested steps "contracts"/"smoke" already ran | 0 when green at digest | full cost every merge | — |
| 15 | test service (`seon.prove`, `my.agent/prove`, judge `:tests`, `root/run-tests!`, dashboards) | `seon.agent/test-plan`: `base-graph` reuses the fork server's main graph and `reach-update`s it | O(change) | 20–45 ms for a one-form change (docstring, 2,616 forms); graph 640 ms/816 MB when built | change |
| 16 | `:tests` effect (`seon.fx/run-tests`, the reconciler's `:gate`) | `bin/worktree --at` a fresh checkout, then `bin/prove --tests` there | steps 1–12 again | a fresh target clone: 4b, or 4c after a schema change | — |
| 17 | daily full run (`bin/test --all`, `bin/check 100`) | everything, by design | O(all) | ≈10 min (AGENTS §3) | intended |

**Gaps.** Steps 1, 3, 4c, 5, 6 and 11 cost the whole tree or the whole program on every run, whatever changed. Step 4c is the 30–42 s. 6 is the warm floor (1.3 GB per plan). No step's cost grows with the result-store size R: reads are per digest. R still grows without bound (1.4 GB, 349k greens), which is a disk concern, not a selection one.

## 2. Parallel mechanisms (one job, two or more ways)

| Job | Mechanisms today (callers) | Survivor | The others become |
|---|---|---|---|
| **The program as facts** | `test-runner/program-db` (test_runner.clj:83: a per-checkout LMDB store, one branch per tree, full import on every schema change; bin/test and the `:gate`'s fresh checkout) vs `seon.root/import-source!` (root.clj:754: boot and the watcher; agents read it) | root's import, which every agent already reads; the same `source/*-tx` | program-db, `tree-files`, `changed-paths`, the `tree-` branches: deleted |
| **The reach graph** | built whole on every plan (test_runner.clj:136) vs kept and updated by `reach-update` (fork_server.clj:55/82, `seon.agent/base-graph` and `branch-graph`, agent.clj:506/547) | the fork server's main graph plus `reach-update`: O(change) | test-runner's build: deleted |
| **Selection** | `plan-of` (test_runner.clj:251; no base, inline process sets at :141 and :269) vs `seon.agent/test-plan` and `run-selection` (agent.clj:576/767; base-relative, removed tests, proofs) | `test-plan` (both end in `proofs/plan` and `left-out`) | plan-of: deleted; bin/prove asks the service |
| **Running** | `shard!` (test_runner.clj:216) vs `run-tests-forked` (agent.clj:827) vs `seon.fx/run-tests` (fx.clj:545: `bin/worktree --at`, then bin/prove in it) | the test service (`seon.prove`), whose run ends in `proofs/run-tests!` | bin/prove and the `:gate` become callers of the `prove` op (seon_prove exists); the shard processes stay as the service's way to run in parallel, if measured to pay |
| **Results** | files (`store-verdicts!`/`verdicts`: shards, store-steps, agents through `:file`; read by plan-of and `status-at`) vs `:seon.proof/` facts (`result-tx`, the only writer; read by `stored-results`, problems, ui). `found` merges them for agents only; **bin/prove never reads facts** | one shared results store (the tests-are-facts session's design, option B) | test-results/ files: deleted after one migration-free switch (a result is regenerable) |
| **Tree id** | `seon.fx/tree-id` (fx.clj:270; ×2 per plan, ×2 per shard) vs `bin/tree` (bin/build, bin/check) | one, fed from the repo's stat-cached index | the other calls it |
| **Per-test ms** | `target/prove/ms` files, `shard-*.time`, a result's `:ms`, `:seon.proof.run/ms` | the result's `:ms` (it is in the results store) | ms-dir: deleted |
| **A merge's contracts and smoke** | the digested steps "contracts" and "smoke" in `bin/prove --merge`, then `bin/check contracts` and `bin/check smoke` again, unconditionally (bin/prove:71-72) | the digested steps | the two reruns: deleted |
| **Whole-program digests** | `reach-index` and `reach-digests` (no production callers; they equal `graph-index` over a fresh graph) beside `graph-index` | `graph-index` | test-only helpers, or deleted |

Not duplicated: `proofs/run-tests!`, `proofs/plan`, `proofs/left-out`, `graph-index` and `fx/inputs` each have one implementation. **Missing:** no code runs the "daily" full run. The word appears only in a comment (program.clj:1100) and in AGENTS §3. That is a task (the cron proc), not a fix here.

## 3. Ranked fixes

Each fix's proof is a cost proof of selection, `(plan k-changed-forms)`, at program sizes [100 1000 3000] forms. Its ticks and bytes must be flat in the forms that did not change and grow only with k.

1. **bin/prove selects through root's program and the test service, not a second program db.** Before: 2.5 s warm, 18–28 s after any schema change (2.6–3.9 G ticks, 8–24 GB), and the same again in every fresh `:gate` checkout. After: root's watcher import, which is already paid, plus `reach-update` on the change (20–45 ms per form, per agent.clj:513) plus `graph-index` (76 ms). Deletes: program-db, tree-files, changed-paths, tests-of, plan-of, and their store and config keys (`program-store`, `program-store-max-bytes`), about 200 lines. Needs: a root on the checkout (`bin/root start`), or the service run in-process, which is root's ruling.
   **Interim, a few lines, if 1 waits:** name the kept store by the digest of the program's own schema entries (`:form/*`, `:var/*`, `:ns/*`, `:schema/*`), not all of `seon.db/schema`. A change to any other entity schema then stops forcing a full import (7 of today's runs). Proof: a schema change outside the program's attributes leaves `program-db` at step 4/4b cost (≤1.5 s, ≤70 M ticks).
2. **Keep the reach graph per tree and update it.** This is folded into 1 if 1 lands. Before: 1.65 s, 254 M ticks, 1.31 GB on every plan. After: O(change). Deletes the whole-program build from the plan path.
3. **Drop `--merge`'s unconditional `bin/check contracts` and `bin/check smoke`.** They are already digested steps of the same run. Before: both run on every merge, whatever their digests hold. After: 0 when green at digest. Deletes 2 runs and their logs from `merge_verdict`; the verdict reads the steps' own lines. Proof: a merge whose change reaches neither step runs neither (plan line: 0 steps).
4. **`bin/build seon` on an unchanged tree must cost O(1)**, a build reused by its sources (§5). The comment says 30–45 s, and it runs whenever any selected key reaches the binary, that is on every merge. Measure it first: target/seon.tree against the tree, and what the no-op build re-reads.
5. **One tree id, from the stat-cached index**, computed once per plan and handed to the shards for their before-check. Before: 0.33–0.73 s × (2 + 2 × shards). After: 0.22 s once, plus the after-checks. Deletes the second implementation (bin/tree or fx/tree-id).
6. **One results home** (tests-are-facts). Today a green that the service recorded only as a fact is invisible to bin/prove, which is lost reuse. After: one AVET seek per digest, read by every planner (`proofs/found` everywhere). Deletes test-results/ (359k files, 1.4 GB) and ms-dir.
7. **Delete `reach-index` and `reach-digests`** (or move them into the tests), and replace test-runner's inline process sets with `program/process-tests`.

Order: 3 and the interim of 1 are hours, independent and small. 1+2 is the real fix: it deletes most of test_runner.clj's planner and the program store. 4 needs a measurement before a design. 6 is in flight in the tests-are-facts session. 5 and 7 are cleanups that follow 1.

## 4. How it was measured

`stages.clj` runs `profile/measure!` around each stage of `plan-of` on the worktree's kept store. `cold.clj` runs `program-db` on (a) a copy of the kept store after one appended comment in src/seon/fx.clj, then src/seon/profile.clj, which is the refresh (reverted after; nothing committed), and (b) an empty store, which is the full import. Both run with `target/jolt/jolt -A:test run FILE` with bin/env sourced. Not measured: the no-op `bin/build seon`, the shard and step run times, the `:gate` checkout end to end, B in the 186 MB stores (another checkout's store was not opened), and an independent re-run of any number.

## 5. Wastes not in root's six assignments (for root to assign)

Root assigned these six: the kept program store → merge-readiness; reach graph at the cost of change → reach-index; fx/inputs → old-word; bin/prove asks the one service → one-wait; Jolt pin and whole config in digests → reuse; a per-run output dir with no cloned logs → ctx-atom. These are the rest:

| # | Waste | Estimate now → after | Fix and what it deletes |
|---|---|---|---|
| A | **`bin/build seon` after any change rebuilds the whole program.** One appended comment in src/seon/profile.clj cost 48.6 s (jolt build 45 s, user 41 s); a no-op is 2.35 s. css is also rebuilt on every tree change (3 s), although input.css did not change. This runs before any shard or step that reaches the binary, which is every `--merge`. | O(program) → O(changed units + their dependents) | Jolt's AOT cache should reuse unchanged units; find why the whole binary relinks or recompiles (a Jolt-side task, `jolt/<topic>`). Key css on its inputs, not the tree. |
| B | **`--merge` runs contracts and smoke twice:** once as digested steps, then unconditionally (bin/prove:71-72). | 2 full step runs per merge → 0 when green | Delete the two reruns; `merge_verdict` reads the steps' lines. |
| C | **Two tree-id implementations** (fx/tree-id, bin/tree), and tree-id runs 2 + 2×shards times per run, each re-hashing every file from an empty index. | 0.4–0.7 s × 10 → 0.22 s × 1 plus the shards' after-checks | One implementation, fed from a copy of the repo's stat-cached index (same id, measured). Could fold into old-word's fx/inputs work. |
| D | **Three per-test ms records:** target/prove/ms (one file per test per checkout), shard-*.time, and the result's :ms. | O(T) file writes per run → 0 | Deal shards from the results' :ms; delete ms-dir. Follows test-facts. |
| E | **Dead whole-program twins** `reach-index` and `reach-digests` (test callers only), and test-runner re-deriving the process sets inline instead of calling `program/process-tests`. | — | Delete, or move into the tests. Natural for reach-index. |
| F | **No daily full run exists.** AGENTS §3 and docs/merging.md promise "once a day"; no cron entry or code runs it (only a comment, program.clj:1100). Reach digests leave file reads and binaries to it, so without it those changes are never caught. | — | A cron message (the one allowed clock) that queues `bin/test --all` and `bin/check 100` on a clean main checkout. |
| G | **Results grow without bound:** 359k files, 1.4 GB, plus 160 stray `proof-tree-<uuid>.edn` files a test writes into the shared directory. Reads stay O(C), so this is disk, not time. | +~1,000 files per unreused run | test-facts' one results store; file the stray writer as a defect. |
| H | **The `:gate` builds a fresh checkout per merge** (`seon.fx/run-tests`: `bin/worktree --at`, then bin/prove): an APFS clone, a process start, a plan, and possibly a build (A). | one checkout plus a full plan per gated merge → a request to the service | Covered once one-wait makes bin/prove a caller of the service. Make sure the `:gate` is moved too. |

## 6. Tracking (re-measured as each fix merges)

The same method as §4, in this worktree after merging main, with `stages.clj` (a warm plan) and `cold.clj` (a new tree and an empty store). Reuse = keys found green at their digest, out of the candidates, on a tree one commit past main.

| Date | Main at | Merged fix | Warm plan | One-file refresh | Schema-change / empty store | reach-graph | Reuse | Notes |
|---|---|---|---|---|---|---|---|---|
| 2026-10-27 19:15 | 971aef7397 | baseline | 2.5 s, 184 M ticks, 1.1 GB | 1.0–1.5 s, 30–68 M ticks | 17.7–27.7 s, 2.6–3.9 G ticks, 8–24 GB | 1.65 s, 254 M ticks, 1.31 GB | 884 of 977 on main's own tree; 0 of 990 on main's merges (merge-log) | load 38–55 |
| 2026-10-27 19:56 | 9dbb90f8bf | kept-store-by-program-schema, inputs-hash-only-what-changed, test-plan-is-the-services, one-run-one-directory, jolt-pin-is-a-class, reach-graph-is-stored, tests-are-facts, contracts-on-main | 0.98–1.57 s plan-of, 67 M ticks, 420 MB (program-db 14 ms, inputs 0.33–0.38 s, reach-graph-of 0.27–0.35 s, graph-index 0.1 s, tree-id 0.3–0.5 s) | 0.74 s, 84 M ticks, 671 MB (after a main merge: lmdb pin and 1 commit) | **24.1 s, 3.3 G ticks, 15.8 GB**: a new program-schema digest (249fecc5) still imports the whole program | 0.27 s from reach-graph.fasl (was 1.65 s); 1.97 s when built | **0 of 885 candidates**: root's main holds 462 results from 54 runs and none at this tree's digests; the 359k test-results/ files are no longer read | one-function change (seon.profile/ms-since): 161 of 997 digests move. Jolt bump: `:seon.pin/jolt` alone moves 451 of 997, and `jolt-compiler-options` 197. Load 23–39. |

**Reading the row.**
- **Warm selection fell from 2.5 s to about 1 s, cold selection did not.** `kept-store-by-program-schema` narrowed the key, but the program schema itself changed today, and the full import that follows still costs 24 s once per checkout. Fix 1 (root's program, with no second program db) is still what removes it.
- **Reuse is 0 after `tests-are-facts`.** The results' home moved to root's main, and nothing carried the old greens over. Until main runs this tree, every checkout runs about 880 tests. Neither main's verification nor a migration-free reseed has run yet. A results gap like this should be expected at every switch of the results home, and stated in the merge.
- **A Jolt bump still moves 45% of digests** (451 of 997). That is all tests whose reach touches a Jolt-pinned namespace. Whether 451 is honest (they do reach Jolt code) is reuse's call.
- `ask-root` answered `:refused` once in four tries while root was up, and then said "nothing is reused". A refused connection reads as "no results", with no retry or error fact.

### Per-merge watch (owner: "stay on top of the perf and the lines of code")

| Merge (main) | src delta | Names what it replaced | Perf change |
|---|---|---|---|
| 04359bea96 agent-repl (tip) | +13 −5 | n/a (small) | none measured |
| 964414bb18, c6b36bac67 one-ms-record | +18 −3 | yes: ms recorded once (cut D) | warm selection 0.98 s, same |
| e0b2437872 wiki-reads-agents-and-readme | +10 −6, 1 key | n/a | none measured |
| 558224bbf9 an-outside-agent-lives-until-done | +20 −10 | n/a | none measured |
| 49f8c4e2de a-daily-full-run-is-the-net | **+192 −18, new ns seon.cron, 1 key** | nothing (fills audit gap F; seon.cron is AGENTS §4's one cron proc) | no common op touched |
| **c10b0c19e1 relevance-lab** | **+526 −146 (net +380), 9 keys added, 3 removed** | nothing in the merge subject. It replaces search-relevance, search-min and related-rarity, but adds a second BM25 (seon.context/bm25) beside seon.docs/tokens | turn's request build 0.60 s, 53.5 M ticks, 250 MB (no earlier baseline) |
| 85dbf9a6ce agent-repl | +139 −70 | not named | none measured apart |
| 7177cc5899 process-tests-reach-their-ops | +143 −97, 1 key changed | yes (the seon.main/-main edge) | — |
| 9079c82fd2 an-old-word-is-a-problem | +137 −17 | yes (docs-test's whole-tree scan) | — |
| 7274786a42 an-outside-agent-proves-its-process-tests-at-the-judge | +77 −8 | not named | — |
| 3dd370f760 gate-skips-the-test-that-started-its-root | +36 −9, 1 key (`#env SEON_GATE_SELF`) | not named | — |
| 88130af42e refresh-reads-only-what-it-must; 3366b9a66e reach-step-builds-in-bulk | +32 −15; +39 −13 | yes; not named | **full program import 24.1 s / 3.3 G ticks → 10.0 s / 2.89 G**; reach-graph-of 0.21 → 0.19 s warm |
| 2f254a45c9 unarmed-needs-no-rebinding; 86cc4c9e36 search-has-no-sibling…; 078a9caee9 one-path-or-fail-loudly | +2 −39; +24 −57; +4 −7 | yes (cut: contract alter-var-root; seon.docs/exact and grep; the unsent queue) | — |
| 7eade14a94 jolt-keyed-by-source-group | +50 −26 | not named | Jolt digests are now keyed per source group. My bump simulation moves every group, so it no longer shows a real bump (769 of 1033) |
| 33 other merges, c6b36bac67..ddcfa0be02 | each ≤ +43, most ±15 | mostly named | — |
| **range total c6b36bac67..ddcfa0be02 (46 merges)** | **+1548 −678 = +870 src, 32 files; context.clj +578/−?, server.clj ±260, program.clj ±230** | relevance-lab is 44% of the growth | see the baseline below |

### An agent's start heap (root 2026-10-28: "add start heap to the perf watch")

Start heap is the agent's heap when it is forked. The agent's heap cap is the start heap plus `:seon.config.agent/heap-extra-bytes` (1 GiB); seon.agent calls lib/heap-cap! at agent.clj:1154. It is read in a fresh agent as `(System/getenv "JOLT_MAX_HEAP")` minus 1 GiB. Each measurement below is one build, on a fresh store or the standing one, with one agent attached on task seon.proofs/prove-runs-what-a-change-reaches.

| Build | Store | Root heap / datoms | Agent start heap |
|---|---|---|---|
| c6b36bac67 | fresh | not read | 0.953 GB (cap 2 027 225 136) |
| 02236e4295 | fresh | not read | 0.969 GB (cap 2 042 937 488) |
| d96168afb1 | fresh | 1.04 GB / 159k before the attach; 1.13 GB / 171k after | 0.970 GB (cap 2 043 487 104) |
| d96168afb1 (root 24927, process-tests' reading) | standing runs/store | **1.44 GB / 353k** | **1.32 GB** (cap 2 390 927 728) |

**No merge grew it.** The build adds 16 MB between c6b36 and main, about 1.7%. The +350 MB comes from what the fork server holds, which is main's db, at about 1.8 KB of agent heap per datom. The standing store holds 182k more datoms than a fresh one:
- `seon.proof*`, +106k, 55%: the result facts of every test run root records (tests-are-facts);
- `seon.tx` and `db`, +48k, 25%: one transaction's metadata per write, 16k transactions;
- `seon.vector` +14k, `seon.task` +6.8k, `seon.run` +1.5k.

So an agent's start heap grows with every test run root records, and with every write, with no bound, while its cap's room stays 1 GiB. Its cost proof is missing: an agent's start heap should cost what the agent reads, not main's whole history of results.

### Common ops, re-measured at main ddcfa0be02 (2026-10-27 22:50, load 4–14, the worktree's own root on a fresh store)

| Op | At c6b36bac67 | At ddcfa0be02 |
|---|---|---|
| Selection, warm (plan-of) | 0.98 s, 69 M ticks | 0.66 s, 73 M ticks, 459 MB; reach-graph-of 0.19 s, graph-index 84 ms |
| Selection, cold (new program-schema digest 012c10f7) | 24.1 s, 3.3 G ticks | **10.0 s, 2.89 G ticks, 9.3 GB, 1.10 M calls** (a full import; −59% wall, −12% ticks) |
| Reuse at main's tree | 833 of 893 | **228 of 910**: root's main holds 300 results at these digests, because no `--merge` has run since the Jolt bump 7627453d (the 21:25 full run was on an older tree) |
| A turn's request build (seon.turn/context-block, in the agent, unchanged tree) | not measured | **0.60 s, 53.5 M ticks, 250 MB** for a 3.1 KB page |
| The agent's ctx pull (`:context`) | 7.1 s first; 0.37–0.39 s after | 18.9 s first (a fresh store: attach, fork, first page); then 0.81 s and 0.54 s, 6.3 and 3.7 KB |
| `(my.agent/prove)`, no change | 144 ms, then 11 ms | 128 ms, 42 M ticks; then 8.8 ms |
| `(my.agent/prove)` after a one-function change (ms-since) | 8.7 s; 48 ran, 14 reused, green | **30.6 s, red: ran 51, deferred 125, reused 14; 44 errors, each OutOfMemoryError** (the heap ceiling of 2 044 014 496 bytes, live just above it: the agent's own heap plus `:seon.config.agent/heap-extra-bytes` 1 GiB, seon.agent at line 1154) |
| `bin/build seon`, catch-up after the Jolt bump | 32.2 s (back to a built tree) | 50.0 s (a new Jolt) |
| `bin/build seon`, no-op | 1.0 s | 0.99 s |

The store from 20:52, made at c6b36bac67, broke `:context`: the agent answered `:transact/schema` "Bad entity attribute :form/ops", an attribute that 7177cc5899 added. On a fresh store it works. The old store is kept at runs/kept-2052 in this worktree.

### Common ops, baseline at main c6b36bac67 (2026-10-27 20:50, load 9–17, the worktree's own root)

| Op | Measured |
|---|---|
| Selection, warm | 0.98 s plan-of (69 M ticks, 426 MB); reach-graph-of 0.21 s; **reuse 833 of 893** (main ran this tree) |
| Selection after 6 merges (22 program files changed) | **program-db refresh 10.5 s, 2.19 G ticks, 11.7 GB, 1.49 M calls**: two thirds of a full import (3.3 G) for 22 files, against 84 M for one commit's change |
| Selection, cold (new program-schema digest) | 24.1 s, 3.3 G ticks (19:56) |
| An eval, `(+ 1 2)` at root | 8.8 k ticks, 56 KB |
| The agent's ctx pull (`:context`, task seon.proofs/prove-runs-what-a-change-reaches) | first, with attach and fork: 7.1 s, 46.6 KB; then 0.37–0.39 s, 3.1–5.7 KB (ticks not read: the op returns none) |
| `(my.agent/prove)`, no change | 144 ms, 40 M ticks first; then 11 ms, 1.8 M ticks; 15 reused |
| `(my.agent/prove)` after a one-function change (seon.profile/ms-since) | 8.7 s, 62 M ticks in the agent; 48 ran, 14 reused, 62 process tests to the judge |
| `bin/build seon`, no-op | 1.0 s |
| `bin/build seon`, one file (a comment in src/seon/profile.clj) | **37.7 s** (user 33.7) |
| `bin/build seon` back to a tree built a minute before | **32.2 s**: no binary is kept by tree, so every return rebuilds |

## 7. Independent audit of today's merges (AGENTS §2: every 10 merges or daily)

**What was read.** Main took 190 first-parent commits today. src, test and bin went +9,495 / −4,320 lines, net +5,175. Two Sonnet auditors that had not seen the work read 36 merges against §1, §3 (earns its place, value review) and §9:
- the 18 test-pipeline merges;
- the 18 merges that grew src most outside the pipeline.

I checked the cuts marked ✓ against the code myself. A claim I could not confirm is marked "unverified". There are no clocks and no old §7a words.

**Ratings.**
- **Overbuilt:** reach-graph-is-stored and cost-bytes-are-the-calls-own.
- **Questionable:** tests-are-facts, jolt-pin-is-a-class, inputs-hash-only-what-changed, tests-stream-and-continue, a-process-tests-digest-moves…, a-test-result-is-reused…, a-write-back-is-never-lost, one-search, health-in-context, agent-repl, outside-repl-surface and an-outside-agent-is-a-first-class-worker.
- **Worth it:** the rest, including render-walk (+679 lines: it deletes the parts layer, 9.5–21.8 M → 0.76 M ticks a new eval), test-plan-is-the-services (it deletes the selector twin), prove-is-the-one-runner (net 0 over 14 files) and observe-reads-are-renders (it deletes merge-readiness).

**What to cut, ranked by value against risk:**

| # | Cut | From | ≈ lines | Replaced by |
|---|---|---|---|---|
| 1 | test_runner's own `program-db` (test_runner.clj:83-125). Its stored graph file `builder-id`/`reach-graph-of` (126-156, keyed by a hand-rolled digest of seon.program's forms) is a stored copy beside a value, which exists only because the runner keeps a second program. ✓ | tests-are-facts, reach-graph-is-stored | −75 | root's program and fork-server graph, through the service (fix 1). This removes the 24 s cold import too. |
| 2 | The **unsent queue**: `ask-root`/`no-root`/`unrecorded?`/`keep-unsent!`/`send-unsent!` (197-260), plus `unsent-dir`. When no root took the results the run stays green ("kept, not lost, so the run is not red", :254). That is a fallback instead of an error fact (§9), and a hand-built retry queue. A refused `:results` ask likewise answers "nothing is reused" with no fact, as measured in §6. ✓ | tests-are-facts | −60, 1 key | one request to the service; no root is a red run that names it |
| 3 | `contract/disarm!` and `unarmed` (contract.clj:285-301): `alter-var-root` over every armed var, process-wide, to measure a cost proof's bytes. That is a §5 code smell, with a residual error the docstring admits. ✓ | cost-bytes-are-the-calls-own | −35 | the cost-proof harness measures under one declared arming state |
| 4 | `problems/health-moved` (problems.clj:712) with `split-rows`, `touched-runs`, `health!`: a second, incremental health path beside `health`, with no number in its merge. ✓ (the code exists; "no number" is from the commit) | health-in-context | −150 | `health`, unless the branch's report shows the saving |
| 5 | one-search's unmeasured keys: `search-weights` (all 1.0), `search-relevance`, `search-min`, `context/relevance` and the `:min` filter. `docs/exact` and `my.agent/grep` still sit beside `search`. ✓ (weights all 1.0) | one-search | −45 to −75, 3 keys | add them back with embed-lab's measured selector, and fold exact and grep into search |
| 6 | `config-key-readers` (program.clj:1044), a hand-kept list of one reader. A reader that is not on it moves no digest, which is a silent hole. ✓ | a-process-tests-digest…, a-test-result-is-reused… | −25 | the key declared at the reader's definition (reuse's area) |
| 7 | `server/fork!` and the `:from`/`:fresh` MCP arguments repeat `attach!`'s run start (unverified) | agent-repl | −35 | `attach!` on a new task |
| 8 | `:seon.render/summary`, `summarized`, `state-summary` for one value, and `repl/equip-ns!`, which fixes at the reader what `make-ns!` should produce (unverified) | outside-repl-surface | −55 | `agent/state` printing small at its producer; `make-ns!` |
| 9 | `prune-runs!` (test_runner.clj:369) and `runs-kept`: a second disk-pruning beside docs/machine.md's | one-run-one-directory | −12, 1 key | machine.md's pruning |
| 10 | The env expression repeated at reconcile.clj:281 and :494, fixed at the reader (unverified) | an-outside-agent-is-a-first-class-worker | −5 | `attach!` records the env |
| 11 | `SEON_SOURCE_BRANCH` as `#env` in config (§9: an env var instead of config) | a-write-back-is-never-lost | −5 | a config key set per checkout by bin/worktree |
| 12 | The twin prewalks `placed-units`/`placed-hiccup` (unverified) | render-walk | −15 | one walk |

Not a cut: `my.root/hold!`/`take-back-tx` has no caller in src, but my.root is root's REPL surface, so its callers are root's evals. Process notes: watcher-source-reload was merged, reverted (5731149001) and merged again with the same diff, and the revert states no cause. Several merges from the 48d650df60 → 13605b6341 → f6ea4caf85 chain undid each other's config-read code within hours, which is the "half-baked plan that got built" of §9. **Total: about −540 lines and −5 config keys**, cuts 1 and 2 being most of the test pipeline's.

