# Separation audit: the machinery that keeps Claude sessions outside the system (2026-11-14)

Read: principles.md and plan.md on jolt/root (b3b29c30bf); the bin/ headers; src and test on main (dee2de4438); all 90
`target/land/*.log`; shrink/DELETIONS.md, BLOCKERS.md, FABLE-ASSESSMENT.md, briefings/tests.md and runtime.md. No build,
test, prove or process was run. [M] = I read it or counted it, with its place. [H] = hypothesis, with what would settle it.
"Area" uses AGENTS §2's names.

## 0. The evidence in four numbers

- [M] **78 git landings since 11-12, and Seon's own merge last ran 11-12 12:55.** `git log --merges --since=2026-11-12
  main | grep -c '^Merge jolt/'` is 78. The last `Seon merge:` commit on jolt/merges is
  `my.agent/grep-leaves-out-the-askers-own-task`, 11-12 12:55. TURNS.md: 6 tasks, 419 turns, 0 merged.
- [M] **30 of 90 land logs end failed or with no verdict.** 27 end `MERGE FAILED`/`TEST FAILED` and 3 have no final line.
  Classified by error type in the log (table in §2.1): **at least 12 failed on the machinery, not the change.**
- [M] **Disk.** `.worktrees` is 107 GB (9 worktrees). The merge gate's `runs/merge-tests` is 9.9 GB, with 3 detached
  worktrees left behind (11-13 03:01, 11-13 18:46, 11-14 10:03). `target/results` is 769 MB in 129,520 files; its config
  bound is 512 MiB (`results-max-bytes`, tests' briefing).
- [M] **Root's finding, from today's live use.** A fresh agent's `(my.agent/prove)` for one docs edit ran 17+ minutes
  without answering. Context found `merge-main!` on the fixed main "moved the branch's base to main's head but applied
  none of main's changes (applied 0)".

## 1. The mapping: what a session does today, and the agent action that replaces it

| A session today | Replaced by (agent action) | State |
|---|---|---|
| Edits files in its worktree and commits them on `jolt/<area>` | `seon_attach` to its area's agent, then `seon_eval` with `seon.repl/edit!` (repl.clj:1049) or a `defn` eval; a file edited on disk is imported as a transaction on that agent's branch | **[M] attach, eval and edit! exist.** **Missing:** importing a file edit onto the session's *own* branch. The one import that exists, `prove-tree!`'s catch-up (system.clj:2205), writes onto a branch keyed by git tree id (`tree-branch` :2177) and drops that branch after the run (`tree-leave!` :2157). |
| Runs one test: `bin/test ns/name`, a fresh shard process | `(my.agent/prove 'ns/name)` (my/agent.clj:322) → `seon.agent/judge!` → the test service | **[M] Exists.** **Broken:** the test child is a fork of the agent (`test-child-runs!` agent.clj:1218), which is patched to look like a checkout (agent.clj:1254). The child's start checks only `:seon.agent/program-differs`. **[M, root]** A one-docs-edit prove ran 17+ minutes with no answer. [H] Cause: the docs change reaches about 104 doc-reader tests (BLOCKERS row 2), plus deferred process tests on the gate's checkout. Settled by the run's granted items and each one's time in root.log. |
| Proves: `bin/prove --merge` on the local shard path, inside `bin/land` | `(my.agent/prove)` / `seon_prove` on the agent's branch | **[M] Exists.** **[M] Broken by its own author's note:** bin/land:150-155 says the standing system "proves no landing", because the system's items "carry its loaded config and libraries". |
| Lands: `bin/land --check`, root's release, then `bin/land` | `seon_done`: the agent's end, the judge, the gate (reconcile.clj:996 `gated`), then Seon's merge into main | **[M] Exists, unused since 11-12.** **Broken:** the gate builds a detached git worktree of the candidate (`run-on-checkout`, fx.clj:1087, under runs/merge-tests) and proves it with `prove-tree!`. Seon's own merge therefore depends on worktrees and the git tree. |
| Merges main: `git merge main` plus `bin/worktree --sync` | `(my.branch/merge-main!)` (my/branch.clj:15) → `seon.agent/merge-main!` (agent.clj:524) | **[M] Exists.** **Broken three ways:** it runs on the branch's own program in the agent's process (agent.clj:524-551); it runs two three-way merges (`delta-three-way` and `task/carry-tx`, :546-548); one merge costs 62 s, 9.9B ticks and 47.5 GB (BLOCKERS, runtime). Root's "applied 0" finding is a fourth (§2, entry 13). |
| Restarts the system: `bin/update`, `bin/system`, `bin/build` | A merge loads in place (`program/load-changes!`); `my.root/landed!` (root.clj:402) keeps the system running when no restart is needed | **[M] Exists.** **Broken:** 3 of 4 kept updates restarted for forms and schemas, which can load live (runtime's briefing §2). Root's restarts killed proves in flight (runtime §3). |
| Reads results: `target/results/<ns>/<test>/<digest>.{pass,red}.edn` and `target/land/*.log` | `:seon.proof/*` entities on the store, read by query | **[M] Schema exists** (seon.facts.edn:192-199). **Missing:** every read and write still goes through files (`stored-results` fx.clj:762, `write-verdicts!`). |
| Git follows: `bin/land`'s `update-ref` and its perl bridge (bin/land:38-59), which writes the landed diff to main as data | `seon_write_back` → `my.root/write-back!` (root.clj:158), commits on jolt/merges; `my.root/sync!` (:216) | **[M] Exists, circular:** `sync!`'s docstring says its branch is landed "(bin/land)". write-back writes jolt/merges, never git's main: 157 commits ahead of main, 0 file differences. |
| Gets its own system: `bin/worktree`, `bin/ports`, `bin/system` per checkout | The one system on 7888; the session's agent on a branch | **[M] The system exists.** Per-checkout systems are what "temporary" in principles §2 means. |
| Learns that main moved: nothing tells it (web sat idle 13 h) | `my.root/landed!` sends `seon_message` to the waiting agents (plan 1.8) | **Missing.** |

## 2. The fragile machinery, one entry each

### 2.1 What the land logs say [M]

| Class (what the log shows) | Lands | Logs |
|---|---|---|
| Harness: `:seon.test-store/no-process-store` on 193-194 tests | 4 | 1112T143838, T150847, T182522, T212357 |
| Harness: a shard's agent tests error together, with `OVERRAN`, `:seon.system/ended` and `:seon.merge/unmerged` ([H] a shard's test root ended under them; settled by the shard-3-rest log's first error) | 4 | 1112T222648 (153 reds), 1113T002917, T005723, T013908 |
| The runner crashed while making its plan (`nio-open-output-port` in `reads-index-of`, test_runner.clj:235) | 1 | 1112T224323 |
| The runner and the program disagree (`No such var: fx/test-budgets` on every test) | 1 | 1112T165332 |
| Flaky at a digest (the memory gauge), which counts as red until the digest moves | 1 | 1114T072722 |
| Only quiet-time stalls (load 11) | 1 | 1112T220651 |
| A shard exited 1 with no report | 1 | 1113T135128 |
| A check step was red (`test-inputs`) on a run where the runner digest moved and 1,982 tests ran | 1 | 1114T082304 |
| Reds the change could own; 5 of them are cost tests (vector, task, db, render, program), [H] some of those load | 14 | the rest |
| No final line | 2 | 1112T215507, T222344 |

Across all logs there are 66 `STALLED` lines, at loads up to 105.9. They come from bin/stalls, which names a test that
has been quiet past a bound on its wall time. A machine that is admitted beyond its cores makes them.

### 2.2 Entries

1. **bin/land (196 lines).** It merges main, rebuilds Jolt, runs health and laws, then runs a *local* `bin/prove --out
   --merge`. It moves main by compare-and-swap. A perl bridge hand-writes the diff as EDN and sends it to root's eval; then
   it runs bin/update and bin/full-run.
   How it fails:
   - [M] the classes in §2.1;
   - [M] a main that moved during the proof is merged in "without proving again" (bin/land:166-174), and the full run is
     red at 41c2e79edc (tests' briefing);
   - [M] the docs path runs only health and seon.doc-test, and main went red at 4af7a1f2ae (BLOCKERS row 1);
   - [M] a bridge that is refused leaves "LANDED, ROOT NOT UPDATED" (bin/land:184).

   **Replaced by** `seon_done` plus git following the store.
2. **bin/prove (193 lines).** It has three modes: ask the standing service, the local shard path (`--out`, `--tests`,
   `--checks`), and `--root PORT`, which writes marker files under `results/proved/`.
   How it fails: [M] two routes give two verdicts for one digest. Runtime's `program-test` costs 91k ticks under bin/test
   and 2.7M in the service (runtime briefing §3). A red plus a green at one digest is "flaky", and stays red until the
   runner digest moves (proofs.clj:280-291).
   **Replaced by** `my.agent/prove`. The script becomes a thin wrapper over `seon_prove` or is deleted.
3. **bin/test and test/test_runner.clj (177 + 955 lines).** Shard orchestration, its own plan, its own report fold, and
   four store makers (DELETIONS rows 1, 2, 9, 10, 16).
   How it fails:
   - [M] §2.1's harness rows;
   - [M] `program-db` calls `remove-tree!` on a shared store whose key digests only the program's attributes. It removed
     the store under root's health and runtime's shard (test_runner.clj:~204, BLOCKERS row 4).

   **Replaced by** the test service.
4. **bin/stalls, bin/bounded, bin/check's bounded: three watchers that read wall time.** [M] 66 STALLED lines, load
   dependent. Principle 6 rules out a verdict that reads load.
   **Replaced by** the tick budget plus `arm-wall!`'s one timer (agent.clj:1199), which already runs in the service.
5. **bin/worktree (284), bin/ports (74), and bin/system per checkout.** 107 GB of worktrees, APFS clones of target/, a
   port lock file, and submodule syncing that must never be `git submodule`.
   How it fails: [M] a second system on one store is refused by nothing (bin/system header); leaked worktrees in
   runs/merge-tests (§0).
   **Replaced by** branches in the one store. **Stays at the edge:** `bin/worktree --at` for deps' forks, until the
   gitlink is an entity (FABLE §2; Stage 2).
6. **bin/update (115).** It merges main into jolt/merges, syncs submodules, and then either calls `landed!` or rebuilds
   and restarts.
   How it fails: [M] restarts that were not needed, and proves killed in flight (§1).
   **Replaced by** live loading. Stage 3.1 classifies the restarts. It stays for a binary change only.
7. **bin/build (376) in landing.** bin/land rebuilds Jolt on every land because a merge can move a pin (bin/land:120-125).
   **Stays at the edge** (the binary, native code, pins). It leaves the land path once a pin change is the only thing that
   triggers it.
8. **bin/env (75).** It sets JOLT_LMDB_LIB, HOME and the AOT cache per checkout. [M] Per-checkout HOME means 25-78 MB of
   downloads in each of 8 worktrees (DELETIONS, deps).
   **Stays at the edge** as the one start path's environment, for one checkout.
9. **`seon.fx/checkout-env!` (fx.clj:152) and `seon.config/hold-checkout!` (config.clj:331).** They turn a fork of
   root's agent into another checkout's test process, one variable at a time.
   How it fails: [M] the class has been patched three times (SEON_CHECKOUT, then the environment at 01e555f88f, then the
   config at 579d62547d). The libzstd path, heap, threads and loaded libraries are still inherited (tests §2, runtime §3).
   **Replaced by** a child started on its branch's environment (Stage 0.5). Delete both.
10. **`seon.system/prove-tree!`, `tree-branch`, the `tree-runs` atom, `tree-enter!`/`tree-leave!`, `tree-refusal` and
    `baked-changes` (system.clj:2143-2244, 1538).** A branch per git tree id, an atom with CAS loops and a promise spin,
    and a refusal for any tree that changes what the binary bakes.
    How it fails: [M] AGENTS §3 says an atom is "a proc not yet made"; the branch is dropped after every run, so nothing
    persists for a session.
    **Replaced by** the import onto the session's agent branch (task 2). `baked-changes` is kept as the start path's check.
11. **The fx checkout runs: `run-on-checkout`, `run-held`, `run-tests`, `run-item`, `gate-answer`, `full-run-command`
    (fx.clj:717, 1072-1380).** A detached worktree for every gate, `gate-<test>` keys, and items run by shell.
    How it fails: [M] 9.9 GB and 3 leaked worktrees; the gate depends on git.
    **Replaced by** gate = `prove!` of the candidate branch.
12. **The result files: `stored-results`, `test-results`, `write-verdicts!`, `forget-greens!`, the `results/proved`
    markers.** [M] 129,520 files, over their bound; statuses are kept as files, unreadable by query.
    **Replaced by** `:seon.proof` entities.
13. **`seon.agent/merge-main!` (agent.clj:524).** It merges on the branch's program, with a second merge (`carry-tx`),
    at 47.5 GB per merge.
    [M, code] The base moves (`swap! state assoc ... :base main`, agent.clj:562) only on the path where `(:theirs r)` is not empty.
    [H] So "base moved, applied 0" means `delta-three-way` saw main's changes in `:theirs` and put none in `:apply`. Every
    later merge then starts from main's head, and those changes are lost to the branch for good. Settled by evaluating
    `(pm/delta-three-way base head main #{})` on context's branch and counting `:theirs`, `:apply` and `:conflicts`.
    **Replaced by** one merge, on main's program, at root (plan 1.6).
14. **`my.root/write-back!`, `sync!`, `landed!`, `import!`, and the jolt/merges tree (root.clj:158-260, 343, 402;
    config/local.edn `:seon.config.source/root ".worktrees/merges"`).** The store writes git through a second worktree and
    branch, and a session then has to land that branch with bin/land.
    How it fails: [M] 157 commits ahead of main with the same tree as main. `import!` takes `load-lock` (root.clj:245, 261), a
    lock.
    **Git stays at the edge**, as the one `:git` effect that moves main.

## 3. Tasks, in the order that makes "edit → branch → proved → merged by seon_done → git follows" work once

Each proof is seen red first. Tasks 1-7 are that path; 8-10 follow it; 11 is the audited cut.

**1. `seon.server/a-sessions-file-edit-merges-through-done`** (root with runtime). This is the acceptance test, written
first, and it stays red until tasks 2-7 land. A scratch system makes an area agent. A file in its source dir is edited, the
import is called, the agent proves, and `seon_done` merges. Today each step goes through a different mechanism, and no
test walks them together.
- `seon.server-test/a-sessions-edit-reaches-gits-main-through-done`: after edit, import, prove and done, main's store
  holds the new form, and git's main tree holds the edited file's text. No `git worktree add` and no bin/land is called.

**2. `seon.agent/a-test-child-starts-on-its-branchs-environment`** (runtime, tests; Stage 0.5; the first slice of
`seon.program/a-process-runs-its-branchs-program`). A child for branch b is started through the one start path, from a
process already on b's environment, never from root's agent. At start it checks once that it holds exactly what b names:
config digest, environment variables, library paths and pin stamp. If it does not, it answers a typed error and stores no
verdict. Deletes the `checkout-env!`/`hold-checkout!` call (agent.clj:1254) and both functions once task 3 removes the
checkout. Proofs:
- `seon.agent-test/a-child-whose-config-differs-from-its-branch-is-refused-and-stores-no-verdict`: one config entry is
  leaked, the answer is `:seon.agent/environment-differs`, and the store holds no result.
- `seon.agent-test/a-test-child-of-a-branch-reads-its-branchs-config`: a branch that sets a config entry is read with
  that value in the child.

**3. `seon.system/a-sessions-files-are-a-transaction-on-its-agents-branch`** (runtime owns the op and the agent; data
owns the transaction; one function, as FABLE §3 asked). `prove-tree!`'s catch-up (`catch-up :fact? false`, the writer's
`:branch-for-tree`) writes onto the session's persistent agent branch, keyed by the area. The writer's load path loads the
changed forms into that agent. Deletes `tree-branch`, `tree-runs`, `tree-enter!`, `tree-leave!`, `:drop-tree-branch`, and
`prove!`'s `on` checkout argument. `tree-refusal` becomes task 2's check. Proofs:
- `seon.system-test/an-edited-file-is-one-transaction-on-the-sessions-branch`: one changed defn gives one transaction of
  that form's datoms, and main is unchanged.
- `seon.system-test/an-import-of-unchanged-files-writes-nothing`: O(d), with no datom written and a tick budget from the
  estimate.
- `seon.system-test/a-file-that-does-not-load-is-answered-and-nothing-is-written`: a typed error naming the path, and the
  branch head unmoved.

**4. `seon.prove/a-docs-edit-is-proved-by-its-readers-in-seconds`** (tests, data). Root's 17-minute prove. First find the
cause [H, §1]. Then the run selects only the tests whose recorded reads include the changed section (BLOCKERS row 2), and a
run that cannot finish answers a typed error. Proofs:
- `seon.prove-test/a-one-section-change-runs-only-the-tests-that-read-it`: the selected set equals the section's readers,
  and the count is within the estimate.
- `seon.prove-test/a-prove-that-cannot-answer-ends-with-a-typed-error`: a run with a child that never answers ends with
  its wall error. It never hangs.

**5. `seon.proofs/a-verdict-is-an-entity-that-names-its-environment`** (tests, data; with `seon.proofs/test-results-are-a-few-facts`).
Where `seon.proofs` writes a file, it writes a `:seon.proof` entity instead, holding the test, a status written once, its
reads and the `{:branch :commit :t}` it ran on. `stored-results` reads the store, in the same landing. Deletes the files'
write path. The file tree is removed after the audit in task 11. Proofs:
- `seon.proofs-test/a-verdict-is-reused-from-the-store-at-its-digest`: a second ask runs nothing.
- `seon.proofs-test/a-verdict-from-another-environment-is-not-reused`: the same digest under another environment runs again.
- `seon.proofs-test/a-second-status-at-one-digest-is-a-defect-not-a-red`: it is filed as a problem, and nothing is
  marked flaky.

**6. `seon.agent/a-merge-of-main-applies-every-change-main-made`** (data, runtime; with `seon.merge/one-merge-system`).
Fix the "applied 0" class at its cause (§2.2, entry 13). The merge runs on main's program at root. `carry-tx` is deleted
and task content becomes a row of the one three-way merge. An estimate is written first, against the measured 62 s and
47.5 GB. Proofs:
- `seon.agent-test/a-merge-of-a-moved-main-applies-what-it-changed`: context's case. Main changes k forms, and the merge
  applies k, or keeps them unmerged; the base never moves past a change it did not apply.
- `seon.merge-test/a-branch-cut-before-a-deletion-merges-the-deletion`.
- `seon.merge-cost-test/a-merge-costs-what-moved`: ticks within 10x of the estimate, at 10 and at 100 moved names.

**7. `seon.reconcile/the-gate-proves-the-candidate-branch`** (tests, runtime). `gated`'s run becomes `prove!` of the
candidate branch. Its process tests run in children started per task 2. Deletes `run-on-checkout`, `run-held`, the
checkout part of `gate-answer`, `full-run-command`'s `:built?`, and runs/merge-tests. Proofs:
- `seon.reconcile-test/a-gate-runs-no-git-worktree`: the effects asked contain no `:git` or worktree request, and the
  verdict is written.
- `seon.reconcile-test/a-gate-red-holds-the-task-with-its-red-test`.

**8. `seon.source/a-merge-moves-gits-main-to-the-stores-files`** (runtime, root; plan 1.7). After Seon's merge, one `:git`
effect commits main's changed files to git's main, a compare-and-swap of the ref, once root has read the diff. Deletes
jolt/merges as an indirection, `sync!`'s land-it-with-bin/land step, and bin/land's perl bridge. Proofs:
- `seon.source-test/a-merged-task-moves-gits-main-to-its-files`: git's main tree equals main's files for the changed paths.
- `seon.source-test/a-ref-that-moved-is-a-typed-error-not-an-overwrite`.

**9. `my.root/landed-tells-the-sessions-it-unblocks`** (context).
- `seon.server-test/a-merge-messages-each-agent-whose-task-needed-it`.

**10. `seon.system/a-merge-restarts-only-for-a-binary-change`** (runtime; Stage 3.1).
- `seon.system-test/forms-and-schemas-load-in-place-and-proves-in-flight-keep-running`.

**11. `seon.task/each-cut-is-audited-before-it-lands`** (root, with each owner; plan 1.9). This is the pilot and the
cut. One area (context: its branch equals main, and it already uses `seon_attach`) works a week with no worktree. Then
each removal goes to the owner with why, what supersedes it and how its users move:
- bin/land's local prove and bridge;
- bin/test's shards and test_runner.clj;
- the result files;
- bin/stalls and the other wall-time watchers;
- bin/worktree, bin/ports and per-checkout bin/system (deps exempt until its gitlink is an entity);
- the `--root` and `--out` modes of bin/prove.

bin/build, bin/env and the one bin/system stay as the edge. The proof for each removal is a law:
- `seon.laws-test/no-src-or-bin-reads-a-removed-path`: the removed names are absent from src, bin and docs.
