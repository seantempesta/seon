# Landing

How a branch lands on main: what it runs, how batches are proven, and how conflicts are resolved. Who lands and why is [AGENTS.md §3](../AGENTS.md); the machine the proofs run on is [machine](machine.md).

## A session works in a worktree of its own

A worktree session is a separate instance of the whole system: `bin/worktree NAME` (run from the main tree) makes `../next-NAME` on branch `jolt/NAME` from main, with its own submodule checkouts at their pins, an APFS clone of `target/` (so `nice -n 15 bin/build seon` is the only build), its own ports (`config/local.edn`, `bin/ports`) and its own stores under its `runs/`. In it:
- `bin/root start | status | stop` runs that worktree's own root, never the standing one (7888); a Claude session started in the worktree reaches it through the committed `.mcp.json` (`./bin/mcp` asks this checkout's root);
- `bin/test` and single check modes iterate; `bin/check` (one mode or the full run) works on a store of the checks' own, `target/check-store`, seeded when missing (`SEON_STORE` names another): `runs/store` is the worktree root's, made without the example program the checks seed, so reusing it fails steps falsely;
- after each landing on main: `git merge main` in the worktree, then `bin/worktree --sync NAME` from the main tree (never `git submodule` in a worktree);
- a submodule the worktree session adds (`reference-code/X`) is written as a `.gitmodules` entry and a gitlink, with its checkout cloned from a local copy; root initializes it in the main tree when it lands (`git submodule update --init reference-code/X`), so later worktrees get it.

`bin/worktree-check` (run from the main tree) proves this whole workflow end to end with a throwaway worktree and removes it; run it after a change to `bin/worktree`, `bin/root`, `bin/mcp` or the build. Leave nothing running: `bin/root stop` before you report. Root removes the worktree after the landing (`bin/worktree --remove NAME`).

## A worktree session's part

A worktree session merges main into its branch, proves the merged result, and reports. Branches live hours: merge main into yours after every landing. While iterating, run only what your change reaches: single check modes and the tests that touch it, never a full check per edit. To land, report the commits, proofs red → green, numbers against estimates, what you deleted, the check's `target/check/<time>/` path, and that nothing of yours is running. A worktree session stops at its own single-namespace tests and its red-to-green proofs, reports, and lets root run the full proof in the batch.

## Root merges, and reads the verdict

Root lands by merging the branch, never by copying files over newer changes, and reads main's check to its last line before landing.
- Every stage's exit status decides the next: no pipe hides a failure (`setopt pipefail`, output to a log), and a check counts only if the build before it succeeded and the binary's time moved.
- Root rebuilds main as part of every landing, so a worktree made from main never reads config with a stale binary.

## A landing runs what the change reaches

A landing runs what the change reaches, not every check (owner, 2026-10-21: "I want faster iteration not a matrix with every run permutation"). The landing proof is: `bin/dragons` (every law census, no build), the tests of the changed namespaces and of their callers (found with the compiler's facts, or grep until reach selection lands), the `bin/check` steps whose code changed, and `bin/check smoke`. The full `bin/check 100` and `bin/test` run once a day in the background, and before a change to boot, the writer, the reconciler or the build; a red there becomes a task, not a blocked landing. Target: tests selected by reach digest and results reused (v1's `seon.test.cache`), so an unchanged test never runs again.

## Ready branches land in batches

Root lands ready branches in batches (owner, 2026-10-21: "this is taking forever"; three branches landed one at a time cost ~40 minutes of serial full checks, each ~10 min, plus re-proving after every main move). Branches that are ready and file-disjoint (or whose overlaps are both-sides additions) merge onto one integration branch from main and are proven together by one `bin/check 100` and one `bin/test`; then each lands.

## Every landing's `src` diff stat is read

Root reads every landing's `src` diff stat before merging (owner, 2026-10-21: "If we are building shit we don't need to build and not using the existing systems or extending them for minimal code we are doing the wrong thing"; `src` grew 14.1k -> 15.9k lines in one day against the ~10k goal). Every function a landing adds names the existing system it extends, or why none could carry it; a new namespace or mechanism where an existing one could be extended is sent back, not landed; the landing's commit message states its net `src` lines (`bin/dragons growth` prints them and the definitions the branch adds), and a landing that grows `src` says what it deletes or plans to delete.

## Conflicts and pins

A conflict is resolved by copying each side's lines, never retyping; the lines after `>>>>>>>` may belong to both sides; each resolved file's forms must balance as its parents' do. Submodule pin bumps happen only in root's landings, never in a worktree session's merge. Commits in the forks ([forks](forks.md): Jolt, Chez, Datahike, konserve) carry no AI trailers. After a landing, root removes the worktree session's worktree and prunes checks and generations ([machine](machine.md#disk-stays-bounded)).

## Where this is going

A landing queue on the one root that merges main into each candidate, runs what it reaches on the merged result, and batches disjoint candidates. All builds, tests and checks become tasks on that root, admitted by workload, with results reused by digest. Seon's own merges get the same gate, and `:behind` fires only when main's change touches what the branch changes or reaches.
