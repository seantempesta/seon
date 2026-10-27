# Merge audit and design, 2026-10-27

Read-only audit of every place a merge happens in Seon on Jolt (`/Users/sean/src/seon-next`, main at 4840ddb153), and the smallest merge path that serves both Seon agents and outside agents. Serves THE PLAN step 4 ("merge only when it can: auto-merge on facts, main's tests green on the merged tree") and step 6 ("root is main, one system").

## The headline numbers

- **Two paths, one used.** Since 2026-10-20, main took **301 first-parent merges**, all by git (root's worktree merges plus `bin/prove --merge`). Seon's own merge (octopus merge plus write-back) has made **14 "Seon merge:" commits ever**. All are on `jolt/seon-merges` (a test runs' branch), **1 is in main's history**, and **0** `bin/update` merges of `jolt/merges` appear on main's first-parent line in that window.
- **Two mains, synced both ways.** Git main is imported into the store's main (`seon.root/import-source!` at boot, `my.root/watch!` on ref moves: `seon.source/replace-tx` / `refresh-tx`). The store's main is written back to git (`:write-back` → `:git` effect on `jolt/merges`), then `bin/update` merges `jolt/merges` into git main. Each direction works, and together they make a loop that has to be kept consistent.
- **Two verdicts.** The Seon judge leaves process tests out (`:seon.run/deferred`). It runs them only when root asks (`my.root/gate!`, `:seon.task/gate?`) and a gate checkout is declared. `bin/prove --merge` runs everything with no green at its digest, process keys included (122–127 per merge in the log), plus contracts and smoke. Both select through the same `seon.proofs/plan` and digests, so the selection is one. The result stores are two: facts on main (`:seon.proof/digest`) and the shared `test-results/` directory, which holds greens only.
- **Two records.** One is facts (`:seon.run/merged-t`, `:seon.run/commit`, proof-run facts). The other is `notes/merge-log.md` (191 lines, hand- and script-appended, 22 `bin/prove --merge` lines).
- **Two serializers.** The flock in `bin/env` (`main_hold`/`main_first`, `MAIN_LOCK_PL`) runs against the reconciler's one-merge-at-a-time (`:seon.config.merge/max-running 1`, `merge-keys`, `live-merge?`, `closed?`).

## Audit, by place

### Seon side (in the store)

| Piece | file:lines (≈ lines) | What it does | Called by | Verdict |
|---|---|---|---|---|
| `seon.merge/three-way`, `changes`, `program`, `same?` | src/seon/merge.clj:80–149 (≈70) | git's three-way over program maps `{key content}` | `delta-three-way`; `program` is read whole only by tests | **Keep.** The one derivation. |
| `touched-keys`, `program-of`, `key-content`, `entity-keys`, `delta-three-way`, `delta-changes` | merge.clj:150–237 (≈85) | Reads only the keys whose datoms differ. Cost, from commit 4840ddb: 100 attrs 8.11 MB / 2,726 calls → **562 KB / 39 calls**; 1,000 attrs → 585 KB / 39 | writer `branch-merge`, agent `merge-main!`, `merge-readiness`, turn `uncontracted-since` | **Keep.** The core strength, already at the cost of what changed. |
| `data-attr?`, `data-attr-set`, `carried-platform-attrs` | merge.clj:31–65 (≈35) | Which data attributes merge beside the program | three-way readers | Keep. Every caller passes `#{}` today except tests: data merge is unused in production. |
| `carried-tx`, `merge-tx` | merge.clj:249–287 (≈40) | The branch's own datoms for the merged keys, as they are | writer octopus, agent `merge-main!` | **Keep.** |
| `mutations`, `uncontracted` | merge.clj:115–124, 288–305 (≈30) | Merge refusals: intern/eval/alter-var-root, missing contract | writer `merge-refused`, turn, judge | Keep. |
| Unmerged entries: `entries`, `conflict-tx`, `unmerged-tx`, `unmerged`, `resolve-tx`, `conflict-value` | merge.clj:306–367 (≈60) | git's index of conflicts, kept on the branch | `my.branch`, agent `merge-main!`, task end facts | Keep. This is the agent's conflict UI. |
| `my.branch/merge-main!`, `unmerged`, `resolve!`, `changes` | src/my/branch.clj (53) | The agent's git words | agents, merge-task turns | Keep. `changes` uses `program/divergence`, a second diff (see duplications). |
| `seon.agent/merge-main!` | src/seon/agent.clj:354–391 (≈38) | Merges main into the branch in the agent's process, with `task/carry-tx` and `load-changes!` | `my.branch`, the root op `:merge-main` (root.clj:397–472) | Keep. |
| `seon.agent/merge-readiness` | agent.clj:393–405 (≈12) | Read-only `delta-three-way` against main | turn `:merge` (turn.clj:102), context.clj:377 | Keep. It is step 4's "merge-readiness on the page". |
| `seon.writer/branch-merge`, `merge-refused` | src/seon/writer.clj:58–107 (≈50) | Branch vs main; refusal is `:behind`/`:unmerged`/`:mutation`/`:uncontracted` | octopus, `:branch-diff` | Keep. |
| `seon.writer/octopus-merge` | writer.clj:109–150 (42) | N branches in one `d/merge-db` commit; a member that overlaps an earlier one is `:behind` | reconciler `:octopus-merge` | **Simplify to one branch.** `max-running` is 1 and the docs say "built and off". About 15 lines go. |
| `:branch-diff`, `:branch-db` ops | writer.clj:225–245 | Root's one read of a branch | reconciler judge, gate, write-back | Keep. |
| Reconciler judge → `:ready-to-merge` / `:gate` / `:octopus-merge` | src/seon/reconcile.clj:409–463 (≈55 merge lines) | Done rule, then the gate (only if `gate?` is set and a checkout is declared), then octopus | reconciler-proc | **Shrink.** The gate becomes unconditional (it is the verdict), and octopus becomes a merge. |
| `merge-keys`, merge-room / `taken` scheduling | reconcile.clj:384–393, 464–481 (≈30) | Starts concurrent merge tasks only when their changed keys are disjoint | `actions` | **Delete.** It is dead under `max-running 1`. |
| `hairy`, `merge-limits` | reconcile.clj:15–20, 67–88 (≈28) | Stops a merge task past conflicts or merge turns | turn.clj:406–426 (merge-task loop) | Goes with per-work merge tasks (below). |
| Reconciler `advance`: `:octopus-merged`, `:gate-read`, `:gated`, `:write-back`, `:main-read`, `:committed` | reconcile.clj:733–745, 937–1001 (≈80) | Records each member, reads the gate's files, writes back changed namespaces, `:git` commit on the merges branch, sha on the run | reconciler | **Keep** write-back and commit. Fold gate-read/gated into the one verdict step. |
| `unfiled-error`; `source/unfiled-forms`, `changed-files`, `files`, `ns-file`, `commit-message` | reconcile.clj:526–533; src/seon/source.clj:496–553 (≈60) | What a write-back can and cannot write | reconciler, writer | Keep. |
| `seon.source/replace-tx`, `refresh-tx`, `import-tx` | source.clj:197–315, 365–394 | Files → program datoms. `refresh-tx` costs the changed files | `import-source!` (root.clj:738–772), `my.root/watch!` | **Keep. This is the bridge for git branches** (design §1). |
| `my.root/gate!` | src/my/root.clj:67–100 (34) | Root's go to run deferred process tests, even after a merge (`:unrun` problem) | owner/root by hand | **Delete.** The verdict always runs the process keys the change reaches. |
| `seon.fx/run-tests` (`:tests` effect) | src/seon/fx.clj:425–480 | A detached worktree at the merges head with the candidate's files over it, then the runner | gate, `gate!` | **Keep, and generalize.** The one verdict effect (design §1). |
| `seon.task/merges?`, `carry-tx`, run end members | src/seon/task.clj:35–40, 288–303, 437–451 | Kind `:merge`/`:whole` merge themselves; task content carried | reconciler, turn | Keep. |
| `seon.template` merge kinds: `merge-name`, `merge-plan`, `merge-decl`, `with-merge`, `design-build`, `stages`, `instructions :merge` | src/seon/template.clj:24–34, 56–60, 87–155 (≈95) | Every work task followed by a `-merge` task | `my.root/task-files` (root.clj:100–118), requests in experiments | **The big one.** `task-files` applies `with-merge` to **every** file-declared task: 434 declarations become about 868 tasks on main. That contradicts the night ruling that a request is one whole task by default. Make file tasks `:whole`; keep the templates only as experiment choices (owner's ruling of 2026-10-26: "don't get rid of the other tasks"). |
| turn.clj merge-task loop | src/seon/turn.clj:11–13, 341–343, 397–426 | Merge turns, `hairy`, first `merge-main!` | every run (the first `merge-main!` is right for all runs) | Keep the first `merge-main!`. The `merge-turns`/`hairy` counting goes with merge tasks. |
| `program/divergence`, `changed-vars` | src/seon/program.clj:1006–1039 (≈34) | A second branch-vs-base diff, via `datoms-diff` on `:var/form`/`:schema/form` | `my.branch/changes`, turn `:will-merge`, agent.clj:572, problems.clj:425 | **Duplicate** of `merge/delta-changes`, a different reader of the same question. Fold later; not on the merge path. |
| `program/diff-plan`, `load-diff!`, `load-changes!` | program.clj:947–1005 | What to load when a process moves between programs | spawn, writer, fork server, `merge-main!` | Keep. A different purpose (loading, not merging). |
| fork server replica of main | `seon.fork-server/server-step` (docs/source.md "A merge loads in place") | `load-changes!` on each main commit | writer's commit notice | Keep. |
| server `:attach` / `:done` | src/seon/server.clj:71–91, 158–175 | An outside run on a store branch (model "outside"); done → `hand-back!` → judge → merge | MCP `seon_attach` / `seon_done` | **Keep. This is already the outside agents' door.** It lacks only the import of a worktree's files. |

### Outside path (git, the shell)

| Piece | file:lines | What it does | Verdict |
|---|---|---|---|
| `bin/prove --merge` + `merge_verdict` | bin/prove:14–82 (merge parts ≈45 of 82) | Every key with no green, process keys too; then contracts and smoke; MERGE OK/FAILED; appends to `notes/merge-log.md` | **Selection: keep.** It becomes what the `:tests` effect runs. **Delete** `merge_verdict`'s log append and its grep-the-logs parsing: the verdict is a fact on the run. |
| `bin/env` lock: `MAIN_LOCK`, `MAIN_LOCK_PL`, `main_first`, `main_hold`, `is_main_tree` | bin/env:30–63 (≈34) | flock so main's verification goes first | **Keep `main_first` (the waiters).** The holder becomes the verdict effect instead of `bin/prove --merge` in the main tree; the owner ruled "main first, as a fact" (2026-10-26 night). `is_main_tree` and the re-exec branch in bin/prove (≈8 lines) go. |
| `notes/merge-log.md` | 191 lines | The merge record | **Delete as record.** Becomes a render of the merged runs' facts (sha, plan counts, reds, src delta) when wanted. |
| `bin/update` | 42 lines | Merges `jolt/merges` into main, ff `jolt/merges`, sync, build, restart | Keep for now. Its step 1 is the only way Seon's merges reach git main. (`seon.fx/git-commit` uses plumbing and touches no checkout, so it could commit on `main` directly later; deferred.) |
| `bin/worktree` | 170 lines | Make/sync/remove worktrees, ports, target clone | Keep (the fork for Claude sessions). Not a merge. |
| `bin/root next` | bin/root:106–115 | Restart on the next generation | Keep (for pins/native, which cannot load in place). |
| docs/merging.md | 84 (long) lines | Procedures for both paths, including "Not built: a merge queue on root" | Rewrite to one path after the build. Sections "Main goes first", "Ready branches merge in batches" and "Seon's merge is a three-way merge" collapse into one. |
| config | default.edn:254–260 (`merge/max-running`, `max-conflicts`, `max-merge-turns`), :285–292 (`gate-checkout`) | | `max-conflicts` and `max-merge-turns` go with merge tasks; `max-running` goes with octopus; `gate-checkout` becomes the verdict's checkout (the merges tree). |

### Inconsistency found

- `:seon.config.source/branch` is `"jolt/seon-merges"` in default.edn:332 and `"jolt/merges"` in the main tree's `config/local.edn`. `bin/update` reads only `:seon.config.source/root`. Roots started without the local layer (tests, check stores) commit write-backs to `jolt/seon-merges`, which is where the 14 Seon merges sit.

## Top duplications

1. **Two mains and two merge mechanisms.** Git merge in the main checkout versus the store's three-way plus write-back plus `bin/update`. Only the first is used (301 to 1).
2. **Two verdicts.** `bin/prove --merge` (everything, process keys included) versus the judge (process keys deferred, then `my.root/gate!` / `:seon.task/gate?` / `:gate` / `:gate-read` / `:gated` / the `:unrun` problem). The gate exists to re-create `--merge` for agents, conditionally.
3. **Two serializers.** The flock versus the reconciler's single merge flight, plus `merge-keys`/octopus scheduling that is dead under `max-running 1`.
4. **Two records.** `notes/merge-log.md` versus run and proof facts.
5. **A merge task per work task.** `with-merge` over all task files doubles 434 tasks, while whole tasks already merge themselves.
6. **Three branch-vs-base diffs.** `merge/delta-changes`, `program/divergence`, and `program/diff-plan` (the last has a different purpose and stays).

## Design: one merge (under a page)

**What a merge is.** A merge is the reconciler's action on a run whose done rule has no reasons. There is no new task kind. It exists now as `:ready-to-merge` → writer merge → `:write-back` → `:committed`. Every agent reaches it the same way: fork a branch of main (`seon_attach`, or the reconciler's spawn), work on it, hand it back (`seon_done`, or the turn loop's end). The inputs are the run's store branch, its merge base, and main. The output is main's next value and the facts on the run (`:merged-t`, `:commit`, the verdict).

**The one verdict.** Main's tests on the merged program: every test and check step with no green at its reach digest, process keys included. The branch already holds main's head (otherwise `merge-refused` answers `:behind` and the agent merges main), so judging the branch judges the merged program. One effect runs it, the existing `:tests` effect (`seon.fx/run-tests`):
- it runs a worktree of the merges tree with the candidate's changed files over it;
- its command is `bin/prove --merge`'s selection rather than a named test list;
- it holds the `main_first` lock while it runs.

Results land as facts on main and greens in `test-results/`; both are keyed by digest, so a repeat costs only what changed. The gate stops being a choice: `:seon.task/gate?` and `my.root/gate!` go.

**A Seon agent's branch** is already in the store, so nothing changes for it.

**A Claude worktree branch enters** through the door outside agents already have:
1. The session does `seon_attach` on its task, which gives it an agent on a store branch of main.
2. At `seon_done` it names its checkout. Root reads that worktree's `src`, `test` and schema files through the `:source` effect it already uses at boot.
3. Root transacts `seon.source/refresh-tx` of the files that differ onto the run's branch. This is the boot import's own function, at the cost of the changed files.
4. From there it is the same judge, the same `delta-three-way`, the same writer merge, and the same write-back.

Files the store does not hold (docs, bin, notes, text resources) travel in the same write-back commit: `seon.fx/git-commit` already takes `{path text}`, so the run's changed non-program files (`git diff base...branch`, read by the `:git` effect) are added to the commit's file map. The session must merge main into its branch before `seon_done`, as AGENTS §3 already requires.

**It deletes.**
- From the outside path:
  - `bin/prove`'s `merge_verdict`, the log append and the main-tree re-exec (about 45 of 82 lines);
  - `is_main_tree` and `main_hold` in bin/env (about 12 lines; `main_first` stays);
  - `notes/merge-log.md` as the record;
  - the docs/merging.md procedures for root's hand merges and batches (about 40 lines).
- From the Seon side:
  - octopus to a one-branch merge (about 15 lines in the writer and about 10 in `actions`);
  - `merge-keys` and merge scheduling (about 30);
  - the gate's conditional and `my.root/gate!` (about 45);
  - `:seon.config.merge/max-running`.
- After the owner's ruling, also per-work merge tasks: `with-merge` in `task-files`, which halves the task count; `hairy`, `merge-limits`, merge-turn counting and the two config keys (about 60 lines plus the templates, which stay only for experiments).
- Net: about 250 lines of src and bin removed. The added code is about 30 lines: done's `:checkout` → `refresh-tx` onto the branch, plus the non-program files in the commit map.

**Kept for later (edge cases deferred).**
- Concurrent or octopus merges (one merge at a time).
- A run found `:behind` restarting itself without a new agent.
- Merging data attributes (`data-attrs` stays `#{}`).
- Deleted, binary or gitlink files, which means **pin bumps, native code and deps.edn stay root's git merges plus `bin/root next`**: the one named exception.
- A git-level conflict in a non-program file is refused as a fact; the session merges main and hands back.
- Committing write-back on `main` directly and dropping `bin/update`'s step 1.
- Folding `program/divergence` into `merge/delta-changes`.

## Two experiments that prove it before building

Run both on a worktree root (`bin/worktree merge-probe`, its own store), never `runs/store`.

1. **Does an import of a git branch reproduce its diff as definitions?**
   - Take a just-merged branch: 4840ddb's second parent (`jolt/data-attrs-from-the-index`) and its merge base with main.
   - On the worktree root, `d/branch!` main to `probe`, with main imported at the merge-base tree.
   - Transact `seon.source/refresh-tx` of the files that differ (`git diff --name-only base tip -- src test resources/seon/schemas`, texts from `git show tip:path`).
   - Then `(pm/delta-three-way base-db probe-db main-db #{})`.

   It should show:
   - `:ours` keys exactly equal to the definitions git's diff touches (a handful in `seon.merge`/`seon.writer`/tests);
   - **0 conflicts**;
   - the refresh writing only those forms' datoms (tens, not the program's thousands);
   - the delta read at about **39 contracted calls / under 600 KB**, as 4840ddb measured;
   - `(seon.source/files probe-db changed-paths)` **byte-equal** to `git show tip:path` for each changed file (0 differing bytes).

2. **Does one result store answer both paths?**
   - On the same `probe` branch, compute the plan the judge would use (`seon.agent/test-plan` / `seon.proofs/plan` with the shared `test-results/` dir) for main's tests.
   - It should show **0 keys to run** for the tip tree that `bin/prove --merge` already proved green at 3100f309 / 4840ddb: the digests from the store import equal `bin/test`'s from the files, the property `seon.program-test/the-same-tree-gives-the-same-digests-in-any-checkout-and-any-run` claims.
   - If it is not 0, the count and the first differing digest's inputs name what the import misses (likely `:env` inputs, or `test/` files not imported). That is the one thing to fix before building.
