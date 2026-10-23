# rename-run: handoff for the rename window

Written 2026-10-23 by the rename session, which ends with the owner's account. Read this, then `README.md` here.

## What this is

The owner (2026-10-23): "I don't like the attempt name. Call it agentic loop or run... Rename everywhere."
Branch `jolt/rename-run` renames **attempt -> run** in code, docs, tasks, schemas, stored keys and task ids
(`:seon.attempt/*` -> `:seon.run/*`, `:seon.attempt/attempt` -> `:seon.run/run`). Root approved the plan:
test run first, then attempt -> run; no migration, because the store is erased at the merge.

The branch is **two committed scripts plus the files they produced**. Never hand-merge its 1,300 lines:
refresh it by the procedure below. Last tip: `ec16021f` (main `5e85c5c5` merged). Worktree: `~/src/seon-jolt/next-rename`.

## Freeze-time steps, in order

Root freezes main first (nothing else merges until the prove ends).

1. **Merge main and run the scripts.** In the worktree on `jolt/rename-run`:
   ```
   git merge --no-edit main
   for f in $(git diff --name-only --diff-filter=U); do git checkout --theirs -- "$f"; done
   python3 notes/rename-run/step1.py
   python3 notes/rename-run/attempt_to_run.py
   ```
   (`--theirs` is main's side during this merge.) Both scripts are idempotent; the second must end `files N` with
   no "attempt" left in the tree: check with
   `git grep -n -i attempt -- . ':!reference-code' ':!notes'`. Allowed leftovers: the AGENTS §7a row, the quoted Jolt
   message "Attempting to call unbound fn" (resources/seon/tasks/seon.program.edn), the image name
   `web/shots/zoom-a8-attempt-calc-neg.jpg`.
2. **Stage by explicit path, never `git commit -a`.** A `-a` once staged the worktree's stale submodule checkout over
   main's pin and broke the build. Use `git add -A -- . ':!reference-code'`, then compare pins:
   `diff <(git ls-tree HEAD reference-code/ | awk '$2=="commit"') <(git ls-tree main reference-code/ | awk '$2=="commit"')`
   must print nothing. Commit.
3. **Sync the submodules.** From the main tree: `bin/worktree --sync rename` (or ask root). The checkouts of datahike and
   the others lag the pins after a merge; a build on stale checkouts fails or lies. Verify with
   `for s in $(git ls-tree HEAD reference-code/ | awk '$2=="commit"{print $4}'); do [ "$(git ls-tree HEAD $s | awk '{print $3}')" = "$(git -C $s rev-parse HEAD)" ] || echo "BEHIND $s"; done`.
4. **Build:** `nice -n 15 bin/build`. An error `Unable to resolve symbol: run` means main added a new use of a test-run local
   (the build catches an unresolved symbol, not a destructure that is now nil). Rename that local to `test-run` inside its
   function and add the pattern to `step1.py` (it patches `observe` and `observed` in `src/seon/turn.clj`).
   Then scan destructures: `git grep -n -P '\{:keys \[[^\]]*(?<![\w-])run(?![\w-])' -- src test`; each must mean an agent's run.
5. **Prove:** `bin/prove` (once) and `bin/check health` (must read 0 dead citations, 0 stale pins, 0 unnamed sites).
   `bin/test --reach` no longer exists and runs nothing. Process tests are left out of a plain prove by design; name them
   (`bin/test NS`, `bin/check MODE`) if you want them. Known flake: `seon.program-cost-test/recording-a-form-is-flat-in-the-program`
   went red once in a loaded 4-shard prove and passed alone (`bin/test --all seon.program-cost-test`); not caused by the rename
   (those files are identical to main); my unverified guess was a stale shared AOT cache.
6. **§9 self-review** (AGENTS §9) of `git diff main HEAD`: leftovers of the old word; a doc anchor or citation that no longer
   resolves (health reads that); a `run` in code that means two things in one scope; prose "run" that should say "test run" or
   "root's run" (below); any change that is not a rename. Report to the merge session (`local_ee711b9e-4e74-4dd7-8606-b21c2e16ddfd`,
   if it still exists; otherwise to root) with the tip and each verdict.
7. **Merge into main** (root merges, never copying files). Then **erase the standing store** (`next/runs/store`): owner's ruling,
   "isn't it just test data just nuke it and move on". Stop the root first (`bin/root stop`), restart it fresh on the new names.
   `../next-merges` is root's write-back tree and stays.

## Known leftovers and judgement calls

- `:seon.run/run` is the schema name for the pulled run entity (root approved it).
- Distinguishing nouns: an agent's **run** (attempt), a **test run** (clojure.test; `test-run-errors`, `test-run-answer`, the
  `test-run` locals in proofs, turn and my.agent) and a **root's run** (a process of root on a store; `runs/`,
  `:seon.config.root/runs-dir`). Only identifiers and the vocabulary rows (`docs/vocabulary.md`) are disambiguated; ordinary
  prose "the run" that already meant a test run is left as written.
- Kept names that are verbs on something else: `seon.cost/run`, `seon.e2e/run`, `seon.intr/run`, `seon.step/run-loop`,
  `seon.check/run-check`, `my.root/run!` (releases tasks so their runs start), `bin/root run`.
- The proof plan's `:run` key (the tests to run) stays; it never shares a map with an agent's `:run`.
- Dated notes in `notes/` keep their text (records), except this folder.
- `:seon.run/waste` (the old waste attribute): root renamed or kept the word separately; not touched by this branch beyond the prefix.
- Earlier passes on this branch's ancestry, for context: pass 1 (words: server, worktree session, merge, failure point, ...) and
  pass 2 (stored words: instrument, server-port, reason/invalid, `root-causes`) are on main; AGENTS §7a is the table.

## Gotchas learned

- `git commit -a` in a worktree with submodules: see step 2.
- `bin/worktree --sync` is the only safe way to move submodule checkouts; do not `git submodule` in a worktree (AGENTS §8).
- Stopping a background `bin/test` leaves its root and generation build running: find them under the worktree's `target/tmp`
  and kill only those PIDs.
- Cost proofs are load-sensitive; do not read one red under a loaded run as a regression before re-running it alone.
