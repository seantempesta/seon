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
