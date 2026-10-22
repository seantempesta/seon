# Landing

How a branch lands on main: what it runs, how batches are proven, and how conflicts are resolved. Who lands and why is [AGENTS.md §3](../AGENTS.md); the machine the proofs run on is [machine](machine.md).

## A lane works in a worktree of its own

A lane is a separate instance of the whole system: `bin/worktree NAME` (run from the main tree) makes `../next-NAME` on branch `jolt/NAME` from main, with its own submodule checkouts at their pins, an APFS clone of `target/` (so `nice -n 15 bin/build seon` is the only build), its own ports (`config/local.edn`, `bin/ports`) and its own stores under its `runs/`. In it:
- `bin/root start | status | stop` runs that worktree's own root, never the standing one (7888); a Claude session started in the worktree reaches it through the committed `.mcp.json` (`./bin/mcp` asks this checkout's root);
- `bin/prove` iterates and lands (it is `bin/test` and `bin/check` without arguments, together): what the change reaches, nothing else; a single check mode (`bin/check MODE`) runs one; `bin/check` (one mode or a run) works on a store of the checks' own, `target/check-store`, seeded when missing (`SEON_STORE` names another): `runs/store` is the worktree root's, made without the example program the checks seed, so reusing it fails steps falsely;
- after each landing on main: `git merge main` in the worktree, then `bin/worktree --sync NAME` from the main tree (never `git submodule` in a worktree);
- a submodule the lane adds (`reference-code/X`) is written as a `.gitmodules` entry and a gitlink, with its checkout cloned from a local copy; root initializes it in the main tree when it lands (`git submodule update --init reference-code/X`), so later worktrees get it.

`bin/lane-check` (run from the main tree) proves this whole workflow end to end with a throwaway lane and removes it; run it after a change to `bin/worktree`, `bin/root`, `bin/mcp` or the build. Leave nothing running: `bin/root stop` before you report. Root removes the worktree after the landing (`bin/worktree --remove NAME`).

## A lane's part

A lane merges main into its branch, proves the merged result with `bin/prove`, and reports. Branches live hours: merge main into yours after every landing. While iterating, `bin/prove` (or `bin/test NS` for one namespace) runs only what your change reaches, never a full check per edit. To land, report the commits, proofs red → green, numbers against estimates, what you deleted, `bin/prove`'s last lines (and the check's `target/check/<time>/` path when steps ran), and that nothing of yours is running.

## Root merges, and reads the verdict

Root lands by merging the branch, never by copying files over newer changes, and reads main's check to its last line before landing.
- Every stage's exit status decides the next: no pipe hides a failure (`setopt pipefail`, output to a log), and a check counts only if the build before it succeeded and the binary's time moved.
- Root rebuilds main as part of every landing, so a worktree made from main never reads config with a stale binary.

## A landing runs what the change reaches

A landing runs what the change reaches, not every check (owner, 2026-10-21: "I want faster iteration not a matrix with every run permutation"; 2026-10-22: "why don't we have a comprehensive policy where we are only running tests that have changed?"). The landing proof is `bin/prove` on the merged tree, then `bin/check smoke`; `bin/dragons` runs inside it as a step keyed by the whole tree. One rule selects for everyone (task `seon.proofs/prove-runs-what-a-change-reaches`): a key runs exactly when no green result is stored at its reach digest (```seon.proofs/plan```), in the one results directory every checkout shares (`:seon.config.source/test-results-dir`), so an unchanged tree runs nothing and a result proven in any checkout is reused in every other.
- **Tests.** A test's key is its reach digest (```seon.program/reach-digests```: the forms it reaches through refs, macros, quoted names, methods, fixtures and schemas, plus the checkout's inputs with no program facts; [source](source.md#tests-run-by-reach)). `bin/test [NS ...]` imports src and test once, selects, and deals what runs, a namespace at a time, into `:seon.config.proofs/jobs` shards (```seon.proofs/shards```), each a process of its own started at once and reaped by the shell (```seon.proofs/run-tests!```): a fork of the runner cannot connect to a store, since Datahike's connection state does not survive fork (measured 2026-10-22: the child's first `d/connect` never returns). Each result is stored as it ends (```seon.proofs/store!```, through ```seon.fx/write-file```); only greens are reused, so a red runs again.
- **Check steps.** Each `bin/check` step is keyed the same way by the `seon.check` mode functions its body runs (```seon.proofs/check-steps```, digested with ```seon.proofs/step-digests```): a change to `seon.check/smoke` or what it calls reruns smoke; a change to bin/, native/, resources/ or a pin (the binary's other inputs, `:env`) reruns every step. Whenever a test or step is about to run, `target/seon` is built from the tree first (a result is keyed by the source, so it must come from the binary built from it; a test that starts `./seon` runs it too; a no-op build costs ~30 s, so only then). `bin/prove` runs the steps to run through `bin/check`'s own bodies (`SEON_CHECK_STEPS`, the green ones written to `SEON_CHECK_GREEN`), and stores the greens at their digests.
- **Agents.** A Seon agent's run (the turn's observe, the judge's `:tests`, the merge gate, and `(my.agent/prove)`) uses the same `plan` against the main it forked from (```seon.agent/test-plan```): the proofs and the tests whose digest moved, each green one held at its digest reused (```seon.agent/run-tests-forked```).

The full runs are explicit: `bin/test --all` and `bin/check 100`. They run once a day in the background, and before a change to boot, the writer, the reconciler or the build; a red there becomes a task, not a blocked landing.

Measured 2026-10-22 (lane PROVE, `jolt/prove` on main `fcac0df`, 369 tests and 34 steps, the shared machine niced): selecting takes the import plus the digests, 7-11 s. An unchanged tree reuses every green: `bin/prove --checks` answers 34 of 34 steps reused in 8.5 s (the full `bin/check 100` takes ~6 min), and `bin/prove` ran only the one red test (402 of 403 reused, 1:05 with the build its run needs). A one-defn change on the agent's turn path (`seon.reader/forgive`'s docstring) ran 71 tests and 33 steps, 10:41; a change only seon.proofs reaches reran 6 of `seon.proofs-test`'s 8 tests and reused the others. The cold run (nothing green at its digests: every test in 4 shards and every step) took 18:39, of which the steps 6 min and three shards 10-122 s; the fourth was held by `seon.source-test/a-restart-catches-up-and-a-new-generation-resumes`, red on main and waiting on an `nc` that never times out, until its root was killed. Before, `bin/test --reach` with nothing reusable ran 354 tests serially in 12:41, and a landing ran the full `bin/check 100` beside it, since no step was selected.

## Ready branches land in batches

Root lands ready branches in batches (owner, 2026-10-21: "this is taking forever"; three branches landed one at a time cost ~40 minutes of serial full checks, each ~10 min, plus re-proving after every main move). Branches that are ready and file-disjoint (or whose overlaps are both-sides additions) merge onto one integration branch from main and are proven together by one `bin/prove` there (each branch's own greens are reused; only what the combination moved runs); then each lands.

## Every landing's `src` diff stat is read

Root reads every landing's `src` diff stat before merging (owner, 2026-10-21: "If we are building shit we don't need to build and not using the existing systems or extending them for minimal code we are doing the wrong thing"; `src` grew 14.1k -> 15.9k lines in one day against the ~10k goal). Every function a landing adds names the existing system it extends, or why none could carry it; a new namespace or mechanism where an existing one could be extended is sent back, not landed; the landing's commit message states its net `src` lines (`bin/dragons growth` prints them and the definitions the branch adds), and a landing that grows `src` says what it deletes or plans to delete.

## Conflicts and pins

A conflict is resolved by copying each side's lines, never retyping; the lines after `>>>>>>>` may belong to both sides; each resolved file's forms must balance as its parents' do. Submodule pin bumps happen only in root's landings, never in a lane's merge. Commits in the forks ([forks](forks.md): Jolt, Chez, Datahike, konserve) carry no AI trailers. After a landing, root removes the lane's worktree and prunes checks and generations ([machine](machine.md#disk-stays-bounded)).

## Where this is going

A landing queue on the one root that merges main into each candidate, runs `prove` on the merged result, and batches disjoint candidates. All builds, tests and checks become tasks on that root, admitted by workload, with results reused by digest (as `prove` does today). Seon's own merges get the same gate, and `:behind` fires only when main's change touches what the branch changes or reaches.
