# Handoff: making next self-contained and cutting over to ~/src/seon-next (2026-10-23)

For the fresh root that takes over after the owner's account change. No memory or session carries over, so this page and the ones it names are all there is. Branch: `jolt/self-contained`, last commit before this page `5eca7197` (git log on the branch has the exact tip). Written by the session `self-contained`; nothing of it is running.

## The order, exactly

1. **The merge queue lands** (root's list in `docs/plan.md`). Main moves; every merge into main, from anyone, is by root.
2. **Freeze main.** Nobody commits to it.
3. **`jolt/rename-run` merges** (attempt → run). It erases the standing `runs/store` (owner ruling: test data); this is why no store is carried to the new root.
4. **Merge `jolt/self-contained` into main, together with step A** (below). Merge main into it first if main moved (conflicts so far were only in `docs/machine.md`, `docs/forks.md`, `docs/parity.md`, `docs/config.md`, `AGENTS.md`, `bin/ports` and the `reference-code/jolt` and `reference-code/datahike` pins: for a pin, take main's, because this branch holds no pin of its own; for prose, take main's and re-apply only the path fixes, which are the lines this branch's diff against main shows). Then `git submodule sync` in the main tree only (the new GitHub URLs; never `git submodule` in a worktree), then `bin/prove`.
5. **Step D, the history tags**, if not done already (it is a precondition of step R's R0 and of the task plans' citations): commit the outer repo's pending work (`~/src/seon-jolt/history-bundles/uncommitted/` holds it, made by `old-cleanup`), then `git -C <old next> fetch ~/src/seon-jolt refs/heads/main:refs/tags/history/prototype` and the nested repos from `history-bundles/*.bundle` as `history/<dir>/*` (`l3__next.bundle` as `history/l3/next/*`, which `seon.merge.edn` cites). Verify no tip is lost (the loop is in the plan, step D). It adds about 20 MiB.
6. **Step R:** `notes/cutover-step-R-2026-10-23.md`, one sequence: preconditions and a pin check against GitHub, the clone with submodules, the unmerged `jolt/*` branches, `config/local.edn`, `bin/build`, `bin/prove`, the grep, the full run, the root. If any check fails, delete `~/src/seon-next`: nothing else changed. Then `old-cleanup`'s successor may delete the nine fork source folders in the old prototype folder (step S: the owner's act).

## Step A, exactly (with the merge of step 4)

In `~/src/seon-jolt` (the parent), with no test run or worktree session writing:
```sh
rm worktree-ports && ln -s next/config/worktree-ports worktree-ports     # the union is already in next/config/worktree-ports
mv test-results next/test-results && ln -s next/test-results test-results
mv worktree-ports.lock next/config/ && ln -s next/config/worktree-ports.lock worktree-ports.lock
```
`next/config/worktree-ports` holds the union of both registries (written 2026-10-23, stale entries pruned, no duplicate blocks); re-union anything an unmerged branch allocated in `../worktree-ports` since. `bin/worktree --remove-at` does not free port blocks: prune a line whose directory is gone. Do not leave a `next/test-results` made by a run before the move: the `mv` would fail (fold its files into the old directory, then remove it).

## What is on the branch

- **Worktrees inside the root:** `bin/worktree --path NAME` is `<root>/.worktrees/NAME` (gitignored); `bin/worktree NAME` checks out an existing `jolt/NAME`; `--sync`, `--remove`, `bin/update` (`.worktrees/merges`) and `bin/worktree-check` use it. Port-registry keys are the directory name.
- **Shared state inside the root:** `bin/env` exports `SEON_TEST_RESULTS=<main>/test-results`; the registry is `<main>/config/worktree-ports`; `bin/ports` finds the main tree by git, not by the folder being named `next`.
- **`bin/agy-agent`** moved in (state in `runs/agy`). **`CLAUDE.md` is removed**: Claude Code reads `AGENTS.md` alone (checked in a fresh desktop session). **README** is the project README. **`docs/decisions.md`** names the project root and worktree ruling without the old folder's path.
- **Step C:** `.gitmodules` has nine `https://github.com/seantempesta/*` URLs (`jolt`, `ChezScheme`, `datahike`, `konserve`, `malli`, `crypto`, `jolt-fressian`, `persistent-sorted-set`, `jolt-overlay`; the last two private, so a clone needs `gh auth login` and `gh auth setup-git`). `docs/forks.md` says so.
- **Docs only:** task `seon.docs/docs-are-the-only-documentation` folds `notes/` into docs sections later. Step E does not copy the parent's `notes/archive` into the tree; the five task plans that cited the parent folder now cite the tags `history/prototype` and `history/l3/next/main`.
- **The plan:** `notes/self-contained-plan-2026-10-23.md` (inventory, options measured, steps A to F, R, S).

## Verified, and not

Verified (2026-10-23):
- All 12 submodule pins at this branch's tip resolve on GitHub (`gh api repos/seantempesta/<fork>/commits/<sha>`); two were missing and I pushed them: Jolt `e0351af87a` as branch `jolt/global-ids-pin`, Datahike `b4596cf37d` on `jolt/global-ids`. No force, never main.
- Every branch of the nine forks is a local head or a tag in `next/.git/modules/reference-code/*` (step B, done; the before/after ref diff was empty; the "lost tips" check printed nothing). `l2/jolt`, `jolt-l1` and `e4/datahike-b` are there as `attic/*` tags from `old-cleanup`'s bundles.
- The grep `git grep -nE 'seon-jolt|/Users/' -- . ':!notes' ':!reference-code'` prints nothing on this branch.
- Relative and absolute submodule URL resolution, a clone of a clone, and `mv` of a root with a worktree and `git worktree repair`, rehearsed in scratch repos.
- `seon.task-files-test` was green (8 of 8) after my two earlier edits to task files; after the last five edits only the quote and backslash counts were checked (unchanged).

Not verified:
- **No test was run on the merged branch.** `target/seon` in the worktree predates the merged config schema, and a run would rebuild Jolt (a long run). `seon.config-test/bin-ports-writes-its-keys-under-their-current-names` fails on a stale binary: rebuild with `nice -n 15 bin/build seon` before trusting it. Step 4's `bin/prove` and step R's R5 and R7 are the real proof.
- Step R has never run end to end (network, GitHub login, about an hour). Its pin check and grep have.
- Step D has not run. The standing root's `config/local.edn` in the old tree still holds an absolute `:seon.config.source/root`; the new root gets a relative one from R3.

## Pitfalls met, so they are not met again

- `git ls-tree HEAD reference-code` lists the directory; the submodules need `reference-code/`.
- `git add -A <paths>` aborts as a whole when one path is gone (a removed `CLAUDE.md`) and `2>/dev/null` hides it: check `git status` before `git commit`.
- A transient "commit-graph is likely corrupt" appeared during one `git merge`; `git commit-graph verify` was clean after. `git -c core.commitGraph=false merge` got past it; the file was not touched.
- zsh reads `$b:refs` as a modifier: write `${b}:refs`. macOS `sed -i` needs a suffix argument.
- A worktree's submodule checkouts do not follow a merge of main: move them with `git -C reference-code/<m> checkout --detach <pin>` (what `bin/worktree --sync` does), never `git submodule`.
- A run that uses `bin/env` before step A creates `next/test-results` in the main tree.
- Untracked research notes can appear in a worktree from an agent that ran there; ask root before removing anything.

## Who to ask

Nobody carries over. `AGENTS.md` (section 2 has root's working rules), `docs/plan.md` (the queue and the cutover) and `docs/decisions.md` (what the owner ruled) are the record.
